package org.alexmond.kweblens.access;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.RequestConfigBuilder;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.alexmond.kweblens.cluster.ClusterRegistry;

/**
 * Asks the cluster what the service account this deployment runs as is allowed to do, via
 * {@code SelfSubjectAccessReview}.
 *
 * <h2>What this is, and what it is not</h2>
 *
 * <p>
 * <b>It is a UI affordance. It is NEVER an authorization gate.</b> ADR-001 (ACCEPTED)
 * fixes kweblens on a single shared operator identity, so there is no user whose
 * permissions could be reflected here — a review can only answer "can kweblens's own
 * service account do this". The authorization that actually protects a write is
 * unchanged: {@code SecurityConfig} (open-mode: every GET public, every non-GET
 * authenticated) plus the cluster's RBAC on that account. <b>Nothing may treat an answer
 * from this service as a precondition for performing a request</b>; its results are
 * consumed only by presentation code, and {@code AccessResultIsNotAGateTest} fails the
 * build if that stops being true.
 *
 * <p>
 * <b>It fails open.</b> Every path that does not produce a verdict — the call throws, the
 * API server is unreachable, the review is itself forbidden, the response carries no
 * status, the authorizer reports an evaluation error — returns
 * {@link AccessVerdict#UNKNOWN}, which renders as <i>enabled</i>. A control greyed out
 * because a probe failed is a lie about the cluster, and would turn a failed check into a
 * denial: the exact inversion this class is written to prevent.
 *
 * <h2>Cost</h2>
 *
 * <p>
 * One review is one request, so the callers batch: a surface asks for the verbs it is
 * about to offer on one (kind, namespace) and gets a bounded, constant number of reviews
 * regardless of how many rows are on screen. Answers are cached per (cluster, group,
 * resource, namespace, verb) for {@link #TTL}, so re-entering a list inside that window
 * costs nothing.
 *
 * <h2>Invalidation</h2>
 *
 * <p>
 * The cache is cluster-scoped state, and a cluster id does not say which API server it
 * currently points at. It therefore registers a {@code ClusterClientListener} exactly as
 * {@code SchemaService} and {@code PortForwardService} do, and drops a cluster's entries
 * when its client is closed — on removal <b>and</b> on re-point. A cached "allowed" that
 * survived "edit this cluster to point somewhere else" is the failure mode this guards
 * against. {@link #TTL} is the second half: an RBAC change on a live cluster stops being
 * invisible for the life of the process.
 */
@Slf4j
@Service
public class AccessReviewService {

	/**
	 * How long an answer stays usable.
	 *
	 * <p>
	 * A minute is short enough that granting or revoking a role reaches the UI while the
	 * operator is still in the same session, and long enough that walking between lists
	 * does not re-ask on every navigation. Both stale directions are survivable: a stale
	 * "allowed" produces the click-then-403 that is the behaviour without this feature at
	 * all, and a stale "denied" greys a control that has just started working — for at
	 * most this long, and never blocking anything, since the server-side gate is
	 * elsewhere.
	 */
	static final Duration TTL = Duration.ofSeconds(60);

	/** The review resource itself: cluster-scoped, one POST per question. */
	private static final ResourceDefinitionContext REVIEWS = new ResourceDefinitionContext.Builder()
		.withGroup("authorization.k8s.io")
		.withVersion("v1")
		.withKind("SelfSubjectAccessReview")
		.withPlural("selfsubjectaccessreviews")
		.withNamespaced(false)
		.build();

	private final ClusterRegistry clusters;

	/** Answers, keyed by {@code clusterId|group|resource|namespace|verb}. */
	private final Map<String, Entry> cache = new ConcurrentHashMap<>();

	private final Duration ttl;

	// Explicit, because the TTL-taking overload below means there is more than one
	// constructor and Spring will not choose for us.
	@Autowired
	public AccessReviewService(ClusterRegistry clusters) {
		this(clusters, TTL);
	}

	/**
	 * As {@link #AccessReviewService(ClusterRegistry)}, with the entry lifetime given.
	 */
	AccessReviewService(ClusterRegistry clusters, Duration ttl) {
		this.clusters = clusters;
		this.ttl = ttl;
		// From the constructor rather than @PostConstruct so the hook is in place for
		// every construction, including tests that build this directly.
		clusters.addClientListener(this::invalidate);
	}

	/**
	 * Forget every answer held for a cluster. Called when its client is closed, because a
	 * cluster id that has been removed or re-pointed no longer describes the API server
	 * whose verdicts are stored under it.
	 * @param clusterId the cluster to forget
	 */
	public void invalidate(String clusterId) {
		this.cache.keySet().removeIf((key) -> key.startsWith(clusterId + '|'));
	}

	/**
	 * Review several verbs against one (group, resource, namespace) in one go — the call
	 * a surface makes when it is about to offer a menu.
	 * @param namespace the namespace to ask about, or {@code null}/blank for a
	 * cluster-wide review
	 * @return one answer per verb, in the order asked
	 */
	public Map<String, AccessAnswer> review(String clusterId, String group, String resource, String namespace,
			List<String> verbs) {
		Map<String, AccessAnswer> answers = new LinkedHashMap<>();
		for (String verb : verbs) {
			answers.put(verb, can(clusterId, group, resource, namespace, verb));
		}
		return answers;
	}

