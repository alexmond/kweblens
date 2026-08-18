package org.alexmond.kweblens.tui.screen;

/**
 * What a key does. One constant per behaviour, so a binding is a row in
 * {@link KeyMap#BINDINGS} rather than a branch in a handler — which is the point: the
 * on-screen key list is derived from that table, and a behaviour reachable without a row
 * would be a key the help cannot know about.
 *
 * <p>
 * v1 is read-only, so there is no action here that changes a cluster. That is not an
 * omission to be filled in later without thought: {@code ClusterDataSource} has no method
 * a write could go through.
 */
public enum KeyAction {

	/** Move the cursor down one row. */
	MOVE_DOWN,

	/** Move the cursor up one row. */
	MOVE_UP,

	/** Move the cursor down half a screen. */
	PAGE_DOWN,

	/** Move the cursor up half a screen. */
	PAGE_UP,

	/** Put the cursor on the first row. */
	TOP,

	/** Put the cursor on the last row. */
	BOTTOM,

	/** Open the {@code :} command line. */
	COMMAND,

	/** Open the {@code /} filter prompt. */
	FILTER,

	/** Enter the selected row — pushes a view whose relationship is a visible filter. */
	DRILL_IN,

	/** Clear the filter, else pop the view stack, else (for {@code q}) quit. */
	BACK,

	/** Quit outright, from anywhere. */
	QUIT,

	/** The previous command in history — not the previous view. */
	HISTORY_PREVIOUS,

	/** The next command in history. */
	HISTORY_NEXT,

	/** The command run before this one, again — k9s's {@code -}. */
	LAST_COMMAND,

	/** Jump to a namespace on the most-recently-used list. */
	NAMESPACE_FAVOURITE,

	/** Show every binding, including the ones the hint bar has no room for. */
	HELP,

	/**
	 * Open the detail pane on the selected object — YAML, relations and events, all of it
	 * computed server-side (GH#368).
	 */
	DETAIL,

	/**
	 * Follow the selected pod's container log (GH#369). k9s's {@code l}.
	 */
	LOGS,

	/**
	 * The log of the container's <em>previous</em> run — the crashloop diagnostic. k9s's
	 * {@code p}, and a separate key rather than a mode because the two are different
	 * readings: one is a live follow, the other a snapshot of a process that has already
	 * exited.
	 */
	PREVIOUS_LOGS,

	/** The next container of the pod, in the log pane. */
	NEXT_CONTAINER,

	/** Turn the API server's per-line timestamps on or off, in the log pane. */
	TIMESTAMPS,

	/** Open the {@code /} search prompt inside the detail pane. */
	SEARCH,

	/** Move to the next line matching the pane's search. */
	NEXT_MATCH,

	/** Move to the previous line matching the pane's search. */
	PREVIOUS_MATCH

}
