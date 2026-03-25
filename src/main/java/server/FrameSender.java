package server;

import java.io.IOException;

public interface FrameSender {
    void queueWebSocketFrame(WebSocketFrame frame) throws IOException;
    void markCloseAfterWrite();
}
