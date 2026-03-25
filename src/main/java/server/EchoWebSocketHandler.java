package server;

public final class EchoWebSocketHandler implements WebSocketHandler {
    @Override
    public void onOpen(WebSocketSession session) {
        try {
            session.sendText("connected");
        } catch (Exception e) {
            Logger.error("WebSocket open handler failed", e);
        }
    }

    @Override
    public void onText(WebSocketSession session, String message) throws Exception {
        session.sendText("echo: " + message);
    }

    @Override
    public void onBinary(WebSocketSession session, byte[] data) throws Exception {
        session.sendBinary(data);
    }

    @Override
    public void onClose(WebSocketSession session, int code, String reason) {
        Logger.info("WebSocket closed code=" + code + " reason=" + reason);
    }
}
