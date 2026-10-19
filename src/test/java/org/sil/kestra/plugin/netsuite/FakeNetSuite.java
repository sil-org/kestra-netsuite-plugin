package org.sil.kestra.plugin.netsuite;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local stand-in for the NetSuite REST API. Records every request, and answers each with the configured
 * {@link Handler}. {@link #page} builds NetSuite-shaped paged responses.
 */
public class FakeNetSuite implements AutoCloseable {
    public record Recorded(String method, String path, Map<String, String> query, Map<String, String> headers, String body) {
    }

    public record Reply(int status, Map<String, String> headers, String body) {
        public static Reply json(Object body) throws IOException {
            return new Reply(200, Map.of(), NetSuiteClient.MAPPER.writeValueAsString(body));
        }
    }

    @FunctionalInterface
    public interface Handler {
        Reply handle(Recorded request) throws Exception;
    }

    private final HttpServer server;
    public final List<Recorded> requests = new CopyOnWriteArrayList<>();
    /** Number of upcoming requests to answer with 429 before the handler runs. */
    public final AtomicInteger throttleNext = new AtomicInteger();
    public volatile Handler handler = r -> new Reply(404, Map.of(), "{}");

    public FakeNetSuite() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/rest", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        Map<String, String> query = new LinkedHashMap<>();
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&")) {
                String[] kv = pair.split("=", 2);
                query.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8));
            }
        }
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, v.getFirst()));
        Recorded request = new Recorded(
            exchange.getRequestMethod(),
            exchange.getRequestURI().getRawPath().substring("/services/rest".length()),
            query,
            headers,
            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
        );
        requests.add(request);

        Reply reply;
        if (throttleNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            reply = new Reply(429, Map.of("Retry-After", "0"), "{}");
        } else {
            try {
                reply = handler.handle(request);
            } catch (Exception e) {
                reply = new Reply(500, Map.of(), e.toString());
            }
        }

        reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        if (reply.body() == null || reply.body().isEmpty()) {
            exchange.sendResponseHeaders(reply.status(), -1);
        } else {
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    /**
     * A NetSuite paged response over {@code total} rows built by {@code row}, for the request's {@code limit}
     * (default 1000) and {@code offset}. Each item gets a {@code links} field, as NetSuite does.
     */
    public static Reply page(Recorded request, int total, java.util.function.IntFunction<Map<String, Object>> row) throws IOException {
        int limit = Integer.parseInt(request.query().getOrDefault("limit", "1000"));
        int offset = Integer.parseInt(request.query().getOrDefault("offset", "0"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = offset; i < Math.min(offset + limit, total); i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("links", List.of(Map.of("rel", "self", "href", "https://example/" + i)));
            item.putAll(row.apply(i));
            items.add(item);
        }
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("links", List.of());
        page.put("count", items.size());
        page.put("hasMore", offset + items.size() < total);
        page.put("items", items);
        page.put("offset", offset);
        page.put("totalResults", total);
        return Reply.json(page);
    }

    public static Reply error(int status, String detail) {
        return new Reply(status, Map.of(), """
            {"type":"https://www.rfc-editor.org/rfc/rfc9110.html#section-15.5.1","title":"Bad Request","status":%d,
             "o:errorDetails":[{"detail":"%s","o:errorCode":"INVALID_PARAMETER"}]}""".formatted(status, detail));
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
