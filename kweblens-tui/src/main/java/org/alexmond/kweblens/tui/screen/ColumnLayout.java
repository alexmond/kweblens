package org.alexmond.kweblens.tui.screen;

/**
 * How wide each column is at a given terminal width — recomputed on every resize.
 *
 * <p>
 * A terminal's width is not a preference, it is a fact that changes while the app is
 * running, and a table whose columns were sized once at startup is wrong from the first
 * {@code SIGWINCH}. The GH#361 spike measured re-layout working at 132×44, 100×30 and
 * 170×50 — but also measured that TamboUI's {@code TuiRunner.run} <b>consumes
 * {@code ResizeEvent} itself and never hands it to the {@code EventHandler}</b> (0
 * deliveries across three real resizes). So the trigger for recomputing these widths is
 * the renderer noticing {@code Frame.area()} changed, not an event.
 *
 * <h2>Who gets the slack</h2>
 *
 * {@code AGE} and {@code STATE} are bounded by their content, so they are fixed. A
 * namespace is usually short and a name is usually long, so the namespace column is
 * capped and <b>the name takes every remaining cell</b>. When there is not enough width
 * for all four, columns are given up from the right — age first, then state — because a
 * truncated name is a row you cannot identify, which is worse than a row whose age you
 * cannot see.
 *
 * @param namespace width of the NAMESPACE column, 0 when it does not fit
 * @param name width of the NAME column
 * @param state width of the STATE column, 0 when it does not fit
 * @param age width of the AGE column, 0 when it does not fit
 */
public record ColumnLayout(int namespace, int name, int state, int age) {

	/** Cells between two columns, matching the table's column spacing. */
	public static final int GAP = 1;

	private static final int AGE_WIDTH = 5;

	private static final int STATE_WIDTH = 14;

	private static final int NAMESPACE_MAX = 24;

	private static final int NAMESPACE_MIN = 8;

	private static final int NAME_MIN = 12;

	/** The layout for a namespaced kind {@code width} cells wide. */
	public static ColumnLayout forWidth(int width) {
		return forWidth(width, true);
	}

	/**
	 * The layout for a table {@code width} cells wide.
	 * @param width the usable width, i.e. the frame's width less any border chrome
	 * @param namespaced whether the kind has namespaces at all. A cluster-scoped kind
	 * gets <b>no NAMESPACE column</b> rather than an empty one: a blank cell under a
	 * heading is a claim that the value is missing, and a Node's namespace is not
	 * missing, it does not exist.
	 */
	public static ColumnLayout forWidth(int width, boolean namespaced) {
		int usable = Math.max(0, width);
		if (usable < NAME_MIN) {
			return new ColumnLayout(0, usable, 0, 0);
		}
		int age = AGE_WIDTH;
		int state = STATE_WIDTH;
		int namespace = (namespaced) ? NAMESPACE_MIN : 0;
		if (remaining(usable, namespace, state, age) < NAME_MIN) {
			age = 0;
		}
		if (remaining(usable, namespace, state, age) < NAME_MIN) {
			state = 0;
		}
		if (remaining(usable, namespace, state, age) < NAME_MIN) {
			namespace = 0;
		}
		int slack = remaining(usable, namespace, state, age) - NAME_MIN;
		if (namespace > 0 && slack > 0) {
			int grow = Math.min(NAMESPACE_MAX - namespace, slack / 2);
			namespace += Math.max(0, grow);
		}
		return new ColumnLayout(namespace, Math.max(NAME_MIN, remaining(usable, namespace, state, age)), state, age);
	}

	private static int remaining(int usable, int namespace, int state, int age) {
		int used = namespace + state + age;
		int gaps = GAP * columns(namespace, state, age);
		return usable - used - gaps;
	}

	private static int columns(int namespace, int state, int age) {
		int count = 0;
		if (namespace > 0) {
			count++;
		}
		if (state > 0) {
			count++;
		}
		if (age > 0) {
			count++;
		}
		return count;
	}

	/** Total cells this layout occupies, gaps included. */
	public int total() {
		return this.namespace + this.name + this.state + this.age + GAP * columns(this.namespace, this.state, this.age);
	}

}
