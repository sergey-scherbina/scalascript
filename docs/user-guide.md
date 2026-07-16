# ScalaScript User Guide

A practical reference for ScalaScript v1.27+.

---

## 1. Installation

### Standalone Install

```bash
cs install ssc --channel https://releases.scalascript.io/coursier.json
```

Alternative release paths:

```bash
brew install scalascript/tap/ssc
curl -fsSL https://get.scalascript.io | sh
```

See `docs/getting-started-standalone.md` for the fresh-machine path.

### Developer Checkout

- **Java 21+** (for JVM backend and interpreter)
- **[scala-cli](https://scala-cli.virtuslab.org)** (required)
- **[Node.js](https://nodejs.org) 18+** (for JS backend only)

### Install

```bash
git clone https://github.com/sergey-scherbina/scalascript
cd scalascript

# Optional: install scala-cli
./setup.sh

# Build the ssc launcher and helper scripts into bin/
./install.sh --dev
```

After installation:

```bash
ssc examples/hello.ssc     # v2 VM default runner
ssc-tools run --v1 examples/hello.ssc  # explicit v1 compatibility
jssc examples/hello.ssc    # JS backend
sscc examples/hello.ssc    # JVM backend
```

---

## 2. First Script

Create `hello.ssc`:

````ssc
---
name: hello
version: 1.0.0
---

# Hello

```scalascript
def greet(name: String): String = s"Hello, $name!"
println(greet("World"))
```
````

Run it four ways:

```bash
ssc hello.ssc           # v2 VM default runner
ssc run --bytecode hello.ssc # direct ASM, same native frontend/checker
ssc-tools run --v1 hello.ssc # explicit v1 compatibility
ssc-tools run-jvm hello.ssc  # compile via JvmGen + run with scala-cli
ssc-tools run-js  hello.ssc  # compile via JsGen  + run with Node.js
jssc hello.ssc          # alias for run-js via bin/ wrapper
sscc hello.ssc          # alias for run-jvm via bin/ wrapper
```

All five produce `Hello, World!` with byte-identical output.

### Watch Mode

```bash
ssc-tools watch hello.ssc # tools command: re-runs on every file save
```

`ssc watch` supports **hot reload** for both plain scripts and HTTP servers:

- **Scripts**: on each file save the script is re-run from scratch; any
  error is printed and the watcher continues.
- **HTTP servers** (files that call `serve(port)`): the server starts on
  the first run and keeps its port bound.  On subsequent saves, only the
  route table is cleared and rebuilt — no port rebind, no downtime.
  Use `ssc serve file.ssc` as a more intent-revealing alias:

```bash
ssc serve server.ssc    # starts server, hot-reloads routes on every save
```

Each reload prints a timestamp (`[HH:mm:ss] Reloading ...`) and clears
the terminal so you always see fresh output at the top.

To measure reload latency without editing your real file, use `watch-bench`.
It copies the source to a temporary directory, mutates a code block between
cycles, and runs the same parse-cache + incremental type-check + reload path:

```bash
ssc watch-bench --cycles 10 --target-ms 100 examples/rest-api.ssc
ssc watch-bench --cycles 10 --target-ms 100 --require-target examples/rest-api.ssc
```

The output includes warm-up, p50, and max cycle times. `--require-target`
exits non-zero when the max cycle exceeds the target.

For a quick execution benchmark smoke, use `ssc bench --smoke`. It runs the
checked-in `bench/corpus/hello-world.ssc` workload through the interpreter only;
add `--target-ms N --require-target` only on a pinned local or dedicated
performance runner.

### REPL

```bash
ssc repl
> def f(x: Int) = x * 2
> f(21)
42
```

The REPL accepts multi-line input (blank line runs the snippet) and supports
in-session HTTP server control and route testing via `:` commands.

#### REPL HTTP commands (web-aware mode)

Start an HTTP server, register handlers, and test them — all without leaving
the REPL prompt.

**Server lifecycle:**

```
ssc> :serve           # start on default port 8080
Listening on :8080

ssc> :serve 9000      # custom port
Listening on :9000

ssc> :stop            # stop + clear routes
Server stopped. Routes cleared.

ssc> :stop --keep-routes   # stop but keep registered routes
Server stopped. Routes kept (3 routes).
```

**Registering routes:**

```
# Inline lambda
ssc> :mount GET /ping { _ => Response.text("pong") }
Mounted: GET /ping

# Function already defined in the REPL
ssc> def greet(req: Request) = Response.text(s"Hi ${req.params("name")}!")
ssc> :mount GET /hi/:name greet
Mounted: GET /hi/:name  (greet)

# Handler file (last expression is used as handler)
ssc> :mount GET /items/:id handlers/entity.ssc coll=items
Mounted: GET /items/:id  (handlers/entity.ssc, ctx: {coll=items})

# Named function from a multi-function file
ssc> :mount GET /greet/:name handlers.ssc#greet
Mounted: GET /greet/:name  (handlers.ssc#greet)

# Load a .ssc file that contains route() calls
ssc> :load api/users.ssc
Loaded api/users.ssc:
  GET  /users
  POST /users

# Hot-reload after editing the file
ssc> :reload api/users.ssc
```

**Inspecting and removing routes:**

```
ssc> :routes
  GET    /ping           <inline>
  GET    /hi/:name       <inline>
  GET    /items/:id      handlers/entity.ssc  {coll=items}
  GET    /users          api/users.ssc
  POST   /users          api/users.ssc

ssc> :unmount GET /ping
Unmounted: GET /ping

ssc> :clear    # remove all routes (server keeps running)
Routes cleared.
```

**Testing handlers:**

```
# Real HTTP request (requires :serve)
ssc> :http GET /hi/alice
→ 200 OK  text/plain
Hi alice!

ssc> :http POST /users {"name":"bob"} -H "Authorization: Bearer tok"
→ 201 Created  application/json
{"id":1,"name":"bob"}

# In-process dispatch — no server needed
ssc> :call GET /items/99
→ 200 OK
{"collection":"items","id":"99"}

ssc> :call GET /hi/carol?format=upper
→ 200 OK
HI CAROL!
```

**Settings:**

```
ssc> :set errorDetails false   # suppress verbose deserialization errors in 400 responses
errorDetails = false

ssc> :set errorDetails true    # re-enable (default)
errorDetails = true
```

See [`specs/repl-web.md`](repl-web.md) and [`specs/mount-handlers.md`](mount-handlers.md)
for the full command reference, handler-file contract, and typed-handler deserialization rules.

### Standard `run`/`build-jvm` and explicit `ssc-tools` compatibility

`ssc run` is the ScalaScript 2.1 compiler-free default. It executes the staged
`mira-md -> ssc1-front -> ssc1-check -> ssc1-lower` tower through the prebuilt
Scala 3 seed and v2 kernel, rejects partial CoreIR that contains the parser
`_err` sentinel, and never invokes Scala CLI, scalac, or javac. `--native` and
`--v2` are idempotent standard-path assertions; `--bytecode` changes only the
execution backend to direct ASM.

The v1/Scalameta frontend and compiler/codegen commands require an explicit
`ssc-tools` executable. Plain `ssc` rejects `--v1`, `--compat-frontend`, and
tools-owned commands before source execution; it never spawns or discovers the
tools launcher.

When you need true JVM or Node.js execution semantics (or want to benchmark
performance) use `--target jvm` or `run-js`:

```bash
ssc run              hello.ssc   # v2 VM default runner
ssc run --native     hello.ssc   # idempotent standard-path assertion
ssc run --native --bytecode hello.ssc # same frontend → direct ASM
ssc-tools run --compat-frontend hello.ssc # explicit Scalameta frontend
ssc-tools run --v1   hello.ssc   # v1 tree-walking interpreter rollback
ssc run              hello.ssc -- one two  # v2 VM program args
ssc-tools run --target jvm hello.ssc # JvmGen → temp .sc → scala-cli run
ssc-tools run-jvm    hello.ssc   # same — backward-compat alias
ssc-tools run-js     hello.ssc   # JsGen  → temp .js → node
ssc-tools run-js --v2 hello.ssc  # FrontendBridge → CoreIR → v2 JsGen → node
```

The `ssc-tools` generated-backend commands (`run --target jvm`, `run-jvm`, and `run-js`):
- compile the `.ssc` file through the respective backend codegen
- write the output to a temporary file (deleted after the run)
- execute it and forward stdout/stderr transparently
- leave **no artifacts on disk** — use `ssc compile-jvm` / `ssc compile-js`
  if you want reusable `.scjvm` / `.scjs` artifacts

For v2 VM runners, `--` separates source files from program argv. Positionals
before `--` are still source files, so multi-file runs are unchanged:

```bash
ssc run app.ssc -- one two
ssc run --v2 app.ssc -- one two
ssc run --bytecode app.ssc -- one two
ssc run --native app.ssc support.ssc -- one two
```

The staged native route evaluates the canonical pure ScalaScript Markdown
Profile for every root and returns its `MarkdownDocument` alongside CoreIR and
the parsed Frontmatter YAML product. The Scala 3 seed validates that structural
ADT; it does not reparse source text. Headings/scopes, pure-link import
paragraphs, prose and `${...}` source, fenced blocks, bullet/ordered lists,
images, GFM pipe tables, and metadata directives are covered. Unsupported
CommonMark extensions stay inert prose, while an unterminated fence is a
source-located error before plugin installation. CommonMark/Flexmark are not on
the standard path.

Standalone Markdown link imports resolve relative to the importing file;
`std/...` resolves against `bin/lib/native-front/runtime`. The loader
deduplicates normalized module paths, accepts multiple source files before
`--`, and forwards only the values after `--` as program arguments. Prose and
inline-code links are not imports. There is no transparent fallback: explicitly
invoke `ssc-tools run --compat-frontend` for a compatibility-only source.
Both v2 VM and direct-ASM runners treat a top-level missing-dispatch `Stub` or
an unresolved dotted runtime-effect `Op` as a nonzero runtime failure. They are
diagnostics, not printable successful program values.

The native frontend accepts named layout givens with indented member bodies:
`given intShow: Show[Int] with` followed by `def` members. Explicit property and
method calls such as `intShow.prefix` and `intShow.show(7)` use deterministic
static dispatch on both the VM and direct ASM, including sibling-member calls
inside the given body. Exact top-level `summon[TC[T]]` resolves a matching named
given, including nested type arguments. Product `Mirror.Of[T]` and custom
`derives TC` evidence are also synthesized by the standard self-hosted route.
Missing evidence fails explicitly; anonymous givens, sum Mirrors, subtype
search, and type-directed overload selection remain bounded migration gaps.

Nested constructor matches on the native route preserve source arm order. If an
outer tag matches but an inner constructor does not—such as
`Person(name, age, Some(email))` receiving `None`—matching continues with the
next arm over the original once-evaluated value. This behavior is identical on
the VM and direct ASM and applies recursively to deeper constructor patterns.

The standard native route publishes the same portable `math` receiver on both
execution lanes. `math.Pi`, `math.E`, and numeric `abs`, `sqrt`, `pow`, `sin`,
`cos`, `tan`, `log`, `log10`, `exp`, `min`, `max`, `floor`, `ceil`, and `round`
dispatch through the v2 kernel; no Scala compiler or compatibility plugin is
loaded. Integer `abs` stays an integer, while `sqrt`/`pow` are floating-point
and `round` returns an integer.

The native route also has its own core-free ServiceLoader plugin boundary. The
process globals (`args`, `cwd`, `sep`, `platform`), crypto intrinsics (hashing,
Base64, HMAC/PBKDF2/random, AES-GCM/CBC, RSA/X.509, Ed25519, HOTP/TOTP, and
Shamir recovery), and the JVM implementations of
`std.fs`/`std.os`, typed `std.json`, runtime `std.yaml`, the `Storage` effect,
and general `Signal`/`computed`/`effect` reactivity no longer load the v1 `PluginBridge`,
interpreter values, or Scalameta classes. File reads/writes, byte I/O, directory
operations, environment lookup, path operations, temporary paths, total JSON
navigation, strict/tolerant parsing, exact string-decimals, and structured JSON
builders work on both the v2 VM and direct-ASM native routes. The native HTTP
client adds JDK-backed GET/POST/PUT/PATCH/DELETE, streaming line callbacks,
base-URL blocks, timeout/retry controls, `Response` builders, and cache helpers;
non-string JSON responses use the same native JSON codec. A JDK server host now
supports exact-method/path `route`, `serve`, `serveAsync`, `stop`, v2 `Request`
construction, and handler-closure invocation on both VM and direct ASM. Advanced
middleware, TLS, uploads, SSE, streaming responses, and WebSockets remain
bounded `native HTTP server unavailable` errors until their host sub-slices
land; there is no compatibility fallback. Named JDBC connections from explicit
root `databases:` front-matter plus `Db.query`/`Db.execute` now use the standalone
SQL runtime through the same core-free provider boundary on VM and direct ASM.
Typed writes, LISTEN/NOTIFY, and native lowering of fenced `sql`/`transaction`
blocks remain separate migration slices. `runEphemeralStorage` creates a fresh
insertion-ordered map per scope; `runStorage` loads and flushes its JSON string
map after each mutation. Its path is an explicit second argument, then
`SSC_STORAGE_PATH`, then `./ssc-storage.json`. Both runners, all five
`Storage.get/put/remove/has/keys` operations, and packaged `build-jvm` artifacts
use the same core-free provider on VM and direct ASM. General reactive signals
use a separate portable provider: dependencies are recollected on every
effect/computed rerun, subscribers flush synchronously in insertion order,
diamonds run a consumer once, and a running effect may write a signal it reads
without recursively scheduling itself. The same semantics run in packaged
`build-jvm` artifacts. Other plugin families remain explicit optional surfaces;
a missing native provider fails explicitly. Use
`ssc-tools run --compat-frontend` for a tools-tier plugin.

Runtime YAML uses portable `YStr`, `YNum`, `YBool`, `YNull`, `YArr`, and `YObj`
values. `parseYaml` accepts the documented project subset (nested block/flow
maps and lists, comments, quoted scalars, and literal/folded blocks), `toYaml`
sorts object keys and emits stable round-trippable text, and the `yaml*`
accessors are total. A `yaml` or `yml` fence is attached to its nearest heading
and is available as `<SectionId>.yaml`; for example `# Service config` binds
`ServiceConfig.yaml`. The same rule works in imported modules and on native VM,
direct ASM, and packaged `build-jvm` JARs. Runtime YAML intentionally uses the
project-owned dependency-free `SimpleYaml`; front-matter remains the separate
self-hosted structural parser and is not reparsed by the Scala seed.

For lossless tooling rather than `.ssc` runtime values, the separate
`scalascript-uniml-yaml` library exposes `Yaml.parse(SourceInput, YamlLimits)`
and `Yaml.project(ParseResult, YamlProjectionOptions)` on both JVM and
Scala.js. Its CST preserves comments, whitespace, scalar spelling/styles,
document markers, directives, tags, anchors, aliases, order, and duplicates.
Projection selects Failsafe, JSON, or YAML 1.2 Core Schema explicitly; tags are
never executed, aliases remain nodes by default, and opt-in resolution has
cycle, expansion, and node limits.

### `build-jvm` — executable JAR directly from native CoreIR + ASM

Plain `bin/ssc` now exposes the physically slim ScalaScript 2.1 tier;
`bin/ssc-standard` is an equivalent descriptive alias:

```bash
bin/ssc run app.ssc
bin/ssc run --bytecode app.ssc
bin/ssc build-jvm app.ssc -o app.jar
bin/ssc info --execution-plan --bytecode
```

Its classpath is only `bin/lib/standard/ssc.jar` plus the explicit JAR allowlist
under `bin/lib/standard/jars/`; its self-hosted tower and std sources live under
`bin/lib/standard/native-front/`. The entry JAR itself is class-filtered, so it
does not retain dormant compatibility command references. A standard request
for a compiler-backed or compatibility command fails before source execution
with a diagnostic naming `ssc-tools`.

The closed standard JAR layout excludes the optional SQL wire codec and its
ujson/upickle/upack/geny implementation family. Basic native SQL remains
available through the named JDBC provider without those JARs. External ASM is
owned only by the direct JVM-bytecode backend: ordinary native VM execution
does not load `org.objectweb.asm`, while `--bytecode` selects and loads it
explicitly. The canonical JSON, Frontmatter YAML, and Markdown modules are pure
ScalaScript scanners with no `extern def` or host-regex route.

The full installation also stages `bin/ssc-tools`, whose classpath contains the
compatibility runtime, plugins, and lazy compiler directory. Compatibility is
available through explicit `ssc-tools run --v1 ...` or `ssc-tools run
--compat-frontend ...`; neither standard launcher delegates or spawns it.
Self-install repeats the same default-standard plus explicit-tools split.

CI proves that this is a physical boundary, not only a launcher convention:

```bash
tests/e2e/v21-slim-distribution-gate.sh \
  --report target/v21-slim-distribution.tsv
```

The gate copies the installation, deletes its compatibility JARs, compiler,
legacy frontend, full CLI, and `ssc-tools`, then runs VM/direct-ASM, imports and
argv, FS/OS, JSON, YAML, HTTP, SQL, UI, State, and `build-jvm` through plain
`ssc`. It also hides `scala-cli`, `scalac`, and `javac`, rejects
compiler/Scalameta/v1 references recursively, and verifies that a requested
compatibility route fails with the tools-tier remedy instead of falling back.

For a complete 2.1 self-hosted-core release check, run:

```bash
scripts/v21-self-hosted-core-release-gate
```

The consolidated release includes the authoritative negative environment:
`tests/e2e/v21-negative-toolchain-release-gate.sh`. It copies only the standard
launchers/tree, removes compiler and Scalameta layouts, hides
scala-cli/scalac/javac, removes both compiler modules, then re-runs the complete
frontend/checker and VM/direct-ASM corpus plus provider and HTTP server smokes.
The derived Java module set explicitly retains `jdk.crypto.ec` because JCA
provider discovery is reflective and therefore invisible to `jdeps`.

It rebuilds the staged distribution, proves the single- and multi-file ssc0
compiler images are gen1/gen2/gen3 fixpoints, verifies the 110-file native
frontend image against checked-in sources, runs the bounded JSON/YAML/Markdown
mutation corpus, strict dependency/JAR/class-load checks, the tools-deleted slim
distribution, reproducible `build-jvm`, and the full 196-document
frontend/VM/ASM taxonomy. `--quick` skips only that final corpus sweep.

The stronger module gate proves the same tier works when the Java compiler
modules themselves are unavailable:

```bash
tests/e2e/v21-jre-module-gate.sh \
  --report target/v21-jre-module.tsv
```

It derives the runtime modules by scanning every standard JAR as a root, rejects
`java.compiler` and `jdk.compiler`, verifies that neither can be resolved, and
runs the VM, direct ASM, representative providers, and a generated H2 SQL JAR
through `java --limit-modules`. The standard H2 JAR excludes only its optional
`SourceCompiler*` classes; the complete driver remains available to
`ssc-tools`.

`ssc build-jvm` runs the self-hosted frontend and native checker, emits
`ssc.gen.Entry` directly through ASM, and writes a deterministic self-contained
JAR. It does not generate Scala/Java source and does not invoke Scala CLI,
scalac, or javac:

```bash
ssc build-jvm app.ssc -o app.jar
java -jar app.jar

# Multiple explicit roots are linked in command-line order. `--` is optional
# on java -jar, but provides the same visible argv as `ssc run ... --`.
ssc build-jvm app.ssc support.ssc -o app.jar
java -jar app.jar -- one two
```

The JAR embeds the v2 core, Scala runtime, and the currently selected core-free
native providers (host/process globals, crypto, FS/OS, JSON, HTTP, UI, State,
Storage, general reactive signals, and SQL when a database is configured).
Imported modules and multiple explicit
roots are lowered into the same checked program; resolved source identities are
recorded in the artifact. Entries are lexically ordered with fixed ZIP metadata;
two builds from identical sources and staged runtime inputs are byte-identical.
The manifest names `ssc.gen.Entry`, while
`META-INF/scalascript/artifact.properties` records source SHA-256 digests,
runtime inputs, provider classes, and parsed database configuration without
timestamps or absolute paths. Database configuration is reconstructed before
native provider installation, so a configured H2 program runs through
`java -jar` without consulting the ScalaScript installation or loading a Java
compiler.

Generated methods carry ordinary JVM line tables and a JSR-45 `SSC` source map.
Stack traces for primary-source code therefore name the original `.ssc` file
and line, while debuggers can resolve secondary explicit roots and imported
modules through the SMAP file table. Artifact metadata hashes the same linked
source closure, including staged `std/...` imports. Debug names are reproducible
display paths; absolute checkout paths are never embedded.

This command is distinct from legacy `compile-jvm --bytecode`, which produces a
`.scjvm` compatibility artifact through generated Scala and compiler tooling.

Release/CI verification uses
`tests/e2e/v21-build-jvm-release-gate.sh --report <file.tsv>`. It rebuilds from
two unrelated clean source directories, compares bytes, executes representative
native providers with compiler commands hidden, and rejects compiler,
Scalameta, compatibility-bridge, and v1 frontend/runtime references with
`javap`/`jdeps` inspection.

`ssc run-js --v2 <file.ssc> [args...]` is an opt-in v2 JS lane. It keeps the
legacy `run-js` path unchanged, but routes the source through FrontendBridge,
emits v2 CoreIR JavaScript, and passes trailing args through Node's
`process.argv` so ScalaScript `args` works in the generated JS process.

`ssc run --target jvm` also accepts `--server-backend jdk|jetty|netty` to
select the HTTP server implementation when the script defines HTTP routes.
`ssc run-jvm` accepts `--frontend <custom|react|solid|vue|swing>` and
`--transport http|in-process`; `http` is the current behavior, while
`in-process` is accepted for the JVM-hosted Swing frontend and rejected with a
clear diagnostic for frontend/backend pairs that cannot share one JVM process.

When to use each:

| Command | Runtime | When to use |
|---------|---------|-------------|
| `ssc run` | v2 VM via self-hosted frontend | Default compiler-free day-to-day runner |
| `ssc run --native` | v2 VM via self-hosted frontend | Idempotently assert the standard route in CI/deployment |
| `ssc run --native --bytecode` | direct ASM via self-hosted frontend | Validate native frontend plus JVM bytecode execution |
| `ssc build-jvm <files...> -o app.jar` | direct ASM executable JAR | Ship a reproducible compiler-free JVM artifact |
| `ssc-tools run --compat-frontend` | v2 VM via Scalameta bridge | Explicit compatibility route |
| `ssc-tools run --v1` | v1 interpreter | Explicit rollback/debug path for old tree-walking behavior |
| `ssc run <file> -- [args...]` | v2 VM | Pass program args to v2 `args` without changing source-file positionals |
| `ssc-tools run --target jvm` | JVM via scala-cli | Explicit compiler-backed JVM route |
| `ssc-tools run-jvm` | JVM via scala-cli | Backward-compat alias for the tools JVM route |
| `ssc-tools run-js` | Node.js | Browser-API testing, npm interop, JS-target verification |
| `ssc-tools run-js --v2` | Node.js via v2 CoreIR JS | Explicit v2 JS lane verification |
| `ssc-tools run-rust` | Native binary via Cargo | One-shot native build & execute (see [`rust-backend.md`](rust-backend.md)) |
| `ssc-tools build-rust` | Native binary via Cargo | Ship a `cargo build`ed binary to `-o <path>` |
| `ssc-tools emit-swift` | v2 AppCore Swift package | Inspect/integrate deterministic checked-CoreIR Swift sources |
| `ssc-tools run-swift` | v2 AppCore via SwiftPM | Build and run the generated domain executable or NativeUi ABI debug CLI on macOS |
| `ssc-tools build/run --target macos` | v2 AppCore or Xcode application | Domain programs use SwiftPM; NativeUi programs build/verify the generated `.app` and `run` launches it |
| `ssc-tools build/run --target ios` | v2 Xcode application | NativeUi programs build the application scheme; `run` installs and launches it on an available Simulator |

**Requirements:** `ssc run` requires a staged installation produced by
`installBin`, but does not require Scala CLI or a Java/Scala compiler at user
runtime. `ssc-tools run --target jvm` / `ssc-tools run-jvm` require `scala-cli`
on PATH; `ssc-tools run-js` requires `node`; `ssc-tools run-rust` /
`ssc-tools build-rust` require `cargo` on PATH (install via `brew install rust`
or <https://www.rust-lang.org/tools/install>). `ssc-tools run-swift` and
`ssc-tools run --target macos` require Swift 6+ on PATH; package generation itself
does not invoke Scala CLI, v1 `JvmGen`, or the legacy SwiftUI emitter.

```bash
# Example: same file, four runtimes
ssc run                    examples/recursion.ssc    # interpreter
ssc-tools run --target jvm  examples/recursion.ssc    # JVM bytecode
ssc-tools run-js            examples/recursion.ssc    # Node.js
ssc-tools run-rust          examples/hello.ssc        # native binary via Cargo
ssc-tools run-swift         examples/swift/appcore-money.ssc  # native SwiftPM AppCore
ssc-tools run-swift         examples/swift/appcore-nativeui.ssc # portable NativeUi ABI debug CLI

# HTTP server on JVM (real threads, JDBC available)
ssc run --target jvm myapp.ssc

# Verify JS output matches interpreter output
ssc run     examples/hello.ssc > out-int.txt
ssc run-js  examples/hello.ssc > out-js.txt
diff out-int.txt out-js.txt   # should be empty
```

#### Compiling to a native binary via Rust

`ssc build-rust myapp.ssc` emits a self-contained Cargo crate, runs
`cargo build --release` inside it, and copies the produced binary to
`-o <path>` (default `./<stem>`).  No JVM, no Node, no runtime
dependency — the binary is fully native.

```bash
# Write hello.ssc:
cat > hello.ssc <<'EOF'
```scalascript
@main def run(): Unit = println("Hello from Rust")
```
EOF

ssc build-rust hello.ssc      # → ./hello
./hello                       # Hello from Rust

ssc run-rust hello.ssc        # build + execute in one step
```

The Rust backend covers `var`/`while`, `enum` + pattern matching,
closures, single-generator `for … yield`, filesystem/env I/O,
`sha256`/`base64`/JSON, and an HTTP server. Anything outside the
surface fails loudly (never a silent miscompile), and a `rust` fence
block is always available as an escape into hand-written Rust. See
[`rust-backend.md`](rust-backend.md) for the full matrix and roadmap.

#### Compiling checked CoreIR to Swift AppCore

The ScalaScript 2 Swift backend emits a deterministic Swift Package with a
target-owned `AppCore` library/runtime and a thin executable. It consumes only
the checked v2 `Program`; generated sources do not reference the v1 parser,
interpreter, `JvmGen`, `View`, or `SwiftUIEmitter`. Checked programs using the
provenance-qualified `std/ui` ABI additionally receive
`Sources/AppCore/NativeUiHost.swift` and a dedicated `<AppName>Cli` product.
That host mirrors ABI-v1 signals, views, events, fetch/form/table descriptors,
storage/offline values, trusted HTML, and exactly-one-root extraction without
importing SwiftUI into AppCore.

```bash
ssc-tools emit-swift --target macos -o ./myapp-swift myapp.ssc
swift run --package-path ./myapp-swift

ssc-tools run-swift myapp.ssc -- arg1 arg2
ssc-tools build --target macos myapp.ssc       # v2 is the default
ssc-tools run --target macos myapp.ssc
ssc-tools run --v1 --target macos legacy.ssc # explicit compatibility only
```

For checked sources, top-level `main: run` is authoritative: module values are
initialized first, then the selected zero-argument entry is called exactly
once. A source that defines both `main()` and `run()` does not implicitly call
`main()` when `main: run` is present. Missing or invalid entries fail before
Swift generation.

The native host executes the shipped `std/ui` path rather than a reduced
constructor-only dialect: `localeSignal`/localized derived text, self-hosted
`JsonValue`, keyed JSON lists, toolkit builders, theme conversion, and
`serve(lower(view(), defaultTheme), ...)` all cross the same checked CoreIR and
run on macOS and iOS. Proper-list builder operations and checked String maps
are normalized before the Apple renderer, while malformed values retain their
source location.

`examples/swift/appcore-nativeui.ssc` is the executable AppCore smoke. Its
debug CLI evaluates the checked source into `NativeUiAbi(1, root, config)` and
prints the version/root/operation summary. A retained `NativeUiSession` keeps
signal, computed, keyed-render, and user closures callable after root handoff;
failed evaluation aborts provisional state before the same host can be reused.
UI-mode generation also writes `AppleApp/NativeUiStore.swift`,
`NativeUiRenderer.swift`, `NativeUiStyles.swift`, `NativeUiHtml.swift`, and the
SwiftUI `App` entry. The main-actor store owns one stable observable cell per
signal, dependency-safe derived reads, exact subscription tokens, and atomic
Host/Store keyed transactions. The recursive renderer covers reactive text,
show/fragment/elements, text and checkbox bindings, styles/accessibility, and
keyed component identity with move/delete/fresh-reinsert semantics. Malformed
or deferred semantics are source-located Unsupported output. Fetch signals and
actions now use URLSession with first/last-subscriber ownership, structural
request metadata, generation-checked replacement/cancellation, atomic
idle/loading/done/error state, click-time body/header snapshots, 2xx-only
capture/clear/ordered success effects, form-body snapshots, and preflighted
http/https/mailto navigation. Unsafe, hostless, stale, malformed, or late
descriptors cannot start or mutate current work. The UserDefaults/NWPathMonitor
slice is now native:
`persistedSignal` reads and transactionally commits String values through the
configured defaults store without depending on a rendered cell, while failed
root/keyed work and disposed wrappers cannot write. `onlineSignal` is one
process-wide root signal backed by a single first/last-owner `NWPathMonitor`;
direct and computed readers share it, callbacks hop to the main actor, and stale
callbacks are generation-rejected. Native `dataTable` values now share one
strict macOS/iOS Grid renderer and a transactional table model for static,
signal, and fetch sources. Explicit dotted row keys stay typed and unique;
loading/error states retain the last coherent rows; text/date/money/status/
link/stacked columns use deterministic formatting; and Field/WholeRow/Fields
post, delete, local link, and inline-edit actions authenticate the current
descriptor/action/row capability before launch and completion. Relative row
URLs use the normalized `--server-url`, while malformed paths, unsafe URLs,
stale rows, obsolete replacements, and non-2xx responses cannot mutate current
state. Trusted `rawHtml` now renders in a per-view nonpersistent WKWebView with
page JavaScript disabled and compiled rules that block network subresources
while retaining inline CSS and `data:` assets. Renderer-owned document loads,
height callbacks, failures, and link taps are generation-authenticated;
external http/https/mailto taps leave the web view through the shared native
URL policy.

NativeUi generation also writes a deterministic Xcode-14-compatible
multi-platform application target and shared app-only scheme. Checked top-level
metadata supplies the required reverse-DNS `bundle-id`, display name, marketing
version, and build version. `ssc build/run --target macos|ios` invokes that
project and scheme, discovers the destination product through
`-showBuildSettings`, and rejects anything other than an `APPL` bundle with the
expected id and non-CLI executable. Generated-file ownership is limited to the
paths in `.ssc-swift-generated.json`, so product/mode changes remove stale
entry points while preserving unlisted resources. macOS run launches the
verified `.app`; iOS run builds, installs, and launches it on an available
Simulator. Signed routes consume that same checked Xcode artifact and never
fall back to v1 or select the debug CLI product:

```bash
# Physical iOS device and signed IPA (SSC_TEAM_ID may replace --team-id)
ssc run --target ios --device --device-id <udid> --team-id <team> myapp.ssc
ssc package --target ios --team-id <team> \
  --export-method app-store-connect --out dist myapp.ssc

# Developer-ID app, bounded keychain-profile notarization, and optional DMG
ssc package --target macos --distribution --team-id <team> \
  --notary-profile <keychain-profile> --notary-timeout-seconds 900 myapp.ssc
# Add --no-notarize and/or --no-dmg when those steps are intentionally omitted.

# Upload only the already-built, verified IPA/PKG; generated lanes never call gym
ssc publish --target ios --testflight --team-id <team> \
  --api-key-path app-store-key.json myapp.ssc
ssc publish --target ios --appstore --team-id <team> \
  --api-key-path app-store-key.json myapp.ssc
ssc publish --target macos --appstore --team-id <team> \
  --api-key-path app-store-key.json myapp.ssc
```

Team authority is flag then `SSC_TEAM_ID`; the App Store Connect key path is
flag then `APP_STORE_CONNECT_API_KEY_PATH`; the notary profile is flag then
`SSC_NOTARY_KEYCHAIN_PROFILE`. API-key JSON requires non-empty `key_id` and
`key` (`issuer_id` is optional for individual keys). `--fastlane` uses an
existing source-adjacent `Fastfile` with lanes named `testflight`, `appstore`,
or `mac_appstore`; ScalaScript still builds and verifies the artifact first and
passes its exact path, project, scheme, and bundle id through environment.

**Reactive web server.** A declarative `std/ui` view compiled with
`serve(view, port)` emits a native `tokio` + `hyper` server with
server-side rendering, a reactive **signal store**, **computed-signal
live recompute**, **Server-Sent Events** push (`/__ssc/events`), and a
**direct WebSocket** signal endpoint on `port + 1` — no JS framework,
no Node runtime:

```scalascript
@main def run(): Unit =
  val locale   = signal("locale", "fr")
  val greeting = computedSignal(() => locale())   // recomputes server-side on change
  serve(element("div", Map(), Map(), List(signalText(greeting))), 8080)
```

```bash
ssc build-rust app.ssc && ./app &
curl -s localhost:8080/__ssc/state                # {"__c0":"fr","locale":"fr"}
curl -s 'localhost:8080/__ssc/push?name=locale&value=de'
curl -s localhost:8080/__ssc/state                # {"__c0":"de","locale":"de"}
```

See [`rust-backend.md`](rust-backend.md#web-toolkit-on-rust--reactive-serve).

#### `ssc build --target jvm` output

`ssc build myapp.ssc --target jvm` compiles to a bootstrap JAR and prints:

```
Building myapp.jar (jvm, bootstrap)...
→ target/build/jvm/myapp.jar
```

`ssc package myapp.ssc --target jvm` (fat assembly with all deps) prints:

```
Building myapp.jar (jvm, fat assembly)...
→ target/build/jvm/myapp.jar
```

To override config at runtime without recompiling:

```bash
java -Dscalascript.frontend=vue -jar target/build/jvm/myapp.jar
java -Dscalascript.server.port=9090 -jar target/build/jvm/myapp.jar
```

---

## 3. Language Basics

### Content composition and output

The built-in `md"..."` interpolator uses ordinary `$name` and `${expr}`
interpolation, then drops blank edge lines and the common leading-space indent:

```scalascript
val name = "Ada"
val summary = md"""
  Name: $name
  Score: ${20 + 22}
"""
```

On the ScalaScript 2.1 native path this is parsed and lowered by the self-hosted
ScalaScript frontend directly to the portable indent primitive. It does not
resolve a global named `md`, load Scalameta, or call a host Markdown parser.
Ordinary calls such as a user-defined `md(value)` remain lexical.

`doc(parts...)` keeps ordinary runtime values in source order; nested documents
are flattened recursively when rendered, while empty nested documents add no
text or separator. `render(value)` writes the resulting leaves separated by one
newline, or one ordinary value using the same display rules as `println`:

```scalascript
val report = doc(
  "=== Results ===",
  List("one", "two").mkString("\n"),
  42
)

render(report)
```

The reserved native document representation is opaque: `render` never exposes
`NativeDoc(...)` in output, including when one document contains another.

The helpers compose values; they do not parse Markdown. On the ScalaScript 2.1
compiler-free JVM path they are owned by the core-free native host provider and
run identically on the VM, direct ASM, and `build-jvm` artifacts without the v1
`DocV`/`PluginBridge` graph, Scalameta, or an external renderer/parser. A local
definition named `doc` or `render` retains normal lexical precedence over the
provider fallback. `md` remains a language feature rather than provider API.

### Collections with real Scala semantics

The interpreter models true Scala collection semantics, not a single uniform
list type:

| Type | Semantics |
|------|-----------|
| `List`, `Seq`, `Iterable` | Immutable singly-linked list |
| `Vector`, `IndexedSeq` | Distinct indexed type with O(log₃₂ n) access / `updated` |
| `Array` | **Mutable**, with reference identity — `a(i) = x` mutates in place |
| `LazyList` | **Lazy** — backed by Scala `LazyList`, so `#::` defers evaluation and infinite streams work |
| `Map`, `Set` | Immutable hash collections |

Constructors and conversions are available across backends:

```scalascript
val v = Vector(1, 2, 3).updated(1, 20)   // Vector(1, 20, 3)
val a = Array(1, 2, 3); a(0) = 99        // mutates in place
val nats = LazyList.from(0)              // infinite, lazy
val firstFive = nats.take(5).toList     // List(0, 1, 2, 3, 4)
val asVec = List(1, 2, 3).toVector       // .toSeq / .toArray / .toList / .toLazyList
```

`Vector == List` compares structurally by element, so the two interoperate in
equality checks.

### Numeric and bitwise operators

Integer arithmetic supports the full set of bitwise operators. Integers are
64-bit, so the unsigned shift `>>>` fills from bit 63:

```scalascript
val flags = 0x0F & 0x33   // and        → 3
val set   = 0x01 | 0x10   // or         → 17
val xor   = 0xFF ^ 0x0F   // xor        → 240
val shl   = 1 << 4        // shift left → 16
val shr   = -16 >> 2      // arithmetic shift right (sign-extends) → -4
val ushr  = -1 >>> 60     // logical shift right (64-bit, zero-fills) → 15
val inv   = ~0            // bitwise not → -1
```

### Markdown Frontend From Content

ScalaScript already treats Markdown headings, links, YAML front-matter, and
fenced blocks as language syntax. The first implemented user-facing slice is
frontend from Markdown: a Markdown-hosted document lowers to `std/ui` so pages
and screens can be authored without hand-written markup generation. The same
`DocumentContent` snapshot is exposed through `std/content` and later supports
the broader metadata API for prose-defined metadata, fenced YAML/JSON/TOML data,
and other embedded language blocks.

````markdown
# Pricing {#pricing route=/pricing layout=marketing}

Simple plans for small teams.

<!-- @meta component=PlanList source=plans data=plans-data -->
## Plans

- Starter: $19
- Pro: $49

```yaml @id=plans-data
plans:
  - id: starter
    price: 19
  - id: pro
    price: 49
```
````

Frontend MVP API:

````markdown
```yaml @id=team-controls @ui=toolkit
signals:
  teamName: "ScalaScript team"
  enabled: false
  applied: false
controls:
  type: card
  children:
    - type: heading
      level: 2
      text: Toolkit controls
    - type: textField
      signal: teamName
      label: Team name
    - type: checkbox
      signal: enabled
      label: Enable toolkit renderer
    - type: button
      signal: applied
      value: true
      label: Apply toolkit
      enabledWhen: enabled
```
````

```scalascript
[contentToolkitNode](std/ui/content.ssc)
[lower](std/ui/lower.ssc)
[defaultTheme](std/ui/theme.ssc)
[serve](std/ui/primitives.ssc)

val page = lower(contentToolkitNode(), defaultTheme)
serve(page, 8099)
```

`contentToolkitNode()` is the toolkit bridge. It reads the current parsed
Markdown document and returns a `TkNode`, so it can be placed inside `vstack`,
`card`, routers, or any other `std/ui` composition before calling `lower`.
Structured fences marked `@ui=toolkit` are rendered as toolkit controls instead
of being omitted as generic data. The fence accepts `signals:` (scalar defaults)
and `controls:` / `control:`. Supported control `type` values are `vstack`,
`hstack`, `fragment`, `divider`, `heading`, `text`, `rawText`, `signalText`,
`show`, `textField`, `checkbox`, `button`, `badge`, and `card`.
Use `serve(page, port)` for direct browser or phone preview; use
`emit(page, outDir)` when you need static `index.html` + `app.js` artifacts.
The same Markdown-authored controls also lower through native clients. Swing
and JavaFX run the generated JVM frontend with native `JTextField` / `TextField`,
checkbox, and button widgets; SwiftUI emits `TextField`, `Toggle`, and `Button`
declarations from the same `View` tree:

```bash
ssc run-jvm --frontend swing  examples/frontend/markdown-native-controls/markdown-native-controls.ssc
ssc run-jvm --frontend javafx examples/frontend/markdown-native-controls/markdown-native-controls.ssc
ssc emit --frontend swiftui   examples/frontend/markdown-native-controls/markdown-native-controls.ssc
```

Native frontends do not reparse Markdown. They consume the shared toolkit/View
pipeline and normalize the HTML-like `std/ui/lower` output into native controls.

For small controls, YAML is optional. Ordinary Markdown links whose destination
starts with `toolkit:` lower to toolkit nodes when the selected region is passed
through `contentToolkitNode()`, `contentToolkitBlock(id)`, or
`contentToolkitSection(id)`:

```markdown
## Controls {#controls}

<!-- @meta id=markdown-controls -->
- [Team name](toolkit:textField?signal=teamName&value=ScalaScript%20team)
- [Enable live preview](toolkit:checkbox?signal=enabled&value=false)
- [Status](toolkit:badge?text=Status&variant=default)
- [Current status](toolkit:signalText?signal=applyStatus&value=Not%20applied%20yet)
- [Apply Markdown controls](toolkit:button?signal=applyStatus&value=Applied%20from%20Markdown&enabledWhen=enabled)
- [Markdown controls](toolkit:badge?variant=success)
```

The link label becomes the control label/content unless the query supplies
`label=` or `text=`. Links with the same `signal=` share one reactive signal in
that selected document, section, or block. `toolkit:` links are content links,
not imports, so pure link paragraphs and list items remain in `DocumentContent`.
Use `examples/markdown-toolkit-links.ssc` for a live browser example.

When one `.ssc` document contains multiple independent Markdown-authored UI
regions, keep each region in Markdown and select it by stable id. If a region
declares `component=<name>` metadata, register the matching renderer explicitly
and pass it through `contentToolkitOptionsWithComponents`:

```scalascript
[contentData](std/content.ssc)
[contentComponent, contentToolkitBlock, contentToolkitSection, contentToolkitOptionsWithBindings, contentToolkitOptionsWithComponents](std/ui/content.ssc)
[vstack](std/ui/layout.ssc)
[heading](std/ui/typography.ssc)
[rawText](std/ui/reactive.ssc)
[lower](std/ui/lower.ssc)
[defaultTheme](std/ui/theme.ssc)
[serve](std/ui/primitives.ssc)

val planList = contentComponent("PlanList") { ctx =>
  vstack(gap = 4)(
    heading(2, "Plans from component"),
    rawText("component=" + ctx.name),
    rawText("kind=" + ctx.kind + " id=" + ctx.id),
    rawText("data=" + ctx.data.isDefined.toString)
  )
}

val markdownOptions = contentToolkitOptionsWithComponents([planList])
println(contentData("plans-data").isDefined.toString)

val page = lower(
  vstack(gap = 16)(
    contentToolkitSection("plans", markdownOptions),
    contentToolkitBlock("team-controls"),
    contentToolkitBlock("review-status")
  ),
  defaultTheme
)
serve(page, 8099)
```

`contentToolkitBlock(id)` renders exactly one Markdown block with explicit
metadata such as `@id=team-controls @ui=toolkit` on a fenced YAML block.
`contentToolkitSection(id)` renders a heading section by generated or explicit
section id such as `## Plans` -> `plans` or `## Plans {#plans}`. Missing ids and
duplicate block ids are interpreter errors. `contentComponent(name)(render)`
creates a typed renderer for Markdown metadata such as
`<!-- @meta component=PlanList -->`; the renderer receives
`ContentComponentContext` with `name`, `kind`, `id`, `attrs`, and the selected
`section` or `block`. If metadata names a component that is not present in the
registry, toolkit lowering falls back to the default Markdown renderer.
Metadata `data=<id>` resolves a fenced YAML/JSON/TOML block such as
`yaml @id=plans-data` into `ctx.data`; the same value is available from
code through `contentData("plans-data")`. Missing data references produce
`None`, so components can render empty or fallback states while the document is
being edited.

Markdown inline placeholders are data-bound explicitly, not executed as code.
Use `contentBind(value, contentData("id").get)` when you want a selected
document, section, or block to replace `${name}` / `${nested.name}` with values
from YAML/JSON/TOML data before plain-text or Markdown rendering. For toolkit
selectors, pass the same data through `contentToolkitOptionsWithBindings(data)`;
the selected content is bound before default `TableNode` lowering or before a
registered component receives `ctx.block` / `ctx.section`.

The lower-level `View` renderer remains available when exact HTML-like Markdown
shape is more important than toolkit composition:

```scalascript
[contentDocument](std/content.ssc)
[contentView](std/ui/content.ssc)
val page = contentView(contentDocument())
```

`contentDocument()` returns the current parsed module snapshot. `contentView`,
`contentViewSection`, and `contentViewBlock` lower that snapshot to low-level
frontend `View` values. Imports may be written as separate Markdown paragraphs
or grouped in one pure import paragraph, for example
`[contentDocument](std/content.ssc) [contentView](std/ui/content.ssc)`.

Lower-level lookup helpers read the same Markdown snapshot without lowering it
to UI nodes. These helpers run in the interpreter and in generated JS/JVM
output, so Markdown-authored metadata can drive CLI output, browser codegen,
and generated Scala programs with the same source:

```scalascript
[contentDocument, contentCurrentSection, contentSection, contentBlock, contentData, contentMetadata, contentBind, contentPlainText, contentToMarkdown](std/content.ssc)

val doc = contentDocument()
val here = contentCurrentSection()
val plans = contentSection("plans").get
val controls = contentBlock("team-controls").get

println(doc.title.getOrElse(""))
println(here.id)
println(contentPlainText(plans))
println(contentPlainText(controls))
println(contentToMarkdown(plans))
println(contentData("plans-data").isDefined)
println(contentMetadata("defaultRenderer").isDefined)
```

`contentSection(id)` finds generated or explicit section ids,
`contentBlock(id)` finds explicitly identified blocks, and missing lookups
return `None`. `contentMetadata(path)` reads `content:` front-matter metadata
by dot path. `contentBind(value, bindings)` accepts a `DocumentContent`,
`SectionContent`, or `ContentBlock` plus a `ContentValue.MapV`; it resolves
simple `${name}` and `${nested.name}` inline placeholders and preserves missing
or non-path expressions as source markers. `contentPlainText(value)` accepts a
`SectionContent` or `ContentBlock` and extracts readable text for logging,
indexing, search, or component previews. `contentToMarkdown(value)` accepts a `DocumentContent`,
`SectionContent`, or `ContentBlock` and serializes it back to deterministic
semantic Markdown for export, previews, or later editing flows; it does not
promise byte-for-byte source whitespace preservation. `contentCurrentSection()`
returns the currently executing code block's enclosing Markdown section;
headingless code reports an error.

GitHub-Flavored Markdown pipe tables are included in the same content snapshot
as `ContentBlock.Table`. Header and cell text preserve inline emphasis, strong
text, links, inline code, and `${expr}` source nodes. `<!-- @meta ... -->`
immediately before a table attaches block attrs, so `contentBlock("plan-table")`
can select the table by id. `contentPlainText(table)` emits a stable readable
` | `-separated form, `contentToMarkdown(table)` emits a deterministic pipe
table, `contentView(table)` emits semantic table markup, and
`contentToolkitBlock("plan-table")` lowers it to the existing toolkit
`TableNode`. When table cells contain `${name}` placeholders, bind a data map
first with `contentBind(table, data)` or pass
`contentToolkitOptionsWithBindings(data)` to the toolkit selector.

Direct imports can also expose their Markdown snapshots as named content
modules. The namespace is the imported module's `name:` front-matter value, or
the imported path stem when `name:` is absent. Helper imports of
`std/content.ssc` and `std/ui/content.ssc` are filtered out of this namespace
table:

```scalascript
[money](std/money.ssc)
[contentModuleSection, contentModuleData, contentModuleMetadata](std/content.ssc)

val section = contentModuleSection(
  "std-money",
  "minor-units-integer-count-of-the-smallest-unit-e-g-cents"
).get

println(section.title)
println(contentModuleMetadata("std-money", "theme.density").isDefined)
```

Use `contentModules()` when you need the full direct-import table, or
`contentModule(namespace)` when you need the imported `DocumentContent` itself.
Duplicate direct import namespaces are runtime errors on lookup; transitive
imports are not exposed unless the parent imports them directly.

The ScalaScript 2.1 standard native path preserves this content structurally.
The self-hosted tower builds `DocumentContent` and direct-import ownership from
the canonical Markdown/YAML values; the Scala 3 seed only validates immutable
tags, and the core-free content provider performs lookups and rendering. Native
VM, direct ASM, and `build-jvm` therefore support `contentDocument`, section/
block/data/metadata lookup, imported-module lookup, `contentPlainText`, and
`contentToMarkdown` without Scalameta, CommonMark/Flexmark, or the v1 content
bridge. `build-jvm` stores the same values in
`META-INF/scalascript/content.bin`. `contentBind(...)` is an ordinary pure
definition in `std/content.ssc`: dotted-path validation, nested `MapV` lookup,
recursive block/inline rebuilding, and deterministic value rendering run in
ScalaScript on INT/JS/JVM/native VM/ASM and artifacts. The native provider does
not parse paths or install a lossy identity binding. Source-aware
`contentCurrentSection()` remains a separate follow-up until calls carry source
identity.

When a module is consumed through artifacts, the same snapshot is preserved for
the current module: `.scir` carries `NormalizedModule.document`, and `.sscc` v3
stores an optional trailing `DocumentContent` payload after the executable token
stream. Older artifacts can still have no snapshot; in that case content
helpers use the normal missing-content diagnostic instead of reconstructing a
partial document from execution sections.
Imports for these helpers must be normal Markdown import links outside fenced
code blocks. `contentToolkitNode()`, `contentToolkitBlock(id)`, and
`contentToolkitSection(id)` remain
frontend-toolkit helpers; use them with `lower(...)`/`serve(...)`, not as the
low-level JS/JVM metadata API.

The target contract, phase plan, and remaining metadata helpers are tracked in
[`specs/markdown-content-introspection.md`](../specs/markdown-content-introspection.md).

### Values and Variables

```scalascript
val x = 42          // immutable
var n = 0           // mutable
val pi = 3.14159
val name = "Alice"
```

### Functions

```scalascript
def add(a: Int, b: Int): Int = a + b

// Default parameters
def greet(name: String, prefix: String = "Hello"): String =
  s"$prefix, $name!"

// Lambdas
val double = (x: Int) => x * 2
val sum = (xs: List[Int]) => xs.foldLeft(0)(_ + _)

// Braced interpolation accepts a complete expression. Quotes and nested
// delimiters inside the expression belong to that expression, not the outer
// string.
val squares = List(1, 4, 9, 16, 25)
println(s"Squares: ${squares.mkString(", ")}")

// Higher-order
def apply(f: Int => Int, x: Int): Int = f(x)
apply(double, 21)    // 42
```

A definition without a parameter clause is evaluated when referenced as a
value. This differs from an explicit empty parameter clause:

```scalascript
def stage: Int => Int = value => value + 1
def explicit(): Int => Int = value => value + 2

val next = stage       // evaluates `stage`, then stores the returned function
println(next(4))       // 5
println(explicit()(4)) // 6; explicit `()` remains required
```

For lists of pairs, `map` and `flatMap` accept a two-parameter callback and
spread each pair in field order. A one-parameter callback still receives the
whole pair; direct two-argument functions retain their ordinary arity.

```scalascript
List(("parse", 1), ("check", 2)).map((name, n) => s"$name=$n")
```

Self and mutual tail calls are stack-safe in the standard VM and direct-ASM
lanes, including functions declared locally inside another function. The
direct-ASM `build-jvm` artifact uses the same trampoline contract; it does not
invoke the VM or a Java/Scala compiler to obtain tail-call elimination.

### Singleton Objects

Object bodies may use significant indentation or explicit braces. Both forms
own methods and parameterless properties identically; a bare sibling reference
stays inside the object.

```scalascript
object Counters:
  def base: Int = 40
  def next(n: Int): Int = n + 1
  def answer: Int = next(base) + 1

println(Counters.answer) // 42
```

The colon after `object Counters` opens the body. Colons in parameter, return,
and value type annotations remain ordinary type ascriptions.

### Case Classes and Sealed Traits

```scalascript
case class Point(x: Double, y: Double)
case class Person(name: String, age: Int)

val p = Point(3.0, 4.0)
val dist = math.sqrt(p.x * p.x + p.y * p.y)

// Copy with updates
val older = Person("Alice", 30).copy(age = 31)

// Sealed trait hierarchy
sealed trait Shape
case class Circle(radius: Double) extends Shape
case class Rect(w: Double, h: Double) extends Shape

def area(s: Shape): Double = s match
  case Circle(r) => math.Pi * r * r
  case Rect(w, h) => w * h
```

On the compiler-free ScalaScript 2.1 standard path, named `.copy` overrides are
matched by field label rather than position, even when written out of
declaration order. Unmentioned fields retain their original values. The
receiver and override expressions evaluate exactly once, left to right;
positional copy keeps declaration-order prefix replacement. `Focus`/`Prism`
are also compiler-free on the ScalaScript 2.1 standard path. `Focus[T]` accepts
structural selector chains: ordinary fields produce a Lens, `.some` crosses an
`Option` as an Optional, and `.each` traverses a `List` in source order.
`andThen` composes those structural paths; `Prism[Outer, Variant]` matches the
exact enum/sealed-hierarchy variant tag. Missing Optional/Prism targets return
`None` and leave `set`/`modify` input unchanged. Arbitrary getter expressions,
method calls, indexes, or filters are rejected explicitly; the standard
launcher never falls back to Scala macros, reflection, or the v1 frontend.

### Enums

```scalascript
enum Color:
  case Red, Green, Blue

enum Result[+A]:
  case Ok(value: A)
  case Err(msg: String)

val r: Result[Int] = Result.Ok(42)
r match
  case Result.Ok(n)  => println(s"Got $n")
  case Result.Err(m) => println(s"Error: $m")
```

### Pattern Matching

```scalascript
val x = 5
x match
  case 0         => "zero"
  case n if n > 0 => s"positive: $n"
  case _         => "negative"

// Destructuring
case class Point(x: Int, y: Int)
val pt = Point(3, 4)
pt match
  case Point(0, _) => "on y-axis"
  case Point(x, y) => s"at $x, $y"

// List patterns
val xs = List(1, 2, 3)
xs match
  case Nil         => "empty"
  case head :: Nil => s"singleton: $head"
  case head :: tail => s"head=$head, rest=${tail.length}"
```

### Collections

```scalascript
val nums = List(1, 2, 3, 4, 5)
nums.map(_ * 2)             // List(2, 4, 6, 8, 10)
nums.filter(_ % 2 == 0)     // List(2, 4)
nums.foldLeft(0)(_ + _)     // 15
nums.take(3)                // List(1, 2, 3)
nums.drop(3)                // List(4, 5)

val words = List("foo", "bar", "baz")
words.sorted                // List(bar, baz, foo)
words.mkString(", ")        // "foo, bar, baz"

// Map
val scores = Map("Alice" -> 95, "Bob" -> 87)
scores("Alice")             // 95
scores.getOrElse("Charlie", 0)  // 0
scores + ("Charlie" -> 72)

// Option
val maybeUser: Option[String] = Some("alice")
maybeUser.map(_.toUpperCase)        // Some(ALICE)
maybeUser.getOrElse("anonymous")    // "alice"
```

The compiler-free native VM and direct-ASM lanes use the same `mkString`
contract: the supplied separator appears only between adjacent elements, while
empty and singleton lists do not introduce it.

### For Comprehensions

```scalascript
val result = for
  x <- List(1, 2, 3)
  y <- List(10, 20)
  if x + y < 30
yield x * y

// result = List(10, 20, 20, 40)

// With Option
val parsed = for
  x <- parseInt("42")
  y <- parseInt("10")
yield x + y
// parsed = Some(52)
```

### Typeclasses

```scalascript
trait Show[A]:
  def show(a: A): String

given Show[Int] with
  def show(n: Int): String = n.toString

given [A: Show] => Show[List[A]] with
  def show(xs: List[A]): String =
    xs.map(summon[Show[A]].show).mkString("[", ", "]", "]")

def display[A: Show](a: A): String = summon[Show[A]].show(a)

println(display(42))              // "42"
println(display(List(1, 2, 3)))   // "[1, 2, 3]"
```

### Case Objects

A top-level `case object` is one stable nullary value. It survives `.ssc`
imports and can be referenced, compared, and matched directly:

```scalascript
trait ParserContext
case object NoContext extends ParserContext

val defaultContext = NoContext
println(defaultContext == NoContext) // true

defaultContext match
  case NoContext => println("default")
  case _         => println("custom")
```

On the compiler-free native route, inheritance is type metadata; the runtime
value is the portable nullary constructor `NoContext`.

### Extension Methods

```scalascript
extension (n: Int)
  def squared: Int = n * n
  def times(f: => Unit): Unit = (0 until n).foreach(_ => f)

4.squared      // 16
3.times { println("hello") }
```

The indented definitions are the complete extension body. Dedent—or the end of
the Markdown code fence—closes it before the next top-level declaration.
Extension ownership is retained across `.ssc` imports, so selected calls use
the imported extension rather than an unrelated built-in method with the same
name.

Symbolic extensions share names safely with primitive operators. When a `|`
extension is in scope, non-`Int` operands call that extension; two integers
still use bitwise OR:

```scalascript
case class Choice(label: String)

extension (left: Choice)
  def |(right: Choice): Choice = Choice(left.label + "|" + right.label)

val selected = Choice("a") | Choice("b") // Choice("a|b")
val mask = 6 | 3                           // 7
```

### List and Map Literals

In `.ssc` code blocks `[…]` is compact syntax sugar — no import needed:

```scalascript
val nums   = [1, 2, 3]             // List(1, 2, 3)
val empty  = []                     // List()
val words  = ["hello", "world"]

val scores = ["Alice" -> 95, "Bob" -> 87]   // Map("Alice" -> 95, "Bob" -> 87)
val cfg    = ["host" -> "db", "port" -> 5432]
```

The rule is simple: `[k -> v, ...]` (arrow present) expands to `Map(...)`,
anything else to `List(...)`.  Works everywhere an expression is expected —
method arguments, `val` initializers, nested literals:

```scalascript
val matrix  = [[1, 0], [0, 1]]              // List(List(1,0), List(0,1))
val headers = ["Content-Type" -> "application/json", "X-Token" -> token]

route("POST", "/api/todos") { req =>
  Db.execute("default", "INSERT INTO todos(text) VALUES (?)", [req.body.trim])
  Response.status(201, "created")
}
```

Type-parameter brackets are never affected — `def f[A](x: A)` is unchanged.

### Tuples

Tuples are immutable heterogeneous sequences.  Access via `._1`, `._2`, …

```scalascript
val pair  = (1, "hello")
pair._1                         // 1
pair._2                         // "hello"

val (x, y) = (10, 20)          // destructuring
println(s"x=$x y=$y")          // x=10 y=20
```

`Unit` is the 0-tuple `()`.  `def f(): Unit` and `def f(): ()` are identical.

#### Tuple monoid `++` (v1.60)

Tuples concatenate with `++`.  The result is always flat.
A bare value (non-tuple) is treated as a 1-element tuple on either side:

```scalascript
val a = (1, "hello")
val b = (true, 3.14)
a ++ b                          // (1, "hello", true, 3.14)

// bare value on the right — appends as element
a ++ 42                         // (1, "hello", 42)

// bare value on the left — prepends as element
"z" ++ a                        // ("z", 1, "hello")

// bare ++ bare — creates a 2-tuple
1 ++ "x"                        // (1, "x")

// () is the identity — collapses to the other operand
() ++ (1, 2)                    // (1, 2)
(1, 2) ++ ()                    // (1, 2)
() ++ 42                        // 42   (not (42,))
```

Effect runners use `Out(E) ++ (R,)` — `runStream` returns `(Source[A], R)`,
`runLogger` returns `R` (because `Out(Logger) = ()` and `() ++ R = R`).
See `specs/tuple-monoid.md` for the full algebraic specification.

### Optics

```scalascript
case class Address(street: String, city: String)
case class Person(name: String, address: Address)

// Lens — focus on a field
val cityLens = Focus[Person](_.address.city)
val alice = Person("Alice", Address("Main St", "Boston"))

cityLens.get(alice)               // "Boston"
cityLens.set(alice, "NYC")        // Person(Alice, Address(Main St, NYC))
cityLens.modify(alice, _.upper)   // Person(Alice, Address(Main St, BOSTON))

// Prism — focus on an enum case
enum Shape:
  case Circle(r: Int)
  case Rect(w: Int, h: Int)

val circlePrism = Prism[Shape, Shape.Circle]
circlePrism.getOption(Shape.Circle(5))   // Some(Circle(5))
circlePrism.getOption(Shape.Rect(3, 4))  // None

// Traversal — focus on all elements
case class Team(members: List[Person])
val nameTrav = Focus[Team](_.members.each.name)
nameTrav.getAll(team)                // List("Alice", "Bob")
nameTrav.modify(team, _.toUpperCase) // all names uppercased
```

---

## 4. Effects and Async

### Algebraic Effects

Effects are named interfaces; handlers intercept operations and may resume continuations.

```scalascript
effect Logger:
  def info(msg: String): Unit

effect Random:
  def nextInt(n: Int): Int

def program(): String =
  Logger.info("Starting...")
  val n = Random.nextInt(10)
  Logger.info(s"Got $n")
  s"Result: $n"

// Run with a concrete handler
handle(program()) {
  case Logger.info(msg, resume) =>
    println(s"[LOG] $msg")
    resume(())
  case Random.nextInt(n, resume) =>
    resume(scala.util.Random.nextInt(n))
}
```

### Standard Effects

Pre-built effects ship in `std/effects/`:

```scalascript
// State effect
def counter(): Int =
  State.modify[Int](_ + 1)
  State.get[Int]

runState(0)(counter())    // (1, ())

// Logger effect
def business(): Unit =
  Logger.info("Doing work")
  Logger.warn("Something odd")

runConsoleLogger(business())          // prints to console
val logs = runTestLogger(business())  // List[LogEntry]

// Random with seed (deterministic)
def roll(): Int = Random.nextInt(6) + 1
runSeededRandom(42)(roll())           // always the same
```

### Typed Effects (v1.12.1+)

The `!` operator in a function signature declares which effects that function
may perform. A function with no `!` annotation is total/pure.

```scalascript
// Function that may perform Logger and Random effects
def rollAndLog(label: String): Int ! Logger & Random =
  val n = Random.nextInt(6) + 1
  Logger.info(s"$label rolled $n")
  n

// Unhandled effects are compile errors (EffectAnalysis)
// rollAndLog("d6")  ← compile error: Logger & Random unhandled
runConsoleLogger(runSeededRandom(42)(rollAndLog("d6")))  // OK
```

Typed stdlib discharge signatures carry their effect in the type:

```scalascript
runLogger   : (body: A ! Logger)  => A
runRandom   : (seed: Long)(body: A ! Random) => A
```

#### `multi effect` — multi-shot continuations

Ordinary effects are single-shot (the continuation is invoked at most once).
`multi effect` allows the handler to resume the continuation many times:

The one-shot rule is enforced at runtime with one atomic claim shared by deep
handler and forwarding wrappers. A second sequential or concurrent `resume`
aborts the run as
`error [ONESHOT_VIOLATION]: One-shot violation: <Effect>.<op> resumed more than once`;
ordinary ScalaScript `try/catch` cannot intercept this control-contract failure.
Low-level CoreIR `effect.perform` remains reusable for free-monad/Mira programs.

On the standard portable VM and direct-ASM lanes, deep handler resumption is
stack-safe in tail, binding/sequence, non-tail, and escaped-continuation
positions. The shared runtime iterates the explicit effect computation rather
than recursively folding handlers on the JVM stack; the
[axis-20 probe](../tests/interop-conformance/probes/20-stack-safety-deep-effect-recursion.ssc)
qualifies 100,000 tail/sequence operations and 20,000 non-tail/escaped resumes.
This guarantee is lane-specific: other backends claim it only after running the
same control vector.

```scalascript
multi effect NonDet:
  def choose[A](options: List[A]): A

def prog(): List[Int] ! NonDet =
  val x = NonDet.choose(List(1, 2, 3))
  val y = NonDet.choose(List(10, 20))
  List(x + y)

handle(prog()) {
  case NonDet.choose(opts, resume) =>
    opts.flatMap(o => resume(o))   // resume called once per option
}
// List(11, 21, 12, 22, 13, 23)
```

#### `Reader[R]` capability

`Reader[R]` injects a read-only context value without threading it through
every call:

```scalascript
def greetUser(): String ! Reader[String] =
  val name = Reader.get[String]
  s"Hello, $name!"

runReader("Alice")(greetUser())   // "Hello, Alice!"
```

See [`specs/algebraic-effects.md`](algebraic-effects.md) for the full spec.

### Scala 3 control API (explicit Tier 1 + lexical direct M1)

Ordinary Scala 3/JVM programs can use the compiler-independent
`scalascript.control` API. The repository builds the publication-ready leaf as
`io.scalascript:scalascript-control_3`; it is deliberately independent of
CoreIR, UniML, backends, the CLI, and the legacy interop runtime. This coordinate
describes the built artifact and is not a claim that a release has already been
published to a Maven repository.

```scala
import scalascript.control.*

object Input extends Effect:
  val key: EffectKey[Input.type] =
    EffectKey.named(EffectId("example.input"), this)

case object Read extends Operation[Input.type, Int]:
  val effect: EffectKey[Input.type] = Input.key
  val id: OperationId = OperationId(effect.id, "read")

val program: Eff[Input.type, Int] = perform(Read).map(_ + 1)

val handled = handle[Input.type, Nothing, Int, Int](program)(
  new Handler[Input.type, Nothing, Int, Int]:
    val effect: EffectKey[Input.type] = Input.key

    def onReturn(value: Int): Eff[Nothing, Int] = Eff.pure(value)

    def onOperation[A](
        operation: Operation[Input.type, A],
        resumption: Resumption[A, Nothing, Int]
    ): Eff[Nothing, Int] =
      operation match
        case Read =>
          resumption match
            case Resumption.Reusable(continuation) =>
              continuation.resume(41)
            case Resumption.OneShot(_) =>
              throw new AssertionError("Read must be reusable")
)

assert(Eff.runPure(handled) == 42)
```

Prompts are fresh path-dependent values, so one `reset` cannot accidentally
discharge another prompt. `shift` captures up to the nearest matching prompt and
its rank-2 body sees the actual residual effect row:

```scala
val scoped = freshPrompt[Int]
val prompt = scoped.prompt

val shifted: Eff[Control[scoped.Key], Int] =
  shift[scoped.Key, Int, Nothing, Int](prompt)(
    [Residual >: Nothing <: Effect] =>
      (continuation: Continuation[Int, Residual, Int]) =>
        continuation.resume(21).map(_ * 2)
  )

val answer = reset[scoped.Key, Nothing, Int](prompt)(shifted)
assert(Eff.runPure(answer) == 42)
```

For a bounded lexical region, `direct.reset` and `direct.shift` provide ordinary
direct-style Scala syntax while expanding exclusively to the same explicit API:

```scala
val directAnswer: Eff[Nothing, Int] =
  direct.reset[scoped.Key, Nothing, Int](prompt) {
    val resumed =
      direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
        [Residual >: Nothing <: Effect] =>
          (continuation: Continuation[Int, Residual, Int]) =>
            continuation.resume(41)
      )
    resumed + 1
  }

assert(Eff.runPure(directAnswer) == 42)
```

M1 accepts pure prefixes/suffixes, block-level shift binds, tail shifts, sequential
binds, and strict local `val`/`var`/`given` or pattern bindings that cross a
capture. Those locals are rebound under the generated owner; a reusable
continuation therefore shares one ordinary Scala closure cell for a captured
`var`. It fails at compile time if a marker escapes its matching lexical scope,
crosses a callback/resource/control barrier, targets an outer scope through a
nested `direct.reset`, or appears in an unsupported expression shape. A local
method, class, type, ordinary lazy value, or inline application that would cross
the capture split also fails closed instead of producing a raw owner error or
forcing lazy/inline code. Cross-method transformation and managed callbacks belong
to the later compiler plugin; M1 introduces no second runtime or exception-based
continuation path.

Reusable continuations expose `resume`; one-shot continuations expose only atomic
`tryResume`, whose second or concurrent loser returns typed `AlreadyResumed`.
Tier 1 is local: `Continuation.save()` performs the typed
`Save.Rejected(UnmanagedCapture)` operation because no post-X1 save plan exists.
It does not serialize a JVM stack, replay a prefix, or fabricate a durable value.

The complete runnable example prints `Vector(10, 20)`, `42`, and `42` (the final
line is the direct-style result):

```bash
scripts/sbtc "scala3ControlApi/Test/runMain scalascript.controlapi.examples.effectsAndShift"
```

Source: [`ControlApiExample.scala`](../v2/host/scala/control/src/test/scala/scalascript/controlapi/examples/ControlApiExample.scala).
Successful durable `save`/`run`, network transfer, Scala↔ScalaScript lowering and
typed call/value bridges, managed callbacks and mixed tail SCCs, the compiler
plugin, admission, and exact/portable runners remain separate post-X1 milestones. See the
[`Scala 3/JVM host profile`](../specs/scala3-bidirectional-control.md).

### JavaScript/TypeScript explicit control API (first slice)

Ordinary ESM applications can use the zero-production-dependency
`@scalascript/control` package from
[`v2/host/js/control`](../v2/host/js/control). It is the compiler-independent
local reference implementation of the same `Pure | Op` laws as the Scala API:
`Eff` is iterative and reusable, handlers are deep, unmatched effects retain their
resumptions, and one-shot ownership is claimed before the suffix is constructed.

```js
import {
  Eff,
  ResumeMultiplicity,
  defineEffect,
  handle,
  perform
} from "@scalascript/control"

const InputOwner = Symbol("example.Input.owner")
const Input = defineEffect("example.Input", InputOwner)
const Read = Input.operation("read", {
  multiplicity: ResumeMultiplicity.OneShot
})

const program = perform(Read()).map(value => value + 1)
const handled = handle(program, {
  effect: Input,
  onReturn: value => Eff.pure(value),
  onOperation(operation, resumption) {
    if (!Read.is(operation) || resumption.kind !== "OneShot") {
      return Eff.pure(-1)
    }
    const attempt = resumption.continuation.tryResume(41)
    return attempt.ok ? attempt.computation : Eff.pure(-1)
  }
})

console.log(Eff.runPure(handled)) // 42
```

The named owner symbol is semantically separate from the stable descriptor
string. TypeScript preserves its `unique symbol` type, so two independently
declared keys with the same descriptor cannot discharge one another's effect row;
reusing the same owner and descriptor returns the same runtime key. Inline or
widened symbols are rejected by the declaration because they cannot carry a
sound generative type identity.

`freshPrompt` uses a scoped generic callback, and its private declaration brands
make nested prompt keys incompatible in TypeScript. `reset` discharges only its
own `Control<P>` row; `shift` keeps its body under the same reset, rather than
silently providing `shift0` behavior. `Continuation.local` may resume repeatedly,
while its `save()` performs the typed
`Save.Rejected(UnmanagedCapture("Continuation.local"))` operation.
Opaque computations, continuations, and prompts keep authority-bearing state in
private weak storage, and every reachable internal constructor is guarded by a
module-private token. The published package also carries the project Apache 2.0
license.

This package does not yet claim the complete JavaScript/TypeScript host profile.
Generated descriptors/facades, ScalaScript↔JavaScript value and call bridges,
cross-method managed transforms and event-loop callbacks, mixed-language tail SCCs,
successful durable save/run, and exact/portable runners remain later slices. The
exact ESM and `.d.ts` ABI is frozen in the
[`JS/TS host profile`](../specs/javascript-typescript-bidirectional-control.md).

### JavaScript/TypeScript lexical direct control (T1)

For a closed synchronous lexical region, install the separate authoring transform
alongside the explicit runtime and the consuming project's TypeScript compiler:

```bash
npm install @scalascript/control
npm install --save-dev @scalascript/control-direct typescript@5.9
```

Write `direct.reset` with top-level `const` or `let` shift bindings:

```ts
import { Eff, freshPrompt } from "@scalascript/control"
import { direct } from "@scalascript/control-direct"

const answer = freshPrompt<number, number>(prompt => Eff.runPure(
  direct.reset(prompt, (): number => {
    const selected: number = direct.shift(prompt, continuation =>
      continuation.resume(41)
    )
    return selected + 1
  })
))
```

Compile with the wrapper, which follows ordinary `tsc` project options and retains
TypeScript diagnostics and source maps:

```bash
npx ssc-control-tsc --project tsconfig.json
```

T1 accepts the TypeScript 5.9.x compiler API and is qualified with 5.9.3. The CLI
resolves that compiler from the named project/config directory or current working
directory, including when the packed command is reached through an npm `.bin`
symlink in an external store. It does not bundle a second compiler or use a global
fallback. The exact tarball's only development dependency is the registry pin
`typescript: 5.9.3`; it publishes no repository-local `file:`, `link:`, `workspace:`,
absolute, or relative sibling specification. Ordinary installation after clean
extraction therefore does not require a neighboring ScalaScript checkout or create
a dangling `@scalascript/control` link.

The transform emits the existing `@scalascript/control` `Eff.pure`, `reset`,
`shift`, and `flatMap` protocol. Prefix statements run once; every reusable resume
enters its suffix; sequential markers preserve source order; and ordinary local
`let` state is shared by multi-shot resumes. An aliased named import such as
`import { direct as d } ...` works because the compiler binding, not the text
`direct`, owns the marker. Shadowed names, comments, strings, and same-spelled
properties are not transformed.

The authoring package is build-time-only. A clean file has every owned marker use
lowered and its named `direct` import specifier removed; emitted JavaScript imports
only `@scalascript/control` and runs after development dependencies are omitted.
In a mixed exact-module import, type-only specifiers are also removed from JavaScript
while ordinary runtime specifiers remain. Declaration emit still sees the original
TypeScript import and preserves its public types. Exact-module type-only source
exports are normalized the same way: no empty `export {} from ...` module edge is
left under either `verbatimModuleSyntax` mode, mixed runtime exports remain, and the
original `.d.ts` export is retained. Other modules and runtime specifiers are not
rewritten.

Any value use of the marker that would remain in JavaScript—including object
shorthand and local runtime export aliases—is a diagnostic, and one diagnostic
cancels every rewrite in that source file. TypeScript declaration-level and
specifier-level type-only exports of the marker remain accepted. A runtime
CommonJS/Node10 `import markers = require("@scalascript/control-direct")` is treated
as an unsupported namespace marker import whether used or unused; its explicit
`import type markers = require(...)` counterpart is erased from JavaScript and kept
in declaration output.

Lowering does not replace an authored binding with a mutable callback parameter.
It receives the resumed value under a collision-safe generated name and then emits
the original `const` or `let` declaration initialized from it. This applies to real
JavaScript under `allowJs: true, checkJs: false` as well as TypeScript.

T1 deliberately fails closed for async/generator/await/yield, cleanup, loops,
switch, a marker nested under a branch/callback/class, prompt mismatch, `var` or
destructuring marker binds, arbitrary marker positions, a marker-layer prefix or
shift body value-referencing its own or a later suffix binding (including shorthand
property or assignment-initializer values), and intrinsic direct `eval` anywhere in
a selected file, including a file selected only to erase the marker import. The
binding checks follow TypeScript runtime-value symbols through nested syntax and do
not confuse ordinary property names, type-only references, or genuine shadowing.
Parentheses plus `as`, non-null, and type assertions are transparent for marker/eval
ownership; indirect eval and `Function` remain global-only unmanaged operations. The
stable diagnostics are
`JS_DIRECT_OUTSIDE_RESET`, `JS_DIRECT_CAPTURE_BARRIER`, `JS_DIRECT_UNSUPPORTED`,
and `JS_DIRECT_PROMPT_MISMATCH`. If a root marker reaches runtime without the
transform, it throws `JS_DIRECT_UNTRANSFORMED`; it never acts as a second control
implementation.

The exact accepted grammar and package/tooling API are in the
[`direct-control specification`](../specs/javascript-typescript-control-direct.md).
Cross-method/module CPS, managed callbacks, generated value/call bridges, runners,
durable continuations, and shared-lane qualification remain later host-profile
slices.

### Direct Syntax (Do-Notation)

Write monadic code without `<-` arrows:

On the compiler-free ScalaScript 2.1 standard path, the supported native
contract is deliberately bounded to explicit `direct[Option]` and
`direct[List]` blocks. Fresh assignments are binds, Option short-circuits,
List expands in source order, and pure `val`, mutable `var`, and nested direct
blocks work without a compiler or runtime fallback. Type-directed direct
blocks, postfix `.!`, Async/custom monads, and pure auto-lift remain available
only through explicit `--compat-frontend` until their native contracts land.

```scalascript
// Explicit direct block
val result = direct[Option] {
  x = parseInt("42")     // Option[Int] — binds or short-circuits
  y = parseInt("10")
  x + y
}
// result = Some(52)

// Postfix .! for inline bind
direct[Option] {
  val total = parseInt("42").! + parseInt("10").!
  Some(total)
}

// Type-directed (implicit direct block when return type is known)
def loadUser(id: String): Option[User] =
  raw  = usersMap.get(id)        // Option[User] — auto-bind
  name = raw.name
  User(name.trim)
```

### Async

```scalascript
// Single-threaded deterministic
runAsync {
  val a = Async.async(() => heavyCompute(1))
  val b = Async.async(() => heavyCompute(2))
  val results = Async.parallel(List(() => 1, () => 2, () => 3))
  Async.delay(100)
  Async.await(a) + Async.await(b)
}

// Real parallel threads (JVM only)
runAsyncParallel {
  Async.parallel(List(
    () => { Async.delay(100); "a" },
    () => { Async.delay(100); "b" }
  ))
  // finishes in ~100ms instead of ~200ms
}
```

On the ScalaScript 2.1 standard JVM path, both runners are owned by the
core-free effect-runners provider. `runAsync` evaluates futures and parallel
lists deterministically on the caller thread. `runAsyncParallel` starts virtual
threads and joins results in declared order. Future failures are delivered at
`await`/`parallel`; they are never silently converted to Unit. VM, direct ASM,
and `build-jvm` use the same provider without the compatibility frontend or a
compiler toolchain.

### Pull generators

`generator` starts a lazy pull stream. Values cross a synchronous handoff only
when `next`, `toList`, `foreach`, or a downstream combinator requests them, so
an infinite producer cannot build an unbounded host queue. `take` cancels the
abandoned upstream producer after its last requested value.

```scalascript
val naturals = generator[Int] { () =>
  var n = 0
  while true do
    suspend(n)
    n = n + 1
}

val firstEvenSquares = naturals
  .filter(_ % 2 == 0)
  .map(n => n * n)
  .take(5)

println(firstEvenSquares.toList) // List(0, 4, 16, 36, 64)
```

The local surface includes `next`, `toList`, `foreach`, `map`, `filter`,
`take`, `drop`, `flatMap`, `zip`, and `zipWithIndex`. Producer failures reach
the next consumer operation rather than becoming an empty stream. ScalaScript
2.1 uses the same core-free provider on native VM, direct ASM, and
`build-jvm`; it does not load the compatibility frontend or compiler toolchain.
See [`examples/generators.ssc`](../examples/generators.ssc) for the complete
contract.

### Streams

```scalascript
val latest =
  Source.from(1 to 5)
    .buffer(3, OverflowStrategy.DropHead)
    .debounce(100)
    .runToList()

val ordered =
  Source.from(1 to 4)
    .throttle(Rate(2, 1000))
    .runToList()
```

`Source[A]`, `Sink[A]`, and `Flow[A, B]` are provided by
`std/streams.ssc` and the streams plugin. The interpreter path supports
bounded `buffer` with `Backpressure`/`Block`, `Drop`, `DropHead`/`DropOldest`,
and `Fail`, deterministic order-preserving `throttle`, latest-value
`debounce`, `Source.signal(sig)` live subscriptions for frontend signals, and
reverse `sig.bind(source)` in the interpreter/JVM desktop path. SwiftUI codegen
still lowers signals to native `@State`; a platform-native stream bridge is
tracked separately.

### Reactive Signals

```scalascript
val count   = Signal(0)
val doubled = computed { count.get * 2 }
val squared = computed { count.get * count.get }

effect {
  println(s"count=${count.get} doubled=${doubled.get}")
}

count.set(3)   // → "count=3 doubled=6"
count.set(5)   // → "count=5 doubled=10"
```

On the compiler-free ScalaScript 2.1 route, this surface is implemented by a
dedicated core-free provider on native VM, direct ASM, and `build-jvm`. Effects
run immediately and synchronously; dependency sets refresh on every rerun,
diamond subscriptions are deduplicated in insertion order, and a self-write
does not recursively reschedule the currently running effect. Async scheduling
and explicit subscription disposal are not part of this general signal API.

---

## 5. Web Server

### Basic Routes

```scalascript
route("GET", "/") { req =>
  Response.html("<h1>Hello!</h1>")
}

route("GET", "/users/:id") { req =>
  val id = req.params("id")
  Response.json(Map("id" -> id, "name" -> "Alice"))
}

route("POST", "/users") { req =>
  val user = jsonRead[User](req.body)
  Response.json(user).withStatus(201)
}

route("DELETE", "/users/:id") { req =>
  users.remove(req.params("id"))
  Response.status(204)
}

serve(8080)
```

### OpenAPI and Swagger

When a server calls `serve(port)` or `serveAsync(port)`, ScalaScript registers
two development endpoints:

| Route | Behaviour |
|---|---|
| `GET /_openapi.json` | OpenAPI 3.1 JSON derived from registered `route()` handlers |
| `GET /_swagger` | Swagger UI page pointing at `/_openapi.json` |

The interpreter derives path parameters, query parameters, and JSON request-body
properties from typed handler metadata where available. JVM codegen exposes the
same built-in endpoints from its generated `_routes` table. Front-matter routes
with matching `apiClients:` endpoint metadata carry their response type into the
OpenAPI response schema; raw `Request => Any` handlers keep the generic `200 OK`
fallback.

For CI, client generation, or API gateway import you can export the same
document without binding an HTTP port:

```bash
ssc emit-openapi api.ssc
ssc emit-openapi api.ssc --format yaml -o openapi.yaml
ssc emit-openapi api.ssc --title "Users API" --version 2.0.0 --server https://api.example.com
```

`emit-openapi` runs the interpreter in abort-at-first-serve dry-run mode:
`serve(...)` does not open a socket, and execution stops there after earlier
`route(...)` registrations, `@openapi(...)` metadata, and `openApiSecurity(...)`
declarations have been collected.

Planned, not implemented yet: a shared contract-validation command family will
check OpenAPI and GraphQL contracts against ScalaScript route/resolver source,
typed request/response shapes, status/error metadata, profiles, and overlays.
See [`specs/contract-validation.md`](contract-validation.md) for the planned
model.

Per-route operation metadata can be attached to the next `route(...)` call with
`@openapi(...)`:

```scalascript
[openapi](std/openapi.ssc)

@openapi(summary = "Get user", description = "Returns a user.", tags = List("users"))
route("GET", "/users/:id") { req => Response.json(Map("id" -> req.params("id"))) }
```

Supported fields are `summary`, `description`, `tags`, `deprecated`, and
`security`. Declare reusable security schemes with `openApiSecurity(...)`:

```scalascript
[openapi, openApiSecurity](std/openapi.ssc)

openApiSecurity("bearerAuth", "bearer", "JWT")

@openapi(security = List("bearerAuth"))
route("DELETE", "/users/:id") { req => Response.status(204) }
```

### File-based handlers (`mount()`)

`mount()` evaluates a `.ssc` file once at startup and registers the result
as the handler for a given method + path. The file's last expression determines
the handler shape:

| Last expression | Behaviour |
|---|---|
| `Response` value | auto-wrapped as `_ => response` (static reply) |
| `req => Response` | used directly (1-arg handler) |
| `(req, ctx) => Response` | called with `req` and the `ctx` map from `mount()` |

```scalascript
// Static response — ping.ssc contains: Response.text("pong")
mount("GET", "/ping", "handlers/ping.ssc")

// 1-arg handler — hello.ssc contains: req => Response.text(s"Hello, ${req.params("name")}!")
mount("GET", "/hello/:name", "handlers/hello.ssc")

// Same handler file reused for two routes via ctx map
mount("GET", "/users/:id",    "handlers/entity.ssc", Map("coll" -> "users"))
mount("GET", "/products/:id", "handlers/entity.ssc", Map("coll" -> "products"))

serve(8080)
```

`file` is resolved relative to the calling `.ssc` file's directory (same as
`import`).  A second `mount()` for the same `(method, path)` replaces the
existing entry (idempotent — safe to call on hot-reload).

See [`examples/mount-demo/`](../examples/mount-demo/) for a runnable example.

### Typed handlers

If a handler file's last expression is a function whose parameters are **not** `Request`, the runtime treats it as a typed handler: it auto-deserializes the input from the request and auto-serializes the output to JSON 200.

**Deserialization priority** (first match wins, by field name):
1. Path params (`:name` segments in the mounted path)
2. Query params (`?name=alice`)
3. JSON body (field with the same name)

**Simple example — case class in, case class out:**

```scalascript
// handlers/greet.ssc
case class GreetInput(name: String)
case class GreetOutput(greeting: String)

(input: GreetInput) => GreetOutput(s"Hello, ${input.name}!")
```

Mounted as `mount("GET", "/greet/:name", "handlers/greet.ssc")`, a `GET /greet/alice` request fills `GreetInput(name = "alice")` from the path param and returns `{"greeting":"Hello, alice!"}`.

**Multi-field example — fields from different sources:**

```scalascript
// handlers/greet-extended.ssc
case class GreetInput(name: String, lang: String)
case class GreetOutput(greeting: String)

(input: GreetInput) =>
  val msg = if input.lang == "es" then s"Hola, ${input.name}!" else s"Hello, ${input.name}!"
  GreetOutput(msg)
```

Mounted on `/greet/:name`, a request `GET /greet/alice?lang=es` fills `name` from the path param and `lang` from the query string.

**Explicit deserialization error handling** — use `Either[Request, Input]`:

```scalascript
(req: Either[Request, GreetInput]) =>
  req match
    case Left(raw)    => Response.json("""{"error":"missing name"}""", status = 400)
    case Right(input) => GreetOutput(s"Hello, ${input.name}!")
```

**Output types:**

| Return type | Result |
|---|---|
| Case class / named tuple | JSON object, status 200 |
| Unnamed tuple (e.g. `(a + b, a * b)`) | JSON array, status 200 |
| `Either[Response, Output]` | `Left(resp)` used as-is; `Right(output)` → JSON 200 |

**Special trailing parameters** — the last parameter(s) may be `Request` and/or `Map[String, Any]` (in that order); these are never deserialized:

```scalascript
(name: String, req: Request)                        // deser name; req = raw request
(name: String, ctx: Map[String, Any])               // deser name; ctx = mount context
(name: String, req: Request, ctx: Map[String, Any]) // all three
```

See [`specs/mount-handlers.md`](mount-handlers.md) for the full typed handler signature table and deserialization rules.

### Request and Response

```scalascript
// Request fields
req.method                  // "GET", "POST", ...
req.path                    // "/users/42"
req.params("id")            // path captures
req.query.getOrElse("q", "")  // ?q=...
req.headers("Content-Type")
req.body                    // raw body string
req.form("username")        // form fields
req.session                 // Map[String, String] from signed cookie
req.json                    // JsonValue
req.bearerToken             // Option[String]
req.jwtClaims               // Option[Map[String, String]]

// Response builders
Response.html(body)
Response.text(body)
Response.json(value)         // auto-serialized
Response.redirect("/login")
Response.notFound("Oops")
Response.status(422, "Unprocessable")
```

### Middleware

```scalascript
useCors("https://myapp.com")   // or "*" for dev
useGzip()
useCacheHeaders(maxAge = 3600)
useSessionStore(ttlSeconds = 1800)  // server-side sessions
```

### Authentication

```scalascript
// Password hashing
val hash = hashPassword(req.form("password"))
verifyPassword(req.form("password"), storedHash)

// Sessions
route("POST", "/login") { req =>
  if !csrfValid(req) then Response.status(403)
  else if verifyPassword(req.form("pass"), getHash(req.form("user"))) then
    Response.redirect("/dashboard")
      .withSession(Map("user" -> req.form("user")))
  else
    Response.status(401, "Bad credentials")
}

route("POST", "/logout") { req =>
  Response.redirect("/").clearSession()
}

// JWT for APIs
route("GET", "/api/profile") { req =>
  req.jwtClaims match
    case Some(claims) => Response.json(claims)
    case None         => Response.status(401)
}

val token = jwtSign(Map("sub" -> userId, "role" -> "admin"))
```

### Validation

```scalascript
route("POST", "/register") { req =>
  validate {
    val name  = requireString(req.json, "name")
    val email = requireString(req.json, "email")
    val age   = requireRange(req.json, "age", 18, 120)
    User(name, email, age)
  } match
    case Right(user) => Response.json(user)
    case Left(errs)  => Response.status(422, errs.map(_.message).mkString(", "))
}
```

### Streaming and SSE

```scalascript
// Server-Sent Events
route("GET", "/events") { req =>
  sse(req) { sink =>
    var i = 0
    while i < 10 do
      sink.send(s"data: event $i\n\n")
      Async.delay(500)
      i += 1
  }
}

// Streaming response body
route("GET", "/download") { req =>
  streamResponse { sink =>
    for chunk <- readChunks("large-file.bin") do
      sink.write(chunk)
  }
}
```

### Rozum agent gateways

`std.agent` provides an app-owned tool loop over OpenAI-compatible rozum
gateways. Use `runAgent` for ordinary non-streaming completions, or
`runAgentStream` / `collectAgentStream` when the gateway returns SSE chunks.

Tool schemas can be explicit JSON Schema strings through `agentTool`, or
derived from a typed case-class input through `AgentSchema`:

```scalascript
[AgentSchema, agentToolFor, toolOk](std/agent.ssc)
[jStr, jNum, jField, jObj](std/json.ssc)

case class PostTransaction(amount: Int, memo: String) derives AgentSchema

val postTransaction =
  agentToolFor[PostTransaction](
    "post_transaction",
    "Post one accounting transaction.",
    summon[AgentSchema[PostTransaction]]
  ) { args =>
    toolOk(jObj(List(
      jField("amount", jNum(args.amount.toString)),
      jField("memo", jStr(args.memo))
    )))
  }
```

Derived schemas support top-level case-class records with `String`, `Int`,
`Double`, `Boolean`, `List[T]`, and `Option[T]` fields where `T` is supported.
Use explicit `agentTool(..., parametersJson)` for custom constraints, enums, or
unsupported shapes.

```scalascript
[AgentEndpoint, RunOptions, runAgentStream](std/agent.ssc)

val result = runAgentStream(
  AgentEndpoint("http://localhost:18089"),
  "local-model",
  "You are a careful assistant.",
  "Summarize the current task.",
  List(),
  RunOptions(maxSteps = 4)
) { event =>
  if event.kind == "TextDelta" then
    println(event.text)
}
```

Streaming events use `AgentEvent.kind` values such as `TextDelta`,
`ToolCallStarted`, `ToolCallDelta`, `ToolCallResult`, `Errored`, and
`Stopped`. `collectAgentStream(...)` returns `AgentStreamResult` when the app
wants the final `AgentResult` plus the ordered event list.

For bounded ordered failover across several rozum gateways, build an
`AgentEndpointPool` and call the pool variants:

```scalascript
[AgentEndpoint, AgentEndpointPool, RunOptions, runAgentPool](std/agent.ssc)

val pool = AgentEndpointPool(List(
  AgentEndpoint("http://primary.internal:18089"),
  AgentEndpoint("http://secondary.internal:18089")
), maxAttempts = 2)

val result = runAgentPool(pool, "local-model", "system", "user", List(), RunOptions())
```

Pool failover retries only transport failures and HTTP `5xx` responses. HTTP
`4xx`, unknown tools, and handler validation errors use the normal `Error` or
tool-result path and do not try another endpoint. See
[`examples/rozum-agent-pool.ssc`](../examples/rozum-agent-pool.ssc),
[`examples/rozum-agent-schema-derived.ssc`](../examples/rozum-agent-schema-derived.ssc),
[`examples/rozum-agent-streaming.ssc`](../examples/rozum-agent-streaming.ssc),
[`specs/rozum-agent-schema-derivation.md`](../specs/rozum-agent-schema-derivation.md),
[`specs/rozum-agent-endpoint-pool.md`](../specs/rozum-agent-endpoint-pool.md),
and [`specs/rozum-agent-streaming.md`](../specs/rozum-agent-streaming.md).

### TLS

```scalascript
val tlsCfg = tls("server.crt", "server.key")
serve(443, tls = tlsCfg)
```

### Static Build

```bash
ssc render server.ssc /about   # print response body for GET /about
ssc build src/ dist/           # dir-walk static site generator (backward compat)
ssc build server.ssc --target web --out dist/   # project-file mode, web target
```

### Development Server with Hot Reload

```bash
ssc serve server.ssc   # start server + watch for changes; routes reload live
ssc watch server.ssc   # identical — use whichever reads more naturally
```

On each save the route table is cleared and rebuilt from the new source; the
TCP port stays bound so in-flight requests are not dropped.

---

## 6. SQL Databases

### Declaring connections

Add a `databases:` map to front-matter.  Each entry is a named JDBC connection:

````ssc
---
name: myapp
databases:
  default:
    url: "jdbc:sqlite:./app.db"
  analytics:
    url:      "jdbc:postgresql://db:5432/analytics"
    user:     "${env:DB_USER}"
    password: "${env:DB_PASSWORD}"
    driver:   "org.postgresql.Driver"   # optional — auto-detected for bundled drivers
---
````

Bundled drivers: **SQLite** (`jdbc:sqlite:`) and **H2** (`jdbc:h2:`).  Any other driver can be added as a `dep:` import or placed on the classpath.

#### SclJet low-level format module

`std/scljet/index.ssc` is an independent pure ScalaScript SQLite-format engine
under staged development. Its current M2 surface decodes database headers, all
four B-tree page/cell forms, overflow chunks/chains, record serial types, and
UTF-8/UTF-16 bytes/code points. `openReadonly(vfs, path, options)` acquires a
SHARED lock, rejects non-empty journal/WAL sidecars, maintains an immutable LRU
page cache, validates freelist and auto-vacuum pointer-map ownership, decodes
raw `sqlite_schema`, and exposes forward rowid/WITHOUT ROWID/index cursors.
`jvmSqliteVfs()` adapts the separately packaged JVM host plugin to that abstract
VFS contract; page, record, B-tree, schema, and pager policy remain `.ssc` code.

Run `ssc-tools run --v1 examples/scljet-readonly.ssc` for a complete real-file
open/schema/row/close flow or `examples/scljet-readonly-codecs.ssc` for the pure
low-level codecs. SclJet does not replace the JDBC/sql.js `sqlite:` provider yet
and still exposes no query planner, recovery, WAL snapshot, or writable
connection. See `specs/scljet.md` for the compatibility gates.

Connection strings support `${scheme:ref}` secret references (see §6.2).

On `ssc run --native` and `ssc run --native --bytecode`, explicit root-module
`databases:` maps are parsed without the v1 frontend and passed to the core-free
native SQL provider. Named H2/SQLite/PostgreSQL connections and programmatic
`Db.query`/`Db.execute` use the shared JDBC runtime. Conflicting declarations of
the same database name across explicit roots fail before a connection is opened.
Native `sql` fences execute in document order through that provider, expose
each `_sqlBlock_N` result and the first `<Section>.sql` alias, and support
source-expression binds without loading the compatibility frontend. Native
`RowCodec` products also support native `Db.query/insert/update[A]` with
identifier-validated generated writes and case-insensitive row reconstruction.
`transaction` fences and PostgreSQL LISTEN/NOTIFY remain compatibility-only
until their follow-up slices land. The
native UI provider also builds mutable/derived signals and basic
text/signal/show/fragment/element views, and `emit(view, outDir)` writes a
deterministic escaped UTF-8 `index.html` without loading `frontendCore` or a
frontend compiler. Framework SPA generation, `serve(view, port)`, keyed/fetch
widgets, storage/WebAuthn, and native desktop/mobile rendering remain follow-up
UI slices.

The representative native effect provider implements curried
`runState(initial) { body }` with `State.get`, `State.set`, and
`State.modify`. Handler scope is host-owned and stack-safe: nested runners
restore the outer state and exceptions pop their handler in `finally`. Logger,
Random, Clock, Env, Retry, Cache, Async, and Stream still require their explicit
native provider slices; the standard path does not load v1 `BlockForm` adapters
for them.

### `sql` fenced blocks

A ` ```sql ``` ` fenced block executes against the `default` database. Use
` ```sql @db=analytics ``` ` to select another declared connection. The raw
result is an update count for DDL/DML or a portable `List[Map]` for a row
statement:

````ssc
```sql
CREATE TABLE IF NOT EXISTS todos (
  id   INTEGER PRIMARY KEY AUTOINCREMENT,
  text TEXT    NOT NULL
)
```
````

In the compiler-free standard frontend, `${expr}` creates one JDBC `?` and one
source-ordered bind value. `$$` emits a literal dollar. The first SQL fence
under a heading is also available as `<Section>.sql`:

````ssc
## Users

```sql
INSERT INTO users(name, email) VALUES (${name}, ${email})
```

println(Users.sql)
````

An unterminated `${...}` bind and a JVM `sql @side=client` fence fail before
execution; neither condition triggers the v1/Scalameta compatibility frontend.
Programmatic `Db.execute` and `Db.query` continue to accept explicit `?`
parameters as shown below.

### Programmatic access

`Db.query` and `Db.execute` are available in `scalascript` blocks:

```scalascript
// Returns List[Map[String, Any]] — one map per row, keyed by column name
val rows = Db.query("default", "SELECT id, text FROM todos ORDER BY id", [])

// Returns update count (Int)
Db.execute("default", "INSERT INTO todos(text) VALUES (?)", ["Buy milk"])
Db.execute("default", "DELETE FROM todos WHERE id = ?", [id.toInt])
```

Typed SQL helpers decode and encode rows through case-class field mappings. On
the compiler-free 2.1 standard VM/direct-ASM path, `derives RowCodec` consumes
portable `Mirror` metadata and the public `Db.query/insert/update[A]` API uses
the registered field order. JVM codegen retains its existing derived
`RowCodec[A]` implementation.

```scalascript
import scalascript.typeddata.RowCodec

case class Todo(id: Long, text: String, done: Boolean) derives RowCodec

val todos: List[Todo] =
  Db.query[Todo]("default", "SELECT id AS id, text AS text, done AS done FROM todos", [])

Db.insert("default", "todos", Todo(1, "Buy milk", false))
Db.update("default", "todos", "id", 1, Todo(1, "Buy oat milk", false))
```

`Db.insert` inserts every encoded field as a column. `Db.update` requires an
explicit key column and key value; the key column is excluded from the `SET`
list when present in the encoded row. Table and column names are validated as
plain SQL identifiers; values are still passed as JDBC bind parameters.

#### PostgreSQL LISTEN / NOTIFY

For Postgres, the **publish** side is plain SQL — `Db.query("db", "SELECT
pg_notify(?, ?)", [channel, payload])`. The **receive** side needs a connection
that stays open and is drained for notifications, which `Db.*` give via the
connection cached per database name:

```scala
Db.pgListen("default", "events")                              // LISTEN "events"
val _ = Db.query("default", "SELECT pg_notify(?, ?)", ["events", "hi"])
val notes = Db.getNotifications("default", 1000)              // block up to 1000 ms
for (n <- notes) println(n("channel") + ": " + n("payload"))  // {channel, payload, pid}
Db.unlisten("default", "events")
```

`Db.getNotifications("db"[, timeoutMs])` returns each pending notification as a
`Map { channel, payload, pid }`; `timeoutMs` blocks up to that long waiting for
one (omitted / `0` = non-blocking, returns whatever is buffered). These are
PostgreSQL-only — calling `Db.getNotifications` on a non-Postgres connection
raises a clear error. The channel name in `pgListen`/`unlisten` is quoted
(case-exact, matching the `pg_notify` string), so no injection through the name.
Example: [`examples/pg-listen-notify.ssc`](../examples/pg-listen-notify.ssc)
(requires a running Postgres).

For JVM `RowCodec[A]` code, explicit row codecs can use
`RowFieldSpec[A]` metadata to keep storage schemas stable while Scala field
names evolve: canonical column names for writes, aliases for reads from older
columns, defaults for missing columns, key-column markers, and opt-in unknown
column rejection. Derived JVM row codecs can express the same schema metadata
with annotations:

```scalascript
import scalascript.typeddata.{RowCodec, aliases, fieldName, key, rejectUnknown}

@rejectUnknown
case class Todo(
  @key id: Long,
  @fieldName("text") @aliases("title") label: String,
  done: Boolean = false
) derives RowCodec
```

`@fieldName` is the canonical column written by `Db.insert/update`, `@aliases`
are accepted while decoding older column names, default parameters become
missing-column defaults, and `@rejectUnknown` rejects extra columns. JDBC column
lookup is case-insensitive. The same annotations are honored on the interpreter
typed SQL path for `Db.query/insert/update[A]`; explicit `RowFieldSpec[A]`
values remain a JVM typeclass API.

The interpreter typed SQL path can also read the same mapping metadata from
front-matter `schemas:`. This is useful when the `.ssc` module should describe
external storage names without decorating the case class:

```yaml
schemas:
  Todo:
    rejectUnknown: true
    fields:
      id:
        key: true
      label:
        name: text
        aliases: [title]
      done:
        default: false
```

On the interpreter path, front-matter field entries override annotation/default
metadata for the same type and field. JVM `RowCodec` derivation still uses
annotations or explicit `RowFieldSpec[A]` values, because plain Scala typeclass
derivation does not read module front-matter.

The first argument is the connection name from `databases:`.  Bind parameters are passed as a list — use `[]` for no parameters.

### Electron desktop SQL

Electron desktop bundles run the app inside a Chromium renderer loaded from
`file://`. The renderer cannot use Node `fs`, so database file access is routed
through the Electron main/preload bridge. Current behavior:

- Electron bundles with `databases:` expose `window.__sscElectron.db` through
  preload and execute SQLite in the main process.
- `sqlite::memory:` and `sqlite:` are in-memory main-process databases.
- `sqlite:<path>` persists under `app.getPath("userData")`.
- Browser/web builds still use the localStorage-backed fallback when no
  Electron bridge exists.

For details and the active bridge rollout, see [`electron-sql.md`](electron-sql.md)
and [`electron-persistence-bridge.md`](electron-persistence-bridge.md).

For apps that should run backend routes on the JVM and use Electron only as the
desktop client, see the planned
[`electron-jvm-rest-backend.md`](electron-jvm-rest-backend.md) mode.

The first explicit Electron/JVM REST dev mode is available as
`ssc run --frontend electron --backend jvm-rest app.ssc`: the CLI starts the JVM
backend, waits for the source `serve(...)` port, launches Electron, and forwards
relative renderer `fetch("/...")` calls to the JVM backend. Plain `ssc run app.ssc`
now chooses the same split mode when the source declares `frontend: electron`,
has backend routes, and calls `serve(...)`. `ssc run --target desktop-jvm app.ssc`
is available as the explicit shorthand for the same Electron + JVM REST dev
supervisor. Distributed server/client commands and non-Electron full-stack
supervision are still planned.

The first server-only split command is also available:
`ssc run --mode server --backend jvm app.ssc` starts only the JVM backend/server
side from the same source file. When the source contains `serve(port)`, the CLI
prints the local backend URL and detected LAN backend URL candidates before
starting the JVM process. Client-only `--mode client --server-url ...`
support has started with Electron:
`ssc run --mode client --frontend electron --server-url http://server:8080 app.ssc`
starts only the Electron client and wires renderer `fetch("/...")` calls to the
configured backend URL. Browser frontends are also supported:
`ssc run --mode client --frontend react --server-url http://server:8080 app.ssc`
generates a SPA, serves it from a local preview server, prints the local
frontend URL, detected LAN frontend URLs, and backend URL, then opens the local
URL in the system browser only when `--open-browser` is set. The default is
`--no-open-browser`; the same behavior can be configured in front matter with
`open-browser: true` or `open-browser: false`. `emit-spa --server-url
http://server:8080 app.ssc` prints the same backend URL injection in standalone
HTML form.

Web client preview accepts `--host <addr>` and `--port <n>` to choose the bind
address and port printed in those URLs. The same defaults can live in front
matter as `host:` / `bind-host:` / `bindHost:` and `port:`, with command-line
flags taking precedence. Server-only JVM mode accepts the same options:
`--port` overrides a simple literal `serve(port)` before launch, while `--host`
currently affects URL logging only until the JVM HTTP server SPI supports an
explicit bind host.

### Planned full-stack and client storage features

The features in this subsection are **planned and not implemented yet**. Their
specs are included here so the intended direction is visible, but current
releases should not be expected to expose these APIs or front-matter keys.

**Planned, not implemented yet: split client/server databases.** Full-stack
clients may keep their own local SQL database for cache, offline state, drafts,
or preferences while the JVM backend owns the authoritative server database. The
planned split-mode contract names these as separate `databases:` entries, for
example `server` (`side: server`) and `localCache` (`side: client`).

**Planned, partially implemented: monolithic in-process full-stack transport.**
Some target pairs can eventually run frontend and backend logic inside one
runtime process. The CLI now recognizes `--transport http|in-process` and front
matter `fullstack.transport` / `transport`. Interpreter route tests can already
dispatch through `InProcessBackendTransport` without opening a socket, and
`ssc run-jvm --frontend swing` now runs the Swing UI in the same JVM process
instead of launching nested `scala-cli`. In generated JVM/Swing apps,
`fetchAction` / `fetchActionClear` button handlers can dispatch to backend
routes through a generated JVM `BackendTransport` without opening an HTTP
socket. Swing `dataTable` can also load rows and delete them through the same
transport adapter. Generated JVM/Swing typed clients use that transport too.
Broader frontend client selection is still planned. Distributed clients, browser-to-JVM apps, and
server-only/client-only split commands remain HTTP/REST. See
[`fullstack-in-process-transport.md`](fullstack-in-process-transport.md).

**JVM desktop frontend (Swing).** `ssc run-jvm --frontend swing app.ssc`
compiles through the JVM backend and launches the JDK-only Swing runtime in the
current JVM process (JDK 11+ required).  Plain `ssc run --frontend swing` stays
on the interpreter path and currently reports that Swing interpreter intrinsics
are planned.  `ssc run-jvm --frontend swing --transport in-process` is accepted
as the monolithic JVM mode.  Swing `fetchAction` / `fetchActionClear` handlers
call generated JVM backend routes in the same process via a generated
`BackendTransport`; `dataTable` loads rows and deletes through the same
dispatcher.  Generated typed route clients also use this transport.

*Window icon.* Set `app-icon: path/to/icon.png` in front matter; JvmGen
emits it as `iconPath = Some(...)` in the generated `SwingRuntime.Options` and
loads it via `ImageIcon` at startup.

*Graceful shutdown.* Pass `onShutdown = Some(() => ...)` in
`SwingRuntime.Options` to register a cleanup hook (stop background servers,
databases, actor systems) that runs before the window is disposed.  Without it,
the frame uses `EXIT_ON_CLOSE`.

*Examples.* The static toolkit subset (text, buttons, text fields, checkboxes,
stacks, spacers, dividers, scroll views, styles, local signals) is demonstrated
by
[`examples/frontend/swing-hello/swing-hello.ssc`](../examples/frontend/swing-hello/swing-hello.ssc).
The no-socket full-stack example is
[`examples/frontend/swing-fullstack/`](../examples/frontend/swing-fullstack/).
A typed-client variant is
[`examples/frontend/swing-typed-client/`](../examples/frontend/swing-typed-client/).

*Packaging.* After `ssc run-jvm` confirms the app, produce a fat JAR with
`scala-cli package --assembly app.ssc -o app.jar`, then wrap it with
`jpackage` (JDK 14+) for a platform installer (`.dmg`, `.msi`, `.deb`).
Automated `ssc build --target desktop-jvm` is planned but not yet implemented.

JavaFX and Compose Desktop remain future adapters. See
[`jvm-desktop-frontend.md`](jvm-desktop-frontend.md).

**Planned, partially implemented: typed route clients.** Front matter can
declare typed client endpoint metadata with `apiClients:` / `api-clients:`; the
parser stores method/path/request/response type names in the AST, and JVM
codegen preserves them as metadata. When no manual client metadata is present,
ScalaScript derives an `Api` client from `route(...)`, `mount(...)`, and
front-matter `routes:` declarations; a non-body route with one path parameter
becomes a callable method such as `awaitClient(Api.getApiItemsById("42"))`.
In JVM/Swing mode, codegen now emits
callable client methods that encode request values, dispatch through the
same generated `BackendTransport` used by Swing fetch helpers, and decode
typed JSON responses. JS/browser codegen now emits Promise-returning HTTP
client objects over `fetch`; when a bundle is produced with `emit-spa
--server-url` or client mode, relative client calls use the injected
`globalThis.__sscBackendBaseUrl` to reach the JVM backend. Electron JVM REST
dev mode uses the same generated HTTP clients in its renderer bundle.
[`examples/frontend/typed-client-distributed/`](../examples/frontend/typed-client-distributed/)
shows the same `.ssc` source running as a JVM backend on one machine and as a
React/Electron client on another via `--server-url`. Client-side ScalaScript
can use `awaitClient(Messages.list())`; JS codegen lowers it to `await
Messages.list()` and wraps the client bundle in an async top-level function.
Mark client-only ScalaScript blocks with `@side=client` when the same source is
also compiled as a JVM server. Generated clients now route request encoding and
response decoding through a typed JSON codec facade. In JVM/Swing mode, that
facade now uses `scalascript.typeddata.JsonCodec[T]`, including derived
case-class codecs; JS/browser/Electron clients pass request/response type names
through the same facade and use generated runtime codec metadata to rebuild
known case-class/enum response shapes. See
[`typed-route-clients.md`](typed-route-clients.md).

**Partially implemented: browser client storage APIs.** Client frontends may use
standard browser storage when SQL is the wrong shape for the data. JS/browser
and Electron client code can use `IndexedDb.store[A]("store")` for Promise-based
typed local object storage, awaited with `awaitClient(...)`. Browser/Electron
clients use native IndexedDB; Node/test runs use a lightweight fallback. Planned
client-only APIs still cover `localStorage` for tiny string settings,
`sessionStorage` for per-window temporary state, the Cache API for HTTP response
caching, and OPFS for origin-private files or browser-local SQLite/Wasm storage.
These APIs belong to the frontend side of split apps; server-side references
should fail at build time.

**Partially implemented: client/server object store sync.** For IndexedDB-shaped
data that must also live on the server, the planned model is a paired
client/server object store. The client keeps objects in IndexedDB; the JVM
backend can now keep the authoritative copy in an `ObjectStore` backed by a
simple JDBC JSON table. JVM code can call
`ObjectStore.put/get/all/delete/changes[A](dbName, store, ...)` using
`ObjectCodec[A]`. JVM codegen now generates typed REST sync endpoints for
front-matter entries such as:

```yaml
objectStores:
  drafts:
    type: Draft
    sync: client-server
    database: default
    key: id
```

This exposes `GET /__ssc/sync/drafts/changes?since=<cursor>&limit=<n>` and
`POST /__ssc/sync/drafts/push` over the server ObjectStore. Browser/Electron
clients can use `awaitClient(Sync.pull[Draft]("drafts", "app"))` and
`awaitClient(Sync.push[Draft]("drafts", "app"))` to synchronize the local
`IndexedDb.store[A]` with those endpoints. Use `Sync.put[A]` and
`Sync.remove[A]` when local edits should be queued durably before the next
push. `Sync.conflicts("drafts", "app")` lists persisted conflicts, and
`Sync.resolve[A]("drafts", key, "server" | "client" | "drop", "app")` resolves
one conflict explicitly. On the JVM backend, `objectStores.<name>.conflict`
also supports automatic `manual`, `server-wins`, and `client-wins` push
policies.

**Sync UI helpers.** `Sync.sync[A]("store", "db")` combines push + pull into
one async call. `Sync.status("store", "db")` returns a synchronous plain object
with five fields useful for driving sync badges and indicators:

| Field | Type | Description |
|---|---|---|
| `pending` | Int | Number of queued mutations not yet pushed |
| `conflicts` | Int | Number of unresolved conflicts |
| `lastPulled` | Long\|null | Epoch-ms of last successful `pull` (null if never) |
| `lastPushed` | Long\|null | Epoch-ms of last successful `push` (null if never) |
| `isSyncing` | Boolean | `true` while a `push`/`pull`/`sync` is in flight |

`Sync.isOnline` is a boolean property reflecting `navigator.onLine` in browsers
(`true` in Node/tests where the property is absent). `Sync.isSyncing("store",
"db")` returns `true` while any async sync operation is running for that store.
See [`client-server-object-store.md`](client-server-object-store.md) and
`examples/sync-todo.ssc` for the full API.

**Planned, partially implemented: graph storage.** Graph-shaped data is planned
as a separate persistence family. Property graphs cover vertices, edges, labels,
properties, and traversal-heavy domains; RDF graphs cover triples/quads, linked
data, ontologies, and SPARQL. The first JVM runtime slices are available in
`backend/graph`: `GraphRuntime.inMemory()`, `GraphRuntime.tinkerGraph()`, and
`GraphRuntime.rdf4jMemory()` store typed vertices, edges, RDF subjects, and
triples through the `VertexCodec`, `EdgeCodec`, and `RdfCodec` mapping layer;
`GraphRuntime.sparqlSelect()` evaluates RDF4J SPARQL `SELECT` queries and
returns binding rows as `Map[String, RdfNode]`. JVM codegen now parses
`graphs:` front matter and emits a typed `.ssc` `Graph.*` facade for declared
in-memory, `embedded-tinkergraph`, and `rdf4j-memory` stores plus
`Sparql.select` for RDF4J-backed stores; the interpreter loads
`runtime/std/graph-plugin` and provides the portable in-memory facade over
runtime case-class values.
Production adapters such as Neo4j/Cypher, JanusGraph/TinkerPop providers, and
RDF4J-compatible servers remain planned. See
[`graph-storage.md`](graph-storage.md).

**Planned, partially implemented: typed mapping across stores.** Typed mapping
across stores is planned as a shared codec layer rather than one universal ORM.
The first runtime foundation now lives in `backend/typed-data`: generated
JVM/Swing and JS typed route clients share the same typed JSON facade names for
request encoding and response decoding, while full user-defined/derived codecs
remain planned. The same module now also exposes the first explicit Scala API:
`JsonValue`, `DecodeError`, `Codec[A, Repr]`, `JsonCodec[A]`, primitive/list/
option instances, object-codec helpers for manually mapping case classes, and
`derives JsonCodec` support for case classes and sealed ADTs. ADTs encode with
an explicit `"$type"` discriminator and `"value"` payload object. Explicit
object codecs can use `JsonFieldSpec[A]` for renamed fields, aliases, default
values, key-field metadata, and unknown-field rejection. Derived JVM
`JsonCodec` product codecs can use `@fieldName`, `@aliases`, case-class default
parameters, `@key`, and `@rejectUnknown` for the same schema metadata.
The first `ObjectValue` / `ObjectFieldSpec[A]` / `ObjectCodec[A]` layer is also
available for portable IndexedDB/ObjectStore document shapes. It reuses
`JsonCodec` field values, supports explicit and derived case-class codecs, and
extracts stable object keys from `@key` fields.
Case classes and ADTs should derive codecs such as `JsonCodec`, `RowCodec`,
`ObjectCodec`, `VertexCodec`, `EdgeCodec`, `RdfCodec`, `DatasetCodec`, and
`SparkCodec`, then use backend-specific APIs at the query boundary.
`DatasetCodec[A]` is now available for local/MapReduce Dataset element
serialization. It derives from `JsonCodec[A]` by default and exposes
`encodeAll` / `decodeAll` helpers, so a JVM Dataset pipeline can map typed
values to stable `JsonValue` elements and decode them later without hand-written
adapters. It also exposes `encodePartition`, `decodePartition`,
`encodePartitions`, and `decodePartitions` helpers so distributed MapReduce
workers can move typed partition payloads through the same codec layer instead
of inventing a separate worker-only representation. `std/mapreduce` now also
exposes `runDistributedWire`, `WireProcessPartition`, and `WirePartitionResult`
for actor workers that exchange `DatasetWirePartition` payloads directly; named
handlers on that path operate on `JsonValue`, and callers decode back to domain
values with `DatasetCodec.decodePartitions[A]`. `runDistributedShuffleWire`
adds the same wire representation for coordinator-mediated `groupBy` /
`reduceByKey`: key and combine handlers operate on `JsonValue`, and reduce
outputs are returned as `DatasetWirePartition` payloads. `DistributedDataset`
adds typed `encode/decode[A]` helpers for that boundary, plus
`run/runShuffle[A, B]` wrappers that keep the actor-effect map and shuffle
calls behind the typed Dataset facade on the JVM generated path.
For the distributed binary wire milestone, `DatasetWire` also wraps
`DatasetWirePartition` in the shared `WireEnvelope` model and can encode/decode
the same logical partition as JSON, MsgPack, or CBOR. Large partitions can be
split at element boundaries with `encodePartitionChunks` and reassembled with
`decodePartitionChunks`. `runDistributedWire`, `runDistributedShuffleWire`, and
`DistributedDataset.run/runShuffle` accept `wireFormat = "json" | "msgpack" |
"cbor"`; JSON keeps the in-memory object-message fallback, while MsgPack/CBOR
send `DatasetWire` envelope bytes through partition, shuffle-bucket, and
key-result actor messages.
`SparkSchemaCodec[A]` is also available for Spark-like schema metadata: it
derives field names from `@fieldName`, preserves `@key`, maps primitive and
collection shapes to `SparkSchemaType`, and marks `Option[A]` fields nullable.
When a `SparkSchemaCodec[A]` is in scope, SparkGen typed readers use that
shared schema for `Dataset.fromJsonAs`, `fromCsvAs`, `fromParquetAs`, and
`fromTable`, then alias external column names back to the Scala case-class
field names before `.as[A]`.
`VertexCodec[A]`, `EdgeCodec[A]`, and `RdfCodec[A]` are now available for the
typed mapping layer: property graph values encode to `VertexValue` /
`EdgeValue`, and RDF values encode to `RdfValue` triples. `backend/graph` now
adds a portable graph runtime SPI and in-memory JVM backend for those encoded
values; `graphs:` front matter, generated JVM `Graph.*`, and interpreter
`Graph.*` can use in-memory graph stores today. Full database adapters are
still planned. The first
`RowValue` / `RowValueCodec[A]` / `RowCodec[A]` API is now available for simple
case-class row maps with primitive and nullable columns. Explicit JVM row codecs
can use `RowFieldSpec[A]` for renamed columns, aliases, defaults, key metadata,
case-insensitive lookup, and unknown-column rejection; derived JVM row codecs can
use the same schema annotations as `JsonCodec`. `SqlRuntime.query[A]` can decode
JDBC rows through `RowCodec[A]`; `SqlRuntime.insert/update[A]` encode typed
values through the same codec. The interpreter and JVM codegen paths
expose typed `Db.query/insert/update[A]` for programmatic SQL reads and writes.
This keeps SQL, IndexedDB, ObjectStore sync, property graphs, RDF, MapReduce,
and Spark convenient without hiding their different query models. Existing
Spark support remains available today; deeper Spark encoder/schema convergence
with the shared mapping annotations is still planned. See
[`data-mapping.md`](data-mapping.md).

### `transaction` fenced blocks

A ` ```transaction ``` ` fenced block runs multiple `;`-separated SQL statements
atomically in one JDBC transaction.  If any statement throws, all prior statements
are rolled back.

````ssc
```transaction
UPDATE accounts SET balance = balance - ${amount} WHERE id = ${fromId};
UPDATE accounts SET balance = balance + ${amount} WHERE id = ${toId}
```
````

- Statements are separated by `;`.  A trailing `;` is ignored.
- `${expr}` bind parameters work exactly like in ` ```sql ``` ` blocks — safe,
  `PreparedStatement`-based, no string substitution.
- `$$` is an escaped literal `$`.
- The block's return value is the result of the **last** statement: an `Int`
  (affected-row count) for DML, or `List[Map[String, Any]]` for a SELECT.
- Target the block at a named database with `@db=name`:

````ssc
```transaction @db=payments
INSERT INTO events (type, amount) VALUES ('debit', ${amount});
UPDATE balances SET total = total - ${amount} WHERE user_id = ${userId}
```
````

Connections come from `databases:` front-matter; the default name is `"default"`.

### `xml` fenced blocks

A ` ```xml ``` ` fenced block is parsed as well-formed XML 1.0 and bound to the
section identifier as a `Markup.Doc` value (from `markup-core`).

````ssc
## Invoice

```scala
val company = "Acme & Sons"
val amount  = 1200
```

```xml
<invoice>
  <company>${company}</company>
  <amount>${amount}</amount>
</invoice>
```
````

After execution `Invoice.xml` is a `Value.MarkupV(doc)` where `doc` is a
`scalascript.markup.Markup.Doc`.

**Interpolation and escaping:**
- `${expr}` evaluates the expression in the surrounding ScalaScript scope.
- The result is XML-escaped before being embedded: `<` → `&lt;`, `&` → `&amp;`,
  `>` → `&gt;`, `"` → `&quot;`, `'` → `&apos;`.
- The escaped content is then parsed by `PureMarkupCodec`, so entity references
  are decoded back into the text nodes of the resulting `Markup.Doc`.
  The net effect is safe: a string like `"A & B <Corp>"` becomes text content
  `A & B <Corp>` without injecting child elements.

**Parse errors:** a malformed `xml` block throws `InterpretError` with the line
and column from `PureMarkupCodec`.

**Section binding:** the block is bound as `<sectionIdent>.xml` — the same
convention as `.html`, `.css`, and `.sql`.

### XSLT transformation (`v1.56`)

`MarkupCodec.transform` applies an XSLT 1.0 stylesheet to a `Markup.Doc` and
returns `Either[TransformError, Markup.Doc]`.  On the JVM / interpreter backend
the transform is delegated to `XsltTransformer`, which uses
`javax.xml.transform.TransformerFactory`.  Other backends return
`Left(TransformError("XSLT not supported by this codec"))` by default.

```scala
import scalascript.markup.*

val source = xml"<catalog><book><title>Scala 3</title></book></catalog>"

val xslt = """<?xml version="1.0"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:param name="currency">USD</xsl:param>
  <xsl:template match="/">
    <html><body>
      <xsl:for-each select="catalog/book">
        <p><xsl:value-of select="title"/> — <xsl:value-of select="$currency"/></p>
      </xsl:for-each>
    </body></html>
  </xsl:template>
</xsl:stylesheet>"""

MarkupCodec.default.transform(source, xslt, Map("currency" -> "EUR")) match
  case Right(doc) => println(PureMarkupCodec.serialize(doc, SerializeOpts(omitXmlDecl = true)))
  case Left(err)  => println(s"Error: ${err.message}")
```

**Parameters** — top-level `<xsl:param>` values are supplied via
`params: Map[String, String]` (third argument, defaults to `Map.empty`).

**Error handling** — any of the following returns `Left(TransformError(...))`:
- Malformed or non-XSL stylesheet document
- Transform engine error (e.g. undefined variable reference)
- XSLT output that is not valid XML (use `<xsl:output method="xml"/>` to ensure this)

**Empty output** — stylesheets that produce no element content (e.g. a stylesheet
whose only template is `<xsl:template match="/"/>`) return a synthetic
`Markup.Doc` with a single `<empty/>` root element rather than an error.

**Capability gate** — `.transform(` calls on `Markup.Doc` values are detected by
`CapabilityCheck` and require `Feature.Xslt`.  Backends that don't declare it
(JS, Wasm, Native) reject the program at the capability-check stage.

**Example** — see `examples/xslt-transform.ssc` for a complete working demo.

### REST API + SQLite example

A complete todo list with SQLite persistence and a JSON REST API:

````ssc
---
name: todo-api
databases:
  default:
    url: "jdbc:sqlite:./todos.db"
---

# Todo API

```sql
CREATE TABLE IF NOT EXISTS todos (
  id   INTEGER PRIMARY KEY AUTOINCREMENT,
  text TEXT    NOT NULL
)
```

```scalascript
route("GET", "/api/todos") { req =>
  val rows = Db.query("default", "SELECT id, text FROM todos ORDER BY id", [])
  val body = "[" + rows.map(r => s"""{"id":${r("id")},"text":"${r("text")}"}""").mkString(",") + "]"
  Response.text(body)
}

route("POST", "/api/todos") { req =>
  val text = req.body.trim
  if text.isEmpty then Response.status(400, "empty body")
  else
    Db.execute("default", "INSERT INTO todos(text) VALUES (?)", [text])
    Response.status(201, "created")
}

route("POST", "/api/todos/delete") { req =>
  Db.execute("default", "DELETE FROM todos WHERE id = ?", [req.body.trim.toInt])
  Response.status(204)
}

serve(8080)
```
````

The database file `todos.db` is created on first run and survives restarts.

---

## 6.2. Secret Management

### Built-in `${scheme:ref}` resolution

Secret references in `databases:` connection fields are expanded at connection-open time — never stored in plaintext:

| Scheme | Reference | Resolved from |
|--------|-----------|--------------|
| `${env:NAME}` | `${env:DB_PASSWORD}` | process environment variable |
| `${file:PATH}` | `${file:/run/secrets/db_pw}` | file contents, whitespace-trimmed (Docker / K8s secret volumes) |
| `${sops:key.path}` | `${sops:db.prod.password}` | YAML piped to stdin via `sops -d` |

Multiple references in one string are all expanded:

```yaml
url: "jdbc:postgresql://${env:DB_HOST}:${env:DB_PORT}/myapp"
```

### Piping secrets via sops

```bash
sops -d secrets.enc.yaml | ssc myapp.ssc
```

`ssc` detects a piped stdin at startup, parses the decrypted YAML, and flattens nested keys to dot-separated paths:

```yaml
# secrets.enc.yaml (decrypted output)
db:
  prod:
    password: "s3cr3t"
TOP_SECRET: "value"
```

Accessible as `${sops:db.prod.password}` and `${sops:TOP_SECRET}` in any `databases:` field.  List elements are keyed by index (`hosts.0`, `hosts.1`, …).

```yaml
databases:
  prod:
    url:      "jdbc:postgresql://db:5432/myapp"
    user:     "${sops:db.prod.user}"
    password: "${sops:db.prod.password}"
```

### Docker / Kubernetes secret files

```yaml
databases:
  prod:
    password: "${file:/run/secrets/db_password}"
```

The file is read and whitespace-stripped at connection time.  The path is typically a tmpfs mount injected by Docker Compose `secrets:` or a K8s volume.

### `SecretResolver` SPI

Custom backends (HashiCorp Vault, AWS Secrets Manager, GCP Secret Manager, Doppler, …) are implemented as `SecretResolver` plugins loaded via `java.util.ServiceLoader`:

```scala
// In a plugin JAR:
class VaultResolver extends scalascript.sql.SecretResolver:
  val scheme = "vault"
  def resolve(ref: String): String =
    val (path, field) = ref.span(_ != '#')
    VaultClient.read(path).data(field.drop(1))

// META-INF/services/scalascript.sql.SecretResolver:
// com.example.VaultResolver
```

Usage in front-matter: `${vault:secret/myapp/db#password}`

See [`secret-resolvers.md`](../secret-resolvers.md) for the full spec — Vault, AWS SM, GCP SM, Azure KV, Doppler, 1Password, pass.

---

## 7. WebSocket

### Server

```scalascript
onWebSocket("/chat") { ws =>
  println(s"Connected: ${ws.id}")
  while !ws.isClosed do
    ws.recv() match
      case Some(msg) =>
        println(s"Received: $msg")
        ws.send(s"echo: $msg")
      case None => ()
  println(s"Disconnected: ${ws.id}")
}

serve(8080)
```

WebSocket options:

```scalascript
onWebSocket("/game", maxConnections = 100, rateLimit = 10) { ws => ... }
```

Rate limiting: `rateLimit` = max messages per second per connection.

### Broadcasting to Rooms

```scalascript
val rooms = mutable.Map[String, List[WebSocket]]()

onWebSocket("/room/:name") { ws =>
  val room = ws.params("name")
  rooms.updateWith(room)(_.map(ws :: _).orElse(Some(List(ws))))

  while !ws.isClosed do
    ws.recv() match
      case Some(msg) =>
        rooms.getOrElse(room, Nil)
          .filter(_ != ws)
          .foreach(_.send(msg))
      case None => ()

  rooms.updateWith(room)(_.map(_.filter(_ != ws)))
}
```

### Client

```scalascript
wsConnect("ws://localhost:8080/chat") { ws =>
  ws.send("hello")
  ws.recv() match
    case Some(reply) => println(s"Server said: $reply")
    case None        => println("Disconnected")
}

// TLS
wsConnect("wss://secure.example.com/chat") { ws => ... }
```

### Async WebSocket Receive

```scalascript
runAsync {
  val msg = Async.recvFrom(ws)
  process(msg)
}
```

---

## 8. Actors

The ScalaScript 2.1 standard launcher provides local actors without the v1
interpreter or compatibility bridge. Each actor has a FIFO mailbox and a JDK
21 virtual thread; `runActors` returns when the local scope is quiescent, while
child failures are propagated. Plain and timed `receive`, `self`, `pid ! msg`,
and `exit(pid, reason)` behave identically on the native VM, direct ASM, and
`build-jvm` paths. Bare `exit(code: Int)` remains the process-exit overload;
the standard providers dispatch the two shapes explicitly.

Typed `ActorRef` fields, `tell`, `publishAs`, `globalWhereis`, named behaviors,
and `spawnRemote` are available as deterministic process-local loopback. Actor
network transport, cluster membership, links/monitors, supervision trees,
durable mailboxes, and timers still require the explicit tools/compatibility or
an advanced provider; the standard launcher never falls back to them
transparently. See
[`actors-pingpong.ssc`](../examples/actors-pingpong.ssc) and
[`actors-typed-remote-spawn.ssc`](../examples/actors-typed-remote-spawn.ssc).

### Basic Actors

```scalascript
runActors {
  val counter = spawn {
    var count = 0
    while true do
      receive {
        case "increment" => count += 1
        case "get"       => self() ! count
        case "stop"      => exit(self(), "normal")
      }
  }

  counter ! "increment"
  counter ! "increment"
  counter ! "get"

  receive {
    case n: Int => println(s"Count: $n")   // 2
  }
}
```

### Supervision

```scalascript
runActors {
  val worker = spawn {
    receive {
      case "crash" => throw new RuntimeException("boom")
      case msg     => println(s"Working on: $msg")
    }
  }

  // Supervisor links to the worker
  val supervisor = spawn {
    link(worker)
    trapExit(true)
    receive {
      case ("EXIT", pid, reason) =>
        println(s"Worker $pid died: $reason")
        // restart or give up
    }
  }

  worker ! "crash"
}
```

### Distributed Actors

```scalascript
// Node A — start cluster node
clusterStart("node-a", port = 4242)

// Node B — connect and discover
val cluster = clusterConnect("ws://node-a:4242")

// Spawn on a remote node
val remote = cluster.spawn("node-a") {
  receive {
    case msg => println(s"Remote got: $msg")
  }
}

remote ! "hello from node-b"

// List cluster members
cluster.members.foreach(m => println(m.id))
```

---

## 9. Data Processing

### Dataset API

```scalascript
val ds = Dataset.from(List(
  ("Alice", 85), ("Bob", 92), ("Alice", 78), ("Bob", 88)
))

// Transformations (lazy)
val byName = ds.groupBy(_._1)
val avgScore = byName.map { case (name, records) =>
  (name, records.map(_._2).sum.toDouble / records.length)
}

// Execute
avgScore.runLocal().foreach(println)
// (Alice, 81.5)
// (Bob, 90.0)
```

### Aggregations

```scalascript
val nums = Dataset.from(1 to 1000)
nums.top(5)          // List(1000, 999, 998, 997, 996)
nums.count()         // 1000
nums.sum()           // 500500
nums.avg()           // 500.5
nums.min()           // 1
nums.max()           // 1000
nums.countByValue()  // Map(1 -> 1, 2 -> 1, ...)
```

### MapReduce

```scalascript
val words = Dataset.fromFile("corpus.txt")
  .flatMap(line => line.split("\\s+").toList)
  .map(_.toLowerCase)

val wordCounts = words
  .reduceByKey(identity)((a, b) => a + b)
  .sortBy(_._2)
  .collect()
```

### Parallel and Distributed

```scalascript
// Local multi-core
val result = bigDataset.map(expensiveTransform).runParallel()

// Distributed (requires running actor cluster)
val result = bigDataset.runDistributed(clusterNodes,
  handler = "worker",
  failurePolicy = FailurePolicy.RetryOnce
)
```

For offline MapReduce examples and local conformance runs, use
`localLoopbackCluster(Node(...), ...)` from `std.mapreduce`. It creates local
actor workers that handle both map partitions and shuffle key partitions.
`Cluster.connect(...)` remains the remote-node API and does not silently fall
back to local workers.

### File I/O

```scalascript
val ds = Dataset.fromFile("data.csv")    // one item per line
ds.saveToFile("output.csv")
ds.toList
ds.toMap(_.split(",").head, _.split(",").last)
```

---

## 10. DSL Authoring

### Parser Combinators

```scalascript
// Primitive parsers
val digit  = Parser.satisfy(_.isDigit)
val letter = Parser.satisfy(_.isLetter)
val ws     = Parser.whitespace

// Build up
val integer: Parser[Int] =
  digit.rep1.map(_.mkString.toInt)

val ident: Parser[String] =
  (letter ~ (letter | digit | char('_')).rep)
    .map { case (h, t) => (h +: t).mkString }

// Combinators
val pair   = integer <~ ws ~ integer   // both, skip whitespace
val choice = integer | ident           // first match wins
val items  = integer.sep(char(','))    // comma-separated list

// Sequencing operators
val a ~> b   // keep right
val a <~ b   // keep left
val a ~ b    // keep both as pair
val a >> b   // sequence, keep right (flatMap)
```

### Error Recovery

```scalascript
// Recover: skip until delimiter found
val stmt = expr.recoverUntil(char(';'))

// Error node: produce a placeholder, continue
val safeExpr = expr.errorNode(ErrorExpr("bad input"))

// Parse all: accumulate errors, don't stop at first failure
parseAll(stmts, input) match
  case ParseResult.Ok(asts, warnings) => ...
  case ParseResult.Err(errors)        => ...
```

### Indentation-Aware Parsing

```scalascript
val block: Parser[List[Stmt]] = withIndent {
  sameIndent(stmt).rep
}

val ifStmt = for
  _     <- keyword("if")
  cond  <- expr
  body  <- block
yield IfStmt(cond, body)
```

### Multi-Pass Compilation

```scalascript
// Define passes
val parse: Pass[String, Ast]          = Pass.of(parseSource)
val typecheck: Pass[Ast, TypedAst]    = Pass.of(checkTypes)
val optimize: Pass[TypedAst, TypedAst] = Pass.of(optimize)
val emit: Pass[TypedAst, String]       = Pass.of(generateCode)

// Chain
val compiler = parse andThen typecheck andThen optimize andThen emit
compiler.run(sourceCode) match
  case Right(code) => writeFile(code)
  case Left(errs)  => errs.foreach(println)

// Parallel passes (both run, results combined)
val analysis = typecheck parallel scopeAnalysis

// Recovery
val lenient = typecheck.recover { errs =>
  println(s"Type errors: ${errs.length}")
  partiallyTyped
}
```

### Tree Walkers

```scalascript
// Catamorphism — bottom-up fold
val eval: Ast => Value = cata[Ast, Value] {
  case Lit(n)        => Value.Int(n)
  case Add(l, r)     => Value.Int(l.asInt + r.asInt)
  case Mul(l, r)     => Value.Int(l.asInt * r.asInt)
  case If(c, t, e)   => if c.asBool then t else e
}

// Visitor
object Printer extends Visitor[Ast]:
  def visit(node: Ast): Unit = node match
    case Lit(n)    => print(n)
    case Add(l, r) => print("("); visit(l); print("+"); visit(r); print(")")
```

---

## 11. Module System

### Basic Imports

```markdown
<!-- Import all definitions from another file -->
[geometry](./geometry.ssc)

<!-- Selective import -->
[Circle, Rectangle](./shapes.ssc)

<!-- Aliased import -->
[Card as UICard](./ui/card.ssc)
[Card as ChartCard](./charts/card.ssc)

<!-- Multiple import links in one pure Markdown paragraph -->
[money, minorUnits](std/money.ssc) [Right, foldEither](std/either.ssc)
```

A Markdown paragraph is treated as imports when it contains only import links
and Markdown whitespace. Links embedded in prose stay prose, and `#...` links
remain internal cross-references.

#### Name conflicts across modules

If you import the same **function name** from two different modules (e.g.
`htmlEsc` from both `a.ssc` and `b.ssc`), the **last import wins** and the
compiler emits a one-time warning so the shadow is never silent:

```
[warn] 'htmlEsc' imported from 'b.ssc' shadows the 'htmlEsc' imported from 'a.ssc' — last import wins
```

To keep both, import one (or both) under an alias — `[htmlEsc as escA](a.ssc)`.
Re-importing the same name from the *same* module is idempotent and does not
warn. Full policy: [`specs/import-name-conflict-policy.md`](../specs/import-name-conflict-policy.md).

### Package Namespaces

```yaml
---
package: org.example.ui
---
```

```scalascript
// consumers access via: org.example.ui.Button.render(...)
object Button:
  def render(label: String): String = html"<button>$label</button>"
```

### URL Imports

```markdown
[utils](https://raw.github.com/org/lib/v2.0/utils.ssc)
```

Cached at `~/.cache/ssc/`. Set `SSC_NO_NETWORK=1` to disable fetching.

### Dependency Imports

```yaml
---
dependencies:
  mylib: https://cdn.example.com/mylib/v1.2
---
```

```markdown
[Widget](mylib://widget.ssc)
```

`ssc.lock` pins exact hashes for reproducible builds.

GitHub Release assets can be imported directly with the `github:` resolver:

```markdown
[Plugin](github:owner/repo@v1.0.0)
[Plugin](github:owner/repo@v1.0.0#dist/plugin.sscpkg)
[Plugin](github:owner/repo@v1.0.0 sha256:0123...)
```

The resolver uses the GitHub Releases API, selects the first `.sscpkg` asset
unless an asset path is specified after `#`, stores the artifact under
`~/.cache/scalascript/deps/github/`, and honors `GITHUB_TOKEN` when set.

Maven-shaped dependencies can be resolved through Coursier:

```markdown
[Lib](dep:com.example:demo:1.0.0)
[ScalaLib](dep:com.example::demo-scala:1.0.0)
[JitPackLib](jitpack:com.github.owner:repo:v1.0.0)
```

Legacy source imports such as `[Lib](dep:org.example/lib:1.2)` still use the
existing dep-sources chain. Coursier command lookup uses
`-Dssc.coursier.command`, `SSC_COURSIER`, `cs`, then `coursier`; additional
repositories can be supplied with `ssc.coursier.repositories` or
`SSC_COURSIER_REPOSITORIES`.

### Package Registry

```bash
ssc search json                     # search the public static registry
ssc search json --refresh           # force a registry re-download
ssc search json --offline           # use only the cached registry
ssc info io.scalascript/json        # show registry metadata and install snippet
ssc add io.scalascript/json         # add the latest registry version to a manifest
ssc add io.scalascript/json 1.0.0 --file app.ssc
```

The built-in public registry URL is
`https://sergey-scherbina.github.io/scalascript/packages.yaml`. The browseable
HTML index is served from `https://sergey-scherbina.github.io/scalascript/registry/`
(the Pages root serves the project landing page).
No custom domain is required for the MVP.

Use `--registry <url>` on registry commands for a local mirror or internal
registry. To make that default persistent:

```yaml
registry:
  url: https://internal.example/scalascript/packages.yaml
```

### Plugin System

```bash
ssc new my-app                    # app template
ssc new my-lib --template lib     # library template
ssc new my-plugin --template plugin
ssc new my-dsl --template dsl
ssc new my-web --template web-app
ssc new my-wasm --template wasm-app
ssc plugin install ./my-plugin.sscpkg   # install from file
ssc plugin install org.example/mylib   # install from registry
ssc plugin list
ssc plugin uninstall mylib
ssc plugin pack src/                    # create .sscpkg from source
```

The plugin template creates a minimal Backend SPI project, `.sscpkg` package
manifest, extern source declarations, and a GitHub Actions release workflow.
See `specs/community-plugins.md`.

Source-language plugins own fenced code-block tags. The CLI bundles
SourceLanguage plugins for `scala`, `html`, `css`, `javascript`/`js`, `xml`,
bind-aware `sql`, and bind-aware `transaction`; list visible tags with:

```bash
ssc --list-source-languages
```

Standard `scala` fences are runnable when the file contains only standard Scala
fences. In mixed `scalascript`/`scala` documents, standard `scala` fences are
documentation examples by default, so snippets in prose do not execute
accidentally. To run both languages in source order, opt in explicitly:

```yaml
runScalaFences: true
# aliases: run-scala-fences: true, scalaFences: runnable, scala-fences: runnable
```

Custom fenced DSLs use the same ServiceLoader-based `SourceLanguage` SPI.

The app, lib, plugin, dsl, web-app, and wasm-app templates are bundled into
`ssc.jar`. `releases/coursier.json`, `releases/homebrew/ssc.rb`, and
`releases/install.sh` are repository-side release inputs for standalone
install paths.

### Separate Compilation

```bash
ssc emit-interface lib.ssc             # .scim — interface for consumers
ssc emit-ir lib.ssc                    # .scir — normalized IR, including DocumentContent when present
ssc compile-jvm lib.ssc               # .scjvm — compiled JVM artifact
ssc compile-js lib.ssc                # .scjs — compiled JS artifact
ssc link --backend jvm artifacts/     # link into executable
ssc build --incremental src/          # build whole project
ssc deps app.ssc                      # show import closure
ssc info lib.scjvm                    # inspect artifact
```

Markdown content snapshots are preserved by `.scir` and `.sscc` artifacts when
the source module has one, so `std/content` helpers keep working for
artifact-backed current-module execution.

### sbt Integration

```scala
enablePlugins(ScalascriptInteropPlugin)

sscBinary := "ssc"
Compile / sscSourceDirectories := Seq((Compile / sourceDirectory).value / "scalascript")
```

`sbt compile` runs `sscCompile` first. The task scans `src/main/scalascript/`,
invokes `ssc build --incremental`, and writes artifacts under
`Compile / sscArtifactDir` (`target/ssc-artifacts` by default).

`sbt package` runs `sscLink` before packaging. The task links the generated
ScalaScript artifacts with `ssc link --backend <backend> --output <jar>` and
writes the linked JAR to `Compile / sscLinkedJar`
(`target/ssc-artifacts/linked.jar` by default).

`sbt test` runs `sscTest` first. The task scans `src/test/scalascript/`,
invokes `ssc test --output-format junit-xml`, writes results under
`Test / sscTestResultsDir` (`target/scala-*/ssc-test-results` by default),
and fails the sbt test run when the JUnit XML reports failures or errors.

Developer tools:

```bash
sbt sscRun path/to/app.ssc
sbt sscRepl
sbt sscWatch
sbt sscBspSetup
```

`sscBspSetup` writes `.bsp/scalascript.json` so BSP-aware editors can discover
ScalaScript diagnostics through `ssc lsp --project <project>`.

---

## 12. Testing

### Writing Tests

```scalascript
// ssc test file.ssc
test("addition works") {
  assert(1 + 1 == 2)
  assert(List(1, 2, 3).sum == 6)
}

test("pattern matching") {
  val x: Option[Int] = Some(42)
  x match
    case Some(n) => assert(n == 42)
    case None    => fail("expected Some")
}
```

### Testing Effects

Use test handlers to control effects deterministically:

```scalascript
test("logger captures output") {
  val logs = runTestLogger {
    Logger.info("hello")
    Logger.warn("uh oh")
  }
  assert(logs.map(_.message) == List("hello", "uh oh"))
}

test("random is deterministic") {
  val a = runSeededRandom(42)(Random.nextInt(100))
  val b = runSeededRandom(42)(Random.nextInt(100))
  assert(a == b)
}

test("state effect") {
  val (finalState, result) = runState(0) {
    State.modify[Int](_ + 1)
    State.modify[Int](_ + 1)
    State.get[Int]
  }
  assert(finalState == 2)
  assert(result == 2)
}

test("mock HTTP") {
  val result = runMockHttp(Map(
    "GET /api/user" -> """{"name":"Alice"}"""
  )) {
    Http.get("/api/user")
  }
  assert(result.contains("Alice"))
}
```

### Conformance Tests

Run the cross-backend conformance suite:

```bash
scala-cli conformance/run.sc
```

Each test runs on all three backends and compares output. Add new tests under `conformance/` with an expected output in `conformance/expected/`.

---

## 13. Formatting (`ssc fmt`)

`ssc fmt` is the canonical formatter for `.ssc` files. It normalises
front-matter key order, heading style, blank lines around code blocks,
and trailing whitespace — all without touching code block contents.

### Basic usage

```bash
ssc fmt file.ssc              # format in-place
ssc fmt src/*.ssc             # format multiple files
ssc fmt --check file.ssc      # exit non-zero if file needs formatting (CI)
ssc fmt --stdout file.ssc     # print formatted output to stdout
```

### What is normalised

**Front-matter** (YAML block between `---` delimiters):

- Key order: `name`, `version`, `description`, `main`, `package`, `exports`,
  `dependencies`, `routes`, then remaining keys alphabetically.
- Trailing spaces removed from every line.
- Exactly one blank line after the closing `---`.

**Markdown body**:

- Heading style: `##Title` becomes `## Title` (exactly one space after `#`s).
- Exactly one blank line before and after each fenced code block.
- Exactly one blank line before each heading (except the first line of the body).
- Trailing whitespace stripped from all lines.
- LF line endings; file ends with exactly one newline.

**Code block contents** are never touched — they are preserved verbatim.

**Shebang** (`#!/usr/bin/env ssc`) is kept at position 0 unchanged.

### CI integration

Add `ssc fmt --check` to your CI pipeline to enforce consistent formatting:

```yaml
- name: Check .ssc formatting
  run: find src -name '*.ssc' -exec ssc fmt --check {} +
```

The command exits 0 if every file is already formatted, non-zero otherwise
(with a message per offending file on stderr).

### Idempotency

`ssc fmt` is idempotent: running it twice produces the same result as running
it once. A file that passes `--check` will always pass `--check` again after
being formatted.

---

## 14. Apache Spark

The `spark` backend (`bin/ssc-spark` or `ssc run --backend spark`) compiles a `.ssc`
program to a Scala 3.7.1 `.sc` script with `//> using` directives and runs it
via `scala-cli` against Apache Spark 4.0.0. Spark JARs are resolved on demand
by Coursier — no `sbt`/`maven` setup needed.

### 13.1 Quick start

```ssc
---
name: spark-quick
backend: spark
spark-master: local[*]
---

# Spark quick start

```scalascript
case class User(id: Int, name: String, active: Boolean)

val users = Dataset.fromList(List(
  User(1, "Alice", true),
  User(2, "Bob",   false),
  User(3, "Carol", true)
))

users.filter(_.active).show()
println(s"active: ${users.filter(_.active).count()}")
```
```

Run:

```bash
bin/ssc-spark spark-quick.ssc
```

The first run downloads Spark 4 + transitive deps (~200 MB) through Coursier;
subsequent runs are instant.

### 13.2 Front-matter keys

| Key | Type | Purpose |
|-----|------|---------|
| `backend: spark` | string | Select the Spark backend |
| `spark-version` | string | Spark release (default `4.0.0`) |
| `spark-master` | string | `local[*]` / `local[N]` / `spark://...` / `yarn` / `k8s://...` |
| `spark-app-name` | string | Visible in Spark UI / history server / driver logs |
| `spark-config` | map | Ad-hoc `key: value` entries — each emits one `.config(k, v)` line |
| `spark-hive-metastore` | string | Thrift URI; triggers `.enableHiveSupport()` + `spark-hive_2.13` dep |
| `spark-warehouse` | string | Warehouse path; triggers `.enableHiveSupport()` + `spark-hive_2.13` dep |

CLI overrides take precedence over front-matter:

```bash
ssc-spark file.ssc --spark-master spark://prod:7077 --spark-version 4.0.0
```

### 13.3 SQL fenced blocks

A `sql` fenced block becomes a `val _sqlBlock_<N>: DataFrame` in the generated
`@main` scope. The enclosing section's identifier also produces a friendly
alias.

````markdown
## Active Users

```sql
SELECT id, name FROM users WHERE active = ${true} ORDER BY name
```

```scalascript
ActiveUsers.sql.show()        // section alias
_sqlBlock_0.show()            // C.1 internal name still works
```
````

`${expr}` interpolation lifts to `:bind<N>` parameters — the value is evaluated
as Scala in the surrounding scope and passed via Spark SQL's
parameterised `sql(text, args)`.

### 13.4 Case-class encoders (Phase E)

`Dataset[CaseClass]` works natively on Scala 3 + Spark `_2.13` thanks to a
mirror-based encoder shim emitted at the top of every Spark source. Supports:

- Primitive fields (`String`, `Int`, `Long`, `Double`, `Boolean`, …)
- `Option[T]`
- Nested case classes
- Collections — `Seq[T]`, `List[T]`, `Vector[T]`, `Set[T]`, `Array[T]`, `Map[K, V]`
- Tuples (`Tuple2`, `Tuple3`, …)
- `o.a.s.ml.linalg.Vector` (when MLlib is present — see §13.9)

```scalascript
case class Address(city: String, zip: String)
case class Person(id: Int, name: String, address: Option[Address], tags: Seq[String])

val people: Dataset[Person] = Dataset.fromList(...)
people.printSchema()    // case-class structure preserved
```

### 13.5 `@SqlFn` UDFs

A `def` annotated with `@SqlFn` inside a `scalascript` block becomes a Spark
UDF registered on the session catalog, callable from subsequent `sql` blocks.

```scalascript
@SqlFn
def upper(s: String): String = s.toUpperCase
```

```sql
SELECT upper(name) FROM users
```

The codegen strips the annotation and emits a Java `UDFN` wrapper with the
return DataType resolved from `SparkGen.SqlFnDataType` — TypeTag-free, so it
runs cleanly on Scala 3 + Spark `_2.13`.

### 13.6 Structured Streaming (Phase F)

Detection of `spark.readStream` / `.writeStream` auto-emits the streaming
imports and appends `spark.streams.active.headOption.foreach(_.awaitTermination())`
before `spark.stop()` when the user code doesn't already call `awaitTermination`.
`.format("kafka")` auto-emits the `spark-sql-kafka-0-10_2.13` dep.

```scalascript
val stream = spark.readStream.format("rate").option("rowsPerSecond", 1).load()
stream.writeStream.format("console").start()
// awaitTermination shim auto-emitted
```

Examples: `spark-streaming-rate-console.ssc`, `spark-streaming-file-parquet.ssc`,
`spark-streaming-kafka.ssc`. Full spec: [`specs/spark-streaming.md`](spark-streaming.md).

### 13.7 Delta Lake (Lakehouse L.2)

`.format("delta")` in user code triggers auto-emit of
`io.delta:delta-spark_2.13:3.2.0` plus the required Spark SQL extensions and
catalog configs.

```scalascript
df.write.format("delta").save("/tmp/users-delta")
val back = spark.read.format("delta").load("/tmp/users-delta")
```

Iceberg / Hudi are deferred until upstream publishes Spark 4 + `_2.13`
artifacts. Full spec: [`specs/spark-lakehouse.md`](spark-lakehouse.md).

### 13.8 Hive metastore + `@TempView` (Phase G)

Set `spark-hive-metastore:` and/or `spark-warehouse:` in front-matter to
enable Hive support — `.enableHiveSupport()` is added to the builder and the
`spark-hive_2.13` dep auto-emitted.

`@TempView("name")` on a `val` registers the Dataset as a temp view for
subsequent `sql` blocks:

```scalascript
@TempView("users")
val users = Dataset.fromParquetAs[User]("/data/users.parquet")
```

`Dataset.fromTable[T]("name")` is a typed reader over `spark.table(name).as[T]`
that composes with both temp views and Hive tables.

Typed Spark readers can also consume shared typed-data schema metadata:

```scalascript
import scalascript.typeddata.{SparkSchemaCodec, fieldName, key}

case class UserMetric(
  @key id: Long,
  @fieldName("display_name") name: String,
  score: Option[Double]
) derives SparkSchemaCodec

val metrics = Dataset.fromCsvAs[UserMetric](
  "/data/metrics.csv",
  "header" -> "true"
)
```

The reader schema uses the external `display_name` column, then projects it
back to `name` before Spark's `Encoder[UserMetric]` materializes the dataset.

Full spec: [`specs/spark-catalog.md`](spark-catalog.md).

### 13.9 MLlib (Phase M)

Any `import org.apache.spark.ml.*` triggers auto-emit of
`spark-mllib_2.13:<sparkVersion>` and the `Vector` encoder shim
(`o.a.s.ml.linalg.Vector` via the public `SQLDataTypes.VectorType` singleton).

```scalascript
import org.apache.spark.ml.classification.LogisticRegression
import org.apache.spark.ml.feature.{HashingTF, Tokenizer}
import org.apache.spark.ml.Pipeline

val pipeline = new Pipeline().setStages(Array(
  new Tokenizer().setInputCol("text").setOutputCol("words"),
  new HashingTF().setInputCol("words").setOutputCol("features"),
  new LogisticRegression()
))
val model = pipeline.fit(training)
model.save("/tmp/model")
val loaded = PipelineModel.load("/tmp/model")
```

Full spec: [`specs/spark-mllib.md`](spark-mllib.md).

### 13.10 Cluster submission (`ssc submit`)

For non-local clusters use `ssc submit` (fat JAR via `scala-cli package` +
`spark-submit`):

```bash
ssc submit file.ssc                                  # local
ssc submit file.ssc --spark-master spark://prod:7077 # standalone
ssc submit file.ssc --spark-master yarn -- --num-executors 8 --executor-memory 4g
ssc submit file.ssc --spark-master k8s://https://...  # Kubernetes
ssc submit file.ssc --dry-run                         # print the spark-submit invocation
```

### 13.11 Caveats

- **Scala 3.7.1 pin** — Scala 3.8.x has a TASTy-bridge regression that breaks
  Spark `_2.13` runtime reflection in `ExpressionEncoder`. The shim pins
  `//> using scala 3.7.1` automatically; if you override it, things break.
- **JDK 17+ `--add-opens`** — Spark needs reflective access to `sun.nio.ch`,
  `java.lang.reflect`, etc. The shim emits these as `//> using javaOpt`
  directives — `scala-cli run` works without extra args.
- **No TypeTag path** — `import spark.implicits._` is dropped from emit (its
  TypeTag-bound `newProductEncoder` poisons implicit search). Use
  `import SscSparkEncoders.given` instead — already injected.
- **Iceberg / Hudi blocked upstream** — see § 13.7. Watch tasks for
  `iceberg-spark-runtime-4.0_2.13` and `hudi-spark4.0-bundle_2.13`.

---

## 15. WebAssembly backend

`ssc emit-wasm file.ssc` lowers `scalascript`/`ssc` blocks to a WebAssembly
module (no Node.js / JVM at runtime). `sql` fenced blocks are supported via the
cross-backend SQL runtime (v1.27 Phase 5). Cross-backend semantics: identical
output to the interpreter and the JS / JVM backends for the same source.

`//> using dep` directives inside blocks are hoisted to the top of the
compilation unit and deduplicated, so you can pull in scalajs-dom or other
Scala.js libraries directly from a Wasm block:

```scalascript
//> using dep org.scala-js::scalajs-dom::2.8.0
import org.scalajs.dom.window
```

```bash
ssc emit-wasm examples/wasm-fibonacci.ssc -o out.wasm
examples/run-wasm.sh out.wasm
```

Examples: `wasm-fibonacci.ssc`, `wasm-sorting.ssc`, `wasm-matrix.ssc`,
`wasm-primes.ssc`, `wasm-collections.ssc`, `wasm-scalascript.ssc`
(Point geometry), `wasm-http.ssc` (HTTP Fetch via scalajs-dom).

### Algebraic effects on Wasm

Algebraic effects **compile and run** on the WASM backend. The effectful path
uses a pure-Scala effect runtime (`WasmEffectRuntime`) and `generateUserOnly`
codegen — there is no 300 KB preamble, and `backendWasm` depends only on
`backendJvm`. Supported within effects: arithmetic (`_binOp`), collection
higher-order functions (`_dispatch`), **multi-shot resume** (a continuation
resumed many times), and cross-module effects — all with no source changes
relative to the interpreter/JVM. An effectful `@main` (including a `String*`
parameter clause and a non-`Unit` return) is supported; a raw `Array[String]`
`@main` is rejected before scala-cli with a clear "use `String*`" diagnostic.

### `@wasm` extern FFI

`@wasm` externs bind to host functions, and cross-module inlining + macro
expansion run on the WASM path. (`@wasmExport` / `@wasmImport` are out of scope
by design.)

Full reference: [`specs/wasm-backend.md`](wasm-backend.md).

---

## 16. Frontend Framework SPI (v1.18 A)

Same `.ssc` UI source, four targets. Pick the backend per build; the source
stays identical.

| Backend id | Emits | Reactive primitive |
|-----------|-------|-------------------|
| `frontend-react` | React JSX + hooks | `useState` / `useEffect` |
| `frontend-vue` | Vue 3 SFC-equivalent | `ref` + render fn |
| `frontend-solid` | Solid components | `createSignal` / `createEffect` |
| `frontend-custom` | Minimal hand-written runtime | Built-in `Signal[T]` |

Shared reactive abstractions emit cleanly on all four:

- `Signal[T]` — observable value
- `ShowSignal(cond) { … }` — conditional render
- `ToggleSignal` — boolean toggle binding
- `ForSignal(items) { item => … }` — list render with keyed diff

```scalascript
val count = Signal(0)
button(onClick = () => count.set(count.get + 1)) { text("inc") }
ShowSignal(count.get > 5) { text("big") }
ForSignal(items) { item => li { text(item.title) } }
```

See [`specs/frontend-framework-spi-plan.md`](frontend-framework-spi-plan.md) and
[`specs/frontend-abstract-model.md`](frontend-abstract-model.md). Examples
under `examples/frontend/`.

---

## 17. Frontend Toolkit — `std/ui`

The `std/ui` toolkit lets you build reactive browser SPAs directly from a
`.ssc` script — no separate build step, no npm, no webpack.

- **One file** — routes, backend logic, and frontend UI in the same `.ssc`
- **React target** — emitted as React 18 hooks + JSX; `frontend: react` in front-matter
- **Theme-aware** — all widgets read design tokens from a `Theme` value
- **Fully reactive** — `Signal[T]` binds two-way to inputs, conditionals,
  text nodes, and REST fetch loops
- **Hot-reload** — `ssc serve myapp.ssc` hot-reloads on save

---

### 17.1 Architecture

Three layers, each with a single responsibility:

```
Widget constructors          lower()              serve() / emit()
(layout, input, display…)  ──────────►  View IR  ────────────────►  React SPA
return TkNode                            (pure)                       or bundle
```

1. **`TkNode`** — sealed ADT of case classes; pure data, no DOM, no extern.
   Widget constructors (`vstack`, `textField`, `badge`, …) build this tree.
2. **`lower(tree: TkNode, theme: Theme): View`** — converts the tree to the
   backend-agnostic `View` IR, threading in design tokens from the theme.
3. **`serve(view: View, port: Int)`** — starts the HTTP server; the React emitter
   compiles `View` to React hooks + JSX, served at `/`.
   **`emit(view: View, outDir: String)`** — writes a self-contained `index.html`
   - `app.js` bundle to `outDir` for static hosting.

---

### 17.2 Front-matter

```yaml
frontend: react
```

This key activates the React emitter.  It is required for any script that
calls `serve(lower(...), port)` or `emit(lower(...), dir)`.

---

### 17.3 Import pattern

Import selectively from each `std/ui` sub-module:

```markdown
[signal, seedSignal, serve, fetchUrlSignal, fetchAction, fetchActionClear, incSignal](std/ui/primitives.ssc)
[lower](std/ui/lower.ssc)
[defaultTheme](std/ui/theme.ssc)
[vstack, hstack, divider, spacer](std/ui/layout.ssc)
[heading, text](std/ui/typography.ssc)
[textField, checkbox, signalButton, actionButton](std/ui/input.ssc)
[webauthnRegister, webauthnAssert](std/ui/webauthn.ssc)
[showWhen, signalText_, fragment_, forKeyed, rawText, rawHtml](std/ui/reactive.ssc)
[loadState, stateName, errorText, triState, triStateText](std/ui/state.ssc)
[badge, spinner, signalPre](std/ui/display.ssc)
[card, cardWithHeader, modal](std/ui/containers.ssc)
[tableCol, tableRow, table, sortableTable, fcol, rowDelete, rowPost, rowLink, rowEdit, dataTable](std/ui/data.ssc)
[route, router, link, hashRouter](std/ui/routing.ssc)
```

Only import what you use — unused imports do not affect bundle size.

---

### 17.4 Signals

Signals are the reactive primitive.  Each signal has a stable string `name`
used as the JS variable name in the emitted code.

```scalascript
val count   = signal[Int]("count",   0)
val name    = signal[String]("name", "")
val open    = signal[Boolean]("open", false)
val refresh = signal[Int]("refresh", 0)
```

The React emitter turns each `signal(name, default)` call into:
```js
const [count, setCount] = useState(0)
```

Use `seedSignal(name, source)` when a text input should start from a fetched
or computed `Signal[String]` but remain user-editable. The primitive works in
the JS/browser runtime and in the React, Vue, Solid, Custom JS, SwiftUI, Swing,
and JavaFX frontend emitters:

```scalascript
val serverName = fetchUrlSignal("serverName", "/api/profile/name", refresh)
val draftName  = seedSignal("draftName", serverName)
```

The draft copies `serverName` while pristine. The first `inputChange` or
`setSignal` write marks it dirty; later refreshes of `serverName` do not
overwrite the edited draft.

#### Components — instance-scoped signals (`std/ui/component.ssc`)

Signal names are global, so a reusable widget function that calls
`signal("count", 0)` would collide with itself when instantiated twice.
`component(kind, key)(body)` scopes a `Ctx`: signals created through
`ctxSignal(ctx, name, default)` register as `"<kind>__<key>__<name>"` —
one namespace per instance. Props are just the function's typed parameters.

```scalascript
[Ctx, component, ctxSignal](std/ui/component.ssc)

def counterCard(key: String, label: String): TkNode =
  component("counterCard", key)(ctx => {
    val count = ctxSignal(ctx, "count", 0)
    cardWithHeader(heading(3, label))(
      hstack(gap = 8)(signalText_(count), actionButton(incSignal(count), "+1"))
    )
  })
```

Segments are sanitized to valid JS identifiers (non-`[A-Za-z0-9_]` chars
become `_`); keys must be unique per `kind`. Nested components scope through
`childCtx(parent, kind, key)`. Runnable example:
`examples/frontend/component-demo/component-demo.ssc`.

Signals also support programmatic `sig()` / `sig.get()` reads and
`sig.set(v)` writes on both the interpreter and JS lanes (server-side
init, tests, event closures).

#### Offline-first primitives (`std/ui/offline.ssc`)

```scalascript
[localStorageGet, localStorageSet, localStorageRemove,
 onlineSignal, persistedSignal](std/ui/offline.ssc)

val draft  = persistedSignal("draft", "")   // survives a reload
val online = onlineSignal()                  // Signal[Boolean], navigator.onLine
localStorageSet("k", "v")                    // raw storage access
```

`persistedSignal(name, default)` initializes from `localStorage` (falling
back to `default`) and writes every change back — regardless of who set the
signal (an input binding, a fetch action, or `sig.set`). `onlineSignal()`
tracks the browser's `online`/`offline` events; both lower to a per-process
map and constant `true` off-browser (JVM interpreter, Node), so the same
logic is unit-testable server-side. Runnable example:
`examples/frontend/offline-demo/offline-demo.ssc`.

#### Fetched-view state (`std/ui/state.ssc`)

Use `loadState` and `triStateText` when a view already has loading, empty, and
error signals and needs one consistent state switch. Loading wins over error,
error wins over empty, and ready content is shown only when all three guards
are clear.

```scalascript
[signal](std/ui/primitives.ssc)
[loadState, stateName, triStateText](std/ui/state.ssc)

val loading = signal("invoicesLoading", false)
val empty   = signal("invoicesEmpty", false)
val error   = signal("invoicesError", "")
val state   = loadState(loading, empty, error)

val body = triStateText(state,
  readyView = text("Invoices loaded"),
  loadingText = "Loading invoices...",
  emptyText = "No invoices yet",
  errorPrefix = "Could not load invoices: ")
```

`stateName(state)` returns a computed `Signal[String]` with
`loading`/`error`/`empty`/`ready`, which is useful in tests and status labels.
`errorText(state, prefix)` returns a reactive error message signal. Runnable
example: `examples/std-ui/tri-state-demo.ssc`.

#### Forms — validators as data (`std/ui/form.ssc`)

```scalascript
[Ctx, component](std/ui/component.ssc)
[field, form, formField, submitGate](std/ui/form.ssc)

component("signup", "main")(ctx => {
  val f = form(ctx, [
    field("name",  label = "Name",  required = true, minLength = 2),
    field("email", label = "Email", pattern = "[^@]+@[^@]+",
          patternMsg = "must be an email")
  ])
  vstack(gap = 16)(
    formField(f, "name"),          // input + live error line
    formField(f, "email"),
    submitGate(f,                   // real submit only while valid
      actionButton(fetchActionWith("POST", "/api/signup", formBody([...]), [...]), "Save"),
      text("fill the form to submit"))
  )
})
```

A form is a list of `FieldSpec`s — validators are **data**
(`required`/`minLength`/`maxLength`/`pattern`), so the same rules run in the
browser, on the server, and in unit tests (`validateField(spec, value)` is
pure). Drafts are component-scoped signals; `fieldError`/`formValid` are
computed signals that re-validate as the user types. Submit transport stays
the existing `fetchAction*`/`formBody` machinery. Current limits (see
`specs/ssc-toolkit-v2.md`): errors show from the start (no touched-state
yet), and there is no busy/error submit tri-state (needs an onFailure fetch
effect). Runnable example: `examples/frontend/form-demo/form-demo.ssc`.

#### Browser WebAuthn actions (`std/ui/webauthn.ssc`)

`webauthnRegister` and `webauthnAssert` return `EventHandler` values for
`actionButton(...)`. On click, the browser runtime POSTs a begin endpoint,
calls `navigator.credentials.create` or `navigator.credentials.get`, POSTs the
base64url-encoded response to a complete endpoint, then writes either the
server response body to a result signal or a concise failure to an error signal.

```scalascript
[signal](std/ui/primitives.ssc)
[actionButton](std/ui/input.ssc)
[webauthnRegister, webauthnAssert](std/ui/webauthn.ssc)

val result = signal("passkeyResult", "")
val error  = signal("passkeyError", "")

val enrol = webauthnRegister("/webauthn/enrol/begin",
  "/webauthn/enrol/complete", "My app", result, error)

val signin = webauthnAssert("/webauthn/signin/begin",
  "/webauthn/signin/complete", result, error)

actionButton(enrol, "Enrol passkey")
actionButton(signin, "Sign in with passkey")
```

Registration begin routes return JSON with `challenge` and `userId` required;
`userName`, `displayName`, and `rpName` are optional. Assertion begin routes
return `challenge` plus `allowCredentials`, either as an array or as a
JSON-encoded string array for compatibility with simple `Response.text(...)`
examples. Complete routes receive the same verifier-shaped fields consumed by
`std/auth.ssc`: registration posts `clientDataJSON` and `attestationObject`;
assertion posts `clientDataJSON`, `authenticatorData`, `signature`, and
`credentialId`.

The passkey ceremony only works from a real browser user gesture on localhost
or HTTPS. Interpreter/Node fallbacks still construct the handler for static
rendering and tests, but invoking it writes `WebAuthn is only available in a
browser` to the error signal. Runnable full-stack example:
`examples/frontend/webauthn-toolkit-demo/webauthn-toolkit-demo.ssc`.

---

### 17.5 Widget catalog

#### Layout

| Constructor | Description |
|-------------|-------------|
| `vstack(gap = 0)(children*)` | Vertical flex column; `gap` is pixel spacing between children |
| `hstack(gap = 0, wrap = false)(children*)` | Horizontal flex row; `wrap = true` sets `flex-wrap: wrap` so children flow onto additional lines once they exceed the container's width instead of overflowing/shrinking on one (`specs/std-ui-hstack-wrap.md`) |
| `divider()` | Horizontal rule styled with `theme.colors.muted` |
| `spacer(grow = false)` | Fixed 8 px gap; `grow = true` fills remaining space (use inside `hstack` to right-align) |

```scalascript
hstack(gap = 12)(
  heading(2, "My App"),
  spacer(grow = true),
  badge("v1.0", "success")
)
```

#### Typography

| Constructor | Description |
|-------------|-------------|
| `heading(level: Int, text: String)` | `h1`–`h6`; font size from theme |
| `text(content: String)` | `<p>` paragraph |

#### Input

| Constructor | Description |
|-------------|-------------|
| `textField(value, label, disabled, required)` | Labeled text input; `value` must be `Signal[String]`; two-way bound |
| `checkbox(checked, label, disabled)` | Checkbox; `checked` must be `Signal[Boolean]`; two-way bound |
| `select(options, selected, label, placeholder, disabled)` | Dropdown over `(value, label)` pairs; `selected` must be `Signal[String]`; two-way bound. `options` is static (built once, like `columns`/`tabs` elsewhere in the toolkit — see `specs/std-ui-select.md`); `placeholder`, when non-empty, is a disabled/hidden leading option shown while nothing is selected |
| `selectFrom(items, key, optionFn, selected, label, placeholder, disabled)` | Like `select`, but `items` is a `Signal[List[A]]` instead of a static list — the `<option>` children re-render (added/removed/reordered) when the signal's list changes, e.g. a fetched contracts list, without a page reload. `key: A => String` gives each item a stable identity (mirrors `forKeyed`'s own `key`); `optionFn: A => (String, String)` maps each item to the `(value, label)` pair `select`'s static `options` takes directly. Reactive on the JS runtime lane (`emit-js`/`emit-spa`) only — `ssc run`'s live-`serve()` path renders the list once, same as `forKeyed`/`dataTable` (see `specs/std-ui-select.md` § "Reactive options (selectFrom)") |
| `signalButton(signal, value, label, disabled, variant, size)` | Button that sets `signal` to `value` on click. `variant` (default `"primary"`) picks the background colour from `Theme.colors`: `primary\|secondary\|danger\|success\|warning`; an unrecognized string falls back to `"primary"`, no crash (`specs/std-ui-button-variant.md`). `size` (default `"md"`) picks the font-size + padding: `sm\|md\|lg`, reused from `Theme.typography`'s `caption`/`body`/`heading` tokens and a shifted `Theme.spacing` pair; an unrecognized string falls back to `"md"`, no crash (`specs/std-ui-button-size.md`) |
| `actionButton(handler, label, disabled, variant, size)` | Button wired to an `EventHandler` (`fetchAction`, `incSignal`, …). Same `variant`/`size` params as `signalButton` |

```scalascript
val name    = signal[String]("name", "")
val agreed  = signal[Boolean]("agreed", false)
val submitted = signal[Boolean]("submitted", false)
val contract  = signal[String]("contract", "c-1002")

vstack(gap = 12)(
  textField(value = name, label = "Your name"),
  checkbox(checked = agreed, label = "I accept the terms"),
  select([("c-1001", "Acme Corp"), ("c-1002", "Northwind")], contract,
    label = "Active contract", placeholder = "Choose a contract..."),
  signalButton(submitted, true, "Submit", disabled = !agreed)
)
```

Calling `select` with a named argument that skips an earlier default (e.g.
`select(options, selected, disabled = true)` alone, without also naming
`label`/`placeholder`) hits a known `bin/ssc run` standard-tier bug — name
every trailing parameter from the first one you override onward instead
(`select(options, selected, label = "", placeholder = "", disabled =
true)`), or call fully positionally. See `BUGS.md` §
`standard-tier-named-arg-skip-default`.

Runnable example: `examples/frontend/select-demo/select-demo.ssc`.
Reactive-options runnable example (build via `emit-js`/`emit-spa`, not
`ssc run` — see the example's own docstring):
`examples/frontend/select-reactive-demo/select-reactive-demo.ssc`.

#### Reactive helpers

| Constructor | Description |
|-------------|-------------|
| `showWhen(signal, whenTrue, whenFalse)` | Conditional render; `signal` must be `Signal[Boolean]` |
| `signalText_(signal)` | Inline reactive text node; re-renders when signal changes |
| `fragment_(children*)` | Group children without a wrapper `<div>` |
| `forKeyed(items, key)(render)` | Keyed browser list; surviving row DOM nodes move by stable string keys on the `emit-spa --frontend custom` runtime |
| `rawText(text: String)` | Literal text inline (no element, no binding) |
| `rawHtml(html: String)` | Trusted raw markup escape hatch for missing widgets/pre-rendered fragments; Apple targets isolate it in a nonpersistent, network-blocked WKWebView with page JavaScript disabled; caller must sanitise user-controlled input |

```scalascript
showWhen(submitted,
  hstack(gap = 4)(rawText("Welcome, "), signalText_(name), rawText("!")),
  text("Please fill out the form above.")
)

forKeyed(rows, (id: String) => id)((id: String) =>
  hstack(gap = 8)(text(id), signalButton(selected, id, "Select")))

rawHtml("<strong data-source=\"trusted\">Trusted markup</strong>")
```

#### Display

| Constructor | Description |
|-------------|-------------|
| `badge(content, variant)` | Colored pill badge; variants: `"success"` `"warning"` `"danger"` `"notification"` `"default"` |
| `spinner()` | CSS spinning loader |
| `signalPre(signal)` | `<pre>` block showing a `Signal[String]`; preserves newlines |

```scalascript
val logs = signal[String]("logs", "")
// ...
signalPre(logs)

badge("3 new",  "notification")
badge("saved",  "success")
badge("error",  "danger")
```

#### Containers

| Constructor | Description |
|-------------|-------------|
| `card(body*)` | Bordered, rounded card |
| `cardWithHeader(header)(body*)` | Card with a bold header bar |
| `cardWithFooter(footer)(body*)` | Card with a footer bar |
| `cardFull(header, footer)(body*)` | Card with both |
| `modal(open, title)(body*)` | Full-screen overlay dialog; shown when `open` (`Signal[Boolean]`) is `true` |

```scalascript
val showModal = signal[Boolean]("showModal", false)

cardWithHeader(heading(4, "Settings"))(
  text("Manage your account"),
  actionButton(setSignal(showModal, true), "Open settings")
)

modal(showModal, "Account Settings")(
  text("Settings form goes here"),
  actionButton(setSignal(showModal, false), "Close")
)
```

#### Data

| Constructor | Description |
|-------------|-------------|
| `tableCol(label, key)` | Column definition — `label` shown in header, `key` used for sort |
| `tableRow(cells*)` | Row; each cell is a `TkNode` |
| `table(cols, rows)` | Plain table |
| `sortableTable(sortCol, cols, rows)` | Clicking a header sets `sortCol` (`Signal[String]`) to that column's key; caller sorts `rows` accordingly |

```scalascript
val cols = [tableCol("Name", "name"), tableCol("Score", "score")]
val rows = users.map(u => tableRow(text(u.name), text(u.score.toString)))
table(cols, rows)
```

---

### 17.6 Fetch primitives

These event handlers and signals bridge the browser UI to the server REST API.

#### `fetchUrlSignal` — live data binding

```scalascript
// GETs /api/todos on mount; re-GETs whenever refresh changes.
val todosJson = fetchUrlSignal("todos", "/api/todos", refresh)
signalPre(todosJson)  // display raw JSON, or parse and render
```

In the generated custom SPA, a transport or response-body rejection is treated
as an unavailable refresh: the signal keeps its last-good value, no unhandled
promise rejection escapes, and the next tick may fetch again. Fulfilled HTTP
responses retain the existing text-binding semantics, including non-2xx
responses. Use an explicit status/error model when the UI needs to distinguish
those responses from successful application data.

#### `seedSignal` — editable draft from fetched text

```scalascript
val refresh = signal[Int]("refresh", 0)
val sourceName = fetchUrlSignal("sourceName", "/api/profile/name", refresh)
val draftName = seedSignal("draftName", sourceName)

textField(value = draftName, label = "Editable name")
actionButton(incSignal(refresh), "Reload source")
```

`seedSignal` is writable. It mirrors `sourceName` until the user edits the
field; after that, reloads update `sourceName` but leave `draftName` intact.

#### `fetchAction` — POST / PUT / DELETE on click

```scalascript
val text = signal[String]("text", "")
val refresh = signal[Int]("refresh", 0)

// On click: POST /api/todos with body = text.value, then refresh += 1
actionButton(fetchAction("POST", "/api/todos", text, refresh), "Add")
```

On the compiler-free standard JVM/static lane, `fetchUrlSignal` and
`fetchAction` remain declarative: helpers can construct and read the values
without loading the compatibility frontend, while actual network requests are
performed only by the emitted browser/runtime target. A fetch signal therefore
has an empty initial value during static JVM evaluation.

#### `fetchActionClear` — submit and clear input

```scalascript
// Same as fetchAction, but also resets text to "" after success.
actionButton(fetchActionClear("POST", "/api/todos", text, refresh), "Add")
```

#### `incSignal` — manual refresh

```scalascript
// Bump refresh by 1 — any fetchUrlSignal watching it will re-fetch.
actionButton(incSignal(refresh), "Reload")
```

#### `dataTable` — reactive REST table

```scalascript
val refresh = signal[Int]("refresh", 0)
val todos   = fetchUrlSignal("todos", "/api/todos", refresh)

// Fetches GET /api/todos → renders rows; each row has a Delete button
// that POSTs {id} to /api/todos/delete, then bumps refresh.
dataTable(
  todos,
  [fcol("Task", "text")],
  [rowDelete("/api/todos/delete", "id", refresh)]
)
```

`dataTable` consumes a `fetchUrlSignal`; the signal expects the server to return a JSON array:
```json
[{"id": 1, "text": "Buy milk"}, {"id": 2, "text": "Write tests"}]
```

---

### 17.7 Client-side routing

```scalascript
val page = signal[String]("page", "/")

val tree = vstack(gap = 0)(
  hstack(gap = 16)(
    link("/",       "Home",  page),
    link("/about",  "About", page),
    link("/todos",  "Todos", page)
  ),
  divider(),
  router(page, [
    route("/",       [heading(1, "Home"),  text("Welcome!")]),
    route("/about",  [heading(1, "About"), text("Built with std/ui.")]),
    route("/todos",  [heading(1, "Todos"), todosPanel])
  ])
)
```

`link` renders `<a href=path>` and sets `page` signal on click (no page
reload — SPA navigation).  `router` shows exactly one route at a time via
`eqSignal` guards compiled to React conditionals.

#### Hash-based routing

`hashRouter` uses `window.location.hash` as the current path — works with
static hosting (no server-side routing needed):

```scalascript
hashRouter([
  route("#/",      [heading(1, "Home")]),
  route("#/about", [heading(1, "About")])
])
```

---

### 17.8 Themes

```scalascript
// Built-in themes
serve(lower(tree, defaultTheme), 8080)   // light
serve(lower(tree, darkTheme),    8080)   // dark

// Custom theme
val myTheme = Theme(
  ColorPalette(
    primary    = "#0f766e",   // teal
    onPrimary  = "#ffffff",
    secondary  = "#6d28d9",
    surface    = "#f0fdfa",
    onSurface  = "#134e4a",
    background = "#ffffff",
    muted      = "#94a3b8",
    danger     = "#dc2626",
    success    = "#16a34a",
    warning    = "#d97706"
  ),
  SpacingScale(xs = 4, sm = 8, md = 16, lg = 24, xl = 32, xxl = 48),
  TypographyScale(
    body    = TypographyItem(16, "Inter, system-ui, sans-serif"),
    heading = TypographyItem(24, "Inter, system-ui, sans-serif")
  ),
  RadiusScale(sm = 4, md = 8, lg = 16, full = 9999)
)
```

All widget constructors are theme-unaware — themes are applied exclusively
inside `lower`.  Switching themes is a single argument change.

---

### 17.9 Serve vs emit

| | `serve(lower(tree, theme), port)` | `emit(lower(tree, theme), dir)` |
|---|---|---|
| **Use case** | Development / production server | Static hosting (S3, GitHub Pages, CDN) |
| **Registers `route()` handlers** | Yes | No |
| **Output** | HTTP on `port` | `dir/index.html` + `dir/app.js` |
| **Hot reload** | `ssc serve myapp.ssc` | Re-run `ssc myapp.ssc` |

```bash
ssc serve myapp.ssc               # dev server with hot reload
ssc myapp.ssc                     # emit bundle to ./dist/
```

#### The production SPA path: `emit-spa` (self-contained)

For a deployable single-file SPA, the **production path is
`ssc emit-spa --frontend custom app.ssc > app.html`** — the whole program is
compiled by the static JS backend (JsGen) with the framework-free signals
runtime inlined; the output makes **no external requests** (no CDN scripts,
no fonts — audited; the OAuth endpoint constants visible in the bundle are
inert `jwt-auth` runtime strings, only used if OAuth is called). The
`react`/`vue`/`solid` emit-spa flavors load their framework from a CDN and
stay demo-grade. Toolkit-v2 primitives (components, offline storage, forms,
keyed lists) are verified on this path (see `specs/ssc-toolkit-v2.md`).

---

### 17.10 Full example

A minimal but complete SPA: sign-up form + modal confirmation.

````ssc
#!/usr/bin/env ssc
---
name: signup
version: 1.0.0
frontend: react
---

# Sign-up demo

[signal, serve](std/ui/primitives.ssc)
[lower](std/ui/lower.ssc)
[defaultTheme](std/ui/theme.ssc)
[vstack, hstack, divider, spacer](std/ui/layout.ssc)
[heading, text](std/ui/typography.ssc)
[textField, checkbox, signalButton, actionButton](std/ui/input.ssc)
[showWhen, signalText_, fragment_, rawText](std/ui/reactive.ssc)
[badge](std/ui/display.ssc)
[cardWithHeader, modal](std/ui/containers.ssc)

```scalascript
val name      = signal[String]("name",      "")
val email     = signal[String]("email",     "")
val agreed    = signal[Boolean]("agreed",   false)
val submitted = signal[Boolean]("submitted", false)
val showInfo  = signal[Boolean]("showInfo",  false)

val form = cardWithHeader(heading(3, "Create account"))(
  vstack(gap = 12)(
    textField(value = name,  label = "Display name"),
    textField(value = email, label = "Email address"),
    checkbox(checked = agreed, label = "I accept the terms of service"),
    hstack(gap = 8)(
      signalButton(submitted, true, "Sign up", disabled = !agreed),
      actionButton(setSignal(showInfo, true), "?")
    )
  )
)

val confirmModal = modal(showInfo, "What happens next?")(
  text("We will send a confirmation email to the address you entered."),
  actionButton(setSignal(showInfo, false), "Got it")
)

val tree = vstack(gap = 24)(
  heading(1, "Welcome"),
  showWhen(submitted,
    hstack(gap = 4)(
      badge("Done", "success"),
      rawText(" Signed up as "),
      signalText_(name)
    ),
    form
  ),
  confirmModal
)

serve(lower(tree, defaultTheme), 8080)
```
````

---

### 17.11 React emitter internals

How the React emitter translates `View` IR to React code:

| `View` / `EventHandler` | Emitted React code |
|-------------------------|-------------------|
| `signal("x", 0)` | `const [x, setX] = useState(0)` |
| `inputChange(sig)` | `onChange={e => setSig(e.target.value)}` |
| `toggleSignal(sig)` | `onChange={e => setSig(e.target.checked)}` |
| `setSignal(sig, v)` | `onClick={() => setSig(v)}` |
| `showSignal(cond, t, f)` | `{cond ? t : f}` |
| `signalText(sig)` | `{sig}` inline JSX |
| `eqSignal(sig, v)` | `sig === v` computed inline |
| `fetchUrlSignal(name, url, tick)` | `useState("")` + `useEffect([tick], () => fetch(url).then(r=>r.text()).then(setName))` |
| `fetchAction(m, url, body, tick)` | `() => fetch(url,{method:m,body:getBody()}).then(()=>setTick(t=>t+1))` |
| `fetchActionClear(m, url, body, tick)` | Same + `setBody("")` on success |
| `hashSignal()` | `useState(location.hash)` + `hashchange` listener |

The emitter produces a single self-contained `app.js` with no external
runtime dependencies beyond React 18 (loaded from CDN in `index.html`).

---

## 18. Frontend Toolkit — Scala API (v1.18 B / B+ / B++ / C)

Higher-level declarative UI on top of the framework SPI.  Lives in
the `frontend-toolkit` sbt module; user code reaches for the `Tk`
facade.  Every widget lowers to the backend-agnostic `View` AST
through `Toolkit.lower(node, theme)`, so the same widget tree
compiles through React / Vue / Solid / Custom.

**Widget catalog (as of Phase B++)**

- *Layout*: Stack (`vstack`/`hstack`), Box, Spacer, Divider, Card
- *Typography*: Heading, Text, Code
- *Inputs*: Button, TextField, Checkbox, Slider, Select, RadioGroup,
  Textarea, DatePicker, NumberInput
- *Display*: Alert (a.k.a. `notice`), Badge, Avatar, Icon, Spinner,
  Progress, Tooltip
- *Containers*: Modal, Drawer
- *Navigation*: Tabs, Router + Link
- *Data*: Table with click-to-sort + ARIA
- *Form*: validation pipeline + `FormContext`

```scala
import scalascript.frontend.*
import scalascript.frontend.toolkit.*
import scalascript.frontend.toolkit.Tk

val name   = new ReactiveSignal[String]("name", "")
val agree  = new ReactiveSignal[Boolean]("agree", false)

val tree: ToolkitNode = Tk.vstack(gap = 16)(
  Tk.heading(1, "Sign up"),
  Tk.card()(
    Tk.vstack(gap = 12)(
      Tk.textField(name, label = Some("Display name"), required = true),
      Tk.checkbox (agree, label = "I accept the terms.")
    )
  ),
  Tk.button("Submit", onClick = () => submit(name(), agree()),
            kind = ButtonKind.Primary),
  Tk.notice(AlertSeverity.Success, title = Some("OK")) {
    Tk.text("Submitted.")
  }
)

// Lower once; every backend consumes the resulting View identically.
val view: View = Toolkit.lower(tree, Theme.default)
```

**Forms with validation**

```scala
Tk.form(onSubmit = ctx => api.createUser(ctx.values())) { ctx =>
  val email = ctx.field[String]("email", "",
    Validators.and(Validators.required, Validators.email))
  val pwd   = ctx.field[String]("password", "",
    Validators.and(Validators.required, Validators.minLength(8)))
  Tk.vstack(gap = 8)(
    Tk.textField(email.value, label = Some("Email"), error = Some(email.error)),
    Tk.textField(pwd.value,   label = Some("Password"),
                 inputType = "password", error = Some(pwd.error)),
    Tk.button("Create", onClick = () => (), formSubmit = true)
  )
}
```

**Routing**

```scala
val currentPath = new ReactiveSignal[String]("path", "/")
Tk.router(currentPath, notFound = Tk.text("404"))(
  Tk.route("/")          (_ => homePage()),
  Tk.route("/users/:id") (params => userProfile(params("id")))
)
```

**Run the toolkit demo**

A complete reference SPA ships with the project under
[`frontend-examples`](../frontend-examples/src/main/scala/scalascript/frontend/examples/ToolkitDemo.scala):

```bash
# 1. Compile + test (217 toolkit + 41 demo = 258 tests)
sbt frontendToolkit/test frontendExamples/test

# 2. Emit 16 static bundles (4 demos x 4 backends)
sbt "frontendExamples/runMain scalascript.frontend.examples.EmitAll"
# → target/frontend-examples/toolkit-demo/{custom,react,solid,vue}/

# 3. Serve via the bundled scalascript HTTP server (no Python/Node)
ssc serve 8000 target/frontend-examples/toolkit-demo/react
# open http://localhost:8000/
```

The Custom backend currently renders the toolkit demo as static
HTML (signal-binding through JVM lambdas is Phase D work); React /
Vue / Solid run fully reactive.

**SSR**

`Ssr.renderToHtml(viewOrNode, theme)` snapshots the toolkit tree to
a static HTML string with no JS subscriptions — useful for SEO,
static-site generation, email templates, or snapshot tests:

```scala
import scalascript.frontend.toolkit.{Ssr, Theme}
val html = Ssr.renderToHtml(tree, Theme.default)
val doc  = Ssr.renderDocument(tree, title = "Demo", theme = Theme.dark)
```

Spec: [`specs/frontend-toolkit-spec.md`](frontend-toolkit-spec.md).
Cross-backend integration: [`docs/frontend-usage.md`](frontend-usage.md).

---

## 19. Cluster management

Distributed actors + cluster primitives baked in across all server backends:

| Primitive | Notes |
|-----------|-------|
| Bully leader election | `cluster.leader()` returns the current bully-elected leader (Signal) |
| Phi-accrual failure detector | Tunable phi threshold per cluster; heartbeat-driven liveness |
| Self-health | `cluster.self.health` — driver-side health metric stream |
| Federation | Multi-cluster gossip + cross-cluster routing |
| Raft consensus | Strongly-consistent state machine — `RaftStateMachine[S]` |
| ZooKeeper client | `zkClient { … }` for legacy coordinator integration |
| Operational routes | `GET /_ssc-cluster/status`, `/members`, `/leader` — built-in across server backends |

Typed actor references are available as a typed surface over the existing
distributed `Pid` runtime:

```scalascript
runActors {
  startNode("node-a")
  registerBehavior("echo", (arg: Any) =>
    receive { case "ping" => println("pong") }
  )

  val ref = spawnRemote[String]("node-a", "echo", ())
  println(ref.address)
  println(ref.isLocal)
  ref.publishAs("actors.echo")
  ref.tell("ping")
}
```

`spawnRemote` invokes a named behavior registered on the target node; it does
not serialize arbitrary closures.  The current implementation uses the JSON
`ssc-actors-v1` control channel (`cluster_spawn` / `cluster_spawn_ack`);
binary MsgPack/CBOR transport is planned in the distributed wire milestone.
See [`examples/actors-typed-remote-spawn.ssc`](../examples/actors-typed-remote-spawn.ssc).

Cluster capability snapshots expose the local node view and code identity:

```scalascript
runActors {
  startNode("node-a")
  val seeds = SeedResolver.staticList(List("ws://node-b:9100/_ssc-actors"))
  val cluster = clusterOf(seeds)
  val identity = codeIdentity()

  println(cluster.localNodeId)
  println(cluster.resolveSeeds())
  println(identity.digest)
  assertCodeIdentity(identity)
}
```

`SeedResolver.staticList`, `SeedResolver.dnsSrv`, and
`SeedResolver.k8sHeadlessService` work today in the interpreter. The Consul
resolver descriptor is part of the public API but still planned for runtime
resolution; using it with `resolveSeeds` now fails with an explicit diagnostic
instead of silently joining no peers.
See [`examples/cluster-capability.ssc`](../examples/cluster-capability.ssc).

Cluster and remote registry metadata can also be declared in front matter:

```yaml
cluster:
  name: demo
  nodeId: api-1
  role: server
  seedNodes:
    - ws://api-1:9100/_ssc-actors

remoteHandlers:
  users.get:
    function: getUser
    path: /api/v1/users/:id
    request: UserId
    response: User
```

This metadata is parsed and preserved in AST/IR/`.sscc` today. `remoteHandlers:`
entries are also lowered by the interpreter into a local remote handler
registry. Source `@remote(name = "users.get", path = "/api/v1/users/:id") def`
and simple `remote def echo(...)` declarations lower into the same metadata.
Import `std.remote` and call named operations through
`Remote.function[A, B]("users.get")`; `remoteTryCall` returns a typed
`RemoteCallError` in `Left(...)` when the handler is unavailable. If a handler
declares `path`, the interpreter also exposes a POST HTTP JSON fallback route
using the current ScalaScript value JSON shape. A client can call that route
explicitly with `Remote.http[A, B]("http://host:port/api/v1/users/42")`, which
posts the same value JSON and decodes the response. The parser also derives
typed route client metadata for those handlers under the generated `RemoteRpc`
client name, so JS/JVM typed-route client generation can call `path:` remote
handlers through the existing HTTP client path. Trait-shaped `remoteStub[Api]`,
async effect-row lowering, `remoteSources:`, `remoteBehaviors:`, and
WebSocket/internal-wire transports are still planned. For split-process code
that already knows the base server URL, `remoteStub[Api](baseUrl)` or
`Remote.stub[Api](baseUrl)` returns a lightweight path-based `RemoteStub`; use
`stub.call[A, B](path, value)`, `stub.tryCall[A, B](path, value)`, or
`stub.function[A, B](path)` to call the same HTTP JSON fallback routes without
repeating the base URL. The `Api` type argument is accepted now as
forward-compatible syntax; generated trait methods are still planned.

See [`examples/remote-registry-rpc.ssc`](../examples/remote-registry-rpc.ssc).
The parser rejects registry entries whose `function`, `source`, or `behavior`
target is not defined in the same module, and rejects request/response/args
types that are neither built in nor declared in the same module.

The source-level cluster block lowers to the same metadata:

```ssc
cluster Demo:
  nodes = 3
  seedDiscovery = SeedResolver.k8sHeadlessService("ssc-demo")
  leaderElection = Raft
  authTokenFrom = K8sSecret("ssc-cluster-token", key = "token")
  heartbeat(intervalMs = 5000, deadAfterMs = 40000)
  quorum(2)
```

Specs: [`specs/cluster-management.md`](cluster-management.md),
[`specs/cluster-raft.md`](cluster-raft.md),
[`specs/cluster-federation.md`](cluster-federation.md),
[`specs/client-zookeeper.md`](client-zookeeper.md).

---

## 20. x402 micropayments

HTTP 402 → typed payment challenge / settlement. Same `.ssc` source describes
both client and server; the protocol layer wires the payment family
(Ethereum / Cardano) automatically.

```scalascript
x402Server {
  route("/quote") { req => requirePayment(Payment.usdc(0.10)) { _ =>
    Response.json(getQuote(req.params("symbol")))
  }}
}
```

```scalascript
x402Client(wallet) {
  val quote = httpGet("https://api/quote?symbol=AAPL").settle()
  println(quote)
}
```

Browser EVM wallets can use the Scala.js helper:

```scalascript
val wallet = await(Wallets.metaMask(Network.Base))
```

`Wallets.metaMask` connects through `window.ethereum`, validates the
active EIP-155 chain, and signs x402 EIP-712 authorizations with
`eth_signTypedData_v4`.

MPC wallet vaults use the same wallet SPI but delegate signatures to an
external provider instead of holding local keys. The shared
`wallet-vault-mpc` module defines `RemoteSigningClient`, `McpVault`, and
`McpRemoteSigner`; provider adapters live in separate modules. Fireblocks is
available through `wallet-vault-mpc-fireblocks`:

```scalascript
import scalascript.wallet.vault.mpc.fireblocks.*

val vault = FireblocksVault(
  apiKey        = sys.env("FIREBLOCKS_API_KEY"),
  privateKeyPem = sys.env("FIREBLOCKS_PRIVATE_KEY_PEM"),
  baseUrl       = sys.env.getOrElse("FIREBLOCKS_BASE_URL", "https://api.fireblocks.io"),
  options       = FireblocksOptions(vaultAccountId = "0", assetId = "ETH"),
)
```

The Fireblocks adapter signs each request with Fireblocks RS256 JWT auth,
creates RAW transactions via `/v1/transactions`, and polls
`/v1/transactions/{id}` until the signature is completed or failed.

Specs:
- [`specs/x402.md`](x402.md) — protocol + flows
- [`specs/blockchain-spi.md`](blockchain-spi.md) — pluggable backends (EVM, Bitcoin, Solana, Cardano)
- [`specs/micropayment-spi.md`](micropayment-spi.md) — payment family abstraction
- [`specs/wallet-vault-mpc.md`](wallet-vault-mpc.md) — MPC remote signing vaults
- [`specs/mcp-x402-wallet.md`](mcp-x402-wallet.md) — MCP × x402 paid LLM tools

Examples: `x402-server.ssc`, `x402-client.ssc`, `x402-metamask.ssc`,
`x402-cardano.ssc` (end-to-end Cardano flow with CIP-8 wallet + Scalus
escrow validator), `wallet-mpc-fireblocks.ssc`.

Cardano Scalus escrow support is still incomplete: the compiled Plutus validator
is committed and `EscrowScript.address(network)` can derive stable mainnet and
testnet script addresses, but reference-script deployment and real claim
transaction integration remain planned. The client also exposes
`Wallets.cardano(hex, network, scalusMode = true)` for the planned escrow flow:
it signs a structured Scalus claim message and carries the escrow UTxO ref in
`authorization.nonce`. `CardanoProvider.Scalus` can verify that structured
claim proof before settlement, and the on-chain Scalus validator now checks the
canonical CIP-8 redeemer proof against the datum's payer key hash and claim
message hash, the receiver output's exact lovelace amount, and the
claim/refund validity window; `X402EscrowScriptSimulatorTest` also covers the
validator happy path and rejection branches through constructed Scalus
`ScriptContext` values. Current production Cardano flows should still keep
using the default non-Scalus mode until the Plutus claim transaction builder is
validated against Preprod. `BloxbeanClaimTxBuilder.draft` can serialize a
claim transaction draft with script input/output, redeemer, collateral,
required signer, script data hash, and relayer vkey witness. It is still not
the production default: live script ex-unit evaluation and Preprod validation
remain planned. `BlockfrostClient.getProtocolParams()` reads latest-epoch
fee/execution/collateral settings plus Plutus cost models, and
`BloxbeanClaimTxBuilder.draftBalanced(...)` can use those params to estimate
protocol min-fee from final draft CBOR size. `ScalusSettlerConfig.claimExUnits`
can carry conservative static Plutus ex-units into the redeemer and fee estimate.
For node-backed evaluation, `ScalusTxEvaluator.bloxbean(...)` adapts bloxbean
`TransactionEvaluator` results and the evaluated-balanced draft path rebuilds
the redeemer and fee from evaluator output. For hosted node evaluation,
`BlockfrostClient.evaluateTx(cbor)`, `ScalusTxEvaluator.blockfrost(...)`, and
`ScalusTxEvaluator.ogmiosHttp(url)` map Blockfrost/Ogmios budget responses into
typed `ScalusExUnits` before the same evaluated-balanced rebuild path runs.
`BloxbeanPreprodIntegrationTest` is
skipped by default; set `X402_SCALUS_PREPROD_IT=true` plus the required
Blockfrost/escrow/relayer env vars to build a live Preprod draft, and set
`X402_SCALUS_PREPROD_SUBMIT=true` only when you intentionally want to submit it.
`ScalusSettler.preprod/mainnet` exists for wiring and tests; its default builder
fails explicitly until the remaining production pieces land.

---

## 21. Compiler Plugins with Intrinsics

ScalaScript's backend is open to extension: any capability not built into
the language — cryptographic hashing, ML inference, GPU kernels, custom
IO — can be packaged as a **plugin** and distributed as a single
`.sscpkg` file.

### 21.1 What an intrinsic is

An **intrinsic** is an `extern def` in a `.ssc` source file whose body
is supplied by a plugin rather than written in ScalaScript.  From the
caller's perspective it looks like an ordinary function:

```scala
import [Crypto](crypto)

val digest = sha256("hello, world")
val token  = base64Encode(digest)
```

The `extern def` declaration lives in the plugin's bundled `.ssc` source
(e.g. `sources/crypto.ssc`) and is imported like any other module:

```scala
// sources/crypto.ssc  (shipped inside the .sscpkg)
extern def sha256(input: String): String
extern def base64Encode(s: String): String
extern def base64Decode(s: String): String
extern def hmacSha256(key: String, data: String): String
```

The compiler knows the type signature; the actual implementation is
provided by the plugin's `IntrinsicImpl` entries.

### 21.2 The `IntrinsicImpl` variants

Each `extern def` maps to one of four strategies in
`scalascript.backend.spi.IntrinsicImpl`:

| Variant | When to use |
|---------|------------|
| `NativeImpl(fn)` | Interpreter backend — `fn` is called directly with evaluated args; no code generation needed |
| `RuntimeCall(sym)` | Code-generating backends (JVM, JS) — the call site becomes `sym(args…)`; the function is defined in `runtime/jvm.scala` or `runtime/js.js` bundled with the plugin |
| `InlineCode(emit)` | Inline target-source at each call site when `RuntimeCall` is too coarse-grained |
| `HostCallback(name)` | Out-of-process backends — routes the call back through the host wire protocol |

**Typical pattern:** supply both `RuntimeCall` and `NativeImpl` for the same
symbol, registered in separate `Backend` classes — one for JVM/JS code
generation, one for the interpreter.

```scala
// For JVM/JS backends — emitted as a call to a runtime helper
QualifiedName("std.crypto.sha256") -> RuntimeCall("_cryptoSha256")

// For the interpreter — direct JVM call, no code emission
QualifiedName("std.crypto.sha256") -> NativeImpl { (_, args) =>
  val md = java.security.MessageDigest.getInstance("SHA-256")
  md.digest(args.head.toString.getBytes("UTF-8")).map("%02x".format(_)).mkString
}
```

### 21.3 Runtime helpers

For `RuntimeCall`, the function `_cryptoSha256` must exist in the
generated output.  The plugin ships platform-specific helpers that
`BackendRegistry` prepends to every compiled file:

```scala
// runtime/jvm.scala  — injected before user code for JVM output
private def _cryptoSha256(input: Any): String =
  val md = java.security.MessageDigest.getInstance("SHA-256")
  md.digest(input.toString.getBytes("UTF-8")).map("%02x".format(_)).mkString
```

```javascript
// runtime/js.js  — injected for JS/Node output
const _cryptoSha256 = (input) => {
  const crypto = require('crypto');
  return crypto.createHash('sha256').update(String(input), 'utf8').digest('hex');
};
```

### 21.4 The `Backend` SPI

A plugin exposes its intrinsic table by implementing `scalascript.backend.spi.Backend`:

```scala
class CryptoBackendPlugin extends Backend:
  def id          = "crypto-intrinsics-jvm"
  def displayName = "Crypto Intrinsics (JVM/JS)"
  def spiVersion  = SpiVersion.Current

  def capabilities    = Capabilities(features = Set.empty, outputs = Set.empty,
                                     options  = Set.empty,
                                     spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current))
  def intrinsics      = CryptoIntrinsics.table   // Map[QualifiedName, IntrinsicImpl]
  def acceptedSources = Set.empty

  // Pure intrinsic-provider plugin — does not compile standalone programs.
  def compile(ir, opts) =
    CompileResult.Failed(List("use jvm or js backend"))
```

Register it with Java's `ServiceLoader` by creating:

```
META-INF/services/scalascript.backend.spi.Backend
```

containing the fully-qualified class name.

### 21.5 The `.sscpkg` format

A plugin is distributed as a ZIP file with a `.sscpkg` extension and the
following layout:

```
manifest.yaml          # package metadata
sources/               # .ssc files with extern def declarations
  crypto.ssc
runtime/               # target-platform helpers
  jvm.scala
  js.js
intrinsics/            # JAR(s) with Backend SPI implementations
  crypto-plugin-1.0.0.jar
```

**`manifest.yaml`** example:

```yaml
id:         org.example.crypto
version:    1.0.0
spiVersion: "0.1.0"
kind:       [library, plugin]
targets:    [jvm, interpreter]

capabilities:
  features:   []
  declares:   [CryptoUtils]

exports:
  externDefs:
    - std.crypto.sha256
    - std.crypto.base64Encode
    - std.crypto.base64Decode
    - std.crypto.hmacSha256
```

Build the `.sscpkg` with:

```bash
# Compile the plugin JAR (scala-cli or sbt)
scala-cli package . --assembly -o crypto-plugin-1.0.0.jar

# Pack
mkdir -p _pkg/intrinsics _pkg/sources _pkg/runtime
cp manifest.yaml _pkg/
cp -r sources/ _pkg/sources/
cp -r runtime/ _pkg/runtime/
cp crypto-plugin-1.0.0.jar _pkg/intrinsics/
ssc plugin pack _pkg -o org.example.crypto-1.0.0.sscpkg
```

### 21.6 Installing and using plugins

```bash
# Install permanently in ~/.scalascript/compiler/plugins/
ssc plugin install ./org.example.crypto-1.0.0.sscpkg

# Ad-hoc for a single run
ssc --plugin ./org.example.crypto-1.0.0.sscpkg run my-script.ssc

# Use a bundled advanced plugin without a public registry/domain
ssc --plugin bin/lib/compiler/plugin-available/sql-plugin.sscpkg run my-script.ssc

# Inspect installed plugins
ssc plugin list

# Check compatibility without running
ssc plugin check ./org.example.crypto-1.0.0.sscpkg

# Uninstall
ssc plugin uninstall org.example.crypto
```

The compiler discovers plugins via a registry file at
`~/.scalascript/registry.yaml`.  Plugins are loaded lazily on first
import — an unused plugin adds zero overhead.

### 21.7 Built-in plugins

Standard capabilities beyond the core language are shipped as `.sscpkg` plugins
bundled with `ssc`.

Essential plugins are auto-loaded from `bin/lib/compiler/plugins` so existing
programs keep working without extra flags: `json`, `content`, `frontend`,
`request`, `fetch`, `graph`, `http`, `ws`, `mcp`, `remote`, `streams`, `deploy`,
`uuid`, `mime`, `fs`, `os`, `yaml`, `bench`, `logger`, `random`, `clock`, `env`,
`state`, `retry`, and `cache`.

Advanced plugins are bundled locally but not auto-loaded. They live under
`bin/lib/compiler/plugin-available` and can be enabled with `ssc --plugin
bin/lib/compiler/plugin-available/<name>.sscpkg ...` or installed permanently
with `ssc plugin install bin/lib/compiler/plugin-available/<name>.sscpkg`.
This is the no-domain path for opt-in capabilities such as `auth`, `oauth`,
`sql`, `crypto`, `payments`, `payment-request`, `pdf`, `smtp`, `graphql`,
`dstreams`, `pwa`, `nfc`, and `swing`.

ScalaScript 2.1 also has core-free native provider lanes. These providers
implement the v2 `NativePlugin` SPI and are staged outside
`bin/lib/standard/jars`, so their dependencies are physically absent from
plain `ssc`. Select one explicitly with `ssc-provider`; execution still uses
`StandardMain`, the self-hosted frontend/checker, and native VM or direct ASM:

```bash
ssc-provider pdf run examples/invoice-pdf.ssc
ssc-provider pdf run --bytecode examples/pdf-extract-demo.ssc
ssc-provider mcp run examples/mcp-client-discover.ssc
ssc-provider mcp run --bytecode examples/agent-mcp-toolsource.ssc
RDF4J_URL=http://localhost:8080/rdf4j-server/repositories/kg \
  ssc-provider graph-rdf4j run examples/graph-rdf4j-http-storage.ssc
SWIFT_AGGREGATOR_URL=http://localhost:9000 SWIFT_API_KEY=secret \
  ssc-provider swift run examples/international-bank-rails.ssc
ssc-provider nfc run examples/nfc-ndef.ssc
ssc-provider nfc run --bytecode examples/nfc-ndef.ssc
```

The PDF lane supplies `htmlToPdfBase64`, `pdfPageCount`, `pdfToMarkdown`, and
the dependency-free `buildMimeMessage` companion needed by invoice email.
OpenHTMLtoPDF, PDFBox, and jsoup remain under `bin/lib/providers/pdf/jars`;
they are not copied into the standard runtime, compiler image, or generated
default `build-jvm` artifact. Unknown provider names fail before source
execution. Compiler-backed `.sscpkg` plugins remain an explicit `ssc-tools`
surface and are not loaded by this native launcher.

The MCP lane supplies a native JSON-RPC client over an explicitly configured
stdio subprocess. It uses the shared MCP protocol codec and a real child
process transport without loading the v1 interpreter or compiler. The bundled
`examples/mcp-server-tools.js` server makes the discovery and agent-tool-source
examples deterministic for VM/direct-ASM regression tests; production clients
can point the same `Transport.Spawn` value at another MCP stdio server.

The `graph-rdf4j` lane adds only the remote `Sparql.select` and
`Sparql.update` operations to the standard process-local Graph provider. It
uses the RDF4J SPARQL HTTP protocol at `RDF4J_URL`, with optional
`RDF4J_USER`/`RDF4J_PASS` basic authentication. Its JSON dependency is staged
only under `bin/lib/providers/graph-rdf4j/jars`; plain `ssc` keeps local Graph
storage but does not claim remote SPARQL ownership.

The `swift` lane supplies the portable `SwiftProvider` and bank-rails values
and performs authenticated transfer creation/status requests against
`SWIFT_AGGREGATOR_URL`. Its HTTP/JSON implementation is isolated under
`bin/lib/providers/swift/jars`; the exact regression uses a local aggregator
fixture and fixed UETR/GPI results, never production credentials or a public
network.

Compiler- and target-owned documents use `ssc-tools`, never a fallback from
plain `ssc`. Quoted macro examples run with `ssc-tools run --v1`. `emit-wasm`
now emits the linked `main.wasm`, its stem-named ES module, and `__loader.js` as
a directly runnable set; the pure example is executed under Node while the
HTTP example is compile-validated without contacting its public URL. The x402
example uses `ssc-tools run-jvm`; its staged x402/sttp classpath lives only at
`bin/lib/tools/x402` and is selected only when generated source imports
`scalascript.x402`.

| Plugin | Intrinsics it provides |
|--------|----------------------|
| `std/json-plugin` | `jsonStringify`, `jsonParse`, `jsonRead`, `lookup`, `lookupOpt` |
| `std/http-plugin` | `serve`, `route`, `httpGet`, `httpPost`, `Response.*` |
| `std/sql-plugin` | `Db.query`, `Db.execute`, `Db.insert`, `Db.update`, `Db.transaction` (advanced; opt-in) |
| `std/ws-plugin` | `wsRoute`, `wsBroadcast`, `WsSession.*` |
| `std/frontend-plugin` | `lower`, `serve` (UI), `emit` |
| `std/fetch-plugin` | `fetchAction`, `fetchUrlSignal`, `incSignal` |
| `std/auth-plugin` | `session`, `jwt`, `oauth2` (advanced; opt-in) |
| `std/mcp-plugin` | `mcpServer`, `mcpTool` |

Third-party plugins follow the same `.sscpkg` format.  See
`examples/plugins/crypto-plugin/` for a complete worked example and
`examples/plugins/hello-backend/` for a minimal skeleton.

### 21.8 Name collisions: your function wins over an intrinsic

If you define a top-level `def` with the same bare name as a plugin
intrinsic (for example your own `def rateLimit(req)` while `auth-plugin`
provides a `rateLimit` intrinsic), **your definition always wins** —
calls resolve to your function regardless of plugin load order.  The
compiler emits a one-time warning so the shadow is never silent:

```
[warn] 'rateLimit' shadows plugin intrinsic 'rateLimit' — user definition wins
```

This mirrors ordinary lexical shadowing.  A `def` that is *local* to
another function does not collide with the global intrinsic and produces
no warning.  To keep using the intrinsic, rename your function.  Full
policy: [`specs/intrinsic-shadow-policy.md`](../specs/intrinsic-shadow-policy.md).

---

## 22. Config System (v1.28)

ScalaScript v1.28 introduces first-class configuration: YAML, JSON, and HOCON config
sources that merge into a single typed tree, with uniform secret substitution and typed
bindings in ScalaScript, Scala, and JavaScript.

### 22.1 Config sources

Three sources can be used simultaneously — they merge according to a priority order
(highest to lowest by default: fenced blocks > external files > front-matter).

**Front-matter** — the entire front-matter is the config root.  Existing keys
(`databases:`, `frontend:`, `dep:`) remain fully backward-compatible:

```yaml
---
frontend: react
config:
  server:
    port: 8080
    host: "0.0.0.0"
  files: [app.yaml, "${env:ENV}.hocon"]   # external files
---
```

**Fenced config blocks** — inline config in the `.ssc` file:

````
```yaml config "server"
port: ${env:PORT | 8080}
host: "0.0.0.0"
tls:
  cert: ${file:/run/secrets/cert.pem}
```

```json config "features"
{"dark_mode": true, "beta": false}
```
````

Named blocks (e.g. `"server"`) are scoped to `config.server.*`.
Unnamed blocks (` ```yaml config `) merge at the root.

**External files** — referenced from front-matter:

```yaml
---
config:
  files:
    - path: defaults.yaml
    - path: "${env:ENV}.hocon"
      optional: true
---
```

Or shorthand: `config: [app.yaml, prod.hocon]`

### 22.2 Substitution syntax

All config formats support the unified `${scheme:ref}` syntax plus HOCON-style variables:

| Pattern | Meaning |
|---------|---------|
| `${env:PORT}` | Required environment variable |
| `${env:PORT \| 8080}` | Env var with default |
| `${file:/run/secrets/pw}` | File contents (trimmed) |
| `${sops:db.password}` | sops-decrypted YAML key |
| `${vault:secret/app#field}` | Vault plugin |
| `${config:server.port}` | Cross-reference another config key |
| `${?VAR}` | HOCON optional — empty string if missing |
| `${VAR}` | HOCON required env var |

### 22.3 Using config in ScalaScript code

The `config` global is always available (no import needed):

```scala
// Dynamic path accessor (no type annotation needed)
val port = config.server.port.getInt("port")    // or:
val port = config.getInt("server.port")          // Option[Int]
val host = config.getString("server.host")       // Option[String]
val host = config.requireString("server.host")   // String (throws if missing)

// Section accessor
val srv = config.section("server")
val port: Int = srv.requireInt("port")
```

**Typed binding with `derives Config`:**

```scala
case class ServerConfig(port: Int, host: String, tls: Boolean) derives Config
case class AppConfig(server: ServerConfig, debug: Boolean) derives Config

val app = config.as[AppConfig]               // Either[ConfigError, AppConfig]
val srv = config[ServerConfig]("server")     // Either[ConfigError, ServerConfig]
```

### 22.4 Priority override

Default order: fenced blocks > external files > front-matter.

Override in front-matter:
```yaml
---
config:
  priority: [frontmatter, files, blocks]   # frontmatter wins
---
```

Override in code:
```scala
val loader = ConfigLoader.fromFrontmatter(yaml)
  .withPriority(List(Priority.Frontmatter, Priority.Files, Priority.Blocks))
```

### 22.5 JavaScript and Scala binding

**JavaScript** — select strategy via `config.js-binding`:

```yaml
config:
  js-binding: bake         # embed as const __ssc_config = {...}  (default)
  js-binding: process-env  # use process.env.KEY  (Node.js)
  js-binding: runtime      # load window.__SSC_CONFIG || config.json
```

**Scala** — select strategy via `config.scala-output`:

```yaml
config:
  scala-output: embedded          # val __ssc_config: Map[String, Any] = Map(...)  (default)
  scala-output: application.conf  # write TypesafeConfig application.conf alongside .sc
  scala-output: object            # generate object AppConfig { val port: Int = 8080 }
```

### 22.6 Hot reload

In `ssc watch` mode, changes to any external config file referenced in front-matter
automatically trigger a reload — no restart needed.

### 22.7 Full example

```ssc
---
databases:
  prod:
    url:      "${env:DB_URL | jdbc:sqlite:./dev.db}"
    password: "${sops:db.password}"
config:
  server:
    port: 8080
    host: "0.0.0.0"
  files: [app.yaml]
  js-binding: bake
  priority: [blocks, files, frontmatter]
---

```yaml config "feature-flags"
dark-mode: true
beta-users: [alice, bob]
```

```scala
case class ServerConfig(port: Int, host: String) derives Config
case class Flags(darkMode: Boolean) derives Config

val srv   = config[ServerConfig]("server").fold(throw _, identity)
val flags = config[Flags]("feature-flags").fold(throw _, identity)

println(s"Serving on ${srv.host}:${srv.port}")
println(s"Dark mode: ${flags.darkMode}")
```
```

---

## 23. Progressive Web App (`std.pwa`)

Turn any `.ssc` web app into a PWA in two lines.  The `pwa(...)` call
registers `GET /manifest.json` (W3C Web App Manifest) and `GET /sw.js`
(a precaching service worker) as ordinary routes — **before** `serve(port)`.

Works identically in `ssc run` (interpreter) and `ssc run-jvm` (JVM codegen).

### 23.1 Setup

Add `std.pwa` to your `requires:` front-matter and call `pwa(...)`:

```scalascript
---
requires:
  - std.http
  - std.pwa
---

pwa(
  name            = "My App",
  shortName       = "App",
  description     = "A ScalaScript web app",
  themeColor      = "#4285F4",
  backgroundColor = "#ffffff",
  display         = "standalone",   // standalone | fullscreen | minimal-ui | browser
  startUrl        = "/",
  icons           = List("/icon-192.png", "/icon-512.png"),
  precache        = List("/", "/styles.css", "/app.js"),
)

route("GET", "/") { _ => Response.html("...") }
serve(8080)
```

All parameters except `name` are optional.

### 23.2 Frontend wiring

Add to your HTML `<head>`:

```html
<link rel="manifest" href="/manifest.json">
<meta name="theme-color" content="#4285F4">
```

Add before `</body>`:

```html
<script>
  if ('serviceWorker' in navigator)
    navigator.serviceWorker.register('/sw.js');
</script>
```

### 23.3 Service worker strategy

Phase 1 implements **cache-first with precaching**: URLs in `precache` are
fetched and cached on SW `install`; every `GET` is served from cache if
present, falling back to the network otherwise.  Old cache versions are
cleaned up on `activate`.

Network-first, stale-while-revalidate, and push notifications are
Phase 2 features.

### 23.4 Registered routes

| Route | Content-Type | Description |
|-------|--------------|-------------|
| `GET /manifest.json` | `application/manifest+json` | W3C Web App Manifest built from `pwa(...)` config |
| `GET /sw.js` | `application/javascript` | Precaching service worker |

### 23.5 Icon sizing

`icons` entries are matched against `192` / `512` in the URL to auto-assign
the `sizes` field in the manifest.  Serve your icons as static files in the
same directory you run `ssc` from.

### 23.6 Full example

See [examples/pwa/pwa-demo.ssc](../examples/pwa/pwa-demo.ssc) and
[specs/pwa-plugin.md](pwa-plugin.md) for the full spec and architecture notes.

---

## 24. NFC NDEF (`std.nfc`)

`std.nfc` exposes portable Near Field Communication status, NDEF read/write
declarations, and helper constructors for text, URI, and MIME NDEF records.
Regular `.ssc` code uses `NfcCapabilities`, `NfcScanOptions`, `NdefRecord`,
and `NdefMessage`; Android `android.nfc.*`, Apple Core NFC, and browser
`NDEFReader` objects remain behind backend/plugin adapters.

Phase 1 is intentionally honest. The compiler-free native lane is selected
explicitly with `ssc-provider nfc`; on a JVM host without a device adapter it
returns `supported = false` from `nfcCapabilities()` while all text, URI, and
MIME record constructors execute identically on VM and direct ASM. `readNdef()`
and `writeNdef()` fail with a bounded provider diagnostic until a native
Android/iOS/Web adapter is selected. Plain `ssc` does not load the provider or
its capability globals. Compiler/tools compatibility keeps its existing
interpreter plugin behavior.

```scalascript
[nfcCapabilities, nfcPermissionStatus, textRecord, uriRecord, mimeRecord](std/nfc.ssc)

val caps = nfcCapabilities()
println("NFC supported: " + caps.supported)
println("Permission: " + nfcPermissionStatus())

val text = textRecord("Hello from ScalaScript", "en", None)
val link = uriRecord("https://scalascript.example/nfc", None)
val raw  = mimeRecord("application/octet-stream", List(1, 2, 3, 255), None)
```

Use `Feature.NfcNdef` for NDEF read/write/status. Raw tag communication
(`Feature.NfcTagTech`) and card emulation / presentment
(`Feature.NfcCardEmulation`) are deliberately separate future capabilities
because Android, iOS, and Web NFC expose different policy and entitlement
surfaces.

Native packaging has a shared requirements contract for NFC: `Capability.NfcNdef`
maps to iOS `NFCReaderUsageDescription` + the NDEF reader-session entitlement,
Android `android.permission.NFC` + `android.hardware.nfc`, and Web NFC's secure
context / user-activation permission model. Full SwiftUI/iOS, Android, and
Web/PWA packager consumption of that contract is still a follow-up; the
explicit JVM host provider remains deterministic unsupported for hardware I/O.

See [examples/nfc-ndef.ssc](../examples/nfc-ndef.ssc) and
[specs/std-nfc.md](../specs/std-nfc.md).

---

## Quick Reference

### CLI

```bash
ssc run file.ssc                    # interpret
ssc run file.ssc -- arg1 arg2       # v2 VM program args
ssc run --target jvm file.ssc       # compile + run on JVM (scala-cli)
ssc run-js file.ssc                 # compile + run with node
ssc run-js --v2 file.ssc            # opt-in v2 CoreIR JS lane
ssc watch file.ssc                  # watch mode
ssc repl                            # REPL
ssc test file.ssc                   # run tests
ssc fmt file.ssc                    # format .ssc files
ssc emit-js file.ssc                # transpile to JS
ssc emit-lib --host js --feature optics -o dir/   # standalone host library
ssc emit-spa file.ssc               # SPA bundle
ssc emit-wc file.ssc                # Web Components
ssc build myapp.ssc                 # build project file → dist/
ssc build                           # auto-discover .ssc in cwd
ssc build src/                      # dir-walk mode (backward compat)
ssc package myapp.ssc               # build all targets: from frontmatter
ssc install [--prefix <dir>]        # install ssc to ~/.local
ssc plugin install X                # install plugin
```

### Emitting standalone host libraries (`emit-lib`)

`ssc emit-lib` packages a ScalaScript feature as a **native library for a host ecosystem**, with no
`.ssc` or ScalaScript-runtime dependency at the consumer's edge (see
[`docs/polyglot-libraries`](../specs/polyglot-libraries.md)). The first host+feature is **JS optics**:

```bash
ssc emit-lib --host js --feature optics -o build/optics
# Wrote build/optics/package.json
# Wrote build/optics/index.mjs
# Wrote build/optics/optics.d.ts
```

This produces a self-contained `@scalascript/optics` npm ESM package — the Lens/Optional/Traversal/Prism
runtime plus a curated `optics.d.ts` — usable directly from JS/TypeScript:

```javascript
import { makeLens, makeOptional, field, index } from './build/optics/index.mjs';
const l = makeLens(['a', 'b']);
l.get({ a: { b: 5 } });            // 5
l.set({ a: { b: 5 } }, 9);         // { a: { b: 9 } }  (immutable)
```

For the **JVM** host it emits a buildable `ssc-optics` sbt project (a native Scala optics
implementation over dynamic JSON-like values — `Map[String, Any]` / `List` / `Option`):

```bash
ssc emit-lib --host jvm --feature optics -o build/optics-jvm
# build.sbt, src/main/scala/ssc/optics/Optics.scala, README.md
```
```scala
import ssc.optics.Optics.*
val l = makeLens(List("a", "b"))
l.get(Map("a" -> Map("b" -> 5)))            // 5
l.set(Map("a" -> Map("b" -> 5)), 9)         // Map(a -> Map(b -> 9))
```

For the **Rust** host it emits a dependency-free `ssc-optics` crate (`cargo build`) — a native Rust
optics implementation over a dynamic `Value` enum (`Obj`/`Arr`/`Opt`/`Str`/`Int`/`Bool`/`Null`):

```bash
ssc emit-lib --host rust --feature optics -o build/optics-rust
# Cargo.toml, src/lib.rs, README.md
```
```rust
use ssc_optics::*;
let l = make_lens(vec!["a".to_string(), "b".to_string()]);
l.get(&s);                    // Value::Int(5)
l.set(&s, Value::Int(9));     // { a: { b: 9 } }  (immutable)
```

For the **Java** host it emits a dependency-free `ssc-optics` Maven project (Java 17+) — a native Java
optics implementation over a dynamic `Object` (`Map<String,Object>` / `List<Object>` / `Optional<Object>`):

```bash
ssc emit-lib --host java --feature optics -o build/optics-java
# pom.xml, src/main/java/ssc/optics/Optics.java, README.md
```
```java
import static ssc.optics.Optics.*;
var l = makeLens(List.of("a", "b"));
l.get(s);                    // 5
l.set(s, 9);                 // { a: { b: 9 } }  (immutable)
```

Flags: `--host <js|jvm|rust|java>` (default `js`), `--feature <optics>` (default `optics`), `-o <dir>`
(default `./<feature>-<host>-lib/`), `--version <semver>` (default `0.1.0`). Supported today: `--host
js|jvm|rust|java --feature optics` — all four optics hosts ship (idiomatic typed/macro Scala optics
follow the same shape).

### Key Environment Variables

| Variable | Purpose |
|----------|---------|
| `SSC_SESSION_SECRET` | HMAC secret for signed cookie sessions |
| `SSC_JWT_SECRET` | JWT signing secret (falls back to session secret) |
| `SSC_STORAGE_PATH` | Default path for Storage effect JSON file |
| `SSC_NO_NETWORK` | Set to `1` to disable URL imports |

### JVM System Properties as Config

When running on the JVM (interpreter, `ssc run`, or a packaged JAR), all system properties
with the prefix `scalascript.` or its alias `ssc.` are injected as the highest-priority
config layer — overriding sidecar files, fenced config blocks, and front-matter.

```bash
# Override the frontend framework for an already-compiled JAR
java -Dscalascript.frontend=vue -jar myapp.jar

# Override any config key (dotted keys become nested maps)
java -Dscalascript.server.port=9090 -jar myapp.jar
java -Dssc.features.darkMode=true   -jar myapp.jar

# Same with ssc run (interpreter path)
ssc run -J-Dscalascript.frontend=vue myapp.ssc
```

See [`specs/config-system.md`](config-system.md) §2.4 and §3.1 for the full priority order.

## 24. REPL Debugger (v1.34)

`ssc repl` has a built-in interactive debugger — no IDE or TCP connection
needed.  Set breakpoints and step through your snippets directly from the
`ssc>` prompt.

### 24.1 Setting breakpoints

```
ssc> :break 2           # break at line 2 of the next snippet
[break] set at line 2

ssc> :break list        # list active breakpoints
[break] lines: 2

ssc> :break clear       # clear all breakpoints
[break] all breakpoints cleared
```

### 24.2 Running a snippet with a breakpoint

```
ssc> :break 2
[break] set at line 2

ssc> val x = 10
   | val y = x * 2
   | y + 1
   |
[stopped] at line 2
  > val y = x * 2

(debug) :locals
  x = 10

(debug) :next
[stopped] at line 3
  > y + 1

(debug) :continue
=> 21
```

### 24.3 Step mode

```
ssc> :step              # stop at every line of the next snippet
[step] step-in enabled — enter your snippet

ssc> 1 + 2
   |
[stopped] at line 1
  > 1 + 2
(debug) :continue
=> 3
```

### 24.4 Commands at the `(debug)` prompt

| Command | Alias | Description |
|---|---|---|
| `:continue` | `:c` | Resume to next breakpoint or end |
| `:next` | `:n` | Step over to next line |
| `:step` | `:s` | Step into next expression |
| `:out` | | Step out of current function |
| `:locals` | `:l` | Show local variables |
| `:stack` | `:bt` | Show call stack |
| `:print <expr>` | | Evaluate expression in current frame |
| `:help` | `:h` | Show all debug commands |
| `:quit` | `:q` | Stop snippet, return to `ssc>` |

See [specs/repl-debugger.md](repl-debugger.md) for the full reference including
threading model, `:print` semantics, and known limitations.

---

## 25. SwiftUI / iOS / macOS Targets

Declare `frontend: swiftui` with a reverse-DNS `bundle-id` in top-level
front-matter to target Apple platforms. `ssc-tools` lowers the checked v2 program to
AppCore plus a deterministic multi-platform Xcode application target and
delegates build/run/package/publish to Xcode, `ios-deploy`, and fastlane.

````ssc
---
name: my-app
version: 1.0.0
build-version: 1
bundle-id: com.example.my-app
frontend: swiftui
---
````

### CLI commands

```bash
ssc-tools run --target ios                                # iOS Simulator
ssc-tools run --target ios --device --team-id TEAM        # physical device
ssc-tools package --target ios --team-id TEAM             # signed .ipa
ssc-tools publish --target ios --testflight --team-id TEAM \
  --api-key-path key.json                                 # TestFlight
ssc-tools package --target macos --distribution --team-id TEAM \
  --notary-profile profile                                # Developer-ID + DMG
ssc-tools publish --target macos --appstore --team-id TEAM \
  --api-key-path key.json                                 # Mac App Store
```

### View IR → SwiftUI mapping

| ScalaScript View IR | SwiftUI |
|---------------------|---------|
| `Column(...)` | `VStack` |
| `Row(...)` | `HStack` |
| `Text(s)` | `Text` |
| `Button(label, action)` | `Button` |
| `TextInput(value)` | `TextField` |
| `Toggle(value)` | `Toggle` |
| `Image(url)` | `AsyncImage` |
| `LazyList(items)` | `List` |

`ReactiveSignal[T]` lowers to `@State private var` in the generated Swift
source.

Full reference: [`specs/swiftui.md`](swiftui.md).

---

## 26. Deploy Plugin (`ssc deploy`)

The deploy plugin (`std/deploy-plugin`) adds a multi-target, topology-aware
deploy system driven by frontmatter in your `.ssc` file.  It is a CLI-time
plugin — no compile step; all work happens at `ssc deploy` time.

### Manifest blocks

Add up to four blocks inside the YAML frontmatter of your `.ssc` file:

```yaml
---
name: myapp
version: 0.1.0
# --- deploy targets ----------------------------------------------------------
deploy:
  api:
    kind: traditional
    transport: subprocess
    port: 8080
    jar: .ssc-artifacts/api.jar
  worker:
    kind: traditional
    transport: subprocess
    port: 9090
    jar: .ssc-artifacts/worker.jar
  web:
    kind: k8s
    cluster: my-cluster
    namespace: prod
    image: registry.example.com/web:latest

# --- multi-target groups -----------------------------------------------------
groups:
  local-stack:
    members: [api, worker]
    exec: parallel
    failure: rollback-all
  full-deploy:
    members: [api, web]
    exec: sequence
    deps:
      web: [api]
    failure: abort-remaining

# --- environments -------------------------------------------------------------
environments:
  local:
    purpose: local
    targets: [api, worker]
  production:
    purpose: production
    targets: [api, web]
    blue-green:
      switch: instant
      health-check: /_health
      bake-time: 5m
---
```

| Block | Purpose |
|-------|---------|
| `deploy:` | Per-target configuration. Required. |
| `groups:` | Named multi-target groups with exec mode + dependency DAG. |
| `environments:` | Named deploy environments with purpose axis + blue-green config. |
| `state:` | State-backend configuration (optional; defaults to no-op). |

### `ssc deploy` subcommands

```bash
ssc deploy myapp.ssc               # deploy default target (or all in default env)
ssc deploy myapp.ssc --env production  # deploy all targets for 'production' env
ssc deploy myapp.ssc --group local-stack  # deploy a named group
ssc deploy myapp.ssc --target api  # deploy a single named target
ssc deploy myapp.ssc --dry-run     # print plan without executing
ssc deploy myapp.ssc --verbose     # verbose output

ssc deploy myapp.ssc plan          # print execution plan (stages, deps, env)
ssc deploy myapp.ssc status        # report health of all targets
ssc deploy myapp.ssc envs          # list environments from the manifest
```

### Execution modes

| Mode | YAML | Behaviour |
|------|------|-----------|
| `parallel` | `exec: parallel` | All targets in one stage (virtual threads). |
| `sequence` | `exec: sequence` | Targets one at a time in listed order. |
| `pipeline` | `exec: pipeline` | Explicitly-specified stages; each stage is parallel. |

If `deps:` is provided, `parallel` and `sequence` both respect the DAG —
topology sort is computed with Kahn's algorithm, and cycle detection throws a
`DeployError` with `dag-cycle` in the message.

### Failure policies

| Policy | YAML | Behaviour |
|--------|------|-----------|
| `RollbackAll` | `failure: rollback-all` | On any failure, roll back all already-succeeded targets in the group. |
| `ContinueRemaining` | `failure: continue-remaining` | Skip dependents of the failed target; deploy everything else. |
| `AbortRemaining` | `failure: abort-remaining` | Stop immediately on first failure. |

### Target adapters (v1.52.1)

| `kind` | `transport` | Adapter | Status |
|--------|-------------|---------|--------|
| `traditional` | `subprocess` | `LocalSubprocessTarget` | ✓ implemented |
| `k8s` | *(any)* | `StubDeployTarget` | stub (v1.52.2+) |
| `faas` | *(any)* | `StubDeployTarget` | stub (v1.52.3+) |
| `static` | *(any)* | `StubDeployTarget` | stub (v1.52.4+) |

The `LocalSubprocessTarget` spawns `java -jar <jar>` and polls `/_health`
(HTTP 200) for up to 30 seconds before declaring the target healthy.

### Artifact kinds

`ArtifactKind` controls what `ssc deploy build` produces:

| String alias | Enum | Produces |
|---|---|---|
| `fat-jar` / `fatjar` | `FatJar` | Assembly JAR (default) |
| `thin-jar` | `ThinJar` | Thin JAR |
| `native` / `native-binary` | `NativeBinary` | GraalVM native binary |
| `node` / `node-bundle` | `NodeBundle` | Node.js bundle |
| `spa` / `spa-bundle` | `SpaBundle` | SPA dist directory |
| `oci` / `oci-image` | `OciImage` | OCI container image |
| `lambda` / `lambda-zip` | `LambdaZip` | AWS Lambda ZIP |
| `rsync` / `rsync-tree` | `RsyncTree` | Raw rsync tree |

### State backend

The `state:` block configures where deploy state is persisted (revision,
artifact hash, deploy time, outputs).  In v1.52.1 the only backend is the
no-op (in-memory, not persisted):

```yaml
state:
  backend: noop
```

### Environments and blue-green

`environments:` supports a `base:` key for inheritance and a `purpose:` axis:

| Purpose | YAML |
|---------|------|
| `local` | Development workstation |
| `test` | CI / ephemeral PR environments |
| `staging` | Pre-production |
| `production` | Live traffic |

`ssc deploy` automatically selects the `local`-purpose environment when no
`--env` flag is given (if one exists in the manifest).

### Full example

See [`examples/deploy.ssc`](../examples/deploy.ssc) for a complete annotated
manifest with 6 targets, 3 groups, and 4 environments.

---

## 27. Traditional Payment Processors (`payments-plugin`)

The payments plugin (`runtime/std/payments-plugin/`) provides a 14-method
`PaymentProvider` SPI and the fiat-aware `Money` type.  The Stripe adapter
(`runtime/std/payments-stripe/`) ships in v1.53.1; PayPal, Adyen, and Square
adapters follow in v1.53.2–v1.53.4.

### Money type

```scala
import scalascript.payments.money.{Money, Currency}

val price  = Money(4999L, Currency.USD)          // $49.99 — stored as Long minor units
val tax    = price * BigDecimal("0.20")           // HALF_EVEN banker's rounding
val total  = price + tax                          // $59.99 — throws CurrencyMismatch if currencies differ
val seats  = Money.allocate(total, List(1, 1, 1)) // [20.00, 19.99, 19.99] — no lost penny
```

### Stripe adapter

```scala
import scalascript.compiler.plugin.payments.*
import scalascript.payments.stripe.StripeProvider

val stripe = StripeProvider("sk_test_xxx", PaymentMode.Test)
// or: val stripe = PaymentProvider.named("stripe")   // ServiceLoader picks up StripeProvider
```

### One-time charge

```scala
stripe.createIntent(CreateIntentRequest(
  amount  = Money(4999L, Currency.USD),
  method  = Some(PaymentMethod.Card("pm_card_visa")),
  confirm = true,
)) match
  case PaymentIntent.Succeeded(_, _, charge) => println(s"Paid: ${charge.id.value}")
  case PaymentIntent.RequiresAction(_, _, c) => println(s"3DS2: ${c.redirectUrl}")
  case PaymentIntent.Failed(_, err, _)       => println(s"Error: ${err.getMessage}")
  case _                                     => ()
```

### Subscriptions

```scala
val plan = stripe.createPlan(CreatePlanRequest(Money(999L, Currency.USD), BillingInterval.Monthly()))
val sub  = stripe.subscribe(customerId, plan.id, SubscribeOpts(trialPeriodDays = Some(14)))
stripe.changeSubscription(sub.id, premiumPlanId, ProrationMode.CreateProration)
stripe.cancelSubscription(sub.id, atPeriodEnd = true)
```

### Webhooks

```scala
route("POST", "/webhooks/stripe") { req =>
  val wReq = WebhookRequest(req.headers, req.rawBody)
  stripe.webhookReceiver.handle(wReq, config.getString("stripe.webhook-secret")) {
    case PaymentEvent.PaymentIntentSucceeded(intent) => db.markPaid(intent.id.value)
    case PaymentEvent.DisputeCreated(dispute)        => alertOncall(dispute.id.value)
  }
  Response.ok
}
```

Webhook verification: `Stripe-Signature` HMAC-SHA256 + ±5-minute timestamp
tolerance.  The `SeenKeyStore` deduplicates replayed events (in-memory default;
Redis/Postgres backends in v1.53.7).

### Failure policies

| Error class | Behaviour |
|---|---|
| `CardDeclined(retryPolicy = RetryNow)` | Transient — retry with same idempotency key |
| `CardDeclined(retryPolicy = DoNotRetry)` | Permanent — do not retry |
| `AuthenticationRequired(challenge)` | 3DS2 redirect needed — pass `challenge.redirectUrl` to frontend |
| `ProviderUnreachable` | Network/PSP outage — retry with backoff |
| `RateLimitExceeded(retryAfter)` | Too many requests — wait and retry |

Full reference: [`specs/traditional-payments.md`](traditional-payments.md),
[`examples/traditional-payments.ssc`](../examples/traditional-payments.ssc).

---

## 28. GraalVM Native Binary

`ssc` can be compiled to a self-contained native binary with no JVM required
at runtime.

### Build

```bash
sbt "cli/graalvmNativeImage"
# → produces ssc-native in cli/target/
```

Distribute the single `ssc-native` binary — no JRE, no scala-cli wrapper
needed for the binary itself.

### Plugin bridge

When the native `ssc` encounters `--plugin foo.jar`, it automatically spawns
`ssc-plugin-host.jar` as a subprocess. The subprocess loads the plugin JAR via
`URLClassLoader` + `ServiceLoader` and communicates with the native process
over a wire protocol. **Plugin authors change nothing** — existing `.sscpkg`
and `.jar` plugins work without recompilation.

```bash
ssc-native --plugin my-plugin.jar run file.ssc
```

For building a plugin as a native binary itself, see
[`docs/native-plugin-guide.md`](native-plugin-guide.md).

---

## 29. Library Packages

ScalaScript libraries can be distributed as `.ssclib` archives. A library
archive contains a manifest, packaged `.ssc` sources, and optionally
precompiled public interface artifacts.

```bash
ssc package --lib --precompile --manifest ssclib-manifest.yaml -o dist/my-lib.ssclib
```

`--precompile` adds `.scim` files under `ir/` inside the archive. These files
capture the public interface shape used by compatibility checks and by future
import-time fast paths. Source files remain packaged under `src/`, so existing
source-based imports keep working.

To compare two library versions:

```bash
ssc check-compat dist/my-lib-1.0.ssclib dist/my-lib-1.1.ssclib
```

`check-compat` exits successfully when the new archive preserves the public
surface. It reports removed or changed public symbols when the new archive is
not compatible with the old one. If an archive has no precompiled `.scim`
artifacts, the command derives interfaces from packaged `src/*.ssc` files.

---

## 30. Restricted Quoted Macros

ScalaScript has a first restricted slice of v2 quoted macros for separately
compiled modules linked with `ssc link`.

```scala
inline def plusOne(x: Int): Int = ${ plusOneImpl('x) }

def plusOneImpl(x: Expr[Int])(using q: QuotedContext): Expr[Int] =
  '{ $x + 1 }
```

The current implementation records macro metadata in `.scim` interfaces and
expands direct quoted-expression bodies during link-time source merging. The
example shape above expands a downstream `plusOne(n)` call as if the linked
source contained `((x) => x + 1)(n)`.

Status: **partially implemented**. Supported now: `${ impl('x) }` entrypoints,
direct `'{ $x + ... }` quoted bodies, `MacroImpl` IR metadata, cross-module
link expansion, and interpreter `ssc run` parity for the same direct
quoted-body subset. In the interpreter, `Expr[A].asValue` returns the quoted
runtime value as `Option[A]`, and `Expr[A].asTerm` returns an opaque
`ScalaScriptTerm` value with `name` and `value` fields.

### Compile-time constant folding (`Expr.asValue match`)

A macro implementation can branch on whether its argument is a compile-time
constant. The `Some` branch is taken for a literal argument; the `None` branch
is the dynamic fallback:

```scala
inline def label(x: Int): String = ${ labelImpl('x) }

def labelImpl(x: Expr[Int])(using q: QuotedContext): Expr[String] =
  x.asValue match
    case Some(n) => Expr("literal: " + n.toString)
    case None    => '{ "dynamic: " + $x.toString }

println(label(7))   // literal: 7
```

On the interpreter (`ssc run`), `asValue` is always available, so a literal
argument takes the `Some` branch and the `${ }` splice unwraps the returned
`Expr` to its value. Cross-module `ssc link` const-folds the same call sites at
link time: a literal argument expands to the `Some` branch (with `Expr(e)`
unwrapped to `e`), and any other argument expands to the `None` direct quote.
See [examples/quoted-macro-constfold.ssc](../examples/quoted-macro-constfold.ssc).

Unsupported restricted macro forms fail explicitly. `${ impl(x) }` reports
that quoted macro entrypoints must pass quoted arguments such as
`${ impl('x) }`, and linker-time macro metadata rejects implementation bodies
that are not direct quoted expressions such as `'{ $x + 1 }`. Common
unsupported implementation shapes get targeted hints: an `x.asValue match` that
is not the `case Some(n) => … case None => …` shape reports that it cannot be
const-folded, a top-level `Expr(...)` body reports that direct quote syntax is
required today, and nested quotes or splices outside a direct quoted expression
explain the current restricted body shape.

Generated-backend execution: quoted macros now run on the **JVM and JS backends** too — a
pre-codegen pass (`MacroCodegen`) expands expandable macro call sites (direct
quotes and `Expr.asValue match` const-folds) to their beta-reduced expansion and
strips the macro definitions before `JvmGen` / `JsGen` run. Macro-free modules are
untouched. This works both **single-module** (macro defined and used in the same
file) and **cross-module** (macro defined in an imported `.ssc` and called from a
consumer), on JVM and JS.

A macro whose implementation is **interpreter-only** (its body is not a direct
quote `'{ … }` and not an `Expr.asValue match` — e.g. `x.asValue.getOrElse(…)` or
`x.asTerm`) runs on `ssc run` and on the v2 bridge run path (`ssc run --v2`), but
cannot compile to the JVM/JS backends. `ssc check` now warns about these up front
(`quoted macro \`name\` has an
interpreter-only implementation …`) instead of leaving you with a cryptic
target-compiler error.

Still planned: richer quoted-term construction inside macro implementations,
and source-positioned diagnostics.

---

## 31. Mirror-Based Custom Derives

The interpreter exposes a runtime Mirror value for declared product and sum
types. This lets user-defined typeclasses provide a `derived(m: Mirror)` method
and opt into `derives`.

```scala
trait Csv[A]:
  def header: String

case class CsvInstance(header: String) extends Csv[Any]

object Csv:
  def derived(m: Mirror): Csv[Any] =
    CsvInstance(m.elemLabels.mkString(","))

case class Person(name: String, age: Int) derives Csv

val csv = summon[Csv[Person]]
println(csv.header) // name,age
```

You can also summon Mirror metadata directly:

```scala
case class Person(name: String, age: Int)

val m = summon[Mirror.Of[Person]]
println(m.label)                    // Person
println(m.elemLabels.mkString("|"))  // name|age
println(m.elemTypes.mkString("|"))   // String|Int
```

Status: **partially implemented**. Product `Mirror.Of[T]` /
`Mirror.ProductOf[T]`, ordered labels/types, and custom `derived(m: Mirror)`
also run on the compiler-free ScalaScript 2.1 standard VM, direct-ASM, and
`build-jvm` path. The broader interpreter additionally supports
`Mirror.SumOf[T]`, `Mirror.of[T]`,
`label`, `fields`, `elemLabels`, `elemTypes`, `variants`, `isProduct`,
`isSum`, `fromProduct`, and `ordinal`. Still planned: source-level
`inline match` over `Mirror.Product/Sum`, richer compile-time tuple
operations, native sum Mirrors, and broader generated-backend edge cases.

---

### Feature Quick-Links

- Typed algebraic effects: §4, [specs/algebraic-effects.md](algebraic-effects.md)
- Algebraic effects: §4, `docs/architecture.md`
- Direct syntax: [docs/direct-syntax.md](direct-syntax.md)
- Coroutines + generators: [specs/coroutines.md](coroutines.md)
- Scala 3 control API: §4 above, [lexical macro contract](../specs/scala3-control-macros.md), [Scala/JVM host profile](../specs/scala3-bidirectional-control.md), [runnable Scala example](../v2/host/scala/control/src/test/scala/scalascript/controlapi/examples/ControlApiExample.scala)
- DSL authoring: [specs/dsl.md](dsl.md)
- Dataset / MapReduce: [specs/mapreduce.md](mapreduce.md)
- **SQL databases + secret management: §6, §6.2, [secret-resolvers.md](../secret-resolvers.md)**
- Apache Spark: §14 above, [specs/spark-streaming.md](spark-streaming.md), [specs/spark-lakehouse.md](spark-lakehouse.md), [specs/spark-catalog.md](spark-catalog.md), [specs/spark-mllib.md](spark-mllib.md)
- Actors + cluster: [specs/actors-dist.md](actors-dist.md), [specs/cluster-management.md](cluster-management.md)
- Frontend toolkit + framework SPI: [specs/frontend-toolkit-spec.md](frontend-toolkit-spec.md), [specs/frontend-framework-spi-plan.md](frontend-framework-spi-plan.md)
- x402 micropayments + wallet SPI: [specs/x402.md](x402.md), [specs/wallet-spi.md](wallet-spi.md), [specs/wallet-spi-scalajs.md](wallet-spi-scalajs.md), [specs/blockchain-spi.md](blockchain-spi.md), [specs/micropayment-spi.md](micropayment-spi.md)
- MCP: [specs/mcp.md](mcp.md)
- Metaprogramming: [specs/metaprogramming.md](metaprogramming.md), [specs/arch-metaprogramming-v2.md](arch-metaprogramming-v2.md)
- Error handling: [docs/error-handling.md](error-handling.md)
- Backend SPI: [specs/backend-spi.md](backend-spi.md)
- Compiler plugins with intrinsics: §21 above, `examples/plugins/crypto-plugin/`
- Config system (YAML/HOCON/JSON + typed binding): §22 above, [specs/config-system.md](config-system.md)
- Progressive Web App: §23 above, [specs/pwa-plugin.md](pwa-plugin.md)
- REPL debugger: §24 above, [specs/repl-debugger.md](repl-debugger.md)
- SwiftUI / iOS / macOS native targets: §25 above, [specs/swiftui.md](swiftui.md)
- **Deploy plugin (`ssc deploy`): §26 above, [`examples/deploy.ssc`](../examples/deploy.ssc)**
- **Traditional payment processors: §27 above, [specs/traditional-payments.md](traditional-payments.md), [`examples/traditional-payments.ssc`](../examples/traditional-payments.ssc)**
- GraalVM native binary: §28 above, [specs/native-platform.md](native-platform.md), [docs/native-plugin-guide.md](native-plugin-guide.md)
- Library packages: §29 above, [specs/arch-library-modularity.md](arch-library-modularity.md)
- Restricted quoted macros: §30 above, [specs/arch-metaprogramming-v2.md](arch-metaprogramming-v2.md)
- Mirror-based custom derives: §31 above, [specs/arch-metaprogramming-v2.md](arch-metaprogramming-v2.md)
