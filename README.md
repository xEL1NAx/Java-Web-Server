# Java Web Server Project

This project is a from-scratch Java web server inspired by nginx/Apache-style features.

It keeps the original custom HTTP/1.1 server and adds HTTPS + HTTP/2 capabilities.

## Included Features

Implemented in this project:

- TCP listener
- HTTP/1.1 request parsing
- routing
- static file serving
- basic response generation
- keep-alive
- NIO event loop for HTTP/1.1
- worker thread pool for request handling
- access/error logging
- reverse proxying
- TLS/HTTPS listener
- ALPN negotiation (`h2`, `http/1.1`)
- HTTP/2 over TLS (`h2`)
- cleartext HTTP/2 (`h2c`) via prior-knowledge preface on the HTTP listener
- HTTP/2 server push (`PUSH_PROMISE`)
- HTTP/2 WebSockets (extended `CONNECT` with `:protocol = websocket`)
- virtual hosts
- gzip compression
- Brotli precompressed asset serving (`file.ext.br` when client sends `Accept-Encoding: br`)
- in-memory static file caching with ETag / Last-Modified / 304 support
- chunked transfer encoding for large HTTP/1.1 responses
- WebSocket upgrade + echo handler on HTTP/1.1
- rate limiting
- access control by CIDR
- optional HTTP Basic Auth per host
- directory listing (optional)

## HTTP/2 Scope

HTTP/2 works on both listeners:

- HTTPS listener with ALPN (`h2`)
- HTTP listener using `h2c` prior knowledge (`PRI * HTTP/2.0...` preface)

What is supported:

- client preface handling
- SETTINGS / SETTINGS ACK
- PING / PING ACK
- WINDOW_UPDATE
- HEADERS + CONTINUATION
- DATA frames
- HPACK header decoding/encoding
- regular request handling through the same router / static files / proxy pipeline
- HTTP/2 WebSocket streams
- HTTP/2 server push for configured paths and `Link: rel=preload` responses

This project remains a demo/learning server, not a hardened production replacement for nginx or Apache.

## Important Implementation Note

To keep the project dependency-free, the HPACK codec uses the JDK's `java.net.http` internal HPACK package.
That means build and run commands need one extra JVM flag:

```bash
--add-exports java.net.http/jdk.internal.net.http.hpack=ALL-UNNAMED
```

That flag is already included in `build.sh` and `run.sh`.

## Project Structure

```text
src/main/java/server/
  Main.java
  HttpServer.java
  EventLoop.java
  Connection.java
  HttpParser.java
  HttpRequest.java
  HttpResponse.java
  Router.java
  StaticFileHandler.java
  ReverseProxyHandler.java
  TlsServer.java
  Http2Connection.java
  Http2Frame.java
  Hpack.java
  Config.java
  Logger.java
  ...
```

## Build

### Option 1: script

```bash
./build.sh
```

### Option 2: Maven

```bash
mvn -q -DskipTests package
```

### Option 3: javac directly

```bash
javac \
  --add-exports java.net.http/jdk.internal.net.http.hpack=ALL-UNNAMED \
  -d out \
  $(find src/main/java -name '*.java')
```

## Run

### Option 1: one Java command (compiles + runs)

```bash
java \
  --add-exports java.net.http/jdk.internal.net.http.hpack=ALL-UNNAMED \
  Launcher.java
```

Optional custom config path:

```bash
java \
  --add-exports java.net.http/jdk.internal.net.http.hpack=ALL-UNNAMED \
  Launcher.java src/main/resources/server-config.json
```

### Option 2: script

```bash
./run.sh
```

### Option 3: java directly from compiled classes

```bash
java \
  --add-exports java.net.http/jdk.internal.net.http.hpack=ALL-UNNAMED \
  -cp out \
  server.Main src/main/resources/server-config.json
```
## Default Config

The bundled config starts:

- HTTP on `8080`
- HTTPS on `8443`

The sample config ships with HTTPS enabled and expects a local demo keystore in the project root:

```text
keystore.p12
```

## Replace the Demo Certificate

Create your own PKCS12 keystore:

```bash
./generate-keystore.sh
```

Then update `src/main/resources/server-config.json` if you want different passwords or file names.

## Test It

HTTP/1.1 over TLS:

```bash
curl -k https://localhost:8443/health --http1.1
```

HTTP/2 over TLS:

```bash
curl -k https://localhost:8443/health --http2
```

HTTP/2 cleartext (h2c prior knowledge):

```bash
curl --http2-prior-knowledge http://localhost:8080/health
```

## Sample Routes

The sample config contains:

- `GET /health` -> returns `OK`
- `GET /hello` -> returns a plain text message
- `GET /redirect-me` -> redirects to `/`
- `/ws` -> WebSocket echo endpoint
- static files from `src/main/resources/www`

## Reverse Proxy

To proxy all unmatched requests for a host, set `proxyPass` on the host:

```json
"proxyPass": "http://127.0.0.1:9000"
```

Or create explicit proxy routes:

```json
{
  "method": "ANY",
  "path": "/api/",
  "type": "proxy",
  "match": "prefix",
  "upstream": "http://127.0.0.1:9000"
}
```

## Brotli Support

If a file next to your asset exists with the same name plus `.br`, for example:

```text
app.js
app.js.br
```

then the server returns the `.br` file when the client advertises Brotli support.

## HTTP/2 Server Push Configuration

You can configure host-level push mappings:

```json
"http2Push": {
  "/": ["/app.js"],
  "/index.html": ["/app.js"]
}
```

For HTTP/2 clients that allow push, these paths are sent with `PUSH_PROMISE` before the main response body.

Additionally, if a response sets a `Link` header with `rel=preload`, those paths are also considered for server push.

## Access Control and Auth

- CIDR allow/deny lists are configured globally in `access`
- optional Basic Auth can be configured per host with:
  - `basicAuthUser`
  - `basicAuthPassword`

