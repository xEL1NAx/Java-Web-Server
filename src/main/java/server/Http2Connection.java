package server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

final class Http2Connection {
    private final HttpServer server;
    private final Config config;
    private final InputStream in;
    private final OutputStream out;
    private final String remoteIp;
    private final boolean secure;
    private final Map<Integer, StreamState> streams = new LinkedHashMap<>();

    private boolean closeRequested = false;
    private int peerMaxFrameSize = 16_384;
    private int peerInitialWindowSize = 65_535;
    private int connectionSendWindow = 65_535;
    private int lastSeenStreamId = 0;

    Http2Connection(HttpServer server, Config config, InputStream in, OutputStream out, String remoteIp, boolean secure) {
        this.server = server;
        this.config = config;
        this.in = in;
        this.out = out;
        this.remoteIp = remoteIp;
        this.secure = secure;
    }

    public void run() throws IOException {
        readClientPreface();
        Http2Frame.writeSettings(out, new int[][] {
                {Http2Frame.SETTING_ENABLE_PUSH, 0},
                {Http2Frame.SETTING_MAX_CONCURRENT_STREAMS, 100},
                {Http2Frame.SETTING_INITIAL_WINDOW_SIZE, 65_535},
                {Http2Frame.SETTING_MAX_FRAME_SIZE, 16_384},
                {Http2Frame.SETTING_MAX_HEADER_LIST_SIZE, config.requestHeaderLimitBytes}
        });

        while (!closeRequested) {
            Http2Frame frame = Http2Frame.read(in, Math.max(peerMaxFrameSize, 16_384));
            if (frame == null) {
                return;
            }
            handleFrame(frame);
        }
    }

    private void readClientPreface() throws IOException {
        byte[] actual = in.readNBytes(Http2Frame.CLIENT_PREFACE.length);
        if (actual.length < Http2Frame.CLIENT_PREFACE.length) {
            throw new IOException("Incomplete HTTP/2 client preface");
        }
        for (int i = 0; i < Http2Frame.CLIENT_PREFACE.length; i++) {
            if (actual[i] != Http2Frame.CLIENT_PREFACE[i]) {
                throw new IOException("Invalid HTTP/2 client preface");
            }
        }
    }

    private void handleFrame(Http2Frame frame) throws IOException {
        lastSeenStreamId = Math.max(lastSeenStreamId, frame.streamId);
        switch (frame.type) {
            case Http2Frame.TYPE_SETTINGS -> handleSettings(frame);
            case Http2Frame.TYPE_PING -> handlePing(frame);
            case Http2Frame.TYPE_HEADERS -> handleHeaders(frame);
            case Http2Frame.TYPE_CONTINUATION -> handleContinuation(frame);
            case Http2Frame.TYPE_DATA -> handleData(frame);
            case Http2Frame.TYPE_WINDOW_UPDATE -> handleWindowUpdate(frame);
            case Http2Frame.TYPE_RST_STREAM -> streams.remove(frame.streamId);
            case Http2Frame.TYPE_GOAWAY -> closeRequested = true;
            case Http2Frame.TYPE_PRIORITY, Http2Frame.TYPE_PUSH_PROMISE -> {
                // ignored in this demo implementation
            }
            default -> {
                // ignore unknown extension frames
            }
        }
    }

