package org.alexmond.kweblens.tui.screen;

import java.util.List;

import org.alexmond.kweblens.tui.log.LogModel;
import org.alexmond.kweblens.tui.log.LogOpen;
import org.alexmond.kweblens.tui.log.LogRequest;

/**
 * The log pane's state: which reading is on screen, and what each of its keys turns that
 * into.
 *
 * <p>
 * Every key here is the same operation — <b>re-open with one field of the
 * {@link LogRequest} changed</b> — and every one of them therefore owes the previous
 * follow a release. That release is not written here: {@link Navigation#logs} is the one
 * owner (see its javadoc), so a key added to this class cannot leak a connection by
 * forgetting a line, which is the whole point of GH#369.
 *
 * <p>
 * No TamboUI type, no cluster type, no thread. What comes out of every method is a
 * sentence for the footer — empty when it worked — which is what lets
 * {@code ViewControllerLogTest} drive the real thing against a fake navigation with no
 * terminal.
 */
public class LogPane {

	private final Navigation navigation;

	/** The document on screen, or null when the pane is closed. */
	private LogModel model;

	/** What produced {@link #model}, so a key can vary one field of it. */
	private LogRequest request;

	public LogPane(Navigation navigation) {
		this.navigation = navigation;
	}

	/**
	 * Open on a pod, live or from its previous run.
	 * @return what could not be done, or empty when the pane opened
	 */
	public String open(String namespace, String pod, boolean previous) {
		return reopen(LogRequest.of(namespace, pod, previous));
	}

	/**
	 * The next container of the pod, wrapping.
	 *
	 * <p>
	 * <b>A pod with one container says so rather than re-opening.</b> Re-opening would
	 * release and re-establish the same follow for no change on screen — a flicker and a
	 * connection churn that reads as a bug — and the reader who pressed {@code c} is
	 * asking a question that deserves an answer.
	 */
	public String cycleContainer() {
		if (!isOpen()) {
			return "";
		}
		List<String> containers = this.model.containers();
		if (containers.size() < 2) {
			return "This pod has " + ((containers.size() == 1) ? "only one container (" + containers.get(0) + ")"
					: "no containers this build can name") + ", so there is nothing to switch to.";
		}
		int at = containers.indexOf(this.model.container());
		String next = containers.get((at + 1) % containers.size());
		return reopen(this.request.inContainer(next));
	}

	/**
	 * Timestamps on or off. A re-open, because the API server stamps the lines — there is
	 * nothing client-side to toggle, and a pane that faked them would be showing the time
	 * the terminal read a line rather than the time the container wrote it.
	 */
	public String toggleTimestamps() {
		if (!isOpen()) {
			return "";
		}
		if (this.request.previous()) {
			return "A previous run is a snapshot the API server does not stamp; "
					+ "press p for the live log to turn timestamps on.";
		}
		return reopen(this.request.withTimestampsToggled());
	}

	/**
	 * The other run: live ↔ terminated instance. k9s's {@code p}, from inside the pane.
	 */
	public String togglePrevious() {
		if (!isOpen()) {
			return "";
		}
		return reopen(this.request.withPreviousToggled());
	}

	/**
	 * Ask the session for this reading, and keep it only if it arrived.
	 *
	 * <p>
	 * <b>A refused re-open leaves the pane on what it was showing</b>, with the reason in
	 * the footer. The session opens the new follow before releasing the old one for
	 * exactly this: a container name the cluster refuses must not cost the reader the
	 * buffer they were already reading.
	 */
	private String reopen(LogRequest wanted) {
		LogOpen opened = this.navigation.logs(wanted);
		if (!opened.available()) {
			return opened.error();
		}
		this.model = opened.model();
		this.request = wanted;
		return "";
	}

	/** Close the pane and release the connection. */
	public void close() {
		this.model = null;
		this.request = null;
		this.navigation.closeLogs();
	}

	/** Whether the pane is up. */
	public boolean isOpen() {
		return this.model != null;
	}

	/** The document, or null when the pane is closed — ask {@link #isOpen()} first. */
	public LogModel model() {
		return this.model;
	}

	/**
	 * Move everything the reader thread has buffered into the document. Called once per
	 * tick from the screen; the whole of "one repaint per flush period" is that this is
	 * the only thing that moves a line onto the screen.
	 * @return whether a repaint is owed
	 */
	public boolean flush() {
		return isOpen() && this.model.flush();
	}

}
