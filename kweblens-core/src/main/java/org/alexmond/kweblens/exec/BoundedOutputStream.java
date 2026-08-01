package org.alexmond.kweblens.exec;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;

/**
 * Collects up to {@code limit} bytes and silently discards the rest, remembering that it
 * did. Used to capture the output of a one-shot container command so a runaway process —
 * or a caller asking for a multi-gigabyte file — cannot exhaust the heap.
 */
class BoundedOutputStream extends OutputStream {

	private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

	private final long limit;

	private boolean truncated;

	BoundedOutputStream(long limit) {
		this.limit = limit;
	}

	@Override
	public void write(int b) {
		if (buffer.size() < limit) {
			buffer.write(b);
		}
		else {
			truncated = true;
		}
	}

	@Override
	public void write(byte[] bytes, int off, int len) {
		long room = limit - buffer.size();
		if (room <= 0) {
			truncated = (len > 0) || truncated;
			return;
		}
		int take = (int) Math.min(room, len);
		buffer.write(bytes, off, take);
		if (take < len) {
			truncated = true;
		}
	}

	byte[] toByteArray() {
		return buffer.toByteArray();
	}

	boolean isTruncated() {
		return truncated;
	}

}
