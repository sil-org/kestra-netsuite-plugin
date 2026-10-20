# Kestra NetSuite Plugin

## What

- Kestra tasks under `org.sil.kestra.plugin.netsuite` for reading and writing Oracle NetSuite data through the SuiteTalk REST API, using Token-Based Auth.
- `Query` (read-only) runs a SuiteQL query and, when `paginate` is true (the default), pages through every result.
- `Request` (read and write) calls the REST record API (`/services/rest/record/v1/{path}`) with GET/POST/PUT/PATCH/DELETE: get, list, create, update, and delete records.

## How

### Architecture

Single-module plugin with no runtime dependencies beyond Kestra core. It uses the JDK `HttpClient` and Kestra's Jackson mapper.

One jar, one package (`org.sil.kestra.plugin.netsuite`), holding both tasks and their shared code. Only the tasks and `AbstractNetSuiteTask` are public:

- `AbstractNetSuiteTask` holds the connection properties (`realm`, the four TBA credentials, `baseUrl`) and builds a `NetSuiteClient`.
- `NetSuiteClient` signs every request, including each retry. It retries 429/503 with backoff, or for as long as `Retry-After` says. For any other non-2xx response it throws with NetSuite's `o:errorDetails` message.
- `Rows` walks paged responses (`items`/`hasMore`/`offset`), removes the per-item `links` field, and returns rows by `fetchType` (`STORE`, `FETCH`, `FETCH_ONE`, `NONE`).
- `Query` POSTs to `/query/v1/suiteql`. Defaults: `fetchType: STORE`, `paginate: true`.
- `Request` sends any method to `/record/v1/{path}`. A paginated GET that returns a collection goes through `Rows`. Defaults: `fetchType: FETCH`, `paginate: true`. Every other response is returned in `body`, and on create the `Location` header gives `id`.
- `NetSuiteOAuth1` signs each request with Token-Based Auth (OAuth 1.0a, HMAC-SHA256). The realm is the account ID as written, e.g. `1234567_SB1`. The hostname uses it lowercased, with `-` in place of `_`.

### Tests

- `NetSuiteOAuth1Test` checks the signer against a signature computed independently in Python.
- `FakeNetSuite` is a local `HttpServer` stand-in for the REST API. It records every request and builds NetSuite-shaped paged responses.
- `QueryTest` and `RequestTest` run the tasks against it.

### Project Structure

```
plugin-netsuite/
├── src/main/java/org/sil/kestra/plugin/netsuite/
├── src/test/java/org/sil/kestra/plugin/netsuite/
├── build.gradle
└── README.md
```

## Local rules

- Run `./gradlew lintPluginDocs test` before committing. Schema titles must not end with a period, and `@PluginProperty` groups must be ones the linter allows.
- Return NetSuite values as NetSuite sends them. Don't cast `"T"`/`"F"` or numeric strings.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/enterprise/instance/versioned-plugins
