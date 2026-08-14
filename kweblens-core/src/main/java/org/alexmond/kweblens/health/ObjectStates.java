package org.alexmond.kweblens.health;

import java.util.List;
import java.util.Optional;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

/**
 * The state of <b>every object in one list</b>, with the {@link StatusContext} opened
 * once for the whole list rather than once per row.
 *
 * <p>
 * <b>What this adds.</b> {@link StatusVocabulary} already answers for one object and
 * {@link StatusContexts} already opens what the four context-carrying kinds need, and
 * both have been reachable from {@code kweblens-core} since GH#340 — the health checks,
 * the overviews and {@code web/api/ListProjection} all call them today. What was missing
 * is the <i>composition</i>: every caller wired {@code open()} to a per-row
 * {@code state()} by hand, correctly, and nothing made the next one do the same. This is
 * that wiring, written once, so a consumer outside the web module — a TUI, the CLI, a
 * report — asks one question and cannot get the expensive answer.
 *
 * <h2>Once per list, not once per row — the whole reason for the shape</h2>
 *
 * A Service's verdict needs the Endpoints collection, a claim's needs the metrics
 * backend, a ConfigMap's or Secret's needs a scan of pods, service accounts and ingresses
 * (+80-120 ms on a real namespace). Opening that per row multiplies it by the row count,
 * and the result would be <i>identical</i> — same labels, same tones — so no output
 * comparison can catch it. That is why this method takes the objects rather than one
 * object: a caller holding this API cannot spell the per-row version, and
 * {@code ObjectStatesTest} asserts the count of opens directly.
 *
 * <p>
 * For a single object whose verdict is a pure function of itself, call
 * {@link StatusVocabulary#state(String, GenericKubernetesResource)} — that is what it is
 * for. For a <b>stream</b> (a watch), open a {@link StatusContexts#refreshing} context
 * once and hand that one context to the three-argument {@code StatusVocabulary.state} per
 * event, which is what the SPA's watch endpoint does: a stream has no list to hand over
 * and its context has to be allowed to age out, so it is deliberately not this call.
 *
 * <h2>Absent is a third answer, not a null one</h2>
 *
 * An {@link Optional#empty()} means <b>nothing here judges this object</b> — an Event, a
 * ServiceAccount, a CRD, or a context-carrying kind whose second collection could not be
 * fetched. It is not "OK": an object nobody examined must never be selectable, countable
 * or colourable as healthy. That is the same distinction {@code ListProjection} makes by
 * shipping no {@code kweblensState} field at all rather than a null-valued one.
 */
@Service
@RequiredArgsConstructor
public class ObjectStates {

	private final StatusContexts contexts;

	/**
	 * Each object's state, positionally: the returned list has the <b>same size and the
	 * same order</b> as {@code objects}, and element <i>i</i> is the state of object
	 * <i>i</i>, empty when nothing judges it.
	 *
	 * <p>
	 * The objects are not touched. This yields the verdicts beside the list rather than
	 * attaching them to it, because what a consumer does with a state is its own business
	 * — the SPA needs it as a field on the wire ({@code ListProjection}), a terminal
	 * needs it as a coloured cell, and a report needs it as a tally.
	 *
	 * <p>
	 * {@code namespace} is the scope the objects were listed with, and it scopes the
	 * context too — pass the same value that produced the list, or the second collection
	 * describes a different set of objects than the rows do. Null or blank means
	 * cluster-wide, exactly as it does for {@code ResourceService.listRaw}.
	 *
	 * <p>
	 * An empty list opens nothing: there is no row for a context to be consulted about,
	 * so paying for one would be the cost this class exists to bound, spent on nothing.
	 */
	public List<Optional<ObjectState>> forList(String clusterId, String kind, String namespace,
			List<GenericKubernetesResource> objects) {
		if (objects == null || objects.isEmpty()) {
			return List.of();
		}
		// Once, here, before a single row is looked at. Everything below is a map lookup.
		StatusContext context = this.contexts.open(clusterId, kind, namespace);
		return objects.stream()
			.map((object) -> Optional.ofNullable(StatusVocabulary.state(kind, object, context)))
			.toList();
	}

}
