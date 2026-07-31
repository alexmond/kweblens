package org.alexmond.kweblens.web.files;

/**
 * One entry in a container directory listing.
 *
 * @param name the entry name as it appears on disk (never a path)
 * @param type {@code dir}, {@code file}, {@code symlink} or {@code other}
 * @param size size in bytes, or {@code null} when the container has no usable
 * {@code stat}
 * @param mode octal permission bits (e.g. {@code 644}), or {@code null}
 * @param modified last-modified time as epoch seconds, or {@code null}
 * @param owner owning user name, or {@code null}
 * @param group owning group name, or {@code null}
 * @param linkTarget for a symlink, its literal target; otherwise {@code null}
 * @param linkType for a symlink, whether the target resolves to a {@code dir} or a
 * {@code file}; {@code null} when the link is broken or unresolvable
 */
public record PodFileEntry(String name, String type, Long size, String mode, Long modified, String owner, String group,
		String linkTarget, String linkType) {
}
