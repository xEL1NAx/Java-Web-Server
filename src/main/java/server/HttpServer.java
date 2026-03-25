package server;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HttpServer {
    private final Config config;
    private final Router router;
    private final StaticFileHandler staticFileHandler;
    private final ReverseProxyHandler reverseProxyHandler;
    private final AccessControl accessControl;
    private final RateLimiter rateLimiter;
    private final ExecutorService workers;
    private final ExecutorService h2Connections;
    private final WebSocketHandler defaultWebSocketHandler = new EchoWebSocketHandler();

    private EventLoop eventLoop;
    private TlsServer tlsServer;

    public HttpServer(Config config) {
        this.config = config;
        this.router = new Router();
        this.staticFileHandler = new StaticFileHandler(config);
        this.reverseProxyHandler = new ReverseProxyHandler(config);
        this.accessControl = new AccessControl(config.access);
        this.rateLimiter = new RateLimiter(config.rateLimit);
        this.workers = Executors.newFixedThreadPool(config.workerThreads);
        this.h2Connections = Executors.newCachedThreadPool();
    }

    public void start() throws Exception {
        Logger.configure(Path.of(config.accessLogPath), Path.of(config.errorLogPath));
        this.eventLoop = new EventLoop(this, config);
        this.eventLoop.start();

        if (config.httpsEnabled) {
            this.tlsServer = new TlsServer(this, config);
            this.tlsServer.start();
        }
    }

    public Config getConfig() {
        return config;
    }

    public ReverseProxyHandler getReverseProxyHandler() {
        return reverseProxyHandler;
    }

    public void handOffToCleartextHttp2(SocketChannel channel, String remoteIp, byte[] prefetchedBytes, boolean expectClientPreface) {
        h2Connections.submit(() -> {
            try {
                channel.configureBlocking(true);
                InputStream rawIn = Channels.newInputStream(channel);
                InputStream in = (prefetchedBytes == null || prefetchedBytes.length == 0)
                        ? rawIn
                        : new SequenceInputStream(new ByteArrayInputStream(prefetchedBytes), rawIn);
                OutputStream out = Channels.newOutputStream(channel);
                new Http2Connection(this, config, in, out, remoteIp, false, expectClientPreface).run();
            } catch (Exception e) {
                Logger.error("Cleartext HTTP/2 connection error from " + remoteIp, e);
            } finally {
                try {
                    channel.close();
                } catch (Exception ignored) {
                }
            }
        });
    }

    public void submitRequest(Connection connection, HttpRequest request) {
        workers.submit(() -> {
            long start = System.nanoTime();
            ServerResult result = processRequest(request);
            boolean headOnly = "HEAD".equals(request.method);
            boolean keepAlive = request.keepAlive();
            eventLoop.executeOnLoop(() -> connection.onResponseReady(result, headOnly, keepAlive, request));
            long durationMicros = (System.nanoTime() - start) / 1000;
            Logger.access(request.remoteIp, request.method, Util.inferHostWithoutPort(request.host()), request.path, result.response.status,
                    result.response.body == null ? 0 : result.response.body.length, durationMicros);
        });
    }

    public ServerResult processRequest(HttpRequest request) {
        try {
            if (!accessControl.isAllowed(request.remoteIp)) {
                return ServerResult.response(HttpResponse.text(403, "Forbidden"));
            }
            if (!rateLimiter.allow(request.remoteIp)) {
                return ServerResult.response(HttpResponse.text(429, "Too Many Requests").header("Retry-After", "1"));
            }

            Config.HostConfig host = config.resolveHost(request.host());
            RequestContext context = new RequestContext(config, host, request.remoteIp, request.secure, this);

            if (!accessControl.basicAuthMatches(request, host)) {
                return ServerResult.response(HttpResponse.text(401, "Unauthorized")
                        .header("WWW-Authenticate", "Basic realm=\"JavaWebServer\""));
            }

            if (request.isHttp2WebSocketConnect()) {
                if (!isWebSocketPath(host, request.path)) {
                    return ServerResult.response(HttpResponse.text(404, "WebSocket endpoint not found"));
                }
                HttpResponse accepted = HttpResponse.of(200, HttpResponse.statusReason(200));
                return ServerResult.websocket(accepted, defaultWebSocketHandler);
            }

            if (request.isWebSocketUpgrade()) {
                if (!isWebSocketPath(host, request.path)) {
                    return ServerResult.response(HttpResponse.text(404, "WebSocket endpoint not found"));
                }
                String key = request.header("sec-websocket-key");
                if (key == null) {
                    return ServerResult.response(HttpResponse.text(400, "Missing Sec-WebSocket-Key"));
                }
                HttpResponse handshake = HttpResponse.of(101, HttpResponse.statusReason(101))
                        .header("Upgrade", "websocket")
                        .header("Connection", "Upgrade")
                        .header("Sec-WebSocket-Accept", webSocketAccept(key));
                return ServerResult.websocket(handshake, defaultWebSocketHandler);
            }

            HttpResponse response = router.route(request, context);
            if (response == null) {
                if (host.proxyPass != null && !host.proxyPass.isBlank()) {
                    response = reverseProxyHandler.handle(request, context, host.proxyPass);
                } else {
                    response = staticFileHandler.handle(request, context);
                }
            }

            applyCommonTransforms(request, response);
            return ServerResult.response(response);
        } catch (Exception e) {
            Logger.error("Request handling failed for " + request.path, e);
            return ServerResult.response(HttpResponse.text(500, "Internal Server Error"));
        }
    }

    private void applyCommonTransforms(HttpRequest request, HttpResponse response) throws Exception {
        if (!response.headers.containsKey("Content-Type") && response.body != null && response.body.length > 0) {
            response.header("Content-Type", "application/octet-stream");
        }

        if (request.secure) {
            response.header("Strict-Transport-Security", "max-age=31536000");
        }

        String acceptEncoding = Optional.ofNullable(request.header("accept-encoding")).orElse("");
        String contentType = response.headers.get("Content-Type");

        if (!response.chunked
                && response.body != null
                && response.body.length >= config.compressionMinBytes
                && Util.isCompressibleContentType(contentType)
                && !response.headers.containsKey("Content-Encoding")) {
            if (acceptEncoding.contains("gzip")) {
                response.body = Util.gzip(response.body);
                response.header("Content-Encoding", "gzip");
                response.header("Vary", "Accept-Encoding");
            }
        }

        if ("HEAD".equals(request.method)) {
            response.body = new byte[0];
            response.chunked = false;
            response.chunks.clear();
        } else if (!response.chunked && response.body != null && response.body.length >= config.chunkedThresholdBytes) {
            response.asChunked(16 * 1024);
        }

        if (!request.keepAlive() && !response.chunked) {
            response.closeConnection();
        }
    }

    private boolean isWebSocketPath(Config.HostConfig host, String path) {
        for (String wsPath : host.websocketPaths) {
            if (path.equals(wsPath)) return true;
        }
        return false;
    }

    private String webSocketAccept(String key) throws Exception {
        String combined = key.trim() + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        byte[] sha1 = MessageDigest.getInstance("SHA-1").digest(combined.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sha1);
    }
}


