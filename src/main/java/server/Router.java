package server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class Router {
    public HttpResponse route(HttpRequest request, RequestContext context) throws Exception {
        for (Config.RouteConfig route : context.host.routes) {
            boolean methodMatches = route.method.equalsIgnoreCase(request.method)
                    || "ANY".equalsIgnoreCase(route.method)
                    || ("HEAD".equalsIgnoreCase(request.method) && "GET".equalsIgnoreCase(route.method));
            if (!methodMatches) {
                continue;
            }
            boolean matches = "prefix".equalsIgnoreCase(route.match)
                    ? request.path.startsWith(route.path)
                    : request.path.equals(route.path);
            if (!matches) continue;

            return switch (route.type.toLowerCase(Locale.ROOT)) {
                case "literal" -> HttpResponse.bytes(route.status,
                        route.responseText.getBytes(StandardCharsets.UTF_8),
                        route.contentType);
                case "file" -> {
                    if (route.filePath == null) {
                        yield HttpResponse.text(500, "Route filePath is not configured");
                    }
                    Path p = Path.of(route.filePath);
                    if (!Files.exists(p)) yield HttpResponse.text(404, "File not found");
                    byte[] body = Files.readAllBytes(p);
                    yield HttpResponse.bytes(route.status, body, route.contentType);
                }
                case "proxy" -> {
                    if (route.upstream == null) yield HttpResponse.text(500, "Proxy upstream is not configured");
                    ReverseProxyHandler proxy = context.server.getReverseProxyHandler();
                    yield proxy.handle(request, context, route.upstream);
                }
                case "redirect" -> {
                    if (route.location == null) yield HttpResponse.text(500, "Redirect location is not configured");
                    yield HttpResponse.redirect(route.status, route.location);
                }
                default -> HttpResponse.text(500, "Unknown route type: " + route.type);
            };
        }
        return null;
    }
}
