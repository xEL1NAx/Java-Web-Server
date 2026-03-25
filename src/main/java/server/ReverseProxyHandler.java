package server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.*;

public final class ReverseProxyHandler {
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "transfer-encoding", "upgrade", "host"
    );

    private final HttpClient client;
    private final Config config;

    public ReverseProxyHandler(Config config) {
        this.config = config;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public HttpResponse handle(HttpRequest request, RequestContext context, String upstreamBase) {
        try {
            String upstream = Util.combineUri(upstreamBase, request.target);
            java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(URI.create(upstream))
                    .timeout(Duration.ofSeconds(30));

            for (Map.Entry<String, List<String>> e : request.headers.entrySet()) {
                if (HOP_BY_HOP.contains(e.getKey())) continue;
                for (String value : e.getValue()) {
                    builder.header(Util.normalizeHeaderName(e.getKey()), value);
                }
            }

            builder.header("X-Forwarded-For", request.remoteIp);
            builder.header("X-Forwarded-Proto", request.secure ? "https" : "http");
            builder.header("X-Forwarded-Host", Optional.ofNullable(request.host()).orElse(""));
            builder.method(request.method, request.body.length == 0 ? BodyPublishers.noBody() : BodyPublishers.ofByteArray(request.body));

            java.net.http.HttpResponse<byte[]> upstreamResp = client.send(builder.build(), BodyHandlers.ofByteArray());
            HttpResponse response = HttpResponse.bytes(upstreamResp.statusCode(), upstreamResp.body(),
                    upstreamResp.headers().firstValue("content-type").orElse("application/octet-stream"));

            upstreamResp.headers().map().forEach((k, vals) -> {
                if (!HOP_BY_HOP.contains(k.toLowerCase(Locale.ROOT))) {
                    if (!vals.isEmpty()) response.header(k, vals.get(0));
                }
            });

            if (response.body.length >= config.chunkedThresholdBytes) {
                response.asChunked(16 * 1024);
            }
            return response;
        } catch (Exception e) {
            Logger.error("Proxy error for " + upstreamBase + request.target, e);
            return HttpResponse.text(502, "Bad Gateway");
        }
    }
}
