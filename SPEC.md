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
routes:
  - method: GET
    path: /api/todos
    handler: listTodos
  - method: POST
    path: /api/todos
    handler: addTodo
---
```

`routes:` declares HTTP routes alongside the regular module surface;
each entry is equivalent to writing
`route(method, path) { req => handler(req) }` inline.  The handler is
looked up by name from the top-level defs in the module, so a typical
file just lists handlers in the manifest and defines them in
`scalascript` blocks below.

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

### 5.5 Functional Updates and Lenses

Case classes carry an auto-generated `copy` method that returns a new
instance with selected fields overridden. Both positional and named
arguments are accepted, with the Scala 3 rule that positionals must
precede nameds:

```scalascript
case class Address(street: String, city: String)
case class Person(name: String, age: Int, address: Address)

val alice = Person("Alice", 30, Address("Main St", "Boston"))
val older = alice.copy(age = 31)             // named override
val full  = alice.copy("Bob", 40, alice.address) // all-positional
val mixed = alice.copy("Bob", age = 40)       // positional, then named
val moved = alice.copy(address = alice.address.copy(city = "NYC"))
```

`Focus[T](_.field.subfield)` is a built-in lens constructor. It takes a
type argument `T` and a lambda whose body is a chain of field selects;
the result is a `Lens` value with `get`, `set`, `modify`, and `andThen`:

```scalascript
val ageLens  = Focus[Person](_.age)
val cityLens = Focus[Person](_.address.city)

ageLens.get(alice)                  // 30
ageLens.set(alice, 99)              // Person(Alice, 99, ...)
ageLens.modify(alice, _ + 10)       // Person(Alice, 40, ...)
cityLens.set(alice, "Paris")        // address.city updated; rest preserved

// Composition
val composed = Focus[Person](_.address).andThen(Focus[Address](_.street))
composed.set(alice, "Broadway")     // Person(Alice, 30, Address(Broadway, Boston))
```

Lenses are pure values — `set` and `modify` never mutate the input.
All three backends share the same semantics; the JVM backend lowers
`Focus[T](_.a.b)` to a literal `Lens((s: T) => s.a.b, (s, v) =>
s.copy(a = s.a.copy(b = v)))`, the JS backend emits a runtime
`_makeLens([...path])`, and the interpreter builds a path-keyed
`InstanceV("Lens", ...)`.

`Prism[Outer, Variant]` is the sum-type counterpart of `Lens`: it
focuses on a single case of an `enum` or sealed hierarchy. `getOption`
returns `Some(value)` when the runtime variant matches and `None`
otherwise; `set` and `modify` are no-ops when the variant doesn't
match.

```scalascript
enum Shape:
  case Circle(radius: Int)
  case Rect(width: Int, height: Int)

val circle = Prism[Shape, Circle]
val s: Shape = Circle(5)

circle.getOption(s)                         // Some(Circle(5))
circle.modify(s, c => Circle(c.radius * 2)) // Circle(10)
circle.set(Rect(3, 4), Circle(9))           // Rect(3, 4) — unchanged
circle.reverseGet(Circle(7))                // Circle(7) as Shape
```

The JVM backend lowers `Prism[O, V]` to a `Prism(getOption,
reverseGet)` literal whose `getOption` is `s match { case _v: V =>
Some(_v); case _ => None }`. The JS backend emits a runtime
`_makePrism('V')` and matches on the `_type` field. The interpreter
matches on the variant's typeName.

When a `Focus[T](...)` lambda path contains a `.some` step, the result
is an `Optional[T, A]` rather than a `Lens[T, A]`. Optionals expose
`getOption` (returns `None` when any `Option` along the path is empty),
`set` / `modify` (no-ops when the focus doesn't exist), and `andThen`
(composes with other Optionals or Lenses, lifting to Optional):

```scalascript
case class Address(street: String, city: String)
case class Profile(home: Option[Address])
case class User(name: String, profile: Option[Profile])

val cityOpt = Focus[User](_.profile.some.home.some.city)

val alice = User("Alice", Some(Profile(Some(Address("Main St", "Boston")))))
val bob   = User("Bob", None)

