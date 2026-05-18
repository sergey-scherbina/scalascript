# DSL — defining domain-specific languages in ScalaScript

Status: **design / planning**.  Implementation tracked as
**v1.20 — DSL primitives + std/parsing** in MILESTONES.md.
Companion to [`docs/markdown-as-syntax.md`](markdown-as-syntax.md)
(multi-language fenced blocks via SourceLanguage SPI plugins —
landed), [`docs/metaprogramming.md`](metaprogramming.md) (v1.14
`inline` + `derives` — useful for typed DSL ASTs but not
required), and [`docs/modularity.md`](modularity.md) §5 (the
`std/dsl/*` and `std/parsing/*` nested-area layout).

This document is the source of truth for ScalaScript's DSL
story: what already works, what's missing, the user-defined
interpolator design, and the parser-combinator standard
library.

## 1. What already works today

ScalaScript ships **more DSL infrastructure than it advertises**.
Five mechanisms produce four different flavours of DSL:

| Mechanism | Used by | Flavour |
|-----------|---------|---------|
| **Extension methods** + fluent chaining | `Focus[T](_.a.b)` lens path, `xs.map(f).filter(p)` | Internal eDSL |
| **Operator overloading** via extension | `path andThen lens`, `a |+| b` (when v1.13 lands) | Internal eDSL |
| **Built-in interpolators** `html"..."` / `css"..."` | HTML/CSS embedded in source | Templated string DSL |
| **Multi-language fenced blocks** (`` ``` ``-prefixed in Markdown) | `html` / `css` / `scala` blocks, future `sql` / `graphql` | Block DSL via SourceLanguage SPI |
| **Front-matter directives** | `serve:`, `package:`, `exports:`, `route:` | Declarative DSL |

What this means in practice:

```scala
// 1. Fluent internal DSL — works today
val activeUsers = users.filter(_.active).map(_.name).take(10)

// 2. Built-in interpolator — works today
val page = html"<h1>Hello $name</h1>"

// 3. Multi-language fenced block — works today on backends that ship a SourceLanguage plugin
```sql
SELECT * FROM users WHERE age > 18
```

// 4. Front-matter declarative — works today
---
route:
  GET /api/users -> listUsers
---
```

What's **missing**:

1. **User-defined string interpolators** — `extension (sc: StringContext) def sql(args: Any*): SqlQuery = ...`.  Scala 3 supports them; ScalaScript's typer + codegen need verification.
2. **Parser combinator standard library** — for building external DSLs (parsing user input into ASTs).  Today users either roll their own or use multi-language blocks (which require an SPI plugin).
3. **AST helpers** — common sealed-trait shapes (expressions, statements, declarations), pretty-printers, position tracking.
4. **DSL pattern library** — precedence climbing, error recovery, source-position threading — the bits every DSL author re-implements.

## 2. Three DSL flavours in scope, one out

ScalaScript v1.20 ships infrastructure for three DSL flavours:

| Flavour | What | v1.20 contribution |
|---------|------|--------------------|
| **Internal eDSL** | Fluent API + operator overloading using host-language syntax | Works today — document the patterns |
| **Interpolator DSL** | `sql"..."` / `json"..."` / etc. with compile-time and runtime parts | Verify user-defined interpolators work cross-backend; ship patterns |
| **External DSL** | Full parser over text input → AST → interpreter | Ship `std/parsing/*` combinator library |

Out of scope: **multi-language fenced blocks via SourceLanguage
plugins** — that's already a separate SPI feature, designed in
`docs/backend-spi.md` §9 and partially landed.  v1.20 is about
user-space DSLs, not language-plugin authoring.

## 2.5 Reified by default (locked 2026-05-18)

**Architectural choice applying to ALL three DSL flavours**:
DSLs in ScalaScript produce **data**, not effects.  Building
a DSL value composes a tree of operations; execution is an
**explicit** extension method call.

```scala
val query: Query[User]      = sql"SELECT * FROM users WHERE id = $id"   // data
val users: List[User]       = query.exec   // explicit execution

val parser: Parser[Double]  = (num ~ (char('+') ~> num).*).map(_sum)    // data
val result: ParseResult[Double] = parser.parse("1 + 2")                 // explicit execution
```

### Why reified-by-default

- **Inspectable** — walk the value, count nodes, find
  amibiguity, dump for debug
- **Multiple interpreters** — same DSL value, run via
  recursive-descent / Pratt / compile-time codegen / mock
  for tests
- **Composable across module boundaries** — pass `Query[A]`
  / `Parser[A]` between modules without runtime coupling;
  important for v1.19 dep imports
- **Compile-time evaluation possible** — once v1.14 `inline`
  lands, the same DSL value can be evaluated at compile
  time inside an `inline def`

### Cost

Boxed combinator nodes vs lambda closures — measurable
allocation on hot paths.  Acceptable for v1; specialised
interpreters (`compileToPratt`, etc.) are extension
methods that compile down to specialised executors when
needed.

### The `.exec` convention

Every reified DSL provides at least one execution
extension method.  Conventional name: `.exec`.  Return
type depends on the DSL's nature:

| DSL kind | `.exec` return | Example |
|----------|----------------|---------|
| Pure (no IO) | `A` (synchronous) | `Parser[A].exec` (when input is in context) |
| Effectful (IO / Async) | `Async[A]` | `Query[A].exec` (DB IO) |
| With error channel (v1.15) | `A throws E` | `Validator[A].exec` |
| Free-monad-shaped | `F[A]` via `foldMap` | `Free[Op, A].foldMap(handler)` |

For DSLs that take input (`Parser[A]` needs a string),
the execution method takes it:
`parser.parse(input): ParseResult[A]` rather than `.exec`.
Same principle — execution is explicit, parameters are
arguments to the execution.

### When users want immediate evaluation

Three patterns, all opt-in:

1. **Construct + immediately exec on the same line**:
   ```scala
   val users = sql"SELECT * FROM users".exec
   ```
2. **Inline DSL value in a direct block**:
   ```scala
   direct[Async] {
     users = sql"SELECT * FROM users".exec   // auto-binds Async[A]
     Response.json(users)
   }
   ```
3. **DSL author ships a `.eager`-shaped convenience**:
   ```scala
   extension (sc: StringContext) def sqlNow(args: Any*): List[Row] =
     sql"…".exec    // eager — wraps the lazy form
   ```

`.eager` / `.now` shorthands are DSL-author choice, not
language-level mandate.  Stdlib reserves `.exec` as the
canonical execution method.

## 3. Internal eDSL — patterns, not primitives

Internal eDSLs need no new compiler features.  They reuse
host syntax.  The v1.20 milestone documents the **patterns**
that work well so users don't reinvent each one:

### 3.1 Fluent API

```scala
val q = from(users)
  .where(_.age > 18)
  .where(_.active)
  .select(_.name)
  .limit(10)
```

Implementation: each method on `Query[T]` returns a new `Query[T]`
(or `Query[U]` for projecting `select`).  Pattern works
today; no new feature.

### 3.2 Operator-style with extension methods

```scala
val combined: Lens[T, A] = outerLens andThen innerLens
val both:     Set[A]     = a union b
val merged:   Map[K, V]  = m1 |+| m2  // semigroup combine, post-v1.13
```

`extension (a: Foo) def `<symbolic>`(b: Bar): Result`
declares the symbolic operator.  Standard Scala 3.

### 3.3 Builder + DSL block

```scala
val app = mcpServer { srv =>     // existing pattern from v1.17
  srv.tool("foo") { args => … }
  srv.resource("bar") { uri  => … }
}

val schema = describeSchema { s =>
  s.field("name", StringT, required = true)
  s.field("age",  IntT,    required = false)
}
```

Pattern: a top-level constructor takes a single lambda that
receives a builder.  Used today by `mcpServer`, `route`,
`onWebSocket`; documented as a convention.

## 4. Interpolator DSL — user-defined `myDsl"..."`

Built-in interpolators `html"..."` / `css"..."` ship today.
v1.20 confirms (and fixes if needed) that **user-defined**
interpolators work on all three backends.

### 4.1 Surface

Standard Scala 3 extension on `StringContext`:

```scala
extension (sc: StringContext)
  def sql(args: Any*): SqlQuery =
    SqlQuery.compile(sc.parts, args)

extension (sc: StringContext)
  def json(args: Any*): JsonValue =
    JsonValue.parse(sc.parts.zipAll(args, "", "").map(_.toString).mkString)
```

User code:

```scala
val q  = sql"SELECT * FROM users WHERE age > $minAge AND name = $name"
val js = json"""{"name": $name, "age": $age}"""
```

The compiler desugars `sql"…"` to `sc.sql(arg1, arg2, …)`
where `sc` is the `StringContext` built from the literal parts.
Standard Scala 3 — pure parser-side feature.

### 4.2 Backend-level integration

| Backend | Today | v1.20 work |
|---------|-------|------------|
| INT (interpreter) | Likely unsupported — relies on direct evaluation, no StringContext machinery | ~2 days |
| JS (Node) | Probably works (codegen passes through) — verify | ~1 day |
| JVM | Works (lowers to Scala source, which Scala 3 handles natively) | ~0.5 day |

### 4.3 Compile-time validation

With v1.14 `inline def`, an interpolator can validate parts
at compile time:

```scala
extension (sc: StringContext)
  inline def sql(args: Any*): SqlQuery =
    inline if SqlValidator.isValid(sc.parts) then
      SqlQuery.compile(sc.parts, args)
    else
      compiletime.error("Invalid SQL: " + sc.parts.mkString("$_"))
```

Compile-time errors with helpful messages for malformed
embedded code.  Depends on v1.14 — note in `inline if` /
`compiletime.error` of v1.14's surface.

## 5. External DSL — `std/parsing/*` combinators

The biggest piece.  Ship a **parser combinator** library in
the `std/parsing/` nested area, following the v1.18 layout
policy.

### 5.1 Layout (per `docs/modularity.md` §5)

```
std/parsing/
├── types.ssc       # Parser[A], ParseResult[A], Position, ParseError
├── core.ssc        # primitive parsers: char, string, regex
├── combinators.ssc # ~, |, *, ?, +, andThen, map, flatMap
├── helpers.ssc     # token, whitespace, identifier, number, stringLit
└── index.ssc       # aggregator
```

Single-file would be possible at ~500 lines but nested is
right per the policy: clear roles, room to grow.

### 5.2 Core types — `Parser[A]` as data (reified)

Per §2.5 the parser is **data**, not an interface with a
`.parse` method.  Combinators build a tree of case-class
nodes; execution is an explicit extension method.

```scala
case class Position(line: Int, col: Int, offset: Int = 0):
  override def toString = s"$line:$col"

case class ParseError(message: String, pos: Position, context: List[String] = Nil)

enum ParseResult[+A]:
  case Ok[A](value: A, rest: String, pos: Position, span: Span) extends ParseResult[A]
  case Err(error: ParseError)                                     extends ParseResult[Nothing]

// REIFIED: every combinator is a case-class node in this enum.
// Building a Parser produces a tree.  No execution happens until
// you call .parse(...) — see §5.3.2.
enum Parser[+A]:
  // Primitives
  case Char(c: scala.Char)                                            extends Parser[scala.Char]
  case StringP(s: String)                                             extends Parser[String]
  case Regex(pattern: String)                                         extends Parser[String]
  case Satisfy(pred: scala.Char => Boolean)                           extends Parser[scala.Char]
  // Composition
  case Sequence[A, B](left: Parser[A], right: Parser[B])              extends Parser[(A, B)]
  case Choice[A](left: Parser[A], right: Parser[A])                   extends Parser[A]
  case Many[A](inner: Parser[A], minCount: Int)                       extends Parser[List[A]]
  case Opt[A](inner: Parser[A])                                       extends Parser[Option[A]]
  // Transform
  case Mapped[A, B](inner: Parser[A], f: A => B)                      extends Parser[B]
  case FlatMapped[A, B](inner: Parser[A], f: A => Parser[B])          extends Parser[B]
  case Named[A](inner: Parser[A], name: String)                       extends Parser[A]
  // Context manipulation (see §5.8)
  case WithLocalContext[A](
    inner:  Parser[A],
    update: ParserContext => ParserContext
  ) extends Parser[A]
  case ReadContext[A](
    f: ParserContext => Parser[A]
  ) extends Parser[A]
```

### 5.3 Combinators — pure data construction

Combinators are extension methods that produce new
`Parser` enum nodes.  **No execution happens.**

```scala
object Parser:
  // Primitives — leaf nodes
  def char(c: scala.Char): Parser[scala.Char] = Parser.Char(c)
  def string(s: String):  Parser[String]      = Parser.StringP(s)
  def regex(pattern: String): Parser[String]  = Parser.Regex(pattern)
  def satisfy(pred: scala.Char => Boolean): Parser[scala.Char] = Parser.Satisfy(pred)

// Combinators — build composite nodes
extension [A](p: Parser[A])
  def |[A1 >: A](other: => Parser[A1]): Parser[A1] = Parser.Choice(p, other)
  def ~[B](other: => Parser[B]): Parser[(A, B)]    = Parser.Sequence(p, other)
  def ~>[B](other: => Parser[B]): Parser[B]        = (p ~ other).map(_._2)
  def <~[B](other: => Parser[B]): Parser[A]        = (p ~ other).map(_._1)
  def *(): Parser[List[A]]                         = Parser.Many(p, 0)
  def +(): Parser[List[A]]                         = Parser.Many(p, 1)
  def ?(): Parser[Option[A]]                       = Parser.Opt(p)
  def map[B](f: A => B): Parser[B]                 = Parser.Mapped(p, f)
  def flatMap[B](f: A => Parser[B]): Parser[B]     = Parser.FlatMapped(p, f)
  def named(name: String): Parser[A]               = Parser.Named(p, name)
```

### 5.3.1 Left-recursion combinators (locked)

PEG semantics naïvely forbids direct left recursion
(`expr := expr '+' term` loops infinitely).  Ship a family
of **special-purpose combinators** that let users write
left-recursive grammars naturally:

```scala
extension [A](p: Parser[A])
  // A op A op A — left-associative chain
  def chainLeft[A1 >: A](op: Parser[(A1, A) => A1]): Parser[A1] =
    (p ~ (op ~ p).*).map { case (first, rest) =>
      rest.foldLeft(first) { case (acc, (f, b)) => f(acc, b) }
    }

  // A op A op A — right-associative chain
  def chainRight[A1 >: A](op: Parser[(A, A1) => A1]): Parser[A1] =
    (p ~ (op ~ p).?).map { case (first, opt) =>
      opt match
        case None => first
        case Some((f, rest)) => f(first, rest)
    }

  // A suf suf suf — postfix chains (e.g., `x.field.method.field`)
  def chainPostfix(suffix: Parser[A => A]): Parser[A] =
    (p ~ suffix.*).map { case (first, rest) =>
      rest.foldLeft(first)((acc, f) => f(acc))
    }

  // pre pre pre A — prefix chains (e.g., `not not not x`)
  def chainPrefix(prefix: Parser[A => A]): Parser[A] =
    (prefix.* ~ p).map { case (pres, base) =>
      pres.foldRight(base)((f, acc) => f(acc))
    }
```

Worked example — calculator that previously needed
PEG-workaround:

```scala
val factor: Parser[Int] = num | (char('(') ~> expr <~ char(')'))
val term:   Parser[Int] = factor.chainLeft(char('*').as((a, b) => a * b))
val expr:   Parser[Int] = term.chainLeft(char('+').as((a, b) => a + b))
```

Reads as left-recursive grammar; under the hood compiles
to `(p ~ (op ~ p).*).foldLeft` — same PEG-safe shape, but
the user thinks in CFG terms.

For **truly mutually recursive left-recursion** (e.g.,
`expr ::= expr '+' term | letExpr` where `letExpr ::=
"let" id "=" expr`) — that needs memoised fixpoint (Warth
2008).  **Deferred to v1.20.x** — packrat overhead is
acceptable only when measurement justifies, and the
operator-chain combinators above cover ~95% of real
parser-author needs.

### 5.3.2 Execution — explicit extension methods

The default interpreter is recursive-descent.  Other
interpreters can ship as extension methods over the same
`Parser[A]` data — `compileToPratt`, `validate` (grammar
check without running input), `parseInline` (compile-time
via v1.14 `inline`).

```scala
// Default — recursive-descent runtime parser
extension [A](p: Parser[A])
  def parse(input: String, pos: Position = Position(1, 1)): ParseResult[A] =
    std.parsing._internal.RecursiveDescent.run(p, input, pos)

// Specialised — compile to a Pratt parser (deferred to v1.20.x
// when measurement justifies it).  Same Parser data, faster
// evaluator.
extension [A](p: Parser[A])
  def compileToPratt: CompiledParser[A] =
    std.parsing._internal.PrattCompiler.compile(p)

// Static — validate grammar without input (find unreachable
// alternatives, left-recursion warnings, etc.)
extension [A](p: Parser[A])
  def validate: Either[GrammarError, GrammarReport] =
    std.parsing._internal.GrammarValidator.check(p)

// Compile-time — for interpolators that want compile-time
// validation (depends on v1.14 inline; deferred to v1.20.1)
extension [A](p: Parser[A])
  inline def parseInline(input: String)(using Quotes): Expr[A] = ...
```

The user picks the interpreter at the call site; the
`Parser` itself doesn't care.

### 5.4 Worked example

```scala
[char, string, regex, ~, |, map, ~>, named](../std/parsing)

object Calc:
  // expr := term ('+' term)*
  // term := factor ('*' factor)*
  // factor := num | '(' expr ')'

  val ws  = regex("\\s*")
  val num = regex("-?\\d+(\\.\\d+)?").map(_.toDouble)

  lazy val factor: Parser[Double] =
    num | (ws ~> char('(') ~> ws ~> expr <~ ws <~ char(')'))

  lazy val term: Parser[Double] =
    (factor ~ (ws ~> char('*') ~> ws ~> factor).*)
      .map { case (a, rest) => rest.foldLeft(a)(_ * _) }

  lazy val expr: Parser[Double] =
    (term ~ (ws ~> char('+') ~> ws ~> term).*)
      .map { case (a, rest) => rest.foldLeft(a)(_ + _) }

Calc.expr.parse("1 + 2 * 3")    // Ok(7.0, "", 1:10)
Calc.expr.parse("1 + (2 + 3) * 4")  // Ok(21.0, "", 1:18)
```

### 5.5 Error reporting

```scala
val p = string("foo") | string("bar") named "foo-or-bar"
p.parse("baz")
// Err(ParseError(
//   message = "expected foo-or-bar",
//   pos     = 1:1,
//   context = List("at start of expression")
// ))
```

Position tracking is automatic.  `.named` decorates errors
with a higher-level rule name.

### 5.6 `Span` — uniform source position (locked)

Mandatory for **every** DSL that wants to interop with the
rest of the stack.  Lives in `std/dsl/types.ssc`:

```scala
case class Span(
  source:    String = "<unknown>",  // file path, or "interpolator:<line>:<col>"
  startLine: Int,
  startCol:  Int,
  endLine:   Int,
  endCol:    Int,
  byteOffset: Int = 0    // for interpolator-nested DSLs
):
  def merge(other: Span): Span =
    Span(source, startLine, startCol,
         other.endLine, other.endCol, byteOffset)

  override def toString: String =
    if startLine == endLine && startCol == endCol then s"$source:$startLine:$startCol"
    else s"$source:$startLine:$startCol-$endLine:$endCol"

object Span:
  val Empty: Span = Span("", 0, 0, 0, 0)

trait HasSpan:
  def span: Span
```

**Locked**: every Parser-built AST node that's expected to
participate in cross-DSL composition (interpolators
embedded in fenced blocks; DSL output passed across module
boundaries; error messages crossing nesting layers)
implements `HasSpan`.

Why locked: without a uniform position type, each DSL ships
its own incompatible position machinery and cross-DSL error
messages become "best-effort string concatenation" — the
boundary between "parse error in your SQL" and "syntax
error in your .ssc handler.ssc at line 47" can't be
reported coherently.

### 5.7 Hygiene for interpolated `$value` (tentative)

Open architectural question with a **tentative**
recommendation.  Two-tier convention:

| Syntax | Semantics | Default |
|--------|-----------|---------|
| `$value` | **Parameter binding** — value passes through as a typed argument; DSL chooses safe handling (e.g., SQL parameter, JSON value) | Safe-by-default |
| `$$identifier` | **Raw inline** — value is spliced into the source text as-is (e.g., SQL identifier, raw JSON fragment) | Escape hatch |

Std-lib helpers in `std/dsl/types.ssc`:

```scala
case class Param[+T](value: T)        // safe — DSL-treated as binding
case class RawInline(text: String)    // unsafe — DSL-treated as raw text

// DSL author can require these explicitly via type discrimination:
extension (sc: StringContext)
  def sql(args: (Param[?] | RawInline)*): Query[?] =
    SqlCompiler.compile(sc.parts, args.toList)
    // safe: rejects bare `$x` for non-Param[?] / non-RawInline values
```

**Why tentative**: this convention only matters when a DSL
has injection-class concerns (SQL, shell, HTML).
Lightweight DSLs (e.g., a CSV parser) don't need this.
The `Param[?] | RawInline` discrimination is the
**strongest** form; a relaxed default ("any `$value` is
just substituted; DSL author handles safety") works for
simpler cases.

The lock will happen once we see ≥3 real injection-class
DSLs surface — until then, document the convention,
don't enforce.

### 5.8 Parser context via ADT nodes (locked)

Some DSLs need to track parser-local state that isn't part
of input position — indentation level, current scope,
already-bound identifiers, etc.  Locked design (2026-05-18):
**context lives in the Parser ADT as two extra nodes**;
runtime evaluator carries the value through evaluation
(NOT thread-local).

```scala
// std/parsing/types.ssc — additions to the existing Parser enum

trait ParserContext

case object NoContext extends ParserContext

enum Parser[+A]:
  // ... existing nodes from §5.2 (Char, StringP, Regex, Sequence, ...)

  // NEW: context-manipulation nodes
  case WithLocalContext[A](
    inner:  Parser[A],
    update: ParserContext => ParserContext
  ) extends Parser[A]

  case ReadContext[A](
    f: ParserContext => Parser[A]
  ) extends Parser[A]

// Extension API
extension [A](p: Parser[A])
  def localCtx(update: ParserContext => ParserContext): Parser[A] =
    Parser.WithLocalContext(p, update)

object Parser:
  def readCtx[A](f: ParserContext => Parser[A]): Parser[A] =
    Parser.ReadContext(f)
```

The default-interpreter signature gains a `ctx` parameter:

```scala
extension [A](p: Parser[A])
  def parse(
    input: String,
    pos:   Position       = Position(1, 1),
    ctx:   ParserContext  = NoContext
  ): ParseResult[A] =
    std.parsing._internal.RecursiveDescent.run(p, input, pos, ctx)
```

#### Why this shape

- **`Parser[A]` types stay simple** — no `Parser[A, C]` polluting every signature for users who don't need context
- **Context is in the Parser data** — `WithLocalContext` / `ReadContext` are first-class ADT nodes; users introspecting / transforming the Parser tree see context manipulations as data
- **Runtime carry, not thread-local** — interpreter passes `ctx` through recursion; reentrant; parallel `.parse(...)` calls don't interfere
- **Open for any context type** — `ParserContext` is a marker trait; downstream DSL ships its own subtype (`IndentContext`, `ScopeContext`, etc.)
- **Type safety via pattern-match** — `ReadContext(case ic: IndentContext => …)` is the convention; mismatched context surfaces as a parser failure, not a runtime crash

#### Worked example — indentation-aware (preview, v1.20.2)

```scala
// std/parsing/layout.ssc (ships in v1.20.2; design preview here)

case class IndentContext(currentLevel: Int, stack: List[Int]) extends ParserContext

extension [A](p: Parser[A])
  // Run `p` with current indent set to `min`
  def withIndent(min: Int): Parser[A] = p.localCtx {
    case ic: IndentContext => ic.copy(currentLevel = min)
    case _                 => IndentContext(min, Nil)
  }

  // Require current input column ≥ current indent level
  def sameIndent: Parser[Unit] = Parser.readCtx {
    case ic: IndentContext =>
      Parser.satisfyPosition(_.col >= ic.currentLevel).map(_ => ())
    case _ =>
      Parser.fail("sameIndent requires an IndentContext in scope")
  }

  // Multi-line block at current indent
  def block: Parser[List[A]] = ???
```

#### Considered alternatives — rejected

- **(a) Field per node** (`case Char(c: scala.Char, ctx: ParserContext)`) —
  combinator nodes are *data*, context is *runtime state*; storing
  context per-node duplicates it without payoff, and changing it
  requires reconstructing the tree
- **(b) Subclass** (`trait CtxParser[+A, C] extends Parser[A]`) —
  combining `Parser[A]` with `CtxParser[A, C]` in `~` / `|` causes
  type pollution or loses context information; user has to choose
  one layer
- **(d) Thread-local** — rejected by user direction (and bad
  practice generally: not reentrant, problematic across coroutine
  boundaries in v1.9, hidden state)

#### Cost

Two extra `Parser` ADT cases; one extra `ctx` parameter on
the evaluator.  No runtime overhead when `NoContext` —
RecursiveDescent skips the dispatch path.  Indentation DSL
ships as a separate opt-in module (v1.20.2).

## 6. AST / DSL helpers — `std/dsl/*`

A smaller companion library to `std/parsing/`.  Provides:

- **AST node shapes** (common sealed trait patterns)
- **Pretty-printer** combinators
- **Source-position threading**
- **Precedence climbing** for operator parsers
- **AST walking** (cata / ana / hylo via v1.1 Foldable)

```
std/dsl/
├── types.ssc       # Node, Located, Span, PrettyPrint trait
├── pretty.ssc      # combinators: text, indent, line, group
├── walker.ssc      # cata-style traversal
└── index.ssc       # aggregator
```

These tools formalise the patterns every DSL author reinvents.
Ship in v1.20 alongside parsing.

## 7. Coexistence with other features

| Feature | Relationship |
|---------|--------------|
| **v1.8 direct-syntax** | DSL bodies can use `direct[F]` for monadic effects (e.g., `sql"…"` returning `Async[ResultSet]`) |
| **v1.13 Final Tagless** | A DSL can be typed-final-tagless: `Calc[F]` with `add: F[Int]`, `mul: F[Int]`, interpreted to different effect types |
| **v1.14 `inline` / `derives`** | Compile-time DSL validation (`inline if` on interpolator parts); `derives PrettyPrint` for AST debug |
| **v1.15 `throws[A, E]`** | Parsers return `Parser[A]` where parse failure is naturally encoded as `Err`; integrating with `throws` is via `parse(input).toThrows[ParseError]` helper |
| **v1.17 MCP** | MCP tool definitions are themselves a kind of DSL — `srv.tool("name") { args => … }` is the builder-pattern flavour (§3.3) |
| **Multi-language fenced blocks** | Complementary: blocks are language-plugin territory, this milestone is user-space DSL territory |

### 7.1 Composition contract — `Exec[D[_], F[_]]` typeclass (tentative)

Cross-DSL composition needs a uniform "how do I run this
DSL value" hook.  **Tentative** proposal: a typeclass
`Exec[D, F]` over the DSL constructor `D` and target
monad `F`.

```scala
// std/dsl/types.ssc — tentative
trait Exec[D[_], F[_]]:
  extension [A](d: D[A]) def exec: F[A]

// Pure DSL — execution returns the value directly
given Exec[Parser, [X] =>> ParseResult[X]] with
  extension [A](p: Parser[A]) def exec: ParseResult[A] = ???  // needs input?

// Effectful DSL (SQL example) — execution returns Async
given Exec[Query, Async] with
  extension [A](q: Query[A]) def exec: Async[A] = SqlRuntime.run(q)

// Combined with direct-syntax (v1.8):
def handler(req: Request): Response throws AppError = direct[Async] {
  users = sql"SELECT * FROM users WHERE active".exec   // exec gives Async[List[User]]
  Response.json(users)
}
```

**Why tentative**:

- **Pro**: every DSL plays the same game; `direct[F]` blocks
  auto-bind any `D[A].exec: F[A]`; clear contract for DSL
  authors
- **Con**: forces every DSL to fit `D[_]` → `F[_]` shape
  (single-monad target); cross-effect lifts get awkward
- **Con**: parsers (`Parser[A]`) need extra `input: String`
  parameter, doesn't fit `Exec[D, F]` neatly
- **Alternative (simpler)**: just convention — every DSL
  has SOME `.exec`-shaped extension; no shared typeclass,
  no formal contract.  DSL authors document their own
  return types

Likely we ship the **convention-only** version in v1.20 and
revisit the typeclass form in v1.20.x once we have ≥3 DSLs
that would benefit from formal `Exec` dispatch.  Locked
once a real cross-DSL composition use case demands it.

## 8. Implementation phases (v1.20)

### Phase 1 — User-defined interpolators (~3 days)

Verify `extension (sc: StringContext) def myDsl(args: Any*)`
works on all three backends.  Fix the gaps found.  Conformance:
custom-interpolator round-trip, escaping, mixed-type args.

### Phase 2 — `std/parsing/core.ssc` — reified `Parser[A]` ADT + primitive constructors (~2 days)

`enum Parser[+A]` (case-class-per-combinator-node per §5.2),
`Position`, `Span`, `ParseResult`, `ParseError`, primitive
constructors `Parser.char` / `string` / `regex` / `satisfy`.
No interpreter yet — pure data.  Conformance: each
constructor builds the right ADT node; pattern-matchable.

### Phase 3 — `std/parsing/combinators.ssc` — combinator extensions + default interpreter (~3 days)

Combinators `~` / `|` / `*` / `+` / `?` / `map` / `flatMap`
/ `~>` / `<~` / `named` (extension methods producing
`Parser.X` nodes — pure data construction, see §5.3).
Plus the default recursive-descent interpreter shipped as
`extension [A](p: Parser[A]) def parse(input, pos): ParseResult[A]`
(see §5.3.1).  Conformance: calculator from §5.4
round-trips.  Specialised interpreters
(`compileToPratt`, `validate`, `parseInline`) are explicit
v1.20.x follow-ups, NOT in this phase.

### Phase 4 — `std/parsing/helpers.ssc` (~2 days)

Tokenization helpers: `whitespace`, `identifier`, `number`,
`stringLit`, `keyword(s)`.  Conformance: a JSON parser
written entirely from these helpers.

### Phase 5 — `std/dsl/*` helpers (~3 days)

AST helpers, pretty-printer combinators, precedence climbing,
**plus mandatory `Span` + `HasSpan` types per §5.6**
(locked) and the **tentative** `Param[T]` / `RawInline` /
`Exec[D, F]` typeclass shapes per §5.7 + §7.1 (shipped as
opt-in convenience; convention-only enforcement in v1.20,
formal lock decided in v1.20.x once usage shows the shape).
Conformance: a typed `Calc` AST with pretty-printer that
round-trips through parse → AST → pretty → parse.

### Phase 6 — Documentation + examples (~2 days)

- `examples/dsl-sql-interpolator.ssc` — full SQL-like
  interpolator with compile-time validation
- `examples/dsl-calc-parser.ssc` — calculator with parser
  combinators
- `examples/dsl-json-parser.ssc` — JSON parser from `std/parsing`
- `docs/dsl.md` walkthrough updates

### Effort

Six phases, ~2.5 weeks end-to-end.  Mostly stdlib work
(no compiler changes beyond Phase 1 interpolator verification).
Conformance gates the merge.

## 9. Hard-no list (locked by design)

| Feature | Reason |
|---------|--------|
| **DSL = new keyword** (`dsl MyLang { … }`) | Reuse Scala 3 mechanisms (extension, interpolator, fluent API); no new syntax |
| **Parser-generator language** (BNF/EBNF as source) | Parser combinators give the same expressive power without a separate language to maintain |
| **Compile-time evaluation of arbitrary user parsers** | `inline` works for known-at-compile-time parts (interpolator validation); full parser evaluation at compile-time is `quoted.Expr` territory (v2.x) |
| **Macros to inline parser combinators** | `Parser[A]` runtime overhead is fine for v1; revisit when measurement shows hot-path cost |
| **Built-in parser-generator backed by a DFA / Pratt parser** | Parser combinators (LL with backtracking) cover 90% of DSL needs; DFA generation is a separate library if someone needs lex-perf |
| **Multi-line interpolators across lines** | Scala 3 `"""…"""` triple-quoted strings already work; no new syntax |

## 10. Open questions

### Locked architectural choices (recap)

- **Reified DSL by default** (§2.5) — all 3 flavours
  produce data; execution is an explicit extension method
- **Parser as ADT data, not a trait** (§5.2 / §5.3) —
  `enum Parser[+A]` with case-class nodes; combinators
  build the tree; `.parse(input)` extension method is the
  default interpreter; specialised interpreters
  (`compileToPratt`, `validate`, `parseInline`) ship as
  additional extensions
- **Left-recursion via combinator family** (§5.3.1) —
  `chainLeft` / `chainRight` / `chainPostfix` /
  `chainPrefix` cover ~95% of left-recursion needs without
  packrat overhead; general fixpoint deferred to v1.20.x
- **`Span` mandatory** for cross-DSL-composable AST nodes
  (§5.6)
- **Context-in-parser via ADT nodes** (§5.8) — `WithLocalContext`
  / `ReadContext` cases on the `Parser` enum + `ParserContext`
  marker trait; runtime evaluator carries the value, not
  thread-local; indentation-aware parsing built on top in
  v1.20.2

### Tentative — likely-but-not-locked (§5.7 / §7.1)

- **`Param[T]` / `RawInline` hygiene discrimination**
  (§5.7) — ship as opt-in helpers in `std/dsl/types.ssc`;
  enforce via type signatures only when ≥3 real
  injection-class DSLs surface
- **`Exec[D[_], F[_]]` composition typeclass** (§7.1) —
  ship convention-only in v1.20 (every DSL has SOME
  `.exec`-shaped extension; no formal typeclass); revisit
  formal `Exec` lock in v1.20.x once ≥3 DSLs would
  benefit from typeclass dispatch

### Still open (decide when real usage surfaces)

- **Interpolator backend support gap** — Phase 1 may discover
  that user-defined interpolators don't work on the
  interpreter today (the interpreter directly evaluates
  source rather than going through the typer's
  StringContext lowering).  Cost of fixing TBD; if large,
  promote to its own milestone.
- **Naming**: `std/parsing` vs `std/parser`?  Settled on
  plural `parsing` because the library covers parsing-the-
  activity (combinators, error reporting, position tracking)
  not just a `Parser` type.
- **Streaming parsing** for large inputs — `Parser[A]` is
  string-based; streaming requires either `Parser[A]` over
  `LazyList[Char]` or integration with v1.10 Generators.
  Defer to v1.20.x if real users need it.
- **Error recovery** — combinators today fail-fast on first
  error.  Error recovery (skipping to next sync token,
  collecting multiple errors) is a `std/parsing/recovery.ssc`
  follow-up.
- **Pratt parsing for precedence** — alternative to the
  recursive-descent precedence-climbing.  Add as Phase 5
  helper if it pulls weight; otherwise skip.
- **`inline def` interpolators in v1.20 vs v1.20.1** — Phase
  1 ships runtime interpolators; compile-time-validated
  ones depend on v1.14.  Probably v1.20 ships both
  (depending on v1.14 timing), or v1.20.1 picks up
  validation when v1.14 is firm.
- **Pretty-printer width tuning** — does `std/dsl/pretty`
  ship `Doc[A]` à la Wadler/Leijen, or simpler `string +
  indent` combinators?  Wadler is the standard but heavier;
  simpler may be enough for v1.
- **Typed interpolator outputs** (advanced — defer to
  v1.20.1+) — `sql"SELECT name FROM users"` returns
  `Query[Tuple1[String]]` not just `Query[?]`.  Requires
  compile-time SQL-subset parser via v1.14 `inline` +
  Mirror-based schema discovery.  Real win for type-safety
  but high implementation cost; revisit when v1.14 firms.
- **DSL versioning policy** — when a DSL author breaks
  syntax (`sql"..."` v2 incompatible with v1), how do
  consumers handle?  Probably ties into v1.19 dep import
  semver; locked once both v1.19 and v1.20 ship and
  community DSLs surface.

### TODO — separate mini-milestones when consumer surfaces

These are not part of v1.20 / v1.20.x scope.  Each is a
genuinely useful follow-up that needs its own design pass
when a concrete consumer asks:

- **Quasi-quotation** — `sql"... ${otherQuery}"` where
  `otherQuery: Query[A]` is itself a reified DSL value
  (not just a runtime value).  Allows DSL composition
  ("embed this sub-query at this position").  Probably
  builds on §5.7 hygiene with a new `EmbedReified[D]`
  marker; tackle when ≥2 DSLs want it.
- **Parser testing helpers** — `Parser.test(input,
  expected): TestCase`, property-based `parse(pretty(ast))
  == ast` shapes, fuzz-testing combinators.  Useful, no
  language-level blockers.  Ships as `std/parsing/testing.ssc`
  in a follow-up milestone.
- **DSL playground / REPL** — `ssc dsl <name>` interactive
  evaluation.  Useful for SQL-like and query-language
  authors.  Needs interactive REPL infrastructure (already
  in `MILESTONES.md` Beyond).
- **Generated DSL documentation** — auto-doc derived from
  Grammar / AST shape.  For interpolators, document
  expected forms.  Static-site generator integration.
  v2.x-class — touches doc-tooling pipeline.

## 11. Conformance plan

### Interpolator tests (3)

| Test | Exercises |
|------|-----------|
| `interpolator-basic.ssc` | User-defined `myDsl"…"` round-trip across all backends |
| `interpolator-escaping.ssc` | `"$$"` literal dollar, multi-line, special chars |
| `interpolator-typed-args.ssc` | `$intArg`, `$stringArg`, `$listArg` — typed substitution |

### Parser tests (4)

| Test | Exercises |
|------|-----------|
| `parser-primitives.ssc` | char / string / regex / satisfy + position tracking |
| `parser-combinators.ssc` | ~ / `|` / *  / + / map / named — calculator from §5.4 |
| `parser-helpers.ssc` | token / identifier / number / stringLit |
| `parser-json.ssc` | Full JSON parser from `std/parsing` helpers; matches `jsonParse` output |

### DSL helpers tests (2)

| Test | Exercises |
|------|-----------|
| `dsl-ast-round-trip.ssc` | parse → AST → pretty-print → parse equivalence |
| `dsl-precedence.ssc` | Precedence climbing for `1 + 2 * 3` → `1 + (2 * 3)` |

Each test runs on all three backends; observable output
matches exactly.
