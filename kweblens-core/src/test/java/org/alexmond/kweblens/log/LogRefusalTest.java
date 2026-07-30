package org.alexmond.kweblens.log;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Telling an API-server refusal apart from a container's own output.
 *
 * <p>
 * Both arrive by the same route — fabric8 returns the 400 body as log content, from
 * {@code getLog()} and on the {@code watchLog()} stream alike — so this check is the only
 * thing standing between a rollout and a fabricated log line attributed to the user's
 * application. It has to be exact in both directions: miss a refusal and the error is
 * displayed as output, over-match and real output is discarded as an error.
 */
class LogRefusalTest {

	private static final String REFUSAL = """
			{"kind":"Status","apiVersion":"v1","metadata":{},"status":"Failure",\
			"message":"container \\"podinfo\\" in pod \\"podinfo-574c4999dd-nkbhc\\" \
			is waiting to start: ContainerCreating","reason":"BadRequest","code":400}""";

	@Test
	void recognisesTheRefusalTheApiServerReturnsForAContainerThatHasNotStarted() {
		assertThat(LogRefusal.isRefusal(REFUSAL)).isTrue();
	}

	@Test
	void extractsTheReadableMessageThroughItsEscapedQuotes() {
		// The message quotes the container and pod names, so it contains escaped quotes —
		// which is precisely what scanning for the next '"' gets wrong.
		assertThat(LogRefusal.message(REFUSAL)).isEqualTo(
				"container \"podinfo\" in pod \"podinfo-574c4999dd-nkbhc\" is waiting to start: ContainerCreating");
	}

	@Test
	void doesNotClaimAnApplicationsOwnJsonLogging() {
		// Structured logging is common, and some of it mentions status and failure. Only
		// a
		// Status envelope at the very start is a refusal.
		assertThat(LogRefusal.isRefusal("{\"level\":\"error\",\"status\":\"Failure\",\"kind\":\"Status\"}")).isFalse();
		assertThat(LogRefusal.isRefusal("{\"msg\":\"the API returned kind=Status status=Failure\"}")).isFalse();
	}

	@Test
	void doesNotClaimASuccessfulStatusObject() {
		assertThat(LogRefusal.isRefusal("{\"kind\":\"Status\",\"status\":\"Success\"}")).isFalse();
	}

	@Test
	void handlesNothingAndNonsenseWithoutThrowing() {
		assertThat(LogRefusal.isRefusal(null)).isFalse();
		assertThat(LogRefusal.isRefusal("")).isFalse();
		assertThat(LogRefusal.message("{\"kind\":\"Status\",\"status\":\"Failure\"}"))
			.isEqualTo("the log is not available");
	}

}
