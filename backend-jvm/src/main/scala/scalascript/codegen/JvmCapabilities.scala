package scalascript.codegen

import scalascript.backend.spi.*

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
