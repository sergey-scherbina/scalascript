# ScalaScript

A language where **Markdown is syntax, not decoration** — `.ssc` files are
executable documents combining YAML front-matter, Markdown prose, and
Scala 3 code blocks.

`.ssc` files support two code-block languages:

| Annotation | Language | Backends |
|------------|----------|----------|
| ` ```scalascript` | ScalaScript dialect — effects, handlers, content helpers, TCO | interpreter · JS transpiler · JVM |
| ` ```ssc` | Alias for `scalascript` | interpreter · JS transpiler · JVM |
| ` ```scala` | Standard Scala 3 — no ScalaScript extensions | interpreter · **Scala.js** (JS) · JVM |

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

**Requirements:** [scala-cli](https://scala-cli.virtuslab.org) · [Node.js](https://nodejs.org) (for JS backend) · [sbt](https://www.scala-sbt.org) (optional, for library use)

```bash
git clone https://github.com/sergey-scherbina/scalascript
cd scalascript

# One-time setup: builds the self-contained ssc launcher and symlinks the
# scripts/launchers/* wrappers into bin/.  The entire bin/ is generated
# and gitignored — sources live in scripts/launchers/.
./install.sh

# Pipe sops-decrypted secrets into a script (${sops:key} references in databases:)
sops -d secrets.enc.yaml | ssc myapp.ssc

# Interpreter (tree-walking, no compilation step)
bin/ssc examples/hello.ssc

# Watch mode — re-run on every file change
bin/ssc watch examples/hello.ssc

# Interactive REPL
bin/ssc repl

# Transpile to JavaScript and run via Node.js
bin/jssc examples/hello.ssc

# Compile to JVM bytecode and run via Scala 3 / scala-cli
bin/sscc examples/hello.ssc

# Run on Apache Spark (Spark 4.0.0 / Scala 3.7.1, local[*] by default)
bin/ssc-spark examples/spark-encoder-demo.ssc

# Run all examples
./examples/run-all.sc