    private void handleSettings(Http2Frame frame) throws IOException {
        if ((frame.flags & Http2Frame.FLAG_ACK) != 0) {
            return;
        }
        if (frame.streamId != 0 || frame.payload.length % 6 != 0) {
            protocolError("Malformed SETTINGS frame");
            return;
        }
        for (int i = 0; i < frame.payload.length; i += 6) {
            int id = ((frame.payload[i] & 0xFF) << 8) | (frame.payload[i + 1] & 0xFF);
            int value = ((frame.payload[i + 2] & 0xFF) << 24)
                    | ((frame.payload[i + 3] & 0xFF) << 16)
                    | ((frame.payload[i + 4] & 0xFF) << 8)
                    | (frame.payload[i + 5] & 0xFF);
            switch (id) {
                case Http2Frame.SETTING_INITIAL_WINDOW_SIZE -> {
                    int delta = value - peerInitialWindowSize;
                    peerInitialWindowSize = value;
                    for (StreamState state : streams.values()) {
                        state.sendWindow += delta;
                    }
                }
                case Http2Frame.SETTING_MAX_FRAME_SIZE -> peerMaxFrameSize = value;
                case Http2Frame.SETTING_ENABLE_PUSH, Http2Frame.SETTING_HEADER_TABLE_SIZE,
                     Http2Frame.SETTING_MAX_CONCURRENT_STREAMS, Http2Frame.SETTING_MAX_HEADER_LIST_SIZE -> {
                    // accepted / ignored for now
                }
                default -> {
                    // unknown settings are ignored by spec
                }
            }
        }
        Http2Frame.writeSettingsAck(out);
    }

    private void handlePing(Http2Frame frame) throws IOException {
        if ((frame.flags & Http2Frame.FLAG_ACK) != 0) {
            return;
        }
        if (frame.streamId != 0 || frame.payload.length != 8) {
            protocolError("Malformed PING frame");
            return;
        }
        Http2Frame.writePingAck(out, frame.payload);
    }

    private void handleHeaders(Http2Frame frame) throws IOException {
        if (frame.streamId == 0) {
            protocolError("HEADERS frame on stream 0");
            return;
        }
        StreamState stream = streams.computeIfAbsent(frame.streamId, id -> new StreamState(id, peerInitialWindowSize));
        byte[] fragment = extractHeaderFragment(frame);
        stream.headerBlock.write(fragment, 0, fragment.length);
        stream.headersEnded = (frame.flags & Http2Frame.FLAG_END_HEADERS) != 0;
        stream.endStream = (frame.flags & Http2Frame.FLAG_END_STREAM) != 0;
        if (stream.headersEnded) {
            decodeHeaders(stream);
            if (stream.endStream) {
                dispatch(stream);
            }
        }
    }

    private void handleContinuation(Http2Frame frame) throws IOException {
        StreamState stream = streams.get(frame.streamId);
        if (stream == null) {
            protocolError("CONTINUATION for unknown stream");
            return;
        }
        stream.headerBlock.write(frame.payload, 0, frame.payload.length);
        if ((frame.flags & Http2Frame.FLAG_END_HEADERS) != 0) {
            stream.headersEnded = true;
            decodeHeaders(stream);
            if (stream.endStream) {
                dispatch(stream);
            }
        }
    }

    private void handleData(Http2Frame frame) throws IOException {
        StreamState stream = streams.get(frame.streamId);
        if (stream == null) {
            Http2Frame.writeRstStream(out, frame.streamId, Http2Frame.ERROR_STREAM_CLOSED);
            return;
        }
        byte[] data = extractData(frame);
        if (stream.body.size() + data.length > config.maxBodySizeBytes) {
            sendSimpleResponse(frame.streamId, 413, "Payload Too Large");
            streams.remove(frame.streamId);
            return;
        }
        stream.body.write(data, 0, data.length);
        if (data.length > 0) {
            Http2Frame.writeWindowUpdate(out, 0, data.length);
            Http2Frame.writeWindowUpdate(out, frame.streamId, data.length);
        }
        if ((frame.flags & Http2Frame.FLAG_END_STREAM) != 0) {
            stream.endStream = true;
            if (stream.headersDecoded) {
                dispatch(stream);
            }
        }
    }

    private void handleWindowUpdate(Http2Frame frame) {
        if (frame.payload.length != 4) {
            return;
        }
        int increment = ((frame.payload[0] & 0x7F) << 24)
                | ((frame.payload[1] & 0xFF) << 16)
                | ((frame.payload[2] & 0xFF) << 8)
                | (frame.payload[3] & 0xFF);
        if (increment <= 0) {
            return;
        }
        if (frame.streamId == 0) {
            connectionSendWindow += increment;
        } else {
            StreamState stream = streams.get(frame.streamId);
            if (stream != null) {
                stream.sendWindow += increment;
            }
        }
    }

