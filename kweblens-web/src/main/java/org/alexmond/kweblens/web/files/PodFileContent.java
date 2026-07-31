package org.alexmond.kweblens.web.files;

/**
 * The contents of one file inside a container.
 *
 * @param path the normalised path that was read
 * @param container the container it was read from
 * @param size the file size in bytes as reported by the container
 * @param binary whether the bytes are not valid, NUL-free UTF-8 text
 * @param truncated whether fewer bytes arrived than the file claims to hold
 * @param editable whether the editor may offer to save this file — false for binaries and
 * for anything that did not arrive whole, so a save cannot silently rewrite a file with a
 * partial or mis-decoded copy of itself
 * @param encoding {@code utf-8} when {@code content} is the text itself, {@code base64}
 * when it is base64 of the raw bytes
 * @param content the file contents in the stated encoding
 */
public record PodFileContent(String path, String container, long size, boolean binary, boolean truncated,
		boolean editable, String encoding, String content) {
}