cityOpt.getOption(alice)            // Some("Boston")
cityOpt.getOption(bob)              // None
cityOpt.set(alice, "Paris").profile // Some(Profile(Some(Address("Main St", "Paris"))))
cityOpt.set(bob, "Paris")           // unchanged — bob has no profile
```

Lowering by backend:
- **JVM** — `Optional((s: T) => <getter>, (s: T, v) => <setter>)` where
  the getter threads through `Option.flatMap` / `.map` and the setter
  rebuilds nested `.copy(...)` calls; intermediate `Option`s are
  preserved via `.map`. `Lens.andThen(Optional)` / `Optional.andThen(Lens)`
  overloads in the preamble produce an `Optional`.
- **JS** — runtime helper `_makeOptional(steps)` walks an array of
  field names plus the marker `"__some__"`; missing layers yield `_None`
  for get and a no-op for set.
- **Interpreter** — path is `List[PathStep]` (`FieldStep(name)`,
  `SomeStep`, or `EachStep`); `opticGetOption` / `opticSet` walk the
  steps dynamically against `InstanceV` / `OptionV` / `ListV` values.

A `.each` step in the path turns the optic into a `Traversal[T, A]` —
the multi-foci sibling of `Lens` / `Optional`. Traversals expose
`getAll: T => List[A]` (every reachable focus), `modify(s, f)` (applies
`f` to each focus and rebuilds), `set(s, a)` (replaces every focus with
the same value), and `andThen` (composes with any other optic,
producing another Traversal).

```scalascript
case class Person(name: String, age: Int)
case class Team(name: String, members: List[Person])

val t = Team("eng", List(Person("Alice", 30), Person("Bob", 25)))

val nameOfEach = Focus[Team](_.members.each.name)

nameOfEach.getAll(t)                       // List(Alice, Bob)
nameOfEach.modify(t, n => n.toUpperCase)   // Team(eng, List(Person(ALICE, …), …))
nameOfEach.set(t, "anonymous")             // every member's name = "anonymous"
```

Composition rules (any optic ∘ Traversal → Traversal; Traversal ∘ any
→ Traversal):
- **Interpreter** — Traversal's `andThen` accepts Lens / Optional /
  Traversal by reading their `_path` or `_steps` and concatenating;
  Lens / Optional `andThen` likewise recognise a Traversal argument
  and lift the result.
- **JS** — runtime helper `_makeTraversal(steps)` walks an array of
  field names plus markers `"__some__"` / `"__each__"`. `_opticGetAll`
  uses `Array.flatMap`, `_opticModifyAll` uses `Array.map`.
- **JVM** — `case class Traversal[S, A](toList, modifyF)` in the
  preamble; cross-type `andThen` overloads on `Lens` / `Optional` /
  `Traversal` produce a `Traversal[S, B]`. Path emission threads the
  value through `List.flatMap` (for inner `.some` / `.each` segments)
  and `.map` (for the leaf field segment); modify rebuilds nested
  `.copy(...)` interleaved with `.map(p => …)` at every flat-step.

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

Each binding can be **aliased** with `Name as Alias`, so two libraries
that both export the same identifier can be used side by side:

```markdown
[Card as UICard](./ui-pack/card.ssc)
[Card as ChartCard](./chart-pack/card.ssc)

```scalascript
println(UICard.render(...))
println(ChartCard.render(...))
\```
```

Aliasing is mixable: `[Foo as F, Bar]` aliases `Foo` to `F` and
imports `Bar` under its own name.  Whitespace around `as` is
required.

**Package prefix.**  A module's front-matter `package:` field puts
all the module's top-level declarations under a dotted namespace:

```yaml
---
name: cards
package: org.example.ui
---
```

```scalascript
object Card:
  def render(t: String): String = html"<div class='card'>${t}</div>"
```

Importers see the module's contents through the prefix:

```markdown
[org](./cards.ssc)

```scalascript
println(org.example.ui.Card.render("hi"))
\```
```

Under the hood every `scalascript` code block in the module is
wrapped in nested `object`s matching the segments.  Two libraries
that each export `Card` can ship different `package:` prefixes and
coexist in a consumer's tree without an alias on the import.

Limitation (shared with all imports): the JS / JVM backends still
inline the whole module at the import site, so a single consumer
importing TWO packaged modules that both name their outer wrapper
(`org`) hits a duplicate-declaration error.  Workaround: import via
URL or use a dependency alias scheme so only one inline happens.

**URL imports** are supported too.  Replace the relative path with
an `http://` or `https://` URL and the importer fetches the file
into a local cache on first access, then reuses the cache:

```markdown
[Card](https://raw.githubusercontent.com/u/r/v1.0/card.ssc)
```

