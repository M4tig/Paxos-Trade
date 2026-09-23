package didatrade.server;

import java.util.HashSet;
import java.util.Set;

/** Drains decided Paxos instances strictly in increasing slot order. */
final class OrderedPaxosExecutor {
	interface CommandExecutor {
		boolean execute (int command_id);
	}

	private int next_instance_to_execute;
	private final Set<Integer> executed_commands;

	OrderedPaxosExecutor() {
		this.next_instance_to_execute = 0;
		this.executed_commands = new HashSet<> ();
	}

	synchronized int nextInstanceToExecute () {
		return this.next_instance_to_execute;
	}

	int drain (PaxosLog log, CommandExecutor command_executor) {
		int executed = 0;
		while (true) {
			int instance = this.nextInstanceToExecute ();
			PaxosInstance entry = log.getEntry (instance);
			if (entry == null || !entry.isDecided ())
				return executed;

			if (entry.isExecuted ()) {
				this.advance (instance);
				executed++;
				continue;
			}

			int command_id = entry.getCommandId ();
			if (!this.wasExecuted (command_id)
					&& !command_executor.execute (command_id))
				return executed;

			this.rememberExecuted (command_id);
			entry.markExecuted ();
			this.advance (instance);
			executed++;
		}
	}

	private synchronized void advance (int executed_instance) {
		if (executed_instance == this.next_instance_to_execute)
			this.next_instance_to_execute++;
	}

	private synchronized boolean wasExecuted (int command_id) {
		return this.executed_commands.contains (command_id);
	}

	private synchronized void rememberExecuted (int command_id) {
		this.executed_commands.add (command_id);
	}
}
