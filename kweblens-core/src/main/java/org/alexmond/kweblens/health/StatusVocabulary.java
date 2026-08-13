package org.alexmond.kweblens.health;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

/**
 * <b>The</b> definition of what state an object is in — the single seam both an overview
 * card's count and a list's status filter go through.
 *
 * <p>
 * <b>Why this exists.</b> Before it there were two definitions of a status. A card's
 * numbers came from {@link WorkloadHealth} on the server; a list row's status came from
 * {@code columns.ts} in the browser, off {@code status.phase} and a keyword table. They
 * produce similar words for different predicates, so wiring "click 3 Pending" straight
 * through would open a list showing two, with nothing on screen admitting the discrepancy
 * (GH#336). The count and the filter now read the same value, computed once, by this
 * class.
 *
 * <h2>Why the server computes it and ships it, rather than the browser recomputing
 * it</h2>
 *
 * Two reasons, and the second is the decisive one:
 *
 * <ol>
 * <li><b>A shared predicate would have to be written twice</b> — once in Java for the
 * card (the count is server-side by #155's design, because the alternative is shipping
 * every object of seven kinds to the browser to produce seven numbers) and once in
 * TypeScript for the filter. Two transcriptions of one rule drift, and the drift is
 * invisible: both sides keep answering, just differently.
 * <li><b>For several kinds the browser does not hold the inputs at all.</b> A Service is
 * broken when nothing is behind it, which lives in a <i>second</i> object — the Endpoints
 * list ({@link NetworkHealthService}). A PersistentVolumeClaim is "Nearly full" only
 * according to the metrics backend ({@link StorageHealthService}). A ConfigMap is "Not
 * referenced" only after scanning pods, service accounts and ingresses
 * ({@link ConfigUsageService}). No predicate over a list row can reproduce any of those,
 * so "express one predicate and evaluate it in both places" is not merely more work — for
 * those kinds it is impossible.
 * </ol>
 *
 * So the object's state travels <i>with</i> the object, on the list row, and the browser
 * filters on a value the server computed. See {@code web/api/ListProjection} for where it
 * is attached and {@code kweblens-ui/src/objectFilter.ts} for the {@code status:} term
 * that selects on it.
 *
 * <h2>What is covered, and what an uncovered kind means</h2>
 *
 * Two families, and the difference is where the inputs live rather than how good the
 * verdict is:
 *
 * <ul>
 * <li><b>Pure-function kinds</b> — the workloads {@link WorkloadHealth} judges and the
 * cluster-scoped Node and Namespace that {@link ClusterObjectHealth} does. The verdict is
 * a function of the object alone, so it costs nothing on the list path and
 * {@link #state(String, GenericKubernetesResource)} answers with no help.
 * <li><b>Context-carrying kinds</b> — Service, PersistentVolumeClaim, ConfigMap and
 * Secret (GH#340). Their verdicts are the three checks above, and none of them can be
 * reached from the row: they need the Endpoints collection, the metrics backend, or a
 * scan of the namespace. A {@link StatusContext} opens exactly that, <b>once per
 * request</b>, and {@link #state(String, GenericKubernetesResource, StatusContext)} then
 * costs a map lookup per row. {@link StatusContexts} is what opens one.
 * </ul>
 *
 * <p>
 * The two are asked about separately — {@link #covers} and {@link #needsContext} —
 * because the callers differ: the Workloads and Cluster overviews summarise through
 * {@link HealthService}, which can only judge the first family, while a list request has
 * to know whether to open a context before it walks the rows.
 *
 * <p>
 * <b>Event is not covered, and that is a decision</b> (GH#339). An event's
 * {@code Warning} / {@code Normal} is its {@code type} field — a property of a report
 * <i>about</i> another object, not a verdict on the event itself — so a state here would
 * be a second, differently-shaped thing wearing the same word. The Cluster overview's
 * Warnings figure is counted from that field and stays plain text for exactly that
 * reason.
 *
 * <p>
 * An uncovered kind ships no state, and {@link #state} returns {@code null} for it. That
 * is deliberately not "OK": a row with no state is not matched by any {@code status:}
 * term, so nothing can be counted as healthy on the strength of never having been
 * examined.
 */
public final class StatusVocabulary {

