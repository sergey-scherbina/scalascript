package scalascript.codegen

import scalascript.backend.spi.*

/** Capabilities declared by the Apache Flink backend (target id `"flink"`).
 *
 *  v2.1.5 — DStream-to-Flink DataStream lowering.  The generated Scala 3
 *  program uses the Flink DataStream API for `map`, `filter`, `keyBy`,
 *  `window`, and `combinePerKey` operators.  For bounded `InMemory.source`
 *  inputs the shim falls back to driver-local `Seq[Any]` (same as Spark/Kafka
 *  shims); live `Kafka.source` inputs use a real Flink environment and require
 *  a running Flink cluster (gated by `FLINK_MASTER` env var). */
val FlinkCapabilities: Capabilities = Capabilities(
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
    Feature.Dataset,
    Feature.DistributedStreams
  ),
  outputs        = Set(OutputKind.ExecutionResult),
  options        = Set("flinkMaster", "flinkParallelism", "flinkCheckpointDir"),
  spiRange       = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  blockLanguages = Set.empty
)

/** Capabilities declared by the Apache Beam backend (target id `"beam"`).
 *
 *  v2.1.5 — DStream-to-Beam PCollection lowering.  The generated Scala 3
 *  program emits an Apache Beam Java SDK `Pipeline` driven by the runner
 *  specified in `PipelineOptions.extraProperties("runner")`.  Defaults to
 *  `DirectRunner` (in-process, no cluster required). */
val BeamCapabilities: Capabilities = Capabilities(
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
    Feature.Dataset,
    Feature.DistributedStreams
  ),
  outputs        = Set(OutputKind.ExecutionResult),
  options        = Set("beamRunner", "beamFlinkMaster", "beamCheckpointDir"),
  spiRange       = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  blockLanguages = Set.empty
)
