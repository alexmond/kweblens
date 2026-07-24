package org.alexmond.kweblens.web.ai;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a remediation apply is attempted without explicit confirmation. Remediation
 * is never autonomous — it always requires an explicit approve. Mapped to 400.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class ConfirmationRequiredException extends RuntimeException {

	public ConfirmationRequiredException() {
		super("Remediation requires explicit confirmation (confirm=true).");
	}

}
