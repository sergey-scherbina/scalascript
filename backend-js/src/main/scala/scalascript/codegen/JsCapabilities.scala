package scalascript.codegen

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

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
    Feature.Crypto,
    Feature.McpServer,
    Feature.McpClient,
    Feature.Dataset
  ),
  outputs  = Set(OutputKind.JavaScriptSource),
  options  = Set("optimizationLevel", "emitAssertions", "target"),
  spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current)
)

/** Stage 5+/A.3 — `RuntimeCall` intrinsics surfaced as JS `const`
 *  aliases prepended to JsGen's output. */
val JsIntrinsics: Map[QualifiedName, IntrinsicImpl] =
  Map(
    QualifiedName("nowMillis")        -> RuntimeCall("Date.now"),
    // Stage 5+/B.3 — bare println/print rewritten to Console.* in Normalize.
    // `_println` / `_print` do their own `_show` internally so args are not
    // double-escaped.
    // Backward-compat: code that bypasses Normalize still uses bare `println`.
    QualifiedName("println")         -> RuntimeCall("_println"),
    QualifiedName("print")           -> RuntimeCall("_print"),
    // Canonical form after Normalize rewrites bare println → Console.println.
    QualifiedName("Console.println") -> RuntimeCall("_println"),
    QualifiedName("Console.print")   -> RuntimeCall("_print")
  ) ++ JsHttpIntrinsics     // Stage 5+/B — HTTP:     intrinsics/Http.scala
    ++ JsWsIntrinsics       // Stage 5+/D — WS:       intrinsics/Ws.scala
    ++ JsAuthIntrinsics     // Stage 5+/D — Auth:     intrinsics/Auth.scala
    ++ JsCoreIntrinsics     // Stage 5+/E — Core:     intrinsics/Core.scala
    ++ JsJsonIntrinsics     // Stage 5+/E — JSON:     intrinsics/Json.scala
    ++ JsRequestIntrinsics  // Stage 5+/E — Request:  intrinsics/Request.scala
    ++ JsMcpIntrinsics      // v1.17     — MCP:       intrinsics/Mcp.scala
    ++ JsDatasetIntrinsics  // v1.21     — Dataset:   intrinsics/Dataset.scala
