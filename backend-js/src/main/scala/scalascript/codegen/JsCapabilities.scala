package scalascript.codegen

import scalascript.backend.spi.*

/** Capabilities declared by the JsGen backend (target id `"js"`).
 *
 *  Stage 4: standalone `val` exposed for `CapabilityCheck` to consult.
 *  Stage 5: lifted into `JsBackend.capabilities`. */
val JsCapabilities: Capabilities = Capabilities(
  features = Set(
    Feature.AlgebraicEffects,
    Feature.MutableState,
    Feature.PatternMatching,
    Feature.TypeClasses,
    Feature.ExtensionMethods,
    Feature.DefaultParameters,
    Feature.ForComprehensions,
    Feature.WhileLoops,
    // TCO on JS is trampoline-based (not native): semantics preserved, so
    // declare support.  A future "native TCO required" Feature could
    // distinguish.
    Feature.TailCallOptimization,
    Feature.StringInterpolators,
    Feature.ModuleImports,
    // Platform — JsGen runtime targets Node.js: console.log, http server,
    // WebSocket via 'ws' module, password hashing, JWT, fs, crypto.
    Feature.ConsoleIO,
    Feature.HttpServer,
    Feature.WebSockets,
    Feature.Auth,
    Feature.FileSystem,
    Feature.Crypto
  ),
  outputs  = Set(OutputKind.JavaScriptSource),
  options  = Set("optimizationLevel", "emitAssertions", "target"),
  spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current)
)
