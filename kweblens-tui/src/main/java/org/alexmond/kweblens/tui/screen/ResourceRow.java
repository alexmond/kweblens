package org.alexmond.kweblens.tui.screen;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMeta;

/**
 * One line of the table, already reduced to text.
 *
 * <p>
 * <b>The model holds rows, not objects.</b> A row is four short strings; a
 * {@link GenericKubernetesResource} is a whole object graph, and 2 000 of them is the
 * heap this product is bounded by (#292/#293). Holding rows means the objects fetched by
 * a page, or delivered by a watch tick, can be dropped as soon as the batch that produced
 * them is projected — which is the same discipline {@code TuiListing} already applies to
 * printing.
 *
 * <p>
 * {@code state} is deliberately a {@link String} and deliberately open. Three producers
 * in {@code StatusVocabulary} pass a value straight through from the cluster, so there is
 * no exhaustive set to switch over; {@code null} means <em>nothing judged this
 * object</em>, which is a third answer and not "OK".
 *
 * @param key {@code namespace/name} — the identity a watch event is coalesced on
 * @param namespace the object's namespace, or empty for a cluster-scoped kind
 * @param name the object's name
 * @param state the verdict's label, or null when nothing judges this kind
 * @param age a short human age, e.g. {@code 3d} — empty when the object carries no
 * creation timestamp
 * @param labels {@code metadata.labels}, empty and never null. <b>The one field here that
 * is not a short string, and it earns its place:</b> drill-down is implemented as a label
 * requirement in the filter grammar ({@link DrillDown}), so a row that cannot answer
 * "what are your labels" cannot be selected by the query the title is showing. The cost
 * is the map the client already allocated — retaining it holds the map and its strings,
 * not the object graph they came out of, which is the thing #292/#293 is about.
 */
public record ResourceRow(String key, String namespace, String name, String state, String age,
		Map<String, String> labels) {

	private static final long MINUTE_SECONDS = 60L;

	private static final long HOUR_SECONDS = 3_600L;

	private static final long DAY_SECONDS = 86_400L;

	public ResourceRow {
		// Not Map.copyOf: a YAML `key:` with nothing after it is a present key with a
		// null value, which Map.copyOf refuses and the cluster does not. The same
		// reasoning FilterRow already records.
		labels = (labels != null) ? Collections.unmodifiableMap(new LinkedHashMap<>(labels)) : Map.of();
	}

	/**
	 * A row with no labels — every caller that is describing a row rather than projecting
	 * an object, which is most tests and the whole of {@code WatchSupervisor}'s recovery
	 * fixtures. It delegates, so there is still one canonical shape.
	 */
	public ResourceRow(String key, String namespace, String name, String state, String age) {
		this(key, namespace, name, state, age, Map.of());
	}

	/**
	 * The coalescing key for an object: {@code namespace/name}, and it must be derivable
	 * from a {@code DELETED} event's object as well as an {@code ADDED} one, because that
	 * is how an add and its delete cancel inside one tick.
	 */
	public static String keyOf(GenericKubernetesResource object) {
		ObjectMeta metadata = (object != null) ? object.getMetadata() : null;
		if (metadata == null) {
			return "/";
		}
		return text(metadata.getNamespace()) + "/" + text(metadata.getName());
	}

	/**
	 * Project one object, given the verdict computed for it <em>elsewhere</em>.
	 *
	 * <p>
	 * The verdict is a parameter rather than something this class works out, because
	 * {@code ObjectStates.forList} opens one {@code StatusContext} for a whole list and a
	 * per-row lookup would open one per row. The caller batches; this only formats.
	 * @param object the object
	 * @param state the verdict label, or null when nothing judges it
	 * @param now the clock reading age is measured against
	 */
	public static ResourceRow of(GenericKubernetesResource object, String state, Instant now) {
		ObjectMeta metadata = (object != null) ? object.getMetadata() : null;
		String namespace = (metadata != null) ? text(metadata.getNamespace()) : "";
		String name = (metadata != null) ? text(metadata.getName()) : "";
		String age = (metadata != null) ? age(metadata.getCreationTimestamp(), now) : "";
		Map<String, String> labels = (metadata != null) ? metadata.getLabels() : null;
		return new ResourceRow(namespace + "/" + name, namespace, name, state, age, labels);
	}

	/**
	 * A short age like {@code 12s}, {@code 4m}, {@code 6h}, {@code 30d}. An unparseable
	 * or missing timestamp yields empty rather than a guess — "we do not know" is not
	 * "brand new".
	 */
	static String age(String creationTimestamp, Instant now) {
		if (creationTimestamp == null || creationTimestamp.isBlank()) {
			return "";
		}
		Instant created;
		try {
			created = Instant.parse(creationTimestamp);
		}
		catch (DateTimeParseException ex) {
			return "";
		}
		long seconds = Duration.between(created, now).getSeconds();
		if (seconds < 0) {
			return "0s";
		}
		if (seconds < MINUTE_SECONDS) {
			return seconds + "s";
		}
		if (seconds < HOUR_SECONDS) {
			return (seconds / MINUTE_SECONDS) + "m";
		}
		if (seconds < DAY_SECONDS) {
			return (seconds / HOUR_SECONDS) + "h";
		}
		return (seconds / DAY_SECONDS) + "d";
	}

	private static String text(String value) {
		return (value != null) ? value : "";
	}

}
