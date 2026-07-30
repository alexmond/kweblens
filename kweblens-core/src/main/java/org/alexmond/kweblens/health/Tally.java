package org.alexmond.kweblens.health;

import java.util.ArrayList;
import java.util.List;

import io.fabric8.kubernetes.api.model.HasMetadata;

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

	private int total;

	private int ok;

	private int attention;

	private int suspended;

	Tally(String id, String label, String kind) {
		this.id = id;
		this.label = label;
		this.kind = kind;
	}

	void ok() {
		this.total++;
		this.ok++;
	}

	void suspended() {
		this.total++;
		this.suspended++;
	}

	/**
	 * Record an object needing attention. Only the first {@link #MAX_NAMED} are named.
	 */
	void attention(String namespace, String name, String reason) {
		this.total++;
		this.attention++;
		if (this.named.size() < MAX_NAMED) {
			this.named.add(new UnhealthyItem(this.kind, namespace, name, reason));
		}
	}

	void attention(HasMetadata object, String reason) {
		String namespace = (object.getMetadata() != null) ? object.getMetadata().getNamespace() : null;
		String name = (object.getMetadata() != null) ? object.getMetadata().getName() : "";
		attention(namespace, name, reason);
	}

	KindHealth toKindHealth() {
		return new KindHealth(this.id, this.label, this.kind, this.total, this.ok, this.attention, this.suspended,
				List.copyOf(this.named), this.attention > this.named.size(), null);
	}

}
