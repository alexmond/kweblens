package org.alexmond.kweblens.web.files;

/**
 * The body of a file write. Exactly one of the two fields must be set: {@code text} for
 * an edited text file, {@code base64} for an upload or any content that is not UTF-8
 * text. Keeping them separate means the server never has to guess an encoding, so it
 * cannot corrupt a binary by round-tripping it through a string.
 *
 * @param text UTF-8 text to write verbatim
 * @param base64 base64 of the exact bytes to write
 */
public record PodFileWrite(String text, String base64) {
}
