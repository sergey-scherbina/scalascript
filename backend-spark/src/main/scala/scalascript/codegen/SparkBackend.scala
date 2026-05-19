package scalascript.codegen

import scalascript.backend.spi.*
import scalascript.ir
import scalascript.transform.Denormalize

/** Backend SPI adapter for the Apache Spark target (target id `"spark"`).
 *
 *  Phase A of v1.25's Spark roadmap (§ 9.5 of `SPEC.md`): replaces the
 *  `Main.runViaSparkBackend` side-path with a regular `Backend` so the
 *  target shows up in `--list-backends`, can be selected via
 *  `--backend spark`, participates in `CapabilityCheck`, and accepts
 *  options through `BackendOptions.extra` instead of bespoke CLI
 *  plumbing.
 *
 *  Pipeline (mirrors the historical `runViaSparkBackend`):
 *
 *    1. Denormalize the IR module back to `ast.Module` — `SparkGen`
 *       consumes the AST shape.
 *    2. Generate the Scala 3 + Spark source via
 *       `SparkGen.generate(astModule, baseDir, sparkVersion)`.
 *    3. Persist to a deterministic temp file (`/tmp/ssc-spark-<hash>.scala`)
 *       so `scala-cli` can read it and so the user can inspect it.
 *    4. Shell out to `scala-cli run <tempfile>
 *         --dep org.apache.spark::spark-core:<version>
 *         --dep org.apache.spark::spark-sql:<version>
 *         --scala 3`.
 *    5. Return `CompileResult.Executed(stdout, stderr, exit)`.
 *
 *  Resolution priority for both `sparkVersion` and `sparkMaster` —
 *  the front-matter side (`spark-version:`, `spark-master:`) is read
 *  at the call site *before* Normalize strips `Manifest.raw`; the
 *  resolved strings are threaded into `BackendOptions.extra`.  This
 *  backend therefore sees them as single strings in `extras`, falling
 *  back to defaults only when no caller supplied a value:
 *
 *    `sparkVersion` — opts.extra.get(...) → SparkGen.DefaultVersion
 *    `sparkMaster`  — opts.extra.get(...) → SparkGen.DefaultMaster
 *
 *  Phase B (this iteration) — `sparkMaster` parameterisation enables
 *  `--spark-master local[4]`, `--spark-master spark://host:7077`, etc.
 *  Phase B.2 (open) — `ssc submit` packages a fat JAR and invokes
 *  `spark-submit`; today cluster master URLs still go through
 *  `scala-cli run` and rely on the driver being able to ship classes
 *  to executors itself (works for thin-classpath Spark Standalone but
 *  not YARN / K8s in production). */
class SparkBackend extends Backend:
  def id:              String                               = "spark"
  def displayName:     String                               = "Apache Spark (Scala 3 + scala-cli)"
  def spiVersion:      String                               = SpiVersion.Current
  def capabilities:    Capabilities                         = SparkCapabilities
  def intrinsics:      Map[ir.QualifiedName, IntrinsicImpl] = Map.empty
  def acceptedSources: Set[String]                          = Set.empty

  def compile(module: ir.NormalizedModule, opts: BackendOptions): CompileResult =
    val astModule    = Denormalize(module)
    val sparkVersion = opts.extra.getOrElse("sparkVersion", SparkGen.DefaultVersion)
    val sparkMaster  = opts.extra.getOrElse("sparkMaster",  SparkGen.DefaultMaster)
    val baseDir      = opts.baseDir.map(p => os.Path(p.toAbsolutePath.toString))
    val code         = SparkGen.generate(
      astModule,
      baseDir      = baseDir,
      sparkVersion = sparkVersion,
      sparkMaster  = sparkMaster
    )
    val hash         = java.lang.Integer.toHexString(code.hashCode & 0x7fffffff)
    val tmpFile      = os.Path(s"/tmp/ssc-spark-$hash.scala")
    os.write.over(tmpFile, code)
    System.err.println(s"[spark] Spark $sparkVersion (master=$sparkMaster) — generated: $tmpFile")
    val cmd = List(
      "scala-cli", "run", tmpFile.toString,
      "--dep", s"org.apache.spark::spark-core:$sparkVersion",
      "--dep", s"org.apache.spark::spark-sql:$sparkVersion",
      "--scala", "3"
    )
    // Run scala-cli inheriting stdout/stderr — Spark's own logging and the
    // user program's println go straight to the terminal; capturing into
    // CompileResult.Executed strings would buffer indefinitely and lose
    // the interactive feel of `ssc run`.  Return empty stdout/stderr and
    // just the exit code; the CLI prints nothing on top of the inherited
    // output.
    val exit = scala.sys.process.Process(cmd).!
    CompileResult.Executed(stdout = "", stderr = "", exit = exit)
