# Client/Server Object Store And Sync

Status: **draft / planning**. This document specifies the planned
IndexedDB-like client/server data model for ScalaScript full-stack apps.

## Goals

- Let one `.ssc` app use browser-native `IndexedDB` locally and a server-side
  object/document store on the JVM backend.
- Provide a small sync contract so clients can pull server changes, keep local
  offline state, and push local mutations back to the server.
- Keep the first server implementation lightweight: no new database engine,
  no custom storage daemon, and no mandatory external service.
- Preserve escape hatches for production systems: PostgreSQL JSONB, JDBC SQL
  tables, or a CouchDB-compatible backend can implement the same contract.

## Non-Goals

- Do not make server storage literally implement the browser IndexedDB API.
  IndexedDB is a browser API with browser transaction semantics; the server
  gets an analogous object-store model, not the same runtime.
- Do not make automatic multi-master conflict resolution invisible. Conflicts
  must be observable and application-resolvable.
- Do not replace relational SQL. Use SQL when relational queries, joins, and
  constraints are the natural model.
- Do not require CouchDB/PouchDB in the first implementation.

## Architecture

The model has three layers:

1. **Client local store** — `IndexedDB` for browser/Electron/React clients.
2. **Server object store** — JVM-side object/document storage exposed through
   REST sync endpoints.
3. **Sync helper** — a standard-library helper that moves changes between the
   two stores using cursors and optimistic versions.

The source-level shape should be declarative:

```yaml
---
objectStores:
  todos:
    sync: client-server
    key: id
    server:
      backend: jdbc-json
      database: server
      table: ssc_object_store
    client:
      backend: indexeddb
      database: app
      store: todos
---
```

Client code can use the local store directly:

```scalascript
IndexedDb.put("todos", todo.id, todo)
Sync.push("todos")
val latest = Sync.pull("todos")
```

Server code can use the analogous object-store API:

```scalascript
ObjectStore.put("todos", todo.id, todo)
val todo = ObjectStore.get("todos", todoId)
```

### Server Storage Contract

The minimum server representation is an append-friendly JSON object table:

```sql
CREATE TABLE ssc_object_store (
  store_name  VARCHAR NOT NULL,
  object_key  VARCHAR NOT NULL,
  value_json  TEXT NOT NULL,
  version     BIGINT NOT NULL,
  updated_at  TIMESTAMP NOT NULL,
  deleted     BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (store_name, object_key)
);
```

The portable first implementation should target JDBC and work with SQLite, H2,
and PostgreSQL. PostgreSQL may specialize `value_json` to `JSONB` and add GIN
indexes later, but the first contract should not depend on PostgreSQL-only
features.

Required operations:

| Operation | Meaning |
| --- | --- |
| `get(store, key)` | Return the current object and version, or missing |
| `put(store, key, value, expectedVersion?)` | Insert/update with optimistic concurrency |
| `delete(store, key, expectedVersion?)` | Tombstone an object for sync |
| `changes(store, sinceCursor, limit)` | Return changed objects/tombstones after a cursor |
| `compact(store, beforeCursor)` | Optional cleanup of old tombstones/history |

### REST Sync Contract

Generated full-stack apps should expose narrow sync endpoints for stores that
opt into `sync: client-server`:

```text
GET  /__ssc/sync/{store}/changes?since=<cursor>&limit=<n>
POST /__ssc/sync/{store}/push
```

`changes` returns ordered changes plus the next cursor. `push` accepts local
mutations with the client's last-known server version. The server applies each
mutation if the expected version still matches; otherwise it returns a conflict
entry with the current server object.

The first JVM implementation is typed and generated from `objectStores:`.
Each synced store must provide a concrete ScalaScript value type:

```yaml
objectStores:
  drafts:
    type: Draft
    sync: client-server
    database: default
    key: id
```

For that declaration, JVM codegen emits:

```text
GET  /__ssc/sync/drafts/changes
POST /__ssc/sync/drafts/push
```

`changes` reads `since` and `limit` query parameters and returns:

```json
{
  "changes": [
    {
      "key": "d1",
      "version": 2,
      "updatedAt": "2026-05-26T12:00:00Z",
      "deleted": false,
      "value": { "id": "d1", "title": "Write spec" }
    }
  ],
  "nextCursor": 2
}
```

`push` accepts:

