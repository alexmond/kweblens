package org.alexmond.kweblens.tui.data;

import java.io.InputStream;

import io.fabric8.kubernetes.client.dsl.LogWatch;
import lombok.RequiredArgsConstructor;

import org.alexmond.kweblens.log.LogService;

/**
 * A {@link LogStream} over fabric8's {@link LogWatch}, closed through
 * {@link LogService#release} — which is the whole reason this class exists rather than
 * the watch being handed out directly. Calling {@code LogWatch.close()} here would leave
 * the connection to the API server open and the reader parked; see {@link LogStream}.
 */
@RequiredArgsConstructor
class CoreLogStream implements LogStream {

	private final LogService logs;

	private final LogWatch watch;

	@Override
	public InputStream stream() {
		return this.watch.getOutput();
	}

	@Override
	public void close() {
		this.logs.release(this.watch);
	}

}
