package org.alexmond.kweblens.web.files;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Binary detection exists to stop the editor round-tripping bytes it cannot represent. A
 * file that is misdetected as text and then saved is silently corrupted, so both the
 * NUL-byte scan and the strict UTF-8 check are asserted.
 */
class PodFileCodecTest {

	@Test
	void treatsUtf8TextAsEditable() {
		byte[] content = "hello: wörld\n".getBytes(StandardCharsets.UTF_8);

		PodFileContent described = PodFileCodec.describe("/app.yaml", "app", content.length, content, false);

		assertThat(described.binary()).isFalse();
		assertThat(described.editable()).isTrue();
		assertThat(described.encoding()).isEqualTo("utf-8");
		assertThat(described.content()).isEqualTo("hello: wörld\n");
	}

	@Test
	void treatsNulBearingContentAsBinary() {
		byte[] content = { 0x7f, 'E', 'L', 'F', 0x00, 0x01, 0x02 };

		PodFileContent described = PodFileCodec.describe("/bin/app", "app", content.length, content, false);

		assertThat(described.binary()).isTrue();
		assertThat(described.editable()).isFalse();
		assertThat(described.encoding()).isEqualTo("base64");
		assertThat(Base64.getDecoder().decode(described.content())).isEqualTo(content);
	}

	@Test
	void treatsNonUtf8BytesAsBinaryEvenWithoutNuls() {
		// Latin-1 "café" — no NUL bytes, so the classic scan alone would call it text and
		// a lenient decode would replace the 0xE9 with U+FFFD, rewriting the file on
		// save.
		byte[] content = { 'c', 'a', 'f', (byte) 0xE9, '\n' };

		assertThat(PodFileCodec.isBinary(content)).isTrue();
	}

	@Test
	void refusesToMarkATruncatedFileEditable() {
		byte[] content = "partial".getBytes(StandardCharsets.UTF_8);

		PodFileContent described = PodFileCodec.describe("/big.log", "app", 9_000_000, content, true);

		assertThat(described.truncated()).isTrue();
		assertThat(described.editable()).isFalse();
	}

	@Test
	void decodesExactlyOneRepresentation() {
		assertThat(PodFileCodec.decode(new PodFileWrite("hi", null))).isEqualTo("hi".getBytes(StandardCharsets.UTF_8));
		assertThat(PodFileCodec.decode(new PodFileWrite(null, Base64.getEncoder().encodeToString(new byte[] { 1, 2 }))))
			.isEqualTo(new byte[] { 1, 2 });
		assertThat(PodFileCodec.decode(new PodFileWrite("", null))).isEmpty();
	}

	@Test
	void refusesAmbiguousOrInvalidPayloads() {
		assertThatThrownBy(() -> PodFileCodec.decode(new PodFileWrite("hi", "aGk=")))
			.isInstanceOf(PodFileException.class)
			.hasMessageContaining("exactly one");
		assertThatThrownBy(() -> PodFileCodec.decode(new PodFileWrite(null, null))).isInstanceOf(PodFileException.class)
			.hasMessageContaining("exactly one");
		assertThatThrownBy(() -> PodFileCodec.decode(null)).isInstanceOf(PodFileException.class);
		assertThatThrownBy(() -> PodFileCodec.decode(new PodFileWrite(null, "not base64!!")))
			.isInstanceOf(PodFileException.class)
			.hasMessageContaining("base64");
	}

}
