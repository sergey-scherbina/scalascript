package scalascript.parser

import org.scalatest.funsuite.AnyFunSuite

/** `effect E { … }` — the braced spelling, which did not parse at all: `effectLinePat` requires a
 *  trailing `:`, so the declaration was left untouched and `multi` survived as a bare identifier,
 *  reporting `Undefined: multi` — a message about the wrong word
 *  (BUGS.md `multi-effect-in-braces-does-not-parse`).
 *
 *  The normaliser is deliberately NARROW, and the last two tests are what make that a rule rather
 *  than an accident: a shape it does not cover must come out BYTE-IDENTICAL, so nothing that works
 *  today can start failing. */
class EffectBracesTest extends AnyFunSuite:

  test("a braced effect becomes the colon form the rewriter knows"):
    val out = Parser.preprocessEffectBraces("multi effect E {\n  def op(x: Int): Int\n}\n")
    assert(out.contains("multi effect E:"), out)
    assert(!out.contains("{"), out)
    assert(out.contains("def op(x: Int): Int"), out)

  test("the multi marker survives the whole chain, so the effect is multi-shot"):
    val out = Parser.preprocessEffects(
      Parser.preprocessEffectBraces("multi effect E {\n  def op(x: Int): Int\n}\n"))
    assert(out.contains("val __multiShot__ = true"), out)

  test("a plain (non-multi) braced effect gets no multi marker"):
    val out = Parser.preprocessEffects(
      Parser.preprocessEffectBraces("effect E {\n  def op(x: Int): Int\n}\n"))
    assert(out.contains("object E {"), out)
    assert(!out.contains("__multiShot__"), out)

  test("a one-line braced body is NOT rewritten — left exactly as it is today"):
    val src = "effect E { def op(): Int }\n"
    assert(Parser.preprocessEffectBraces(src) == src)

  test("an unclosed brace is NOT rewritten — left exactly as it is today"):
    val src = "effect E {\n  def op(): Int\n"
    assert(Parser.preprocessEffectBraces(src) == src)

  test("source with no effect at all is returned unchanged"):
    val src = "object A { val x = 1 }\n"
    assert(Parser.preprocessEffectBraces(src) == src)
