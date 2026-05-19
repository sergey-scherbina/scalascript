package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import java.io.ByteArrayOutputStream

/** Integration coverage for `ssc submit` orchestration (v1.25 § 9.5 Phase B.2).
 *
 *  The pure command builders (`SparkSubmit.packageCommand`,
 *  `SparkSubmit.submitCommand`) already have their own unit tests in
 *  `backend-spark`.  This suite exercises the *glue* in
 *  `scalascript.cli.submitCommand`:
 *
 *    1. Parsing CLI flags (`--spark-master`, `--spark-version`,
 *       `--dry-run`, `--` separator for spark-submit pass-through).
 *    2. Resolving spark-version / spark-master from front-matter when
 *       no CLI flag is given.
 *    3. Wiring it all together — same `/tmp/ssc-spark-<hash>` source +
 *       jar paths the orchestration uses for the real shell-out.
 *
 *  Tests rely on `--dry-run` so no actual `scala-cli package` or
 *  `spark-submit` ever runs — every assertion is on the printed argv. */
class SubmitCommandTest extends AnyFunSuite:

  private def captureStdout(thunk: => Unit): String =
    val buf = ByteArrayOutputStream()
    Console.withOut(buf)(thunk)
    buf.toString.trim

  private def writeFixture(dir: os.Path, frontMatter: String = ""): os.Path =
    val src =
      s"""|${if frontMatter.nonEmpty then s"---\n$frontMatter\n---\n" else ""}# Word Count
          |
          |```scalascript
          |val xs = List(1, 2, 3)
          |```
          |""".stripMargin
    val path = dir / "job.ssc"
    os.write(path, src)
    path

  test("--dry-run prints package + submit argv with default master") {
    val tmp = os.temp.dir()
    val ssc = writeFixture(tmp)
    val out = captureStdout {
      scalascript.cli.submitCommand(List("--dry-run", ssc.toString))
    }
    assert(out.contains("scala-cli --power package"),
      s"missing package argv, got:\n$out")
    assert(out.contains("--assembly"))
    assert(out.contains("org.apache.spark::spark-core:4.0.0"))
    assert(out.contains("org.apache.spark::spark-sql:4.0.0"))
    // Default master = local[*] (no front-matter, no CLI flag).
    assert(out.contains("spark-submit --master local[*] --class runSparkJob"),
      s"expected spark-submit with local[*] master, got:\n$out")
    assert(out.contains(".jar"), s"expected JAR reference, got:\n$out")
  }

  test("--spark-master CLI flag overrides the default") {
    val tmp = os.temp.dir()
    val ssc = writeFixture(tmp)
    val out = captureStdout {
      scalascript.cli.submitCommand(List(
        "--dry-run",
        "--spark-master", "spark://prod.example.com:7077",
        ssc.toString
      ))
    }
    assert(out.contains("--master spark://prod.example.com:7077"),
      s"expected spark:// master, got:\n$out")
    assert(!out.contains("local[*]"),
      s"default master must not leak when --spark-master is set:\n$out")
  }

  test("front-matter spark-master used when no CLI flag") {
    val tmp = os.temp.dir()
    val ssc = writeFixture(tmp, frontMatter = "spark-master: yarn")
    val out = captureStdout {
      scalascript.cli.submitCommand(List("--dry-run", ssc.toString))
    }
    assert(out.contains("--master yarn"), s"expected yarn from front-matter, got:\n$out")
  }

  test("CLI --spark-master takes precedence over front-matter") {
    val tmp = os.temp.dir()
    val ssc = writeFixture(tmp, frontMatter = "spark-master: yarn")
    val out = captureStdout {
      scalascript.cli.submitCommand(List(
        "--dry-run", "--spark-master", "local[4]", ssc.toString
      ))
    }
    assert(out.contains("--master local[4]"))
    assert(!out.contains("--master yarn"),
      s"front-matter master must yield to CLI flag, got:\n$out")
  }

  test("--spark-version threads through to both deps") {
    val tmp = os.temp.dir()
    val ssc = writeFixture(tmp)
    val out = captureStdout {
      scalascript.cli.submitCommand(List(
        "--dry-run", "--spark-version", "3.5.1", ssc.toString
      ))
    }
    assert(out.contains("spark-core:3.5.1"))
    assert(out.contains("spark-sql:3.5.1"))
    assert(!out.contains("4.0.0"),
      s"default version must not leak when --spark-version=3.5.1, got:\n$out")
  }

  test("`--` separator passes extra args through to spark-submit") {
    val tmp = os.temp.dir()
    val ssc = writeFixture(tmp)
    val out = captureStdout {
      scalascript.cli.submitCommand(List(
        "--dry-run", "--spark-master", "yarn", ssc.toString,
        "--", "--executor-memory", "4g", "--num-executors", "8"
      ))
    }
    // Extra args land between the --class block and the JAR.
    assert(out.contains("--class runSparkJob --executor-memory 4g --num-executors 8"),
      s"expected extras spliced between --class and JAR, got:\n$out")
  }

  test("argv before `--` separator with the same flag names stays parsed by ssc") {
    // Sanity: `--spark-master` before `--` is consumed by submitCommand,
    // not forwarded to spark-submit even though it looks like a Spark flag.
    val tmp = os.temp.dir()
    val ssc = writeFixture(tmp)
    val out = captureStdout {
      scalascript.cli.submitCommand(List(
        "--dry-run", "--spark-master", "yarn", ssc.toString
      ))
    }
    // Master appears once, in the spark-submit invocation — not duplicated.
    val masterOccurrences = "--master ".r.findAllIn(out).size
    assert(masterOccurrences == 1,
      s"expected exactly one --master in output, got $masterOccurrences:\n$out")
  }
