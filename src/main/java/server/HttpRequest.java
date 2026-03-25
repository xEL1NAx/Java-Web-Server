package server;

import java.util.*;

public final class HttpRequest {
    public final String method;
    public final String target;
    public final String path;
    public final String version;
    public final Map<String, List<String>> headers;
    public final byte[] body;
    public final String remoteIp;
    public final boolean secure;
    public final long receivedAtNanos;

    public HttpRequest(String method, String target, String version,
                       Map<String, List<String>> headers, byte[] body,
                       String remoteIp, boolean secure, long receivedAtNanos) {
        this.method = method;
        this.target = target;
        this.path = Util.pathOnly(target);
        this.version = version;
        this.headers = headers;
        this.body = body == null ? new byte[0] : body;
        this.remoteIp = remoteIp;
        this.secure = secure;
        this.receivedAtNanos = receivedAtNanos;
    }

    public String header(String name) {
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    public List<String> headers(String name) {
        return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
    }

    public boolean keepAlive() {
        String connection = header("connection");
        if ("HTTP/1.1".equalsIgnoreCase(version)) {
            return !Util.headerContainsToken(connection, "close");
        }
        return Util.headerContainsToken(connection, "keep-alive");
    }

    public boolean isWebSocketUpgrade() {
        return "GET".equalsIgnoreCase(method)
                && Util.headerContainsToken(header("connection"), "Upgrade")
                && "websocket".equalsIgnoreCase(header("upgrade"));
    }

    public boolean isHttp2WebSocketConnect() {
        return "HTTP/2".equalsIgnoreCase(version)
                && "CONNECT".equalsIgnoreCase(method)
                && "websocket".equalsIgnoreCase(header(":protocol"));
    }

    public Map<String, List<String>> queryParams() {
        return Util.parseQuery(target);
    }

    public String host() {
        return header("host");
    }
}

