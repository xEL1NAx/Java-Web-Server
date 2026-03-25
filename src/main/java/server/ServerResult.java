package server;

public final class ServerResult {
    public final HttpResponse response;
    public final WebSocketHandler webSocketHandler;
    public final boolean upgradeWebSocket;

    private ServerResult(HttpResponse response, WebSocketHandler webSocketHandler, boolean upgradeWebSocket) {
        this.response = response;
        this.webSocketHandler = webSocketHandler;
        this.upgradeWebSocket = upgradeWebSocket;
    }

    public static ServerResult response(HttpResponse response) {
        return new ServerResult(response, null, false);
    }

    public static ServerResult websocket(HttpResponse handshake, WebSocketHandler handler) {
        return new ServerResult(handshake, handler, true);
    }
}
