# ScalaScript Language Specification

**Version**: 0.1.0-draft
**Status**: Work in Progress

## 1. Introduction

ScalaScript is a hybrid language that unifies Markdown document structure with Scala-style typed expressions. This specification defines the syntax, type system, and semantics of the language.

### 1.1 Design Goals

1. Markdown constructs are first-class syntax, not comments
2. Type safety with Scala-style type system
3. Target-independent semantics
4. Human-readable source that is also machine-parseable

### 1.2 Notation

This specification uses EBNF notation. See [grammar/scalascript.ebnf](grammar/scalascript.ebnf) for the complete formal grammar.

## 2. Lexical Structure

### 2.1 Source Files

- File extension: `.ssc`
- Encoding: UTF-8
- Line endings: LF or CRLF (normalized to LF)

### 2.2 Document Structure

A ScalaScript file consists of:

1. **Optional YAML front-matter** (module manifest)
2. **Markdown body** with embedded code regions

```text
[front-matter]
[markdown-body]
```

### 2.3 Front-Matter

YAML block delimited by `---` lines at the start of the file:

```yaml
---
name: module-name
version: 1.0.0
dependencies:
  - other-module: ^2.0.0
exports:
  - functionName
  - TypeName
---
```

See [schemas/frontmatter.yaml](schemas/frontmatter.yaml) for the complete schema.

### 2.4 Comments

- Markdown: HTML comments `<!-- comment -->`
- Code blocks: Scala-style `//` and `/* */`

## 3. Markdown as Syntax

### 3.1 Headings → Namespaces/Scopes

Headings define hierarchical scopes:

```markdown
# Module          → top-level namespace
## Section        → nested scope
### Subsection    → further nested
```

Scope rules:
- Definitions in inner scopes shadow outer scopes
- Siblings do not see each other's private definitions
- Heading level determines nesting depth

### 3.2 Links → Imports/References

Markdown links serve as imports and cross-references:

```markdown
[std/collections](std/collections)     → import module
[List](std/collections#List)           → import specific item
[see also](#Section)                   → internal reference
```

### 3.3 Fenced Code Blocks → Typed Expressions

Code blocks carry a language annotation that determines how they are
processed by each backend.

````markdown
```scalascript
def add(x: Int, y: Int): Int = x + y
```
````

Supported language tags:

| Tag | Language | Description |
|-----|----------|-------------|
| `scalascript` | ScalaScript | Full ScalaScript dialect: effects/handlers, tail-call optimisation, content helpers, module imports. Executed by the interpreter, transpiled by the JS backend, compiled by the JVM backend. See §7.4 for per-backend TCO support. |
| `ssc` | ScalaScript | Legacy alias for `scalascript`. |
| `scala` | Standard Scala 3 | No ScalaScript-specific extensions. Executed by the interpreter and JVM backend as standard Scala 3. The JS backend compiles these blocks via Scala.js (`scala-cli --js`). |

A `.ssc` document may freely mix `scala` and `scalascript` blocks.
Definitions in `scala` blocks are visible to subsequent `scalascript` blocks
(and vice versa) within the same file because they share the interpreter
environment.

Other tags (`json`, `yaml`, `text`, etc.) are treated as inert prose by all
backends.

### 3.4 Inline Interpolation

Inline code with `${}` is evaluated and interpolated:

```markdown
The sum is `${add(2, 3)}`.
```

### 3.5 Lists → Data Structures

Ordered and unordered lists can represent data:

```markdown
- item1
- item2
- item3
```

May be typed as `List[String]` in context.

## 4. Type System

### 4.1 Primitive Types

| Type | Description | Literals |
|------|-------------|----------|
| `Unit` | No value | `()` |
| `Boolean` | Truth value | `true`, `false` |
| `Int` | 32-bit integer | `42`, `-1`, `0xFF` |
| `Long` | 64-bit integer | `42L` |
| `Double` | 64-bit float | `3.14`, `1e10` |
| `String` | Unicode text | `"hello"`, `"""multi"""` |
| `Char` | Unicode char | `'a'` |

### 4.2 Compound Types

```scalascript
// Tuples
(Int, String)
(1, "hello")

// Functions
Int => String
(Int, Int) => Int

// Generics
List[Int]
Map[String, Int]
Option[A]
```

### 4.3 Algebraic Data Types

