package org.alexmond.kweblens.tui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The number keys, bound to the namespaces you have actually been in.
 *
 * <p>
 * <b>Most-recently-used, not configured</b> — which is the k9s observation worth copying.
 * A configured favourites list is a file you edit once and then out-grow; an MRU list is
 * right after the second time you visit a namespace, and it needs no setup at all. The
 * operator never tells this class anything; it watches where they go.
 *
 * <p>
 * {@code 0} is always <b>every namespace</b> and is never an MRU slot, so there is always
 * a way back out to the whole cluster and it is always the same key. {@code 1}-{@code 9}
 * are the last nine namespaces visited, most recent first.
 */
public class NamespaceFavourites {

	/** How many namespaces the digits can hold: 1-9, with 0 reserved for "all". */
	static final int SLOTS = 9;

	private final List<String> recent = new ArrayList<>(SLOTS);

	/**
	 * Note that a namespace was visited: it becomes slot 1 and everything else shifts
	 * down. Blank means every namespace, which is slot 0 and not remembered.
	 */
	public void remember(String namespace) {
		if (namespace == null || namespace.isBlank()) {
			return;
		}
		this.recent.remove(namespace);
		this.recent.add(0, namespace);
		while (this.recent.size() > SLOTS) {
			this.recent.remove(this.recent.size() - 1);
		}
	}

	/**
	 * What a digit key means.
	 * @return the namespace for {@code digit}, {@link Optional#empty()} when that slot is
	 * unused. <b>Slot 0 is not empty and is not a namespace</b> — see
	 * {@link #isAll(char)}.
	 */
	public Optional<String> at(char digit) {
		int slot = digit - '1';
		if (slot < 0 || slot >= this.recent.size()) {
			return Optional.empty();
		}
		return Optional.of(this.recent.get(slot));
	}

	/** Whether this digit means "every namespace" rather than one of them. */
	public boolean isAll(char digit) {
		return digit == '0';
	}

	/** The list, most recent first — what the help pane shows next to the digits. */
	public List<String> recent() {
		return List.copyOf(this.recent);
	}

}
