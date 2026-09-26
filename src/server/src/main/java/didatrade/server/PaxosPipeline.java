package didatrade.server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import didatrade.DidaTradePaxos;
import didatrade.util.CollectorStreamObserver;
import didatrade.util.GenericResponseCollector;
import didatrade.util.PhaseOneResponseProcessor;
import didatrade.util.PhaseTwoResponseProcessor;

/** Fixed workers run independent Paxos attempts; slot state stays in PaxosLog. */
final class PaxosPipeline {
	static final int CAPACITY = 4;
	static final long RETRY_DELAY_MILLIS = 200;
	static final long RPC_TIMEOUT_SECONDS = 5;

	private final DidaTradeServerState server_state;
	private final ExecutorService workers;
	private final Semaphore capacity;
	private final Runnable completion_signal;

	PaxosPipeline(DidaTradeServerState state, Runnable completion_signal) {
		this.server_state = state;
		this.workers = Executors.newFixedThreadPool (CAPACITY);
		this.capacity = new Semaphore (CAPACITY);
		this.completion_signal = completion_signal;
	}

	boolean hasCapacity () {
		return this.capacity.availablePermits () > 0;
	}

	boolean submit (PaxosInstance entry, int ballot) {
		return this.submitTask ( () -> this.runConsensus (entry, ballot));
	}

	// Package-private to test that all four workers can progress independently.
	boolean submitTask (Runnable task) {
		if (!this.capacity.tryAcquire ())
			return false;
		try {
			this.workers.execute ( () -> {
				try {
					task.run ();
				} finally {
					this.capacity.release ();
					this.completion_signal.run ();
				}
			});
		} catch (RuntimeException e) {
			this.capacity.release ();
			throw e;
		}
		return true;
	}

	void shutdown () {
		this.workers.shutdownNow ();
	}

	private void runConsensus (PaxosInstance entry, int ballot) {
		try {
			if (entry.isDecided () || !this.server_state.canProposeFor (ballot))
				return;
			int instance = entry.instance_nb;
			int proposed_value = entry.getProposedRequestId ();
			List<Integer> acceptors = this.server_state.scheduler.acceptors (ballot);
			int n_acceptors = acceptors.size ();
			int completed_ballot = this.server_state.getCompletedBallot ();

			PhaseOneResult phase_one = this.runPhaseOne (
					instance, ballot, completed_ballot, acceptors, n_acceptors, proposed_value);
			// A sibling worker can advance the shared ballot while Phase 1 is in flight.
			if (phase_one.aborted || entry.isDecided ()
					|| !this.server_state.canProposeFor (ballot))
				return;

			int phase_two_value = entry.phaseTwoValueFor (ballot, phase_one.value);
			this.server_state.paxos_log.replaceProposedRequestId (instance, phase_two_value);
			if (this.runPhaseTwo (instance, ballot, phase_two_value, acceptors,
					n_acceptors, this.server_state.scheduler.quorum (ballot)))
				return;

			if (entry.decide (ballot, phase_two_value)) {
				this.server_state.setCompletedBallot (ballot);
			}
		} finally {
			entry.releaseClaim (ballot, TimeUnit.MILLISECONDS.toNanos (RETRY_DELAY_MILLIS));
		}
	}

	private PhaseOneResult runPhaseOne (
			int instance, int ballot, int completed_ballot,
			List<Integer> acceptors, int n_acceptors, int proposed_value) {
		DidaTradePaxos.PhaseOneRequest request = DidaTradePaxos.PhaseOneRequest
				.newBuilder ().setInstance (instance).setRequestballot (ballot).build ();
		int low_ballot = Math.max (completed_ballot, 0);
		PhaseOneResponseProcessor processor = new PhaseOneResponseProcessor (
				this.server_state.scheduler, low_ballot, ballot);
		ArrayList<DidaTradePaxos.PhaseOneReply> responses = new ArrayList<> ();
		GenericResponseCollector<DidaTradePaxos.PhaseOneReply> collector = new GenericResponseCollector<> (
				responses, n_acceptors, processor);
		for (int acceptor : acceptors) {
			CollectorStreamObserver<DidaTradePaxos.PhaseOneReply> observer = new CollectorStreamObserver<> (
					collector);
			this.server_state.async_stubs[acceptor]
					.withDeadlineAfter (RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
					.phaseone (request, observer);
		}
		collector.waitUntilDone ();

		PhaseOneResult result = evaluatePhaseOneResult (processor, proposed_value);
		if (result.aborted && result.maxballot > this.server_state.getCurrentBallot ()) {
			this.server_state.setCurrentBallot (result.maxballot);
		}
		return result;
	}

	static PhaseOneResult evaluatePhaseOneResult (
			PhaseOneResponseProcessor processor, int proposed_value) {
		boolean aborted = !processor.getAccepted ();
		int value = proposed_value;
		if (!aborted && processor.getValballot () > -1)
			value = processor.getValue ();
		return new PhaseOneResult (aborted, value, processor.getMaxballot ());
	}

	private boolean runPhaseTwo (
			int instance, int ballot, int value,
			List<Integer> acceptors, int n_acceptors, int quorum) {
		if (!this.server_state.canProposeFor (ballot))
			return true;
		DidaTradePaxos.PhaseTwoRequest request = DidaTradePaxos.PhaseTwoRequest
				.newBuilder ().setInstance (instance).setRequestballot (ballot)
				.setValue (value).build ();
		PhaseTwoResponseProcessor processor = new PhaseTwoResponseProcessor (quorum);
		ArrayList<DidaTradePaxos.PhaseTwoReply> responses = new ArrayList<> ();
		GenericResponseCollector<DidaTradePaxos.PhaseTwoReply> collector = new GenericResponseCollector<> (
				responses, n_acceptors, processor);
		for (int acceptor : acceptors) {
			CollectorStreamObserver<DidaTradePaxos.PhaseTwoReply> observer = new CollectorStreamObserver<> (
					collector);
			this.server_state.async_stubs[acceptor]
					.withDeadlineAfter (RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
					.phasetwo (request, observer);
		}
		collector.waitUntilDone ();
		if (processor.getAccepted ())
			return false;
		if (processor.getMaxballot () > this.server_state.getCurrentBallot ()) {
			this.server_state.setCurrentBallot (processor.getMaxballot ());
		}
		return true;
	}

	static final class PhaseOneResult {
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
