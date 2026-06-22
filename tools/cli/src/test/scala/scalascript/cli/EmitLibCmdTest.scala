package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** `ssc emit-lib --host js --feature optics` writes the standalone npm package (the CLI surface
 *  over `JsLibPackager`). See `specs/polyglot-libraries.md` §4. */
class EmitLibCmdTest extends AnyFunSuite with Matchers:

  test("emit-lib js/optics writes package.json + index.mjs + optics.d.ts to -o dir"):
    val dir = os.temp.dir(prefix = "ssc-emit-lib")
    try
      new EmitLibCmd().run(List("--host", "js", "--feature", "optics", "--version", "1.2.3",
                                "-o", dir.toString))
      val files = os.list(dir).map(_.last).toSet
      files shouldBe Set("package.json", "index.mjs", "optics.d.ts")
      os.read(dir / "package.json")  should include (""""version": "1.2.3"""")
      os.read(dir / "package.json")  should include (""""name": "@scalascript/optics"""")
      os.read(dir / "index.mjs")     should include ("_makeLens as makeLens")
      os.read(dir / "optics.d.ts")   should include ("export function makeLens")
    finally os.remove.all(dir)

  test("emit-lib is registered in the CommandRegistry under 'emit-lib'"):
    CommandRegistry.all.exists(_.name == "emit-lib") shouldBe true
