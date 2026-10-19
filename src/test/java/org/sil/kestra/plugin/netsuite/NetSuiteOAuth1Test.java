package org.sil.kestra.plugin.netsuite;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class NetSuiteOAuth1Test {
    private final NetSuiteOAuth1 auth = new NetSuiteOAuth1("1234567_SB1", "ck", "cs secret", "tk", "ts/secret");
    private final URI uri = URI.create("https://1234567-sb1.suitetalk.api.netsuite.com/services/rest/query/v1/suiteql?limit=1000&offset=0");

    @Test
    void signatureMatchesReferenceImplementation() throws Exception {
        // Expected value computed independently with Python's hmac/hashlib from the same inputs.
        String header = auth.header("POST", uri, "abc123", 1700000000L);

        assertThat(header, containsString("oauth_signature=\"" + NetSuiteOAuth1.encode("42zQdfHS92oQU78vXg1DB1pS2N/nzhfPRA+Y9tvtgmo=") + "\""));
    }

    @Test
    void signatureWithEncodedQueryMatchesReferenceImplementation() throws Exception {
        URI get = URI.create("https://1234567-sb1.suitetalk.api.netsuite.com/services/rest/record/v1/customer"
            + "?q=" + NetSuiteOAuth1.encode("email START_WITH \"j\"") + "&limit=1000");

        String header = auth.header("GET", get, "abc123", 1700000000L);

        assertThat(header, containsString("oauth_signature=\"" + NetSuiteOAuth1.encode("WEv7j+cO0r53095skG7MlVQYuRZT8f8p5EKhFCoRo5o=") + "\""));
    }

    @Test
    void headerCarriesRealmAndOAuthParams() throws Exception {
        String header = auth.header("POST", uri, "abc123", 1700000000L);

        assertThat(header, startsWith("OAuth realm=\"1234567_SB1\", "));
        assertThat(header, containsString("oauth_consumer_key=\"ck\""));
        assertThat(header, containsString("oauth_token=\"tk\""));
        assertThat(header, containsString("oauth_signature_method=\"HMAC-SHA256\""));
        assertThat(header, containsString("oauth_timestamp=\"1700000000\""));
        assertThat(header, containsString("oauth_nonce=\"abc123\""));
        assertThat(header, containsString("oauth_version=\"1.0\""));
    }

    @Test
    void freshNonceEachCall() throws Exception {
        assertThat(auth.header("POST", uri), not(equalTo(auth.header("POST", uri))));
    }

    @Test
    void encodesPerRfc3986() {
        assertThat(NetSuiteOAuth1.encode("a b*c~d+e/f"), is("a%20b%2Ac~d%2Be%2Ff"));
    }
}
