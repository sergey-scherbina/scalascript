# Backend-specific code blocks in `.ssc`

Status: **planned**.  Tracked as `backend-specific-blocks` milestone in `BACKLOG.md`.
Related specs: [`specs/arch-ffi.md`](arch-ffi.md), [`specs/std-fs-os.md`](std-fs-os.md),
[`specs/backend-spi.md`](backend-spi.md).

---

## 1. Core rule — platform types are a compile error in `.ssc`

ScalaScript is a **target-agnostic language**. A `.ssc` file compiles to all
backends (JVM, JS/Node, Browser, Rust, WASM, …). Any direct use of a
platform-specific type or function in regular `scalascript` fenced code is a
**compile-time error**, not a warning.

Examples of forbidden usage in regular `.ssc` code:

```
error: `java.io.File` is a JVM-only type and cannot appear in ScalaScript code.
       Use `std.fs` from the standard library instead, or isolate this code
       in a `java` or `scala` fenced block (see spec: backend-specific-blocks).

error: `process.env` is a JS/Node-only value and cannot appear in ScalaScript code.
       Use `std.os.env(key)` from the standard library instead, or isolate this
       code in a `javascript` fenced block.
```

**The correct paths, in order of preference:**

1. **Standard library first** — `std.fs`, `std.os`, `std.process`, `std.http`,
   `std.crypto`, etc.  Cross-backend by design.  Always the first choice.
2. **Plugin intrinsic** — complex or performance-sensitive ops implemented as
   `extern def` in `runtime/std/<feature>-plugin/`, one native impl per backend.
   See `AGENTS.md` §"New intrinsics always go to `runtime/std/` plugins".
3. **`@jvm` / `@js` / `@rust` / `@wasm` annotation** — lightweight one-liner FFI
   on an `extern def`.  See §4 (FFI annotations, extended).
4. **Backend-specific fenced block** — ad-hoc escape hatch for multi-line native
   code that cannot or should not become a named intrinsic.  See §2.

Options 3 and 4 are intentionally explicit and syntactically conspicuous so
that platform coupling never hides inside otherwise cross-backend `.ssc`.

---

## 2. Backend-specific fenced blocks

When platform-specific code is unavoidable, it is placed in a fenced block
**tagged with the target language**.  The compiler routes each block to exactly
the matching backend and ignores it for all others.

### 2.1 Supported block tags and their targets

| Fenced tag        | Backend              | Language / mechanism                         |
|-------------------|----------------------|----------------------------------------------|
| ` ```scala `      | JVM (JvmGen)         | Scala 3 source, compiled via scala-cli        |
| ` ```java `       | JVM (JvmGen)         | Java source, compiled via scala-cli           |
| ` ```javascript ` | JS / Node / Browser  | Emitted verbatim into the JS bundle           |
| ` ```rust `       | Rust backend         | Emitted verbatim into the Rust crate          |
| ` ```wasm `       | WASM backend         | WAT or Rust-WASM; backend decides             |

A block without a backend qualifier (plain ` ```scalascript `) is
**cross-backend** and subject to the platform-type ban in §1.

> **Status / width note (2026-07-21).** This spec is **planned**. The ` ```scala `
> row above describes the *intended* escape-hatch mechanism (real Scala 3 source
> compiled via scala-cli for the JVM target). **It is not wired up today:** a
> current ` ```scala ` fence runs through the ScalaScript engine, byte-identical
> to a ` ```scalascript ` fence, with `Int` at **64-bit**
> ([`numeric-widths.md`](numeric-widths.md); the width follows the BACKEND, not
> the fence tag). When the real-Scala route lands it will carry Scala's own
> **32-bit `Int`** — a *declared, separately-widthed* boundary that must be
> explicit (a `scala` block and a `scalascript` block exchanging integers would
> otherwise mean one word at two widths). See
> [`w5-int-width-findings.md`](w5-int-width-findings.md) for the measurement and
> the decision to keep this route as guarded scaffolding for now, and
> `tests/e2e/scala-fence-width-parity-smoke.sh` for the guard that fails loudly
> the moment a `scala` fence's output diverges from a `scalascript` fence's.

### 2.2 Syntax

Backend-specific blocks may appear anywhere in the document body where a
regular `scalascript` block may appear.  They are top-level declarations only
— not inline expressions, not nested inside other blocks.

```markdown
# My module

```scalascript
// cross-backend public API declaration
extern def currentPid(): Int
```

```scala
// JVM-only implementation
def currentPid(): Int = ProcessHandle.current().pid().toInt
```