```json
{
  "mutations": [
    {
      "key": "d1",
      "expectedVersion": 1,
      "deleted": false,
      "value": { "id": "d1", "title": "Write spec" }
    }
  ]
}
```

and returns applied `results` plus explicit `conflicts`. This landed before
the client-side `Sync.pull/push` helper, so browser/Electron clients still need
to call the generated endpoints manually or through generated typed clients.
The first generated route implementation reports conflicts explicitly; automatic
`server-wins` / `client-wins` policies remain planned.

Conflict policy should be explicit per store:

```yaml
objectStores:
  todos:
    sync: client-server
    conflict: server-wins # server-wins | client-wins | manual
```

The default should be `manual` once user-facing APIs exist, because silent
overwrites are dangerous. Demos may opt into `server-wins` or `client-wins`.

## Standard Solutions And Rationale

There are established choices, but they solve different parts of the problem:

| Option | Strength | Tradeoff |
| --- | --- | --- |
| PouchDB + CouchDB protocol | Mature IndexedDB-to-server replication model | Adds a specific replication ecosystem and server protocol |
| CouchDB | Real document database with built-in replication | External server dependency; not minimal for ScalaScript bootstrap |
| PostgreSQL JSONB | Excellent production server-side document storage | Requires PostgreSQL for best features |
| SQLite/H2 JSON text table via JDBC | Minimal bundled JVM implementation | Sync/conflict logic must be implemented in ScalaScript runtime |

The recommended first implementation is **JDBC JSON object store + generated REST
sync**, because ScalaScript already has JVM/JDBC plumbing and can run with a
single embedded database. A future CouchDB/PouchDB-compatible mode is worth
keeping as an optional backend for apps that want battle-tested replication.

## Migration

- Existing SQL/database features remain unchanged.
- `IndexedDB` APIs remain client-only.
- `ObjectStore` APIs are available on the server side and may be used by routes.
- `Sync` APIs are available to client code only when an `objectStores:` entry
  declares `sync: client-server`.

## Phases

1. **Spec + codec foundation** — document object-store front matter, server API,
   client API, and sync endpoint shape. Partially landed 2026-05-26:
   `backend/typed-data` provides `ObjectValue`, `ObjectFieldSpec[A]`, and
   `ObjectCodec[A]` for portable JSON-compatible object/document values and
   key extraction. Follow-up landed 2026-05-26: JS/browser/Electron codegen
   exposes `IndexedDb.store[A](store, dbName?, keyField?)` as a Promise-based
   typed local client store, backed by native IndexedDB when available and a
   lightweight fallback for Node/tests. Follow-up landed 2026-05-26:
   `backend-sql-runtime` includes `ObjectStoreBackend`,
   `ObjectStoreRuntime`, `Stored[A]`, and a JDBC JSON-table implementation;
   JVM codegen exposes `ObjectStore.put/get/all/delete/changes[A]` over
   declared `databases:` connections. Generated REST sync remains planned.
2. **Server object store SPI** — landed 2026-05-26: small
   `ObjectStoreBackend` contract implemented first by JDBC JSON storage.
3. **Generated sync routes** — landed 2026-05-26: JVM codegen parses
   `objectStores:` front matter and generates typed `changes` / `push` REST
   endpoints for `sync: client-server` stores over the JDBC ObjectStore
   runtime.
4. **Client sync helper** — implement IndexedDB-backed pull/push helpers for
   browser/Electron clients.
5. **Conflict handling** — expose conflict results and implement configured
   `server-wins`, `client-wins`, and `manual` policies.
6. **Examples + conformance** — add an offline todo example that edits locally,
   syncs to the JVM server, restarts, and pulls changes into another client.

## Testing Strategy

- Unit-test version checks, tombstones, cursors, and conflict results.
- Integration-test JDBC object store against SQLite/H2 first; PostgreSQL JSONB
  can be added as an optional profile later.
- Browser/Electron smoke-test local IndexedDB writes, offline queueing, push,
  pull, and conflict reporting.
- Distributed smoke-test two clients syncing through one JVM backend.

## Open Questions

- Should the front-matter key be `objectStores:`, `documents:`, or `syncStores:`?
- Should `ObjectStore` values be restricted to JSON-compatible values in v1?
- Should the first conflict default be `manual` only, or allow a safer
  generated `server-wins` default for read-mostly caches?
- Should CouchDB/PouchDB compatibility be a later backend or a first-class
  protocol target from the beginning?
