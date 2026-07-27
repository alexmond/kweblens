package org.alexmond.kweblens.web.exec;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.fabric8.kubernetes.client.dsl.ExecWatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import org.alexmond.kweblens.exec.ExecService;
import org.alexmond.kweblens.web.security.AuditService;

/**
 * Bridges an in-browser terminal to a pod exec session. The handshake carries the target
 * as query params ({@code cluster}, {@code namespace}, {@code pod}, {@code container});
 * keystrokes arrive as text frames and are written to the process stdin, while the
 * process output is framed back by {@link WebSocketOutputStream}. Exec is a privileged
 * action — the {@code /ws/**} handshake is authenticated (see SecurityConfig) and each
 * session is audited.
 */
@Slf4j
@RequiredArgsConstructor
public class PodExecWebSocketHandler extends TextWebSocketHandler {

	private static final int SEND_BUFFER = 512 * 1024;

	private final ExecService execService;

	private final AuditService audit;

	private final Map<String, ExecWatch> watches = new ConcurrentHashMap<>();

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		MultiValueMap<String, String> params = queryParams(session.getUri());
		String cluster = params.getFirst("cluster");
		String namespace = params.getFirst("namespace");
		String pod = params.getFirst("pod");
		String container = params.getFirst("container");
		boolean attach = "attach".equals(params.getFirst("mode"));
		WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, 5000, SEND_BUFFER);
		OutputStream output = new WebSocketOutputStream(safe);
		try {
			ExecWatch watch = attach ? execService.attach(cluster, namespace, pod, container, output, null)
					: execService.exec(cluster, namespace, pod, container, output, null);
			watches.put(session.getId(), watch);
			audit.record(cluster, attach ? "attach" : "exec", "Pod/" + namespace + "/" + pod);
		}
		catch (RuntimeException ex) {
			log.warn("Could not open {} session for {}/{}: {}", attach ? "attach" : "exec", namespace, pod,
					ex.getMessage());
			closeQuietly(session, CloseStatus.SERVER_ERROR.withReason("session failed"));
		}
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
		ExecWatch watch = watches.get(session.getId());
		if (watch != null && watch.getInput() != null) {
			watch.getInput().write(message.getPayload().getBytes(StandardCharsets.UTF_8));
			watch.getInput().flush();
		}
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		ExecWatch watch = watches.remove(session.getId());
		if (watch != null) {
			watch.close();
		}
	}

	private MultiValueMap<String, String> queryParams(URI uri) {
		return (uri != null) ? UriComponentsBuilder.fromUri(uri).build().getQueryParams()
				: new org.springframework.util.LinkedMultiValueMap<>();
	}

	private void closeQuietly(WebSocketSession session, CloseStatus status) {
		try {
			session.close(status);
		}
		catch (IOException ex) {
			log.debug("Failed to close exec session: {}", ex.getMessage());
		}
	}

}
