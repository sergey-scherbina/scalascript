# Interpreter (`int` lane) — bugs

Scope: defects whose FIX goes in `v1/runtime/backend/interpreter/`. Layout and routing rules:
`specs/work-tracking-layout.md`. Entry format (the header is parsed, the prose is not):
`specs/bugs-index.md`. Query with `scripts/bugs-report --module v1/runtime/backend/interpreter`, never by
grepping for status.

Newest first.

## imported-generic-fold-with-a-tuple-accumulator-presents-one-component — a cross-module generic fold+present over a tuple accumulator returns ONE tuple component instead of the tuple

<!-- status: open
     lane: int
     kind: bug
     area: runtime
     gate: none
     reported-by: claude-code
     reported-at: 2026-08-30
     confirmed: yes -->

Found writing `tests/conformance/std-aggregator-properties.ssc` (the aggregation-algebra laws
case): `runAggregator(xs, ZipAgg(SumAgg(), CountAgg[Double]()))` — whose accumulator is a
`(Double, Int)` TUPLE via `PairMonoid` — returns `2` (the count component alone) under `--v1`,
where the reference front returns the tuple `(4, 2)`. Minimal repro, pinned by a one-declaration
control (the IDENTICAL function body, local vs imported):

```scalascript
[Aggregator, runAggregator, ZipAgg, SumAgg, CountAgg](std/aggregator.ssc)

def runLocal[In, Acc, Out](xs: List[In], agg: Aggregator[In, Acc, Out]): Out =
  val acc = xs.foldLeft(agg.monoid.empty)((a, in) => agg.monoid.combine(a, agg.prepare(in)))
  agg.present(acc)

val xs = List(3.0, 1.0)
val agg = ZipAgg(SumAgg(), CountAgg[Double]())
println(runLocal(xs, agg).toString)      // (4, 2)  — correct
println(runAggregator(xs, agg).toString) // 2       — one component
```

