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

/** Stage 5+/A.2 proof point — every intrinsic the interpreter routes
 *  through `Backend.intrinsics` instead of hardcoding in
 *  `Interpreter.initBuiltins`.  Each `NativeImpl` is registered as a
 *  global before the user module runs, exactly as if it were a
 *  built-in.
 *
 *  Initially: just `nowMillis` — a brand-new builtin that doesn't
 *  exist as a hardcoded function, so registering it via the intrinsic
 *  table is purely additive (no risk of regressing existing
 *  programs).  5+/B migrates existing builtins (`println` / `print`)
 *  through this same table. */
val InterpreterIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(
  QualifiedName("nowMillis") -> NativeImpl(_ => java.lang.System.currentTimeMillis())
)
