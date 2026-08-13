package org.alexmond.kweblens.health;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

/**
 * The <b>context</b> a verdict needs that the object itself does not carry — opened once
 * per request, consulted once per row.
 *
 * <p>
 * {@link StatusVocabulary#state(String, GenericKubernetesResource)} answers for a
 * workload or a Node because those verdicts are a pure function of the object. Three
 * checks are not:
 *
 * <ul>
 * <li>a Service is broken when <b>nothing is behind it</b>, which lives in the Endpoints
 * collection ({@link NetworkHealthService});
 * <li>a PersistentVolumeClaim is "Nearly full" only according to the <b>metrics
 * backend</b> ({@link StorageHealthService});
 * <li>a ConfigMap or Secret is "Not referenced" only after a <b>scan of the namespace</b>
 * ({@link ConfigUsageService}).
 * </ul>
 *
 * <p>
 * <b>Why an object rather than a call per row.</b> The alternative — asking the cluster
 * again for each row — turns one list request into N, and the list path is the one #293
 * and #302 spent real work bounding. A context is therefore opened <i>before</i> the rows
 * are walked, holds whatever second collection the rule needs, and is then a map lookup
 * per row. What that costs, per kind, is written on each {@code contextFor} method.
 *
 * <p>
 * <b>Answering {@code null} is allowed and means "not mine".</b> A context is opened for
 * one kind; asked about another it declines, and {@link StatusVocabulary} then ships no
 * state on that row. That is the same "absence of a claim" an uncovered kind gets — never
 * a quiet "OK".
 */
@FunctionalInterface
public interface StatusContext {

	/**
	 * A context that judges nothing. What the list path uses for a kind whose verdict
	 * needs no context, and what {@link StatusContexts} falls back to when the second
	 * collection could not be fetched: the list still renders, the rows simply carry no
	 * state, and no {@code status:} term selects them.
	 */
	static StatusContext none() {
		return (kind, object) -> null;
	}

	/**
	 * This object's state, or {@code null} when this context does not judge that kind.
	 */
	ObjectState state(String kind, GenericKubernetesResource object);

}
