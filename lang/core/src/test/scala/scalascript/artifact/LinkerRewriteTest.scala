package scalascript.artifact

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ir.*
import scalascript.parser.Parser
import scalascript.transform.Normalize

/** v2.0 — end-to-end tests that prove `Linker.link` actually rewrites
 *  cross-module `VarRef` nodes to their mangled FQNs.
 *
 *  These exercise the full pipeline: parse a small `.ssc` source, run
 *  through `Normalize` (which populates `Content.CodeBlock.body` with
 *  real `IrExpr` trees), wrap the result in a `CompiledModule` with a
 *  chosen package + export list, then link two such modules and
 *  inspect the merged IR for the expected FQN rewrites.
 *
 *  Whereas `LinkerTest` covers the structural / collision concerns,
 *  this suite locks down the *traversal* behaviour of
 *  `Linker.rewriteExpr` over real translated IR.
 */
class LinkerRewriteTest extends AnyFunSuite:

  // ── Helpers ────────────────────────────────────────────────────────────

  /** Parse the given scalascript source as a single-block `.ssc` module,
   *  normalise it to IR, and wrap it in a `CompiledModule` with the chosen
   *  package + exports.
   *
   *  Note: we deliberately don't put a `package:` line in the front-matter.
   *  When the parser sees a manifest pkg it wraps every code block in
   *  nested `object` declarations, which the `AstToIr` translator currently
   *  models as `Unsupported(...)` — opaque to the Linker rewriter.
   *  Supplying the package via the `pkg` parameter lets us test the rewriter
   *  on the actual translated body shape.
   */
  private def buildModule(
      src:        String,
      pkg:        List[String],
      exports:    List[String],
      moduleName: String
  ): Linker.CompiledModule =
    val withFence =
      s"""# $moduleName
         |
         |```scalascript
         |$src
         |```
         |""".stripMargin
    val astMod    = Parser.parse(withFence)
    val normMod   = Normalize(astMod)
    val expSyms = exports.map { name =>
      ExportedSymbol(
        name = name,
        fqn  = Linker.mangle(pkg, name),
        kind = "def",
        tpe  = "Any"
      )
    }
    val iface = ModuleInterface(
      magic         = ArtifactVersion.magic,
      abiVersion    = ArtifactVersion.current,
      pkg           = pkg,
      moduleName    = Some(moduleName),
      moduleVersion = None,
      sourceHash    = "0" * 64,
      exports       = expSyms
    )
    Linker.CompiledModule(iface = iface, body = normMod)

  /** Pull every `VarRef` reachable from an `IrExpr` tree, in pre-order. */
  private def collectVarRefs(e: IrExpr): List[String] =
    val buf = scala.collection.mutable.ListBuffer.empty[String]
    def walkExpr(x: IrExpr): Unit = x match
      case VarRef(n)        => buf += n
      case Call(_, args)    => args.foreach(walkExpr)
      case Apply(fn, args)  => walkExpr(fn); args.foreach(walkExpr)
      case Select(q, _)     => walkExpr(q)
      case Lambda(_, body)  => walkExpr(body)
      case If(c, t, e)      => walkExpr(c); walkExpr(t); e.foreach(walkExpr)
      case Block(stmts)     => stmts.foreach(walkExpr)
      case Perform(_, _, a) => a.foreach(walkExpr)
      case Handle(b, cs, r) =>
        walkExpr(b); cs.foreach(c => walkExpr(c.body)); walkExpr(r.body)
      case Resume(_, v)     => walkExpr(v)
      case TailCall(_, a)   => a.foreach(walkExpr)
      case ExternCall(_, a, _) => a.foreach(walkExpr)
      case MacroImpl(_, a, _, _) => a.foreach(walkExpr)
      case MatchTree(s, root) =>
        walkExpr(s); walkNode(root)
      case _: Lit | _: Unsupported => ()
    def walkNode(n: DecisionNode): Unit = n match
      case Switch(cases, default) =>
        cases.foreach { case (_, c) => walkNode(c) }
        default.foreach(walkNode)
      case Leaf(action) => walkExpr(action)
    walkExpr(e)
    buf.toList

  /** Collect every `VarRef` from every code-block body in a section tree. */
  private def collectVarRefsFromSections(sections: List[Section]): List[String] =
    sections.flatMap { s =>
      s.content.flatMap {
        case cb: Content.CodeBlock => cb.body.flatMap(collectVarRefs)
        case _ => Nil
      } ++ collectVarRefsFromSections(s.subsections)
    }

  /** Find the first `Apply` whose `fn` is a `VarRef` with the given name
   *  anywhere under the section tree.  Used to assert the rewriter has
   *  modified call-site targets. */
  private def findApplyOfVarRef(sections: List[Section], name: String): Option[Apply] =
    var found: Option[Apply] = None
    def walkExpr(x: IrExpr): Unit =
      if found.nonEmpty then ()
      else x match
        case a @ Apply(VarRef(n), _) if n == name => found = Some(a)
        case Apply(fn, args)  => walkExpr(fn); args.foreach(walkExpr)
        case Select(q, _)     => walkExpr(q)
        case Lambda(_, body)  => walkExpr(body)
        case If(c, t, e)      => walkExpr(c); walkExpr(t); e.foreach(walkExpr)
        case Block(stmts)     => stmts.foreach(walkExpr)
        case Call(_, args)    => args.foreach(walkExpr)
        case Perform(_, _, a) => a.foreach(walkExpr)
        case Handle(b, cs, r) =>
          walkExpr(b); cs.foreach(c => walkExpr(c.body)); walkExpr(r.body)
        case Resume(_, v)     => walkExpr(v)
        case TailCall(_, a)   => a.foreach(walkExpr)
        case ExternCall(_, a, _) => a.foreach(walkExpr)
        case _                => ()
    def walkSecs(ss: List[Section]): Unit = ss.foreach { s =>
      s.content.foreach {
        case cb: Content.CodeBlock => cb.body.foreach(walkExpr)
        case _ => ()
      }
      walkSecs(s.subsections)
    }
    walkSecs(sections)
    found

  /** Return only the sections that came from the module at the given
   *  zero-based input index, assuming `Linker.link` concatenates input
   *  modules' sections in order.  Each test module above has exactly one
   *  top-level section (its `# moduleName` heading). */
  private def sectionsOf(merged: NormalizedModule, moduleIndex: Int): List[Section] =
    List(merged.sections(moduleIndex))

  // ── Test 1: Top-level VarRef rewrite ───────────────────────────────────

  test("rewrite — top-level VarRef from module B is mangled to module A's FQN"):
    val a = buildModule(
      src     = "def foo(x: Int) = x + 1",
      pkg     = List("a"),
      exports = List("foo"),
      moduleName = "A"
    )
    val b = buildModule(
      src     = "val y = foo(42)",
      pkg     = List("b"),
      exports = List("y"),
      moduleName = "B"
    )
    val merged = Linker.link(List(a, b))

    // Module B's section is the second one (after A's).
    val bSecs = sectionsOf(merged, 1)
    // The call `foo(42)` should now target `a_foo`.
    val call = findApplyOfVarRef(bSecs, "a_foo")
    assert(call.isDefined,
      s"expected Apply(VarRef(\"a_foo\"), …) under B's body; varRefs = " +
        collectVarRefsFromSections(bSecs))
    // Sanity: there should be NO remaining `foo` reference under B.
    val bRefs = collectVarRefsFromSections(bSecs)
    assert(!bRefs.contains("foo"),
      s"expected no bare `foo` in B after rewrite, got: $bRefs")
    assert(bRefs.contains("a_foo"),
      s"expected `a_foo` in B's refs, got: $bRefs")

  // ── Test 2: Select rewrite (qualified call) ────────────────────────────

  test("rewrite — qualified `a.bar()` is folded to VarRef(\"a_bar\")"):
    // Stage 5.3: the rewriter now folds Select chains whose joined path
    // matches a foreign export's FQN.  `Select(VarRef("a"), "bar")` where
    // module A (pkg=["a"]) exports `bar` (fqn="a_bar") collapses to
    // `VarRef("a_bar")` so downstream emitters get a single bare reference.
    val a = buildModule(
      src     = "def bar(): String = \"hi\"",
      pkg     = List("a"),
      exports = List("bar"),
      moduleName = "A"
    )
    val b = buildModule(
      src     = "val z = a.bar()",
      pkg     = List("b"),
      exports = List("z"),
      moduleName = "B"
    )
    val merged = Linker.link(List(a, b))
    val bSecs  = sectionsOf(merged, 1)

    // Select chain must be gone; a single VarRef("a_bar") must remain.
    var foundSelect = false
    var foundFqn    = false
    def walk(x: IrExpr): Unit = x match
      case Select(VarRef("a"), "bar") => foundSelect = true
      case VarRef("a_bar")            => foundFqn = true
      case Apply(fn, args)            => walk(fn); args.foreach(walk)
      case Select(q, _)               => walk(q)
      case Lambda(_, body)            => walk(body)
      case If(c, t, e)                => walk(c); walk(t); e.foreach(walk)
      case Block(stmts)               => stmts.foreach(walk)
      case _                          => ()
    bSecs.foreach { s =>
      s.content.foreach {
        case cb: Content.CodeBlock => cb.body.foreach(walk)
        case _ => ()
      }
    }
    val refs = collectVarRefsFromSections(bSecs)
    assert(!foundSelect, s"Select(VarRef(\"a\"),\"bar\") should have been folded; refs = $refs")
    assert(foundFqn,     s"expected VarRef(\"a_bar\") after fold; refs = $refs")

  test("rewrite — multi-segment package `std.dsl.foo()` is folded to VarRef(\"std_dsl_foo\")"):
    // Same fold applies to deeper Select chains: Select(Select(VarRef("std"),"dsl"),"foo")
    // where module A (pkg=["std","dsl"]) exports `foo` (fqn="std_dsl_foo")
    // collapses to a single `VarRef("std_dsl_foo")`.
    val a = buildModule(
      src     = "def foo(): Int = 42",
      pkg     = List("std", "dsl"),
      exports = List("foo"),
      moduleName = "A"
    )
    val b = buildModule(
      src     = "val z = std.dsl.foo()",
      pkg     = List("b"),
      exports = List("z"),
      moduleName = "B"
    )
    val merged = Linker.link(List(a, b))
    val bSecs  = sectionsOf(merged, 1)
    val refs   = collectVarRefsFromSections(bSecs)
    assert(refs.contains("std_dsl_foo"), s"expected VarRef(\"std_dsl_foo\"); refs = $refs")

  // ── Test 3: Lambda parameter shadowing ─────────────────────────────────

  test("rewrite — lambda parameter shadowing prevents rewrite of bound name"):
    val a = buildModule(
      src     = "def foo(x: Int) = x + 1",
      pkg     = List("a"),
      exports = List("foo"),
      moduleName = "A"
    )
    val b = buildModule(
      src     = "val g = (foo: Int) => foo + 1",
      pkg     = List("b"),
      exports = List("g"),
      moduleName = "B"
    )
    val merged = Linker.link(List(a, b))
    val bSecs  = sectionsOf(merged, 1)

    // Walk B's body and find the Lambda; its body must NOT contain VarRef("a_foo").
    var sawLambdaWithLocalFoo = false
    def walk(x: IrExpr): Unit = x match
      case Lambda(ps, body) if ps.contains("foo") =>
        val bodyRefs = collectVarRefs(body)
        // The shadowed `foo` inside the lambda must stay bare.
        assert(bodyRefs.contains("foo"),
          s"expected shadowed `foo` to remain inside the lambda body, got: $bodyRefs")
        assert(!bodyRefs.contains("a_foo"),
          s"shadowing failed: `foo` inside lambda was rewritten to `a_foo`; refs: $bodyRefs")
        sawLambdaWithLocalFoo = true
      case Apply(fn, args) => walk(fn); args.foreach(walk)
      case Select(q, _)    => walk(q)
      case Lambda(_, body) => walk(body)
      case If(c, t, e)     => walk(c); walk(t); e.foreach(walk)
      case Block(stmts)    => stmts.foreach(walk)
      case _               => ()
    bSecs.foreach { s =>
      s.content.foreach {
        case cb: Content.CodeBlock => cb.body.foreach(walk)
        case _ => ()
      }
    }
    assert(sawLambdaWithLocalFoo,
      s"expected to encounter a Lambda binding `foo`; refs = " +
        collectVarRefsFromSections(bSecs))

  // ── Test 4: Multiple imports + multiple references ────────────────────

  test("rewrite — multiple imports and multiple call sites are all rewritten consistently"):
    val a = buildModule(
      src     =
        """def a1(x: Int) = x
          |def a2(x: Int) = x""".stripMargin,
      pkg     = List("a"),
      exports = List("a1", "a2"),
      moduleName = "A"
    )
    val b = buildModule(
      src     =
        """val p = a1(1)
          |val q = a2(2)
          |val r = a1(3)
          |val s = a2(a1(4))""".stripMargin,
      pkg     = List("b"),
      exports = List("p", "q", "r", "s"),
      moduleName = "B"
    )
    val merged = Linker.link(List(a, b))
    val bSecs  = sectionsOf(merged, 1)
    val bRefs  = collectVarRefsFromSections(bSecs)

    // Every original `a1` / `a2` reference must be replaced.
    assert(!bRefs.contains("a1"),
      s"expected no bare `a1` in B after rewrite, got: $bRefs")
    assert(!bRefs.contains("a2"),
      s"expected no bare `a2` in B after rewrite, got: $bRefs")
    // The mangled names must appear with the expected multiplicities:
    //   a_a1: three call sites (p, r, s's inner)
    //   a_a2: two   call sites (q, s's outer)
    val countA1 = bRefs.count(_ == "a_a1")
    val countA2 = bRefs.count(_ == "a_a2")
    assert(countA1 == 3, s"expected 3 occurrences of `a_a1`, got: $countA1 (refs=$bRefs)")
    assert(countA2 == 2, s"expected 2 occurrences of `a_a2`, got: $countA2 (refs=$bRefs)")

  // ── Test 5: No-op when no cross-module reference ──────────────────────

  test("rewrite — no spurious rewrites when module B references only its own names"):
    val a = buildModule(
      src     = "def foo(x: Int) = x + 1",
      pkg     = List("a"),
      exports = List("foo"),
      moduleName = "A"
    )
    val b = buildModule(
      src     =
        """def bar(x: Int) = x + 2
          |val q = bar(7)""".stripMargin,
      pkg     = List("b"),
      exports = List("bar", "q"),
      moduleName = "B"
    )
    val merged = Linker.link(List(a, b))
    val bSecs  = sectionsOf(merged, 1)
    val bRefs  = collectVarRefsFromSections(bSecs)

    // `bar` (B's own) must NOT be mangled to `a_bar` / `b_bar`.
    assert(bRefs.contains("bar"),
      s"expected bare `bar` (B's own name) in B's refs, got: $bRefs")
    assert(!bRefs.contains("a_bar"),
      s"unexpected rewrite of `bar` to `a_bar`, refs: $bRefs")
    assert(!bRefs.contains("b_bar"),
      s"B's own `bar` must not be mangled to `b_bar` (same module), refs: $bRefs")
    assert(!bRefs.contains("a_foo"),
      s"A's `foo` is unused in B; no `a_foo` should appear, refs: $bRefs")

  // ── Test 6: Cross-recursive rewrite (A↔B) ─────────────────────────────

  test("rewrite — A references B and B references A: both call sites are rewritten"):
    // The Linker treats the input list as a flat set of modules — there is
    // no constraint that module A's body cannot reference module B's
    // exports.  Both directions should be rewritten symmetrically: A's
    // call to `bb` → `b_bb`, and B's call to `aa` → `a_aa`.
    val a = buildModule(
      src     =
        """def aa(x: Int) = x
          |val u = bb(10)""".stripMargin,
      pkg     = List("a"),
      exports = List("aa", "u"),
      moduleName = "A"
    )
    val b = buildModule(
      src     =
        """def bb(x: Int) = x
          |val v = aa(20)""".stripMargin,
      pkg     = List("b"),
      exports = List("bb", "v"),
      moduleName = "B"
    )
    val merged = Linker.link(List(a, b))
    val aSecs  = sectionsOf(merged, 0)
    val bSecs  = sectionsOf(merged, 1)
    val aRefs  = collectVarRefsFromSections(aSecs)
    val bRefs  = collectVarRefsFromSections(bSecs)

    // A: own def name `aa` stays bare; call to B's `bb` is rewritten.
    assert(aRefs.contains("aa"),  s"expected own `aa` in A's refs, got: $aRefs")
    assert(aRefs.contains("b_bb"), s"expected `bb` rewritten to `b_bb` in A, got: $aRefs")
    assert(!aRefs.contains("bb"),  s"expected no bare `bb` in A, got: $aRefs")

    // B: own def name `bb` stays bare; call to A's `aa` is rewritten.
    assert(bRefs.contains("bb"),  s"expected own `bb` in B's refs, got: $bRefs")
    assert(bRefs.contains("a_aa"), s"expected `aa` rewritten to `a_aa` in B, got: $bRefs")
    assert(!bRefs.contains("aa"),  s"expected no bare `aa` in B, got: $bRefs")

  // ── Bonus: rewriter preserves Lit / structural shape ──────────────────

  test("rewrite — literal arguments are preserved through the rewrite"):
    val a = buildModule(
      src     = "def foo(x: Int) = x",
      pkg     = List("a"),
      exports = List("foo"),
      moduleName = "A"
    )
    val b = buildModule(
      src     = "val y = foo(42)",
      pkg     = List("b"),
      exports = List("y"),
      moduleName = "B"
    )
    val merged = Linker.link(List(a, b))
    val bSecs  = sectionsOf(merged, 1)
    val call   = findApplyOfVarRef(bSecs, "a_foo").getOrElse(
      fail(s"missing rewritten call site in B; refs: ${collectVarRefsFromSections(bSecs)}")
    )
    // The literal 42 must still be the sole argument, unchanged.
    assert(call.args == List(Lit(LitValue.IntL(42L))),
      s"expected args=[Lit(IntL(42))], got: ${call.args}")

  // ── arch-meta-v2-p3: cross-module inline expansion ──────────────────────

  test("expandInlineSource — single-param inline: lambda-lifted form emitted"):
    val table = Map("double" -> (List("x") -> "x * 2"))
    val result = Linker.expandInlineSource("val y = double(n + 1)", table)
    assert(result == "val y = ((x) => x * 2)(n + 1)",
      s"unexpected expansion: $result")

  test("expandInlineSource — zero-param inline: body wrapped in parens (no lambda)"):
    val table = Map("pi" -> (Nil -> "3.14159"))
    val result = Linker.expandInlineSource("val r = pi", table)
    // name not followed by '(' — not treated as a call site
    assert(result == "val r = pi", s"should leave bare reference as-is: $result")

  test("expandInlineSource — zero-param inline with call-site parens"):
    val table = Map("pi" -> (Nil -> "3.14159"))
    val result = Linker.expandInlineSource("val r = pi()", table)
    assert(result == "val r = (3.14159)", s"unexpected: $result")

  test("expandInlineSource — multi-param inline expands all params"):
    val table = Map("clamp" -> (List("lo", "x", "hi") -> "if x < lo then lo else if x > hi then hi else x"))
    val result = Linker.expandInlineSource("clamp(0, v, 100)", table)
    assert(result == "((lo, x, hi) => if x < lo then lo else if x > hi then hi else x)(0, v, 100)",
      s"unexpected: $result")

  test("expandInlineSource — name in string literal is NOT expanded"):
    val table = Map("double" -> (List("x") -> "x * 2"))
    val result = Linker.expandInlineSource("""val s = "double(5)"""", table)
    assert(result == """val s = "double(5)"""", s"string contents must not be expanded: $result")

  test("expandInlineSource — nested parens in args parsed correctly"):
    val table = Map("neg" -> (List("x") -> "-x"))
    val result = Linker.expandInlineSource("neg(f(g(1, 2), 3))", table)
    assert(result == "((x) => -x)(f(g(1, 2), 3))",
      s"unexpected: $result")

  test("expandInlineSource — word-boundary: `notDouble(5)` is not confused with `double`"):
    val table = Map("double" -> (List("x") -> "x * 2"))
    val result = Linker.expandInlineSource("notDouble(5)", table)
    assert(result == "notDouble(5)", s"should not match partial identifier: $result")

  test("expandInlineSource — no-op on source with no matching calls (returns same ref)"):
    val table = Map("double" -> (List("x") -> "x * 2"))
    val src = "val y = triple(5)"
    val result = Linker.expandInlineSource(src, table)
    assert(result eq src, "should return the original string ref when nothing matched")

  test("expandInlineSource — multiple distinct inline calls in one source"):
    val table = Map(
      "inc"  -> (List("n") -> "n + 1"),
      "dbl"  -> (List("n") -> "n * 2")
    )
    val result = Linker.expandInlineSource("inc(dbl(x))", table)
    // Only the outermost match fires — the inner `dbl(x)` is already inside
    // the matched arg span and stays as-is in the arg text.
    // After first pass: inc → expanded; dbl inside arg stays raw.
    // (The expander makes only one pass, not recursive.)
    assert(result.contains("=> n + 1"), s"inc should be expanded: $result")

  // ── arch-meta-v2-p4: restricted quoted macro expansion ─────────────────

  test("normalizeQuotedMacroBody strips quote and splice markers"):
    val result = Linker.normalizeQuotedMacroBody("""'{ $x + 1 }""")
    assert(result == "x + 1", s"unexpected normalized body: $result")

  test("normalizeQuotedMacroBody rejects unsupported non-quoted body"):
    val ex = intercept[IllegalArgumentException] {
      Linker.normalizeQuotedMacroBody("""x.asValue.getOrElse(0)""")
    }
    assert(ex.getMessage.contains("restricted quoted macros must return a direct quoted expression"), ex.getMessage)

  test("expandMacroSource expands quoted macro call through lambda-lifted body"):
    val table = Map("plusOne" -> Linker.MacroExpansion(List("x"), """'{ $x + 1 }"""))
    val result = Linker.expandMacroSource("val y = plusOne(n)", table)
    assert(result == "val y = ((x) => x + 1)(n)", s"unexpected macro expansion: $result")

  test("link — inline export in dependency module is expanded in consumer source"):
    // Build a library module that exports an `inline def`
    val libIface = ModuleInterface(
      magic         = ArtifactVersion.magic,
      abiVersion    = ArtifactVersion.current,
      pkg           = List("lib"),
      moduleName    = Some("lib"),
      moduleVersion = None,
      sourceHash    = "0" * 64,
      exports       = List(ExportedSymbol(
        name             = "square",
        fqn              = "lib_square",
        kind             = "def",
        tpe              = "Any",
        isInline         = true,
        inlineParamNames = List("n"),
        inlineBodySource = Some("n * n")
      ))
    )
    val libBody = NormalizedModule(
      manifest = None,
      sections = List(Section(
        heading     = Heading(level = 1, text = "lib"),
        content     = List(Content.CodeBlock("inline def square(n: Int): Int = n * n")),
        subsections = Nil))
    )
    val libModule = Linker.CompiledModule(iface = libIface, body = libBody)

    // Build a consumer module that calls `square`
    val withFence =
      s"""# Consumer
         |
         |```scalascript
         |val y = square(5)
         |```
         |""".stripMargin
    val consumerAst  = scalascript.parser.Parser.parse(withFence)
    val consumerNorm = scalascript.transform.Normalize(consumerAst)
    val consumerIface = ModuleInterface(
      magic         = ArtifactVersion.magic,
      abiVersion    = ArtifactVersion.current,
      pkg           = List("app"),
      moduleName    = Some("consumer"),
      moduleVersion = None,
      sourceHash    = "0" * 64,
      exports       = List(ExportedSymbol(name = "y", fqn = "app_y", kind = "val", tpe = "Any"))
    )
    val consumerModule = Linker.CompiledModule(iface = consumerIface, body = consumerNorm)

    val merged = Linker.link(List(libModule, consumerModule))
    // The consumer's code block source should have `square(5)` replaced by
    // the lambda-lifted form.
    val allSources = merged.sections.flatMap { s =>
      s.content.collect { case cb: Content.CodeBlock => cb.source }
    }
    val consumerSrc = allSources.find(_.contains("5")).getOrElse(
      fail(s"could not find consumer code block; sources: $allSources")
    )
    assert(consumerSrc.contains("=> n * n"),
      s"expected lambda-lifted inline body in consumer source; got:\n$consumerSrc")

  test("link — quoted macro export in dependency module is expanded in consumer source"):
    val libIface = ModuleInterface(
      magic         = ArtifactVersion.magic,
      abiVersion    = ArtifactVersion.current,
      pkg           = List("lib"),
      moduleName    = Some("lib"),
      moduleVersion = None,
      sourceHash    = "0" * 64,
      exports       = List(
        ExportedSymbol(
          name = "plusOne",
          fqn  = "lib_plusOne",
          kind = "def",
          isInline = true,
          inlineParamNames = List("x"),
          inlineBodySource = Some("__ssc_macro__(plusOneImpl(__ssc_quote__(\"x\")))"),
          macroImpl = Some(MacroImplRef(
            implName = "plusOneImpl",
            quotedParams = List("x"),
            resultType = Some("Int"),
            expansionBodySource = Some("""'{ $x + 1 }""")
          ))
        )
      )
    )
    val libBody = NormalizedModule(
      manifest = None,
      sections = List(Section(
        heading     = Heading(level = 1, text = "lib"),
        content     = List(Content.CodeBlock("inline def plusOne(x: Int): Int = ${ plusOneImpl('x) }")),
        subsections = Nil))
    )
    val consumerAst  = Parser.parse(
      """# Consumer
        |
        |```scalascript
        |val y = plusOne(41)
        |```
        |""".stripMargin)
    val consumerNorm = Normalize(consumerAst)
    val consumerIface = ModuleInterface(
      magic         = ArtifactVersion.magic,
      abiVersion    = ArtifactVersion.current,
      pkg           = List("app"),
      moduleName    = Some("consumer"),
      moduleVersion = None,
      sourceHash    = "0" * 64,
      exports       = List(ExportedSymbol(name = "y", fqn = "app_y", kind = "val", tpe = "Any"))
    )

    val merged = Linker.link(List(
      Linker.CompiledModule(iface = libIface, body = libBody),
      Linker.CompiledModule(iface = consumerIface, body = consumerNorm)
    ))
    val consumerSrc = merged.sections.flatMap(_.content.collect {
      case cb: Content.CodeBlock if cb.source.contains("41") => cb.source
    }).headOption.getOrElse(fail(s"could not find consumer code block: ${merged.sections}"))

    assert(consumerSrc.contains("=> x + 1"),
      s"expected quoted macro body in consumer source; got:\n$consumerSrc")
