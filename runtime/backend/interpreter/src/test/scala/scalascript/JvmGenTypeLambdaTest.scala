package scalascript

import org.scalatest.funsuite.AnyFunSuite

import scalascript.codegen.JvmGen
import scalascript.parser.Parser

/** JvmGen emission for type-level lambdas (sprint `type-lambda-p2b` /
 *  `type-lambda-nested-aliases`).
 *
 *  A placeholder alias (`type X = Map[Int, _]`) is desugared by the parser to a
 *  native Scala-3 type lambda (`[A] =>> Map[Int, A]`). `blockContainsTypeLambda`
 *  must then route the block through the tree-emit path (`emitStats`) — NOT the
 *  verbatim `block.src` — so Scala 3 sees the lambda. A verbatim `Map[Int, _]`
 *  would be read as a wildcard ("does not take type parameters") when applied.
 *  These text-shape assertions confirm the desugared `=>>` survives into the
 *  emitted Scala for both top-level and nested-in-object aliases. */
class JvmGenTypeLambdaTest extends AnyFunSuite:

  private def emit(ssc: String): String =
    JvmGen.generate(Parser.parse(ssc))

  test("top-level placeholder alias emits the desugared `=>>` lambda"):
    val code = emit(
      """|# M
         |
         |```scalascript
         |type IntKey = Map[Int, _]
         |def f(): IntKey[Long] = ???
         |```
         |""".stripMargin
    )
    assert(code.contains("[A] =>> Map[Int, A]"),
      s"expected desugared type lambda in emitted code, got:\n$code")
    assert(!code.contains("Map[Int, _]"),
      s"verbatim wildcard alias must not survive to emitted code, got:\n$code")

  test("placeholder alias nested in an `object` emits the desugared `=>>` lambda"):
    val code = emit(
      """|# M
         |
         |```scalascript
         |object Keys:
         |  type IntKey = Map[Int, _]
         |  def f(): IntKey[Long] = ???
         |```
         |""".stripMargin
    )
    assert(code.contains("[A] =>> Map[Int, A]"),
      s"expected desugared type lambda inside object body, got:\n$code")
    assert(!code.contains("Map[Int, _]"),
      s"verbatim wildcard alias must not survive to emitted code, got:\n$code")