```javascript
// JS/Node-only implementation
function currentPid() { return (typeof process !== 'undefined') ? process.pid : 0; }
```

```rust
// Rust-only implementation
fn current_pid() -> i64 { std::process::id() as i64 }
```
```

### 2.3 Scoping rules

- Declarations inside a backend block are **not visible** in `scalascript`
  blocks or in backend blocks for other targets.
- A backend block **may call** `extern def`s declared in a `scalascript` block
  in the same file — this is the standard pattern for implementing an extern.
- A `scalascript` block **may not** call a symbol that exists only in a backend
  block — compile error: "symbol only available on JVM backend".
- Multiple blocks for the same backend in the same file are **concatenated** in
  document order before compilation.

### 2.4 `java` fenced block

The `java` tag is a distinct tag from `scala` for JVM routing because:
- It signals intent: this is pure Java, no Scala syntax.
- Tooling (highlighting, linters) can apply Java-specific rules.
- Forward compatibility with scala-cli `//> using java-source` native compilation.

Both `java` and `scala` blocks in the same file are compiled together by the
JVM backend.  They may refer to each other's declarations.

---

## 3. Q&A — three design questions resolved

### Q1. How do `std.os`, `std.process`, and similar OS-level functions work cross-backend?

**Answer: they work everywhere via the `std.*` abstraction layer.**

`process.env` and `os.hostname()` are forbidden directly — but `std.os.env(key)`
and `std.os.hostname` are not, because they are cross-backend `extern def`s
with a native implementation registered for every target:

| Function            | JVM impl                          | JS/Node impl            | Browser impl                  | Rust impl               |
|---------------------|-----------------------------------|-------------------------|-------------------------------|-------------------------|
| `std.os.env(key)`   | `System.getenv(key)`              | `process.env[key]`      | `""` (no env)                 | `std::env::var(key)`    |
| `std.os.args`       | `sys.env` / JVM args              | `process.argv.slice(2)` | `[]` (no CLI args)            | `std::env::args()`      |
| `std.os.exit(code)` | `System.exit(code)`               | `process.exit(code)`    | `throw ExitException(code)`   | `std::process::exit()`  |
| `std.os.cwd`        | `System.getProperty("user.dir")`  | `process.cwd()`         | `"/"` (stub)                  | `std::env::current_dir()`|
| `std.os.platform`   | `Platform.Jvm`                    | `Platform.NodeJs`       | `Platform.Browser`            | `Platform.Native`       |
| `std.process.exec`  | `ProcessBuilder`                  | `child_process.execSync`| `ProcessError.NotSupported`   | `std::process::Command` |

The key insight: **the abstraction is not "pretend it works the same everywhere"
but "give every backend a correct, idiomatic implementation"**.  The browser
backend returns sensible stubs or throws `NotSupported` for operations that
fundamentally cannot exist (spawning processes, reading `/etc/hosts`).  The
`.ssc` user calls `std.os.env("PORT")` and gets the right thing on every target
without knowing which backend is running.

**What about `std.os.platform`?**  This is the designated escape hatch for the
rare case where `.ssc` code must branch on target:

```scalascript
import std.os.{platform, Platform}

val port: Int =
  if platform == Platform.Browser then 0    // no port concept in browser
  else std.os.env("PORT").toIntOption.getOrElse(8080)
```

This is cross-backend code — no backend block needed.  `std.os.platform` is
always available and always returns the compile-time-known `Platform` value.
The compiler may constant-fold the branch and dead-strip the browser path when
compiling for JVM.

**Rule:** if an operation makes sense on all backends (even if the semantics
differ), it belongs in `std.*` with per-backend impls.  If an operation is
genuinely impossible on a backend, it returns a typed `NotSupported` error —
never a runtime crash.

### Q2. How do the JS and WASM backends interact?

**Answer: WASM is a compilation target, not a JS layer.  They interact through
explicit boundary functions, not implicit sharing.**

There are two distinct compilation models:

#### Model A — standalone WASM (no JS host)

The `.ssc` file compiles directly to a WASM module.  No JS code involved.
WASM `wasm` fenced blocks provide low-level WAT or Rust-WASM snippets.
The resulting `.wasm` binary runs in any WASM runtime (browser, wasmtime,
wasmer, wasm-pack, Deno, Node WASM API).

#### Model B — WASM + JS glue (browser embed)

The Rust/WASM backend produces a `.wasm` + a thin `glue.js` via `wasm-bindgen`.
The `glue.js` is loaded by a JS host (browser page or Node.js).
`javascript` fenced blocks in the same `.ssc` file are emitted into the JS
host side.  `wasm` fenced blocks go to the WASM side.