    private void decodeHeaders(StreamState stream) throws IOException {
        if (stream.headersDecoded) {
            return;
        }
        stream.headers = Hpack.decodeHeaderBlock(stream.headerBlock.toByteArray(), 4096);
        stream.headersDecoded = true;
    }

    private void dispatch(StreamState stream) throws IOException {
        HttpRequest request;
        try {
            request = toRequest(stream);
        } catch (Exception e) {
            sendSimpleResponse(stream.id, 400, "Bad Request");
            streams.remove(stream.id);
            return;
        }

        long start = System.nanoTime();
        ServerResult result = server.processRequest(request);
        if (result.upgradeWebSocket) {
            HttpResponse response = HttpResponse.text(501, "WebSocket over HTTP/2 is not implemented in this project.");
            sendResponse(stream, request, response);
        } else {
            sendResponse(stream, request, result.response);
        }
        long durationMicros = (System.nanoTime() - start) / 1000;
        HttpResponse logged = result.response;
        byte[] loggedBody = materializeBody(logged);
        Logger.access(request.remoteIp, request.method, Util.inferHostWithoutPort(request.host()), request.path,
                logged.status, loggedBody.length, durationMicros);
        streams.remove(stream.id);
    }

    private HttpRequest toRequest(StreamState stream) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        String method = single(stream.headers, ":method");
        String path = single(stream.headers, ":path");
        String authority = Optional.ofNullable(single(stream.headers, ":authority"))
                .orElse(single(stream.headers, "host"));
        String scheme = Optional.ofNullable(single(stream.headers, ":scheme"))
                .orElse(secure ? "https" : "http");

        if (method == null || path == null) {
            throw new IllegalArgumentException("Missing HTTP/2 pseudo headers");
        }

        for (Map.Entry<String, List<String>> entry : stream.headers.entrySet()) {
            String name = entry.getKey();
            if (name.startsWith(":")) {
                continue;
            }
            headers.put(name.toLowerCase(Locale.ROOT), new ArrayList<>(entry.getValue()));
        }
        if (authority != null) {
            headers.put("host", new ArrayList<>(List.of(authority)));
        }
        if (stream.body.size() > 0 && !headers.containsKey("content-length")) {
            headers.put("content-length", new ArrayList<>(List.of(String.valueOf(stream.body.size()))));
        }

