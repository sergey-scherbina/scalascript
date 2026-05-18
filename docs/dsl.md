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

### 5.2 Core types

```scala
case class Position(line: Int, col: Int):
  override def toString = s"$line:$col"

case class ParseError(message: String, pos: Position, context: List[String] = Nil)

enum ParseResult[+A]:
  case Ok[A](value: A, rest: String, pos: Position) extends ParseResult[A]
  case Err(error: ParseError) extends ParseResult[Nothing]

trait Parser[+A]:
  def parse(input: String, pos: Position = Position(1, 1)): ParseResult[A]
```

### 5.3 Combinators

```scala
object Parser:
  // Primitives
  def char(c: Char): Parser[Char]
  def string(s: String): Parser[String]
  def regex(pattern: String): Parser[String]
  def satisfy(pred: Char => Boolean): Parser[Char]

  // Choice
  extension [A](p: Parser[A]) def |(other: => Parser[A]): Parser[A]

  // Sequence
  extension [A](p: Parser[A])
    def ~[B](other: => Parser[B]): Parser[(A, B)]
    def ~>[B](other: => Parser[B]): Parser[B]    // discard left
    def <~[B](other: => Parser[B]): Parser[A]    // discard right

  // Repetition
  extension [A](p: Parser[A])
    def *(): Parser[List[A]]      // zero or more
    def +(): Parser[List[A]]      // one or more
    def ?(): Parser[Option[A]]    // optional

  // Transform
  extension [A](p: Parser[A])
    def map[B](f: A => B): Parser[B]
    def flatMap[B](f: A => Parser[B]): Parser[B]

  // Named
  extension [A](p: Parser[A]) def named(name: String): Parser[A]   // for errors
```

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

## 8. Implementation phases (v1.20)

### Phase 1 — User-defined interpolators (~3 days)

Verify `extension (sc: StringContext) def myDsl(args: Any*)`
works on all three backends.  Fix the gaps found.  Conformance:
custom-interpolator round-trip, escaping, mixed-type args.

### Phase 2 — `std/parsing/core.ssc` primitives (~2 days)

`Parser[A]` trait, `Position`, `ParseResult`, `ParseError`,
plus `char` / `string` / `regex` / `satisfy` primitives.
Conformance: each primitive parses correctly + reports
errors at the right position.

### Phase 3 — `std/parsing/combinators.ssc` (~3 days)

`~` / `|` / `*` / `+` / `?` / `map` / `flatMap` / `~>` / `<~`
/ `named`.  Conformance: calculator from §5.4 round-trips.

### Phase 4 — `std/parsing/helpers.ssc` (~2 days)

Tokenization helpers: `whitespace`, `identifier`, `number`,
`stringLit`, `keyword(s)`.  Conformance: a JSON parser
written entirely from these helpers.

### Phase 5 — `std/dsl/*` helpers (~3 days)

AST helpers, pretty-printer combinators, precedence climbing.
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
