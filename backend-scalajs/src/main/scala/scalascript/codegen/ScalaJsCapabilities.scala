package scalascript.codegen

import scalascript.backend.spi.*

/** Capabilities declared by the ScalaJsBackend (target id `"scalajs-spa"`).
 *
 *  Stage 4: standalone `val` exposed for `CapabilityCheck` to consult.
 *  Stage 5: lifted into `ScalaJsBackend.capabilities`. */
val ScalaJsCapabilities: Capabilities = Capabilities(
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
    // Platform — SPA target.  Browser DOM only; no HTTP server / WS server
    // (the browser is the client).  Crypto via SubtleCrypto; FileSystem
    // limited to download/upload via input elements (not declared here).
    Feature.ConsoleIO,
    Feature.Crypto
  ),
  outputs  = Set(OutputKind.JavaScriptSource, OutputKind.HtmlSource),
  options  = Set("optimizationLevel", "emitSourceMaps"),
  spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current)
)
