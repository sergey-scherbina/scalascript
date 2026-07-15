package scalascript.transform

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

class EffectAnalysisMultiShotTest extends AnyFunSuite:

  private def analyze(code: String) =
    val mod = Parser.parse(s"# S\n\n```scalascript\n$code\n```\n")
    val trees = mod.sections.flatMap(_.content.collect {
      case scalascript.ast.Content.CodeBlock(_, _, Some(t), _, _, _, _) =>
        scalascript.ast.ScalaNode.fold(t)(identity)
    })
    EffectAnalysis.analyze(trees)

  test("plain effect is NOT multi-shot") {
    val r = analyze(
      """object Logger {
        |  def log(msg: String): Unit = __effectOp__
        |}""".stripMargin
    )
    assert(!r.multiShotEffects.contains("Logger"))
    assert(r.effectOps.contains("Logger.log"))
  }

  test("multi effect (with __multiShot__ marker) is detected") {
    // multi effect NonDet preprocessed to:
    val r = analyze(
      """object NonDet {
        |  val __multiShot__ = true
        |  def choose(options: List[Any]): Any = __effectOp__
        |}""".stripMargin
    )
    assert(r.multiShotEffects.contains("NonDet"))
    assert(r.effectOps.contains("NonDet.choose"))
  }

  test("private type origin evidence has no effect-analysis behavior") {
    val r = analyze(
      """object Empty {
        |  private type __effectDecl__ = true
        |  private type __effectUnsupportedShape__ = true
        |}""".stripMargin
    )
    assert(r.effectOps.isEmpty)
    assert(r.effectfulFuns.isEmpty)
    assert(r.multiShotEffects.isEmpty)
  }

  test("mix: Logger is one-shot, NonDet is multi-shot") {
    val r = analyze(
      """object Logger {
        |  def log(msg: String): Unit = __effectOp__
        |}
        |object NonDet {
        |  val __multiShot__ = true
        |  def choose(options: List[Any]): Any = __effectOp__
        |}""".stripMargin
    )
    assert(!r.multiShotEffects.contains("Logger"))
    assert(r.multiShotEffects.contains("NonDet"))
  }
