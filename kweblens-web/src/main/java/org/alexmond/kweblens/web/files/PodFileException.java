package org.alexmond.kweblens.web.files;

import lombok.Getter;

import org.springframework.http.HttpStatus;

/**
 * A file-browser request that cannot be satisfied — a bad path, a missing file, a
 * directory where a file was expected, a payload over the configured cap, or a refusal
 * because the feature (or writing) is switched off. Carries the HTTP status and a stable
 * machine-readable code so the API answers with a {@code ProblemDetail} the UI can act
 * on, instead of a 500 that says only "kweblens broke".
 */
@Getter
public class PodFileException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	public PodFileException(HttpStatus status, String code, String message) {
		this(status, code, message, null);
	}

	public PodFileException(HttpStatus status, String code, String message, Throwable cause) {
		super(message, cause);
		this.status = status;
		this.code = code;
	}

}
