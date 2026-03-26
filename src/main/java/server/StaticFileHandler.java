package server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

public final class StaticFileHandler {
    private final StaticFileCache cache;
    private final Config config;
    private final PhpCgiHandler phpCgiHandler;

    public StaticFileHandler(Config config) {
        this.config = config;
        this.cache = new StaticFileCache(config.staticCacheMaxBytes, config.staticCacheMaxEntries);
        this.phpCgiHandler = new PhpCgiHandler();
    }

    public HttpResponse handle(HttpRequest request, RequestContext context) throws Exception {
        boolean staticMethod = request.method.equals("GET") || request.method.equals("HEAD");

        Path root = Path.of(context.host.root).normalize().toAbsolutePath();
        String requestPath = Util.sanitizeWebPath(request.path);
        Path resolved = root.resolve(requestPath.substring(1)).normalize();

        if (!resolved.startsWith(root)) {
            return HttpResponse.text(403, "Forbidden");
        }

        if (Files.isDirectory(resolved)) {
            for (String index : context.host.indexFiles) {
                Path candidate = resolved.resolve(index);
                if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                    resolved = candidate;
                    break;
                }
            }
            if (Files.isDirectory(resolved)) {
                if (!staticMethod) {
                    return HttpResponse.text(405, "Method Not Allowed").header("Allow", "GET, HEAD");
                }
                if (!context.host.directoryListing) return HttpResponse.text(403, "Directory listing is disabled");
                return directoryListing(requestPath, resolved, request.method.equals("HEAD"));
            }
        }

        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            return HttpResponse.text(404, "Not Found");
        }

        if (isPhpFile(resolved)) {
            if (!context.host.phpEnabled) {
                return HttpResponse.text(404, "Not Found");
            }
            String scriptName = toWebPath(root, resolved);
            return phpCgiHandler.handle(request, context, root, resolved, scriptName);
        }

        if (!staticMethod) {
            return HttpResponse.text(405, "Method Not Allowed").header("Allow", "GET, HEAD");
        }

        StaticFileCache.Entry entry = load(root, resolved);
        String acceptEncoding = Optional.ofNullable(request.header("accept-encoding")).orElse("");
        boolean wantsBr = acceptEncoding.contains("br") && entry.brotliBody != null;
        boolean wantsGzip = acceptEncoding.contains("gzip") && entry.gzipBody != null;
        String ifNoneMatch = request.header("if-none-match");
        String ifModifiedSince = request.header("if-modified-since");

        if ((ifNoneMatch != null && ifNoneMatch.equals(entry.etag))
                || (ifModifiedSince != null && ifModifiedSince.equals(Util.rfc1123(entry.lastModified)))) {
            return HttpResponse.of(304, HttpResponse.statusReason(304))
                    .header("ETag", entry.etag)
                    .header("Last-Modified", Util.rfc1123(entry.lastModified))
                    .header("Content-Type", entry.contentType);
        }

        byte[] body = entry.body;
        HttpResponse response = HttpResponse.bytes(200, body, entry.contentType)
                .header("ETag", entry.etag)
                .header("Last-Modified", Util.rfc1123(entry.lastModified))
                .header("Cache-Control", "public, max-age=60");

        if (wantsBr) {
            response.body = entry.brotliBody;
            response.header("Content-Encoding", "br").header("Vary", "Accept-Encoding");
        } else if (wantsGzip) {
            response.body = entry.gzipBody;
            response.header("Content-Encoding", "gzip").header("Vary", "Accept-Encoding");
        } else if (Util.isCompressibleContentType(entry.contentType) && entry.body.length >= config.compressionMinBytes) {
            response.body = Util.gzip(entry.body);
            response.header("Content-Encoding", "gzip").header("Vary", "Accept-Encoding");
        }

        if (request.method.equals("HEAD")) {
            response.body = new byte[0];
        } else if (response.body.length >= config.chunkedThresholdBytes) {
            response.asChunked(16 * 1024);
        }

        return response;
    }

    private HttpResponse directoryListing(String requestPath, Path dir, boolean headOnly) throws IOException {
        List<Path> children;
        try (var stream = Files.list(dir)) {
            children = stream.sorted().collect(Collectors.toList());
        }
        StringBuilder body = new StringBuilder();
        body.append("<html><head><title>Index of ").append(requestPath).append("</title></head><body>");
        body.append("<h1>Index of ").append(requestPath).append("</h1><ul>");
        if (!"/".equals(requestPath)) {
            String parent = requestPath.endsWith("/") ? requestPath.substring(0, requestPath.length() - 1) : requestPath;
            int slash = parent.lastIndexOf('/');
            String parentPath = slash <= 0 ? "/" : parent.substring(0, slash + 1);
            body.append("<li><a href=\"").append(parentPath).append("\">..</a></li>");
        }
        for (Path child : children) {
            String name = child.getFileName().toString();
            String href = requestPath.endsWith("/") ? requestPath + name : requestPath + "/" + name;
            if (Files.isDirectory(child)) href += "/";
            body.append("<li><a href=\"").append(href).append("\">").append(name).append(Files.isDirectory(child) ? "/" : "").append("</a></li>");
        }
        body.append("</ul></body></html>");
        HttpResponse resp = HttpResponse.html(200, body.toString());
        if (headOnly) resp.body = new byte[0];
        return resp;
    }

    private StaticFileCache.Entry load(Path root, Path file) throws Exception {
        String key = file.toString();
        StaticFileCache.Entry cached = cache.get(key);
        long lastModified = Files.getLastModifiedTime(file).toMillis();
        if (cached != null && cached.lastModified == lastModified) {
            return cached;
        }

        byte[] body = Files.readAllBytes(file);
        byte[] gzip = null;
        if (Util.isCompressibleContentType(Util.guessMime(file.getFileName().toString())) && body.length >= config.compressionMinBytes) {
            gzip = Util.gzip(body);
        }

        byte[] br = null;
        Path brFile = file.resolveSibling(file.getFileName().toString() + ".br");
        if (Files.exists(brFile) && Files.isRegularFile(brFile)) {
            br = Files.readAllBytes(brFile);
        }

        String contentType = Util.guessMime(file.getFileName().toString());
        String etag = sha1Hex(body) + "-" + lastModified;
        StaticFileCache.Entry entry = new StaticFileCache.Entry(body, gzip, br, contentType, lastModified, "\"" + etag + "\"", false);
        cache.put(key, entry);
        return entry;
    }

    private static boolean isPhpFile(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) return false;
        return fileName.toString().toLowerCase(Locale.ROOT).endsWith(".php");
    }

    private static String toWebPath(Path root, Path file) {
        String rel = root.relativize(file).toString().replace('\\', '/');
        return rel.startsWith("/") ? rel : "/" + rel;
    }

    private static String sha1Hex(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] digest = md.digest(bytes);
        StringBuilder out = new StringBuilder();
        for (byte b : digest) out.append(String.format("%02x", b));
        return out.toString();
    }
}
