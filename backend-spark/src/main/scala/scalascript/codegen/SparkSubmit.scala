package scalascript.codegen

/** Helpers for the `ssc submit` CLI verb (v1.25 § 9.5 Phase B.2).
 *
 *  Today's `ssc run --backend spark` (Phase A + B.1) ships the driver
 *  via `scala-cli run` — fine for Spark Standalone with a thin
 *  classpath, but YARN / Kubernetes / production clusters require a
 *  pre-built fat JAR launched through `spark-submit`.  `ssc submit`
 *  closes that gap:
 *
 *    ssc submit file.ssc                          # local[*] default
 *    ssc submit file.ssc --spark-master spark://host:7077
 *    ssc submit file.ssc --spark-master yarn
 *    ssc submit file.ssc --spark-master k8s://cluster.local:6443
 *
 *  Pipeline (orchestrated in `Main.submitCommand`):
 *
 *    1. Parse the .ssc, resolve sparkVersion + sparkMaster (same
 *       three-level priority as `ssc run --backend spark`).
 *    2. Generate Spark Scala 3 source via `SparkGen.generate(...)`.
 *    3. Write to `/tmp/ssc-spark-<hash>.scala`.
 *    4. Build a fat JAR — `scala-cli --power package <src> --assembly
 *       -o /tmp/ssc-spark-<hash>.jar --dep …spark-core… --dep …spark-sql…`.
 *    5. Launch — `spark-submit --master <url> --class runSparkJob <jar>`.
 *
 *  Step 5 is what makes `ssc submit` different from `ssc run --backend
 *  spark`: a fat JAR with all transitive deps means YARN / K8s
 *  executors don't need a ScalaScript / scala-cli install — they only
 *  need the same Spark version as the driver.
 *
 *  The two command builders are kept pure (no I/O, no shell-out) so
 *  `SparkSubmitTest` can pin the exact argv that would be invoked on
 *  every supported master URL without actually shelling out to Spark.
 *  The orchestration around them in `Main.submitCommand` does the
 *  actual `scala.sys.process.Process(cmd).!` work. */
object SparkSubmit:

  /** The Scala 3 class name that `SparkGen` emits via `@main def
   *  runSparkJob(): Unit`.  Pinned here so `spark-submit --class`
   *  picks up the same identifier the codegen produces; changing one
   *  side without the other would silently break submission. */
  val DefaultMainClass: String = "runSparkJob"

  /** Build the argv that produces a fat assembly JAR from the generated
   *  Spark Scala source.  `scala-cli --power package --assembly`
   *  resolves all Spark deps via Coursier and packages them into a
   *  single uber-JAR suitable for `spark-submit`.
   *
   *  `--force` overwrites any prior JAR at `outJar` (the temp path is
   *  hash-derived so it can be safely reused across runs of the same
   *  source). */
  def packageCommand(
      srcPath:      os.Path,
      outJar:       os.Path,
      @annotation.unused sparkVersion: String
  ): List[String] =
    // The emitted source already carries `//> using scala`, `//> using
    // dep`, and `//> using javaOpt` directives (v1.25 § 9.5 Phase E),
    // so scala-cli pulls dependencies + Scala version from the file
    // itself.  `sparkVersion` is kept on the signature for compatibility
    // with older call sites that still pass it; it has no effect today.
    List(
      "scala-cli", "--power", "package", srcPath.toString,
      "--assembly",
      "-o", outJar.toString,
      "--force"
    )

  /** Build the argv that launches the fat JAR on a Spark master.
   *  `extraSparkArgs` is spliced between the standard `--master` /
   *  `--class` block and the JAR path — callers use it for cluster-
   *  specific tuning (`--executor-memory`, `--num-executors`, etc.)
   *  that the ScalaScript CLI doesn't model directly.  All entries
   *  flow through verbatim — no quoting, no escaping. */
  def submitCommand(
      jar:            os.Path,
      mainClass:      String         = DefaultMainClass,
      master:         String         = "local[*]",
      extraSparkArgs: List[String]   = Nil
  ): List[String] =
    List(
      "spark-submit",
      "--master", master,
      "--class", mainClass
    ) ++ extraSparkArgs ++ List(jar.toString)
