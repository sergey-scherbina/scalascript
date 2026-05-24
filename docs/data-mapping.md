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
Db.update("server", "todos", key = todo.id, value = todo)
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

IndexedDb.store[Draft]("drafts").put(draft)
ObjectStore.collection[Draft]("drafts").put(draft)
```

Rules:

- `JsonCodec[A]` is the canonical wire/storage format for REST and object
  stores.
- IndexedDB may store structured-clone-compatible values, but the portable
  representation should remain JSON-compatible in v1.
- ObjectStore sync uses `ObjectCodec[A]` plus `Stored[A]` metadata.

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

- a vertex codec maps the case class to labels + properties;
- an edge codec maps source id, target id, label, and properties;
- graph ids are explicit;
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

- RDF ids are IRIs or blank nodes, not arbitrary database keys.
- Field annotations map fields to predicates.
- `RdfCodec[A]` emits triples/quads and can decode query bindings when the
  result shape is complete.
- Complex semantic queries remain SPARQL.

## Dataset, MapReduce, And Spark Mapping

ScalaScript already has `Dataset[T]` for local/parallel/distributed MapReduce
and an Apache Spark backend with Scala 3 native encoder derivation. The planned
mapping layer should make those existing data-processing surfaces participate in
the same typed mapping story as databases.

```scalascript
case class Event(userId: String, kind: String, amount: Double)
  derives JsonCodec, RowCodec, DatasetCodec, SparkCodec

val events: Dataset[Event] =
  Dataset.fromJsonAs[Event]("/data/events/*.json")

val totals =
  events
    .filter(_.kind == "purchase")
    .reduceByKey(_.userId)((a, b) => a.copy(amount = a.amount + b.amount))
```

Roles:

- `DatasetCodec[A]` is the portable element codec for local, parallel, and
  distributed MapReduce. It covers worker-message serialization, file-backed
  reads/writes where applicable, and conformance fixtures.
- `SparkCodec[A]` or `SparkEncoder[A]` is the Spark-specific mapping to
  `Encoder[A]` and `StructType`. It should reuse the same field-name,
  nullability, default, and rename conventions as `RowCodec[A]`.
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
2. **Core codec derivation** — add `derives JsonCodec` and shared
   `DecodeError`/path infrastructure.
3. **SQL row mapping** — add `derives RowCodec`, `Db.query[A]`, and
   insert/update helpers for simple case classes.
4. **Object/IndexedDB mapping** — add `ObjectCodec[A]`, typed IndexedDB stores,
   and server ObjectStore collections.
5. **Graph mapping** — add `VertexCodec[A]` and `EdgeCodec[A]` for property
   graph vertices/edges.
6. **RDF mapping** — add `RdfCodec[A]` with predicate/class/id annotations.
7. **Dataset/Spark mapping integration** — align existing `Dataset[T]`,
   distributed MapReduce serialization, Spark encoder/schema derivation, and
   typed table/file readers with the shared codec conventions.
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