For frequently-imported libraries the URL can be pinned once in the
front-matter `dependencies:` map and referenced by a short scheme
name throughout the file:

```yaml
---
dependencies:
  cards: https://raw.githubusercontent.com/u/cards/v1.0
  ui:    https://cdn.example.com/ui-pack/2.3
---
```

```markdown
[Card](cards://card.ssc)         → https://…/cards/v1.0/card.ssc
[Button](ui://forms/button.ssc)  → https://…/ui-pack/2.3/forms/button.ssc
```

The scheme name (`cards`, `ui`) is the key in `dependencies:`; the
value is the base URL prefix.  Bare-string version constraints
(`^1.2.0`) still flow to scala-cli for the JVM target's Maven
resolution; only URL-shaped values activate the cross-backend
resolver.

Cache layout: `~/.cache/ssc/<scheme>/<authority>/<path>` — so
`https://github.com/foo/bar/v1.ssc` lands at
`~/.cache/ssc/https/github.com/foo/bar/v1.ssc`.  Relative imports
inside a URL-fetched file (`./helper.ssc`) are resolved by inverting
the cache layout and fetching from the same origin, so a library can
ship `.ssc` files that import their siblings without rewriting paths.

Set the environment variable `SSC_NO_NETWORK=1` to disallow any
outbound fetch.  Cached files keep working; uncached URLs fail with a
clear error — useful for sandboxed or reproducible builds.

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

#### 7.2.1 Async — built-in effect

The `Async` effect is pre-registered on all three backends — no `effect Async:`
declaration is needed.  Four operations and a default handler `runAsync(body)`
drive deterministic, single-threaded async-style code:

```scalascript
runAsync {
  val a = Async.async(() => 1)
  val b = Async.async(() => 2)
  Async.await(a) + Async.await(b)               // 3
}
```

| Operation                          | Effect                                    |
|------------------------------------|-------------------------------------------|
| `Async.delay(ms: Int): Unit`       | pause the calling thread for `ms` milliseconds |
| `Async.async(thunk: () => A): Future[A]` | execute `thunk`, wrap the result in a `Future` |
| `Async.await(fut: Future[A]): A`   | extract the value carried by a `Future`   |
| `Async.parallel(thunks: List[() => A]): List[A]` | run each thunk, collect results in declared order |

`runAsync` walks the same Free tree any other handler would, dispatching
`Async.*` `Perform` nodes against its built-in handler.  Non-Async
`Perform` nodes propagate outward so `runAsync` composes with `handle(...)`
in either order.

Semantics are deliberately single-threaded so observable output is
byte-identical across the interpreter, JS Node, and `ssc compile` backends
— a thunk passed to `async` runs immediately on the calling thread, and
`parallel` runs its thunks sequentially in declared order.  The exact
contract a user writes against (`await` produces values, `parallel`
preserves declared order) is the same one a real-thread / Promise-based
handler upholds, so swapping the handler later doesn't change observable
output for code that didn't rely on timing.  `delay` uses `Thread.sleep`
on the JVM and `Atomics.wait` on Node so it costs wall-clock time
without coloring the surrounding code as async.

An alternate `runAsyncParallel(body)` driver provides genuine
concurrency on the JVM backends (interpreter + `ssc compile`) using
`ExecutorService` + `CompletableFuture` — thunks passed to
`Async.async` and `Async.parallel` execute on real worker threads,
`Async.await` blocks the calling thread on the future.  `parallel`
still waits on each future in *declared* order, so byte-identical
output is preserved across the single- and parallel-handler variants
for code that doesn't rely on completion order:

```scalascript
runAsyncParallel {
  Async.parallel(List(
    () => { Async.delay(200); 1 },
    () => { Async.delay(200); 2 },
    () => { Async.delay(200); 3 }
  ))   // List(1, 2, 3) — finishes in ~200ms instead of ~600ms
}
```

The Node JS target currently aliases `runAsyncParallel` to `runAsync`
(single-threaded — real concurrency on Node will use `worker_threads`
in a follow-up); code written against the parallel handler still runs
correctly there, just without the speedup.

#### 7.2.2 Storage — built-in key-value effect

`Storage` is the persistence companion to `Async` — same Free-Monad
shape, two handlers (file-backed and ephemeral).  Five operations:

