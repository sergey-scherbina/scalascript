package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** A `multi effect` must be multi-shot whether or not the file uses fences.
 *
 *  Fences are optional (2026-07-09) and a BARE `.ssc` produces a code block whose lang is `scala`,
 *  while a fenced one produces `scalascript`. `Interpreter.collectScalaTrees` filtered on
 *  `Lang.isScalaScript`, so it skipped a bare file entirely, `multiShotEffects` came back empty, and
 *  a correct program died with `One-shot violation` — blaming the user for a marker the front had
 *  dropped (BUGS.md `multi-effect-marker-is-lost-in-a-bare-ssc`).
 *
 *  BOTH spellings are asserted to give the SAME answer. Testing only the bare one would pass if the
 *  effect machinery broke in a way that made both wrong. */
class MultiEffectBareFileTest extends AnyFunSuite:

  private val program =
    """multi effect NonDet:
      |  def choose(options: List[Int]): Int
      |
      |def program(): Int ! NonDet = NonDet.choose(List(1, 2)) + 10
      |
      |def main(): Unit =
      |  val all = handle(program()) {
      |    case NonDet.choose(opts, resume) => opts.flatMap(opt => resume(opt))
      |    case x => List(x)
      |  }
      |  println(all)
      |""".stripMargin

  private def run(src: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(out = ps).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  test("a `multi effect` resumes more than once in a BARE file, as it does in a fenced one"):
    val fenced = run("# t\n\n```scalascript\n" + program + "```\n")
    val bare   = run(program)
    assert(fenced == "List(11, 12)", fenced)
    assert(bare == fenced, s"bare=[$bare] fenced=[$fenced]")
