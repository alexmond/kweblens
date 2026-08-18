package org.alexmond.kweblens.tui.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.alexmond.kweblens.event.EventSummary;
import org.alexmond.kweblens.resource.Relation;

/**
 * Everything the detail pane shows about one object, <b>all of it computed by the
 * server</b>: its YAML, its relations and its events.
 *
 * <h2>Why the three travel together</h2>
 *
 * They are three readings of one object at one moment. Fetching them separately would let
 * the YAML describe a spec the relations were not computed from — a Service whose
 * selector has just been edited would show the new selector above the old selector's
 * pods, and nothing on screen would say the two disagree. So the adapter reads the object
 * <em>once </em> and derives all three from it (GH#368).
 *
 * <h2>The relations are not recomputed here, and cannot be</h2>
 *
 * {@code RelationService} owns the twelve joins — "which pods back this Service", "what
 * mounts this Secret", "what created this pod" — and its javadoc says why they live in
 * core: one implementation serves the SPA, this terminal and the agent tool surface. What
 * arrives here is that map, keyed by the relation's stable name, and the terminal's whole
 * job is to render it. {@code TuiComputesNoRelationTest} is what keeps that true.
 *
 * <p>
 * <b>Each {@link Relation} carries three states and all three are statements.</b> Items
 * cut off at a bound ({@code truncated}), a join that failed ({@code error}) and a join
 * RBAC refused ({@code notPermitted}) are different claims about the cluster, and an
 * empty section is only ever the fourth one — "there are none". See
 * {@code DetailSections} for the words each is drawn with.
 *
 * @param yaml the object's YAML, or {@code ""} when there is none to show
 * @param relations the server's relation map, keyed by relation name; empty for a kind
 * with no relations, which costs the cluster nothing
 * @param events the object's own events, newest first
 * @param error why there is nothing to show, or null when there is. A pane that could not
 * be opened is a sentence, never an exception (GH#434).
 */
public record ObjectDetail(String yaml, Map<String, Relation> relations, List<EventSummary> events, String error) {

	public ObjectDetail {
		yaml = (yaml != null) ? yaml : "";
		// Not Map.copyOf: its iteration order is unspecified, and the order relations
		// arrive in is the server's own — RelationService builds a LinkedHashMap and the
		// pane draws the sections in that order rather than inventing a second one. A
		// copyOf here would have shuffled the sections on every JVM start, which reads as
		// a rendering bug and is not one.
		relations = (relations != null) ? Collections.unmodifiableMap(new LinkedHashMap<>(relations)) : Map.of();
		events = (events != null) ? List.copyOf(events) : List.of();
	}

	/** A detail that was read. */
	public static ObjectDetail of(String yaml, Map<String, Relation> relations, List<EventSummary> events) {
		return new ObjectDetail(yaml, relations, events, null);
	}

	/**
	 * There is no such object any more — the row was listed before it was deleted, and
	 * the keystroke came after. Deliberately not an empty detail: a pane of empty
	 * sections would assert that an object with nothing in it exists.
	 */
	public static ObjectDetail missing(String kind, String name) {
		return new ObjectDetail("", Map.of(), List.of(),
				"There is no " + kind + " called '" + name + "' any more — it was listed before it was deleted.");
	}

	/** The cluster would not answer. */
	public static ObjectDetail failed(String reason) {
		return new ObjectDetail("", Map.of(), List.of(), reason);
	}

	/** Whether there is anything to draw. */
	public boolean available() {
		return this.error == null;
	}

}
