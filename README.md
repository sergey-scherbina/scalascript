# ScalaScript

A language where **Markdown is syntax, not decoration** — `.ssc` files are
executable documents combining YAML front-matter, Markdown prose, and
Scala 3 code blocks.

`.ssc` files support two code-block languages:

| Annotation | Language | Backends |
|------------|----------|----------|
| ` ```scalascript` | ScalaScript dialect — effects, handlers, content helpers, TCO | interpreter · JS transpiler · JVM · **Rust (native binary; up to R.5 web toolkit)** · WebAssembly |
| ` ```ssc` | Alias for `scalascript` | interpreter · JS transpiler · JVM · Rust |
| ` ```scala` | Standard Scala 3 — no ScalaScript extensions | interpreter · **Scala.js** (JS) · JVM · Rust (passthrough) |
| ` ```rust` | Standard Rust — passthrough verbatim into the emitted Cargo crate | **Rust** |

Standard `scala` fences run in document order when a file uses only standard
Scala fences. In mixed `scalascript`/`scala` documents, standard `scala` fences
are illustrative by default; add `runScalaFences: true` (or
`run-scala-fences: true`) to YAML front-matter to run both languages together
in source order.

````ssc
---
name: hello
version: 1.0.0
---

# Hello World

```scalascript
def greet(name: String): String = s"Hello, $name!"
println(greet("World"))
```
````

```text
Hello, World!
```

