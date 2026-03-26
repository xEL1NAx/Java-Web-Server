package server;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class PhpCgiHandler {
    public HttpResponse handle(HttpRequest request, RequestContext context, Path documentRoot, Path scriptFile, String scriptName) {
        Config.HostConfig host = context.host;
        ProcessBuilder builder = new ProcessBuilder(host.phpCgiPath);
        Path parent = scriptFile.getParent();
        if (parent != null) {
            builder.directory(parent.toFile());
        }
        builder.redirectErrorStream(true);

        Map<String, String> env = builder.environment();
        populateCgiEnvironment(env, request, context, documentRoot, scriptFile, scriptName);
        if (host.phpIniPath != null && !host.phpIniPath.isBlank()) {
            env.put("PHPRC", host.phpIniPath);
        }

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            Logger.error("Failed to start PHP CGI executable: " + host.phpCgiPath, e);
            return HttpResponse.text(502, "PHP CGI is not available");
        }

        try (OutputStream stdin = process.getOutputStream()) {
            if (request.body.length > 0) {
                stdin.write(request.body);
            }
        } catch (IOException e) {
            process.destroyForcibly();
            Logger.error("Failed to write request body to PHP CGI", e);
            return HttpResponse.text(502, "Failed to send request body to PHP CGI");
        }

        AtomicReference<byte[]> rawOutputRef = new AtomicReference<>(new byte[0]);
        AtomicReference<Throwable> outputErrorRef = new AtomicReference<>();

        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                rawOutputRef.set(Util.readAll(process.getInputStream(), host.phpCgiMaxOutputBytes));
            } catch (Throwable t) {
                outputErrorRef.set(t);
            }
        });

        boolean finished;
        try {
            finished = process.waitFor(host.phpCgiTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            joinQuietly(reader, 250);
            return HttpResponse.text(500, "Interrupted while executing PHP CGI");
        }

        if (!finished) {
            process.destroyForcibly();
            joinQuietly(reader, 250);
            return HttpResponse.text(504, "PHP script execution timed out");
        }

        joinQuietly(reader, 1000);

        Throwable outputError = outputErrorRef.get();
        if (outputError != null) {
            Logger.error("Failed to read PHP CGI output", outputError);
            return HttpResponse.text(502, "Failed to read PHP CGI output");
        }

        byte[] rawOutput = rawOutputRef.get();
        if (rawOutput.length == 0) {
            Logger.warn("PHP CGI produced an empty response for " + scriptName);
            return HttpResponse.text(502, "Empty response from PHP CGI");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            Logger.warn("PHP CGI exited with code " + exitCode + " for " + scriptName);
        }

        try {
            return parseCgiResponse(rawOutput);
        } catch (Exception e) {
            Logger.error("Invalid CGI response from PHP script " + scriptName, e);
            return HttpResponse.text(502, "Invalid response from PHP CGI");
        }
    }

    private void populateCgiEnvironment(Map<String, String> env, HttpRequest request, RequestContext context,
                                        Path documentRoot, Path scriptFile, String scriptName) {
        String host = Util.inferHostWithoutPort(request.host());
        if (host == null || host.isBlank()) {
            host = context.host.serverName;
        }
        int port = request.secure ? context.config.httpsPort : context.config.port;

        env.put("GATEWAY_INTERFACE", "CGI/1.1");
        env.put("SERVER_SOFTWARE", "JavaWebServer/1.1");
        env.put("SERVER_PROTOCOL", request.version);
        env.put("REQUEST_METHOD", request.method);
        env.put("REQUEST_URI", request.target);
        env.put("DOCUMENT_URI", request.path);
        env.put("DOCUMENT_ROOT", documentRoot.toString());
        env.put("SCRIPT_NAME", scriptName);
        env.put("SCRIPT_FILENAME", scriptFile.toString());
        env.put("QUERY_STRING", queryString(request.target));
        env.put("REMOTE_ADDR", request.remoteIp == null ? "" : request.remoteIp);
        env.put("SERVER_NAME", host);
        env.put("SERVER_PORT", String.valueOf(port));
        env.put("REQUEST_SCHEME", request.secure ? "https" : "http");
        env.put("HTTPS", request.secure ? "on" : "off");
        env.put("REDIRECT_STATUS", "200");

        String contentType = request.header("content-type");
        if (contentType != null) {
            env.put("CONTENT_TYPE", contentType);
        } else {
            env.remove("CONTENT_TYPE");
        }

        if (request.body.length > 0) {
            env.put("CONTENT_LENGTH", String.valueOf(request.body.length));
        } else {
            env.remove("CONTENT_LENGTH");
        }

        for (Map.Entry<String, List<String>> header : request.headers.entrySet()) {
            String name = header.getKey();
            if (name == null || name.isBlank() || name.startsWith(":")) {
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if ("content-type".equals(lower) || "content-length".equals(lower)) {
                continue;
            }
            String value = String.join(", ", header.getValue());
            env.put("HTTP_" + lower.toUpperCase(Locale.ROOT).replace('-', '_'), value);
        }
    }

    private HttpResponse parseCgiResponse(byte[] rawOutput) throws IOException {
        int bodyStart = findBodyStart(rawOutput);
        if (bodyStart < 0) {
            throw new IOException("CGI output is missing header/body separator");
        }

        String headerText = new String(rawOutput, 0, bodyStart, StandardCharsets.ISO_8859_1);
        String[] lines = headerText.split("\\r?\\n");

        int status = 200;
        Map<String, String> headers = new LinkedHashMap<>();
        boolean hasContentType = false;

        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (name.equalsIgnoreCase("status")) {
                String[] parts = value.split("\\s+", 2);
                try {
                    status = Integer.parseInt(parts[0]);
                } catch (Exception ignored) {
                    status = 200;
                }
                continue;
            }
            if (name.equalsIgnoreCase("content-length")) {
                continue;
            }
            if (name.equalsIgnoreCase("content-type")) {
                hasContentType = true;
            }
            headers.put(name, value);
            if (name.equalsIgnoreCase("location") && status == 200) {
                status = 302;
            }
        }

        byte[] body = new byte[Math.max(0, rawOutput.length - bodyStart)];
        if (body.length > 0) {
            System.arraycopy(rawOutput, bodyStart, body, 0, body.length);
        }

        HttpResponse response = HttpResponse.of(status, HttpResponse.statusReason(status));
        response.body = body;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            response.header(e.getKey(), e.getValue());
        }
        if (!hasContentType) {
            response.header("Content-Type", "text/html; charset=utf-8");
        }
        return response;
    }

    private static int findBodyStart(byte[] bytes) {
        int idx = indexOf(bytes, new byte[] {'\r', '\n', '\r', '\n'});
        if (idx >= 0) {
            return idx + 4;
        }
        idx = indexOf(bytes, new byte[] {'\n', '\n'});
        return idx >= 0 ? idx + 2 : -1;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return -1;
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static String queryString(String target) {
        int idx = target.indexOf('?');
        return idx >= 0 && idx + 1 < target.length() ? target.substring(idx + 1) : "";
    }

    private static void joinQuietly(Thread thread, long millis) {
        try {
            thread.join(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
