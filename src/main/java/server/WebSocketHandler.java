package server;

public interface WebSocketHandler {
    void onOpen(WebSocketSession session);
    void onText(WebSocketSession session, String message) throws Exception;
    void onBinary(WebSocketSession session, byte[] data) throws Exception;
    void onClose(WebSocketSession session, int code, String reason);
}
