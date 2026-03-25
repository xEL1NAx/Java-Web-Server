package server;

import java.util.LinkedHashMap;
import java.util.Map;

public final class StaticFileCache {
    private final long maxBytes;
    private final int maxEntries;
    private final LinkedHashMap<String, Entry> map = new LinkedHashMap<>(16, 0.75f, true);
    private long totalBytes = 0;

    public StaticFileCache(long maxBytes, int maxEntries) {
        this.maxBytes = maxBytes;
        this.maxEntries = maxEntries;
    }

    public synchronized Entry get(String key) {
        return map.get(key);
    }

    public synchronized void put(String key, Entry entry) {
        Entry old = map.put(key, entry);
        if (old != null) totalBytes -= old.size();
        totalBytes += entry.size();
        evictIfNeeded();
    }

    private void evictIfNeeded() {
        while (totalBytes > maxBytes || map.size() > maxEntries) {
            Map.Entry<String, Entry> eldest = map.entrySet().iterator().next();
            totalBytes -= eldest.getValue().size();
            map.remove(eldest.getKey());
        }
    }

    public static final class Entry {
        public final byte[] body;
        public final byte[] gzipBody;
        public final byte[] brotliBody;
        public final String contentType;
        public final long lastModified;
        public final String etag;
        public final boolean directory;

        public Entry(byte[] body, byte[] gzipBody, byte[] brotliBody, String contentType, long lastModified, String etag, boolean directory) {
            this.body = body;
            this.gzipBody = gzipBody;
            this.brotliBody = brotliBody;
            this.contentType = contentType;
            this.lastModified = lastModified;
            this.etag = etag;
            this.directory = directory;
        }

        long size() {
            return (body == null ? 0 : body.length)
                    + (gzipBody == null ? 0 : gzipBody.length)
                    + (brotliBody == null ? 0 : brotliBody.length);
        }
    }
}
