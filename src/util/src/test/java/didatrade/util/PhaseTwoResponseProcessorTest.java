package didatrade.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import didatrade.DidaTradePaxos;

class PhaseTwoResponseProcessorTest {

	@Test
	void rpcErrorsCannotCompleteAnAcceptedQuorum () {
		PhaseTwoResponseProcessor processor = processor ();
		GenericResponseCollector<DidaTradePaxos.PhaseTwoReply> collector = collector (processor);

		collector.addResponse (acceptedReply (0));
		collector.addNoResponse ();
		collector.addNoResponse ();
		collector.waitUntilDone ();

		assertFalse (processor.getAccepted ());
	}

	@Test
	void quorumOfAcceptedRepliesCompletesPhaseTwo () {
		PhaseTwoResponseProcessor processor = processor ();
		GenericResponseCollector<DidaTradePaxos.PhaseTwoReply> collector = collector (processor);

		collector.addResponse (acceptedReply (0));
		collector.addResponse (acceptedReply (1));
		collector.waitUntilDone ();

		assertTrue (processor.getAccepted ());
	}

	@Test
	void rejectionAbortsAndPreservesHigherBallot () {
		PhaseTwoResponseProcessor processor = processor ();
		GenericResponseCollector<DidaTradePaxos.PhaseTwoReply> collector = collector (processor);

		collector.addResponse (acceptedReply (0));
		collector.addResponse (DidaTradePaxos.PhaseTwoReply.newBuilder ()
				.setServerid (1)
				.setAccepted (false)
				.setMaxballot (8)
				.build ());
		collector.waitUntilDone ();

		assertFalse (processor.getAccepted ());
		assertEquals (8, processor.getMaxballot ());
	}

	@Test
	void duplicateAcceptedReplyDoesNotCompleteQuorum () {
		PhaseTwoResponseProcessor processor = processor ();
		GenericResponseCollector<DidaTradePaxos.PhaseTwoReply> collector = collector (processor);

		collector.addResponse (acceptedReply (0));
		collector.addResponse (acceptedReply (0));
		collector.addNoResponse ();
		collector.waitUntilDone ();

		assertFalse (processor.getAccepted ());
	}

	@Test
	void duplicateCallbackCountsOnceThenDistinctResponderCompletesQuorum () {
		PhaseTwoResponseProcessor processor = processor ();
		GenericResponseCollector<DidaTradePaxos.PhaseTwoReply> collector = collector (processor);

		collector.addResponse (acceptedReply (0));
		collector.addResponse (acceptedReply (0));
		assertFalse (processor.getAccepted ());
		collector.addResponse (acceptedReply (1));
		collector.waitUntilDone ();

		assertTrue (processor.getAccepted ());
	}

	@Test
	void duplicateRejectionCannotOverrideAnAcceptedReply () {
		PhaseTwoResponseProcessor processor = processor ();
		GenericResponseCollector<DidaTradePaxos.PhaseTwoReply> collector = collector (processor);

		collector.addResponse (acceptedReply (0));
		collector.addResponse (DidaTradePaxos.PhaseTwoReply.newBuilder ()
				.setServerid (0).setAccepted (false).setMaxballot (8).build ());
		collector.addResponse (acceptedReply (1));
		collector.waitUntilDone ();

		assertTrue (processor.getAccepted ());
		assertEquals (0, processor.getMaxballot ());
	}

	@Test
	void replyFromNonAcceptorDoesNotCompleteQuorum () {
		PhaseTwoResponseProcessor processor = processor ();
		GenericResponseCollector<DidaTradePaxos.PhaseTwoReply> collector = collector (processor);

		collector.addResponse (acceptedReply (0));
		collector.addResponse (acceptedReply (99));
		collector.addNoResponse ();
		collector.waitUntilDone ();

		assertFalse (processor.getAccepted ());
	}

	private PhaseTwoResponseProcessor processor () {
		return new PhaseTwoResponseProcessor (2, List.of (0, 1, 2));
	}

	private GenericResponseCollector<DidaTradePaxos.PhaseTwoReply> collector (
			PhaseTwoResponseProcessor processor) {
		return new GenericResponseCollector<> (new ArrayList<> (), 3, processor);
	}

	private DidaTradePaxos.PhaseTwoReply acceptedReply (int server_id) {
		return DidaTradePaxos.PhaseTwoReply.newBuilder ()
				.setServerid (server_id)
				.setAccepted (true)
				.build ();
	}
}
