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

```
Hello, World!
```

## Quick Start

**Requirements:** [scala-cli](https://scala-cli.virtuslab.org)

```bash
git clone https://github.com/sergey-scherbina/scalascript
cd scalascript

# Run an example
bin/ssc examples/hello.ssc

# Run all examples
./examples/run-all.sc

# Start HTTP server for the examples browser
./bin/http.ssc
```

## Language Features

| Feature | Example |
|---------|---------|
| Case classes | `case class Point(x: Double, y: Double)` |
| Sealed traits / enums | `enum Color { case Red, Green, Blue }` |
| Pattern matching | `x match { case Circle(r) => math.Pi * r * r }` |
| Typeclasses | `trait Show[A]`, `given`, `summon[Show[Int]]` |
| Extension methods | `extension (n: Int) def squared: Int = n * n` |
| For comprehensions | `for x <- xs if x > 0 yield x * x` |
| Collections | `List`, `Map`, `Option` with full method dispatch |
| Built-in math | `math.sqrt`, `math.Pi`, `math.pow`, … |
| String interpolation | `s"Hello, $name"`, `md"""…"""` |
| `md` interpolator | Strips common indentation from multi-line strings |
| Auto-output | Last non-Unit expression in a block is printed automatically |
| `doc` / `render` | Structured document builder for rich output |

## Examples

| File | Description |
|------|-------------|
| [hello.ssc](examples/hello.ssc) | Minimal example |
| [script.ssc](examples/script.ssc) | Functions, loops, Fibonacci |
| [data-types.ssc](examples/data-types.ssc) | Case classes, sealed traits, enums, pattern matching |
| [extensions.ssc](examples/extensions.ssc) | Extension methods, for comprehensions, while, recursion |
| [imports.ssc](examples/imports.ssc) | Math, geometry, statistics |
| [typeclass.ssc](examples/typeclass.ssc) | Show, Eq, Ord, Monoid, Functor via `given`/`summon` |
| [typed-data.ssc](examples/typed-data.ssc) | Data pipelines, Option, enums |
| [content.ssc](examples/content.ssc) | `md` interpolator, auto-output, `doc`/`render` |

Run them all at once:

```bash
./examples/run-all.sc
```

## Project Layout

```
bin/
  ssc          # interpreter launcher (scala-cli dev mode)
  http.ssc     # HTTP server for examples browser

compiler/
  src/main/scala/scalascript/
    parser/      # Markdown + YAML + Scala parser
    ast/         # AST types
    interpreter/ # Tree-walking interpreter
    cli/         # Command-line entry point
    server/      # Built-in HTTP server

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
