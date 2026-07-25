package org.alexmond.kweblens.portforward;

/**
 * Thrown when a port-forward cannot be started (unsupported target kind, or the forward
 * failed to establish). Mapped to a 400 by the web layer.
 */
public class PortForwardException extends RuntimeException {

	public PortForwardException(String message) {
		super(message);
	}

}
