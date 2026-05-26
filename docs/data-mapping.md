# Typed Data Mapping Across Stores

Status: **draft / planning**. This document specifies a common data-mapping
layer for SQL databases, browser storage, client/server object stores, property
graphs, RDF graphs, `Dataset[T]`/MapReduce, Apache Spark, and future persistence
or data-processing backends.

## Goals

- Make ordinary ScalaScript case classes and ADTs easy to persist and load from
  every supported storage family.
- Share one derivation foundation across SQL rows, JSON documents, IndexedDB
  objects, server `ObjectStore` values, graph vertices/edges, RDF triples,
  local/distributed `Dataset[T]` elements, and Spark rows/encoders.
- Avoid a lowest-common-denominator ORM that hides the important differences
  between relational, document, graph, and semantic stores.
- Provide stable identity/version metadata for sync-oriented stores.
- Keep backend-specific query languages available: SQL stays SQL, Gremlin stays
  Gremlin, Cypher stays Cypher, SPARQL stays SPARQL.

## Non-Goals

- Do not invent a universal query language for every database.
- Do not force graph or RDF data into tables.
- Do not hide transaction, consistency, indexing, and query semantics behind a
  fake common API.
- Do not require runtime reflection. Mapping should be generated through the
  existing `inline`/`derives` direction.

## Architecture

The shared foundation is typed codecs:

```scalascript
trait Codec[A, Repr]:
  def encode(value: A): Repr
  def decode(repr: Repr): Either[DecodeError, A]
```

Each storage backend chooses its representation:

| Store family | Codec type | Representation |
| --- | --- | --- |
| SQL | `RowCodec[A]` | row/column map plus bind parameters |
| JSON / REST | `JsonCodec[A]` | JSON object/value |
| IndexedDB | `JsonCodec[A]` or `ObjectCodec[A]` | structured-clone-compatible object |
| Server ObjectStore | `ObjectCodec[A]` | JSON object plus metadata |
| Property graph vertex | `VertexCodec[A]` | labels + properties |
| Property graph edge | `EdgeCodec[A]` | from/to + label + properties |
| RDF | `RdfCodec[A]` | triples/quads |
| Dataset / MapReduce | `DatasetCodec[A]` | local/distributed element serialization |
| Apache Spark | `SparkCodec[A]` / `SparkEncoder[A]` | Spark `Encoder[A]` and `StructType` schema |

Initial runtime foundation landed in `backend/typed-data` as a small shared
codegen/runtime module. Today it owns the emitted typed JSON facade used by
generated typed route clients:

- `_ssc_typed_json_encode(value, typeName)`
- `_ssc_typed_json_decode_response(text, contentType, typeName)`

The current bodies intentionally preserve the existing minimal JSON behavior.
The important contract is that JVM/Swing and JS/browser/Electron clients call
the same named boundary, so future `JsonCodec[A]` derivation can replace the
facade implementation without reshaping generated client methods.

The first explicit codec API also lives in `backend/typed-data`:

```scala
enum JsonValue
final case class DecodeError(path: List[String], message: String)
trait Codec[A, Repr]
trait JsonCodec[A] extends Codec[A, JsonValue]
```

It includes primitive, `List[A]`, and `Option[A]` JSON codecs,
`JsonCodec.objectCodec(...)` / `JsonCodec.field(...)` helpers for manually
writing product codecs, `derives JsonCodec` support for case classes, and
sealed ADT/sum derivation. ADTs use an explicit discriminator envelope:

```json
{"$type":"VariantName","value":{}}
```

The `value` field contains the selected variant payload encoded by that
variant's own `JsonCodec`. Case objects encode as an empty object payload.
Explicit product codecs can use `JsonFieldSpec[A]` for schema migration:
canonical field names, aliases for renamed fields, default values for missing
fields, and opt-in unknown-field rejection.
Derived JVM `JsonCodec` product codecs can use the same schema metadata through
field/class annotations:

