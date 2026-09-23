package didatrade.server;

import java.util.HashSet;
import java.util.Set;

public class PaxosInstance {
	int instance_nb;
	private int command_id;
	int read_ballot;
	private int write_ballot;
	private int accepted_value;
	private int accept_ballot;
	private int learned_value;
	private final Set<Integer> accepted_by = new HashSet<> ();
	private boolean decided;
	boolean value_is_locked;
	private boolean executed;

	public PaxosInstance() {
		this.instance_nb = 0;
		this.command_id = 0;
		this.read_ballot = -1;
		this.write_ballot = -1;
		this.accepted_value = 0;
		this.accept_ballot = -1;
		this.learned_value = 0;
		this.decided = false;
		this.value_is_locked = false;
		this.executed = false;
	}

	public PaxosInstance(int id) {
		this.instance_nb = id;
		this.command_id = 0;
		this.read_ballot = -1;
		this.write_ballot = -1;
		this.accepted_value = 0;
		this.accept_ballot = -1;
		this.learned_value = 0;
		this.decided = false;
		this.value_is_locked = false;
		this.executed = false;
	}

	public PaxosInstance(int id, int ballot) {
		this.instance_nb = id;
		this.command_id = 0;
		this.read_ballot = ballot;
		this.write_ballot = -1;
		this.accepted_value = 0;
		this.accept_ballot = -1;
		this.learned_value = 0;
		this.decided = false;
		this.value_is_locked = false;
		this.executed = false;
	}

	public synchronized PhaseOneState phaseOneState (
			boolean accepted, int ballot, int maxballot) {
		if (accepted)
			this.read_ballot = ballot;
		return new PhaseOneState (
				accepted, this.accepted_value, this.write_ballot, maxballot);
	}

	public synchronized void accept (int ballot, int value) {
		this.accepted_value = value;
		this.write_ballot = ballot;
	}

	public synchronized boolean recordLearn (
			int ballot, int value, int server_id, int quorum) {
		if (this.decided || ballot < this.accept_ballot)
			return false;
		if (ballot > this.accept_ballot) {
			this.accept_ballot = ballot;
			this.learned_value = value;
			this.accepted_by.clear ();
		} else if (value != this.learned_value) {
			return false;
		}
		this.accepted_by.add (server_id);
		if (this.accepted_by.size () < quorum)
			return false;
		this.command_id = this.learned_value;
		this.decided = true;
		return true;
	}

	public synchronized int getAcceptCount () {
		return this.accepted_by.size ();
	}

	public synchronized boolean decide (int ballot, int value) {
		if (this.decided)
			return false;
		this.command_id = value;
		this.accept_ballot = ballot;
		this.decided = true;
		return true;
	}

	public synchronized boolean isDecided () {
		return this.decided;
	}

	public synchronized int getCommandId () {
		return this.command_id;
	}

	public synchronized int getAcceptBallot () {
		return this.accept_ballot;
	}

	public synchronized boolean isExecuted () {
		return this.executed;
	}

	public synchronized void markExecuted () {
		this.executed = true;
	}

	public static final class PhaseOneState {
		final boolean accepted;
		final int value;
		final int valballot;
		final int maxballot;

		PhaseOneState(boolean accepted, int value, int valballot, int maxballot) {
			this.accepted = accepted;
			this.value = value;
			this.valballot = valballot;
			this.maxballot = maxballot;
		}
	}
}
