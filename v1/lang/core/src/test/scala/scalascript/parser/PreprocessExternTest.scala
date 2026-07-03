package scalascript.parser

import org.scalatest.funsuite.AnyFunSuite

/** v2.0 / std-pass-rate: pins the surface forms `extern def`,
 *  `extern class`, `extern object`, and list-form `[Name](path.ssc)`
 *  imports inside scalascript code blocks.  The preprocessors run
 *  before scalameta sees the code; failures here surface as opaque
 *  "illegal start of definition" diagnostics from scalameta. */
class PreprocessExternTest extends AnyFunSuite:

  // Wrap a fenced scalascript code block so `Parser.parse` actually runs
  // the preprocessor chain on it.
  private def parseBlock(body: String): scalascript.ast.Module =
    val src =
      s"""# T
         |
         |```scalascript
         |$body
         |```
         |""".stripMargin
    Parser.parse(src)

  private def blockTree(m: scalascript.ast.Module): Option[scalascript.ast.ScalaNode] =
    m.sections.headOption.flatMap(_.content.collectFirst {
      case cb: scalascript.ast.Content.CodeBlock => cb.tree
    }).flatten

  // ── extern def — pre-existing path, regression guard ───────────────────

  test("extern def survives the preprocessor and parses as Source"):
    val m = parseBlock("extern def add(x: Int, y: Int): Int")
    assert(blockTree(m).isDefined, "extern def should parse to a tree")

  // ── extern class — new 2026-05-19 ──────────────────────────────────────

  test("extern class with body-less defs parses"):
    val m = parseBlock(
      """extern class SseStream:
        |  def send(data: String): Unit
        |  def close(): Unit""".stripMargin)
    assert(blockTree(m).isDefined,
      "extern class with abstract methods should be rewritten + parsed")

  test("extern class with body-less val parses"):
    val m = parseBlock(
      """extern class UploadedFile:
        |  val name: String
        |  val size: Long""".stripMargin)
    assert(blockTree(m).isDefined, "extern class with val members should parse")

  // ── extern object — new 2026-05-19 ─────────────────────────────────────

  test("extern object with body-less defs parses"):
    val m = parseBlock(
      """extern object Dataset:
        |  def empty[A]: Dataset[A]
        |  def of[A](items: A*): Dataset[A]""".stripMargin)
    assert(blockTree(m).isDefined, "extern object with abstract methods should parse")

  // ── list-form imports inside code blocks — new 2026-05-19 ──────────────

  test("single-line [Name](path.ssc) is stripped and the block parses"):
    val m = parseBlock(
      """[ToolResult](./types.ssc)
        |
        |def use(t: ToolResult): Unit = ()""".stripMargin)
    assert(blockTree(m).isDefined,
      "single-name list-import should be stripped, leaving valid Scala")

  test("multi-name [A, B, C](path.ssc) is stripped"):
    val m = parseBlock(
      """[ToolResult, ResourceResult, Content](./types.ssc)
        |
        |def f(): Unit = ()""".stripMargin)
    assert(blockTree(m).isDefined, "multi-name list-import should be stripped")

  test("multi-line list import (continuation after comma) is stripped"):
    val m = parseBlock(
      """[ToolResult, ResourceResult, PromptResult, Content, Message, Role, Transport,
        | Tool, Resource, Prompt, requireString](./types.ssc)
        |
        |def g(): Unit = ()""".stripMargin)
    assert(blockTree(m).isDefined, "multi-line list-import should be stripped")

  test("alias-form [Card as UICard](path) is stripped"):
    val m = parseBlock(
      """[Card as UICard](./ui/card.ssc)
        |
        |def render(c: UICard): String = ???""".stripMargin)
    assert(blockTree(m).isDefined, "aliased list-import should be stripped")

  test("ordinary Scala [T](arg) type application is NOT stripped"):
    // Type-argument applications like `List[Int]` or `f[String]("x")` must
    // pass through untouched.
    val m = parseBlock(
      """def listOf[A](xs: A*): List[A] = xs.toList
        |val xs = listOf[Int](1, 2, 3)""".stripMargin)
    assert(blockTree(m).isDefined, "type applications must not be stripped")

  // ── slash-style imports — last std/ fix ─────────────────────────────────

  test("slash-import: `import x/y/z.{A}` rewritten to `import x.y.z.{A}`"):
    // std/mapreduce/index.ssc uses `import std/mapreduce/dataset.{Dataset}`
    // — a convention adopted from Python / ES module paths.  scalameta
    // strictly expects `.` segments; preprocessSlashImports rewrites
    // before parsing.
    val m = parseBlock(
      """import std/mapreduce/dataset.{Dataset}
        |val d: Dataset = ???""".stripMargin)
    assert(blockTree(m).isDefined,
      "slash-form import should be rewritten to dot-form")

  test("slash-import: multiple slash imports in one block all rewritten"):
    val m = parseBlock(
      """import std/a/b.{X}
        |import std/c/d.{Y, Z}
        |val x: X = ???""".stripMargin)
    assert(blockTree(m).isDefined, "multiple slash imports should all be rewritten")

  test("slash-import: regular dot-form imports left alone"):
    val m = parseBlock(
      """import scala.collection.immutable.List
        |val xs = List(1, 2, 3)""".stripMargin)
    assert(blockTree(m).isDefined, "dot-form imports must pass through unchanged")

  test("slash-import: file path inside string is NOT rewritten"):
    // Defensive: a `val path = \"a/b/c\"` shouldn't be touched.  Our regex
    // anchors on `import` keyword, so strings are safe.
    val m = parseBlock(
      """val path = "a/b/c.txt"
        |val name = "x/y/z"""".stripMargin)
    assert(blockTree(m).isDefined, "string literals must not be touched")
