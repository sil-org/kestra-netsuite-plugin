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
import java.util.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class QueryTest {
    @Inject
    private RunContextFactory runContextFactory;

    private FakeNetSuite netsuite;
    private int totalRows = 2500;

    @BeforeEach
    void start() throws Exception {
        netsuite = new FakeNetSuite();
        netsuite.handler = request -> FakeNetSuite.page(request, totalRows, i -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", String.valueOf(i));
            if (i % 2 == 0) {
                row.put("email", "user" + i + "@example.org"); // NetSuite omits null columns
            }
            return row;
        });
    }

    @AfterEach
    void stop() {
        netsuite.close();
    }

    private Query.QueryBuilder<?, ?> builder(FetchType fetchType) {
        return Query.builder()
            .id("query")
            .type(Query.class.getName())
            .realm(Property.ofValue("1234567_SB1"))
            .consumerKey(Property.ofValue("ck"))
            .consumerSecret(Property.ofValue("cs"))
            .tokenId(Property.ofValue("tk"))
            .tokenSecret(Property.ofValue("ts"))
            .query(Property.ofExpression("SELECT id, email FROM {{ table }} ORDER BY id"))
            .fetchType(Property.ofValue(fetchType))
            .baseUrl(Property.ofValue(netsuite.baseUrl()));
    }

    private RunContext runContext() {
        return runContextFactory.of(Map.of("table", "customer"));
    }

    @Test
    void fetchFollowsPagingAndFlattensRows() throws Exception {
        Query.Output output = builder(FetchType.FETCH).build().run(runContext());

        assertThat(output.getSize(), is(2500L));
        assertThat(output.getRows(), hasSize(2500));
        assertThat(output.getRows().getFirst(), is(Map.of("id", "0", "email", "user0@example.org")));
        assertThat(output.getRows().get(1), is(Map.of("id", "1")));
        assertThat(output.getRows().getLast().get("id"), is("2499"));

        assertThat(netsuite.requests, hasSize(3));
        assertThat(netsuite.requests.stream().map(r -> r.query().get("offset")).toList(), contains("0", "1000", "2000"));
        FakeNetSuite.Recorded first = netsuite.requests.getFirst();
        assertThat(first.method(), is("POST"));
        assertThat(first.path(), is("/query/v1/suiteql"));
        assertThat(first.query().get("limit"), is("1000"));
        assertThat(first.headers().get("Prefer"), is("transient"));
        assertThat(NetSuiteClient.MAPPER.readValue(first.body(), Map.class), is(Map.of("q", "SELECT id, email FROM customer ORDER BY id")));
        assertThat(first.headers().get("Authorization"), startsWith("OAuth realm=\"1234567_SB1\", "));
    }

    @Test
    void paginateFalseReturnsFirstPageOnly() throws Exception {
        Query.Output output = builder(FetchType.FETCH)
            .paginate(Property.ofValue(false))
            .pageSize(Property.ofValue(100))
            .build().run(runContext());

        assertThat(output.getRows(), hasSize(100));
        assertThat(netsuite.requests, hasSize(1));
    }

    @Test
    void storeWritesAllRowsToIonFile() throws Exception {
        RunContext runContext = runContext();
        Query.Output output = builder(FetchType.STORE).build().run(runContext);

        assertThat(output.getSize(), is(2500L));
        assertThat(output.getRows(), nullValue());

        List<Object> stored = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(runContext.storage().getFile(output.getUri())))) {
            FileSerde.reader(reader, stored::add);
        }
        assertThat(stored, hasSize(2500));
        assertThat(stored.get(2), is(Map.of("id", "2", "email", "user2@example.org")));
    }

    @Test
    void fetchOneRequestsSingleRow() throws Exception {
        Query.Output output = builder(FetchType.FETCH_ONE).build().run(runContext());

        assertThat(output.getRow(), is(Map.of("id", "0", "email", "user0@example.org")));
        assertThat(netsuite.requests, hasSize(1));
        assertThat(netsuite.requests.getFirst().query().get("limit"), is("1"));
    }

    @Test
    void noneReturnsTotalCount() throws Exception {
        Query.Output output = builder(FetchType.NONE).build().run(runContext());

        assertThat(output.getSize(), is(2500L));
        assertThat(netsuite.requests, hasSize(1));
    }

    @Test
    void emptyResult() throws Exception {
        totalRows = 0;
        Query.Output output = builder(FetchType.FETCH).build().run(runContext());

        assertThat(output.getRows(), empty());
        assertThat(output.getSize(), is(0L));
    }

    @Test
    void retriesWhenThrottledWithFreshSignature() throws Exception {
        netsuite.throttleNext.set(2);
        totalRows = 10;
        Query.Output output = builder(FetchType.FETCH).build().run(runContext());

        assertThat(output.getSize(), is(10L));
        assertThat(netsuite.requests, hasSize(3));
        assertThat(new HashSet<>(netsuite.requests.stream().map(r -> r.headers().get("Authorization")).toList()), hasSize(3));
    }

    @Test
    void surfacesNetSuiteErrorDetail() {
        netsuite.handler = request -> FakeNetSuite.error(400, "Search error occurred: Field 'emial' for record 'customer' was not found.");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> builder(FetchType.FETCH).build().run(runContext()));

        assertThat(e.getMessage(), containsString("HTTP 400"));
        assertThat(e.getMessage(), containsString("Field 'emial' for record 'customer' was not found."));
    }

    @Test
    void pageSizeIsCappedAt1000() throws Exception {
        builder(FetchType.FETCH).pageSize(Property.ofValue(5000)).build().run(runContext());

        assertThat(netsuite.requests.getFirst().query().get("limit"), is("1000"));
    }
}
