# Kestra NetSuite Plugin

Kestra tasks for Oracle NetSuite, using the SuiteTalk REST API and Token-Based Auth. One jar contains
two tasks:

| Task | Use it for |
| --- | --- |
| `org.sil.kestra.plugin.netsuite.Query` | SuiteQL queries that return full rows, with optional automatic paging (`paginate`, on by default) |
| `org.sil.kestra.plugin.netsuite.Request` | Generic REST record calls: get, list, create, update, and delete |

## Connection properties (both tasks)

| Property | Required | Notes |
| --- | --- | --- |
| `realm` | yes | The account ID, e.g. `1234567` or `1234567_SB1`. |
| `consumerKey`, `consumerSecret`, `tokenId`, `tokenSecret` | yes | Token-Based Auth credentials. Use `{{ secret(...) }}`. |
| `baseUrl` | no | Defaults to `https://{realm}.suitetalk.api.netsuite.com`. Override it mainly for testing. |

To avoid repeating the credentials in every task, set them once with `pluginDefaults`. The package
prefix covers both tasks:

```yaml
pluginDefaults:
  - type: org.sil.kestra.plugin.netsuite
    values:
      realm: "1234567_SB1"
      consumerKey: "{{ secret('NETSUITE_CONSUMER_KEY') }}"
      consumerSecret: "{{ secret('NETSUITE_CONSUMER_SECRET') }}"
      tokenId: "{{ secret('NETSUITE_TOKEN_ID') }}"
      tokenSecret: "{{ secret('NETSUITE_TOKEN_SECRET') }}"
```

The examples below assume these defaults are set.

## `Query`

```yaml
tasks:
  - id: customers
    type: org.sil.kestra.plugin.netsuite.Query
    query: |
      SELECT id, entityid, companyname, email
      FROM customer
      WHERE isinactive = 'F'
      ORDER BY id
```

| Property | Default | Notes |
| --- | --- | --- |
| `query` | | The SuiteQL statement. Include `ORDER BY` so paging is stable. |
| `fetchType` | `STORE` | See the table below. |
| `paginate` | `true` | When `false`, only the first page of up to `pageSize` rows is returned. |
| `pageSize` | `1000` | Capped at NetSuite's maximum of 1000. |

## `Request`

The path is relative to `/services/rest/record/v1/`.

```yaml
tasks:
  # Get one record; the response is in outputs.customer.body
  - id: customer
    type: org.sil.kestra.plugin.netsuite.Request
    path: customer/1234
    queryParams:
      fields: id,companyName,email

  # List matching record IDs across every page; the items are in outputs.matches.rows
  - id: matches
    type: org.sil.kestra.plugin.netsuite.Request
    path: customer
    queryParams:
      q: email START_WITH "jason"

  # Create a record; the new ID is in outputs.create.id
  - id: create
    type: org.sil.kestra.plugin.netsuite.Request
    method: POST
    path: customer
    body:
      companyName: Example Org
      subsidiary:
        id: "1"

  # Update a record
  - id: update
    type: org.sil.kestra.plugin.netsuite.Request
    method: PATCH
    path: "customer/{{ outputs.create.id }}"
    body:
      email: info@example.org
```

| Property | Default | Notes |
| --- | --- | --- |
| `method` | `GET` | `GET`, `POST`, `PUT`, `PATCH`, or `DELETE`. |
| `path` | | E.g. `customer`, `customer/1234`, or `salesOrder/1234/item`. Put query parameters in `queryParams`, not in the path. |
| `queryParams` | | E.g. `q`, `fields`, `expandSubResources`, `limit`. |
| `body` | | The JSON body for `POST`, `PUT`, and `PATCH`. |
| `headers` | | Extra headers, e.g. `Prefer: respond-async`. |
| `paginate` | `true` | When a `GET` returns a collection, follow every page and return the items through `fetchType`. When `false`, the first response is returned as-is in `body`. |
| `fetchType` | `FETCH` | Used only for paginated collections. |

The outputs are `statusCode`, `body` (the parsed JSON, or null for 204), `location`, and `id` (the
record ID from `Location` on create). A paginated collection uses `rows`, `row`, `uri`, and `size`
instead of `body`.

Record collections return only `id` for each item. To get full rows, use `Query`.

## `fetchType`

| Value | Output |
| --- | --- |
| `STORE` | `uri`: an ION file in internal storage with every row. `size`: the row count. Use this for anything large. |
| `FETCH` | `rows`: every row inline. `size`: the row count. Use this only for small results, since outputs are stored in the database. |
| `FETCH_ONE` | `row`: the first row only. |
| `NONE` | `size`: the total row count NetSuite reports. No rows are returned. |

To turn a `STORE` output into JSON, chain `io.kestra.plugin.serdes.json.IonToJson` with
`from: "{{ outputs.<task>.uri }}"`.

## NetSuite behaviors

- Paged items have NetSuite's per-item `links` field removed. Everything else is passed through
  unchanged.
- In SuiteQL results:
  - Column names come back lowercase.
  - Null columns are left out of the row entirely.
  - Most values are strings, and booleans are `"T"` or `"F"`.
- HTTP 429 and 503 responses are retried with exponential backoff, or for as long as `Retry-After`
  says. Each retry gets a fresh signature.
- Any other error fails the task with NetSuite's `o:errorDetails` message, e.g. the field a query
  can't find, or "The record instance does not exist."

## Build and test

Requires Java 21.

```bash
./gradlew lintPluginDocs test shadowJar
```

The jar is written to `build/libs/plugin-netsuite-<version>.jar`. The tests run against a local fake
of the NetSuite REST API (`FakeNetSuite`), so they need no NetSuite account.

## Install on Kestra Cloud / Enterprise

1. Build the jar, as above.
2. In Kestra, open **Instance → Versioned Plugins → Install**, and choose **Custom plugin**.
3. Upload `plugin-netsuite-<version>.jar`. No restart is needed.
4. Before uploading a new version, bump `version` in `gradle.properties`. Flows can pin a version
   if needed.

## Run locally with Docker

```bash
./gradlew shadowJar && docker compose up
```

Kestra starts at <http://localhost:8080> with the plugin loaded from `build/libs/`.
