# ScalaScript

A language where **Markdown is syntax, not decoration** — `.ssc` files are
executable documents combining YAML front-matter, Markdown prose, and
Scala 3 code blocks.

`.ssc` files support two code-block languages:

| Annotation | Language | Backends |
|------------|----------|----------|
| ` ```scalascript` | ScalaScript dialect — effects, handlers, content helpers, TCO | interpreter · JS transpiler · JVM |
| ` ```scala` | Standard Scala 3 — no ScalaScript extensions | interpreter · **Scala.js** (JS) · JVM |

````ssc
---
name: hello
version: 1.0.0
---

# Hello World

```scalascript
def greet(name: String): String = s"Hello, $name!"
println(greet("World"))
```
````

```text
Hello, World!
```

## Quick Start

**Requirements:** [scala-cli](https://scala-cli.virtuslab.org) · [Node.js](https://nodejs.org) (for JS backend) · [sbt](https://www.scala-sbt.org) (optional, for library use)

```bash
git clone https://github.com/sergey-scherbina/scalascript
cd scalascript

# One-time setup: builds the self-contained ssc launcher and symlinks the
# scripts/launchers/* wrappers into bin/.  The entire bin/ is generated
# and gitignored — sources live in scripts/launchers/.
./scripts/install.sh bin/ssc

# Interpreter (tree-walking, no compilation step)
bin/ssc examples/hello.ssc

# Watch mode — re-run on every file change
bin/ssc watch examples/hello.ssc

# Interactive REPL
bin/ssc repl

# Transpile to JavaScript and run via Node.js
bin/jssc examples/hello.ssc

# Compile to JVM bytecode and run via Scala 3 / scala-cli
bin/sscc examples/hello.ssc

# Run all examples
./examples/run-all.sc

# Start HTTP server for the examples browser
bin/http.ssc
```

## What Works

All three backends support `scalascript` blocks.  `scala` blocks (standard
Scala 3) are supported by the interpreter and JVM backend; the JS backend
compiles them via Scala.js.

| Feature | Syntax |
|---------|--------|
| Values and variables | `val x = 42`, `var n = 0` |
| Functions | `def f(x: Int): Int = x * 2` |
| Lambdas and closures | `val double = (x: Int) => x * 2` |
| Higher-order functions | `xs.map(double)`, `xs.filter(_ > 0)` |
| Case classes | `case class Point(x: Double, y: Double)` |
| Enums / sealed traits | `enum Color { case Red; case Green; case Blue }` |
| Recursive ADTs | `enum Tree { case Leaf(v: Int); case Branch(l: Tree, r: Tree) }` |
| Pattern matching | `x match { case Some(n) => n; case None => 0 }` |
| For comprehensions | `for x <- xs if x > 0 yield x * x` |
| While loops | `while n > 0 do { ... }` |
| Collections | `List`, `Map`, `Option` with full method dispatch |
| Tuples | `val t = (1, "hello"); t._1` |
| String interpolation | `` s"Hello, $name" ``, `` md"..." `` (strips indent) |
| Math | `math.sqrt`, `math.abs`, `math.pow`, `math.Pi`, … |
| Extension methods | `extension (n: Int) def squared: Int = n * n` |
| Typeclasses | `trait Show[A]`, `given`, `summon[Show[Int]]` |
| Recursion | factorial, Fibonacci, tree traversal |
| Tail-call optimisation | self-TCO and mutual TCO — no `@tailrec` required |
| Algebraic effects | `effect E:`, `handle(body) { case E.op(arg, resume) => ... }` |
| Default parameters | `def f(x: Int, step: Int = 1)`, also on class/enum constructors |
| Module imports | `[name](./lib.ssc)` markdown links bring definitions into scope |
| Content helpers | `doc(...)` / `render(...)` structured output |
| HTML / CSS interpolators | `html"..."` (auto-escaping) and `css"..."` with `${expr}` |
| REST primitives | `route(method, path)(handler)` + `Request`/`Response` + `serve(port)` |
| Browser SPA target | `ssc emit-spa file.ssc > spa.html` — same `route()` source runs as a single-page app |
| Sessions + CSRF | `req.session`, `Response.html(...).withSession(Map(...))`, `csrfToken()` / `csrfValid(req)` |
| JWT bearer tokens | `jwtSign(Map(...))`, `jwtVerify(token)`, `req.bearerToken`, `req.jwtClaims` (HS256) |

## Examples

| File | Description |
|------|-------------|
| [hello.ssc](examples/hello.ssc) | Minimal "Hello, World!" |
| [index.ssc](examples/index.ssc) | Landing page for the `serve` examples browser |
| [script.ssc](examples/script.ssc) | Functions, loops, Fibonacci |
| [data-types.ssc](examples/data-types.ssc) | Case classes, sealed traits, enums, pattern matching |
| [functional.ssc](examples/functional.ssc) | Lambdas, closures, HOF, composition, pipelines |
| [enums.ssc](examples/enums.ssc) | Simple and parameterised enums, recursive ADTs |
| [extensions.ssc](examples/extensions.ssc) | Extension methods, for comprehensions, while, recursion |
| [imports.ssc](examples/imports.ssc) | Math, geometry, statistics |
| [typeclass.ssc](examples/typeclass.ssc) | Show, Eq, Ord, Monoid, Functor via `given`/`summon` |
| [typed-data.ssc](examples/typed-data.ssc) | Data pipelines, Option, enums |
| [content.ssc](examples/content.ssc) | `md` interpolator, auto-output, `doc`/`render` |
| [recursion.ssc](examples/recursion.ssc) | Self-TCO, mutual TCO, Collatz — deep recursion without overflow |
| [effects.ssc](examples/effects.ssc) | Algebraic effects — Console routing, nondeterminism, early return |
| [default-params.ssc](examples/default-params.ssc) | Default parameter values on defs, classes, and enum cases |
| [lang-split.ssc](examples/lang-split.ssc) | `scala` vs `scalascript` block annotations side by side |
| [scala-js-demo.ssc](examples/scala-js-demo.ssc) | Pure `scala` 3 document — runs on all three backends with byte-identical output |
| [rest-api.ssc](examples/rest-api.ssc) | Tiny in-memory REST API — `route()`, `html"..."`, `serve()` |
| [spa-demo.ssc](examples/spa-demo.ssc) | Same `route()` / `serve()` source, browser SPA via `ssc emit-spa` |
| [auth-demo.ssc](examples/auth-demo.ssc) | Login / logout with signed cookie sessions + CSRF tokens |

Run them all at once:

```bash
./examples/run-all.sc
```

## Benchmarks

Cross-backend micro-benchmarks (fib, tail-recursive sum, list ops) comparing
ScalaScript's three backends against hand-written Scala 3 and JavaScript:

```bash
scala-cli bench/run.sc
```

See [`bench/README.md`](bench/README.md) for the workload list, methodology,
and a sample results table.

## End-to-end smoke

`e2e/rest-smoke.sc` boots `examples/rest-api.ssc` through each of the three
backends in turn and diffs their HTTP responses — guards against drift between
the interpreter / JVM / JS serve runtimes.

```bash
scala-cli e2e/rest-smoke.sc
```

## Conformance Suite

Cross-backend tests that verify JVM interpreter and JS transpiler produce identical output.
Run with:

```bash
scala-cli conformance/run.sc
```

| Test | What it covers |
|------|----------------|
| arithmetic | Integer and floating-point ops, `math.*` |
| strings | String methods and interpolation |
| collections | `List` — map, filter, fold, take, drop, … |
| option | `Option` — `map`, `getOrElse`, `filter`, … |
| pattern-matching | Literals, guards, `Option`, tuple patterns |
| case-classes | Case class construction, field access, pattern matching |
| for-comprehensions | `yield`, guards, nested generators, `do` |
| higher-order-functions | Lambdas, `compose`, `flatMap`, eta-expansion |
| recursion | Factorial and Fibonacci |
| tail-recursion | Self-TCO at depth 100 000 — `sum`, `countdown` |
| mutual-recursion | Mutual TCO — `isEven`/`isOdd` at depth 100 000 |
| sealed-traits | ADT hierarchy with `sealed trait` + `case class` |
| variables | `var` mutation and `while` loops |
| tuples | Tuple construction, `_1`/`_2`/`_3`, destructuring |
| maps | `Map` — `size`, `getOrElse`, `contains`, `keys`, `values` |
| list-companion | `List.fill`, `List.tabulate`, `List.range` |
| modules | `[name](./path.ssc)` imports — bind definitions from another file |
| effects | Algebraic effects: Console routing, Choose nondeterminism, Fail early-return |

## Backends

ScalaScript supports three execution backends:

| Command | Backend | How it works |
|---------|---------|--------------|
| `bin/ssc file.ssc` | Interpreter | Tree-walking interpreter — instant startup, no compilation |
| `bin/jssc file.ssc` | JavaScript | `scalascript` blocks → custom JS transpiler; `scala` blocks → **Scala.js** via scala-cli |
| `bin/sscc file.ssc` | JVM / Scala 3 | Generates a `.sc` script and compiles via scala-cli |

The JavaScript backend handles two block types differently:

- **`scalascript` blocks** — transpiled by our custom JS transpiler (`JsGen`), which supports
  ScalaScript-specific features (effects, content helpers, TCO, imports).
- **`scala` blocks** — compiled by Scala.js via `scala-cli --js`, giving full Scala 3 fidelity
  (standard library, type system, no custom runtime limitations).

When a `.ssc` file contains both, the Scala.js-compiled section runs first, followed by
the ScalaScript transpiled section.

The `ssc-js` script is a lower-level tool for the JS backend:

```bash
# Emit combined JS to stdout (Scala.js bundle + ScalaScript runtime)
bin/ssc-js examples/hello.ssc

# Transpile and run in one step (same as jssc)
bin/ssc-js --run examples/hello.ssc

# Pipe to Node.js manually
bin/ssc-js examples/hello.ssc | node
```

## Project Layout

```text
bin/
  ssc          # interpreter launcher (tree-walking, no compilation step)
  jssc         # JS runner: transpiles .ssc → JS and runs via Node.js
  sscc         # JVM runner: compiles .ssc → Scala 3 → JVM via scala-cli
  ssc-js       # JS transpiler: emit JS to stdout, or --run to execute
  http.ssc     # HTTP server for examples browser

compiler/
  src/main/scala/scalascript/
    parser/      # Markdown + YAML + Scala parser
    ast/         # AST types
    interpreter/ # Tree-walking interpreter
    codegen/     # Code generators (JsGen → JavaScript, JvmGen → Scala 3)
    cli/         # Command-line entry point
    server/      # Built-in HTTP server

conformance/     # Cross-backend conformance test suite
  expected/      # Canonical expected outputs

examples/        # Runnable .ssc files
  run-all.sc     # Runs all examples in order

build.sbt        # sbt build — use scalascript as a library
project/
  build.properties

docs/            # Architecture, spec, design docs
scripts/         # setup.sh (install scala-cli), install.sh (build binary)
```

## Installing as a Binary

```bash
# Install scala-cli first (if needed)
scripts/setup.sh

# Build and install ssc to /usr/local/bin
scripts/install.sh
```

After installation:

```bash
ssc examples/hello.ssc
jssc examples/hello.ssc
sscc examples/hello.ssc
```

## Library Usage (sbt)

ScalaScript can be used as a Scala 3 library via sbt:

```bash
sbt compiler/compile   # compile
sbt compiler/test      # run tests
sbt compiler/package   # produce a JAR
```

The public API surface:

```scala
import scalascript.parser.Parser
import scalascript.interpreter.Interpreter
import scalascript.codegen.{JsGen, JvmGen}

val module = Parser.parse(source)            // parse a .ssc file
Interpreter.run(module)                      // interpret
val js    = JsGen.generate(module)           // emit JavaScript
val scala = JvmGen.generate(module)          // emit Scala 3 script
```

## Design Principles

1. **Reuse, don't invent.** Markdown, YAML, Scala 3 — use what works.
2. **One source, many targets.** Source semantics are target-independent.
3. **Human and machine readable.** Pleasant for humans, trivially parseable for machines.
4. **No AI at runtime or compile time.** The language stands on its own.

## Documentation

- [Language Specification](SPEC.md)
- [Markdown as Syntax](docs/markdown-as-syntax.md)
- [Architecture](docs/architecture.md)
- [Target Backends](docs/targets.md)

## License

[Apache License 2.0](LICENSE)

## Author

Sergiy (Victorovych) Shcherbyna <sergey.scherbina@gmail.com>
