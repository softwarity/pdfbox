package com.softwarity.pdfbox;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A local HTTP server for tests that records every request it receives, so a test can prove that
 * pdfbox did — or, above all, did NOT — reach the network. Routes answer with a fixed body, a
 * redirect, or after a delay.
 */
final class CountingHttpServer implements AutoCloseable {

    record Route(int status, String contentType, byte[] body, String location, long delayMillis, boolean chunked) {}

    private final HttpServer server;
    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    private final List<String> hits = new CopyOnWriteArrayList<>();

    CountingHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    CountingHttpServer serve(String path, String contentType, byte[] body) {
        routes.put(path, new Route(200, contentType, body, null, 0, false));
        return this;
    }

    CountingHttpServer slow(String path, String contentType, byte[] body, long delayMillis) {
        routes.put(path, new Route(200, contentType, body, null, delayMillis, false));
        return this;
    }

    /** No Content-Length: the size is only known while the body streams in. */
    CountingHttpServer chunked(String path, String contentType, byte[] body) {
        routes.put(path, new Route(200, contentType, body, null, 0, true));
        return this;
    }

    CountingHttpServer redirect(String path, String location) {
        routes.put(path, new Route(302, null, new byte[0], location, 0, false));
        return this;
    }

    /** {@code 127.0.0.1:port} — the host[:port] form used in PDFBOX_FETCH_ALLOWED_HOSTS. */
    String authority() {
        return "127.0.0.1:" + server.getAddress().getPort();
    }

    String url(String path) {
        return "http://" + authority() + path;
    }

    int requestCount() {
        return hits.size();
    }

    int requestCount(String path) {
        return (int) hits.stream().filter(path::equals).count();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        hits.add(path);
        Route route = routes.get(path);
        try (exchange) {
            if (route == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (route.delayMillis() > 0) {
                try {
                    Thread.sleep(route.delayMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (route.location() != null) {
                exchange.getResponseHeaders().add("Location", route.location());
            }
            if (route.contentType() != null) {
                exchange.getResponseHeaders().add("Content-Type", route.contentType());
            }
            exchange.sendResponseHeaders(route.status(),
                    route.chunked() ? 0 : route.body().length == 0 ? -1 : route.body().length);
            if (route.body().length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(route.body());
                }
            }
        } catch (IOException e) {
            // Client gave up (timeout / size cap): expected in some tests.
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
