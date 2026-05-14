# ScalaScript

A language where **Markdown is syntax, not decoration** — `.ssc` files are
executable documents combining YAML front-matter, Markdown prose, and
Scala 3 code blocks.

````ssc
---
name: hello
version: 1.0.0
---

# Hello World

```scala
def greet(name: String): String = s"Hello, $name!"
println(greet("World"))
```
````

```text
Hello, World!
```

## Quick Start

**Requirements:** [scala-cli](https://scala-cli.virtuslab.org) · [Node.js](https://nodejs.org) (for JS backend)

```bash
git clone https://github.com/sergey-scherbina/scalascript
cd scalascript

# Interpreter (tree-walking, no compilation step)
bin/ssc examples/hello.ssc

# Transpile to JavaScript and run via Node.js
bin/jssc examples/hello.ssc

# Compile to JVM bytecode and run via Scala 3 / scala-cli
bin/sscc examples/hello.ssc

# Run all examples
./examples/run-all.sc

# Start HTTP server for the examples browser
./bin/http.ssc
```

## What Works

All three backends (JVM interpreter, JS transpiler, Scala 3 compiler) support:

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
| Content helpers | `doc(...)` / `render(...)` structured output |

JVM interpreter only: `serve` (requires a running interpreter at request time).

## Examples

| File | Description |
|------|-------------|
| [hello.ssc](examples/hello.ssc) | Minimal "Hello, World!" |
| [script.ssc](examples/script.ssc) | Functions, loops, Fibonacci |
| [data-types.ssc](examples/data-types.ssc) | Case classes, sealed traits, enums, pattern matching |
| [functional.ssc](examples/functional.ssc) | Lambdas, closures, HOF, composition, pipelines |
| [enums.ssc](examples/enums.ssc) | Simple and parameterised enums, recursive ADTs |
| [extensions.ssc](examples/extensions.ssc) | Extension methods, for comprehensions, while, recursion |
| [imports.ssc](examples/imports.ssc) | Math, geometry, statistics |
| [typeclass.ssc](examples/typeclass.ssc) | Show, Eq, Ord, Monoid, Functor via `given`/`summon` |
| [typed-data.ssc](examples/typed-data.ssc) | Data pipelines, Option, enums |
| [content.ssc](examples/content.ssc) | `md` interpolator, auto-output, `doc`/`render` |

Run them all at once:

```bash
./examples/run-all.sc
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
| sealed-traits | ADT hierarchy with `sealed trait` + `case class` |
| variables | `var` mutation and `while` loops |
| tuples | Tuple construction, `_1`/`_2`/`_3`, destructuring |
| maps | `Map` — `size`, `getOrElse`, `contains`, `keys`, `values` |

## Backends

ScalaScript supports three execution backends:

| Command | Backend | How it works |
|---------|---------|--------------|
| `bin/ssc file.ssc` | Interpreter | Tree-walking interpreter — instant startup, no compilation |
| `bin/jssc file.ssc` | JavaScript | Transpiles `.ssc` → JS, runs via Node.js |
| `bin/sscc file.ssc` | JVM / Scala 3 | Generates a `.sc` script and compiles via scala-cli |

The `ssc-js` script is a lower-level tool for the JS backend:

```bash
# Print generated JavaScript to stdout (useful for debugging)
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
