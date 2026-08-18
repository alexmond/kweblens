package org.alexmond.kweblens.tui.data;

/**
 * The log of a container's <em>previous</em> run, or the reason there is not one.
 *
 * <p>
 * <b>"There is no previous run" is an answer, not an empty result</b> (GH#369). A
 * container that has never restarted and a container whose terminated instance logged
 * nothing produce the same empty string from the API server, and a pane that drew both as
 * a blank document would be making the same claim about two different clusters. The
 * crashloop diagnostic is exactly the case where the reader needs to be told which one
 * they are looking at: an empty pane reads as "it crashed silently", which is a finding,
 * and it must not be manufactured by a missing branch here.
 *
 * <p>
 * Same shape as {@link ObjectDetail} for the same reason — a refusal is a sentence the
 * pane draws, never an exception thrown out of a key press (GH#434).
 *
 * @param text the terminated instance's log, or {@code ""} when there is none to show
 * @param reason why there is nothing to show, or null when there is
 */
public record PreviousLog(String text, String reason) {

	public PreviousLog {
		text = (text != null) ? text : "";
	}

	/** A previous run that was read. */
	public static PreviousLog of(String text) {
		return new PreviousLog(text, null);
	}

	/**
	 * There is no terminated instance. {@code LogService.previous} answers null for this
	 * — the API server 400s rather than returning an empty body — and the null is turned
	 * into words here rather than at the pane, so every caller of the port gets the same
	 * sentence.
	 * @param container the container asked about, blank for the pod's default one
	 */
	public static PreviousLog none(String container) {
		return new PreviousLog("", "No previous run of " + which(container)
				+ " — it has not restarted, so there is no terminated instance to read.");
	}

	/**
	 * There <em>was</em> a previous run and it wrote nothing — a third state, and it has
	 * to be one.
	 *
	 * <p>
	 * The API server answers a container that has never restarted with a 400, and a
	 * terminated instance that logged nothing with an empty 200. Those are different
	 * facts about the cluster and they are the two a crashloop reader is choosing
	 * between: "it has not crashed" and "it crashed without saying why". Collapsing them
	 * into one blank pane manufactures the second, which is a finding.
	 * @param container the container asked about, blank for the pod's default one
	 */
	public static PreviousLog silent(String container) {
		return new PreviousLog("", "The previous run of " + which(container) + " wrote nothing to its log.");
	}

	/** The cluster would not answer. */
	public static PreviousLog failed(String reason) {
		return new PreviousLog("", reason);
	}

	private static String which(String container) {
		return (container != null && !container.isBlank()) ? "container '" + container + "'"
				: "the pod's default container";
	}

	/** Whether there is anything to draw. */
	public boolean available() {
		return this.reason == null;
	}

}
