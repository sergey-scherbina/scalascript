package scalascript.codegen

import scalascript.backend.spi.*
import scalascript.ir
import scalascript.transform.Denormalize

/** Backend SPI adapter for the Apache Kafka Streams target (target id `"kafka-streams"`).
 *
 *  v2.1.4 — DStream-to-Kafka-Streams topology compilation.
 *
 *  Pipeline:
 *  1. Denormalize the IR module back to `ast.Module` — `KafkaStreamsGen` consumes AST.
 *  2. Generate Scala 3 + Kafka Streams source via `KafkaStreamsGen.generate(...)`.
 *  3. Persist to `/tmp/ssc-kafka-streams-<hash>.scala`.
 *  4. Shell out to `scala-cli run <tmpfile>
 *       --dep org.apache.kafka:kafka-streams_2.13:<version>
 *       --dep org.apache.kafka:kafka-streams-test-utils_2.13:<version>`.
 *  5. Return `CompileResult.Executed(stdout, stderr, exit)`. */
object KafkaStreamsBackend:
  val KafkaBrokersOption: String = "kafkaBrokers"
  val KafkaAppIdOption:   String = "kafkaAppId"
  val KafkaStateDirOption: String = "kafkaStateDir"

class KafkaStreamsBackend extends Backend:
  def id:              String                               = "kafka-streams"
  def displayName:     String                               = "Apache Kafka Streams (Scala 3 + scala-cli)"
  def spiVersion:      String                               = SpiVersion.Current
  def capabilities:    Capabilities                         = KafkaStreamsCapabilities
  def intrinsics:      Map[ir.QualifiedName, IntrinsicImpl] = Map.empty
  def acceptedSources: Set[String]                          = Set.empty

  def compile(module: ir.NormalizedModule, opts: BackendOptions): CompileResult =
    val astModule  = Denormalize(module)
    val brokers    = opts.extra.getOrElse(KafkaStreamsBackend.KafkaBrokersOption, "localhost:9092")
    val appId      = opts.extra.getOrElse(KafkaStreamsBackend.KafkaAppIdOption, KafkaStreamsGen.DefaultAppId)
    val stateDir   = opts.extra.getOrElse(KafkaStreamsBackend.KafkaStateDirOption, KafkaStreamsGen.DefaultStateDir)
    val baseDir    = opts.baseDir.map(p => os.Path(p.toAbsolutePath.toString))
    val code       = KafkaStreamsGen.generate(astModule, baseDir = baseDir,
                       brokers = brokers, appId = appId, stateDir = stateDir)
    val hash       = java.lang.Integer.toHexString(code.hashCode & 0x7fffffff)
    val tmpFile    = os.Path(s"/tmp/ssc-kafka-streams-$hash.scala")
    os.write.over(tmpFile, code)
    System.err.println(s"[kafka-streams] appId=$appId brokers=$brokers — generated: $tmpFile")
    val cmd = List("scala-cli", "run", tmpFile.toString)
    val exit = scala.sys.process.Process(cmd).!
    CompileResult.Executed(stdout = "", stderr = "", exit = exit)
