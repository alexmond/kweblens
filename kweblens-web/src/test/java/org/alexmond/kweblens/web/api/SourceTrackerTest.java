package org.alexmond.kweblens.web.api;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.log.LogSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The attach rules for a source set that changes underneath a live stream.
 *
 * <p>
 * This is the logic behind the rollout gap: without it the pods a rollout creates never
 * join the stream and the old ones just go quiet, which is exactly when someone is
 * watching. The cases below are the ones that were easy to get wrong — a recreated pod
 * that reuses its name, an attach that fails because the container has not started yet,
 * and the difference between a source the user asked for and one that appeared later.
 */
class SourceTrackerTest {

	private static final LogSource POD_A = new LogSource("app", "web-a", "server", "uid-a");

	private static final LogSource POD_B = new LogSource("app", "web-b", "server", "uid-b");

	@Test
	void attachesEverythingInTheInitialSet() {
		SourceTracker tracker = new SourceTracker();
		assertThat(tracker.needingAttach(List.of(POD_A, POD_B))).containsExactly(POD_A, POD_B);
	}

	@Test
	void doesNotAttachASourceTwice() {
		SourceTracker tracker = new SourceTracker();
		tracker.needingAttach(List.of(POD_A));
		tracker.attached(POD_A);
		assertThat(tracker.needingAttach(List.of(POD_A))).isEmpty();
	}

	@Test
	void attachesAPodThatAppearsMidStream() {
		// The whole point: a rollout's new replica must join a stream already in flight.
		SourceTracker tracker = new SourceTracker();
		tracker.needingAttach(List.of(POD_A));
		tracker.attached(POD_A);
		assertThat(tracker.needingAttach(List.of(POD_A, POD_B))).containsExactly(POD_B);
	}

	@Test
	void treatsARecreatedPodWithTheSameNameAsANewSource() {
		// A StatefulSet rollout deletes web-0 and creates web-0 again. Keying on the name
		// alone would leave it believing it is still following, and that pod's logs would
		// stop for the rest of the session.
		LogSource before = new LogSource("app", "web-0", "server", "uid-1");
		LogSource after = new LogSource("app", "web-0", "server", "uid-2");
		SourceTracker tracker = new SourceTracker();
		tracker.needingAttach(List.of(before));
		tracker.attached(before);
		assertThat(tracker.needingAttach(List.of(after))).containsExactly(after);
		// ...while still presenting as the same source to the client, so the legend and
		// colour do not jump.
		assertThat(after.id()).isEqualTo(before.id());
	}

	@Test
	void reAttachesAReaderThatEnded() {
		// An API-server hiccup that closes one watch would otherwise stop following that
		// pod silently — output just stops, with nothing to see.
		SourceTracker tracker = new SourceTracker();
		tracker.needingAttach(List.of(POD_A));
		tracker.attached(POD_A);
		tracker.detached(POD_A);
		assertThat(tracker.needingAttach(List.of(POD_A))).containsExactly(POD_A);
	}

	@Test
	void stopsRetryingEventually() {
		// A pod that never starts must not make an open view retry for as long as it is
		// left open.
		SourceTracker tracker = new SourceTracker();
		for (int i = 0; i < SourceTracker.MAX_ATTEMPTS; i++) {
			assertThat(tracker.needingAttach(List.of(POD_A))).as("attempt %d", i).containsExactly(POD_A);
			tracker.failed(POD_A);
		}
		assertThat(tracker.needingAttach(List.of(POD_A))).isEmpty();
	}

	@Test
	void reportsAFailureOnTheFirstAttemptForASourceTheUserAskedFor() {
		SourceTracker tracker = new SourceTracker();
		tracker.needingAttach(List.of(POD_A));
		assertThat(tracker.failed(POD_A)).isTrue();
	}

	@Test
	void reportsAFailureOnlyOnceWhileItPersists() {
		SourceTracker tracker = new SourceTracker();
		tracker.needingAttach(List.of(POD_A));
		assertThat(tracker.failed(POD_A)).isTrue();
		tracker.needingAttach(List.of(POD_A));
		assertThat(tracker.failed(POD_A)).as("repeat of the same failure").isFalse();
	}

	@Test
	void staysQuietWhileALateJoinerIsStillStarting() {
		// A pod is not readable the instant it exists. Flagging every new replica as
		// broken
		// for its first few seconds would put an error on the screen during every rollout
		// —
		// the false alarm that teaches people to ignore the view.
		SourceTracker tracker = new SourceTracker();
		tracker.needingAttach(List.of(POD_A));
		tracker.attached(POD_A);
		for (int i = 0; i < SourceTracker.GRACE_ATTEMPTS; i++) {
			tracker.needingAttach(List.of(POD_A, POD_B));
			assertThat(tracker.failed(POD_B)).as("attempt %d within grace", i).isFalse();
		}
		tracker.needingAttach(List.of(POD_A, POD_B));
		assertThat(tracker.failed(POD_B)).as("after the grace period").isTrue();
	}

	@Test
	void reportsRecoveryOnlyForASourceThatHadFailed() {
		SourceTracker tracker = new SourceTracker();
		tracker.needingAttach(List.of(POD_A, POD_B));
		tracker.failed(POD_A);
		assertThat(tracker.attached(POD_A)).as("previously reported broken").isTrue();
		assertThat(tracker.attached(POD_B)).as("never failed, nothing to clear").isFalse();
	}

	@Test
	void sendsTheTailSnapshotOnceOnly() {
		// The snapshot is history; re-sending it after a re-attach would duplicate output
		// the user is already looking at.
		SourceTracker tracker = new SourceTracker();
		tracker.needingAttach(List.of(POD_A));
		assertThat(tracker.needsSnapshot(POD_A)).isTrue();
		tracker.snapshotDone(POD_A);
		assertThat(tracker.needsSnapshot(POD_A)).isFalse();
	}

	@Test
	void stillOwesTheSnapshotWhenTakingItFailed() {
		// A pod that was not readable yet must still get its history once it is: for a
		// replica created by a rollout, that startup output is the part worth reading.
		SourceTracker tracker = new SourceTracker();
		tracker.needingAttach(List.of(POD_A));
		assertThat(tracker.needsSnapshot(POD_A)).isTrue();
		tracker.failed(POD_A);
		assertThat(tracker.needsSnapshot(POD_A)).as("not marked taken by a failed attempt").isTrue();
	}

}
