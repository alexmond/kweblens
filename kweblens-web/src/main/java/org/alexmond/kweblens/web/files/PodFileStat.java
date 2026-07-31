package org.alexmond.kweblens.web.files;

/**
 * What the container reports about a single path.
 *
 * @param type {@code dir}, {@code file}, {@code symlink}, {@code other} or
 * {@code missing}
 * @param size size in bytes, or {@code null} if unknown
 * @param mode octal permission bits, or {@code null}
 * @param modified epoch seconds, or {@code null}
 * @param owner owning user, or {@code null}
 * @param group owning group, or {@code null}
 * @param linkTarget literal symlink target, or {@code null}
 * @param realPath the path with all symlinks resolved ({@code readlink -f}), or
 * {@code null} when the container cannot resolve it
 */
public record PodFileStat(String type, Long size, String mode, Long modified, String owner, String group,
		String linkTarget, String realPath) {

	boolean exists() {
		return !"missing".equals(type);
	}

	boolean isDirectory() {
		return "dir".equals(type);
	}

}
