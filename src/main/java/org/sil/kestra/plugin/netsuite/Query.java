package org.sil.kestra.plugin.netsuite;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.enums.MonacoLanguages;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Run a SuiteQL query against NetSuite",
    description = """
        Sends the query to the SuiteTalk REST SuiteQL endpoint using Token-Based Auth (OAuth 1.0a, HMAC-SHA256) \
        and, unless `paginate` is false, follows pagination until NetSuite reports `hasMore: false`. Rows from \
        every page are returned as one flat list, with NetSuite's per-row `links` field removed.

        Rows are returned as NetSuite sends them: column names are lowercase, null columns are omitted from \
        the row, and most values are strings (booleans are `"T"`/`"F"`). Include an `ORDER BY` so offset paging \
        is stable."""
)
@Plugin(
    examples = {
        @Example(
            title = "Store all active customers in internal storage",
            full = true,
            code = """
                id: netsuite_customers
                namespace: company.team

                tasks:
                  - id: customers
                    type: org.sil.kestra.plugin.netsuite.Query
                    realm: "1234567_SB1"
                    consumerKey: "{{ secret('NETSUITE_CONSUMER_KEY') }}"
                    consumerSecret: "{{ secret('NETSUITE_CONSUMER_SECRET') }}"
                    tokenId: "{{ secret('NETSUITE_TOKEN_ID') }}"
                    tokenSecret: "{{ secret('NETSUITE_TOKEN_SECRET') }}"
                    fetchType: STORE
                    query: |
                      SELECT id, entityid, companyname, email
                      FROM customer
                      WHERE isinactive = 'F'
                      ORDER BY id
                """
        ),
        @Example(
            title = "Fetch a small result inline and loop over it",
            full = true,
            code = """
                id: netsuite_subsidiaries
                namespace: company.team

                tasks:
                  - id: subsidiaries
                    type: org.sil.kestra.plugin.netsuite.Query
                    realm: "{{ secret('NETSUITE_REALM') }}"
                    consumerKey: "{{ secret('NETSUITE_CONSUMER_KEY') }}"
                    consumerSecret: "{{ secret('NETSUITE_CONSUMER_SECRET') }}"
                    tokenId: "{{ secret('NETSUITE_TOKEN_ID') }}"
                    tokenSecret: "{{ secret('NETSUITE_TOKEN_SECRET') }}"
                    fetchType: FETCH
                    query: SELECT id, name FROM subsidiary ORDER BY id

                  - id: each
                    type: io.kestra.plugin.core.flow.ForEach
                    values: "{{ outputs.subsidiaries.rows }}"
                    tasks:
                      - id: log
                        type: io.kestra.plugin.core.log.Log
                        message: "{{ json(taskrun.value).name }}"
                """
        )
    },
    metrics = {
        @Metric(name = "fetch.size", type = Counter.TYPE, unit = "rows", description = "Number of rows fetched."),
        @Metric(name = "requests", type = Counter.TYPE, unit = "requests", description = "Number of pages requested.")
    }
)
public class Query extends AbstractNetSuiteTask implements RunnableTask<Query.Output> {
    static final int MAX_PAGE_SIZE = 1000;

    @Schema(
        title = "SuiteQL query",
        description = "Include an `ORDER BY` clause so paging returns each row exactly once."
    )
    @PluginProperty(language = MonacoLanguages.SQL, group = "main")
    @NotNull
    private Property<String> query;

    @Schema(
        title = "How to return the rows",
        description = """
            - `STORE`: write all rows to an ION file in internal storage and return its `uri`.
            - `FETCH`: return all rows in `rows`. Only for small results, since outputs are stored in the database.
            - `FETCH_ONE`: return only the first row in `row`.
            - `NONE`: fetch nothing; `size` is the total row count NetSuite reports."""
    )
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.STORE);

    @Schema(
        title = "Follow pagination",
        description = "When true, keep requesting pages until NetSuite reports `hasMore: false`. " +
            "When false, return only the first page of up to `pageSize` rows."
    )
    @Builder.Default
    private Property<Boolean> paginate = Property.ofValue(true);

    @Schema(title = "Rows requested per page", description = "NetSuite allows at most 1000.")
    @Builder.Default
    private Property<Integer> pageSize = Property.ofValue(MAX_PAGE_SIZE);

    @Override
    public Output run(RunContext runContext) throws Exception {
        String rQuery = runContext.render(query).as(String.class).orElseThrow();
        FetchType rFetchType = runContext.render(fetchType).as(FetchType.class).orElse(FetchType.STORE);
        boolean rPaginate = runContext.render(paginate).as(Boolean.class).orElse(true);
        int rPageSize = Math.min(runContext.render(pageSize).as(Integer.class).orElse(MAX_PAGE_SIZE), MAX_PAGE_SIZE);
        // NONE only needs totalResults and FETCH_ONE only the first row.
        int limit = rFetchType == FetchType.NONE || rFetchType == FetchType.FETCH_ONE ? 1 : rPageSize;

        NetSuiteClient client = client(runContext);
        String body = NetSuiteClient.MAPPER.writeValueAsString(Map.of("q", rQuery));
        Rows.NextPage page = offset -> client.send(
            "POST",
            "/query/v1/suiteql",
            NetSuiteClient.params(Map.of("limit", String.valueOf(limit)), Map.of("offset", String.valueOf(offset))),
            body,
            Map.of("Prefer", "transient")
        ).json();

        Rows.Result result = Rows.collect(runContext, rFetchType, page.fetch(0), rPaginate, page);

        runContext.metric(Counter.of("fetch.size", result.size()));
        runContext.metric(Counter.of("requests", client.requests()));
        return Output.builder()
            .rows(result.rows())
            .row(result.row())
            .uri(result.uri())
            .size(result.size())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "All rows, when `fetchType` is `FETCH`")
        private final List<Map<String, Object>> rows;

        @Schema(title = "The first row, when `fetchType` is `FETCH_ONE`")
        private final Map<String, Object> row;

        @Schema(title = "Internal storage URI of an ION file with all rows, when `fetchType` is `STORE`")
        private final URI uri;

        @Schema(
            title = "Number of rows",
            description = "Rows fetched, or for `NONE` the total row count reported by NetSuite."
        )
        private final Long size;
    }
}
