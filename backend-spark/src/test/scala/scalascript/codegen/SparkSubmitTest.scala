package scalascript.codegen

import org.scalatest.funsuite.AnyFunSuite

/** Unit tests for `SparkSubmit` (v1.25 § 9.5 Phase B.2).
 *
 *  The actual `scala-cli package` and `spark-submit` shell-outs live
 *  in `Main.submitCommand` and need a real Spark install to exercise.
 *  These tests cover the pure command builders only — exact argv
 *  shape for every supported master URL and version override, so any
 *  future SparkGen / spark-submit interface change has to update the
 *  pinned strings here. */
class SparkSubmitTest extends AnyFunSuite:

  // ── packageCommand ────────────────────────────────────────────────────────

  test("packageCommand emits scala-cli --power package with both Spark deps") {
    val cmd = SparkSubmit.packageCommand(
      srcPath      = os.Path("/tmp/x.scala"),
      outJar       = os.Path("/tmp/x.jar"),
      sparkVersion = "4.0.0"
    )
    // The `--power` flag is required for `package` to expose its
    // full feature set (assembly mode, etc.) on recent scala-cli.
    assert(cmd.take(3) == List("scala-cli", "--power", "package"),
      s"expected scala-cli --power package prefix, got: $cmd")
    assert(cmd.contains("/tmp/x.scala"))
    assert(cmd.contains("--assembly"))
    assert(cmd.containsSlice(List("-o", "/tmp/x.jar")))
    assert(cmd.contains("--force"))
    // `_2.13` suffix pinned explicitly — Spark publishes only `_2.13`
    // cross-builds; `::` would expand to `_3:` and fail Coursier
    // resolution.  Scala 3 reads `_2.13` JARs via the TASTy bridge.
    assert(cmd.containsSlice(List("--dep", "org.apache.spark:spark-core_2.13:4.0.0")))
    assert(cmd.containsSlice(List("--dep", "org.apache.spark:spark-sql_2.13:4.0.0")))
    assert(cmd.containsSlice(List("--scala", "3")))
  }

  test("packageCommand reflects custom Spark version in both deps") {
    val cmd = SparkSubmit.packageCommand(
      srcPath      = os.Path("/tmp/y.scala"),
      outJar       = os.Path("/tmp/y.jar"),
      sparkVersion = "3.5.1"
    )
    assert(cmd.containsSlice(List("--dep", "org.apache.spark:spark-core_2.13:3.5.1")))
    assert(cmd.containsSlice(List("--dep", "org.apache.spark:spark-sql_2.13:3.5.1")))
    // No reference to the default version leaks through.
    assert(!cmd.exists(_.contains("4.0.0")),
      s"custom 3.5.1 version should not coexist with 4.0.0 default, got: $cmd")
  }

  // ── submitCommand ─────────────────────────────────────────────────────────

  test("submitCommand defaults to local[*] master + runSparkJob class") {
    val cmd = SparkSubmit.submitCommand(jar = os.Path("/tmp/x.jar"))
    assert(cmd.head == "spark-submit", s"expected spark-submit prefix, got: $cmd")
    assert(cmd.containsSlice(List("--master", "local[*]")))
    assert(cmd.containsSlice(List("--class", "runSparkJob")))
    // JAR path lands at the very end after master/class options.
    assert(cmd.last == "/tmp/x.jar")
  }

  test("submitCommand honours a custom master URL — Standalone") {
    val cmd = SparkSubmit.submitCommand(
      jar    = os.Path("/tmp/x.jar"),
      master = "spark://prod.example.com:7077"
    )
    assert(cmd.containsSlice(List("--master", "spark://prod.example.com:7077")))
    // Default local[*] must not leak.
    assert(!cmd.contains("local[*]"))
  }

  test("submitCommand honours a custom master URL — YARN") {
    val cmd = SparkSubmit.submitCommand(jar = os.Path("/tmp/x.jar"), master = "yarn")
    assert(cmd.containsSlice(List("--master", "yarn")))
  }

  test("submitCommand honours a custom master URL — Kubernetes") {
    val cmd = SparkSubmit.submitCommand(
      jar    = os.Path("/tmp/x.jar"),
      master = "k8s://cluster.local:6443"
    )
    assert(cmd.containsSlice(List("--master", "k8s://cluster.local:6443")))
  }

  test("submitCommand splices extraSparkArgs between class and JAR") {
    val cmd = SparkSubmit.submitCommand(
      jar            = os.Path("/tmp/x.jar"),
      master         = "yarn",
      extraSparkArgs = List(
        "--executor-memory", "4g",
        "--num-executors",   "8",
        "--deploy-mode",     "cluster"
      )
    )
    // The class block ends at index 4; the JAR is last; extras are in between.
    val classIdx = cmd.indexOf("--class")
    val jarIdx   = cmd.indexOf("/tmp/x.jar")
    assert(classIdx >= 0 && jarIdx > classIdx,
      s"--class must come before the JAR, got: $cmd")
    // Every extra arg appears in order between --class block and JAR.
    val between = cmd.slice(classIdx + 2, jarIdx)
    assert(between.containsSlice(List("--executor-memory", "4g")))
    assert(between.containsSlice(List("--num-executors", "8")))
    assert(between.containsSlice(List("--deploy-mode", "cluster")))
  }

  test("submitCommand honours a custom mainClass override") {
    val cmd = SparkSubmit.submitCommand(
      jar       = os.Path("/tmp/x.jar"),
      mainClass = "com.example.OtherEntry"
    )
    assert(cmd.containsSlice(List("--class", "com.example.OtherEntry")))
    // Default runSparkJob must not appear when an override is given.
    assert(!cmd.contains("runSparkJob"))
  }

  test("DefaultMainClass matches the @main def name emitted by SparkGen") {
    // `SparkGen.genModule` opens the @main wrapper with `@main def runSparkJob():
    // Unit =`.  Scala 3 turns that into a class named `runSparkJob`, which
    // `spark-submit --class` then resolves.  Drifting either side without
    // the other would silently break submission, so we pin both:
    assert(SparkSubmit.DefaultMainClass == "runSparkJob")
    // Asserting against a sample of generated source guards against the
    // codegen drifting independently of this constant.
    import scalascript.parser.Parser
    val module = Parser.parse("# Test\n```scalascript\nval x = 1\n```\n")
    val code   = SparkGen.generate(module)
    assert(code.contains(s"@main def ${SparkSubmit.DefaultMainClass}"),
      s"SparkGen no longer emits @main def ${SparkSubmit.DefaultMainClass}; update SparkSubmit.DefaultMainClass:\n$code")
  }