```scala
@rejectUnknown
case class Todo(
  @key id: String,
  @fieldName("text") @aliases("title") label: String,
  done: Boolean = false
) derives JsonCodec
```

`@fieldName` selects the canonical storage name, `@aliases` are accepted on
decode, case-class default parameters become missing-field defaults, `@key`
marks identity fields for downstream stores, and `@rejectUnknown` enables
strict decoding for extra fields.

JVM/Swing generated typed route clients now use this `JsonCodec[T]` layer for
typed request encoding and typed response decoding. JS/browser/Electron typed
route clients use the same facade boundary with a generated JavaScript codec
registry for case-class and enum-case shapes; request/response type names from
`apiClients:` select the codec at runtime.

The first row-mapping API also lives in `backend/typed-data`:

```scala
enum RowValue
trait RowValueCodec[A]
final case class RowFieldSpec[A](name, aliases, default, key)
trait RowCodec[A] extends Codec[A, Map[String, RowValue]]
```

It currently supports primitive column values, nullable `Option[A]` columns, and
`derives RowCodec` for simple case classes. Explicit row codecs can use
`RowFieldSpec[A]` for canonical column names, aliases for renamed columns,
default values for missing columns, key-column metadata, case-insensitive JDBC
column lookup, and opt-in unknown-column rejection. It is the codec foundation
for SQL rows and Spark-like tabular schemas. `SqlRuntime.query[A](conn, sql,
binds)` now executes JDBC reads and decodes result rows through `RowCodec[A]`;
interpreter and JVM codegen now expose typed SQL reads as `Db.query[A](dbName,
sql, binds)`. `SqlRuntime.insert/update[A]` and interpreter/JVM
`Db.insert/update[A]` encode typed values for explicit table/key based writes.
The JVM `RowCodec` derivation consumes the same `@fieldName`, `@aliases`,
case-class defaults, `@key`, and `@rejectUnknown` annotations as `JsonCodec`.
Interpreter typed SQL helpers consume the same annotations for
`Db.query/insert/update[A]` by storing runtime case-class schema metadata.
They also consume module front-matter `schemas:` metadata, so interpreter-only
or mixed documents can keep storage mapping next to `databases:`:

```yaml
schemas:
  Person:
    rejectUnknown: true
    fields:
      id:
        key: true
      displayName:
        name: display_name
        aliases: [name]
      active:
        default: true
```

For interpreter typed SQL, a front-matter field entry overrides the matching
case-class annotation/default metadata. JVM `RowCodec` derivation still uses
annotations or explicit `RowFieldSpec[A]`; consuming front-matter there would
require a separate codegen bridge rather than plain Scala typeclass derivation.
See [`examples/typed-sql-crud.ssc`](../examples/typed-sql-crud.ssc) for a
minimal CRUD example.

User code should stay direct:

```scalascript
case class Todo(id: String, text: String, done: Boolean)
  derives JsonCodec, RowCodec, ObjectCodec

case class Module(id: String, path: String)
  derives VertexCodec

case class Imports(from: String, to: String)
  derives EdgeCodec
```

The derivation rules should use declaration order and field names by default,
with annotations/front-matter overrides for external schemas.

## Identity And Sync Metadata

Stores that synchronize or cache remote state need a common envelope:

```scalascript
case class Stored[A](
  id: String,
  value: A,
  version: Long,
  updatedAt: Instant,
  deleted: Boolean
)
```

Rules:

- Domain types may carry their own id field, for example `Todo.id`.
- Mapping metadata decides which field is the storage key.
- Sync-capable stores use `Stored[A]` internally even when user code works with
  plain `A`.
- Conflict detection uses `version`, not object equality.

## SQL Mapping

SQL mapping should be light and explicit. It maps rows and bind parameters; it
does not replace SQL.

```scalascript
case class Todo(id: String, text: String, done: Boolean) derives RowCodec

val rows: List[Todo] =
  Db.query[Todo]("server", "SELECT id, text, done FROM todos ORDER BY id", [])

Db.insert("server", "todos", todo)
Db.update("server", "todos", "id", todo.id, todo)
```

