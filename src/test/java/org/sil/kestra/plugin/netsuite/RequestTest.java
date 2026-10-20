package org.sil.kestra.plugin.netsuite;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class RequestTest {
    @Inject
    private RunContextFactory runContextFactory;

    private FakeNetSuite netsuite;

    @BeforeEach
    void start() throws Exception {
        netsuite = new FakeNetSuite();
    }

    @AfterEach
    void stop() {
        netsuite.close();
    }

    private Request.RequestBuilder<?, ?> builder(String path) {
        return Request.builder()
            .id("request")
            .type(Request.class.getName())
            .realm(Property.ofValue("1234567_SB1"))
            .consumerKey(Property.ofValue("ck"))
            .consumerSecret(Property.ofValue("cs"))
            .tokenId(Property.ofValue("tk"))
            .tokenSecret(Property.ofValue("ts"))
            .path(path.contains("{{") ? Property.ofExpression(path) : Property.ofValue(path))
            .baseUrl(Property.ofValue(netsuite.baseUrl()));
    }

    private RunContext runContext() {
        return runContextFactory.of(Map.of("customerId", "1234"));
    }

    @Test
    void getSingleRecordReturnsBody() throws Exception {
        netsuite.handler = r -> FakeNetSuite.Reply.json(Map.of(
            "links", List.of(Map.of("rel", "self", "href", "https://example/customer/1234")),
            "id", "1234",
            "companyName", "Example Org"
        ));

        Request.Output output = builder("customer/{{ customerId }}")
            .queryParams(Property.ofValue(Map.of("fields", "id,companyName")))
            .build().run(runContext());

        assertThat(output.getStatusCode(), is(200));
        assertThat(((Map<?, ?>) output.getBody()).get("companyName"), is("Example Org"));
        assertThat(output.getRows(), nullValue());

        FakeNetSuite.Recorded request = netsuite.requests.getFirst();
        assertThat(request.method(), is("GET"));
        assertThat(request.path(), is("/record/v1/customer/1234"));
        assertThat(request.query(), is(Map.of("fields", "id,companyName")));
        assertThat(request.headers().get("Authorization"), startsWith("OAuth realm=\"1234567_SB1\", "));
        assertThat(request.body(), is(""));
    }

    @Test
    void getCollectionFollowsPagingAndKeepsQueryParams() throws Exception {
        netsuite.handler = r -> FakeNetSuite.page(r, 2300, i -> Map.of("id", String.valueOf(i)));

        Request.Output output = builder("/customer/")
            .queryParams(Property.ofValue(Map.of("q", "email START_WITH \"jason\"")))
            .build().run(runContext());

        assertThat(output.getSize(), is(2300L));
        assertThat(output.getRows(), hasSize(2300));
        assertThat(output.getRows().getFirst(), is(Map.of("id", "0")));
        assertThat(output.getBody(), nullValue());

        assertThat(netsuite.requests, hasSize(3));
        assertThat(netsuite.requests.stream().map(r -> r.query().get("offset")).toList(), contains(nullValue(), is("1000"), is("2000")));
        assertThat(netsuite.requests.stream().map(r -> r.query().get("q")).distinct().toList(), contains("email START_WITH \"jason\""));
        assertThat(netsuite.requests.getFirst().path(), is("/record/v1/customer"));
    }

    @Test
    void getCollectionWithPaginateFalseReturnsRawFirstPage() throws Exception {
        netsuite.handler = r -> FakeNetSuite.page(r, 2300, i -> Map.of("id", String.valueOf(i)));

        Request.Output output = builder("customer").paginate(Property.ofValue(false)).build().run(runContext());

        Map<?, ?> body = (Map<?, ?>) output.getBody();
        assertThat(body.get("hasMore"), is(true));
        assertThat(body.get("totalResults"), is(2300));
        assertThat((List<?>) body.get("items"), hasSize(1000));
        assertThat(output.getRows(), nullValue());
        assertThat(netsuite.requests, hasSize(1));
    }

    @Test
    void getCollectionStore() throws Exception {
        netsuite.handler = r -> FakeNetSuite.page(r, 1500, i -> Map.of("id", String.valueOf(i)));

        RunContext runContext = runContext();
        Request.Output output = builder("customer").fetchType(Property.ofValue(FetchType.STORE)).build().run(runContext);

        List<Object> stored = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(runContext.storage().getFile(output.getUri())))) {
            FileSerde.reader(reader, stored::add);
        }
        assertThat(stored, hasSize(1500));
        assertThat(output.getSize(), is(1500L));
    }

    @Test
    void getCollectionNoneReturnsTotal() throws Exception {
        netsuite.handler = r -> FakeNetSuite.page(r, 4321, i -> Map.of("id", String.valueOf(i)));

        Request.Output output = builder("customer").fetchType(Property.ofValue(FetchType.NONE)).build().run(runContext());

        assertThat(output.getSize(), is(4321L));
        assertThat(netsuite.requests, hasSize(1));
    }

    @Test
    void postCreatesRecordAndReturnsId() throws Exception {
        netsuite.handler = r -> new FakeNetSuite.Reply(204,
            Map.of("Location", netsuite.baseUrl() + "/services/rest/record/v1/customer/5678"), "");

        Request.Output output = builder("customer")
            .method(Property.ofValue(Request.Method.POST))
            .body(Property.ofValue(Map.of("companyName", "Example Org", "subsidiary", Map.of("id", "1"))))
            .build().run(runContext());

        assertThat(output.getStatusCode(), is(204));
        assertThat(output.getId(), is("5678"));
        assertThat(output.getLocation(), endsWith("/record/v1/customer/5678"));
        assertThat(output.getBody(), nullValue());

        FakeNetSuite.Recorded request = netsuite.requests.getFirst();
        assertThat(request.method(), is("POST"));
        assertThat(request.headers().get("Content-Type"), is("application/json"));
        assertThat(NetSuiteClient.MAPPER.readValue(request.body(), Map.class),
            is(Map.of("companyName", "Example Org", "subsidiary", Map.of("id", "1"))));
    }

    @Test
    void patchAndDeleteUseTheirMethods() throws Exception {
        netsuite.handler = r -> new FakeNetSuite.Reply(204, Map.of(), "");

        builder("customer/1234")
            .method(Property.ofValue(Request.Method.PATCH))
            .body(Property.ofValue(Map.of("email", "new@example.org")))
            .build().run(runContext());
        builder("customer/1234")
            .method(Property.ofValue(Request.Method.DELETE))
            .build().run(runContext());

        assertThat(netsuite.requests.stream().map(FakeNetSuite.Recorded::method).toList(), contains("PATCH", "DELETE"));
        assertThat(netsuite.requests.get(0).body(), containsString("new@example.org"));
        assertThat(netsuite.requests.get(1).body(), is(""));
    }

    @Test
    void extraHeadersAreSent() throws Exception {
        netsuite.handler = r -> new FakeNetSuite.Reply(202, Map.of("Location", "https://example/async/job/9"), "");

        Request.Output output = builder("customer")
            .method(Property.ofValue(Request.Method.POST))
            .body(Property.ofValue(Map.of("companyName", "Example Org")))
            .headers(Property.ofValue(Map.of("Prefer", "respond-async")))
            .build().run(runContext());

        assertThat(netsuite.requests.getFirst().headers().get("Prefer"), is("respond-async"));
        assertThat(output.getStatusCode(), is(202));
    }

    @Test
    void surfacesNetSuiteErrorDetail() {
        netsuite.handler = r -> FakeNetSuite.error(404, "The record instance does not exist. Provide a valid record instance ID.");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> builder("customer/999").build().run(runContext()));

        assertThat(e.getMessage(), containsString("GET /record/v1/customer/999 failed with HTTP 404"));
        assertThat(e.getMessage(), containsString("The record instance does not exist."));
    }

    @Test
    void rejectsQueryStringInPath() {
        assertThrows(IllegalArgumentException.class, () -> builder("customer?q=x").build().run(runContext()));
        assertThat(netsuite.requests, empty());
    }
}
