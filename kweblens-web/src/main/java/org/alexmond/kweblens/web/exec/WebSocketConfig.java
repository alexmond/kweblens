package org.alexmond.kweblens.web.exec;

import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import org.alexmond.kweblens.exec.ExecService;
import org.alexmond.kweblens.web.security.AuditService;

/**
 * Registers the pod-exec WebSocket at {@code /ws/exec} (same-origin only). The handshake
 * is authenticated by the security filter chain before it reaches the handler.
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

	private final ExecService execService;

	private final AuditService audit;

	@Bean
	public PodExecWebSocketHandler podExecWebSocketHandler() {
		return new PodExecWebSocketHandler(execService, audit);
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(podExecWebSocketHandler(), "/ws/exec");
	}

}
