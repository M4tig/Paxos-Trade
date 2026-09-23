package didatrade.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import didatrade.DidaTradePaxos;
import didatrade.util.CollectorStreamObserver;
import didatrade.util.GenericResponseCollector;
import didatrade.util.PhaseOneResponseProcessor;
import didatrade.util.PhaseTwoResponseProcessor;

public class MainLoop implements Runnable {
	static final int PIPELINE_SIZE = 4;

	DidaTradeServerState server_state;

	private boolean has_work;
	private long wake_generation;
	private final PaxosPipeline pipeline;
	private final OrderedPaxosExecutor ordered_executor;

	public MainLoop(DidaTradeServerState state) {
		this.server_state = state;
		this.has_work = true;
		this.wake_generation = 0;
		this.pipeline = new PaxosPipeline (PIPELINE_SIZE, this::signal);
		this.ordered_executor = new OrderedPaxosExecutor ();
	}

	public void run () {
		while (!Thread.currentThread ().isInterrupted ()) {
			long generation;
			synchronized (this) {
				this.has_work = false;
				generation = this.wake_generation;
			}

			boolean progressed;
			do {
				progressed = this.drainDecidedEntries ();
				progressed = this.scheduleConsensus (generation) || progressed;
			} while (progressed);

			this.waitForWork ();
		}
		this.pipeline.shutdown ();
	}

	public synchronized void wakeup () {
		this.wake_generation++;
		this.signal ();
	}

	private synchronized void signal () {
		this.has_work = true;
		notifyAll ();
	}

	private synchronized void waitForWork () {
		while (!this.has_work) {
			try {
				wait ();
			} catch (InterruptedException e) {
				Thread.currentThread ().interrupt ();
				return;
			}
		}
	}

	private boolean scheduleConsensus (long generation) {
		int ballot = this.server_state.getCurrentBallot ();
		if (!this.canProposeAt (ballot))
			return false;

		boolean progressed = false;
		while (true) {
			Set<Integer> assigned_requests = this.pipeline.assignedRequestIds ();
			RequestRecord request_record = this.server_state.req_history
					.getFirstPendingExcluding (assigned_requests);
			if (request_record == null)
				break;

			PaxosPipeline.Proposal proposal = this.pipeline.allocate (
					request_record.getId (),
					this.ordered_executor.nextInstanceToExecute ());
			if (proposal == null)
				break;
			progressed = true;
		}

		List<PaxosPipeline.Proposal> ready = this.pipeline.readyProposals (
				ballot, generation);
		for (PaxosPipeline.Proposal proposal : ready) {
			PaxosInstance entry = this.server_state.paxos_log
					.testAndSetEntry (proposal.instance);
			if (entry.isDecided ())
				continue;

			boolean submitted = this.pipeline.submit (
					proposal,
					ballot,
					generation,
					() -> this.runConsensus (proposal, ballot));
			progressed = submitted || progressed;
		}
		return progressed;
	}

	private void runConsensus (PaxosPipeline.Proposal proposal, int ballot) {
		PaxosInstance entry = this.server_state.paxos_log
				.testAndSetEntry (proposal.instance);
		if (entry.isDecided () || !this.canProposeAt (ballot))
			return;

		List<Integer> acceptors = this.server_state.scheduler.acceptors (ballot);
		int n_acceptors = acceptors.size ();
		int completed_ballot = this.server_state.getCompletedBallot ();

		PhaseOneResult phase_one = this.runPhaseOne (
				proposal.instance,
				ballot,
				completed_ballot,
				acceptors,
				n_acceptors,
				proposal.request_id);
		// Another worker may have observed a higher ballot while Phase 1 was in
		// flight. Never start Phase 2 after that shared ballot has advanced.
		if (phase_one.aborted || !this.canProposeAt (ballot))
			return;

		this.pipeline.replaceValue (proposal.instance, phase_one.value);
		boolean phase_two_aborted = this.runPhaseTwo (
				proposal.instance,
				ballot,
				phase_one.value,
				acceptors,
				n_acceptors,
				this.server_state.scheduler.quorum (ballot));
		if (phase_two_aborted)
			return;

		if (entry.decide (ballot, phase_one.value)) {
			this.server_state.setCompletedBallot (ballot);
			this.signal ();
		}
	}

	boolean canProposeAt (int ballot) {
		return canProposeAt (
				ballot,
				this.server_state.getCurrentBallot (),
				this.server_state.scheduler.leader (ballot),
				this.server_state.my_id);
	}

	static boolean canProposeAt (
			int attempt_ballot,
			int current_ballot,
			int leader,
			int server_id) {
		return attempt_ballot > -1
				&& attempt_ballot == current_ballot
				&& leader == server_id;
	}

	private boolean drainDecidedEntries () {
		int first_instance = this.ordered_executor.nextInstanceToExecute ();
		int drained = this.ordered_executor.drain (
				this.server_state.paxos_log, this::executeCommand);
		for (int instance = first_instance; instance < first_instance + drained; instance++)
			this.pipeline.executionCompleted (instance);
		return drained > 0;
	}

