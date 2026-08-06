package scalascript.uniml.dialect.scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*

/** An indented block inside parentheses ends at the closing paren.
  *
  * `f(x =>` + an indented body is common, and its last line ends with the `)` that closes the CALL.
  * A block bounded only by COLUMN tries to start a statement there. `parseBlock` has always had a
  * `stopAtParen` flag for exactly this; a lambda body passed it and an `if`/`then` branch did not,
  * so one level of nesting was enough to lose it — `scripts/smoke-ci.ssc`, a script that runs in CI
  * and therefore parses fine for the reference front, spent four diagnostics on it.
  */
final class SpikeParenBlockSpec extends AnyFunSuite:
  private val src = SourceId("memory:paren-block")

  private def clean(text: String): Unit =
    val r = UniML.parse(SourceInput.fromString(src, text), SpikeDialect)
    assert(r.diagnostics.isEmpty, s"expected a clean parse of:\n$text\ngot ${r.diagnostics.map(_.message)}")

  test("a branch block inside a parenthesised lambda") {
    clean("def f(): Unit =\n  xs.foreach(x =>\n    if x > 0 then\n      println(x))\n")
    clean("def f(): Unit =\n  xs.foreach(x =>\n    if x > 0 then\n      println(\"a\" +\n              \"b\"))\n")
    // else branch too, and two levels of nesting
    clean("def f(): Unit =\n  xs.foreach(x =>\n    if x > 0 then\n      println(x)\n    else\n      println(0))\n")
    clean("def f(): Unit =\n  xs.foreach(x =>\n    if x > 0 then\n      if x > 1 then\n        println(x))\n")
  }

  test("the shapes that already worked still do — this added a stop, it did not move one") {
    clean("def f(): Unit =\n  xs.foreach(x =>\n    println(x))\n")
    clean("def f(): Unit =\n  xs.foreach(x =>\n    val y = x\n    println(y))\n")
    clean("def f(): Unit =\n  xs.foreach { x =>\n    if x > 0 then\n      println(x)\n  }\n")
  }

  test("a branch block NOT inside parens still runs to its dedent") {
    // The control. `stopAtParen` is conditioned on parenDepth, so a top-level branch must be
    // unaffected — if this ever fails, the stop is firing where no paren is open and every
    // brace-free block just got shorter.
    clean("def f(x: Int): Int =\n  if x > 0 then\n    val a = 1\n    a\n  else\n    0\n")
    clean("def f(x: Int): Unit =\n  if x > 0 then\n    println(1)\n  println(2)\n")
  }

  test("a block that IS a group element ends at the comma that ends the element") {
    // Same class as the paren stop above, one token earlier: `vstack([ …, if c then a else b, … ])`
    // — the `,` after the else branch ends the list ELEMENT, but the branch is an offside block and
    // the comma sits at a column it still accepted, so it tried to parse a statement there
    // ("expected statement, found ','", examples/graph-fullstack.ssc:155). The reference front
    // parses that fence — it fails at RUNTIME on an undefined name — so this was a gap here.
    clean("def f(): Unit =\n  vstack([\n    if c then\n      a\n    else\n      b,\n    other\n  ])\n")
    clean("def f(): Unit =\n  g(1,\n    if c then\n      a\n    else\n      b,\n    3)\n")
  }

  test("the comma ENDS the element and the group keeps going — it does not end the group") {
    // The control that matters for a stop token: stopping too eagerly and stopping too late look
    // the same from a diagnostic count of zero. Two block-valued elements in one call must yield
    // TWO arguments — if the comma ended the whole group instead of the element, the second block
    // would be lost and this still parses clean.
    def argCountOf(text: String): List[Int] =
      val r = UniML.parse(SourceInput.fromString(src, text), SpikeDialect)
      assert(r.diagnostics.isEmpty, s"expected a clean parse of:\n$text\ngot ${r.diagnostics.map(_.message)}")
      r.roots.toList.flatMap(root => SpikeAst.walk(SpikeTyped.module(root))).collect {
        case c: SpikeAst.Apply => c.args.length
      }

    assert(argCountOf("def f(): Unit =\n  g(\n    if c then\n      a\n    else\n      b,\n    if d then\n      e\n    else\n      h)\n").contains(2))
    assert(argCountOf("def f(): Unit =\n  g(1,\n    if c then\n      a\n    else\n      b,\n    3)\n").contains(3))
    // and the one-element call is still one element, not zero
    assert(argCountOf("def f(): Unit =\n  g(\n    if c then\n      a\n    else\n      b)\n").contains(1))
  }

  test("Scala 3 comma-separated parents are CAPTURED, not merely tolerated") {
    // Two copies of the inheritance clause exist — `skipExtendsClause` erases, `captureExtendsClause`
    // records `td.parent` — and only the erasing one had grown the comma loop, so the capturing one
    // (the only one `parseTraitOrClassNoop` calls) still ended the declaration at the comma. A
    // no-diagnostic assertion would pass with the second parent silently DROPPED, hence the names.
    def parentsOf(text: String): List[String] =
      val r = UniML.parse(SourceInput.fromString(src, text), SpikeDialect)
      assert(r.diagnostics.isEmpty, s"expected a clean parse of:\n$text\ngot ${r.diagnostics.map(_.message)}")
      r.roots.toList.flatMap(root => SpikeTyped.module(root).decls).collect { case t: SpikeAst.TraitDecl => t.parents }.flatten.toList

    assert(parentsOf("trait A extends B, C\n") == List("B", "C"))
    assert(parentsOf("trait A[T[_]] extends B[T], C[T]\n") == List("B", "C"))
    assert(parentsOf("class A(x: Int) extends B, C:\n  def f(): Int = 1\n") == List("B", "C"))
    assert(parentsOf("trait A extends B with C\n") == List("B", "C"))  // the `with` spelling still works
    assert(parentsOf("trait A extends B\n") == List("B"))              // and a lone parent stays lone
  }
