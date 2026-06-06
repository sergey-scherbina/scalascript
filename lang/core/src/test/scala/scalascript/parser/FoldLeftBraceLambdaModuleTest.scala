package scalascript.parser

import org.scalatest.funsuite.AnyFunSuite

/** busi-p0-foldleft-brace-lambda — `foldLeft { (acc, x) => ... }` brace-block
 *  lambda allegedly silently drops a module's exports per busi report.
 *  Verify on the actual parser. */
class FoldLeftBraceLambdaModuleTest extends AnyFunSuite:

  test("foldLeft brace-lambda body parses to a non-empty tree"):
    val src =
      """# Mod
        |
        |```scalascript
        |def sumList(xs: List[Int]): Int =
        |  xs.foldLeft(0) { (acc, x) => acc + x }
        |
        |def save(xs: List[Int]): Int = sumList(xs)
        |```
        |""".stripMargin
    val m  = Parser.parse(src)
    val cb = m.sections.flatMap(_.content).collectFirst {
      case c: scalascript.ast.Content.CodeBlock => c
    }.getOrElse(fail("expected one CodeBlock"))
    assert(cb.tree.isDefined,
      s"no-package module: brace-lambda body did not parse (parseError=${cb.parseError})")

  test("foldLeft brace-lambda with package: front-matter still parses"):
    val src =
      """---
        |package: org.example.foo
        |---
        |# Mod
        |
        |```scalascript
        |def sumList(xs: List[Int]): Int =
        |  xs.foldLeft(0) { (acc, x) => acc + x }
        |
        |def save(xs: List[Int]): Int = sumList(xs)
        |```
        |""".stripMargin
    val m  = Parser.parse(src)
    val cb = m.sections.flatMap(_.content).collectFirst {
      case c: scalascript.ast.Content.CodeBlock => c
    }.getOrElse(fail("expected one CodeBlock"))
    assert(cb.tree.isDefined,
      s"with-package module: brace-lambda body did not parse (parseError=${cb.parseError})")

  test("foldLeft brace-lambda with package: — InterfaceExtractor sees the def"):
    // The actual symptom busi reports: module's exports are NOT registered.
    // Mirror the exact code path that surfaces this — compile the module's
    // interface and check that `save` is in the export list.
    val src =
      """---
        |package: org.example.foo
        |---
        |# Mod
        |
        |```scalascript
        |def sumList(xs: List[Int]): Int =
        |  xs.foldLeft(0) { (acc, x) => acc + x }
        |
        |def save(xs: List[Int]): Int = sumList(xs)
        |```
        |""".stripMargin
    val m  = Parser.parse(src)
    val iface = scalascript.artifact.InterfaceExtractor.extract(m, src.getBytes("UTF-8"))
    val exportNames = iface.exports.map(_.name)
    assert(exportNames.contains("save"),
      s"InterfaceExtractor did not see `save` — got exports: $exportNames")
    assert(exportNames.contains("sumList"),
      s"InterfaceExtractor did not see `sumList` — got exports: $exportNames")

  test("brace-lambda + trailing-underscore param (busi-72 combo) — both work"):
    // The real busi-72 scenario: both bugs in the SAME module.  The
    // trailing-underscore in `type_: String` used to block the parse and
    // busi attributed the failure to the brace-lambda too.  With P0 #1
    // landed, this combo must parse cleanly.
    val src =
      """---
        |package: org.example.foo
        |---
        |# Mod
        |
        |```scalascript
        |def eventCanonical(type_: String, payload_: String): String =
        |  List(type_, payload_).foldLeft("") { (acc, s) => acc + s }
        |
        |def save(t: String, p: String): String = eventCanonical(t, p)
        |```
        |""".stripMargin
    val m  = Parser.parse(src)
    val cb = m.sections.flatMap(_.content).collectFirst {
      case c: scalascript.ast.Content.CodeBlock => c
    }.getOrElse(fail("expected one CodeBlock"))
    assert(cb.tree.isDefined,
      s"combined trailing-underscore + brace-lambda body did not parse (parseError=${cb.parseError})")
    val iface = scalascript.artifact.InterfaceExtractor.extract(m, src.getBytes("UTF-8"))
    val exportNames = iface.exports.map(_.name)
    assert(exportNames.contains("save"),
      s"`save` not in exports — got: $exportNames")
    assert(exportNames.contains("eventCanonical"),
      s"`eventCanonical` not in exports — got: $exportNames")

  test("nested brace-lambdas — `xs.foldLeft(0) { (a, x) => ys.foldLeft(a) { (b, y) => b + y } }`"):
    val src =
      """---
        |package: org.example.foo
        |---
        |# Mod
        |
        |```scalascript
        |def sumNested(xs: List[Int], ys: List[Int]): Int =
        |  xs.foldLeft(0) { (a, x) => ys.foldLeft(a) { (b, y) => b + y } }
        |
        |def save(xs: List[Int], ys: List[Int]): Int = sumNested(xs, ys)
        |```
        |""".stripMargin
    val m  = Parser.parse(src)
    val cb = m.sections.flatMap(_.content).collectFirst {
      case c: scalascript.ast.Content.CodeBlock => c
    }.getOrElse(fail("expected one CodeBlock"))
    assert(cb.tree.isDefined,
      s"nested brace-lambda body did not parse (parseError=${cb.parseError})")