	/**
	 * The list row's field name. Synthetic — no Kubernetes object has it — and prefixed
	 * so it cannot collide with a CRD's own top-level field, which is the one thing a
	 * top-level addition to a generic object could break.
	 */
	public static final String FIELD = "kweblensState";

	private StatusVocabulary() {
	}

	/**
	 * Whether a state can be computed for this kind <b>from the object alone</b>. The
	 * Workloads and Cluster overviews use it to pick the kinds they can honestly
	 * summarise, so "the card has a breakdown" and "the rows carry a state" are the same
	 * set by construction.
	 *
	 * <p>
	 * Deliberately false for the context-carrying kinds: their cards are drawn by their
	 * own check rather than by {@link HealthService}, and asking that service to judge
	 * one would get a bare "OK" from {@link WorkloadHealth}'s default.
	 * {@link #needsContext} is the question to ask about those.
	 */
	public static boolean covers(String kind) {
		return WorkloadHealth.supports(kind) || ClusterObjectHealth.supports(kind);
	}

	/**
	 * Whether this kind's verdict needs a second collection — so a caller that wants a
	 * state on the row must open a {@link StatusContext} first.
	 *
	 * <p>
	 * Asked of the three checks rather than listed here, so adding a kind to one of them
	 * adds it to the filter too and there is no second list to forget to update.
	 */
	public static boolean needsContext(String kind) {
		return NetworkHealthService.supports(kind) || StorageHealthService.supports(kind)
				|| ConfigUsageService.supports(kind);
	}

	/**
	 * This object's state as far as the object alone can say — {@code null} for every
	 * kind whose verdict needs a context, which is the honest answer when none was
	 * supplied.
	 */
	public static ObjectState state(String kind, GenericKubernetesResource object) {
		return state(kind, object, StatusContext.none());
	}

	/**
	 * This object's state, or {@code null} when nothing here can judge it.
	 *
	 * <p>
	 * For a pure-function kind this delegates to exactly the call {@link HealthService}
	 * tallies with — not a copy of its rules, the same method — so a change to a verdict
	 * moves the card and the filter together or moves neither. For a context-carrying
	 * kind the context <b>is</b> the check that drew the card, holding the collection it
	 * already fetched, so the same guarantee holds by the same means.
	 *
	 * <p>
	 * A context-carrying kind asked without its context answers {@code null} rather than
	 * falling through to a bare "OK": that is the one thing this class must never do,
	 * because an object nobody examined would then be selectable as healthy.
	 */
	public static ObjectState state(String kind, GenericKubernetesResource object, StatusContext context) {
		if (object == null) {
			return null;
		}
		if (needsContext(kind)) {
			return (context != null) ? context.state(kind, object) : null;
		}
		return covers(kind) ? toState(verdict(kind, object)) : null;
	}

	/**
	 * A verdict as the row carries it. The single place a {@link WorkloadHealth.Verdict}
	 * becomes an {@link ObjectState}, so a card's label and a row's label cannot be
	 * derived differently.
	 */
	static ObjectState toState(WorkloadHealth.Verdict verdict) {
		return new ObjectState(label(verdict.label()), verdict.tone());
	}

	/**
	 * The verdict for a covered kind, whichever check owns it.
	 *
	 * <p>
	 * Every caller goes through here — {@link #state} for the row, {@link HealthService}
	 * for the card — so adding a kind is one line in {@link #covers} and one case here,
	 * and there is no way to add it to a card without adding it to the filter. Callers
	 * must have checked {@link #covers} first; an uncovered kind falls through to
	 * {@link WorkloadHealth}, whose default is a bare "OK" that nothing should be counted
	 * under by accident.
	 */
	static WorkloadHealth.Verdict verdict(String kind, GenericKubernetesResource object) {
		return ClusterObjectHealth.supports(kind) ? ClusterObjectHealth.verdict(kind, object)
				: WorkloadHealth.verdict(kind, object);
	}

	/**
	 * The label a state is counted and filtered under. Blank collapses to "Unknown"
	 * because {@link Tally} counts it under that key — a row whose state read "" while
	 * its card said "Unknown" would be a one-word discrepancy of exactly the kind this
	 * class exists to remove.
	 */
	private static String label(String label) {
		return (label != null && !label.isBlank()) ? label : "Unknown";
	}

}
