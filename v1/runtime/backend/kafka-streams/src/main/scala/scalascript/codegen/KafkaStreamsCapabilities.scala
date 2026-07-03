package scalascript.codegen

import scalascript.backend.spi.*

/** Capabilities declared by the Kafka Streams backend (target id `"kafka-streams"`).
 *
 *  v2.1.4 — DStream-to-Kafka-Streams topology compilation.  The generated
 *  Scala 3 program builds a `StreamsBuilder` topology and runs it via
 *  `TopologyTestDriver` for bounded `InMemory.source` inputs (no broker
 *  required).  Unbounded `Kafka.source` sources require a live broker (gated
 *  by `KAFKA_BROKERS` env var in integration tests).
 *
 *  Capabilities align with the Kafka Streams engine: full event time, keyed
 *  state, exactly-once (via `processing.guarantee = exactly_once_v2`), and
 *  windowed joins. */
val KafkaStreamsCapabilities: Capabilities = Capabilities(
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
  options        = Set("kafkaBrokers", "kafkaAppId", "kafkaStateDir"),
  spiRange       = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  blockLanguages = Set.empty
)
