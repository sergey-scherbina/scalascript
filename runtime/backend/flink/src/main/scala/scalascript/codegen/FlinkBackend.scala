package scalascript.codegen

import scalascript.backend.spi.*
import scalascript.ir
import scalascript.transform.Denormalize

/** Backend SPI adapter for the Apache Flink target (target id `"flink"`).
 *
 *  v2.1.5 — DStream-to-Flink DataStream lowering.
 *
 *  Pipeline:
 *  1. Denormalize the IR module back to `ast.Module` — `FlinkGen` consumes AST.
 *  2. Generate Scala 3 + Flink source via `FlinkGen.generate(...)`.
 *  3. Persist to `/tmp/ssc-flink-<hash>.scala`.
 *  4. Shell out to `scala-cli run <tmpfile>`.
 *  5. Return `CompileResult.Executed(stdout, stderr, exit)`. */
object FlinkBackend:
  val FlinkMasterOption:      String = "flinkMaster"
  val FlinkParallelismOption: String = "flinkParallelism"
  val FlinkCheckpointOption:  String = "flinkCheckpointDir"

class FlinkBackend extends Backend:
  def id:              String                               = "flink"
  def displayName:     String                               = "Apache Flink (Scala 3 + scala-cli)"
  def spiVersion:      String                               = SpiVersion.Current
  def capabilities:    Capabilities                         = FlinkCapabilities
  def intrinsics:      Map[ir.QualifiedName, IntrinsicImpl] = Map.empty
  def acceptedSources: Set[String]                          = Set.empty

  def compile(module: ir.NormalizedModule, opts: BackendOptions): CompileResult =
    val astModule   = Denormalize(module)
    val master      = opts.extra.getOrElse(FlinkBackend.FlinkMasterOption, FlinkGen.DefaultFlinkMaster)
    val parallelism = opts.extra.get(FlinkBackend.FlinkParallelismOption).flatMap(_.toIntOption).getOrElse(1)
    val checkpoint  = opts.extra.get(FlinkBackend.FlinkCheckpointOption)
    val baseDir     = opts.baseDir.map(p => os.Path(p.toAbsolutePath.toString))
    val code        = FlinkGen.generate(astModule, baseDir = baseDir,
                        flinkMaster = master, parallelism = parallelism, checkpointDir = checkpoint)
    val hash        = java.lang.Integer.toHexString(code.hashCode & 0x7fffffff)
    val tmpFile     = os.Path(s"/tmp/ssc-flink-$hash.scala")
    os.write.over(tmpFile, code)
    System.err.println(s"[flink] master=$master parallelism=$parallelism — generated: $tmpFile")
    val cmd = List("scala-cli", "run", tmpFile.toString)
    val exit = scala.sys.process.Process(cmd).!
    CompileResult.Executed(stdout = "", stderr = "", exit = exit)

/** Backend SPI adapter for the Apache Beam target (target id `"beam"`).
 *
 *  v2.1.5 — DStream-to-Beam PCollection lowering.
 *
 *  Pipeline:
 *  1. Denormalize the IR module back to `ast.Module` — `BeamGen` consumes AST.
 *  2. Generate Scala 3 + Beam source via `BeamGen.generate(...)`.
 *  3. Persist to `/tmp/ssc-beam-<hash>.scala`.
 *  4. Shell out to `scala-cli run <tmpfile>`.
 *  5. Return `CompileResult.Executed(stdout, stderr, exit)`. */
object BeamBackend:
  val BeamRunnerOption:    String = "beamRunner"
  val BeamFlinkMasterOption: String = "beamFlinkMaster"
  val BeamCheckpointOption:  String = "beamCheckpointDir"

class BeamBackend extends Backend:
  def id:              String                               = "beam"
  def displayName:     String                               = "Apache Beam (Scala 3 + scala-cli)"
  def spiVersion:      String                               = SpiVersion.Current
  def capabilities:    Capabilities                         = BeamCapabilities
  def intrinsics:      Map[ir.QualifiedName, IntrinsicImpl] = Map.empty
  def acceptedSources: Set[String]                          = Set.empty

  def compile(module: ir.NormalizedModule, opts: BackendOptions): CompileResult =
    val astModule  = Denormalize(module)
    val runner     = opts.extra.getOrElse(BeamBackend.BeamRunnerOption, BeamGen.DefaultRunner)
    val flinkMstr  = opts.extra.get(BeamBackend.BeamFlinkMasterOption)
    val checkpoint = opts.extra.get(BeamBackend.BeamCheckpointOption)
    val baseDir    = opts.baseDir.map(p => os.Path(p.toAbsolutePath.toString))
    val code       = BeamGen.generate(astModule, baseDir = baseDir,
                       runner = runner, flinkMaster = flinkMstr, checkpointDir = checkpoint)
    val hash       = java.lang.Integer.toHexString(code.hashCode & 0x7fffffff)
    val tmpFile    = os.Path(s"/tmp/ssc-beam-$hash.scala")
    os.write.over(tmpFile, code)
    System.err.println(s"[beam] runner=$runner — generated: $tmpFile")
    val cmd = List("scala-cli", "run", tmpFile.toString)
    val exit = scala.sys.process.Process(cmd).!
    CompileResult.Executed(stdout = "", stderr = "", exit = exit)
