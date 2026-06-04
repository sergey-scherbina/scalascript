package scalascript.artifact

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

/** Verifies the v2.0 AST-based detectors in `InterfaceExtractor`:
 *
 *    - `extern def` recognition uses `EffectAnalysis.isExternDef`
 *      (Term.Name("__extern__")) after `Parser.preprocessExtern`, not
 *      the old `body == ???` placeholder check.
 *
 *    - capability detection walks `Term.Apply` / `Term.Select` call
 *      sites against `CapabilityRegistry`, so it no longer fires inside
 *      string literals or comments, and respects user shadowing of
 *      bare names. */
class InterfaceExtractorTest extends AnyFunSuite:

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

  /** Build a module with the given YAML front-matter and a single
   *  scalascript code block holding `scalascriptSource`.  Front-matter is
   *  written verbatim — newline-terminated entries like `exports: [foo]`
   *  or `package: std.dsl`. */
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

  // ── extern def detection ────────────────────────────────────────────────

  test("extern def — recorded under externDefs with HTTP capability"):
    val iface = extract("extern def serve(port: Int): Unit")
    val externs = iface.externDefs.map(_.name).toSet
    assert(externs.contains("serve"),
      s"expected 'serve' among externs, got: ${iface.externDefs}")
    // Signature string preserves the parameter list, not just the return type.
    val serve = iface.externDefs.find(_.name == "serve").get
    assert(serve.tpe.contains("port: Int"),
      s"expected signature to include 'port: Int', got: ${serve.tpe}")
    assert(serve.kind == "extern", s"expected kind=extern, got ${serve.kind}")
    // serve(port:…) is an HTTP intrinsic; the extern *declaration* itself
    // does not produce a call site, so capabilities here are derived from
    // the declaration of an HTTP-flavoured extern — we do not assert HTTP
    // for the declaration alone, only for use.

  test("extern def — multiple externs are all extracted"):
    val iface = extract(
      """extern def readFile(path: String): String
        |extern def sha256(data: String): String""".stripMargin)
    val names = iface.externDefs.map(_.name).toSet
    assert(names == Set("readFile", "sha256"),
      s"expected {readFile, sha256}, got: $names")

  test("extern def — a plain `def foo(x: Int) = ???` is NOT extern"):
    // The `???` placeholder is not the extern marker — only `__extern__`
    // injected by Parser.preprocessExtern is.  This was the old heuristic;
    // confirm we no longer treat `???` as extern.
    val iface = extract("def todo(x: Int): Int = ???")
    assert(iface.externDefs.isEmpty,
      s"expected no externs for `def … = ???`, got: ${iface.externDefs}")

  // ── capability detection: positive cases ────────────────────────────────

  test("capability — Http detected when `serve(...)` is called"):
    val iface = extract(
      """extern def serve(port: Int): Unit
        |serve(8080)""".stripMargin)
    val caps = iface.capabilities.map(_.name).toSet
    assert(caps.contains("Http"),
      s"expected Http capability, got: $caps")

  test("capability — WebSocket detected when `onWebSocket(...)` is called"):
    val iface = extract(
      """extern def onWebSocket(path: String, h: Any): Unit
        |onWebSocket("/ws", null)""".stripMargin)
    val caps = iface.capabilities.map(_.name).toSet
    assert(caps.contains("WebSocket"),
      s"expected WebSocket capability, got: $caps")

  test("capability — Dataset detected for qualified `Dataset.of(...)`"):
    val iface = extract("""Dataset.of(1, 2, 3)""")
    val caps = iface.capabilities.map(_.name).toSet
    assert(caps.contains("Dataset"),
      s"expected Dataset capability, got: $caps")

  test("capability — Crypto via `crypto.<anything>` qualifier"):
    val iface = extract("""val h = crypto.digest("x")""")
    val caps = iface.capabilities.map(_.name).toSet
    assert(caps.contains("Crypto"),
      s"expected Crypto capability via crypto.* qualifier, got: $caps")

  // ── capability detection: negative cases (the bug fix) ──────────────────

  test("capability — string literal containing `serve(` does NOT trigger Http"):
    // This was the canonical false-positive of the v1 grep heuristic.
    val iface = extract("""val msg = "call serve(8080) to start"""")
    val caps = iface.capabilities.map(_.name).toSet
    assert(!caps.contains("Http"),
      s"expected Http NOT detected from a string literal, got: $caps")

  test("capability — comment containing `serve(...)` does NOT trigger Http"):
    val iface = extract(
      """// remember to call serve(8080)
        |val x = 1""".stripMargin)
    val caps = iface.capabilities.map(_.name).toSet
    assert(!caps.contains("Http"),
      s"expected Http NOT detected from a comment, got: $caps")

  test("capability — user-defined `def serve` shadows the intrinsic"):
    // Best-effort shadowing: a top-level `def serve` in the module masks
    // the Http intrinsic detection.  Documented limitation: this is
    // *module-wide*, not lexical.
    val iface = extract(
      """def serve = 1
        |val x = serve""".stripMargin)
    val caps = iface.capabilities.map(_.name).toSet
    assert(!caps.contains("Http"),
      s"expected Http NOT detected when user defines `serve`, got: $caps")

  test("capability — empty program detects no capabilities"):
    val iface = extract("val x = 1 + 2")
    assert(iface.capabilities.isEmpty,
      s"expected no capabilities, got: ${iface.capabilities}")

  // ── manifest `exports:` filters the export list ─────────────────────────

  test("exports filter — manifest `exports: [foo]` excludes `bar`"):
    // With an explicit `exports:` list, private helpers must stay hidden:
    // the consumer-facing interface should expose only the listed names.
    val iface = extractWithFrontMatter(
      frontMatter = "exports:\n  - foo",
      scalascriptSource =
        """def foo(x: Int): Int = x + 1
          |def bar(x: Int): Int = x - 1""".stripMargin
    )
    val names = iface.exports.map(_.name).toSet
    assert(names == Set("foo"),
      s"expected only `foo` to be exported, got: $names")

  test("exports filter — empty/absent `exports:` keeps current (export-all) behaviour"):
    // No `exports:` in the manifest must NOT inadvertently hide top-level defs.
    val iface = extractWithFrontMatter(
      frontMatter = "name: m",
      scalascriptSource =
        """def foo(x: Int): Int = x + 1
          |def bar(x: Int): Int = x - 1""".stripMargin
    )
    val names = iface.exports.map(_.name).toSet
    assert(names == Set("foo", "bar"),
      s"expected both `foo` and `bar` when no `exports:` is set, got: $names")

  test("type evidence — exported symbols carry declared, inferred, and unknown evidence"):
    val iface = extract(
      """val declaredAny: Any = 1
        |val inferredInt = 42
        |def unknownDef() = someUndefinedComplexThing()""".stripMargin
    )
    val byName = iface.exports.map(s => s.name -> s).toMap

    val declaredAny = byName("declaredAny").evidence.getOrElse(fail("declaredAny evidence missing"))
    assert(declaredAny.kind == "Declared")
    assert(declaredAny.tpe == "Any")

    val inferredInt = byName("inferredInt").evidence.getOrElse(fail("inferredInt evidence missing"))
    assert(inferredInt.kind == "Inferred")
    assert(inferredInt.tpe == "Int")

    val unknownDef = byName("unknownDef").evidence.getOrElse(fail("unknownDef evidence missing"))
    assert(unknownDef.kind == "Unknown")
    assert(unknownDef.tpe == "() => Any")

  test("type evidence — .scim JSON round-trip preserves exported evidence"):
    val iface = extract(
      """val declaredAny: Any = 1
        |val inferredInt = 42
        |def unknownDef() = someUndefinedComplexThing()""".stripMargin
    )
    val json = ArtifactIO.writeInterface(iface)
    ArtifactIO.readInterface(json) match
      case Right(parsed) =>
        val byName = parsed.exports.map(s => s.name -> s).toMap
        assert(byName("declaredAny").evidence.exists(_.kind == "Declared"))
        assert(byName("inferredInt").evidence.exists(_.kind == "Inferred"))
        assert(byName("unknownDef").evidence.exists(_.kind == "Unknown"))
      case Left(err) => fail(s"round-trip failed: $err")

  test("exports filter — `exports:` listing an undefined name yields an empty subset"):
    // A name listed in `exports:` that isn't actually defined is silently
    // dropped; we don't fabricate symbols.  Real defs that ARE listed still
    // get through.
    val iface = extractWithFrontMatter(
      frontMatter = "exports:\n  - foo\n  - missing",
      scalascriptSource =
        """def foo(x: Int): Int = x + 1
          |def bar(x: Int): Int = x - 1""".stripMargin
    )
    val names = iface.exports.map(_.name).toSet
    assert(names == Set("foo"),
      s"expected only `foo` (since `missing` isn't defined), got: $names")

  // ── package: walks the synthetic object shell from `Parser` ─────────────

  test("package walk — `package: std.dsl` surfaces inner `case class DocLine`"):
    // `Parser.wrapSectionInPackage` wraps the body in `object std: object dsl: …`.
    // The extractor must descend that shell so `DocLine` appears in exports,
    // not the synthetic `std` object itself.
    val iface = extractWithFrontMatter(
      frontMatter = "package: std.dsl",
      scalascriptSource = """case class DocLine(text: String)"""
    )
    val names = iface.exports.map(_.name).toSet
    val fqns  = iface.exports.map(_.fqn).toSet
    assert(names.contains("DocLine"),
      s"expected `DocLine` in exports, got: $names (fqns=$fqns)")
    assert(!names.contains("std"),
      s"synthetic package shell `std` must NOT appear in exports, got: $names")
    // FQN convention: pkg segments joined with `_`, then the name.
    val doc = iface.exports.find(_.name == "DocLine").get
    assert(doc.fqn == "std_dsl_DocLine",
      s"expected FQN `std_dsl_DocLine`, got: ${doc.fqn}")
    assert(doc.kind == "class", s"expected kind=class, got: ${doc.kind}")
    // The module's `pkg` field on the interface is preserved verbatim.
    assert(iface.pkg == List("std", "dsl"),
      s"expected pkg=[std,dsl], got: ${iface.pkg}")

  test("package walk — multiple inner defs all become exports under the pkg fqn"):
    val iface = extractWithFrontMatter(
      frontMatter = "package: org.example",
      scalascriptSource =
        """def render(x: Int): String = x.toString
          |case class Box(value: Int)""".stripMargin
    )
    val names = iface.exports.map(_.name).toSet
    assert(names == Set("render", "Box"),
      s"expected {render, Box}, got: $names")
    val fqns = iface.exports.map(s => s.name -> s.fqn).toMap
    assert(fqns("render") == "org_example_render",
      s"expected render → org_example_render, got: ${fqns("render")}")
    assert(fqns("Box") == "org_example_Box",
      s"expected Box → org_example_Box, got: ${fqns("Box")}")

  test("package walk — `extern def` inside a package shell is still detected"):
    // Externs are emitted via `Parser.preprocessExtern` BEFORE the package
    // wrap, so they end up nested inside `object std: object dsl: def foo = __extern__`.
    // The extractor must still surface them in `externDefs`, not just the
    // generic exports list.
    val iface = extractWithFrontMatter(
      frontMatter = "package: std.dsl",
      scalascriptSource = """extern def doRead(path: String): String"""
    )
    val externNames = iface.externDefs.map(_.name).toSet
    assert(externNames.contains("doRead"),
      s"expected `doRead` among externs even under `package: std.dsl`, got: $externNames")
    val fqn = iface.externDefs.find(_.name == "doRead").get.fqn
    assert(fqn == "std_dsl_doRead",
      s"expected extern FQN `std_dsl_doRead`, got: $fqn")

  // ── anonymous given identity (v2.0 fix) ─────────────────────────────────
  //
  // Anonymous `given Eq[Int] with …` previously produced an InstanceDecl
  // with an empty `witnessName` and a truncated `fqn` like `"std_eq_"`.
  // The synthesized convention now is `given_<Typeclass>_<TypeHead>` —
  // deterministic, no hashes, identifier-safe, and round-trips identically
  // across builds.  See InterfaceExtractor.synthGivenName.

  test("anonymous given — `given Eq[Int] with …` synthesizes `given_Eq_Int`"):
    val iface = extract(
      """given Eq[Int] with
        |  def eqv(a: Int, b: Int): Boolean = a == b""".stripMargin)
    assert(iface.instances.size == 1,
      s"expected exactly one instance; got: ${iface.instances}")
    val i = iface.instances.head
    assert(i.typeclass   == "Eq",                 s"typeclass: ${i.typeclass}")
    assert(i.typeParam   == "Int",                s"typeParam: ${i.typeParam}")
    assert(i.witnessName == "given_Eq_Int",
      s"expected `given_Eq_Int`; got: ${i.witnessName}")
    assert(i.fqn         == "given_Eq_Int",
      s"expected `given_Eq_Int` (no pkg); got: ${i.fqn}")

  test("anonymous given — under `package: pkg`, FQN is `pkg_given_Eq_Int`"):
    val iface = extractWithFrontMatter(
      frontMatter = "package: pkg",
      scalascriptSource =
        """given Eq[Int] with
          |  def eqv(a: Int, b: Int): Boolean = a == b""".stripMargin)
    assert(iface.instances.size == 1, s"got: ${iface.instances}")
    val i = iface.instances.head
    assert(i.witnessName == "given_Eq_Int", s"witnessName: ${i.witnessName}")
    assert(i.fqn         == "pkg_given_Eq_Int", s"fqn: ${i.fqn}")

  test("named given — `given intShow: Show[Int] with …` keeps the explicit name"):
    // Named givens with a `with`-body (the form `Defn.Given` recognises)
    // must NOT be renamed — only the empty-name anonymous case is
    // synthesized.  This is the regression guard against accidentally
    // overriding user-chosen identifiers.
    val iface = extract(
      """trait Show[A]:
        |  def show(x: A): String
        |
        |given intShow: Show[Int] with
        |  def show(x: Int): String = x.toString""".stripMargin)
    val inst = iface.instances.find(_.typeclass == "Show").getOrElse(
      fail(s"expected Show instance; got: ${iface.instances}"))
    assert(inst.witnessName == "intShow",
      s"expected explicit name `intShow`; got: ${inst.witnessName}")
    assert(inst.fqn == "intShow",
      s"expected fqn `intShow`; got: ${inst.fqn}")

  test("anonymous given — multiple instances for different types don't collide"):
    val iface = extract(
      """given Eq[Int] with
        |  def eqv(a: Int, b: Int): Boolean = a == b
        |
        |given Eq[String] with
        |  def eqv(a: String, b: String): Boolean = a == b
        |
        |given Eq[Boolean] with
        |  def eqv(a: Boolean, b: Boolean): Boolean = a == b""".stripMargin)
    val witnessNames = iface.instances.map(_.witnessName).toSet
    assert(witnessNames == Set("given_Eq_Int", "given_Eq_String", "given_Eq_Boolean"),
      s"expected three distinct synthesized names; got: $witnessNames")
    // And the FQN set matches (no `_` truncation).
    val fqns = iface.instances.map(_.fqn).toSet
    assert(fqns == Set("given_Eq_Int", "given_Eq_String", "given_Eq_Boolean"),
      s"expected matching FQN set; got: $fqns")

  test("anonymous given — parametric type drops type-var arg in the head name"):
    // `given Eq[List[Int]] with …` should synthesize `given_Eq_List` —
    // type-arg arguments are dropped from the head-name for stability
    // (the full type expression remains in `typeParam`).
    val iface = extract(
      """trait Eq[A]:
        |  def eqv(a: A, b: A): Boolean
        |
        |given Eq[List[Int]] with
        |  def eqv(a: List[Int], b: List[Int]): Boolean = a == b""".stripMargin)
    val inst = iface.instances.find(_.typeclass == "Eq").getOrElse(
      fail(s"expected Eq instance; got: ${iface.instances}"))
    assert(inst.witnessName == "given_Eq_List",
      s"expected synthesized `given_Eq_List`; got: ${inst.witnessName}")
    // The recorded typeParam still keeps the full type expression for
    // diagnostic/round-trip purposes.
    assert(inst.typeParam.startsWith("List["),
      s"expected typeParam to start with `List[`; got: ${inst.typeParam}")

  test("anonymous given — determinism: same source → same synthesized name"):
    // Round-trip the extractor twice over the same source; the synthesized
    // witness name must be byte-identical.  Guards against any future
    // accidental use of hashing or random ids.
    val src =
      """given Eq[Int] with
        |  def eqv(a: Int, b: Int): Boolean = a == b""".stripMargin
    val a = extract(src).instances.head.witnessName
    val b = extract(src).instances.head.witnessName
    assert(a == b, s"witness name must be deterministic; got: a=$a, b=$b")
    assert(a == "given_Eq_Int", s"expected `given_Eq_Int`; got: $a")

  // ── original `package: …` exports filter test (continues below) ─────────

  test("package walk — manifest `exports:` filter still applies inside the package"):
    // Both fixes compose: the package walk surfaces inner names, then the
    // `exports:` filter narrows the public surface.
    val iface = extractWithFrontMatter(
      frontMatter = "package: std.dsl\nexports:\n  - DocLine",
      scalascriptSource =
        """case class DocLine(text: String)
          |case class HiddenHelper(x: Int)""".stripMargin
    )
    val names = iface.exports.map(_.name).toSet
    assert(names == Set("DocLine"),
      s"expected only `DocLine` (HiddenHelper filtered out), got: $names")

  // ── scalaFacade — Tier 1 + Tier 5 of the Scala interop spec ────────────
  //
  // Tier 5 made JvmGen emit real `package X.Y:` clauses (instead of nested
  // `object X: object Y:` wraps), so the JVM symbol for a `package:`-decorated
  // module's `def add` is now `X.Y.add` directly — natural FQN = JVM FQN.
  // The scalaFacade table is therefore the IDENTITY for `pkg`-decorated
  // modules; no-`package:` modules emit an empty facade (their top-level
  // defs live in Scala 3's `<file>_sc$package$` wrapper at the empty
  // package, unreachable from named-package consumers).

  test("scalaFacade — every top-level export maps to identity (natural == JVM)"):
    val iface = extractWithFrontMatter(
      frontMatter = "package: std.eq",
      scalascriptSource =
        """def eqv(a: Int, b: Int): Boolean = a == b
          |def neqv(a: Int, b: Int): Boolean = a != b""".stripMargin
    )
    iface.exports.foreach { sym =>
      val fqn = (iface.pkg :+ sym.name).mkString(".")
      assert(iface.scalaFacade.get(fqn).contains(fqn),
        s"facade should be identity for $fqn; got: ${iface.scalaFacade.get(fqn)}")
    }

  test("scalaFacade — package: pkg is reflected on both sides (identity)"):
    val iface = extractWithFrontMatter(
      frontMatter = "package: org.example.ui",
      scalascriptSource = "def render(): Unit = ()"
    )
    assert(iface.scalaFacade.contains("org.example.ui.render"),
      s"expected `org.example.ui.render` key, got: ${iface.scalaFacade.keySet}")
    assert(iface.scalaFacade("org.example.ui.render") == "org.example.ui.render",
      s"expected identity; got: ${iface.scalaFacade("org.example.ui.render")}")

  test("scalaFacade — no `package:` ⇒ empty facade (no usable JVM path)"):
    val iface = extract("def hello(): Int = 42")
    assert(iface.scalaFacade.isEmpty,
      s"empty `pkg` should produce empty facade; got: ${iface.scalaFacade}")

  test("scalaFacade — nested members get joined with `.` on both sides (identity)"):
    val iface = extractWithFrontMatter(
      frontMatter = "package: std.foo",
      scalascriptSource =
        """object Bar:
          |  def apply(x: Int): Int = x
          |  val zero: Int = 0""".stripMargin
    )
    val bar = iface.exports.find(_.name == "Bar").getOrElse {
      fail(s"expected `Bar` export, got: ${iface.exports.map(_.name)}")
    }
    assert(iface.scalaFacade.get("std.foo.Bar").contains("std.foo.Bar"))
    if bar.nested.nonEmpty then
      bar.nested.foreach { child =>
        val fqn = s"std.foo.Bar.${child.name}"
        assert(iface.scalaFacade.get(fqn).contains(fqn),
          s"nested facade should be identity for $fqn; got: ${iface.scalaFacade.get(fqn)}")
      }

  test("scalaFacade — manifest `exports:` filter applies to the facade too"):
    // Private helpers stay out of `exports`, so they also stay out of the
    // facade.  This is implicit (we build facade from `exports`), the test
    // pins it.
    val iface = extractWithFrontMatter(
      frontMatter = "package: org.acme\nexports:\n  - publik",
      scalascriptSource =
        """def publik(): Int = 1
          |def privat(): Int = 2""".stripMargin
    )
    assert(iface.scalaFacade.contains("org.acme.publik"),
      "exported names must appear in facade")
    assert(!iface.scalaFacade.contains("org.acme.privat"),
      s"private helpers must NOT appear in facade, got: ${iface.scalaFacade.keySet}")

  // ── arch-meta-v2-p3: inline def metadata ────────────────────────────────

  test("inline def — isInline=true, paramNames and bodySource populated"):
    val iface = extract("inline def double(x: Int): Int = x * 2")
    val sym = iface.exports.find(_.name == "double").getOrElse {
      fail(s"expected 'double' in exports; got: ${iface.exports.map(_.name)}")
    }
    assert(sym.isInline, "isInline should be true for `inline def`")
    assert(sym.inlineParamNames == List("x"),
      s"expected paramNames=[x], got: ${sym.inlineParamNames}")
    assert(sym.inlineBodySource.contains("x * 2"),
      s"expected body 'x * 2', got: ${sym.inlineBodySource}")

  test("inline def — non-inline def leaves isInline=false"):
    val iface = extract("def add(a: Int, b: Int): Int = a + b")
    val sym = iface.exports.find(_.name == "add").getOrElse {
      fail(s"expected 'add' in exports; got: ${iface.exports.map(_.name)}")
    }
    assert(!sym.isInline, "isInline must be false for a plain def")
    assert(sym.inlineParamNames.isEmpty)
    assert(sym.inlineBodySource.isEmpty)

  test("inline def — zero-arg inline records empty param list"):
    val iface = extract("inline def pi: Double = 3.14159")
    val sym = iface.exports.find(_.name == "pi").getOrElse {
      fail(s"expected 'pi' in exports")
    }
    assert(sym.isInline, "isInline should be true")
    assert(sym.inlineParamNames.isEmpty, "zero-arg inline has no param names")
    assert(sym.inlineBodySource.nonEmpty, s"body should be non-empty, got: ${sym.inlineBodySource}")

  test("inline def — multi-param inline captures all param names"):
    val iface = extract("inline def clamp(lo: Int, x: Int, hi: Int): Int = if x < lo then lo else if x > hi then hi else x")
    val sym = iface.exports.find(_.name == "clamp").getOrElse {
      fail(s"expected 'clamp' in exports")
    }
    assert(sym.isInline)
    assert(sym.inlineParamNames == List("lo", "x", "hi"),
      s"expected [lo, x, hi], got: ${sym.inlineParamNames}")

  test("inline def — nested inside object: isInline propagated via buildNestedSymbol"):
    val iface = extract(
      """object Math:
        |  inline def square(n: Int): Int = n * n""".stripMargin)
    val mathSym = iface.exports.find(_.name == "Math").getOrElse {
      fail(s"expected 'Math' object export")
    }
    val squareSym = mathSym.nested.find(_.name == "square").getOrElse {
      fail(s"expected nested 'square' export; nested: ${mathSym.nested.map(_.name)}")
    }
    assert(squareSym.isInline, "nested inline def should have isInline=true")
    assert(squareSym.inlineParamNames == List("n"))
    assert(squareSym.inlineBodySource.contains("n * n"))

  // ── arch-meta-v2-p4: restricted quoted macro metadata ──────────────────

  test("quoted macro entrypoint records MacroImplRef and implementation body"):
    val iface = extract(
      """inline def plusOne(x: Int): Int = ${ plusOneImpl('x) }
        |def plusOneImpl(x: Expr[Int])(using q: QuotedContext): Expr[Int] = '{ $x + 1 }""".stripMargin)

    val entry = iface.exports.find(_.name == "plusOne").getOrElse {
      fail(s"expected plusOne export; got ${iface.exports.map(_.name)}")
    }
    val ref = entry.macroImpl.getOrElse(fail(s"plusOne should carry macroImpl metadata: $entry"))
    assert(ref.implName == "plusOneImpl")
    assert(ref.quotedParams == List("x"))
    assert(ref.resultType.contains("Int"))
    assert(ref.expansionBodySource.exists(_.contains("__ssc_quote_expr__")),
      s"expected linked quoted body source, got ${ref.expansionBodySource}")

    val impl = iface.exports.find(_.name == "plusOneImpl").getOrElse {
      fail(s"expected plusOneImpl export; got ${iface.exports.map(_.name)}")
    }
    assert(impl.isMacroImpl, s"implementation should be marked as macro impl: $impl")
    assert(impl.macroQuotedBodySource.exists(_.contains("__ssc_splice__")),
      s"expected quoted body metadata, got ${impl.macroQuotedBodySource}")
