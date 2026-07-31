package org.alexmond.kweblens.health;

/**
 * How many objects of a kind are in one named state.
 *
 * <p>
 * The breakdown a card shows instead of a single total: "69 Running, 8 Pending, 2 Error"
 * says what a bare "83 Pods · 7 need attention" cannot. The counts come from the same
 * per-object verdicts the named-offenders list is built from, so the two cannot disagree
 * — it is one pass over the objects, not a second one.
 *
 * @param label the state's name in the kind's own vocabulary — "Running" for a pod,
 * "Unavailable" for a deployment. Not a severity: two kinds can be equally healthy and
 * say so differently.
 * @param tone how to colour it: {@code ok}, {@code warn}, {@code err} or {@code idle}.
 * Separate from the label because the label is domain language and the tone is a display
 * decision — "Suspended" is amber on a CronJob and "Completed" is muted on a pod, and
 * neither is inferable from the word alone.
 */
public record StateCount(String label, int count, String tone) {

	/** Healthy. */
	public static final String OK = "ok";

	/** Notable but deliberate or transient — suspended, pending. */
	public static final String WARN = "warn";

	/** Broken. */
	public static final String ERR = "err";

	/**
	 * Neither healthy nor broken: scaled to zero, finished, nothing to report. Muted
	 * rather than green, because "0 replicas by choice" and "3 of 3 ready" are not the
	 * same claim and colouring them alike would overstate one of them.
	 */
	public static final String IDLE = "idle";

}
