package server;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class HttpResponse {
    public final int status;
    public final String reason;
    public final Map<String, String> headers = new LinkedHashMap<>();
    public byte[] body = new byte[0];
    public boolean chunked = false;
    public List<byte[]> chunks = new ArrayList<>();
    public boolean close = false;

    private HttpResponse(int status, String reason) {
        this.status = status;
        this.reason = reason;
    }

    public static HttpResponse of(int status, String reason) {
        return new HttpResponse(status, reason);
    }

    public static HttpResponse text(int status, String body) {
        HttpResponse r = of(status, statusReason(status));
        r.body = body.getBytes(StandardCharsets.UTF_8);
        r.headers.put("Content-Type", "text/plain; charset=utf-8");
        return r;
    }

    public static HttpResponse html(int status, String body) {
        HttpResponse r = of(status, statusReason(status));
        r.body = body.getBytes(StandardCharsets.UTF_8);
        r.headers.put("Content-Type", "text/html; charset=utf-8");
        return r;
    }

    public static HttpResponse bytes(int status, byte[] body, String contentType) {
        HttpResponse r = of(status, statusReason(status));
        r.body = body == null ? new byte[0] : body;
        if (contentType != null) r.headers.put("Content-Type", contentType);
        return r;
    }

    public static HttpResponse redirect(int status, String location) {
        HttpResponse r = of(status, statusReason(status));
        r.headers.put("Location", location);
        r.body = new byte[0];
        return r;
    }

    public HttpResponse header(String name, String value) {
        headers.put(Util.normalizeHeaderName(name), value);
        return this;
    }

    public HttpResponse closeConnection() {
        this.close = true;
        return this;
    }

    public HttpResponse asChunked(int preferredChunkSize) {
        this.chunked = true;
        this.chunks.clear();
        if (body != null && body.length > 0) {
            for (int i = 0; i < body.length; i += preferredChunkSize) {
                int len = Math.min(preferredChunkSize, body.length - i);
                byte[] chunk = Arrays.copyOfRange(body, i, i + len);
                chunks.add(chunk);
            }
        }
        return this;
    }

    public ByteBuffer[] toBuffers(boolean headOnly) {
        List<ByteBuffer> bufs = new ArrayList<>();
        Map<String, String> allHeaders = new LinkedHashMap<>(headers);

        if (!allHeaders.containsKey("Date")) {
            allHeaders.put("Date", Util.rfc1123(System.currentTimeMillis()));
        }
        if (!allHeaders.containsKey("Server")) {
            allHeaders.put("Server", "JavaWebServer/1.0");
        }
        if (close) allHeaders.put("Connection", "close");

        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        if (chunked) {
            allHeaders.put("Transfer-Encoding", "chunked");
            allHeaders.remove("Content-Length");
        } else {
            allHeaders.put("Content-Length", String.valueOf(body == null ? 0 : body.length));
        }
        for (Map.Entry<String, String> e : allHeaders.entrySet()) {
            head.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        head.append("\r\n");
        bufs.add(ByteBuffer.wrap(head.toString().getBytes(StandardCharsets.UTF_8)));

        if (!headOnly) {
            if (chunked) {
                for (byte[] chunk : chunks) {
                    String prefix = Integer.toHexString(chunk.length) + "\r\n";
                    bufs.add(ByteBuffer.wrap(prefix.getBytes(StandardCharsets.UTF_8)));
                    bufs.add(ByteBuffer.wrap(chunk));
                    bufs.add(ByteBuffer.wrap("\r\n".getBytes(StandardCharsets.UTF_8)));
                }
                bufs.add(ByteBuffer.wrap("0\r\n\r\n".getBytes(StandardCharsets.UTF_8)));
            } else if (body != null && body.length > 0) {
                bufs.add(ByteBuffer.wrap(body));
            }
        }
        return bufs.toArray(ByteBuffer[]::new);
    }

    public static String statusReason(int status) {
        return switch (status) {
            case 101 -> "Switching Protocols";
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 304 -> "Not Modified";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 408 -> "Request Timeout";
            case 413 -> "Payload Too Large";
            case 414 -> "URI Too Long";
            case 421 -> "Misdirected Request";
            case 426 -> "Upgrade Required";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            case 505 -> "HTTP Version Not Supported";
            default -> "Status";
        };
    }
}