`runLocal`'s body is a verbatim copy of `runAggregator`'s (`std/aggregator.ssc`); only the module
boundary differs. Every intermediate step is correct when performed by hand in the importing file
— `agg.prepare`, `agg.monoid.combine`, an inline `foldLeft`, and `agg.present` on the folded
tuple all give the right tuples (verified step by step); only the imported whole loses the tuple.
A MapAgg-wrapped ZipAgg (`mean` in `std-aggregator.ssc`'s conformance case) presents CORRECTLY
through the same imported `runAggregator`, which is why the existing case never caught this — the
scalar `MapAgg.present` result masks whatever happens to the raw tuple. Suspicion, NOT verified:
the fused/`jit-foldleft-tc` foldLeft path (`EvalRuntime.evalFusedFoldLeft` appears in stack traces
whenever this shape runs) applied to the imported module's compiled block; the damage and the
cause are separate claims and only the damage is established here.

`std-aggregator-properties.ssc` deliberately AVOIDS the shape (its partition-invariance "whole"
is computed by an inline fold, with a comment citing this slug) so the laws gate stays green on
`int` while this is open — `gate: none` until a fix lands and the case can switch back to
`runAggregator` for the tuple-accumulator comparisons.

### Investigation 2026-08-31 — BOTH named suspects REFUTED, and the "imported vs local" framing is wrong

Measured, not reasoned. Every claim below is a control that was actually run.

**The two suspects in the report above are both eliminated.**

- *The fused/JIT foldLeft path:* the bug reproduces IDENTICALLY under `SSC_JIT=off`. Whatever
  truncates the tuple is not the JIT. (Reading agrees: `JitRuntime` never mentions `TupleV` at all,
  and `marshalAndRun` accepts only `InstanceV`/`FunV`/`StringV`/`MapV` as ref params — a tuple makes
  it DECLINE cleanly rather than corrupt. `VmCompiler`'s own foldLeft loop is likewise guarded to a
  `List[Int]` receiver with an Int/Double accumulator.)
- *The `jit-foldleft-tc` memo:* refuted by its own kill-switch — `SSC_JIT_FOLDTC=0` changes nothing.
  Reading agrees again: the memo only engages for `summon[M].member` shapes, and `runAggregator`
  writes `agg.monoid.empty`, which is not one.

**"Imported vs local" is not the axis.** With the report's own two-call file, whichever generic fold
runs FIRST returns `2` and the second returns `(4, 2)` — reverse the two `println`s and the wrong
answer moves with the position, not with the module boundary. The local/imported control that
opened this entry was reading an ordering effect.

**Nor is it call count, or caching.** Three IDENTICAL consecutive `runAggregator` calls are all
wrong (`2`, `2`, `2`), while the SAME call alone in a file is right — so it is not "first call cold,
later calls warm". And the answer is fully DETERMINISTIC per file: the same file gives the same
result on every one of three runs.

**What it actually tracks is the surrounding file, and no smaller rule survived.** Four candidate
triggers were each isolated and each then falsified by a counter-example in the same session:
inline-vs-`val`-bound aggregator (a minimal pair showed `(4, 2)` inline vs `2` when bound — then a
one-file version of that same pair printed `(4, 2)` BOTH ways); result bound to a `val` vs consumed
inline; a preceding scalar `runAggregator` call (does not poison); and `callFun`'s auto-tupling arm
(`case List(Value.TupleV(elems)) if f.params.length > 1 && elems.length == f.params.length` spreads
a single tuple argument across parameters — a real hazard of exactly this shape, but `agg.present`
called DIRECTLY with a tuple returns `(4, 2)`, so that arm is not firing here).

So: small, semantically irrelevant edits flip the outcome deterministically, which says a
COMPILE-TIME/whole-file analysis selects the path, not runtime state — but which one is not
established, and this entry will not guess a fifth time. **The next step is instrumentation, not
more permutation**: dump what the interpreter actually builds for the two members of a minimal
flipping pair and compare, per this repo's own `when-no-external-probe-can-differ-dump-the-ir`
lesson. Status stays `open`; the damage is confirmed and unchanged, the cause is still unknown, and
the two suspicions the original entry offered are now known to be wrong rather than merely unproven.

## sortby-on-a-non-int-key-sorts-lexicographically-not-numerically — `List.sortBy` on a `Double` key sorts as if the keys were strings

<!-- status: fixed
     lane: int
     kind: bug
     area: runtime
     gate: tests/conformance/std-aggregator-approx.ssc
     reported-by: claude-code
     reported-at: 2026-08-30
     confirmed: yes
     fixed-in: 237e31756 -->

Found landing `std/aggregator.ssc`'s `TDigestMonoid` (`specs/aggregation-algebra.md` §6.3), whose
`combine`/`quantile` both `sortBy(c => c.mean)` (a `Double` field). Minimal repro:

```scalascript
case class P(x: Double)
val xs = List(P(1.0), P(10.0), P(2.0), P(100.0), P(20.0))
println(xs.sortBy(p => p.x).map(p => p.x))
```

gave `List(1, 10, 100, 2, 20)` under `--v1` — sorted as if the keys were the strings `"1"`,
`"10"`, `"100"`, `"2"`, `"20"`; `int`/`native` and `run-jvm` all gave the correct `List(1, 2, 10,
20, 100)`. Root cause found directly in the source, not inferred: `DispatchRuntime.scala`'s
`sortBy` had a fast path that special-cased `Value.IntV` keys for a real numeric sort (`java.util
.Arrays.sort` on `Long`), and fell back, for every OTHER key type, to converting each key to its
`Value.show` STRING and sorting those lexicographically (`Comparator.comparing[..., String]`) —
correct for genuinely `String`-keyed sorts, silently wrong for `Double` (and any other non-`Int`
numeric type, which all fell into the same slow path).

**FIXED**: added a second fast path, checked before the string fallback, for the case where every
key is `Value.IntV` or `Value.DoubleV` (i.e. numeric, just not exclusively `Int`) — sorts by the
`Double` value via `Comparator.comparingDouble`. The original all-`Int` fast path is untouched
(kept for exact `Long` precision, no unnecessary `Double` coercion); only genuinely non-numeric
keys (`String`, `Boolean`, …) still take the string-comparison path, which is correct for them.
`TDigestMonoid`'s conformance case now passes on `int` with no `known-red` needed.

## math-object-is-missing-log-and-other-transcendental-functions — `math.log` throws "No method 'log' on InstanceV(math(...))" under `--v1`

<!-- status: fixed
     lane: int
     kind: bug
     area: runtime
     gate: tests/conformance/std-aggregator-approx.ssc
     reported-by: claude-code
     reported-at: 2026-08-30
     confirmed: yes
     fixed-in: 237e31756 -->

Found landing `std/aggregator.ssc`'s `HLLAgg` (`specs/aggregation-algebra.md` §6.1), whose
linear-counting correction needs `math.log`. `BuiltinsRuntime.scala` wired the `math` object's field
map from a fixed list — `sqrt`/`abs`/`pow`/`max`/`min`/`floor`/`ceil`/`round`/`Pi`/`E` — with no
`log` (or `exp`, `sin`, `cos`, and the rest of the transcendental functions) anywhere in the file, on
either the field map or as an underlying `math.log` global to wire in. `int`/`native` both call
`math.log` in other contexts already (e.g. the reference front) without issue — this was specific
to the v1 interpreter's own `math` object being an incomplete, hand-maintained list rather than
delegating to the host's full `scala.math`/`java.lang.Math`.

**FIXED**: added `QualifiedName("math.log")`/`QualifiedName("math.exp")` native intrinsics
(`Core.scala`, mirroring `math.sqrt`'s exact Double/Long-coercion shape) and wired both into the
`math` object's field map (`BuiltinsRuntime.scala`). `sin`/`cos`/`log10` and the rest of
`scala.math`/`java.lang.Math` remain unregistered — real, separate follow-up work if a caller needs
them, not claimed fixed here.

## array-tabulate-lambda-loses-a-sibling-top-level-def-cross-module — `Array.tabulate`'s callback throws `Undefined: <name>` for a sibling top-level function, but only when the calling class is imported from a different module

<!-- status: wontfix
     lane: int
     kind: bug
     area: runtime
     gate: none
     reported-by: claude-code
     reported-at: 2026-08-30
     confirmed: no -->

Originally filed landing `std/aggregator.ssc`'s `CMSMonoid.add` (§6.2), suspecting that computing a
hash INSIDE a lambda passed to `Array.tabulate` broke when the calling class was cross-module.
Minimal repro as filed, `std/x.ssc`:

```scalascript
def helper(x: Int): Int = x + 1

class Thing():
  def go(x: Int): Array[Int] =
    val hv = helper(x)
    Array.tabulate(3)(c => if c == hv then 99 else c)
```

was claimed to throw `Undefined: helper` under `--v1` once `Thing` was imported from a different
file and used from there. Worked around in `CMSMonoid.add` at the time by building the copy via
`Array.tabulate` with no sibling call inside its lambda and mutating the target cell in an ordinary
`for` loop instead.

**RE-INVESTIGATED 2026-08-30, could not reproduce**: neither the exact repro above (`[Thing]
(std/x.ssc)`, cross-file, `--v1`) nor a faithful reconstruction of `CMSMonoid.add`'s actual failing
shape (a case-class field access feeding a nested `Array.tabulate` calling a cross-module sibling
`def`, matching `strHash`/`CMSAcc`/`CMSMonoid` exactly) reproduces any error — both give correct
output. Re-tested against both the CURRENT interpreter and the exact pre-`class-body-val-field-
undefined-in-a-sibling-method`-fix binary (checked out at commit `d1a710850`, the commit that
originally introduced the `CMSMonoid.add` workaround) — neither reproduces on either binary. Most
likely explanation: this session found and filed two interpreter bugs on the same day while landing
the same file (`HLLAgg`/`CMSMonoid` in `std/aggregator.ssc`), and the `Undefined: helper` symptom
actually came from `class-body-val-field-undefined-in-a-sibling-method` (also active in the same
file at the same time) and got misattributed to `Array.tabulate`/cross-module during that
investigation. Closed `wontfix` (not a real defect, nothing to fix) rather than `fixed` (no code
changed here) or `open` (does not reproduce). `CMSMonoid.add` now uses the natural,
un-worked-around form — no regression from doing so, verified against the full conformance suite.

## class-body-val-field-undefined-in-a-sibling-method — a class-level `val` (not a constructor parameter) throws `Undefined: <name>` when read from a different method of the same class

<!-- status: fixed
     lane: int
     kind: bug
     area: runtime
     gate: tests/conformance/std-aggregator.ssc (known-red int)
     reported-by: claude-code
     reported-at: 2026-08-30
     fixed-in: 716ef77bd
     confirmed: yes -->

Found landing `std/aggregator.ssc`'s `HLLAgg` (§6.1), whose `hllMonoid`/`m` were originally declared
as class-body `val`s. Minimal repro, reproduces in a SINGLE file, no import needed at all:

```scalascript
class Thing(precision: Int):
  val doubled = precision * 2
  def get: Int = doubled

println(Thing(4).get)
```

gave `Undefined: doubled` under `--v1` — the field, declared on its own line in the class body (not
a constructor parameter, not a local `val` inside the one method that uses it), was invisible from
`get`, a different method of the SAME instance. Replacing `val doubled = precision * 2` with
`def doubled: Int = precision * 2` (a zero-arg method instead of a field) fixed it completely as a
workaround, and ran correctly (`8`). Every other class in `std/aggregator.ssc` already threaded its
per-instance state through constructor parameters (referenced directly, e.g. `MinAgg`'s `ord`)
rather than a class-body `val`, which is presumably why this had not surfaced before landing
`HLLAgg` — the first place this module needed to derive and CACHE a value from a constructor
parameter rather than use the parameter directly.

**FIXED**: `StatRuntime.scala`'s `Defn.Class` case scanned the class body for `Defn.Def` members
only (methods); a `Defn.Val` statement simply didn't match and was silently dropped — never
evaluated, never added to `typeFieldOrder` (the type-level field-name registry `resolveField`,
pattern matching, and `derives` all key off) or to any instance's field array. Fixed by collecting
`Defn.Val` statements separately (`valFieldStats`/`valFieldNames`), appending their names to
`typeFieldOrder` alongside the constructor's own param names, and evaluating each val's RHS *per
instance* (unlike the name list, a val's RHS can reference the constructor's own parameters, whose
values differ per instance) in a new `withValFields` helper invoked from every constructor path
(the no-defaults fast path and the defaults-fallback path alike), appending the results to both the
instance's `fieldsArr`/`fieldNames` and — since `typeFieldOrder` is now extended too — making the
field visible to external access (`obj.field`) as well as sibling methods. `typeFieldTypes`/
`typeFieldSchemas` (JSON-schema/OpenAPI/derives type metadata) are deliberately NOT extended for
body vals — inferring a val's type with no explicit annotation is separate work; their consumers
degrade safely (via `.getOrElse`/`.map` fallbacks) rather than crashing on the length mismatch.
`HLLAgg` now uses ordinary `val`s again, as originally designed, no `def` workaround needed.
Verified: 1898/1898 `backendInterpreter` sbt tests pass (no regressions from the `typeFieldOrder`
extension, which is consumed pervasively — pattern-matching destructuring, `derives`-synthesized
typeclasses, OpenAPI schemas, optics, named-arg construction), 118/118 smoke-ci checks green,
`std-aggregator`/`std-aggregator-approx`/`std-order` conformance unchanged (still `int`-green).

## map-dot-empty-reads-empty-as-a-literal-key-not-the-companion-accessor — `Map.empty` throws "No key 'empty' in map" under `--v1`

<!-- status: fixed
     lane: int
     kind: bug
     area: runtime
     gate: none
     reported-by: claude-code
     reported-at: 2026-08-29
     fixed-in: 064ca399a
     confirmed: yes -->

Found landing `std/aggregator.ssc`'s `groupByAgg` (§8 of `specs/aggregation-algebra.md`) —
`MapMonoid.empty = Map.empty` ran correctly via the default (native/v2) lane but threw under the v1
tree-walking interpreter specifically. Minimal repro:

```scalascript
val m: Map[String, Int] = Map.empty
println(m)
```

`bin/ssc-tools run --v1`:

```
[ERROR] [line 1, col 28] No key 'empty' in map
scalascript.interpreter.InterpretError: [line 1, col 28] No key 'empty' in map
	at scalascript.interpreter.DispatchRuntime$.dispatchMap$$anonfun$11(DispatchRuntime.scala:2651)
	at scala.collection.immutable.Map$EmptyMap$.getOrElse(Map.scala:255)
	at scalascript.interpreter.DispatchRuntime$.dispatchMap(DispatchRuntime.scala:2651)
```

`Map.empty[K, V]` (with an explicit type argument) failed identically. `Map[K, V]()` and bare
`Map()` both worked correctly on every lane, including `--v1`, and were the workaround used
throughout `std/aggregator.ssc`.

**FIXED**: `Map`, unlike `List`/`Vector`/`Array`/`Set`, was never wrapped in a companion
`Value.InstanceV` — it stayed CoreIntrinsics' raw `Value.NativeFnV` in `interp.globals`, registered
(like every native) in `pluginNativeNames`. `DispatchRuntime.dispatch` has a heuristic for exactly
this shape — "a parameterless native global used in receiver position is auto-called to its value,
then the member is re-dispatched on the result" (built for things like `args.length`) — and `Map`
unintentionally qualified: `Map.empty` auto-called `Map()` with zero args to build an empty map
VALUE, then re-dispatched `"empty"` on THAT value, which `dispatchMap`'s catch-all reads as a
STRING KEY lookup on the (now-empty) map, finding nothing.

Fixed in `BuiltinsRuntime.wrapMapCompanion`: wrap the raw native in a companion `InstanceV`, exactly
like `List`/`Vector`/`Array`/`Set` already are, so `Map.empty` selects a real field instead of
hitting the auto-call heuristic at all. Two things made this less than a one-line fix:

1. **Naming the companion literally `"Map"` collided with a different, legitimate mechanism** —
   `CallRuntime.callValue` has a pre-existing `case Value.InstanceV("Map", fields) =>` for an
   actual MAP VALUE that arrives as an `InstanceV` from elsewhere (a `Map[String, Any]`
   handler-param pass-through in `TypedHandlerWrapper`), treating `fields` as the map's own data
   entries. Tagging the companion `"Map"` too made `Map("a" -> 1, "b" -> 2)` match that case
   instead of the generic apply-field dispatch, misreading the companion's OWN `"empty"`/`"apply"`
   fields as map data. Fixed by tagging the companion `"MapCompanion"` instead — an internal marker
   never exposed to user code, so ordinary field/method dispatch (which doesn't special-case by
   type name except for a short reserved list `"MapCompanion"` isn't in) handles it exactly like
   `List`'s or `Set`'s companion.
2. **The wrapping didn't survive a later lazy plugin load.** The interpreter backend registers
   itself as one of `BackendRegistry`'s in-process backends, so `Interpreter.ensurePluginsLoaded`/
   `installPlugins` — triggered the first time a module references ANY plugin-only name — re-runs
   `installNativeIntrinsicEntries` over its OWN core intrinsics too (not just the triggering
   plugin's), which includes `Map`, resetting `interp.globals("Map")` straight back to a fresh raw
   `NativeFnV` and silently undoing `initBuiltins`'s wrapping. This reproduced ONLY cross-module,
   with the fresh native's identity confirmed via `System.identityHashCode` — the same interpreter
   instance, "Map" correctly wrapped right after `initBuiltins`, then found reset to a *different*
   raw `NativeFnV` closure by the time a later-loaded module's code ran. Fixed by calling
   `wrapMapCompanion` a second time from `BuiltinsRuntime.setupPluginCompanions` too (already called
   at the end of both plugin-loading paths, and already used for exactly this "re-wrap after a
   plugin (re)install" purpose — `DriverManager`/`Db`/`Graph`/`Source` etc. all follow the same
   guard-and-wrap pattern there) — idempotent, a no-op once `Map` is already the companion.

`std/aggregator.ssc`'s `MapMonoid.empty` reverted from the `Map[K, Acc]()` workaround back to
`Map.empty`, as originally designed. Existing usages of `Map.empty` elsewhere in this repo
(`std/dstreams.ssc`, `std/graphql.ssc`, `std/openapi.ssc`), all in *default parameter position*,
were never observed to be evaluated at runtime by an existing test — presumably why this went
uncaught until `groupByAgg` actually called it. Verified: 1898/1898 `backendInterpreter` sbt tests
pass, `std-aggregator`/`std-aggregator-approx` conformance PASS on `int` end-to-end (including the
real cross-module `groupByAgg` call that originally surfaced this).

## point-free-class-method-never-eta-expands-on-int — `obj.method` under `--v1` fails differently, same given-vs-class shape

<!-- status: fixed
     lane: int
     kind: bug
     area: runtime
     gate: none
     reported-by: claude-code
     reported-at: 2026-08-28
     confirmed: yes
     fixed-in: a948d71b8 -->

Companion to `v2/BUGS.md`'s `point-free-class-method-never-eta-expands-on-native` — same repro,
found on the SAME code (drafting `specs/aggregation-algebra.md`), but a genuinely different root
cause in this lane's own dispatch path. Do not assume the v2 fix covers this; fix independently.

Repro (`--v1` flag, i.e. `bin/ssc-tools run --v1 <file>.ssc`):

```scalascript
class ConstMonoid(z: Int):
  def empty: Int = z
  def combine(a: Int, b: Int): Int = a + b

def main(): Unit =
  val cm = ConstMonoid(0)
  println(List(1,2,3).foldLeft(cm.empty)(cm.combine))
```

Fails with `missing argument for parameter 'a'` (not `expected N, got M` — the native/v2 lane's
wording; this lane's failure mode is different because the underlying mechanism is different).
The same two workarounds from the v2 entry apply here too (a `given ... with` instance works; an
explicit lambda `(a, b) => cm.combine(a, b)` works).

**Root cause:**

1. A bare selection (`Term.Select`, no call) unconditionally dispatches with zero args —
   `EvalRuntime.scala:4045-4055` calls `DispatchRuntime.dispatch(qualV, method, Nil, env, interp)`
   with no eta-expansion attempt at this callsite, for ANY receiver.
2. For a real `class` instance (`Value.InstanceV`), this reaches `dispatchOrdinaryInstance`
   (`DispatchRuntime.scala:3357-3373`), which calls `pickArity(typeMethodMap, name, ..., argc=0)`
   (`DispatchRuntime.scala:3484-3490`). `pickArity`'s fallback
   (`map.getOrElse(name + "#" + argc, f)`) finds no `combine#0` entry and falls back to `f` — the
   REAL 2-arg closure — then unconditionally invokes it via `invokeTypeMethod`
   (`DispatchRuntime.scala:3369`/`3557-3562`) → `CallRuntime.callTypeMethod`'s `applyDefaults`
   path (`CallRuntime.scala:940`), which throws "missing argument for parameter 'a'" because no
   default exists for `a`.
3. For a `given ... with` instance, the method is instead stored as a plain field
   (`Value.FunV`) directly inside `Value.InstanceV`'s fields map (`GivenRuntime.scala:241-253`).
   Because `interp.typeMethods` has no entry for that dynamically-generated given-instance type
   name, dispatch falls through to `dispatchInstanceAfterMethods`
   (`DispatchRuntime.scala:3564-3596`) instead, whose no-arg branch correctly returns the raw
   multi-param `FunV` unevaluated (`case v => Pure(v)`, `DispatchRuntime.scala:3596`) — proper
   eta-expansion, by construction of how a given-instance happens to store its methods, not by a
   designed-and-tested eta path.

Fix shape: `dispatchOrdinaryInstance`/`pickArity` need an explicit "resolved arity doesn't fit
zero args → return the unapplied closure" branch, analogous to what
`dispatchInstanceAfterMethods`'s field-lookup path already does for given-instance `FunV`s. No
test anywhere in this module covers point-free access to a plain class's own instance method —
untested gap, not a regression.

**FIXED 2026-08-29 (`a948d71b8`).** Added `DispatchRuntime.dispatchBareSelection`, used only by
`EvalRuntime`'s `case sel: Term.Select` (the one call site that already knows, unambiguously, that
this is a bare selection with no call): it looks up the bare method the same way `pickArity` would,
and when the resolved arity does not fit zero args AND no `name#0` overload exists (`pickArity`
would otherwise have silently fallen back to the real wrong-arity closure), returns it unevaluated
instead — the eta-expansion. Unlike the v2/native lane's twin fix, no "applied" flag was needed:
`Term.Select` (bare) and `Term.Apply(Term.Select(...), args)` (a call, even with an explicit empty
argument list) are different Scalameta AST node shapes evaluated at different sites in this lane,
so an applied `r.k()` never reaches `dispatchBareSelection` at all and keeps throwing the same
`missing argument for parameter` error as before — regression test:
`EtaExpandClassMethodTest`'s applied-call rows. New coverage (the gap noted above):
`v1/runtime/backend/interpreter/src/test/scala/scalascript/EtaExpandClassMethodTest.scala` (7
cases — the point-free eta path, the original foldLeft repro, an applied wrong-arity refusal, an
applied right-arity call, a real nullary method, and the given-instance control).

## bugs-index-gate-reads-prose-for-a-stale-open-entry-not-the-header — 18 entries carry a fix sha and read as open

<!-- status: open
     lane: apparatus
     area: conformance
     kind: apparatus
     gate: none
     found-by: claude-code
     found-at: 2026-08-17
     repro: the census in the body
     confirmed: yes -->

The gate DOES look for an entry whose fix has landed while it still reads `open`. What it keys on is
BODY PROSE: the `STALE_HINT` pattern in `tests/e2e/bugs-index-gate.sh` — read it there rather than
here, and the next paragraph says why it is not quoted — plus a commit id in the body that is already
an ancestor. Deliberately narrow, and the comment beside it says why: "a broad `pending` would match
half the board, and a check that cries wolf is not read."

WHAT IT NEVER READS is the header. `fixed-in:` and `fixed-at:` under `status: open` are invisible to
it, so an entry can name the very commit that fixed it and stay open with the gate green. That is not
a hypothetical: `interp-declaring-a-plain-extern-class-member-breaks-it` carried
`fixed-at: 2026-08-16` and `fixed-in: bdd48657d` under `status: open`, its TWIN — fixed by that same
commit — said `fixed`, and every run of the gate printed `stale-looking open entries: 0`.

WHY ONE OF THE PAIR AND NOT THE OTHER: the `status` key sits on the same line as the opening `<!--`,
while every other key is indented on its own line. An edit written for the indented shape silently
misses it. That is the second time this exact line has been missed here.

THE CENSUS, stated as a question rather than a verdict — across the nine BUGS files, 18 entries have
`fixed-in:` or `fixed-at:` in the header without `status: fixed`:

    BUGS.md                      4
    v2/BUGS.md                  11
    scripts/BUGS.md              2
    tests/conformance/BUGS.md    1

ONLY ONE IS ESTABLISHED TO BE WRONG — the one above, checked against its commit before it was
touched. The other 17 are NOT claimed to be stale: a commit id on an open entry can be honest — a
partial fix that closed one of two claims, a census row citing somebody else's work, or an entry
reopened after a regression. Reading 18 rows as 18 defects is the mistake this entry exists to
prevent; each needs its own look by whoever owns that area.

MEASURED WHILE WRITING THIS, and it sizes the fix better than the census does: this entry TRIPPED the
heuristic twice, and the second time for a reason worth keeping. The first draft used a phrase that
happened to match. The rewrite then QUOTED the trigger phrases in order to explain them — and that
was enough on its own, because the pattern cannot tell a use from a mention. An entry documenting a
prose rule cannot state the rule without breaking it, so this one now points at the pattern in the
gate instead of reproducing it. The header keys have no such failure mode: `fixed-in:` means the same
thing whether an entry is using it or describing it. That is the argument for reading them.

WHAT A HEADER CHECK COULD SAY WITHOUT GUESSING: not "this must be fixed", but "this header claims a
fix and a status that disagree — say which". The honest cases then need one line of prose or a
distinct key; the accidental ones become impossible to leave unnoticed. Costed, not built: the rule
belongs with whoever owns the gate, and this is the measurement they need to size it.

**MOVED HERE FROM `tests/BUGS.md` 2026-08-18**, on `tests/e2e/area-map-gate.sh`'s verdict: the
`fixed-in` commit touches code this board owns. Filed-on-board is where the module's fixers read.

## int-concat-nonlist-builds-a-tuple — `List(1,2) ++ 5` was a TUPLE here and a list everywhere else

<!-- status: fixed
     kind: bug
     fixed-in: 3a95a474e
     lane: int
     area: runtime
     gate: tests/conformance (int lane) + backendInterpreter/testFast
     found-by: claude-code
     found-at: 2026-08-16 -->

**FIXED.** A list `++` a non-list appends that one element, which is what every lane that answers at
all already did. The binary-operator table had arms for (List, List), (Set, Set), (Map, Map) and the
tuple shapes, and no (List, anything-else) — so the pair fell to the final `case _`, which builds
`TupleV(lhs :: rhs :: Nil)`.

**IT IS THE SAME TRAP AS `int-string-concat-operator-builds-a-pair`, ONE TYPE DOWN.** That entry
added the `String ++ String` arm to this very table, and its comment warns that a fix in method
dispatch never fires for the infix operator. The table's fall-through is a pair-builder, so every
combination nobody thought of becomes a tuple silently rather than an error.

**EVERY CELL MEASURED against the reference lane, because the new arm is broad:**

    rhs        reference (bin/ssc-tools run)   this lane, before      after
    5          List(1, 2, 5)                   (List(1, 2), 5)        List(1, 2, 5)
    (3, 4)     List(1, 2, (3, 4))              (List(1, 2), 3, 4)     List(1, 2, (3, 4))
    "ab"       List(1, 2, ab)                  (List(1, 2), ab)       List(1, 2, ab)
    ()         List(1, 2, ())                  List(1, 2)             List(1, 2, ())
    Set(7)     List(1, 2, Set(7))              (List(1, 2), Set(7))   List(1, 2, Set(7))
    Map(…)     List(1, 2, Map(a -> 1))         (List(1, 2), Map(…))   List(1, 2, Map(a -> 1))

Two rows were worse than the headline: a TUPLE right operand was SPLICED, so `++ (3,4)` produced a
three-element tuple rather than either sensible answer, and `()` was SWALLOWED by the generic
`(_, UnitV) => Pure(lhs)` arm. Both now match the reference.

**The jvm lane is deliberately untouched.** It compiles to real Scala, and its answer is a COMPILE
error — `Found: Int, Required: IterableOnce[Int]` — which is the strictest of the five lanes and not
a wrong answer. A census was run before the fix: native, bytecode and js all wrap, int built a
tuple, jvm refuses at compile time.

**Regression:** the conformance corpus 367 passed / 0 failed (the int lane is 238 of those cases),
and `backendInterpreter/testFast` — 1615 tests, 0 failures.

## int-a-double-in-a-signature-falls-off-the-jit — 250×, and the `Long` twin is the control

<!-- status: open
     lane: int
     area: codegen
     kind: perf
     reported-by: claude-code
     reported-at: 2026-08-15
     confirmed: yes
     gate: none -->

**A user function whose signature mentions `Double` is not JIT-compiled, and the call costs
microseconds where the identical `Long` function costs nanoseconds.** Two programs that differ in
nothing but the type:

```scalascript
def idi(x: Long): Long = x          def idd(x: Double): Double = x
def workload(): Long =              def workload(): Double =
  var sum = 0L                        var sum = 0.0
  var i = 0                           var i = 0
  while i < 2000 do                   while i < 2000 do
    sum = sum + idi(1L)                 sum = sum + idd(1.0)
    i = i + 1                           i = i + 1
  sum                                 sum
```

```text
ssc bench --machine --warmup-time 1500 --reps 20, three runs each, ms per workload()
  Long -> Long        0.0120   0.0112   0.0014      ~6 ns per call
  Double -> Double    3.31     3.13     2.99        ~1.6 us per call     250-2000x
```

**It is the SIGNATURE, not the arithmetic.** A loop doing `sum = sum + 1.0` with no call at all runs
at 12 ns per iteration — Double arithmetic is JIT'd fine. Adding the Double-typed call is what
costs, and either position is enough:

```text
  Long   -> Long      0.0281   0.0071
  Double -> Double    5.45     1.36
  Long   -> Double   13.9     11.1      (the body also calls .toDouble)
  Double -> Long      2.79     3.77
```

**Why it matters beyond one row.** `int` is the reference lane for every table in this repository —
`bench/BASELINE.md`'s three-version comparison divides by it — and this is the mechanism behind the
bimodality recorded there on 2026-08-14: the `ssc` column read 0.114 ms and 0.0019 ms for the same
`list-fold` in different rounds, 60×, while its six neighbours in the row moved by 1.4×. A JIT that
covers or does not cover a call, depending on shape, produces exactly that.

**Not a v2 problem, and worth saying because a neighbouring spec looks like it.**
`specs/v2-emitter-unboxed-doubles.md` is about `JvmByteGen.canDouble` in v2's emitter. This entry is
v1's own JIT: v2 runs the same `Double -> Double` call in about 48 ns, twenty times faster than v1.

**What is NOT established here:** where in v1's JIT the refusal happens, and whether the fix is a
`canParamDouble` twin of the existing unboxed-long entry or something wider. The probes are
`Long/Double` × `param/return`, which localises the trigger to the signature and no further. A gate
for this should assert the SHAPE — the `Double` twin within a small factor of the `Long` one — not a
time, so a loaded host can still read it.

## int-no-paren-sibling-method-is-undefined — `Undefined: twice` for a sibling declared without parens

<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     fixed-in: 0a4da7284
     gate: tests/e2e/no-paren-sibling-gate.sh -->

**FIXED.** The sibling-call fix (c04de5df1, b70a1e92c) keyed on `bareAppliedNames`, which collects
`Term.Apply` heads — so `twice()` was found and `twice`, the no-paren form, was not. One construct,
three lanes, before the fix:

    case class Box(n: Int):
      def twice: Int = n * 2
      def quad(): Int = twice * 2      v1: Undefined: twice   native: 12   jvm: 12

The interpreter is the corpus golden, so the disagreement quietly made the golden the wrong lane.

Binding the name the way applied siblings are bound does not work: the body uses it as a VALUE, so
a `NativeFnV` makes `twice * 2` multiply a function. Binding the RESULT would evaluate it eagerly
on every dispatch — running the side effects of a branch never taken, and recursing forever on
`def a: Int = if p then 0 else a`. So `bindSiblings` binds the RECEIVER when a body mentions a name
that is a parameterless method of the type, and the name-lookup miss path in `EvalRuntime` resolves
it through the ordinary dispatch. That path runs only where the answer was already `Undefined`, so
no working program can change behaviour, and each mention invokes, as Scala specifies.

Routed through `DispatchRuntime.dispatch`, not `callTypeMethod`: a StatRuntime-built instance keeps
its values in the positional `fieldsArr` and leaves the map empty, so calling the method directly
resolved `twice` and then died on `n` inside it. `zeroArgMethodNames` is keyed on the method map's
IDENTITY rather than the type name — `typeMethods` fills as definitions evaluate, and a name-keyed
cache consulted mid-registration would pin an empty set forever.

Gate run against the pre-fix binary to prove it can go red: 2 of 5 cases fail there (no-paren,
mixed), the other 3 pass. It also pins the two sides that keep the fix honest — a local `val` still
shadows a same-named method, and a genuine typo is still `Undefined`. Smoke 67/68, 319.8s of 600s;
the one red is `launchers-not-dead` refusing to pass on the empty `bin/` of a fresh worktree.

Complete for this family, verified after a rebuild: the caller x callee paren matrix passes in all
four combinations, as does a no-paren method reading a no-paren method that itself reads a third.
An earlier note here called the fix partial; that was measured on a stale `bin/` and is retracted
in `int-no-paren-def-leaks-into-globals`.


## int-no-paren-def-leaks-into-globals — NOT A BUG, kept so the same wrong reading is not filed twice

<!-- status: wontfix
     kind: apparatus
     lane: int
     area: runtime
     gate: tests/e2e/no-paren-sibling-gate.sh -->

**REFUTED — there is no leak.** This entry claimed that a parameterless `def` inside a class or
object body was registered as a global, on the evidence that

    case class T(n: Int):
      def a: Int = n + 1
    def main() = println(a)          prints <native:a> at exit 0

The probe named the method `a`, and `a` is a BUILT-IN — the HTML anchor element, beside
`"div", "span", "p", "em", ...` in `BuiltinsRuntime.scala`. The control that settles it was never
run when the entry was written:

    def main() = println(a)          with no class in the file at all → still <native:a>
    def zzqqwx …                     a name that is not a builtin     → Undefined: zzqqwx  correct

So the value came from the builtin table, not from the class body, and nothing about parameterless
defs was involved. A def WITH parameters looked "correctly invisible" only because no builtin is
named `plus`.

The second half of the entry — that a no-paren method read from another no-paren method stays
broken — was measured against a STALE `bin/`: the checkout had been fast-forwarded to a commit
containing the fix but never rebuilt. After `scripts/sbtc installBin` the full caller x callee
matrix passes:

    caller ()   callee ()     20        caller ()   callee bare  20
    caller bare callee ()     20        caller bare callee bare  20

so `int-no-paren-sibling-method-is-undefined` is complete, not partial. Those four shapes plus the
chained case are now IN that gate, together with an absent-state control that fails if any probe
name resolves without a class to define it — verified to go red by feeding it `a`.

Two working rules this cost, both already written down and both skipped: run the ABSENT-state
control, because a probe subject reachable WITHOUT the thing tested measures nothing; and rebuild
before measuring in a checkout you have only fast-forwarded.

## int-sibling-call-missed-the-single-arg-dispatch-site — `Undefined: withHeader` from a one-argument method

<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     fixed-in: b70a1e92cc2c368a09170017eb25db6b04f5640d
     gate: tests/e2e/session-roundtrip-gate.sh -->

`Response.withSession(payload)` calling `withHeader` — siblings on the same case class — raised
`Undefined: withHeader` on the v1 lane even after `c04de5df1` made sibling calls work.

**A SECOND DISPATCH SITE, and it took a trace to see it after three wrong guesses.** `bindSiblings`
lives in `invokeTypeMethod`; the single-argument fast path in `DispatchRuntime` (~line 1090) calls
`interp.callTypeMethod1(fn, fields, arg)` DIRECTLY and never goes through it. `withSession(payload)`
takes one argument, so it took that path. It now binds siblings the same way, at the same cost — the
map is returned unchanged unless the body applies a bare name that is a real sibling, cached per
body.

**The three guesses, each disproven by one probe, are the useful part of this entry:**

| guess | probe | verdict |
|---|---|---|
| "v1 cannot resolve siblings at all" | a local case class | works — fixed by c04de5df1 |
| "it is the import boundary" | a class in a module reached by `[R](./m.ssc)` | works |
| "it is the builtin twin, `Response` has one" | a NEW class added to `std/http.ssc`, exported, imported | works (12) |

Every one of those was a plausible story about a real mechanism, and each cost a build. What ended it
was putting a print inside `bindSiblings` and seeing NOTHING for the failing call — a negative
observation, which no amount of reading would have produced. Arity, the thing none of the guesses
mentioned, was the whole answer.

The workaround in `std/http.ssc` — `withSession` building its Response inline — is removed, and its
comment now describes the resolved cause rather than the third guess.

## int-imported-module-mutable-registry-not-shared — a registry mutated by the importer stays empty
<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     gate: tests/e2e/int-imported-registry-gate.sh
     fixed-in: 0009b24f4 -->

**FIXED 2026-08-06.** The entry's own four-line repro prints `1` on int, matching native and js, and
the int conformance lane is 358/358 across eight shards.

**The stated root cause was WRONG, and measuring it is what found the real one.** This entry said
"Same root cause as `int-object-var-mutation-does-not-persist`". That one was fixed earlier the same
day (`6683a457f`, "an object's var now persists") and this SURVIVED it — on the same build:

```
object … var hits, no import      int 1 2   native 1 2     <- fixed by 6683a457f
this entry's imported registry    int 0     native 1       <- still broken
```

Two different defects. Three probes then located the real boundary, and `package:` is not part of it:

| how the importer reaches the state | int | native |
|---|---|---|
| imported module WITHOUT `package:` | 0 | 1 |
| imported module WITH `package:` | 0 | 1 |
| through the OBJECT binding `[Reg]` | **0** | 1 |
| through a top-level FUNCTION `[addVia]` | **1** | 1 |

The same module, the same `var`, differing only in how the importer reaches it. So the boundary is
the IMPORT, and the wrapper that already existed for it covered FUNCTION exports only:
`SectionRuntime.scala` bound `case fv: Value.FunV` to run in the child interpreter and dropped
everything else into `enrichFnClosures`, the import-time SNAPSHOT. An imported object's method
therefore mutated a copy, and a read through the same object read the copy back.

Fix: when the child declares mutable state, an imported `InstanceV`'s FUNCTION FIELDS are rebound
the same way, one by one. The object's shape — its tag, its non-function fields — is untouched, so
every consumer that only reads it is unaffected.

**A better repro than the one below**: two files, twelve lines, no `std/` module, no plugin, no
package. It is the gate's `object` row.

**The gate has four rows and three of them exist to make the fourth meaningful** — the function row
and the no-package row were the probes that located the boundary, and the `pure` row is a module
with NO mutable state whose exports must KEEP the snapshot binding, so a fix that widens the
rebinding too far fails it. Checked in both directions: reverted, exactly the two defect rows fail
and both controls stay green.

### Original report (superseded 2026-08-06)

**Status:** OPEN (found 2026-07-28 by `v2-native-import-graph` while re-measuring
`v2-native-scala-import-parse-only-noop`; the native lane is the correct one here).

**Symptom.** Registering into a mutable registry defined by an imported module had no effect on the
v1 INTERPRETER, while the native and JS lanes both saw the registration.

**Reproduce** — four lines, no plugin, no Scala-style import:

````markdown
[NamedHandler, HandlerRegistry](std/mapreduce/handlers.ssc)

```scalascript
HandlerRegistry.clear()
HandlerRegistry.register(NamedHandler("emit", (s: String) => List(s)))
println(HandlerRegistry.registeredNames().length)
```
````

| lane | result |
|---|---|
| `bin/ssc run` (native) | `1` |
| `ssc-tools emit-js` + node | `1` |
| `ssc-tools run --v1` (INT) | **`0`** |

**Why it matters.** INT is the conformance suite's reference lane and the corpus contract's golden
probe, so a case built on this pattern is graded against the one lane that gets it wrong. The
larger fixture it was found in (`V2TuplePatternCliTest`'s map-reduce hoist) fails on INT for the
same reason: `HandlerRegistry: no handler registered for 'emit'` after registering it.

**Fix direction / relation.** Same family as
`imported-builtin-native-runs-callback-in-defining-interpreter` — module-level mutable state and
which interpreter instance owns it. Not taken by `v2-native-import-graph`: that claim's paths are
`v2/bin/`, and this is the v1 interpreter.

**Believed to be the same root cause as `int-object-var-mutation-does-not-persist`** — REFUTED above: (linked 2026-08-05, and moved here
from `v1/runtime/backend/js/BUGS.md` at the same time — it carried `lane: js` while every symptom and
the fix are the interpreter's, which is what the layout rule routes on).

That entry is this one REDUCED: no imports, no registry, no plugin —

```scalascript
object org:
  var hits = 0
  def bump(): Int = { hits = hits + 1; hits }
org.bump(); org.bump()      int: 1 1     native: 1 2
```

A `var` inside an `object` does not keep its value on this lane, and `std/mapreduce/handlers.ssc`'s
registry is exactly that: object-level mutable state reached through an import. Re-measured 2026-08-06 after that entry was fixed: this one still gave `0` on int, which is what
showed the two are different defects.

Keep both: this one carries the field report and the reason it matters — INT is the conformance
suite's reference lane, so a case built on this pattern is graded against the lane that gets it
wrong — while the other carries the ten-line reduction, the six measured facts and the gate
(`tests/e2e/object-var-mutation-gate.sh`).

## two-fronts-disagree-on-name-resolution — the answer changes with unrelated content in the file

<!-- status: open
     kind: bug
     lane: multi
     area: front
     fixed-in: -
     gate: tests/e2e/sibling-method-gate.sh -->

A bare call where a top-level function and a method share a name resolves DIFFERENTLY on the native
lane depending on what else is in the file. Same construct, same build, measured 2026-08-05:

| file | native | v1 |
|---|---|---|
| the three-class fixture in `sibling-method-gate.sh` | **7** (the method) | 100 |
| the same `Shadowed` class alone in a file | **100** (the top-level fn) | 100 |

The cause is not the shadowing rule. It is which FRONT lowered the file:

    $ ssc run big.ssc     ->  "F did not lower this file"   -> falls back  -> 7
    $ ssc run small.ssc   ->  (no note)                     -> F lowered   -> 100

So two fronts inside ONE lane disagree about name resolution, and which answer a program gets
depends on whether F happened to accept it — for reasons unrelated to the names involved. That is
worse than a lane divergence: a lane is a choice, this is not.

**This entry replaces `int-global-fn-shadows-a-same-named-method`, which described the same
measurement as a v1-vs-native disagreement.** That framing was wrong in a way that matters: it
pointed at the interpreter, and the interpreter is consistent — it answers 100 either way. Both of
the numbers I originally recorded were real; what I had not seen was that the native one is not
stable.

**Not caused by the sibling-call work**, checked rather than assumed: the commit before `b70a1e92c`
gives 100/100 on the small file too, so nothing here regressed when siblings started binding.

**MECHANISM FOUND 2026-08-06, and it is not "unrelated to the names".** F resolves a bare applied
name against top-level defs and @-cells only. A sibling method call whose name has NO top-level twin
is therefore an unbound global to F, and F declines the WHOLE FILE over it. So:

    def size(): Int = 100
    case class Shadowed(n: Int):
      def size(): Int = n
      def viaGlobal(): Int = size()
    def main() = println(Shadowed(7).viaGlobal())

F lowers this — `size` HAS a top-level twin — and answers 100. Add a class that is never called:

    case class Box(n: Int):
      def twice(): Int = n * 2
      def quad(): Int = twice() * 2        // `twice` has no top-level twin

and F declines with `unbound global: (global twice)`, the reference front lowers it instead, and the
answer to the UNCHANGED first question becomes 7. Two files differing only by an uncalled class,
and the reported value of a different class flips. That is the whole instability, stated as a rule.

**AND THE ORACLE SAYS WHICH IS RIGHT, so the "cannot pin it" caveat below is now retired.** Real
Scala 3.8.4, same source, `scala-cli run`:

    def size(): Int = 100
    case class Shadowed(n: Int):
      def size(): Int = n
      def viaGlobal(): Int = size()
    @main def run(): Unit = println(Shadowed(7).viaGlobal())     =>  7

The member wins over a same-named top-level def. So:

    reference front   7     CORRECT
    F               100     wrong
    v1 interpreter  100     wrong  — and v1 is the corpus golden

Two separate defects, then, not one disagreement:

1. **F declines any file containing a sibling call with no top-level twin.** That is the coverage
   gap, and it is what makes the answer depend on unrelated file content. Same reason F declines the
   `Box`/`twice` shape at all, so it shares a cause with the F decline census.
2. **F and v1 both resolve the bare call to the GLOBAL where Scala resolves it to the MEMBER.**
   Independent of (1) and observable without it — `withglobal.ssc` above needs no second class.

Fixing (1) alone would make the answer STABLE and still WRONG (100 everywhere). Worth saying
explicitly, because "the fronts now agree" would read like the bug is closed.

`sibling-method-gate.sh` still does not assert this row — but the reason has changed. It is no
longer that either number might be the contract; it is that 7 is the contract and two of the three
producers do not meet it yet, so pinning it would be filing the same defect a second time as a red
gate. Pin it with the fix, not before.

## int-case-class-method-cannot-call-a-sibling-method — `Undefined: twice` from inside the same class

<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     fixed-in: c04de5df12518209a5ea3ce7260bbf86675c02cf
     gate: none -->

**FIXED.** A method body is evaluated with the instance's FIELDS as its environment, which is why
`n` resolved and `twice` did not. `DispatchRuntime.bindSiblings` now binds the type's own methods
that the body calls BARE, driven by `Interpreter.bareAppliedNames` — the applied names are computed
once per body and cached by tree identity, like `methodUsesThis` beside it, so a body that calls no
bare name returns the same map it was given. Binding the whole method table per dispatch would have
put an allocation on the hottest path in the interpreter.

A/B on one tree: reverted and rebuilt gives `Undefined: twice`, fixed gives 12, and the native lane
answered 12 throughout. Gate `tests/e2e/sibling-method-gate.sh`, wired; smoke 67/67.


Eight lines, measured 2026-08-05:

```
case class Box(n: Int):
  def twice(): Int = n * 2
  def quad(): Int = twice() * 2

def main() =
  println(Box(3).twice())
  println(Box(3).quad())
```

    ssc-tools run --v1   ->  6, then [ERROR] [line 3, col 21] Undefined: twice
    ssc run              ->  6, 12

The SAME method resolves from outside the class and not from a sibling method's body. `n` resolves
fine in both, so it is not the instance scope generally — it is specifically another method of the
same class.

**Found by hitting it, not by looking for it.** `Response.withSession` in `std/http.ssc` was written
as `withHeader("Set-Cookie", …)`, the obvious spelling, and died on v1 with `Undefined: withHeader`
while working on the native lane. Sidestepped there by inlining the one-line body
(`Response(status, headers + (…), body)`), which is why that file now builds the response directly
and says so — but the workaround is in std, and any user writing an ordinary case class hits this
with nothing to tell them why.

Not fixed here: this is method resolution in the interpreter, well outside the session work that
surfaced it, and it deserves its own measurement rather than a patch bolted onto that commit.

## int-object-var-mutation-does-not-persist — a `var` inside an `object` never changes

<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     fixed-in: 6683a457f
     gate: tests/e2e/object-var-mutation-gate.sh -->

**FIXED 2026-08-06. It was TWO defects that each masked the other, which is why both obvious fixes
had been tried and both looked refuted.**

The live assignment path is neither of the sites anyone instrumented. `Term.Assign` never reaches
`EvalRuntime.eval` at all — `BlockRuntime.evalBlock` destructures a block's statements and handles
the assignment inline, evaluating only the RHS. An `[eval]` trace on the interpreter entry shows it
plainly: for `var k = 0; k = k + 1; println(k)` the nodes are `TermBlockImpl`, `LitIntImpl`,
`TermApplyInfixImpl`, `TermApplyImpl` — no `Assign`. That site did

    local(x) = v; interp.globals(x) = v

writing straight to globals and never consulting `objectVarStores`, which is exactly the "the write
LEAKS OUT of the object" fact recorded above.

Routing it through `ObjectVarEnvView.assign` alone changes NOTHING, and that is the trap. `evalBlock`
does not chain to its caller's env: it copies the entries that differ from `interp.globals` into a
fresh HashMap and wraps that in a `MutableEnvView`. `ObjectVarEnvView` answers `MarkerKey` from
`get`/`getOrElse`/`contains` but not from `iterator`/`foreachEntry`, so the copy drops the marker and
the router cannot tell it is inside an object. Equally, carrying the marker alone changes nothing
while the assignment still writes globals directly — which is precisely refuted-fix #5 above.

Both together fix it. `carryObjectVarMarker` copies the marker onto the new map on both copy
branches, and the nine assignment writes in that function route through the documented decision
point instead of `interp.globals`.

    object, plain `hits = hits + 1`      1 1 0  ->  1 2 2
    object, compound `hits += 1`         1 1 0  ->  1 2 2
    plain local var (control)                6  ->      6

The compound form is a SEPARATE path (`x += n` parses as `Term.ApplyInfix`, not `Term.Assign`) and
carried the identical defect; fixing only the plain form would have read as fixing both. Both are in
the gate now, which was written to fail once the gap closed and did exactly that — it now holds int
to the same answer as every other lane, and was re-run against the pre-fix interpreter to confirm it
still goes red (2 failures, controls green).

Correction to fact 3 above, found on the way: `SSC_FASTTIER=0` disables nothing. The flag is read as
`!sys.env.get("SSC_FASTTIER").contains("off")`, so only the literal `off` turns it off — a value the
tier ignores looks exactly like a ruled-out hypothesis. Re-measured with `off`: same `1 1 0`, so the
conclusion held while the evidence for it did not.

Smoke 68/69 at 292.3s of 600s, all four corpus lanes green; the one red is `launchers-not-dead` on a
fresh worktree's empty `bin/`.


**Ten lines, no imports, no front matter** (measured 2026-08-05):

```scalascript
object org:
  var hits = 0
  def bump(): Int =
    hits = hits + 1
    hits
  def peek(): Int = hits
def main() =
  println(org.bump()); println(org.bump()); println(org.peek())
```

```
int    : 1  1  0        native : 1  2  2
```

`bump()` returns 1 forever and `peek()` reads the value `hits` had before either call. The writes are
not merely lost — they land where the reads cannot see them. No error, exit 0.

**This entry was filed as an IMPORT bug and that was the symptom, not the defect.** The original
repro was a module imported with `package: org` in its front matter losing its state, and the
reduction ran through three layers before the floor:

1. `package:` in the front matter, and only `package:` — `name:` alone behaves correctly.
2. `Parser.scala:87` `wrapSectionInPackage` rewrites every section of such a module into a synthetic
   `object org: …`. So the module's top-level `var` becomes an object MEMBER.
3. …and mutating an object member does not persist, with or without imports. The import path was
   never the cause; it was the only place anyone had noticed.

**Two things found on the way, both worth keeping:**

- `ScalaNode.fold` does **not** walk a tree. It is `f(n.tree)` — the root node only. Every caller
  written as if it recursed is inspecting one node. That is how
  `moduleDeclaresTopLevelVar` reported `childHasVars=false` for a module that plainly declares one:
  the `var` sat inside the synthetic object and nothing looked in.
- Fixing that detection (`hasVarStat` now descends into `object` bodies) moves the imported case from
  `1 1 1` to `1 1 0` — the same answer the ten-line repro gives with no import at all. It does not
  fix anything, and it is landed for exactly that reason: the import path no longer contributes a
  SECOND, different wrongness on top of this one, so whoever fixes object-var mutation will see one
  behaviour to fix rather than two.

**Not started.** The remaining question is where an object's `var` lives in the interpreter and why a
write to it is not visible to a subsequent read — plausibly a fresh instance per selection, or a
field write to a copied `InstanceV` field map. `native` gets it right, so the semantics are not in
doubt.

**A second oracle, available since 2026-08-05.** The native lane now binds `package:` as a namespace
too (`native-front-has-no-package-namespace`, fixed in `b65d95cd0`), so the ORIGINAL symptom — a
package-declaring module losing its state — can be run on both lanes through the same construct:

```
module `package: app.counter` with `var hits`, then
bump()  app.counter.bump()  app.counter.get()  get()

native (both fronts)   1  2  2  2      one definition, two names, one cell
int                    1  1  0  1      the qualified path is a separate copy
```

Note the int row's middle pair: `app.counter.bump()` returns 1 and `app.counter.get()` then returns
0, so the qualified path does not see its OWN write. Same defect, reached without writing `object`
by hand — which means every package-declaring module on this lane is affected, not only code that
uses an object explicitly. Useful as a second repro shape when checking a candidate fix.


**Investigated 2026-08-05, not fixed. Six facts, each measured, so the next attempt starts where I
stopped rather than where I started.**

1. **The write LEAKS OUT of the object.** A bare `hits` read OUTSIDE `object org` — a name the
   program never declares at top level — prints `1` after one `org.bump()`. So the value is not
   discarded; it is written to `interp.globals`.
2. **`ObjectVarEnvView.assign` is never called for it.** That function is documented as *"The single
   place a bare-name assignment decides WHERE it lands. Every assignment site routes here — the
   general `EvalRuntime` case and the `BlockRuntime` single-statement fast path"*. Instrumented to
   print on entry, it produced NO output for this program. The claim names two sites; the one that
   handles this assignment is not among them.
3. **Not the fast tier and not the JIT.** `SSC_FASTTIER=0` and `SSC_JIT=0` each give the same
   `1 1 0`.
4. **Not the layout.** Braced `object org { … }` and indented `object org:` behave identically.
5. **Two candidate fixes were tried and REFUTED**, both reverted:
   - `ObjectVarEnvView.iterator` does not yield `MarkerKey` although `get`/`contains`/`getOrElse`
     all answer it, so any consumer that COPIES an env drops the marker. Making the iterator yield
     it changed nothing observable — worth knowing, and probably still worth doing on its own
     merits, but it is not this bug.
   - `BlockRuntime`'s unguarded `Term.Assign(Term.Name(x), rhs)` branch threads a new frame
     (`FrameMap.one`) instead of routing. Making it call the router changed nothing — so that branch
     is not the one taken either.
6. **The machinery in `StatRuntime`'s `Defn.Object` case is present and looks right**: it collects
   `varMemberNames`, registers `interp.objectVarStores(objectName) = members`, and re-points every
   member `FunV` at an `ObjectVarEnvView` over the live map. The registration happens; the write
   does not reach it.

**2026-08-06 — three sites ruled out with an instrument PROVEN live, and the proof method.**

How to know a probe is really running, because two attempts here have now turned on this and both
`strings` and `grep` on the jar answer wrongly (a jar is compressed, so the marker is not there as
text even when the code is):

    unzip -p bin/lib/jars/scalascript-backend-interpreter_3-<ver>.jar \
      'scalascript/interpreter/EvalRuntime$.class' | grep -ac '<your marker>'

Also check the VERSION in the filename first. I spent one round grepping
`...interpreter_3-0.1.0.jar`, which does not exist in this tree — it is `0.2.0-SNAPSHOT` — and a
missing file greps as zero, which reads exactly like "my probe never landed".

With that check passing (6 and 3 marker hits in the shipped `EvalRuntime$` and `BlockRuntime$`),
NONE of these three fires — not for the object var, and not for a plain local `var k = 0; k = k + 1`:

    EvalRuntime  case Term.Assign(Term.Name(name), rhs)   ~4437   the general case, and it is the
                                                                  one that DOES call
                                                                  ObjectVarEnvView.assign
    BlockRuntime case Term.Assign(...) :: rest if varNames.contains(x)     the guarded step branch
    BlockRuntime case Term.Assign(...) :: rest                             the unguarded one

and none fires under any tier setting either: default, `SSC_FASTTIER=0`, `SSC_JIT=0`,
`SSC_JIT_BYTECODE=0`, and `SSC_JIT_THRESHOLD=999999999`. The full list of flags the interpreter
actually reads is `SSC_JIT`, `SSC_JIT_BACKEND`, `SSC_JIT_BYTECODE`, `SSC_JIT_FOLDLEFT`,
`SSC_JIT_FOLDTC`, `SSC_JIT_THRESHOLD`, `SSC_FASTTIER`, `SSC_FUSED_RANGE`, `SSC_INSTANCEV_ARRAY` —
worth having, since a flag that does not exist silently changes nothing and looks like a ruled-out
hypothesis.

So the question is now narrower and does not depend on the object at all: **what executes
`k = k + 1` for an ordinary local var?** It is none of the three, which is why fact 2 above holds
even though its reasoning does not — `ObjectVarEnvView.assign` is genuinely not reached, but not
because the docstring names the wrong sites; the site it names is right and simply never runs. Find
the live path with a probe validated the way above, and the object case follows: the write is
already known to land in `interp.globals` while the read comes from the object's var store.

**Sharpened 2026-08-05, with the instrument itself verified.**

`ObjectVarEnvView.assign` is **never called — for any assignment**, not just the object one. A probe
on its first line printed nothing for a program that mutates a top-level `var` AND an object `var`
in the same run, while both assignments took effect (`1`, `1`). So the function documented as *"the
single place a bare-name assignment decides WHERE it lands"* is not on the path either takes.

**The instrument was validated before that conclusion was drawn, and the first attempt was wrong.**
`strings` on the compiled class returned ZERO lines — not even `object-vars`, the MarkerKey literal
that is certainly there — so an earlier reading of "my probe never reached the jar" measured nothing
at all. `grep -a` on the same file finds both the MarkerKey and the probe, in the compiled class and
in the shipped jar. The build ships the instrumentation; the code simply does not run.

**Next step, unchanged in shape but now better aimed:** find what DOES execute for
`Term.Assign(Term.Name(x), rhs)`, given that neither `ObjectVarEnvView.assign` nor the
`BlockRuntime` branch that threads a `FrameMap` is it. A breakpoint or stack dump belongs at the
place a top-level `var` assignment succeeds — that path exists and works, and whatever it is, the
object case is going through it and losing the object's identity on the way.

**A tool note worth more than this bug**: on this host `strings` silently produces nothing for a
`.class` file. Use `grep -a`. An empty result from `strings` reads exactly like "the string is not
there", and that is how it cost two wrong conclusions here.


**Field report of the same defect: `int-imported-module-mutable-registry-not-shared`** — a mutable
registry defined by an imported module stays empty on this lane (int `0`, native `1`, js `1`),
filed 2026-07-28 and re-measured 2026-08-05. That one carries why it matters: INT is the conformance
suite's reference lane, so a case built on that pattern is graded against the lane that gets it wrong.

## int-std-ui-demo-undefined-impl — `Undefined: impl` renders nothing

<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     fixed-in: cb620c16a
     gate: tests/e2e/std-ui-forms-smoke.sh -->

    $ bin/ssc-tools render examples/std-ui/demo.ssc
    Exception in thread "main" InterpretError: [line 36, col 196] Undefined: impl

stdout EMPTY — the page was not rendered at all, and it presented as eight content assertions
reporting `(0, want N)` because the gate ran the renderer with `2>/dev/null`.

**FIXED BY SOMEONE ELSE, AND I ALMOST SPENT AN AFTERNOON RE-FINDING IT.** Bisected to a single call:
`Input.render(…)` raises it while `FormGroup.render(…)` does not, and rendering `input.ssc` alone is
fine because nothing calls into it. The difference is in `input.ssc`:

    val inv = if error.nonEmpty then """ aria-invalid="true"""" else ""

a triple-quoted literal whose content ENDS in a quote — exactly
`triple-quoted-literal-ending-in-a-quote-is-not-a-string`, fixed in `cb620c16a` by a sibling while
this entry was being written.

**The measurement that produced this entry was taken on a build that predated their fix.** I only
noticed because the same construct appeared in their commit title. On a build from current main the
whole gate passes on every lane. So `fixed-in` names their commit, not mine: I did not fix this, I
mis-dated it. The lesson is the cheap one — a `bin/.build-stamp` older than the change you are
measuring against makes every conclusion a statement about last week's code.

`Undefined: impl` as the message for an unterminated string is its own oddity and is NOT explained
here; whoever wants it should start from the fixed parser rather than from this entry.

## int-set-apply-is-not-membership — `Set(1, 2)(2)` says "Not callable" where real Scala says `true`

<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     gate: tests/conformance/set-ops-infix.ssc
     fixed-in: b8ec91192 -->

Found 2026-08-04 while writing `tests/conformance/set-distinct.ssc` for
`type-ascription-tuple-and-set-arms-missing`. `s(x)` is Scala's membership call — a `Set[A]` *is* an
`A => Boolean` — and the `jvm` lane (real Scala) answers it. The interpreter cannot call a Set at
all:

```
println(Set(1, 2)(2))
  jvm : true
  int : [ERROR] [line 1, col 19] Not callable: Set(1, 2)   (InterpretError, CallRuntime.callValue:58)
```

`.contains(2)` works everywhere, so this is only the apply spelling. It is not in the conformance
case for the usual reason: `int` is the GOLDEN lane, so a row it cannot run cannot be pinned there
— which is also why this needs its own entry rather than a line in that case's prose.

Fix goes in `CallRuntime.callValue`, beside whatever already makes a `Map` callable (`m(k)` works).

**FIXED 2026-08-04.** One line: `Value.SetV` was missing from the callable alternation in
`CallRuntime.callValue` (`case _: Value.ListV | _: Value.MapV | _: Value.VectorV | …`), while
`DispatchRuntime.dispatchSet` had answered `"contains" | "apply"` all along — and the comment
fifteen lines below the alternation already claimed "matching ListV/MapV/SetV apply". The gap was
between a comment and the code it described.

The **v2 lane had the same hole** (`app: not a function: Set(1, 2)`) and is fixed in the same
commit. It was not in this entry: I found it only because the new gate runs on all four lanes
rather than on the lane the bug was filed against.

## int-set-element-order-differs-from-scala — `Set(3, 1, 2)` prints `Set(1, 2, 3)` where real Scala keeps insertion order

<!-- status: wontfix
     kind: bug
     lane: int
     area: runtime
     gate: tests/conformance/set-distinct.ssc -->

Found 2026-08-04 by running the same case on the golden and the oracle:

```
println(Set(3, 1, 3, 2))
  jvm : Set(3, 1, 2)     ← real Scala: Set1..Set4 keep insertion order
  int : Set(1, 2, 3)
```

Every other row of `tests/conformance/set-distinct.ssc` — 21 of 22 — agrees between the two lanes;
this is the only disagreement. Scala's small-set classes (`Set1`–`Set4`) preserve insertion order,
and above four elements a `HashSet`'s order is unspecified, so this is only observable for sets of
four or fewer — which is exactly the size a test tends to use.

**The golden lane is the one that is wrong here**, which is why it is filed rather than encoded: a
conformance case comparing against `int` would freeze the wrong order for every lane. `set-distinct`
sidesteps it by writing every literal in ascending order so that both orders coincide, with a
comment saying why — that keeps the case honest but leaves this unmeasured, hence this entry.

Not urgent: no known program depends on Set ordering, and the reference lanes agree on everything
else. Worth fixing when the interpreter's Set representation is next touched.


**WONTFIX 2026-08-04 — I filed this, then measured it, and it is not a defect.** Kept rather than
deleted because the measurement is the useful part.

The entry assumed "real Scala keeps insertion order" generalises. It does not — it holds only for
`Set1`..`Set4`, the small-set optimisation. From five elements Scala switches representation, and
the difference is not just ordering:

```
                          jvm (real Scala)            int
Set(5,4,3,2,1)            HashSet(5, 1, 2, 3, 4)      Set(1, 2, 3, 4, 5)
Set(9,7,5,3,1,2)          HashSet(5, 1, 9, 2, 7, 3)   Set(1, 2, 3, 5, 7, 9)
Set("e","d","c","b","a")  HashSet(e, a, b, c, d)      Set(a, b, c, d, e)
```

The type NAME changes (`Set(` → `HashSet(`) and the order becomes hash-bucket order — neither
insertion nor sorted. So "match real Scala" is not a contract any lane could implement: it would
mean reproducing a particular JVM's bucket layout and switching the printed type name at the fifth
element.

**The interpreter's sorted rendering is the deliberate choice, not an oversight**, and
`v1/runtime/backend/interpreter/src/test/scala/scalascript/SetTest.scala` has said so all along in a
test named `deterministic show (sorted)`. Sorted is stable across runs, independent of set size, and
is the only order the four lanes can actually agree on.

What remains true from the original report is only that `Set(3, 1, 2)` renders differently on `int`
and `jvm` for sets of four or fewer. `tests/conformance/set-distinct.ssc` writes every literal in
ascending order so the two coincide, with a comment saying why — that stays the right handling.

**Lesson worth keeping**: I filed this from a two-lane, one-size probe (`Set(3,1,3,2)`). Widening it
by one axis — set size — inverted the conclusion. The golden lane was not wrong; my probe was too
narrow to see which behaviour was the contract.

## int-v1-lane-loses-a-builtin-companion-to-its-own-case-class — `Response.html` dies, `Response(...)` works

<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     fixed-in: eb759d10c
     gate: tests/e2e/render-lane-has-the-same-builtins-as-run.sh -->

**FIXED by `eb759d10c` on 2026-08-02 — the same day this entry was renamed and corrected, and the
header was never flipped.** `DispatchRuntime.scala:3520` now reads
`interp.shadowedAlternatives.get(ownerName)` when a member is not found on the bound value, which is
exactly what the analysis below said nothing did. Its own comment at `:3509` quotes this entry.

**Verified 2026-08-09 rather than read**, because the entry's own history is a warning: it was filed
wrong twice, and each wrong version pointed at a different file.

- The four-line repro prints `<p>hi</p>` then `ctor` on `run --v1` AND on the default lane.
- `tests/e2e/render-lane-has-the-same-builtins-as-run.sh` passes all cases on both lanes —
  `companion-html`, `companion-text`, `companion-json`, `constructor`, each `[run]` and `[run --v1]`.

**The "still unreduced" warning below is also discharged, and it was right to make me check.** It
says `examples/components-demo.ssc` reaches the same message through an `html"…"` interpolator rather
than `Response.html`, and *"do not assume it shares a fix"*. It does not fail any more either: the
demo starts and serves. Two separate measurements, because the entry asked for two.

On the v1 interpreter lane, importing `Response` from `std/http.ssc` leaves the name bound to the
case-class CONSTRUCTOR and drops the builtin companion that carries `html` / `text` / `json` /
`redirect` / `notFound` / `status`. Four lines:

```
[Response](std/http.ssc)

def main() =
  println(Response.html("<p>hi</p>").body)
```

    $ bin/ssc-tools run --v1 repro.ssc
    [ERROR] [line 4, col 25] No method 'html' on NativeFnV(<native:Response>)
    $ bin/ssc-tools run repro.ssc          # default lane
    <p>hi</p>

`Response(200, Map(), "x")` works on both lanes — only the companion half is missing. In Scala a
case class and its companion are two halves of one name; `BuiltinsRuntime` (~line 782) binds the
companion as an `InstanceV`, and the import overwrites it.

**RENAMED AND CORRECTED 2026-08-02 — the first version of this entry was wrong twice, and each
error pointed at the wrong file.** It was filed as `int-render-invokes-handler-with-Response-…`,
"reached through `ssc render`", on the reasoning that the name was rebound between the top level
running and the handler being invoked. Both halves of that are false:

* It is not `render`. `ssc-tools run --v1` reproduces it with no route, no handler and no render.
  `render` was simply the v1-lane entry point I happened to be holding.
* It is not invoke-time. The same call fails at a file's TOP LEVEL on the v1 lane. The earlier
  "works at top level" measurement was taken on the DEFAULT lane, where it does work — an A/B that
  changed two variables at once and got read as one.

The displaced companion is already recorded: the import path calls
`StatRuntime.rememberShadowedAlternativeForImport` before overwriting (`SectionRuntime` ~line 498),
so `shadowedAlternatives("Response")` holds it. Nothing read that from member dispatch — it existed
only to disambiguate a typed `val` ascription.

**The gate found a false pass in itself before it found anything else.** Its first version compared
`2>&1` against an expected substring, and the interpreter echoes the offending source line in its
diagnostic (`4 |   println(Response.html("<p>hi</p>").body)`), so the expectation matched the ERROR
TEXT. Two of four cases reported green for a program that had just died; only the `json` case,
whose expectation `200` does not appear in the source, failed honestly. It reads stdout only now.

**Still unreduced, do not assume it shares a fix.** `examples/components-demo.ssc` reaches the same
message through an `html"…"` interpolator rather than through `Response.html`, and a bare
interpolator does not reproduce on its own.

## int-jit-two-object-applies-collide — two objects with an `apply` generate one Java class and refuse to compile
<!-- status: fixed
     kind: bug
     lane: int
     area: codegen
     fixed-in: 4e88f7d2a
     gate: tests/conformance/object-apply-two.ssc  -->

**FIXED 2026-08-03** by `int-jit-apply-collision` — and **the title and the diagnosis above are both
wrong about the cause**, kept rather than rewritten because the correction is the useful part.

It is not about two objects. Every class this backend emits carries the functional-interface bridge
`public T apply(…)` **plus** a static method named after the ssc function. When the ssc function is
itself `apply`, those are one name with one signature — hence both errors, the duplicate and the
"static cannot implement". Two objects is simply how it was noticed; ONE function named `apply`
reaching the JIT is enough.

`AsmJitBackend`'s own `GenCtx` already separates `funName` from `staticMethodName`. The javac backend
conflated them, so the collision had nowhere to be noticed: `funName` is the ssc name used to
RECOGNISE self-recursion, and it was also what got EMITTED as the Java call target. Javac's `GenCtx`
now carries `staticName` beside `funName`, and `staticNameFor` renames **only** `apply` — so nothing
that compiles today can change, and the blast radius is exactly the case that was broken.

Gate `tests/conformance/object-apply-two.ssc` runs both objects AND a 60,000-iteration loop over one
of the applies: without the loop the case would only exercise the tree walk and could pass with the
generator still broken. int, v2 and js all print `u:7 / 34 / true`.-->

**Found 2026-08-02** by `v2-object-apply` while writing the gate for a DIFFERENT lane's bug — the
case had two objects in it and int stopped compiling, which is the only reason anyone looked.

```scalascript
object O:
  def apply(x: Int): String = "u:" + x.toString
object Two:
  def apply(a: Int, b: Int): Int = a * 10 + b
def main() =
  println(O(7))
  println(Two(3, 4))
```

```
u:7
/GenJit_apply_1736458419.java:6: error: method apply(long,long) is already defined
    in class GenJit_apply_1736458419
/GenJit_apply_1736458419.java:2: error: apply(long,long) in GenJit_apply_1736458419
    cannot implement apply(long,long) in LongFn2
```

**The JIT names the generated Java class after the METHOD, not the owner.** Two objects each with an
`apply` therefore land in one class with the same signature. The first `println` succeeds, so the
program half-runs before the JIT reaches the second.

`object O { def apply }` beside a `case class` is fine — measured — so it is specifically two
same-named members, and `apply` is just the common case of that. Any two objects sharing a method
NAME with the same lowered signature should reproduce it.

Not diagnosed further: it is the interpreter's JIT, a different module from the claim that found it.
`v2` and `jvm` both run the two-object program correctly.


## int-field-valued-default-undefined-on-empty-call — a default that reads a field works with an argument, not without one
<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     fixed-in: 5e64aca07
     gate: tests/conformance/named-arg-defaults.ssc  -->

**FIXED 2026-08-02** by `int-field-default`.

**The empty argument list was the one shape that lost the receiver.** `zeroArgAllDefaults` returns
the method's function and the caller invoked it with `Nil`, so the defaults were evaluated in the
function's own closure — without the instance's fields. A default reading a FIELD (`dx: Int = x`)
therefore had nothing to read. The NAMED path never had the bug because it already threads
`recvFields` through `positionalizeNamed`, which is exactly why `shift(dy = 1)` — taking the SAME
default — worked and `shift()` did not.

The zero-arg path now calls `positionalizeNamed(f, Nil, recvFields, interp)` rather than growing its
own default evaluation: **one place decides how a default is evaluated**, so the two shapes cannot
drift apart again. `null` from it keeps the previous behaviour, as everywhere else in that function.

    P2(5).shift(dy = 1)    10/3     jvm 10/3
    P2(5).shift(dx = 100)  105/2    jvm 105/2
    P2(5).shift()          10/2     jvm 10/2   <- was `Undefined: x`

The row was REMOVED from `named-arg-defaults.ssc` when this was filed, precisely so that case could
gate a js fix without pinning this divergence; it is restored here with the fix, and the frozen
expectation is regenerated FROM THE JVM ORACLE — int and js both match it exactly.

**This mattered more than one lane's row:** int is the corpus GOLDEN. A wrong answer here is a wrong
expectation for every other lane compared against it.-->

**Found 2026-08-01** by `js-codegen-pair` while extending `named-arg-defaults` to class methods —
i.e. by a case written for a different lane, which is the only reason anyone looked.

```scalascript
case class P2(x: Int, y: Int = 2):
  def shift(dx: Int = x, dy: Int = 0): String = (x + dx).toString + "/" + (y + dy).toString

def main() =
  println(P2(5).shift(dy = 1))     // int 10/3    jvm 10/3    js 10/3
  println(P2(5).shift(dx = 100))   // int 105/2   jvm 105/2   js 105/2
  println(P2(5).shift())           // int ERROR   jvm 10/2    js 10/2
```

```
[ERROR] [line 2, col 23] Undefined: x
   2 |   def shift(dx: Int = x, dy: Int = 0): String = …
      |                       ^
```

**The sharp part is the first row.** `shift(dy = 1)` ALSO takes the `dx` default and evaluates the
same `x`, and it succeeds. So the field is reachable from the default expression in general; it is
specifically the EMPTY argument list that loses the receiver. That rules out "defaults cannot see
fields" and points at whatever binds the receiver before defaults are evaluated on the no-argument
path.

**Two lanes against one, and the odd one is not the oracle**: jvm runs real Scala and js agrees with
it, so `10/2` is not in doubt. js only started agreeing in the same commit that filed this
(`js-class-method-named-arg-nan`), which is why this had never been visible from a lane comparison.


## object-var-member-assignment-writes-a-top-level-global — an object's `var` is not scoped to the object
<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     fixed-in: b8a41142a
     gate: tests/conformance/object-var-member-scope.ssc -->

FIXED ON THIS LANE in `b8a41142a`. `ObjectVarEnvView` (`StatRuntime.scala`) gives an object's `var` members one
store and one view of it:

  * `Defn.Object` registers the object's `members` map in `interp.objectVarStores` and re-points
    every member function's closure at a view that reads the var names LIVE from it. Everything
    that is not a var member still comes from the snapshot the method already captured, so a
    method's view of the outer scope is unchanged.
  * `ObjectVarEnvView.assign` is the single place a bare-name write decides where it lands. It
    finds the owning scope through a marker key read with the ordinary chained `get` — a method
    body runs inside a `FrameMap` layered on the view (`closureWithSelfFor`), so matching on the
    env's TYPE would have missed. The marker is not a legal identifier and is absent from
    `iterator`, so it cannot surface as a field.
  * The `InstanceV`'s field map is the same view, or `O.n` read from outside would answer from the
    snapshot while the object's own methods answered correctly — two truths for one field.

THREE WRITE SITES, NOT ONE, and finding only the first would have looked like a fix while changing
nothing: `def bump(): Unit = n = n + 1` is a SINGLE-statement body, so the `BlockRuntime` fast path
is the one a member method actually takes. `FastTier` is the third — it writes accumulators straight
to `interp.globals` in a dozen places, so instead of teaching twelve sites about object scopes it
now DECLINES when the closure belongs to such a scope, and the general (correct) path runs.

Programs with no `var` member of an `object` pay one boolean (`interp.objectVarsPresent`) and reach
none of this.

THE DEFECT. Assigning to a `var` member from inside the object's own method wrote a TOP-LEVEL global of the
same bare name. The member never changes, and any unrelated variable of that name is silently
overwritten.

```scalascript
object Counter:
  var n: Int = 0
  def bump(): Unit = n = n + 1
  def value(): Int = n

var n: Int = 100

def main() =
  Counter.bump()
  println(Counter.value())   -- 1 on the JVM
  println(n)                 -- 100 on the JVM
```

Measured on all four lanes against the JVM oracle, with `Counter.bump()` called twice:

    lane    Counter.value()   Counter.n   top-level n   Other.value()
    jvm     2                 2           100           50
    int     0                 0           1             50
    native  100               0           102           102
    js      Error: Method not found: n on [object Object]

THREE LANES OF FOUR ARE WRONG, and two of them corrupt an unrelated variable. Native is the worst
of the three: `Other.value()` returns `102` — the TOP-LEVEL `n` — although `Other` has its own
`n = 50`, so a method reading its own member resolves to a global that has nothing to do with it.

CAUSE ON THIS LANE, and it is one line. `EvalRuntime` `Term.Assign(Term.Name(name), rhs)` writes
`interp.globals(name) = v` unconditionally, while the read path (`case tn: Term.Name`) resolves
`env` FIRST and only then `interp.globals`. Inside an object's method the member lives in the env
built by `StatRuntime`'s `Defn.Object` case, so the write lands in one place and the read comes
from another. `Env` is `Map[String, Value]` — immutable — which is why the write had nowhere else
to go.

`val` MEMBERS ARE FINE, and so are local `var`s, top-level `var`s, and `var`s in a `while` body —
measured, all four lanes agree on those. The defect needs a `var` that is a MEMBER.

THE ENTRY THIS CAME FROM NAMED THE WRONG CAUSE. `int-imported-module-mutable-registry-not-shared`
below reads it as an imported module not sharing state with its importer. The control kills that:
the same object with the same `var`, in ONE file with no import, fails identically. The import was
incidental, and the "registry" framing hid a general scoping defect behind a map-reduce fixture.
Kept as a separate entry (it lives in `v1/runtime/backend/js/BUGS.md`) because the route in is
worth preserving, and closed by the same fix.

RELATED, NOT THE SAME: `O.n = 9` from OUTSIDE the object is unimplemented on three lanes —
int raises `Cannot eval: Term.Assign`, js `Method not found`, the native front rejects the parse
(`structural CoreIR contains parser sentinel _err`). Only the JVM does it. Filed separately; the
fix here does not need it, but a fix that routes member assignment through the object would give
it for free.

## int-char-literal-pattern-never-matches — `case '*' =>` silently fell through, so every Char-dispatching parser took its fallback
<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     fixed-in: unrecorded
     gate: v1/runtime/backend/interpreter/src/test/scala/scalascript/CharLiteralPatternTest.scala -->

**FIXED 2026-07-30.** `PatternRuntime.compileLit` had no `Lit.Char` arm, and its default produced
`Value.NullV` — which equals nothing. So a Char literal PATTERN did not error, it silently fell through
to the next case.

```scalascript
def classify(c: Any): String = c match
  case '*' => "star"
  case '+' => "plus"
  case _   => "other"
```

| call | int before | int after | v2 |
|---|---|---|---|
| `classify('*')` | **`other`** | `star` | `star` |
| `classify('+')` | **`other`** | `plus` | `plus` |
| `classify('z')` | `other` | `other` | `other` |
| `'*' == '*'` | `true` | `true` | `true` |

**Equality worked, which is precisely what hid it.** A lexer or operator-dispatching parser looks healthy —
comparisons behave — right up to the point where every branch quietly takes its fallback and the result is
merely *wrong*, not broken.

**How it surfaced, and why that matters.** `dsl-calc-parser` was recorded in the freeze as
`dsl-calc-parser v2 DIVERGE`, i.e. as a v2 defect. It was not. That case folds over operator Chars
(`case '+' => CalcAdd(acc, …)` … `case _ => acc`), so on int every operator was dropped and the frozen
LIVE golden had recorded `1 + 2 => 1`. v2 matched correctly and was therefore the lane flagged as
divergent. Second time in one day that the corpus golden encoded an int defect and blamed v2 — the other
was `v1-json-two-contradictory-number-policies`.

After the fix int prints `1 + 2 => 1 + 2`. v2 still differs on that case, but now against a CORRECT golden
and for an unrelated reason ([[v2-infix-extension-operator-stringifies]]: `++` on a Doc stringifies).

**`Lit.Unit` and `Lit.Float` were in the same hole** and are now named explicitly. The default arm is kept,
so any literal kind still unnamed continues to fail this way — a silent fall-through is a poor default, and
worth revisiting if a third kind ever turns up.

**Blast radius, measured before landing:** exactly ONE file in the whole tree uses a Char literal pattern
(`examples/dsl-calc-parser.ssc`) — checked with an alternation-aware grep across
`tests/conformance`, `examples`, `v1/runtime/std`, `v2` and `specs`, including `.ssc0` tower sources. That
case has no `expected/` file, so its golden is live and no frozen golden moves.

**The js lane had the SAME hole, and fixing int is what exposed it.** `JsGenCpsCodegen.scala`'s
literal-pattern arm had no `Lit.Char` either, and its default emitted `scrut === undefined` — the same
silent fall-through with a different sentinel. Because `dsl-calc-parser`'s golden is LIVE, int's corrected
output immediately made js the divergent lane, and the contract reported
`dsl-calc-parser js DIVERGE` as a regression I had introduced. Both lanes are fixed here; v2 was right all
along.

⚠️ **The js half needed two rounds, and the reason is worth more than the fix.** The first attempt compared
with `===` and a minimal probe passed:

```scalascript
def cls(c: Any): String = c match { case '*' => "star"; case _ => "other" }
println(cls('*'))          // "star" — passed
println(cls("a*b".charAt(1)))   // "other" — still broken
```

A Char has TWO representations on js: a 1-char string from a literal, and a code-point NUMBER from
`charAt` and friends (which is why `_charCodeOrNull` exists in the extension guards). `===` equates only
the first with the emitted literal. A probe that constructs its own input cannot detect a representation
mismatch — the literal's representation matches by construction — so only the real case, whose Char came
out of a parser, could show it. The fix uses the runtime's normalising `_eq`, which `==` already used
(`"a*b".charAt(1) == '*'` was true throughout).

Also collapses the THIRD partial copy of the JS string escaper: the pattern path had its own `"`-only
version, and the second such copy is what produced [[jsgen-char-literal-escape]]. `escapeJsString` is now
`private[codegen]` so the pattern path shares it rather than becoming a fourth.

## v1-interpreter-hot-path-never-jits — the INT lane's core dispatch is over HotSpot's limit
<!-- status: fixed
     fixed-in: 9369f1016
     lane: int
     area: codegen
     gate: tests/e2e/v1-jit-size.sh
     kind: perf -->

**SUPERSEDED — closed at the end of this entry. Was:** OPEN (found 2026-07-29 by censusing v1 for
the first time; gate landed alongside, the splits are the remaining work).

**`-XX:+DontCompileHugeMethods` is on by default and a method over `-XX:HugeMethodLimit` (8000
bytecodes) is NEVER JIT-compiled** — not C1, not C2. It runs in the bytecode interpreter for the
life of the process, with no warning and no correctness signal. In v2 this exact defect cost
**2.4–10.8×** until `Prims.__method__` was split, which is why `tests/e2e/v2-jit-size.sh` exists.

**That gate scans `v2/{src,backend-jvm-bytecode,jvm-runtime}` only.** Nobody pointed it at v1 — the
tree that is **4.3× larger** (302 210 lines vs 70 844). First census, 73 class dirs:

| bytecodes | method | on the hot path? |
|---:|---|---|
| 28036 | `ActorScheduler.handleActorOp` | every actor op |
| 24984 | `JsGen.genExpr` | every JS emission |
| ~~21114~~ **SPLIT** | `DispatchRuntime$.infix2` → 2473 + Arith 7555 + Ord 7136 + Eq 2035 | 18.8% faster with the interpreter JIT off; neutral with it on |
| 16346 | `RustCodeWalk$.renderTerm` | every Rust emission |
| 15330 | `EvalRuntime$.evalCore` | **every INT term** |
| 14696 | `DispatchRuntime$.dispatchList` | **every INT list op** |
| 9839 | `DispatchRuntime$.dispatchString` | **every INT string op** |

**⚠ CORRECTION 2026-07-30 — I overstated the impact here, and the measurement says so.** This entry
originally claimed *"every conformance INT case, every `ssc run`, and every benchmark of the v1
interpreter goes through methods HotSpot has permanently refused to compile"*, and that it *"reframes
numbers quoted for months"*. Both are wrong, for two reasons found while splitting `infix2`:

1. **`numericFast` short-circuits the hot arithmetic.** It handles `Int`/`Double` for
   `+ - * / % < > <= >=` and returns before `infix2`'s body is ever entered. It is a small separate
   method and JITs fine.
2. **The v1 interpreter has its own bytecode JIT that bypasses these methods on hot loops.** Measured
   on an equality-heavy 3M-iteration loop: **0.72 s with it on, 18.03 s with `SSC_JIT_BYTECODE=off`
   — 25×.** Hot loops never reach `infix2` at all.

So an over-limit method here is a real JIT blocker on the paths that reach it, but "every INT case
pays" is false and the F-cost numbers are NOT invalidated. The census flags a hazard; it does not by
itself establish a hot cost.

**This reframes a measurement that has been quoted for months.** `f-front-compile-cost-7x-on-scljet`
and the general "the interpreter is slow" numbers were all taken against a hot path that cannot be
JIT-compiled. Whatever the true cost of F is, it has been measured on top of this.

**Gate landed, deliberately NOT hard-failing.** `tests/e2e/v1-jit-size.sh` freezes these seven by
name and size: it fails on an EIGHTH, on any frozen method that GROWS, and — self-expiring, like a
`known-red:` — on any frozen method that no longer appears, so the exemption list can only shrink.
Seven pre-existing offenders cannot be fixed in the commit that adds the gate, and a gate that is
red on arrival is disabled within a day.

**Not attempted here:** the splits themselves. Each is a hot-path change in a 5000-line file and
wants its own before/after benchmark, with the alternating-A/B discipline
(`feedback_contended_host_needs_alternating_ab`) because a single run on a loaded host manufactures
impressive numbers. Suggested order — `infix2`, `evalCore`, `dispatchList`, `dispatchString` first,
since they are the ones every INT case pays.

**Note on the file-size intuition:** `Main.scala` is the largest file in the repository (8467 lines)
and has **no** over-limit method. Size of file is not the signal; size of method is. An earlier plan
to split `Main.scala` first would have spent a day on the wrong target.

---

### 2026-08-12 — THE GATE COULD NOT HAVE CAUGHT ANYTHING, and the prize is now MEASURED

Two findings, and the first one had to come first: the splits could not be verified because the
apparatus that measures them was broken in three independent ways.

**1. The gate had never run.** Not in CI, not in smoke, not anywhere — the only things naming
`tests/e2e/v1-jit-size.sh` were this entry, a source comment and the orphan probe. Beyond being
unwired it also *could not work*: its census pipeline ended in `grep -E '^[0-9]+ '`, grep exits 1 on
zero matches, and under `set -euo pipefail` that produced **rc=1 with empty stderr** — a failure
with no message. And it scanned `v1/**/target/scala-*/classes`, which **do not exist** after the
`install.sh --dev` its own header prescribes, because that build restores `bin/lib` from the
toolchain cache and never invokes sbt.

Fixed to scan the SHIPPED artifacts, wired into `ci.yml` beside its v2 twin, and verified green
against the exact staging path CI uses. What the silence had cost, visible immediately:

| method | frozen | now | |
|---|---:|---:|---|
| `RustCodeWalk$::renderTerm` | 16346 | 19550 | **+3204** |
| `JsGen::genExpr` | 25100 | 25328 | +228 |
| `EvalRuntime$::evalCore` | 15330 | 15428 | +98 |
| `DispatchRuntime$::dispatchString` | 9839 | 10013 | +174 |
| `StaticJsEmitter$Ctx::compile` | — | 11387 | **NEW** |
| `SolidEmitter$Ctx::compile` | — | 10670 | **NEW** |

**Plugin bytecode ships NESTED and no census had ever opened it.** All 27 v1 plugins ship as a
`.sscpkg` zip whose payload is `intrinsics/<name>.jar` — a jar inside a zip. `handleActorOp`, at
28036 bytecodes the largest offender in the tree, lives there. The first version of the fix reported
it as *"no longer over the limit — DELETE it from FROZEN"*, which is false: it is alive and
unchanged. Obeying that would have dropped the biggest offender from the census forever, green.

**2. THE PRIZE IS ~40% ON LIST- AND STRING-HEAVY INTERPRETER WORKLOADS — measured, not inferred.**

The control needs no code change and no rebuild: `-XX:-DontCompileHugeMethods` removes the limit
entirely, so both arms are the SAME artifact and the difference is exactly what any split could
recover. `-XX:HugeMethodLimit` is develop-only and unavailable in a product JVM; this flag is not.

*Mechanism, from `-XX:+PrintCompilation`, deterministic:*

    limit ON   dispatchList (14697 bytes)   never submitted — refused, silently
               dispatchList1 (5301 bytes)   tier 3 and tier 4   <- an EARLIER split, JITs fine
    limit OFF  dispatchList (14697 bytes)   tier 4, C2 compiles it
               evalCore     (15429 bytes)   tier 4, C2 compiles it

So these methods ARE hot enough that HotSpot wants them, and the limit is what refuses. `evalCore`
is *not* reached on an arithmetic loop — the interpreter's own bytecode JIT takes that, which is why
the `infix2` split measured neutral — but list and string operations do reach it.

*Cost, alternating A/B, five rounds, ABBA order, medians. The host was at load 58→79, so the
RESOLUTION FLOOR was measured first with an A-vs-A pair through the same harness:*

| workload | A/A noise floor | real A/B (limit ON → OFF) |
|---|---|---|
| `lists-big` | +16.4% (min +21.5%) | **−49.4%** (min −36.6%) |
| `strings-big` | −8.2% (min −11.6%) | **−44.8%** (min −40.9%) |

The effect is 2–4× the noise floor and in the same direction on both workloads, which the A/A
control is not. A number taken at load 79 is not a number to quote precisely — but "≈40%, well
outside a measured floor, with the mechanism confirmed deterministically" is a costed task, which
this entry did not have before.

**This corrects this entry's own correction, in the useful direction.** "Every INT case pays" is
still false. But the 2026-07-30 note concluded the census "flags a hazard; it does not by itself
establish a hot cost", and for `dispatchList`/`dispatchString`/`evalCore` a hot cost is now
established. **Suggested order is therefore `dispatchList` first** — it is the one with both the
largest bytecode and a demonstrated ~40% — not `evalCore`, and `infix2`'s neutral result should be
read as "arithmetic is taken by the bytecode JIT", not as "splitting does not pay".


### 2026-08-12 — `dispatchList` SPLIT, and the acceptance test says `evalCore` is next

`dispatchList` 14696 -> **4123 / 6043 / 2999**, three parts, all under the limit. Pure sequential
decomposition: each part tries its cases in the ORIGINAL order and its `case _` falls through to the
next, so the first matching case is still the one the single `match` chose. The frozen list in
`tests/e2e/v1-jit-size.sh` SHRANK from 8 entries to 7.

**Proof it worked is deterministic and does not depend on the host** — `-XX:+PrintCompilation` on
the shipped build, same workload:

| | limit ON (default) | limit OFF |
|---|---|---|
| `dispatchList` **before**, 14697 bytes | never submitted | tier 4 |
| `dispatchList` **after**, 4124 bytes | **tier 3 AND tier 4** | tier 3 and 4 — the flag changes nothing |

**THE WALL-CLOCK A/B DOES NOT COLLAPSE TO ZERO YET, and that is the useful result.** Re-running the
flag control on the split build still shows `lists-big` at −43%. That is not the split failing: the
flag removes the limit for EVERY method, and the same PrintCompilation run says why —

    evalCore (15429 bytes)   limit ON: 0 compilations   limit OFF: tier 4
    dispatchString           limit ON: 0                limit OFF: 0   (not hot in this workload)
    dispatchList (4124)      limit ON: tier 3 + 4       limit OFF: same

`evalCore` is on the same path and is still refused. So **the flag A/B is the acceptance test for
the whole programme, not for one split**: it reaches ~0 only when every over-limit method on the
path is under the limit. Next target is `evalCore`; `dispatchString` after it, and note it was not
hot in these two workloads, so it wants a workload that reaches it before it is worth splitting.

**Correctness: `backendInterpreter/testFast` 1608/1609.** The one failure is
`ThreeWayMutualTcoTest`, and it is NOT this change: it fails identically with the split reverted on
the same host (8250 / 10106 ms against the split's 9700–10719), its workload is `ping→pong→pang`
mutual recursion over `Int` with no list operation anywhere, and the assertion that fails is a
WALL-CLOCK bound (8000 ms) which the file's own comment says exists so a re-introduced blow-up
"fails loudly instead of hanging the suite". Its output assertion passes. Host load was ~60.

**No wall-clock number is claimed for this split on its own.** Isolating it needs a before/after
build comparison, and the host ran at load 60–109 all session — an A/A control through the same
harness measured a noise floor between −4.8% and +65% depending on the workload. The mechanism
above is the evidence; a number taken today would not be one.


### 2026-08-12 — `evalCore` SPLIT: the list path is now fully JIT-able, and `dispatchString` is all that is left

`evalCore` 15428 -> **4455 / 5120 / 3616**. Frozen list 7 -> 6. The interpreter now has exactly ONE
method over the limit: `dispatchString`, 10013.

Harder than `dispatchList` in two ways, both handled. Its 61 arms match on term TYPE WITH GUARDS —
several `Term.Apply` differing only by their `if` — so first-match-wins is load-bearing and the
sequential decomposition preserves it by construction. And its PRELUDE has side effects:
`interp.trackPos(term)` and the DAP `onStep` hook must fire ONCE per term, so the prelude stays in
`evalCore` alone and the parts are `term match` and nothing else.

**A PREDICTION WRITTEN DOWN BEFORE MEASURING, and it is a PAIR so it can fail in either direction:**

> `lists-big` — the flag effect should collapse to ~0 (`dispatchList` and `evalCore` both split,
> `dispatchString` not hot there). `strings-big` — it should REMAIN, because `dispatchString` is
> still 10013 and IS hot there.

**Confirmed deterministically, both sides**, `-XX:+PrintCompilation`, compilations with the limit ON
vs OFF:

| | `evalCore` | `dispatchList` | `dispatchString` |
|---|---|---|---|
| `lists-big` | 4 / 4 | 3 / 3 | 0 / 0 — **the flag changes nothing** |
| `strings-big` | 4 / 4 | 3 / 3 | **5 / 8** — the one still refused |

`evalCore (4456 bytes)` reaches tier 3 and tier 4 with the default limit in force, where at 15429 it
was never submitted.

**THE WALL CLOCK RESOLVED NOTHING TODAY, and that is reported rather than dressed up.** At host load
~60 the A/A control through the same harness measured a noise floor of −23.5% on `lists-big`, which
is LARGER than the −13.9% the flag pair showed; `strings-big` was −1.3% against a floor of
+7.6%/−25.0%. So the timings neither confirm nor refute, in either direction — including my own
prediction that `strings-big` would keep an effect. The only thing worth noting is directional and
is not evidence: `lists-big`'s flag effect moved from −43.1% (against a 16.4% floor, before this
split) to −13.9% (inside a 23.5% floor, after it).

`backendInterpreter/testFast` **1609/0** — including `ThreeWayMutualTcoTest`, which failed during
the `dispatchList` work at load ~60 and passes here. That is the second, independent confirmation
that its failure was the host and not a split.

**Remaining:** `dispatchString` (10013), and the acceptance test for it is already written — the
flag pair on `strings-big` must lose the 5/8 asymmetry above. Note it was NOT hot on `lists-big`, so
whoever takes it should keep a string-heavy workload in the loop or the measurement will say nothing.


### 2026-08-12 — `dispatchString` SPLIT: the v1 interpreter now has NO method over the limit

`dispatchString` 10013 -> **5781 / 3437**. Frozen list 6 -> 5, and **every remaining entry belongs to
another subsystem** — the actors plugin, the JS and Rust code generators, two frontend emitters.
The interpreter's hot path is done.

**Acceptance was a named asymmetry, not a timing**, and it was written down before the split:
on a string-heavy workload `dispatchString` was compiled **5 times with the limit on and 8 with it
off**. After:

| workload | `dispatchString` | `evalCore` | `dispatchList` |
|---|---|---|---|
| `strings-big` | **8 / 8** — asymmetry gone | 4 / 4 | 3 / 3 |
| `lists-big` | 0 / 0 (not hot here) | 4 / 4 | 3 / 3 |

At 5782 bytes it reaches tier 2 and tier 4 with the default limit in force. **No method on either
workload is affected by `-XX:-DontCompileHugeMethods` any more** — which is what "the JIT refusal is
gone from this path" means, stated as something that can be checked rather than felt.

**Measured on a STRING-heavy workload deliberately.** This method is not hot on a list-heavy one —
0 compilations either way — so the obvious reuse of the previous split's workload would have
produced a confident-looking null result. The entry's own note from the `evalCore` step said so in
advance, which is why it did not happen.

`backendInterpreter/testFast` **1609/0**.

### The programme, end to end

| method | before | after | frozen list |
|---|---:|---|---|
| `dispatchList` | 14696 | 4123 / 6043 / 2999 | 8 -> 7 |
| `evalCore` | 15428 | 4455 / 5120 / 3616 | 7 -> 6 |
| `dispatchString` | 10013 | 5781 / 3437 | 6 -> 5 |

**What made this tractable was fixing the measuring instrument first.** `tests/e2e/v1-jit-size.sh`
had never run — unwired, silently aborting on an empty census, and scanning a directory that does
not exist after the build its own header prescribed. Until that was repaired there was no way to
know a split had worked, and no way to stop the debt growing: four frozen methods had grown and two
new offenders had appeared unnoticed, and the largest method in the tree had never been censused at
all because plugin bytecode ships nested inside a `.sscpkg`.

**And the honest remainder: no wall-clock number is claimed for any of the three splits.** What IS
established is the mechanism, deterministically, at every step: these methods were never submitted
to the JIT, and now they are compiled at tier 2/3/4 in the default configuration.

**THE FLAG IS NOW MECHANICALLY INERT, AND THAT TURNED THE FINAL A/B INTO A NOISE MEASUREMENT.** With
every over-limit method gone, `-XX:-DontCompileHugeMethods` can change nothing — verified rather
than assumed: diffing the compiled-method SETS between the two arms leaves 99 differing methods and
**the largest is 188 bytes**, so the 8000-byte limit cannot have refused any of them. They are just
the ordinary run-to-run variation in what a JVM's compiler queue gets to.

So both `FLAG` pairs in the final run are A/A pairs in disguise, and with the two declared ones that
is four independent floor measurements in one session, on a host that had quietened to load 17:

    -4.5%   lists-big   (declared A/A)
   -26.5%   lists-big   (FLAG — inert, therefore A/A)
   +24.7%   strings-big (declared A/A)
    -3.2%   strings-big (FLAG — inert, therefore A/A)

**The floor is ±26%, not the -4.5% the first pair showed.** One A/A pair reading tight does not
establish a floor; it establishes that one pair was lucky. That is why no number is quoted here for
a win that the same harness could not have distinguished from its own noise — and it is the reason
to take these measurements on a quiet machine with more rounds before quoting any.


### CLOSED 2026-08-12 — the INT lane has no method over the limit, and the gate that says so runs

The three splits are done and the frozen list went **8 -> 5**. Every remaining entry belongs to
another subsystem and none of them is the interpreter's dispatch:

| still over the limit | owner |
|---|---|
| `ActorScheduler::handleActorOp` 28036 | actors plugin |
| `JsGen::genExpr` 25328 | JS codegen — filed as `jsgen-genexpr-is-three-times-the-jit-limit` |
| `RustCodeWalk$::renderTerm` 19630 | Rust codegen — filed as `renderTerm-is-two-and-a-half-times-the-jit-limit` |
| `StaticJsEmitter$Ctx::compile` 11387, `SolidEmitter$Ctx::compile` 10670 | frontend emitters |

This entry's title is "the INT lane's core dispatch is over HotSpot's limit". That is no longer
true, so it closes rather than being kept open as a container for other people's debt.

**What actually made it closable was repairing the measuring instrument first.** `v1-jit-size.sh`
had never run once: unwired, silently aborting on an empty census under `set -euo pipefail`, and
scanning a directory that does not exist after the build its own header prescribed. Four frozen
methods had grown unnoticed, two new offenders had appeared, and the largest method in the tree had
never been censused because plugin bytecode ships nested inside a `.sscpkg`. It now runs on every
push and the list can only shrink.

**Method:** at every step the prize was sized BEFORE the work with `-XX:-DontCompileHugeMethods` —
same artifact in both arms, no rebuild — and each split was accepted on a DETERMINISTIC criterion
(the method appears in `-XX:+PrintCompilation` with the default limit in force) rather than on a
timing. That mattered: the host ran between load 17 and 109 all session, and four A/A control pairs
on the final quiet run put the harness's own floor at **±26%**, larger than most of the effects
being chased. No wall-clock number is claimed for any of the three splits.


## v1-interp-zero-arg-call-to-all-defaulted-object-method-returns-a-closure — `V.one()` printed `<function(1)>`
<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     fixed-in: b7916b6f7
     gate: tests/conformance/zero-arg-all-defaults.ssc -->

**Fixed 2026-07-30.** An object's members are FIELDS of its instance, and a field holding a function
was only called when it had NO parameters (`DispatchRuntime:3398`). A top-level `def` and a CLASS
method with the identical signature were correct — only object members took the field path.

**THE FIX IS NOT WHERE THE BUG IS, and measuring said so before any code changed.** The parenless
spelling was measured on three lanes first:

| | `V.one` (no parens) |
|---|---|
| INT | `<function(1)>` |
| native | `<closure>` |
| JVM (real Scala) | a lambda |

All three agree it is a function VALUE. By the time a member reaches `dispatch`, `V.one` and
`V.one()` are the same call with an empty argument list — repairing it there would have changed both
and broken behaviour every lane agrees on. The empty list is visible one level up, in `EvalRuntime`'s
explicit-`()` branch, and that is where the decision now lives.

The predicate refuses `using` params and any parameter without a default, so it can only turn an
incomplete call into a complete one — never alter a call that was already valid. The gate guards both
directions: `V.none()`, `V.req(6)` and `plainOne()` are the paths that already worked.

**Found 2026-07-30** by the probe for [[v1-interp-object-method-named-arg-wrong-slot]]; same
object-vs-plain split, different symptom, so filed separately rather than folded into that fix.

```scalascript
object V:
  def one(a: Int = 3): Int = a
  def two(a: Int = 3, b: Int = 4): Int = a * 10 + b
def plainOne(a: Int = 3): Int = a
println(V.one())
println(V.two())
println(plainOne())
```

| | INT | native | JS |
|---|---|---|---|
| `V.one()` | **`<function(1)>`** | 3 | 3 |
| `V.two()` | **`<function(2)>`** | 34 | 34 |
| `plainOne()` (not a member) | 3 | 3 | 3 |

Calling an object method with NO arguments when every parameter has a default returns the partial
closure instead of applying the defaults. A plain top-level `def` with the identical signature is
correct, which is the same asymmetry as the named-arg defect: the member path and the plain path
disagree about default filling.

**Where to look.** `CallRuntime.callFun`'s default-filling block is guarded by
`tupledArgs.nonEmpty && tupledArgs.length < f.params.length`, so the ZERO-argument case never enters
it. Whatever fills defaults for a plain `def` is reached differently. NOT fixed alongside the
named-arg change on purpose: that change is confined to the path where a name is present, and this
one has no named args at all, so a single commit would have made the A/B unreadable.

## v2-doc-only-file-rejected-by-three-gates — a fence-less `.ssc` is a no-op by design, and the native lane refuses it three times over
<!-- status: fixed
     kind: bug
     lane: int
     area: front
     fixed-in: 1e3b5223c -->

**Status: FIXED 2026-07-30** in `1e3b5223c` (`v2-doc-only-noop`). `bin/ssc run --v2 examples/deploy.ssc` now exits
**0** with stdout byte-identical to the v1 reference (both empty).

**What the fix had to get right, and what the first attempt got wrong.** Yesterday's reverted attempt
made the runners short-circuit to an EMPTY program — and that is exactly what surfaced layer 4: an
empty program emits no CONTENT module, and the structural ABI checks content root identities against
the source roots (`NativeV2Structural.scala:56`). A doc-only file is not a program-less file, it is a
file whose program is empty and whose CONTENT is the point. So the runners now keep the stderr note
and take the SAME path as any other file — an empty source parses to an empty statement list and the
content module is still produced. Layer 4 then needs no change at all.

Landed:
* `ssc1-check-run.ssc0` — prints the same `OK` the checked path prints and exits 0 (exiting 0 alone
  was not enough: `RunNativeV2` treats any stdout other than exactly `OK` as a failure, which is why
  the intermediate state said `ssc: checker exit 0`);
* `ssc1-run.ssc0` + `ssc1-run-fsub.ssc0` (all 3 sites) — note on stderr, then the normal path.

Gates: `specs/v2.2-p6.5-fsub.sh --self` 173 ok / 0 FAIL with the X1 fixpoint byte-identical
(421342 B); `v2.2-p6.5-semantic.sh check` 247/247 — both load-bearing here because the shared runners
changed; `run.sh --only 'fence-doc-block,fence-attr-code,hello'` green; `hello` still runs on v2.

**Original status:** OPEN — **diagnosed through three layers; a partial fix only moves the error**
(found 2026-07-29 by `v2-cluster-c-triage` while reducing `deploy`'s `checker exit 2`).

**The design says this file is fine.** `feedback_ssc_fences_by_design` (2026-07-09): *"doc-only =
no-op + stderr note"*. `examples/deploy.ssc` contains **zero** `scalascript` fences, and the v1
interpreter agrees — `bin/ssc-tools run --v1 examples/deploy.ssc` exits **0**.

The native lane refuses it, and refuses it again after each fix:

| gate | location | behaviour | after fixing the one above |
|---|---|---|---|
| 1. checker | `v2/bin/ssc1-check-run.ssc0:49` | `eprint("no scalascript blocks"); exit(2)` -> `ssc: checker exit 2` | — |
| 2. checker/CLI contract | same file + `RunNativeV2.scala:117` | exiting 0 is not enough: the CLI requires stdout to be exactly `OK`, else `ssc: checker exit 0` | reached after 1 |
| 3. runner | `v2/bin/ssc1-run.ssc0:504`, `ssc1-run-fsub.ssc0` (2 sites) | `eprint(...); exit(1)` -> `ssc: native frontend exited with 1` | reached after 2 |
| 4. structural ABI | `RunNativeV2` | `invalid native frontend structural ABI: content root identities [] do not match roots [...]` — an empty program has no content root | reached after 3 |

I implemented 1-3 (checker exits 0 and prints `OK`; both runners emit an empty program instead of
`exit 1`) and **reverted them**: each fix simply surfaced the next gate, and gate 4 lives in
`RunNativeV2`, so a fix that stops at 3 leaves `deploy` failing with a *different* message — no
better than before, while changing behaviour for every doc-only file without the fsub/semantic
gates having been run.

**What a complete fix needs.** All four layers agreeing that "no code" is a valid program:
1. `ssc1-check-run.ssc0` — print `OK`, exit 0 (the stderr note stays).
2. both runners — lower to an EMPTY program (`Pair(Nil, seen)`, an idiom already used in those
   files) instead of `exit 1`.
3. `RunNativeV2` — accept an empty content-root identity set when the program is empty, rather than
   demanding identities match the source roots.
Then re-run `specs/v2.2-p6.5-fsub.sh --self` and `v2.2-p6.5-semantic.sh check`, because 2 changes
the shared runners.

**Worth noting for whoever takes it:** the three gates duplicate the same policy decision in three
places and disagree with the reference and with the written design. That is the actual defect —
`deploy` is just the case that exposed it.

## v2-nfc-case-runs-only-under-its-provider — `nfc-ndef` fails on EVERY standard lane, including INT
<!-- status: open
     kind: bug
     lane: int
     area: front
     gate: tests/conformance/corpus-skip.txt -->

**Status:** OPEN — measured 2026-07-29 by `v2-cluster-c-triage`; **not the same as the PDF three**.

| lane | result |
|---|---|
| `ssc-tools run --v1` (INT — the contract's GOLDEN) | **FAIL** |
| `ssc-tools run-js` | FAIL |
| `bin/ssc run --v2` | FAIL |
| `bin/ssc-provider nfc run` | **ok** |

The PDF cases could declare `backends: [int]` because INT genuinely runs them. This one cannot:
**no standard lane runs it at all**, so there is no honest `backends:` list to write. It belongs
either in `tests/conformance/corpus-skip.txt` on the documented provider-only grounds, or the corpus
needs a way to say "this case is exercised by `bin/ssc-provider <name>`, not by any lane" — which is
the same gap `v2-optin-provider-cases` describes, one step further.

**Do not "fix" it by making INT the declared lane** — INT fails too, so that would freeze a red.

## v2-json-number-keeps-trailing-zero — `jsonParse("2.0")` prints `2.0` on v2 and `2` on INT
<!-- status: fixed
     kind: bug
     lane: int
     area: front
     fixed-in: unrecorded
-->

**ALREADY FIXED — bookkeeping, 2026-08-02**, found by `v2-sys-env`'s triage pass. The entry records
`jsonParse("2.0")` printing `2.0` on v2 and `2` on INT. Re-measured against the ORACLE as well as
the two lanes:

    jvm (real Scala)  2.0
    int               2.0
    v2                2.0

All three agree, and they agree with Scala. `fixed-in: unrecorded` because the change that closed it
is not identified — what is recorded here is the measurement that shows the divergence is gone.

**Status:** OPEN — **needs a decision, not a patch** (found 2026-07-28 by `v2-diverge-triage` while
reducing `json-read`'s DIVERGE; the sibling half, JSON `null`, was unambiguous and is fixed).

**Measured** (real harness, fresh `sbt installBin`):

| input | INT (`ssc-tools run --v1`) | v2 (`bin/ssc run --v2`) |
|---|---|---|
| `jsonParse("0.0")` | `0` | **`0.0`** |
| `jsonParse("2.0")` | `2` | **`2.0`** |
| `jsonParse("1.5")` | `1.5` | `1.5` — agree |
| `jsonParse("7")` | `7` | `7` — agree |

**It is NOT a Show difference.** `println(2.0)` prints `2` on BOTH lanes — ssc's own rendering of an
integral Double drops the `.0`, and the two agree about that. The divergence is in what the native
parser RETURNS: `NativeJsonCodec.rawNumber` maps any literal containing `.`/`e`/`E` to
`Value.DecimalV(raw)` — a decimal that preserves the source text — while v1 yields a float.

**Why this is a decision and not an obvious bug.** `DecimalV` is arguably the better answer: it does
not round-trip a JSON number through binary64, so a payload with more precision than a Double can
hold survives. Changing `rawNumber` to a float would fix the divergence and silently reintroduce
that precision loss. But the current state has its own inconsistency: the SAME numeric value prints
`2` when written as a literal and `2.0` when it arrives from JSON, inside one program on one lane.

Three candidate resolutions, none free:
1. `rawNumber` returns a float for values a Double represents exactly, `DecimalV` otherwise —
   removes the divergence for the common case, keeps precision for the rest, and adds a
   value-dependent type (two JSON documents differing only in magnitude give different types).
2. Keep `DecimalV`, make its rendering follow ssc's numeric Show — consistent inside v2, still
   diverges from INT until v1 changes too.
3. Declare INT wrong (it loses `2.0` -> `2` information) and re-baseline. Honest but it moves the
   golden, so it needs `json-read`'s expectation and every consumer re-checked.

**Blast radius today:** one line of `tests/conformance/json-read.ssc` (line 8) — the whole remaining
`json-read` DIVERGE — plus line 2 of `tests/conformance/expected/json-self-hosted-import.txt`, which
already records `0.0` and is therefore consistent with whichever way this goes.

---

**ADDENDUM 2026-07-30 (`skip-triage-golden-lane`) — MEASURED, and it reverses the premise: v2 is the
CORRECT lane here, and INT's loss is not limited to the `.0` suffix.**

The entry above reads the divergence as a display quirk about integral values (`2.0` vs `2`). It is
much bigger than that. Same fresh worktree build, both lanes, `println(jsonParse(x))`:

| input | INT (`ssc-tools run --v1`) | v2 (`bin/ssc run --v2`) |
|---|---|---|
| `0.10` | `0.1` | `0.10` |
| `1.50` | `1.5` | `1.50` |
| `0.1000000000000000055511151231257827` | **`0.1`** | full 34 digits preserved |
| `1e2`, `1.0e2` | `100` | `100` — agree |

INT does not merely drop a trailing `.0`. It parses every fractional JSON number into a **binary
Double**, so `0.10` and `0.1` become indistinguishable and a payload carrying more precision than
binary64 holds is silently truncated. That is the exact defect the money-safety comment in v1's own
JSON core says must not happen.

**Root cause — v1 contains TWO CONTRADICTORY JSON number policies, and `jsonParse` uses the lossy
one:**

| site | policy |
|---|---|
| `v1/lang/core/src/main/scala/scalascript/interpreter/JsonParser.scala:93` | `if isDouble then Value.doubleV(s.toDouble)` — **lossy binary64** |
| `v1/runtime/std/json-plugin/.../V1JsonCore.scala:127` | exact `BigDecimal`, with the comment *"never a lossy `Double`"* |
| `v2/runtime/std/json-plugin/.../NativeJsonCodec.scala:223` | exact decimal — **agrees with V1JsonCore** |

`jsonParse` reaches the lossy site through `PluginApi.parseJson` ->
`scalascript.interpreter.JsonParser.parse` (`PluginApi.scala:63`), NOT through `V1JsonCore`. So v2
matches v1's DOCUMENTED and INTENDED policy while the frozen golden records v1's other, undocumented
one. The DIVERGE is two v1 policies disagreeing with each other, surfaced through v2.

**What this does to the three options above:**
* Option 1 (float when exactly representable) is now clearly WRONG — it keeps destroying `0.10` and
  `1.50`, which are exactly representable as decimals and are the money case the design cares about.
* Option 2 changes nothing about the precision loss.
* **Option 3 is the correct direction** and is stronger than it looked: it is not "declare INT wrong"
  against v1's intent, it is making `jsonParse` obey the policy v1's own JSON core already states.

**Why it is still not landed here:** the fix is one line at `JsonParser.scala:93`, but that parser is
also the request/JWT/session JSON reader, so it moves the GOLDEN for every case that prints or
computes on a parsed fractional number. Moving the golden is exactly the thing the corpus contract
exists to make deliberate. Filed as its own bug
(`v1-json-two-contradictory-number-policies`) and queued in SPRINT.md with a
measurement-first plan: make the change, run the FULL corpus, and report the exact list of changed
cells BEFORE freezing anything.

**Do NOT "fix" this by degrading v2** to match the golden. That was the shape the original entry
leaned toward, and it would trade away exactness that v1's own source says is required.

## int-extension-on-function-type-alias-does-not-dispatch — `extension (p: Pass[A,B])` never fired, because the receiver is a `FunV` at runtime
<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     fixed-in: unrecorded
     gate: v1/runtime/backend/interpreter/src/test/scala/scalascript/ExtensionOnAliasDispatchTest.scala -->

**FIXED 2026-07-30.** The seam: registration takes the receiver's type name from the SYNTAX
(`StatRuntime`: `Pass[A, B]` -> `"Pass"`), dispatch derives it from the runtime VALUE
(`DispatchRuntime.extensionDispatch`), and a function value matches no nominal case there, so it becomes
`"Any"`. `extensions("Pass")` existed and `extensions("Any")` was looked up.

`Interpreter` now carries `functionTypeAliases` — the names of `type X = …` aliases whose right-hand side
is a function type, recorded by the `Defn.Type` arm that previously dropped them. An extension whose
receiver is such an alias (or a function type written out) files under `"Any"` as well, which is where
dispatch will look. Nominal receivers are untouched: they keep their own key and are matched first, and a
test asserts exactly that with two extensions sharing a method name.

⚠️ **A SECOND defect of the same family had to be fixed for the case to work, and it is the fourth
instance of one pattern today.** `PatternRuntime` held a second, INLINE copy of `compileLit` (line 1252)
serving NESTED patterns, and it had no `Lit.Char` either — so `case Some((l, '+', r)) =>` still fell
through silently after `compileLit` itself was fixed. The bare-literal path and the nested path had
drifted. Now routed through `compileLit`. See [[int-char-literal-pattern-never-matches]].

**Measured on `dsl-mini-language`**, which both defects blocked: it was a corpus SKIP (int exited
non-zero), and int now produces the correct output while **v2 PASSES against it**. Before, int dropped
every operator and reported `cannot parse atom` on a whole expression, while v2 computed `result: 23`
correctly — the fifth case today where the interpreter was the wrong lane.

js still diverges on that case for an unrelated reason, filed as
[[js-pass-error-not-formatted-by-its-module-function]].

**Original report (superseded 2026-07-30):**

**Status:** OPEN. Found 2026-07-30 by `skip-triage-golden-lane` after `91c326d1f` let the import
resolve — the export error was masking this one.

**Reproduce:** `examples/dsl-mini-language.ssc:218` ->
`[ERROR] [line 5, col 3] No method 'andThen' on FunV(<function(1)>)`.

`std/dsl/passes.ssc:33` declares `type Pass[A, B] = A => Either[List[PassError], B]` and `:69`
declares `extension [A, B](p: Pass[A, B]) def andThen[C](...)`. The interpreter registers extensions
keyed by TYPE NAME and dispatches on the receiver's runtime type — which for a function value is
`FunV`, never `Pass`. So an extension whose receiver type is an alias OF A FUNCTION TYPE can never
fire, no matter how it is imported.

**Scope.** Narrower than "extensions are broken": an extension on a `case class`/`enum` type works
(covered by `ModuleImportTypeAliasExtensionTest`, which asserts a computed value across a module
boundary). It is specifically an alias whose right-hand side is a structural runtime shape — function
here; a tuple or `List` alias would likely behave the same way and is worth checking as part of the
fix.

**Why it stays open rather than getting a quick patch.** The fix has to resolve an alias to its
underlying runtime shape at extension-REGISTRATION time (register `andThen` for `FunV` when the
receiver type is an alias of a function type), or record aliases so dispatch can widen the receiver's
type name. Both change extension dispatch, which is shared by every lane — so it wants its own
fail-first test per alias shape (function / tuple / collection), not a spot fix for `Pass`.

**Blocks** `dsl-mini-language` (a corpus SKIP, so it hides v2 and js too). Its two siblings
`dsl-sql-recovery` and `dsl-yaml-like` are now closed.

## std-import-resolver-blind-to-type-alias-and-extension — `[Pass](std/dsl/passes.ssc)` said "not found" for a name the module defines and exports
<!-- status: fixed
     kind: bug
     lane: int
     area: front
     fixed-in: 91c326d1f
     gate: v1/runtime/backend/interpreter/src/test/scala/scalascript/ModuleImportTypeAliasExtensionTest.scala -->

**FIXED 2026-07-30** (`91c326d1f`). Both shapes — a `type` alias and a method declared inside `extension`
— can now be named in an import list. An alias is erased so there is nothing to bind; an extension method
belongs to the TYPE and `exportedExtensions` was already copied wholesale, so only NAMING it failed.

⚠️ The AST walk has to be `Tree.collect` over the whole subtree, not a top-level-stat match: a module with
`package:` in its front-matter is parsed with its statements wrapped in `object`s, so the alias is never a
top-level stat. The first version returned `Set()` for every REAL std module while passing against a
package-less test fixture — a hole in my own fixture, found by instrumenting the helper rather than reading
it. `moduleTopLevelDefNames` right above it still has that blind spot.

Measured outcome: `dsl-sql-recovery` and `dsl-yaml-like` now run on the golden lane (the latter after six
further missing exports in `std/parsing/layout.ssc`), so both stopped being corpus SKIPs — which hides
every lane, not just int. `dsl-mini-language` advanced to a different defect,
[[int-extension-on-function-type-alias-does-not-dispatch]].

**Original report (superseded 2026-07-30):** OPEN. Found 2026-07-30 by `skip-triage-golden-lane` while probing all 77 corpus SKIPs.
Full triage in `specs/skip-triage.md`.

**Reproduce — a one-line consumer against the real std modules:**

```scalascript
[Pass](std/dsl/passes.ssc)
def main() = println("imported-ok")
```
-> `[ERROR] 'Pass' not found in std/dsl/passes.ssc`

`Pass` is defined at `std/dsl/passes.ssc:33` (`type Pass[A, B] = A => Either[List[PassError], B]`) and
IS listed in that module's `exports:`. Same for `ParseErrors` (`std/parsing/recovery.ssc:132`) and for
`withIndent` (`std/parsing/layout.ssc:210`, a `def` inside `extension [A](p: Parser[A])`).

**Isolated to two declaration shapes** — a probe module exporting one of each:

| shape | importable |
|---|---|
| `def` | yes |
| `def` with default arguments | yes |
| `case class` | yes |
| **`type X = ...` alias** | **NO** |
| **`def` inside `extension`** | **NO** |

Neither shape has a backend dependency — both are pure `.ssc`. This is not the `extern`/intrinsic
case (see `std-ui-fetchUrlSignalTo-declared-never-implemented`): `mcpConnect`, `fetchUrlSignal`,
`seedSignal` and `textNode` are all `extern def`s that import fine.

**Why it is worth more than three cases.** It blocks `dsl-mini-language`, `dsl-sql-recovery` and
`dsl-yaml-like` on the GOLDEN lane, and a case that cannot produce a golden is SKIPped for EVERY
lane — so these are three cases where v2 currently has no verdict at all. Fixing the resolver is the
cheapest item on `specs/skip-triage.md`'s list because it needs no freeze change to land.

**Not yet localized to a file.** The resolver lives on the v1 import path (`TransitiveImportHelper`
and friends); the failing check is whatever builds a module's exported-symbol table, which evidently
walks `def`/`class` declarations only.

## v1-json-two-contradictory-number-policies — `jsonParse` truncated fractional numbers to binary64 while v1's own JSON core promised exactness
<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     fixed-in: 6596c1c52
     gate: tests/conformance/json-read.ssc -->

**FIXED 2026-07-30** (`56b2b3e5d`), approved by Sergiy. There turned out to be THREE policies, not two —
the JS backend was a second lossy site — so fixing only `JsonParser.scala:93` would have moved
`json-read`'s divergence from the v2 column to the js column and looked like progress. Both lossy sites
were fixed; int, js and v2 now agree byte-for-byte on ten number probes including `0.10`, `1.50` and a
34-significant-digit decimal.

Full-corpus evidence: an int-only run first bounded the blast radius to exactly 2 of 527 cases, both then
fixed (`json-read`'s frozen lossy `0` -> `0.0`; `json-value`'s `asDouble` rejecting a Decimal). The
three-lane run then reported `json-read v2 DIVERGE` -> PASS and zero json-related regressions.

Remaining cost, deliberate and documented in `specs/json-number-policy.md`: `Decimal ⊕ Double` is an error
by design (exact-numerics §4.3), so mixing a parsed JSON number with a Double LITERAL now raises for
`+`/`>` and makes `== 1.5` return `false` SILENTLY. That last row is the one a reviewer should weigh.

**Original report (superseded 2026-07-30):**

**Status:** OPEN — **needs Sergiy's go**, because the fix moves the corpus GOLDEN. Found 2026-07-30 by
`skip-triage-golden-lane` while reducing `json-read`'s DIVERGE; full measurement and the both-lanes
table live in the ADDENDUM to `v2-json-number-keeps-trailing-zero`.

**The contradiction, in v1 alone** (no v2 involved):

| site | policy |
|---|---|
| `v1/lang/core/src/main/scala/scalascript/interpreter/JsonParser.scala:93` | `if isDouble then Value.doubleV(s.toDouble)` — lossy binary64 |
| `v1/runtime/std/json-plugin/src/main/scala/scalascript/compiler/plugin/json/V1JsonCore.scala:127` | exact `BigDecimal`, commented *"never a lossy `Double`"* |

`jsonParse` routes to the FIRST one (`PluginApi.parseJson` -> `JsonParser.parse`,
`PluginApi.scala:63`). Measured consequence on the golden lane: `jsonParse("0.10")` prints `0.1`,
`jsonParse("1.50")` prints `1.5`, and a 34-significant-digit decimal collapses to `0.1`.

**Why it matters beyond cosmetics.** This parser is not only `jsonParse`'s: it is the reader behind
HTTP request bodies, JWT claims and session cookies. Any amount that arrives as JSON and is then
compared or summed is going through binary64 on the lane the whole corpus treats as ground truth.

**Fix is one line, blast radius is not.** Making line 93 produce an exact decimal aligns `jsonParse`
with `V1JsonCore` and with v2 — but it changes output for every frozen case that prints a parsed
fractional number, and the corpus contract deliberately makes moving the golden a decision rather
than a side effect.

**Required order of work** (do not reorder — the point is to know the cost before paying it):
1. Change `JsonParser.scala:93` in a worktree; rebuild.
2. Run the FULL corpus contract and report the exact list of changed cells.
3. Only then decide whether to re-freeze, and freeze in ONE commit that names this bug.

**Do not** resolve this by making v2 lossy to match. See the ADDENDUM for why that direction is
wrong.

## v2-object-apply-unbound — `object O { def apply(x) }` is unbound on the native lane
<!-- status: fixed
     kind: bug
     lane: native
     area: front
     fixed-in: de5760462
     gate: tests/conformance/object-apply.ssc -->

**FIXED on the DEFAULT front 2026-08-02** by `v2-object-apply`, and on the **legacy fallback front
2026-08-03** by `legacy-object-apply` — every lane and both fronts now agree.

The legacy half needed a registry the file did not have. `objectVarFields` keys `var` members by
object; there was no equivalent for methods, so a flat `objectApplyCell` of "objects that declare an
`apply`" was added beside it — the question is only *does this object have one*, never *which*. The
branch goes FIRST in `resolveE`'s uid chain, ahead of `Some`/`Cons`/`List`/`Set`: those are builtin
names and this one is a user declaration.

Why it was worth finishing rather than leaving to the default front: the legacy front is what
compiles a file whenever F declines it for an UNRELATED reason, so a half-fix makes the answer
depend on which front happened to accept the program. `lane:` was `int` and is corrected to `native` — int is the
lane that WORKS here.

**`O.apply(7)` already worked, and that was the diagnosis.** The member is emitted as `O_apply`;
what was missing is that an APPLIED bare `O` never became that call. `O` is uppercase, so it lexes
as an uppercase-name token and reaches the CONSTRUCTOR path — never `parseCallPlain`. A first
attempt in `calleeOf` changed nothing, which is how the ctor path was found; that attempt was
reverted rather than left in, because `calleeOf` also answers bare NAME references where `O` means
the object itself, and rewriting there would turn an honest `unbound global` into a silently wrong
function value.

    lane                O(7)              O.apply(7)
    int                 user-apply:7      user-apply:7
    jvm                 user-apply:7      user-apply:7
    v2 / F (default)    user-apply:7      user-apply:7   <- fixed here
    v2 / legacy         user-apply:7      user-apply:7   <- fixed 2026-08-03
    js                  not callable                     <- different cause, own lane

A case class still constructs — `objReg` holds `object` declarations only — and the gate carries
that row so the new branch cannot swallow ordinary constructor application.

**Status:** OPEN (found 2026-07-28 by `v2-stub-apply-and-serve-banner` while verifying that the
`List.apply` fix did NOT break the shape the roadmap warned about). Pre-existing — A/B'd against a
build without that fix, identical both sides, so it is not a regression from it.

**Reproduce** — four lines:

```scalascript
object O:
  def apply(x: Int): String = "user-apply:" + x.toString
println(O(7))
println(O.apply(7))
```

| lane | result |
|---|---|
| `bin/ssc run` (native) | **`ssc: unbound global: O`** |
| `bin/ssc-tools run --v1` | `user-apply:7` then `user-apply:7` |

**Severity: LOW-ish, and deliberately so.** It fails **loud** with a non-zero exit, so it is not
the `Stub`-sentinel fail-open class that dominates the other v2 gaps — a user sees it immediately.
It is filed because it is a plain Scala idiom (a companion-style `apply`) that the reference lane
supports and the default lane does not.

**Why it is noted here rather than fixed opportunistically.** The roadmap's `V2-100-3` entry warns
that "fixing" `.apply` in the FRONTEND — lowering it to an application — breaks exactly this shape,
because `O.apply(1)` must reach the user's method rather than index anything. The `List.apply` fix
therefore went into a VM dispatch arm matching only cons/nil receivers, and this entry records the
other half of that trade-off so the next agent does not undo one by fixing the other.

**Related:** `standard-tier-named-arg-skip-default` and the `v2` object-member family — the
suspicion is that the object itself is never registered as a global when its only member is
`apply`, not that `apply` dispatch is missing.

## v1-interp-object-method-named-arg-wrong-slot — a method's named arg bound to the wrong parameter
<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     fixed-in: 17d854036
     gate: tests/conformance/named-arg-defaults.ssc -->

**Fixed 2026-07-30.** `DispatchRuntime.dispatch*` takes a POSITIONAL list, and the method-call path
fed it `case Term.Assign(_, rhs) => eval(rhs, …)`: the label was dropped and the value fell into the
first parameter that had a default. A plain `def` never had this — it goes through `callValueNamed`.

```scalascript
object O:
  def m(a: Int, b: Int = 5, c: Int = 7): Int = a + b * 10 + c * 100
println(O.m(1, c = 9))
```

| probe | INT before | INT after | JVM (real Scala) |
|---|---|---|---|
| `O.m(1, c = 9)` | **791** | 951 | 951 |
| `O.m(a = 1, c = 9)` | **791** | 951 | 951 |
| `V.two(b = 1, a = 2)` | **102** | 201 | 201 |
| `P(5).shift(dy = 1)` | **6/2** | 10/3 | 10/3 |
| `plain(1, c = 9)` | 951 | 951 | 951 |

**This entry's own table was wrong, and the reason is worth more than the fix.** It reported native
as also giving 791. That measurement came from the `bin/` in the shared checkout, which was **seven
days behind HEAD**; rebuilt in a worktree at current main, native gives 951 and always did. Nothing
in the toolchain says a build is old — filed and fixed as `stale-build-silently-used-for-measurement`.

**Fix shape.** Reorder BEFORE dispatch rather than calling the method directly: the receiver still
has to go through `DispatchRuntime`, which is what binds an instance's fields around a class-method
body. `CallRuntime.methodFunFor` recovers the target `FunV` (object members are fields of the
`InstanceV`; class methods live in `interp.typeMethods` — the same two lookups
`dispatchInstanceFallback` makes) and `positionalizeNamed` builds the complete positional list,
filling omitted params from defaults. It returns null — caller keeps the old behaviour instead of
guessing — for `using` params, varargs, an unknown name, more positionals than free slots, or a
required param left unfilled.

**A regression the fix introduced and the probe caught.** `P(5).shift(dy = 1)`, whose default reads
a field (`dx: Int = x`), began CRASHING with `Undefined: x` where it had merely printed the wrong
`6/2` — defaults were being evaluated without the receiver's fields in scope. With them the shape
does not just stop crashing, it becomes correct. A failure to evaluate a default now falls back
rather than raising, and nothing is swallowed: a genuinely broken default still raises from the
ordinary positional path.

**Gate.** `tests/conformance/named-arg-defaults.ssc` gains the seven object-method shapes it had
deliberately omitted, all three lanes agreeing byte-for-byte. CLASS-method shapes stay out, with the
reason in the case: they are `NaN` on JS ([[js-class-method-named-arg-nan]]), so pinning them would
record a divergence rather than a behaviour. Full contract GREEN, 1004/1045 PASS cells, zero
regressions.

## v2-method-dispatch-never-jits — every method call in every v2 program ran interpreted, forever
<!-- status: fixed
     kind: perf
     lane: int
     area: codegen
     fixed-in: unrecorded -->

**Status:** **FIXED 2026-07-28** (`v2-runtime-perf-vs-v1`). Found by benchmarking the v2 runtime
against v1 for the first time — the v2 bench lane had been dead and reporting `n/a`, so this had
never been measurable. Full measurement + A/B: `specs/v2-runtime-perf-vs-v1.md`.

**The defect.** HotSpot ships `-XX:+DontCompileHugeMethods` ON by default: a method whose bytecode
exceeds `-XX:HugeMethodLimit` (8000) is never compiled by C1 or C2 and runs in the bytecode
interpreter for the life of the process. `ssc.Prims.resolveBuiltinRaw`'s `__method__` arm — the
single dispatch point for **every non-arithmetic operation in every ScalaScript program**
(`xs.map`, `s.split`, `n.toInt`, `opt.getOrElse`, …) — compiled to **49,384 bytecodes, 6.2× the
limit**. It was the only method in the v2 kernel over it; the next largest was `arithRest` at 5,235.

**Why nobody saw it.** It fails GREEN in the strongest sense: no warning, no log line, no exception,
and no observable behaviour difference at all. Every correctness gate passed the whole time. The
only symptom was speed, and the v2 bench lane was printing `n/a` instead of numbers, so the speed
was not being read either.

**How it was identified** (the shape of the data, not a guess): workloads that never reach
`__method__` (`arith-loop`, `nested-loop`, `recursion-fib`) sat at or near v1 parity while
everything that dispatches methods was 20-400× slower than the v1 interpreter. A uniformly slow
runtime cannot split that way. `javap -c` then named the method.

**Reproduce** (either direction, on any build):

```bash
scripts/bytecode-size-census v2/src/target/scala-3.8.3/classes 8000   # empty = fixed
./bench.sh --backends ssc,v2,v2-bytecode --reps 30 string-split literal-match vector-index arith-loop
```

**Fix.** `ssc.Prims.methodDispatch1..10` — a pure sequential decomposition: part N tries its cases
in the original order and falls through to part N+1, so the first matching case is exactly the one
the single match would have chosen. Nothing reordered, nothing duplicated. Largest part 5,509.

**Measured effect** (`./bench.sh --backends ssc,v2,v2-bytecode --reps 30`, same worktree, both
sides `sbt installBin`, differing only in `v2/src/Runtime.scala`; the unchanged `ssc` v1 column is
the noise control at ≤1.3× drift):

| | `string-split` | `typeclass-fold` | `literal-match` | `vector-index` | `map-ops` | `arith-loop` (control) |
|---|---|---|---|---|---|---|
| v2-bytecode before | 13.8 | 1.16 | 2.22 | 382.6 | 3.43 | 0.661 |
| v2-bytecode after | 1.28 | 0.107 | 0.263 | 58.3 | 0.778 | 0.625 |
| gain | **10.8×** | **10.8×** | **8.4×** | **6.6×** | **4.4×** | 1.06× (unchanged, as predicted) |

2.4-10.8× across every dispatching workload on both lanes; unchanged within noise on the pure
arithmetic that never reaches `__method__`.

**Guard.** `tests/e2e/v2-jit-size.sh` (+ `--self-test`) fails on any v2 method over 8000 bytecodes
and prints its size, and lists everything ≥6000 so drift toward the limit is visible before it
breaches. Nothing else can see this class of defect.

**Left open by this fix:** `ssc.bytecode.JvmByteGen$.gen` is at **7,052 / 8000 (88%)** — one `case`
from silently un-JITing the whole bytecode emitter. Queued as slice 2 in
`specs/v2-runtime-perf-vs-v1.md` §4.

## v2-json-read-native-representation-parity — native JSON read differs from established output
<!-- status: fixed
     kind: apparatus
     lane: int
     area: codegen
     fixed-in: 1edd8cd6c
     gate: tests/conformance/json-read.ssc -->

**Status:** DONE — fixture/gate defect fixed by `1edd8cd6c` (found and
confirmed 2026-07-27 by `codex` while working SPRINT
`v2-json-self-hosted`).

**Real-harness reproduction.** Build the worktree product with
`scripts/sbtc "installBin"`, then run:

```bash
bin/ssc run --bytecode tests/conformance/json-read.ssc
```

Compare stdout with `tests/conformance/expected/json-read.txt` before
classifying the backend. The product exits 0 but has two exact differences:

```text
expected=None  got=()
expected=0     got=0.0
```

The sibling `json-value` and `json-lookup` cases are already byte-identical on
the same assembled route. The regular three-case conformance command is 3/3,
but it runs only INT/JS/JVM for these manifests, so that green result does not
exercise or disprove the native-v2 mismatch.

**Classification.** The first mixed-lane import control incorrectly appeared
green on V2. An isolated, fail-closed rerun disproved it: explicit
`std/json.ssc` imports still print `()` and `0.0`. This is the intended
self-hosted representation, not a native-v2 conversion defect. The assembled
compatibility product (`bin/ssc-tools run --v1`) prints the same two values
before reaching a later v1-only callable-instance limitation; both the v1 and
v2 `JsonCore` bridges map JSON null to Unit and preserve fractional/exponent
tokens as exact Decimal values. The old `None` / integral-double display comes
from the legacy plugin-global `jsonParse` API.

Adding the self-hosted imports plus V2 to the existing shared manifests is not
valid: the strict/lookup routes fail on JS and do not compile on JVM, while the
old fixtures intentionally exercise legacy plugin intrinsics on those lanes.
The native sweep therefore fed a legacy no-import fixture to a different
public API, then compared it with the legacy API's representation.

**Fix.** Leave the three legacy fixtures and expected files unchanged. Add one
V2-only conformance case that explicitly imports the self-hosted JSON surface
and pins strict raw parse, typed navigation, lookup, Unit-backed JSON null, and
exact decimal rendering. The ordinary conformance command must then execute
the V2 boundary instead of relying on an ad-hoc sweep.

**Verification.** `json-self-hosted-import` passes its declared V2 lane, and
its output is byte-identical through native VM, direct ASM, and
`ssc-standard`. The original `json-read,json-value,json-lookup` slice remains
3/3 on INT/JS/JVM, and `v21-self-hosted-json-cutover-smoke.sh` passes.

## scljet-correlated-subquery-join-where-unsupported — joined outer rows bypass correlated evaluation
<!-- status: fixed
     kind: feature
     lane: int
     area: runtime
     fixed-in: b63206552 -->

**Status:** FIXED (found 2026-07-28 by independent
`scljet-production-completion` review; reproduced on assembled
`bin/lib/ssc.jar` from `b63206552` through `bin/ssc-tools run --v1`; fixed in
`71d8a6f0e`).

**Real-harness reproduction.** On the SC-1b fixture, run
`SELECT t.id FROM t LEFT JOIN u ON t.v=u.x WHERE t.v NOT IN
(SELECT x FROM s WHERE owner=t.id) ORDER BY t.id`. Reference SQLite 3.51.0
returns id `4`; SclJet returns no rows. The equivalent single-table correlated
query returns `4`, so this is specific to the joined outer-row path.

**Root cause.** `joinCondState` and `multiCondState` call plain `predState`.
They neither receive the database nor substitute values from the joined outer
row, so EXISTS/IN/scalar subquery condition kinds never reach `condStateCtx`.
A fix needs joined-row substitution for every bound table plus two- and
N-table fail-first gates; treating only the first table as the outer context
would be another partial implementation.

**Fix:** `71d8a6f0e` replaces the Boolean joined filter with a shared
`Either[String, Int]` condition path, binds every visible outer table, preserves
LEFT/RIGHT NULL-extension, and applies direct inner-table shadowing. The later
nested-scope closure expands `scljet-sql-correlated-join` to a twelve-line
oracle; it passes on INT and JS across single-, two-, and N-table contexts,
immediate and nested shadowing, and legitimate grandparent correlation. The
live differential adds nine named outcomes, preserves prepare/bind/execute
phase plus semantic error category, and still passes Xerial reopen and
integrity checks.

## scljet-correlated-subquery-errors-swallowed — NOT turns subquery execution failure into TRUE
<!-- status: fixed
     kind: bug
     lane: int
     area: front
     fixed-in: b63206552 -->

**Status:** FIXED (found 2026-07-28 by independent
`scljet-production-completion` review; reproduced on assembled
`bin/lib/ssc.jar` from `b63206552` through `bin/ssc-tools run --v1`; fixed in
`71d8a6f0e`).

**Real-harness reproduction.** Run
`SELECT id FROM t WHERE NOT EXISTS (SELECT x FROM missing WHERE
missing.x=t.v) ORDER BY id`. Reference SQLite 3.51.0 fails with
`no such table: missing`; SclJet returns every outer row. Parse/execute failures
inside correlated IN and scalar subqueries are swallowed by the same path.

**Root cause.** `existsHolds`, `inSubqState`, and `scalarSubqState` map every
`Left` to FALSE. NOT EXISTS/NOT IN then invert that fabricated FALSE to TRUE.
The correlated state/filter pipeline must carry `Either[String, Int]` (or an
equivalent explicit error channel) through query execution and never classify
an execution error as SQL FALSE or UNKNOWN.

**Fix:** `71d8a6f0e` propagates the explicit error channel through single-,
two-, and N-table filters and performs unconditional structural preflight, so
empty outer inputs, rowid/index misses, missing tables, and malformed
EXISTS/IN/scalar subqueries cannot hide an error. All 15 outcomes in
`scljet-correlated-subquery-errors` pass on INT and JS. The live differential
compares phase and semantic category rather than unstable vendor message text.

## claim-ledger-claimfile-scope-drift — inconsistent claim metadata makes the overlap guard fail open
<!-- status: fixed
     kind: apparatus
     lane: int
     area: runtime
     fixed-in: 0fade8820
     gate: tests/conformance/corpus-baseline.tsv -->

**Status:** **FIXED 2026-07-27** by opus (`claim-metadata-consistency`). Found 2026-07-27 by Codex
while claiming SPRINT E7; observed at `0fade8820` / `540f0ba52`.

**Confirmed still open when picked up, and independently re-observed.** A live-claim census found
exactly one drifting pair — and it bit within the hour: `corpus-gate-remaining-reds` had
`v1/runtime/**` in its `.claim` and `v1/runtime/backend/interpreter/**` in its ledger row, while the
row ALSO declared `tests/conformance/corpus-baseline.tsv` and `corpus-skip.txt`, which the `.claim`
did not — the Codex hole, still open at that moment.

**Fix — two asymmetric rules, split by who can fix the problem.** (1) A claim the push adds or
rewrites must agree with its own ledger row on both fields, compared as SETS so ordering is not a
false alarm; disagreement is refused, because the pusher owns both copies. (2) A claim already live
on the remote whose copies disagree is compared on the UNION of the two and named in a warning —
union is the fail-closed reading, and refusing on someone else's stale row would let one agent block
every other, turning the mutex into a deadlock.

**Second hole, same family, found while fixing the first:** the hook ran with globbing ON, and
`for p in $paths` is an unquoted expansion — so a claim path written `v1/runtime/**` was expanded
against the working tree and shrank to the directories that existed at that moment, silently ceasing
to cover anything created later. `set -f` now covers the whole hook.

**Verified by A/B, not by a green run.** The suite is now runnable against an older hook
(`HOOKS_SRC=<dir> bash tests/coord/claim-hooks.sh`). Against the PREVIOUS hook the four new gates
FAIL — ledger/claim paths drift, items drift, the union-from-the-ledger-side case, and the glob case
— and the negative control ("two copies agree" → allow) passes on both, so the suite is not merely
always-refusing. Against the fix: 20/20 PASS.

**Today's drifted row was deliberately NOT hand-repaired**, per this entry's own instruction. The
union rule makes it harmless, the warning names it, and rule 1 makes its owner sync it on their next
claim push — which is the mechanism doing the work rather than a one-off cleanup hiding it.

**Reproduce.** On `origin/main`, the `corpus-gate-remaining-reds` row in
`.work/active/LEDGER.tsv` declared
`tests/conformance/corpus-baseline.tsv`, while
`.work/active/corpus-gate-remaining-reds.claim` did not. A second claim
(`corpus-contract-baseline-roster`) declared that exact path and
`scripts/coord-claim` still pushed successfully as `0fade8820`; an identical
path overlap should have been refused.

**Root cause.** Scope is duplicated in the ledger and the per-claim file with
no consistency check. The pre-push overlap guard reads `items:` / `paths:` from
the remote `.claim` blobs, while the ledger is the mutex's authoritative active
scope and may contain different values. Any update that changes only one copy
silently creates a hole.

**Fix / done-when.** Queue `claim-metadata-consistency`: make every supported
claim update write both representations, and make pre-push fail closed when a
ledger row and its `.claim` disagree before it considers overlaps. The
regression gate must construct an inconsistent pair and prove the push is
rejected; a matching pair remains accepted. Do not merely repair today's row —
that would hide the mechanism.

## parser-package-wrap-drops-fence-attrs — a `package:` module silently loses EVERY fence attribute
<!-- status: fixed
     kind: bug
     lane: int
     area: front
     fixed-in: unrecorded -->

**Status:** **FIXED 2026-07-27** by `corpus-gate-remaining-reds` (one argument). Found while
implementing `@doc`: the feature worked on a plain file and did nothing in `std/coroutine.ssc`.

**Symptom.** With `package: x` in the front-matter, `@db=`, `@side=`, `@id=` and `@doc` all vanish —
`cb.attrs` arrives empty at every consumer. Without `package:` the same file is fine. Minimal A/B is
three lines of front-matter:

```
---
name: p
package: std.probe      ← remove this line and the @doc block stops running
---
```

**Root cause.** `Parser.wrapSectionInPackage` re-wraps each parseable block in `object <seg>:` and
rebuilds it as `Content.CodeBlock(cb.lang, nested, tree, cb.span, pe, cb.lineOffset)` — **without
`attrs`**. The field has a default (`Map.empty`), so the omission compiled and was silent. Fix: pass
`cb.attrs` through.

**Why it went unnoticed.** The only shipped consumer of fence attributes was `@db=`/`@side=` on `sql`
blocks, and the modules that use those do not declare a `package:`. It took a new attribute that
literate `std/` modules DO use to hit the combination.

## imported-builtin-native-runs-callback-in-defining-interpreter — an imported builtin runs your closure in the WRONG scope
<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     fixed-in: unrecorded -->

**Status:** **FIXED 2026-07-27** by `corpus-gate-remaining-reds`. Filed first as
`coroutine-started-after-resume-int-vs-v2` (one wrong flag); the flag turned out to be the visible
tip of a scope bug.

**Symptom, minimal (11 lines).** With `[coroutineCreate](std/coroutine.ssc)` imported, a coroutine
body cannot see the importer's top-level `var`s at all:

```scalascript
var n = 7
val co = coroutineCreate[String, String, String] { () => println(n); n = 99; suspend("s"); "done" }
println(coroutineResume(co, "x"))
println("outside n = " + n.toString)
```

Reading `n` inside the body raised `Undefined: n` (with a position pointing into
`std/coroutine.ssc`, which is the tell); writing it silently created a child-local binding, so the
caller's `var` stayed `7`. **Without the import the same program is correct** (`outside n = 99`) —
that A/B is the whole diagnosis.

**Root cause.** `coroutineCreate` is a NativeFn registered per-interpreter (`interp.globals(...) =
NativeFnV(...)`), closing over the interpreter that registered it. `std/coroutine.ssc` re-exports it
through `extern def`, so importing the module handed the CHILD's copy to the parent, and the body —
a closure created in the PARENT — was then run via the child's interpreter, against the child's
globals. Closures do not carry their interpreter (`CallRuntime.callValue(fn, args, env, interp)`
takes it from the caller), so the scope followed the builtin, not the closure.

**The guard already existed, one layer up.** `rebindPluginNative` rebinds *plugin* natives to the
caller for exactly this reason — its comment says "otherwise callback-style intrinsics invoke user
closures in the child Interpreter that loaded the dependency, where the caller's module globals are
absent". A *builtin* native was not covered. Fix: at the import binding site, if the imported value
is a `NativeFnV` and the importer already has its own `NativeFnV` under that name, bind the
importer's — a host builtin is per-interpreter infrastructure, not a value to transfer.

**Why it survived.** `tests/conformance/coroutine-native-lifecycle.ssc` — whose committed golden
states the correct answer (`started:false` then `started:true`) — was gated `backends: [jvm, v2]`,
so **INT was never held to it**. Widened to `[int, jvm, v2]` as part of this fix; INT now PASSes it.

**Verification.** `coroutine-demo` is byte-identical on INT and v2 (8 lines; it was 17 vs 8 before
`@doc`, and SKIPped on every lane before that). Body reads `n = 7`, writes `99`, caller observes
`99`. Full conformance **305 passed / 0 failed** — the change is in import binding, so the whole
suite was the affected slice, not a slice of it.

## coroutine-demo-import-cycle-on-interpreter — `examples/coroutine-demo.ssc` cannot run on the INT lane
<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     fixed-in: unrecorded -->

**Status:** FIXED (2026-07-27, `corpus-gate-remaining-reds`) by removing the three self-imports from
`v1/runtime/std/coroutine.ssc`. Found 2026-07-27 by `corpus-contract-shard-fix` in the corpus
contract's first completed verdict. Not a gate artifact — reproduced directly.

**Verification.** `coroutine-demo` on INT: rc 0, 17 lines (was: exit 1). `std/coroutine.ssc` run
DIRECTLY: 9 lines, **0 duplicate lines**. A genuine `A→B→A` cycle still errors
(`Import cycle detected: b.ssc → a.ssc → b.ssc`). The case is no longer `SKIP`ped on every lane —
which is what then exposed `literate-import-executes-example-blocks` and
`coroutine-demo-js-not-callable` above. **The gate is not greener for this fix; it is more honest:
one hidden SKIP became two visible, filed gaps.**

**Why the fix is in the `.ssc` and not the resolver — a rejected approach, recorded so it is not
retried blind.** The obvious fix is to make the resolver treat a DIRECT self-import as a no-op
(`moduleLoading` is insertion-ordered, so `moduleLoading.lastOption.contains(resolvedPath)` is
exactly "this module is importing itself"). That was implemented and it worked for the demo, and a
true `A→B→A` cycle still errored. **But it silently broke running a literate module directly:**
`ssc-tools run --v1 v1/runtime/std/coroutine.ssc` went from a clear cycle ERROR to executing every
example block **TWICE** (18 output lines, all duplicated). Cause: the ENTRY file is never pushed onto
`moduleLoading`, so its self-import is not recognised as one and the file is loaded as its own child
— body runs once as the child and once as the entry. Trading an explicit error for silent duplicated
side effects is a worse bug than the one being fixed. Doing it properly needs a `selfPath` on
`Interpreter` (the file whose body is executing) threaded through the entry construction too — a real
change to shared machinery, not a one-liner.

**Root cause (unchanged).** `v1/runtime/std/coroutine.ssc` re-imported ITSELF at lines 44, 63 and 82
(`[Step, coroutineCreate, coroutineResume, suspend](std/coroutine.ssc)`) inside its `## Example`
blocks. The cycle detector at `SectionRuntime.scala:382` is correct and caught it. The imports were
vacuous — proven, not assumed: with the resolver patch skipping them the examples still ran, so the
names were already in scope. The demo only started importing `std/coroutine.ssc` in `3cb6209a4`
("import demo API for tools check", 2026-07-21), which made the *tools check* pass and broke the
*interpreter* lane, unnoticed because the differential gate could not finish a run.

**Original symptom.** `bin/ssc-tools run --v1 examples/coroutine-demo.ssc` → exit 1 with
`[ERROR] Import cycle detected: coroutine.ssc → coroutine.ssc`
(`InterpretError` at `SectionRuntime.scala:384`). Because the corpus contract takes the live INT
output as the golden when a case has no `expected/` file, an INT that cannot run skips the case on
**every** lane — so this single defect removed the whole coroutine demo from differential coverage.

## jdk-backend-accept-teardown-race — uncaught `RejectedExecutionException` on `jdk-backend-accept` thread at teardown
<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     fixed-in: unrecorded -->

**Status:** FIXED (2026-07-27, `post-f4-board-reconcile`). Found 2026-07-23 by opus while fixing
`wsproxy-teardown-race` — a sibling of that race in a *different* subsystem (the JDK HTTP
backend, `http-server/jvm`), recorded with a ready one-line fix and deferred as out of the
wsproxy claim's scope; applied as queued. The accept loop now catches the rejection on the
submit itself and closes the orphaned client socket when `_running` is already false
(`_running` is `@volatile` and `stop()` flips it before `shutdownNow()`, so the guard is live
by the time a rejection is possible). Verified: `runtimeServerJvm/compile` clean,
`backendInterpreterServer/test` 62 succeeded / 0 failed (1 pre-existing canceled),
`ServeAsyncReadyTest` 4/4 on 3 consecutive runs with no `RejectedExecutionException` printed.
**Honest limit on that last number:** the race showed in only ~2/5 runs before, so three clean
runs is weak evidence on its own — the load-bearing part is that the documented stack
(`JdkServerBackend.scala:109` → `ThreadPerTaskExecutor.execute`) is now explicitly handled
rather than left to a catch clause that is inactive at teardown.

**Amendment 2026-07-27 (`f4-arc-closure`, measured — it is not weak evidence, it is NO evidence;
do not redo these two attempts):** the sibling `f4-arc-closure` lane independently applied the same
one-liner and then ran the baseline A/B that the note above stops short of. Result: **the suite
cannot distinguish fixed from unfixed.**

| Attempt | With fix | Without fix (`git stash` baseline) | Verdict |
|---|---|---|---|
| `backendInterpreterServer/testOnly *ServeAsyncReadyTest*` ×5 | 5/5 green, 0 occurrences | 5/5 green, **0 occurrences** | does NOT discriminate |
| purpose-built 40-round "connect continuously, `stop()` mid-flight" stress + `jdk-backend-accept` uncaught-exception collector | PASS | **PASS** | does NOT discriminate → test deleted rather than kept as a green-lying gate |

So the closure rests **entirely** on the code-path argument (a rejection at teardown matched no
clause: `SocketException` — wrong type; `Throwable if _running` — guard false), not on any green run.
Why the window resists synthetic reproduction: `stop()` flips `_running` and closes the listen socket
*before* `_connPool.shutdownNow()`, so after the close `accept()` throws (caught) and the loop exits —
the rejection needs `shutdownNow()` to land in the microseconds between an `accept()` that already
returned a client and the following `execute()`. That is why it only appeared under real
interpreter-server load. A deterministic gate would need a test-only seam in the accept loop;
judged disproportionate for a benign teardown-only defect. `runtimeServerJvm/test` 30/30 green.

**Symptom:** an uncaught `java.util.concurrent.RejectedExecutionException` prints on a
`jdk-backend-accept-<port>` thread at test teardown (observed in `ServeAsyncReadyTest`, ~2/5
local `backendInterpreterServer/test` runs). Stack:
`JdkServerBackend.$anonfun$3(JdkServerBackend.scala:109)` → `ThreadPerTaskExecutor.execute`.

**Root cause:** the accept loop submits `pool.execute(() => proxyConnection(client, …))`
(`JdkServerBackend.scala:109`) for a connection accepted right at shutdown. `stop()` has already
set `_running = false` and called `_connPool.shutdownNow()`, so the submit is rejected. The catch
clause `case _: Throwable if _running => ()` (line 112) only swallows *while running*; at teardown
`_running` is false, so the rejection escapes uncaught. (`WsProxy`'s own accept loop does NOT have
this bug — its catch swallows `Throwable` unconditionally and only *logs* when running.)

**Why non-CI-blocking (unlike the ws-proxy-conn race):** the process-wide WS slot is reserved
*inside* `proxyConnection`, which never runs here — so nothing leaks `_wsActiveCount` and there is
no cascade into a downstream 503/101 boundary failure. `sbt test` exits 0 even when it prints
(verified: 5/5 local runs green, exception appeared in 2). Only cost: one accepted `client` socket
leaked + stderr noise.

**One-line fix (ready):** mirror `WsProxy`'s accept loop — swallow the rejection during shutdown,
e.g. add to the catch at `JdkServerBackend.scala:110-112`:
`case _: java.util.concurrent.RejectedExecutionException if !_running => ()`
(and best-effort `client.close()`). Deferred: separate subsystem, not the ws-proxy-conn race.

## bench-compile-wrapper-hides-real-compiler-benches — compile measurements cannot start
<!-- status: fixed
     kind: apparatus
     lane: int
     area: front
     fixed-in: 5aee0cd35 -->

**Status:** DONE (2026-07-21, `5aee0cd35`; found and confirmed through the real wrapper by codex while
starting Q2 measured optimization).

**Reproduce:**

```bash
BENCH_WI=1 BENCH_MI=1 BENCH_F=1 scripts/bench compile parseActors
```

The wrapper exits 1 with `No matching benchmarks` after invoking JMH with
`.*CompilerBench.*parseActors.*`. The benchmark exists as `ParserBench.parseActors`, so the
apparatus excludes the observable it claims to measure. `scripts/bench compile typeActors` and
`unifyDeep` have the same shape because their classes are `TyperBench` and `UnifyBench`.

**Root cause / companion defect.** `scripts/bench` hard-codes `CompilerBench` as the compile-lane
class filter; only `SsccFormatCompilerBench` happens to contain that substring. Its `list` command
also queries only `interpreterBench`, so it hides every compiler-project benchmark while claiming to
list all available benches.

**Fix / verification.** The compile lane now selects all four compiler benchmark classes and `list`
aggregates the interpreter and compiler projects without duplicates. The CI wrapper regression
compares the exact generated sbt/JMH commands and combined list output, printing expected and actual
values on any mismatch. Real one-warmup/one-measurement runs through the wrapper selected
`ParserBench.parseActors` (3.934 ms/op), `TyperBench.typeActors` (0.003 ms/op), and
`UnifyBench.unifyDeep` (1.156 us/op); `scripts/bench list` showed all compiler classes. The smoke
numbers prove routing only and are not the Q2 optimization baseline. The regression gate and affected
`v2-*` conformance slice (11/11) passed.

## v1-interp-int-literal-above-2^31-becomes-null — the INT conformance REFERENCE silently prints `null`
<!-- status: fixed
     kind: bug
     lane: int
     area: front
     fixed-in: 5b71ad2f6
     gate: tests/conformance/int-width.ssc -->

**Status:** FIXED `5b71ad2f6` (`int-literal-failopen`). Found 2026-07-17 by
`int-width-conformance` while writing `tests/conformance/int-width.ssc`. Not reported by anyone —
found by measuring. Original SHA `cb35fffa6`, real harness (`bin/ssc-tools`, built by
`install.sh --dev`).

**FIX / confirmed root cause.** The suspected "32-bit host `Int` in the lexer" was WRONG — there is
no hand-written numeric lexer. `Parser.preprocessNumericLiterals` only promoted a literal
`> Long.Max` to `BigInt`; the band `(Int.Max, Long.Max]` was emitted as a **bare decimal**, which
scalameta's 32-bit `Lit.Int` cannot hold, so the block failed to parse and was swallowed by the
`Try{}.toOption.flatten` wrapper → `null`, exit 0. Fix: emit an `L` suffix for any non-decimal
magnitude `> Int.Max`, so scalameta yields `Lit.Long` (a 64-bit `IntV`). That fixes the whole band,
makes `-9223372036854775808` (min64) parse natively as `Long.MinValue`, and makes a literal past
Int64 a **hard parse error** (fail closed) — scalameta rejects `<n>L` for `n > Long.Max`. The
bare-oversized `> Long.Max` → BigInt auto-promotion was removed (it silently retyped a literal by
magnitude, against `specs/numeric-widths.md` §2); BigInt is the explicit `BigInt(...)` / `n` suffix.
Boundary map now byte-identical to v2 native across `[-9223372036854775808, 9223372036854775807]`.

**The v1 interpreter cannot represent an integer LITERAL at or above 2^31.** It degrades it to
`null` and **exits 0**. Its *arithmetic* is 64-bit; its *literals* are not. This is the INT
conformance reference — the lane every other backend is compared against.

**Repro** (v1 interpreter, `ssc-tools run --v1`):

```text
println(2147483647)      -> 2147483647           OK (2^31-1 fits a 32-bit int)
println(2147483648)      -> null                 WRONG, exit 0        <- 2^31
println(3000000000)      -> null                 WRONG, exit 0
println(4611686018427387904) -> null             WRONG, exit 0        <- 2^62
println(2147483647 + 1)  -> 2147483648           OK  (computed, not a literal)
println(2147483648 + 1)  -> [ERROR] No method '+' on (null)  ... and STILL exit 0
println(-9223372036854775808) -> [ERROR] Cannot apply unary - to 9223372036854775808
```

For contrast, v2 native prints every one of those correctly except the `min64` literal (see the
sibling entry below). Values *computed* from in-range literals are fine on both:
`(2147483647+1) * (2147483647+1)` gives an exact 2^62.

**Why this matters more than the arithmetic.** It **fails open**: a wrong answer (`null`), exit 0,
no diagnostic — the project's signature failure mode. It also makes the reference itself
non-conforming to `specs/numeric-widths.md` §2 (`Int` is 64-bit) for the literal surface, which
means the "v1 interpreter = 64-bit ✅ / v1 codegen = 32-bit ❌" framing in `SPRINT.md`
§int-width-conformance is **true only for computed values**. Nobody noticed because the canonical
probe everyone reaches for is `2147483647 + 1` — the one form that works.

**Suspected root cause (NOT yet confirmed — do not treat as diagnosed).** The literal parses into a
32-bit host `Int` somewhere in the v1 front/lexer and overflows to a null/absent value rather than a
64-bit one, while `EvalRuntime` arithmetic is genuinely 64-bit. Start at the `Lit`/constant path in
the scalameta-based v1 front, not at `EvalRuntime`.

**Scope note.** `tests/conformance/int-width.ssc` deliberately builds every value above 2^31 from
in-range literals so it tests the width contract rather than this bug. If this is fixed, that case
can be simplified — but do not simplify it before then.

## scljet-jdbc-stable-spi-import-regression — JDBC plugin bypasses the stable value surface
<!-- status: fixed
     kind: regression
     lane: int
     area: runtime
     fixed-in: aca439fcc -->

**Status:** RESOLVED via crystallization exemption (2026-07-19, `v1-crystallize-green`,
Sergiy-authorized). Originally raised by `ci-red-main` (reconfirmed 2026-07-17 in the real aggregate
`scripts/sbtc "test"` at code SHA `aca439fcc`).
`StableSpiEnforcementTest` failed its source boundary comparison because six files in
`v1/runtime/std/scljet-jdbc-plugin` import `scalascript.interpreter.Value`; `ScljetEngine.scala`
also imports `Interpreter`. The two-case suite reported 1 pass / 1 failure, listing each offending
file and import.

**Impact / real-harness evidence.** SclJet's SQL and JDBC behavior tests can pass while the plugin
silently regresses the stable plugin architecture. This is not a proxy classification: the guard
scans the shipped value-surface plugins, compares the observed imports against the permitted API,
and prints all six mismatches.

**Resolution (2026-07-19) — documented crystallization exemption, not a bare silence.** The earlier
"exemption is valid only for a runtime-provider boundary" stance predated Sergiy's v1/v2-independence
decision. Under that decision **v1 is crystallized: it stabilizes and freezes, and is NOT developed
further** (see SPRINT `v2-finish`). The stable-SPI enforcement exists to protect FUTURE SPI evolution;
a frozen v1 plugin has no future SPI evolution to protect, so the migration requirement does not apply.
The import is inherent to the facade's design — the SclJet `java.sql.Driver` shim bootstraps the v1
interpreter to run the pure-`.ssc` SclJet SQLite engine (added `9ac5d0a62`), so it traffics in
interpreter `Value`. `scljet-jdbc-plugin` was therefore added to `StableSpiEnforcementTest`'s `exempt`
set with a comment recording the frozen-v1 rationale (distinct from `actors-plugin`, which is exempt
because it is interpreter-coupled *by design*). Test 2 ("no stale exemptions") still guards it: if the
plugin ever stops importing `scalascript.interpreter`, the exemption fails and must be dropped. Focused
`StableSpiEnforcementTest` 2/2 green. If v1 is ever un-frozen for development, the honest follow-up is
the real migration to `scalascript-plugin-api` — this exemption is valid ONLY while v1 stays frozen.

## v2-native-jvmvfs-externs-unbound — host-file I/O intrinsics are invisible to the native tier
<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     fixed-in: 6131e17a3 -->

**Status:** **FIXED (2026-07-17, `6131e17a3`)** — new `v2/runtime/std/scljet-vfs-plugin`
(`ScljetVfsNativePlugin`) registers the 21 `jvmVfs*` natives on the native SPI. **All 9
`examples/scljet-*` now run on `bin/ssc run` and match the v1 reference byte-for-byte**, and the
default command reads a file written by the reference `sqlite3` 3.51.0 with output byte-identical
to sqlite3's own. No duplication: `SclJetJvmVfsHost` (438 lines, already dependency-free) moved
verbatim into a zero-dep `scljetVfsHost` module that BOTH plugins depend on — only the value
adapter is per-SPI, so the two lanes cannot drift on locking or durability. Gate:
`sbt v2NativeScljetVfsPlugin/test` 5/5 (a real file round-trip incl. lock tags and short reads),
which exists because the two examples covering this end-to-end write to temp paths and are
auto-skipped by the corpus contract as non-deterministic.
Found 2026-07-17 after the charAt/toLong fixes made the rest of scljet run natively.
**v2 native plugin host**, not the engine.

**Symptom/reproduce:**

```
bin/ssc run examples/scljet-readonly.ssc  → ssc: unbound global: jvmVfsDelete
bin/ssc run examples/scljet-jvm-vfs.ssc   → ssc: unbound global: jvmVfsDelete
bin/ssc-tools run --v1 <same>             → works
```

**Root cause.** The `jvmVfs*` externs (`scljet/jvm-vfs.ssc`) are supplied by
`v1/runtime/std/scljet-vfs-plugin`, a **v1-style** plugin resolved through the interpreter's
ServiceLoader. The native tier runs its own `NativePluginHost` over `v2/runtime/std/*-plugin`, so a
v1 plugin is simply not in its world. `bin/lib/.../plugins/scljet-vfs-plugin.sscpkg` being present
is a red herring — the file is there, the native host does not consult it.

**Impact.** Everything in scljet that does not touch a host file now runs on the default command
(7 of 9 `examples/scljet-*` pass; SQL, CRUD, codecs, in-memory VFS, writes). The two that open a
real file do not. So on `bin/ssc run` scljet is an in-memory engine only.

**Fix (not a one-liner).** Port `scljet-vfs-plugin` to the v2 native plugin SPI (`NativePlugin` /
`NativePluginHost`), the way `v2/runtime/std/content-plugin` is done. Until then, host-file scljet
belongs to the `int`/`js`/JVM lanes, which is where its conformance already runs.

## v2-native-scala-import-parse-only-noop — module-defined names stay unbound after `import std.*`
<!-- status: fixed
     kind: bug
     lane: int
     area: front
     fixed-in: unrecorded -->

**Re-verified NOT REPRODUCING 2026-08-02 at `1305736e1`.** The entry's own narrow probe, run again
on both lanes: `import std.mapreduce.*` then `Stage(List(MapOp("tag"))).ops.length` prints `1` on
native AND `1` on v1 — the module-defined names bind. (The original fixture's paths,
`std/mapreduce/handlers.ssc`, no longer exist, so the narrow probe is the reproducible half.)

`fixed-in: unrecorded` deliberately: `8f736ca8b` appears in this entry as the sha the measurement
was taken ON, not as a fix, and recording a measurement sha as the fix is how a `fixed-in` field
comes to name the wrong commit.

**Status:** **NO LONGER REPRODUCES 2026-07-28**, measured by `v2-native-import-graph` (NIG-3) on
`8f736ca8b`. Left here rather than deleted because the entry's diagnosis ("the native front's
`parseOneStmt` consumes Scala-style imports as parse-only no-ops") is what someone would go fix,
and it is no longer the observable.

**What was measured.** The entry's own fixture, written twice — once with the Markdown link
imports it recommends, once with the Scala-style import it says is broken — and run on both lanes:

| program | `bin/ssc run` (native) | `ssc-tools run --v1` |
|---|---|---|
| `[NamedHandler, HandlerRegistry](std/mapreduce/handlers.ssc)` + `[…](std/mapreduce/distributed.ssc)` | `3` / `1` | `0`, then a handler-registry error |
| `import std.mapreduce.*` | `3` / `1` | `0`, then a handler-registry error |

Both import forms are now **identical** on the native lane and both produce the fixture's expected
`3` / `1`. A narrower probe confirms the binding directly: `import std.mapreduce.*` then
`Stage(List(MapOp("tag"))).ops.length` prints `1` on native and `1` on v1 — the module-defined
`Stage` and `MapOp` resolve. The reported symptom
(`ssc: unhandled runtime effect: WorkerProtocol.applyStage`) does not appear on any probe.

**What the same measurement DID find — and it is the other lane.** In the fixture above the v1
INTERPRETER is now the one that diverges: it prints `0` where native and JS print `1`. Minimal,
four lines, no Scala-style import involved: see
`int-imported-module-mutable-registry-not-shared` below.

**Original report follows** (found 2026-07-17 by `ci-red-main` after correcting
`V2TuplePatternCliTest` to use staged `bin/ssc`). The map-reduce fixture's Scala-style
`import std.mapreduce.*` appears to make registry-backed names such as `HandlerRegistry` available,
but does not load module-defined `WorkerProtocol`; the program prints its first expected line `3`
and then exits with `ssc: unhandled runtime effect: WorkerProtocol.applyStage`.

**Real-harness repro.** After `scripts/sbtc "cli/assembly; installBin"`, run a `.ssc` containing
`import std.mapreduce.*`, handler registration, and
`WorkerProtocol.applyStage(Stage(List(MapOp("tag"))), List("ada"))` through `bin/ssc`. The native
front's `parseOneStmt` branch explicitly consumes Scala-style imports as parse-only no-ops because
it assumes imported names resolve through registry/globals. An unbound qualified call then falls
through to an `Op`, producing the same misleading `unhandled runtime effect` diagnostic documented
for other missing module names. This is distinct from a Markdown link ignored inside a fence.

**Expected/fix direction.** Define whether Scala-style package imports participate in the native
module graph and implement that contract instead of relying on an incomplete ambient registry.
Until then, the supported native module form is an outside-fence Markdown link such as
`[WorkerProtocol, Stage, MapOp](std/mapreduce/distributed.ssc)`. The tuple CLI test should use those
explicit links because it tests handler-registration order, not import syntax. This bug remains
open after that fixture correction; at least `examples/distributed-dataset-typed-helpers.ssc` also
uses the affected Scala-style import and needs a future real-harness audit.

## v2-native-charAt-toString-yields-code — `charAt(i).toString` renders the character CODE on v2-native → every uppercase keyword breaks
<!-- status: fixed
     fixed-in: f39448c96
     lane: int
     area: front
     kind: bug
     gate: tests/e2e/char-type-test.sh -->

**SUPERSEDED (see the close at the end) — was: STILL REPRODUCES, re-verified 2026-08-02 at `1305736e1`**, byte for byte as reported:

```
bin/ssc-tools run --v1  →  INSERT
bin/ssc run             →  737883698284
```

**RE-MEASURED 2026-08-09 ON ALL FOUR LANES, and the framing below is wrong where it says the two
lanes cannot both be right.**

| lane | `charAt(i).toString` over `"INSERT"` |
| --- | --- |
| int | `INSERT` |
| **jvm** | `INSERT` |
| js | `INSERT` |
| native | `737883698284` |
| v2 bridge | `737883698284` |

The jvm lane compiles to real Scala, where `charAt` returns a `Char` and `Char.toString` is the
character — there is nothing ambiguous about it at the LANGUAGE level, and three lanes including that
oracle agree. So this is not "two lanes cannot both be right, so it needs a language decision"; it is
**one lane wrong against the oracle**, three to two. The earlier note compared int against native
only, which is why the question looked open.

**What IS a decision — and the entry is right that it is one — is the COST.** `v2/src/Runtime.scala`
models a char as its code on purpose: line 2110 is
`case (StrV(s), "charAt", List(IntV(i))) => IntV(s.charAt(i.toInt).toLong)`, and the comment at line
73 says so plainly ("`s.charAt(i) == 34`"). Without a Char representation the runtime cannot tell an
`Int` that came from `charAt` from any other `Int`, so `.toString` has nothing to dispatch on. The
open question is therefore how v2 represents `Char`, not which answer is correct.

Left open and not attempted here: `v2/src/Runtime.scala` is under another agent's claim, and the
change is a kernel representation decision rather than a patch.

Classified `open` rather than `unknown` on that evidence. Worth restating why it is not a small
divergence: it is **silent wrong data with a zero exit code**, so nothing that checks a status sees
it, and the shape of the wrongness hides it — an already-uppercase string comes back as digits while
a lowercase one is fine, because that branch goes through `.toChar.toString`. The entry's own
analysis stands: without a Char box, `charAt(i).toString` is inherently ambiguous and the two lanes
cannot both be right, so this needs a language decision, not a patch. `gate: none` is accurate — the
engine-side fix removed the only in-corpus user, which means nothing in the suite would notice a
regression here.

**SUPERSEDED. Status was:** **ENGINE SIDE FIXED (2026-07-17, `46f09ad29`)** — scljet no longer uses the ambiguous
idiom, and the SQL engine now runs on the default `bin/ssc run` (23/24 scljet conformance cases,
was 0). **The LANGUAGE-LEVEL divergence stays OPEN**: any other `.ssc` using `charAt(i).toString`
is still silently wrong on v2-native. Found 2026-07-17 while asking why the scljet engine cannot
run on the DEFAULT `bin/ssc run`. **v2 native front / no-Char-box design**, not the engine. Same
family as the documented `v2-native-string-map-filter-char-methods`.

**Symptom/reproduce** — silent wrong data, and the shape of the wrongness hides it: an
already-uppercase string comes back as digits, a lowercase one is fine.

```scala
val s = "INSERT"
var a = ""; var i = 0
while i < s.length do { a = a + s.charAt(i).toString; i = i + 1 }
println(a)
// bin/ssc-tools run --v1 → INSERT          (the conformance reference)
// bin/ssc run           → 737883698284     ← v2 native: the char CODES, concatenated
```

**Root cause — by design, and that is the point.** `v2/lib/ssc1-lower.ssc0:1410` lowers
`charAt(i)` to the primitive `scodeAt` — the code POINT — because **v2 has no Char box** (chars are
`IntV`; see `v2-native-string-map-filter-char-methods`). So `.toString` on it is `Int.toString` and
prints the number. `.toChar.toString` works (it yields a 1-char String), which is why a *lowercase*
input survives: that branch goes through `(c - 32).toChar.toString`.

This cannot be fixed by making `charAt` return a 1-char String: `charCode(s, i)` is defined as
`s.charAt(i).toInt` (`scljet/sql.ssc:89`), and `String.toInt` would then try to parse `"I"` as a
number. Without a Char box, `charAt(i).toString` is **inherently ambiguous** — the two lanes cannot
both be right.

**Impact — this is why scljet does not run on `bin/ssc run`.** `upperStr` (`scljet/sql.ssc:100`)
uses `s.charAt(i).toString` for the non-lowercase branch, so `upperStr("INSERT")` →
`"737883698284"`. Every SQL keyword comparison is `isKw(tok, "INSERT")` = `upperStr(tok.text) ==
word`, so on v2-native the engine does not recognise its own keywords:

```
v1:        INSERT INTO emp VALUES (7,'bob','sales',250)  → ok
v2 native: executeMutation expects INSERT, DELETE, UPDATE, DROP INDEX, CREATE TABLE, or CREATE INDEX
```

**Fix taken (engine-side, portable):** replace `X.charAt(i).toString` with
`charCode(X, i).toChar.toString`, which is correct on BOTH lanes (verified) and symmetric with the
branch right next to it. 9 sites in `scljet/sql.ssc`.

**Still open (the language-level half).** Any `.ssc` code using `charAt(i).toString` is silently
wrong on v2-native. Options, none free: (a) add a Char box to v2 — previously rejected; (b) a
peephole in the lowering so a *direct* `charAt(i).toString` chain emits a code→string conversion —
fixes the common form, misses `val c = s.charAt(i); c.toString`; (c) fail closed on the ambiguous
shape, in the spirit of `1832b5b22` (a typo'd zero-arg method FAILS CLOSED). Silent divergence is
the worst of the four.

**Method note.** The `bin/` in a checkout can be *stale*: the shared main checkout's `bin/ssc`
reported a `StackOverflowError` for this same program (an older build), while a freshly
`installBin`-ed worktree reports the real error. Always rebuild before believing a v2-native
failure — AGENTS.md's "reproduce in the real harness" applies to the lane's binary too.

---

### CLOSED 2026-08-12 — fixed in `f39448c96` on 2026-08-10, and nothing here could tell

**The entry outlived its defect by two days.** Re-measured on a build made from this tree, the same
five-row program on five lanes:

| | `charAt(0)` | `.toString` | `.toInt` | `"[" + c + "]"` | the `+=` loop over `"INSERT"` |
|---|---|---|---|---|---|
| int | `I` | `I` | `73` | `[I]` | `INSERT` |
| native | `I` | `I` | `73` | `[I]` | `INSERT` |
| bytecode | `I` | `I` | `73` | `[I]` | `INSERT` |
| js | `I` | `I` | `73` | `[I]` | `INSERT` |
| jvm | `I` | `I` | `73` | `[I]` | `INSERT` |

`f39448c96` took the option this entry lists as (b) and the analysis above calls impossible — and
the analysis is wrong in an instructive way. It says a Char cannot both render as a character and
convert as a number "without a Char box", then concludes the two lanes cannot both be right. The fix
adds the box, but as `CharV extends IntV`: a Char IS its code point at every numeric site and
carries the character at the few text sites. `charAt` moved onto a SECOND primitive, `scharAt`,
while `scodeAt` stayed for the compiler's own passes, which build escapes and need the number.

**THE OPEN HALF WAS THE GATE, NOT THE LANGUAGE.** `gate: none` was accurate, and it is why this sat
here: nothing in the suite could have told anyone the defect was gone, or noticed it coming back.
`tests/e2e/char-type-test.sh` did exist by then — written for the same fix — but it asked only
`case _: Char`, the type-ascription half. What a Char PRINTS was ungated, which is precisely the
half this entry is about.

### The gate now carries both halves, and the second control is the one that matters

Five rendering rows added, and the gate verified against TWO planted defects rather than one:

**Plant 1 — the original cause.** `scharAt` reverted to `scodeAt` in the staged F front
(`bin/lib/*/native-front/tower/bin/fsub.ssc`, which is read at runtime, so no rebuild). Gate RED,
reproducing this entry's string exactly: `INSERT` → `737883698284`.

**That plant alone would NOT have justified the new rows** — it also flipped row 4
(`kind(s.charAt(0))`: `Char` → `Int`), so the OLD five-row gate caught it too. A control that the
old apparatus also passes is not evidence that the new apparatus adds anything.

**Plant 2 — a regression the old gate is blind to.** Both Char rendering arms in
`v2/src/Runtime.scala` disabled (`case CharV(c) if false => c.toString`, and the same on the
`toString` method arm), leaving `__isTag__` untouched. Result:

    rows 1-5   Char String Int Char String     <- IDENTICAL. The old gate reads GREEN.
    rows 6,7   I  I           ->  73  73
    row  8     73              ->  73           <- unchanged in BOTH states, deliberately
    row  9     [I]             ->  [73]
    row 10     INSERT          ->  737883698284

Row 8 is in the gate as the OPPOSITE guard: `charAt(0).toInt` must stay `73`, so a future "fix" that
makes a Char stop being a number fails here rather than in a corpus program weeks later. It is the
one row that must NOT move, and holding it still under both plants is what shows the other four are
measuring rendering and not the representation as a whole.

Row 10 is the original repro rather than a reduction: an accumulation over an ALREADY-UPPERCASE
string. The shape is load-bearing — the reported bug was invisible on lowercase input, because that
branch went through `.toChar.toString` and was correct. A tidier fixture using `"abc"` would have
passed on the broken toolchain.

**Cost:** none in the suite's shape — the same four lane invocations run the same single program,
five rows longer. The smoke budget (180 s) is untouched.

## busi-v1-lane-runtime-regressions — four imported owner adapters fail on the 3666-based v1 runtime
<!-- status: fixed
     kind: regression
     lane: int
     area: front
     fixed-in: f81d86e98 -->

**Status:** done (2026-07-16; confirmed by busi on the assembled derived pin). Both the production
v2 lane and documented `--v1` rollback lane are green.

**Real-harness repro:** busi `origin/main` after `ksef-2-0-protocol`, with its malformed KSeF
front matter quoted, runs `make v2-web-e2e-v1` against the assembled pin `25a2bfebc`: 5/9 pass.
Housing, Personal Vault, Official Documents and Corporate fail; `make v2-web-e2e-v2` passes 9/9.
The same failures reproduce without Chromium through the imported adapters:

```sh
SSC_LANE_FLAG=--v1 SSC_NO_CDS=1 scripts/ssc tests/v2/housing_http.ssc
SSC_LANE_FLAG=--v1 SSC_NO_CDS=1 scripts/ssc tests/v2/personal_vault_http.ssc
SSC_LANE_FLAG=--v1 SSC_NO_CDS=1 scripts/ssc tests/v2/residency_http.ssc
SSC_LANE_FLAG=--v1 SSC_NO_CDS=1 scripts/ssc tests/v2/corporate_http.ssc
```

They stop respectively at `head on Nil`, `No field 'isEmpty'`, `Option.get on None`, and
`Error: null`. The pin is `3666ccb7a` plus five std/ui-only commits; busi had previously recorded
the same browser matrix 9/9 on the later `6826f2569` lineage, before downgrading for a JSON-renderer
compatibility problem. The owner adapters themselves did not change between those runs.

**Fix direction:** identify the minimal post-3666 interpreter fixes, add one imported multi-file
regression that retains the real nested callback/effect shape, then publish a derived busi pin from
the current cherry-pick lineage. Do not paper over interpreter state corruption in busi, and do not
bundle the unrelated current-main launcher-tier migration.

**Root isolated for Personal Vault:** its imported domain declares `enum DataClass` with a
parameterless `case None`. v1 `StatRuntime.bindNullaryCase` currently installs every nullary case
both as `DataClass.None` and as a bare global, overwriting the built-in `Value.NoneV`. The later
Option-valued vault code therefore receives `InstanceV(None)` and `.isEmpty` fails. The fix must
protect the built-in binding while retaining qualified enum access, with a multi-file regression;
renaming the busi enum or broad-disabling bare enum cases would only hide the runtime defect.

**Remaining roots isolated by assembled A/B:** with an explicit `-Xss64m`, the exact published
pin still fails Housing at `xs.head` after an `xs.nonEmpty && ...` guard and Official Documents at
`Option.get` after an `isEmpty || ...` guard. Short-circuit evaluation fixes those direct adapter
repros, but the canonical browser lane also needs the two function-local `var` isolation/re-sync
fixes before Housing passes. The focused Corporate adapter was likewise an incomplete oracle:
after the deterministic stack exposes the real request path, its exported function calls an
internal `sameMoney`, which calls the module-local Boolean `sameCurrency`; that second helper call
instead resolves the importer's internal `std.money.sameCurrency: Unit` and fails at `Unit && ...`.
The module importer enriches only the exported function, leaving internal helper `FunV` closures
empty. The fix must bind locally declared functions to their defining module context at arbitrary
helper depth while preserving caller-interpreter effects. A broad all-global binding was rejected:
it creates recursive structural `FunV`/Map equality during canonical hub boot.

**Fix and verification:** `207109cc3` protects core ADTs during enum registration; `9f7c3ce6c`
pins imported short-circuit guards; `10e116a63` binds locally declared helpers to an
identity-stable lexical module view with a faithful multi-file name-collision regression. The
upstream interpreter suite passed 1849/1849 and the affected conformance slice passed 8/8. The
minimal published consumer lineage additionally backports the deterministic launcher stack and
the two function-local var fixes; its head is `83941df60` on
`origin/busi-pin/v1-runtime-regressions`. Busi confirmed all focused adapters and the real browser
matrix: `make v2-web-e2e-v1` 9/9 in 4.4 minutes and `make v2-web-e2e-v2` 9/9 in 1.6 minutes.

## v2-native-double-toLong-noop — `Double.toLong` is a no-op on v2-native → any Long op on the result explodes
<!-- status: fixed
     kind: bug
     lane: int
     area: front
     fixed-in: 3449c588c -->

**Status:** **FIXED (2026-07-17, `3b0ddea92`)** — `v2/lib/ssc1-lower.ssc0` now routes `toLong` to
the shared runtime method table like its neighbours `toInt`/`toDouble`, so the receiver decides.
The risk recorded below (that `Int.toLong` survives only BECAUSE the lowering erases it) was
measured before landing: `Int.toLong`, `Long.toLong` and `Double.toLong` all behave. Gate:
`contract.sc --lanes v2` — no new regressions, one closed gap recorded (`scljet-crud`).
Found 2026-07-16 by `scljet-address`; the SAME class as `v2-native-toDouble-toFloat-noop`, same
file. **Not the scljet engine — the v2 native front.**

**Residual, pre-existing, NOT introduced by the fix: `String.toLong` is wrong on v2-native.**
`"42".toLong + 1L` → `421` before the fix (the no-op left a String, so `+` concatenated) and
`<closure>1` after (the native method table has no String→Long entry, unlike `String.toInt` which
works). Both are wrong; v1 says `43`. The new shape at least fails visibly instead of looking like
a plausible number. scljet is unaffected — all its `.toLong` receivers are Ints.

**Symptom/reproduce** — three lines, and the failure is *loud but misattributed*: the value even
prints correctly, so it looks fine right up to the first arithmetic:

```scala
val d: Double = 6.0
val n: Long = d.toLong
println((8L | n).toString)
// bin/ssc-tools run --v1  → 14          (v1 interp, the conformance reference)
// bin/ssc run             → ssc: expected Int, got 6     ← v2 native
```

`.toLong` leaves the value a **Double**; it renders as an integer (whole doubles print without a
decimal point), so nothing looks wrong until a Long/bit operation receives it. `Double.toInt`
works — only `toLong` is broken. `Long.toDouble` works (that was the earlier fix).

**Root cause — confirmed, and the comment names the blind spot.** `v2/lib/ssc1-lower.ssc0:1624`:

```
else if #seq(field, "toLong") then robj      -- no-op
...
-- ... `toLong` stays a no-op (Long IS Int here).
```

That reasoning is right for an **Int** receiver (ssc `Int` is 64-bit, so `Int.toLong` IS identity)
and wrong for a **Double** one, which must actually convert. The adjacent `toDouble`/`toFloat`
were fixed on 2026-07-15 by routing them to the shared runtime method table; `toLong` was left
behind. `else if #seq(field, "toShort") then robj` (line ~1633) is the same shape and likely the
same bug.

**Fix (one line, same shape as its neighbours), plus the check it needs.** Route it:
`Pair("prim", Pair("__method__", Cons(Pair("str", "toLong"), Cons(robj, Nil))))`. The backends
already implement it — `JvmBackend.scala:362` (`case "toLong" | "toInt"`), `RustBackend.scala:1610`
— **but verify the NATIVE tier's method table has `toLong` before landing**: today every
`Int.toLong` survives *because* the lowering erases it, so routing it through `__method__` when
the native table lacks the entry would break every existing `Int.toLong`.

**Impact.** `scljet/write.ssc:163` (`encodeReal`) does `(… * 4503599627370496.0).toLong`, so
**every REAL write through scljet crashes on `bin/ssc run`** — the default command
(`ssc: expected Int, got 2251799813685248`, which is `0.5 * 2^52`, the mantissa of 1.5). It went
unnoticed because scljet's conformance runs `[int, js]`, and `int` (the v1 interp) is correct.

**Related, opposite direction (minor, not filed separately):** `Double.toDouble` does not exist on
the **v1 interp** (`No method 'toDouble' on Double`) but works on v2-native — a stdlib
completeness gap of the same family as `interp-collection-stdlib-completeness-gaps`.

## v2-native-front-multiline-curried-def — a curried `def` whose second clause starts on a new line is mis-parsed
<!-- status: fixed
     kind: bug
     lane: int
     area: front
     fixed-in: d0722478e -->

**Status:** FIXED 2026-07-18 by `native-front-run-gaps` (`d0722478e`; claim released in
`e0a1c8e6f`). Found 2026-07-16 while probing why `std/agent.ssc` would not parse on the native front.

**Reproduce:**

```bash
# a bare .ssc (no fences) is code in its entirety, so no nested fence is needed here
printf 'def agentTool(name: String, description: String)\n             (handler: String => String): String =\n  handler(name + description)\nprintln(agentTool("a", "b")(s => s + "!"))\n' > /tmp/c.ssc
bin/ssc run /tmp/c.ssc            # native → ssc: TYPEERR: cannot unify Tuple with non-Tuple
bin/ssc-tools run --v1 /tmp/c.ssc # v1     → ab!
# the same def written on ONE line works on both lanes
```

**Root cause.** Semicolon inference (`ssc1-front.ssc0`, `canEndLine`/`canStartLine`) turns the
newline between `)` and `(` into `;` — a `)` can end a statement and a `(` can start one — so
`parseDef`'s `if kindIs("(", toks4)` misses the second clause and `(handler: …)` parses as its own
statement. `isCont` is the wrong place to fix it: adding `(` there would re-glue
`val x = f { … }` NL `(x, y)`, a bug that pass explicitly fixed.

**Fix/result** (in `parseDef`, right after the first `parseParamList`): a def signature is not
complete before its `:`/`=`, so a `;` sitting directly before a `(` is always a continuation —
drop just that separator. A `;` followed by anything else still ends the signature, so an abstract
`def op(x: Int)` in a trait/effect stays abstract. The staged native/v1 repro prints `ab!` on both,
the same-line form remains green, and `std/agent.ssc` advances to the independent `try`/`catch` gap.

## interp-collection-stdlib-completeness-gaps — common List/String/math methods missing on the v1 interp
<!-- status: fixed
     kind: feature
     lane: int
     area: front
     fixed-in: unrecorded -->

**Status:** FIXED (2026-07-15) — first batch. Surfaced by the v2-vs-v1 differential
(sprint #16) as `No method …` errors on the v1 interpreter (`ssc-tools run --v1`), the conformance
reference. Added: `List.reduce` / `reduceRight` / `reduceOption` / `reduceLeftOption` /
`transpose` (`DispatchRuntime.dispatchList`, next to the existing `reduceLeft`; also added to
the builtin-vs-extension precedence whitelist `hasBuiltinMemberBeforeExtension`);
`String.capitalize` (`DispatchRuntime` string dispatch, uppercases only the first char per
Scala); `math.max` / `math.min` (Int/Double overloads in `intrinsics/Core.scala`, wired into
the `math` object in `BuiltinsRuntime`). `Vector(…)` is a `ListV` in the interp so these cover
Vector too. Regression test `CollectionGapsTest`.
**Second batch (2026-07-15):** `List.patch` / `zipAll` / `scanRight` / `distinctBy` / `sliding(size, step)`
(the 2-arg form) added to `dispatchList` (+ whitelist); a `seqElems` helper lets `patch`/`zipAll` accept
both `List(…)` and `Vector(…)` collection args (existing methods like `diff`/`intersect` only accept
`ListV` — a broader pre-existing limitation left as-is).
**Third batch (2026-07-15):** `Int.toHexString`/`toBinaryString`/`toOctalString` (64-bit, via `java.lang.Long`,
in `dispatchInt`); `Integer.parseInt(s[, radix])` (the radix form is the only way to parse hex/binary — `.toInt`
takes no radix; `intrinsics/Core.scala` + an `Integer` object in `BuiltinsRuntime`); `List.partitionMap`
(Left/Right = `InstanceV(_, "value" -> v)`).

## v2-native-string-map-filter-char-methods — `String.map`/`.filter` + char methods on v2-native
<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     fixed-in: unrecorded -->

**Status:** FIXED (2026-07-15, `v2/src/Runtime.scala`). `"abc".map(...)`/`.filter(...)` threw `no dispatch
for .map on "abc"` on `bin/ssc run` (v2 native), and char methods on an iterated char (`c.toUpper`,
`c.isDigit`) eta-expanded to a closure. ROOT: v2 has NO Char box (chars are `IntV` code points), so unlike
interp/JS (which added `CharV`/`_Char` — see the `string-map-nonchar-*` entry below) v2 can't distinguish a
Char from an Int by type. FIX (boxless adaptation): (1) `String.filter`/`filterNot` → String (unambiguous —
a Char predicate keeps it a String). (2) `String.map` → if every result is an `IntV` in 16-bit char range,
render a String (the dominant char→char case, matching v1), else a List. (3) added char ops on a code-point
`IntV` — `toUpper`/`toLower` (return the transformed char CODE so a map renders a String) and
`isDigit`/`isLetter`/`isLetterOrDigit`/`isUpper`/`isLower`/`isWhitespace` (Bool); safe because these are only
ever called when the Int IS a char. Verified byte-identical to the v1 interpreter on the common cases
(`map(c=>c.toUpper)`→`ABC`, `filter(c=>c.isDigit)`→`123`, `map(c=> if c.isLetter then c.toUpper else c)`→`A1B2`).
KNOWN residual divergence (documented, no Char type to avoid it): `"abc".map(c => c + 1)` → String `"bcd"` on
v2 vs `List(98,99,100)` on v1 — char→int-in-range arithmetic can't be told from char→char without a Char box.
Remaining separate gap: `String.padTo` on v2-native.

## v2-native-toDouble-toFloat-noop — `.toDouble`/`.toFloat` dropped by the native frontend → integer division
<!-- status: fixed
     kind: bug
     lane: int
     area: front
     fixed-in: unrecorded -->

**Status:** FIXED (2026-07-15, `v2/lib/ssc1-lower.ssc0`). Found by the v2-vs-v1 differential
(task #16): `List(1,2,3,4,5,6).sum.toDouble / xs.length` printed `3` on `bin/ssc run` (v2
native) but `3.5` on the v1 interpreter and the v2 BRIDGE lane (`ssc-tools run --v2`). The
native ssc1 frontend lowered `.toDouble` and `.toFloat` to the bare receiver (`robj`, a
no-op), so the value stayed an Int and a following `/` did integer division
(`7.toDouble / 2` → `3`, not `3.5`). This is correct for `.toLong` (Long IS Int in v2's
representation) but wrong for the float conversions. FIX: lower them to
`__method__("toDouble"/"toFloat", robj)` like the `.toInt` case right above, so the shared
runtime method table converts (`IntV(n).toDouble → FloatV`; String receivers convert too).
Isolated because the bridge lane (same v2 Runtime, v1 scalameta frontend) was already
correct → the bug was purely the native frontend lowering. Verified via native `v2/ssc1`:
`21.toDouble/6` → `3.5`, `7.toDouble/2` → `3.5`; conformance 640ok.

## interp-string-interp-open-bracket-in-nested-string — `[` in a string literal inside `${…}` mangles the interpolation
<!-- status: fixed
     kind: bug
     lane: int
     area: front
     fixed-in: unrecorded -->

**Status:** FIXED (2026-07-15, `Parser.scala` `preprocessListLiterals`). ROOT CAUSE (not
what the original report guessed): NOT the interpreter or scalameta, but the `.ssc`
**preprocessor** that rewrites ScalaScript list-literal syntax `[1,2,3]` → `List(1,2,3)`.
Its string-skipper `skipStringFrom` was not interpolation-aware: for `s"…${expr}…"` it
stopped at the first `"` INSIDE a `${…}` splice, so a splice containing a string literal
with `[` (e.g. `${xs.mkString("[", ", ", "]")}`) leaked its `[` back to the list-literal
rewriter, which turned `"["` into `"List("` — corrupting the code so the interpolation
rendered `List(1, 2, 3)` (fallback `xs.toString`) and `${xs.mkString("[")}` became the
garbled `1List(2List(3`. FIX: `skipInterpStringFrom`/`skipSpliceFrom` skip `${…}` splices
(and their nested strings/braces) when the `"` opens an interpolated string
(`isInterpQuote`: preceded by an identifier char), used in the main scan loop and
`findClose`. Verified: `preprocessListLiterals` leaves splice brackets untouched while still
rewriting genuine `[…]` list literals (incl. `[s"${x.mkString("[")}", 2]` →
`List(s"${x.mkString("[")}", 2)`); `InterpBracketTest` + core/test 1048/0 + interpreter
suite green. Found by the v2-vs-v1 differential (task #16); the v2 lanes were already correct
because they don't run this `.ssc` list-literal preprocessor. NOTE: sibling preprocessors
that also skip strings non-interpolation-awarely (`preprocessBraceCharLiterals`, `hasArrow`)
could have an analogous latent issue — not observed, left as-is.

## control-interop-harness-rust-multishot-drift — FIXED (2026-07-14, claude)
<!-- status: fixed
     kind: apparatus
     lane: int
     area: runtime
     fixed-in: cbdc4791a -->

**Status:** fixed in the `control-vectors-audit-followup` landing; reported by
codex-interop in rozum (#interoperability, 2026-07-14) while auditing the
semantic-vectors lane. "I will not touch your harness files."

**Symptom:** `tests/interop-conformance/probes/02-multi-shot-resume.ssc:13` states the
Rust runner's multi-shot is "deferred (R.6)", contradicting the landed Rust R.6
multi-shot and the current portable-VM profile.

**Reproduce:** `git grep -n "deferred (R.6)" tests/interop-conformance/` shows the stale
comment line.

**Fix/verification:** the probe comment now states Rust R.6 multi-shot has landed while
v2 JS still lacks `effect.*`; harness 9/9 measurable-now still green.

## control-interop-portable-vm-oneshot-guard-absent — FIXED / awaiting confirmation (2026-07-14, claude)
<!-- status: fixed
     kind: apparatus
     lane: int
     area: front
     fixed-in: cbdc4791a
     confirmed: no -->

**Status:** fixed in `cbdc4791a`; awaiting reporter confirmation. Formerly tracked
as pending conformance axis 21, now promoted to the measurable suite. Reported by
codex-interop (rozum #interoperability, 2026-07-14): "generated JVM effect runtime
appears to lack the one-shot repeated-resume guard present in JS/interpreter paths".

**Symptom:** resuming twice from a handler over a plain (non-`multi`) `effect` runs
silently and returns a value instead of a typed one-shot-violation diagnostic.

**Reproduce:** `handle { val x = One.op(); x } { case One.op(resume) => resume(1) + resume(2); case Return(x) => x }`
→ `3` on BOTH portable-VM tiers: `ssc run` (interp) and `ssc run --bytecode` (2026-07-14).
Cross-lane per codex-interop: the guard IS present in the v1 interpreter / JS paths and
ABSENT in generated JVM — confirmed absent on the portable-VM native lane here.

**Root cause:** both v2 frontends erase declaration multiplicity before the shared effect
runtime. The self-hosted parser records `effect_decl(name, false|true, ops)`, but
`ssc1-lower.ssc0` binds it as `ignoredMulti` and always emits reusable `effect.perform`;
the compatibility bridge rewrites `multi effect` to `effect`. `PortableEffects` therefore
constructs the same reusable 3-field `Op(label,arg,k)` for both declarations. VM and ASM
correctly agree because both dispatch through this single runtime; the lost metadata is
upstream, not an ASM-specific bug.

**Fix/verification:** both lowering paths now preserve declaration multiplicity;
plain typed effects use `effect.perform.oneshot`, while raw/Mira and `multi effect`
retain reusable `effect.perform`. The shared base continuation owns one atomic
claim without changing CoreIR or the three-field `Op`. VM and ASM now reject the
second resume with the same exact structured diagnostic and no suffix; their
multi-shot controls both return `3`. Direct runtime tests pass 4/4, real Swift
focused tests pass 3/3, native e2e passes, interop reports 10/10 measurable axes,
and affected conformance reports 6/6. The independently found Swift implicit-
`Return` fallback is fixed in `f21abfcc8`; residual-forwarding and stack-safety
were tracked and closed independently and do not reopen this original portable-
VM guard bug.

## spec-effect-example-platform-type — FIXED (2026-07-14, Codex)
<!-- status: fixed
     kind: apparatus
     lane: int
     area: runtime
     fixed-in: 96fc5adfb -->

**Status:** fixed in `96fc5adfb`; found during the architecture audit, observed
at `93962e590`.

**Symptom:** canonical `SPEC.md` showed ordinary ScalaScript code implementing an
effect clause with `scala.io.StdIn.readLine()`. Binding project architecture makes
any direct `scala.*` reference in a regular `scalascript` block a compile-time
error, so the language specification's own primary effect example contradicted the
platform-isolation contract.

**Reproduce:** `rg -n "scala\.io\.StdIn" SPEC.md` at `93962e590` finds the invalid
reference in §7.2.1.

**Fix/verification:** in `96fc5adfb` the example is a portable deterministic handler that
resumes with `"Ada"`; the changed spec contains no `scala.io.StdIn`, and the control
spec separately requires `std.*`, plugin, annotation, or backend-fence isolation for
platform work. This was documentation-only; no runtime behavior changed.

## v2-bridged-ui-emit-name-collision — `emit` resolves to the streams plugin, not the UI plugin, on `run --v2`
<!-- status: fixed
     kind: bug
     lane: int
     area: front
     fixed-in: unrecorded -->

**Status:** FIXED (2026-07-13, opus, Option 1 — mirror the native lane's UI-plugin
ownership of the internal name). `PluginBridge.loadAll`'s plugin loop has per-plugin
provenance (`backend.id`), so when bridging the frontend/UI plugin
(`id == "scalascript-frontend-interpreter"`) it now ALSO registers each annotated std/ui
symbol under its `__ssc_nativeui_v1.<name>` internal name — delegating to THAT iteration's
`nativeFn` (the UI emit, captured unambiguously before the streams `emit` overwrites the
plain global) and dropping `NativeUiSites.hiddenArgumentCount(name)` hidden args. Verified
end-to-end: `ssc-tools run --v2 examples/swift/appcore-nativeui.ssc` now runs the FRONTEND
emit (`[ui.emit] wrote swiftui app`, not the streams `emit(value)`) and produces a valid
SwiftUI package whose `ContentView.swift` reflects the tree (`@State message = "after"`,
`Text("message")` = `message.id`, `Text("\(message)")` = signalText). No regression: the
NATIVE lane uses `NativePluginHost` (not `PluginBridge.loadAll`); the BATCH lane does NOT
run `annotate` (so its plain-`emit` no-op stub is untouched); PluginBridge 33/33. (`--v2`
emits SwiftUI while `--native` emits HTML for this app — a separate frontend-framework-default
difference, not this bug.) Original root-cause below.

<details><summary>original root-cause (root-caused before the Option-1 fix)</summary>
open — root-caused (2026-07-13, opus); DEEP, architectural, NOT a shim. Found
running `examples/swift/appcore-nativeui.ssc` on `ssc-tools run --v2` (after fixing
`v2-bridged-ui-signal-id-field`): `emit(tree, outDir)` crashes. Two nested causes:
1. `NativeUiSites.annotate` (run by FrontendBridge on `run --v2`, `FrontendBridge.scala:864`)
   rewrites the source-only std/ui symbols `emit`/`serve` to `__ssc_nativeui_v1.emit`/`serve`
   with a hidden `NativeUiSourceRef` arg — but ONLY the NATIVE ui-plugin registers those
   names, and `run --v2` loads the v1-COMPAT bridge (`PluginBridge.loadAll`, not the native
   plugin set), so it crashes `unbound global: __ssc_nativeui_v1.emit`.
2. The deeper blocker: `emit` is a NAME COLLISION — BOTH the frontend/UI plugin
   (`FrontendIntrinsics:318`, `emit(tree: View, outDir): Unit`, error `"emit(tree, outDir)"`)
   AND the streams plugin (`StreamsIntrinsics:643`, error `"emit(value)"`) register
   `QualifiedName("emit")`. On the v1-compat bridge `V2PluginRegistry.registerGlobal` is
   last-write-wins, so the UI `emit` is OVERWRITTEN by the streams `emit` and is UNREACHABLE.
   A shim that delegates `__ssc_nativeui_v1.emit` → plain `emit` therefore hits the streams
   emit → `emit(value)` (verified). The native lane solves this exactly via `annotate` + the
   native ui-plugin owning `__ssc_nativeui_v1.emit`; the v1-compat bridge has no equivalent
   disambiguation. Proper fix (FrontendBridge/plugin-namespacing owner): expose the UI `emit`
   under an unambiguous handle on the bridge (e.g. register `__ssc_nativeui_v1.emit` from the
   FRONTEND plugin specifically, dropping the hidden source arg), OR skip the `emit`/`serve`
   annotate rewrite on the v1-compat lane and resolve the plain-`emit` collision by owner.
NOT BLOCKING: `appcore-nativeui.ssc` runs end-to-end on `--native` (its real lane); only the
`run --v2` v1-compat migration lane is affected.
</details>

## v2-bridged-ui-signal-id-field — std/ui `signal(...).id` crashes on the bridged VM lane
<!-- status: fixed
     kind: bug
     lane: int
     area: front
     fixed-in: unrecorded -->

**Status:** FIXED (2026-07-13, opus). `signal(name, default)` builds a
`scalascript.frontend.ReactiveSignal` whose `id` (its stable cross-backend name) is the
`name` arg. The native v2 ui-plugin (`NativeUiSignal`) exposed `.id`, but TWO other lanes
didn't and crashed `No method 'id' / no field 'id' on (Reactive)Signal`: (1) the v2
FrontendBridge lane — `PluginBridge.v1ToV2`'s `Value.Foreign(_, s: frontend.Signal)`
NamedMethodObj wrapper handled `apply/get/set/bind` but not `id`; added
`case "id" => rs.id` (guarded on `ReactiveSignal`; the bare `Signal[T]` trait has no id).
(2) the v1 interpreter — `DispatchRuntime.dispatchForeign`'s `Foreign("ReactiveSignal", sig)`
case handled `get`/`set` but not `id`; added `case name=="id" => StringV(sig.id)`. Verified:
`signal("myid","before").id` → `myid` on INT, v2 bridged (`run --v2`), and v2 native (`--native`).
Conformance `signal-id-bridged` [int, v2] green; PluginBridge 33/33. (Found running
`appcore-nativeui.ssc` on `run --v2`; the app then hits a separate `emit`-unbound gap on that
bridged lane — distinct, not this bug; the app runs end-to-end on `--native`.)
_Original report:_ found by opus running `examples/swift/appcore-nativeui.ssc`
on `ssc-tools run --v2` (the FrontendBridge VM lane). `val m = signal("name","before"); m.id`
crashes `__method__: no field 'id' on named-method-obj (None)` (`v2/src/Runtime.scala:2957`).
The v1 std/ui `signal` (`extern def signal[T](name, default): Signal[T]`, primitives.ssc)
is bridged into v2 as a `NamedMethodObj` that exposes `.set` (works) but whose
`getField("id")` returns `None` — even though the NATIVE v2 ui-plugin represents a signal as
`DataV("NativeUiSignal", [id, scope, kind, read, write, metadata])` WITH an `id` field +
`registerTaggedMethod("NativeUiSignal","id")` (`UiNativePlugin.scala:417/451`). So the app's
`textNode(message.id)` works on the native/Swift lane but crashes on the bridged VM lane —
a cross-lane inconsistency in the v1-std/ui→v2 PluginBridge NamedMethodObj wrapper (shared
bridge/kernel area, NOT Swift-specific). **CONFIRMED `--v2`-bridge-lane ONLY, not blocking:**
on the app's real lanes the whole thing works — `bin/ssc run --native examples/swift/appcore-nativeui.ssc`
emits `/tmp/ssc-nativeui/index.html` = `<!doctype html>\nmessageafter` (`message.id`→`"message"`,
`signalText(message)`→`"after"`), and the Swift lane (`run --v2 --target macos`) builds+launches.
Only the plain `run --v2` FrontendBridge lane (v1-compat bridged plugins, which the native ui
apps do NOT target) resolves `signal` to the id-less NamedMethodObj. LOW PRIORITY. Fix belongs
to the FrontendBridge/plugin-bridge owner: make the bridged std/ui signal's NamedMethodObj
resolve `id` (and the other declared `NativeUiSignal` fields), mirroring the native ui-plugin.

## v2-swift-at-global-cell-vivification — mutated signals crash "unbound global: @x"
<!-- status: fixed
     kind: bug
     lane: int
     area: front
     fixed-in: unrecorded -->

**Status:** FIXED (2026-07-13, opus); found running the native-UI example apps
end-to-end on the Swift lane (continues the busi-app frontier). A `val x = Signal(0)`
that is later mutated (`x += 1`) lowers to `cell.set(Global("@x"), …)` — but the `@x`
cell is never `global.reg`'d, so the Swift runtime's `.global` resolution hit
`fatalError("unbound global: @x")`. The general interpreter (`v2/src/Runtime.scala:686-689`)
VIVIFIES such a cell on first access; the Swift runtime didn't. Fix (mirrors the
interpreter exactly): `SwiftRuntime.scala` `.global(name)` now auto-creates a
`SscCell(.unit)` for a missing `@`-prefixed name instead of crashing; and
`SwiftBackend.scala` excludes `@`-prefixed names from the free-variable "unbound global"
set and from the `validateTerm` "unsupported global" check (they are vivified cells, not
external symbols). Verified under real swift-run: `refreshTick += 1` read-modify-writes the
auto-vivified cell like the interpreter. `v2SwiftBackend/test` green (2 new tests: validation
authorizes `@`-cells; real-swift auto-vivify).

## interp-tco-tail-call-in-match — NOT A BUG (investigated 2026-07-13)
<!-- status: fixed
     kind: apparatus
     lane: int
     area: codegen
     fixed-in: unrecorded -->

Claimed during m3d that "the interpreter does not TCO a tail self-call nested
inside a `match`". **Rigorously disproven** — the interpreter TCOs tail self-calls
correctly, including all of these at high depth with no stack overflow:

```scalascript
// 1,000,000-deep: Either return + accumulator + tail call inside a nested match
def enc(x: Int): Either[String, Int] = Right(x * 2)
def loop(n: Int, acc: List[Int]): Either[String, List[Int]] = n match
  case 0 => Right(acc)
  case _ => enc(n) match
    case Left(e) => Left(e)
    case Right(v) => loop(n - 1, v :: acc)   // TCO'd — loop(1000000, Nil) is fine
```

Also verified: a plain tail call inside `match` and a tail call inside an `if`
inside a `match` both TCO at 500k+. The overflow that triggered this claim was
plain **non-tail** recursion — `packLeavesLoop`'s `LeafPlan(...) :: packLeavesLoop(...)`
branches and the original non-accumulator `encodeRowCellsWithRowid`
(`recurse match { case Right => x :: rest }`) — resolved by `while` loops in
`write.ssc`. No interpreter change is needed. (The `feat(scljet): M3 multi-page`
commit message repeats the wrong claim; disregard it.)

## interp-if-then-no-else-after-while — a bare `if cond then stmt` before a return is skipped
<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     fixed-in: unrecorded -->

**Status:** FIXED (2026-07-13, opus). ROOT CAUSE: a statement-position control-flow term
(an `if`/`while`/`match` with a NESTED `var X = e` assignment) evaluated via `step`'s
`interp.eval` slow path writes only `interp.globals`, NOT the block's `local` map. Normally
the block reads a declared var from globals, but a preceding `while`'s `syncCallerEnv`
MATERIALISES each var into `local` (`EvalRuntime` ~4276), so a later read via `local`
(fastPrimitiveValue over localView) sees the STALE pre-if value — the `if`'s side effect is
silently lost (`if curCount>0 then leaves=leaves+1` left `leaves` at 0). Confirmed base-path
(fails with JIT+FastTier both off) and while-triggered (`if true then x=5; x` → 5 without a
while, 0 after one). FIX (`BlockRuntime.step`): after a NON-LAST slow-path statement, re-sync
`local` from globals for the block's declared vars (`resyncDeclLocals`, reusing the cached
`blockDeclNames` scan from the var-scope fix; globals is the source of truth for a declared
var during the block). Bounded + only on the already-slow monadic path; reusedView blocks
(no decls) are zero-overhead. Verified: `pack(List(1,2,3))` → 1 (was 0), `pack(Nil)` → 0,
`if-only` → 5, nested-if-in-while + multi-if-after → 110; new conformance
`if-then-no-else-after-while` [int]; full `backendInterpreter/test` 1827 pass, 0 regressions
(the 1 fail is the pre-existing js-effect-multishot-in-while-loop). Original report below.
<details><summary>original report</summary>
open (found 2026-07-13). Interpreter (`ssc-tools run --v1`) bug;
workaround in place.

**Symptom:** a single-branch `if cond then <stmt>` (no `else`) that appears as a
statement in a block — specifically after a `while` loop, followed by the block's
result expression — is not evaluated. Minimal repro (returns **0**, must be 1):

```scalascript
def pack(xs: List[Int]): Int =
  var remaining = xs
  var curCount = 0
  var leaves = 0
  while remaining.nonEmpty do
    curCount = curCount + 1
    remaining = remaining.tail
  if curCount > 0 then leaves = leaves + 1   // <-- skipped
  leaves
```

Confirmed it is the `if` statement, not the loop: replacing that line with
`leaves = curCount` yields 3 (so `curCount` IS 3 after the loop), but the
`if curCount > 0 then leaves = leaves + 1` form leaves `leaves` at 0. A multi-line
`if cond then\n  stmt` fails the same way. It bit SclJet's `packLeaves`, silently
dropping the last B-tree leaf (last rows missing while `integrity_check` still
passed).

**Workaround:** use an `if/else` **expression** — `leaves = if cond then f(leaves)
else leaves`. In `runtime/std/scljet/write.ssc` `packLeaves`.

**Likely cause:** parser/interpreter treats the trailing result expression as the
implicit `else`, or drops the `then` side of a no-`else` `if` used in statement
position. Needs a fix in the interpreter's block/if lowering.
</details>

## interp-var-scope-leak-across-calls — a callee's `var` clobbers a caller's live `var` of the same name
<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     fixed-in: unrecorded -->

**Status:** FIXED (2026-07-13, opus). `var` decl/assign dual-write to the module-global
`interp.globals` (keyed by name, load-bearing so a `while` body's fresh env sees mutations)
had no per-call scoping, so a callee's `var X` clobbered a caller's live `var X`. Fix (block-
scoped save/restore): `collectVarDeclNames` (cached per block-AST by `stats` identity in
`blockVarNamesCache`) finds a block's globals-writing `Defn.Var` names; on block eval it
snapshots ONLY the outer bindings the block SHADOWS (nothing shadowed → zero overhead, the
common case) and restores them on block EXIT via a `FlatMap` continuation (fires on
completion, not on effect suspension) + a `Throwable` restore-and-rethrow path. The loop
counter lives in the enclosing block and is restored only at that block's exit (after the
loop), so the JIT / fast-while paths read/write globals exactly as before during the loop;
each recursion level snapshots its own shadow. Verified: repro `mkpages(84).length` → 84
(was 1); recursion `sumfac(5)` → 153; new `VarScopeAcrossCallsTest` 8/8; full
`backendInterpreter/test` **1827 pass, 0 regressions** (the 1 fail is the pre-existing
js-effect-multishot-in-while-loop above, unrelated). Original report below.
<details><summary>original report</summary>
open (found 2026-07-13). Interpreter (`ssc-tools run --v1`) correctness
bug; workaround = use distinct `var` names across the call hierarchy.

**Symptom:** when a function `F` has a `var X` live (e.g. a `while` loop counter)
and, inside that scope, calls a function `G` that also declares `var X`, `G`'s
final value of `X` **overwrites** `F`'s `X` — as if `var`s share one environment
keyed by name instead of per-call frames. Minimal repro:

```scalascript
def page(): List[Int] =
  var acc: List[Int] = Nil
  var i = 0
  while i < 512 do
    acc = 0 :: acc
    i = i + 1
  acc

def mkpages(n: Int): List[List[Int]] =
  var acc: List[List[Int]] = Nil
  var i = 0                       // <-- same name as page()'s `i`
  while i < n do
    acc = page() :: acc           // page() sets i = 512; that leaks back here
    i = i + 1                     // 513 ≥ n → loop exits after ONE iteration
  acc

// mkpages(84).length is 1 (must be 84). Rename page()'s vars to pi/pacc → 84.
```

Verified: identical code with distinct var names (`pi`/`pacc` vs `mi`/`macc`)
gives the correct 84; the only change is the name collision. The clobber can
cause either early loop exit (as above) or a wrong result, silently.

**Workaround:** give `var`s unique (e.g. function-prefixed) names when a callee
might reuse them — see the `cf`/`bl`/`il`/`bid`/`vb`/`bc` prefixes in
`scljet/bytes.ssc` and `scljet/write.ssc`. It did NOT bite the existing SclJet
builders only because they happened to use distinct names down each call chain.

**Root cause (traced 2026-07-13):** `BlockRuntime.scala` writes every `var`
declaration and assignment to **`interp.globals` keyed by name**, in addition to
the local frame:
- `Defn.Var` — line ~230: `local(n.value) = v; interp.globals(n.value) = v`
- `Term.Assign` — line ~242: `local(x) = v; interp.globals(x) = v`
- compound assign (`x += n`) — same dual write ~256-266

This is deliberate (comment at ~233): a `while` loop evaluates its body in a
`freshEnv`, so body mutations are propagated OUT to the enclosing loop via the
shared `interp.globals` map rather than a frame reference. Consequence: a callee's
`var i` writes `interp.globals("i")`, and after the call the caller's loop re-reads
`i` from that clobbered global. Confirmed tier-independent — same wrong result on
the bytecode VM, with `SSC_FASTTIER=off`, and with `SSC_FASTTIER=off
SSC_JIT_BYTECODE=off` (pure tree-walk) — so it is base behavior, not a fast-path
optimization.

**Fix direction (architectural, broad blast radius — verify across the whole
interpreter suite + all backends before landing):** make loop-visible mutable
state per-frame instead of routing it through `interp.globals` by name — e.g. the
`while`-loop body shares its parent frame's `MutableEnvView` by reference (so
mutations are seen without the global write), or a user-function invocation
save/restores the `interp.globals` entries that its local `var` names shadow.
Every fast path that reads/writes loop vars via `interp.globals(body.names(k))`
(EvalRuntime `tryLong*While*`, FastTier) must move in lockstep. Until then the
workaround stands: unique `var` names down each call chain.
</details>

## scljet-freelist-recursive-stack-overflow — valid large freelist crashes the interpreter
<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     fixed-in: 7399fad95
     confirmed: no -->

**Status:** fixed (2026-07-12, `7399fad95`), awaiting Sergiy confirmation;
found by codex in the pinned SclJet M2d SQLite 3.53.3 corpus.

- **Real-harness repro:** regenerate/consume
  `tests/fixtures/scljet/m2/valid/freelist.db` (512-byte pages, 183 freelist
  pages, SHA recorded in `manifest.tsv`, reference `PRAGMA integrity_check =
  ok`) and run `bin/ssc-tools run --v1
  tests/tools/scljet-corpus-dump.ssc`. The first eleven fixture families read;
  opening `freelist.db` ends in a JVM `StackOverflowError`, not a structured
  `SqliteError`.
- **Root cause:** `validateFreelistLoop` and `validateFreelistLeaves` recursively
  retain one interpreter call frame per trunk/leaf. ScalaScript v1 does not
  guarantee tail-call elimination, contradicting the M2 spec's explicit
  iterative-traversal invariant.
- **Plan/done-when:** replace both recursive walks with a bounded mutable-local
  `while` state machine that preserves immutable pager values, duplicate/cycle
  checks, pointer-map validation and exact count. The 183-page fixture must
  open/dump without platform exception on the real assembled runner; corrupt
  cycles/duplicates must still return localized `SqliteError`.
- **Fix/verification:** both leaf membership and trunk/leaf validation now use
  explicit bounded `while` state machines, preserving duplicate/cycle and
  pointer-map checks. The real assembled corpus runner reads the 183-page
  freelist as part of the exact 23-file/619-line oracle; all five named corrupt
  files and 32 deterministic mutations remain structured and conformance is
  6/6.

## scljet-readonly-close-imported-selector — facade close selects the wrong pager handle
<!-- status: fixed
     kind: bug
     lane: int
     area: front
     fixed-in: c281958bd
     confirmed: no -->

**Status:** fixed (2026-07-12, `c281958bd`), awaiting Sergiy confirmation;
found by codex during the SclJet M2c assembled JVM VFS example.

- **Real-harness repro:** run `bin/ssc-tools run --v1
  examples/scljet-readonly.ssc` after `scripts/sbtc "installBin"`. The real JVM
  adapter opens handle 2, schema/table reads succeed, and direct
  `closeReadonlyPager(database.pager)` succeeds, but
  `closeReadonly(database)` returns `SqliteIo: unlock: unknown or closed
  handle` (`bad-handle`).
- **Root cause:** the cross-module facade body selected `database.pager` from
  an imported case class and then forwarded it. In the v1 interpreter's known
  receiver-blind imported-field environment that selector can bind the wrong
  registered `pager` layout, even though constructor-pattern matching the same
  value yields the correct `ReadonlyPager`/`JvmSqliteFile(2)`.
- **Fix/result:** `schema.ssc`, where `ReadonlyDatabase` is defined, now owns
  constructor-pattern access/replacement helpers; every facade transition uses
  those helpers and never selects/copies the imported product directly. The
  multi-file real-plugin smoke opens a pinned SQLite image, reads schema/row,
  requires public `closeReadonly` to return `Right(())`, and verifies deletion.

## portable-codepoint-string-construction — v1 lacks Int.toChar, v2 renders Char numerically
<!-- status: fixed
     kind: feature
     lane: int
     area: front
     fixed-in: unrecorded -->

**Status:** FIXED for INT/JVM/JS/v2-VM/v2-native (2026-07-12, opus); the broader
portable UTF-16 text API from checked code points (Done-when) remains a design item.
INT/JVM/JS `Int.toChar` fixed earlier (interp Int dispatch + JS number/bigint dispatch).
v2 lane fixed now: the v2 VM has no Char value type, so `case (IntV(n), "toChar", Nil)`
in `v2/src/Runtime.scala` returned `IntV(n & 0xffff)` and `65.toChar.toString` rendered
"65". Changed it to return a single-code-point `StrV` — the convention the VM already uses
for chars (`toCharArray`, `sfromCodes`). Verified via a direct `Prims.resolve("__method__")`
probe: `toChar(65)→"A"`, `toChar(8364)→"€"`, `.toString` chains render the character.
Known edge: `65.toChar.toInt` (unusual round-trip) now parses the 1-char string rather
than returning 65 — a real `CharV` type would be needed for full Scala parity (separate
larger change, tracked under the Done-when text API).

- **Real-harness repro:** in an `.ssc` module evaluate `val a = 65.toChar; val
  b = 0x20ac.toChar; println(a.toString + b.toString)`. `ssc-tools run --v1`
  fails with `No method 'toChar' on IntV(65)`; `ssc run` prints `658364`
  instead of `A€`. Mapping `List(65,66,67).map(_.toChar).mkString` similarly
  prints `656667` on v2.
- **Impact:** pure byte decoders can compute exact Unicode code points but
  cannot portably construct a `String` without a host/plugin decoder. SclJet
  M2 therefore preserves `DecodedText(encoded, encoding, codePoints,
  wellFormed)` and does not fake `SqlText`.
- **Done-when:** a separately specified language/std text API constructs UTF-16
  strings from checked code points identically on interpreter, VM/ASM, JS and
  native targets, with malformed-sequence policy owned by the caller.

## scljet-vfs-plugin-not-packaged — installBin registry/package list diverged
<!-- status: fixed
     kind: apparatus
     lane: int
     area: conformance
     fixed-in: 2a594b870
     confirmed: no -->

**Status:** fixed (2026-07-12, `2a594b870`), awaiting Sergiy confirmation;
found by codex in the real assembled-distribution gate for SclJet M1.

- **Real-harness repro:** add `scljet-vfs` to `allPlugins` and run
  `scripts/sbtc "installBin"`. Compilation and std-source staging complete, then
  the task fails with `installBin pluginPkgs missing std plugin(s): scljet-vfs`.
- **Root cause:** `installBin` intentionally validates its explicit task-macro
  `pluginPkgsBySpec` list against `allPlugins`; the new project was registered in
  `allPlugins` but omitted from that explicit package-task list.
- **Fix/result:** the explicit package task is registered; `installBin` stages
  27 essential plugins including `scljet-vfs-plugin.sscpkg`, the assembled JVM
  example autoloads it, and SclJet conformance is 3/3.

## v21-scljet-jvm-vfs-unclassified-lane — new explicit tools example became both-fail
<!-- status: fixed
     kind: apparatus
     lane: int
     area: conformance
     fixed-in: 5cd86bf1c
     confirmed: no -->

**Status:** fixed (2026-07-12, `5cd86bf1c`), awaiting Sergiy confirmation;
found by codex when the strict zero-gap freeze caught the concurrently landed
`examples/scljet-jvm-vfs.ssc` row.

- **Real-harness repro:** run strict `scripts/bc-parity-sweep` after the SclJet
  JVM VFS example lands. The document declares `backends: [int]` and a
  `ssc-tools run --v1` shebang, but is absent from the explicit-lane manifest;
  plain standard VM and ASM both fail, producing one new `both-fail` row.
- **Root cause:** the example and its v1 JVM host plugin landed after the frozen
  13-row explicit-lane census, without registering their compiler/tools-owned
  execution contract in the exact v2.1 manifest.
- **Done-when:** an exact tools regression executes the real JVM VFS plugin in
  a temporary filesystem with deterministic output, the manifest accounts for
  the row as `target-lane`, and exhaustive ordinary/negative reports return to
  zero `both-fail`, mismatch, one-sided, and blockers.
- **Fix/result:** `v21-explicit-scljet-jvm-vfs-tools-smoke.sh` executes the real
  v1 host plugin with exact output and cleanup; the row is an exact target lane.
  The final 200-row ordinary and negative reports contain no parity gaps.

## v21-empty-runtime-taxonomy-total — zero-row summary printed a blank total
<!-- status: fixed
     kind: apparatus
     lane: int
     area: front
     fixed-in: 0efc3dd75
     confirmed: no -->

**Status:** fixed (2026-07-12, `0efc3dd75`), awaiting Sergiy confirmation;
found by codex in the zero-`both-fail` negative release gate.

- **Real-harness repro:** generate a runtime taxonomy from a parity report with
  no `both-fail` rows and the empty production manifest. All category counts
  print `0`, but the final summary line prints `total` with an empty value.
- **Root cause:** awk's never-incremented `total` scalar was concatenated
  directly instead of being numerically normalized.
- **Fix/result:** the summary now prints `total + 0`; the exact zero-row
  runtime taxonomy freeze and its synthetic smoke remain green.

## v2-frontend-tkv2-pwa-fast-provider-missing — unit classpath contradicts assembled default
<!-- status: fixed
     kind: apparatus
     lane: int
     area: front
     fixed-in: 3a87eb29c
     confirmed: no -->

**Status:** fixed (2026-07-12, `3a87eb29c`), awaiting Sergiy confirmation;
found by codex during the Swift NativeUi release gate and reported in the
`scalascript` Rozum room.

- **Real-harness repro:** both full `v2FrontendBridge/test` and isolated
  `V2ConformanceTest -- -z tkv2-pwa` produce all eight correct PWA assertions
  but print `backend=jdk`; the checked corpus expects the shipped
  `backend=fast` default. The full suite is 198/199 for this reason alone.
- **Root cause:** `v2FrontendBridge` has `backendInterpreterServer` through the
  plugin bridge but no test dependency on `runtimeServerJvmFast`, so
  `ServiceLoader` cannot discover the provider that the assembled CLI includes.
  The prior expected-file-only fix `b060951ce` verified the assembled corpus but
  did not align this unit-test classpath.
- **Fix/done-when:** add `runtimeServerJvmFast % Test` only to
  `v2FrontendBridge`, retain the exact fast banner, and pass isolated plus full
  bridge suites. This is test wiring, not a production backend selection change.
- **Fix/result:** the test-only dependency now exposes the shipped fast provider;
  isolated tkv2 PWA and the pre-SclJet full bridge suite are green.

## v2-httpclient-curried-extern-unbound — curried top-level `extern def` doesn't bind as a global on `ssc run`
<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     fixed-in: unrecorded -->
**Status:** FIXED (2026-07-12, opus). The v2 VM + native lanes were already fixed by the v2.1
native-curried-closures work (8df3e63a6/d85a1e903); the remaining failure was the **v1 interpreter**
lane. Root cause: `httpClient(base){ block }` is not a plugin-native intrinsic — it's an eval-time
special form matched by AST shape (`EvalRuntime.reservedApplyHeads`). StatRuntime's extern-def branch
deliberately creates no binding (it relies on the intrinsic table for the global), so `httpClient`
never entered `globals` → never entered `exportedGlobals` → `import [httpClient](http.ssc)` threw
"'httpClient' not found" at import-resolution time (the *call* always worked, the import validation
didn't). Fix: StatRuntime now registers a placeholder `NativeFnV` global when an extern name is a
reserved block-form head and no plugin global exists (widened `reservedApplyHeads` to
`private[interpreter]`); the placeholder is only ever consulted by import validation, never the call.
Verified `run --v1` on the repro (now prints ok) + conformance `curried-extern-import` [INT].
_Original report:_ found by claude-code (rozum-ucc-test) while porting rozum's UCC
acceptance e2e test to native `.ssc`. Not blocking (single-param http externs work; the test uses those).
- **Real-harness repro:** `ssc run` a `.ssc` importing `[httpClient](std/http.ssc)` that calls
  `httpClient("http://x"){ … }` → `RuntimeException: unbound global: httpClient`. Minimal:
  `[httpClient, httpRetry, httpTimeout](std/http.ssc)` then a `scalascript` fence with
  `httpClient("http://example.invalid") { httpTimeout(2000); httpRetry(2, 500) }`. (`ssc run` only;
  `run-jvm` has a separate std-module JVM-lowering issue — Any-vs-Int in the generated prelude.)
- **Scope:** single-param-list http externs (`httpGet`/`httpPost`/`httpTimeout`/`httpRetry`) bind + work
  fine on `ssc run`. ONLY the CURRIED `httpClient(baseUrl)(block)` from `std/http.ssc` stays unbound.
- **Root cause (partial):** `JvmBackend` emits its "unbound global" stub (`JvmBackend.scala` ~1224) for
  `httpClient` ⇒ it is not among the program's DEFINED globals. A referenced *curried* plugin-native is
  not injected as a defined global the way single-param-list ones are.
- **Ruled out (two failed fix attempts, released claims `887913740` + `5225aef97`):**
  1. `PluginBridge.registerGlobal` for httpClient/Timeout/Retry — NO-OP: JvmBackend decides
     bound-vs-stub from the frontend's defined globals, not runtime plugin registration.
  2. Recording top-level `extern def m(a)(b)` into `curriedExternMethods` in
     `FrontendBridge.stripExternDecls` (mirroring the `extern class/trait` scan @899) — compiles, but
     httpClient STILL unbound ⇒ the curried-merge / `curriedExternMethods` is NOT the cause.
- **Open question (where to fix):** WHERE does a referenced single-param plugin-native (`httpGet`) become
  a DEFINED global so JvmBackend binds it instead of stubbing? `httpGet` is not injected in
  `FrontendBridge`, and the injection wasn't found in `JvmBackend` either. Whatever that mechanism is, a
  curried extern is skipped by it — start there.
- **Done-when:** `ssc run` binds a curried top-level `extern def m(a)(b)` (e.g. `httpClient`) to its
  plugin native so `httpClient(base){block}` runs the block; a conformance/probe covers it.

## http-handler-concurrent-interpreter-entry — accepted durable fact can disappear
<!-- status: fixed
     kind: bug
     lane: int
     area: front
     fixed-in: 1f7ea78d7 -->

**Status:** fixed (2026-07-11); reported by busi's personal-Vault canonical
browser E2E. Fix commit: `1f7ea78d7`.

- **Real-harness repro:** boot busi's assembled `scripts/ssc --v2` hub against
  an empty scratch repo, pair `/app`, and advance the eleven Vault simulator
  actions while its reactive dashboard polls `GET /api/vault`. All POSTs return
  200 with the exact action, but the final recovery-rotation fact can vanish;
  sequential live HTTP and in-process runs are green.
- **Expected:** one interpreter excludes mutations from every other callback,
  while safe reads share a fair section and the network backend continues to
  parse/write concurrently. Accepted mutations are visible to later requests
  and survive restart without starving behind a reactive GET burst.
- **Root cause:** `WebServer` creates and documents a shared single-thread
  executor, and WS callbacks use it, but `InterpreterHttpHandler.onHttpRequest`
  calls `Interpreter.invoke` directly on whichever JDK/Jetty/Netty request
  thread entered the SPI. This violates the interpreter thread-safety comment
  and races application-level read-modify-write transactions.
- **Root cause/fix:** the v1 `InterpreterHttpHandler` entered one mutable
  interpreter directly from concurrent request threads. A weak per-interpreter
  fair reentrant read/write gate now wraps safe reads, mutations, middleware,
  streams and WebSocket callbacks while leaving I/O and distinct interpreters
  concurrent.
- **Verification:** focused concurrency 8/8; interpreter-server 58/58;
  `rest-validate` INT/JS/JVM; assembled busi live Vault/restart; canonical busi
  Chromium 6/6 including offline drain, Housing and eleven Vault transitions.
- **Rejected prototype:** an exclusive per-interpreter lock passed 54 module
  tests and the focused live Vault check, but the full SPA's eager GET fan-out
  starved mutations for more than 10 seconds. A standalone concurrent POST+GET
  completed and persisted, so the remaining symptom was queue starvation, not
  a deadlock. The gate must preserve concurrent safe reads.

## v21-imports-tuple2-collection-match — imported collection pipeline rejects `Tuple2/2`
<!-- status: fixed
     kind: bug
     lane: int
     area: front
     fixed-in: 7a4cc0c00 -->

**Status:** FIXED (verified 2026-07-12, opus) — already resolved on main by
`579679058` "fix(v2.1): match imported two-element tuples" (+ `7a4cc0c00` rendering),
which landed after this entry was filed. Root cause was a tag mismatch: the native
front tags source 2-tuples / `a -> b` arrows `DataV("Pair", …)` while runtime
collection ops (`zip`/`groupBy`/`->`/Map factory) build `DataV("Tuple2", …)`, and the
VM `Match` does an exact `(tag, arity)` lookup with no normalization → a `Tuple2/2`
scrutinee found no `Pair/2` arm. The fix expands a source tuple pattern into BOTH
`Pair/2` and `Tuple2/2` arms (`ssc1-lower.ssc0:2189`; `_sel_` accessors got the same
dual treatment). VERIFIED: `examples/imports.ssc` and `examples/extensions.ssc` are
now byte-identical across `ssc-tools run --v1` / `ssc-standard run --native` /
`--native --bytecode`.

- **Real-harness repro (historical):** `bin/ssc-standard run --native examples/imports.ssc`
  prints the complete native math section and `distance (0,0)-(3,4) = 5`, then
  VM/ASM reach the classified collection pipeline and fail with `match: no arm
  for Tuple2/2`. `examples/extensions.ssc` now reaches the same boundary after
  printing through `min = 1, max = 9`.
- **Expected:** the imported list/tuple pipeline binds portable tuple pairs and
  completes identically on VM/ASM.
- **Plan/done-when:** isolate the post-math tuple pipeline in a multi-file
  fixture, repair constructor/tuple pattern ownership without a host fallback,
  make `imports.ssc` and `extensions.ssc` identical, and retire their
  language-runtime taxonomy rows only after all release gates pass.

## v2-nativeui-component-scope-compat — new scope extern is unbound in legacy INT/JS lanes
<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     fixed-in: 1f3ca3962
     gate: tests/conformance/run.sh -->

**Status:** fixed (2026-07-11, `1f3ca3962`); found by codex while verifying the atomic
NativeUi ABI-v1 migration, announced to `@scalascript` in Rozum.

- **Real-harness repro:** run `tests/conformance/run.sh --only 'tkv2-*'
  --no-memo` after `std/ui/component.ssc` starts wrapping component bodies in
  the new `componentScope(scopeId, bodyThunk)` extern. Nine cases pass, while
  `tkv2-busi-home`, `tkv2-component`, and `tkv2-forms` fail: INT reports
  `'componentScope' not found in primitives.ssc` and JS reports `not callable:
  ()`.
- **Expected:** the new source-level helper must preserve all existing toolkit
  lanes. V2 NativeUi owns scoped signal identity; backends without that state
  model must execute the body exactly once and return its value.
- **Root cause:** the public extern was added before compatibility
  implementations existed. After those adapters were assembled, JS became
  green but INT exposed a deeper module boundary: `SectionRuntime` rebinds an
  explicitly imported plugin native to the parent interpreter, but leaves the
  same native child-owned when it enters exported functions through transitive
  `childCtx` closure enrichment. `componentScope` therefore invoked the user
  thunk in the primitives child interpreter, where caller module globals such
  as `ctxName`, `ctxSignal`, and `form` do not exist.
- **Planned fix:** retain identity adapters
  `componentScope(scopeId, thunk) = thunk()` in the owning standard plugin and
  JS/JVM/Rust runtimes; additionally apply the existing parent
  `rebindPluginNative` rule to plugin-native entries placed into transitive
  `childCtx`. Do not broaden lambda capture: that experiment made the component
  case pass but broke optimized forms/fold parameter semantics, while the same
  program stayed green with fast/JIT disabled. The existing multi-file toolkit
  imports are the faithful cross-module regression. Keep the v2 NativeUi
  plugin's scoped semantics unchanged.
- **Fix/verified:** exact-once identity adapters landed in all legacy runtimes;
  only child-provenance/identity-proven natives are rebound to the caller. The
  assembled multi-file toolkit corpus is 12/12 and the Rust adapter compiles.
- **Done-when:** fresh toolkit conformance is 12/12 across declared lanes,
  focused plugin/codegen tests cover the thunk contract, and the landed SHA is
  reported in Rozum. Keep `fixed` until Sergiy confirms.

## v2-xslt-transform-empty-output — `fixed` (2026-07-09)
<!-- status: fixed
     kind: bug
     lane: int
     area: front
     fixed-in: unrecorded -->

- **Found by:** codex, during the v2 production unmasking loop.
- **Repro:** from a clean worktree, stage the CLI with
  `scripts/sbtc "installBin"`, then run
  `bin/ssc run --v2 examples/xslt-transform.ssc`.
- **Observed failure:** the command exits 0 with empty stdout. The same staged
  CLI with `--v1` exits 1 on `Unknown interpolator 'xml'`, so v1 is not a valid
  output oracle for this example.
- **Expected:** v2 should execute the documented JVM/interpreter markup example:
  identity transform, element rename, HTML transform with `EUR` parameter
  substitution, and malformed-stylesheet error handling.
- **Impact:** a documented example in `README.md` and `docs/user-guide.md`
  silently does nothing on the v2 production lane.
- **Root cause:** the v2 bridge did not register the markup-core surface used by
  the example. `FrontendBridge` treated `xml"""..."""` like generic string
  interpolation, and `PluginBridge` had no `MarkupCodec`, `PureMarkupCodec`,
  `SerializeOpts`, `TransformError.message`, or `Right`/`Left` result bridge for
  XSLT calls.
- **Fix:** `b668359f9` adds the v2 markup/XSLT bridge: `v2PluginBridge` depends
  on `markupCore`/`backendInterpreter`, `FrontendBridge` pre-registers
  `SerializeOpts`/`TransformError` and lowers `xml` interpolation through
  XML-escaping bridge helpers, and `PluginBridge` registers JvmMarkupCodec-backed
  method objects for parse/serialize/transform plus a markup `Show` renderer.
- **Verified:** `scripts/sbtc 'v2FrontendBridge/testOnly
  ssc.bridge.FrontendBridgeTest'` (39/39); `scripts/sbtc 'installBin'`;
  `bin/ssc run --v2 examples/xslt-transform.ssc` prints identity `<catalog>`,
  rename `<report>/<item>`, HTML `EUR`, and expected stylesheet error handling;
  `tests/conformance/run.sh --only 'v2-*,content*' --no-memo` (7/7);
  full `./v2/conformance/check.sh`; `git diff --check`.
- **Status:** fixed; waiting for human confirmation before `done`.

## v2-graph-neo4j-foreign-parity — `fixed` (2026-07-09)
<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     fixed-in: unrecorded -->

- **Found by:** codex, during the post-split production output-parity refresh.
- **Repro:** after `scripts/sbtc "installBin"`, run
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/graph-neo4j-storage.ssc`
  or directly compare `bin/ssc run --v1 examples/graph-neo4j-storage.ssc`
  against `bin/ssc run --v2 examples/graph-neo4j-storage.ssc`.
- **Observed failure:** v1 prints
  `StoredEdge(knows, carol, bob-knows-carol, bob, Knows(bob, carol, 2021))`
  while v2 prints `<foreign>`.
- **Impact:** default v2 output-parity mismatch in the production gate. The
  graph plugin's edge write result is usable enough to run, but direct display
  of a plugin-created instance leaks the v2 opaque foreign renderer.
- **Initial hypothesis:** `Graph.putEdge` returns
  `PluginValue.instance("StoredEdge", ...)`, i.e. a v1 `InstanceV` with named
  fields. `PluginBridge.v1ToV2` keeps named-field instances as
  `ForeignV(NamedMethodObj)` to preserve field access when v2 has no registered
  field order, but the bridged print path `v1Show` falls through to
  `Show.show` for that foreign wrapper, producing `<foreign>` instead of the
  underlying v1 `Value.show`.
- **Root cause:** the hypothesis was correct, with one extra display path:
  registered `println` uses the bridged `v1Show`, but last-expression
  `__autoPrint__` goes through the v2 core `Show.show`. Both paths treated
  `ForeignV(NamedMethodObj)` as opaque even when `underlying` was a v1
  interpreter `Value`, so plugin-created named instances printed as `<foreign>`.
- **Fix:** `c39afa9ba` renders bridged named v1 values with v1
  `Value.show` while preserving `NamedMethodObj` field access. The v2 core
  `Show` now sends `NamedMethodObj.underlying` through the existing
  `foreignRenderer` callback, so auto-print and ordinary print agree.
- **Gates:** `scripts/sbtc "v2PluginBridge/testOnly ssc.bridge.PluginBridgeTest"`
  passed 23/23; `scripts/sbtc "installBin"` passed;
  `tests/conformance/run.sh --only 'graph-edge-display' --no-memo` passed INT;
  targeted `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/graph-neo4j-storage.ssc`
  passed 1/1; full parity is now
  **65/98 identical · 10 mismatch · 0 v2-error · 23 v1-only** across 195
  examples.

## root-test-stable-spi-os-plugin-import — `fixed` (2026-07-08)
<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     gate: tests/conformance/run.sh
     fixed-in: unrecorded -->

- **Found by:** codex, during the same full root `scripts/sbtc "test"` gate.
- **Repro observed in root gate:** `StableSpiEnforcementTest` failed
  `value-surface plugins depend only on scalascript-plugin-api` with
  `os-plugin/scala/scalascript/compiler/plugin/os/OsIntrinsics.scala: import
  scalascript.interpreter.InterpretError`.
- **Impact:** stable plugin API enforcement is red; value-surface plugins are not
  fully isolated from interpreter internals.
- **Root cause:** OS plugin had already migrated to `PluginNative`/`PluginValue`,
  but one fallback still imported and threw interpreter `InterpretError` directly.
- **Fix:** `c3e277723` replaces the direct interpreter error with
  `PluginError.raise("exit(code: Int)")`, keeping the value-surface plugin on
  `scalascript-plugin-api`; the existing NUL separator literal was also normalized
  to `"\u0000"` so future diffs stay text-friendly.
- **Verified:** `scripts/sbtc "backendInterpreterPluginTests/testOnly scalascript.StableSpiEnforcementTest"`
  2/2 green; `scripts/sbtc "osPlugin/testOnly scalascript.compiler.plugin.os.OsPluginTest"`
  14/14 green; `tests/conformance/run.sh --only 'std-process-import' --no-memo`
  1/1 green.

## root-test-sealed-extension-option-dispatch — `fixed` (2026-07-08)
<!-- status: fixed
     kind: bug
     lane: int
     area: codegen
     fixed-in: 1e503de04 -->

- **Found by:** codex, during `green-main-full-sbt-test-gating` root
  `scripts/sbtc "test"` after the bytecode split-runtime and Scala.js npm
  dependency fixes.
- **Repro observed in root gate:** `SealedExtensionDispatchTest` failed
  `Some dispatches extension on Option`: expected stdout `42\n99`, actual
  `Some(42)\n99` at `SealedExtensionDispatchTest.scala:81`.
- **Targeted repro to run before fixing:** `scripts/sbtc "backendInterpreter/testOnly
  scalascript.SealedExtensionDispatchTest"`.
- **Initial hypothesis:** the `Some` receiver path dispatches an extension found on
  the sealed parent `Option`, but passes the case instance instead of the expected
  unwrapped payload for this extension shape. Inspect the test before changing
  dispatch: `None` still prints `99`, so the bug may be specific to case payload
  extraction for `Some`.
- **Status:** fixed in `1e503de04`. Actual root cause was built-in dispatch
  applicability, not payload extraction: interpreter `Option.orElse` accepted any
  single argument, so `Some(42).orElse(0)` returned the built-in receiver `Some(42)`
  before the user extension `def orElse(default: A): A` could run. Built-in
  `Option.orElse` now handles only Option-valued alternatives; non-Option
  defaults fall through to extension dispatch.
- **Verified:** `scripts/sbtc "backendInterpreter/testOnly
  scalascript.SealedExtensionDispatchTest"` (**4/4 green**);
  `scripts/sbtc "backendInterpreter/testOnly scalascript.SealedExtensionDispatchTest
  scalascript.InterpreterTest -- -z \"built-in members take precedence\" -z
  \"option orElse\""` (filtered invariant slice green); and
  `tests/conformance/run.sh --only
  'option,optional,typeclass-extension,std-functor-applicative-monad,std-monaderror'
  --no-memo` (**5/5 green** on INT/JS/JVM).

## conformance-int-std-semigroup-monoid — `fixed` (2026-07-08)
<!-- status: fixed
     kind: bug
     lane: int
     area: cli
     fixed-in: e571fd3ae
     gate: tests/conformance/run.sh -->

- **Found by:** codex, during full `green-main-conformance-gating` after the
  `.scjvm` cache invalidation fix.
- **Repro:** after `scripts/sbtc "installBin"`, run
  `tests/conformance/run.sh --only 'std-semigroup-monoid' --no-memo` or direct
  `bin/ssc run --v1 tests/conformance/std-semigroup-monoid.ssc`.
- **Observed:** full conformance reports `std-semigroup-monoid` failing only on
  INT: expected lines 4-6 are `Some(24)`, `42`, and `foo`, but INT prints fewer
  lines (`<missing>` for those entries). JS and JVM pass.
- **Status:** fixed in `e571fd3ae`. Root cause was INT given registration:
  `given intSum: Monoid[Int]` was registered only as `Monoid[Int]`, while
  `combineAllOption[A: Semigroup]` needs `Semigroup[Int]`. Scala/JS/JVM accept
  this because `Monoid extends Semigroup`; the interpreter did not expose that
  parent typeclass key.
- **Fix:** concrete and parametric `given` registration now follows the
  interpreter's `parentTypes` chain and registers parent typeclass aliases such
  as `Semigroup[Int]` for `Monoid[Int]`. Exact concrete keys still own ambiguity
  tracking; aliases fill only missing parent keys.
- **Verified:** direct `bin/ssc run --v1 tests/conformance/std-semigroup-monoid.ssc`
  prints all six expected lines; `scripts/sbtc "backendInterpreter/testOnly scalascript.FinalTaglessConformanceTest scalascript.GivenUsingTest"`
  (**17/17 green**); and
  `tests/conformance/run.sh --only 'std-semigroup-monoid' --no-memo`
  (**1/1 green** across INT/JS/JVM).

## conformance-int-sql-block-scope — `fixed` (2026-07-08)
<!-- status: fixed
     kind: bug
     lane: int
     area: front
     fixed-in: c31389b25
     gate: tests/conformance/sql-basic.ssc -->

- **Found by:** codex, during full `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `bin/ssc run --v1 tests/conformance/sql-basic.ssc`.
- **Observed:** SQL block interpolation fails with
  `[line 1, col 1] Undefined: newId` even though the preceding Scala block defines
  `val newId = 1L`; `sql-basic` and `sql-transaction` therefore produce missing
  stdout. JS/JVM are skipped by backend metadata.
- **Status:** fixed in `c31389b25`; root cause was the CLI backend path
  normalizing parseable fenced `scala` blocks to `ir.Content.EmbeddedBlock` and
  denormalizing them back to AST code blocks without a parsed tree. The interpreter
  therefore skipped those blocks, so globals such as `newId` / `personId` never
  existed when SQL bind expressions were evaluated. `Denormalize` now re-parses
  parseable embedded blocks (`scala`, `ssc`, `scalascript`) while keeping opaque
  foreign blocks tree-less.
- **Verified:** `scripts/sbtc "sqlPlugin/testOnly scalascript.compiler.plugin.sql.SqlPluginInterpreterTest"`;
  `scripts/sbtc "installBin"`; direct `bin/ssc run --v1 tests/conformance/sql-basic.ssc`
  and `bin/ssc run --v1 tests/conformance/sql-transaction.ssc`; and
  `tests/conformance/run.sh --only 'sql-basic,sql-transaction' --no-memo`
  (**2/2 green**).

## conformance-effects-choose-one-shot — `fixed` (2026-07-08)
<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     gate: tests/conformance/effects.ssc
     fixed-in: unrecorded -->

- **Found by:** codex, while refreshing `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `scripts/conformance -- --only 'effects' --no-memo`.
- **Observed:** the INT lane printed only `Alice` and then failed with
  `One-shot violation: Choose.pick resumed more than once`; JS/JVM printed the
  expected nondeterminism list and passed.
- **Root cause:** `tests/conformance/effects.ssc` documented the `Choose` block as
  "Multi-shot: nondeterminism" but declared it as plain `effect Choose`. Per
  `specs/algebraic-effects.md` and existing interpreter tests, multi-resume
  handlers must opt in with `multi effect`.
- **Fix:** `edda7c5d3` changes the conformance declaration to
  `multi effect Choose`.
- **Verification:** `bin/ssc run --v1 tests/conformance/effects.ssc` prints all
  three expected lines, and
  `scripts/conformance -- --only 'effects' --no-memo` passes INT/JS/JVM.

## conformance-actors-exit-os-shadow — `fixed` (2026-07-08)
<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     gate: tests/conformance/actors-supervision.ssc
     fixed-in: unrecorded -->

- **Found by:** codex, while refreshing `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `bin/ssc run --v1 tests/conformance/actors-supervision.ssc` or
  `scripts/conformance -- --only 'actors-supervision' --no-memo`.
- **Observed:** the INT lane printed only `worker starting`; JS/JVM passed. A
  focused trace showed `link(worker)` registered but `exit(me, "crash")` never
  reached `ActorScheduler.killActor`.
- **Root cause:** lazy plugin loading registered `std.os.exit(code)` under the same
  bare global name as the core actor primitive `exit(pid, reason)`, overwriting the
  actor native. The OS intrinsic treated non-code argument shapes as `sys.exit(0)`,
  so the worker actor stopped the process path instead of sending an actor exit
  signal.
- **Fix:** `96bf969ed` keeps a pre-existing native binding as a fallback when a
  plugin intrinsic reports a usage mismatch, and changes `std.os.exit` to throw a
  usage error for non-`Int` arguments. A regression test loads actors+os plugins
  together and verifies actor `exit(pid, reason)` still drives supervision.
- **Verification:** `scripts/sbtc "backendInterpreterPluginTests/testOnly scalascript.ActorSupervisionTest"`
  passes 10/10; after `scripts/sbtc "installBin"`,
  `scripts/conformance -- --only 'actors-supervision' --no-memo` passes INT/JS/JVM.

## v2-rozum-schema-streaming-parity — `fixed` (2026-07-08)
<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     fixed-in: unrecorded -->

- **Found by:** codex, during the v2 production parity loop after
  `v2-quoted-macro-interpreter-parity` was fixed.
- **Symptom:** the latest full
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity --all` has **0
  v2-error** cases and only one remaining production-relevant blocker cluster:
  `examples/rozum-agent-schema-derived.ssc` and
  `examples/rozum-agent-streaming.ssc` still mismatch, while
  `rozum-agent.ssc` and `rozum-agent-pool.ssc` already match.
- **Repro:** after `scripts/sbtc "installBin"`, run:
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/rozum-agent-schema-derived.ssc examples/rozum-agent-streaming.ssc`.
  If needed, compare direct outputs:
  `bin/ssc run examples/rozum-agent-schema-derived.ssc`,
  `bin/ssc run --v2 examples/rozum-agent-schema-derived.ssc`,
  `bin/ssc run examples/rozum-agent-streaming.ssc`, and
  `bin/ssc run --v2 examples/rozum-agent-streaming.ssc`.
- **Notes:** decide by evidence whether this is a v2 bridge/server/batch bug or a
  scope classification issue. Do not normalize `scripts/v2-output-parity`; fix the
  real output path or document an explicit lane/scope exclusion.
- **Root cause:** two independent v2 bridge/runtime gaps. `AgentSchemaInstance` is a
  case class with a method body (`decode`), but v2 only dispatched its fields, so
  `schema.decode(argsJson)` returned `Stub` and the typed handler later matched on
  `Stub` instead of `Some`/`None`. Separately, FrontendBridge's constructor lowering
  dropped positional args whenever any named arg was present, so
  `AgentEvent("TextDelta", text = content)` produced `kind = Unit`; streaming callbacks
  ran but every `event.kind == ...` guard was false.
- **Fix:** `e80b1e70b` preserves positional constructor args in mixed
  positional/named case-class calls, adds `AgentSchemaInstance.decode` dispatch to the
  v2 runtime, and adds v2 regression tests for both shapes.
- **Verification:** after `scripts/sbtc "installBin"`, targeted parity
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/rozum-agent-schema-derived.ssc examples/rozum-agent-streaming.ssc`
  is **2/2 MATCH**; the full rozum cluster (`rozum-agent`, `rozum-agent-pool`,
  `rozum-agent-schema-derived`, `rozum-agent-streaming`) is **4/4 MATCH**. Affected
  conformance `scala-cli tests/conformance/run.sc -- --only 'rozum*' --no-memo`
  has **0 matching cases**. Full parity is now **60/81 identical · 5 mismatch ·
  0 v2-error · 16 v1-only**.

## v2-quoted-macro-interpreter-parity — `fixed` (2026-07-08)
<!-- status: fixed
     kind: bug
     lane: int
     area: front
     fixed-in: unrecorded -->

- **Found by:** codex, during the v2 production parity sweep after content-toolkit
  section parity was fixed.
- **Symptom:** `examples/quoted-macro-interpreter.ssc` was a remaining production
  mismatch. v1 printed three lines (`42`, `literal: 7`, `x`), while v2 printed
  only `42` or, after registering the first helper, returned curried closures for
  the computed-body macros.
- **Repro:** after `scripts/sbtc "installBin"`, run:
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/quoted-macro-interpreter.ssc`.
  Direct check:
  `bin/ssc run examples/quoted-macro-interpreter.ssc` versus
  `bin/ssc run --v2 examples/quoted-macro-interpreter.ssc`.
- **Root cause:** the v2 run path used `MacroCodegen.expand` only for generated-
  backend-expandable macro bodies. That correctly left interpreter-only bodies
  (`x.asValue.getOrElse(...)`, `x.asTerm.name`) in helper form, but the v2 bridge
  did not register the v1 interpreter's helper globals (`__ssc_macro__`,
  `__ssc_quote__`, `Expr`, `QuotedContext`, etc.) or `Expr.asValue` /
  `Expr.asTerm` method dispatch. A second forward-reference bug meant an inline
  macro entrypoint that appeared before its `impl(...)(using QuotedContext)` helper
  converted before FrontendBridge knew the helper needed a synthesized `using`
  argument, so the impl call returned a curried closure.
- **Fix (387c804da):** `PluginBridge.registerInterpreterBuiltins` registers the
  restricted quoted-macro helper globals for v2 runs, `Prims.__method__` handles
  `DataV("Expr").asValue/asTerm`, `__resolve_given__` supplies the built-in
  `QuotedContext`, and FrontendBridge pre-records `using` metadata before converting
  top-level bodies.
- **Verification:** `bin/ssc run examples/quoted-macro-interpreter.ssc` and
  `bin/ssc run --v2 examples/quoted-macro-interpreter.ssc` both print `42`,
  `literal: 7`, `x`; targeted parity for `quoted-macro-interpreter.ssc` plus
  `quoted-macro-constfold.ssc` is **2/2 MATCH**; affected conformance
  `scala-cli tests/conformance/run.sc -- --only '*quoted*' --no-memo` has **0
  matching cases**; full
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity --all` is now
  **58/81 identical · 7 mismatch · 0 v2-error · 16 v1-only**.

## plugin-lazyload-extern-imports — `fixed` (2026-07-07)
<!-- status: fixed
     kind: bug
     lane: int
     area: front
     fixed-in: unrecorded -->

- **Found by:** claude (tkv2-pwa-adopt slice) — the stock `examples/pwa/pwa-demo.ssc`
  fails on clean origin/main.
- **Symptom:** extern defs provided by lazy-loaded std plugins are unreachable from
  `.ssc`: `[smtpSend](std/smtp.ssc)` / `[tcpListen](std/tcp.ssc)` → "'X' not found in
  std/Y.ssc" at import; `requires: [std.pwa]` + `pwa(...)` → "Undefined: pwa".
  Preloaded plugins (std/ui frontend/fetch) are unaffected. Reproduced with a fresh
  origin/main worktree build — pre-existing, likely from the recent plugin-loading /
  stable-SPI stream.
- **Repro:** `bin/ssc examples/pwa/pwa-demo.ssc` (stock example) or a 5-line probe
  importing `[smtpSend](std/smtp.ssc)`.
- **Impact:** every opt-in plugin capability (smtp send, raw TCP / IMAP sim, pwa) is
  dead from user code on main. busi's live deploys pin an older ssc, so production
  is unaffected until a bump.
- **Fix (2026-07-07):** the essential/advanced .sscpkg split (fast startup) never
  wired the advanced set into any load path. Now: Main registers the
  `plugin-available/` dirs (`BackendRegistry.setAvailableDirs`) and the
  interpreter's LAZY `ensurePluginsLoaded()` (first missing name/extern) commits
  them via `BackendRegistry.loadAvailableNow()` (idempotent, best-effort per pkg).
  Startup stays fast; smtp/tcp/pwa/sql/auth/crypto… are reachable again. Verified:
  probes ([smtpSend](std/smtp.ssc), [tcpListen](std/tcp.ssc), bare tcpConnect
  extern), stock pwa-demo boots, `tkv2-pwa` conformance un-pended and green.
- **Note:** `tests/conformance/tkv2-pwa.ssc` covers the .ssc-level path;
  `PwaPluginTest` covers the generators.

## asm-jit-effect-pathology — `fixed` (2026-06-21, `0d5e03b87`)
<!-- status: fixed
     kind: perf
     lane: int
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** benchmark perf-divergence sweep (`./bench.sh`), accepted from `SPRINT.md`.
- **Symptom:** the synthetic `ssc-asm` backend (`SSC_JIT_BACKEND=asm`) is orders of magnitude slower than
  default `ssc` on `bench/corpus/effect-oneshot.ssc`, a hot loop that performs and handles a one-shot
  algebraic effect (`Bump.tick(resume) => resume(1)`). Current worktree repro after `sbt cli/installBin`:
  `./bench.sh effect-oneshot --backend ssc` = 0.043 ms/iter; `./bench.sh effect-oneshot --backend ssc-asm`
  = 9.46 ms/iter.
- **Root cause:** `JavacJitBackend.walkLong` already lowered one-shot tail-resume effect calls to
  `JitGlobals.resolveEffectLong*`, but `AsmJitBackend.walkLong` did not. The `Bump.tick().toLong` expression
  therefore made ASM bytecode JIT bail and left the workload on the slow effect trampoline.
- **FIXED (2026-06-21, `0d5e03b87`):** ASM now mirrors the Javac lowering for active one-shot effect
  resolvers (`resolveEffectLong`, `resolveEffectLong1`, `resolveEffectLong2`) and treats a resolved effect
  call as Long-shaped for `.toLong`/`.toInt` routing. Regression guard: `AsmEffectJitTest` compiles and runs
  `acc + Bump.tick().toLong` through `AsmJitBackend` with an active resolver.
- **Verified:** `sbt -no-colors "backendInterpreter/testOnly scalascript.interpreter.vm.jit.AsmEffectJitTest
  scalascript.EffectOneShotFastPathTest scalascript.JitLintTest"` = 85/85; `sbt -no-colors cli/installBin`;
  `./bench.sh effect-oneshot --backend ssc` = 0.025 ms/iter; `./bench.sh effect-oneshot --backend ssc-asm`
  = 0.032 ms/iter (was 9.46 ms/iter in the accepted repro).

## interp-monadic-forcomp — `fixed` (2026-06-15)
<!-- status: fixed
     kind: bug
     lane: int
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** `CrossBackendPropertyTest` (wave-4 comprehension probes).
- **Symptom:** a `for`-comprehension over `Option` / `Either` (non-`List` monad) threw **in the interpreter**; JS + JVM were correct.
  - `for x <- Some(3); y <- Some(4) yield x + y` → interp `No method 'getOrElse' on List` (interp desugared the Option for-comp as a List op → result was a `List`, not an `Option`).
  - `for x <- Right(3); y <- Right(4) yield x * y` → interp `Cannot iterate over Right(3)`.
- **FIXED (2026-06-15):** made `PatternRuntime.evalForYield` monad-polymorphic. When a generator's evaluated value is NOT a `ListV` (and the pattern is irrefutable + the tail is all simple generators), it desugars to `recv.flatMap(pat => <rest>)` / `recv.map(pat => body)` dispatched on the actual value via `DispatchRuntime.dispatch1` + a `NativeFnV` closure — exactly what the JS/JVM backends emit. `List` keeps its allocation-light fast path; guards / refutable patterns over a non-List monad fall through unchanged. Guard: `CrossBackendPropertyTest` "monadic for-comprehension cross-backend" (option some/none, either right/left, single-generator, + a List regression — interp == JS == JVM).

## interp-returnclause-effect-in-while — `fixed` (2026-06-15)
<!-- status: fixed
     kind: bug
     lane: int
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** `CrossBackendPropertyTest` diagnostic (return-clause shape localization).
- **Symptom:** a deep return-clause handler over a program that performs an effect inside a `while` loop threw **in the interpreter** with `Unhandled effect: Log.emit (no handler in scope)`, even for a single iteration. **JS and JVM both produce the correct result.** This made the property test's case-7 (return-clause) shape vacuous: interp threw → seed skipped → JS/JVM never compared.
- **Repro:**
  ```scalascript
  effect Log:
    def emit(): Int
  def prog(): Int ! Log =
    var i = 0
    while i < 3 do
      Log.emit()
      i = i + 1
    0
  val xs = handle(prog()) {
    case Log.emit(resume) => 7 :: resume(())
    case Return(_) => List()
  }
  println(xs.length)   // js/jvm: 3 ; interp: THROWS
  ```
- **Root cause:** the handler body `7 :: resume(())` is NOT a clean tail-resume, so `evalHandle` installs no inline resolver for `Log.emit`. The op then has to thread as a `Computation` (Perform/FlatMap) through `handleInterp`, but the fast-while path (`tryFastWhileAssign`, `EvalRuntime.scala`) drove the loop's leading applies eagerly via `Computation.run`, so the `Perform` escaped the handler. A direct (non-loop) emit works; only the while-loop shape failed.
- **FIXED (2026-06-15):** captured `EffectAnalysis.effectOps` into `Interpreter.effectOpNames` (alongside `multiShotEffects`) at module init, and added an up-front guard `whileBodyHasUnresolvedEffect` at the top of `tryFastWhileAssign`: if the loop body performs an effect op with NO active inline resolver (`EffectsRuntime.lookupResolver(eff, op) == null`), bail (return null) to the monadic trampoline, which threads effects via `FlatMap`. The one-shot tail-resume fast path keeps a live resolver, so the guard returns false for it and the fast/JIT path is preserved (no perf regression — `EffectVmContinuationsTest` / `EffectOneShotFastPathTest` stay green). Guard: `CrossBackendPropertyTest` "effect return-clause cross-backend (… / while)" now runs the while shape interp == JS == JVM, and the generated JVM differential rose from 17 → 19 checked seeds (the formerly-skipped return-clause seeds 23/59 now produce an interp baseline). 366 effect/JIT/VM tests green.

---

## interp-import-cycle-stackoverflow — `fixed` (2026-06-14)
<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     fixed-in: unrecorded -->

- **Reported:** busi (`@busi-claude-code`), during the busi `p5` `dispatch.ssc`
  decomposition (the facade re-export / strict-DAG work).
- **Symptom:** a true module **import cycle** (`A→B→A`, e.g. a sub-module importing
  back from the facade that imports it) aborts with a bare `java.lang.StackOverflowError`
  and **no module-resolution message** — the cause (a cycle) is invisible. Distinct
  from the FIXED `interp-module-loader-dedup` (a *diamond* is acyclic and handled by
  the cache; a *cycle* is not).
- **Repro:** 3–4 modules forming a cycle: `a` imports `b`, `b` imports `a` (or the
  facade↔leaf variant: `a` imports back from `facade`, `facade` imports `a`). Run the
  entry → `StackOverflowError`. See `runtime/.../InterpImportCycleTest.scala`.
- **Root cause:** `SectionRuntime.runImport`'s `moduleCache.getOrElseUpdate(path, …)`
  only **inserts after the thunk returns**; while a module's body is still running its
  path is absent from the cache, so a cyclic re-import re-runs it → unbounded recursion.
- **Fix:** a shared, insertion-ordered `moduleLoading: LinkedHashSet[os.Path]` threaded
  into child interpreters like `moduleCache`. `runImport` checks it **before**
  `getOrElseUpdate` — a re-entry on a still-loading path throws
  `InterpretError("Import cycle detected: a.ssc → b.ssc → a.ssc")`; the path is added
  before the body runs and removed in a `finally`, so a later legitimate import of the
  same (finished) module is unaffected. Purely diagnostic — no semantic change for
  acyclic graphs / diamonds. Spec `specs/import-cycle-diagnostic.md`.
- **Verify:** `InterpImportCycleTest` (2-cycle + facade↔leaf cycle → legible error not
  `StackOverflowError`; acyclic re-export control still computes) + `InterpModuleDedupTest`
  green (no regression).
- **Landed:** (this branch → origin/main).

## interp-parameterized-effect-decl — `fixed` (2026-06-13, `2a818e45c`)
<!-- status: fixed
     kind: bug
     lane: int
     area: front
     fixed-in: unrecorded -->

- **Fixed:** `Parser.effectLinePat` (the regex that rewrites `effect Name:` →
  `object Name { … }`) had no type-param clause after the name, so `effect State[S]:` /
  `effect Box[T]:` were left un-rewritten and reached the Scala parser as a bare
  `effect Name[T]` expression → `No method 'Name' on NativeFnV(<native:effect>)`. Added an
  optional `(?:\[[^\]]*\])?` after `(\w+)` (the `object` drops the type param; op
  signatures may still mention it — the interpreter erases types). Shared `lang/core`
  Parser, so all backends benefit. Regress: `StdEffectsTest` (`effect Box[T]:` decl + handle).

## interp-effect-multishot-in-subsection — `fixed` (2026-06-13, `2a818e45c`)
<!-- status: fixed
     kind: bug
     lane: int
     area: codegen
     fixed-in: unrecorded -->

- **CORRECTION:** filed as `interp-effect-multishot-cross-section-leak` — that "global state
  leaks from an earlier one-shot `handle`" diagnosis was **wrong**. Real cause: `multiShotEffects`
  was **never populated for subsection code blocks at all**. `Interpreter.runInit` collected the
  effect-analysis trees only from top-level `module.sections` content, not the nested `##`/`###`
  subsections where the blocks actually live (`[DBG] sections=1 allTrees=0 multiShotEffects=Set()`).
  So a `multi effect` declared in a subsection was never registered → its handler defaulted to
  one-shot → `One-shot violation` on the 2nd `resume`. A `multi effect` directly under the top-level
  `#` worked, which made it look order/leak-dependent.
- **Fixed:** `runInit`'s tree collection now recurses `s.subsections`. Regress: `StdEffectsTest`
  (`multi effect` in a `##` subsection multi-shots); `examples/algebraic-effects.ssc` runs
  end-to-end and is in `ExamplesSmokeTest`. Interp-only — JVM/JS codegen already gather all
  blocks recursively.

## interp-toString-on-collection — `fixed` (2026-06-13, `225aacc18`)
<!-- status: fixed
     kind: bug
     lane: int
     area: codegen
     fixed-in: unrecorded -->

- **Fixed:** intercept `toString` (0-arg) at the top of `DispatchRuntime.dispatch`
  (alongside the `asInstanceOf` early-return) → render via `Value.show`, the canonical
  println / string-interpolation path, so `x.toString == s"$x"` for every value. A
  case-class instance with a user-defined `toString` method keeps it (checked via
  `lookupTypeMethod` first). Needed to intercept at the TOP because type-specific
  dispatchers mis-handle the name first (`map.toString` → key lookup → "No key
  'toString'"). Interp-only fix (JVM/JS codegen emit native `toString`). Regress:
  `BugReproTest` (list render + composite canonical-render invariant across
  List/Map/tuple/Option/case-class); 65 `.toString`-dependent tests across 7 suites
  green; `examples/async-parallel-demo.ssc` now runs end-to-end.
- **Found:** by me, expanding `ExamplesSmokeTest` (`examples/async-parallel-demo.ssc`
  fails). Reproduces on `origin/main` (`e73fd9a73`) via the interpreter.
- **Symptom:** `.toString` is universal in Scala (every value has it) but the
  interpreter has no `.toString` dispatch for a `ListV` (and likely other collection /
  composite Values) → `No method 'toString' on ListV(List(50, 50, 50))`.
- **Repro:**
  ```scalascript
  val xs = List(50, 50, 50)
  println("result=" + xs.toString)   // No method 'toString' on ListV
  ```
- **Note:** broadly useful, likely small — add a universal `.toString` fallback in the
  interpreter's method dispatch (render via the same path as `println`/string-concat).
  Check Map/Set/tuple/Option/Either too. Cross-backend regression.

## interp-module-loader-dedup — `done` (busi confirmed, rozum seq-137)
<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     fixed-in: unrecorded -->

**Re-verified 2026-08-02 at `1305736e1`** on the entry's own three-module shape — `big` (with a
load-time `println` as the side effect), `spi` importing `big`, `entry` importing BOTH. The side
effect fires **exactly once** and both values are right, so the loader dedups. Without dedup the
entry says `big` is evaluated repeatedly to the point of OOM; a single line of output is the
discriminating observable, which is why the fixture prints on load rather than asserting a time.

- **Reported:** busi (`@busi-claude-code`), rozum `scalascript` seq-132 (2026-06-12).
- **Symptom:** interpreting (not `ssc check`) an entry that imports a large module via
  **two edges** (diamond) — e.g. `server.ssc` imports `dispatch.ssc` (~7942 lines)
  directly *and* via a small `route_spi.ssc` that also imports `dispatch` — blows up:
  pathological re-evaluation → OOM / hang at load time, 0 lines of the program run.
  `ssc check` is green (typer memoizes module loads; the interpreter loader did not).
- **Repro:** 3 modules — `big` (large/with a load-time side effect) + `spi` importing
  `big` + `entry` importing both `big` and `spi`. Without dedup, `big` is evaluated
  once per DAG path (exponential in diamond layers). See
  `runtime/.../InterpModuleDedupTest.scala`.
- **Root cause:** `SectionRuntime.runImport` created a fresh `Interpreter` and re-ran
  the imported module on **every import edge** — no cache keyed by module path.
- **Fix:** shared `moduleCache: Map[os.Path, Interpreter]` threaded through child
  interpreter constructors; `getOrElseUpdate(resolvedPath)` in `runImport` → each module
  evaluated once per run (init side effects run once, matching the typer). Spec
  `specs/interp-module-loader-dedup.md`.
- **Verify:** rebuild `installBin` on the landing pin, re-run the busi diamond (drop the
  `Any`-typed `route_spi` workaround). Regression: `InterpModuleDedupTest` (diamond +
  3-layer stacked diamond; asserts shared module loads exactly once).
- **Landed:** `f6d3245a3` (origin/main, 2026-06-12).
- **Confirmed:** busi bumped to `7470392e` + `installBin`, removed the `Any` workaround,
  their phase23 diamond (was OOM at load) now loads + passes (30 checks), full regression
  green, ph-2 domain-module split unblocked (rozum seq-137). **Closed.**

## v2-native-result-unregistered-field — fieldAt crashes on a native result whose case class isn't imported
<!-- status: fixed
     kind: bug
     lane: int
     area: front
     fixed-in: unrecorded
     gate: tests/conformance/v2-native-result-unregistered-field.ssc -->

**Status:** FIXED 2026-07-09 (793922d00) — fieldAt named 3-arg routes through methodOp/__method__ (handles ForeignV/NamedMethodObj); guarded by
tests/conformance/v2-native-result-unregistered-field.ssc)

Calling a GLOBAL, extern-backed std function (e.g. `exec`, from
std/process.ssc, bound by the os-plugin) WITHOUT importing its declared
return type (`ProcessResult`), then accessing a field on the result
(`r.exitCode`), crashes on v2 — even though the exact same code runs fine
on v1. busi's `core/repo_git_mirror.ssc` does exactly this (calls `exec`
bare, never imports `ProcessResult`; the plugin activates because the file
separately imports OTHER externs from std/fs.ssc, e.g. `mkdirs`/`readFile`).

v1's interpreter resolves `.exitCode` DYNAMICALLY by name against the
native result's own field map — it never needs `ProcessResult`'s shape
ahead of time. v2 compiles named field access to a static
`fieldAt(recv, index, name)` using a GLOBAL, receiver-blind
field-name→index registry built only from `case class` declarations
TEXTUALLY PRESENT in the compiled unit (FrontendBridge's `fieldIndex`/
`registerCaseClass`). Since `ProcessResult` is never imported here, its
shape is unregistered — the v1→v2 bridge can't build a proper
`DataV("ProcessResult", …)` for the native result and falls back to a raw
representation. `fieldAt`'s runtime fallback then calls `asData` on that
non-`DataV` value and crashes: "expected Data, got ProcessResult(...)".

GENERAL gap, not process-specific: any native/extern function returning a
case-class-shaped value whose class isn't ALSO imported in the same
compile unit will hit this (the sibling of v2-head-field-dispatch-shadow —
same "fieldIndex is global + receiver-blind, fieldAt trusts it
unconditionally" root design, different receiver shape: plugin-native
value instead of a builtin Cons). This is the busi `repo_git_mirror`
v2-sweep failure. Repro: `bin/ssc --v2
tests/conformance/v2-native-result-unregistered-field.ssc`. Fix candidate:
`fieldAt`'s runtime fallback should route through the `__method__`
structural/plugin dispatch (which already handles
`ForeignV(NamedMethodObj)`) instead of unconditionally calling `asData`,
OR the v1↔v2 bridge should tag native results by their declared name
regardless of whether that case class was locally registered.
Found+minimized 2026-07-09 by busi (fable).

## v2-route-params-stub — req.params(name) always returns Stub on v2
<!-- status: fixed
     kind: bug
     lane: int
     area: front
     fixed-in: unrecorded
     gate: tests/e2e/route-params-v2-smoke.sh -->

**Status:** FIXED 2026-07-09 — `req.params`/`req.query` (and `bearerToken`/
`jwtClaims`/`basicAuth`) are runtime-INJECTED by the server
(`InterpreterHttpHandler.liftRequest`) and are NOT in std/http.ssc's `Request`
case class. `FrontendBridge.registerCaseClass` was overwriting BOTH the field
registry and `V2PluginRegistry` with the 9-field case-class layout, so
`v1ToV2` dropped `params`/`query` from the Request `DataV` entirely and
`req.params` fell through to a stubbed dispatch. Fix: a single source of truth
`PluginBridge.requestFieldNames` (the runtime layout) that FrontendBridge locks
into its registry (`runtimeShapedTypes`), which `registerCaseClass` no longer
overrides. `req.params(:name)` now returns the real segment on --v2 for mid and
trailing positions; other Request fields (method/path/headers/query) verified
unchanged. Corpus 154/8 (no regression). Gate flipped green:
tests/e2e/route-params-v2-smoke.sh now fails on regression. Fixed by
lucky-perch; reported+repro'd by busi.

FOLLOW-UP FIXED 2026-07-09 (user-request-collision): the fix reserved
"Request" GLOBALLY (FrontendBridge.runtimeShapedTypes), so a user's OWN
`case class Request` resolved as the HTTP Request(14) and its fields read
Stub in the batch/conformance path (standalone `ssc run` was fine). The
lock is now CONDITIONAL — only the std/http.ssc lib Request shape (its
exact 9 declared fields) is locked; a user Request with a different shape
registers and wins. Guarded by tests/conformance/user-request-shadow.ssc.
(Also reverted the parallel fieldNames snapshot/restore batch-isolation
d5f9ce486 — it was orthogonal, did not fix an active bug, and re-asserted
the built-in Request baseline.)

Any HTTP route registered with a `:name` dynamic path segment
(`route("GET", "/foo/:id/bar")`) works fine on v1: `req.params("id")`
resolves to the real matched segment. On v2, `req.params("id")` (and any
other `:name`) silently returns `DataV("Stub", ...)` instead — no crash, no
warning, just a wrong value flowing into business logic. Position doesn't
matter: a MID-position segment (`/mid/:x/tail`) and a TRAILING one
(`/end/:x`) both reproduce identically. Repro:
`tests/e2e/route-params-v2-smoke.sh` (fixture:
tests/e2e/fixtures/route-params-smoke.ssc) — prints `--v1: mid=hello |
end=hello` (correct) vs `--v2: mid=Stub | end=Stub` (broken).

Found 2026-07-09 running busi's full JDG money-loop simulator cycle
(`make v2-sims`). This is why 2 of busi's 3 KSeF checks and its tax
e-Deklaracje check fail on --v2 — every one of those routes reads
`req.params("ref")` to look up an object by ID
(`/api/online/Invoice/Get/:ref`, `/e-deklaracje/status/:ref`,
`/e-deklaracje/upo/:ref`) and gets "Stub" instead, so the lookup misses and
the handler falls through to a 404-shaped "no such X" response — easy to
misdiagnose as an application bug rather than a routing one. NOTE: busi's
bank simulator (PSD2/AIS) ALSO hits this (its `:id`-authorisations route
prints `id=[Stub]` too, confirmed by instrumenting it directly) but its
Makefile check (`v2-bank-pis-check`) happens to still report OK because
`payViaBank`'s client code reads `transactionStatus` straight off the
`/authorisations` POST response body (which the sim hardcodes to `"ACSC"`
regardless of whether the id matched) and never calls the separate
`/status` GET that would have exposed the same bug — a test-coverage gap,
not evidence the bug is narrower than it is.

Likely mechanism (not fully traced to a single line — flagging for whoever
picks this up): `PluginBridge.scala`'s `v1ToV2` conversion of a v1
`Request` `InstanceV` has two diverging paths — a positional
`fieldsArr != null` fast path that trusts the v1 instance's own field
order directly, and a named `effFields` + `V2PluginRegistry.lookupFieldNames`
path that reorders by a hardcoded 14-name list (registered at
PluginBridge.scala ~line 349: `method, path, body, headers, params, query,
json, form, files, session, cookies, bearerToken, jwtClaims, basicAuth`).
That hardcoded order does NOT match std/http.ssc's own declared 9-field
`Request` case class (`method, path, headers, body, form, files, cookies,
session, json` — no `params`/`query`/etc at all), so whichever v1 HTTP
server backend actually constructs the runtime `Request` instance for a
matched route (three different backend implementations exist under
v1/runtime/http-server: JdkServerBackend / RestRuntime / ProxyRuntime) may
not be populating (or ordering) a `params` slot the way either the ssc
declaration or the hardcoded bridge list expects — worth checking whether
the real Request instance even carries path-match results in
`effFields`/`fieldsArr` at all for the backend serving this test.
Found+minimized 2026-07-09 by busi (fable) while running the full JDG
money-loop simulator cycle.
