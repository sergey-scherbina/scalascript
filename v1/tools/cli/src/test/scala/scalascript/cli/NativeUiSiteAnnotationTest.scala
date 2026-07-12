package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import _root_.ssc.{Program, Def, Term, Const, NativeUiSites}

/** The self-hosted native frontend (the default `ssc run` path, RunNativeV2) lowers
 *  std/ui primitive calls to PLAIN globals and — unlike the scalameta FrontendBridge —
 *  did NOT run the `NativeUiSites.annotate` pass. Two anonymous `computedSignal`/`eqSignal`
 *  calls then reached the ui plugin's fallback registration with one shared id
 *  (`__computed__manual:computedSignal`) and collided ("conflicting kind/default"),
 *  which is exactly what blocked rozum's control center (20+ computedSignals) on v2.
 *
 *  These tests pin the two-step the `compile` path now performs:
 *  `calledNativeUiPrimitives` (scan) → scope to the anonymous derived-signal primitives →
 *  `NativeUiSites.annotate`, yielding a UNIQUE lexical site per call site. */
class NativeUiSiteAnnotationTest extends AnyFunSuite:

  test("calledNativeUiPrimitives collects APPLIED std/ui primitives and ignores bare references"):
    // Two computedSignal calls (applied) + a BARE `serve` reference that is never called.
    val program = Program(
      List(Def("signals", Term.Seq(List(
        Term.App(Term.Global("computedSignal"), List(Term.Lam(0, Term.Lit(Const.CStr("a"))))),
        Term.App(Term.Global("computedSignal"), List(Term.Lam(0, Term.Lit(Const.CStr("b"))))),
        Term.App(Term.Global("eqSignal"), List(Term.Local(0), Term.Lit(Const.CStr("a")))),
        Term.Global("serve") // bare, un-applied — must NOT be collected
      )))),
      Term.Lit(Const.CUnit))

    val called = RunNativeV2.calledNativeUiPrimitives(program)
    assert(called.contains("computedSignal"))
    assert(called.contains("eqSignal"))
    assert(!called.contains("serve"), s"bare (un-called) primitive must be excluded, got: $called")

  test("two anonymous computedSignals at distinct sites get UNIQUE ids after annotate (no collision)"):
    val program = Program(
      List(Def("signals", Term.Seq(List(
        Term.App(Term.Global("computedSignal"), List(Term.Lam(0, Term.Lit(Const.CStr("a"))))),
        Term.App(Term.Global("computedSignal"), List(Term.Lam(0, Term.Lit(Const.CStr("b")))))
      )))),
      Term.Lit(Const.CUnit))

    // Exactly what RunNativeV2.compile now does.
    val eligible = RunNativeV2.calledNativeUiPrimitives(program).intersect(Set("computedSignal", "eqSignal"))
    val annotated = NativeUiSites.annotate(program, NativeUiSites.Config(eligibleSymbols = eligible))

    val internal = NativeUiSites.internalName("computedSignal")
    val Term.Seq(List(
      Term.App(Term.Global(firstName), Term.Lit(Const.CStr(firstSite)) :: _),
      Term.App(Term.Global(secondName), Term.Lit(Const.CStr(secondSite)) :: _)
    )) = annotated.defs.head.body: @unchecked

    assert(firstName == internal && secondName == internal, "both calls rewritten to the internal computedSignal")
    assert(firstSite != secondSite, s"the two computedSignal sites must be distinct (collision fix), got $firstSite / $secondSite")
