# Graph Storage

Status: **draft / planning**. This document specifies planned graph database
support for ScalaScript across embedded, server, and remote backends.

## Goals

- Support graph-shaped data as a first-class persistence option beside SQL,
  client-local storage, and object stores.
- Cover both common graph families:
  - **property graphs**: vertices, edges, labels, properties; queried with
    Gremlin, Cypher, or a small ScalaScript graph API;
  - **RDF graphs**: triples/quads, IRIs, literals; queried with SPARQL.
- Allow the same `.ssc` app to choose embedded graph storage for local/dev
  apps or server/remote graph storage for production deployments.
- Keep the first implementation small and JVM-friendly.

## Non-Goals

- Do not implement a new graph database engine in ScalaScript.
- Do not make one graph model hide the other. Property graphs and RDF solve
  related but different problems.
- Do not require Neo4j, JanusGraph, CouchDB, or any external service for the
  bootstrap implementation.
- Do not promise that every graph query language is supported on every backend.

## Graph Models

### Property Graph

Use property graphs for application relationships, traversal-heavy domains,
recommendation-style queries, access-control graphs, dependency graphs, social
graphs, fraud/risk links, and "find paths between entities" workloads.

Canonical data model:

```text
Vertex(id, labels, properties)
Edge(id, from, to, label, properties)
```

Useful query surfaces:

- **Gremlin/TinkerPop**: traversal API with many backend providers; good
  interoperability story for JVM graph systems.
- **Cypher/openCypher/GQL-like syntax**: familiar pattern matching, strongest
  ecosystem around Neo4j and compatible databases.
- **ScalaScript graph API**: small typed API for basic CRUD/traversal that can
  lower to embedded storage first and richer backends later.

### RDF Graph

Use RDF when the domain is semantic-web/linked-data shaped: ontologies, triples,
vocabularies, provenance, external knowledge graphs, SPARQL endpoints, and data
that benefits from global IRIs.

Canonical data model:

```text
Triple(subject, predicate, object)
Quad(subject, predicate, object, graph)
```

Useful query surface:

- **SPARQL** over an RDF repository. On the JVM, RDF4J provides embedded
  repositories and a standard API for SPARQL execution.

## Front Matter

Planned configuration should sit beside `databases:` and `objectStores:`:

```yaml
---
graphs:
  deps:
    model: property
    side: server
    backend: embedded-tinkergraph
  kg:
    model: rdf
    side: server
    backend: rdf4j-memory
  prodGraph:
    model: property
    side: server
    backend: neo4j
    uri: bolt://graph.internal:7687
    user: ${env:NEO4J_USER}
    password: ${env:NEO4J_PASSWORD}
---
```

Rules:

- `side: server` graph stores are opened by the JVM backend.
- `side: client` graph stores are allowed only for future browser-local graph
  runtimes; they are not part of the first implementation.
- A backend declares which query languages it supports.
- Build-time diagnostics should reject unsupported combinations, for example
  SPARQL against a property graph backend or Cypher against an RDF backend.

## Backend Options

| Backend family | Embedded? | Server/remote? | Best fit | Notes |
| --- | --- | --- | --- | --- |
| TinkerPop TinkerGraph | Yes | No | Tests, demos, small in-memory property graphs | Minimal first property-graph backend |
| TinkerPop Gremlin providers | Depends on provider | Yes | Backend-neutral property graph traversal | Gremlin can evaluate locally or against remote graph systems |
| Neo4j | Java embedded and server modes | Yes | Production property graph, Cypher, rich tooling | Good production target, heavier bootstrap dependency |
| JanusGraph | JVM/server deployment over storage backends | Yes | Very large distributed property graphs | Uses storage backends such as Cassandra, HBase, BerkeleyDB |
| RDF4J | Yes | Yes through repositories/endpoints | RDF/SPARQL, semantic data | Good first RDF backend on JVM |
| External managed graph services | No | Yes | Cloud production deployments | Access through drivers/protocols; not bootstrap requirement |

## Recommended First Implementation

Start with two small server-side backends:

