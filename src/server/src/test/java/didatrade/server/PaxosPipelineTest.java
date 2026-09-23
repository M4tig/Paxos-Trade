package didatrade.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class PaxosPipelineTest {

	@Test
	void allocatesUniqueMonotonicSlotsConcurrently () throws Exception {
		PaxosPipeline pipeline = new PaxosPipeline (32, () -> {
		});
		ExecutorService allocators = Executors.newFixedThreadPool (8);
		CountDownLatch start = new CountDownLatch (1);
		List<Integer> instances = new ArrayList<> ();

		try {
			for (int request_id = 0; request_id < 32; request_id++) {
				int id = request_id;
				allocators.execute ( () -> {
					await (start);
					PaxosPipeline.Proposal proposal = pipeline.allocate (id, 0);
					synchronized (instances) {
						instances.add (proposal.instance);
					}
				});
			}
			start.countDown ();
			allocators.shutdown ();
			assertTrue (allocators.awaitTermination (2, TimeUnit.SECONDS));

			Set<Integer> unique = new HashSet<> (instances);
			assertEquals (32, unique.size ());
			for (int instance = 0; instance < 32; instance++)
				assertTrue (unique.contains (instance));
		} finally {
			allocators.shutdownNow ();
			pipeline.shutdown ();
		}
	}

	@Test
	void mapsDifferentRequestsToDifferentSlotsWithinBound () {
		PaxosPipeline pipeline = new PaxosPipeline (2, () -> {
		});
		try {
			PaxosPipeline.Proposal first = pipeline.allocate (100, 0);
			PaxosPipeline.Proposal second = pipeline.allocate (200, 0);

			assertNotNull (first);
			assertNotNull (second);
			assertEquals (100, pipeline.requestForInstance (first.instance));
			assertEquals (200, pipeline.requestForInstance (second.instance));
			assertNull (pipeline.allocate (300, 0));
			assertNull (pipeline.allocate (100, 0));
		} finally {
			pipeline.shutdown ();
		}
	}

	@Test
	void runsDifferentInstancesAtTheSameTime () throws Exception {
		CountDownLatch completed = new CountDownLatch (3);
		PaxosPipeline pipeline = new PaxosPipeline (3, completed::countDown);
		CountDownLatch started = new CountDownLatch (3);
		CountDownLatch release = new CountDownLatch (1);

		try {
			for (int request_id = 0; request_id < 3; request_id++)
				pipeline.allocate (request_id, 0);

			for (PaxosPipeline.Proposal proposal : pipeline.readyProposals (0, 0))
				pipeline.submit (proposal, 0, 0, () -> {
					started.countDown ();
					await (release);
				});

			assertTrue (started.await (2, TimeUnit.SECONDS));
			assertEquals (3, pipeline.runningCount ());
			release.countDown ();
			assertTrue (completed.await (2, TimeUnit.SECONDS));
		} finally {
			release.countDown ();
			pipeline.shutdown ();
		}
	}

	private static void await (CountDownLatch latch) {
		try {
			latch.await ();
		} catch (InterruptedException e) {
			Thread.currentThread ().interrupt ();
		}
	}
}
