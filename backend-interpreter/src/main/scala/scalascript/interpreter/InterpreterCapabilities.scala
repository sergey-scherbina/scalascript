package scalascript.interpreter

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

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
    Feature.Crypto
  ),
  outputs  = Set(OutputKind.ExecutionResult),
  options  = Set("emitAssertions"),
  spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current)
)

/** Intrinsics the interpreter routes through `Backend.intrinsics`
 *  instead of hardcoding in `Interpreter.initBuiltins`.  Each
 *  `NativeImpl` is registered as a global before the user module
 *  runs, exactly as if it were a built-in.  `NativeContext`
 *  receives the per-session `out` / `err` so I/O honours the
 *  interpreter's wrapping (null-stream during `ssc render`, etc.). */
val InterpreterIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(
  // Stage 5+/A.2 — additive demo (no existing hardcoded path).
  QualifiedName("nowMillis") -> NativeImpl((_, _) =>
    java.lang.System.currentTimeMillis()
  ),
  // Stage 5+/B — migrating existing Console builtins.  Hardcoded
  // `nativeP("println")` / `nativeP("print")` removed from
  // Interpreter.initBuiltins; this is now the single source.
  QualifiedName("println") -> NativeImpl((ctx, args) =>
    ctx.out.println(args.map(formatArg).mkString(" "))
  ),
  QualifiedName("print") -> NativeImpl((ctx, args) =>
    ctx.out.print(args.map(formatArg).mkString(" "))
  ),
  // Stage 5+/B — HTTP server intrinsics migrated from nativeP.
  // route(method, path) — curried; returns a NativeFnV that accepts the handler.
  // wrapAnyAsValue passes Value through unchanged, so the returned NativeFnV
  // is callable directly by the interpreter's dispatch.
  QualifiedName("route") -> NativeImpl((ctx, args) =>
    args match
      case List(method: String, path: String) =>
        Value.NativeFnV("route.handler", Computation.pureFn {
          case List(handler) =>
            ctx.registerRoute(method, path, handler)
            Value.UnitV
          case _ => throw InterpretError("route(method, path) { handler }")
        })
      case _ => throw InterpretError("route(method, path) { handler }")
  ),
  // serve(port) / serve(port, dir) — starts the HTTP server.
  // Delegates to ctx.registerHealthDefaults() and WebServer.start via ctx.
  QualifiedName("serve") -> NativeImpl((ctx, args) =>
    args match
      case List(port: Long) =>
        ctx.registerHealthDefaults()
        if !ctx.headless then scalascript.server.WebServer.start(port.toInt, ".", ctx.out)
        ()
      case List(port: Long, dir: String) =>
        ctx.registerHealthDefaults()
        if !ctx.headless then scalascript.server.WebServer.start(port.toInt, dir, ctx.out)
        ()
      case _ => throw InterpretError("serve(port) or serve(port, dir)")
  ),
  // stop() — no-op stub; real shutdown deferred to feature/v1.5-tls-http-client.
  QualifiedName("stop") -> NativeImpl((_, _) => ())
)

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
  case other     => other.toString
