package org.alexmond.kweblens.web.sim;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;

/**
 * Per-object randomness that is <b>deterministic in the object's index</b>: object 17 is
 * the same size and shape in every run, at every {@code size} setting, on every machine.
 *
 * <p>
 * That is not a nicety. A rig is used to compare two measurements, and a rig whose
 * objects differ between the runs being compared cannot tell a code change from its own
 * noise. It also means a defect found at index 17 can be reproduced by seeding 20 objects
 * instead of 3 000.
 *
 * <p>
 * {@link Random} rather than a modern {@code RandomGenerator} on purpose: its algorithm
 * is specified by the JDK, so the same seed gives the same stream across JVM versions,
 * which is exactly the property being relied on here.
 */
final class SimRandom {

	/** Filler vocabulary — config-file-ish, so a generated ConfigMap reads like one. */
	private static final String[] WORDS = { "timeout", "enabled", "replicas", "endpoint", "retries", "buffer",
			"threshold", "interval", "backend", "upstream", "cache", "shards", "tls", "verbose", "region", "bucket",
			"queue", "worker", "handler", "limit" };

	private final Random random;

	SimRandom(String salt, int index) {
		this.random = new Random(mix(salt.hashCode(), index));
	}

	/**
	 * SplitMix64 finalisation over (salt, index), so two kinds at the same index get
	 * unrelated streams — without it every kind's object 17 was "large" together, which
	 * is a correlation no real cluster has.
	 */
	private static long mix(int salt, int index) {
		long z = ((long) salt << 32) ^ (index + 0x9E37_79B9L);
		z = (z ^ (z >>> 30)) * 0xBF58_476D_1CE4_E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D0_49BB_1331_11EBL;
		return z ^ (z >>> 31);
	}

	/** Inclusive on both ends. */
	int between(int min, int max) {
		return (max <= min) ? min : min + this.random.nextInt(max - min + 1);
	}

	/** True with the given percentage probability. */
	boolean chance(int percent) {
		return this.random.nextInt(100) < percent;
	}

	String word() {
		return WORDS[this.random.nextInt(WORDS.length)];
	}

	/** A hex string of {@code length} characters — uids, image digests, hashes. */
	String hex(int length) {
		StringBuilder sb = new StringBuilder(length);
		while (sb.length() < length) {
			sb.append(Integer.toHexString(this.random.nextInt(16)));
		}
		return sb.toString();
	}

	/**
	 * Roughly {@code bytes} of config-file-shaped text. Approximate by design: a real
	 * ConfigMap is not a round number of bytes either, and forcing one would put a
	 * suspicious spike in every size histogram taken against this rig.
	 */
	String text(int bytes) {
		StringBuilder sb = new StringBuilder(bytes + 32);
		while (sb.length() < bytes) {
			sb.append(word())
				.append('.')
				.append(word())
				.append('=')
				.append(word())
				.append('-')
				.append(between(1, 9999))
				.append('\n');
		}
		return sb.toString();
	}

	/**
	 * Base64 of {@code bytes} random bytes — a Secret's values are base64 on the wire,
	 * and something that merely looks like base64 would break the drawer that decodes it.
	 */
	String base64(int bytes) {
		byte[] raw = new byte[Math.max(1, bytes)];
		this.random.nextBytes(raw);
		return Base64.getEncoder().encodeToString(raw);
	}

	/**
	 * A PEM block, <b>base64-encoded</b> — which is literally what a Secret's
	 * {@code tls.crt} value is on the wire. The encoding is not cosmetic: the drawer
	 * decodes what it is given, so a value that merely looked like a certificate would
	 * render as a decoding failure in the one view the fixture exists to exercise.
	 * {@code encodedLength} is the length of the result, so callers can size the object.
	 */
	String pem(String label, int encodedLength) {
		int textLength = Math.max(96, encodedLength * 3 / 4);
		StringBuilder text = new StringBuilder(textLength + 64);
		text.append("-----BEGIN ").append(label).append("-----\n");
		while (text.length() < textLength - 24) {
			text.append(hex(64)).append('\n');
		}
		text.append("-----END ").append(label).append("-----\n");
		return Base64.getEncoder().encodeToString(text.toString().getBytes(StandardCharsets.UTF_8));
	}

}