Defaults:

- case-class field name maps to column name;
- nullable columns map to `Option[A]`;
- lists/maps/nested case classes require JSON column mapping unless a backend
  provides native support;
- custom column names use annotations or mapping metadata.

## JSON, IndexedDB, And ObjectStore Mapping

JSON-like stores share the same structural mapping:

```scalascript
case class Draft(id: String, title: String, body: String)
  derives JsonCodec, ObjectCodec

awaitClient(IndexedDb.store[Draft]("drafts").put(draft))
ObjectStore.put("server", "drafts", draft)
```

The first portable object codec foundation is available in `backend/typed-data`:

```scalascript
import scalascript.typeddata.{ObjectCodec, ObjectValue, JsonValue, key}

case class Draft(@key id: String, title: String, body: String)
  derives ObjectCodec

val stored: ObjectValue = ObjectCodec[Draft].encode(Draft("d1", "Plan", "Text"))
val id: Option[String] = ObjectCodec[Draft].key(Draft("d1", "Plan", "Text"))
```

Rules:

- `JsonCodec[A]` is the canonical wire/storage format for REST and object
  stores.
- IndexedDB may store structured-clone-compatible values, but the portable
  representation should remain JSON-compatible in v1.
- JS/browser/Electron client code can use `IndexedDb.store[A](store, dbName?,
  keyField?)` today. The API returns Promises for `put`, `get`, `remove`,
  `keys`, `all`, and `clear`; ScalaScript client code awaits them with
  `awaitClient(...)`. Runtime type names come from the JS typed JSON facade.
- JVM code can use `ObjectStore.put/get/all/delete/changes[A]` today over a
  declared JDBC database. Values are encoded through `ObjectCodec[A]` into a
  JSON table and returned with `Stored[A]` version/tombstone metadata where
  needed.
- ObjectStore sync uses `ObjectCodec[A]` plus `Stored[A]` metadata. The current
  `ObjectCodec[A]` layer covers portable object values and key extraction;
  generated REST sync and browser/Electron sync helpers are available for the
  first client/server object-store path.

## Property Graph Mapping

Property graph mapping is split into vertices and edges:

```scalascript
@graphLabel("Module")
case class Module(id: String, path: String) derives VertexCodec

@graphEdge("imports")
case class Imports(from: String, to: String) derives EdgeCodec

Graph.putVertex("deps", Module("A", "a.ssc"))
Graph.putEdge("deps", Imports("A", "B"))
```

Defaults:

- `VertexValue`, `EdgeValue`, `VertexCodec[A]`, and `EdgeCodec[A]` are now
  available in `backend/typed-data`;
- `derives VertexCodec` maps the case class to labels + properties and uses
  `@key` or an `id` field as the graph id;
- `derives EdgeCodec` maps `@graphFrom` / `@graphTo` (or fields named `from` /
  `to`) to endpoints and maps the remaining fields to properties;
- graph ids are explicit;
- `backend/graph` now provides a JVM `GraphRuntime.inMemory()` backend that can
  store typed vertices and edges through `VertexCodec[A]` / `EdgeCodec[A]`;
- JVM codegen now parses `graphs:` front matter and exposes `Graph.putVertex`,
  `Graph.getVertex`, `Graph.vertices`, `Graph.putEdge`, `Graph.edges`, and
  `Graph.neighbors` over declared in-memory stores;
- `runtime/std/graph-plugin` exposes the same portable `Graph.*` surface on the
  interpreter path, storing runtime case-class values directly;
- complex traversal remains backend-specific through `Gremlin.query`,
  `Cypher.query`, or portable `Graph.*` helpers.

## RDF Mapping

RDF needs semantic mapping rather than object/table mapping:

```scalascript
@rdfClass("schema:Person")
case class Person(
  @rdfId id: Iri,
  @rdf("schema:name") name: String
) derives RdfCodec
```

Rules:

- `RdfNode`, `RdfTriple`, `RdfValue`, and `RdfCodec[A]` are now available in
  `backend/typed-data`;
