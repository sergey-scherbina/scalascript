package scalascript.typer

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser
import scalascript.ir.{ExportedSymbol, ModuleInterface}

/** v2.0 — typer strict mode pins down undefined-name diagnostics.
 *
 *  In permissive mode (the default, used by `ssc compile`, `emit-interface`,
 *  and `emit-ir`) an unknown identifier silently collapses to `SType.Any` so
 *  that downstream compilation never cascades on a single resolution miss.
 *
 *  In strict mode (used by `ssc check-with-iface`) the same unknown identifier
 *  is still typed as `SType.Any` (no cascade), but the typer additionally
 *  records a `TypeError` naming the missing symbol.
 */
class TyperStrictModeTest extends AnyFunSuite:

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

  // ── 1. Permissive default — undefined name silently yields SType.Any ──────

  test("permissive default — undefined name produces no diagnostic"):
    val typed = Typer.typeCheck(moduleOf(
      """def useUnknown(): Int = thisNameDoesNotExist(1, 2)"""
    ))
    assert(!typed.hasErrors,
      s"permissive mode should accept undefined names; got errors:\n" +
      typed.errors.map(_.show).mkString("\n"))

  // ── 2. Strict mode — undefined name records a TypeError ───────────────────

  test("strict mode — undefined name produces a diagnostic naming the symbol"):
    val typed = Typer.typeCheckStrict(moduleOf(
      """def useUnknown(): Int = thisNameDoesNotExist(1, 2)"""
    ))
    assert(typed.hasErrors,
      "strict mode should reject undefined names")
    val msgs = typed.errors.map(_.msg).mkString(" | ")
    assert(msgs.contains("thisNameDoesNotExist"),
      s"expected diagnostic to name the missing symbol; got: $msgs")

  // ── 3. Strict + import — names from interface resolve; others don't ───────

  test("strict + import — name in the imported interface resolves cleanly"):
    val iface = ModuleInterface(
      magic         = scalascript.ir.ArtifactVersion.magic,
      abiVersion    = scalascript.ir.ArtifactVersion.current,
      pkg           = List("a"),
      moduleName    = Some("a"),
      moduleVersion = None,
      sourceHash    = "0" * 64,
      exports       = List(ExportedSymbol(
        name = "add",
        fqn  = "a.add",
        kind = "def",
        tpe  = "(Int, Int) => Int"
      ))
    )
    val typed = Typer.typeCheckWithInterfaces(
      moduleOf("""def useAdd(): Int = add(1, 2)"""),
      interfaces = Map("a" -> iface),
      strict     = true
    )
    assert(!typed.hasErrors,
      s"expected `add` from the imported interface to resolve; got errors:\n" +
      typed.errors.map(_.show).mkString("\n"))

  test("strict + import — a name NOT exported by the interface is rejected"):
    val iface = ModuleInterface(
      magic         = scalascript.ir.ArtifactVersion.magic,
      abiVersion    = scalascript.ir.ArtifactVersion.current,
      pkg           = List("a"),
      moduleName    = Some("a"),
      moduleVersion = None,
      sourceHash    = "0" * 64,
      exports       = List(ExportedSymbol(
        name = "add",
        fqn  = "a.add",
        kind = "def",
        tpe  = "(Int, Int) => Int"
      ))
    )
    val typed = Typer.typeCheckWithInterfaces(
      moduleOf("""def useMul(): Int = mul(1, 2)"""),
      interfaces = Map("a" -> iface),
      strict     = true
    )
    assert(typed.hasErrors,
      "expected `mul` (not exported by the interface) to be flagged")
    val msgs = typed.errors.map(_.msg).mkString(" | ")
    assert(msgs.contains("mul"),
      s"expected diagnostic to name the missing `mul`; got: $msgs")

  // ── 4. Sanity: builtins are never flagged ─────────────────────────────────

  test("strict mode — builtin prelude names (println, Some, List, Map, math, runActors) pass"):
    val typed = Typer.typeCheckStrict(moduleOf(
      """val r1 = println("hi")
        |val r2 = Some(42)
        |val r3 = List(1, 2, 3)
        |val r4 = Map("a" -> 1)
        |val n  = None""".stripMargin
    ))
    assert(!typed.hasErrors,
      s"builtin names should not be flagged; got errors:\n" +
      typed.errors.map(_.show).mkString("\n"))

  // ── 5. Same-module defs are in scope, even in strict mode ─────────────────

  test("strict mode — references to same-module defs resolve via the scope chain"):
    val typed = Typer.typeCheckStrict(moduleOf(
      """def add(x: Int, y: Int): Int = x + y
        |def useAdd(): Int = add(1, 2)""".stripMargin
    ))
    assert(!typed.hasErrors,
      s"same-module defs should resolve; got errors:\n" +
      typed.errors.map(_.show).mkString("\n"))

  // ── 6. Function parameters are in scope inside the function body ──────────

  test("strict mode — function parameters resolve inside the body"):
    val typed = Typer.typeCheckStrict(moduleOf(
      """def addOne(x: Int): Int = x + 1"""
    ))
    assert(!typed.hasErrors,
      s"function params should resolve in body; got errors:\n" +
      typed.errors.map(_.show).mkString("\n"))

  // ── 7. Select against an imported module — exported member resolves ──────

  /** A helper that builds a `ModuleInterface` with the given exports. */
  private def ifaceWith(alias: String, exportNames: String*): ModuleInterface =
    ModuleInterface(
      magic         = scalascript.ir.ArtifactVersion.magic,
      abiVersion    = scalascript.ir.ArtifactVersion.current,
      pkg           = List(alias),
      moduleName    = Some(alias),
      moduleVersion = None,
      sourceHash    = "0" * 64,
      exports       = exportNames.toList.map(n => ExportedSymbol(
        name = n,
        fqn  = s"$alias.$n",
        kind = "def",
        tpe  = "Any"
      ))
    )

  test("strict + Select — exported member of imported module resolves cleanly"):
    val iface = ifaceWith("ImportedMod", "existing")
    val typed = Typer.typeCheckWithInterfaces(
      moduleOf("""def use(): Int = ImportedMod.existing"""),
      interfaces = Map("ImportedMod" -> iface),
      strict     = true
    )
    assert(!typed.hasErrors,
      s"existing member should resolve; got errors:\n" +
      typed.errors.map(_.show).mkString("\n"))

  test("strict + Select — missing member of imported module is rejected"):
    val iface = ifaceWith("ImportedMod", "other")
    val typed = Typer.typeCheckWithInterfaces(
      moduleOf("""def use(): Int = ImportedMod.missing"""),
      interfaces = Map("ImportedMod" -> iface),
      strict     = true
    )
    assert(typed.hasErrors,
      "expected a diagnostic for ImportedMod.missing")
    val msgs = typed.errors.map(_.msg).mkString(" | ")
    assert(msgs.contains("ImportedMod") && msgs.contains("missing"),
      s"expected diagnostic to mention both 'ImportedMod' and 'missing'; got: $msgs")

  // ── 8. Select against a local val — never flagged (receiver isn't a module) ─

  test("strict + Select — receiver is a same-module val: no error"):
    val typed = Typer.typeCheckStrict(moduleOf(
      """val localVal = 42
        |def use(): Any = localVal.anyMember""".stripMargin
    ))
    assert(!typed.hasErrors,
      s"Select on a local value should not be flagged; got errors:\n" +
      typed.errors.map(_.show).mkString("\n"))

  // ── 9. Select where qualifier itself is undefined — single fallback error ─

  test("strict + Select — undefined qualifier falls back to bare-name check, no double-report"):
    val typed = Typer.typeCheckStrict(moduleOf(
      """def use(): Any = totallyUnknown.foo"""
    ))
    assert(typed.hasErrors,
      "expected the bare-name undefined check to fire on the qualifier")
    val msgs = typed.errors.map(_.msg)
    val undefMsgs = msgs.filter(_.contains("totallyUnknown"))
    assert(undefMsgs.length == 1,
      s"expected exactly one diagnostic naming the missing qualifier, got ${undefMsgs.length}:\n" +
      undefMsgs.mkString("\n"))
    // And critically: no "has no member" error — the qualifier doesn't
    // resolve to an imported module, so the member-missing check is silent.
    assert(!msgs.exists(_.contains("has no member")),
      s"unknown qualifier should not produce a member-missing error; got: ${msgs.mkString(" | ")}")

  // ── 10. Builtin types: receivers with non-module types — no error ─────────

  test("strict + Select — Select on a builtin literal (e.g. 1.toString) is not flagged"):
    val typed = Typer.typeCheckStrict(moduleOf(
      """val s1 = 1.toString
        |val s2 = "x".length""".stripMargin
    ))
    assert(!typed.hasErrors,
      s"Select on builtin literals should pass; got errors:\n" +
      typed.errors.map(_.show).mkString("\n"))

  // ── 11. Deep Select chains — `pkg.sub.member` and deeper ──────────────────
  //
  // v2.0 strict-mode extension: the checker walks the qualifier chain
  // recursively.  A sub-namespace is modelled as an `ExportedSymbol` of
  // kind "object" whose `nested` list holds its inner exports.  When
  // every step resolves cleanly the final member is validated against
  // the deepest namespace; otherwise a single diagnostic names the
  // exact break point — no cascade.

  /** Build a `ModuleInterface` whose top-level exports may themselves carry
   *  nested members (a sub-namespace, e.g. an inner object).  Mirrors the
   *  shape `InterfaceExtractor` would emit once nested-namespace
   *  extraction is implemented (TODO in `ExportedSymbol.nested`). */
  private def ifaceWithNested(
      alias: String,
      topLevel: List[ExportedSymbol]
  ): ModuleInterface =
    ModuleInterface(
      magic         = scalascript.ir.ArtifactVersion.magic,
      abiVersion    = scalascript.ir.ArtifactVersion.current,
      pkg           = List(alias),
      moduleName    = Some(alias),
      moduleVersion = None,
      sourceHash    = "0" * 64,
      exports       = topLevel
    )

  private def subNs(name: String, members: String*): ExportedSymbol =
    ExportedSymbol(
      name   = name,
      fqn    = s"pkg_$name",
      kind   = "object",
      tpe    = "Any",
      nested = members.toList.map(m => ExportedSymbol(
        name = m,
        fqn  = s"pkg_${name}_$m",
        kind = "def",
        tpe  = "Any"
      ))
    )

  test("strict + deep Select — `pkg.sub.member` with valid 3-level path: no error"):
    val iface = ifaceWithNested(
      alias    = "pkg",
      topLevel = List(subNs("sub", "member"))
    )
    val typed = Typer.typeCheckWithInterfaces(
      moduleOf("""def use(): Any = pkg.sub.member"""),
      interfaces = Map("pkg" -> iface),
      strict     = true
    )
    assert(!typed.hasErrors,
      s"valid 3-level path should resolve; got errors:\n" +
      typed.errors.map(_.show).mkString("\n"))

  test("strict + deep Select — `pkg.sub.missing` flags the missing leaf, naming `pkg.sub`"):
    val iface = ifaceWithNested(
      alias    = "pkg",
      topLevel = List(subNs("sub", "existing"))
    )
    val typed = Typer.typeCheckWithInterfaces(
      moduleOf("""def use(): Any = pkg.sub.missing"""),
      interfaces = Map("pkg" -> iface),
      strict     = true
    )
    assert(typed.hasErrors,
      "expected diagnostic for `pkg.sub.missing`")
    val msgs = typed.errors.map(_.msg).mkString(" | ")
    assert(msgs.contains("pkg.sub") && msgs.contains("missing"),
      s"expected diagnostic to mention both `pkg.sub` and `missing`; got: $msgs")
    // Single diagnostic — no cascade.
    val hasNoMemberMsgs = typed.errors.map(_.msg).count(_.contains("has no member"))
    assert(hasNoMemberMsgs == 1,
      s"expected exactly one `has no member` diagnostic, got $hasNoMemberMsgs:\n" +
      typed.errors.map(_.show).mkString("\n"))

  test("strict + deep Select — `pkg.missingSub.anything`: single diagnostic about the first break, no cascade"):
    val iface = ifaceWithNested(
      alias    = "pkg",
      topLevel = List(subNs("sub", "member"))   // `missingSub` is NOT an export
    )
    val typed = Typer.typeCheckWithInterfaces(
      moduleOf("""def use(): Any = pkg.missingSub.anything"""),
      interfaces = Map("pkg" -> iface),
      strict     = true
    )
    assert(typed.hasErrors,
      "expected diagnostic about `pkg.missingSub`")
    val msgs = typed.errors.map(_.msg)
    assert(msgs.exists(m => m.contains("pkg") && m.contains("missingSub")),
      s"expected diagnostic naming `pkg` and `missingSub`; got: ${msgs.mkString(" | ")}")
    // No cascade — should NOT also mention `anything` as missing.
    val hasNoMemberMsgs = msgs.count(_.contains("has no member"))
    assert(hasNoMemberMsgs == 1,
      s"expected exactly one diagnostic, got $hasNoMemberMsgs:\n" +
      typed.errors.map(_.show).mkString("\n"))
    assert(!msgs.exists(_.contains("anything")),
      s"deeper member `anything` should NOT be flagged when the chain already broke; got: ${msgs.mkString(" | ")}")

  test("strict + deep Select — 4-level `pkg.sub.deeper.x` resolves when all valid, errors at first break"):
    // Build pkg.sub with a nested sub-object `deeper` exporting `x`.
    val deeper = ExportedSymbol(
      name   = "deeper",
      fqn    = "pkg_sub_deeper",
      kind   = "object",
      tpe    = "Any",
      nested = List(ExportedSymbol("x", "pkg_sub_deeper_x", "def", "Any"))
    )
    val sub = ExportedSymbol(
      name   = "sub",
      fqn    = "pkg_sub",
      kind   = "object",
      tpe    = "Any",
      nested = List(deeper)
    )
    val iface = ifaceWithNested(alias = "pkg", topLevel = List(sub))

    val typedOk = Typer.typeCheckWithInterfaces(
      moduleOf("""def use(): Any = pkg.sub.deeper.x"""),
      interfaces = Map("pkg" -> iface),
      strict     = true
    )
    assert(!typedOk.hasErrors,
      s"valid 4-level path should resolve; got errors:\n" +
      typedOk.errors.map(_.show).mkString("\n"))

    // `pkg.sub.deeper.missingX` — break at the leaf only, naming `pkg.sub.deeper`.
    val typedBreak = Typer.typeCheckWithInterfaces(
      moduleOf("""def use(): Any = pkg.sub.deeper.missingX"""),
      interfaces = Map("pkg" -> iface),
      strict     = true
    )
    val breakMsgs = typedBreak.errors.map(_.msg)
    assert(breakMsgs.exists(m => m.contains("pkg.sub.deeper") && m.contains("missingX")),
      s"expected diagnostic naming `pkg.sub.deeper` and `missingX`; got: ${breakMsgs.mkString(" | ")}")
    assert(breakMsgs.count(_.contains("has no member")) == 1,
      s"expected exactly one `has no member` diagnostic; got: ${breakMsgs.mkString(" | ")}")

  test("strict + deep Select — chain rooted at a local val (`localVal.x.y`): no false positive"):
    val typed = Typer.typeCheckStrict(moduleOf(
      """val localVal = 42
        |def use(): Any = localVal.x.y""".stripMargin
    ))
    assert(!typed.hasErrors,
      s"Select chain on a local value should not be flagged; got errors:\n" +
      typed.errors.map(_.show).mkString("\n"))

  test("strict + deep Select — sub-namespace with empty `nested` (extractor TODO): permissive"):
    // Today, `InterfaceExtractor` does NOT populate `ExportedSymbol.nested`,
    // so any sub-namespace export has an empty nested list.  Until that
    // lands, deep selects through such a sub-namespace fall back to
    // permissive behaviour (no diagnostic, no false positive).  This test
    // pins down the current behaviour so a future change that flips it
    // can re-baseline explicitly.
    // TODO(v2.x): once `InterfaceExtractor` records nested members, flip
    // this test's expectation to assert that `pkg.sub.unknown` is rejected.
    val opaqueSub = ExportedSymbol(
      name   = "sub",
      fqn    = "pkg_sub",
      kind   = "object",
      tpe    = "Any",
      nested = Nil   // <-- the extractor's current output shape
    )
    val iface = ifaceWithNested(alias = "pkg", topLevel = List(opaqueSub))
    val typed = Typer.typeCheckWithInterfaces(
      moduleOf("""def use(): Any = pkg.sub.anyMember"""),
      interfaces = Map("pkg" -> iface),
      strict     = true
    )
    assert(!typed.hasErrors,
      s"opaque sub-namespace should be permissive today; got errors:\n" +
      typed.errors.map(_.show).mkString("\n"))
