package org.alexmond.kweblens.tui.detail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;

import org.alexmond.kweblens.event.EventSummary;
import org.alexmond.kweblens.resource.Relation;
import org.alexmond.kweblens.tui.data.ObjectDetail;

/**
 * Turns one {@link ObjectDetail} into the lines of the pane — <b>and this is the
 * rendering layer, the only place allowed to look inside a relation at all.</b>
 *
 * <h2>Three states, three different sentences</h2>
 *
 * A {@link Relation} is {@code (items, truncated, error, notPermitted)} and every one of
 * those is a claim about the cluster:
 *
 * <ul>
 * <li><b>truncated</b> — "we stopped at N; the collection is larger than what you see".
 * Drawn <em>with</em> the rows, because the rows are real; what is missing is the rest of
 * them.</li>
 * <li><b>error</b> — "this failed". Drawn <em>instead of</em> rows.</li>
 * <li><b>notPermitted</b> — "you may not see this". Also drawn instead of rows, and
 * checked <em>first</em>, because a refusal carries an {@code error} too: reading the two
 * as exclusive renders every RBAC refusal as a malfunction.</li>
 * </ul>
 *
 * <p>
 * An empty section is the fourth statement and only that one: <b>there are none</b>. That
 * is why a failed or refused relation never renders as an empty table — a reader told a
 * Service has no endpoints goes looking for a broken selector rather than for a
 * permissions problem.
 *
 * <h2>What it does not do</h2>
 *
 * It computes no relation. The items arrive from {@code RelationService}; all that
 * happens here is reading {@code metadata.name}, the kind and the namespace off objects
 * the server chose. The richer per-key projections the SPA has (an Endpoints split into
 * ready and not-ready addresses) are deliberately not ported: they mean reading into a
 * related object's {@code subsets}, and v1 of this pane renders every relation the same
 * honest Name/Kind/Namespace way the SPA falls back to for the nine keys it has no rich
 * shape for. Bland and correct beats rich and wrong.
 */
public final class DetailSections {

	/** How wide a name column may get before it stops being a column. */
	private static final int NAME_WIDTH = 44;

	private static final int KIND_WIDTH = 20;

	private static final int TYPE_WIDTH = 9;

	private static final int REASON_WIDTH = 22;

	private static final int AGE_WIDTH = 6;

	private static final String INDENT = "  ";

	private static final String ROW_INDENT = "    ";

	private DetailSections() {
	}

	/**
	 * The whole pane, <b>without its headline</b>.
	 *
	 * <p>
	 * The headline is drawn by the frame title instead ({@link #headline}), so it stays
	 * on screen while the document scrolls. A headline that scrolls away is not one — and
	 * a copy in both places is worse, because the first thing an operator sees is the
	 * same sentence twice.
	 * @param detail what the server computed
	 * @return every line, in order
	 */
	public static List<DetailLine> of(ObjectDetail detail) {
		List<DetailLine> lines = new ArrayList<>();
		if (!detail.available()) {
			lines.add(DetailLine.of(detail.error(), DetailLine.Tone.NOTICE));
			return List.copyOf(lines);
		}
		relations(lines, detail.relations());
		events(lines, detail.events());
		yaml(lines, detail.yaml());
		return List.copyOf(lines);
	}

	/**
	 * The pane's first line: <b>the verdict, first</b>, then what it is about.
	 *
	 * <p>
	 * The verdict is the one from the list — {@code ObjectStates.forList}, computed once
	 * per page with one status context — rather than a second one asked for on the way
	 * in. A per-object verdict would open a status context per row, which is the thing
	 * the list work exists not to do.
	 * @param state the row's verdict label, or null when nothing judges this kind
	 * @param kind the object's kind
	 * @param namespace its namespace, empty for a cluster-scoped object
	 * @param name its name
	 */
	public static String headline(String state, String kind, String namespace, String name) {
		String verdict = (state != null && !state.isBlank()) ? state : "— no verdict";
		String where = (namespace == null || namespace.isBlank()) ? name : namespace + "/" + name;
		return verdict + "  ·  " + kind + "  ·  " + where;
	}

	private static void relations(List<DetailLine> lines, Map<String, Relation> relations) {
		lines.add(DetailLine.of("RELATIONS", DetailLine.Tone.SECTION));
		if (relations.isEmpty()) {
			lines.add(DetailLine.text(INDENT + "kweblens computes no relations for this kind."));
			return;
		}
		for (Map.Entry<String, Relation> entry : relations.entrySet()) {
			relation(lines, title(entry.getKey()), entry.getValue());
		}
	}

