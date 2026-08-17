package org.alexmond.kweblens.tui.data;

/**
 * Why a {@link Subscription} stopped delivering — the signal GH#413 was filed for,
 * because a table that has stopped updating and does not say so is worse than an error.
 *
 * <h2>Both endings are reported; they are not the same sentence</h2>
 *
 * A watch can end because the API server gave up on it cleanly, or because it broke. The
 * <em>consequence</em> is identical — nothing further arrives, so the rows on screen are
 * a photograph — and so the screen's response is identical: say so, and re-establish.
 * What differs is the words, and that difference is worth carrying: "the stream ended"
 * sends an operator to look at an idle-timeout proxy, "410: too old resource version"
 * sends them to look at etcd compaction. Collapsing them would throw away the only clue
 * in the signal.
 *
 * <h2>What does NOT reach here</h2>
 *
 * A close the TUI asked for. {@link CoreClusterDataSource} suppresses the end it caused
 * itself, because a screen that is shutting down does not need to be told its watch
 * stopped, and a reconnect that closes the old handle must not read that close as a fresh
 * loss.
 *
 * <h2>A subscription that never started is an ending too</h2>
 *
 * {@link #notSubscribed} and {@link #notListed} are reported by
 * {@code ScreenSession.switchTo} when the kind the operator asked for could not be
 * watched, or could not be listed (GH#434). Nothing arrives in either case, which is the
 * same consequence the two endings above have and therefore the same posture and the same
 * response — so the supervisor's machinery is the right machinery and the only thing that
 * has to differ is, again, the words. <b>Which is why the phrase is carried rather than
 * derived from {@link #clean}</b>: "watch failed" about a watch that was open and a list
 * that was refused is a sentence that sends an operator to the wrong place.
 *
 * @param clean whether the stream ended without an error
 * @param what which step stopped, in two or three words — never null, never empty
 * @param reason a short phrase for a header — never null, never empty
 */
public record WatchEnd(boolean clean, String what, String reason) {

	/** As much of a failure's message as a one-line header can carry. */
	private static final int REASON_LIMIT = 80;

	/** The stream ended without an error. */
	public static WatchEnd completed() {
		return new WatchEnd(true, "watch ended", "the stream ended");
	}

	/**
	 * The stream died. fabric8 reconnects by itself, so this is what it looks like when
	 * it has given up rather than a blip.
	 */
	public static WatchEnd failed(Throwable cause) {
		return new WatchEnd(false, "watch failed", describe(cause));
	}

	/** The subscription could not be opened at all, so there is nothing to end. */
	public static WatchEnd notSubscribed(Throwable cause) {
		return new WatchEnd(false, "could not watch", describe(cause));
	}

	/**
	 * The subscription was opened and its own list was refused — so its events are held
	 * behind a list that never landed, and nothing will reach the screen.
	 */
	public static WatchEnd notListed(Throwable cause) {
		return new WatchEnd(false, "could not list", describe(cause));
	}

	/** What the header says happened, in four or five words. */
	public String summary() {
		return this.what + ": " + this.reason;
	}

	/**
	 * The most specific message the exception carries. fabric8 wraps the API server's
	 * {@code Status} in a {@code KubernetesClientException} inside a
	 * {@code WatcherException}, so the outer message is usually the generic one and the
	 * cause is the sentence naming the code.
	 */
	private static String describe(Throwable cause) {
		if (cause == null) {
			return "no reason given";
		}
		String message = text(cause.getCause());
		if (message.isEmpty()) {
			message = text(cause);
		}
		if (message.isEmpty()) {
			return cause.getClass().getSimpleName();
		}
		return (message.length() > REASON_LIMIT) ? message.substring(0, REASON_LIMIT - 1) + "…" : message;
	}

	private static String text(Throwable cause) {
		if (cause == null || cause.getMessage() == null) {
			return "";
		}
		return cause.getMessage().replace('\n', ' ').trim();
	}

}
