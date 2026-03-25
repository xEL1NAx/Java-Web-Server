package server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class Config {
    public String bindAddress = "0.0.0.0";
    public int port = 8080;
    public boolean httpsEnabled = false;
    public int httpsPort = 8443;
    public int workerThreads = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
    public int selectorTimeoutMillis = 500;
    public int keepAliveTimeoutMillis = 15_000;
    public int connectionIdleTimeoutMillis = 30_000;
    public int requestHeaderLimitBytes = 32 * 1024;
    public int maxBodySizeBytes = 4 * 1024 * 1024;
    public int readBufferSize = 16 * 1024;
    public int chunkedThresholdBytes = 128 * 1024;
    public int compressionMinBytes = 512;
    public long staticCacheMaxBytes = 32L * 1024 * 1024;
    public int staticCacheMaxEntries = 256;
    public String accessLogPath = "logs/access.log";
    public String errorLogPath = "logs/error.log";
    public TlsConfig tls = new TlsConfig();
    public RateLimitConfig rateLimit = new RateLimitConfig();
    public AccessConfig access = new AccessConfig();
    public List<HostConfig> hosts = new ArrayList<>();

    public static final class TlsConfig {
        public String keystorePath = "keystore.p12";
        public String keystorePassword = "changeit";
        public String keyPassword = "changeit";
        public String keystoreType = "PKCS12";
    }

    public static final class RateLimitConfig {
        public boolean enabled = true;
        public int capacity = 50;
        public int refillTokens = 50;
        public long refillIntervalMillis = 1000;
    }

    public static final class AccessConfig {
        public List<String> allowCidrs = new ArrayList<>();
        public List<String> denyCidrs = new ArrayList<>();
    }

    public static final class HostConfig {
        public String serverName = "localhost";
        public List<String> aliases = new ArrayList<>();
        public String root = "src/main/resources/www";
        public boolean directoryListing = false;
        public List<String> indexFiles = List.of("index.html", "index.htm");
        public String proxyPass = null;
        public boolean trustXForwardedFor = false;
        public String basicAuthUser = null;
        public String basicAuthPassword = null;
        public List<String> websocketPaths = new ArrayList<>();
        public Map<String, List<String>> http2Push = new LinkedHashMap<>();
        public List<RouteConfig> routes = new ArrayList<>();

        public boolean matches(String host) {
            if (host == null) return false;
            if (serverName.equalsIgnoreCase(host)) return true;
            for (String alias : aliases) {
                if (alias.equalsIgnoreCase(host)) return true;
            }
            return false;
        }
    }

    public static final class RouteConfig {
        public String method = "GET";
        public String path = "/";
        public String type = "literal"; // literal, file, proxy, redirect
        public String match = "exact"; // exact, prefix
        public String responseText = "";
        public String contentType = "text/plain; charset=utf-8";
        public String filePath = null;
        public String upstream = null;
        public String location = null;
        public int status = 200;
        public String requiredRole = null;
    }

    public static Config load(Path path) throws IOException {
        String text = Files.readString(path);
        Object rootObj = SimpleJson.parse(text);
        if (!(rootObj instanceof Map<?, ?> mapRaw)) {
            throw new IllegalArgumentException("Config must be a JSON object");
        }
        Map<String, Object> root = castMap(mapRaw);
        Config cfg = new Config();
        Path baseDir = path.toAbsolutePath().getParent();
        cfg.bindAddress = str(root, "bindAddress", cfg.bindAddress);
        cfg.port = integer(root, "port", cfg.port);
        cfg.httpsEnabled = bool(root, "httpsEnabled", cfg.httpsEnabled);
        cfg.httpsPort = integer(root, "httpsPort", cfg.httpsPort);
        cfg.workerThreads = integer(root, "workerThreads", cfg.workerThreads);
        cfg.selectorTimeoutMillis = integer(root, "selectorTimeoutMillis", cfg.selectorTimeoutMillis);
        cfg.keepAliveTimeoutMillis = integer(root, "keepAliveTimeoutMillis", cfg.keepAliveTimeoutMillis);
        cfg.connectionIdleTimeoutMillis = integer(root, "connectionIdleTimeoutMillis", cfg.connectionIdleTimeoutMillis);
        cfg.requestHeaderLimitBytes = integer(root, "requestHeaderLimitBytes", cfg.requestHeaderLimitBytes);
        cfg.maxBodySizeBytes = integer(root, "maxBodySizeBytes", cfg.maxBodySizeBytes);
        cfg.readBufferSize = integer(root, "readBufferSize", cfg.readBufferSize);
        cfg.chunkedThresholdBytes = integer(root, "chunkedThresholdBytes", cfg.chunkedThresholdBytes);
        cfg.compressionMinBytes = integer(root, "compressionMinBytes", cfg.compressionMinBytes);
        cfg.staticCacheMaxBytes = longValue(root, "staticCacheMaxBytes", cfg.staticCacheMaxBytes);
        cfg.staticCacheMaxEntries = integer(root, "staticCacheMaxEntries", cfg.staticCacheMaxEntries);
        cfg.accessLogPath = resolvePath(baseDir, str(root, "accessLogPath", cfg.accessLogPath));
        cfg.errorLogPath = resolvePath(baseDir, str(root, "errorLogPath", cfg.errorLogPath));

        if (root.get("tls") instanceof Map<?, ?> tlsMap) {
            Map<String, Object> t = castMap(tlsMap);
            cfg.tls.keystorePath = resolvePath(baseDir, str(t, "keystorePath", cfg.tls.keystorePath));
            cfg.tls.keystorePassword = str(t, "keystorePassword", cfg.tls.keystorePassword);
            cfg.tls.keyPassword = str(t, "keyPassword", cfg.tls.keyPassword);
            cfg.tls.keystoreType = str(t, "keystoreType", cfg.tls.keystoreType);
        }

        if (root.get("rateLimit") instanceof Map<?, ?> rlMap) {
            Map<String, Object> r = castMap(rlMap);
            cfg.rateLimit.enabled = bool(r, "enabled", cfg.rateLimit.enabled);
            cfg.rateLimit.capacity = integer(r, "capacity", cfg.rateLimit.capacity);
            cfg.rateLimit.refillTokens = integer(r, "refillTokens", cfg.rateLimit.refillTokens);
            cfg.rateLimit.refillIntervalMillis = longValue(r, "refillIntervalMillis", cfg.rateLimit.refillIntervalMillis);
        }

        if (root.get("access") instanceof Map<?, ?> accessMap) {
            Map<String, Object> a = castMap(accessMap);
            cfg.access.allowCidrs = stringList(a.get("allowCidrs"));
            cfg.access.denyCidrs = stringList(a.get("denyCidrs"));
        }

        if (root.get("hosts") instanceof List<?> hostsList) {
            cfg.hosts.clear();
            for (Object hostObj : hostsList) {
                if (!(hostObj instanceof Map<?, ?> hostMapRaw)) continue;
                Map<String, Object> hostMap = castMap(hostMapRaw);
                HostConfig host = new HostConfig();
                host.serverName = str(hostMap, "serverName", host.serverName);
                host.aliases = stringList(hostMap.get("aliases"));
                host.root = resolvePath(baseDir, str(hostMap, "root", host.root));
                host.directoryListing = bool(hostMap, "directoryListing", host.directoryListing);
                List<String> idx = stringList(hostMap.get("indexFiles"));
                if (!idx.isEmpty()) host.indexFiles = idx;
                host.proxyPass = nullableString(hostMap.get("proxyPass"));
                host.trustXForwardedFor = bool(hostMap, "trustXForwardedFor", host.trustXForwardedFor);
                host.basicAuthUser = nullableString(hostMap.get("basicAuthUser"));
                host.basicAuthPassword = nullableString(hostMap.get("basicAuthPassword"));
                host.websocketPaths = stringList(hostMap.get("websocketPaths"));
                host.http2Push = pathMap(hostMap.get("http2Push"));

                if (hostMap.get("routes") instanceof List<?> routeList) {
                    for (Object routeObj : routeList) {
                        if (!(routeObj instanceof Map<?, ?> routeMapRaw)) continue;
                        Map<String, Object> routeMap = castMap(routeMapRaw);
                        RouteConfig route = new RouteConfig();
                        route.method = str(routeMap, "method", route.method).toUpperCase(Locale.ROOT);
                        route.path = str(routeMap, "path", route.path);
                        route.type = str(routeMap, "type", route.type);
                        route.match = str(routeMap, "match", route.match);
                        route.responseText = str(routeMap, "responseText", route.responseText);
                        route.contentType = str(routeMap, "contentType", route.contentType);
                        route.filePath = nullableResolvedPath(baseDir, routeMap.get("filePath"));
                        route.upstream = nullableString(routeMap.get("upstream"));
                        route.location = nullableString(routeMap.get("location"));
                        route.status = integer(routeMap, "status", route.status);
                        route.requiredRole = nullableString(routeMap.get("requiredRole"));
                        host.routes.add(route);
                    }
                }
                cfg.hosts.add(host);
            }
        }

        if (cfg.hosts.isEmpty()) {
            cfg.hosts.add(new HostConfig());
        }
        return cfg;
    }

    public HostConfig resolveHost(String hostHeader) {
        String host = Util.inferHostWithoutPort(hostHeader);
        if (host != null) {
            for (HostConfig cfg : hosts) {
                if (cfg.matches(host)) return cfg;
            }
        }
        return hosts.get(0);
    }

    private static String nullableString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Map<String, Object> castMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static String str(Map<String, Object> map, String key, String defaultValue) {
        Object v = map.get(key);
        return v == null ? defaultValue : String.valueOf(v);
    }

    private static int integer(Map<String, Object> map, String key, int defaultValue) {
        Object v = map.get(key);
        return v == null ? defaultValue : ((Number) v).intValue();
    }

    private static long longValue(Map<String, Object> map, String key, long defaultValue) {
        Object v = map.get(key);
        return v == null ? defaultValue : ((Number) v).longValue();
    }

    private static boolean bool(Map<String, Object> map, String key, boolean defaultValue) {
        Object v = map.get(key);
        return v == null ? defaultValue : (Boolean) v;
    }


    private static String resolvePath(Path baseDir, String candidate) {
        Path p = Path.of(candidate);
        if (p.isAbsolute()) return p.toString();
        return baseDir.resolve(candidate).normalize().toString();
    }

    private static String nullableResolvedPath(Path baseDir, Object value) {
        return value == null ? null : resolvePath(baseDir, String.valueOf(value));
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (Object item : list) out.add(String.valueOf(item));
        return out;
    }

    private static Map<String, List<String>> pathMap(Object value) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?> raw)) {
            return out;
        }
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String key = Util.sanitizeWebPath(String.valueOf(entry.getKey()));
            List<String> vals = stringList(entry.getValue());
            List<String> normalized = new ArrayList<>();
            for (String path : vals) {
                normalized.add(Util.sanitizeWebPath(path));
            }
            out.put(key, normalized);
        }
        return out;
    }
}