	private static void relation(List<DetailLine> lines, String title, Relation relation) {
		if (relation.notPermitted()) {
			lines.add(DetailLine.of(INDENT + title, DetailLine.Tone.SUBSECTION));
			lines.add(DetailLine.of(ROW_INDENT + "not permitted — you may not see this: " + reason(relation),
					DetailLine.Tone.NOTICE));
			return;
		}
		if (relation.error() != null) {
			lines.add(DetailLine.of(INDENT + title, DetailLine.Tone.SUBSECTION));
			lines.add(DetailLine.of(ROW_INDENT + "failed — this could not be loaded: " + reason(relation),
					DetailLine.Tone.NOTICE));
			return;
		}
		List<Object> items = relation.items();
		lines.add(DetailLine.of(INDENT + title + " (" + items.size() + ")", DetailLine.Tone.SUBSECTION));
		if (relation.truncated()) {
			lines.add(DetailLine.of(ROW_INDENT + "truncated — we stopped at " + items.size()
					+ "; the collection is larger than what you see", DetailLine.Tone.NOTICE));
		}
		if (items.isEmpty()) {
			lines.add(DetailLine.text(ROW_INDENT + "none"));
			return;
		}
		lines.add(DetailLine.of(ROW_INDENT + pad("NAME", NAME_WIDTH) + pad("KIND", KIND_WIDTH) + "NAMESPACE",
				DetailLine.Tone.HEADING));
		for (Object item : items) {
			lines.add(DetailLine.text(ROW_INDENT + row(item)));
		}
	}

	/**
	 * One related object as Name/Kind/Namespace.
	 *
	 * <p>
	 * Every item {@code RelationService} produces is a Kubernetes object, so
	 * {@link HasMetadata} is the whole vocabulary needed. Anything that somehow is not
	 * gets its own {@code toString} rather than three blanks — three blanks under three
	 * headings claims the object has no name.
	 */
	private static String row(Object item) {
		if (!(item instanceof HasMetadata object)) {
			return String.valueOf(item);
		}
		ObjectMeta metadata = object.getMetadata();
		String name = (metadata != null && metadata.getName() != null) ? metadata.getName() : "—";
		String namespace = (metadata != null && metadata.getNamespace() != null) ? metadata.getNamespace() : "—";
		String kind = (object.getKind() != null) ? object.getKind() : "—";
		return pad(name, NAME_WIDTH) + pad(kind, KIND_WIDTH) + namespace;
	}

	private static void events(List<DetailLine> lines, List<EventSummary> events) {
		lines.add(DetailLine.of("EVENTS (" + events.size() + ")", DetailLine.Tone.SECTION));
		if (events.isEmpty()) {
			lines.add(DetailLine.text(INDENT + "none"));
			return;
		}
		lines.add(DetailLine.of(
				INDENT + pad("TYPE", TYPE_WIDTH) + pad("REASON", REASON_WIDTH) + pad("AGE", AGE_WIDTH) + "MESSAGE",
				DetailLine.Tone.HEADING));
		for (EventSummary event : events) {
			lines.add(DetailLine
				.text(INDENT + pad(text(event.type()), TYPE_WIDTH) + pad(text(event.reason()), REASON_WIDTH)
						+ pad(text(event.age()), AGE_WIDTH) + text(event.message())));
		}
	}

	/**
	 * The YAML, one line per line and <b>unindented</b>: indenting it would change what
	 * the document appears to say, and this is the one section a reader may want to copy.
	 */
	private static void yaml(List<DetailLine> lines, String yaml) {
		if (yaml.isBlank()) {
			lines.add(DetailLine.of("YAML", DetailLine.Tone.SECTION));
			lines.add(DetailLine.text(INDENT + "none"));
			return;
		}
		String[] rows = yaml.split("\n", -1);
		lines.add(DetailLine.of("YAML (" + rows.length + " lines)", DetailLine.Tone.SECTION));
		for (String row : rows) {
			lines.add(DetailLine.of(row, DetailLine.Tone.YAML));
		}
	}

	/**
	 * {@code ownedBy} becomes {@code Owned By} — <b>derived, never tabulated</b>. A
	 * key→title table is a second catalog of the server's relation names that goes stale
	 * the day a thirteenth relation is added, and it would be silent about it: the
	 * section would simply appear with its raw key. Re-spacing at the camel-case humps
	 * gets every one of the twelve right.
	 */
	static String title(String key) {
		StringBuilder out = new StringBuilder(key.length() + 4);
		for (int i = 0; i < key.length(); i++) {
			char character = key.charAt(i);
			if (i > 0 && Character.isUpperCase(character) && !Character.isUpperCase(key.charAt(i - 1))) {
				out.append(' ');
			}
			out.append((i == 0) ? Character.toUpperCase(character) : character);
		}
		return out.toString();
	}

	private static String reason(Relation relation) {
		return (relation.error() != null && !relation.error().isBlank()) ? relation.error() : "no reason given";
	}

	private static String text(String value) {
		return (value != null) ? value : "—";
	}

	/**
	 * Pad to a column width, and <b>never truncate</b>: a long name pushes the columns
	 * after it rather than being cut, because a name cut in half is a name you cannot
	 * look up.
	 */
	private static String pad(String value, int width) {
		if (value.length() >= width) {
			return value + " ";
		}
		return value + " ".repeat(width - value.length());
	}

}
