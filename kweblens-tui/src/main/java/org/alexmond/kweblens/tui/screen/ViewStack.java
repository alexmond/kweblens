package org.alexmond.kweblens.tui.screen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Where you are, and every level you can walk back through.
 *
 * <h2>{@code esc} clears a filter before it pops, and that order is the whole point</h2>
 *
 * A filter is a narrowing of the level you are on; the level is where you came from.
 * Popping first would throw away the place as well as the narrowing, so one press would
 * undo two things and the operator would have to guess which. {@link #back()} therefore
 * has exactly three outcomes, tried in order — {@link Back#FILTER_CLEARED},
 * {@link Back#POPPED}, {@link Back#AT_ROOT} — and it returns which one happened rather
 * than a boolean, because "nothing left to pop" is the answer {@code q} turns into a quit
 * and {@code esc} turns into nothing at all.
 *
 * <p>
 * The root is never popped. A stack that could empty would leave the screen with no kind
 * to draw, and "quit" is a decision for the caller holding the runner, not for a model.
 */
public class ViewStack {

	private final Deque<View> levels = new ArrayDeque<>();

	public ViewStack(View root) {
		this.levels.push(root);
	}

	/** The level being shown. */
	public View current() {
		return this.levels.peek();
	}

	/** Go one level deeper. */
	public void push(View view) {
		this.levels.push(view);
	}

	/**
	 * Replace the level being shown, keeping everything under it — what running a
	 * {@code :} command from a drilled-in view does not do, and what re-scoping the
	 * current view (a namespace favourite, an edited filter) does.
	 */
	public void replace(View view) {
		this.levels.pop();
		this.levels.push(view);
	}

	/** Set the current level's filter. Returns whether it changed. */
	public boolean filter(String query) {
		View view = current();
		String next = (query != null) ? query : "";
		if (view.filter().equals(next)) {
			return false;
		}
		replace(view.withFilter(next));
		return true;
	}

	/**
	 * One press of {@code esc} (or {@code q}): clear the filter if there is one, else pop
	 * a level, else say there was nothing to do.
	 */
	public Back back() {
		View view = current();
		if (view.filtered()) {
			replace(view.withFilter(""));
			return Back.FILTER_CLEARED;
		}
		if (this.levels.size() <= 1) {
			return Back.AT_ROOT;
		}
		this.levels.pop();
		return Back.POPPED;
	}

	/**
	 * The breadcrumbs, outermost first — the line k9s grows a segment on per level.
	 * Deriving them from the stack rather than keeping a parallel list is what stops the
	 * two disagreeing after a pop.
	 */
	public List<String> crumbs() {
		List<String> crumbs = new ArrayList<>(this.levels.size());
		for (View view : this.levels) {
			crumbs.add(0, view.crumb());
		}
		return List.copyOf(crumbs);
	}

	/** How deep the stack is; 1 at the root. */
	public int depth() {
		return this.levels.size();
	}

	/** What one press of {@code esc} did. */
	public enum Back {

		/** There was a filter and it is gone; the level is unchanged. */
		FILTER_CLEARED,

		/** A level was popped. */
		POPPED,

		/** Nothing to clear and nowhere to go — the caller decides whether that quits. */
		AT_ROOT

	}

}