```scalascript
enum Color:
  case Red, Green, Blue

enum Option[+A]:
  case Some(value: A)
  case None
```

### 4.4 Type Inference

Types are inferred where possible:

```scalascript
val x = 42          // x: Int
val f = (x: Int) => x + 1  // f: Int => Int
```

Explicit annotations required for:
- Public API definitions
- Recursive functions
- Ambiguous contexts

### 4.5 Subtyping

- `Nothing` is bottom type (subtype of all)
- `Any` is top type (supertype of all)
- Variance annotations: `+` covariant, `-` contravariant

## 5. Expressions

### 5.1 Literals

```scalascript
42                  // Int
3.14                // Double
"hello"             // String
true                // Boolean
()                  // Unit
```

### 5.2 Definitions

```scalascript
val x: Int = 42                       // immutable value
var y: Int = 0                        // mutable variable
def f(x: Int): Int = x+1              // function
def g(x: Int, step: Int = 1): Int = x + step  // default parameter
type Alias = List[Int]                // type alias
```

Default parameters are supported across the interpreter, type checker, and
JS transpiler. The JVM backend inherits Scala 3's native support.

### 5.3 Control Flow

```scalascript
if condition then expr1 else expr2

expr match
  case pattern1 => result1
  case pattern2 => result2

for x <- collection yield transform(x)

while condition do expr
```

### 5.4 Pattern Matching

```scalascript
x match
  case 0 => "zero"
  case n if n > 0 => "positive"
  case _ => "negative"

// Destructuring
case (a, b) => a + b
case Some(x) => x
case head :: tail => head
```

## 6. Module System

### 6.1 Module Identity

Defined in front-matter:

```yaml
---
name: my-module
version: 1.0.0
---
```

### 6.2 Exports

Explicit exports in front-matter:

```yaml
exports:
  - publicFunction
  - PublicType
```

Or everything at top scope is public by default.

### 6.3 Imports

Via Markdown links:

```markdown
[collections](std/collections)

Using `${collections.List(1, 2, 3)}`
```

Or selective import:

```markdown
[List, Map](std/collections)
```

### 6.4 Visibility

- `# Heading` scope members: public by default
- `## Private` section: private to parent scope
- Explicit `private` modifier in code blocks

## 7. Semantics

### 7.1 Evaluation Order

- Strict evaluation (not lazy by default)
- Left-to-right argument evaluation
- Short-circuit for `&&` and `||`

### 7.2 Effects

ScalaScript supports algebraic effects and handlers — a structured mechanism
for defining, performing, and intercepting side effects without monads.
Effects are defined as named interfaces; handlers intercept them and can
resume the continuation, abort, or transform the result.

Implementation: a **trampolined Free Monad** (`Pure | Perform | FlatMap`) with
constant-time `flatMap` and a right-associating step loop — stack-safe in
bind-chain depth (Bjarnason 2012). `resume(v)` invokes the captured Scala
continuation directly, so side effects in the handler body run exactly once;
multi-shot handlers interpret each `resume` branch independently.

Supported on all three backends — the **JVM interpreter**, the **JS transpiler**,
and the **JVM backend** (`ssc compile`, via JvmGen): each emits the same Free
Monad runtime and CPS-transforms function bodies flagged as effectful so they
build the same Free tree at runtime.

### 7.3 Interop

Backends define interop mechanisms:
- JVM: Java interop via Scala semantics
- JS: JavaScript interop via facade types

### 7.4 Tail-Call Optimisation

ScalaScript guarantees stack-safe execution of tail-recursive calls without
requiring an `@tailrec` annotation. Both **self-recursive** tail calls
(a function calling itself in tail position) and **mutual** tail calls
(two or more functions that call each other in tail position) are supported.

Per-backend status:

| Backend            | Self-TCO | Mutual TCO | Mechanism |
|--------------------|----------|------------|-----------|
| JVM interpreter    | ✅       | ✅         | Trampoline catching `TailCall` / `MutualTailCall` signals thrown from native-function shims that shadow the recursive name(s). |
| JS transpiler      | ✅       | ✅         | JsGen rewrites tail-position calls into a `while`-loop reassignment of the parameter vector; mutual cliques pivot through a `_tailCall` sentinel and a top-level `_trampoline`. |
| JVM backend (`ssc compile`) | ✅ | ✅ | Self-TCO inherits Scala 3's native optimisation. Mutual cliques are detected via SCC analysis of tail-position calls; each clique member is rewritten to an `_f_impl` body that returns a `_TailCall` thunk for friend-calls and reassigns parameters for self-calls, driven by a top-level `_trampoline`. |

