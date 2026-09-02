package scalascript.uniml.dialect.scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*

/** A QUALIFIED type name is a type, and an ANONYMOUS `given` is consumed even though it models
  * nothing.
  *
  * These were the last two diagnostics in the tagged corpus, and they had one cause between them.
  * `captureType` took a single name and then knew only `[…]` and `=>`; it never consumed a DOT, so
  * `a.b.C[Book]` ended at `a` and everything after it was read as a new statement. And the
  * anonymous `given` branch returned a no-op that consumed NOTHING, so `given a.b.C[Book] = …` left
  * the cursor on `a` as well.
  *
  * Both are ordinary Scala and the reference front parses and runs them — checked before either was
  * touched, since a construct the language does not have is not a gap.
  *
  * The anonymous given stays SEMANTICALLY a no-op. The projection documents `spike.sealed` as what
  * an anonymous given produces because it "genuinely carries nothing", and a name-less
  * `spike.given` would hand the typed AST a node whose `given.name` is absent. What changed is only
  * that the construct is now EATEN — which is what a lossless parser owes a form it does not model.
  */
final class SpikeGivenSpec extends AnyFunSuite:
  private val src = SourceId("memory:given")

  private def clean(text: String): ParseResult =
    val r = UniML.parse(SourceInput.fromString(src, text), SpikeDialect)
    assert(r.diagnostics.isEmpty, s"expected a clean parse of:\n$text\ngot ${r.diagnostics.map(_.message)}")
    r

  private def kinds(text: String): Vector[String] =
    def go(n: UniNode): Vector[String] = n match
      case b: UniNode.Branch => Vector(b.kind) ++ b.edges.flatMap(e => go(e.child))
      case _                 => Vector.empty
    clean(text).roots.flatMap(go)

  test("a qualified type name is a type — every position, not just `given`") {
    clean("given a.b.C[Book] = a.b.C.derived\n")
    clean("given bookCodec: a.b.C[Book] = a.b.C.derived\n")
    clean("def f(): a.b.C[Int] = g()\n")
    clean("val v: a.b.C[Int] = g()\n")
    clean("def h(x: a.b.C[Int]): Int = 1\n")
    clean("def deep(): a.b.c.d.E[F[G]] = q()\n")
  }

  test("the anonymous `given` is CONSUMED — and still models nothing") {
    // Both halves matter. Consuming it is the fix; staying a no-op is the contract the typed
    // projection documents, and a `spike.given` here would carry no `given.name`.
    clean("given C[Book] = C.derived\n")
    clean("given C = C.derived\n")
    clean("given a.b.C[Book] = a.b.C.derived\n")
    val ks = kinds("given a.b.C[Book] = a.b.C.derived\n")
    assert(ks.contains("spike.sealed"), s"the anonymous given stopped being a no-op: $ks")
    assert(!ks.contains("spike.given"), s"the anonymous given gained a name-less given node: $ks")
  }

  test("a NAMED given still projects as a given, with its body") {
    val ks = kinds("given bookCodec: a.b.C[Book] = a.b.C.derived\n")
    assert(ks.contains("spike.given"), s"the named given lost its node: $ks")
  }

  test("a NAMED `given … with` keeps its members; the ANONYMOUS one now models the same node") {
    val named = kinds("given bc: C[Book] with\n  def one(): Int = 1\n")
    assert(named.contains("spike.givenobj"), s"the named `with` lost its node: $named")

    // This assertion used to be INVERTED — "the anonymous `with` models nothing" — pinning an open
    // question: "whether those members ought to reach the typed AST … if the shape below ever
    // changes, that is the question to settle first." Settled 2026-09-02:
    // `kr-summon-anonymous-given` put the idiomatic spelling in the corpus, and BOTH v3 fronts now
    // model it as a name-less given-object whose consumers synthesize fsub's canonical name
    // (v3/BUGS.md `v3-front-does-not-parse-an-anonymous-given`). What the old guard actually
    // stood against — members swallowed into a contentless node — is asserted directly below.
    val r = UniML.parse(SourceInput.fromString(src,
      "given C[Book] with\n  def one(): Int = 1\n"), SpikeDialect)
    assert(r.diagnostics.isEmpty, s"it stopped parsing: ${r.diagnostics.map(_.message)}")
    val anon = kinds("given C[Book] with\n  def one(): Int = 1\n")
    assert(anon.contains("spike.givenobj"),
      s"the anonymous `with` lost its given-object node: $anon")
    assert(anon.contains("spike.def"),
      s"the anonymous instance's members were swallowed — the exact drop the old guard stood against: $anon")
  }

  test("a dot that is NOT part of a type is left alone") {
    // The control for the dot rule. `captureType` runs at the type position only, but a type is
    // followed by `=` and then an EXPRESSION full of dots — if the rule leaked past the type, the
    // right-hand side would be eaten and the binding would lose its value.
    val ks = kinds("val v: C = a.b.c\n")
    assert(ks.contains("spike.val"), s"the val disappeared: $ks")
    clean("def f(): Int = a.b.c()\n")
    clean("val w: a.b.C = x.y.z\n")
    // and a dot with no name after it is not a type segment
    clean("def g(): Int =\n  xs.map(_.length).sum\n")
  }