The boundary between the two sides is declared with the `@wasmExport` /
`@wasmImport` annotations (see §4):

```scalascript
// declared in .ssc — implemented on the WASM side, callable from JS host
@wasmExport
extern def computeHash(input: String): String

// declared in .ssc — implemented on the JS host side, callable from WASM
@wasmImport("crypto.subtle.digest")
extern def jsDigest(algo: String, data: Bytes): Bytes
```

**Cross-calling rules:**

| Direction              | Mechanism                                         |
|------------------------|---------------------------------------------------|
| `.ssc` → WASM impl     | Normal extern def, `wasm` block provides the body |
| `.ssc` → JS host impl  | Normal extern def, `@wasmImport` wires it         |
| JS host → WASM export  | `@wasmExport` generates the WASM export table entry|
| WASM → JS host         | `@wasmImport` generates the WASM import table entry|

The `scalascript` blocks see neither side — they see only typed `extern def`s.
The compiler enforces that every `extern def` has at least one implementation
for the target being compiled.

**JS ↔ WASM data passing:**  all values crossing the boundary must be of a
type in the **WASM value types** set: `Int`, `Long`, `Double`, `Boolean`,
`String` (encoded as linear memory + length), `Bytes` (pointer + length).
Passing arbitrary ScalaScript types across the boundary is a compile error —
use serialisation or `Bytes` + codec.

#### Which model to use?

| Use case                         | Model       |
|----------------------------------|-------------|
| Compute-heavy library in browser | B (WASM + JS glue) |
| CLI tool / server                | A (standalone WASM) |
| Full web app with UI             | JS target (not WASM) |
| Edge / serverless (Cloudflare Workers, Fastly) | A or B |

Standalone WASM (Model A) and JS are **separate compilation targets** — a
single `.ssc` file can be compiled to either, but not both simultaneously.
If you need a WASM module exposed to a browser page, you compile to Model B
which produces both artefacts together.

### Q3. What do we do with the existing `@jvm` / `@js` FFI annotations? How do they interact with backend blocks and cross-backend `.ssc` code?

**Answer: extend to all four backends, keep as the lightweight path, define a
strict layering that preserves platform independence.**

#### Extension to `@rust` and `@wasm`

The annotation set expands from two to four:

```scalascript
@jvm("java.util.UUID.randomUUID().toString()")
@js("crypto.randomUUID()")
@rust("uuid::Uuid::new_v4().to_string()")
@wasm("/* WAT or wasm-bindgen expr */")
extern def randomUUID(): String
```

All four are optional independently.  The capability check applies: if you
compile for Rust and `@rust` is absent but `@jvm` is present, it is a compile
error unless a `rust` fenced block or plugin intrinsic provides the impl.

#### The four FFI layers and when to use each

```
Layer 0 — std.*                    cross-backend stdlib, always preferred
Layer 1 — plugin intrinsic         complex impl, full type safety, testable in isolation
Layer 2 — @jvm/@js/@rust/@wasm     one-liner inline expr on an extern def
Layer 3 — backend fenced block     multi-line native code, top-level only
```

Each layer is **strictly more powerful and strictly less portable** than the
one above.  You should use the lowest-numbered layer that meets your needs.

#### How they preserve platform independence

The key invariant: **a regular `scalascript` block never directly calls
platform code — it only calls `extern def`s**.  The `extern def` is the
abstraction boundary.  The implementation of that extern (whether via a plugin,
`@jvm` annotation, or fenced block) is always segregated from the cross-backend
body.

```scalascript
// CORRECT — platform independence is preserved
// The scalascript block calls only the extern def.
// The impl is segregated into annotations and/or fenced blocks.

@jvm("java.net.InetAddress.getLocalHost().getHostName()")
@js("require('os').hostname()")
@rust("hostname::get().unwrap().to_string_lossy().to_string()")
extern def hostname(): String          // ← abstraction boundary

val greeting = s"Hello from ${hostname()}"   // ← pure .ssc, no platform ref
```

```scalascript
// ALSO CORRECT — complex impl via fenced block, same boundary
extern def hostname(): String

// ...
```scala
def hostname(): String =
  java.net.InetAddress.getLocalHost.getHostName
```

// ...
```javascript
function hostname() { return require('os').hostname(); }
```
```

```scalascript
// WRONG — platform reference leaks into scalascript block
val h = java.net.InetAddress.getLocalHost.getHostName   // ← compile error
```

