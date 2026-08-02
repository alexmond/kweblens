package org.alexmond.kweblens.web.ai;

/**
 * An analysis was asked for on an instance that cannot run one —
 * {@code kweblens.ai.enabled} is off, or no chat client is configured. Distinct from "the
 * model failed": nothing was attempted and nothing was spent.
 *
 * <p>
 * The UI learns the same fact from {@code aiAvailable} on the diagnosis read and does not
 * offer the trigger, so this answers a caller who asked anyway — and it must not read as
 * a kweblens fault, which an unmapped 500 would.
 */
public class AiUnavailableException extends RuntimeException {

	public AiUnavailableException(String message) {
		super(message);
	}

}
