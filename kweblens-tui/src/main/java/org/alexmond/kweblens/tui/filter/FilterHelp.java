package org.alexmond.kweblens.tui.filter;

import java.util.List;

/**
 * The syntax, as the operator reads it — the same 13 rows and 7 notes the browser's help
 * popover renders.
 *
 * <p>
 * <b>This is a transcription of {@code objectFilter.ts}'s {@code FILTER_HELP} and
 * {@code FILTER_HELP_NOTES}, and it must stay one.</b> Those constants are exported there
 * precisely so the help cannot drift from the parser; the TUI cannot import TypeScript,
 * so the second copy lives here and the drift risk is real. Two things hold it down: a
 * test that parses every example (so an example that stops being true fails the build
 * here, not in a screenshot), and the rule that a grammar change lands in both
 * implementations or in neither.
 *
 * <p>
 * A filter language nobody can find is a filter language nobody uses, and a placeholder
 * cannot hold this.
 */
public final class FilterHelp {

	/** The syntax rows, in the order the popover shows them. */
	public static final List<Row> ROWS = List.of(
			new Row("nginx", "name, namespace or kind contains “nginx” (what the box always did)"),
			new Row("-nginx", "everything that does NOT match — any term takes a leading “-”"),
			new Row("web prod", "both must match; terms are separated by spaces and ANDed"),
			new Row("\"two words\"", "text with a space in it"),
			new Row("/^web-\\d+$/", "regex over name, namespace and kind (case-insensitive)"),
			new Row("ns:kube-system", "one field only — also name: and kind:"),
			new Row("name:/^web/", "a field matched by regex"),
			new Row("status:Pending", "the state an overview card counts — the whole word, not part of it"),
			new Row("status:/backoff/", "states matched loosely — every kind of backoff"),
			new Row("app=web", "label equality — also app==web"),
			new Row("app!=db", "label differs, or the object has no app label (as in kubectl)"),
			new Row("env in (dev,stage)", "set membership — also notin"),
			new Row("label:app", "the label exists; -label:app for objects without it"));

	/**
	 * Where this is knowingly narrower than {@code kubectl -l}, said out loud rather than
	 * left to be discovered.
	 */
	public static final List<String> NOTES = List.of(
			"Text matching is case-insensitive; label values compare exactly, like kubectl.",
			"A /regex/ belongs to the engine that runs it, and kweblens has two of them (the browser’s and "
					+ "the terminal’s). Unicode property classes like \\p{L} work in both; a pattern an engine "
					+ "cannot read is refused in a sentence rather than quietly read as something else.",
			"Kubernetes writes label presence as “partition” and absence as “!partition”. Here they are "
					+ "label:partition and -label:partition, because a bare word stays a text search.",
			"status: is the state the server computed — the one the overview cards count, so a card’s number "
					+ "and the rows it selects are the same objects. The whole label has to match, or “Complete” "
					+ "would take “Completed” with it and those two numbers would stop agreeing.",
			"A kind the server has no verdict for carries no state, and no status: term selects it — the "
					+ "absence of a claim, not a claim of health.",
			"Numeric label requirements (key>1, key<1) are not supported, and neither are arbitrary field "
					+ "selectors (status.phase=Running) — status: is the one there is.",
			"The filter runs over every object the server sent — nothing is truncated, so a term that matches "
					+ "nothing really matches nothing.");

	private FilterHelp() {
	}

	/**
	 * One line of the help.
	 *
	 * @param example a query that must parse — asserted, not assumed
	 * @param meaning what it does, in the operator's words
	 */
	public record Row(String example, String meaning) {
	}

}
