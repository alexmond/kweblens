package org.alexmond.kweblens.web.files;

import java.util.List;

/**
 * A container directory listing.
 *
 * @param path the normalised path that was requested
 * @param resolvedPath the same directory after the container resolved symlinks
 * ({@code pwd -P}) — this is what the confinement check in
 * {@link FilesProperties#getAllowedRoots()} is applied to
 * @param container the container that was read
 * @param entries the entries, directories first then by name
 * @param truncated whether the listing hit {@link FilesProperties#getMaxEntries()}
 */
public record PodDirectoryListing(String path, String resolvedPath, String container, List<PodFileEntry> entries,
		boolean truncated) {
}
