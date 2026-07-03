package scalascript.typer

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser
import scalascript.ir.ExportedSymbol

/** core-min-prelude-spi — a plugin contributes typed prelude symbols (names + `SType.show`
 *  signatures via `ExportedSymbol`); the Typer resolves AND type-checks calls to them, with no
 *  hardcoded core list. See `specs/core-min-prelude-spi.md`. */
class TyperPreludeSymbolsTest extends AnyFunSuite:

  private def moduleOf(src: String): scalascript.ast.Module =
    Parser.parse(s"# Test\n\n```scalascript\n$src\n```\n")

  // A plugin-contributed intrinsic: `plug(Int): String`.
  private val plug = List(ExportedSymbol("plug", "plug", "def", "(Int) => String"))

  test("without preludeSymbols, strict mode flags the plugin name as undefined"):
    val typed = Typer(strict = true).typeCheck(moduleOf("""def f(): String = plug(1)"""))
    assert(typed.errors.exists(_.msg.contains("plug")),
      s"expected undefined-name for `plug`; got: ${typed.errors.map(_.msg).mkString(" | ")}")

  test("with preludeSymbols, the plugin name RESOLVES (no undefined-name)"):
    val typed = Typer(strict = true, preludeSymbols = plug).typeCheck(moduleOf("""def f(): String = plug(1)"""))
    assert(!typed.errors.exists(_.msg.contains("undefined name: plug")),
      s"`plug` should resolve via preludeSymbols; got: ${typed.errors.map(_.msg).mkString(" | ")}")

  test("with preludeSymbols, the declared TYPE is used — a return-type mismatch is flagged"):
    // `plug` returns String; a `def g(): Int = plug(1)` body type (String) ≠ the declared Int return.
    val typed = Typer(strict = true, preludeSymbols = plug).typeCheck(moduleOf("""def g(): Int = plug(1)"""))
    assert(typed.hasErrors,
      "expected a type mismatch: plug returns String but g is declared Int")
    // …and the SAME call typed correctly does NOT error (proves it's the type, not the name).
    val ok = Typer(strict = true, preludeSymbols = plug).typeCheck(moduleOf("""def g(): String = plug(1)"""))
    assert(!ok.hasErrors,
      s"a correctly-typed plugin call should pass; got: ${ok.errors.map(_.msg).mkString(" | ")}")
