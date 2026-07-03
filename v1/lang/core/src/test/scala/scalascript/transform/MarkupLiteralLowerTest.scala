package scalascript.transform

import org.scalatest.funsuite.AnyFunSuite

/** v1.55.5 — element-literal lowering.
 *
 *  Tests for `MarkupLiteralLower.lowerSource` directly, verifying that:
 *    - Self-closing elements with no attributes → `Markup.Element(QName.local("name"), Nil, Nil)`
 *    - Self-closing elements with string attrs  → `List(Attr(...))`
 *    - Self-closing elements with expr attrs    → `Attr(..., (expr).toString)`
 *    - Open/close elements with text children   → `Markup.Text("...")`
 *    - Nested elements recurse correctly
 *    - Namespaced tags produce `QName.prefixed(...)` calls
 *    - Source without the markup import is left unchanged
 *    - Only scalascript blocks are lowered (xml/other lang blocks untouched)
 *    - Multiple elements in one source are all lowered
 *    - The `lowerSource` helper is stable on plain Scala code
 */
class MarkupLiteralLowerTest extends AnyFunSuite:

  // ─── helper — lowerSource only (no parser round-trip) ─────────────────────

  /** Run only the source-text rewriter, which requires the import sentinel. */
  private def lower(src: String): String =
    MarkupLiteralLower.lowerSource(src)

  // ─── 1. Self-closing, no attributes ──────────────────────────────────────

  test("self-closing element no attrs"):
    val src    = "import scalascript.markup.*\nval x = <br/>"
    val result = lower(src)
    assert(result.contains("""Markup.Element(QName.local("br"), Nil, Nil)"""),
      s"unexpected: $result")

  test("self-closing element no attrs — plain name"):
    val result = lower("import scalascript.markup.*\nval x = <root/>")
    assert(result.contains("""Markup.Element(QName.local("root"), Nil, Nil)"""),
      s"unexpected: $result")

  // ─── 2. Self-closing with string attribute ────────────────────────────────

  test("self-closing element with quoted string attribute"):
    val result = lower("""import scalascript.markup.*
val x = <img src="foo.png"/>""")
    assert(result.contains("""Attr(QName.local("src"), "foo.png")"""),
      s"unexpected: $result")
    assert(result.contains("""Markup.Element(QName.local("img"),"""),
      s"unexpected: $result")

  // ─── 3. Self-closing with expression attribute ────────────────────────────

  test("self-closing element with braced expression attribute"):
    val result = lower("import scalascript.markup.*\nval x = <a href={url}/>")
    assert(result.contains("(url).toString"),
      s"expected expr.toString, got: $result")
    assert(result.contains("""Attr(QName.local("href"),"""),
      s"unexpected: $result")

  test("self-closing element with complex expression attribute"):
    val result = lower("import scalascript.markup.*\nval x = <a href={base + path}/>")
    assert(result.contains("(base + path).toString"),
      s"expected expr.toString for complex expr, got: $result")

  // ─── 4. Open/close element with text child ────────────────────────────────

  test("open/close element with text content"):
    val result = lower("""import scalascript.markup.*
val x = <p>hello world</p>""")
    assert(result.contains("""Markup.Text("hello world")"""),
      s"unexpected: $result")
    assert(result.contains("""Markup.Element(QName.local("p"),"""),
      s"unexpected: $result")

  // ─── 5. Open/close element with no children ──────────────────────────────

  test("open/close element with no children"):
    val result = lower("import scalascript.markup.*\nval x = <div></div>")
    assert(result.contains("""Markup.Element(QName.local("div"), Nil, Nil)"""),
      s"unexpected: $result")

  // ─── 6. Nested elements ───────────────────────────────────────────────────

  test("nested element"):
    val result = lower("import scalascript.markup.*\nval x = <ul><li/></ul>")
    assert(result.contains("""Markup.Element(QName.local("li"), Nil, Nil)"""),
      s"expected nested li, got: $result")
    assert(result.contains("""Markup.Element(QName.local("ul"),"""),
      s"expected outer ul, got: $result")

  test("deeply nested elements"):
    val result = lower("import scalascript.markup.*\nval x = <a><b><c/></b></a>")
    assert(result.contains("""Markup.Element(QName.local("c"), Nil, Nil)"""),
      s"expected c element, got: $result")
    assert(result.contains("""Markup.Element(QName.local("b"),"""),
      s"expected b element, got: $result")
    assert(result.contains("""Markup.Element(QName.local("a"),"""),
      s"expected a element, got: $result")

  // ─── 7. Namespaced tags ───────────────────────────────────────────────────

  test("namespaced self-closing element"):
    val result = lower("import scalascript.markup.*\nval x = <svg:rect/>")
    assert(result.contains("""QName.prefixed("svg", "rect")"""),
      s"expected prefixed QName, got: $result")

  test("namespaced open/close element"):
    val result = lower("import scalascript.markup.*\nval x = <ns:root></ns:root>")
    assert(result.contains("""QName.prefixed("ns", "root")"""),
      s"expected prefixed QName, got: $result")

  // ─── 8. Module-level: no import → block source unchanged ────────────────

  test("module block without markup import is not lowered"):
    import scalascript.parser.Parser
    val src =
      """# Test
        |
        |```scalascript
        |val x = 42
        |```
        |""".stripMargin
    val module = Parser.parse(src)
    val cb = module.sections.flatMap(_.content).collectFirst {
      case cb: scalascript.ast.Content.CodeBlock => cb
    }.get
    // No markup import → source is not rewritten
    assert(!cb.source.contains("Markup.Element"),
      s"expected no lowering without import, got:\n${cb.source}")

  // ─── 9. Multiple elements in one source ──────────────────────────────────

  test("multiple elements in one source all lowered"):
    val result = lower("import scalascript.markup.*\nval x = <div/>\nval y = <span/>")
    assert(result.contains("""Markup.Element(QName.local("div"), Nil, Nil)"""),
      s"expected div lowered, got: $result")
    assert(result.contains("""Markup.Element(QName.local("span"), Nil, Nil)"""),
      s"expected span lowered, got: $result")

  // ─── 10. Element with multiple attributes ────────────────────────────────

  test("element with multiple attributes"):
    val result = lower("""import scalascript.markup.*
val x = <input type="text" value={v}/>""")
    assert(result.contains("""Attr(QName.local("type"), "text")"""),
      s"expected type attr, got: $result")
    assert(result.contains("""Attr(QName.local("value"), (v).toString)"""),
      s"expected value attr, got: $result")

  // ─── 11. Plain Scala code is stable ──────────────────────────────────────

  test("plain Scala code with markup import but no elements is unchanged"):
    val src = "import scalascript.markup.*\nval x = 1 + 2"
    val result = lower(src)
    assert(result == src, s"expected unchanged Scala code, got: $result")

  // ─── 12. Module-level integration via ast.Module ─────────────────────────

  test("lower(module) rewrites scalascript blocks with markup import"):
    import scalascript.parser.Parser
    val src =
      """# Test
        |
        |```scalascript
        |import scalascript.markup.*
        |val x = <br/>
        |```
        |""".stripMargin
    val module = Parser.parse(src)
    // The parser already calls MarkupLiteralLower.lower, so we inspect source
    val cb = module.sections.flatMap(_.content).collectFirst {
      case cb: scalascript.ast.Content.CodeBlock => cb
    }.get
    assert(cb.source.contains("Markup.Element"),
      s"expected element lowered in code block source, got:\n${cb.source}")