1. **Property graph v1: TinkerGraph/TinkerPop**
   - embedded/in-memory JVM graph;
   - Gremlin traversal support;
   - good for examples, tests, local tooling, and dependency graphs;
   - no external server required.
2. **RDF graph v1: RDF4J memory/native repository**
   - embedded JVM RDF repository;
   - SPARQL query support;
   - covers semantic/knowledge-graph use cases without inventing RDF support.

Then add production adapters:

- Neo4j driver for server property graphs and Cypher;
- JanusGraph/TinkerPop provider support for distributed graphs;
- RDF4J HTTP repository or GraphDB-style RDF4J-compatible server for RDF.

This gives a good bootstrap path: the language gains graph semantics and tests
without forcing users to install a graph server, while still leaving an obvious
route to production graph databases.

## Standard Library Shape

Small typed API for portable graph operations:

```scalascript
Graph.addVertex("deps", id = "A", labels = ["Module"], props = Map("path" -> "a.ssc"))
Graph.addEdge("deps", from = "A", to = "B", label = "imports", props = Map())
val next = Graph.neighbors("deps", "A", edge = "imports")
```

Query-language-specific escape hatches:

```scalascript
Gremlin.query("deps", "g.V().hasLabel('Module').out('imports').values('path')")
Cypher.query("prodGraph", "MATCH (a:Module)-[:imports]->(b) RETURN b.path")
Sparql.query("kg", "SELECT ?s WHERE { ?s ?p ?o } LIMIT 10")
```

The portable `Graph.*` API should be enough for CRUD, neighbors, simple path
queries, and examples. Complex traversal/pattern/semantic queries use the
backend's native query language.

## Client/Server Use

For Electron/React/custom browser clients, graph storage should normally live
on the server first:

- client calls REST routes such as `/api/graph/search` or generated graph query
  endpoints;
- server executes Gremlin/Cypher/SPARQL against the configured graph backend;
- client may cache query results in IndexedDB, client-local SQL, or the
  client/server object store sync layer.

Client-local graph storage can be considered later for offline-first apps, but
browser-local graph engines are a separate capability and should not block the
server-side graph milestone.

## Security

- Do not expose arbitrary query execution endpoints by default.
- If raw Gremlin/Cypher/SPARQL query endpoints are enabled, require explicit
  front-matter opt-in and route-level authorization.
- Prefer typed route handlers or named prepared queries for user-facing apps.
- Treat graph query text like SQL: bind parameters where supported and never
  concatenate untrusted input into query strings.

## Phases

1. **Spec + examples** — document `graphs:` front matter, models, backend
   matrix, and standard-library API.
2. **Graph SPI** — define a small graph backend contract with capability flags:
   property graph, RDF, Gremlin, Cypher, SPARQL, embedded, remote.
3. **Embedded property graph** — add TinkerGraph/TinkerPop-backed server graph
   support and a dependency-graph example.
4. **Embedded RDF graph** — add RDF4J memory/native repository support and a
   SPARQL example.
5. **Server adapters** — add Neo4j driver/Cypher support and optional RDF4J HTTP
   repository support.
6. **Full-stack examples** — Electron/React frontend queries server graph routes
   and caches selected results locally.

## Testing Strategy

- Unit-test graph front-matter parsing and capability diagnostics.
- Unit-test portable `Graph.*` operations against embedded TinkerGraph.
- Unit-test SPARQL queries against embedded RDF4J memory/native repositories.
- Smoke-test a JVM REST backend serving graph query results to a frontend.
- Add conformance tests only for portable `Graph.*`; native Gremlin/Cypher/SPARQL
  behavior belongs to backend-specific suites.

## Open Questions

- Should `graphs:` share the existing `databases:` connection registry shape, or
  use a separate registry?
- Should Cypher support target Neo4j first, or wait for a broader GQL/openCypher
  abstraction?
- Should graph query fenced blocks exist (`gremlin`, `cypher`, `sparql`), or
  should v1 use only function calls?
- Should embedded graph persistence be in-memory only for v1, or include a
  file-backed option immediately?
