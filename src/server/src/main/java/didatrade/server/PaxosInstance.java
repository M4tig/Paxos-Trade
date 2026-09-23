package didatrade.server;

public class PaxosInstance {
	int instance_nb;
	volatile int command_id;
	int read_ballot;
	int write_ballot;
	volatile int accept_ballot;
	int n_accepts;
	volatile boolean decided;
	boolean value_is_locked;
	private boolean executed;

	public PaxosInstance() {
		this.instance_nb = 0;
		this.command_id = 0;
		this.read_ballot = -1;
		this.write_ballot = -1;
		this.accept_ballot = -1;
		this.n_accepts = 0;
		this.decided = false;
		this.value_is_locked = false;
		this.executed = false;
	}

	public PaxosInstance(int id) {
		this.instance_nb = id;
		this.command_id = 0;
		this.read_ballot = -1;
		this.write_ballot = -1;
		this.accept_ballot = -1;
		this.n_accepts = 0;
		this.decided = false;
		this.value_is_locked = false;
		this.executed = false;
	}

	public PaxosInstance(int id, int ballot) {
		this.instance_nb = id;
		this.command_id = 0;
		this.read_ballot = ballot;
		this.write_ballot = -1;
		this.accept_ballot = -1;
		this.n_accepts = 0;
		this.decided = false;
		this.value_is_locked = false;
		this.executed = false;
	}

	public synchronized boolean decide (int ballot, int value) {
		if (this.decided)
			return false;
		this.command_id = value;
		this.accept_ballot = ballot;
		this.decided = true;
		return true;
	}

	public boolean isDecided () {
		return this.decided;
	}

	public int getCommandId () {
		return this.command_id;
	}

	public int getAcceptBallot () {
		return this.accept_ballot;
	}

	public synchronized boolean isExecuted () {
		return this.executed;
	}

	public synchronized void markExecuted () {
		this.executed = true;
	}
}