	private boolean executeCommand (int command_id) {
		RequestRecord request_record = this.server_state.req_history
				.getIfExists (command_id);
		if (request_record == null)
			return false;

		// A command can appear in more than one independently decided slot. Its
		// processed RequestRecord makes those later occurrences execution no-ops.
		if (this.server_state.req_history.getIfProcessed (command_id) != null)
			return true;

		DidaTradeCommand command = request_record.getRequest ();
		boolean result = this.executeAction (command);
		request_record.setResponse (result);
		this.server_state.req_history.moveToProcessed (command_id);
		return true;
	}

	private PhaseOneResult runPhaseOne (
			int entry_number,
			int ballot,
			int completed_ballot,
			List<Integer> acceptors,
			int n_acceptors,
			int proposed_value) {
		DidaTradePaxos.PhaseOneRequest phase_one_request = DidaTradePaxos.PhaseOneRequest
				.newBuilder ()
				.setInstance (entry_number)
				.setRequestballot (ballot)
				.build ();

		int low_ballot = Math.max (completed_ballot, 0);
		PhaseOneResponseProcessor phase_one_processor = new PhaseOneResponseProcessor (
				this.server_state.scheduler, low_ballot, ballot);
		ArrayList<DidaTradePaxos.PhaseOneReply> responses = new ArrayList<> ();
		GenericResponseCollector<DidaTradePaxos.PhaseOneReply> collector = new GenericResponseCollector<> (
				responses, n_acceptors, phase_one_processor);

		for (int acceptor : acceptors) {
			CollectorStreamObserver<DidaTradePaxos.PhaseOneReply> observer = new CollectorStreamObserver<> (
					collector);
			this.server_state.async_stubs[acceptor].phaseone (
					phase_one_request, observer);
		}
		collector.waitUntilDone ();

		PhaseOneResult result = evaluatePhaseOneResult (
				phase_one_processor, proposed_value);
		if (result.aborted
				&& result.maxballot > this.server_state.getCurrentBallot ()) {
			this.server_state.setCurrentBallot (result.maxballot);
			this.wakeup ();
		}
		return result;
	}

	static PhaseOneResult evaluatePhaseOneResult (
			PhaseOneResponseProcessor processor, int proposed_value) {
		boolean ballot_aborted = !processor.getAccepted ();
		int phase_two_value = proposed_value;

		if (!ballot_aborted && processor.getValballot () > -1)
			phase_two_value = processor.getValue ();

		return new PhaseOneResult (
				ballot_aborted,
				phase_two_value,
				processor.getMaxballot ());
	}

	private boolean runPhaseTwo (
			int entry_number,
			int ballot,
			int phase_two_value,
			List<Integer> acceptors,
			int n_acceptors,
			int quorum) {
		DidaTradePaxos.PhaseTwoRequest request = DidaTradePaxos.PhaseTwoRequest
				.newBuilder ()
				.setInstance (entry_number)
				.setRequestballot (ballot)
				.setValue (phase_two_value)
				.build ();
		PhaseTwoResponseProcessor processor = new PhaseTwoResponseProcessor (quorum);
		ArrayList<DidaTradePaxos.PhaseTwoReply> responses = new ArrayList<> ();
		GenericResponseCollector<DidaTradePaxos.PhaseTwoReply> collector = new GenericResponseCollector<> (
				responses, n_acceptors, processor);

		for (int acceptor : acceptors) {
			CollectorStreamObserver<DidaTradePaxos.PhaseTwoReply> observer = new CollectorStreamObserver<> (
					collector);
			this.server_state.async_stubs[acceptor].phasetwo (request, observer);
		}
		collector.waitUntilDone ();

		if (processor.getAccepted ())
			return false;

		if (processor.getMaxballot () > this.server_state.getCurrentBallot ()) {
			this.server_state.setCurrentBallot (processor.getMaxballot ());
			this.wakeup ();
		}
		return true;
	}

	private boolean executeAction (DidaTradeCommand command) {
		boolean result = false;
		int balance = 0;

		switch (command.getAction ()) {
			case DidaTradeAction.POPULATE :
				result = this.server_state.trade_manager.populate (
						command.getQuantity ());
				break;
			case DidaTradeAction.ADDUSER :
				result = this.server_state.trade_manager.add_user (
						command.getUserId (),
						command.getQuantity (),
						command.getStock ());
				break;
			case DidaTradeAction.SELL :
				result = this.server_state.trade_manager.sell (
						command.getUserId (), command.getQuantity ());
				break;
			case DidaTradeAction.BUY :
				result = this.server_state.trade_manager.acquire (
						command.getUserId (), command.getQuantity ());
				break;
			case DidaTradeAction.BALANCE :
				balance = this.server_state.trade_manager.balance (
						command.getUserId ());
				command.setQuantity (balance);
				result = balance != -1;
				break;
			case DidaTradeAction.DUMP :
				this.server_state.trade_manager.dump ();
				result = true;
				break;
			default :
				System.err.println ("*** Unknown command ****");
				break;
		}
		return result;
	}

	static class PhaseOneResult {
		final boolean aborted;
		final int value;
		final int maxballot;

		PhaseOneResult(boolean aborted, int value, int maxballot) {
			this.aborted = aborted;
			this.value = value;
			this.maxballot = maxballot;
		}
	}
}
