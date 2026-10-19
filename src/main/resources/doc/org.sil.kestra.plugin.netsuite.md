Query and update Oracle NetSuite from Kestra flows through the SuiteTalk REST API.

## Tasks

- `Query` runs a SuiteQL query and returns the rows as one flat list. When `paginate` is true (the default), it follows pagination until NetSuite reports `hasMore: false`. Otherwise it returns only the first page.
- `Request` calls the REST record API (`/services/rest/record/v1/...`) with any method. It returns single-record responses as they are. For record collections, when `paginate` is true (the default), it follows every page and returns the items as one list. Otherwise it returns the first response as-is.

## Authentication

Both tasks use NetSuite Token-Based Auth (OAuth 1.0a, HMAC-SHA256). You need four values from the NetSuite integration record and access token: consumer key, consumer secret, token ID, and token secret. You also need the account ID, which is the `realm`.

Store the four credentials as Kestra secrets and reference them with `{{ secret('...') }}`. Don't put them directly in flow YAML. To avoid repeating them in every task, set them once per namespace with `pluginDefaults` on `org.sil.kestra.plugin.netsuite`.

## NetSuite row format

Rows and records are returned exactly as NetSuite sends them, except that the per-item `links` field is removed from paged results. In SuiteQL results, column names come back lowercase, null columns are left out of the row, and most values are strings. Booleans come back as `"T"` or `"F"`.
