# Target Backends

ScalaScript compiles to multiple target platforms. This document describes the backend translation model and target-specific considerations.

## Design Principle

> **One source, many targets.** Source semantics are target-independent. Backends translate; they do not reinterpret.

The compiler pipeline produces a **Typed IR** (intermediate representation) that captures the full semantics of the program. Each backend translates this IR to target-specific code.

## Target Matrix

| Backend | Status | Runtime | Use Case |
|---------|--------|---------|----------|
| JVM (Scala-CLI) | **M1** | JVM 17+ | Server, CLI, scripting |
| JavaScript | **M3** | Browser/Node | Web apps, universal |
| WASM | Future | WASM runtime | Portable binary |
| Native | Future | OS native | Performance-critical |

## JVM Backend (Primary)

### Overview

The JVM backend generates Scala 3 code that runs via Scala-CLI. This provides:
- Mature, battle-tested runtime
- Excellent tooling and IDE support
- Java ecosystem interoperability
- Fast iteration via Scala-CLI scripting mode

### Translation Model

```text
.ssc source → Typed IR → Scala 3 source → JVM bytecode
```

**Module mapping:**
- ScalaScript module → Scala object
- Heading scopes → Nested objects
- Functions → Methods
- Types → Scala types

**Example:**

ScalaScript:
````markdown
---
name: math-utils
---

# MathUtils

```scala
def square(x: Int): Int = x * x
```
````

Generated Scala:
```scala
object MathUtils:
  def square(x: Int): Int = x * x
```

### Interop

JVM backend supports Java/Scala interop via facade declarations:

```scala
@js.native // Actually @jvm.native for JVM
trait JavaList[A]:
  def add(elem: A): Boolean
  def get(index: Int): A
  def size(): Int
```

### Configuration

In front-matter:
```yaml
scala:
  version: 3.3.0
  jvmTarget: "17"
  options:
    - "-Xmax-inlines:64"
```

## JavaScript Backend

### Overview

The JS backend generates ES modules that run in browsers or Node.js. Key features:
- Zero-install distribution (just serve the JS)
- DOM interop via facade types
- Async/Promise integration

### Translation Model

```text
.ssc source → Typed IR → JavaScript (ESM)
```

**Type mapping:**

| ScalaScript | JavaScript |
|-------------|------------|
| `Int` | `number` |
| `Double` | `number` |
| `String` | `string` |
| `Boolean` | `boolean` |
| `List[A]` | `Array` |
| `Map[K,V]` | `Map` |
| `Option[A]` | `A \| null` |
| `Unit` | `undefined` |

### Module Output

ScalaScript:
````markdown
---
name: greeter
---

# Greeter

```scala
def greet(name: String): String = s"Hello, $name!"
```
````

Generated JavaScript:
```javascript
// greeter.js
export function greet(name) {
  return `Hello, ${name}!`;
}
```

### Browser Interop

DOM access via facade types:

```scala
@js.native
object document:
  def getElementById(id: String): Element
  def createElement(tag: String): Element

@js.native
trait Element:
  var innerHTML: String
  def appendChild(child: Element): Unit
```

### Configuration

```yaml
js:
  moduleType: esm      # esm | commonjs | umd
  target: es2020       # es5 | es2015 | es2020 | esnext
```

## WASM Backend (Future)

### Goals

- Portable binary format
- Near-native performance
- Language-agnostic runtime

### Approach

Two possible strategies:

1. **Direct compilation**: SSC → WASM binary
2. **Via existing toolchain**: SSC → Scala → Scala Native → WASM

Strategy TBD based on ecosystem maturity.

## Native Backend (Future)

### Goals

- Direct machine code generation
- No runtime dependency
- Maximum performance

### Approach

Likely via Scala Native or LLVM backend.

## Conformance

### Semantic Guarantees

All backends must provide identical observable behavior for:
- Pure computations
- Data structure operations
- Pattern matching
- Type checking

### Allowed Divergence

Backends may differ in:
- Performance characteristics
- Memory layout
- FFI/interop mechanisms
- Platform-specific APIs

