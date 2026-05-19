package scalascript.codegen

import scalascript.backend.spi.*

/** Capabilities declared by the Apache Spark backend (target id `"spark"`).
 *
 *  Phase A — SPI integration.  Feature set mirrors JvmCapabilities at a
 *  coarse level: every language feature `SparkGen` emits passes through
 *  to scala-cli, which holds the actual Scala 3 compiler.  Capabilities
 *  the JVM backend declares but Spark cannot reasonably support at
 *  cluster scale (WebSockets, HttpServer, Auth, McpServer) are *also*
 *  declared here — `SparkGen` does not refuse them at codegen time, and
 *  the resulting Spark driver program can call them just like any JVM
 *  process.  Backends that genuinely cannot execute a feature should
 *  drop it; we don't double-gate here.
 *
 *  `outputs = ExecutionResult` reflects that `compile` runs scala-cli
 *  and returns the process's stdout / stderr / exit code rather than
 *  emitting persistent text. */
val SparkCapabilities: Capabilities = Capabilities(
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
  outputs        = Set(OutputKind.ExecutionResult),
  // `sparkConfig`  carries the encoded `Map[String, String]` of
  //                `spark-config:` front-matter entries (Phase C.3 slice 3).
  // `sparkAppName` overrides `.appName(...)` on `SparkSession.builder`
  //                (Phase C.3 slice 4) so the Spark UI / history server /
  //                driver+executor logs show a human-readable per-job
  //                name instead of the default `scalascript-job`.
  // Both surfaced here so `--describe-backend spark` advertises them
  // alongside the existing version/master knobs.
  options        = Set("sparkVersion", "sparkMaster", "sparkConfig", "sparkAppName"),
  spiRange       = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  // Phase C: `sql` fenced blocks (SPEC.md § 3.3.1) compile to
  // `spark.sql(sqlText, namedParams)` on Spark targets — see SparkGen
  // for the emission logic.  The `node.js` tag is intentionally NOT
  // declared; Spark consumes `scalascript` / `scala` / `sql` only.
  blockLanguages = Set(scalascript.ast.Lang.Sql)
)
