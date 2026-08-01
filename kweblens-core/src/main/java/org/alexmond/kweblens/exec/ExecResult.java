package org.alexmond.kweblens.exec;

import java.nio.charset.StandardCharsets;

/**
 * The outcome of a one-shot command run inside a container by
 * {@link ExecService#run(String, String, String, String, java.util.List, byte[], long, java.time.Duration)}.
 *
 * <p>
 * {@code stdout} is kept as raw bytes deliberately: the pod file browser reads arbitrary
 * files, and decoding them as text before the caller has decided whether they are text
 * would corrupt binaries. {@code stderr} is diagnostic text and is decoded as UTF-8 with
 * replacement.
 *
 * <p>
 * Not a record: a record component of array type would be stored and handed back by
 * reference, so this class copies on the way in and on the way out.
 */
public final class ExecResult {

	private final int exitCode;

	private final byte[] stdout;

	private final String stderr;

	private final boolean truncated;

	public ExecResult(int exitCode, byte[] stdout, byte[] stderr, boolean truncated) {
		this.exitCode = exitCode;
		this.stdout = (stdout != null) ? stdout.clone() : new byte[0];
		this.stderr = (stderr != null) ? new String(stderr, StandardCharsets.UTF_8) : "";
		this.truncated = truncated;
	}

	/** The command's exit status; {@code 0} means success. */
	public int exitCode() {
		return exitCode;
	}

	/** Raw standard output (a defensive copy). */
	public byte[] stdout() {
		return stdout.clone();
	}

	/** Standard error, decoded as UTF-8 — diagnostics only, never file content. */
	public String stderr() {
		return stderr;
	}

	/** Whether stdout hit the caller's byte cap and was cut short. */
	public boolean truncated() {
		return truncated;
	}

	public boolean succeeded() {
		return exitCode == 0;
	}

}
