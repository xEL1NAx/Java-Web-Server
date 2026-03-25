package server;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class AccessControl {
    private final CidrMatcher allow;
    private final CidrMatcher deny;

    public AccessControl(Config.AccessConfig config) {
        this.allow = new CidrMatcher(config.allowCidrs);
        this.deny = new CidrMatcher(config.denyCidrs);
    }

    public boolean isAllowed(String ip) {
        if (deny.matches(ip)) return false;
        if (!hasAllowRules()) return true;
        return allow.matches(ip);
    }

    public boolean hasAllowRules() {
        return !allow.isEmpty();
    }

    public boolean basicAuthMatches(HttpRequest request, Config.HostConfig host) {
        if (host.basicAuthUser == null || host.basicAuthPassword == null) return true;
        String auth = request.header("authorization");
        if (auth == null || !auth.startsWith("Basic ")) return false;
        try {
            String decoded = new String(Base64.getDecoder().decode(auth.substring(6)), StandardCharsets.UTF_8);
            String expected = host.basicAuthUser + ":" + host.basicAuthPassword;
            return expected.equals(decoded);
        } catch (Exception e) {
            return false;
        }
    }
}
