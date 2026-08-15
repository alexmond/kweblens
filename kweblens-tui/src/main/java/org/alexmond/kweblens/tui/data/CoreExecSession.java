package org.alexmond.kweblens.tui.data;

import java.io.OutputStream;

import io.fabric8.kubernetes.client.dsl.ExecWatch;
import lombok.RequiredArgsConstructor;

/**
 * An {@link ExecSession} over fabric8's {@link ExecWatch} — the remote terminal, whose
 * size is set by an API call rather than by an ioctl on this machine.
 */
@RequiredArgsConstructor
class CoreExecSession implements ExecSession {

	private final ExecWatch watch;

	@Override
	public OutputStream stdin() {
		return this.watch.getInput();
	}

	@Override
	public void resize(int columns, int rows) {
		this.watch.resize(columns, rows);
	}

	@Override
	public void close() {
		this.watch.close();
	}

}
