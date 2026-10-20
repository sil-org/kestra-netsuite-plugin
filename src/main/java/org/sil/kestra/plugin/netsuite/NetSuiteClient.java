package org.sil.kestra.plugin.netsuite;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.serializers.JacksonMapper;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Sends signed requests to the NetSuite SuiteTalk REST API ({@code {baseUrl}/services/rest}), retrying throttled
 * responses and turning error responses into exceptions that carry NetSuite's error detail.
 */
final class NetSuiteClient {
    public static final ObjectMapper MAPPER = JacksonMapper.ofJson();
    private static final int MAX_ATTEMPTS = 6;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private final String restUrl;
    private final NetSuiteOAuth1 auth;
    private final Logger logger;
    private int requests = 0;

    NetSuiteClient(String baseUrl, NetSuiteOAuth1 auth, Logger logger) {
        this.restUrl = baseUrl.replaceAll("/+$", "") + "/services/rest";
        this.auth = auth;
        this.logger = logger;
    }

    /** Number of logical requests sent, not counting retries. */
    public int requests() {
        return requests;
    }

    /**
     * @param path  path under {@code /services/rest}, e.g. {@code /record/v1/customer}
     * @param query query parameters, sent in iteration order
     * @param body  JSON request body, or null for none
     * @throws IllegalStateException for any non-2xx response, after retries
     */
    public Response send(String method, String path, Map<String, String> query, String body, Map<String, String> headers) throws Exception {
        String queryString = query == null || query.isEmpty() ? "" : "?" + query.entrySet().stream()
            .map(e -> NetSuiteOAuth1.encode(e.getKey()) + "=" + NetSuiteOAuth1.encode(e.getValue()))
            .collect(Collectors.joining("&"));
        URI uri = URI.create(restUrl + path + queryString);
        requests++;

        for (int attempt = 1; ; attempt++) {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", auth.header(method, uri))
                .header("Accept", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            if (headers != null) {
                headers.forEach(request::setHeader);
            }

            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (status >= 200 && status < 300) {
                return new Response(status, response.headers(), response.body());
            }
            if ((status == 429 || status == 503) && attempt < MAX_ATTEMPTS) {
                long waitSeconds = response.headers().firstValue("Retry-After")
                    .flatMap(NetSuiteClient::parseLong)
                    .orElse(1L << (attempt - 1));
                logger.warn("NetSuite returned {} for {} {}; retrying in {}s (attempt {}/{})",
                    status, method, path, waitSeconds, attempt, MAX_ATTEMPTS);
                Thread.sleep(waitSeconds * 1000);
                continue;
            }
            throw new IllegalStateException("NetSuite " + method + " " + path + " failed with HTTP " + status + ": " + errorDetail(response.body()));
        }
    }

    public record Response(int status, HttpHeaders headers, String body) {
        public Map<String, Object> json() throws Exception {
            return MAPPER.readValue(body, new TypeReference<>() {});
        }
    }

    /** NetSuite errors carry the useful message in {@code o:errorDetails[].detail}; fall back to the raw body. */
    @SuppressWarnings("unchecked")
    static String errorDetail(String body) {
        try {
            Map<String, Object> error = MAPPER.readValue(body, new TypeReference<>() {});
            if (error.get("o:errorDetails") instanceof List<?> list && !list.isEmpty()) {
                return list.stream()
                    .map(d -> String.valueOf(((Map<String, Object>) d).get("detail")))
                    .collect(Collectors.joining("; "));
            }
        } catch (Exception ignored) {
            // not JSON; use the raw body
        }
        return body;
    }

    /** Builds an ordered query-parameter map; later arguments override earlier ones. */
    @SafeVarargs
    public static Map<String, String> params(Map<String, String>... maps) {
        Map<String, String> params = new LinkedHashMap<>();
        for (Map<String, String> map : maps) {
            if (map != null) {
                params.putAll(map);
            }
        }
        return params;
    }

    private static Optional<Long> parseLong(String value) {
        try {
            return Optional.of(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
