package scalascript.artifact

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

/** v2.0 Stage 5.6+ — `InterfaceExtractor` populates `ExportedSymbol.nested`
 *  for top-level `Defn.Object` exports by walking the object's body stats.
 *
 *  This unlocks strict-mode deep-Select validation against real `.scim`
 *  artifacts: `pkg.Foo.bar` can now be checked because `Foo`'s nested list
 *  carries an entry for `bar`.  Until this landed, sub-namespaces with
 *  empty `nested` fell back to permissive behaviour in the typer.
 */
class InterfaceExtractorNestedTest extends AnyFunSuite:

  /** Build a single-block scalascript module from a code body. */
  private def moduleOf(scalascriptSource: String): scalascript.ast.Module =
    val withFence =
      s"""# Test
         |
         |```scalascript
         |$scalascriptSource
         |```
         |""".stripMargin
    Parser.parse(withFence)

  private def extract(scalascriptSource: String) =
    val m   = moduleOf(scalascriptSource)
    val raw = scalascriptSource.getBytes("UTF-8")
    InterfaceExtractor.extract(m, raw)

  private def extractWithFrontMatter(frontMatter: String, scalascriptSource: String) =
    val full =
      s"""---
         |$frontMatter
         |---
         |
         |# Test
         |
         |```scalascript
         |$scalascriptSource
         |```
         |""".stripMargin
    val m   = Parser.parse(full)
    val raw = full.getBytes("UTF-8")
    InterfaceExtractor.extract(m, raw)

  // ── 1. Single-level nested: object Foo: def bar ───────────────────────────

  test("nested — `object Foo: def bar` populates Foo.nested = [bar]"):
    val iface = extract(
      """object Foo:
        |  def bar(): Int = 42""".stripMargin
    )
    val foo = iface.exports.find(_.name == "Foo").getOrElse(
      fail(s"expected `Foo` in exports, got: ${iface.exports.map(_.name)}"))
    assert(foo.kind == "object", s"expected kind=object, got: ${foo.kind}")
    assert(foo.nested.map(_.name) == List("bar"),
      s"expected Foo.nested = [bar], got: ${foo.nested.map(_.name)}")
    val bar = foo.nested.head
    assert(bar.name == "bar", s"expected name=bar, got: ${bar.name}")
    assert(bar.fqn  == "Foo_bar", s"expected fqn=Foo_bar, got: ${bar.fqn}")
    assert(bar.kind == "def", s"expected kind=def, got: ${bar.kind}")

  // ── 2. Two-level nesting: object Foo: object Bar: def baz ─────────────────

  test("nested — two-level: `object Foo: object Bar: def baz` populates Foo→Bar→baz"):
    val iface = extract(
      """object Foo:
        |  object Bar:
        |    def baz(): String = "x"""".stripMargin
    )
    val foo = iface.exports.find(_.name == "Foo").getOrElse(
      fail(s"expected `Foo` in exports, got: ${iface.exports.map(_.name)}"))
    assert(foo.nested.map(_.name) == List("Bar"),
      s"expected Foo.nested = [Bar], got: ${foo.nested.map(_.name)}")
    val bar = foo.nested.head
    assert(bar.kind == "object", s"expected Bar.kind=object, got: ${bar.kind}")
    assert(bar.fqn  == "Foo_Bar", s"expected Bar.fqn=Foo_Bar, got: ${bar.fqn}")
    assert(bar.nested.map(_.name) == List("baz"),
      s"expected Bar.nested = [baz], got: ${bar.nested.map(_.name)}")
    val baz = bar.nested.head
    assert(baz.fqn == "Foo_Bar_baz", s"expected baz.fqn=Foo_Bar_baz, got: ${baz.fqn}")
    assert(baz.kind == "def", s"expected baz.kind=def, got: ${baz.kind}")

  // ── 3. Multiple members per object ────────────────────────────────────────

  test("nested — multiple members: `object Foo: { def a; val b; class C }` are all recorded"):
    val iface = extract(
      """object Foo:
        |  def a(): Int = 1
        |  val b: Int = 2
        |  class C(x: Int)""".stripMargin
    )
    val foo = iface.exports.find(_.name == "Foo").getOrElse(
      fail(s"expected `Foo` in exports, got: ${iface.exports.map(_.name)}"))
    val nestedNames = foo.nested.map(_.name).toSet
    assert(nestedNames == Set("a", "b", "C"),
      s"expected Foo.nested names = {a, b, C}, got: $nestedNames")
    val byName = foo.nested.map(s => s.name -> s).toMap
    assert(byName("a").kind == "def", s"expected a.kind=def, got: ${byName("a").kind}")
    assert(byName("b").kind == "val", s"expected b.kind=val, got: ${byName("b").kind}")
    assert(byName("C").kind == "class", s"expected C.kind=class, got: ${byName("C").kind}")
    // FQNs use the parent name as prefix.
    assert(byName("a").fqn == "Foo_a", s"expected a.fqn=Foo_a, got: ${byName("a").fqn}")
    assert(byName("b").fqn == "Foo_b", s"expected b.fqn=Foo_b, got: ${byName("b").fqn}")
    assert(byName("C").fqn == "Foo_C", s"expected C.fqn=Foo_C, got: ${byName("C").fqn}")

  // ── 4. Package-wrapped: nested inside `object std: object dsl:` shell ─────

  test("nested — package-wrapped `case class Doc` still produces Doc under FQN std_dsl_Doc"):
    // `package: std.dsl` wraps the body in `object std: object dsl: …`; the
    // extractor strips that shell so `Doc` appears at the top of `exports`,
    // not the synthetic `std`/`dsl` objects.  This pins down the existing
    // contract: nested-extraction must not regress the package-walk
    // behaviour established in Stage 5.2.
    val iface = extractWithFrontMatter(
      frontMatter = "package: std.dsl",
      scalascriptSource = """case class Doc(text: String)"""
    )
    val names = iface.exports.map(_.name).toSet
    assert(names.contains("Doc"),
      s"expected `Doc` in exports, got: $names")
    assert(!names.contains("std") && !names.contains("dsl"),
      s"synthetic shell objects must NOT appear in exports, got: $names")
    val doc = iface.exports.find(_.name == "Doc").get
    assert(doc.fqn == "std_dsl_Doc",
      s"expected FQN std_dsl_Doc, got: ${doc.fqn}")
    assert(doc.kind == "class", s"expected kind=class, got: ${doc.kind}")

  // ── 5. Package-wrapped object: nested members are populated ───────────────

  test("nested — package-wrapped `object Helpers: def fmt` populates Helpers.nested"):
    // Combines two features: the package-shell unwrap (Stage 5.2) and
    // nested-object extraction (this stage).  Helpers' nested list must
    // carry `fmt` so a strict-mode deep-Select on `std_dsl.Helpers.fmt`
    // can validate against the .scim.
    val iface = extractWithFrontMatter(
      frontMatter = "package: std.dsl",
      scalascriptSource =
        """object Helpers:
          |  def fmt(s: String): String = s""".stripMargin
    )
    val helpers = iface.exports.find(_.name == "Helpers").getOrElse(
      fail(s"expected `Helpers` in exports, got: ${iface.exports.map(_.name)}"))
    assert(helpers.fqn == "std_dsl_Helpers",
      s"expected Helpers.fqn=std_dsl_Helpers, got: ${helpers.fqn}")
    assert(helpers.nested.map(_.name) == List("fmt"),
      s"expected Helpers.nested = [fmt], got: ${helpers.nested.map(_.name)}")
    val fmt = helpers.nested.head
    assert(fmt.fqn  == "std_dsl_Helpers_fmt",
      s"expected fmt.fqn=std_dsl_Helpers_fmt, got: ${fmt.fqn}")
    assert(fmt.kind == "def", s"expected fmt.kind=def, got: ${fmt.kind}")

  // ── 6. Backward-compat: flat top-level defs are unaffected ────────────────

  test("nested — flat top-level `def` / `val` outside any object: nested stays empty"):
    val iface = extract(
      """def topDef(): Int = 1
        |val topVal: Int = 2""".stripMargin
    )
    iface.exports.foreach { s =>
      assert(s.nested.isEmpty,
        s"expected leaf symbol ${s.name} to have empty nested, got: ${s.nested}")
    }

  // ── 7. Depth limit — beyond MaxNestedDepth (3) falls back to empty ────────

  test("nested — depth limit: 4-deep `object A: object B: object C: object D: def x` truncates at D"):
    // Depth 1 = A, 2 = B, 3 = C, 4 = D.  At depth 3 (C), the recursion
    // refuses to descend further, so C's nested stays empty even though
    // D is present in source.  This is the documented cap; lift via
    // MaxNestedDepth when needed.
    val iface = extract(
      """object A:
        |  object B:
        |    object C:
        |      object D:
        |        def x(): Int = 0""".stripMargin
    )
    val a = iface.exports.find(_.name == "A").getOrElse(
      fail(s"expected `A`, got: ${iface.exports.map(_.name)}"))
    val b = a.nested.find(_.name == "B").getOrElse(fail(s"missing B"))
    val c = b.nested.find(_.name == "C").getOrElse(fail(s"missing C"))
    // C is at depth 3 — recursion stops here; D is not recorded.
    assert(c.nested.isEmpty,
      s"expected C.nested to be empty at the depth cap, got: ${c.nested.map(_.name)}")