| Operation                          | Effect                            |
|------------------------------------|-----------------------------------|
| `Storage.get(key): Option[String]` | look up — `None` if absent        |
| `Storage.put(key, value): Unit`    | insert / overwrite                |
| `Storage.remove(key): Unit`        | delete                            |
| `Storage.has(key): Boolean`        | membership test                   |
| `Storage.keys(): List[String]`     | all keys, insertion order         |

```scalascript
runEphemeralStorage {
  Storage.put("user", "alice")
  println(Storage.get("user"))        // Some(alice)
  println(Storage.keys())             // List(user)
}
```

Two handlers, same body:

  - `runStorage(body[, path])` — JSON file-backed.  Path resolution:
    explicit second arg → `SSC_STORAGE_PATH` env var →
    `./ssc-storage.json`.  Hydrates from the file on scope entry,
    flushes after every mutation.
  - `runEphemeralStorage(body)` — in-memory; fresh map per scope,
    discarded at exit.  Conformance tests use this so they stay
    hermetic.

Available on all three backends.  Cross-backend wire format is
JSON `{key: stringValue, …}` with insertion order preserved so a
file written by one backend reads cleanly on another.

#### 7.2.3 Reactive signals

Fine-grained reactivity in the Solid / Knockout style — three
primitives, push-based notification with scheduled flush:

```scalascript
val count   = Signal(0)
val doubled = computed { count.get * 2 }
effect { println("c=" + count.get + " d=" + doubled.get) }
count.set(5)        // prints "c=5 d=10"
```

| Primitive                       | Effect                                        |
|---------------------------------|-----------------------------------------------|
| `Signal[A](initial: A)`         | mutable cell — `.get` reads with tracking, `.set(v)` writes |
| `computed[A] { expr }`          | read-only derived `Signal[A]`; auto-tracks its dependencies |
| `effect { expr }`               | reactive side-effect; runs once at registration, reruns on dep changes |

Implementation: a side-table keyed by a monotone id holds the
mutable state; reading a signal inside an active `effect` /
`computed` registers a mutual subscription; writing queues all
subscribers into a `LinkedHashSet`; a scheduled flush drains the
set, so each effect reruns at most once per synchronous
transaction (dedupes the diamond — root → derived → consumer,
where the consumer also reads the root).  Subscribers currently
running on the effect stack are skipped, so `effect { tick.set(tick.get + 1) }`
bumps `tick` once instead of infinite-looping.

Available on all three backends: the interpreter holds the
side-table in-process; JsGen emits the same push-model in pure
ES2020; JvmGen emits a typed `Signal[A]` case class so
`count.get * 2` typechecks under Scala 3.

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

`session: Map[String, String]` is populated from the incoming `Cookie:`
header — see *Sessions* below.

#### Sessions

ScalaScript ships HMAC-SHA256-signed cookie sessions on every server
runtime.  The wire format is identical across all three backends:

```text
session=<b64url(json(payload))>.<b64url(hmac_sha256(b64url_json))>
        ; Path=/; HttpOnly; SameSite=Lax        (Max-Age=0 when clearing)
```

The signing secret comes from the `SSC_SESSION_SECRET` environment
variable.  If unset, the runtime generates a per-process random key
and logs a one-line warning on stderr — sessions then don't survive a
process restart (fine for dev; surface for prod).

```scalascript
// Read — req.session is a Map[String, String] decoded from the cookie,
// or empty if the cookie was absent / tampered with.
val userId: Option[String] = req.session.get("user")

// Write — withSession attaches a setSession field; the runtime emits
// the signed Set-Cookie when the response goes out.
Response.html(body).withSession(Map("user" -> "alice"))

// Clear — clearSession() emits Set-Cookie with Max-Age=0.
Response.redirect("/").clearSession()
```

#### CSRF helpers

`csrfToken()` returns a fresh url-safe random string; the caller is
expected to stash it under `"csrf"` in the session and render it as a
hidden form field.  `csrfValid(req)` checks the request's `csrf` form
field (or the `X-CSRF-Token` header) against the session's stored
value, using constant-time comparison.  Both helpers are available on
all three backends.

```scalascript
route("GET", "/login") { req =>
  val token = req.session.getOrElse("csrf", csrfToken())
  Response.html(html"""<form method="POST">
    <input type="hidden" name="csrf" value="${token}">
    ...
  </form>""").withSession(req.session.updated("csrf", token))
}

route("POST", "/login") { req =>
  if !csrfValid(req) then Response.status(403, "CSRF check failed")
  else { ... }
}
```

