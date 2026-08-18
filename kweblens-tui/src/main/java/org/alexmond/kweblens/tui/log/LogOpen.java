package org.alexmond.kweblens.tui.log;

/**
 * What one open of the log pane produced: a document, or the reason there is not one.
 *
 * <p>
 * <b>A refusal is a sentence, never an exception</b> (GH#434). Opening a log pane
 * resolves the pod's containers and then opens a stream, both on the render thread inside
 * a key press, and either can be refused — RBAC on {@code pods/log}, a pod deleted
 * between the list and the keystroke, a container that has not started. An exception out
 * of an {@code EventHandler} does not kill TamboUI and does not print: the runner catches
 * it, the default {@code RenderErrorHandler} is {@code displayAndQuit},
 * {@code inErrorState} is set and never cleared, and the session dies behind a stack
 * trace with nothing in the log.
 *
 * @param model the pane's document, or null when it could not be opened
 * @param error why it could not, or null when it was
 */
public record LogOpen(LogModel model, String error) {

	/** A pane that opened. */
	public static LogOpen of(LogModel model) {
		return new LogOpen(model, null);
	}

	/** A pane that did not, and why. */
	public static LogOpen failed(String reason) {
		return new LogOpen(null, reason);
	}

	/** Whether there is anything to draw. */
	public boolean available() {
		return this.error == null;
	}

}
