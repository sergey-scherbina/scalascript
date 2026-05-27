package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.{JsGen, TreeShaker}
import scalascript.parser.Parser

/** Tests for dead-code elimination (tree-shaking) in JsGen.
 *
 *  Each test builds a tiny module, runs generateWithStats (tree-shaking ON)
 *  or generate (tree-shaking OFF), and asserts the presence or absence of
 *  function/const declarations in the generated JS output.
 *
 *  Convention: `withShake(source)` returns the generated JS with tree-shaking
 *  active.  `withoutShake(source)` returns the generated JS with tree-shaking
 *  disabled (--no-tree-shake equivalent).
 */
class JsTreeShakeTest extends AnyFunSuite:

  // ── helpers ──────────────────────────────────────────────────────────────

  private def withShake(source: String): String =
    val (code, _) = JsGen.generateWithStats(Parser.parse(source), noTreeShake = false)
    code

  private def withoutShake(source: String): String =
    // generate() has tree-shaking OFF (backward-compat API)
    JsGen.generate(Parser.parse(source))

  private def shakeStats(source: String): JsGen.TreeShakeStats =
    val (_, statsOpt) = JsGen.generateWithStats(Parser.parse(source), noTreeShake = false)
    statsOpt.getOrElse(fail("Expected tree-shake stats but got None"))

  private def containsFunctionDecl(js: String, name: String): Boolean =
    js.contains(s"function $name(") || js.contains(s"function $name ")

  private def containsConstDecl(js: String, name: String): Boolean =
    js.contains(s"const $name ") || js.contains(s"const $name=")

  // ─────────────────────────────────────────────────────────────────────────
  // 1. Unused helper absent when @main never calls it
  // ─────────────────────────────────────────────────────────────────────────

  test("unused def helper is absent from shaken JS output") {
    val source =
      """# App
        |```scalascript
        |def main() = println("hello")
        |def helper() = println("never called")
        |```
        |""".stripMargin

    val js = withShake(source)
    assert(!containsFunctionDecl(js, "helper"), "helper should be pruned")
    assert(containsFunctionDecl(js, "main"),    "main must be kept")
  }

  // ─────────────────────────────────────────────────────────────────────────
  // 2. Used helper is present in output
  // ─────────────────────────────────────────────────────────────────────────

  test("helper called by @main is kept in shaken JS output") {
    val source =
      """# App
        |```scalascript
        |def helper() = "hi"
        |def main() = println(helper())
        |```
        |""".stripMargin

    val js = withShake(source)
    assert(containsFunctionDecl(js, "helper"), "helper is called by main — must be kept")
    assert(containsFunctionDecl(js, "main"),   "main must be kept")
  }

  // ─────────────────────────────────────────────────────────────────────────
  // 3. Transitive reachability: foo → bar kept; baz pruned
  // ─────────────────────────────────────────────────────────────────────────

  test("transitive reachability: main->foo->bar kept, baz pruned") {
    val source =
      """# App
        |```scalascript
        |def bar() = 42
        |def foo() = bar()
        |def baz() = 999
        |def main() = println(foo())
        |```
        |""".stripMargin

    val js = withShake(source)
    assert(containsFunctionDecl(js, "main"), "main must be kept")
    assert(containsFunctionDecl(js, "foo"),  "foo is reachable via main")
    assert(containsFunctionDecl(js, "bar"),  "bar is reachable via foo")
    assert(!containsFunctionDecl(js, "baz"), "baz is unreachable — should be pruned")
  }

  // ─────────────────────────────────────────────────────────────────────────
  // 4. Export-annotated def is reachable even if main doesn't call it
  // ─────────────────────────────────────────────────────────────────────────

  test("export-listed def is kept even when main does not call it") {
    val source =
      """---
        |exports:
        |  - publicApi
        |---
        |# App
        |```scalascript
        |def main() = println("running")
        |def publicApi() = "exported"
        |def internal() = "private"
        |```
        |""".stripMargin

    val js = withShake(source)
    assert(containsFunctionDecl(js, "publicApi"), "publicApi is in exports — must be kept")
    assert(!containsFunctionDecl(js, "internal"), "internal is not reachable — should be pruned")
  }

  // ─────────────────────────────────────────────────────────────────────────
  // 5. Top-level side-effectful statement referencing a val keeps that val
  // ─────────────────────────────────────────────────────────────────────────

  test("top-level statement that references a val keeps that val") {
    val source =
      """# App
        |```scalascript
        |val x = 42
        |val unused = 99
        |println(x)
        |```
        |""".stripMargin

    val js = withShake(source)
    assert(containsConstDecl(js, "x"),         "x is referenced by println — must be kept")
    assert(!containsConstDecl(js, "unused"),    "unused is never referenced — should be pruned")
  }

  // ─────────────────────────────────────────────────────────────────────────
  // 6. --no-tree-shake: all symbols present regardless
  // ─────────────────────────────────────────────────────────────────────────

  test("--no-tree-shake keeps all symbols including unused ones") {
    val source =
      """# App
        |```scalascript
        |def main() = println("hi")
        |def neverCalled() = 0
        |def alsoNeverCalled() = 1
        |```
        |""".stripMargin

    val js = withoutShake(source)
    assert(containsFunctionDecl(js, "main"),          "main must be present")
    assert(containsFunctionDecl(js, "neverCalled"),   "neverCalled must be present with no-tree-shake")
    assert(containsFunctionDecl(js, "alsoNeverCalled"), "alsoNeverCalled must be present with no-tree-shake")
  }

  // ─────────────────────────────────────────────────────────────────────────
  // 7. --stats: output contains "Tree-shake: kept N / M symbols"
  // ─────────────────────────────────────────────────────────────────────────

  test("--stats: generateWithStats returns TreeShakeStats with summary") {
    val source =
      """# App
        |```scalascript
        |def main() = println("hi")
        |def unused1() = 1
        |def unused2() = 2
        |```
        |""".stripMargin

    val stats = shakeStats(source)
    assert(stats.summary.startsWith("Tree-shake: kept "), "summary format must start correctly")
    assert(stats.summary.contains("symbols"),             "summary must mention 'symbols'")
    assert(stats.total >= 3,  "total should be at least 3 (main + unused1 + unused2)")
    assert(stats.kept  >= 1,  "main must be kept")
    assert(stats.pruned >= 2, "unused1 + unused2 should be pruned")
  }

  // ─────────────────────────────────────────────────────────────────────────
  // 8. Recursive function: self-reference handled correctly (no infinite loop)
  // ─────────────────────────────────────────────────────────────────────────

  test("recursive function reachable from main does not cause infinite loop") {
    val source =
      """# App
        |```scalascript
        |def factorial(n: Int): Int =
        |  if n <= 1 then 1 else n * factorial(n - 1)
        |def main() = println(factorial(5))
        |```
        |""".stripMargin

    val js = withShake(source)
    assert(containsFunctionDecl(js, "factorial"), "factorial is reachable from main")
    assert(containsFunctionDecl(js, "main"),      "main must be present")
  }

  // ─────────────────────────────────────────────────────────────────────────
  // 9. Mutually recursive functions: both kept if either is reachable
  // ─────────────────────────────────────────────────────────────────────────

  test("mutually recursive functions: both kept if either is reachable from main") {
    val source =
      """# App
        |```scalascript
        |def isEven(n: Int): Boolean = if n == 0 then true else isOdd(n - 1)
        |def isOdd(n: Int): Boolean  = if n == 0 then false else isEven(n - 1)
        |def main() = println(isEven(4))
        |```
        |""".stripMargin

    val js = withShake(source)
    assert(containsFunctionDecl(js, "isEven"), "isEven is reachable from main")
    assert(containsFunctionDecl(js, "isOdd"),  "isOdd is reachable from isEven")
  }

  // ─────────────────────────────────────────────────────────────────────────
  // 10. Class: reachable when main instantiates it; unreachable class pruned
  // ─────────────────────────────────────────────────────────────────────────

  test("case class kept when reachable from main; unused class pruned") {
    val source =
      """# App
        |```scalascript
        |case class Point(x: Int, y: Int)
        |case class Unused(a: Int)
        |def main() = println(Point(1, 2))
        |```
        |""".stripMargin

    val js = withShake(source)
    assert(containsFunctionDecl(js, "Point"),  "Point is used by main — must be kept")
    assert(!containsFunctionDecl(js, "Unused"), "Unused is never referenced — should be pruned")
  }

  // ─────────────────────────────────────────────────────────────────────────
  // 11. TreeShaker.shake: total count is accurate
  // ─────────────────────────────────────────────────────────────────────────

  test("TreeShaker.shake: total and reachable counts are accurate") {
    val source =
      """# App
        |```scalascript
        |def a() = 1
        |def b() = a()
        |def c() = 99
        |def main() = b()
        |```
        |""".stripMargin

    val result = TreeShaker.shake(Parser.parse(source))
    assert(result.total >= 4,          s"expected at least 4 declarations, got ${result.total}")
    assert(result.reachable.contains("main"), "main must be reachable")
    assert(result.reachable.contains("b"),    "b is called by main")
    assert(result.reachable.contains("a"),    "a is called by b")
    assert(!result.reachable.contains("c"),   "c is not reachable")
  }

  // ─────────────────────────────────────────────────────────────────────────
  // 12. Multiple code blocks in one module
  // ─────────────────────────────────────────────────────────────────────────

  test("tree-shaking spans multiple scalascript code blocks in one module") {
    val source =
      """# App
        |
        |## Section A
        |
        |```scalascript
        |def helperA() = "A"
        |def unusedA() = "X"
        |```
        |
        |## Section B
        |
        |```scalascript
        |def main() = println(helperA())
        |def unusedB() = "Y"
        |```
        |""".stripMargin

    val js = withShake(source)
    assert(containsFunctionDecl(js, "helperA"), "helperA is called by main")
    assert(containsFunctionDecl(js, "main"),    "main must be present")
    assert(!containsFunctionDecl(js, "unusedA"), "unusedA is never referenced")
    assert(!containsFunctionDecl(js, "unusedB"), "unusedB is never referenced")
  }

  // ─────────────────────────────────────────────────────────────────────────
  // 13. Top-level terms (side effects) always kept and referenced names kept
  // ─────────────────────────────────────────────────────────────────────────

  test("top-level terms keep the functions they reference") {
    val source =
      """# App
        |```scalascript
        |def greet() = println("hello")
        |def unused() = "nope"
        |greet()
        |```
        |""".stripMargin

    val js = withShake(source)
    assert(containsFunctionDecl(js, "greet"),  "greet is referenced by a top-level statement")
    assert(!containsFunctionDecl(js, "unused"), "unused is not referenced anywhere")
  }

  // ─────────────────────────────────────────────────────────────────────────
  // 14. TreeShakeStats.summary format
  // ─────────────────────────────────────────────────────────────────────────

  test("TreeShakeStats.summary format matches expected pattern") {
    val stats = JsGen.TreeShakeStats(kept = 42, total = 78)
    val s = stats.summary
    assert(s.startsWith("Tree-shake: kept 42 / 78 symbols"), s"unexpected format: $s")
    assert(s.contains("removed 36"),  s"should say 'removed 36': $s")
    assert(s.contains("-46%"),        s"should say '-46%': $s")
    assert(stats.pruned == 36,        "pruned count should be 78 - 42 = 36")
  }

  // ─────────────────────────────────────────────────────────────────────────
  // 15. generateWithStats with noTreeShake=true returns None for stats
  // ─────────────────────────────────────────────────────────────────────────

  test("generateWithStats with noTreeShake=true returns None for stats") {
    val source =
      """# App
        |```scalascript
        |def main() = println("hi")
        |def dead() = 0
        |```
        |""".stripMargin

    val (code, statsOpt) = JsGen.generateWithStats(Parser.parse(source), noTreeShake = true)
    assert(statsOpt.isEmpty, "noTreeShake=true must return None for stats")
    assert(containsFunctionDecl(code, "dead"), "dead must be present when tree-shaking is off")
    assert(containsFunctionDecl(code, "main"), "main must always be present")
  }

  // ─────────────────────────────────────────────────────────────────────────
  // 16. Object declarations: reachable objects kept; unreachable pruned
  // ─────────────────────────────────────────────────────────────────────────

  test("object declaration kept when referenced by main") {
    val source =
      """# App
        |```scalascript
        |object Config:
        |  val port = 8080
        |
        |object Unused:
        |  val x = 0
        |
        |def main() = println(Config.port)
        |```
        |""".stripMargin

    val js = withShake(source)
    assert(containsConstDecl(js, "Config"), "Config is referenced by main — must be kept")
    assert(!containsConstDecl(js, "Unused"), "Unused is never referenced — should be pruned")
  }
