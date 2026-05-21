package scalascript.interpreter

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.interpreter.Value

/** Capabilities declared by the tree-walking Interpreter (target id `"int"`).
 *
 *  Stage 4: standalone `val` exposed for `CapabilityCheck` to consult.
 *  Stage 5: lifted into `InterpreterBackend.capabilities` when the
 *  backend trait gets implemented. */
val InterpreterCapabilities: Capabilities = Capabilities(
  features = Set(
    Feature.AlgebraicEffects,    // native Computation Free monad
    Feature.MutableState,
    Feature.PatternMatching,
    Feature.TypeClasses,
    Feature.ExtensionMethods,
    Feature.DefaultParameters,
    Feature.ForComprehensions,
    Feature.WhileLoops,
    Feature.TailCallOptimization,
    Feature.StringInterpolators,
    Feature.ModuleImports,
    // Platform — same `server/*.scala` wrappers as the JvmGen runtime.
    Feature.ConsoleIO,
    Feature.HttpServer,
    Feature.WebSockets,
    Feature.Auth,
    Feature.FileSystem,
    Feature.Crypto,
    Feature.McpServer,           // v1.17 — own-impl: stdio (HTTP+SSE Phase 2)
    Feature.McpClient,           // v1.17 — own-impl: spawn (HTTP+SSE Phase 2)
    Feature.Dataset
  ),
  outputs  = Set(OutputKind.ExecutionResult),
  options  = Set("emitAssertions"),
  spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  // v1.26 Phase 6 — Interpreter runs `sql` fenced blocks against a
  // `java.sql.Connection` resolved from the surrounding
  // `given Connection` scope, falling back to the
  // ConnectionRegistry built from front-matter `databases:`.
  // `transaction` blocks execute all statements atomically via
  // `ConnectionRegistry.withTransaction`.
  blockLanguages = Set(scalascript.ast.Lang.Sql, scalascript.ast.Lang.Transaction)
)

/** Intrinsics the interpreter routes through `Backend.intrinsics`
 *  instead of hardcoding in `Interpreter.initBuiltins`.  Each
 *  `NativeImpl` is registered as a global before the user module
 *  runs, exactly as if it were a built-in.  `NativeContext`
 *  receives the per-session `out` / `err` so I/O honours the
 *  interpreter's wrapping (null-stream during `ssc render`, etc.). */
val InterpreterIntrinsics: Map[QualifiedName, IntrinsicImpl] =
  Map(
    // Stage 5+/A.2 — additive demo (no existing hardcoded path).
    QualifiedName("nowMillis") -> NativeImpl((_, _) =>
      java.lang.System.currentTimeMillis()
    ),
    // Stage 5+/B.3 — bare `println` / `print` rewritten to
    // `Console.println` / `Console.print` in Normalize; the interpreter
    // installs the NativeFn under `Console.println` and assembles a
    // `globals("Console")` companion object in initBuiltins below.
    QualifiedName("Console.println") -> NativeImpl((ctx, args) =>
      ctx.out.println(args.map(formatArg).mkString(" "))
    ),
    QualifiedName("Console.print") -> NativeImpl((ctx, args) =>
      ctx.out.print(args.map(formatArg).mkString(" "))
    )
  ) ++ CoreIntrinsics     // Stage 5+/E — Core:     intrinsics/Core.scala
    // HTTP / WS / MCP / JSON / Request / Frontend / Auth / OAuth / JDBC / UI now ship as plugins.

/** Same shape as `Value.show` but works on the `Any` payload an
 *  intrinsic sees post-unwrap.  Critical: doubles render without the
 *  trailing `.0` when they're integer-valued, matching the original
 *  `Value.show` formatting (otherwise `println(2 * 2.0)` prints
 *  `"4.0"` instead of `"4"` and conformance / unit tests regress). */
private def formatArg(a: Any): String = a match
  case d: Double if d == d.toLong.toDouble && !d.isInfinite => d.toLong.toString
  case d: Double => d.toString
  case s: String => s
  case ()        => "()"
  case v: Value  => Value.show(v)
  case other     => other.toString