        return new HttpRequest(method.toUpperCase(Locale.ROOT), path, "HTTP/2", headers,
                stream.body.toByteArray(), remoteIp, secure, System.nanoTime());
    }

    private void sendResponse(StreamState stream, HttpRequest request, HttpResponse response) throws IOException {
        byte[] body = materializeBody(response);
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : response.headers.entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            if (isIllegalHttp2ResponseHeader(name)) {
                continue;
            }
            headers.put(name, entry.getValue());
        }
        headers.putIfAbsent("date", Util.rfc1123(System.currentTimeMillis()));
        headers.putIfAbsent("server", "JavaWebServer/1.1");
        headers.put("content-length", String.valueOf(body.length));

        byte[] headerBlock = Hpack.encodeResponseHeaders(response.status, headers, 4096);
        Http2Frame.write(out, Http2Frame.TYPE_HEADERS,
                body.length == 0 ? Http2Frame.FLAG_END_HEADERS | Http2Frame.FLAG_END_STREAM : Http2Frame.FLAG_END_HEADERS,
                stream.id,
                headerBlock);

        if (body.length > 0) {
            sendData(stream, body);
        }

        if (response.close) {
            Http2Frame.writeGoAway(out, stream.id, Http2Frame.ERROR_NO_ERROR, "closing".getBytes(StandardCharsets.UTF_8));
            closeRequested = true;
        }
    }

    private void sendData(StreamState stream, byte[] body) throws IOException {
        int offset = 0;
        while (offset < body.length && !closeRequested) {
            waitForSendWindow(stream);
            int chunk = Math.min(body.length - offset, Math.min(peerMaxFrameSize, Math.min(connectionSendWindow, stream.sendWindow)));
            if (chunk <= 0) {
                continue;
            }
            byte[] slice = new byte[chunk];
            System.arraycopy(body, offset, slice, 0, chunk);
            offset += chunk;
            connectionSendWindow -= chunk;
            stream.sendWindow -= chunk;
            int flags = offset >= body.length ? Http2Frame.FLAG_END_STREAM : 0;
            Http2Frame.write(out, Http2Frame.TYPE_DATA, flags, stream.id, slice);
        }
    }

    private void waitForSendWindow(StreamState stream) throws IOException {
        while ((connectionSendWindow <= 0 || stream.sendWindow <= 0) && !closeRequested) {
            Http2Frame frame = Http2Frame.read(in, Math.max(peerMaxFrameSize, 16_384));
            if (frame == null) {
                closeRequested = true;
                return;
            }
            handleFrame(frame);
        }
    }

    private byte[] materializeBody(HttpResponse response) {
        if (!response.chunked) {
            return response.body == null ? new byte[0] : response.body;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (byte[] chunk : response.chunks) {
            bos.write(chunk, 0, chunk.length);
        }
        return bos.toByteArray();
    }

    private void sendSimpleResponse(int streamId, int status, String bodyText) throws IOException {
        HttpResponse response = HttpResponse.text(status, bodyText);
        StreamState stream = streams.computeIfAbsent(streamId, id -> new StreamState(id, peerInitialWindowSize));
        HttpRequest synthetic = new HttpRequest("GET", "/", "HTTP/2", Map.of("host", List.of("localhost")), new byte[0], remoteIp, secure, System.nanoTime());
        sendResponse(stream, synthetic, response);
    }

    private void protocolError(String message) throws IOException {
        Logger.error("HTTP/2 protocol error from " + remoteIp + ": " + message, null);
        Http2Frame.writeGoAway(out, lastSeenStreamId, Http2Frame.ERROR_PROTOCOL_ERROR, message.getBytes(StandardCharsets.UTF_8));
        closeRequested = true;
    }

    private static String single(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static boolean isIllegalHttp2ResponseHeader(String name) {
        return name.equals("connection")
                || name.equals("transfer-encoding")
                || name.equals("keep-alive")
                || name.equals("proxy-connection")
                || name.equals("upgrade");
    }

    private static byte[] extractHeaderFragment(Http2Frame frame) {
        int pos = 0;
        int padLength = 0;
        if ((frame.flags & Http2Frame.FLAG_PADDED) != 0) {
            padLength = frame.payload[pos++] & 0xFF;
        }
        if ((frame.flags & Http2Frame.FLAG_PRIORITY) != 0) {
            pos += 5;
        }
        int len = Math.max(0, frame.payload.length - pos - padLength);
        byte[] out = new byte[len];
        System.arraycopy(frame.payload, pos, out, 0, len);
        return out;
    }

    private static byte[] extractData(Http2Frame frame) {
        int pos = 0;
        int padLength = 0;
        if ((frame.flags & Http2Frame.FLAG_PADDED) != 0) {
            padLength = frame.payload[pos++] & 0xFF;
        }
        int len = Math.max(0, frame.payload.length - pos - padLength);
        byte[] out = new byte[len];
        System.arraycopy(frame.payload, pos, out, 0, len);
        return out;
    }

    private static final class StreamState {
        final int id;
        final ByteArrayOutputStream headerBlock = new ByteArrayOutputStream();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        int sendWindow;
        boolean headersEnded;
        boolean headersDecoded;
        boolean endStream;
        Map<String, List<String>> headers = new LinkedHashMap<>();

        StreamState(int id, int sendWindow) {
            this.id = id;
            this.sendWindow = sendWindow;
        }
    }
}