See [examples/auth-demo.ssc](examples/auth-demo.ssc) for the full
login / logout flow.

#### JWT bearer-token auth

For stateless APIs — natural fit for the browser-SPA target where the
same `.ssc` serves both UI and JSON endpoints — ScalaScript ships
HS256 JWTs (RFC 7519):

```text
<b64url(header)>.<b64url(payload)>.<b64url(hmac_sha256(h_b64.p_b64))>
                       header = {"alg":"HS256","typ":"JWT"}
                       payload = JSON of a Map[String, String]
```

Secret resolution: `SSC_JWT_SECRET` is preferred; `SSC_SESSION_SECRET`
is used as a fallback so a small deployment only needs one env var.

```scalascript
// Sign — payload is any Map[String, String].  Caller sets `exp` etc.
val token = jwtSign(Map("sub" -> "alice", "role" -> "admin"))

// Verify — returns None on missing/tampered/expired tokens.
jwtVerify(token) match
  case Some(claims) => ...
  case None         => ...

// Inside a route handler, the Authorization: Bearer <token> header is
// pre-parsed for convenience:
req.bearerToken           // Option[String]
req.jwtClaims             // Option[Map[String, String]] (verified)
```

`jwtVerify` rejects malformed tokens, signature mismatches, non-HS256
header `alg`, and tokens whose `exp` claim (Unix seconds) is in the
past or not parseable as an integer.  Other claims (`nbf`, `iss`,
`aud`, …) are passed through verbatim — callers can enforce them.

#### Server-side session store

By default sessions are *stateless* — the signed cookie carries the
whole payload.  That's enough for small (<4KB) data and saves the
server any per-user state.  Three cases want server-side storage
instead:

  - **Large payloads.** Browsers cap cookies at ~4KB; a long list of
    permissions or a JWT bundle overflows.
  - **Instant revocation.** A stolen stateless cookie keeps working
    until its expiry; a server-side store can drop the entry on logout
    and the cookie's bytes become useless.
  - **Sensitive data.** Signed cookies are tamper-proof but readable
    by anyone with the bytes.  Keep secrets server-side.

Flip the switch with a single top-level call:

```scalascript
useSessionStore()                  // 30-minute idle TTL by default
useSessionStore(ttlSeconds = 600)  // 10-minute idle
```

After that, `withSession(payload)` stashes the payload in a process-
local map keyed by a random SSID, and the cookie carries only
`session=<b64url({"_ssid": "..."})>.<sig>`.  `req.session` transparently
dereferences the SSID, so existing handlers don't change.  TTL is
*idle* — each `req.session` access refreshes the entry's
last-access timestamp; a 30-min-TTL session that's hit every minute
lives forever.  `clearSession()` deletes both the cookie and the
server-side entry.

Implementation: lazy TTL sweep on every 256th access (no background
thread).  The default in-memory backend is per-process — for
multi-process deployments swap it for a `Storage`-backed store once
that effect lands.

#### HTTP Basic auth

For dev tooling and internal endpoints, a one-liner challenge:

```scalascript
route("GET", "/admin") { req =>
  req.basicAuth match
    case Some((user, pass)) if check(user, pass) => Response.html(...)
    case _ => Response.basicAuthChallenge("Admin area")
}
```

- `req.basicAuth: Option[(String, String)]` is parsed eagerly from
  `Authorization: Basic <b64(user:password)>` — malformed encodings
  surface as `None`.
- `Response.basicAuthChallenge(realm)` returns `401` with
  `WWW-Authenticate: Basic realm="..."` so the browser pops the
  native sign-in prompt.

Use over HTTPS only — Basic auth ships credentials in every request
in (effectively) plaintext.  Not appropriate for production user-facing
flows; see signed cookie sessions or JWT for those.

#### OAuth2 / OIDC

Authorization-code flow against external identity providers.  Two
primitives, both available on all three backends:

```scalascript
// 1) Build the provider's authorize URL.  Caller picks a `state`,
//    stashes it in the session, and verifies on callback.
val state = csrfToken()
Response.redirect(oauthAuthorizeUrl(
  "google", clientId, "http://localhost:8080/oauth/callback", state
)).withSession(req.session.updated("oauth_state", state))

// 2) Exchange the returned code for tokens.  POSTs to the provider's
//    token endpoint and returns the parsed JSON / form-encoded body
//    as Option[Map[String, String]].
route("GET", "/oauth/callback") { req =>
  if req.query.get("state") != req.session.get("oauth_state") then
    Response.status(403, "bad state")
  else oauthExchangeCode(
    "google", req.query("code"),
    clientId, clientSecret,
    "http://localhost:8080/oauth/callback"
  ) match
    case Some(tok) => Response.redirect("/").withSession(req.session.updated("access_token", tok("access_token")))
    case None      => Response.status(401, "token exchange failed")
}
```

