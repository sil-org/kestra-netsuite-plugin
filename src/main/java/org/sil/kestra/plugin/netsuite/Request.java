package org.sil.kestra.plugin.netsuite;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
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
    title = "Call the NetSuite REST record API",
    description = """
        Sends a request to `/services/rest/record/v1/{path}` using Token-Based Auth (OAuth 1.0a, HMAC-SHA256).

        For a `GET` that returns a paged collection (a response with `items` and `hasMore`) and `paginate` true, \
        the task follows every page and returns the items as one flat list through `fetchType`, with NetSuite's \
        per-item `links` removed. Note that record collections return only `id` for each item; use the \
        SuiteQL `Query` task to get full rows.

        Any other response is returned unchanged in `body`. For a create (`POST`), NetSuite responds with \
        `204 No Content` and a `Location` header; the new record's ID is returned in `id`."""
)
@Plugin(
    examples = {
        @Example(
            title = "Get one customer record",
            full = true,
            code = """
                id: netsuite_get_customer
                namespace: company.team

                tasks:
                  - id: customer
                    type: org.sil.kestra.plugin.netsuite.Request
                    realm: "1234567_SB1"
                    consumerKey: "{{ secret('NETSUITE_CONSUMER_KEY') }}"
                    consumerSecret: "{{ secret('NETSUITE_CONSUMER_SECRET') }}"
                    tokenId: "{{ secret('NETSUITE_TOKEN_ID') }}"
                    tokenSecret: "{{ secret('NETSUITE_TOKEN_SECRET') }}"
                    path: customer/1234
                    queryParams:
                      fields: id,companyName,email

                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ outputs.customer.body.companyName }}"
                """
        ),
        @Example(
            title = "List the IDs of every customer matching a filter",
            full = true,
            code = """
                id: netsuite_list_customers
                namespace: company.team

                tasks:
                  - id: customers
                    type: org.sil.kestra.plugin.netsuite.Request
                    realm: "1234567_SB1"
                    consumerKey: "{{ secret('NETSUITE_CONSUMER_KEY') }}"
                    consumerSecret: "{{ secret('NETSUITE_CONSUMER_SECRET') }}"
                    tokenId: "{{ secret('NETSUITE_TOKEN_ID') }}"
                    tokenSecret: "{{ secret('NETSUITE_TOKEN_SECRET') }}"
                    path: customer
                    queryParams:
                      q: email START_WITH "jason"
                """
        ),
        @Example(
            title = "Create a customer and use the new ID",
            full = true,
            code = """
                id: netsuite_create_customer
                namespace: company.team

                tasks:
                  - id: create
                    type: org.sil.kestra.plugin.netsuite.Request
                    realm: "1234567_SB1"
                    consumerKey: "{{ secret('NETSUITE_CONSUMER_KEY') }}"
                    consumerSecret: "{{ secret('NETSUITE_CONSUMER_SECRET') }}"
                    tokenId: "{{ secret('NETSUITE_TOKEN_ID') }}"
                    tokenSecret: "{{ secret('NETSUITE_TOKEN_SECRET') }}"
                    method: POST
                    path: customer
                    body:
                      companyName: Example Org
                      subsidiary:
                        id: "1"

                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "Created customer {{ outputs.create.id }}"
                """
        )
    },
    metrics = {
        @Metric(name = "fetch.size", type = Counter.TYPE, unit = "items", description = "Number of collection items fetched."),
        @Metric(name = "requests", type = Counter.TYPE, unit = "requests", description = "Number of requests sent.")
    }
)
public class Request extends AbstractNetSuiteTask implements RunnableTask<Request.Output> {
    private static final String RECORD_API = "/record/v1/";

    public enum Method {
        GET, POST, PUT, PATCH, DELETE
    }

    @Schema(title = "HTTP method")
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<Method> method = Property.ofValue(Method.GET);

