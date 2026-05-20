# Writing a ScalaScript Backend

A walk-through for third-party backend authors.  Two shapes:

  - **In-process JAR** — JVM-only.  ~30 lines of Scala + one
    `META-INF/services` entry.  Distributed as a JAR, attached via
    `ssc --plugin <jar>`.  This document.
  - **Out-of-process subprocess** — any language that can read JSON
    on stdin.  See [`backend-spi-protocol.md`](backend-spi-protocol.md)
    for the wire spec and `examples/plugins/canned-backend/` for a
    50-line bash worked example.

By the end of this doc you'll have a no-op backend that:

  1. Registers with `BackendRegistry.lookup("hello")`.
  2. Returns `CompileResult.TextOutput("hello, world", "text", Nil)`
     from every `compile()` call.
  3. Loads via `ssc --plugin path/to/your-backend.jar`.

## 1. Declare the Backend implementation

Create one Scala file.  The full SPI surface is in
[`docs/backend-spi.md`](backend-spi.md) §4.2:

```scala
package mybackend

import scalascript.backend.spi.*
import scalascript.ir

class HelloBackend extends Backend:
  def id:              String = "hello"
  def displayName:     String = "Hello, World"
  def spiVersion:      String = SpiVersion.Current
  def capabilities:    Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set(OutputKind.ExecutionResult),
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current)
  )
  def intrinsics:      Map[ir.QualifiedName, IntrinsicImpl] = Map.empty
  def acceptedSources: Set[String] = Set.empty

  def compile(module: ir.NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.TextOutput(
      code     = "hello, world",
      language = "text",
      sources  = Nil
    )
```

That's the whole backend.  The 7 abstract members + `compile` are the
entire contract.

### What each member is for

| Member          | Purpose |
|-----------------|---------|
| `id`            | The string `BackendRegistry.lookup(id)` and `--target <id>` use. |
| `displayName`   | Human-friendly label, shown by `--list-backends`. |
| `spiVersion`    | SPI version this backend was built against.  Use `SpiVersion.Current`. |
| `capabilities`  | What language features and output kinds you handle.  Core's `CapabilityCheck` walks the IR and refuses to call `compile` on programs that need features you don't declare. |
| `intrinsics`    | Map from `extern def` qualified names to `IntrinsicImpl` (inline-code emit, runtime call, or host callback). Empty until you implement platform packages. |
| `acceptedSources` | Names of `SourceLanguage` plugins whose blocks you can embed (e.g. `"scala"`, `"html"`).  Empty if you only handle the host `scalascript` blocks. |
| `compile`       | The work.  Receives normalized IR + options, returns a `CompileResult` variant. |

## 2. Register via ServiceLoader

Create a single resource file:

```text
src/main/resources/META-INF/services/scalascript.backend.spi.Backend
```

with one line — the fully-qualified name of your `Backend` class:

```text
mybackend.HelloBackend
```

`java.util.ServiceLoader` reads this automatically when the JAR is
on the classpath.  No central registration is needed.

## 3. Build a JAR

scala-cli works fine:

```bash
scala-cli --power package . --output hello-backend.jar
```

Or use sbt, mill, gradle, maven — any build tool that produces a
standard JAR with the META-INF entry preserved.

You'll need this dependency in scope at compile time:

```scala
//> using dep io.scalascript:scalascript-backend-spi:0.1.0-SNAPSHOT
```

(Substitute the version published by your scalascript checkout.
The artifact is small — types only, no transitive dependencies on
core or any specific backend.)

## 4. Load and use

```bash
# Verify the JAR is discoverable
ssc --plugin hello-backend.jar --list-backends
# Expected:
#   ...
#   hello          Hello, World  [spi=0.1.0, in-process]

# Use it
ssc --plugin hello-backend.jar --target hello compile examples/hello.ssc
# (or --backend hello for run-style commands)
```

If your backend implements `InteractiveBackend`, `ssc serve` will
see it too — pass `--backend hello` to make `serve` use yours
instead of the default interpreter.

## 5. Going beyond no-op

When you're ready for real work:

  - **Read the IR** via `module.sections` and `module.manifest`.
    `Content.CodeBlock(source, body, span)` holds each scalascript
    block; `body` is the lowered IR (populated once Stage 5+/A lands —
    until then re-parse `source` if you need scalameta).
    `Content.EmbeddedBlock(language, source, span)` is for `html` /
    `css` / future plugin-owned dialects.
  - **Capabilities are mandatory.**  Add every `Feature` enum value
    your backend genuinely supports.  `CapabilityCheck` runs before
    `compile` and short-circuits with `Diagnostic.Unsupported` if
    you under-declare.
  - **Failure shape.**  Return
    `CompileResult.Failed(List(Diagnostic.Generic(msg, source)))`
    rather than throwing — core's CLI / serve mode renders the list.
  - **Diagnostics enums.**  `Diagnostic.Unsupported(feature, backendId)`,
    `Diagnostic.UnknownIntrinsic(name, backendId)`,
    `Diagnostic.UnknownBlockLanguage(language)`, and
    `Diagnostic.Generic(message, source)` cover every diagnostic core
    knows how to render.

## 6. Idiomatic patterns

  - **Lazy initialisation** — `BackendRegistry` calls your no-arg
    constructor lazily on first lookup.  Don't do expensive work in
    the constructor; do it in `compile` (or memoise per session for
    `InteractiveBackend`).
  - **Reuse `Denormalize`** if you want to delegate to existing
    AST-consuming code: `scalascript.transform.Denormalize(ir)`
    returns an `ast.Module` with the scalameta trees re-parsed.
    The four bundled backends use this shim today; Stage 5+/A
    replaces it with IR-native traversal.
  - **`acceptedSources` is the negotiation point** with
    `SourceLanguage` plugins.  If you declare `"html"` you must be
    able to embed the IR fragments the `html` plugin emits.

## 7. Worked example

`examples/plugins/hello-backend/` ships with this repo as a
buildable scala-cli project — the source above plus the
META-INF/services entry, a `project.scala` with the dep, and a
README that walks through the build / install / invoke loop.

## 8. Out-of-process variant

If you want to write your backend in Rust, Go, Python — anything
that can read newline-delimited JSON — implement the wire protocol
from [`backend-spi-protocol.md`](backend-spi-protocol.md) and ship
a `plugin.yaml`.  Discovery picks it up from
`$SCALASCRIPT_PLUGIN_PATH` or `~/.scalascript/compiler/plugins/`.
`examples/plugins/canned-backend/` is a 50-line bash worked example.
