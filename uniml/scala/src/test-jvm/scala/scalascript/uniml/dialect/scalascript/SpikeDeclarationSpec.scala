package scalascript.uniml.dialect.scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*

/** Two DECLARATION forms that carry no value: a by-name parameter and an abstract `val`.
  *
  * Both come from `v1/runtime/std/` — `http.ssc:89` and `geo.ssc:103` — which is standard library
  * and parses for the reference front by definition. The abstract val is the more instructive
  * failure: demanding `=` did not fail at the val, it consumed the REST OF THE FILE looking for
  * one and reported "expected '=', found '<eof>'". A diagnostic pointing at the end of a file
  * usually means something earlier decided to keep going.
  */
final class SpikeDeclarationSpec extends AnyFunSuite:
  private val src = SourceId("memory:decl")

  private def clean(text: String): Unit =
    val r = UniML.parse(SourceInput.fromString(src, text), SpikeDialect)
    assert(r.diagnostics.isEmpty, s"expected a clean parse of:\n$text\ngot ${r.diagnostics.map(_.message)}")

  test("a by-name parameter") {
    clean("extern def httpClient(baseUrl: String)(block: => Unit): Unit\n")
    clean("def once(body: => Int): Int = body\n")
    clean("def twice(a: Int, body: => Int): Int = body + body\n")
  }

  test("an abstract val in a class body") {
    clean("extern class WatchHandle:\n  def cancel(): Unit\n  val id: String\n")
    clean("trait T:\n  val name: String\n  val size: Int\n")
  }

  test("a val WITH a value still requires and keeps its RHS") {
    // The control. Making `=` optional could have made every val's RHS optional — a `val x = ` with
    // a missing RHS would then parse silently as a declaration, which is the shape of a typo the
    // parser should still refuse.
    clean("def f(): Int =\n  val x: Int = 1\n  x\n")
    clean("def f(): Int =\n  val x = 1\n  x\n")
    val r = UniML.parse(SourceInput.fromString(src, "def f(): Int =\n  val x: Int = 1\n  x\n"), SpikeDialect)
    def kinds(n: UniNode): Vector[String] = n match
      case b: UniNode.Branch => Vector(b.kind) ++ b.edges.flatMap(e => kinds(e.child))
      case _                 => Vector.empty
    val ks = r.roots.flatMap(kinds)
    assert(ks.contains("spike.val"), "a val with a value must stay a spike.val")
    assert(!ks.contains("spike.valdecl"), "a val with a value must NOT become a declaration")
  }
