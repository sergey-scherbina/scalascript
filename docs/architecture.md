# Architecture

This document describes the ScalaScript compiler pipeline and internal architecture.

## Pipeline Overview

```text
┌─────────────┐     ┌─────────────┐     ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Source    │────▶│   Lexer/    │────▶│   Parser    │────▶│   Typer     │────▶│  Backend    │
│   (.ssc)    │     │   Scanner   │     │             │     │             │     │             │
└─────────────┘     └─────────────┘     └─────────────┘     └─────────────┘     └─────────────┘
                          │                   │                   │                   │
                          ▼                   ▼                   ▼                   ▼
                       Tokens              Raw AST            Typed IR          Target Code
```

## Phase Details

### 1. Lexical Analysis (Lexer/Scanner)

**Input:** UTF-8 source text
**Output:** Token stream

The lexer operates in two modes:

1. **Markdown mode** (default): Recognizes headings, links, code fences, etc.
2. **Code mode**: Inside fenced blocks, uses Scala-like tokenization

**Key responsibilities:**
- Handle Markdown structure tokens (`#`, `[`, `]`, `` ``` ``, etc.)
- Detect and extract YAML front-matter
- Track indentation for code blocks
- Produce unified token stream

**Token types:**
```scala
enum Token:
  // Markdown
  case Heading(level: Int, text: String)
  case CodeFenceStart(lang: Option[String])
  case CodeFenceEnd
  case LinkStart, LinkEnd, LinkTargetStart, LinkTargetEnd
  case Text(content: String)
  case Newline, Indent, Dedent

  // Code (inside fenced blocks)
  case Identifier(name: String)
  case IntLiteral(value: Int)
  case StringLiteral(value: String)
  case Keyword(kw: Keyword)
  case Operator(op: String)
  // ... etc
```

### 2. Parsing

**Input:** Token stream
**Output:** Raw AST (untyped)

The parser builds an AST that preserves both Markdown structure and code semantics.

**AST node types:**

```scala
// Document structure
case class Module(
  manifest: Option[Manifest],
  sections: List[Section]
)

case class Section(
  heading: Heading,
  content: List[Content],
  subsections: List[Section]
)

enum Content:
  case Prose(text: String, interpolations: List[Interpolation])
  case CodeBlock(lang: String, ast: List[Statement])
  case Import(path: ModulePath, bindings: List[Binding])
  case DataList(items: List[ListItem])

// Code AST
enum Expr:
  case Literal(value: Any, tpe: Option[Type])
  case Ident(name: String)
  case Apply(fn: Expr, args: List[Expr])
  case Lambda(params: List[Param], body: Expr)
  case If(cond: Expr, thenp: Expr, elsep: Option[Expr])
  case Match(scrutinee: Expr, cases: List[CaseClause])
  case Block(stats: List[Statement], expr: Expr)
  // ... etc

enum Statement:
  case ValDef(name: String, tpe: Option[Type], rhs: Expr)
  case DefDef(name: String, params: List[Param], retTpe: Option[Type], body: Expr)
  case TypeDef(name: String, params: List[TypeParam], rhs: Type)
  case ClassDef(...)
  case ExprStatement(expr: Expr)
```

**Parser structure:**
- Recursive descent for Markdown structure
- Pratt parser for expressions (handles precedence)
- Special handling for interpolation boundaries

### 3. Type Checking (Typer)

**Input:** Raw AST
**Output:** Typed IR

The typer performs:
- Name resolution (scopes from headings)
- Import resolution
- Type inference
- Type checking
- Exhaustiveness checking for matches

Current implementation note: some exported summaries and route/schema metadata
still fall back to `Any` when the local typer cannot prove a shape. The planned
tightening pass is specified in
[`docs/typer-real-types-roadmap.md`](typer-real-types-roadmap.md); it keeps
`Any` as an explicit dynamic boundary while carrying structured type evidence
through exported symbols, routes/remotes, schemas, typed data mapping, Spark,
and plugins.

**Typed IR:**

```scala
case class TypedModule(
  name: String,
  version: SemVer,
  imports: List[ResolvedImport],
  scopes: List[TypedScope],
  exports: List[Symbol]
)

case class TypedScope(
  name: String,
  symbols: SymbolTable,
  definitions: List[TypedDef],
  nested: List[TypedScope]
)

enum TypedExpr:
  case TLiteral(value: Any, tpe: Type)
  case TIdent(sym: Symbol, tpe: Type)
  case TApply(fn: TypedExpr, args: List[TypedExpr], tpe: Type)
  // ... all expressions with Type attached
```

**Type system features:**
- Hindley-Milner inference with local type inference
- Subtyping with variance
- Union and intersection types
- Path-dependent types (limited)
- GADTs via match types

### 4. Backend Translation

**Input:** `ir.NormalizedModule` (post-`Normalize`).
**Output:** A `CompileResult` variant: `TextOutput`, `Segmented`,
`BinaryOutput`, `Executed`, or `Failed(diagnostics)`.

Every backend implements the SPI trait in `backend-spi`:

```scala
package scalascript.backend.spi

trait Backend:
  def id:              String                              // "jvm", "js", "wasm", …
  def displayName:     String
  def spiVersion:      String                              // SpiVersion.Current
  def capabilities:    Capabilities                        // §11 — features/outputs/options
  def intrinsics:      Map[ir.QualifiedName, IntrinsicImpl] // §8 — platform-native calls
  def acceptedSources: Set[String]                         // §9 — embedded source languages
  def compile(ir: NormalizedModule, opts: BackendOptions): CompileResult

trait InteractiveBackend extends Backend:
  def openSession(opts: BackendOptions): Session

trait Session extends AutoCloseable:
  def feed(block: NormalizedBlock): CompileResult
  def invokeHandler(handlerRef: SymbolRef, args: List[Value]): Value
  def close(): Unit
```

Full design + open questions: [`docs/backend-spi.md`](backend-spi.md);
out-of-process wire protocol: [`docs/backend-spi-protocol.md`](backend-spi-protocol.md).

**Bundled adapters** (each in its own sbt subproject):

| Subproject               | Backend impl                  | id              | Output kind        |
|--------------------------|-------------------------------|-----------------|--------------------|
| `backend-jvm/`           | `codegen.JvmBackend`          | `jvm`           | `ScalaSource`      |
| `backend-js/`            | `codegen.JsBackend`           | `js`            | `JavaScriptSource` |
| `backend-scalajs/`       | `codegen.ScalaJsPluginBackend`| `scalajs-spa`   | `JavaScriptSource` + `HtmlSource` |
| `backend-interpreter/`   | `interpreter.InterpreterBackend` | `int`        | `ExecutionResult`  |

`InterpreterBackend` is the only one extending `InteractiveBackend`
(its `Session` underpins `ssc serve`).

**Discovery.**  `core/plugin/BackendRegistry` combines two paths:

  1. **ServiceLoader** picks up every JAR with a
     `META-INF/services/scalascript.backend.spi.Backend` entry — how
     the four bundled backends register, and how `--plugin <jar>`
     attaches a third-party JAR via `URLClassLoader`.
  2. **plugin.yaml** under `$SCALASCRIPT_PLUGIN_PATH` /
     `~/.scalascript/compiler/plugins/` (or `--plugin-dir <dir>`) declares
     subprocess plugins.  `SubprocessBackend` wraps each as a stdio
     speaker.

`BackendRegistry.lookup(id)` consults both — in-process first,
subprocess on miss (lazy spawn).

## Symbol Table & Scopes

Scopes form a tree structure mirroring the heading hierarchy:

```text
ModuleScope (root)
├── imports: [std/math → math]
├── symbols: [Circle, Rectangle, area]
└── children:
    ├── Scope("Shapes")
    │   ├── symbols: [Circle, Rectangle]
    │   └── children:
    │       ├── Scope("Circle") → [Circle class]
    │       └── Scope("Rectangle") → [Rectangle class]
    └── Scope("Calculations")
        └── symbols: [area]
```

**Resolution rules:**
1. Look in current scope
2. Look in parent scopes (up to root)
3. Look in imports
4. Look in prelude (built-in types)

## Error Handling

Errors are accumulated, not fatal:

```scala
case class CompileError(
  phase: Phase,
  position: Position,
  message: String,
  severity: Severity
)

enum Severity:
  case Error, Warning, Info
```

Error recovery strategies:
- **Lexer:** Skip to next valid token
- **Parser:** Skip to next statement/section
- **Typer:** Use `ErrorType` placeholder, continue checking

## IR Serialization

Typed IR can be serialized for:
- Incremental compilation
- IDE integration
- Debugging

Format options:
- JSON (human-readable, debugging)
- Binary (compact, fast)

```scala
// IR dump example (JSON)
{
  "module": "geometry",
  "version": "1.0.0",
  "scopes": [
    {
      "name": "Shapes",
      "definitions": [
        {
          "kind": "class",
          "name": "Circle",
          "params": [{"name": "radius", "type": "Double"}]
        }
      ]
    }
  ]
}
```

## Extension Points

### Custom Backends

See [`docs/writing-a-backend.md`](writing-a-backend.md) for a
step-by-step walk-through of a no-op backend in under 100 lines.
Two distribution shapes:

  - **In-process JAR.**  Implement `Backend` in any JVM language,
    drop a `META-INF/services/scalascript.backend.spi.Backend` entry
    listing your class, attach the JAR via `--plugin <jar>` (or
    place it on the bundled classpath).  Worked example:
    `examples/plugins/hello-backend/`.
  - **Subprocess.**  Any language that can read newline-delimited
    JSON.  Drop a `plugin.yaml` under `~/.scalascript/compiler/plugins/`;
    `SubprocessBackend` wraps the process and routes
    `Backend.compile` over stdio.  Worked example:
    `examples/plugins/canned-backend/`.

Both shapes go through `core/plugin/BackendRegistry` and are
interchangeable from the CLI's view.

### Language Extensions

Future: macro system for compile-time metaprogramming.

### IDE Integration

The compiler exposes:
- Position-to-symbol mapping
- Type-at-position queries
- Completion suggestions
- Hover information

Via Language Server Protocol (LSP) in future milestones.

## Directory Structure (Implementation)

```text
backend-spi/                # public, semver-stable
└── src/main/scala/scalascript/backend/spi/
    ├── Backend.scala          Backend / InteractiveBackend / Session
    ├── BackendOptions.scala
    ├── Capabilities.scala
    ├── CompileResult.scala
    ├── Diagnostic.scala
    ├── Feature.scala
    ├── OutputKind.scala
    ├── IntrinsicImpl.scala
    ├── SourceLanguage.scala
    └── SpiVersion.scala

ir/                         # NormalizedModule + JSON / MsgPack codecs
└── src/main/scala/scalascript/ir/Ir.scala

core/                       # parser, typer, normalize, registry, validation
└── src/main/scala/scalascript/
    ├── parser/Parser.scala
    ├── typer/Typer.scala, Types.scala
    ├── ast/                       # AST types + scalameta wrapper
    ├── imports/ImportResolver.scala
    ├── interpreter/Value.scala    # Computation Free monad (shared)
    ├── transform/Normalize.scala  # AST → IR
    ├── transform/Denormalize.scala# IR → AST (Stage 5 transitional)
    ├── transform/EffectAnalysis.scala
    ├── validate/CapabilityCheck.scala
    └── plugin/
        ├── BackendRegistry.scala
        ├── PluginManifest.scala
        ├── SubprocessBackend.scala
        └── WireProtocol.scala

backend-jvm/                # Backend adapter — JvmGen Scala source
backend-js/                 # Backend adapter — JsGen JavaScript
backend-scalajs/            # Backend adapter — Scala.js SPA
backend-interpreter/        # InteractiveBackend — tree-walking interpreter

cli/                        # ssc command + sbt-assembly fat jar
```