After [installing](#installing-as-a-binary), every `.ssc` file is a first-class executable:

```bash
$ ssc hello.ssc
Hello, World!

# The shebang line (#!/usr/bin/env ssc) lets you run files directly:
$ chmod +x hello.ssc && ./hello.ssc
Hello, World!

# Watch mode — re-runs on every save:
$ ssc watch hello.ssc
```

## Quick Start

**Standard 2.1 runtime requirement:** Java 21+. Scala CLI/scalac/javac are not
required by plain `ssc` (or its `ssc-standard` alias); Scala CLI is an optional
tools-tier dependency reached only through explicit `ssc-tools` commands.
[Node.js](https://nodejs.org) is needed for the JS backend, and
[sbt](https://www.scala-sbt.org) for contributor builds.

The standard self-hosted route supports named indented `given ... with`
objects and explicit property/method calls on both its VM and direct-ASM lanes.
Ordinary `object Name:` bodies retain their first and later members with the
same owned method/property semantics as explicitly braced objects.
Indented extension bodies close at dedent and retain selected-call ownership
when imported through another `.ssc` module.
An imported symbolic extension such as parser choice `|` handles non-primitive
values while `6 | 3` remains ordinary integer bitwise OR.
Exact top-level `summon[TC[T]]` resolves a matching named given. Product
`Mirror.Of[T]` / `Mirror.ProductOf[T]` metadata and custom `derives TC` evidence
are self-hosted on the standard VM/direct-ASM/build-jvm route; anonymous
givens, sum Mirrors, and general implicit search remain migration gaps.
Parameterless `def value: T = ...` references evaluate the nullary definition,
while explicit `def value(): T = ...` keeps its required call. List `map` and
`flatMap` spread portable pair elements into matching two-parameter callbacks.
Explicit `direct[Option]` and `direct[List]` blocks are compiler-free too:
fresh assignments bind, Option short-circuits, and List expansion keeps source
order; broader direct inference and monads remain compatibility-only gaps.
Named case-class `.copy(field = value)` is likewise native: labels are
preserved independently of call order, omitted fields are retained, and the
receiver/overrides evaluate exactly once from left to right.
Top-level `case object` declarations are stable portable nullary values across
imports, direct references, equality, and pattern matching.
Its built-in `math` object is also portable on both lanes (`Pi`, `E`, `abs`,
`sqrt`, `pow`, `floor`, `ceil`, and `round`) without loading the tools tier.
Nested constructor patterns fall through to later source arms correctly, so
repeated outer case-class tags can be distinguished by inner `Some`/`None`.

```bash
git clone https://github.com/sergey-scherbina/scalascript
cd scalascript

# Standalone install, once releases are published:
cs install ssc --channel https://releases.scalascript.io/coursier.json

# Contributor checkout only: builds and stages local bin/ launchers.
./install.sh --dev

# Pipe sops-decrypted secrets into a script (${sops:key} references in databases:)
sops -d secrets.enc.yaml | ssc myapp.ssc

# Default, physically slim 2.1 tier: self-hosted frontend + v2 VM / direct ASM
bin/ssc run examples/hello.ssc
bin/ssc run --bytecode examples/hello.ssc
# Descriptive alias with the identical standard classpath
bin/ssc-standard run examples/hello.ssc

# Explicit native provider: adds only the selected provider classpath while
# keeping StandardMain + the self-hosted frontend/VM or direct ASM
bin/ssc-provider pdf run examples/invoice-pdf.ssc
bin/ssc-provider pdf run --bytecode examples/pdf-extract-demo.ssc
bin/ssc-provider mcp run examples/mcp-client-discover.ssc
RDF4J_URL=http://localhost:8080/rdf4j-server/repositories/kg \
  bin/ssc-provider graph-rdf4j run examples/graph-rdf4j-http-storage.ssc
SWIFT_AGGREGATOR_URL=http://localhost:9000 SWIFT_API_KEY=secret \
  bin/ssc-provider swift run examples/international-bank-rails.ssc

# Roll back through the explicit optional tools/compatibility tier
bin/ssc-tools run --v1 examples/hello.ssc
bin/ssc-tools run --compat-frontend examples/hello.ssc
# Compiler/target-owned documents stay on explicit tools lanes
bin/ssc-tools run --v1 examples/quoted-macro-constfold.ssc
bin/ssc-tools emit-wasm examples/wasm-scalascript.ssc
bin/ssc-tools run-jvm examples/x402-client.ssc
# Plain ssc never delegates; this fails early and names ssc-tools:
bin/ssc run --v1 examples/hello.ssc

# Watch mode — re-run on every file change
bin/ssc-tools watch examples/hello.ssc

# Interactive REPL
bin/ssc-tools repl

# Transpile to JavaScript and run via Node.js
bin/jssc examples/hello.ssc

# Compile to JVM bytecode and run via Scala 3 / scala-cli
bin/sscc examples/hello.ssc

# Build a deterministic self-contained JAR directly through native CoreIR + ASM
bin/ssc build-jvm examples/hello.ssc -o hello.jar
java -jar hello.jar

# Contributor/release check: remove the tools tier and prove the remainder works
tests/e2e/v21-slim-distribution-gate.sh \
  --report target/v21-slim-distribution.tsv

# Also remove java.compiler/jdk.compiler from the resolvable module graph
tests/e2e/v21-jre-module-gate.sh --report target/v21-jre-module.tsv

# Authoritative negative release: no tools/compiler/scalameta files or commands,
# no compiler modules, exhaustive frontend + VM/ASM + providers/server
tests/e2e/v21-negative-toolchain-release-gate.sh \
  --report target/v21-negative-toolchain-release.tsv

# Compile to a native binary via Rust + Cargo (requires `cargo` on PATH;
# see docs/rust-backend.md for the capability surface).
bin/ssc-tools build-rust examples/hello.ssc && ./hello
bin/ssc-tools run-rust   examples/hello.ssc

# Inspect or run the checked ScalaScript 2 CoreIR Swift package (Swift 6+)
bin/ssc-tools emit-swift --target macos -o appcore-swift examples/swift/appcore-money.ssc
bin/ssc-tools run-swift examples/swift/appcore-money.ssc
bin/ssc-tools run-swift examples/swift/appcore-nativeui.ssc # ABI debug CLI; emit also writes the reactive AppleApp sources
bin/ssc-tools build --target macos examples/swift/appcore-nativeui.ssc # real Xcode .app
bin/ssc-tools run --target ios examples/swift/appcore-nativeui.ssc     # build/install/launch Simulator app

# Run on Apache Spark (Spark 4.0.0 / Scala 3.7.1, local[*] by default)
bin/ssc-spark examples/spark-encoder-demo.ssc

# Run all examples
./examples/run-all.sc

# Start HTTP server for the examples browser
bin/http.ssc
```

## Documentation

> Full categorized index of all docs: **[docs/README.md](docs/README.md)**.

**Getting started**

| | |
|---|---|
| [Project Summary](docs/project-summary.md) | One-page overview — what ScalaScript is, the five backends, and the headline capabilities |
| [User Guide](docs/user-guide.md) | Installation, CLI commands, language basics, HTTP, effects, actors, Apache Spark, WebAssembly, frontend frameworks, cluster, x402 — practical day-to-day reference |
| [Tutorial 1 — Todo API](docs/tutorial.md#tutorial-1-collaborative-todo-api) | Build a todo API step by step — data model → REST → auth → WebSocket → TLS → MCP |
| [Tutorial 2 — Spark ETL](docs/tutorial.md#tutorial-2-etl-pipeline-with-apache-spark) | End-to-end Spark pipeline — `Dataset[T]` → `@SqlFn` UDF → `@TempView` → Delta Lake |
| [Tutorial 3 — Frontend Toolkit demo](docs/tutorial.md#tutorial-3-frontend-toolkit-demo) | Compile + emit + serve a toolkit SPA through React / Vue / Solid / Custom + SSR via `ssc serve` |
| [Tutorial 4 — Full-stack .ssc](docs/tutorial.md#tutorial-4-full-stack-ssc--sqlite-todo-app-with-reactive-ui) | SQLite + REST API + reactive `runtime/std/ui` frontend in one `.ssc` file — `databases:`, `sql` blocks, `serve(lower(tree, theme), port)` |

**Language reference**

| | |
|---|---|
| [Language Specification](SPEC.md) | Formal grammar, type system, semantics, all language constructs |
| [UniML](specs/uniml.md) / [YAML 1.2.2 adapter](specs/uniml-yaml.md) | Cross-platform token-as-instruction tree VM with lossless JSON/XML/YAML dialect modules; YAML preserves presentation syntax and exposes explicit Core/JSON/Failsafe plus bounded alias projection |
| [Direct Syntax](docs/direct-syntax.md) | Do-notation over any monad — `direct[M] { x = expr }`, `.!` postfix bind, effect-row unions; explicit Option/List blocks also run on the compiler-free standard 2.1 path |
| [Coroutines & Generators](specs/coroutines.md) | Coroutine primitive underlying one-shot effects and generators |
| [Algebraic Effects spec](specs/algebraic-effects.md) | Typed effect rows — `!` operator, `multi effect`, Rémy-style unification, typed stdlib, `Reader[R]`, `NonDet` |
| [Error Handling](docs/error-handling.md) | Checked errors via `throws[A, E]`, `attemptCatch`, `HasStackTrace` |
| [Metaprogramming](specs/metaprogramming.md) / [v2 macros](specs/arch-metaprogramming-v2.md) | `inline`, `derives`, `compiletime.*`; partially implemented restricted quoted macros for `ssc link`, interpreter `ssc run`, and the JVM + JS backends, incl. `Expr.asValue match` compile-time constant folding, with targeted unsupported-body diagnostics; product `Mirror.Of[T]` and user `derived(m: Mirror)` typeclasses also run on the compiler-free standard 2.1 path |
| [Markdown Content Introspection](specs/markdown-content-introspection.md) | Markdown-to-frontend content layer: Markdown/YAML/GFM tables/fenced language blocks lower to `std/ui` through `contentToolkitNode()` or explicit `contentToolkitBlock(id)` / `contentToolkitSection(id)` selectors, including declarative `yaml @ui=toolkit` controls, Markdown-authored tables as `TableNode`, `contentData(id)` lookup, explicit `contentComponent(...)` registries for `component=<name>` / `data=<id>` metadata, pure-ScalaScript `contentBind(value, bindings)` resolution for Markdown `${name}` placeholders (including compiler-free native VM/ASM and `build-jvm`), and cross-backend `std/content` lookup helpers `contentCurrentSection()`, `contentSection(id)`, `contentBlock(id)`, `contentMetadata(path)`, `contentPlainText(value)`, `contentToMarkdown(value)`, plus direct imported module content namespaces via `contentModule(namespace)` for interpreter, JS, and JVM targets; `.scir` and `.sscc` preserve the current-module `DocumentContent` snapshot when present; low-level `contentView(contentDocument())` remains available |
| [Markdown Content Linked Namespaces](specs/markdown-content-linked-namespaces.md) | Focused contract for reusing Markdown-authored sections, blocks, data, and `content:` metadata from direct `.ssc` imports through `contentModules()` and namespace-scoped lookup helpers; the [2.1 native structural provider](specs/v2.1-native-content.md) carries the same direct-import content through VM, ASM, standard/slim/JRE, and deterministic `build-jvm` without a host Markdown parser |
| [Markdown Content Native Client Parity](specs/markdown-content-native-client-parity.md) | Same Markdown-authored toolkit controls render through Swing, JavaFX, and SwiftUI native frontends by normalizing shared `std/ui/lower` `View.Element` output into native text fields, checkboxes/toggles, buttons, and signal state |
| [DSL Authoring](specs/dsl.md) | Parser combinators, multi-pass pipelines, `runtime/std/parsing/*` |

**Platform & runtime**

| | |
|---|---|
| [Architecture](docs/architecture.md) | Compiler pipeline, module structure, backend SPI |
| [Target Backends](docs/targets.md) | Interpreter · JS transpiler · JVM · **Rust (Cargo crate → native binary)** — capabilities and tradeoffs |
| [Rust backend guide](docs/rust-backend.md) | `ssc emit-rust` / `build-rust` / `run-rust` — compile `.ssc` to a native binary via Cargo, with mixed ` ```rust ` fence blocks |
| [Actors & Distributed](specs/actors-dist.md) | Spawn, supervise, cluster over WebSocket |
| [Distributed Wire Protocol](specs/distributed-wire-protocol.md) | Planned: opt-in JSON/MsgPack/CBOR internal wire layer for actors, Dataset/DStream, typed route clients/RPC, object sync, security, compression, and compatibility |
| [Distributed Runtime](specs/distributed-runtime.md) | Planned: canonical local/remote/distributed runtime model for streams, actors, typed async calls, cluster lifecycle, cluster operations, code deployment, and worker bundles |
| [Dataset / MapReduce](specs/mapreduce.md) | `Dataset[T]` — local, parallel, distributed |
| [Apache Spark backend](specs/spark-streaming.md) | Spark 4 + Scala 3.7.1: `Dataset[T]`, `sql` blocks, `@SqlFn` UDFs, Structured Streaming, Delta Lake, Hive catalog, MLlib — see §13 of the User Guide |
| [Frontend Framework SPI](specs/frontend-framework-spi-plan.md) | React / Vue / Solid / custom frontend backends; design doc has historical planning context |
| [Frontend Toolkit](specs/frontend-toolkit-spec.md) | High-level declarative widgets: Forms, Routing, Widgets v2, Table |
| [Cluster Management](specs/cluster-management.md) | Bully election, phi-accrual failure detector, federation, Raft, ZooKeeper client |
| [x402 micropayments](specs/x402.md) | HTTP 402 protocol, Ethereum + Cardano flows, MCP × x402 paid tools |
| [Bank Rails v1.54](specs/bank-rails.md) | SEPA CT/DD, ACH, Pix, FedNow — `BankRailsProvider` SPI, mandate lifecycle, async settlement |
| [International Bank Rails v1.55](specs/international-bank-rails.md) | SWIFT MT103 + pacs.008, SEPA Instant, UK FPS/BACS/CHAPS, India UPI, Japan Zengin, Singapore PayNow |
| [Browser SQL](specs/browser-sql.md) | Cross-backend `sql` fenced blocks (JS / Node / Wasm / JVM) |
| [Electron SQL](specs/electron-sql.md) | Current `sqlite:` behavior in Electron desktop bundles, including the localStorage-backed renderer fallback |
| [Electron Persistence Bridge](specs/electron-persistence-bridge.md) | Main/preload bridge for durable Electron SQLite under `app.getPath("userData")` |
| [SclJet](specs/scljet.md) | Pure ScalaScript SQLite-format engine: M2 opens clean files through an abstract VFS, validates schema/freelist/pointer maps, and traverses table/index B-trees; writes, recovery, WAL, and SQL remain later gates |
| [Secret Resolvers](secret-resolvers.md) | `${env:}` · `${file:}` · `${sops:}` · `SecretResolver` SPI for Vault / AWS SM / GCP / Doppler / 1Password |
| [MCP Support](specs/mcp.md) | MCP server tools + resources, MCP client |
| [Rozum / Agent SDK](specs/rozum-integration.md) | Generic app-owned agent loop over a stateless OpenAI-compatible rozum gateway, with strict tool schemas and in-process tool handlers |
| [Rozum Agent Streaming](specs/rozum-agent-streaming.md) | `runAgentStream` / `collectAgentStream` for OpenAI-compatible SSE text/tool-call deltas with ordered `AgentEvent`s |
| [Rozum Agent Endpoint Pool](specs/rozum-agent-endpoint-pool.md) | `AgentEndpointPool` + `runAgentPool` / streaming pool variants for bounded ordered failover across multiple rozum gateways |
| [Rozum Agent Schema Derivation](specs/rozum-agent-schema-derivation.md) | `AgentSchema[A] derives` + `agentToolFor[A]` for typed tool inputs with OpenAI-compatible JSON Schema parameters |
| [Markdown as Syntax](docs/markdown-as-syntax.md) | How Markdown constructs map to AST nodes |
| [SwiftUI / iOS / macOS](specs/swiftui.md) | V2 checked-CoreIR Swift/AppCore generation with authoritative manifest entrypoints, the standard locale/JsonValue/keyed `lower/serve` toolkit path, native SwiftUI observation/rendering, persisted/online adapters, lifecycle-safe URLSession fetch/actions/tables, isolated trusted HTML, deterministic verified macOS/iOS Xcode applications, signed device/IPA/Developer-ID/notary/DMG packaging, and explicit TestFlight/App Store uploads |
| [GraalVM native binary](specs/native-platform.md) | `ssc` native binary via GraalVM native-image; no-JVM distribution; `ssc-plugin-host.jar` bridge |
| [Native plugin guide](docs/native-plugin-guide.md) | Compile a plugin to a native binary — fully JVM-free `ssc → plugin` |

**Planned / partial**

| | |
|---|---|
| [Blockchain SPI](specs/blockchain-spi.md) | Draft / planned, not fully implemented: pluggable chain abstraction below wallet and x402 support |
| [Electron JVM REST Backend](specs/electron-jvm-rest-backend.md) | Partially implemented: explicit `ssc run --frontend electron --backend jvm-rest`, `ssc run --target desktop-jvm`, plain `ssc run` for `frontend: electron` full-stack sources, server-only `ssc run --mode server --backend jvm`, Electron client-only, and web client preview via `ssc run --mode client --frontend react --server-url ...` with opt-in `--open-browser` plus `--host`/`--port` preview binding; packaging and runtime-level JVM bind-host support remain planned |
| [Full-stack in-process transport](specs/fullstack-in-process-transport.md) | Planned, partially implemented: `BackendTransport` types, `ssc run --transport http|in-process` parsing/diagnostics, interpreter/test-harness `InProcessBackendTransport`, same-process Swing runtime, and generated JVM/Swing`BackendTransport` dispatch for `fetchAction`, `dataTable`, and typed route clients without opening an HTTP socket; broader frontend selection remains planned |
| [JVM Desktop Frontend](specs/jvm-desktop-frontend.md) | Partially implemented: `frontend-swing` backend, ServiceLoader registration, `ssc run-jvm --frontend swing`, static Swing toolkit subset emission, local signal action bridge, Swing `fetchAction` / `dataTable` / typed route client backend dispatch in the same JVM process, no-socket full-stack examples, and a `swing-plugin` skeleton for future interpreter intrinsics; JavaFX/Compose adapters remain planned |
| [Typed route clients](specs/typed-route-clients.md) | Planned, partially implemented: `apiClients:` / `api-clients:` front-matter endpoint metadata parses into AST and codegen metadata; when no manual client metadata is present, `RouteDeriver` synthesizes `Api` from `route(...)`, `mount(...)`, and `routes:` declarations, including callable path-param methods such as `Api.getApiItemsById("42")`; JVM/Swing generates callable in-process client methods, and JS/browser/Electron bundle codegen emits Promise-returning HTTP clients over `fetch` using `--server-url` / `__sscBackendBaseUrl`; `awaitClient(...)` gives client-side ScalaScript code a small await bridge; generated clients call a stable typed JSON codec facade sourced from `backend/typed-data`; JVM/Swing typed clients use `JsonCodec[T]`, and JS/browser/Electron typed clients use generated runtime codec metadata for known case-class/enum shapes |
| [Contract validation](specs/contract-validation.md) | Planned, not implemented yet: shared OpenAPI/GraphQL validation model for route/resolver signatures, request/response bodies, typed errors/status codes, profiles, overlays/imports, CLI checks, compatibility diffs, and contract tests |
| [Typer real types roadmap](specs/typer-real-types-roadmap.md) | Planned, partially implemented foundation: reduce accidental `Any` in exported symbols/IR and carry structured type evidence through case classes, enums, generics, routes/remotes, OpenAPI/GraphQL schemas, Dataset/Spark mapping, and plugin metadata |
| [Client/server object store](specs/client-server-object-store.md) | Complete: JS/browser/Electron `IndexedDb.store[A]` typed local client store, JVM/JDBC `ObjectStore`, generated JVM REST sync routes, `Sync.pull/push/sync[A]`, durable queued `Sync.put/remove[A]`, explicit `Sync.conflicts/resolve[A]`, generated JVM conflict policies (`manual` / `server-wins` / `client-wins`), and `Sync.status` / `Sync.isOnline` / `Sync.isSyncing` UI helpers |
| [Graph storage](specs/graph-storage.md) | Planned, partially implemented: `graphs:` front matter parses into AST/IR; JVM codegen exposes `Graph.*` over in-memory, embedded TinkerGraph property, and RDF4J memory RDF stores plus `Sparql.select` over RDF4J; the core-free 2.1 standard provider exposes deterministic process-local `Graph.*` property/RDF storage on VM/direct ASM/build-jvm. `ssc-provider graph-rdf4j` adds remote SPARQL query/update without compatibility fallback; Neo4j and JanusGraph/TinkerPop remain separate provider work |
| [Typed data mapping](specs/data-mapping.md) | Planned, partially implemented: `backend/typed-data` owns the shared emitted typed JSON facade for generated route clients plus `JsonValue` / `DecodeError` / `JsonCodec[A]`, explicit object helpers, `JsonFieldSpec` rename/default/key/unknown-field helpers, `derives JsonCodec` for case classes/sealed ADTs, schema annotations for derived JVM/interpreter product codecs, `schemas:` front-matter metadata for interpreter typed SQL, initial `RowValue` / `RowFieldSpec` / `RowCodec[A]` support for simple case-class rows and explicit JVM column metadata, initial `ObjectValue` / `ObjectFieldSpec[A]` / `ObjectCodec[A]` support for portable IndexedDB/ObjectStore document shapes, `VertexValue` / `EdgeValue` / `RdfValue` plus `VertexCodec[A]` / `EdgeCodec[A]` / `RdfCodec[A]` for graph/RDF mapping, `DatasetCodec[A]` for local/MapReduce Dataset element serialization and distributed worker partition payloads, `DatasetWire` JSON/MsgPack/CBOR envelope + chunk helpers for `DatasetWirePartition`, `std/mapreduce runDistributedWire` and `runDistributedShuffleWire` for direct `DatasetWirePartition` actor/shuffle payloads, `DistributedDataset.encode/decode[A]` typed boundary helpers, `DistributedDataset.run/runShuffle[A, B]` actor-effect wrappers, and `SparkSchemaCodec[A]` for Spark-like schema metadata using the same field annotations; JS/browser/Electron `IndexedDb.store[A]` typed local object storage, and JVM/JDBC `ObjectStore` storage through `ObjectCodec[A]`; `SqlRuntime.query/insert/update[A]` use `RowCodec[A]`; interpreter and JVM codegen expose `Db.query/insert/update[A]` for typed SQL reads and writes; SparkGen typed readers use `SparkSchemaCodec[A]` metadata when present and alias external column names back to Scala field names before `.as[T]`; richer cross-store convergence remains planned |
| [Distributed binary wire protocol](specs/distributed-wire-protocol.md) | Planned, partially implemented: opt-in `wire:` config with JSON fallback plus MsgPack/CBOR profiles for internal ScalaScript distributed actors, Dataset/DStream, typed route clients/RPC, WebSocket subscriptions, object sync, compression, integrity, and future schema evolution. Implemented pieces include shared `WireEnvelope`, actor binary WS, typed RPC binary HTTP negotiation, and `DatasetWire` JSON/MsgPack/CBOR partition envelopes with chunking |
| [Distributed runtime](specs/distributed-runtime.md) | Planned, partially implemented: `local` / `remote` / `distributed` placement vocabulary, named operation registry, code identity, stream bridges, typed actor remote spawn, `! Async` remote calls, cluster runner UX, token rotation, rolling upgrades, and cluster-aware deployment. Implemented pieces include stream bridge basics, typed actor refs/remote spawn, `ClusterCapability`, static `SeedResolver`, interpreter code identity checks, typed `cluster:` / registry front-matter metadata, interpreter `remoteHandlers:` lowering with in-process `Remote.function` plus HTTP JSON fallback routes, explicit `Remote.http[A, B](url)` calls, path-based `Remote.stub(baseUrl)` HTTP stubs, and generated `RemoteRpc` typed HTTP client metadata for `path:` remote handlers |

Dataset/MapReduce binary wire now reaches actor messages directly: `wireFormat = "msgpack" | "cbor"` sends `DatasetWire` envelope bytes for partition, shuffle-bucket, and key-result messages, while JSON keeps the object-message fallback.

## What Works

All three backends support `scalascript` blocks.  `scala` blocks (standard
Scala 3) are supported by the interpreter and JVM backend; the JS backend
compiles them via Scala.js.

### Core language

| Feature | Syntax |
|---------|--------|
| Values and variables | `val x = 42`, `var n = 0` |
| Functions | `def f(x: Int): Int = x * 2` |
| Lambdas and closures | `val double = (x: Int) => x * 2` |
| Higher-order functions | `xs.map(double)`, `xs.filter(_ > 0)` |
| Default parameters | `def f(x: Int, step: Int = 1)`, also on class/enum constructors |
| Case classes | `case class Point(x: Double, y: Double)` |
| Enums / sealed traits | `enum Color { case Red; case Green; case Blue }` |
| Recursive ADTs | `enum Tree { case Leaf(v: Int); case Branch(l: Tree, r: Tree) }` |
| Pattern matching | `x match { case Some(n) => n; case None => 0 }` |
| For comprehensions | `for x <- xs if x > 0 yield x * x` |
| While loops | `while n > 0 do { ... }` |
| Collections | `List`, `Map`, `Option`, `Set`, `Vector`, `Array`, `LazyList`, `Seq`, `IndexedSeq`, `Iterable` — full method dispatch; native `List.mkString` preserves the requested separator for empty, singleton, and multi-element lists |
| Real collection semantics | The interpreter models true Scala semantics — `Array` is mutable with reference identity (`a(i) = x`), `LazyList` is lazy (`#::` defers; infinite streams work), `Vector` is a distinct indexed type with O(log₃₂ n) access; constructors `Seq/Vector/Array/IndexedSeq/Iterable/LazyList(...)` + `.toSeq/.toVector/.toArray/.toList/.toLazyList` conversions |
| Tuples | `val t = (1, "hello"); t._1` |
| Bitwise operators | `a & b`, `a \| b`, `a ^ b`, `a << n`, `a >> n`, `a >>> n`, `~a` on `Int` |
| String interpolation | `` s"Hello, $name" ``, `` s"${items.mkString(", ")}" `` (full expressions, including nested string literals), `` md"..." `` (self-hosted interpolation + indent stripping on ScalaScript 2.1 native VM/ASM/build-jvm; no Scalameta or host parser) |
| Math | `math.sqrt`, `math.abs`, `math.pow`, `math.Pi`, … |
| Extension methods | `extension (n: Int) def squared: Int = n * n`; imported symbolic operators dispatch on ADTs while primitive `Int` operators retain their built-in meaning |
| Typeclasses | `trait Show[A]`, `given`, `summon[Show[Int]]`, context bounds |
| Higher-kinded types (HKT) | `trait Functor[F[_]]`, sealed dispatch, auto-resolution |
| Standard typeclasses | Functor, Applicative, Monad, Foldable, Traversable, Either, Eq, Show, Hash, Order, Semigroup, Monoid, Bifunctor, MonadError, Selective |
| Recursion | factorial, Fibonacci, tree traversal |
| Tail-call optimisation | self-TCO and mutual TCO — no `@tailrec` required |
| Case-class `.copy` | `p.copy(field = newValue, ...)`; named labels and evaluation order are compiler-free on the standard 2.1 path |
| List / map literals | `[1, 2, 3]` → `List(…)`, `["k" -> v, ...]` → `Map(…)`, `[]` → `List()` — compact sugar in `.ssc` code blocks |

### Optics

| Feature | Syntax |
|---------|--------|
| Lenses | `Focus[T](_.a.b)` → `Lens` with `get` / `set` / `modify` / `andThen` |
| Prisms | `Prism[Sum, Variant]` → `getOption` / `set` / `modify` / `reverseGet` |
| Optionals | `Focus[T](_.maybe.some.field)` → `Optional` for paths through `Option` fields |
| Traversals | `Focus[T](_.items.each.field)` → `Traversal` — multi-foci `getAll` / `modify` / `set` |

### Effects and concurrency

| Feature | Syntax |
|---------|--------|
| Algebraic effects | `effect E:` has an atomically enforced one-shot `resume`; a second call fails as `ONESHOT_VIOLATION`. Use `multi effect E:` for reusable resume. VM, direct ASM, and Swift share this contract |
| Deep residual-effect forwarding | On the portable VM and direct ASM, an operation unmatched by the nearest handler remains the same three-field `Op` for the next enclosing handler. Resuming it reinstalls the skipped handler and preserves the original one-shot or reusable continuation; see the [verified design](specs/control-residual-forwarding.md) and [axis-19 probe](tests/interop-conformance/probes/19-residual-forwarding-nested-handlers.ssc) |
| Stack-safe deep effect resume | Portable VM and direct ASM fold handler resumptions through one iterative runtime: 100,000 tail/sequence operations and 20,000 non-tail or escaped continuation resumes run without growing the native stack. See the [verified design](specs/control-effect-stack-safety.md) and [axis-20 probe](tests/interop-conformance/probes/20-stack-safety-deep-effect-recursion.ssc) |
| Scala 3 explicit control API (Tier 1 implemented) | Publication-ready `_3` leaf in `scalascript.control`: typed `Eff`, deep handlers, generative multi-prompt `shift`/`reset`, reusable and one-shot local continuations, and stackless state machines. Local `save()` rejects with typed `UnmanagedCapture`; see the [runnable Scala example](v2/host/scala/control/src/test/scala/scalascript/controlapi/examples/ControlApiExample.scala) and [Scala/JVM profile](specs/scala3-bidirectional-control.md) |
| Shared control semantic vectors (implemented) | One [validated catalog and lane matrix](tests/interop-conformance/README.md) carries 26 stable law vectors across 9 declared lanes. Portable VM and direct ASM each pass 13/13 eligible process vectors; the explicit Scala API passes 17 semantic vectors plus its catalog-coverage test. Prompt vectors 18/22/23 are ready on the explicit Scala lane, while unqualified direct/generated host and AOT lanes remain explicitly `PENDING` |
| Full control interoperability (in progress) | One target-neutral contract in [`specs/control-interoperability.md`](specs/control-interoperability.md). Successful durable `save`/`run`, network transfer, Scala↔ScalaScript typed bridges, managed callbacks/TCO, macros/plugin, admission, and exact/portable runners remain post-X1 work |
| Canonical interop descriptors (Slice A infrastructure) | Target-neutral `v2/interop/descriptor` leaf with bounded canonical codecs and checked factories for `ApiDescriptor`, `ControlSummary`, and `ArtifactManifest`; compiler/linker producers and runtime/admission consumers remain queued in [descriptor v3](specs/ssc-api-descriptor-v3.md) |
| Plugin capability profiles (infrastructure) | Target-neutral `v2/interop/plugin-profile` leaf derives stable semantic/schema identities, pins exact target implementation bytes, projects one canonical descriptor binding, and validates target/capability/transitive dependency closure before plugin installation. The JVM `NativePlugin` adapter remains source/binary compatible; package/linker population is a later descriptor slice. See the [verified profile](specs/plugin-capability-profile-v1.md) |
| Host/runner profiles (planned) | Native typed bidirectional bridges for [Scala/JVM](specs/scala3-bidirectional-control.md), [JS/TS](specs/javascript-typescript-bidirectional-control.md), [Rust](specs/rust-bidirectional-control.md), and [Swift](specs/swift-bidirectional-control.md), measured against the [portable-VM reference runner](specs/control-interop-profile-portable-vm.md), plus the [WASM/WASI runner](specs/wasm-wasi-control-runner.md) |
| Typed effect rows | `def foo(): A ! Logger` — effect appears in function type; closed row (no `!`) = total/pure |
| `multi effect` | Explicit multi-shot effects — continuation can be resumed many times; raw CoreIR `effect.perform` also remains reusable |
| `Reader[R]` capability | Context-injection effect: `Reader.get`, `runReader(value)(body)` |
| `NonDet` multi-shot | Nondeterministic branching via multi-shot continuations |
| `EffectAnalysis` | Compile-time error for unhandled effects (not just a warning) |
| Standard effects — Logger | `Logger.info(msg)`, `runConsoleLogger`, `runTestLogger` |
| Standard effects — Random | `Random.nextInt(n)`, `runSeededRandom(seed)` |
| Standard effects — Clock | `Clock.now()`, `Clock.millis()`, `runSystemClock` |
| Standard effects — State | `State.get`, `State.set(v)`, `State.modify(f)`, `runState(init)` |
| Standard effects — Env | `Env.get(key)`, `runEnv(map)` |
| Standard effects — Http | `Http.get(url)`, `Http.post(url, body)`, `runHttpClient` |
| Standard effects — Retry | `Retry.attempt(n)(body)`, `runRetry(policy)` |
| Standard effects — Cache | `Cache.getOrSet(key)(body)`, `runCache` |
| Standard effects — Tx | `Tx.begin`, `Tx.commit`, `Tx.rollback`, `runTx` |
| Standard effects — Auth | `Auth.check(claims)`, `runAuth(verifier)` |
| Direct syntax (do-notation) | `direct[M] { x = expr; y = expr2 }`, `.!` postfix bind |
| Effect-row unions | `direct[Async \| Random] { ... }` |
| Built-in `Async` effect | `runAsync { Async.delay(ms); Async.parallel(...) }` — deterministic and core-free on 2.1 native VM/direct ASM/build-jvm |
| Real-thread `runAsyncParallel` | ordered JDK 21 virtual-thread concurrency without changing call sites or loading compatibility code |
| Built-in `Storage` effect | `runStorage { Storage.put(k, v); Storage.get(k) }` — core-free JSON file-backed or ephemeral handlers on 2.1 native VM/direct ASM/build-jvm |
| Coroutines | `coroutineCreate`, `coroutineResume`, `suspend`, `Step[Y,T]` ADT, `coroutineCancel` |
| Generators | `generator[T] { () => suspend(v) }`, pull/combinator pipelines — core-free on 2.1 native VM/direct ASM/build-jvm; Dataset bridging remains explicit |
| Reactive signals | `Signal(0)`, `s.get` / `s.set(v)`, `computed { … }`, `effect { … }` with diamond-dedup flush — core-free on 2.1 native VM/direct ASM/build-jvm |
| Free monad | `Free[F,A]`, `liftF`, `foldMap`, `runM` — in `runtime/std/free.ssc` |

### Actors

| Feature | Syntax |
|---------|--------|
| Local actors | `runActors`, `spawn`, `self()`, `pid ! msg`, timed/plain `receive`, and `exit(pid, reason)` are core-free on the 2.1 standard VM/direct ASM/build-jvm path; links, monitors, and supervision trees remain explicit compatibility/advanced-provider surfaces |
| Distributed actors | actor cluster over WS, bully leader election, Phi-accrual FD, gossip, membership events |
| Typed actor refs + named remote spawn | `ActorRef[M]`, `ref.tell(msg)`, `ref.address`, `ref.isLocal`, `registerBehavior`, `spawnRemote`; the 2.1 standard provider supplies deterministic process-local named loopback, while network transport remains explicit compatibility/advanced-provider work |
| Cluster capability + code identity | `clusterOf`, `SeedResolver.staticList`, `codeIdentity`, `assertCodeIdentity` for typed cluster snapshots and deterministic SHA-256 code checks |
| Remote handler registry | `remoteHandlers:`, `@remote(...) def`, and simple `remote def` declarations lower to an interpreter `RemoteHandlerRegistry`; `Remote.function[A, B](name).call(value)` uses the in-process path, `path:` entries expose POST HTTP JSON fallback routes, `Remote.http[A, B](url)` calls those routes explicitly, `remoteStub[Api](baseUrl)` / `Remote.stub[Api](baseUrl)` provide a path-based HTTP JSON fallback stub, and `RemoteClientDeriver` exposes `path:` handlers as generated `RemoteRpc` typed HTTP client metadata for JS/JVM codegen. Generated trait methods, async effect-row lowering, WebSocket/internal-wire transport, and binary `WireCodec[A]` negotiation are planned |

### Web and HTTP

| Feature | Syntax |
|---------|--------|
| HTTP server | `route(method, path)(handler)`, `serve(port)`, `Request`/`Response` |
| REPL web mode | `:serve`/`:stop`/`:clear`/`:mount`/`:load`/`:reload`/`:unmount`/`:routes`/`:http`/`:call` — mount handlers and test routes interactively; `:set errorDetails true\|false` |
| HTTP streaming | `streamResponse`, SSE via `sse(req)` |
| HTTP middleware | CORS, gzip, cache headers, `/_health` / `/_ready`, `/_openapi.json` / `/_swagger` with typed response schemas for front-matter/generated JVM routes when `apiClients:` metadata is available; `ssc emit-openapi` exports the same document as JSON/YAML without starting a server; `@openapi(...)` adds per-route summary/description/tags/deprecated/security metadata and `openApiSecurity(...)` declares OpenAPI security schemes |
| WebSocket server | `onWebSocket(path)`, `ws.send/recv/close/ping`, rate limiting, per-route `maxConnections` |
| TLS | `tls("cert.pem", "key.pem")`, `serve(443, tls=...)`, `wss://` |
| HTTP client | `httpGet/httpPost`, `httpClient { }`, `httpGetStream` for SSE/LLM streaming |
| WebSocket client | `wsConnect(url) { ws => }`, `wss://` |
| REST ergonomics | `jsonParse/jsonStringify/jsonRead`, `req.json`, `JsonValue`, `validate { }`, middleware |
| YAML data | `parseYaml` / `toYaml`, total `yaml*` accessors, and heading-scoped `<SectionId>.yaml` / `yml` fences — core-free on 2.1 native VM/direct ASM/build-jvm |
| Typed handlers | `CaseClass => CaseClass` auto-deser (path/query/body) + auto-ser (JSON 200); `Either[Request, Input]` for explicit error handling |
| SQL databases | `databases:` front-matter declares named JDBC connections; ` ```sql ``` ` fenced blocks execute DDL/DML; ` ```transaction ``` ` fenced blocks run multiple `;`-separated statements atomically (JDBC transaction, commit/rollback); `Db.query/execute` for programmatic access; SQLite, H2, PostgreSQL out of the box |
| XML markup | ` ```xml ``` ` fenced blocks parse well-formed XML 1.0 into `Value.MarkupV(doc: Markup.Doc)`; `${expr}` interpolation with automatic XML escaping; bound as `<section>.xml`; zero-dependency `PureMarkupCodec` parser |
| XSLT transformation | `MarkupCodec.default.transform(doc, xslt, params)` applies an XSLT 1.0 stylesheet to a `Markup.Doc`; returns `Either[TransformError, Markup.Doc]`; parameter substitution via `Map[String, String]`; JVM / interpreter only (`Feature.Xslt`); see `examples/xslt-transform.ssc` |
| Secret resolution | `${env:VAR}`, `${file:/run/secrets/pw}`, `${sops:key.path}` in database URLs/credentials; `SecretResolver` SPI for Vault, AWS SM, GCP SM, Doppler, 1Password and more |
| Progressive Web App | `pwa(name, themeColor, icons, precache)` — registers `GET /manifest.json` + `GET /sw.js`; cache-first precaching service worker; works in `ssc run` and `ssc run-jvm` |

The ScalaScript 2.1 compiler-free standard launcher executes `sql` fences
natively in document order, converts `${expr}` to JDBC binds, preserves `$$`,
and exposes `_sqlBlock_N` plus the first `<Section>.sql` result. This path works
on standard VM, direct ASM, slim/JRE, and `build-jvm` without Scalameta, the v1
frontend, or a transparent fallback. Typed native `Db.query/insert/update[A]`
now derives bounded portable product schemas from `Mirror`, validates generated
identifiers, binds every value, and reconstructs case-insensitive JDBC rows on
the same compiler-free lanes.

Planned, not implemented yet:

| Feature | Planned shape |
|---------|---------------|
| Full-stack in-process transport | `ssc run` and `ssc run-jvm` recognize `--transport http|in-process`; interpreter route tests can use`InProcessBackendTransport`; Swing now runs in the same JVM process, and Swing`fetchAction` / `dataTable` / typed route client calls dispatch through a generated JVM `BackendTransport` without opening an HTTP socket; `examples/frontend/swing-fullstack/` and `examples/frontend/swing-typed-client/` demonstrate the no-socket paths |
| JVM desktop frontend | `ssc run-jvm --frontend swing app.ssc` launches the JDK-only Swing runtime in the current JVM process; `ssc run-jvm --frontend swing --transport in-process` is accepted as the monolithic JVM mode foundation; Swing `fetchAction` can call generated JVM backend routes in-process; `ssc run --frontend swing` remains the interpreter path and reports that Swing intrinsics are planned; JavaFX/Compose adapters remain planned |
| Typed route clients | `apiClients:` / `api-clients:` front matter preserves endpoint method/path/request/response metadata in AST and JVM/JS codegen. JVM/Swing generates callable in-process clients that encode case-class/ADT inputs through `JsonCodec[T]`, dispatch through generated `BackendTransport`, and decode typed JSON responses through `JsonCodec[T]`. JS/browser/Electron bundle codegen emits Promise-returning HTTP clients over `fetch` and passes request/response type names through the shared typed JSON facade; generated JS case-class/enum metadata decodes known response shapes back into generated JS values. Client-side ScalaScript can use `awaitClient(Messages.list())`, which lowers to JS `await` and enables the needed async wrapper. `emit-spa --server-url`, web client mode, and Electron JVM REST dev mode can route those relative calls to a JVM backend. `examples/frontend/typed-client-distributed/` demonstrates one source running as backend on one machine and client on another |
| Client/server object storage | JS/browser/Electron code can use `IndexedDb.store[A]("store", "dbName", "keyField")` for Promise-based typed local object storage, awaited with `awaitClient(...)`; native IndexedDB is used when available, with a lightweight fallback for Node/tests. JVM code can use `ObjectStore.put/get/all/delete/changes[A](dbName, store, ...)` over the declared JDBC database. JVM codegen emits typed `GET /__ssc/sync/{store}/changes` and `POST /__ssc/sync/{store}/push` routes for `objectStores:` entries with `sync: client-server`. JS clients call `Sync.pull/push[A]` or the combined `Sync.sync[A]`; `Sync.put/remove[A]` persist local queued mutations for offline-friendly pushes. Conflicts are listed with `Sync.conflicts`, resolved with `Sync.resolve[A]`, or handled automatically by generated JVM `conflict:` policies. `Sync.status(store, db?)` returns `{ pending, conflicts, lastPulled, lastPushed, isSyncing }` for sync-status badges; `Sync.isOnline` and `Sync.isSyncing(store, db?)` give per-store and network availability signals |
| Graph storage | `backend/graph` now provides `GraphCapabilities`, `PropertyGraphBackend`, `RdfGraphBackend`, `GraphBackend`, `GraphRuntime.inMemory()`, `GraphRuntime.tinkerGraph()`, `GraphRuntime.rdf4jMemory()`, and `GraphRuntime.sparqlSelect()` for portable JVM property/RDF storage through typed codecs plus RDF4J SPARQL `SELECT`. `graphs:` front matter survives AST/IR/.sscc; JVM codegen emits a typed `Graph.*` facade for declared in-memory, `embedded-tinkergraph`, and `rdf4j-memory` graph stores plus `Sparql.select` for RDF4J-backed stores. The core-free `v2/runtime/std/graph-plugin` mirrors deterministic process-local property/RDF operations on standard VM/direct ASM/build-jvm; remote SPARQL/Cypher/Gremlin require explicit providers and produce bounded diagnostics locally. Production adapters such as Neo4j, JanusGraph/TinkerPop providers, and RDF4J-compatible repositories remain planned |
| Typed data mapping | `backend/typed-data` now provides the shared emitted typed JSON facade for generated route clients plus explicit `JsonValue`, `DecodeError`, `JsonCodec[A]`, `JsonFieldSpec`, `RowValue`, `RowValueCodec[A]`, `RowFieldSpec[A]`, `RowCodec[A]`, `ObjectValue`, `ObjectFieldSpec[A]`, `ObjectCodec[A]`, `VertexValue`, `EdgeValue`, `RdfValue`, `VertexCodec[A]`, `EdgeCodec[A]`, `RdfCodec[A]`, `DatasetCodec[A]`, `DatasetWire`, and `SparkSchemaCodec[A]` helpers. `derives JsonCodec` supports case classes and sealed ADTs; `derives RowCodec`, `derives ObjectCodec`, `derives VertexCodec`, `derives EdgeCodec`, `derives RdfCodec`, and `derives SparkSchemaCodec` support simple case-class rows/documents/graph records/schema records. `DatasetCodec[A]` derives from `JsonCodec[A]` and now exposes partition payload helpers for distributed MapReduce worker movement; `DatasetWire` wraps those payloads in shared JSON/MsgPack/CBOR `WireEnvelope`s and chunks large partitions. `std/mapreduce` can move `DatasetWirePartition` payloads directly through `runDistributedWire` and `runDistributedShuffleWire`, with `DistributedDataset.encode/decode[A]` wrapping the typed boundary and `DistributedDataset.run/runShuffle[A, B]` wrapping the actor-effect calls for map and shuffle. `SparkSchemaCodec[A]` uses the same `@fieldName`, `@key`, and `Option` nullability conventions to produce Spark-like schema metadata, and SparkGen typed readers consume that metadata when it is in scope. Graph/RDF codecs add `@graphLabel`, `@graphEdge`, `@graphFrom`, `@graphTo`, `@rdfClass`, `@rdfId`, and `@rdf`. `SqlRuntime.query/insert/update[A]` use `RowCodec[A]`, and interpreter/JVM codegen expose `Db.query/insert/update[A]` for typed SQL reads and writes. Broader cross-store migration helpers remain planned |

Dataset/MapReduce typed wire calls can select `wireFormat = "msgpack" | "cbor"` to send `DatasetWire` envelope bytes through actor partition, shuffle-bucket, and key-result messages.

### Auth and security

| Feature | Syntax |
|---------|--------|
| Sessions + CSRF | `req.session`, `withSession(Map(...))`, `csrfToken()` / `csrfValid(req)` |
| JWT bearer tokens | `jwtSign(Map(...))`, `jwtVerify(token)`, RS256 and HS256 |
| Password hashing | `hashPassword`/`verifyPassword` (PBKDF2) |
| TOTP 2FA | `totpGenerateSecret`, `totpVerify(secret, code)` |
| WebAuthn / passkeys | Server verifier: `webauthnChallenge`, `webauthnVerifyRegistration`, `webauthnVerifyAssertion`, `webauthnStore*`; browser actions: `webauthnRegister` / `webauthnAssert` from `std/ui/webauthn.ssc` |
| OAuth2 | `oauthAuthorizeUrl(...)`, `oauthExchangeCode(...)`, `oauthUserinfo(...)`, Google + GitHub presets |

### MCP (Model Context Protocol)

| Feature | Syntax |
|---------|--------|
| MCP server | `mcpServer { srv => srv.tool(...) }`, `serveMcp(Transport.stdio/Http/Ws)` |
| MCP client | `mcpConnect(url) { client => client.callTool(...) }`; the explicit native VM/ASM lane is selected with `ssc-provider mcp` |

### Data processing

| Feature | Syntax |
|---------|--------|
| Dataset / MapReduce | `Dataset[T]` with map/filter/flatMap/groupBy/reduceByKey/top/countByValue |
| Execution modes | `runLocal`, `runParallel`, `runDistributed` |
| Apache Spark | Same `Dataset[T]` source → `sql` fenced blocks · `@SqlFn` UDFs · Scala 3 native encoder derivation · Structured Streaming · Delta Lake · Hive metastore · MLlib pipelines |

### DSL authoring and metaprogramming

| Feature | Syntax |
|---------|--------|
| Parser combinators | `runtime/std/parsing/*` — Parser ADT, `~/~>/~<`, rep, opt, sep, error recovery, indentation-aware |
| Multi-pass pipelines | `runtime/std/dsl/*` — `Pass[A,B]`, `andThen/parallel/recover`, `Visitor`, `cata`, `ana` |
| Metaprogramming | `inline def/val/if/match`, `compiletime.constValue/summonInline/error`. **Restricted quoted macros run on the interpreter, JVM, and JS backends** (`MacroCodegen.expand` pre-codegen pass), including `Expr.asValue match` compile-time constant folding and **cross-module** macro expansion, with targeted unsupported-body diagnostics; `ssc check` warns on interpreter-only macros. `Mirror.Of[T]` conformance + custom `derived(m: Mirror)` typeclasses (JVM + JS) |
| Derives | `derives Eq/Show/Hash/Order/Foldable/Traversable/Functor`; structural stdlib + custom `derives` cross-backend (JVM + JS) |
| Checked errors | `throws[A, E] = Either[E, A]`, `throwsRaw[A, E] = A \| E`, `attemptCatch`, `HasStackTrace` |

### Module system and tooling

| Feature | Syntax |
|---------|--------|
| Package system | `package: org.example.ui` in frontmatter, namespaced exports, collision-safe imports |
| Module imports | `[name](./lib.ssc)` Markdown links bring definitions into scope; one pure Markdown paragraph may contain multiple import links |
| URL imports | `[X](https://...)` URL fetch, cached at `~/.cache/ssc/` |
| Dependency imports | `[X](dep:org/lib:1.2)` legacy source resolver, `[X](dep:org:name:version)` Coursier resolver, `[X](jitpack:com.github.owner:repo:tag)`, `[X](github:owner/repo@tag[#asset])`, `sha256:` pins, `ssc.lock` |
| Package registry | `ssc search` / `ssc info` / `ssc add` against `https://sergey-scherbina.github.io/scalascript/packages.yaml` by default, overrideable with `--registry` or `registry.url` |
| Project scaffolding | `ssc new my-app`, `--template lib|plugin|dsl|web-app|wasm-app`, bundled templates,`releases/install.sh`, Homebrew formula source |
| Plugin system | `.sscpkg` format, essential bundled plugins auto-loaded from `bin/lib/compiler/plugins`, advanced bundled plugins available locally under `bin/lib/compiler/plugin-available` and enabled with `--plugin` / `ssc plugin install`, `~/.scalascript/registry.yaml` |
| sbt integration | `ScalascriptInteropPlugin`, `sscGenerateFacade`, `sscCompile`, `sscLink`, `sscTest`, `sscRun`, `sscRepl`, `sscWatch`, `sscBspSetup`, `sscBackends` cross-build (emit JVM/JS/Rust/Wasm artifacts from one source), Phase 5 dependency resolution, `src/main/scalascript/` source convention |
| Config system | `config:` front-matter, ` ```yaml config "name" ` fenced blocks, `config.files: [...]`, typed `derives Config`, `JsConfigEmitter`, `ScalaConfigEmitter` — see `specs/config-system.md` |
| Separate compilation | `ssc emit-interface`, `ssc emit-ir`, `ssc compile-jvm/compile-js`, `ssc link`, `ssc build --incremental`, `.scim/.scir/.scjvm/.scjs`; `.scir` and `.sscc` preserve Markdown content snapshots when present |

### Browser and UI

| Feature | Syntax |
|---------|--------|
| Browser SPA target | `ssc emit-spa file.ssc` — same `route()` source runs as a single-page app |
| Web Components | `ssc emit-wc`, `customElements.define` |
| SSR + hydration | `wc(tag, component, args*)` declarative shadow DOM |
| Component library | `runtime/std/ui/*` — Button, Input, Select, Modal, Card, Spinner, Alert, DatePicker, Combobox, and more |
| Frontend Framework SPI | One `.ssc` source compiled to **React**, **Vue 3**, **Solid**, or a **custom** runtime via `frontend-{react,vue,solid,custom}` backends |
| Reactive primitives | `Signal[T]`, `ShowSignal` (conditional render), `ToggleSignal`, `ForSignal[T]` (list render) — uniform semantics across all 4 frontend backends |
| `runtime/std/ui` script toolkit | Declarative widget DSL from a `.ssc` file — `vstack/hstack` (`hstack` also takes a trailing `wrap: Boolean = false` that sets `flex-wrap: wrap` so a row's children flow onto additional lines instead of overflowing/shrinking on one — browser-computed, not caller-pre-grouped; `specs/std-ui-hstack-wrap.md`), `textField`, `checkbox`, `select(options, selected, label, placeholder, disabled)` dropdown over `(value, label)` pairs two-way bound to a `Signal[String]` (`specs/std-ui-select.md`), `selectFrom(items, key, optionFn, selected, label, placeholder, disabled)` — the reactive-options sibling: `items` is a `Signal[List[A]]` instead of a static list, so the `<option>` children re-render (item added/removed/reordered, DOM identity preserved for kept items) on the JS runtime lane when the signal changes, e.g. a fetched contracts list (`specs/std-ui-select.md` § "Reactive options (selectFrom)"), `signalButton`, `actionButton`, `signalLabelButton`, `signalActionButton` — all four take a trailing `variant: String = "primary"` (`primary|secondary|danger|success|warning`, unrecognized → `primary`) that picks the button's background from `Theme.colors` instead of the hardcoded primary blue (`specs/std-ui-button-variant.md`), plus a trailing `size: String = "md"` (`sm|md|lg`, unrecognized → `md`) that picks a smaller/larger font-size + padding pair, reused from `Theme.typography`'s `caption`/`body`/`heading` tokens and a shifted `Theme.spacing` pair — no new theme literals (`specs/std-ui-button-size.md`), `forKeyed(items, key)(render)` keyed browser lists, `rawText` escaped text, `rawHtml` trusted raw-markup escape hatch, `badge`, `spinner`, `card`, `modal`, `table`, `router` + `hashRouter`, `dataTable`, `fetchAction`, and Markdown-driven `contentToolkitNode()` / `contentToolkitBlock(id)` / `contentToolkitSection(id)` / `contentComponent(...)` / `contentData(id)` / `contentBind(value, bindings)` / `contentToolkitOptionsWithBindings(data)` / `contentSection(id)` / `contentBlock(id)` / `contentMetadata(path)` / `contentPlainText(value)` / `contentToMarkdown(value)` / `contentView(contentDocument())` with `yaml @ui=toolkit` controls (including `{type: button, action: <id>}` and `{type: table, source: <id>}` — with an optional inline `columns:` list of typed column specs (`kind: text|date|money|status|link`, plus `label`/`path`/`align` and per-kind `format`/`currency`/`locale`/`url`/`colors`) lowered through the same `fieldColumn`/`dateColumn`/`moneyColumn`/`statusColumn`/`linkColumn` builders as code — that bind to the same registered action / data-source ids as the Markdown links, and `{type: signalText, signal: <id>}` / `showWhen: <id>` that reference a `contentComputed(id, sig)`-registered derived signal), ordinary Markdown `toolkit:` links for simple controls (including `toolkit:button?action=<id>` bound to a registered server effect and `toolkit:table?rows=<id>` bound to a live fetch-signal `DataTable` via `contentRows(...)` / `contentToolkitOptionsWithRows(...)`), and GFM pipe tables lowered to `TableNode`. A `{type: table, source: <id>}` may also bind to a **named data source** registered with `contentDataSource(id, source, columns)` — `staticSource(rows)` (in-memory), `signalSource(sig)` (reactive), or `fetchSource(id, url, tick, headers, rowsPath)` (managed GET that re-fetches on tick, with an optional dotted `rowsPath` to unwrap a non-standard fetch envelope — drilled on the JS browser SPA **and** the server-rendered interpreter `serve`/`emit` + emit-jvm custom-frontend path) (declarative-ui B.3). A registered action can declare a structured `onSuccess` run in order after a 2xx — `fetchActionWith(method, url, body, [onBumpTick(tick), onSetSignal(sig, value), onNavigate(path)])` (declarative-ui B.4); its body can be a `Signal[String]` or a `formBody([field, …])` that assembles `{field: <signal>}` from named field signals at submit — each entry is a bare field name or a `(jsonKey, signalId)` tuple when the wire key must differ from the signal id (declarative-ui B.4+). And a `{type: slot, id: <id>}` control is an escape hatch that injects an arbitrary code-built `TkNode` registered with `contentSlot(id, node)` (declarative-ui B.6). `ssc check` statically warns when a `@ui=toolkit` control references an `action:` / `source:` id that no `contentAction(...)` / `contentRows(...)` registers (declarative-ui B.7 — a typo'd id is caught at build time instead of surfacing as a render-time inline error). The low-level `std/content` helpers and the `contentToolkitNode()` / `contentToolkitBlock(id)` / `contentToolkitSection(id)` renderers all run on interpreter, JVM, and the JS browser backend (emit-spa). Use `lower(tree, theme)` + `serve(view, port)`; `frontend: react` front-matter |
| Browser WebAuthn actions | `std/ui/webauthn.ssc` exports `webauthnRegister` and `webauthnAssert` EventHandlers for passkey buttons on the production `emit-spa --frontend custom` path; begin routes return challenge/options JSON, complete routes receive verifier-shaped base64url JSON |
| Native Markdown toolkit controls | Markdown/YAML `@ui=toolkit` controls lower through the same `std/ui` View pipeline for Swing, JavaFX, and SwiftUI native clients; see `examples/frontend/markdown-native-controls/` |
| NFC NDEF (`std.nfc`) | `nfcCapabilities`, `nfcPermissionStatus`, `readNdef`, `writeNdef`, `textRecord`, `uriRecord`, `mimeRecord`; `ssc-provider nfc` supplies the core-free native VM/ASM host provider and deterministic unsupported-hardware snapshot, while Android/iOS/Web adapters remain capability-gated |
| Rozum / agent loop (`std.agent`) | `runAgent`, `runAgentPool`, `runAgentStream`, `runAgentStreamPool`, `collectAgentStream`, `collectAgentStreamPool`, `AgentEndpoint`, `AgentEndpointPool`, `AgentSchema`, `AgentTool`, `agentToolFor`, `RunOptions`, `ToolResult`, `AgentEvent` — OpenAI-compatible tool-call loop for stateless rozum gateways; apps own prompts, typed or explicit tool schemas, validation, streaming callbacks, failover policy, and side effects |
| Fetch primitives | `fetchUrlSignal` — live GET binding (v2: real fetch + headers); `seedSignal` — editable draft seeded from another `Signal[String]` until user input makes it dirty, supported by the browser runtime and frontend emitters including JVM desktop; `fetchAction/fetchActionClear` — POST/PUT/DELETE on button click with optional `headers: Signal[String]` for bearer tokens; the compiler-free standard JVM/static lane constructs these fetch descriptors without network I/O or a compatibility fallback, while emitted browser runtimes execute them; `dataTableView` over any `TableDataSource` — `staticRowsSource` (in-memory rows, no fetch), `signalRowsSource` (reactive rows signal), or a bare fetch signal (Remote) — on interpreter, JVM, and JS browser backends alike; `rowPostAction` bodies via `fieldPayload` / `wholeRowPayload` / `fieldsPayload`; `incSignal` — manual refresh; `emptyHeaders` sentinel |
| Fetched-view state helper | `std/ui/state.ssc` provides `loadState`, `stateName`, `errorText`, `triState`, and `triStateText` for loading/empty/error/ready views over existing signals, with no new backend intrinsic |
| Themes | `defaultTheme` (light) · `darkTheme` · custom `Theme(ColorPalette, SpacingScale, TypographyScale, RadiusScale)` |
| Frontend Toolkit (v1.18 B+ / B++ / C) | High-level declarative UI via `Tk` facade (sbt API) — `vstack/hstack`, `card`, `textField`, `form` with validators, `router`, `modal/drawer/tabs`, `table`.  Backend-agnostic: lowers to React / Vue / Solid / Custom or to static HTML via `Ssr.renderToHtml`. |
| WebAssembly target | `ssc emit-wasm file.ssc` — `scalascript` blocks lowered to Wasm; cross-backend `sql` fenced blocks supported. **Algebraic effects compile and run on Wasm** (arithmetic, collection HOFs, multi-shot resume, cross-module) via a pure-Scala effect runtime with no preamble bloat. `@wasm` extern FFI + cross-module inlining + macro expansion are wired |
| Native Rust SSR server | `serve(view, port)` on the **Rust backend** (`ssc build-rust`) emits a self-contained `tokio` + `hyper` server with server-side rendering, a reactive **signal store**, **computed-signal live recompute**, **Server-Sent Events** push (`/__ssc/events`), and a **direct WebSocket** signal endpoint on `port + 1` — no JS framework, no Node runtime. See [`docs/rust-backend.md`](docs/rust-backend.md#web-toolkit-on-rust--reactive-serve) |
| i18n | `translations:` frontmatter, `t(key)`, `setLocale(code)` |
| Env access | `getenv(key)` / `getenv(key, default)` |
| Content helpers | Self-hosted `md"..."` plus recursively composable `doc(...)` / `render(...)` structured output (nested docs flatten; reserved tags never leak); the ScalaScript 2.1 native VM, direct ASM, and `build-jvm` artifact use language lowering + the core-free host provider with no v1 `DocV`, `PluginBridge`, Scalameta, or parser dependency |
| HTML / CSS interpolators | `html"..."` (auto-escaping) and `css"..."` with `${expr}` |

### Blockchain, wallets, and micropayments

| Feature | Syntax |
|---------|--------|
| x402 micropayments | HTTP 402 → typed payment challenge / settlement via `Payment[T]`, `x402Server { … }`, `x402Client(...)` — Ethereum + Cardano payment families |
| Blockchain SPI (draft / planned, not fully implemented) | `BlockchainBackend` trait — EVM (mainnet, L2s), Bitcoin, Solana, Cardano via pluggable backends. See [`specs/blockchain-spi.md`](specs/blockchain-spi.md) |
| Wallet Connect (WC v2) | Relay-transport cryptographic primitives — pairing, session, JSON-RPC, X25519 / HKDF / ChaCha20-Poly1305 |
| Browser MetaMask wallet | `x402ClientJs` helper — `Wallets.metaMask(Network.Base)` over `window.ethereum` + EIP-712 signing |
| Ledger hardware wallets | JVM HID and browser WebHID vaults — Ledger HID APDU framing, Ethereum app signing, Cardano CIP-8 helpers |
| MPC wallet vaults | `wallet-vault-mpc` remote signing core plus `wallet-vault-mpc-fireblocks` provider adapter — Fireblocks JWT auth, RAW transaction signing, polling |
| Solana Wallet Standard | `solana-wallet-std` translator — Wallet Standard ↔ unified Wallet SPI |
| ERC-4337 account abstraction | `EntryPoint v0.7 PackedUserOperation` — bundlerless and bundler-driven flows |
| Cardano CIP-8 wallet | Ed25519 key-derived enterprise bech32 address, CIP-8 message signing, Scalus-source escrow validator with on-chain canonical CIP-8 proof verification, exact receiver-output check, claim/refund validity-window checks, Scalus script-context validator tests, stable `EscrowScript.address(network)` script address helper, Scalus-mode structured claim-message signing, server-side Scalus proof verification, bloxbean claim Tx draft with script data hash + relayer witness, typed Blockfrost protocol params, protocol min-fee draft balancing, static/bloxbean/Blockfrost/Ogmios Plutus ex-units, and env-gated Preprod integration coverage |
| Cross-backend crypto | JVM: native `Ed25519`, secp256k1, BLS12-381; JS: `@noble/curves` Scala.js backend — uniform `CryptoBackend` SPI |
| `std.crypto` hashing | `sha256` (hex) / `sha256Base64` (base64 digest) / `sha256OfBase64` (digest over decoded bytes), `byteLengthUtf8`, `hmacSha256`, `base64Encode`/`base64Decode` — digests + encoding (e.g. KSeF `invoiceHash`/`encryptedInvoiceHash`) |
| `std.crypto` encryption | AES-256-GCM (`aesGenKey`/`aesGcmEncrypt`/`aesGcmDecrypt` + byte variants), AES-256-CBC + PKCS#7 with external IV (`aesGenIv`/`aesCbcEncrypt`/`aesCbcDecrypt`), RSA-OAEP (`rsaOaepEncrypt`), X.509 SPKI extraction (`x509PublicKey`) — hybrid encryption for KSeF 2.0 etc. (JVM, including compiler-free native VM/ASM) |
| `std.crypto` signature verify | `verifyEd25519`/`verifyEd25519Url`, `verifyRsaSha256` (PKCS1/PSS) — total verifiers (malformed → `false`, never throw) for trustless federation (JVM, including compiler-free native VM/ASM) |
| `std.crypto` signing | `ed25519Sign`/`ed25519SignUrl`, `rsaSignSha256` (PKCS1/PSS) — private-key signers that round-trip with the verifiers; for chain-checkpoint evidence a third party can verify without a shared secret (JVM, including compiler-free native VM/ASM) |
| `std.crypto` OTP + secret sharing | RFC 4226/6238 `hotp`/`totp`/`totpValidate` plus prime-field `shamirSplit`/`shamirRecover`; fixed vectors and threshold recovery agree on native VM/ASM |
| `std.pdf` generation | `htmlToPdfBase64(html)` — render a confined HTML/CSS subset (table layout, A4, typography/borders/bg) to base64 PDF bytes via OpenHTMLtoPDF; explicit native VM/ASM provider: `ssc-provider pdf ...` (JVM only) |
| `std.pdf` extraction | `pdfToMarkdown(pdfBase64)` / `pdfPageCount(pdfBase64)` — read a PDF's text layer (Apache PDFBox), pages split by `---`; explicit native VM/ASM provider, non-PDF throws (JVM only) |
| `std.mime` assembly | `buildMimeMessage(from,to,subject,htmlBody,attachments)` — hand-rolled RFC 5322 `multipart/mixed`; the dependency-free MIME companion is selected with the explicit PDF invoice provider lane (JVM only) |
| MCP × x402 | `mcpServer { srv => srv.tool(...).requirePayment(...) }` — paid LLM tools |

### Cluster, leader election, federation

| Feature | Syntax |
|---------|--------|
| Bully leader election | `cluster.leader()` returns the current bully-elected leader; reactive on membership change |
| Phi-accrual failure detector | Heartbeat-driven liveness; tunable phi threshold per cluster |
| Self-health | `cluster.self.health` — driver-side health metric stream |
| Federation | Multi-cluster gossip + cross-cluster routing — see [`specs/cluster-federation.md`](specs/cluster-federation.md) |
| Raft consensus | Strongly-consistent state machine — see [`specs/cluster-raft.md`](specs/cluster-raft.md) |
| ZooKeeper client | `zkClient { … }` for legacy coordinator integration — see [`specs/client-zookeeper.md`](specs/client-zookeeper.md) |
| Operational HTTP routes | `/_ssc-cluster/status`, `/_ssc-cluster/members`, `/_ssc-cluster/leader` — built-in across all 4 frontend / Node backends |

## Examples

| File | Description |
|------|-------------|
| [hello.ssc](examples/hello.ssc) | Minimal "Hello, World!" |
| [fs-roundtrip.ssc](examples/fs-roundtrip.ssc) | `std.fs` write / read / append / list / delete round-trip |
| [os-env.ssc](examples/os-env.ssc) | `std.os` env vars, path helpers, platform detection |
| [nfc-ndef.ssc](examples/nfc-ndef.ssc) | `std.nfc` capability check plus text/URI/MIME NDEF record constructors |
| [rozum-agent.ssc](examples/rozum-agent.ssc) | Self-contained fake rozum gateway plus `std.agent` tool-call loop over an accounting-style tool |
| [rozum-agent-schema-derived.ssc](examples/rozum-agent-schema-derived.ssc) | Self-contained fake rozum gateway with derived `AgentSchema` and explicit tool schemas side by side |
| [rozum-agent-pool.ssc](examples/rozum-agent-pool.ssc) | Self-contained fake primary/secondary rozum gateways plus `AgentEndpointPool` failover |
| [rozum-agent-streaming.ssc](examples/rozum-agent-streaming.ssc) | Self-contained fake rozum SSE gateway plus `runAgentStream` callbacks for text/tool-call events |
| [yaml-parse.ssc](examples/yaml-parse.ssc) | `std.yaml` parse/access/stringify plus heading-scoped fenced YAML; exact on core-free 2.1 VM/direct ASM/build-jvm |
| [ui-typed-json.ssc](examples/ui-typed-json.ssc) | `std.json` navigable JsonValue (total accessors, exact `asDecimal`) + structured builders |
| [ui-fetch-json.ssc](examples/ui-fetch-json.ssc) | `fetchJsonValue` (GET → navigable JsonValue) + `fetchJsonAction` (POST structured body) |
| [frontend/std-ui/styled-primitives.ssc](examples/frontend/std-ui/styled-primitives.ssc) | `std.ui` status primitives (badge/tag/pill/kpiCard/tabBar), token-aware `styled()`, `box` sizing; re-themes under dark |
| [datatable-static-spa.ssc](examples/datatable-static-spa.ssc) | Browser SPA backed by a static in-memory `DataTable` (`staticDataTable` / `fieldsBody`); renders client-side with no fetch, identical on interpreter / JVM / JS |
| [index.ssc](examples/index.ssc) | Landing page for the `serve` examples browser |
| [script.ssc](examples/script.ssc) | Functions, loops, Fibonacci |
| [data-types.ssc](examples/data-types.ssc) | Case classes, sealed traits, enums, pattern matching |
| [functional.ssc](examples/functional.ssc) | Lambdas, closures, HOF, composition, pipelines |
| [enums.ssc](examples/enums.ssc) | Simple and parameterised enums, recursive ADTs |
| [extensions.ssc](examples/extensions.ssc) | Extension methods, for comprehensions, while, recursion |
| [imports.ssc](examples/imports.ssc) | Math, geometry, statistics |
| [multi-link-imports.ssc](examples/multi-link-imports.ssc) | Two std modules imported from one pure Markdown paragraph |
| [typeclass.ssc](examples/typeclass.ssc) | Show, Eq, Ord, Monoid, Functor via `given`/`summon` |
| [quoted-macro-interpreter.ssc](examples/quoted-macro-interpreter.ssc) | Restricted quoted macros on the interpreter run path with `Expr.asValue` / `Expr.asTerm` |
| [quoted-macro-constfold.ssc](examples/quoted-macro-constfold.ssc) | Compile-time constant folding via `Expr.asValue match` (literal → `Some`, else `None`); interpreter run + `ssc link` fold |
| [custom-derives-mirror.ssc](examples/custom-derives-mirror.ssc) | User-defined typeclass `derives` through runtime `Mirror` metadata |
| [typed-data.ssc](examples/typed-data.ssc) | Data pipelines, Option, enums |
| [graph-storage.ssc](examples/graph-storage.ssc) | JVM `graphs:` front matter plus generated `Graph.*` facade over embedded TinkerGraph property graph storage |
| [graph-rdf4j-storage.ssc](examples/graph-rdf4j-storage.ssc) | JVM `graphs:` front matter plus generated `Graph.*` and `Sparql.select` facades over RDF4J memory RDF storage |
| [graph-storage-interpreter.ssc](examples/graph-storage-interpreter.ssc) | Interpreter `Graph.*` facade over in-memory property graph storage |
| [content.ssc](examples/content.ssc) | `md` interpolator, auto-output, `doc`/`render` |
| [content-introspection.ssc](examples/content-introspection.ssc) | Markdown-to-frontend authoring: Markdown body content, YAML data, section ids, and multiple real `std/ui` controls declared directly in `yaml @id=... @ui=toolkit` blocks and selected by id; runs as a live `serve(page, 8099)` phone preview |
| [std-ui/raw-html-demo.ssc](examples/std-ui/raw-html-demo.ssc) | Toolkit v2 `rawHtml` escape hatch demo: trusted markup injection beside escaped `rawText` |
| [std-ui/tri-state-demo.ssc](examples/std-ui/tri-state-demo.ssc) | Toolkit v2 loading/empty/error helper demo: `triStateText` switches a fetched view over existing state signals |
| [markdown-toolkit-links.ssc](examples/markdown-toolkit-links.ssc) | Live `serve(page, 8099)` example where `textField`, `checkbox`, `button`, `signalText`, and `badge` controls are declared as ordinary Markdown `toolkit:` links instead of YAML, including a visible Apply-status update |
| [content-to-markdown.ssc](examples/content-to-markdown.ssc) | Reverse-render selected `DocumentContent` regions back to semantic Markdown with `contentToMarkdown(value)` |
| [content-linked-namespaces.ssc](examples/content-linked-namespaces.ssc) | Read section content from a directly imported module namespace with `contentModuleSection(namespace, id)` and render the imported money module's BigInt minor-unit result identically on native VM/ASM and `build-jvm` |
| [content-tables.ssc](examples/content-tables.ssc) | Use a Markdown GFM pipe table as `ContentBlock.Table`, show raw `${name}` placeholders, bind YAML data with `contentBind(...)`, and lower the bound table through `contentToolkitBlock(id)` to a toolkit `TableNode` |
| [content-toolkit-transitive/app.ssc](examples/content-toolkit-transitive/app.ssc) | Entry module renders a `@ui=toolkit` control block through `contentToolkitBlock(id)` called from a transitively-imported child — the JS backend emits the content-toolkit runtime for imports anywhere in the graph |
| [content-live-rows.ssc](examples/content-live-rows.ssc) | Bind a live fetch `Signal` as the rows of a Markdown-authored table: a `toolkit:table?rows=<id>` link resolves a `contentRows(id, signal, columns)` registration to a live `DataTable` (content-toolkit 3b) |
| [content-toolkit-yaml-controls.ssc](examples/content-toolkit-yaml-controls.ssc) | Declarative `@ui=toolkit` YAML control tree whose `{type: button, action: <id>}`, `{type: table, source: <id>}`, and `{type: signalText, signal: <id>}` reference a registered action / data source / computed signal by id, and the table declares typed `columns:` (text/date/money/status/link) inline (declarative-ui Scope B.1 + B.5 + B.2) |
| [content-data-source.ssc](examples/content-data-source.ssc) | `@ui=toolkit` tables bound to **named data sources** — `contentDataSource(id, source, columns)` registers a `fetchSource(...)` (managed GET, re-fetch on tick, with an optional dotted `rowsPath` envelope path) or a `staticSource(...)` (in-memory rows) under an id that `source:` resolves (declarative-ui Scope B.3) |
| [content-action-onsuccess.ssc](examples/content-action-onsuccess.ssc) | A `@ui=toolkit` button bound to an action whose **structured `onSuccess`** runs after a 2xx — `fetchActionWith(method, url, body, [onBumpTick(t), onSetSignal(s, v), onNavigate(path)])` refreshes a table, flashes a status signal, and routes, in order; a failed POST runs none (declarative-ui Scope B.4) |
| [content-form-submit.ssc](examples/content-form-submit.ssc) | A `@ui=toolkit` form whose submit action assembles its POST body from the **named field signals** — `fetchActionWith("POST", url, formBody([("customerName", "customer"), "amount"]), …)` serialises `{customerName, amount}` from the live signals at click (a `(jsonKey, signalId)` entry remaps a signal to a different wire key), no hand-maintained body signal (declarative-ui Scope B.4+) |
| [content-slot.ssc](examples/content-slot.ssc) | A `@ui=toolkit` panel with a `{type: slot, id: <id>}` **escape hatch** filled by a code-built `TkNode` registered with `contentSlot(id, node)` — when the declarative vocabulary can't express a widget, the author drops a ScalaScript-authored one into the panel by id (declarative-ui Scope B.6) |
| [markdown-native-controls.ssc](examples/frontend/markdown-native-controls/markdown-native-controls.ssc) | Same Markdown/YAML-declared controls rendered through native clients: `ssc run-jvm --frontend swing|javafx` for JVM desktop or `ssc emit --frontend swiftui` for SwiftUI source |
| [recursion.ssc](examples/recursion.ssc) | Self-TCO, mutual TCO, Collatz — deep recursion without overflow |
| [effects.ssc](examples/effects.ssc) | Algebraic effects — one-shot Console/Fail operations, explicit multi-shot nondeterminism, early return |
| [std-effects-demo.ssc](examples/std-effects-demo.ssc) | Logger, Random, Clock, State, Env standard effects |
| [direct-demo.ssc](examples/direct-demo.ssc) | `direct[M]` do-notation, `.!` postfix bind, effect-row unions |
| [async-demo.ssc](examples/async-demo.ssc) | Built-in `Async` effect — `runAsync`, `async`, `await`, `parallel`, `delay` |
| [coroutine-demo.ssc](examples/coroutine-demo.ssc) | `coroutineCreate`, `coroutineResume`, `suspend`, generators |
| [signals-demo.ssc](examples/signals-demo.ssc) | Reactive signals — `Signal`, `computed`, `effect`, diamond dedup |
| [storage-demo.ssc](examples/storage-demo.ssc) | Built-in `Storage` effect — core-free JSON-backed and ephemeral handlers on native VM/direct ASM |
| [actors-demo.ssc](examples/actors-demo.ssc) | Actors — spawn, send, receive, supervision, cluster |
| [actors-pingpong.ssc](examples/actors-pingpong.ssc) | Core-free local actor lifecycle — FIFO send, typed receives, timeout, `self`, and `exit` on native VM/direct ASM/build-jvm |
| [actors-typed-remote-spawn.ssc](examples/actors-typed-remote-spawn.ssc) | Typed `ActorRef[M]` helpers plus named `registerBehavior` / `spawnRemote` |
| [cluster-capability.ssc](examples/cluster-capability.ssc) | `ClusterCapability`, static seed discovery, and code identity checks |
| [remote-registry-rpc.ssc](examples/remote-registry-rpc.ssc) | `remoteHandlers:` / `@remote` / `remote def` registry declarations, `Remote.function`, typed `remoteTryCall`, HTTP JSON fallback path metadata, and generated `RemoteRpc` typed client metadata for `path:` handlers |
| [openapi-annotation.ssc](examples/openapi-annotation.ssc) | `@openapi(...)` route metadata and `openApiSecurity(...)` security schemes surfaced in `/_openapi.json` and Swagger UI |
| [ws-recv-demo.ssc](examples/ws-recv-demo.ssc) | Sync-style `ws.recv()` loop alternative to `onMessage` callbacks |
| [mcp-demo.ssc](examples/mcp-demo.ssc) | MCP server with tools and resources; MCP client usage |
| [dataset-stats.ssc](examples/dataset-stats.ssc) | Dataset MapReduce — `runLocal`, `runParallel`, aggregations |
| [distributed-word-count.ssc](examples/distributed-word-count.ssc) | Offline distributed word count using local MapReduce actor workers via `localLoopbackCluster` |
| [distributed-log-aggregation.ssc](examples/distributed-log-aggregation.ssc) | Offline distributed ERROR-count aggregation; pass a log path after `--` |
| [distributed-join.ssc](examples/distributed-join.ssc) | Offline distributed customer/order join using local shuffle workers; pass order and customer CSV paths after `--` |
| [dsl-demo.ssc](examples/dsl-demo.ssc) | Parser combinators, error recovery, multi-pass compilation pipeline |
| [lenses.ssc](examples/lenses.ssc) | Native standard-path `.copy`, structural `Focus` Lens/Optional/Traversal, and `Prism` with no macro/compiler fallback |
| [default-params.ssc](examples/default-params.ssc) | Default parameter values on defs, classes, and enum cases |
| [lang-split.ssc](examples/lang-split.ssc) | `scala` vs `scalascript` block annotations side by side |
| [scala-js-demo.ssc](examples/scala-js-demo.ssc) | Pure `scala` 3 document — runs on all three backends with byte-identical output |
| [rest-api.ssc](examples/rest-api.ssc) | Tiny in-memory REST API — `route()`, `html"..."`, `serve()` |
| [mount-demo/](examples/mount-demo/) | `mount()` intrinsic — file-based handlers, typed (`CaseClass => CaseClass` auto-deser/ser), 1-arg, 2-arg with ctx, static response |
| [sql-sqlite-file.ssc](examples/sql-sqlite-file.ssc) | SQLite file database — `databases:` front-matter, `sql` DDL/DML blocks, `Db.query/execute` |
| [scljet-readonly-codecs.ssc](examples/scljet-readonly-codecs.ssc) | Pure low-level SQLite 3.53.3 header, B-tree cell, and record decoding without JDBC/sql.js |
| [scljet-readonly.ssc](examples/scljet-readonly.ssc) | Write a pinned SQLite image through the JVM VFS plugin, then open its schema and stream a table row through SclJet's pure immutable pager |
| [typed-sql-crud.ssc](examples/typed-sql-crud.ssc) | Typed SQL CRUD — `derives RowCodec`, `Db.insert/update/query[A]` on interpreter and JVM codegen paths |
| [typed-object-codec.ssc](examples/typed-object-codec.ssc) | Typed object/document codec — `derives ObjectCodec`, portable object fields, aliases/defaults, and key extraction |
| [graph-codecs.ssc](examples/graph-codecs.ssc) | Typed graph/RDF codecs — `derives VertexCodec`, `EdgeCodec`, and `RdfCodec` |
| [dataset-typed-mapping.ssc](examples/dataset-typed-mapping.ssc) | Typed Dataset element mapping — `DatasetCodec[A]` over `JsonCodec[A]` in a JVM `Dataset` pipeline |
| [distributed-dataset-codec.ssc](examples/distributed-dataset-codec.ssc) | Distributed Dataset partition payloads — `DatasetCodec.encodePartitions/decodePartitions[A]` |
| [distributed-dataset-wire-protocol.ssc](examples/distributed-dataset-wire-protocol.ssc) | Distributed Dataset wire protocol — `DatasetWirePartition` payloads through local MapReduce actor workers |
| [distributed-dataset-wire-shuffle.ssc](examples/distributed-dataset-wire-shuffle.ssc) | Distributed Dataset wire shuffle — `DatasetWirePartition` reduceByKey through local MapReduce actor workers |
| [distributed-dataset-typed-helpers.ssc](examples/distributed-dataset-typed-helpers.ssc) | Distributed Dataset typed helpers — `DistributedDataset.run/runShuffle[A, B]` around actor map + shuffle wire execution |
| [spark-schema-mapping.ssc](examples/spark-schema-mapping.ssc) | Spark-like schema metadata — `derives SparkSchemaCodec` using shared field annotations |
| [spark-shared-schema-reader.ssc](examples/spark-shared-schema-reader.ssc) | Spark typed CSV reader — `Dataset.fromCsvAs[A]` using `SparkSchemaCodec[A]` external column names |
| [indexeddb-drafts.ssc](examples/indexeddb-drafts.ssc) | Typed client IndexedDB store — `IndexedDb.store[A]`, local put/get/all/keys, `awaitClient(...)` |
| [indexeddb-sync-client.ssc](examples/indexeddb-sync-client.ssc) | Typed client sync helper — `Sync.push[A]` / `Sync.pull[A]` over local IndexedDB and REST sync endpoints |
| [sync-todo.ssc](examples/sync-todo.ssc) | Full sync UI helper API — `Sync.put`, `Sync.sync`, `Sync.status`, `Sync.isOnline`, `Sync.isSyncing`, `Sync.pending` |
| [object-store-jdbc.ssc](examples/object-store-jdbc.ssc) | Typed server ObjectStore — JDBC JSON table, `ObjectStore.put/get/all/delete/changes[A]`, `derives ObjectCodec` |
| [object-store-sync-routes.ssc](examples/object-store-sync-routes.ssc) | Typed ObjectStore sync routes — `objectStores:` front matter generates JVM REST pull/push endpoints |
| [sql-transaction.ssc](conformance/sql-transaction.ssc) | Atomic multi-statement `transaction` block — debit + credit in one JDBC transaction |
| [spa-demo.ssc](examples/spa-demo.ssc) | Same `route()` / `serve()` source, browser SPA via `ssc emit-spa` |
| [pwa-demo.ssc](examples/pwa/pwa-demo.ssc) | Progressive Web App — `pwa(name, icons, precache)`, `GET /manifest.json` + `GET /sw.js` |
| [swing-hello.ssc](examples/frontend/swing-hello/swing-hello.ssc) | Minimal JDK-only Swing desktop window |
| [swing-fullstack/](examples/frontend/swing-fullstack/) | No-socket JVM desktop full-stack example: Swing `fetchActionClear` posts to generated JVM backend routes and `dataTable` reads/deletes rows in the same process |
| [swing-typed-client/](examples/frontend/swing-typed-client/) | No-socket JVM desktop full-stack example using generated `apiClients:` methods over backend routes |
| [typed-client-distributed/](examples/frontend/typed-client-distributed/) | Same-source distributed typed route client: JVM server on one machine, browser/Electron client on another via `--server-url` |
| [webauthn-toolkit-demo/](examples/frontend/webauthn-toolkit-demo/) | Full-stack passkey enrol/sign-in demo using `std/auth.ssc` server verifiers and `std/ui/webauthn.ssc` browser actions |
| [auth-demo.ssc](examples/auth-demo.ssc) | Login / logout with signed cookie sessions + CSRF tokens |
| [fetch-auth.ssc](examples/fetch-auth.ssc) | Bearer-token authed fetch: `fetchActionClear` with `headers` Signal (v1) + `fetchUrlSignal` GET with headers (v2) |
| [seed-signal.ssc](examples/seed-signal.ssc) | Editable draft signal seeded from `fetchUrlSignal`; reloads update the draft only while it is pristine |
| [oauth-demo.ssc](examples/oauth-demo.ssc) | Full OAuth2 sign-in (GitHub or Google) — state, exchange, userinfo |
| [tls-demo.ssc](examples/tls-demo.ssc) | HTTPS + WSS server with `tls(cert, key)` |
| [wc-demo.ssc](examples/wc-demo.ssc) | Web Components via `ssc emit-wc`, SSR + hydration |
| [wasm-fibonacci.ssc](examples/wasm-fibonacci.ssc) | `scalascript` → WebAssembly module via `ssc emit-wasm` |
| [wasm-sorting.ssc](examples/wasm-sorting.ssc) · [wasm-matrix.ssc](examples/wasm-matrix.ssc) · [wasm-primes.ssc](examples/wasm-primes.ssc) · [wasm-collections.ssc](examples/wasm-collections.ssc) | Wasm benchmark suite |
| [wasm-scalascript.ssc](examples/wasm-scalascript.ssc) | `scalascript` blocks → WebAssembly — Point geometry with `//> using dep` |
| [wasm-http.ssc](examples/wasm-http.ssc) | HTTP Fetch via scalajs-dom in Wasm — `//> using dep` hoisted directive |
| [algebraic-effects.ssc](examples/algebraic-effects.ssc) | Typed effects end-to-end — discharge signatures, `Reader[R]`, `NonDet`, `multi effect` |
| [examples/frontend/counter/](examples/frontend/) · [show-hide/](examples/frontend/show-hide/) · [todo/](examples/frontend/todo/) · `toolkit-demo` | One source compiled to React / Vue / Solid / Custom — first three via Frontend Framework SPI, **toolkit-demo** via high-level Toolkit (`Tk` facade) and covered by an Electron Add-flow smoke test |
| [x402-server.ssc](examples/x402-server.ssc) · [x402-client.ssc](examples/x402-client.ssc) | HTTP 402 micropayment server + client (Ethereum settlement) |
| [x402-metamask.ssc](examples/x402-metamask.ssc) | Browser x402 wallet helper — connect MetaMask and sign EIP-712 via `window.ethereum` |
| [x402-cardano.ssc](examples/x402-cardano.ssc) | x402 on Cardano — CIP-8 wallet, Scalus escrow validator, end-to-end client + server |
| [wallet-ledger-js.ssc](examples/wallet-ledger-js.ssc) | Browser Ledger/WebHID vault sketch — connect lifecycle, Ethereum signer, Cardano CIP-8 helper |
| [wallet-mpc-fireblocks.ssc](examples/wallet-mpc-fireblocks.ssc) | JVM Fireblocks MPC vault sketch — remote secp256k1 signing through Fireblocks RAW transactions |
| [spark-sql-demo.ssc](examples/spark-sql-demo.ssc) | Spark SQL via `sql` fenced blocks + `${expr}` bind parameters + section aliases |
| [spark-encoder-demo.ssc](examples/spark-encoder-demo.ssc) | Scala 3 native `Encoder[T]` derivation — `Dataset[CaseClass]` end-to-end on Spark 4 |
| [spark-streaming-rate-console.ssc](examples/spark-streaming-rate-console.ssc) | Structured Streaming — rate source → console sink with auto-`awaitTermination` |
| [spark-delta-demo.ssc](examples/spark-delta-demo.ssc) | Delta Lake — auto-emit `delta-spark` dep + extension/catalog configs |
| [spark-hive-demo.ssc](examples/spark-hive-demo.ssc) | Hive metastore via `spark-hive-metastore:` + `@TempView("...")` + `Dataset.fromTable[T]` |
| [spark-mllib-pipeline.ssc](examples/spark-mllib-pipeline.ssc) | MLlib — Tokenizer + HashingTF + LogisticRegression pipeline end-to-end |
| [xslt-transform.ssc](examples/xslt-transform.ssc) | XSLT 1.0 transformation — identity, element rename, parameter substitution, HTML generation (`MarkupCodec.transform`) |

Run them all at once:

```bash
./examples/run-all.sc
```

### Frontend Toolkit demo

A reference SPA built entirely through the high-level `Tk` facade
(layout + form-like fields + display widgets + theming).  Compiles
to all four frontend backends + static HTML via SSR.

```bash
# 1. Compile + test (frontend-toolkit 217 tests, frontend-examples 41)
sbt frontendToolkit/test frontendExamples/test

# 2. Generate 16 (4 demos x 4 backends) HTML+JS bundles
sbt "frontendExamples/runMain scalascript.frontend.examples.EmitAll"
#   → target/frontend-examples/toolkit-demo/{custom,react,solid,vue}/

# 3. Serve via the bundled ssc static-file server (no Python/Node)
ssc serve 8000 target/frontend-examples/toolkit-demo/react
# open http://localhost:8000/
```

Details: [`examples/frontend/README.md`](examples/frontend/README.md)
and [`docs/user-guide.md#16-frontend-toolkit`](docs/user-guide.md).
For SSR (`Ssr.renderToHtml`) and the full widget catalog see
[`specs/frontend-toolkit-spec.md`](specs/frontend-toolkit-spec.md).

## Benchmarks

### Cold start (interpreter)

The interpreter uses lazy plugin loading (v1.33): the ServiceLoader scan for
HTTP/SQL/OAuth/etc. plugins is deferred until the first plugin name is actually
accessed.  Scripts that never call a plugin skip the scan entirely.

| Script | Steady-state cold start |
|--------|------------------------|
| `hello.ssc` (no plugins) | ~0.31 s |
| Script with HTTP/SQL/auth | ~0.35 s (scan runs on first access) |

**Application Class-Data Sharing (AppCDS)** is enabled in `bin/ssc` and the
`install.sh` launcher (`-XX:+AutoCreateSharedArchive`, JDK 19+; the archive is
auto-created on first run and auto-recreated on classpath change — no build
step). It cuts a fresh `ssc run hello.ssc` from **~378 ms → ~182 ms (−51%)** and
peak RSS from **167 → 114 MB (−32%)**. Old JDKs ignore the flags; opt out with
`SSC_NO_CDS=1`. Three `real-workload-perf` harnesses live under `tests/perf/`:
`coldstart/` (fresh-run wall-clock + RSS), `serverrss/` (steady-state server RSS
+ leak detection — the interpreter server settles at ~195 MB with no climb), and
GC-under-load.

### Microbenchmarks (`scripts/bench`)

Everything benchmark-related goes through one wrapper:

```bash
scripts/bench interp                  # all interpreter microbenchmarks
scripts/bench interp recursionFib     # filter to one bench
scripts/bench cross                   # interp vs JS vs JVM (RuntimeBench)
scripts/bench off recursionFib        # prove the off-mode fall-back works
scripts/bench profile recursionFib    # JFR alloc + GC profile
scripts/bench wall                    # cross-language wall-clock (ssc/scala/node)
scripts/bench help                    # full command list
```

The canonical reference — what each bench measures, when to use it, how to
add a new one — is [`docs/benchmarks.md`](docs/benchmarks.md).

Quick non-blocking smoke checks for perf-sensitive changes:

```bash
ssc bench --smoke               # corpus-driven CLI smoke
scripts/bench smoke             # one-iter JMH smoke (writes bench/jmh-smoke.json)
```

The checked-in workflow and baseline policy live in
[`bench/perf-manifest.yaml`](bench/perf-manifest.yaml) and
[`bench/README.md`](bench/README.md). Raw JMH/runtime outputs are ignored; only
curated summaries should be promoted into tracked baseline files.

### Watch reload benchmark

`ssc watch-bench` measures the same parse-cache, incremental typer, and
interpreter reload path that `ssc watch` uses, but runs against a temporary
copy so the source file is not modified:

```bash
ssc watch-bench --cycles 10 --target-ms 100 examples/rest-api.ssc
ssc watch-bench --cycles 10 --target-ms 100 --require-target examples/rest-api.ssc
```

The command reports warm-up, p50, and max cycle times. `--require-target`
turns the target into a non-zero exit condition for local performance gates.

## End-to-end smoke

`e2e/rest-smoke.sc` boots `examples/rest-api.ssc` through each of the three
backends in turn and diffs their HTTP responses — guards against drift between
the interpreter / JVM / JS serve runtimes.

```bash
scala-cli e2e/rest-smoke.sc
```

## Conformance Suite

Cross-backend tests that verify JVM interpreter and JS transpiler produce identical output.

Run through the **RAM-bounded wrapper** (recommended) — it bounds how many conformance runs execute at
once host-wide and caps the child JVM heap, so parallel worktrees don't saturate the machine (see
`specs/conformance-perf.md`):

```bash
scripts/conformance                 # recommended (guarded); forwards args to run.sc
# or, unguarded:
scala-cli conformance/run.sc
```

| Test | What it covers |
|------|----------------|
| arithmetic | Integer and floating-point ops, `math.*` |
| strings | String methods and interpolation |
| collections | `List` — map, filter, fold, take, drop, … |
| option | `Option` — `map`, `getOrElse`, `filter`, … |
| pattern-matching | Literals, guards, `Option`, tuple patterns |
| case-classes | Case class construction, field access, pattern matching |
| for-comprehensions | `yield`, guards, nested generators, `do` |
| higher-order-functions | Lambdas, `compose`, `flatMap`, eta-expansion |
| recursion | Factorial and Fibonacci |
| tail-recursion | Self-TCO at depth 100 000 — `sum`, `countdown` |
| mutual-recursion | Mutual TCO — `isEven`/`isOdd` at depth 100 000 |
| sealed-traits | ADT hierarchy with `sealed trait` + `case class` |
| variables | `var` mutation and `while` loops |
| tuples | Tuple construction, `_1`/`_2`/`_3`, destructuring |
| maps | `Map` — `size`, `getOrElse`, `contains`, `keys`, `values` |
| list-companion | `List.fill`, `List.tabulate`, `List.range` |
| modules | `[name](./path.ssc)` imports — bind definitions from another file |
| effects | Algebraic effects: Console routing, Choose nondeterminism, Fail early-return |
| std-effects | Logger, Random, Clock, State, Env standard effects with default + test handlers |
| async | Built-in `Async` effect — `runAsync` drives `async` / `await` / `parallel` / `delay` |
| async-parallel | `runAsyncParallel` — real-thread Async on JVM |
| storage | Built-in `Storage` effect — `get` / `put` / `remove` / `has` / `keys` via ephemeral or file-backed handler |
| direct | Direct-syntax do-notation: `direct[M]`, `.!` bind, pure lift, control flow |
| coroutines | `coroutineCreate`, `coroutineResume`, `suspend`, `Step[Y,T]`, `coroutineCancel` |
| generators | `generator[T] { () => suspend(v) }`, lazy `map` / `filter` / `take` / `drop` / `flatMap` / `zip` pipelines |
| streams | `Source[A]` / `Sink[A]` / `Flow[A, B]`, `stream { emit(x) }`, bounded buffers, overflow strategies, wall-clock throttle/debounce, live `Source.signal`, and `sig.bind(source)` |
| signals | Reactive `Signal` / `computed` / `effect` with diamond-dedup flush |
| lenses | Native `.copy`, `Focus[T](_.a.b/.some/.each)`, and `Prism[Sum, Variant]` — get / set / modify / compose |
| prisms | `Prism[Sum, Variant]` — getOption / set / modify on enum / sealed-trait cases |
| optional | `Focus[T](_.maybe.some.field)` — Optional optic with getOption / set / modify / andThen |
| traversal | `Focus[T](_.items.each.field)` — Traversal with getAll / modify / set / andThen |
| actors | spawn / send / receive / supervision / link / exit |
| actors-dist | Distributed actor cluster, WS transport, gossip, leader election |
| mcp | MCP server tools + resources; MCP client callTool |
| dataset | `Dataset[T]` local sequential, parallel, distributed MapReduce |
| dsl | Parser combinators, error recovery, indentation-aware, multi-pass pipeline |
| metaprogramming | `inline`, `derives`, `compiletime.*`, restricted quoted macro link/interpreter expansion (partial), compiler-free product `Mirror.Of[T]` custom derives |
| checked-errors | `throws[A, E]`, `attemptCatch`, `HasStackTrace` |
| websocket | `onWebSocket`, `ws.send/recv`, rate limiting, `wss://` |
| tls | `tls(cert, key)`, HTTPS, WSS |

## Backends

ScalaScript supports the following bundled backends, all loaded through the
**Backend SPI** plugin architecture
([`specs/backend-spi.md`](specs/backend-spi.md)):

| Command | Backend id | How it works |
|---------|------------|--------------|
| `bin/ssc file.ssc` / `ssc run file.ssc` | `v2`        | Default self-hosted frontend/checker → CoreIR → v2 VM. `--bytecode` selects direct ASM; program args use `ssc run file.ssc -- [args...]`. There is no compatibility fallback. |
| `ssc run --native file.ssc` | `v2-native` | Idempotent assertion of the same standard ScalaScript 2.1 path: structural CoreIR + Frontmatter YAML + Markdown Profile → v2 VM, with no host Markdown/front-matter parser and no Scala CLI/scalac/javac process. `--native --bytecode` selects direct ASM; unresolved runtime dispatch/effects fail instead of printing `Stub`/`Op`. |
| `ssc-tools run --compat-frontend file.ssc` / `ssc-tools run --v1 file.ssc` | `int` | Explicit optional Scalameta/v1 compatibility paths. Plain `ssc` rejects these flags and names `ssc-tools`. |
| `ssc-tools run --target jvm file.ssc`      | `jvm`         | Compile via JvmGen → temp `.sc` → `scala-cli run`. True JVM semantics, no artifacts left on disk. Requires `scala-cli`. |
| `ssc-tools run-jvm file.ssc`               | `jvm`         | Alias for `ssc-tools run --target jvm` (kept for backward compatibility) |
| `ssc-tools run-js  file.ssc`               | `js`          | Compile via JsGen → temp `.js` → `node`. True Node.js semantics, no artifacts left on disk. Requires `node`. |
| `ssc-tools run-js --v2 file.ssc [args...]` | `v2-js`       | Explicit tools-tier v2 JS lane: FrontendBridge → CoreIR → v2 JsGen → temp `.cjs` → `node`. |
| `bin/jssc file.ssc`        | `js`          | Alias for `ssc run-js` via `bin/` wrapper |
| `bin/sscc file.ssc`        | `jvm`         | Alias for `ssc run-jvm` via `bin/` wrapper |
| `ssc emit-openapi file.ssc` | `openapi`     | Headless interpreter dry-run that exports registered routes as OpenAPI 3.1 JSON or YAML. Flags: `--format json\|yaml`, `-o`, `--title`, `--version`, repeatable `--server`. |
| `ssc emit-spa file.ssc`    | `scalajs-spa` | Self-contained SPA HTML + JS bundle |
| `bin/ssc-spark file.ssc`   | `spark`       | Apache Spark 4 — generates a Scala 3.7.1 `.sc` script with `//> using dep` directives, runs via `scala-cli`. Auto-detects `sql` blocks, `@SqlFn` UDFs, `readStream`/`writeStream`, `.format("delta")`, `@TempView`, MLlib imports. See [§13 of the User Guide](docs/user-guide.md#13-apache-spark). |
| `ssc emit-wasm file.ssc` / `examples/run-wasm.sh` | `wasm`        | WebAssembly module — `scalascript`/`ssc` blocks lowered to Wasm IR. Cross-backend `sql` fenced blocks supported (v1.27 Phase 5). |
| `ssc-native` (GraalVM) | `native` | No-JVM `ssc` binary; plugins via `ssc-plugin-host.jar` subprocess bridge |
| (sub-backend) | `node`        | Node.js runtime variant of `js` — emits server-side JS with `fs`/`path` shims and a cross-backend SQL runtime (v1.27 Phase 4). |
| (sub-backends) | `frontend-{react,vue,solid,custom}` | Frontend Framework SPI (v1.18 Phase A): same `.ssc` UI source compiled to React (`useState`/`useEffect`), Vue 3 (`ref`/render), Solid (`createSignal`/`createEffect`), or a minimal custom runtime — `ShowSignal`/`ToggleSignal`/`ForSignal` reactive primitives shared across all four. See [`specs/frontend-framework-spi-plan.md`](specs/frontend-framework-spi-plan.md). |
| JVM desktop sub-backend | `frontend-swing` | Partially implemented JDK-only JVM desktop frontend. It provides SPI discovery, `ssc run-jvm --frontend swing`, Swing source emission for text/buttons/fields/toggles/stacks/spacers/dividers/scroll views, basic style hints, local signal actions, Swing `fetchAction` / `dataTable` / typed route client dispatch through generated JVM `BackendTransport`, no-socket full-stack examples, and a `swing-plugin` skeleton for future interpreter intrinsics; JavaFX/Compose adapters remain planned. See [`specs/jvm-desktop-frontend.md`](specs/jvm-desktop-frontend.md). |

The `Backend` trait + `ServiceLoader` discovery let third parties
add their own backend without touching `core` — drop a JAR and
attach it via `ssc --plugin path/to/your-backend.jar`.  See
[`docs/writing-a-backend.md`](docs/writing-a-backend.md) and the
worked samples under `examples/plugins/`.

Useful flags:

```bash
ssc --list-backends                 # list every visible backend
ssc --list-source-languages         # list registered SourceLanguage plugins
ssc --describe-backend jvm          # capabilities + intrinsics + sources
ssc --backend js run file.ssc       # override the per-command default
```

Built-in fenced languages are SourceLanguage plugins too: `scala`, `html`,
`css`, `javascript`/`js`, `xml`, bind-aware `sql`, and bind-aware
`transaction` are visible through `--list-source-languages`. Third-party DSLs
follow the same path.

The JavaScript backend handles two block types differently:

- **`scalascript` blocks** — transpiled by our custom JS transpiler (`JsGen`), which supports
  ScalaScript-specific features (effects, content helpers, TCO, imports).
- **`scala` blocks** — compiled by Scala.js via `scala-cli --js`, giving full Scala 3 fidelity
  (standard library, type system, no custom runtime limitations).

When a `.ssc` file contains both, the Scala.js-compiled section runs first, followed by
the ScalaScript transpiled section.

## Compiler Plugins with Intrinsics

A **compiler plugin** lets you extend ScalaScript with new native capabilities —
cryptographic primitives, ML inference, GPU kernels, hardware I/O — by mapping
`extern def` declarations in a `.ssc` source to platform-specific implementations.

```scala
// crypto.ssc  (shipped inside the .sscpkg)
extern def sha256(input: String): String
extern def hmacSha256(key: String, data: String): String
```

Call it from any `.ssc` file just like a normal function:

```scala
import [Crypto](crypto)

val digest = sha256("hello, world")
println(hmacSha256("secret", digest))
```

Each `extern def` maps to an `IntrinsicImpl`:

| Variant | When to use |
|---------|------------|
| `NativeImpl(fn)` | Interpreter — call the JVM lambda directly |
| `RuntimeCall(sym)` | JVM / JS codegen — emit `sym(args…)` and ship `sym` as a runtime helper |
| `InlineCode(emit)` | Emit arbitrary target code at each call site |
| `HostCallback(name)` | Out-of-process backends — route through the host wire protocol |

Plugins are packaged as `.sscpkg` (a ZIP containing `manifest.yaml`,
`sources/*.ssc`, `runtime/jvm.scala` + `runtime/js.js`, and an `intrinsics/*.jar`
that registers a `Backend` via `ServiceLoader`):

```bash
ssc plugin pack  _pkg/   -o org.example.crypto-1.0.0.sscpkg
ssc plugin install      ./org.example.crypto-1.0.0.sscpkg   # permanent
ssc --plugin ./org.example.crypto-1.0.0.sscpkg run use-crypto.ssc  # ad-hoc
```

Installed distributions also include first-party advanced plugins locally under
`bin/lib/compiler/plugin-available`; use `--plugin` with one of those `.sscpkg`
files when you want opt-in capabilities without a registry domain.

See [`examples/plugins/crypto-plugin/`](examples/plugins/crypto-plugin/) for a
complete worked example and `docs/user-guide.md §21` for the full API reference.

## CLI Commands

```bash
ssc run file.ssc              # v2 VM default runner
ssc-tools run --v1 file.ssc   # explicit v1 tree-walking compatibility
ssc run --v2 file.ssc         # explicit v2 VM runner
ssc run --native file.ssc     # staged self-hosted frontend -> CoreIR -> v2 VM
ssc run --native --bytecode file.ssc # same native frontend -> direct ASM execution
ssc-tools run --compat-frontend file.ssc # explicit Scalameta bridge
ssc run file.ssc -- arg1 arg2 # pass program args to the v2 VM runner
ssc run --bytecode file.ssc -- arg1 arg2 # pass args to the v2 JVM bytecode lane
ssc-tools run --target jvm file.ssc # compile via JvmGen + run with scala-cli (no artifacts)
ssc-tools run-jvm file.ssc     # same as above (backward-compat alias)
ssc-tools run-js file.ssc      # compile via JsGen + run with node (no artifacts)
ssc-tools run-js --v2 file.ssc # explicit v2 CoreIR JS lane through node
ssc-tools watch file.ssc       # watch mode (re-run on change)
ssc-tools watch-bench file.ssc # benchmark watch reload cycles on a temp copy
ssc-tools bench --smoke        # quick interpreter-only benchmark wiring smoke
ssc-tools check file.ssc       # compatibility type-check only; exit 0=clean 1=type-err 2=parse-err 3=notfound
                              #   also warns on @ui=toolkit controls that reference an unregistered action/data-source id (declarative-ui B.7)
ssc check --json file.ssc     # structured JSON diagnostics
ssc check --quiet file.ssc    # no output, exit code only (for pre-commit hooks)
ssc check --watch file.ssc    # re-check on file change, Ctrl-C to stop
ssc check src/                # recursively check all *.ssc files in a directory
ssc repl                      # interactive REPL
ssc build myapp.ssc           # build project file → dist/ (--target ssc|jvm|js|web)
ssc build                     # auto-discover <dirname>.ssc or single .ssc in cwd
ssc build src/                # dir-walk mode (backward compat)
ssc package myapp.ssc         # build all targets: listed in frontmatter
ssc install [--prefix <dir>]  # install ssc to ~/.local (or custom prefix)
ssc compile-jvm file.ssc      # compile to .scjvm artifact
ssc compile-js file.ssc       # compile to .scjs artifact
ssc emit-interface file.ssc   # emit .scim interface
ssc emit-ir file.ssc          # emit .scir normalized IR
ssc link --backend jvm dir/   # link artifacts
ssc build --incremental dir/  # incremental build
ssc emit-js file.ssc          # transpile to JS
ssc emit-lib --host js|jvm|rust|java --feature optics -o dir/  # standalone host library (npm/jar/crate/maven)
ssc emit-spa file.ssc         # SPA HTML bundle
ssc emit-wc file.ssc          # Web Components bundle
ssc test file.ssc             # run tests
ssc preview file.ssc          # preview component variants
ssc deps file.ssc             # show import closure
ssc search json [--refresh|--offline]
ssc info io.scalascript/json [--registry <url>]
ssc add io.scalascript/json [<version>] [--file <manifest>]
ssc info artifact.scjvm       # inspect artifact
ssc plugin install/list/uninstall/check/pack/registry
ssc --list-backends / --describe-backend <id>
jssc file.ssc                 # JS transpiler runner
sscc file.ssc                 # JVM runner
ssc-spark file.ssc            # Apache Spark runner (Spark 4 + Scala 3.7.1)
ssc submit file.ssc           # spark-submit fat JAR (cluster deploy)
ssc --spark-master <url>      # override Spark master (local[*] / spark:// / yarn / k8s://)
ssc --spark-version <v>       # override Spark version (default 4.0.0)
```

## Project Layout

```text
bin/
  ssc          # interpreter launcher (tree-walking, no compilation step)
  jssc         # JS runner: transpiles .ssc → JS and runs via Node.js
  sscc         # JVM runner: compiles .ssc → Scala 3 → JVM via scala-cli
  ssc-js       # JS transpiler: emit JS to stdout, or --run to execute
  http.ssc     # HTTP server for examples browser

runtime/backend/spi/        # SPI traits (Backend, SourceLanguage, PluginRegistry, Capabilities, …)
runtime/scalascript-plugin-api/ # Stable plugin author API (PluginValue, PluginNative, capability traits)
v2/interop/descriptor/      # Target-neutral canonical API/control/artifact descriptors
ir/                         # IR types + JSON/MsgPack codecs
core/
  src/main/scala/scalascript/
    parser/    # Markdown + YAML + Scala parser
    typer/     # Type checker
    ast/       # AST types
    imports/   # Cross-file import resolver
    interpreter/Value.scala  # Computation Free monad (used by interpreter+codegens)
    transform/ # Normalize, DirectDesugar, EffectAnalysis
    plugin/    # PluginRegistry facade, BackendRegistry, SubprocessBackend, WireProtocol
runtime/backend/jvm/      # JvmGen — emits Scala 3 source
runtime/backend/js/       # JsGen — transpiles to JavaScript
runtime/backend/scalajs/  # ScalaJsBackend — emits SPA via Scala.js
runtime/backend/interpreter/
  src/main/scala/scalascript/
    interpreter/    # Tree-walking interpreter
    server/         # Built-in HTTP / WebSocket / Actor / MCP runtime
    bench/          # WsStress benchmark
runtime/runtime-server/common/      # Shared HTTP/WS server primitives (all backends)
mcp/common/                 # Shared MCP protocol types + codec
cli/                        # Main entry point (ssc command)

conformance/     # Cross-backend conformance test suite
  expected/      # Canonical expected outputs

examples/        # Runnable .ssc files
  run-all.sc     # Runs all examples in order
  plugins/       # Worked backend plugin examples

build.sbt        # sbt build — multi-module per spec §4.1
project/
  build.properties
  plugins.sbt    # sbt-assembly for fat-jar packaging

docs/            # Architecture, spec, design docs
  architecture.md        # Compiler pipeline and module structure
  backend-spi.md         # Backend SPI design (source of truth)
  direct-syntax.md       # Direct do-notation (v1.8+)
  coroutines.md          # Coroutine primitive (v1.9+)
  dsl.md                 # DSL authoring and parser combinators (v1.20)
  mapreduce.md           # Dataset / MapReduce API (v1.21–1.22)
  mcp.md                 # MCP server + client (v1.17)
  actors-dist.md         # Distributed actors design
  metaprogramming.md     # inline/derives (v1.14)
  error-handling.md      # Checked errors / throws (v1.15)
  modularity.md          # Package system and separate compilation
  markdown-as-syntax.md  # Markdown as Syntax design
  targets.md             # Target Backends
  user-guide.md          # Practical user reference
  tutorial.md            # Step-by-step todo-list application tutorial
tools/scripts/         # launchers/ and validate-frontmatter.scala
```

## Installing as a Binary

```bash
# Install scala-cli first (if needed)
./setup.sh

# Build ssc into bin/
./install.sh --dev
```

After installation:

```bash
ssc examples/hello.ssc
jssc examples/hello.ssc
sscc examples/hello.ssc
```

## Library Usage (sbt)

ScalaScript can be used as a Scala 3 library via sbt:

```bash
sbt compile      # compile all modules
sbt test         # run unit tests
sbt cli/assembly # produce a self-contained ssc.jar
sbt cli/installBin # stage bin/lib so bin/ssc works from the checkout
```

The public API surface:

```scala
import scalascript.parser.Parser
import scalascript.interpreter.Interpreter
import scalascript.codegen.{JsGen, JvmGen}

val module = Parser.parse(source)            // parse a .ssc file
Interpreter.run(module)                      // interpret
val js    = JsGen.generate(module)           // emit JavaScript
val scala = JvmGen.generate(module)          // emit Scala 3 script
```

The target-neutral descriptor leaf is the sbt project `v2InteropDescriptor` at
`v2/interop/descriptor`; its Maven coordinate is
`io.scalascript:scalascript-interop-descriptor_3`. The
`scalascript.interop.descriptor` package exposes bounded canonical codecs and
checked factories. Slice A supplies infrastructure only: compiler/linker
producers and runtime/admission consumers remain deferred.

Plugin dependency identity and admission live in sbt project
`v2PluginCapabilityProfile` at `v2/interop/plugin-profile`, published as
`io.scalascript:scalascript-plugin-profile_3`. The
`scalascript.interop.plugin` package exposes checked declaration/implementation
binding, one aggregate `DependencyKind.Plugin` projection, and pure inventory
validation. `ssc.plugin.NativePlugin.capabilityDeclaration` is an optional JVM
adapter; legacy providers keep the concrete `None` default. Automatic `.sscpkg`
carriers and linker population remain deferred.

ScalaScript libraries can also be packaged as `.ssclib` archives:

```bash
ssc package --lib --precompile --manifest ssclib-manifest.yaml -o dist/my-lib.ssclib
ssc check-compat dist/my-lib-1.0.ssclib dist/my-lib-1.1.ssclib
```

`--precompile` embeds `.scim` public interface artifacts under `ir/`.
`check-compat` reports removed or changed public symbols between library
versions and falls back to deriving interfaces from packaged sources when a
library has no precompiled interface artifacts.

## Design Principles

1. **Reuse, don't invent.** Markdown, YAML, Scala 3 — use what works.
2. **One source, many targets.** Source semantics are target-independent.
3. **Human and machine readable.** Pleasant for humans, trivially parseable for machines.
4. **No AI at runtime or compile time.** The language stands on its own.

## License

[Apache License 2.0](LICENSE)

## Author

Sergiy (Victorovych) Shcherbyna <sergey.scherbina@gmail.com>
