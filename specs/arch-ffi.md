# Lightweight FFI — @jvm / @js / @rust / @wasm Inline Annotations + glue.jar in .ssclib

Status: **implemented** (reconciled 2026-06-18 — the prior "planned" was stale).
Tier 1 `@jvm` / `@js` inline annotations (Phases 1–2) and Tier 2 `jvm/glue.jar` /
`js/glue.js` in `.ssclib` (Phases 3–4) are built, with `@rust` annotation wiring in
`RustGen` on top. Tests: `FfiAnnotationTest`, `FfiPhase2Test` (interpreter),
`RustFfiAnnotationTest` (Rust); `@jvm`/`@js` codegen in `JvmGen`/`JsGen`
(`extractAnnotationArg`), `CapabilityCheck` wrong-backend errors, glue artifacts via
`GlueClasspathRegistry` / `GlueJsPreambleRegistry`; example
`examples/js-glue-component.ssc`. **`@wasm("expr")` is now wired too** (2026-06-18):
the WASM backend (`runtime/backend/wasm`, Scala.js → `.wasm` via
`scala-cli --js-emit-wasm`) **exists** — the prior "no WASM target" note was stale.
`WasmGen` lowers `@wasm("expr")` externs to a real `def` (`$0`/`$1` substitution, FFI
annotations stripped) and drops unimplemented externs; it also inlines local `.ssc` imports
(transitively, deduped) and expands quoted macros, so cross-module wasm compiles
(`WasmBackendTest`, incl. a `@wasm`-extern end-to-end to a real `.wasm`). Inline FFI
does not own effect/control semantics: managed effects, handlers, capture, and saved
continuations are governed by [`control-interoperability.md`](control-interoperability.md)
and its target profiles. **Residual:**
`@wasmExport` / `@wasmImport` (the raw **WASM ABI** import/export boundary) remain out of
scope **by design** — the backend routes through Scala.js, which *owns* the wasm ABI, so a
hand-controlled `@wasmExport`/`@wasmImport` doesn't map onto this path (it would need a
direct-emit wasm backend, not the Scala.js one). Tracked as `arch-ffi` milestone in `BACKLOG.md`.
Companion: [`specs/arch-library-modularity.md`](arch-library-modularity.md),
[`specs/arch-stable-spi.md`](arch-stable-spi.md),
[`specs/backend-specific-blocks.md`](backend-specific-blocks.md)
(full backend-block + FFI-annotation layering, including `@rust`, `@wasm`,
`@wasmExport`, `@wasmImport`), and
[`wasm-wasi-control-runner.md`](wasm-wasi-control-runner.md) (runner-only saved
control; it does not imply a raw WASM host ABI).

---

## 1. Goals

- **Tier 1 — inline annotations**: a library author calls a Java or JS API
  with a single `@jvm("expr")` / `@js("expr")` annotation on an `extern def`,
  no separate Scala project required.
- **Tier 2 — glue artifact in `.ssclib`**: for complex FFI (callbacks, type
  adapters, stateful wrappers), an optional `jvm/glue.jar` or `js/glue.js`
  inside a `.ssclib` archive is discovered and loaded automatically.  This
  **unifies `.ssclib` and `.sscpkg`** into one format: a pure-ScalaScript
  library is `.ssclib` without a `jvm/` dir; a library with native glue is
  `.ssclib` with one.
- **Close the `std/` privilege gap**: community libraries gain the same
  ability to call Java / JS APIs that `runtime/std/` plugins have today,
  without depending on `scalascript.interpreter.*` internals.

## 2. Non-goals

- Calling arbitrary C / native code (WASM / JNI) — out of scope for v1.x.
- `@wasm("...")` — **DONE 2026-06-18**: `WasmGen` lowers it to a `def` on the
  Scala.js → wasm path (the expression is Scala/Scala.js source, like `@jvm`).
  `@wasmExport` / `@wasmImport` (raw WASM ABI) stay out of scope by design — the
  Scala.js path owns the wasm ABI (see Status above).
- Reflection-based Java interop (`Class.forName`, dynamic proxies).
- Replacing the existing `.sscpkg` / `BackendRegistry` path for first-party
  std plugins — they keep using their current architecture.
