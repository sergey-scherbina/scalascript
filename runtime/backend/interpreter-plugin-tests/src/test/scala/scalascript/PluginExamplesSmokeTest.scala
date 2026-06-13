package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Smoke-run plugin-backed example files end-to-end through the real interpreter
 *  with the std plugins on the classpath (this project depends on `allPlugins`).
 *
 *  The `cli` `ExamplesSmokeTest` runs only *core* examples — its test classpath has
 *  no std plugins, so crypto / uuid / … examples exit 1 there and are excluded. This
 *  closes that gap: each curated example must run to completion without throwing and
 *  without printing an error marker. (Network / GUI / browser / Spark examples are
 *  intentionally left out — they can't run headlessly.) */
class PluginExamplesSmokeTest extends AnyFunSuite:

  // crypto (3) + uuid + invoice pdf/email — self-contained, plugin-backed, headless.
  // Excluded: dataset/spark examples need the Spark backend's `DatasetCodec` (not an
  // interpreter plugin); network / GUI / browser / server examples can't run headless.
  private val examples: List[String] = List(
    "crypto-demo.ssc", "crypto-encrypt-demo.ssc", "crypto-verify-demo.ssc",
    "uuid-v7.ssc", "invoice-pdf.ssc", "invoice-email.ssc"
  )

  private val errorMarkers = List("[ERROR]", "[error]", "Exception", "Undefined:",
    "No method", "No key", "Unhandled effect", "Not callable", "not found")

  test("plugin-backed examples run to completion (no throw, no error output)"):
    val baseDir = TestPaths.repoRoot
    val failures = examples.flatMap { name =>
      val f = baseDir / "examples" / name
      if !os.exists(f) then Some(s"$name: missing")
      else
        val buf = java.io.ByteArrayOutputStream()
        val ps  = java.io.PrintStream(buf, true)
        try
          Interpreter(out = ps, headless = true, baseDir = Some(baseDir)).run(Parser.parse(os.read(f)))
          ps.flush()
          val out = buf.toString
          errorMarkers.find(out.contains) match
            case Some(m) => Some(s"$name: printed error marker '$m'\n    ${out.linesIterator.find(_.contains(m)).getOrElse("").trim.take(120)}")
            case None    => None
        catch case e: Throwable => Some(s"$name: threw ${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("").take(120)}")
    }
    assert(failures.isEmpty,
      s"${failures.size}/${examples.size} plugin examples failed to run cleanly:\n  " +
      failures.mkString("\n  "))
