package org.alexmond.kweblens.tui.screen;

import java.util.ArrayList;
import java.util.List;

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
 * <h2>The kind's own columns</h2>
 *
 * Everything above is the framework's four. A kind may also carry columns of its own
 * (GH#367) — a Pod's {@code READY} and {@code RESTARTS}, a Node's fifteen — and <b>those
 * are the first to go</b>, from the right, one at a time. That ordering is the same
 * argument as above carried one step further: a Node's {@code ARCHITECTURE} is detail,
 * and the row it is attached to is not identifiable without its name.
 *
 * <p>
 * <b>The widths are the terminal's, not the server's.</b> Nothing about a pixel width
 * crosses the seam, because a server cannot see this terminal; what crosses is the value.
 * Each kind column is given room for its heading and a little more, capped, which is a
 * first cut that fits fifteen Node columns on a wide terminal and none of them on a
 * narrow one. Sizing from the visible rows' actual content is the obvious improvement and
 * is deliberately not here — it belongs with the rest of #367's follow-ups.
 *
 * @param namespace width of the NAMESPACE column, 0 when it does not fit
 * @param name width of the NAME column
 * @param state width of the STATE column, 0 when it does not fit
 * @param age width of the AGE column, 0 when it does not fit
 * @param extras width of each kind-specific column that fits, in order; empty when none
 * do
 */
public record ColumnLayout(int namespace, int name, int state, int age, List<Integer> extras) {

	/** Cells between two columns, matching the table's column spacing. */
	public static final int GAP = 1;

	private static final int AGE_WIDTH = 5;

	private static final int STATE_WIDTH = 14;

	private static final int NAMESPACE_MAX = 24;

	private static final int NAMESPACE_MIN = 8;

	private static final int NAME_MIN = 12;

	/** Room for a heading and a value of about the same size. */
	private static final int EXTRA_MIN = 8;

	/** Past this a single cell is buying its width from the name column. */
	private static final int EXTRA_MAX = 20;

	public ColumnLayout {
		extras = (extras != null) ? List.copyOf(extras) : List.of();
	}

	/**
	 * The layout for a namespaced kind {@code width} cells wide, with no kind columns.
	 */
	public static ColumnLayout forWidth(int width) {
		return forWidth(width, true, List.of());
	}

	/** The layout for a kind {@code width} cells wide, with no kind columns. */
	public static ColumnLayout forWidth(int width, boolean namespaced) {
		return forWidth(width, namespaced, List.of());
	}

	/**
	 * The layout for a table {@code width} cells wide.
	 * @param width the usable width, i.e. the frame's width less any border chrome
	 * @param namespaced whether the kind has namespaces at all. A cluster-scoped kind
	 * gets <b>no NAMESPACE column</b> rather than an empty one: a blank cell under a
	 * heading is a claim that the value is missing, and a Node's namespace is not
	 * missing, it does not exist.
	 * @param headers the kind's own column headings, in order
	 * @return the layout
	 */
	public static ColumnLayout forWidth(int width, boolean namespaced, List<String> headers) {
		int usable = Math.max(0, width);
		if (usable < NAME_MIN) {
			return new ColumnLayout(0, usable, 0, 0, List.of());
		}
		List<Integer> extras = wanted(headers);
		int age = AGE_WIDTH;
		int state = STATE_WIDTH;
		int namespace = (namespaced) ? NAMESPACE_MIN : 0;
		// Kind columns first, from the right: they are the detail, and the framework's
		// four are what makes a row identifiable at all.
		while (!extras.isEmpty() && remaining(usable, namespace, state, age, extras) < NAME_MIN) {
			extras.remove(extras.size() - 1);
		}
		if (remaining(usable, namespace, state, age, extras) < NAME_MIN) {
			age = 0;
		}
		if (remaining(usable, namespace, state, age, extras) < NAME_MIN) {
			state = 0;
		}
		if (remaining(usable, namespace, state, age, extras) < NAME_MIN) {
			namespace = 0;
		}
		int slack = remaining(usable, namespace, state, age, extras) - NAME_MIN;
		if (namespace > 0 && slack > 0) {
			int grow = Math.min(NAMESPACE_MAX - namespace, slack / 2);
			namespace += Math.max(0, grow);
		}
		return new ColumnLayout(namespace, Math.max(NAME_MIN, remaining(usable, namespace, state, age, extras)), state,
				age, extras);
	}

	private static List<Integer> wanted(List<String> headers) {
		List<Integer> widths = new ArrayList<>((headers != null) ? headers.size() : 0);
		if (headers == null) {
			return widths;
		}
		for (String header : headers) {
			int length = (header != null) ? header.length() : 0;
			widths.add(Math.min(EXTRA_MAX, Math.max(EXTRA_MIN, length)));
		}
		return widths;
	}

	private static int remaining(int usable, int namespace, int state, int age, List<Integer> extras) {
		int used = namespace + state + age;
		for (int extra : extras) {
			used += extra;
		}
		return usable - used - GAP * columns(namespace, state, age, extras);
	}

	private static int columns(int namespace, int state, int age, List<Integer> extras) {
		int count = extras.size();
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
		int used = this.namespace + this.name + this.state + this.age;
		for (int extra : this.extras) {
			used += extra;
		}
		return used + GAP * columns(this.namespace, this.state, this.age, this.extras);
	}

}
