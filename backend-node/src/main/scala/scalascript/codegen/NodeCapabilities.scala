package scalascript.codegen

import scalascript.backend.spi.*

/** Capabilities declared by the Node.js backend (target id `"node"`).
 *
 *  Feature set mirrors `JsCapabilities` — same JsGen produces the
 *  `scalascript` / `scala` body, so the same surface compiles.  The
 *  one difference that makes this a separate target is
 *  `blockLanguages = Set("node.js", "node")`, which declares that
 *  ```node.js``` (alias ```node```) opaque-executable fenced blocks
 *  are linked verbatim into the emitted `.mjs` bundle. */
val NodeCapabilities: Capabilities = Capabilities(
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
    Feature.HttpServer,
    Feature.WebSockets,
    Feature.Auth,
    Feature.FileSystem,
    Feature.Crypto,
    Feature.McpServer,
    Feature.McpClient,
    Feature.Dataset
  ),
  outputs        = Set(OutputKind.JavaScriptSource),
  options        = Set("optimizationLevel", "emitAssertions"),
  spiRange       = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  blockLanguages = Set(scalascript.ast.Lang.Node, scalascript.ast.Lang.NodeShort)
)
