package org.alexmond.kweblens.tui.detail;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.alexmond.kweblens.tui.screen.RowWindow;

/**
 * The pane's document, its cursor, and its search — <b>no TamboUI type</b>, exactly as
 * {@code ResourceModel} keeps none for the table.
 *
 * <h2>Windowed, for the same reason the table is</h2>
 *
 * A YAML document is a long list of lines and a terminal repaints on a tick forever, so a
 * frame builds widgets for the visible slice only. #364 measured the table at 2 206 rows:
 * 0.69 ms windowed against 27.7 ms naive, and 10 000 rows at 0.68 ms against 120.8 ms —
 * the point being that windowed is <em>flat</em> in document size and naive is linear. A
 * pane holding a 5 000-line CRD would pay the linear one on every tick it is open.
 * {@link #window(int)} is therefore the only thing the renderer may build lines from.
 *
 * <h2>Search is over the whole document, not only the YAML</h2>
 *
 * The ticket asks for search over the YAML, and the YAML is nearly all of the document —
 * but a match in a relation section or an event message is exactly as useful, and a
 * search that silently skipped two sections would be a search that lies about what it
 * looked at. So {@code /} matches every line, case-insensitively, and {@code n}/{@code N}
 * walk the matches with a wrap.
 */
public class DetailModel {

	/** The object this pane is about, for the frame title. */
	private final String subject;

	private final List<DetailLine> lines;

	/** Line indices that matched the current search, ascending. Empty when none. */
	private List<Integer> matches = List.of();

	private String query = "";

	private int selected;

	private int offset;

	public DetailModel(String subject, List<DetailLine> lines) {
		this.subject = subject;
		this.lines = List.copyOf(lines);
	}

	/** What the pane is about — drawn in the frame title. */
	public String subject() {
		return this.subject;
	}

	/** Every line. */
	public List<DetailLine> lines() {
		return this.lines;
	}

	public int size() {
		return this.lines.size();
	}

	/** Where the cursor is, zero-based. */
	public int selectedIndex() {
		return this.selected;
	}

	/**
	 * Move the cursor, clamped. Returns whether it moved — i.e. whether a repaint is
	 * owed.
	 */
	public boolean moveSelection(int delta) {
		return selectTo(this.selected + delta);
	}

	/** Put the cursor on an absolute line, clamped. Returns whether it moved. */
	public boolean selectTo(int index) {
		int next = clamp(index);
		if (next == this.selected) {
			return false;
		}
		this.selected = next;
		return true;
	}

	private int clamp(int index) {
		if (this.lines.isEmpty()) {
			return 0;
		}
		return Math.max(0, Math.min(index, this.lines.size() - 1));
	}

	/**
	 * Search for {@code text}, and put the cursor on the first match at or after where it
	 * already is — not on the first match in the document. Searching from where you are
	 * looking is what every editor does, and jumping to the top would lose the reader's
	 * place to tell them about a match they had already passed.
	 * @param text what to look for; blank clears the search
	 * @return whether anything changed on screen
	 */
	public boolean search(String text) {
		String wanted = (text != null) ? text.trim() : "";
		if (wanted.isEmpty()) {
			return clearSearch();
		}
		this.query = wanted;
		String needle = wanted.toLowerCase(Locale.ROOT);
		List<Integer> found = new ArrayList<>();
		for (int i = 0; i < this.lines.size(); i++) {
			if (this.lines.get(i).text().toLowerCase(Locale.ROOT).contains(needle)) {
				found.add(i);
			}
		}
		this.matches = List.copyOf(found);
		for (int match : this.matches) {
			if (match >= this.selected) {
				this.selected = match;
				return true;
			}
		}
		if (!this.matches.isEmpty()) {
			this.selected = this.matches.get(0);
		}
		return true;
	}

	/**
	 * Forget the search. Returns whether there was one — which is what makes {@code esc}
	 * clear the search <em>before</em> it closes the pane, the same order
	 * {@code ViewStack.back} keeps for a filter and a level.
	 */
	public boolean clearSearch() {
		if (this.query.isEmpty()) {
			return false;
		}
		this.query = "";
		this.matches = List.of();
		return true;
	}

	/** The next match after the cursor, wrapping. Returns whether the cursor moved. */
	public boolean nextMatch() {
		if (this.matches.isEmpty()) {
			return false;
		}
		for (int match : this.matches) {
			if (match > this.selected) {
				return selectTo(match);
			}
		}
		return selectTo(this.matches.get(0));
	}

	/**
	 * The previous match before the cursor, wrapping. Returns whether the cursor moved.
	 */
	public boolean previousMatch() {
		if (this.matches.isEmpty()) {
			return false;
		}
		for (int i = this.matches.size() - 1; i >= 0; i--) {
			if (this.matches.get(i) < this.selected) {
				return selectTo(this.matches.get(i));
			}
		}
		return selectTo(this.matches.get(this.matches.size() - 1));
	}

	/** What is being searched for, or {@code ""}. */
	public String query() {
		return this.query;
	}

	/** How many lines matched. */
	public int matchCount() {
		return this.matches.size();
	}

	/**
	 * Which match the cursor is on, 1-based, or 0 when it is not on one. A reader
	 * stepping through matches needs "2 of 7"; a reader who scrolled away from them needs
	 * to be told that too, rather than being shown a stale number.
	 */
	public int matchPosition() {
		int position = this.matches.indexOf(this.selected);
		return position + 1;
	}

	/**
	 * What the pane says about its search: nothing when there is none, the count when
	 * there are matches, and <b>in words</b> when a search found nothing — an unchanged
	 * screen after {@code /} is indistinguishable from a search that did not run.
	 */
	public String searchStatus() {
		if (this.query.isEmpty()) {
			return "";
		}
		if (this.matches.isEmpty()) {
			return "/" + this.query + "  no match";
		}
		int position = matchPosition();
		if (position == 0) {
			// Scrolled away from the matches: saying "match 0 of 7" would be a number
			// that
			// is not about anything.
			return "/" + this.query + "  " + this.matches.size() + " matches";
		}
		return "/" + this.query + "  match " + position + " of " + this.matches.size();
	}

	/**
	 * The lines a frame of {@code height} rows may build widgets for, scrolling the
	 * minimum needed to keep the cursor on screen. Mutates the offset for the same reason
	 * {@code ResourceModel.window} does: scrolling <em>is</em> the decision about what is
	 * visible, and computing it in the renderer while storing it elsewhere is how the two
	 * disagree.
	 */
	public RowWindow window(int height) {
		if (height <= 0 || this.lines.isEmpty()) {
			this.offset = 0;
			return RowWindow.EMPTY;
		}
		if (this.selected < this.offset) {
			this.offset = this.selected;
		}
		else if (this.selected >= this.offset + height) {
			this.offset = this.selected - height + 1;
		}
		int maxOffset = Math.max(0, this.lines.size() - height);
		this.offset = Math.max(0, Math.min(this.offset, maxOffset));
		int size = Math.min(height, this.lines.size() - this.offset);
		return new RowWindow(this.offset, size, this.selected - this.offset);
	}

	/**
	 * The lines in {@code window}, as a view — a copy would be the thing windowing
	 * avoids.
	 */
	public List<DetailLine> visible(RowWindow window) {
		if (window.size() <= 0) {
			return List.of();
		}
		return this.lines.subList(window.first(), Math.min(window.end(), this.lines.size()));
	}

}
