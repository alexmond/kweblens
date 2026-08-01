package org.alexmond.kweblens.exec;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Output capture for one-shot container commands. The cap is what stops "show me this
 * file" from becoming "stream this 8 GB core dump into the heap", so it is asserted on
 * both write paths — and it must report that it clipped, because a caller that cannot
 * tell a whole file from a truncated one would happily save the truncated copy back.
 */
class ExecCaptureTest {

	@Test
	void keepsEverythingUnderTheLimit() {
		BoundedOutputStream stream = new BoundedOutputStream(10);

		stream.write("hello".getBytes(StandardCharsets.UTF_8), 0, 5);
		stream.write('!');

		assertThat(new String(stream.toByteArray(), StandardCharsets.UTF_8)).isEqualTo("hello!");
		assertThat(stream.isTruncated()).isFalse();
	}

	@Test
	void clipsAtTheLimitAndSaysSo() {
		BoundedOutputStream stream = new BoundedOutputStream(4);

		stream.write("abcdefgh".getBytes(StandardCharsets.UTF_8), 0, 8);

		assertThat(new String(stream.toByteArray(), StandardCharsets.UTF_8)).isEqualTo("abcd");
		assertThat(stream.isTruncated()).isTrue();
	}

	@Test
	void clipsSingleByteWritesToo() {
		BoundedOutputStream stream = new BoundedOutputStream(1);

		stream.write('a');
		stream.write('b');

		assertThat(stream.toByteArray()).hasSize(1);
		assertThat(stream.isTruncated()).isTrue();
	}

	@Test
	void resultCopiesItsBytesAndDecodesOnlyStderr() {
		byte[] stdout = { 0x00, 0x01, 0x02 };
		ExecResult result = new ExecResult(0, stdout, "boom\n".getBytes(StandardCharsets.UTF_8), false);

		stdout[0] = 0x7f;

		assertThat(result.stdout()).containsExactly(0x00, 0x01, 0x02);
		assertThat(result.stderr()).isEqualTo("boom\n");
		assertThat(result.succeeded()).isTrue();
		assertThat(result.truncated()).isFalse();
	}

	@Test
	void resultToleratesAbsentStreams() {
		ExecResult result = new ExecResult(127, null, null, true);

		assertThat(result.stdout()).isEmpty();
		assertThat(result.stderr()).isEmpty();
		assertThat(result.exitCode()).isEqualTo(127);
		assertThat(result.succeeded()).isFalse();
	}

}