Built-in providers — preset authorize / token URLs and default scopes:

| name      | authorize URL                                            | default scope        |
|-----------|----------------------------------------------------------|----------------------|
| `google`  | `https://accounts.google.com/o/oauth2/v2/auth`           | `openid email profile` |
| `github`  | `https://github.com/login/oauth/authorize`               | `user:email`         |

`oauthExchangeCode` returns `None` on non-2xx responses or malformed
bodies; both JSON and `application/x-www-form-urlencoded` provider
responses are accepted (GitHub uses the latter by default).  Custom
providers are not yet first-class — for now, hand-roll the redirect
+ exchange against an arbitrary URL.

```scalascript
// 3) After exchange, fetch the user profile from the provider's
//    /userinfo endpoint with the access token.  Returns the parsed
//    JSON object as Map[String, String] (nested values surface as
//    raw JSON strings).
oauthUserinfo("google", accessToken)   // Some(Map("email" -> ..., "name" -> ...))
oauthUserinfo("github", accessToken)   // Some(Map("login" -> ..., "email" -> ..., ...))
```

`oauthUserinfo` follows the same `Authorization: Bearer <token>` +
`Accept: application/json` shape every well-known provider uses;
GitHub additionally requires a User-Agent header, which the helper
sets automatically.

State handling is the caller's job — pair it with sessions and CSRF
above.  The OAuth helpers stay narrow on purpose so they compose with
the rest of the auth surface instead of duplicating it.  See
[`examples/oauth-demo.ssc`](examples/oauth-demo.ssc) for a complete
end-to-end flow driven by env-supplied credentials.

#### Environment access

```scalascript
val secret = getenv("APP_SECRET")              // "" when unset
val port   = getenv("PORT", "8080")            // fallback default
```

`getenv(key)` / `getenv(key, default)` reads from the process
environment on the interpreter and JVM backends; the JS Node target
reads `process.env`; the browser-SPA target has no environment so the
default is always returned.  Used by `oauth-demo.ssc` to keep client
secrets out of source.

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
- **Static rendering** (`ssc render <file> [path]`) — runs the
  interpreter in *headless mode*: `serve(port)` becomes a no-op so the
  HTTP listener never binds, but `route(...)` calls (and front-matter
  `routes:` entries) still register their handlers in the route table.
  After section evaluation, the requested GET handler is invoked with
  a synthetic empty-headers / empty-body `Request`; the response body
  is printed to stdout.  Useful for generating static HTML from a
  server-style `.ssc` page without booting a server.  The output is
  byte-identical to `curl http://…/<path>` against the same file
  served under `ssc <file>`.
- **Batch static build** (`ssc build <src-dir> [<out-dir>]`) — walks
  `src-dir` **recursively** for `.ssc` files, runs each headlessly,
  and writes every registered literal GET route to disk under
  `<out-dir>` (default `dist/`).  The path-to-file mapping is `/` →
  `index.html`, `/about` → `about.html`, `/blog/x` → `blog/x.html`
  (subdirectories created as needed).  Routes with `:capture`
  segments and files that register no GET routes are skipped.  When
  two files claim the same URL the build emits a `[warn]` line —
  last write wins, but it's almost always a structural bug.

  The page walker skips conventional non-page directories:
  `components/` (imported module convention), `target/`,
  `node_modules/`, `dist/`, `out/`, `.scala-build/`, and any
  directory whose name starts with `.`.

  In addition to rendered pages, `ssc build` runs an **asset pipeline**:
  every non-`.ssc` file under `src-dir` is mirrored into `<out-dir>`
  preserving its relative path, so `<src-dir>/favicon.svg` lands at
  `<out-dir>/favicon.svg`, `<src-dir>/assets/extra.css` lands at
  `<out-dir>/assets/extra.css`, and so on.  The asset walker is
  recursive too and also walks into `components/` (a component
  module may sit next to its icon).  Only the tooling / build-output
  dirs above are skipped.
