# Java Web Server Project

This project is a from-scratch Java web server inspired by nginx/Apache-style features.

It keeps the original custom HTTP/1.1 server, and adds:

- HTTPS with TLS 1.2 / 1.3
- ALPN negotiation
- HTTP/2 over TLS (`h2`)

## Included features

Implemented in this project:

- TCP listener
- HTTP/1.1 request parsing
- routing
- static file serving
- basic response generation
- keep-alive
- NIO event loop for HTTP
- worker thread pool for request handling
- access/error logging
- reverse proxying
- TLS/HTTPS listener
- ALPN (`h2`, `http/1.1`)
- HTTP/2 request handling over TLS
- virtual hosts
- gzip compression
- Brotli **precompressed asset serving** (`file.ext.br` is served when the client sends `Accept-Encoding: br`)
- in-memory static file caching with ETag / Last-Modified / 304 support
- chunked transfer encoding for large HTTP/1.1 responses
- WebSocket upgrade + echo handler on HTTP/1.1
- rate limiting
- access control by CIDR
- optional HTTP Basic Auth per host
- directory listing (optional)

## HTTP/2 scope

HTTP/2 is implemented on the **HTTPS listener** using **ALPN**.

What works:

- TLS + ALPN negotiation to `h2`
- client preface handling
- SETTINGS / SETTINGS ACK
- PING / PING ACK
- WINDOW_UPDATE
- HEADERS + CONTINUATION
- DATA frames
- HPACK header decoding/encoding
- regular request handling through the same router / static files / proxy pipeline

Current limitations:

- cleartext `h2c` is **not** implemented
- HTTP/2 WebSockets are **not** implemented
- server push is **not** implemented
- this is still a demo/learning server, not a hardened production replacement for nginx or Apache

If a client sends the HTTP/2 cleartext preface to the plain HTTP port, the server responds with `426 Upgrade Required` and tells the client to use HTTPS with ALPN.

## Important implementation note

To keep the project dependency-free, the HPACK codec uses the JDK's `java.net.http` internal HPACK package.
That means build and run commands need one extra JVM flag:

```bash
--add-exports java.net.http/jdk.internal.net.http.hpack=ALL-UNNAMED
```

That flag is already included in the provided `build.sh` and `run.sh` scripts.

## Project structure

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

### Option 2: javac

```bash
javac \
  --add-exports java.net.http/jdk.internal.net.http.hpack=ALL-UNNAMED \
  -d out \
  $(find src/main/java -name '*.java')
```

## Run

### Option 1: script

```bash
./run.sh
```

### Option 2: java directly

```bash
java \
  --add-exports java.net.http/jdk.internal.net.http.hpack=ALL-UNNAMED \
  -cp out \
  server.Main src/main/resources/server-config.json
```

## Default config

The bundled config starts:

- HTTP on `8080`
- HTTPS on `8443`

The sample config ships with HTTPS enabled and expects a local demo keystore in the project root:

```text
keystore.p12
```

A self-signed demo keystore is included in the ZIP.

## Replace the demo certificate

Create your own PKCS12 keystore:

```bash
./generate-keystore.sh
```

Then update `src/main/resources/server-config.json` if you want different passwords or file names.

## Test it

HTTP/1.1:

```bash
curl -k https://localhost:8443/health --http1.1
```

HTTP/2:

```bash
curl -k https://localhost:8443/health --http2
```

## Sample routes

The sample config contains:

- `GET /health` → returns `OK`
- `GET /hello` → returns a plain text message
- `GET /redirect-me` → redirects to `/`
- `/ws` → WebSocket echo endpoint on HTTP/1.1
- static files from `src/main/resources/www`

## Reverse proxy

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

## Brotli support

On-the-fly Brotli compression is not included because the project uses no third-party dependencies.

However, if a file next to your asset exists with the same name plus `.br`, for example:

```text
app.js
app.js.br
```

then the server will return the `.br` file when the client advertises Brotli support.

## Access control and auth

- CIDR allow/deny lists are configured globally in `access`
- optional Basic Auth can be configured per host with:
  - `basicAuthUser`
  - `basicAuthPassword`

## Caveats

This is a serious learning/demo project, but it is still not a drop-in replacement for nginx, Apache, Caddy, Jetty, Undertow, or Netty in production.


## Windows quick start

1. Start the server from the project root with `run-windows.bat`.
2. Test HTTP with `curl.exe http://localhost:8080/index.html`.
3. For HTTPS, either:
   - run `curl.exe -k https://localhost:8443/health`, or
   - run `powershell -ExecutionPolicy Bypass -File .\install-cert.ps1` once, then use `curl https://localhost:8443/health`.

Important on Windows PowerShell:
- `curl` is usually an alias for `Invoke-WebRequest`.
- `curl.exe` is the real curl binary.
- A self-signed cert is not trusted until you import `localhost.crt`.

If `/index.html` returns 404, make sure you actually started this project with:

```bat
run-windows.bat
```

or explicitly:

```bat
java --add-exports java.net.http/jdk.internal.net.http.hpack=ALL-UNNAMED -cp out server.Main src\main\resources\server-config.json
```
