package server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class WebSocketSession {
    private final FrameSender connection;

    public WebSocketSession(FrameSender connection) {
        this.connection = connection;
    }

    public void sendText(String message) throws IOException {
        connection.queueWebSocketFrame(WebSocketFrame.text(message));
    }

    public void sendBinary(byte[] data) throws IOException {
        connection.queueWebSocketFrame(WebSocketFrame.binary(data));
    }

    public void sendPong(byte[] payload) throws IOException {
        connection.queueWebSocketFrame(WebSocketFrame.pong(payload));
    }

    public void close(int code, String reason) throws IOException {
        byte[] reasonBytes = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + reasonBytes.length];
        payload[0] = (byte) ((code >> 8) & 0xFF);
        payload[1] = (byte) (code & 0xFF);
        System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
        connection.queueWebSocketFrame(WebSocketFrame.close(payload));
        connection.markCloseAfterWrite();
    }
}