`@tailrec` is not required and is not part of the surface syntax; the
guarantee above is provided automatically wherever the backend supports it.

## 8. Standard Library

### 8.1 Core Types

- `Option[A]` — optional values
- `Either[E, A]` — error handling
- `List[A]` — immutable list
- `Map[K, V]` — immutable map
- `Set[A]` — immutable set

### 8.2 Core Functions

```scalascript
def println(x: Any): Unit
def assert(cond: Boolean, msg: String): Unit
def require(cond: Boolean, msg: String): Unit
```

### 8.3 Web primitives

ScalaScript provides a minimal REST/web layer for the JVM interpreter's
`serve` mode.  The same `.ssc` document declares the routes and starts the
server; handlers execute in the interpreter session that registered them, so
they see the document's top-level mutable state directly.

```scalascript
def route(method: String, path: String)(handler: Request => Response): Unit
def serve(port: Int): Unit
```

Path syntax: literal segments separated by `/`, with `:name` captures
extracted into `Request.params`.  Example: `route("GET", "/users/:id") { req =>
  Response.text(req.params.get("id").get) }`.

#### Request

```scalascript
case class Request(
  method:  String,
  path:    String,
  params:  Map[String, String],   // path captures (e.g. :id)
  query:   Map[String, String],   // ?k=v
  headers: Map[String, String],
  body:    String,
  form:    Map[String, String]    // application/x-www-form-urlencoded body
)
```

`form` is eagerly parsed from `body` when the request's `Content-Type`
starts with `application/x-www-form-urlencoded` or `multipart/form-data`
(text parts only — file parts, those with a `filename=` directive, are
skipped); for any other content type it is `Map.empty` and the handler
can still read the raw `body`.  A dedicated file-upload API is not yet
exposed.

#### Response

```scalascript
case class Response(
  status:  Int = 200,
  headers: Map[String, String] = Map.empty,
  body:    String = ""
)
object Response:
  def html(body: Any): Response
  def text(body: Any): Response
  def json(body: Any): Response          // structural JSON encoder
  def redirect(url: String): Response
  def notFound(body: Any = "Not Found"): Response
  def status(code: Int, body: Any = ""): Response
```

`Response.json(...)` accepts arbitrary values — `List`, `Map`, `Option`,
case classes, tuples, primitives — and serialises them to JSON with
proper string escaping.  A bare `String` argument is treated as already-
serialised JSON and passed through verbatim (so callers that hand-build
JSON bodies keep working).  The encoder is identical across all three
backends (byte-equal output).

A handler may return a `Response`, a `String` (becomes a 200 text response),
or `Unit` (becomes a 204).

#### HTML / CSS string interpolators

Inside any code block, `html"..."` and `css"..."` produce `String` values
with `${expr}` interpolation.  In `html"..."`, interpolated values are
HTML-escaped unless they were produced by `raw(s)`.

```scalascript
val safe = html"<p>hello ${userInput}</p>"        // userInput escaped
val outer = html"<div>${raw(safe)}</div>"          // safe passed through
```

#### Typed HTML DSL

ScalaScript ships a tag-constructor set at the top level: `html`, `head`,
`body`, `div`, `span`, `p`, `a`, `h1`–`h6`, `ul`, `li`, `table`, `tr`, `td`,
`form`, `button`, `label`, … plus void tags (`br`, `hr`, `img`, `input`,
`link`, `meta`).  Attribute keys live under an `attr` namespace
(`attr.cls`, `attr.id`, `attr.href`, `attr.type_`, …) and pair with values
via the `:=` operator.  Each tag returns a `_Raw` HTML node so the result
composes with `html"..."` without double-escaping.

```scalascript
val items = List("milk", "eggs", "<bread>")
val page = html(
  head(title("Demo"), link(attr.rel := "stylesheet", attr.href := "/style.css")),
  body(
    h1(attr.cls := "hero", "Welcome"),
    ul(items.map(li))                  // Lists flatten into children
  )
)
// → <html><head>…</head><body><h1 class="hero">Welcome</h1>
//    <ul><li>milk</li><li>eggs</li><li>&lt;bread&gt;</li></ul>…</body></html>
```

