package server;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class HttpParser {
    private HttpParser() {}

    public static ParseResult tryParse(byte[] input, int length, int headerLimit, int maxBody, String remoteIp, boolean secure) {
        if (length == 0) return ParseResult.needMore();
        String http2Preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";
        if (length >= http2Preface.length()) {
            String start = new String(input, 0, http2Preface.length(), StandardCharsets.US_ASCII);
            if (http2Preface.equals(start)) {
                return ParseResult.error(426, "HTTP/2 is available on the HTTPS listener via ALPN ('h2').");
            }
        }

        int headerEnd = indexOf(input, length, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        if (headerEnd < 0) {
            if (length > headerLimit) return ParseResult.error(400, "Request headers too large");
            return ParseResult.needMore();
        }
        int headerBytes = headerEnd + 4;
        String headerText = new String(input, 0, headerEnd, StandardCharsets.UTF_8);
        String[] lines = headerText.split("\r\n");
        if (lines.length == 0) return ParseResult.error(400, "Empty request");
        String[] requestLine = lines[0].split(" ");
        if (requestLine.length != 3) return ParseResult.error(400, "Malformed request line");
        String method = requestLine[0].trim().toUpperCase(Locale.ROOT);
        String target = requestLine[1].trim();
        String version = requestLine[2].trim();
        if (!version.equals("HTTP/1.1") && !version.equals("HTTP/1.0")) {
            return ParseResult.error(505, "Only HTTP/1.1 and HTTP/1.0 are supported");
        }

        Map<String, List<String>> headers = new LinkedHashMap<>();
        String lastHeader = null;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if ((line.startsWith(" ") || line.startsWith("\t")) && lastHeader != null) {
                List<String> vals = headers.get(lastHeader);
                vals.set(vals.size() - 1, vals.get(vals.size() - 1) + " " + line.trim());
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) return ParseResult.error(400, "Malformed header");
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            lastHeader = name;
        }

        if (target.length() > 8192) return ParseResult.error(414, "URI too long");

        String te = first(headers, "transfer-encoding");
        String cl = first(headers, "content-length");
        byte[] body = new byte[0];
        int consumed = headerBytes;

        if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
            ChunkedBody chunk = parseChunkedBody(input, headerBytes, length, maxBody);
            if (chunk == null) return ParseResult.needMore();
            body = chunk.body;
            consumed = chunk.consumedBytes;
        } else if (cl != null) {
            int bodyLen;
            try {
                bodyLen = Integer.parseInt(cl.trim());
            } catch (NumberFormatException e) {
                return ParseResult.error(400, "Invalid Content-Length");
            }
            if (bodyLen < 0 || bodyLen > maxBody) return ParseResult.error(413, "Payload too large");
            if (length < headerBytes + bodyLen) return ParseResult.needMore();
            body = Arrays.copyOfRange(input, headerBytes, headerBytes + bodyLen);
            consumed = headerBytes + bodyLen;
        }

        HttpRequest request = new HttpRequest(method, target, version, headers, body, remoteIp, secure, System.nanoTime());
        return ParseResult.request(request, consumed);
    }

    private static String first(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static ChunkedBody parseChunkedBody(byte[] input, int start, int length, int maxBody) {
        int pos = start;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        while (true) {
            int lineEnd = indexOf(input, length, "\r\n".getBytes(StandardCharsets.US_ASCII), pos);
            if (lineEnd < 0) return null;
            String line = new String(input, pos, lineEnd - pos, StandardCharsets.US_ASCII).trim();
            int semicolon = line.indexOf(';');
            if (semicolon >= 0) line = line.substring(0, semicolon);
            int chunkSize;
            try {
                chunkSize = Integer.parseInt(line.trim(), 16);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid chunk size");
            }
            pos = lineEnd + 2;
            if (chunkSize == 0) {
                int trailerEnd = indexOf(input, length, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII), pos);
                if (trailerEnd < 0) {
                    if (pos + 2 <= length && input[pos] == '\r' && input[pos + 1] == '\n') {
                        return new ChunkedBody(bos.toByteArray(), pos + 2);
                    }
                    return null;
                }
                return new ChunkedBody(bos.toByteArray(), trailerEnd + 4);
            }
            if (chunkSize < 0) throw new IllegalArgumentException("Negative chunk size");
            if (bos.size() + chunkSize > maxBody) throw new IllegalArgumentException("Payload too large");
            if (pos + chunkSize + 2 > length) return null;
            bos.write(input, pos, chunkSize);
            pos += chunkSize;
            if (input[pos] != '\r' || input[pos + 1] != '\n') {
                throw new IllegalArgumentException("Chunk missing CRLF");
            }
            pos += 2;
        }
    }

    private static int indexOf(byte[] haystack, int haystackLen, byte[] needle) {
        return indexOf(haystack, haystackLen, needle, 0);
    }

    private static int indexOf(byte[] haystack, int haystackLen, byte[] needle, int start) {
        outer:
        for (int i = Math.max(0, start); i <= haystackLen - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static final class ChunkedBody {
        final byte[] body;
        final int consumedBytes;

        ChunkedBody(byte[] body, int consumedBytes) {
            this.body = body;
            this.consumedBytes = consumedBytes;
        }
    }

    public static final class ParseResult {
        public final HttpRequest request;
        public final int consumedBytes;
        public final boolean needMore;
        public final Integer errorStatus;
        public final String errorMessage;

        private ParseResult(HttpRequest request, int consumedBytes, boolean needMore, Integer errorStatus, String errorMessage) {
            this.request = request;
            this.consumedBytes = consumedBytes;
            this.needMore = needMore;
            this.errorStatus = errorStatus;
            this.errorMessage = errorMessage;
        }

        public static ParseResult request(HttpRequest request, int consumedBytes) {
            return new ParseResult(request, consumedBytes, false, null, null);
        }

        public static ParseResult needMore() {
            return new ParseResult(null, 0, true, null, null);
        }

        public static ParseResult error(int status, String message) {
            return new ParseResult(null, 0, false, status, message);
        }
    }
}