# Start HTTP server for the examples browser
bin/http.ssc
```

## Documentation

**Getting started**

| | |
|---|---|
| [User Guide](docs/user-guide.md) | Installation, CLI commands, language basics, HTTP, effects, actors, Apache Spark, WebAssembly, frontend frameworks, cluster, x402 — practical day-to-day reference |
| [Tutorial 1 — Todo API](docs/tutorial.md#tutorial-1-collaborative-todo-api) | Build a todo API step by step — data model → REST → auth → WebSocket → TLS → MCP |
| [Tutorial 2 — Spark ETL](docs/tutorial.md#tutorial-2-etl-pipeline-with-apache-spark) | End-to-end Spark pipeline — `Dataset[T]` → `@SqlFn` UDF → `@TempView` → Delta Lake |
| [Tutorial 3 — Frontend Toolkit demo](docs/tutorial.md#tutorial-3-frontend-toolkit-demo) | Compile + emit + serve a toolkit SPA through React / Vue / Solid / Custom + SSR via `ssc serve` |
| [Tutorial 4 — Full-stack .ssc](docs/tutorial.md#tutorial-4-full-stack-ssc--sqlite-todo-app-with-reactive-ui) | SQLite + REST API + reactive `std/ui` frontend in one `.ssc` file — `databases:`, `sql` blocks, `serve(lower(tree, theme), port)` |

**Language reference**

| | |
|---|---|
| [Language Specification](SPEC.md) | Formal grammar, type system, semantics, all language constructs |
| [Direct Syntax](docs/direct-syntax.md) | Do-notation over any monad — `direct[M] { x = expr }`, `.!` postfix bind, effect-row unions |
| [Algebraic Effects](docs/coroutines.md) | Coroutine primitive underlying effects and generators |
| [Error Handling](docs/error-handling.md) | Checked errors via `throws[A, E]`, `attemptCatch`, `HasStackTrace` |
| [Metaprogramming](docs/metaprogramming.md) | `inline`, `derives`, `compiletime.*` |
| [DSL Authoring](docs/dsl.md) | Parser combinators, multi-pass pipelines, `std/parsing/*` |

**Platform & runtime**

| | |
|---|---|
| [Architecture](docs/architecture.md) | Compiler pipeline, module structure, backend SPI |
| [Target Backends](docs/targets.md) | Interpreter · JS transpiler · JVM — capabilities and tradeoffs |
| [Actors & Distributed](docs/actors-dist.md) | Spawn, supervise, cluster over WebSocket |
| [Dataset / MapReduce](docs/mapreduce.md) | `Dataset[T]` — local, parallel, distributed |
| [Apache Spark backend](docs/spark-streaming.md) | Spark 4 + Scala 3.7.1: `Dataset[T]`, `sql` blocks, `@SqlFn` UDFs, Structured Streaming, Delta Lake, Hive catalog, MLlib — see §13 of the User Guide |
| [Frontend Framework SPI](docs/frontend-framework-spi-plan.md) | One `.ssc` UI source → React / Vue / Solid / custom; shared reactive primitives |
| [Frontend Toolkit](docs/frontend-toolkit-spec.md) | High-level declarative widgets: Forms, Routing, Widgets v2, Table |
| [Cluster Management](docs/cluster-management.md) | Bully election, phi-accrual failure detector, federation, Raft, ZooKeeper client |
| [x402 micropayments](docs/x402.md) | HTTP 402 protocol, Ethereum + Cardano flows, MCP × x402 paid tools |
| [Blockchain SPI](docs/blockchain-spi.md) | Pluggable backends — EVM, Bitcoin, Solana, Cardano |
| [Browser SQL](docs/browser-sql.md) | Cross-backend `sql` fenced blocks (JS / Node / Wasm / JVM) |
| [Secret Resolvers](secret-resolvers.md) | `${env:}` · `${file:}` · `${sops:}` · `SecretResolver` SPI for Vault / AWS SM / GCP / Doppler / 1Password |
| [MCP Support](docs/mcp.md) | MCP server tools + resources, MCP client |
| [Markdown as Syntax](docs/markdown-as-syntax.md) | How Markdown constructs map to AST nodes |

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
| Collections | `List`, `Map`, `Option`, `Set` with full method dispatch |
| Tuples | `val t = (1, "hello"); t._1` |
| String interpolation | `` s"Hello, $name" ``, `` md"..." `` (strips indent) |
| Math | `math.sqrt`, `math.abs`, `math.pow`, `math.Pi`, … |
| Extension methods | `extension (n: Int) def squared: Int = n * n` |
| Typeclasses | `trait Show[A]`, `given`, `summon[Show[Int]]`, context bounds |
| Higher-kinded types (HKT) | `trait Functor[F[_]]`, sealed dispatch, auto-resolution |
| Standard typeclasses | Functor, Applicative, Monad, Foldable, Traversable, Either, Eq, Show, Hash, Order, Semigroup, Monoid, Bifunctor, MonadError, Selective |
| Recursion | factorial, Fibonacci, tree traversal |
| Tail-call optimisation | self-TCO and mutual TCO — no `@tailrec` required |
| Case-class `.copy` | `p.copy(field = newValue, ...)` |
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
| Algebraic effects | `effect E:`, `handle(body) { case E.op(arg, resume) => ... }`, multi-shot |
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
| Built-in `Async` effect | `runAsync { Async.delay(ms); Async.parallel(...) }` |
| Real-thread `runAsyncParallel` | genuine JVM concurrency without touching call sites |
| Built-in `Storage` effect | `runStorage { Storage.put(k, v); Storage.get(k) }` — JSON file-backed or ephemeral |
| Coroutines | `coroutineCreate`, `coroutineResume`, `suspend`, `Step[Y,T]` ADT, `coroutineCancel` |
| Generators | `generator[T] { yield(v) }`, `fromGenerator`, streaming interop |
| Reactive signals | `Signal(0)`, `s.get` / `s.set(v)`, `computed { … }`, `effect { … }` with diamond-dedup flush |
| Free monad | `Free[F,A]`, `liftF`, `foldMap`, `runM` — in `std/free.ssc` |

### Actors

| Feature | Syntax |
|---------|--------|
| Local actors | `spawn`, `self()`, `pid ! msg`, `receive { case ... }`, `link`, `exit`, supervision |
| Distributed actors | actor cluster over WS, bully leader election, Phi-accrual FD, gossip, membership events |

### Web and HTTP

| Feature | Syntax |
|---------|--------|
| HTTP server | `route(method, path)(handler)`, `serve(port)`, `Request`/`Response` |
| HTTP streaming | `streamResponse`, SSE via `sse(req)` |
| HTTP middleware | CORS, gzip, cache headers, `/_health` / `/_ready` |
| WebSocket server | `onWebSocket(path)`, `ws.send/recv/close/ping`, rate limiting, per-route `maxConnections` |
| TLS | `tls("cert.pem", "key.pem")`, `serve(443, tls=...)`, `wss://` |
| HTTP client | `httpGet/httpPost`, `httpClient { }`, `httpGetStream` for SSE/LLM streaming |
| WebSocket client | `wsConnect(url) { ws => }`, `wss://` |
| REST ergonomics | `jsonParse/jsonStringify/jsonRead`, `req.json`, `JsonValue`, `validate { }`, middleware |
| SQL databases | `databases:` front-matter declares named JDBC connections; ` ```sql ``` ` fenced blocks execute DDL/DML; `Db.query/execute` for programmatic access; SQLite, H2, PostgreSQL out of the box |
| Secret resolution | `${env:VAR}`, `${file:/run/secrets/pw}`, `${sops:key.path}` in database URLs/credentials; `SecretResolver` SPI for Vault, AWS SM, GCP SM, Doppler, 1Password and more |

### Auth and security

| Feature | Syntax |
|---------|--------|
| Sessions + CSRF | `req.session`, `withSession(Map(...))`, `csrfToken()` / `csrfValid(req)` |
| JWT bearer tokens | `jwtSign(Map(...))`, `jwtVerify(token)`, RS256 and HS256 |
| Password hashing | `hashPassword`/`verifyPassword` (PBKDF2) |
| TOTP 2FA | `totpGenerateSecret`, `totpVerify(secret, code)` |
| WebAuthn / passkeys | `webAuthnRegisterChallenge`, `webAuthnVerify` |
| OAuth2 | `oauthAuthorizeUrl(...)`, `oauthExchangeCode(...)`, `oauthUserinfo(...)`, Google + GitHub presets |

### MCP (Model Context Protocol)

| Feature | Syntax |
|---------|--------|
| MCP server | `mcpServer { srv => srv.tool(...) }`, `serveMcp(Transport.stdio/Http/Ws)` |
| MCP client | `mcpConnect(url) { client => client.callTool(...) }` |

### Data processing

| Feature | Syntax |
|---------|--------|
| Dataset / MapReduce | `Dataset[T]` with map/filter/flatMap/groupBy/reduceByKey/top/countByValue |
| Execution modes | `runLocal`, `runParallel`, `runDistributed` |
| Apache Spark | Same `Dataset[T]` source → `sql` fenced blocks · `@SqlFn` UDFs · Scala 3 native encoder derivation · Structured Streaming · Delta Lake · Hive metastore · MLlib pipelines |

### DSL authoring and metaprogramming

| Feature | Syntax |
|---------|--------|
| Parser combinators | `std/parsing/*` — Parser ADT, `~/~>/~<`, rep, opt, sep, error recovery, indentation-aware |
| Multi-pass pipelines | `std/dsl/*` — `Pass[A,B]`, `andThen/parallel/recover`, `Visitor`, `cata`, `ana` |
| Metaprogramming | `inline def/val/if/match`, `compiletime.constValue/summonInline/error` |
| Derives | `derives Eq/Show/Hash/Order/Foldable/Traversable/Functor` |
| Checked errors | `throws[A, E] = Either[E, A]`, `throwsRaw[A, E] = A \| E`, `attemptCatch`, `HasStackTrace` |

### Module system and tooling

| Feature | Syntax |
|---------|--------|
| Package system | `package: org.example.ui` in frontmatter, namespaced exports, collision-safe imports |
| Module imports | `[name](./lib.ssc)` markdown links bring definitions into scope |
| URL imports | `[X](https://...)` URL fetch, cached at `~/.cache/ssc/` |
| Dependency imports | `[X](dep:org/lib:1.2)` resolver, `ssc.lock` |
| Plugin system | `.sscpkg` format, `ssc plugin install/list/uninstall/check/pack`, `~/.scalascript/registry.yaml` |
| Separate compilation | `ssc emit-interface`, `ssc compile-jvm/compile-js`, `ssc link`, `ssc build --incremental`, `.scim/.scir/.scjvm/.scjs` |

### Browser and UI

| Feature | Syntax |
|---------|--------|
| Browser SPA target | `ssc emit-spa file.ssc` — same `route()` source runs as a single-page app |
| Web Components | `ssc emit-wc`, `customElements.define` |
| SSR + hydration | `wc(tag, component, args*)` declarative shadow DOM |
| Component library | `std/ui/*` — Button, Input, Select, Modal, Card, Spinner, Alert, DatePicker, Combobox, and more |
| Frontend Framework SPI | One `.ssc` source compiled to **React**, **Vue 3**, **Solid**, or a **custom** runtime via `frontend-{react,vue,solid,custom}` backends |
| Reactive primitives | `Signal[T]`, `ShowSignal` (conditional render), `ToggleSignal`, `ForSignal[T]` (list render) — uniform semantics across all 4 frontend backends |
| Frontend Toolkit (v1.18 B+ / B++ / C) | High-level declarative UI via `Tk` facade — `vstack/hstack`, `card`, `textField`, `form` with validators, `router`, `modal/drawer/tabs`, `table`, `select/radioGroup/textarea/datePicker/numberInput`.  Backend-agnostic: lowers to React / Vue / Solid / Custom or to static HTML via `Ssr.renderToHtml`. |
| WebAssembly target | `ssc emit-wasm file.ssc` — `scalascript` blocks lowered to Wasm; cross-backend `sql` fenced blocks supported |
| i18n | `translations:` frontmatter, `t(key)`, `setLocale(code)` |
| Env access | `getenv(key)` / `getenv(key, default)` |
| Content helpers | `doc(...)` / `render(...)` structured output |
| HTML / CSS interpolators | `html"..."` (auto-escaping) and `css"..."` with `${expr}` |

### Blockchain, wallets, and micropayments

| Feature | Syntax |
|---------|--------|
| x402 micropayments | HTTP 402 → typed payment challenge / settlement via `Payment[T]`, `x402Server { … }`, `x402Client(...)` — Ethereum + Cardano payment families |
| Blockchain SPI | `BlockchainBackend` trait — EVM (mainnet, L2s), Bitcoin, Solana, Cardano via pluggable backends. See [`docs/blockchain-spi.md`](docs/blockchain-spi.md) |
| Wallet Connect (WC v2) | Relay-transport cryptographic primitives — pairing, session, JSON-RPC, X25519 / HKDF / ChaCha20-Poly1305 |
| Solana Wallet Standard | `solana-wallet-std` translator — Wallet Standard ↔ unified Wallet SPI |
| ERC-4337 account abstraction | `EntryPoint v0.7 PackedUserOperation` — bundlerless and bundler-driven flows |
| Cardano CIP-8 wallet | Ed25519 key-derived enterprise bech32 address, CIP-8 message signing, Scalus-source escrow validator |
| Cross-backend crypto | JVM: native `Ed25519`, secp256k1, BLS12-381; JS: `@noble/curves` Scala.js backend — uniform `CryptoBackend` SPI |
| MCP × x402 | `mcpServer { srv => srv.tool(...).requirePayment(...) }` — paid LLM tools |

### Cluster, leader election, federation

| Feature | Syntax |
|---------|--------|
| Bully leader election | `cluster.leader()` returns the current bully-elected leader; reactive on membership change |
| Phi-accrual failure detector | Heartbeat-driven liveness; tunable phi threshold per cluster |
| Self-health | `cluster.self.health` — driver-side health metric stream |
| Federation | Multi-cluster gossip + cross-cluster routing — see [`docs/cluster-federation.md`](docs/cluster-federation.md) |
| Raft consensus | Strongly-consistent state machine — see [`docs/cluster-raft.md`](docs/cluster-raft.md) |
| ZooKeeper client | `zkClient { … }` for legacy coordinator integration — see [`docs/client-zookeeper.md`](docs/client-zookeeper.md) |
| Operational HTTP routes | `/_ssc-cluster/status`, `/_ssc-cluster/members`, `/_ssc-cluster/leader` — built-in across all 4 frontend / Node backends |

## Examples

| File | Description |
|------|-------------|
| [hello.ssc](examples/hello.ssc) | Minimal "Hello, World!" |
| [index.ssc](examples/index.ssc) | Landing page for the `serve` examples browser |
| [script.ssc](examples/script.ssc) | Functions, loops, Fibonacci |
| [data-types.ssc](examples/data-types.ssc) | Case classes, sealed traits, enums, pattern matching |
| [functional.ssc](examples/functional.ssc) | Lambdas, closures, HOF, composition, pipelines |
| [enums.ssc](examples/enums.ssc) | Simple and parameterised enums, recursive ADTs |
| [extensions.ssc](examples/extensions.ssc) | Extension methods, for comprehensions, while, recursion |
| [imports.ssc](examples/imports.ssc) | Math, geometry, statistics |
| [typeclass.ssc](examples/typeclass.ssc) | Show, Eq, Ord, Monoid, Functor via `given`/`summon` |
| [typed-data.ssc](examples/typed-data.ssc) | Data pipelines, Option, enums |
| [content.ssc](examples/content.ssc) | `md` interpolator, auto-output, `doc`/`render` |
| [recursion.ssc](examples/recursion.ssc) | Self-TCO, mutual TCO, Collatz — deep recursion without overflow |
| [effects.ssc](examples/effects.ssc) | Algebraic effects — Console routing, nondeterminism, early return |
| [std-effects-demo.ssc](examples/std-effects-demo.ssc) | Logger, Random, Clock, State, Env standard effects |
| [direct-demo.ssc](examples/direct-demo.ssc) | `direct[M]` do-notation, `.!` postfix bind, effect-row unions |
| [async-demo.ssc](examples/async-demo.ssc) | Built-in `Async` effect — `runAsync`, `async`, `await`, `parallel`, `delay` |
| [coroutine-demo.ssc](examples/coroutine-demo.ssc) | `coroutineCreate`, `coroutineResume`, `suspend`, generators |
| [signals-demo.ssc](examples/signals-demo.ssc) | Reactive signals — `Signal`, `computed`, `effect`, diamond dedup |
| [storage-demo.ssc](examples/storage-demo.ssc) | Built-in `Storage` effect — JSON-backed and ephemeral handlers |
| [actors-demo.ssc](examples/actors-demo.ssc) | Actors — spawn, send, receive, supervision, cluster |
| [ws-recv-demo.ssc](examples/ws-recv-demo.ssc) | Sync-style `ws.recv()` loop alternative to `onMessage` callbacks |
| [mcp-demo.ssc](examples/mcp-demo.ssc) | MCP server with tools and resources; MCP client usage |
| [dataset-stats.ssc](examples/dataset-stats.ssc) | Dataset MapReduce — `runLocal`, `runParallel`, aggregations |
| [dsl-demo.ssc](examples/dsl-demo.ssc) | Parser combinators, error recovery, multi-pass compilation pipeline |
| [lenses.ssc](examples/lenses.ssc) | `.copy(field = v)`, `Focus[T](_.a.b)`, `get` / `set` / `modify` / `andThen` |
| [default-params.ssc](examples/default-params.ssc) | Default parameter values on defs, classes, and enum cases |
| [lang-split.ssc](examples/lang-split.ssc) | `scala` vs `scalascript` block annotations side by side |
| [scala-js-demo.ssc](examples/scala-js-demo.ssc) | Pure `scala` 3 document — runs on all three backends with byte-identical output |
| [rest-api.ssc](examples/rest-api.ssc) | Tiny in-memory REST API — `route()`, `html"..."`, `serve()` |
| [sql-sqlite-file.ssc](examples/sql-sqlite-file.ssc) | SQLite file database — `databases:` front-matter, `sql` DDL/DML blocks, `Db.query/execute` |
| [spa-demo.ssc](examples/spa-demo.ssc) | Same `route()` / `serve()` source, browser SPA via `ssc emit-spa` |
| [auth-demo.ssc](examples/auth-demo.ssc) | Login / logout with signed cookie sessions + CSRF tokens |
| [oauth-demo.ssc](examples/oauth-demo.ssc) | Full OAuth2 sign-in (GitHub or Google) — state, exchange, userinfo |
| [tls-demo.ssc](examples/tls-demo.ssc) | HTTPS + WSS server with `tls(cert, key)` |
| [wc-demo.ssc](examples/wc-demo.ssc) | Web Components via `ssc emit-wc`, SSR + hydration |
| [wasm-fibonacci.ssc](examples/wasm-fibonacci.ssc) | `scalascript` → WebAssembly module via `ssc emit-wasm` |
| [wasm-sorting.ssc](examples/wasm-sorting.ssc) · [wasm-matrix.ssc](examples/wasm-matrix.ssc) · [wasm-primes.ssc](examples/wasm-primes.ssc) · [wasm-collections.ssc](examples/wasm-collections.ssc) | Wasm benchmark suite |
| [examples/frontend/counter/](examples/frontend/) · [show-hide/](examples/frontend/show-hide/) · [todo/](examples/frontend/todo/) · `toolkit-demo` | One source compiled to React / Vue / Solid / Custom — first three via Frontend Framework SPI, **toolkit-demo** via high-level Toolkit (`Tk` facade) |
| [x402-server.ssc](examples/x402-server.ssc) · [x402-client.ssc](examples/x402-client.ssc) | HTTP 402 micropayment server + client (Ethereum settlement) |
| [x402-cardano.ssc](examples/x402-cardano.ssc) | x402 on Cardano — CIP-8 wallet, Scalus escrow validator, end-to-end client + server |
| [spark-sql-demo.ssc](examples/spark-sql-demo.ssc) | Spark SQL via `sql` fenced blocks + `${expr}` bind parameters + section aliases |
| [spark-encoder-demo.ssc](examples/spark-encoder-demo.ssc) | Scala 3 native `Encoder[T]` derivation — `Dataset[CaseClass]` end-to-end on Spark 4 |
| [spark-streaming-rate-console.ssc](examples/spark-streaming-rate-console.ssc) | Structured Streaming — rate source → console sink with auto-`awaitTermination` |
| [spark-delta-demo.ssc](examples/spark-delta-demo.ssc) | Delta Lake — auto-emit `delta-spark` dep + extension/catalog configs |
| [spark-hive-demo.ssc](examples/spark-hive-demo.ssc) | Hive metastore via `spark-hive-metastore:` + `@TempView("...")` + `Dataset.fromTable[T]` |
| [spark-mllib-pipeline.ssc](examples/spark-mllib-pipeline.ssc) | MLlib — Tokenizer + HashingTF + LogisticRegression pipeline end-to-end |

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
[`docs/frontend-toolkit-spec.md`](docs/frontend-toolkit-spec.md).

## Benchmarks

Cross-backend micro-benchmarks (fib, tail-recursive sum, list ops) comparing
ScalaScript's three backends against hand-written Scala 3 and JavaScript:

```bash
scala-cli bench/run.sc
```

See [`bench/README.md`](bench/README.md) for the workload list, methodology,
and a sample results table.

## End-to-end smoke

`e2e/rest-smoke.sc` boots `examples/rest-api.ssc` through each of the three
backends in turn and diffs their HTTP responses — guards against drift between
the interpreter / JVM / JS serve runtimes.

```bash
scala-cli e2e/rest-smoke.sc
```

## Conformance Suite

Cross-backend tests that verify JVM interpreter and JS transpiler produce identical output.
Run with:

```bash
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
| generators | `generator[T] { yield(v) }`, pipeline composition, lazy streams |
| signals | Reactive `Signal` / `computed` / `effect` with diamond-dedup flush |
| lenses | `.copy(field = v)` and `Focus[T](_.a.b)` — get / set / modify / andThen |
| prisms | `Prism[Sum, Variant]` — getOption / set / modify on enum / sealed-trait cases |
| optional | `Focus[T](_.maybe.some.field)` — Optional optic with getOption / set / modify / andThen |
| traversal | `Focus[T](_.items.each.field)` — Traversal with getAll / modify / set / andThen |
| actors | spawn / send / receive / supervision / link / exit |
| actors-dist | Distributed actor cluster, WS transport, gossip, leader election |
| mcp | MCP server tools + resources; MCP client callTool |
| dataset | `Dataset[T]` local sequential, parallel, distributed MapReduce |
| dsl | Parser combinators, error recovery, indentation-aware, multi-pass pipeline |
| metaprogramming | `inline`, `derives`, `compiletime.*` |
| checked-errors | `throws[A, E]`, `attemptCatch`, `HasStackTrace` |
| websocket | `onWebSocket`, `ws.send/recv`, rate limiting, `wss://` |
| tls | `tls(cert, key)`, HTTPS, WSS |

## Backends

ScalaScript supports the following bundled backends, all loaded through the
**Backend SPI** plugin architecture
([`docs/backend-spi.md`](docs/backend-spi.md)):

| Command | Backend id | How it works |
|---------|------------|--------------|
| `bin/ssc file.ssc`         | `int`         | Tree-walking interpreter — instant startup, no compilation |
| `bin/jssc file.ssc`        | `js`          | `scalascript` blocks → custom JS transpiler; `scala` blocks → **Scala.js** via scala-cli |
| `bin/sscc file.ssc`        | `jvm`         | Generates a `.sc` script and compiles via scala-cli |
| `ssc emit-spa file.ssc`    | `scalajs-spa` | Self-contained SPA HTML + JS bundle |
| `bin/ssc-spark file.ssc`   | `spark`       | Apache Spark 4 — generates a Scala 3.7.1 `.sc` script with `//> using dep` directives, runs via `scala-cli`. Auto-detects `sql` blocks, `@SqlFn` UDFs, `readStream`/`writeStream`, `.format("delta")`, `@TempView`, MLlib imports. See [§13 of the User Guide](docs/user-guide.md#13-apache-spark). |
| `ssc emit-wasm file.ssc` / `examples/run-wasm.sh` | `wasm`        | WebAssembly module — `scalascript` blocks lowered to Wasm IR. Cross-backend `sql` fenced blocks supported (v1.27 Phase 5). |
| (sub-backend) | `node`        | Node.js runtime variant of `js` — emits server-side JS with `fs`/`path` shims and a cross-backend SQL runtime (v1.27 Phase 4). |
| (sub-backends) | `frontend-{react,vue,solid,custom}` | Frontend Framework SPI (v1.18 Phase A): same `.ssc` UI source compiled to React (`useState`/`useEffect`), Vue 3 (`ref`/render), Solid (`createSignal`/`createEffect`), or a minimal custom runtime — `ShowSignal`/`ToggleSignal`/`ForSignal` reactive primitives shared across all four. See [`docs/frontend-framework-spi-plan.md`](docs/frontend-framework-spi-plan.md). |

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

The JavaScript backend handles two block types differently:

- **`scalascript` blocks** — transpiled by our custom JS transpiler (`JsGen`), which supports
  ScalaScript-specific features (effects, content helpers, TCO, imports).
- **`scala` blocks** — compiled by Scala.js via `scala-cli --js`, giving full Scala 3 fidelity
  (standard library, type system, no custom runtime limitations).

When a `.ssc` file contains both, the Scala.js-compiled section runs first, followed by
the ScalaScript transpiled section.

## CLI Commands

```bash
ssc run file.ssc              # interpret
ssc watch file.ssc            # watch mode (re-run on change)
ssc repl                      # interactive REPL
ssc compile-jvm file.ssc      # compile to .scjvm artifact
ssc compile-js file.ssc       # compile to .scjs artifact
ssc emit-interface file.ssc   # emit .scim interface
ssc emit-ir file.ssc          # emit .scir normalized IR
ssc link --backend jvm dir/   # link artifacts
ssc build --incremental dir/  # incremental build
ssc emit-js file.ssc          # transpile to JS
ssc emit-spa file.ssc         # SPA HTML bundle
ssc emit-wc file.ssc          # Web Components bundle
ssc test file.ssc             # run tests
ssc preview file.ssc          # preview component variants
ssc deps file.ssc             # show import closure
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

backend/spi/                # SPI traits (Backend, SourceLanguage, Capabilities, …)
ir/                         # IR types + JSON/MsgPack codecs
core/
  src/main/scala/scalascript/
    parser/    # Markdown + YAML + Scala parser
    typer/     # Type checker
    ast/       # AST types
    imports/   # Cross-file import resolver
    interpreter/Value.scala  # Computation Free monad (used by interpreter+codegens)
    transform/ # Normalize, DirectDesugar, EffectAnalysis
    plugin/    # BackendRegistry, SubprocessBackend, WireProtocol
backend/jvm/      # JvmGen — emits Scala 3 source
backend/js/       # JsGen — transpiles to JavaScript
backend/scalajs/  # ScalaJsBackend — emits SPA via Scala.js
backend/interpreter/
  src/main/scala/scalascript/
    interpreter/    # Tree-walking interpreter
    server/         # Built-in HTTP / WebSocket / Actor / MCP runtime
    bench/          # WsStress benchmark
runtime-server/common/      # Shared HTTP/WS server primitives (all backends)
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
scripts/         # launchers/ and validate-frontmatter.scala
```

## Installing as a Binary

```bash
# Install scala-cli first (if needed)
./setup.sh

# Build ssc into bin/
./install.sh
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

## Design Principles

1. **Reuse, don't invent.** Markdown, YAML, Scala 3 — use what works.
2. **One source, many targets.** Source semantics are target-independent.
3. **Human and machine readable.** Pleasant for humans, trivially parseable for machines.
4. **No AI at runtime or compile time.** The language stands on its own.

## License

[Apache License 2.0](LICENSE)

## Author

Sergiy (Victorovych) Shcherbyna <sergey.scherbina@gmail.com>
