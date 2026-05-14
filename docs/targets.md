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
