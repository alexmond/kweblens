package org.alexmond.kweblens.exec;

/**
 * A one-shot container command could not be run to completion — the container has no
 * shell, the pod is gone, the API server refused the exec, or the command outlived its
 * timeout. Distinct from "the command ran and exited non-zero", which is reported through
 * {@link ExecResult#exitCode()}.
 */
public class ExecFailedException extends RuntimeException {

	public ExecFailedException(String message) {
		super(message);
	}

	public ExecFailedException(String message, Throwable cause) {
		super(message, cause);
	}

}
