package server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private final boolean expectClientPreface;
    private final Map<Integer, StreamState> streams = new LinkedHashMap<>();
    private final Map<Integer, WebSocketStreamState> webSocketStreams = new LinkedHashMap<>();

    private boolean closeRequested = false;
    private int peerMaxFrameSize = 16_384;
    private int peerInitialWindowSize = 65_535;
    private int connectionSendWindow = 65_535;
    private int lastSeenStreamId = 0;
    private int nextServerStreamId = 2;
    private boolean peerAllowsPush = true;

    Http2Connection(HttpServer server, Config config, InputStream in, OutputStream out, String remoteIp, boolean secure) {
        this(server, config, in, out, remoteIp, secure, true);
    }

    Http2Connection(HttpServer server, Config config, InputStream in, OutputStream out, String remoteIp, boolean secure, boolean expectClientPreface) {
        this.server = server;
        this.config = config;
        this.in = in;
        this.out = out;
        this.remoteIp = remoteIp;
        this.secure = secure;
        this.expectClientPreface = expectClientPreface;
    }

    public void run() throws IOException {
        if (expectClientPreface) {
            readClientPreface();
        }

        Http2Frame.writeSettings(out, new int[][] {
                {Http2Frame.SETTING_MAX_CONCURRENT_STREAMS, 100},
                {Http2Frame.SETTING_INITIAL_WINDOW_SIZE, 65_535},
                {Http2Frame.SETTING_MAX_FRAME_SIZE, 16_384},
                {Http2Frame.SETTING_MAX_HEADER_LIST_SIZE, config.requestHeaderLimitBytes},
                {Http2Frame.SETTING_ENABLE_CONNECT_PROTOCOL, 1}
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
            case Http2Frame.TYPE_RST_STREAM -> {
                WebSocketStreamState ws = webSocketStreams.remove(frame.streamId);
                if (ws != null) {
                    notifyWebSocketClosed(ws, 1006, "RST_STREAM");
                }
                streams.remove(frame.streamId);
            }
            case Http2Frame.TYPE_GOAWAY -> closeRequested = true;
            case Http2Frame.TYPE_PRIORITY -> {
                // ignored in this demo implementation
            }
            case Http2Frame.TYPE_PUSH_PROMISE -> protocolError("Client sent PUSH_PROMISE");
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
                    if (value < 0) {
                        protocolError("Invalid SETTINGS_INITIAL_WINDOW_SIZE");
                        return;
                    }
                    int delta = value - peerInitialWindowSize;
                    peerInitialWindowSize = value;
                    for (StreamState state : streams.values()) {
                        state.sendWindow += delta;
                    }
                }
                case Http2Frame.SETTING_MAX_FRAME_SIZE -> {
                    if (value < 16_384 || value > 16_777_215) {
                        protocolError("Invalid SETTINGS_MAX_FRAME_SIZE");
                        return;
                    }
                    peerMaxFrameSize = value;
                }
                case Http2Frame.SETTING_ENABLE_PUSH -> {
                    if (value != 0 && value != 1) {
                        protocolError("Invalid SETTINGS_ENABLE_PUSH");
                        return;
                    }
                    peerAllowsPush = value == 1;
                }
                case Http2Frame.SETTING_HEADER_TABLE_SIZE,
                     Http2Frame.SETTING_MAX_CONCURRENT_STREAMS,
                     Http2Frame.SETTING_MAX_HEADER_LIST_SIZE,
                     Http2Frame.SETTING_ENABLE_CONNECT_PROTOCOL -> {
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
            if (stream.endStream || "CONNECT".equalsIgnoreCase(single(stream.headers, ":method"))) {
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
            if (stream.endStream || "CONNECT".equalsIgnoreCase(single(stream.headers, ":method"))) {
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
        if (data.length > 0) {
            Http2Frame.writeWindowUpdate(out, 0, data.length);
            Http2Frame.writeWindowUpdate(out, frame.streamId, data.length);
        }

        WebSocketStreamState ws = webSocketStreams.get(frame.streamId);
        if (ws != null) {
            if (data.length > 0) {
                ws.inbound.write(data, 0, data.length);
                processWebSocketFrames(ws);
            }
            if ((frame.flags & Http2Frame.FLAG_END_STREAM) != 0) {
                notifyWebSocketClosed(ws, 1000, "stream closed");
                removeWebSocketStream(frame.streamId);
            }
            return;
        }

        if (stream.body.size() + data.length > config.maxBodySizeBytes) {
            sendSimpleResponse(frame.streamId, 413, "Payload Too Large");
            streams.remove(frame.streamId);
            return;
        }
        stream.body.write(data, 0, data.length);
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
        if (stream.dispatched) {
            return;
        }
        stream.dispatched = true;

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
            if (request.isHttp2WebSocketConnect()) {
                startHttp2WebSocket(stream, request, result);
                long durationMicros = (System.nanoTime() - start) / 1000;
                Logger.access(request.remoteIp, request.method, Util.inferHostWithoutPort(request.host()), request.path,
                        result.response.status, 0, durationMicros);
                return;
            }
            HttpResponse response = HttpResponse.text(400, "WebSocket upgrade requires HTTP/1.1 Upgrade or HTTP/2 CONNECT.");
            sendResponse(stream, request, response, true);
        } else {
            sendResponse(stream, request, result.response, true);
            long durationMicros = (System.nanoTime() - start) / 1000;
            HttpResponse logged = result.response;
            byte[] loggedBody = materializeBody(logged);
            Logger.access(request.remoteIp, request.method, Util.inferHostWithoutPort(request.host()), request.path,
                    logged.status, loggedBody.length, durationMicros);
        }
        streams.remove(stream.id);
    }

    private HttpRequest toRequest(StreamState stream) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        String method = single(stream.headers, ":method");
        String path = single(stream.headers, ":path");
        String authority = Optional.ofNullable(single(stream.headers, ":authority"))
                .orElse(single(stream.headers, "host"));

        if (method == null || path == null) {
            throw new IllegalArgumentException("Missing HTTP/2 pseudo headers");
        }

        for (Map.Entry<String, List<String>> entry : stream.headers.entrySet()) {
            String name = entry.getKey();
            if (name.startsWith(":")) {
                if (":protocol".equals(name)) {
                    headers.put(name, new ArrayList<>(entry.getValue()));
                }
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

    private void sendResponse(StreamState stream, HttpRequest request, HttpResponse response, boolean allowPush) throws IOException {
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

        if (allowPush) {
            maybeSendServerPush(stream, request, response);
        }

        if (body.length > 0) {
            sendData(stream, body, true);
        }

        if (response.close) {
            Http2Frame.writeGoAway(out, stream.id, Http2Frame.ERROR_NO_ERROR, "closing".getBytes(StandardCharsets.UTF_8));
            closeRequested = true;
        }
    }

    private void maybeSendServerPush(StreamState parentStream, HttpRequest request, HttpResponse response) throws IOException {
        if (!peerAllowsPush) {
            return;
        }
        if (!"GET".equals(request.method) || response.status < 200 || response.status >= 300) {
            return;
        }

        Config.HostConfig host = config.resolveHost(request.host());
        LinkedHashSet<String> candidates = new LinkedHashSet<>();

        for (String configured : host.http2Push.getOrDefault(request.path, List.of())) {
            if (configured != null && !configured.isBlank()) {
                candidates.add(Util.sanitizeWebPath(configured));
            }
        }

        String linkHeader = response.headers.get("Link");
        if (linkHeader != null) {
            candidates.addAll(parseLinkPreloadPaths(linkHeader));
        }

        if (candidates.isEmpty()) {
            return;
        }

        String authority = Optional.ofNullable(request.host()).orElse("localhost");
        String acceptEncoding = request.header("accept-encoding");

        for (String path : candidates) {
            if (path.equals(request.path)) {
                continue;
            }
            int promisedStreamId = reserveServerStreamId();
            if (promisedStreamId < 0) {
                return;
            }

            byte[] promiseBlock = Hpack.encodeHeaderList(List.of(
                    Map.entry(":method", "GET"),
                    Map.entry(":path", path),
                    Map.entry(":scheme", secure ? "https" : "http"),
                    Map.entry(":authority", authority)
            ), 4096);
            Http2Frame.writePushPromise(out, parentStream.id, promisedStreamId, promiseBlock);

            StreamState promised = new StreamState(promisedStreamId, peerInitialWindowSize);
            streams.put(promisedStreamId, promised);

            Map<String, List<String>> pushHeaders = new LinkedHashMap<>();
            pushHeaders.put("host", new ArrayList<>(List.of(authority)));
            if (acceptEncoding != null && !acceptEncoding.isBlank()) {
                pushHeaders.put("accept-encoding", new ArrayList<>(List.of(acceptEncoding)));
            }
            HttpRequest pushRequest = new HttpRequest("GET", path, "HTTP/2", pushHeaders,
                    new byte[0], remoteIp, secure, System.nanoTime());
            ServerResult pushResult = server.processRequest(pushRequest);
            if (pushResult.upgradeWebSocket) {
                Http2Frame.writeRstStream(out, promisedStreamId, Http2Frame.ERROR_REFUSED_STREAM);
                streams.remove(promisedStreamId);
                continue;
            }
            sendResponse(promised, pushRequest, pushResult.response, false);
            streams.remove(promisedStreamId);
        }
    }

    private List<String> parseLinkPreloadPaths(String linkHeader) {
        List<String> paths = new ArrayList<>();
        for (String part : linkHeader.split(",")) {
            String item = part.trim();
            int open = item.indexOf('<');
            int close = item.indexOf('>');
            if (open < 0 || close <= open) {
                continue;
            }
            String candidate = item.substring(open + 1, close).trim();
            if (!candidate.startsWith("/")) {
                continue;
            }
            String lower = item.toLowerCase(Locale.ROOT);
            if (!(lower.contains("rel=preload") || lower.contains("rel=\"preload\""))) {
                continue;
            }
            paths.add(Util.sanitizeWebPath(candidate));
        }
        return paths;
    }

    private int reserveServerStreamId() {
        if (nextServerStreamId <= 0 || (nextServerStreamId & 1) == 1) {
            return -1;
        }
        int id = nextServerStreamId;
        if (nextServerStreamId > Integer.MAX_VALUE - 2) {
            nextServerStreamId = -1;
        } else {
            nextServerStreamId += 2;
        }
        return id;
    }

    private void startHttp2WebSocket(StreamState stream, HttpRequest request, ServerResult result) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : result.response.headers.entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            if (isIllegalHttp2ResponseHeader(name)) {
                continue;
            }
            headers.put(name, entry.getValue());
        }
        headers.putIfAbsent("date", Util.rfc1123(System.currentTimeMillis()));
        headers.putIfAbsent("server", "JavaWebServer/1.1");

        byte[] headerBlock = Hpack.encodeResponseHeaders(result.response.status, headers, 4096);
        Http2Frame.write(out, Http2Frame.TYPE_HEADERS, Http2Frame.FLAG_END_HEADERS, stream.id, headerBlock);

        WebSocketSession session = new WebSocketSession(new Http2WebSocketSender(stream.id));
        WebSocketStreamState state = new WebSocketStreamState(stream.id, session, result.webSocketHandler);
        webSocketStreams.put(stream.id, state);

        try {
            result.webSocketHandler.onOpen(session);
        } catch (Exception e) {
            Logger.error("HTTP/2 WebSocket open handler failed", e);
            state.session.close(1011, "handler failure");
        }
    }

    private void processWebSocketFrames(WebSocketStreamState ws) throws IOException {
        byte[] data = ws.inbound.toByteArray();
        int length = data.length;

        while (length > 0) {
            WebSocketFrame.ParseResult parsed = WebSocketFrame.tryParse(data, length);
            if (parsed.needMore) {
                break;
            }

            WebSocketFrame frame = parsed.frame;
            int remaining = length - parsed.consumed;
            if (remaining > 0) {
                System.arraycopy(data, parsed.consumed, data, 0, remaining);
            }
            length = remaining;

            if (!handleWebSocketFrame(ws, frame)) {
                return;
            }
        }

        ws.inbound.reset();
        if (length > 0) {
            ws.inbound.write(data, 0, length);
        }
    }

    private boolean handleWebSocketFrame(WebSocketStreamState ws, WebSocketFrame frame) throws IOException {
        switch (frame.opcode) {
            case 0x1 -> {
                try {
                    ws.handler.onText(ws.session, new String(frame.payload, StandardCharsets.UTF_8));
                } catch (Exception e) {
                    Logger.error("HTTP/2 WebSocket text handler failed", e);
                    ws.session.close(1011, "text handler failure");
                    return false;
                }
                return true;
            }
            case 0x2 -> {
                try {
                    ws.handler.onBinary(ws.session, frame.payload);
                } catch (Exception e) {
                    Logger.error("HTTP/2 WebSocket binary handler failed", e);
                    ws.session.close(1011, "binary handler failure");
                    return false;
                }
                return true;
            }
            case 0x8 -> {
                int code = 1000;
                String reason = "";
                if (frame.payload.length >= 2) {
                    code = ((frame.payload[0] & 0xFF) << 8) | (frame.payload[1] & 0xFF);
                    if (frame.payload.length > 2) {
                        reason = new String(Arrays.copyOfRange(frame.payload, 2, frame.payload.length), StandardCharsets.UTF_8);
                    }
                }
                notifyWebSocketClosed(ws, code, reason);
                ws.session.close(code, reason);
                return false;
            }
            case 0x9 -> {
                ws.session.sendPong(frame.payload);
                return true;
            }
            case 0xA -> {
                return true;
            }
            default -> {
                ws.session.close(1002, "unsupported opcode");
                return false;
            }
        }
    }

    private void notifyWebSocketClosed(WebSocketStreamState ws, int code, String reason) {
        if (ws.notifiedClose) {
            return;
        }
        ws.notifiedClose = true;
        try {
            ws.handler.onClose(ws.session, code, reason);
        } catch (Exception e) {
            Logger.error("HTTP/2 WebSocket close handler failed", e);
        }
    }

    private void removeWebSocketStream(int streamId) {
        webSocketStreams.remove(streamId);
        streams.remove(streamId);
    }

    private void sendData(StreamState stream, byte[] body, boolean endStream) throws IOException {
        if (body.length == 0) {
            if (endStream) {
                Http2Frame.write(out, Http2Frame.TYPE_DATA, Http2Frame.FLAG_END_STREAM, stream.id, new byte[0]);
            }
            return;
        }

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
            int flags = (offset >= body.length && endStream) ? Http2Frame.FLAG_END_STREAM : 0;
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
        HttpRequest synthetic = new HttpRequest("GET", "/", "HTTP/2", Map.of("host", List.of("localhost")),
                new byte[0], remoteIp, secure, System.nanoTime());
        sendResponse(stream, synthetic, response, false);
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
        boolean dispatched;
        Map<String, List<String>> headers = new LinkedHashMap<>();

        StreamState(int id, int sendWindow) {
            this.id = id;
            this.sendWindow = sendWindow;
        }
    }

    private final class Http2WebSocketSender implements FrameSender {
        private final int streamId;

        private Http2WebSocketSender(int streamId) {
            this.streamId = streamId;
        }

        @Override
        public void queueWebSocketFrame(WebSocketFrame frame) throws IOException {
            StreamState stream = streams.get(streamId);
            if (stream == null) {
                return;
            }
            ByteBuffer buffer = frame.toBuffer();
            byte[] payload = new byte[buffer.remaining()];
            buffer.get(payload);
            sendData(stream, payload, false);
        }

        @Override
        public void markCloseAfterWrite() {
            try {
                StreamState stream = streams.get(streamId);
                if (stream != null) {
                    sendData(stream, new byte[0], true);
                }
                removeWebSocketStream(streamId);
            } catch (IOException e) {
                Logger.error("Failed to close HTTP/2 WebSocket stream " + streamId, e);
                removeWebSocketStream(streamId);
            }
        }
    }

    private static final class WebSocketStreamState {
        final int streamId;
        final WebSocketSession session;
        final WebSocketHandler handler;
        final ByteArrayOutputStream inbound = new ByteArrayOutputStream();
        boolean notifiedClose;

        private WebSocketStreamState(int streamId, WebSocketSession session, WebSocketHandler handler) {
            this.streamId = streamId;
            this.session = session;
            this.handler = handler;
        }
    }
}