- `derives RdfCodec` maps `@rdfClass` to an `rdf:type` triple, `@rdfId` /
  `@key` / `id` to the subject IRI, and `@rdf(...)` field annotations to
  predicates;
- RDF ids are IRIs or blank nodes, not arbitrary database keys.
- Field annotations map fields to predicates.
- `RdfCodec[A]` emits triples/quads and can decode query bindings when the
  result shape is complete.
- `backend/graph` now provides a JVM `GraphRuntime.inMemory()` backend that can
  store typed RDF subjects and triples through `RdfCodec[A]`;
- the generated JVM `Graph.*` facade includes `putRdf`, `getRdf`, and `triples`
  for declared in-memory RDF stores;
- Complex semantic queries remain SPARQL.

## Dataset, MapReduce, And Spark Mapping

ScalaScript already has `Dataset[T]` for local/parallel/distributed MapReduce
and an Apache Spark backend with Scala 3 native encoder derivation. The mapping
layer makes those existing data-processing surfaces participate in the same
typed mapping story as databases.

```scalascript
case class Event(userId: String, kind: String, amount: Double)
  derives JsonCodec, RowCodec

val encoded = Dataset.fromList(events)
  .map(event => DatasetCodec[Event].encode(event))

val totals =
  Dataset.fromList(encoded.collect())
    .map(json => DatasetCodec[Event].decode(json).toOption.get)
    .filter(_.kind == "purchase")
    .reduceByKey(_.userId)((a, b) => a.copy(amount = a.amount + b.amount))
```

Roles:

- `DatasetCodec[A]` is now available as the portable element codec for local,
  parallel, and distributed MapReduce. It wraps `JsonCodec[A]` by default, so
  existing `derives JsonCodec` domain types automatically get a stable
  `JsonValue` representation for Dataset element movement. `DatasetCodec`
  also provides `encodeAll` / `decodeAll` batch helpers with indexed decode
  error paths, plus `DatasetWirePartition` and partition encode/decode helpers
  for distributed worker payloads. `std/mapreduce` can now move those payloads
  directly through `runDistributedWire`; worker handlers on that path consume
  and return `JsonValue`.
- `SparkSchemaCodec[A]` is now available for Spark-like schema metadata in the
  shared typed-data layer. It derives case-class field names from the same
  `@fieldName` annotation used by `JsonCodec` / `RowCodec`, preserves `@key`
  metadata, maps primitive/collection shapes to `SparkSchemaType`, and marks
  `Option[A]` fields nullable. Each `SparkSchemaField` also keeps the original
  Scala field name so SparkGen can read external column names and then alias
  them back before applying Spark's `Encoder[A]`.
- SparkGen owns the real Spark `Encoder[A]` / `StructType` path used by
  `Dataset.fromJsonAs`, `fromCsvAs`, `fromParquetAs`, and `fromTable`. When a
  `SparkSchemaCodec[A]` is in scope, those readers now build the read schema
  from the shared typed-data metadata and project storage names such as
  `display_name` back to Scala field names such as `name` before `.as[A]`.
  Without `SparkSchemaCodec[A]`, the readers keep the existing Encoder-derived
  schema behavior.
- `RowCodec[A]` and Spark schema derivation should converge where possible:
  both describe tabular data, but Spark also needs distributed execution and
  Catalyst-compatible schemas.

Data-flow examples the mapping layer should make direct:

```scalascript
val users: Dataset[User] = Db.dataset[User]("server", "SELECT * FROM users")
users.toTable("analytics_users")

val cached: Dataset[Todo] = ObjectStore.collection[Todo]("todos").toDataset()
val graphVertices: Dataset[Module] = Graph.vertices[Module]("deps").toDataset()
```

Rules:

- Dataset/Spark mapping is about typed data movement and schema derivation, not
  a new query language.
- Local/distributed MapReduce keeps its existing `Dataset[T]` API.
- Spark keeps its existing Spark SQL/DataFrame/MLlib surfaces.
- The common mapping layer supplies codecs, schemas, decode errors, and naming
  conventions so data can move between storage and processing surfaces without
  hand-written adapters for every case class.