#### Interaction between annotations and fenced blocks

An `extern def` may have **both** `@jvm` annotation and a `scala` fenced block
— in that case the fenced block takes precedence for the JVM backend (the
annotation is ignored).  This allows gradual migration: start with a one-liner
annotation, later replace with a full fenced block without changing the call
site.

Precedence order (highest wins) per backend:

```
fenced block  >  @jvm/@js/@rust/@wasm annotation  >  plugin intrinsic (BackendRegistry)
```

An `extern def` with no implementation at any layer for the current compilation
target is a **compile error** (`E_NoBackendImpl`).

#### What annotations cannot do — the boundary must hold

Annotations are **not** a way to bypass the abstraction:

```scalascript
// WRONG — @jvm inline on a non-extern def
// (annotations only apply to extern defs)
@jvm("new java.io.File($0).readText()")
def readFile(path: String): String = ???    // compile error: @jvm requires extern def
```

```scalascript
// WRONG — importing a platform type and using it in scalascript block
import java.io.File    // compile error: E_PlatformType
@jvm("new File($0).exists()")
extern def exists(path: String): Boolean   // @jvm string is not parsed as ssc — ok
```

The string inside `@jvm("...")` is **opaque to the ScalaScript type-checker** —
it is a raw expression passed verbatim to the JVM backend.  The ban on
`java.*` applies only to parsed ScalaScript AST nodes, not to annotation
string literals.

---

## 4. FFI annotation specification (extended)

### 4.1 Full annotation set

```scala
// lang/core/src/main/scala/scalascript/ast/Annotation.scala
case class JvmInline(expr: String)  extends Annotation   // @jvm("...")
case class JsInline(expr: String)   extends Annotation   // @js("...")
case class RustInline(expr: String) extends Annotation   // @rust("...")
case class WasmInline(expr: String) extends Annotation   // @wasm("...")

// boundary annotations for WASM ↔ JS interop (§3 Q2)
case class WasmExport(name: String = "")  extends Annotation  // @wasmExport / @wasmExport("name")
case class WasmImport(path: String)       extends Annotation  // @wasmImport("module.fn")
```

### 4.2 Argument substitution

`$0`, `$1`, … map to extern def parameter names in order.  `$self` is
available for methods on a type (future).

```scalascript
@jvm("new java.io.File($0).exists()")
@js("require('fs').existsSync($0)")
@rust("std::path::Path::new($0).exists()")
extern def fileExists(path: String): Boolean
```

### 4.3 Capability check

If a `@jvm`-only extern (no `@js`, no `js` block, no js-plugin) is called in
a file compiled for JS → compile error.  Same for every backend.

Special case: if **all** of `@jvm`, `@js`, `@rust`, `@wasm` are present, the
extern is fully portable.  The `std.*` library itself uses this pattern
extensively.

### 4.4 `@interpreterUnsupported`

If an `extern def` has `@jvm`/`@js`/`@rust` but no interpreter
implementation (no plugin `NativeImpl`), annotate with
`@interpreterUnsupported("reason")`.  The interpreter will emit a clear error
rather than a null-pointer crash.

---

## 5. Compiler enforcement

### 5.1 Platform-type ban

The type-checker maintains **banned type prefixes** for cross-backend
`scalascript` blocks:

| Prefix              | Reason                                      |
|---------------------|---------------------------------------------|
| `java.*`            | JVM stdlib                                  |
| `javax.*`           | JVM stdlib                                  |
| `scala.*`           | Scala stdlib (JVM / ScalaJS only)           |
| `sun.*`             | JVM internal                                |
| `com.sun.*`         | JVM internal                                |

Import statements resolving to these prefixes in a `scalascript` block are
also compile errors.

The ban does **not** apply to:
- `@jvm("...")` string literals (opaque to the type-checker).
- `scala`, `java`, `javascript`, `rust`, `wasm` fenced blocks (these are
  intentionally native).

### 5.2 Capability gate

`extern def` with no implementation for the current target → `E_NoBackendImpl`
with a helpful message listing available options (annotation, fenced block,
plugin).

### 5.3 Backend block scoping

Symbol from a backend block referenced in a `scalascript` block →
`E_BackendSymbolLeak`: "symbol `Foo` is only available on the JVM backend."

---

## 6. Implementation plan

### Phase 1 — parser: `BackendBlock` AST node

**Task: `backend-blocks-p1-parse`**

Extend the parser to recognise backend-tagged fenced blocks as
`BackendBlock(tag: BackendTag, source: String)`.
`BackendTag` sealed trait: `Jvm | Java | JavaScript | Rust | Wasm`.
Parser test: mixed `scalascript` + all four backend tags parse correctly.

