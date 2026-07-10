package scalascript.typer

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser
import scala.jdk.CollectionConverters.*

/** wide-jit C-1: the typer records a `Term → SType` side-map (`nodeTypes`) during
 *  inference, so the register-VM JIT (C-3) can seed register types from static types
 *  instead of re-inferring at runtime (`typeOf` defaulting to `TInt`). This test pins
 *  that the map is populated with real first-order types. See
 *  `specs/wide-jit-typed-input.md`. */
class WideJitNodeTypesTest extends AnyFunSuite:

  private def moduleOf(src: String): scalascript.ast.Module =
    Parser.parse(
      s"""# T
         |
         |```scalascript
         |$src
         |```
         |""".stripMargin
    )

  test("inferType records Int-typed nodes into nodeTypes"):
    val typer = new Typer()
    typer.typeCheck(moduleOf("""val x = 1 + 2"""))
    assert(typer.nodeTypes.size > 0, "nodeTypes should be populated after typeCheck")
    val vals = typer.nodeTypes.values.asScala.toList
    assert(vals.contains(SType.Int), s"expected an Int-typed node; got $vals")

  test("string literal node is recorded as String"):
    val typer = new Typer()
    typer.typeCheck(moduleOf("""val s = "hi""""))
    assert(
      typer.nodeTypes.values.asScala.exists(_ == SType.String),
      "expected a String-typed node"
    )

  test("companion Typer.typeCheck still returns a TypedModule (behavior-neutral)"):
    val typed = Typer.typeCheck(moduleOf("""val x = 1 + 2"""))
    assert(typed.errors.isEmpty, s"unexpected type errors: ${typed.errors}")
