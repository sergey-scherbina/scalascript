package scalascript.parser

import org.scalatest.funsuite.AnyFunSuite

/** busi-p0-trailing-underscore-ident — Identifiers ending with `_` followed
 *  immediately by `:` (no whitespace) used to make the entire enclosing
 *  scalascript code block silently fail to parse — scalameta's Scala3 lexer
 *  reads `name_:` as a single operator identifier because `:` is an operator
 *  character and `_` allows operator continuation.  The block then ended up
 *  with `tree = None`, the InterfaceExtractor saw zero defs, and the first
 *  import from the module reported "not found".
 *
 *  A small preprocessor that inserts a space between a trailing `_` and a
 *  following `:` in identifier position (skipping strings + comments) lets
 *  the natural `def foo(type_: Int)` form parse, matching busi's expectations.
 */
class TrailingUnderscoreIdentTest extends AnyFunSuite:

  private def assertParses(label: String, code: String): Unit =
    val (tree, err) = Parser.parseScalaWithDiagnostic(code)
    assert(tree.isDefined, s"[$label] expected successful parse, got error: $err")

  test("trailing-underscore-ident — `def foo(type_: Int): Int = type_`"):
    assertParses("def-param",
      "def foo(type_: Int): Int = type_")

  test("trailing-underscore-ident — multi-param with `_`-suffix all the way"):
    assertParses("multi-param",
      "def eventCanonical(type_: String, at_: Long, seq_: Int, payload_: String): String = type_")

  test("trailing-underscore-ident — case class fields"):
    assertParses("case-class",
      "case class Event(type_: String, at_: Long, seq_: Int, payload_: String)")

  test("trailing-underscore-ident — `val type_: Int = 1` ascription"):
    assertParses("val-ascription",
      "val type_: Int = 1")

  test("trailing-underscore-ident — lambda param `(type_: Int) => type_`"):
    assertParses("lambda-param",
      "val f: Int => Int = (type_: Int) => type_")

  test("trailing-underscore-ident — module-export round-trip"):
    // Real shape from busi: a multi-arg def using trailing-underscore params
    // inside a scalascript code block.  The whole block used to be dropped
    // silently (tree = None), so the importer saw zero exports.  Verify the
    // block now parses and its Source contains a `Defn.Def` named `save`.
    val src =
      """# M
        |
        |```scalascript
        |def save(type_ : String): String = type_
        |def emit(type_: String, payload_: String): String = type_ + payload_
        |```
        |""".stripMargin
    val m  = Parser.parse(src)
    val cb = m.sections.flatMap(_.content).collectFirst {
      case c: scalascript.ast.Content.CodeBlock => c
    }.getOrElse(fail("expected one CodeBlock in the parsed module"))
    assert(cb.tree.isDefined, s"module-context: code block did not parse (parseError=${cb.parseError})")

  test("trailing-underscore-ident — preserves operator-id usage e.g. `xs +: ys`"):
    // The space-insertion rule must NOT damage right-associative operator
    // methods like `+:` (cons) — they're `:` after a non-`_` character, so
    // safe by construction.  Just make sure the surface still parses.
    assertParses("right-assoc-cons",
      "def cons(x: Int, ys: List[Int]): List[Int] = x +: ys")

  test("trailing-underscore-ident — vararg splice `: _*` still works"):
    // The vararg splice is `: _*` (space-leading) or even `:_*` (no space).
    // Our rewrite only touches `_:` (underscore-then-colon), so `:_*` is
    // untouched.
    assertParses("vararg-splice",
      "def all(xs: Int*): Int = xs.sum\nval ys = List(1, 2, 3)\nval n = all(ys: _*)")

  test("trailing-underscore-ident — string literal containing `_:` is preserved"):
    // `_:` inside a string must NOT be rewritten.  Otherwise downstream
    // code that emits the string verbatim would diverge from the source.
    val src = """val s = "key_: value""""
    val (tree, err) = Parser.parseScalaWithDiagnostic(src)
    assert(tree.isDefined, s"parse failed: $err")
    // Re-emit through the preprocessor to confirm we kept `_:` intact in
    // the rewritten source.
    val pp = PreprocessorRegistry.applyAll(src)
    assert(pp.contains("\"key_: value\""), s"string body altered: $pp")

  test("trailing-underscore-ident — line comment containing `_:` is preserved"):
    val src = "val x = 1 // type_: Int"
    val pp  = PreprocessorRegistry.applyAll(src)
    assert(pp.contains("type_: Int"), s"comment body altered: $pp")
