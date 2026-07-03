package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Regression for the std.ui-Style failure originally misdiagnosed as "cross-module
 *  enum matching broken".  The real cause was the partial named-arg case-class
 *  default bug (fixed in NamedArgDefaultsTest): `Sty(bg = .., radius = Some(Rad.Md))`
 *  mis-bound `Rad.Md` into the wrong field, so a later match on it failed.  Cross-
 *  module enum matching itself works fine — this proves it end-to-end: an enum value
 *  set via partial named-arg construction in one module is matched in another. */
class EnumCrossModuleTest extends AnyFunSuite:

  test("enum value set via partial named-arg ctor in one module matches in another"):
    val dir = TestPaths.repoRoot / "examples" / "_enumxmod"
    val src = os.read(dir / "main.ssc")
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(out = ps, headless = true,
                baseDir = Some(dir)).run(Parser.parse(src))
    ps.flush()
    val out = buf.toString
    assert(out.contains("result:md"),
      s"cross-module enum match (Rad.Md via partial named ctor) failed:\n$out")
