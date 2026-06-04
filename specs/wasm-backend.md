# WebAssembly Backend

The `wasm` backend compiles `scala` and `scalascript` fenced blocks to a
`.wasm` binary + two JavaScript ES-module files via
`scala-cli --power package --js --js-module-kind es --js-emit-wasm`.

## Supported block types

| Block lang     | Compiled to WASM? | Notes |
|----------------|:-----------------:|-------|
| `scala`        | ✅ Yes            | Standard Scala 3; no restrictions |
| `scalascript`  | ✅ Yes            | Treated as Scala 3; ScalaScript extensions (effects, handlers) not yet transpiled |
| `ssc`          | ✅ Yes            | Alias for `scalascript` |
| `sql`          | Via JS shim       | sql.js / DuckDB-Wasm runtime embedded alongside `.wasm` |
| `javascript`   | ❌ No             | Use `node.js` or inline JS interop |
| `html`/`css`   | ❌ No             | Frontend blocks, not applicable |

## Output artefacts

`ssc emit-wasm file.ssc` writes:

| File             | Description |
|------------------|-------------|
| `module.wasm`    | WebAssembly binary |
| `file.js`        | ES-module entry point — imports `__loader.js` + `module.wasm` |
| `__loader.js`    | Scala.js WASM loader / runtime glue |

For modules with `sql` blocks, additional shim assets are also emitted:
`sql-runtime.mjs`, `sql-registry.mjs`, `package.json`.

## Entry point requirement

Because the output is a `.scala` (not `.sc`) file, the compilation unit
needs a proper entry point:

```scalascript
@main def run(): Unit =
  println("Hello from WebAssembly!")
```

Top-level statements without `@main` are not executable in `.wasm` output.
Top-level definitions (case classes, defs, vals) are fine alongside `@main`.

## Additional Scala.js dependencies

Use `//> using dep` directives inside any compilable block.  The WASM
backend hoists them to the top of the compilation unit before invoking
scala-cli:

```scalascript
//> using dep "org.scala-js::scalajs-dom::2.8.0"

import org.scalajs.dom
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js.Thenable.Implicits.*

def fetch(url: String) =
  dom.fetch(url).toFuture.flatMap(_.text().toFuture)
```

Duplicate directives across multiple blocks are deduplicated automatically.

## HTTP / Fetch API

Browser Fetch API is available via `scalajs-dom`:

```scalascript
//> using dep "org.scala-js::scalajs-dom::2.8.0"

import org.scalajs.dom
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js.Thenable.Implicits.*

@main def run(): Unit =
  val url = "https://api.example.com/data"
  dom.fetch(url).toFuture
    .flatMap(_.text().toFuture)
    .foreach(println)
```

Requires a browser context or Node.js 18+ (global `fetch` available).
See `examples/wasm-http.ssc` for the complete example.

## Mixing scala and scalascript blocks

Both block types are combined into a single compilation unit.  Definitions
from either block are visible to code in the other:

````markdown
```scala
def greet(name: String): String = s"Hello, $name!"
```

```scalascript
@main def run(): Unit =
  println(greet("WASM"))
```
````

## CLI usage

```bash
# Compile to WASM and write artefacts to current directory
ssc emit-wasm examples/wasm-fibonacci.ssc

# Load in Node.js (requires Node 18+)
node --input-type=module -e "import './wasm-fibonacci.js'"
```

## Limitations (Phase 1–3)

- ScalaScript language extensions (algebraic effects, `perform`/`handle`,
  effect rows) are **not** transpiled.  Only code that is valid standard
  Scala 3 can be compiled to WASM today.
- JVM-specific APIs (`java.net.*`, `Thread`, JDBC) will cause compilation
  failures — use Scala.js / browser APIs instead.
- `@main` entry point is required for executable bundles.

## Examples

| File | Description |
|------|-------------|
| `examples/wasm-fibonacci.ssc` | Fibonacci via `scala` blocks |
| `examples/wasm-collections.ssc` | Standard collections via `scala` blocks |
| `examples/wasm-matrix.ssc` | Matrix multiply/transpose |
| `examples/wasm-sorting.ssc` | Sorting algorithms |
| `examples/wasm-primes.ssc` | Prime sieve |
| `examples/wasm-scalascript.ssc` | Geometry algorithms via `scalascript` blocks |
| `examples/wasm-http.ssc` | HTTP Fetch API via scalajs-dom |
