package org.alexmond.kweblens.web.files;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

/**
 * Decides whether a file's bytes are text, and converts between the wire representation
 * and raw bytes.
 *
 * <p>
 * Two independent checks have to agree before content is called text: no NUL byte in the
 * first {@value #BINARY_SCAN_BYTES} bytes (the heuristic the reference extension uses),
 * <em>and</em> the whole payload decodes as strict UTF-8. The second check matters more
 * than the first — a Latin-1 config file has no NUL bytes but would come back through a
 * lenient decoder full of replacement characters, and saving that would silently rewrite
 * every non-ASCII byte in somebody's file. Anything that fails either check is returned
 * as base64 and marked non-editable.
 */
final class PodFileCodec {

	static final int BINARY_SCAN_BYTES = 512;

	private PodFileCodec() {
	}

	static boolean isBinary(byte[] content) {
		int scan = Math.min(content.length, BINARY_SCAN_BYTES);
		for (int i = 0; i < scan; i++) {
			if (content[i] == 0) {
				return true;
			}
		}
		return !isUtf8(content);
	}

	static PodFileContent describe(String path, String container, long size, byte[] content, boolean truncated) {
		boolean binary = isBinary(content);
		String encoding = (binary) ? "base64" : "utf-8";
		String body = (binary) ? Base64.getEncoder().encodeToString(content)
				: new String(content, StandardCharsets.UTF_8);
		return new PodFileContent(path, container, size, binary, truncated, !binary && !truncated, encoding, body);
	}

	/**
	 * Turn a write request into the exact bytes to store. Exactly one representation may
	 * be supplied: guessing between them is how a binary gets corrupted on save.
	 */
	static byte[] decode(PodFileWrite body) {
		if (body == null) {
			throw invalid("a request body with either 'text' or 'base64' is required");
		}
		boolean hasText = body.text() != null;
		boolean hasBase64 = StringUtils.hasText(body.base64());
		if (hasText == hasBase64) {
			throw invalid("provide exactly one of 'text' (UTF-8 text) or 'base64' (raw bytes)");
		}
		if (hasText) {
			return body.text().getBytes(StandardCharsets.UTF_8);
		}
		try {
			return Base64.getDecoder().decode(body.base64());
		}
		catch (IllegalArgumentException ex) {
			throw new PodFileException(HttpStatus.BAD_REQUEST, "invalid-file-content",
					"'base64' is not valid base64: " + ex.getMessage(), ex);
		}
	}

	private static boolean isUtf8(byte[] content) {
		CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
			.onMalformedInput(CodingErrorAction.REPORT)
			.onUnmappableCharacter(CodingErrorAction.REPORT);
		try {
			decoder.decode(ByteBuffer.wrap(content));
			return true;
		}
		catch (CharacterCodingException ex) {
			return false;
		}
	}

	private static PodFileException invalid(String message) {
		return new PodFileException(HttpStatus.BAD_REQUEST, "invalid-file-content", message);
	}

}
