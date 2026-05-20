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
  outputs        = Set(OutputKind.WasmBytecode, OutputKind.JavaScriptSource),
  options        = Set("optimizationLevel", "emitSourceMaps", "target"),
  spiRange       = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  // v1.27 Phase 5 — sql blocks compile via backend-sql-runtime-js
  // (sql.js + DuckDB-Wasm).  The Wasm body itself is unaffected; the
  // JS shim that already accompanies the .wasm blob gains the sql
  // runtime + registry preamble and the bundle ships a `package.json`
  // with the right npm deps.
  blockLanguages = Set(scalascript.ast.Lang.Sql)
)
