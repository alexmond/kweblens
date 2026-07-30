package org.alexmond.kweblens.web.diag;

/**
 * One detected capability, and — when it is missing — <b>why that matters</b>.
 *
 * <p>
 * The {@code detail} field is the whole point of this record. kweblens degrades
 * gracefully when a backend is absent (metric charts render an "unavailable" state rather
 * than failing), which is good behaviour but leaves the user with no way to tell "nothing
 * is installed" from "installed but my query is wrong" without reading server logs. Every
 * capability therefore carries a human-readable explanation of what was looked for and
 * what was found.
 *
 * @param name what was probed, in user terms
 * @param available whether it was found
 * @param detail what was found, or what to install/configure if it was not
 */
public record Capability(String name, boolean available, String detail) {

	public static Capability yes(String name, String detail) {
		return new Capability(name, true, detail);
	}

	public static Capability no(String name, String detail) {
		return new Capability(name, false, detail);
	}

}
