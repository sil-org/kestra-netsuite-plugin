package org.sil.kestra.plugin.netsuite;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds NetSuite Token-Based Auth (OAuth 1.0a, HMAC-SHA256) Authorization headers.
 * <p>
 * Every request needs a fresh nonce and timestamp, so call {@link #header} once per HTTP request (including retries).
 */
final class NetSuiteOAuth1 {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String realm;
    private final String consumerKey;
    private final String consumerSecret;
    private final String tokenId;
    private final String tokenSecret;

    NetSuiteOAuth1(String realm, String consumerKey, String consumerSecret, String tokenId, String tokenSecret) {
        this.realm = realm;
        this.consumerKey = consumerKey;
        this.consumerSecret = consumerSecret;
        this.tokenId = tokenId;
        this.tokenSecret = tokenSecret;
    }

    String header(String method, URI uri) throws GeneralSecurityException {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        return header(method, uri, HexFormat.of().formatHex(nonce), System.currentTimeMillis() / 1000);
    }

    String header(String method, URI uri, String nonce, long timestamp) throws GeneralSecurityException {
        Map<String, String> oauth = new LinkedHashMap<>();
        oauth.put("oauth_consumer_key", consumerKey);
        oauth.put("oauth_nonce", nonce);
        oauth.put("oauth_signature_method", "HMAC-SHA256");
        oauth.put("oauth_timestamp", Long.toString(timestamp));
        oauth.put("oauth_token", tokenId);
        oauth.put("oauth_version", "1.0");

        // Signature base string: query params and oauth params (not realm), encoded then sorted.
        List<String[]> params = new ArrayList<>();
        oauth.forEach((k, v) -> params.add(new String[]{encode(k), encode(v)}));
        if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
            for (String pair : uri.getRawQuery().split("&")) {
                int eq = pair.indexOf('=');
                String k = eq < 0 ? pair : pair.substring(0, eq);
                String v = eq < 0 ? "" : pair.substring(eq + 1);
                params.add(new String[]{encode(decode(k)), encode(decode(v))});
            }
        }
        String normalizedParams = params.stream()
            .sorted((a, b) -> a[0].equals(b[0]) ? a[1].compareTo(b[1]) : a[0].compareTo(b[0]))
            .map(p -> p[0] + "=" + p[1])
            .collect(Collectors.joining("&"));

        String baseString = method.toUpperCase() + "&" + encode(baseUrl(uri)) + "&" + encode(normalizedParams);
        String key = encode(consumerSecret) + "&" + encode(tokenSecret);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getEncoder().encodeToString(mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8)));
        oauth.put("oauth_signature", signature);

        return "OAuth realm=\"" + encode(realm) + "\", " + oauth.entrySet().stream()
            .map(e -> e.getKey() + "=\"" + encode(e.getValue()) + "\"")
            .collect(Collectors.joining(", "));
    }

    /** Scheme and host lowercased, default port dropped, path kept, query removed (RFC 5849 §3.4.1.2). */
    private static String baseUrl(URI uri) {
        String scheme = uri.getScheme().toLowerCase();
        int port = uri.getPort();
        boolean defaultPort = port == -1 || ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + uri.getHost().toLowerCase() + (defaultPort ? "" : ":" + port) + uri.getRawPath();
    }

    /** RFC 3986 percent-encoding, as OAuth 1.0 requires. */
    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~");
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