## Validation And Errors

Decoding errors should be structured and path-aware:

```scalascript
DecodeError(
  path = "$.address.postcode",
  expected = "Int",
  actual = "String",
  message = "Expected integer postcode"
)
```

Mapping APIs should distinguish:

- malformed storage data;
- missing required fields/columns/properties;
- unknown enum/ADT discriminator;
- backend capability mismatch;
- optimistic version conflict.

## Schema Evolution

Mapping needs migration hooks, but not a full migration framework in v1:

```scalascript
case class TodoV2(id: String, text: String, done: Boolean, priority: Int = 0)
  derives JsonCodec, RowCodec
```

Initial rules:

- added fields may have defaults;
- removed fields are ignored by default in JSON/ObjectStore mapping;
- SQL schema migration remains explicit SQL;
- graph/RDF mapping changes are explicit backend migrations.

## Standard Library Shape

The public API should be backend-specific at the query boundary and shared at the
mapping boundary:

```scalascript
Db.query[Todo]("server", "...", params)
IndexedDb.store[Draft]("drafts").get(id)
ObjectStore.collection[Todo]("todos").put(todo)
Graph.vertices[Module]("deps").get(id)
Sparql.query[Person]("kg", "SELECT ...")
Dataset.fromJsonAs[Event]("/data/events/*.json")
Spark.table[User]("users")
```

All of these rely on derived or explicit codecs, but they do not pretend to have
the same query model.

## Phases

1. **Spec + examples** — document the codec hierarchy, default derivation rules,
   identity/version envelope, and per-store examples.
2. **Core codec foundation** — partially landed: `JsonValue`, `DecodeError`,
   `Codec[A, Repr]`, `JsonCodec[A]`, primitive/list/option instances,
   explicit object-codec helpers, `derives JsonCodec` for case classes,
   discriminator-based sealed ADT derivation, explicit `JsonFieldSpec`
   rename/default/key/unknown-field helpers, and derived product-codec support
   for `@fieldName`, `@aliases`, case-class defaults, `@key`, and
   `@rejectUnknown`. Follow-up landed: JS/browser/Electron generated typed
   clients pass request/response type names through the shared facade and use a
   generated runtime codec registry for known case-class/enum shapes. Remaining:
   broader cross-store codec coverage.
3. **SQL row mapping** — partially landed: `RowValue`, `RowValueCodec[A]`,
   primitive/nullable column codecs, and `derives RowCodec` for simple case
   classes. Follow-up landed: `SqlRuntime.query[A]` decodes JDBC result rows
   through `RowCodec[A]`. Follow-up landed: JVM codegen exposes typed
   `Db.query[A]` for programmatic SQL reads. Follow-up landed: `SqlRuntime` and
   JVM `Db` expose typed `insert/update[A]` helpers over `RowCodec[A]`.
   Follow-up landed: interpreter `Db.query/insert/update[A]` now mirrors the
   typed SQL read/write API using runtime case-class field metadata. Follow-up
   landed: `RowFieldSpec[A]` adds explicit column aliases, defaults, key
   metadata, case-insensitive lookup, and unknown-column rejection to the JVM
   `RowCodec`/`SqlRuntime` path. Follow-up landed: derived JVM `RowCodec`
   consumes `@fieldName`, `@aliases`, case-class defaults, `@key`, and
   `@rejectUnknown`. Follow-up landed: interpreter typed SQL stores the same
   schema metadata and uses it for `Db.query/insert/update[A]`. Follow-up
   landed: `schemas:` front-matter parses into AST/IR and interpreter typed SQL
   consumes it for aliases, defaults, key metadata, canonical storage names, and
   unknown-column rejection. Remaining: cross-store codecs.
