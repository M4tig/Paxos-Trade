package didatrade.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Coordinates bounded slot allocation and concurrent consensus attempts. */
final class PaxosPipeline {
	private final int capacity;
	private final ExecutorService workers;
	private final Runnable completion_signal;
	private final Map<Integer, Integer> proposals;
	private final Map<Integer, Attempt> last_attempts;
	private final Set<Integer> running_instances;

	private int next_instance_to_propose;

	PaxosPipeline(int capacity, Runnable completion_signal) {
		this.capacity = capacity;
		this.completion_signal = completion_signal;
		this.workers = Executors.newFixedThreadPool (capacity);
		this.proposals = new HashMap<> ();
		this.last_attempts = new HashMap<> ();
		this.running_instances = new HashSet<> ();
		this.next_instance_to_propose = 0;
	}

	synchronized Proposal allocate (int request_id, int next_instance_to_execute) {
		if (this.proposals.containsValue (request_id))
			return null;

		this.next_instance_to_propose = Math.max (
				this.next_instance_to_propose, next_instance_to_execute);
		if (this.next_instance_to_propose - next_instance_to_execute >= this.capacity)
			return null;

		Proposal proposal = new Proposal (
				this.next_instance_to_propose, request_id);
		this.proposals.put (proposal.instance, proposal.request_id);
		this.next_instance_to_propose++;
		return proposal;
	}

	synchronized Set<Integer> assignedRequestIds () {
		return new HashSet<> (this.proposals.values ());
	}

	synchronized List<Proposal> readyProposals (
			int ballot, long wake_generation) {
		List<Proposal> ready = new ArrayList<> ();
		for (Map.Entry<Integer, Integer> entry : this.proposals.entrySet ()) {
			int instance = entry.getKey ();
			Attempt last_attempt = this.last_attempts.get (instance);
			// A failed attempt is retried only after a new ballot or external wakeup.
			// Worker completion alone must not create a tight retry loop.
			if (!this.running_instances.contains (instance)
					&& (last_attempt == null
							|| last_attempt.ballot != ballot
							|| last_attempt.wake_generation != wake_generation))
				ready.add (new Proposal (instance, entry.getValue ()));
		}
		return ready;
	}

	synchronized boolean submit (
			Proposal proposal,
			int ballot,
			long wake_generation,
			Runnable task) {
		if (this.running_instances.size () >= this.capacity
				|| this.running_instances.contains (proposal.instance))
			return false;

		this.running_instances.add (proposal.instance);
		this.last_attempts.put (
				proposal.instance, new Attempt (ballot, wake_generation));
		this.workers.execute ( () -> {
			try {
				task.run ();
			} finally {
				this.finish (proposal.instance);
			}
		});
		return true;
	}

	synchronized void replaceValue (int instance, int request_id) {
		if (this.proposals.containsKey (instance))
			this.proposals.put (instance, request_id);
	}

	synchronized void executionCompleted (int instance) {
		this.proposals.remove (instance);
		this.last_attempts.remove (instance);
	}

	synchronized int runningCount () {
		return this.running_instances.size ();
	}

	synchronized Integer requestForInstance (int instance) {
		return this.proposals.get (instance);
	}

	void shutdown () {
		this.workers.shutdownNow ();
	}

	private void finish (int instance) {
		synchronized (this) {
			this.running_instances.remove (instance);
		}
		this.completion_signal.run ();
	}

	static final class Proposal {
		final int instance;
		final int request_id;

		Proposal(int instance, int request_id) {
			this.instance = instance;
			this.request_id = request_id;
		}
	}

	private static final class Attempt {
		final int ballot;
		final long wake_generation;

		Attempt(int ballot, long wake_generation) {
			this.ballot = ballot;
			this.wake_generation = wake_generation;
		}
	}
}
