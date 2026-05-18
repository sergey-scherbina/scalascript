package scalascript.codegen

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** Capabilities declared by the JvmGen backend (target id `"jvm"`).
 *
 *  Stage 4: standalone `val` exposed for `CapabilityCheck` to consult.
 *  Stage 5: lifted into `JvmBackend.capabilities` when the backend
 *  trait gets implemented. */
val JvmCapabilities: Capabilities = Capabilities(
  features = Set(
    Feature.AlgebraicEffects,
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
    // Platform — JvmGen runtime wraps the JDK's com.sun.net.httpserver,
    // password / JWT primitives, and the standard FileSystem APIs.
    Feature.ConsoleIO,
    Feature.HttpServer,
    Feature.WebSockets,
    Feature.Auth,
    Feature.FileSystem,
    Feature.Crypto
  ),
  outputs  = Set(OutputKind.ScalaSource),
  options  = Set("optimizationLevel", "emitAssertions"),
  spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current)
)

/** Stage 5+/A.3 — `RuntimeCall` intrinsics surfaced as `def`
 *  aliases prepended to JvmGen's output.  Each entry maps a
 *  qualified name (the call site in user .ssc) to a target Scala
 *  symbol the alias forwards to. */
val JvmIntrinsics: Map[QualifiedName, IntrinsicImpl] =
  Map(
    QualifiedName("nowMillis") -> RuntimeCall("java.lang.System.currentTimeMillis")
  ) ++ JvmHttpIntrinsics  // Stage 5+/B — HTTP: intrinsics/Http.scala