4. **Object/IndexedDB mapping** — partially landed: `ObjectValue`,
   `ObjectFieldSpec[A]`, and `ObjectCodec[A]` provide portable object/document
   mapping over `JsonCodec`, including explicit and derived case-class codecs,
   schema annotations, defaults, key extraction, and unknown-field rejection.
   Remaining: typed IndexedDB stores, server ObjectStore collections, and sync.
5. **Graph mapping** — landed 2026-05-26: `VertexCodec[A]` and `EdgeCodec[A]`
   derive simple property-graph vertex/edge mappings. Follow-up landed
   2026-05-26: `backend/graph` adds the portable `PropertyGraphBackend` /
   `RdfGraphBackend` contracts plus an in-memory JVM backend. Follow-up landed
   2026-05-26: `graphs:` metadata survives AST/IR/.sscc and JvmGen emits a
   `Graph.*` facade for declared in-memory graph stores. Follow-up landed
   2026-05-26: `runtime/std/graph-plugin` mirrors the portable `Graph.*`
   facade for the interpreter path. Remaining: TinkerGraph/RDF4J adapters and
   production graph providers.
6. **RDF mapping** — landed 2026-05-26: `RdfCodec[A]` derives simple RDF
   triple mappings with predicate/class/id annotations.
7. **Dataset/Spark mapping integration** — partially landed 2026-05-26:
   `DatasetCodec[A]` now lives in `backend/typed-data`, derives from
   `JsonCodec[A]`, provides batch encode/decode helpers, and is demonstrated by
   `examples/dataset-typed-mapping.ssc` through the JVM Dataset runtime.
   Follow-up landed 2026-05-26: `SparkSchemaCodec[A]` now lives in
   `backend/typed-data`, derives Spark-like schema metadata from case classes
   using shared `@fieldName`, `@key`, and `Option` nullability conventions, and
   is demonstrated by `examples/spark-schema-mapping.ssc`. Follow-up landed
   2026-05-26: SparkGen typed readers consume `SparkSchemaCodec[A]` when it is
   in scope, build the Spark read schema from shared metadata, and alias storage
   column names back to Scala case-class fields before `.as[A]`; see
   `examples/spark-shared-schema-reader.ssc`. Follow-up landed 2026-05-26:
   `DatasetCodec[A]` now exposes `DatasetWirePartition`,
   `DatasetPartition[A]`, and partition-level encode/decode helpers for
   distributed MapReduce worker payloads; see
   `examples/distributed-dataset-codec.ssc`. Follow-up landed 2026-05-26:
   `std/mapreduce` now exposes `runDistributedWire`,
   `WireProcessPartition`, and `WirePartitionResult`, with
   `examples/distributed-dataset-wire-protocol.ssc` exercising
   `DatasetWirePartition` payloads through local actor workers. Remaining:
   typed shuffle payloads and higher-level typed distributed Dataset helpers.
8. **Examples + conformance** — add one domain type persisted through SQL,
   ObjectStore/IndexedDB sync, graph vertices/edges, and RDF where applicable.
   Include a data-processing example that reads typed data from SQL/ObjectStore
   into `Dataset[T]` and runs locally, distributed, and on Spark where supported.

## Testing Strategy

- Derivation tests for product types, sealed ADTs, `Option`, lists, maps, nested
  case classes, defaults, renamed fields, and unknown fields.
- Round-trip tests per codec family.
- Backend integration tests for `Db.query[A]`, `IndexedDb.store[A]`,
  `ObjectStore.collection[A]`, `Graph.vertices[A]`, `Sparql.query[A]`,
  `Dataset[A]`, and Spark typed readers/encoders.
- Negative tests for missing required fields, wrong types, unsupported backend
  capabilities, and version conflicts.

## Open Questions

- Should the root typeclass be named `Codec`, `Mapper`, `Persisted`, or split
  explicitly by representation only?
- Should `JsonCodec` be the prerequisite for `ObjectCodec`, or can object stores
  have non-JSON structured-clone values from the beginning?
- Should mapping annotations use Scala-style annotations, front-matter schema
  blocks, or both?
- Should `derives Codec` mean JSON only, or derive a bundle of common codecs?
