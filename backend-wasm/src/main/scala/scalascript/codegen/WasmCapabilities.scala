package scalascript.codegen

import scalascript.backend.spi.*

val WasmCapabilities: Capabilities = Capabilities(
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
    Feature.ConsoleIO,
    Feature.Crypto,
  ),
  outputs  = Set(OutputKind.WasmBytecode, OutputKind.JavaScriptSource),
  options  = Set("optimizationLevel", "emitSourceMaps", "target"),
  spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current)
)