- Type-safe mapping between Java types and ScalaScript types — Tier 1 is
  deliberately untyped (string expressions); Tier 2 lets the glue JAR handle
  type conversion.

Consequently this FFI cannot by itself satisfy a host control profile's typed
bidirectional value-and-call bridge. Raw `@jvm`/`@js`/`@rust`/`@wasm` frames and
glue callbacks are `ForeignBarrier` unless the relevant host profile adopts them
through a generated managed descriptor/transform. Opaque host values are
`Unsavable`; exact artifact mode never makes them durable. `NativeImpl`, glue, and
`SpiValue`/`AnyRef` paths are adapters, not capsule or wire representations.

## 3. Current state — the privilege gap

```
std/ plugin author:
  1. Write Scala class implementing Backend + NativeImpl
  2. Register via META-INF/services
  3. Add to build.sbt in 5 places
  → Calls any Java API ✓

Community library author:
  1. …no path that doesn't require the above
  → Can only use ScalaScript builtins ✗
```

Tier 1 closes this for simple cases (one-liner Java/JS expressions).
Tier 2 closes it for everything else without requiring knowledge of
`BackendRegistry` or `NativeContext`.

## 4. Architecture

### 4a. Tier 1 — `@jvm` / `@js` annotations

#### Syntax

```scala
@jvm("java.util.UUID.randomUUID().toString()")
@js("crypto.randomUUID()")
extern def randomUUID(): String

@jvm("System.currentTimeMillis()")
@js("Date.now()")
extern def nowMillis(): Long

// JVM-only: calling on JS backend is a compile error
@jvm("java.lang.Runtime.getRuntime().availableProcessors()")
extern def cpuCount(): Int

// Argument substitution: $0, $1, ... map to the extern def parameters
@jvm("new java.io.File($0).exists()")
@js("(typeof require !== 'undefined' ? require('fs').existsSync($0) : false)")
extern def fileExists(path: String): Boolean

// Multi-arg
@jvm("$0 + $1")
@js("$0 + $1")
extern def concat(a: String, b: String): String
```

#### Annotation definition

```scala
// lang/core/src/main/scala/scalascript/ast/Annotation.scala
case class JvmInline(expr: String) extends Annotation   // @jvm("...")
case class JsInline(expr: String)  extends Annotation   // @js("...")
```

Parsed from `@jvm("...")` syntax by the annotation parser (same path as
existing `@deprecated`, `@experimental` annotations).

#### JVM codegen (`JvmGen.scala`)

When emitting an `extern def` with a `JvmInline` annotation, instead of
looking up the intrinsic in `BackendRegistry`, emit the inline expression
directly as the method body:

```scala
// Input .ssc:
@jvm("java.util.UUID.randomUUID().toString()")
extern def randomUUID(): String

// Emitted Scala:
def randomUUID(): String = java.util.UUID.randomUUID().toString()
```

Argument substitution: `$0` → parameter name at index 0, `$1` → index 1, etc.

```scala
// Input:
@jvm("new java.io.File($0).exists()")
extern def fileExists(path: String): Boolean

// Emitted:
def fileExists(path: String): Boolean = new java.io.File(path).exists()
```

#### JS codegen (`JsGen.scala`)

Same pattern — emit `@js("...")` expression as function body:

```scala
// Emitted JS:
function randomUUID() { return crypto.randomUUID(); }
function fileExists(path) { return (typeof require !== 'undefined' ? require('fs').existsSync(path) : false); }
```

#### Interpreter (`EvalRuntime.scala`)

The interpreter has no Java/JS runtime, so `@jvm` / `@js` annotated externs
have no interpreter implementation by default.  Two options for library authors:

1. Also provide a `NativeImpl` via the standard plugin path (for interpreter
   support).
2. Annotate with `@interpreterUnsupported` — calling from interpreter → clear
   error: "this function requires JVM or JS backend".

#### Capability check (`CapabilityCheck.scala`)

If a `@jvm`-only `extern def` (no `@js`) is called in a file compiled for
the JS backend → compile error:

