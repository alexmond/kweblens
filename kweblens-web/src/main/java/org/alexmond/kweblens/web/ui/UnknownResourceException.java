package org.alexmond.kweblens.web.ui;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a route references a resource id that is not in the {@code NavCatalog}.
 * Mapped to 404.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class UnknownResourceException extends RuntimeException {

	public UnknownResourceException(String id) {
		super("No resource kind registered with id '" + id + "'");
	}

}
