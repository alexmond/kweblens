package org.alexmond.kweblens.web.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import io.fabric8.kubernetes.client.dsl.LogWatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.alexmond.kweblens.log.LogService;

/**
 * Pod log API: a plain-text tail snapshot and a live Server-Sent-Events stream. The
 * stream reads fabric8's {@link LogWatch#getOutput()} on a daemon thread and delivers
 * each log line as one SSE event.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class LogApiController {

	private final LogService logs;

	@GetMapping(value = "/api/v1/clusters/{clusterId}/pods/{namespace}/{pod}/log",
			produces = MediaType.TEXT_PLAIN_VALUE)
	public String tail(@PathVariable String clusterId, @PathVariable String namespace, @PathVariable String pod,
			@RequestParam(required = false) String container, @RequestParam(defaultValue = "200") int tailLines) {
		return logs.tail(clusterId, namespace, pod, container, tailLines);
	}

	@GetMapping(value = "/api/v1/clusters/{clusterId}/pods/{namespace}/{pod}/log/stream",
			produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter stream(@PathVariable String clusterId, @PathVariable String namespace, @PathVariable String pod,
			@RequestParam(required = false) String container) {
		SseEmitter emitter = new SseEmitter(0L);
		LogWatch watch;
		try {
			watch = logs.watch(clusterId, namespace, pod, container);
		}
		catch (RuntimeException ex) {
			emitter.completeWithError(ex);
			return emitter;
		}
		emitter.onCompletion(watch::close);
		emitter.onTimeout(watch::close);
		// After the completion hooks, never before — see SseKeepAlive. A pod that is not
		// logging writes nothing, so without a probe a departed subscriber left this log
		// follow open: measured at 3 clients gone and 3 API-server connections plus 3
		// reader threads still held five minutes later.
		SseKeepAlive.attach(emitter);
		Thread reader = new Thread(() -> pump(emitter, watch.getOutput(), watch), "log-sse-" + pod);
		reader.setDaemon(true);
		reader.start();
		return emitter;
	}

	private void pump(SseEmitter emitter, InputStream source, LogWatch watch) {
		try (watch; BufferedReader reader = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				emitter.send(SseEmitter.event().data(line));
			}
			emitter.complete();
		}
		catch (IOException ex) {
			log.debug("Log stream ended: {}", ex.getMessage());
			SseKeepAlive.completeQuietly(emitter, ex);
		}
	}

}