```
error: `fileExists` is marked @jvm only and cannot be called from JS backend.
       Provide a @js("...") alternative or use a JVM-only module.
```

#### Third-party Java libraries

For deps beyond `java.*`, the user adds them to front-matter:

```yaml
---
dependencies:
  - dep: com.google.guava:guava:32.0-jre
---
```

```scala
@jvm("com.google.common.hash.Hashing.sha256().hashString($0, java.nio.charset.StandardCharsets.UTF_8).toString()")
extern def sha256hex(input: String): String
```

The `//> using dep` directive generated by JVM codegen picks up the `dep:`
front-matter and adds it to the scala-cli invocation.  No change to existing
dependency resolution.

### 4b. Tier 2 — `jvm/glue.jar` and `js/glue.js` inside `.ssclib`

#### Extended `.ssclib` layout

```
my-lib-1.0.ssclib
  ssclib-manifest.yaml
  src/
    main.ssc          # public API — extern defs may reference glue classes
  jvm/
    glue.jar          # optional: pre-compiled JVM glue code
  js/
    glue.js           # optional: pre-compiled JS glue code (UMD module)
```

`ssclib-manifest.yaml` declares the glue:

```yaml
name: io.example/my-lib
version: 1.0.0
entry: src/main.ssc
glue:
  jvm: jvm/glue.jar
  js:  js/glue.js
```

#### JVM codegen — glue.jar on classpath

When `ImportResolver` unpacks a `.ssclib` containing `jvm/glue.jar`, it adds
the JAR to the compile/link classpath.  JVM-emitted code can call classes from
`glue.jar` directly — no `@jvm("...")` string needed:

```scala
// In main.ssc — calls a class from glue.jar
@jvm("io.example.internal.CryptoHelper.hashSha256($0)")
extern def sha256(input: String): String
```

Or, if the glue class is registered as a ScalaScript plugin via
`META-INF/services` inside `glue.jar`, it also contributes `NativeImpl`
entries to `BackendRegistry` at load time — giving the library interpreter
support without a separate build step.

#### JS codegen — glue.js injected as preamble

`js/glue.js` is injected verbatim before the emitted ScalaScript JS output
(same mechanism as `Backend.runtimePreamble` today).  The `.ssc` code can
then call functions exported by `glue.js`:

```js
// glue.js
function _mylib_sha256(input) {
  // ... implementation using webcrypto or a bundled hash lib
  return hashResult;
}
```

```scala
// main.ssc
@js("_mylib_sha256($0)")
extern def sha256(input: String): String
```

#### `ssc package --lib` with glue

```bash
# Builds and packages a library with JVM glue
ssc package --lib \
  --manifest ssclib-manifest.yaml \
  --jvm-glue target/glue.jar \   # pre-compiled by sbt/scalac
  --js-glue dist/glue.js \       # pre-compiled by node/esbuild
  --output dist/my-lib-1.0.ssclib
```

The library author is responsible for building `glue.jar` / `glue.js` with
their own toolchain (sbt, scalac, esbuild, rollup, etc.).  ScalaScript only
packages it.

#### Unification of `.ssclib` and `.sscpkg`

With Tier 2, the distinction between "pure ScalaScript library" and "plugin
with native glue" becomes:

| | `.ssclib` without glue | `.ssclib` with `jvm/glue.jar` |
|--|----------------------|-------------------------------|
| JVM code | No | Yes (pre-compiled) |
| JS code | No | Yes (pre-compiled) |
| Backend registration | No | Optional (via `META-INF/services` in glue.jar) |
| Distributed as | `.ssclib` | `.ssclib` |
| Discovery | `ssc search` / `dep:` | same |

`.sscpkg` remains for backwards compatibility and for first-party `std/`
plugins that use `BackendRegistry` APIs directly.  New community libraries
use `.ssclib` exclusively.

## 5. Migration

- `@jvm` / `@js` are new annotations — no existing code affected.
- `jvm/glue.jar` support in `ImportResolver` is additive.
- `.sscpkg` format is unchanged; no migration needed for existing plugins.
- Community plugin authors who currently use `.sscpkg` can optionally migrate
  to `.ssclib` + `jvm/glue.jar` — the result is equivalent for consumers.

