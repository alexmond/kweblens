package org.alexmond.kweblens.web.files;

import java.util.Locale;

import org.springframework.http.HttpStatus;

import org.alexmond.kweblens.exec.ExecFailedException;
import org.alexmond.kweblens.exec.ExecResult;

/**
 * Turns a failed in-container command into an answer a human can act on.
 *
 * <p>
 * Two very different failures land here and must not be conflated. A <em>non-zero
 * exit</em> means the script ran and refused: the file is missing, is a directory, is not
 * readable, the mount is read-only. An {@link ExecFailedException} means the command
 * never ran — most often because the image has no shell at all, which is the distroless
 * case the reference extension simply does not support. Reporting that as "empty
 * directory" would be a lie about the container, so it gets its own status and a message
 * that names the cause.
 */
final class PodFileFailures {

	/** {@link ExecResult#exitCode()} when the stream closed without an exit status. */
	private static final int NO_EXIT_STATUS = -1;

	private static final String[] NO_SHELL_HINTS = { "executable file not found", "no such file or directory",
			"unable to start container process", "oci runtime exec failed", "not found in $path" };

	private PodFileFailures() {
	}

	/** Translate a script that ran and exited non-zero. */
	static PodFileException translate(ExecResult result, String path) {
		String token = firstLine(result.stderr());
		return switch (token) {
			case "missing" -> new PodFileException(HttpStatus.NOT_FOUND, "file-not-found",
					"no such file or directory in the container: " + path);
			case "not-a-directory" ->
				new PodFileException(HttpStatus.BAD_REQUEST, "not-a-directory", "not a directory: " + path);
			case "is-a-directory" -> new PodFileException(HttpStatus.BAD_REQUEST, "is-a-directory",
					path + " is a directory; kweblens does not delete or overwrite directories");
			case "not-a-regular-file" -> new PodFileException(HttpStatus.BAD_REQUEST, "not-a-regular-file",
					path + " is not a regular file (device, socket or fifo) and cannot be read");
			case "not-readable" -> new PodFileException(HttpStatus.FORBIDDEN, "file-not-readable",
					"the container's user cannot read " + path);
			case "cannot-write" -> new PodFileException(HttpStatus.FORBIDDEN, "file-not-writable",
					"cannot create a temporary file beside " + path
							+ " — the directory is read-only (a ConfigMap/Secret mount always is)"
							+ " or the container's user lacks permission. The file is unchanged.");
			case "cannot-replace" -> new PodFileException(HttpStatus.FORBIDDEN, "file-not-writable",
					"cannot overwrite " + path + " — it is read-only for the container's user.");
			case "size-mismatch" -> new PodFileException(HttpStatus.BAD_GATEWAY, "write-unverified",
					"the upload could not be verified inside the container, so " + path
							+ " was left unchanged. This happens when the image has no working 'wc'.");
			case "cannot-delete" -> new PodFileException(HttpStatus.FORBIDDEN, "file-not-deletable",
					"cannot delete " + path + " — it is read-only for the container's user.");
			default -> generic(result, path);
		};
	}

	/** Translate a command that could not be started at all. */
	static PodFileException translate(ExecFailedException ex, String namespace, String pod) {
		String message = (ex.getMessage() != null) ? ex.getMessage() : "";
		if (looksLikeMissingShell(message)) {
			return noShell(namespace, pod, message);
		}
		if (message.contains("timed out")) {
			return new PodFileException(HttpStatus.GATEWAY_TIMEOUT, "container-command-timeout", message);
		}
		return new PodFileException(HttpStatus.BAD_GATEWAY, "container-command-failed",
				"could not run a command in " + namespace + "/" + pod + ": " + message);
	}

	private static PodFileException generic(ExecResult result, String path) {
		String stderr = result.stderr().trim();
		// ExecService reports -1 when the API server closed the stream without an exit
		// status, i.e. the command never ran to completion. Observed against a real
		// cluster: a container with no shell fails exactly this way — exit -1, empty
		// stderr — so neither the 126/127 check nor the stderr hints below catch it, and
		// it used to surface as "the container refused the request", which is wrong twice
		// over. Nothing refused anything, and -1 is not an exit code the container chose.
		if (result.exitCode() == NO_EXIT_STATUS) {
			return new PodFileException(HttpStatus.NOT_IMPLEMENTED, "no-exit-status",
					"the command could not be run in this container — it exited without a status."
							+ " The usual cause is that the image ships no shell (distroless and scratch"
							+ " images have none), so its filesystem cannot be browsed." + detail(stderr));
		}
		if (result.exitCode() == 126 || result.exitCode() == 127 || looksLikeMissingShell(stderr)) {
			return new PodFileException(HttpStatus.NOT_IMPLEMENTED, "no-shell",
					"this container has no usable shell, so its filesystem cannot be browsed."
							+ " The file browser needs /bin/sh plus cat, wc and printf;"
							+ " distroless and scratch images have none of them." + detail(stderr));
		}
		return new PodFileException(HttpStatus.BAD_GATEWAY, "container-command-failed",
				"the container refused the request for " + path + " (exit " + result.exitCode() + ")" + detail(stderr));
	}

	private static PodFileException noShell(String namespace, String pod, String message) {
		return new PodFileException(HttpStatus.NOT_IMPLEMENTED, "no-shell",
				"container in " + namespace + "/" + pod + " has no shell, so its filesystem cannot be browsed."
						+ " The file browser needs /bin/sh; distroless and scratch images do not ship one."
						+ detail(message));
	}

	private static boolean looksLikeMissingShell(String message) {
		String lower = message.toLowerCase(Locale.ROOT);
		for (String hint : NO_SHELL_HINTS) {
			if (lower.contains(hint)) {
				return true;
			}
		}
		return false;
	}

	private static String detail(String stderr) {
		return (stderr.isEmpty()) ? "" : " (" + stderr + ")";
	}

	private static String firstLine(String stderr) {
		int newline = stderr.indexOf('\n');
		String line = (newline >= 0) ? stderr.substring(0, newline) : stderr;
		return line.trim();
	}

}