	/**
	 * Review one verb, from the cache when it is still fresh.
	 * @return never {@code null}; {@link AccessVerdict#UNKNOWN} whenever there is no
	 * verdict to report
	 */
	public AccessAnswer can(String clusterId, String group, String resource, String namespace, String verb) {
		String ns = (namespace != null) ? namespace : "";
		String key = clusterId + '|' + group + '|' + resource + '|' + ns + '|' + verb;
		Entry cached = this.cache.get(key);
		if (cached != null && !cached.expired(System.nanoTime())) {
			return cached.answer();
		}
		AccessAnswer answer = ask(clusterId, group, resource, ns, verb);
		this.cache.put(key, new Entry(answer, System.nanoTime() + this.ttl.toNanos()));
		return answer;
	}

	/**
	 * The request itself.
	 *
	 * <p>
	 * <b>Every failure returns {@code UNKNOWN}</b> — that is the whole design, and the
	 * catch below is load-bearing rather than defensive: a review can fail because the
	 * API server is unreachable, because the service account may not create reviews, or
	 * because the cluster id is not registered. None of those is a denial.
	 */
	private AccessAnswer ask(String clusterId, String group, String resource, String namespace, String verb) {
		try {
			GenericKubernetesResource answered = probe(this.clusters.require(clusterId))
				.genericKubernetesResources(REVIEWS)
				.resource(request(group, resource, namespace, verb))
				.create();
			return read(answered);
		}
		catch (RuntimeException ex) {
			log.debug("Access review for {} {} in '{}' on cluster '{}' did not answer: {}", verb, resource, namespace,
					clusterId, ex.getMessage());
			return AccessAnswer.unknown("the access review could not be run");
		}
	}

	/**
	 * The registry's client, seen through a request config that <b>does not retry</b>.
	 *
	 * <p>
	 * Measured: with fabric8's default backoff limit of 10, a review answered {@code 500}
	 * took <b>23 seconds</b> to give up — three verbs would have held a list surface for
	 * over a minute before rendering the (correct) fail-open answer. A probe whose only
	 * job is to decide whether to grey out a button has no business retrying; if the
	 * first attempt does not answer, the honest result is {@link AccessVerdict#UNKNOWN}
	 * now rather than the same result later.
	 *
	 * <p>
	 * This is <b>not</b> a second client and does not violate the registry's ownership of
	 * lifecycles: {@code newClient} returns a view that shares the parent's HTTP client
	 * and its {@code closed} future, so it holds nothing of its own and <b>must never be
	 * closed</b> — closing it would close the registry's client with it.
	 */
	private static KubernetesClient probe(KubernetesClient client) {
		return client
			.newClient(new RequestConfigBuilder(client.getConfiguration().getRequestConfig())
				.withRequestRetryBackoffLimit(0)
				.build())
			.adapt(KubernetesClient.class);
	}

	private static GenericKubernetesResource request(String group, String resource, String namespace, String verb) {
		Map<String, Object> attributes = new LinkedHashMap<>();
		attributes.put("group", group);
		attributes.put("resource", resource);
		attributes.put("verb", verb);
		if (!namespace.isBlank()) {
			attributes.put("namespace", namespace);
		}
		GenericKubernetesResource review = new GenericKubernetesResource();
		review.setApiVersion("authorization.k8s.io/v1");
		review.setKind("SelfSubjectAccessReview");
		review.setAdditionalProperty("spec", Map.of("resourceAttributes", attributes));
		return review;
	}

	/**
	 * Read the verdict out of the answered review.
	 *
	 * <p>
	 * Three things can make this unknown rather than denied, and the third is easy to
	 * miss: an {@code evaluationError} means the authorizer itself could not decide, and
	 * the API server reports that alongside {@code allowed: false}. Reading that pair as
	 * a refusal would grey out a control on the strength of a broken authorizer.
	 */
	private static AccessAnswer read(GenericKubernetesResource answered) {
		Object status = (answered != null) ? answered.getAdditionalProperties().get("status") : null;
		if (!(status instanceof Map<?, ?> fields)) {
			return AccessAnswer.unknown("the cluster answered the review without a status");
		}
		Object error = fields.get("evaluationError");
		if (error instanceof String message && !message.isBlank()) {
			return AccessAnswer.unknown(message);
		}
		if (!(fields.get("allowed") instanceof Boolean allowed)) {
			return AccessAnswer.unknown("the cluster answered the review without a verdict");
		}
		if (allowed) {
			return AccessAnswer.allowed();
		}
		Object reason = fields.get("reason");
		return AccessAnswer.denied((reason instanceof String text && !text.isBlank()) ? text : null);
	}

	/**
	 * One cached answer and the {@link System#nanoTime()} reading past which it is
	 * re-asked. A monotonic clock rather than the wall clock: a cache a clock adjustment
	 * can make immortal is not a cache with a TTL.
	 */
	private record Entry(AccessAnswer answer, long deadlineNanos) {

		boolean expired(long nowNanos) {
			return nowNanos - this.deadlineNanos >= 0;
		}
	}

}
