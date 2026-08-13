package org.alexmond.kweblens.health;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.alexmond.kweblens.health.KindHealth.UnhealthyItem;

/**
 * Accumulates one kind's verdicts into a {@link KindHealth}.
 *
 * <p>
 * Every overview check counts the same way and caps the named offenders the same way, so
 * the counting lives here once. That matters beyond tidiness: the cap must be
 * <em>reported</em>, and a check that forgot to set {@code truncated} would silently
 * present a partial list as the complete one.
 */
final class Tally {

	/**
	 * Cap on named objects per kind. A namespace-wide outage should produce a usable
	 * list, not a thousand rows — and the cap is reported, never silently applied.
	 */
	static final int MAX_NAMED = 25;

	private final String id;

	private final String label;

	private final String kind;

	private final List<UnhealthyItem> named = new ArrayList<>();

	/**
	 * Counts per state label, insertion-ordered so a kind with no clear winner still
	 * reads in a stable order rather than shuffling between refreshes.
	 */
	private final Map<String, StateCount> states = new LinkedHashMap<>();

	private int total;

	private int ok;

	private int attention;

	private int suspended;

	Tally(String id, String label, String kind) {
		this.id = id;
		this.label = label;
		this.kind = kind;
	}

	/**
	 * Count one object's verdict — the <b>only</b> way a check adds to a tally.
	 *
	 * <p>
	 * Every overview check used to open this switch itself, which is how a state could be
	 * counted under one tone on a card and shipped with another on the row. Reached
	 * through {@link StatusVocabulary} on both sides now, so a verdict is turned into a
	 * count in exactly one place and into an {@link ObjectState} in exactly one other.
	 */
	void record(WorkloadHealth.Verdict verdict, String namespace, String name) {
		switch (verdict.state()) {
			case ATTENTION -> attention(namespace, name, verdict.reason(), verdict.label(), verdict.tone());
			case SUSPENDED -> suspended(verdict.label(), verdict.tone());
			// OK and IDLE both mean "not needing attention"; IDLE keeps its own label and
			// tone, which is what stops "everything is fine" being said about objects
			// that
			// are simply not doing anything.
			default -> ok(verdict.label(), verdict.tone());
		}
	}

	void ok(String label, String tone) {
		this.total++;
		this.ok++;
		count(label, tone);
	}

	void suspended(String label, String tone) {
		this.total++;
		this.suspended++;
		count(label, tone);
	}

	private void count(String label, String tone) {
		String key = (label != null && !label.isBlank()) ? label : "Unknown";
		StateCount existing = this.states.get(key);
		this.states.put(key, new StateCount(key, (existing != null) ? existing.count() + 1 : 1,
				(existing != null) ? existing.tone() : tone));
	}

	/**
	 * Record an object needing attention. Only the first {@link #MAX_NAMED} are named.
	 */
	private void attention(String namespace, String name, String reason, String label, String tone) {
		count(label, tone);
		this.total++;
		this.attention++;
		if (this.named.size() < MAX_NAMED) {
			this.named.add(new UnhealthyItem(this.kind, namespace, name, reason));
		}
	}

	KindHealth toKindHealth() {
		// Most-populous first: the shape of the kind should read before any single
		// number.
		List<StateCount> breakdown = this.states.values()
			.stream()
			.sorted(Comparator.comparingInt(StateCount::count).reversed())
			.toList();
		return new KindHealth(this.id, this.label, this.kind, this.total, this.ok, this.attention, this.suspended,
				breakdown, List.copyOf(this.named), this.attention > this.named.size(), null);
	}

}
