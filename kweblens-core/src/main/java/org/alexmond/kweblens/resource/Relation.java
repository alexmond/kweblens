package org.alexmond.kweblens.resource;

import java.util.List;

import io.fabric8.kubernetes.client.KubernetesClientException;

/**
 * One resolved relation — the related objects, or why they could not be fetched.
 *
 * <p>
 * The three-state shape is the point. A relation that fails must never render as an empty
 * list, because "there are none" is a <em>factual claim about the cluster</em> and
 * getting it wrong is worse than showing nothing: a user told a Service has no endpoints
 * will go looking for a broken selector rather than a permissions problem. So each
 * relation carries either items, or an error, or {@code notPermitted} — and the UI is
 * expected to say which.
 *
 * <p>
 * {@code notPermitted} is separated from a generic error because it is the expected
 * outcome of a least-privilege deployment, not a malfunction: the joins read more kinds
 * than the object itself, so a service account scoped to the object's kind will
 * legitimately be refused here. See {@code docs/design/adr-001-identity-model.md}.
 *
 * @param items the related objects (empty and non-null when the fetch succeeded but
 * matched nothing)
 * @param truncated true when {@code items} was cut off by a bound, so the UI can say so
 * rather than implying completeness
 * @param error a human-readable reason the relation could not be resolved, or null
 * @param notPermitted true when the credential kweblens used was refused (403)
 */
public record Relation(List<Object> items, boolean truncated, String error, boolean notPermitted) {

	public static Relation of(List<Object> items) {
		return new Relation(items, false, null, false);
	}

	public static Relation of(List<Object> items, boolean truncated) {
		return new Relation(items, truncated, null, false);
	}

	public static Relation failed(String reason) {
		return new Relation(List.of(), false, reason, false);
	}

	public static Relation notPermitted(String detail) {
		return new Relation(List.of(), false, detail, true);
	}

	/**
	 * Classify a client failure. A 403 is reported as {@code notPermitted} so the UI can
	 * distinguish "you cannot see this" from "this is broken"; anything else is an error.
	 */
	public static Relation from(RuntimeException ex) {
		if (ex instanceof KubernetesClientException kube && kube.getCode() == 403) {
			return notPermitted("kweblens is not permitted to read this related kind");
		}
		return failed(ex.getMessage());
	}

}
