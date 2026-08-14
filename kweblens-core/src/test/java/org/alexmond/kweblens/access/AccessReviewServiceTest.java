package org.alexmond.kweblens.access;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AccessReviewService} — and, before anything else, that it <b>fails open</b>.
 *
 * <p>
 * The fail-open cases come first in this file on purpose. They are the assertions that
 * matter: a review that errors, is refused, or answers without a verdict has told us
 * nothing, and turning that into {@code DENIED} would make a failed <i>probe</i> disable
 * a control the service account can perfectly well use. Every one of them is written so
 * that inverting the fallback in {@link AccessReviewService} — returning {@code denied()}
 * from the catch, or reading a missing status as a refusal — turns them red.
 *
 * <p>
 * {@link #aRealRefusalIsStillReportedAsDenied()} is the positive control for that set: a
 * verdict this test knows the answer to, so "everything is UNKNOWN" cannot pass as
 * fail-open. Without it a service that returned {@code UNKNOWN} unconditionally would
 * satisfy every assertion above it.
 *
 * <p>
 * The mock is deliberately <b>not</b> in crud mode. A CRUD dispatcher would store the
 * posted review and echo it back with the status we sent, so every test would be
 * asserting on its own input; each response below is stubbed against the exact review
 * path instead.
 */
@EnableKubernetesMockClient
class AccessReviewServiceTest {

	private static final String REVIEW_PATH = "/apis/authorization.k8s.io/v1/selfsubjectaccessreviews";

	KubernetesClient client;

	KubernetesMockServer server;

	private final ClusterRegistry registry = new ClusterRegistry();

	private AccessReviewService service() {
		return service(AccessReviewService.TTL);
	}

	private AccessReviewService service(Duration ttl) {
		this.registry.register("mock", "mock", this.client);
		return new AccessReviewService(this.registry, ttl);
	}

	private void answerWith(String status) {
		this.server.expect()
			.post()
			.withPath(REVIEW_PATH)
			.andReturn(201,
					"{\"apiVersion\":\"authorization.k8s.io/v1\",\"kind\":\"SelfSubjectAccessReview\"" + status + "}")
			.always();
	}

	private AccessAnswer askDeletePods(AccessReviewService service) {
		return service.can("mock", "", "pods", "ns1", "delete");
	}

	// ---- Fail-open. These come first because they are the point. ----

	@Test
	void aReviewThatErrorsLeavesTheActionEnabled() {
		this.server.expect().post().withPath(REVIEW_PATH).andReturn(500, "boom").always();

		assertThat(askDeletePods(service()).verdict()).isEqualTo(AccessVerdict.UNKNOWN);
	}

	@Test
	void aReviewTheClusterItselfForbidsLeavesTheActionEnabled() {
		// The service account may not create SelfSubjectAccessReviews. That says nothing
		// about whether it may delete pods, and must not be read as though it did.
		this.server.expect().post().withPath(REVIEW_PATH).andReturn(403, "{\"kind\":\"Status\"}").always();

		assertThat(askDeletePods(service()).verdict()).isEqualTo(AccessVerdict.UNKNOWN);
	}

	@Test
	void aReviewAnsweredWithoutAStatusLeavesTheActionEnabled() {
		answerWith("");

		assertThat(askDeletePods(service()).verdict()).isEqualTo(AccessVerdict.UNKNOWN);
	}

	@Test
	void aReviewAnsweredWithoutAVerdictLeavesTheActionEnabled() {
		answerWith(",\"status\":{\"reason\":\"still thinking\"}");

		assertThat(askDeletePods(service()).verdict()).isEqualTo(AccessVerdict.UNKNOWN);
	}

	@Test
	void anAuthorizerThatCouldNotDecideIsUnknownAndNotDenied() {
		// `allowed: false` WITH an evaluationError is the API server saying the
		// authorizer
		// broke, not that the answer is no. Reading the pair as a refusal would grey out
		// a
		// control on the strength of a failure.
		answerWith(",\"status\":{\"allowed\":false,\"evaluationError\":\"webhook unreachable\"}");

		AccessAnswer answer = askDeletePods(service());
		assertThat(answer.verdict()).isEqualTo(AccessVerdict.UNKNOWN);
		assertThat(answer.reason()).isEqualTo("webhook unreachable");
	}

	@Test
	void aClusterThatIsNotRegisteredIsUnknown() {
		AccessReviewService service = service();

		assertThat(service.can("no-such-cluster", "", "pods", "ns1", "delete").verdict())
			.isEqualTo(AccessVerdict.UNKNOWN);
	}

	// ---- The positive control: the fail-open cases above are only meaningful if a real
	// verdict still gets through. ----

	@Test
	void aRealRefusalIsStillReportedAsDenied() {
		answerWith(",\"status\":{\"allowed\":false,\"reason\":\"RBAC: no rules authorize this\"}");

		AccessAnswer answer = askDeletePods(service());
		assertThat(answer.verdict()).isEqualTo(AccessVerdict.DENIED);
		assertThat(answer.denied()).isTrue();
		assertThat(answer.reason()).isEqualTo("RBAC: no rules authorize this");
	}

	@Test
	void aRealAllowIsReportedAsAllowed() {
		answerWith(",\"status\":{\"allowed\":true}");

		assertThat(askDeletePods(service()).verdict()).isEqualTo(AccessVerdict.ALLOWED);
	}

	// ---- Cost. ----

	@Test
	void aBatchOfVerbsCostsOneReviewEachAndTheAnswersAreThenFree() {
		answerWith(",\"status\":{\"allowed\":true}");
		AccessReviewService service = service();
		int before = this.server.getRequestCount();

		Map<String, AccessAnswer> first = service.review("mock", "", "pods", "ns1",
				List.of("create", "patch", "delete"));
		int afterFirst = this.server.getRequestCount();
		Map<String, AccessAnswer> second = service.review("mock", "", "pods", "ns1",
				List.of("create", "patch", "delete"));

		assertThat(first).containsOnlyKeys("create", "patch", "delete");
		assertThat(afterFirst - before).as("one review per verb, never one per row").isEqualTo(3);
		assertThat(this.server.getRequestCount() - afterFirst).as("a repeat inside the TTL asks nothing").isZero();
		assertThat(second).isEqualTo(first);
	}

	@Test
	void anExpiredAnswerIsAskedAgain() {
		answerWith(",\"status\":{\"allowed\":true}");
		AccessReviewService service = service(Duration.ZERO);
		int before = this.server.getRequestCount();

		askDeletePods(service);
		askDeletePods(service);

		assertThat(this.server.getRequestCount() - before).isEqualTo(2);
	}

	// ---- Invalidation: a cached answer must not survive the id being re-pointed. ----

	@Test
	void repointingAClusterDropsWhatTheOldOneAnswered() {
		answerWith(",\"status\":{\"allowed\":true}");
		AccessReviewService service = service();
		assertThat(askDeletePods(service).verdict()).isEqualTo(AccessVerdict.ALLOWED);
		int afterFirst = this.server.getRequestCount();

		// Same id, a different client — "edit this cluster to point somewhere else". The
		// registry closes the old client and tells its listeners, which is what has to
		// empty the cache: the id no longer describes the API server that said yes.
		this.registry.register("mock", "mock", this.server.createClient());

		assertThat(askDeletePods(service).verdict()).isEqualTo(AccessVerdict.ALLOWED);
		assertThat(this.server.getRequestCount() - afterFirst).as("the answer was re-asked, not remembered")
			.isEqualTo(1);
	}

	@Test
	void unregisteringAClusterDropsWhatItAnswered() {
		answerWith(",\"status\":{\"allowed\":true}");
		AccessReviewService service = service();
		askDeletePods(service);
		int afterFirst = this.server.getRequestCount();

		this.registry.unregister("mock");
		this.registry.register("mock", "mock", this.server.createClient());
		askDeletePods(service);

		assertThat(this.server.getRequestCount() - afterFirst).isEqualTo(1);
	}

}
