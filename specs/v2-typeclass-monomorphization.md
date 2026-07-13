# v2 typeclass monomorphization — design (2026-07-13)

Design for correct polymorphic typeclass/`given` dispatch in the v2 self-hosted lowering
(`v2/lib/ssc1-lower.ssc0`). This is the "A1-cont" architectural project: the current dispatch
is STATIC and cannot handle a polymorphic function used at multiple instance types. This spec
picks **monomorphization** over runtime dictionaries and specifies the pass concretely.

## Problem (established by three diagnostic passes)

- A given instance is a set of GLOBALS: `intSum_combine`, `intSum_empty`, `stringConcat_combine`, …
  A typeclass method call lowers to `<given>_<method>`, resolved at COMPILE time.
- A `def combineAll[A](xs: List[A])(using Semigroup[A]): A` has a ctx param `__tc_Semigroup`. Its
  body's `combine`/`empty` resolve via the DEF-BODY active ctx (`computeActiveCtx` → `kc5ActiveCtxCell`).
- `computeActiveCtx` binds the ctx param to the FIRST instance (`findGiven(tc,"*")`→`findAnyGiven`
  → `intSum`). So `combineAll` ALWAYS uses `intSum`, regardless of the call's element type — passes
  for `List[Int]` by luck, gives garbage for `List[String]`/`List[List]`, and `__missing_tc_` for a
  TC with no given at all.
- The body is lowered ONCE but the needed instance depends on the CALL → static `<given>_<method>`
  dispatch cannot bridge it. There is NO runtime dict (a ctx param is not a value carrying methods).

## Decision: monomorphization (not runtime dictionaries)

Monomorphization FITS the existing static model and REUSES `computeActiveCtx`:
- Runtime dicts would need a new value representation (a record of methods), method access lowered to
  field access, and dict threading through every call — a large change to the value + dispatch model.
- Monomorphization keeps everything static: emit ONE specialized copy of the polymorphic body per
  needed instance, each lowered with the active ctx set to that CONCRETE instance. `combine` then
  resolves to `<instance>_combine` exactly as it does today — no new runtime machinery.

## Mechanism

For `combineAll(List(1,2,3))` (needs `Semigroup[Int]` = intSum) and `combineAll(List("a","b"))`
(needs `Semigroup[String]` = stringConcat), emit:
- `combineAll$intSum` — combineAll's body lowered with active ctx `{Semigroup → intSum}` (NO ctx param;
  `combine` → `intSum_combine`, `empty` → `intSum_empty`).
- `combineAll$stringConcat` — same body, active ctx `{Semigroup → stringConcat}`.
Rewrite the calls to `combineAll$intSum(List(1,2,3))` / `combineAll$stringConcat(List("a","b"))`
(dropping the injected dict arg — there is none anymore).

## Phased implementation (in `lowerProg`, all data already available)

1. **Collect** — walk all call sites (the `resolveE`/app path). For each `f(args)` where
   `lookupSig(kc5SigCell, f)` returns ctx TCs, resolve the instance PER TC by the arg type:
   `argType = if isListApp(firstArg) then typeOfExpr(firstElem) else typeOfExpr(firstArg)`
   (the `List[A]`→element rule; `typeOfExpr` extended to return "List" for a `List(...)` app), then
   `findGiven(givenTab, tc, argType)`; fall back to `findAnyGiven` for unknown types (== today, no
   worse). Record the set of `(f, [instances])` pairs needed (dedup by an instance-key string).
2. **Emit** — for each distinct `(f, instanceKey)`, emit a specialized def by lowering f's body with
   `kc5ActiveCtxCell` set to `[Pair(instance, tc), …]` (the concrete instances) and the ctx params
   dropped from f's signature. Memoize emitted keys (recursion + repeated call sites).
3. **Rewrite** — replace `f(args)` with `f$<instanceKey>(args)` at each collected call site.
4. **Transitivity** — if f's body calls another sig-fn `g` forwarding a ctx dict, g must be
   monomorphized at the corresponding instance. Make Collect follow calls INSIDE each emitted
   specialized body (worklist to a fixpoint; memoization bounds it).

## Fallbacks / limits (documented, not crashes)

- Unknown arg type (non-literal, non-List) → keep today's `findAnyGiven` first-instance (no regression).
- Nested containers other than List (Option[A], Map) and multi-param-clause givens still need real
  unification — a later refinement; they fall back to first-instance as today.
- The element-type rule assumes `f(List[A])(using TC[A])` (given for the element). A given for the
  List itself (`TC[List[Int]]`) is the rare case and falls back.

## Verification (MANDATORY — kernel change affects ALL v2 codegen)

- v2 bytecode sweep (`ssc run --bytecode` over `tests/conformance/*`, baseline **99 PASS / 76 FAIL**):
  MUST NOT regress any of the 99; SHOULD recover the typeclass/tagless/std-monoid cluster (~12).
- Run the v2 self-hosted lowering test suite.
- Land per-slice: Collect+Emit+Rewrite for the direct (non-transitive) case FIRST, verify, then add
  transitivity. Revert on any regression to the 99.

## Entry points (file:line, current)

- `buildGivenTable` :454 (givens `(TC,Type)→name`), `buildSigTable` :511 (fn→ctx TCs),
  `computeActiveCtx` :579 (def-body ctx — set at :2988), `buildGivenArgs`/`injectGivens` :628/:647
  (call-site), `typeOfExpr` :618, `lowerProg` :4009 (tables + `lowerStmts`).
