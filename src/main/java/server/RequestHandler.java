package server;

@FunctionalInterface
public interface RequestHandler {
    HttpResponse handle(HttpRequest request, RequestContext context) throws Exception;
}
