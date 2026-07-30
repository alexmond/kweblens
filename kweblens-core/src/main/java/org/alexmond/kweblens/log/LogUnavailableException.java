package org.alexmond.kweblens.log;

/**
 * The API server declined to return a log — most often because the container has not
 * started yet.
 *
 * <p>
 * A distinct type because this is usually <b>not</b> a fault: during a rollout every new
 * pod is briefly in this state, so callers that follow a workload treat it as "try again
 * shortly" rather than as an error worth showing. It exists at all because fabric8
 * returns the refusal as log text, which callers would otherwise display as output the
 * container produced.
 */
public class LogUnavailableException extends RuntimeException {

	public LogUnavailableException(String message) {
		super(message);
	}

}
