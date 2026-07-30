package org.alexmond.kweblens.log;

import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.client.utils.Serialization;

import org.springframework.util.StringUtils;

/**
 * Recognises an API-server refusal that arrived <b>as log content</b>.
 *
 * <p>
 * Asking for the log of a container that has not started yet answers HTTP 400 with a
 * {@code Status} body, and fabric8 surfaces that JSON as the log itself — from
 * {@code getLog()} as the returned string, and from {@code watchLog()} as the first thing
 * on the stream. Neither throws. Without this check the workload appears to have printed
 * a Kubernetes error object: a fabricated line, attributed to the user's application, on
 * <b>every</b> pod a rollout creates.
 *
 * <p>
 * The test is deliberately narrow — a {@code Status} envelope in {@code Failure} state at
 * the very start — because plenty of applications log structured JSON of their own, and
 * mistaking real output for an error would be the worse failure of the two.
 */
public final class LogRefusal {

	private LogRefusal() {
	}

	public static boolean isRefusal(String body) {
		if (body == null) {
			return false;
		}
		String head = body.stripLeading();
		return head.startsWith("{\"kind\":\"Status\"") && head.contains("\"status\":\"Failure\"");
	}

	/**
	 * The {@code message} out of the refusal — the readable half ("container X is waiting
	 * to start: ContainerCreating") rather than the whole JSON.
	 *
	 * <p>
	 * Deserialised with the real model rather than picked apart by hand: the message
	 * routinely contains escaped quotes, since it quotes the container and pod names, and
	 * that is exactly what naive string scanning gets wrong.
	 */
	public static String message(String body) {
		try {
			Status status = Serialization.unmarshal(body.stripLeading(), Status.class);
			return StringUtils.hasText(status.getMessage()) ? status.getMessage() : "the log is not available";
		}
		catch (RuntimeException ex) {
			return "the log is not available";
		}
	}

}
