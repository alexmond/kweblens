package org.alexmond.kweblens.web.files;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.StringJoiner;

import org.springframework.http.HttpStatus;

/**
 * Validation and normalisation of container filesystem paths.
 *
 * <p>
 * A path here is attacker-controlled input that ends up as an argument to a command
 * running inside somebody's pod, so it is checked before it goes anywhere:
 *
 * <ul>
 * <li>it must be <strong>absolute</strong> — a relative path would be resolved against
 * whatever working directory the container image happens to set, which is not something
 * kweblens can reason about;</li>
 * <li>{@code .} and {@code ..} segments are collapsed, and a {@code ..} that would climb
 * above {@code /} is <strong>rejected</strong> rather than silently clamped, so a
 * traversal attempt is visible instead of being quietly rewritten;</li>
 * <li>a NUL byte is rejected — it cannot appear in a POSIX path and its presence means
 * somebody is trying to truncate an argument.</li>
 * </ul>
 *
 * <p>
 * Quoting is deliberately <em>not</em> handled here, because nothing this produces is
 * ever spliced into a shell string: paths are passed as positional arguments to
 * {@code sh -c '<script>' kweblens <path>} and read back as {@code "$1"}, which no shell
 * re-parses. That is what makes spaces, newlines, quotes and {@code $(...)} in filenames
 * harmless. See {@link PodFileScripts}.
 */
final class PodFilePath {

	private static final int MAX_LENGTH = 4096;

	private PodFilePath() {
	}

	/**
	 * Normalise a caller-supplied path, or throw {@link PodFileException} explaining why
	 * it is not usable.
	 */
	static String normalize(String raw) {
		// Deliberately not trimmed: a trailing space is a legal part of a POSIX filename,
		// and quietly stripping it would make such a file permanently unreachable.
		String path = (raw != null) ? raw : "";
		if (path.isEmpty()) {
			path = "/";
		}
		if (path.length() > MAX_LENGTH) {
			throw invalid("path is longer than " + MAX_LENGTH + " characters");
		}
		if (path.indexOf('\0') >= 0) {
			throw invalid("path contains a NUL byte");
		}
		if (path.charAt(0) != '/') {
			throw invalid("path must be absolute (start with '/'): " + path);
		}
		Deque<String> segments = new ArrayDeque<>();
		for (String segment : path.split("/")) {
			if (segment.isEmpty() || ".".equals(segment)) {
				continue;
			}
			if ("..".equals(segment)) {
				if (segments.isEmpty()) {
					throw invalid("path escapes the filesystem root: " + path);
				}
				segments.removeLast();
			}
			else {
				segments.addLast(segment);
			}
		}
		if (segments.isEmpty()) {
			return "/";
		}
		StringJoiner joiner = new StringJoiner("/", "/", "");
		segments.forEach(joiner::add);
		return joiner.toString();
	}

	/**
	 * Whether an already-normalised path sits under one of the configured roots. An empty
	 * root list means "no confinement configured", which is permissive by design — the
	 * feature as a whole is the gate, not this list.
	 */
	static boolean isWithinRoots(String path, List<String> roots) {
		if (roots == null || roots.isEmpty()) {
			return true;
		}
		for (String root : roots) {
			String normalizedRoot = normalize(root);
			if ("/".equals(normalizedRoot) || path.equals(normalizedRoot) || path.startsWith(normalizedRoot + "/")) {
				return true;
			}
		}
		return false;
	}

	/** The last segment of a path — used as the download filename. */
	static String fileName(String path) {
		int slash = path.lastIndexOf('/');
		String name = (slash >= 0) ? path.substring(slash + 1) : path;
		return (name.isEmpty()) ? "root" : name;
	}

	private static PodFileException invalid(String message) {
		return new PodFileException(HttpStatus.BAD_REQUEST, "invalid-file-path", message);
	}

}
