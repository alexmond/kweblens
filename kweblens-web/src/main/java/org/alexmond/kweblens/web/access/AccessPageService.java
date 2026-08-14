package org.alexmond.kweblens.web.access;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import org.alexmond.kweblens.access.AccessAnswer;
import org.alexmond.kweblens.access.AccessReviewService;
import org.alexmond.kweblens.access.AccessVerdict;
import org.alexmond.kweblens.resource.ResourceDescriptor;

/**
 * Turns "the operator is about to look at this kind in this namespace" into the bounded
 * set of reviews that answers it.
 *
 * <p>
 * <b>Nothing here decides whether a request may proceed.</b> The result is presentation
 * input — which controls to grey out and what to say next to them — and the writes it
 * describes are gated where they have always been: {@code SecurityConfig} for kweblens's
 * own login, and the cluster's RBAC for the service account. See
 * {@link AccessReviewService} for the whole rule, and {@code AccessResultIsNotAGateTest}
 * for the guard that keeps it true.
 */
@Service
@RequiredArgsConstructor
public class AccessPageService {

	/**
	 * The verbs every surface asks about, and the reason the cost is constant.
	 *
	 * <p>
	 * Three reviews per (kind, namespace), <b>never one per row</b>: a list of 200 pods
	 * asks the same three questions a list of two does. {@code patch} covers the mutating
	 * row actions and the YAML editor's Apply (server-side apply is a PATCH); {@code
	 * create} covers Create and an apply that would add an object; {@code delete} covers
	 * Delete, Force delete and the bulk bar.
	 */
	public static final List<String> VERBS = List.of("create", "patch", "delete");

	private final AccessReviewService reviews;

	/**
	 * Review {@link #VERBS} against one kind in one scope.
	 * @param namespace the namespace on screen, or {@code null}/blank for "every
	 * namespace"
	 */
	public KindAccess forKind(String clusterId, ResourceDescriptor descriptor, String namespace) {
		boolean scoped = descriptor.namespaced() && namespace != null && !namespace.isBlank();
		String ns = scoped ? namespace : null;
		Map<String, AccessAnswer> answers = this.reviews.review(clusterId, descriptor.group(), descriptor.plural(), ns,
				VERBS);
		Map<String, VerbAccess> verbs = new LinkedHashMap<>();
		answers.forEach((verb, answer) -> verbs.put(verb, VerbAccess.of(narrow(answer, descriptor, scoped))));
		return new KindAccess(descriptor.kind(), ns, verbs);
	}

	/**
	 * Weaken a cluster-wide "no" about a namespaced kind to
	 * {@link AccessVerdict#UNKNOWN}.
	 *
	 * <p>
	 * When the list spans every namespace there is no single namespace to ask about, so
	 * the review is cluster-wide — and the two directions do not mean the same thing. A
	 * cluster-wide <b>yes</b> holds in every namespace, so it stands. A cluster-wide
	 * <b>no</b> only says "not everywhere": a service account with a RoleBinding in one
	 * namespace gets exactly that answer while being perfectly able to delete the pod the
	 * operator is pointing at. Rendering it as denied would grey out a control that works
	 * — a false refusal, which is the one outcome worse than no answer at all.
	 */
	private static AccessAnswer narrow(AccessAnswer answer, ResourceDescriptor descriptor, boolean scoped) {
		if (!answer.denied() || scoped || !descriptor.namespaced()) {
			return answer;
		}
		return AccessAnswer.unknown("the review was cluster-wide, which cannot rule out a single namespace");
	}

}