- **Bundle** (`ssc bundle <file.ssc> [<file.ssc>…] [-o name.sscpkg]`) —
  packs one or more `.ssc` entry files together with every transitively-
  imported `.ssc` into a zip archive (`.sscpkg`).  A consumer unzips
  and uses the entries with relative imports — no network, no registry.

  Archive layout:
  ```text
  bundle.yaml                  manifest: entries + transitive list
  <entry-1>.ssc                each entry at the archive root
  <entry-2>.ssc
  <relative paths>/...         every imported file under its
                               path relative to the bundle root
  _external/<basename>         files that lived ABOVE the bundle
                               root in the source tree — flattened
                               here; import paths inside the bundle
                               are rewritten so the archive is
                               self-contained.
  ```

  The bundle root is the common parent directory of every entry's
  parent.  Default output name is `<entry>.sscpkg` for a single entry,
  `bundle.sscpkg` for multiple entries.

### 8.4 Components

A "component" in ScalaScript is a convention, not a compiler feature: a
`.ssc` file owns one **`object`** whose fields are everything callers
need to render the component.  The standard shape is:

```scalascript
object Name:
  val css: String = """ ... """
  def render(p1: T1, p2: T2, ...): String = html"""..."""
```

The module is then imported with `[Name](./path/to/name.ssc)` and used
as `Name.render(...)` / `Name.css`.

```ssc
[Button](./components/button.ssc)
[Card](./components/card.ssc)

```scalascript
val styles = List(Button.css, Card.css).mkString("\n")
val page   = html"""
  <style>${raw(styles)}</style>
  ${raw(Card.render("First", "Body."))}
  ${raw(Button.render("Save"))}
"""
\```
```

Why this shape:

- **One source of truth per component.**  The CSS and the render logic
  live in the same `.ssc` file; renaming a class only has to be done in
  one place.
- **No new compiler concept.**  Imports already pull `Name` into scope,
  `object`s already work cross-backend, `html"..."` and the typed HTML
  DSL compose without extra glue.
- **Composition is plain function calls.**  Nesting, list rendering,
  conditionals — all use the language as-is (`items.map(Card.render(...))
  .mkString`).
- **CSS aggregation via `collectCss(...)`.**  A built-in helper
  reaches into each argument's `css` field and concatenates them:

  ```scalascript
  val styles = collectCss(Button, Card, FormInput)
  // styles = Button.css + "\n" + Card.css + "\n" + FormInput.css
  ```

  Arguments that don't expose a String `css` are silently skipped, so
  ordinary objects can be mixed in.  Identical output on all three
  backends.

- **JS aggregation via `collectJs(...)`.**  Symmetric with `collectCss`:
  reads each argument's `js` field and concatenates them.  A component
  that needs a sprinkle of client-side interactivity can co-locate the
  handler:

  ```scalascript
  object Counter:
    private val sc = scope("Counter")
    val css: String = sc.css(""".root { … } .button { … }""")
    val js:  String = s"""
      document.querySelectorAll('.${sc.cls("root")}').forEach(root => {
        const btn = root.querySelector('.${sc.cls("button")}');
        btn.addEventListener('click', () => /* … */);
      });
    """
    def render(...): String = html"""<div class="${sc.cls("root")}">…</div>"""
  ```

  Pages then write `<script>${raw(collectJs(Counter, …))}</script>`.

- **Class scoping via `scope(name)`.**  Two components can both write
  bare class names like `.title` without conflict if each runs its
  CSS through a per-component scope:

  ```scalascript
  object Card:
    private val s = scope("Card")

    val css: String = s.css("""
      .title { font-size: 1.1em; }
      .body  { color: #374151; }
    """)
    // expands to ".title__Card { … } .body__Card { … }"

    def render(t: String, b: String): String = html"""
      <div class="${s.cls("title")}">${t}</div>
      <div class="${s.cls("body")}">${b}</div>
    """
  ```

  `s.css(stylesheet)` is a regex pass that suffixes each `.identifier`
  selector with `__<scope>`; `s.cls(name)` returns the same suffixed
  name for use in render output.  Limitations: the rewriter doesn't
  understand `url(./file.ext)`-style dots — keep URL strings free of
  bare-identifier dots if you depend on them.

A minimal worked example lives in `examples/components-demo.ssc`
(importing `examples/components/{button,card}.ssc`) and is covered by
the cross-backend smoke test in `e2e/components-smoke.sh`.

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
