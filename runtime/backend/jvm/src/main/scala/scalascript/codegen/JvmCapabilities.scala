package scalascript.codegen

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.codegen.jvm.*

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
    Feature.Crypto,
    Feature.McpServer,
    Feature.McpClient,
    Feature.Dataset,
    Feature.PaymentRequest
  ),
  outputs  = Set(OutputKind.ScalaSource),
  options  = Set("optimizationLevel", "emitAssertions"),
  spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  // v1.26 Phase 6 — JvmGen emits JDBC execution via
  // `scalascript.sql.SqlRuntime` for `sql` fenced blocks.
  // `transaction` blocks emit a `withTransaction` call wrapping
  // all statements in one atomic JDBC transaction.
  blockLanguages = Set(scalascript.ast.Lang.Sql, scalascript.ast.Lang.Transaction)
)

/** Stage 5+/A.3 — `RuntimeCall` intrinsics surfaced as `def`
 *  aliases prepended to JvmGen's output.  Each entry maps a
 *  qualified name (the call site in user .ssc) to a target Scala
 *  symbol the alias forwards to. */
val JvmIntrinsics: Map[QualifiedName, IntrinsicImpl] =
  Map(
    QualifiedName("nowMillis")        -> RuntimeCall("java.lang.System.currentTimeMillis"),
    // Stage 5+/B.3 — bare println/print rewritten to Console.* in Normalize.
    // RuntimeCall targets the preamble's overridden `println`/`print` that
    // route through `_show` for correct Double formatting.
    // Backward-compat bare forms (for code that bypasses Normalize).
    QualifiedName("println")         -> RuntimeCall("println"),
    QualifiedName("print")           -> RuntimeCall("print"),
    QualifiedName("Console.println") -> RuntimeCall("println"),
    QualifiedName("Console.print")   -> RuntimeCall("print")
  ) ++ JvmHttpIntrinsics     // Stage 5+/B — HTTP:     intrinsics/Http.scala
    ++ JvmWsIntrinsics       // Stage 5+/D — WS:       intrinsics/Ws.scala
    ++ JvmAuthIntrinsics     // Stage 5+/D — Auth:     intrinsics/Auth.scala
    ++ JvmCoreIntrinsics     // Stage 5+/E — Core:     intrinsics/Core.scala
    ++ JvmJsonIntrinsics     // Stage 5+/E — JSON:     intrinsics/Json.scala
    ++ JvmRequestIntrinsics  // Stage 5+/E — Request:  intrinsics/Request.scala
    ++ JvmMcpIntrinsics      // v1.17     — MCP:       intrinsics/Mcp.scala
    ++ JvmDatasetIntrinsics  // v1.21     — Dataset:   intrinsics/Dataset.scala
    ++ JvmPaymentIntrinsics  // v1.38     — Payment:   intrinsics/Payment.scala