Supported on all three backends.  The interpreter, JsGen, and JvmGen each
ship the same tag registry, attribute namespace, and `:=` operator, and
they emit byte-identical HTML for the same source.

#### Heading-bound `html` / `css` blocks (interpreter)

A fenced ```` ```html ```` or ```` ```css ```` block placed under a
heading binds the rendered string to a section identifier.  The block
runs in source order with the rest of the section, so `${expr}` can
reference any binding defined earlier in the same section.

```ssc
# Page

```scalascript
val title = "Welcome"
val user  = "<World>"
\```

```html
<h1>${title}</h1>
<p>Hello, ${user}</p>
\```

```scalascript
println(Page.html)        // <h1>Welcome</h1><p>Hello, &lt;World&gt;</p>
\```
```

The section identifier is the heading text camelCased over runs of
alphanumerics; the first word keeps its original casing so `# Page`
binds to `Page` (object-style) and `# my page` binds to `myPage`
(val-style).  Multiple blocks of the same kind in one section overwrite
each other; an `html` block and a `css` block in the same section both
land as fields on the same `Page` instance (`Page.html`, `Page.css`).
Supported on all three backends: the interpreter renders eagerly in source
order, JsGen emits JS template literals assigned to a per-section object,
and JvmGen emits `lazy val` accessors on a per-section `object` so forward
references to definitions later in the module resolve at access time.

#### Backends

The REST primitives are available on all three backends:

- **JVM interpreter** (`ssc run` / `bin/ssc`) — handlers run inside the
  interpreter session that registered them.  When no route matches, the
  request falls through to static asset serving from the root directory
  (any non-`.ssc` file is served with a sniffed `Content-Type`; path-traversal
  is blocked by canonical-path checks), and then to `.ssc`-page rendering.
- **JVM backend** (`ssc compile` / `bin/sscc`) — JvmGen emits a `serveRuntime`
  preamble (case classes for `Request` / `Response`, the route registry, and
  a JDK `HttpServer` dispatcher) when the module calls `route(...)`.  The
  compiled `.sc` script blocks on `Thread.join` after `serve(port)` returns.
  Unmatched routes fall through to static asset serving (under the cwd,
  with the same MIME-sniffing and traversal guard as the interpreter)
  before 404'ing.
- **JS backend, Node target** (`ssc emit-js` / `bin/jssc`) — JsGen emits
  a Node `http` server runtime in `JsRuntime`.  Node's event loop keeps
  the process alive once `serve(port)` calls `server.listen(...)`.
  Static asset fall-through uses Node's `fs.realpathSync` for the
  traversal guard.
- **JS backend, browser SPA target** (`ssc emit-spa`) — same source,
  wrapped as a self-contained `<!doctype html>` document with the
  runtime + a `JsRuntimeBrowserPatch` overlay embedded in a single
  `<script>`.  The overlay overrides `serve(...)` so it hooks `click`
  on `<a>` elements (same-origin links only — external / `mailto:` /
  fragment hrefs are left to the browser), listens to `popstate`, and
  dispatches the current `location.pathname` through the same
  `_routes` / `_matchPath` / `Response` machinery the Node target
  uses.  `Response.html(...)` swaps `document.body.innerHTML`;
  `Response.json(...)` is shown as a `<pre>`; redirects update the
  history and dispatch again.  Output from `println` flushes to
  `console.log`.  The Node-only static-asset helpers stay in the
  bundle as dead code — they're lazily loaded inside the original
  `serve(port)`, which is shadowed by the browser override.

## Appendix A: Reserved Words

```text
abstract case catch class def do else enum
extends false final finally for given if
implicit import lazy match new null object
override package private protected return
sealed super then this throw trait true try
type val var while with yield
```

## Appendix B: Operator Precedence

From lowest to highest:
1. Assignment operators
2. `||`
3. `&&`
4. `|`
5. `^`
6. `&`
7. `==`, `!=`
8. `<`, `>`, `<=`, `>=`
9. `+`, `-`
10. `*`, `/`, `%`
11. Unary `+`, `-`, `!`, `~`
12. Postfix operators

## Appendix C: Grammar Summary

See [grammar/scalascript.ebnf](grammar/scalascript.ebnf) for the complete EBNF grammar.
