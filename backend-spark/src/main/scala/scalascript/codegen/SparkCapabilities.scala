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
  options        = Set("sparkVersion", "sparkMaster"),
  spiRange       = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  blockLanguages = Set.empty
)
