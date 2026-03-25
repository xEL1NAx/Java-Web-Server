package server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RateLimiter {
    private final boolean enabled;
    private final int capacity;
    private final int refillTokens;
    private final long refillIntervalMillis;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(Config.RateLimitConfig cfg) {
        this.enabled = cfg.enabled;
        this.capacity = Math.max(1, cfg.capacity);
        this.refillTokens = Math.max(1, cfg.refillTokens);
        this.refillIntervalMillis = Math.max(1L, cfg.refillIntervalMillis);
    }

    public boolean allow(String key) {
        if (!enabled) return true;
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity, System.currentTimeMillis()));
        synchronized (b) {
            long now = System.currentTimeMillis();
            long elapsed = now - b.lastRefill;
            if (elapsed >= refillIntervalMillis) {
                long steps = elapsed / refillIntervalMillis;
                long add = steps * refillTokens;
                b.tokens = (int) Math.min(capacity, b.tokens + add);
                b.lastRefill = now;
            }
            if (b.tokens <= 0) return false;
            b.tokens--;
            return true;
        }
    }

    private static final class Bucket {
        int tokens;
        long lastRefill;

        Bucket(int tokens, long lastRefill) {
            this.tokens = tokens;
            this.lastRefill = lastRefill;
        }
    }
}
