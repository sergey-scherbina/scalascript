package scalascript.codegen

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

/** An in-fence `[names](path.ssc)` import must not reach the emitted Scala as code.
 *
 *  `emitBlock` passes a block's SOURCE through verbatim when the block needs no rewriting, so the
 *  markdown import line was emitted as-is and scalac answered `expression expected but '[' found`
 *  thousands of lines away from anything the user wrote
 *  (`jvm-std-import-inside-a-fence-plus-def-main-emits-broken-scala`).
 *
 *  THE TWO CONDITIONS ARE THE POINT, and each alone is green — which is why the shipped corpus never
 *  caught it. An entrypoint `def main` is the shape with nothing to rewrite; a top-level statement
 *  needs auto-output wrapping, and rebuilding the block from its parsed tree drops the line as a
 *  side effect. Both rows are below, so a future change that "fixes" only the loud one fails here.
 */
final class JvmGenInFenceImportTest extends AnyFunSuite:

  private val linkLine = """(?m)^\s*\[[^\]]+\]\([^)]*\.ssc\)\s*$""".r

  /** Emitted Scala, minus the comment lines — the rewrite turns the import into `// list-import: …`,
   *  which is legal Scala and must NOT count as a leak. Asserting on the raw text would pass for the
   *  wrong reason if the line were merely moved. */
  private def codeWithoutComments(code: String): String =
    code.linesIterator.filterNot(_.trim.startsWith("//")).mkString("\n")

  test("an in-fence import does not reach the emitted Scala — with an entrypoint def main"):
    val source =
      """# t
        |
        |```scalascript
        |[exists](std/fs.ssc)
        |def main(): Unit = println(1)
        |```
        |""".stripMargin
    val code = codeWithoutComments(JvmGen.generate(Parser.parse(source)))
    assert(linkLine.findFirstIn(code).isEmpty,
      s"the import link was emitted as code: ${linkLine.findFirstIn(code).getOrElse("")}")
    // The program itself must still be there — a fix that dropped the whole block would also pass
    // the assertion above.
    assert(code.contains("def main()"), code.takeRight(400))

  test("…and with a top-level statement, the shape that was already green"):
    val source =
      """# t
        |
        |```scalascript
        |[exists](std/fs.ssc)
        |println(1)
        |```
        |""".stripMargin
    val code = codeWithoutComments(JvmGen.generate(Parser.parse(source)))
    assert(linkLine.findFirstIn(code).isEmpty,
      s"the import link was emitted as code: ${linkLine.findFirstIn(code).getOrElse("")}")
    assert(code.contains("println(1)"), code.takeRight(400))

  /** The shape most programs actually have. Fences have been optional since 2026-07-09, so in a bare
   *  `.ssc` the whole file is code and the identical import line sits inside that one block — the
   *  same verbatim path, with no fence anywhere to point at. */
  test("…and in a BARE .ssc, where the whole file is one code block"):
    val source =
      """[exists](std/fs.ssc)
        |def main(): Unit = println(1)
        |""".stripMargin
    val code = codeWithoutComments(JvmGen.generate(Parser.parse(source)))
    assert(linkLine.findFirstIn(code).isEmpty,
      s"the import link was emitted as code: ${linkLine.findFirstIn(code).getOrElse("")}")
    // Asserted TOGETHER with the line above on purpose: if a bare file were parsed as prose the
    // program would vanish, the link assertion would pass for the wrong reason, and this row would
    // be testing nothing.
    assert(code.contains("def main()"), code.takeRight(400))

  test("a document-level import above the fence still emits no link line"):
    val source =
      """# t
        |
        |[exists](std/fs.ssc)
        |
        |```scalascript
        |def main(): Unit = println(1)
        |```
        |""".stripMargin
    val code = codeWithoutComments(JvmGen.generate(Parser.parse(source)))
    assert(linkLine.findFirstIn(code).isEmpty,
      s"the import link was emitted as code: ${linkLine.findFirstIn(code).getOrElse("")}")

  /** The regex says the line is gone; this says the FILE PARSES, which is what the bug report
   *  actually was (`expression expected but '[' found`). It closes the gap between "the symptom I
   *  matched on" and "the failure the user saw" — a second junk line of a different shape would
   *  slip past the regex and be caught here.
   *
   *  THE DIALECT IS THE WHOLE SUBTLETY, and getting it wrong made this row fail against a
   *  generator that was already fixed: what JvmGen emits is a SCRIPT — `object … : …` followed by
   *  a top-level `main()` — and plain `dialects.Scala3` rejects a top-level term by construction,
   *  not because anything is wrong with the file. `JvmGen.scala:3214` had already learned this and
   *  says so; `withAllowToplevelTerms` is the dialect scalameta provides for that shape. */
  test("the emitted Scala parses — the failure in the report was scalac refusing the file"):
    val source =
      """# t
        |
        |```scalascript
        |[exists](std/fs.ssc)
        |def main(): Unit = println(1)
        |```
        |""".stripMargin
    val code = JvmGen.generate(Parser.parse(source))
    import scala.meta.{dialects, *}
    val scriptDialect = dialects.Scala3.withAllowToplevelTerms(true)
    val parsed = scriptDialect(Input.VirtualFile("<emitted>", code)).parse[Source]
    assert(parsed.toOption.isDefined, parsed.toString.take(400))

  /** The probe itself must be able to fail: a regex that matches nothing would pass every test
   *  above on a broken generator too. */
  test("control — the detector sees a link line when one is present"):
    assert(linkLine.findFirstIn("[exists](std/fs.ssc)").isDefined)
    assert(linkLine.findFirstIn("// list-import: [exists](std/fs.ssc)").isEmpty,
      "the rewritten form is a comment and must not read as a leak")
