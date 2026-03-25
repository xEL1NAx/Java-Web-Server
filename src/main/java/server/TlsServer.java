package server;

import javax.net.ssl.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TlsServer {
    private final HttpServer server;
    private final Config config;
    private final SSLServerSocket serverSocket;
    private final ExecutorService acceptor = Executors.newSingleThreadExecutor();
    private final ExecutorService workers;

    public TlsServer(HttpServer server, Config config) throws Exception {
        this.server = server;
        this.config = config;
        this.serverSocket = createServerSocket(config);
        this.workers = Executors.newFixedThreadPool(Math.max(2, config.workerThreads / 2));
    }

    public void start() {
        acceptor.submit(() -> {
            Logger.info("HTTPS listener started on " + config.bindAddress + ":" + config.httpsPort + " (ALPN: h2, http/1.1)");
            while (!serverSocket.isClosed()) {
                try {
                    SSLSocket socket = (SSLSocket) serverSocket.accept();
                    workers.submit(() -> handle(socket));
                } catch (Exception e) {
                    Logger.error("HTTPS accept failed", e);
                }
            }
        });
    }

    private void handle(SSLSocket socket) {
        String remoteIp = ((InetSocketAddress) socket.getRemoteSocketAddress()).getAddress().getHostAddress();
        try (socket) {
            socket.setSoTimeout(config.connectionIdleTimeoutMillis);
            SSLParameters params = socket.getSSLParameters();
            params.setApplicationProtocols(new String[]{"h2", "http/1.1"});
            socket.setSSLParameters(params);
            socket.startHandshake();

            String protocol = socket.getApplicationProtocol();
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            if ("h2".equals(protocol)) {
                Logger.info("Negotiated HTTP/2 with " + remoteIp);
                new Http2Connection(server, config, in, out, remoteIp, true).run();
                return;
            }

            handleHttp11(socket, remoteIp, in, out);
        } catch (Exception e) {
            Logger.error("HTTPS connection error from " + remoteIp, e);
        }
    }

    private void handleHttp11(SSLSocket socket, String remoteIp, InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[Math.max(16 * 1024, config.readBufferSize)];
        int len = 0;
        boolean websocket = false;
        WebSocketHandler wsHandler = null;

        while (true) {
            if (!websocket) {
                HttpParser.ParseResult parsed;
                while (true) {
                    parsed = HttpParser.tryParse(buffer, len, config.requestHeaderLimitBytes, config.maxBodySizeBytes, remoteIp, true);
                    if (!parsed.needMore) break;
                    int read = in.read(buffer, len, buffer.length - len);
                    if (read == -1) return;
                    len += read;
                    if (len == buffer.length) buffer = Arrays.copyOf(buffer, buffer.length * 2);
                }

                if (parsed.errorStatus != null) {
                    HttpResponse error = HttpResponse.text(parsed.errorStatus, parsed.errorMessage).closeConnection();
                    writeResponse(out, error, false);
                    return;
                }

                HttpRequest request = parsed.request;
                len = consume(buffer, len, parsed.consumedBytes);
                ServerResult result = server.processRequest(request);
                writeResponse(out, result.response, "HEAD".equals(request.method));
                Logger.access(request.remoteIp, request.method, Util.inferHostWithoutPort(request.host()), request.path, result.response.status,
                        result.response.body == null ? 0 : result.response.body.length, (System.nanoTime() - request.receivedAtNanos) / 1000);

                if (result.upgradeWebSocket) {
                    websocket = true;
                    wsHandler = result.webSocketHandler;
                    WebSocketSession blockingSession = new WebSocketSession(new BlockingConnectionAdapter(out));
                    wsHandler.onOpen(blockingSession);
                } else if (!request.keepAlive() || result.response.close) {
                    return;
                }
            } else {
                while (true) {
                    WebSocketFrame.ParseResult frameResult = WebSocketFrame.tryParse(buffer, len);
                    if (!frameResult.needMore) {
                        len = consume(buffer, len, frameResult.consumed);
                        WebSocketFrame frame = frameResult.frame;
                        WebSocketSession session = new WebSocketSession(new BlockingConnectionAdapter(out));
                        switch (frame.opcode) {
                            case 0x1 -> {
                                try {
                                    wsHandler.onText(session, new String(frame.payload, java.nio.charset.StandardCharsets.UTF_8));
                                } catch (Exception e) {
                                    Logger.error("WebSocket text handler failed", e);
                                    out.write(WebSocketFrame.close(new byte[]{0x03, (byte) 0xF3}).toBuffer().array());
                                    out.flush();
                                    return;
                                }
                            }
                            case 0x2 -> {
                                try {
                                    wsHandler.onBinary(session, frame.payload);
                                } catch (Exception e) {
                                    Logger.error("WebSocket binary handler failed", e);
                                    out.write(WebSocketFrame.close(new byte[]{0x03, (byte) 0xF3}).toBuffer().array());
                                    out.flush();
                                    return;
                                }
                            }
                            case 0x8 -> {
                                int code = 1000;
                                String reason = "";
                                if (frame.payload.length >= 2) {
                                    code = ((frame.payload[0] & 0xFF) << 8) | (frame.payload[1] & 0xFF);
                                    if (frame.payload.length > 2) {
                                        reason = new String(Arrays.copyOfRange(frame.payload, 2, frame.payload.length), java.nio.charset.StandardCharsets.UTF_8);
                                    }
                                }
                                wsHandler.onClose(session, code, reason);
                                out.write(WebSocketFrame.close(frame.payload).toBuffer().array());
                                out.flush();
                                return;
                            }
                            case 0x9 -> {
                                out.write(WebSocketFrame.pong(frame.payload).toBuffer().array());
                                out.flush();
                            }
                            case 0xA -> { }
                            default -> {
                                out.write(WebSocketFrame.close(new byte[]{0x03, (byte) 0xEA}).toBuffer().array());
                                out.flush();
                                return;
                            }
                        }
                        break;
                    } else {
                        int read = in.read(buffer, len, buffer.length - len);
                        if (read == -1) return;
                        len += read;
                        if (len == buffer.length) buffer = Arrays.copyOf(buffer, buffer.length * 2);
                    }
                }
            }
        }
    }

    private static int consume(byte[] buffer, int len, int consumed) {
        int remaining = len - consumed;
        if (remaining > 0) {
            System.arraycopy(buffer, consumed, buffer, 0, remaining);
        }
        return remaining;
    }

    private static void writeResponse(OutputStream out, HttpResponse response, boolean headOnly) throws IOException {
        for (ByteBuffer buf : response.toBuffers(headOnly)) {
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            out.write(bytes);
        }
        out.flush();
    }

    private static SSLServerSocket createServerSocket(Config config) throws Exception {
        KeyStore ks = KeyStore.getInstance(config.tls.keystoreType);
        try (InputStream in = new FileInputStream(config.tls.keystorePath)) {
            ks.load(in, config.tls.keystorePassword.toCharArray());
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, config.tls.keyPassword.toCharArray());

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);

        SSLServerSocketFactory factory = ctx.getServerSocketFactory();
        SSLServerSocket socket = (SSLServerSocket) factory.createServerSocket();
        socket.bind(new InetSocketAddress(config.bindAddress, config.httpsPort));
        socket.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
        SSLParameters params = socket.getSSLParameters();
        params.setApplicationProtocols(new String[]{"h2", "http/1.1"});
        socket.setSSLParameters(params);
        return socket;
    }

    private static final class BlockingConnectionAdapter implements FrameSender {
        private final OutputStream out;

        BlockingConnectionAdapter(OutputStream out) {
            this.out = out;
        }

        @Override
        public void queueWebSocketFrame(WebSocketFrame frame) throws IOException {
            out.write(frame.toBuffer().array());
            out.flush();
        }

        @Override
        public void markCloseAfterWrite() {
            // blocking connection closes by returning from handler
        }
    }
}
