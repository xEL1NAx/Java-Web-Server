package server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;

public final class Connection implements FrameSender {
    private final SocketChannel channel;
    private final EventLoop eventLoop;
    private final HttpServer server;
    private final SelectionKey key;
    private final String remoteIp;
    private final byte[] readTemp;
    private final Queue<ByteBuffer> writeQueue = new ArrayDeque<>();

    private byte[] inBuffer = new byte[16 * 1024];
    private int inLen = 0;
    private long lastActivityMillis = System.currentTimeMillis();
    private boolean awaitingResponse = false;
    private boolean closeAfterWrite = false;
    private boolean websocket = false;
    private WebSocketHandler webSocketHandler;
    private WebSocketSession webSocketSession;

    public Connection(SocketChannel channel, EventLoop eventLoop, HttpServer server, SelectionKey key, int readBufferSize, String remoteIp) {
        this.channel = channel;
        this.eventLoop = eventLoop;
        this.server = server;
        this.key = key;
        this.remoteIp = remoteIp;
        this.readTemp = new byte[readBufferSize];
    }

    public String remoteIp() {
        return remoteIp;
    }

    public long lastActivityMillis() {
        return lastActivityMillis;
    }

    public void onReadable() throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(readTemp);
        int n;
        while ((n = channel.read(buf)) > 0) {
            lastActivityMillis = System.currentTimeMillis();
            append(readTemp, n);
            buf.clear();
        }
        if (n == -1) {
            close();
            return;
        }
        if (!awaitingResponse) {
            if (websocket) {
                processWebSocketFrames();
            } else {
                processRequests();
            }
        }
    }

    public void onWritable() throws IOException {
        while (!writeQueue.isEmpty()) {
            ByteBuffer head = writeQueue.peek();
            channel.write(head);
            if (head.hasRemaining()) {
                break;
            }
            writeQueue.poll();
        }
        lastActivityMillis = System.currentTimeMillis();
        if (writeQueue.isEmpty()) {
            eventLoop.disableWriteInterest(this);
            if (closeAfterWrite) {
                close();
                return;
            }
            if (!awaitingResponse && inLen > 0) {
                if (websocket) processWebSocketFrames();
                else processRequests();
            }
        }
    }

    public void onResponseReady(ServerResult result, boolean headOnly, boolean keepAlive, HttpRequest request) {
        awaitingResponse = false;
        HttpResponse response = result.response;
        if (!result.upgradeWebSocket && !keepAlive) response.closeConnection();
        enqueueBuffers(response.toBuffers(headOnly));
        if (!result.upgradeWebSocket && !keepAlive) closeAfterWrite = true;

        if (result.upgradeWebSocket) {
            this.websocket = true;
            this.webSocketHandler = result.webSocketHandler;
            this.webSocketSession = new WebSocketSession(this);
            this.webSocketHandler.onOpen(this.webSocketSession);
        }

        eventLoop.enableWriteInterest(this);
    }

    public void queueWebSocketFrame(WebSocketFrame frame) throws IOException {
        enqueueBuffers(new ByteBuffer[]{frame.toBuffer()});
        eventLoop.enableWriteInterest(this);
    }

    public void markCloseAfterWrite() {
        closeAfterWrite = true;
    }

    public void close() {
        try {
            key.cancel();
            channel.close();
        } catch (IOException ignored) {
        }
    }

    public SelectionKey key() {
        return key;
    }

    private void processRequests() {
        while (!awaitingResponse) {
            HttpParser.ParseResult parsed;
            try {
                parsed = HttpParser.tryParse(inBuffer, inLen, server.getConfig().requestHeaderLimitBytes, server.getConfig().maxBodySizeBytes, remoteIp, false);
            } catch (Exception e) {
                Logger.error("HTTP parse failed for " + remoteIp, e);
                ServerResult result = ServerResult.response(HttpResponse.text(400, "Bad Request").closeConnection());
                onResponseReady(result, false, false, null);
                return;
            }
            if (parsed.needMore) return;
            if (parsed.errorStatus != null) {
                ServerResult result = ServerResult.response(HttpResponse.text(parsed.errorStatus, parsed.errorMessage).closeConnection());
                onResponseReady(result, false, false, null);
                return;
            }
            consume(parsed.consumedBytes);
            awaitingResponse = true;
            server.submitRequest(this, parsed.request);
        }
    }

    private void processWebSocketFrames() throws IOException {
        while (true) {
            WebSocketFrame.ParseResult parsed = WebSocketFrame.tryParse(inBuffer, inLen);
            if (parsed.needMore) return;
            consume(parsed.consumed);
            WebSocketFrame frame = parsed.frame;
            switch (frame.opcode) {
                case 0x1 -> {
                    try {
                        webSocketHandler.onText(webSocketSession, new String(frame.payload, StandardCharsets.UTF_8));
                    } catch (Exception e) {
                        Logger.error("WebSocket text handler failed", e);
                        queueWebSocketFrame(WebSocketFrame.close(new byte[]{0x03, (byte) 0xF3}));
                        markCloseAfterWrite();
                        return;
                    }
                }
                case 0x2 -> {
                    try {
                        webSocketHandler.onBinary(webSocketSession, frame.payload);
                    } catch (Exception e) {
                        Logger.error("WebSocket binary handler failed", e);
                        queueWebSocketFrame(WebSocketFrame.close(new byte[]{0x03, (byte) 0xF3}));
                        markCloseAfterWrite();
                        return;
                    }
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
                    webSocketHandler.onClose(webSocketSession, code, reason);
                    queueWebSocketFrame(WebSocketFrame.close(frame.payload));
                    markCloseAfterWrite();
                    return;
                }
                case 0x9 -> webSocketSession.sendPong(frame.payload);
                case 0xA -> { /* pong */ }
                default -> {
                    queueWebSocketFrame(WebSocketFrame.close(new byte[]{0x03, (byte) 0xEA}));
                    markCloseAfterWrite();
                    return;
                }
            }
        }
    }

    private void enqueueBuffers(ByteBuffer[] bufs) {
        for (ByteBuffer buf : bufs) {
            if (buf != null && buf.hasRemaining()) writeQueue.add(buf);
        }
    }

    private void append(byte[] bytes, int len) {
        ensureCapacity(inLen + len);
        System.arraycopy(bytes, 0, inBuffer, inLen, len);
        inLen += len;
    }

    private void ensureCapacity(int needed) {
        if (needed <= inBuffer.length) return;
        int newCap = inBuffer.length;
        while (newCap < needed) newCap *= 2;
        inBuffer = Arrays.copyOf(inBuffer, newCap);
    }

    private void consume(int bytes) {
        if (bytes <= 0) return;
        int remaining = inLen - bytes;
        if (remaining > 0) {
            System.arraycopy(inBuffer, bytes, inBuffer, 0, remaining);
        }
        inLen = remaining;
    }
}
