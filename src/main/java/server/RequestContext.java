package server;

public final class RequestContext {
    public final Config config;
    public final Config.HostConfig host;
    public final String remoteIp;
    public final boolean secure;
    public final HttpServer server;

    public RequestContext(Config config, Config.HostConfig host, String remoteIp, boolean secure, HttpServer server) {
        this.config = config;
        this.host = host;
        this.remoteIp = remoteIp;
        this.secure = secure;
        this.server = server;
    }
}
