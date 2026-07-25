package org.alexmond.kweblens.web.helm;

/**
 * Thrown when a Helm mutation cannot be carried out — the chart could not be resolved
 * from a repository, or the release/target is invalid. Mapped to a 400 by the web layer.
 */
public class HelmException extends RuntimeException {

	public HelmException(String message) {
		super(message);
	}

	public HelmException(String message, Throwable cause) {
		super(message, cause);
	}

}
