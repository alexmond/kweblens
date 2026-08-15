package org.alexmond.kweblens.tui.screen;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/**
 * What the screen is showing: the rows, in a stable order, and where the cursor is.
 *
 * <p>
 * <b>No TamboUI type appears here</b>, on purpose — the same instinct as this project's
 * "logic in {@code .ts}, rendering in {@code .vue}". Selection, scrolling and the visible
 * window are decisions with right and wrong answers, and they are testable without a
 * terminal; the widget layer is a 0.4.0 library and everything that touches it is a bet.
 *
 * <h2>Order is the model's, not the cluster's</h2>
 *
 * Rows are kept in a {@link TreeMap} on {@code namespace/name}, so a watch event inserts
 * where the row belongs instead of appending to the end. A live list whose rows jump
 * around when something changes is a list you cannot read, and the API server makes no
 * ordering promise across a watch.
 *
 * <h2>Not thread-safe, and that is the design</h2>
 *
 * Every mutation runs on the render thread, out of the tick. Watch events do not touch
 * this class at all — they go into {@link WatchCoalescer}'s buffer and arrive here as one
 * batch per flush.
 */
public class ResourceModel {

	private final TreeMap<String, ResourceRow> rows = new TreeMap<>();

	/**
	 * The ordered view, rebuilt only when the map changes. {@code TreeMap.values()} is a
	 * view but not random-access, and the renderer indexes into it once per visible row
	 * per frame.
	 */
	private List<ResourceRow> ordered = List.of();

	private int selected;

	private int offset;

	/** Replace every row — what an initial list or a re-list does. */
	public void replaceAll(Collection<ResourceRow> replacement) {
		this.rows.clear();
		add(replacement);
	}

	/** Add or update rows, keeping the order. Returns true if anything changed. */
	public boolean upsert(Collection<ResourceRow> batch) {
		if (batch.isEmpty()) {
			return false;
		}
		add(batch);
		return true;
	}

	/** Remove rows by key. Returns true if anything was actually removed. */
	public boolean remove(Collection<String> keys) {
		boolean removed = false;
		for (String key : keys) {
			removed |= this.rows.remove(key) != null;
		}
		if (removed) {
			reindex();
		}
		return removed;
	}

	private void add(Collection<ResourceRow> batch) {
		for (ResourceRow row : batch) {
			this.rows.put(row.key(), row);
		}
		reindex();
	}

	private void reindex() {
		this.ordered = List.copyOf(this.rows.values());
		this.selected = clamp(this.selected);
	}

	/** Every row, in display order. */
	public List<ResourceRow> rows() {
		return this.ordered;
	}

	public int size() {
		return this.ordered.size();
	}

	public int selectedIndex() {
		return this.selected;
	}

	/** The selected row, or empty when there are none. */
	public Optional<ResourceRow> selectedRow() {
		return (this.ordered.isEmpty()) ? Optional.empty() : Optional.of(this.ordered.get(this.selected));
	}

	/**
	 * Move the cursor by {@code delta} rows, clamped to the list. Returns true if it
	 * actually moved — the caller uses that to decide whether a repaint is owed, so a key
	 * pressed at the end of the list costs nothing.
	 */
	public boolean moveSelection(int delta) {
		return selectTo(this.selected + delta);
	}

	/** Put the cursor on an absolute index, clamped. Returns true if it moved. */
	public boolean selectTo(int index) {
		int next = clamp(index);
		if (next == this.selected) {
			return false;
		}
		this.selected = next;
		return true;
	}

	private int clamp(int index) {
		if (this.ordered.isEmpty()) {
			return 0;
		}
		return Math.max(0, Math.min(index, this.ordered.size() - 1));
	}

	/**
	 * The rows a frame of {@code height} lines may build widgets for, scrolling the
	 * viewport the minimum needed to keep the selection on screen.
	 *
	 * <p>
	 * This mutates {@code offset}: scrolling <em>is</em> a decision about what is
	 * visible, and computing it in the renderer while storing it somewhere else is how
	 * the two disagree. The window it returns is the only thing the renderer is allowed
	 * to build rows from — see {@link RowWindow} for what that is worth in milliseconds.
	 */
	public RowWindow window(int height) {
		if (height <= 0 || this.ordered.isEmpty()) {
			this.offset = 0;
			return RowWindow.EMPTY;
		}
		if (this.selected < this.offset) {
			this.offset = this.selected;
		}
		else if (this.selected >= this.offset + height) {
			this.offset = this.selected - height + 1;
		}
		int maxOffset = Math.max(0, this.ordered.size() - height);
		this.offset = Math.max(0, Math.min(this.offset, maxOffset));
		int size = Math.min(height, this.ordered.size() - this.offset);
		return new RowWindow(this.offset, size, this.selected - this.offset);
	}

	/**
	 * The rows in {@code window}, as a view. A copy here would be a copy of the thing the
	 * windowing exists to avoid making.
	 */
	public List<ResourceRow> visible(RowWindow window) {
		if (window.size() <= 0) {
			return List.of();
		}
		return this.ordered.subList(window.first(), Math.min(window.end(), this.ordered.size()));
	}

	/**
	 * How many rows carry each verdict, for a status line that counts what is on screen.
	 */
	public List<String> distinctStates() {
		List<String> states = new ArrayList<>();
		for (ResourceRow row : this.ordered) {
			if (row.state() != null && !states.contains(row.state())) {
				states.add(row.state());
			}
		}
		return states;
	}

}
