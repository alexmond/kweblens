package org.alexmond.kweblens.web.exec;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Forwards pod exec output to a WebSocket as UTF-8 text frames — the sink handed to
 * {@link org.alexmond.kweblens.exec.ExecService}. The session should be a
 * {@code ConcurrentWebSocketSessionDecorator} so concurrent sends are serialized.
 */
public class WebSocketOutputStream extends OutputStream {

	private final WebSocketSession session;

	public WebSocketOutputStream(WebSocketSession session) {
		this.session = session;
	}

	@Override
	public void write(int b) throws IOException {
		write(new byte[] { (byte) b }, 0, 1);
	}

	@Override
	public void write(byte[] bytes, int off, int len) throws IOException {
		if (len > 0 && session.isOpen()) {
			session.sendMessage(new TextMessage(new String(bytes, off, len, StandardCharsets.UTF_8)));
		}
	}

}
