package didatrade.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class OrderedPaxosExecutorTest {

	@Test
	void doesNotExecutePastAnUndecidedSlot () {
		PaxosLog log = new PaxosLog ();
		OrderedPaxosExecutor executor = new OrderedPaxosExecutor ();
		decide (log, 1, 201);
		List<Integer> commands = new ArrayList<> ();

		assertEquals (0, executor.drain (log, command_id -> {
			commands.add (command_id);
			return true;
		}));
		assertEquals (List.of (), commands);
		assertEquals (0, executor.nextInstanceToExecute ());
	}

	@Test
	void drainsAContiguousDecidedPrefixInSlotOrder () {
		PaxosLog log = new PaxosLog ();
		OrderedPaxosExecutor executor = new OrderedPaxosExecutor ();
		decide (log, 1, 201);
		decide (log, 2, 301);
		decide (log, 0, 101);
		List<Integer> commands = new ArrayList<> ();

		assertEquals (3, executor.drain (log, command_id -> {
			commands.add (command_id);
			return true;
		}));
		assertEquals (List.of (101, 201, 301), commands);
		assertEquals (3, executor.nextInstanceToExecute ());
	}

	@Test
	void executesEachCommandOnlyOnce () {
		PaxosLog log = new PaxosLog ();
		OrderedPaxosExecutor executor = new OrderedPaxosExecutor ();
		decide (log, 0, 101);
		decide (log, 1, 101);
		AtomicInteger executions = new AtomicInteger ();

		assertEquals (2, executor.drain (log, command_id -> {
			executions.incrementAndGet ();
			return true;
		}));
		assertEquals (0, executor.drain (log, command_id -> {
			executions.incrementAndGet ();
			return true;
		}));
		assertEquals (1, executions.get ());
	}

	@Test
	void waitsAtDecidedSlotUntilCommandPayloadIsAvailable () {
		PaxosLog log = new PaxosLog ();
		OrderedPaxosExecutor executor = new OrderedPaxosExecutor ();
		decide (log, 0, 101);

		assertEquals (0, executor.drain (log, command_id -> false));
		assertEquals (0, executor.nextInstanceToExecute ());
		assertEquals (1, executor.drain (log, command_id -> true));
		assertEquals (1, executor.nextInstanceToExecute ());
	}

	private static void decide (
			PaxosLog log, int instance, int command_id) {
		log.testAndSetEntry (instance).decide (0, command_id);
	}
}
