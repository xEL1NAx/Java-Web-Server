package server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.GZIPOutputStream;

public final class Util {
    private static final DateTimeFormatter RFC_1123 = DateTimeFormatter.RFC_1123_DATE_TIME;

    private Util() {}

    public static String rfc1123(long millis) {
        return RFC_1123.format(ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), java.time.ZoneOffset.UTC));
    }

    public static Map<String, List<String>> parseQuery(String target) {
        int idx = target.indexOf('?');
        if (idx < 0 || idx == target.length() - 1) return Map.of();
        String q = target.substring(idx + 1);
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (String pair : q.split("&")) {
            if (pair.isEmpty()) continue;
            String[] parts = pair.split("=", 2);
            String key = urlDecode(parts[0]);
            String value = parts.length > 1 ? urlDecode(parts[1]) : "";
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        return map;
    }

    public static String pathOnly(String target) {
        int idx = target.indexOf('?');
        return idx >= 0 ? target.substring(0, idx) : target;
    }

    public static String urlDecode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    public static byte[] gzip(byte[] input) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(input);
        }
        return bos.toByteArray();
    }

    public static byte[] readAll(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new IOException("Body exceeds limit");
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    public static boolean headerContainsToken(String value, String token) {
        if (value == null) return false;
        for (String part : value.split(",")) {
            if (part.trim().equalsIgnoreCase(token)) return true;
        }
        return false;
    }

    public static String normalizeHeaderName(String s) {
        StringBuilder out = new StringBuilder();
        boolean up = true;
        for (char c : s.toCharArray()) {
            if (c == '-') {
                out.append(c);
                up = true;
            } else {
                out.append(up ? Character.toUpperCase(c) : Character.toLowerCase(c));
                up = false;
            }
        }
        return out.toString();
    }

    public static String inferHostWithoutPort(String hostHeader) {
        if (hostHeader == null) return null;
        int idx = hostHeader.indexOf(':');
        return idx >= 0 ? hostHeader.substring(0, idx) : hostHeader;
    }

    public static boolean isCompressibleContentType(String ct) {
        if (ct == null) return false;
        ct = ct.toLowerCase(Locale.ROOT);
        return ct.startsWith("text/") || ct.contains("json") || ct.contains("javascript")
                || ct.contains("xml") || ct.contains("svg");
    }

    public static String guessMime(String filename) {
        String f = filename.toLowerCase(Locale.ROOT);
        if (f.endsWith(".html") || f.endsWith(".htm")) return "text/html; charset=utf-8";
        if (f.endsWith(".css")) return "text/css; charset=utf-8";
        if (f.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (f.endsWith(".json")) return "application/json; charset=utf-8";
        if (f.endsWith(".txt") || f.endsWith(".log")) return "text/plain; charset=utf-8";
        if (f.endsWith(".svg")) return "image/svg+xml";
        if (f.endsWith(".png")) return "image/png";
        if (f.endsWith(".jpg") || f.endsWith(".jpeg")) return "image/jpeg";
        if (f.endsWith(".gif")) return "image/gif";
        if (f.endsWith(".webp")) return "image/webp";
        if (f.endsWith(".ico")) return "image/x-icon";
        if (f.endsWith(".pdf")) return "application/pdf";
        if (f.endsWith(".wasm")) return "application/wasm";
        return "application/octet-stream";
    }

    public static String sanitizeWebPath(String path) {
        String p = path == null || path.isBlank() ? "/" : path;
        p = p.replace('\\', '/');
        if (!p.startsWith("/")) p = "/" + p;
        return p;
    }

    public static boolean isLoopbackOrLocalhost(String host) {
        if (host == null) return false;
        if ("localhost".equalsIgnoreCase(host)) return true;
        try {
            InetAddress addr = InetAddress.getByName(host);
            return addr.isLoopbackAddress() || addr.isAnyLocalAddress();
        } catch (Exception e) {
            return false;
        }
    }

    public static String combineUri(String base, String targetPathWithQuery) {
        String path = targetPathWithQuery.startsWith("/") ? targetPathWithQuery : "/" + targetPathWithQuery;
        if (base.endsWith("/")) return base.substring(0, base.length() - 1) + path;
        return base + path;
    }

    public static URI safeUri(String s) {
        return URI.create(s);
    }
}
