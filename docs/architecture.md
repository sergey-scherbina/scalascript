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

**Input:** Typed IR
**Output:** Target-specific code

Each backend implements the `Backend` trait:

```scala
trait Backend:
  def name: String
  def translate(module: TypedModule): Output

  // Hook points for customization
  def translateType(tpe: Type): TargetType
  def translateExpr(expr: TypedExpr): TargetExpr
  def generateRuntime(): TargetCode
```

**JVM Backend specifics:**
- Generates Scala 3 source
- Uses Scala-CLI for compilation
- Preserves type information for IDE support

**JS Backend specifics:**
- Generates ES modules
- Includes minimal runtime for ScalaScript types
- Source maps for debugging

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

Implement `Backend` trait and register:

```scala
object MyBackend extends Backend:
  def name = "my-target"
  def translate(module: TypedModule) = ...

BackendRegistry.register(MyBackend)
```

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
compiler/
├── lexer/
│   ├── Scanner.scala
│   ├── Token.scala
│   └── MarkdownLexer.scala
├── parser/
│   ├── Parser.scala
│   ├── ExprParser.scala
│   └── AST.scala
├── typer/
│   ├── Typer.scala
│   ├── TypeInference.scala
│   ├── Symbols.scala
│   └── Types.scala
├── ir/
│   ├── TypedIR.scala
│   └── IRSerializer.scala
├── backend/
│   ├── Backend.scala
│   ├── JVMBackend.scala
│   └── JSBackend.scala
└── main/
    ├── Compiler.scala
    └── CLI.scala
```