### Phase 2 — type-checker enforcement

**Task: `backend-blocks-p2-typecheck`**

- Banned-prefix check → `E_PlatformType`.
- Capability gate → `E_NoBackendImpl`.
- Backend-symbol-leak check → `E_BackendSymbolLeak`.
- Test: `tests/conformance/backend-blocks-platform-type-ban.ssc`.

### Phase 3 — JVM backend emission

**Task: `backend-blocks-p3-jvm`**

`JvmGen` emits `scala` blocks verbatim; `java` blocks as separate `.java`
sources via `//> using sources`.
Test: `currentPid()` via `scala` block; JVM target returns PID > 0.

### Phase 4 — JS backend emission

**Task: `backend-blocks-p4-js`**

`JsGen` emits `javascript` blocks verbatim after preamble.
Test: `currentPid()` via `javascript` block; Node.js returns `process.pid`.

### Phase 5 — Rust backend emission

**Task: `backend-blocks-p5-rust`**

`RustGen` emits `rust` blocks into `mod inline_native`.
Conformance snapshot.

### Phase 6 — extend `@rust` / `@wasm` annotations + `@wasmExport` / `@wasmImport`

**Task: `backend-blocks-p6-ffi-extend`**

Add `RustInline`, `WasmInline`, `WasmExport`, `WasmImport` annotation AST
nodes.  Wire `@rust("...")` into `RustGen`.  Wire `@wasmExport` / `@wasmImport`
into WASM backend boundary emission.
Update arch-ffi.md to reference this spec.

### Phase 7 — audit + flip ban to error

**Task: `backend-blocks-p7-audit`**

Enable ban as warning, surface all violations in `runtime/std/`, `examples/`,
`tests/conformance/`.  Migrate or isolate each one.  Flip to hard error.

---

## 7. Examples

### 7.1 Full cross-backend `hostname` via annotations

```scalascript
@jvm("java.net.InetAddress.getLocalHost().getHostName()")
@js("(typeof require !== 'undefined' ? require('os').hostname() : location.hostname)")
@rust("hostname::get().unwrap().to_string_lossy().to_string()")
extern def hostname(): String

val msg = s"Running on: ${hostname()}"
```

### 7.2 Complex JVM impl via fenced block

```markdown
```scalascript
extern def availableProcessors(): Int
```

```java
public class SysInfo {
  public static int availableProcessors() {
    return Runtime.getRuntime().availableProcessors();
  }
}
```

```scala
def availableProcessors(): Int = SysInfo.availableProcessors()
```

```javascript
function availableProcessors() {
  return navigator.hardwareConcurrency || 1;
}
```
```

### 7.3 Platform branch in cross-backend `.ssc` code (no backend block needed)

```scalascript
import std.os.{platform, Platform, env}

val port: Int = platform match
  case Platform.Browser => 0
  case _                => env("PORT").toIntOption.getOrElse(8080)
```

### 7.4 WASM ↔ JS boundary (Model B)

```markdown
```scalascript
@wasmExport
extern def computeFib(n: Int): Long

@wasmImport("js.console.log")
extern def jsLog(msg: String): Unit
```

```rust
// WASM side — compiled to .wasm
fn compute_fib(n: i32) -> i64 {
    if n <= 1 { return n as i64; }
    let (mut a, mut b) = (0i64, 1i64);
    for _ in 2..=n { let c = a + b; a = b; b = c; }
    b
}
```

```javascript
// JS host side — loaded alongside .wasm
const js = { console: { log: (msg) => console.log(msg) } };
```
```

### 7.5 WRONG — platform type in `scalascript` block

```markdown
```scalascript
// ERROR: java.io.File is JVM-only
def readLines(path: String): List[String] =
  scala.io.Source.fromFile(new java.io.File(path)).getLines().toList
```
```

```
error: `java.io.File` is a JVM-only type (E_PlatformType).
       Use `std.fs.readFile(path)` or isolate in a `scala` fenced block.
```

---

## 8. Non-goals

- `@swift` / `@dotnet` / `@python` annotations — deferred to backend specs.
- Reflection-based Java interop in `scalascript` blocks.
- Suppression annotation (`@allowPlatformType`) — rejected; there is no safe
  form of this.
- Mixing backend blocks as inline expressions — blocks are top-level only.
- Automatic type bridging across WASM ↔ JS boundary — caller is responsible
  for passing WASM-compatible value types.