## 6. Phases

### Phase 1 — `@jvm` / `@js` annotations + JVM codegen

- `JvmInline`, `JsInline` annotation AST nodes.
- Annotation parser handles `@jvm("...")` and `@js("...")` syntax.
- `JvmGen` emits inline expression as method body; `$N` substitution.
- `CapabilityCheck` errors on `@jvm`-only def called from JS backend.
- Tests: 10+ — `randomUUID()` with `@jvm` + `@js`; `fileExists()` JVM only;
  `$0` substitution; wrong-backend compile error; third-party dep in front-matter.
- Example: `examples/ffi-inline.ssc` demonstrating `@jvm` / `@js` patterns.

### Phase 2 — `@js` codegen + interpreter behaviour

- `JsGen` emits `@js("...")` expression as function body.
- Interpreter: `@interpreterUnsupported` annotation + clear error message.
- Cross-backend parity test: same `.ssc` compiles and runs on both JVM + JS
  (for defs with both `@jvm` + `@js`).

### Phase 3 — `jvm/glue.jar` in `.ssclib` + `ssc package --lib --jvm-glue`

- `ssclib-manifest.yaml` extended with `glue.jvm` / `glue.js` fields.
- `ImportResolver` unpacks `jvm/glue.jar` to cache; adds to JVM classpath.
- `scc package --lib` accepts `--jvm-glue <jar>` and `--js-glue <js>`.
- Tests: `.ssclib` with `glue.jar`; JVM codegen finds glue class on classpath;
  `ssc package --lib --jvm-glue` produces correct archive.
- Example: `examples/ffi-glue-jar/` — a mini library with a pre-compiled
  `glue.jar` that wraps a Java API.

### Phase 4 — `js/glue.js` injection + `META-INF/services` in glue.jar

- JS codegen injects `js/glue.js` as preamble.
- `glue.jar` `META-INF/services/scalascript.backend.spi.Backend` entries
  loaded into `BackendRegistry` at import time (optional interpreter support).
- Tests: JS output contains `glue.js` preamble before `.ssc`-generated code;
  interpreter loads `NativeImpl` from `glue.jar` ServiceLoader.

## 7. Testing strategy

- Phase 1: `FfiAnnotationTest` — for each `@jvm` pattern, parse annotation,
  verify `JvmGen` output matches expected Scala expression.
- Phase 2: `FfiJsTest` — `JsGen` output; interpreter error message test.
- Phase 3: integration test creating a temp `.ssclib` with a `glue.jar`
  fixture, resolving via `ImportResolver`, compiling a consumer `.ssc`.
- Phase 4: full end-to-end — `ssc build --backend js` with a `glue.js`-using
  library produces runnable JS output.

## 8. Open questions

1. **Multi-statement Tier 1**: `@jvm` / `@js` take a single expression.
   What if the implementation needs multiple statements?  Options: (a) require
   a helper method in `glue.jar` (use Tier 2); (b) allow block syntax
   `@jvm("""{ val x = ...; x.toString }""")`.  Recommendation: (a) — Tier 1
   is for one-liners; Tier 2 for anything complex.
2. **`@jvm` security**: embedding raw Java expressions in `.ssc` files is
   equivalent to `eval` in terms of safety surface.  Should there be a
   compiler flag `--allow-inline-ffi` that gates Tier 1?  Recommendation:
   yes, default `true` (FFI is opt-in by the library author, and the library
   is already untrusted code the user chose to import).
3. **Interpreter support for `@jvm`**: is it worth adding a reflection-based
   fallback for `@jvm` in the interpreter (use `Class.forName` + reflection
   to call the Java method)?  Recommendation: no for Phase 1 — reflection is
   slow and complex; explicit `@interpreterUnsupported` is cleaner.
4. **glue.jar signing**: should `ssc` verify a SHA-256 digest of `glue.jar`
   (stored in `ssclib-manifest.yaml`) before loading it on the classpath?
   Recommendation: yes, when `sha256:` pin is present in the `dep:` URI — same
   as the `DepCache` pinning mechanism.