### Conformance Suite

The conformance suite (M4) defines tests that all backends must pass:

```text
conformance/
├── arithmetic/
│   ├── int-ops.ssc
│   ├── double-ops.ssc
│   └── expected.json
├── collections/
│   ├── list-ops.ssc
│   └── expected.json
└── ...
```

Each test specifies expected output; all backends must produce identical results.

## Backend Selection

### Automatic

Based on context:
- Running via Scala-CLI → JVM
- Browser `<script>` import → JS
- Explicit target flag → specified backend

### Manual

```bash
ssc compile --target=jvm myfile.ssc
ssc compile --target=js myfile.ssc
```

Or in front-matter:
```yaml
targets:
  - jvm
  - js
```

## Block Language Support

ScalaScript code blocks carry a language tag on the fence
(```` ```scalascript ````, ```` ```sql ````, etc.) that determines
how each backend processes the block.  The table below tracks
per-backend support for every block language currently in `Lang.scala`.

| Block lang        | Interpreter | JVM | JS / Scala.js | Node | WASM | Spark |
|-------------------|:-----------:|:---:|:-------------:|:----:|:----:|:-----:|
| `scalascript` / `ssc` | ✅          | ✅  | ✅            | ✅   | ✅   | ✅    |
| `scala`           | ✅          | ✅  | ✅            | ✅   | ✅   | ✅    |
| `html`            | ✅ (`<id>.html` String binding) | ✅ | ✅ | ✅ | ✅ | ✅ |
| `css`             | ✅ (`<id>.css` String binding)  | ✅ | ✅ | ✅ | ✅ | ✅ |
| `javascript` / `js` | ✅ (String value) | ✅ | ✅ (spliced into output) | ✅ | ✅ | ✅ |
| `node.js` / `node` | ❌ (`UnknownBlockLanguage`) | ❌ | ❌ | ✅ (linked into bundle) | ❌ | ❌ |
| `sql`             | ✅ (JDBC via `backend-sql-runtime`) | ✅ (emits `SqlRuntime.execute`) | ❌ | ❌ | ❌ | ✅ (Spark SQL) |

When a backend doesn't claim a block language, `CapabilityCheck`
emits a `Diagnostic.UnknownBlockLanguage(<lang>)` so the user gets a
precise diagnostic at compile time instead of silently dropping the
block.  The mechanism is wired generically via `Lang.isOpaqueExec` —
adding a new opaque-exec block lang only requires updating
`ast.Lang.scala` plus the producing backend's
`Capabilities.blockLanguages`; every other backend automatically
starts rejecting it.

### v1.26 — `sql` block specifics

The `sql` block is parameterised executable (SPEC § 3.3.1):
every `${expr}` is rewritten to a JDBC `?` bind parameter by the
shared `transform/SqlBindRewriter`, with no string-substitution
escape hatch.  Two backends consume the same rewriter:

  - **Interpreter / JVM** — `SqlBindRewriter.rewriteJdbc` → JDBC
    `PreparedStatement` via `scalascript.sql.SqlRuntime.execute`.
    H2 + SQLite drivers ship bundled; Postgres / MySQL / Oracle /
    MSSQL come in via `dep:` front-matter imports.
  - **Spark** — `SqlBindRewriter.rewriteSparkSql` → named
    `:bind<N>` placeholders consumed by `spark.sql(text, args)`.

Connection resolution on the JVM/Interpreter path: front-matter
`databases:` map → `ConnectionRegistry`; `given Connection` in scope
overrides the registry.  See `docs/postgres.md` for the
`client-postgres` library that complements this with a Future-based
async API for end-user scalascript code.

## Adding New Backends

To add a new backend:

1. Implement `Backend` trait with `translate(ir: TypedIR): TargetCode`
2. Define type mappings for all core types
3. Implement standard library stubs
4. Pass full conformance suite
5. Document target-specific considerations

```scala
trait Backend:
  def name: String
  def translate(module: TypedModule): TargetOutput
  def typeMapping: Map[SSCType, TargetType]
```