    @Schema(
        title = "Record path",
        description = "Relative to `/services/rest/record/v1/`, e.g. `customer`, `customer/1234`, or " +
            "`salesOrder/1234/item`. Put query parameters in `queryParams`, not in the path."
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<String> path;

    @Schema(
        title = "Query parameters",
        description = "For example `q` (a record filter), `fields`, `expandSubResources`, or `limit`."
    )
    private Property<Map<String, String>> queryParams;

    @Schema(title = "JSON request body", description = "Sent for `POST`, `PUT` and `PATCH`.")
    private Property<Map<String, Object>> body;

    @Schema(
        title = "Extra request headers",
        description = "For example `Prefer: respond-async`. These override the defaults."
    )
    @PluginProperty(group = "advanced")
    private Property<Map<String, String>> headers;

    @Schema(
        title = "Follow pagination",
        description = "When true and a `GET` returns a paged collection, keep requesting pages until NetSuite " +
            "reports `hasMore: false` and return the items through `fetchType`. When false, the first response " +
            "is returned unchanged in `body`."
    )
    @Builder.Default
    private Property<Boolean> paginate = Property.ofValue(true);

    @Schema(
        title = "How to return collection items when paginating",
        description = """
            Only used when a paginated `GET` returns a collection.
            - `FETCH`: return all items in `rows`.
            - `STORE`: write all items to an ION file in internal storage and return its `uri`.
            - `FETCH_ONE`: return only the first item in `row`.
            - `NONE`: return only `size`, the total item count NetSuite reports."""
    )
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext runContext) throws Exception {
        Method rMethod = runContext.render(method).as(Method.class).orElse(Method.GET);
        String rPath = runContext.render(path).as(String.class).orElseThrow().trim().replaceAll("^/+|/+$", "");
        if (rPath.contains("?")) {
            throw new IllegalArgumentException("Put query parameters in `queryParams`, not in `path`: " + rPath);
        }
        Map<String, String> rQueryParams = runContext.render(queryParams).asMap(String.class, String.class);
        Map<String, Object> rBody = runContext.render(body).asMap(String.class, Object.class);
        Map<String, String> rHeaders = runContext.render(headers).asMap(String.class, String.class);
        boolean rPaginate = runContext.render(paginate).as(Boolean.class).orElse(true);
        FetchType rFetchType = runContext.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH);

        String apiPath = RECORD_API + rPath;
        String jsonBody = rBody.isEmpty() || rMethod == Method.GET || rMethod == Method.DELETE
            ? null
            : NetSuiteClient.MAPPER.writeValueAsString(rBody);

        NetSuiteClient client = client(runContext);
        NetSuiteClient.Response response = client.send(rMethod.name(), apiPath, rQueryParams, jsonBody, rHeaders);

        String location = response.headers().firstValue("Location").orElse(null);
        Output.OutputBuilder output = Output.builder()
            .statusCode(response.status())
            .location(location)
            .id(location == null ? null : location.substring(location.lastIndexOf('/') + 1));

        Object parsed = parse(response.body());
        if (rMethod == Method.GET && rPaginate && Rows.isPage(parsed)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> firstPage = (Map<String, Object>) parsed;
            Rows.NextPage next = offset -> client.send(
                "GET",
                apiPath,
                NetSuiteClient.params(rQueryParams, Map.of("offset", String.valueOf(offset))),
                null,
                rHeaders
            ).json();

            Rows.Result result = Rows.collect(runContext, rFetchType, firstPage, true, next);
            output.rows(result.rows()).row(result.row()).uri(result.uri()).size(result.size());
            runContext.metric(Counter.of("fetch.size", result.size()));
        } else {
            output.body(parsed);
        }

        runContext.metric(Counter.of("requests", client.requests()));
        return output.build();
    }

    /** JSON bodies are parsed; an empty body (e.g. 204) is null; anything else is returned as a string. */
    private static Object parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return NetSuiteClient.MAPPER.readValue(body, Object.class);
        } catch (Exception e) {
            return body;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "HTTP status code")
        private final Integer statusCode;

        @Schema(
            title = "Response body",
            description = "The parsed JSON response, or null for an empty response such as `204 No Content`. " +
                "Not set when a paginated collection is returned through `fetchType`."
        )
        private final Object body;

        @Schema(title = "The `Location` response header", description = "Set by NetSuite on create, pointing to the new record.")
        private final String location;

        @Schema(title = "Record ID from the `Location` header", description = "The ID of the record a `POST` created.")
        private final String id;

        @Schema(title = "All collection items, when paginating with `fetchType` `FETCH`")
        private final List<Map<String, Object>> rows;

        @Schema(title = "The first collection item, when paginating with `fetchType` `FETCH_ONE`")
        private final Map<String, Object> row;

        @Schema(title = "Internal storage URI of an ION file with all items, when paginating with `fetchType` `STORE`")
        private final URI uri;

        @Schema(
            title = "Number of collection items",
            description = "Items fetched, or for `NONE` the total count reported by NetSuite."
        )
        private final Long size;
    }
}
