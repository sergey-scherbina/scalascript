# Bug tracker

Durable ledger of bugs reported in the `scalascript` rozum room (or found locally).
See the `rozum` skill — "The bug-tracking loop". Newest first. Status flow:
`open → needs-info → fixed → (confirmed) → done`. Keep fixed/done entries with their
commit SHA until the reporter confirms, then they can be trimmed.

| Status legend | |
|---|---|
| `open` | reproduced / accepted, work to do |
| `needs-info` | blocked on a repro question asked in the room |
| `fixed` | landed on `origin/main`, reporter not yet re-confirmed |
| `done` | reporter confirmed fixed (safe to trim) |


## jsgen-enum-payload-extract — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-6 enum probes).
- **Symptom:** matching an `enum` case WITH a payload bound the wrong value on JS — `enum Shape: case Circle(r: Int); … case Circle(r) => …` bound `r` to the case's `_tag` (0/1), not the field. `area(Circle(2)) + area(Square(3))` gave `1` instead of `21`; interp + JVM correct. `genPattern`'s Extract used field NAMES from `caseClassFieldsByType` when known, else the positional `Object.values(scrut).slice(1)[i]` — but enum cases carry an extra `_tag` field, and `caseClassFieldsByType` was populated only for `Defn.Class`, not enum cases, so `slice(1)[0]` returned `_tag`.
- **FIXED (2026-06-15):** `caseClassFieldsInModule` now also indexes `Defn.Enum` cases (name → field list), so enum-case Extract binds by field name. Guard: `CrossBackendPropertyTest` "enum payload, collect, Option.fold cross-backend" (enum-payload-match + enum-nullary).

## interp-collect-partial / jsgen-collect-partial — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-6 collection probes).
- **Symptom:** `xs.collect { case x if x % 2 == 0 => x * 10 }` (a partial function with a guard) threw `Match failure: 1` in the INTERPRETER (it called the PF as a total function), and on JS threw `Method not found: collect` (no `collect` in the array `_dispatch`); JVM correct. `collect` must SKIP elements the PF isn't defined on.
- **FIXED (2026-06-15):** interp — a `collectStep` helper catches the located "Match failure" and skips (reusing the existing `None`-skip path). JS — added a `collect` array-dispatch case that calls the element fn and skips when it throws a "Match failure" (the emitted PF closure's no-match error). Guard: `CrossBackendPropertyTest` collect-guard.

## jsgen-option-fold-curried — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-6 Option probes).
- **Symptom:** `Some(5).fold(0)(x => x * 2)` failed on JS — the curried `Option.fold(ifEmpty)(f)` was absent from the `_Some`/`_None` dispatch (only `Either.fold(fa, fb)` uncurried was present). interp + JVM correct.
- **FIXED (2026-06-15):** added `fold` to the JS Option dispatch — `_Some`: `(f) => f(value)`, `_None`: `(f) => ifEmpty` — handling the curried second clause. Also added `exists`/`forall` and fixed `Some.contains` to use structural `_eq`. Guard: `CrossBackendPropertyTest` option-fold-some/-none.

## xbackend-range-by-step — `open`

- **Found by:** `CrossBackendPropertyTest` (wave-6).
- **Symptom:** `(0 to 10 by 2)` — a Range with a `by` step — throws on interp (`No method 'by' on List`; interp materializes `0 to 10` as a List, which has no `by`) and on JS; JVM (raw scala-cli) is correct. `by` / stepped ranges are unsupported off-JVM.
- **Note:** needs a Range representation (or a `by` that re-steps the materialized list) in interp + a `by` dispatch on JS. Deferred (lower frequency, multi-backend).

## jvmgen-autooutput-after-classdef — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-5 case-class probes).
- **Symptom:** a JVM program with a top-level `case class` (or trait/object) followed by ANY auto-output/expression statement printed NOTHING — `case class P(x: Int)\nprintln(if P(1) == P(1) then 10 else 0)` produced empty output; interp + JS correct. `wrapAutoOutput` emitted a bare `{ … }` block, and `case class P(x: Int)` on one line followed by `{ … }` on the next is parsed by Scala as **P's body template**, so the statement was swallowed (never run).
- **FIXED (2026-06-15):** `wrapAutoOutput` now emits `locally { … }` (an unambiguous method call) instead of a bare `{ … }`, so the block can't attach to a preceding definition. Guard: `CrossBackendPropertyTest` "collections, case-class equality, num+string cross-backend" (caseclass-eq/-ne/-output).

## jsgen-structural-equality — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-5 case-class probes).
- **Symptom:** `==` on the JS backend used JS reference equality (`===`), so two structurally-equal case-class instances / tuples / Lists compared unequal — `P(1) == P(1)` → `false`; interp + JVM correct.
- **FIXED (2026-06-15):** added a `_eq(a, b)` deep-structural-equality runtime helper (arrays elementwise, objects by `_type` + own keys, primitives by `===`) and routed `_arith('==' / '!=', …)` through it. Also used for Set dedup. Guard: `CrossBackendPropertyTest` caseclass-eq/-ne, tuple-eq.

## jsgen-set-constructor — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-5 Set probes).
- **Symptom:** `Set(1, 2, 3)` failed on JS with `TypeError: Constructor Set requires 'new'` — JsGen had `Map`/`List` constructor cases but no `Set`, so `Set(...)` fell through to the JS global `Set`.
- **FIXED (2026-06-15):** added a `Set(...)` / `Set[T](...)` case emitting `_setOf(...)` — a runtime helper that builds a structurally-deduplicated array, so the existing array `_dispatch` methods (`size`/`toList`/`sorted`/`contains`/…) apply. Guard: `CrossBackendPropertyTest` set-dedup-ops, set-contains.

## interp-num-string-concat — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-5 Map probes).
- **Symptom:** `6 + "_"` (a number `+` a String — Scala's `any2stringadd`) threw in the interpreter (`No method '+' on IntV`); JS + JVM correct. interp's `Int + …` only handled numeric operands.
- **FIXED (2026-06-15):** `dispatchInt` / `dispatchInt1` now concatenate when the `+` operand is a `StringV` (`n.toString + s`). Guard: `CrossBackendPropertyTest` num-string-concat.

## js-supertype-typetest — `fixed` (2026-06-15)

- **Found by:** busi (UI session). A `cardWithHeader(header)` card title rendered on **no**
  screen in the SPA — money, compliance, and the new UA ФОП cockpit alike — while the card
  body rendered fine and the interpreter (`ssc render`) was correct, so every `.ssc` test
  passed. Browser DOM inspection showed the card-header `<div>` absent; the page heading
  (`thView(2,…)`) and standalone section headings (`thView(3,…)` in a vstack) rendered.
- **Symptom:** on the **JS backend**, a type-test against a supertype — sealed trait /
  parent enum / abstract class — never matches a subtype instance. `sealed trait TkNode;
  case class HeadingNode(t) extends TkNode; (x: Any) match { case h: TkNode => … }` skips the
  `TkNode` arm for a `HeadingNode`. Emitted objects carry only their leaf `_type`
  (`{_type:'HeadingNode'}`); `JsGenCpsCodegen.genPattern`'s `Pat.Typed` branch emitted an
  exact `scrut._type === 'TkNode'` check, which a subtype never satisfies. `cardWithHeader`
  lowers `header match { case h: TkNode => render; case _ => [] }` (header field typed `Any`),
  so the title fell to the empty wildcard. The JS analogue of the interp/JIT fix for #1/#3.
- **FIXED — single-module (commit 775a10e68):** scanned type decls + `extends` into
  `supertypeName → Set[concrete leaf _type]` per module; `genPattern`'s `Pat.Typed` widens a
  no-tag (supertype) check to an `_type` OR over that closure. Guard `SupertypeTypeTestJsTest`.
- **FIXED — cross-module (follow-up):** the first commit was insufficient for the actual busi
  case and the single-module test gave **false confidence**. The JS backend emits each imported
  module with a *fresh child `JsGen`* (genImport), and `TkNode` + subtypes live in `nodes.ssc`
  (a `package:` module) while `case h: TkNode` lives in `lower.ssc` — so the importer's matcher
  had no record of the subtype graph and still fell back to the broken exact check (browser
  re-verify after the rebuild still showed dropped titles + `_type === 'TkNode'` in the emitted
  SPA). Fix: accumulate the subtype edges ACROSS imports — `collectSubtypeEdgesFromModule`
  (descends into `package:` wrapping objects) + `recomputeSubtypeClosure`, folded in for the
  entry module and, in genImport, for each imported module + propagated into the child gen
  (mirrors `importedParamOrder`). Guard `SupertypeTypeTestXModuleJsTest` (multi-file: imported
  `package:` trait/subtypes + transitive enum across the import boundary) — the multi-file test
  the `bugs` rule requires. Spec `specs/js-supertype-typetest.md`.
- **Repro:**
  ```scalascript
  sealed trait TkNode
  case class HeadingNode(text: String) extends TkNode
  def isTk(x: Any): String = x match
    case h: TkNode => "tk"
    case _         => "other"
  println(isTk(HeadingNode("hi")))  // interp/JVM: "tk" ; JS (buggy): "other"
  ```

## jsgen-collection-dispatch-gaps — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-4 collection-HOF probes).
- **Symptom:** `xs.sortWith((a,b) => a < b)`, `xs.sorted`, `xs.partition(p)` fail on the JS backend (node) — they were simply MISSING from the `_dispatch` runtime method table (`JsRuntimePart2b.scala`); interp + JVM correct. `val (a, b) = xs.partition(…)` then also failed for lack of `partition`.
- **FIXED (2026-06-15):** added `sortWith` (`lt(a,b)?-1:lt(b,a)?1:0`), `sorted`, `partition` (→ `[yes, no]`), and `span` to the JS `_dispatch` array-method table. The `val (a, b) = …` tuple destructuring already works (`genPatDestructure`). Guard: `CrossBackendPropertyTest` "collection HOFs and pattern matching cross-backend".

## jsgen-match-guard-bind — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-4 pattern-match probes).
- **Symptom:** a `match` with a case GUARD (`case x if x < 0 => …`) fails on the JS backend (node syntax error); interp + JVM correct. `genMatchAsStmts` and the coroutine `genGenStmt` match dropped `c.cond` entirely, so a guarded `case x if …` got pattern-cond `"true"` and was treated as a catch-all mid-chain → malformed `{ … } else if (…)` JS. (`genReceiveMatcher` ANDed the guard but evaluated it with the pattern bindings out of scope.)
- **FIXED (2026-06-15):** all three JS match paths now fold the guard into the arm condition via an IIFE that scopes the pattern bindings: `(cond) && (() => { <bindings>; return (<guard>); })()`. Guarded arms are no longer mistaken for catch-alls (the switch fast-path also excludes them since the cond is no longer `"true"`). Guard: `CrossBackendPropertyTest` "collection HOFs and pattern matching cross-backend" (match-guard-bind shape).

## interp-monadic-forcomp — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-4 comprehension probes).
- **Symptom:** a `for`-comprehension over `Option` / `Either` (non-`List` monad) threw **in the interpreter**; JS + JVM were correct.
  - `for x <- Some(3); y <- Some(4) yield x + y` → interp `No method 'getOrElse' on List` (interp desugared the Option for-comp as a List op → result was a `List`, not an `Option`).
  - `for x <- Right(3); y <- Right(4) yield x * y` → interp `Cannot iterate over Right(3)`.
- **FIXED (2026-06-15):** made `PatternRuntime.evalForYield` monad-polymorphic. When a generator's evaluated value is NOT a `ListV` (and the pattern is irrefutable + the tail is all simple generators), it desugars to `recv.flatMap(pat => <rest>)` / `recv.map(pat => body)` dispatched on the actual value via `DispatchRuntime.dispatch1` + a `NativeFnV` closure — exactly what the JS/JVM backends emit. `List` keeps its allocation-light fast path; guards / refutable patterns over a non-List monad fall through unchanged. Guard: `CrossBackendPropertyTest` "monadic for-comprehension cross-backend" (option some/none, either right/left, single-generator, + a List regression — interp == JS == JVM).

## xbackend-wave4-jvm-transient — `wontfix` (2026-06-15, not reproduced)

- Two wave-4 shapes (`xs.zip(ys).map((a,b)=>a+b).sum`, `(1,(2,3)) match { case (a,(b,c)) => … }`) reported a JVM `scala-cli failed` ONCE, but did NOT reproduce on a clean re-run (interp == JS == JVM all green). The original failure coincided with two contending `sbt`/`scala-cli` processes corrupting temp compiles. Kept as cross-backend guards in "collection HOFs and pattern matching cross-backend"; no code change.

## jvmgen-js-curried-partial — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (main-path edge-case probes).
- **Symptom:** PARTIAL application of a curried def fails on the **JS backend** (`not callable: NaN`); interp + JVM are correct. `def add(a: Int)(b: Int) = a + b; val f = add(3); f(4)` — JsGen flattens curried params to `function add(a, b)`, so `add(3)` runs the body with `b === undefined` → `3 + undefined` = `NaN`. FULL application `add(1)(2)` works (it arrives flattened as `add(1, 2)`); only under-applied calls break. Reproduced for 2- and 3-clause defs.
- **FIXED (2026-06-15):** added a `_curry(fn, arity, args)` JS runtime helper (accumulates args, applies when arity reached) and an auto-curry guard at the top of plain multi-clause def emission: `if (arguments.length < N) return _curry(fname, N, arguments);`. Only emitted for multi-clause defs with no defaults / using / context-bounds; single-clause defs and full applications are unaffected (arity already reached). Guard: `CrossBackendPropertyTest` "curried partial application cross-backend" (2-/3-clause, full + partial, interp == JS == JVM).

## effect-perform-in-fordo — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (effects-in-HOF/loop probes).
- **Symptom:** an effect op performed inside a `for i <- 0 until n do …` loop diverged across all three backends. interp was CORRECT; **JVM** failed scala-cli (`None of the overloaded alternatives of method + in class Int` — `acc + Counter.tick()` where `tick()` is the Any `_perform`), and **JS** printed garbage (`0[object Object][object Object]…`). The `while`-loop form of the same program works on all backends (dedicated CPS while-trampoline); the `for … do` → `foreach(i => …)` desugar did NOT CPS-thread the effect in the closure body. `.map` / `.foldLeft` closures DO thread effects — only `foreach`-from-`for-do` was broken.
- **FIXED (2026-06-15):** added for-do recognizers to BOTH CPS emitters (`JvmGenCpsTransform.emitCpsExpr` + `JsGenCpsCodegen.genCpsExpr`) that desugar to the same while-trampoline the `while` form uses, so the body's `perform`s thread through `_bind`:
  - **Range** `for i <- (lo until/to hi) do body` → index `var`/`let` + trampoline (covers `until` exclusive + `to` inclusive + bodies reading the loop var).
  - **Collection** `for x <- coll do body` (pure non-Range `coll`) → `.iterator` (JVM) / array-index (JS) + trampoline.
  Multi-generator / guarded / complex-pattern for-do falls through to the existing (raw / `_forEach`) path unchanged. Guard: `CrossBackendPropertyTest` "effect perform in for-do loop cross-backend" — 5 shapes (range until/to/loop-var + collection elem/side-effect), interp == JS == JVM.
- **Repro:**
  ```scalascript
  effect Counter:
    def tick(): Int
  def prog(): Int ! Counter =
    var acc = 0
    for i <- 0 until 3 do
      acc = acc + Counter.tick()
    acc
  println(handle(prog()) { case Counter.tick(resume) => resume(5) })  // interp: 15 ; jvm: COMPILE ERROR ; js: garbage
  ```

## jvmgen-handle-result-mainpath — `fixed` (2026-06-15, all contexts incl. Any-taint propagation)

- **Found by:** `CrossBackendPropertyTest` (effect-result × main-path composition probes).
- **Symptom:** a `val r = handle(...)` (Any-typed `_handle` result) used in a NON-arithmetic main-path
  context fails JVM scala-cli; interp + JS run it fine. A cluster of related JVM-only divergences:
  - `r match { case _ => r * 2 }` → `value * is not a member of Any` (`emitExprDeep` had no `Term.Match` case → arm fell to `.syntax`).
  - `if r > 5 then r * 10 else 0` → `Found Any / Required Boolean` (the `_binOp(">", r, 5)` cond wasn't cast to Boolean).
  - `dbl(r)` (user fn) → `Found Any / Required Int` (main-path call didn't cast the arg to the callee param type; only the CPS path did).
- **FIXED (2026-06-15):** in `emitExprDeep` — added a `Term.If` Boolean cast when the cond is an Any-typed handle-result comparison, a `Term.Match` case that recurses scrutinee + arm bodies + guards, and a `Term.Tuple` case; cast main-path call args that reference a handle-result val to the callee's `calleeParamType` (reusing the CPS `localDefSigs`/`depDefs` index). Routed any term that references a handle-result val through `emitExprDeep` via a new `termRefsHandleResultVal` in `termNeedsCustomEmit`. Guard: `CrossBackendPropertyTest` "effect-result main-path composition cross-backend" (match / if-cmp / fn-arg / multishot-arith / nested-handles — interp == JS == JVM).
- **ALSO FIXED (2026-06-15, Any-taint propagation):** the two formerly-deferred contexts:
  - `List(r, r).sum` → `No given Numeric[Any]` — broadened the `emitExprDeep` `_anyCall0` Select routing from "qual IS a handle-result-val Name" to "qual REFERENCES one" (`termRefsHandleResultVal(qual)`), so `List(r, r).sum` → `_anyCall0(List(r, r), "sum")`.
  - tuple-accessor arithmetic `val t = (r, r+1); t._1 + t._2` — added `anyTypedVals`, a superset of `handleResultVals` populated by Any-taint PROPAGATION: an untyped val whose rhs references an Any-typed val (`val t = (r, r+1)`) is itself Any-typed. The routing predicates now key off `anyTypedVals`, and the arith-operand check also recognizes `Select(anyTypedVal, _)` (so `t._1 + t._2` lowers to `_binOp`). Only ever non-empty for effect programs (seeded by `handleResultVals`), so pure code is unaffected. Guard: `result-in-list-sum` + `result-in-tuple` added to the composition test (interp == JS == JVM).

## agent-streaming-test-port-collision — `fixed` (2026-06-15, 26dae7699)

- **Found by:** codex during `rozum-agent-endpoint-pool` regression check.
- **SHA at filing:** `2334d0be4` (feature worktree).
- **Symptom:** running the sync and streaming agent SDK suites in the order
  `AgentSdkInterpreterTest AgentSdkStreamingInterpreterTest` aborts the streaming
  suite with `java.net.BindException: Address already in use`.
- **Repro:** `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/rozum-agent-endpoint-pool && sbt "backendInterpreterPluginTests/testOnly scalascript.AgentSdkInterpreterTest scalascript.AgentSdkStreamingInterpreterTest"`.
- **Root cause:** `examples/rozum-agent.ssc` binds `19694`, the same port as
  `AgentSdkStreamingInterpreterTest`; when the sync suite ran first, the
  streaming suite could immediately rebind the same port and abort.
- **Fix:** moved `AgentSdkStreamingInterpreterTest` to port `19698`.
- **Verify:** `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/rozum-agent-endpoint-pool && sbt "backendInterpreterPluginTests/testOnly scalascript.AgentSdkInterpreterTest scalascript.AgentSdkStreamingInterpreterTest"` — 14 tests passed in the formerly failing order.



---

## jvmgen-handle-result-arith — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (new effect-composition shapes — handle result fed into main-path arithmetic).
- **Symptom:** using a `val` bound to `handle(...)` as an operand of an arithmetic/comparison infix fails JVM scala-cli with `value * is not a member of Any`. `handle(...)` lowers to `_handle(...)` (returns `Any`), so `val r = handle(...){…}; println(r * 2 + base)` emits `r * 2` raw on the Any-typed result, which Scala 3 rejects. interp + JS run it fine.
- **Repro:** a one-shot effect program ending `val r = handle(loop(n)){ case Counter.tick(resume) => resume(k) }; println(r * 2 + base)` (or two results: `println(r1 + r2)`).
- **Root cause:** `termNeedsCustomEmit` only routed a handle-result-val through `emitExprDeep` (where `ApplyInfix` lowers `+ - * / % < > <= >=` to `_binOp`) when the val appeared in a 0-arg method `Select` (`termContainsHandleResultCall`), NOT when it appeared as an arithmetic operand — so `r * 2` fell to `emitExpr`'s `.syntax` raw fallback.
- **FIXED:** added `termContainsHandleResultArith` (walks for a handle-result-val `Term.Name` used as an operand of an arithmetic/comparison `ApplyInfix`) and wired it into `termNeedsCustomEmit`; the existing `emitExprDeep` `ApplyInfix` → `_binOp` path then lowers it (nested arith re-fires the predicate via `emitCallArg`→`emitExpr`). Guard: `CrossBackendPropertyTest` effect sub-shapes 8 (`r*2+base`) and 9 (`r1+r2`), run through scala-cli on seeds 11/47 and 155/191. Property test green.

---

## interp-returnclause-effect-in-while — `fixed` (2026-06-15)

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

## jvmgen-returnclause-effect-in-recursion — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` diagnostic (return-clause shape localization).
- **Symptom:** a return-clause handler over a **recursive** effectful function fails JVM scala-cli compilation: `Found: (_t3 : Any) / Required: Int`. **interp and JS both produce the correct result.**
- **Repro:**
  ```scalascript
  effect Log:
    def emit(): Int
  def go(n: Int): Int ! Log =
    if n <= 0 then 0
    else
      Log.emit()
      go(n - 1)
  def prog(): Int ! Log =
    go(3)
  val xs = handle(prog()) {
    case Log.emit(resume) => 7 :: resume(())
    case Return(_) => List()
  }
  println(xs.length)   // interp/js: 3 ; jvm: scala-cli COMPILE ERROR
  ```
- **Root cause:** the CPS transform emits `def go(n: Int): Any = _bind(..., (_t3: Any) => go(_t3))` — the recursive call passes the Any-typed `_bind` continuation result `_t3` to `go`, whose param stays declared `Int`. The existing `applyCalleeCasts` (which casts CPS call args to the callee's declared param types) only consulted `depDefs`/`depClasses` (IMPORTED deps), never the user module's own defs, so a recursive/sibling call got no cast. (Widening the param to `Any` is NOT a valid fix — params keep their declared type so field access like `node.nodes` type-checks; the design casts at call sites instead.)
- **FIXED (2026-06-15):** added `localDefSigs` — a pre-pass index of the user module's own `Defn.Def`s — and made `applyCalleeCasts` / `calleeParamType` / `calleeTypeArgMap` consult it as a fallback after `depDefs`. `go(_t3)` now emits `go(_t3.asInstanceOf[Int])`. Guard: `CrossBackendPropertyTest` "effect return-clause cross-backend (direct / recursion)" (interp == JS == JVM). 120 effect/CPS unit tests stay green.

---

## jvmgen-multishot-handle-result-any — `fixed` (2026-06-15, 23a33c976)

- **Found by:** `CrossBackendPropertyTest` (its multi-shot effect shape).
- **Symptom:** a method call on the result of `handle(...)` fails JVM scala-cli with e.g. `value sum is not a member of Any` — `handle(...)` lowers to `_handle(...)` which returns `Any`, so `val all = handle(prog()){…}; all.sum` (typical for a multi-shot handler whose result is a `List`) doesn't type-check. interp + JS (dynamically typed) run it fine.
- **Repro:** a `multi effect NonDet` program ending `val all = handle(prog()){ case NonDet.choose(opts, resume) => opts.flatMap(o => resume(o)) }; println(all.sum)`.
- **Severity / why deferred:** harder than the emitCaseBody class — it is about the `_handle` RESULT type (Any), not the handler body. A real fix needs the codegen to know the handled-program's result type (here `List[Int]`) and cast, or `_handle` to be generically typed; `List[Any].sum` would still need `Numeric[Any]`. Not a localized fix. The property test excludes the multi-shot-result-method-call shape (uses a block-handler one-shot instead) so it stays green; re-add it as the regression check when fixed. - **FIXED (23a33c976):** runtime `_anyCall0(recv, m)` dynamically dispatches 0-arg collection methods on an Any Iterable (numeric folds via `_binOp`); codegen tracks vals bound to `handle(...)` (`handleResultVals`), routes a `x.method` on them through `emitExprDeep` (via `termContainsHandleResultCall` in `termNeedsCustomEmit`) → `_anyCall0`. Property test re-added the multi-shot `all.{sum,max,min,length}` shape as the guard; 96 tests green.
**Status:** open. SHA at filing: 2088ce52e.
---

## jvmgen-effect-handler-arg-arith — `fixed` (2026-06-15, 7c843b121)

- **Found by:** `CrossBackendPropertyTest` (its broadened multi-arg / arithmetic effect handlers).
- **Symptom:** a handler that does arithmetic on op-args, e.g. `case Combine.mix(a, b, resume) =>
  resume(a * b + 1)`, fails JVM scala-cli with `value * is not a member of Any`. The op-args are
  bound `val a = _args(0)` (type `Any`) and `emitCaseBody` had no arithmetic case → `a * b`
  emitted raw, which Scala 3 rejects on `Any`. interp + JS run it fine.
- **Repro:** `println(handle(loop(5)) { case Combine.mix(a, b, resume) => resume(a * b + 1) })`
  for `effect Combine: def mix(a: Int, b: Int): Int` → scala-cli "value * is not a member of Any".
- **FIXED (7c843b121):** `emitCaseBody` now lowers an arithmetic/comparison `ApplyInfix` to the
  `_binOp("op", l, r)` runtime helper (same as `emitExpr` for Any operands; mirrors the existing
  `::` Any-cast case). Guard: `CrossBackendPropertyTest` effect shapes (arg-carrying / two-op) run
  through scala-cli. 101 effect+jvmgen tests green.
- **ALSO FIXED (78d1ce178) — control-flow case:** an `if` in a handler body with a comparison on Any-typed op-args (`if k > 2 then resume(k) else resume(0)`) — `emitCaseBody` had no `Term.If` case so `k > 2` emitted raw. Added a `Term.If` case that recurses (lowers `k > 2` to `_binOp`) + casts the condition to `Boolean`. Property test gained a conditional-resume effect shape (run through scala-cli).

---

## jvmgen-handle-in-arg-position — `fixed` (2026-06-15, 91fc574f5)

- **Found by:** `CrossBackendPropertyTest` (xbackend-property-equivalence — the generated
  cross-backend differential, found this on its first effects run).
- **Symptom:** JVM codegen emits a `handle(...)` effect expression RAW (unqualified) when it
  appears in **call-argument position**, e.g. `println(handle(body){cases})`, so scala-cli fails
  with `Not found: handle - did you mean _handle?`. interp **and** JS run it correctly.
- **Works (idiomatic):** binding the result first — `val r = handle(body){cases}; println(r)` —
  lowers correctly to `_handle(() => body, Set(...), Map(...))`. Only the inline/nested form breaks.
- **Repro (minimal):**
  ```scalascript
  effect Counter:
    def tick(): Int
  def loop(n: Int): Int ! Counter =
    var acc = 0
    var i = 0
    while i < n do
      acc = acc + Counter.tick()
      i = i + 1
    acc
  println(handle(loop(3)) { case Counter.tick(resume) => resume(2) })
  ```
  `ssc emit-jvm` / scala-cli the output → "Not found: handle". Change last line to
  `val r = handle(loop(3)) { ... }
println(r)` → works.
- **Root cause:** `JvmGen` lowers `handle` via `emitExpr` (case `handle(body){cases}` →
  `emitHandleForm`) and special-cases the `val x = handle(...)` / statement forms, but an
  effectful term nested inside another `Term.Apply` arg falls to the `.syntax` raw fallback
  instead of recursing the arg through `emitExpr`/`emitHandleForm`. (Likely the same for other
  effectful forms — `runAsync`, etc. — as direct call args.)
- **Severity:** low — narrow corner case, trivial workaround (bind to a `val`). Fix touches the
  core CPS emission path (would need care vs the 33 JvmGenEffects tests), so deferred from the
  property-test slice that found it.
- **FIXED (91fc574f5):** `termContainsEffectExpr` (walks children for any effectful sub-expr) added to `termNeedsCustomEmit` so a `handle`/effect nested in a call arg routes through `emitExprDeep` and lowers to `_handle(...)`. Regression guard: `CrossBackendPropertyTest` effect kind uses the inline `println(handle(...))` form (interp==JS==JVM via scala-cli). 119 effect+jvmgen tests green, no regression.
- **Status:** open. SHA at filing: 4b21d527b. The property test excludes the inline form (uses the
  bound form) so it stays green; re-add the inline form to that generator as the regression check
  when this is fixed.

---

## interp-import-cycle-stackoverflow — `fixed` (2026-06-14)

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

## interp-cons-in-effect-handler — `fixed` (example) (2026-06-13, `721ee62b9`)

- **FINAL diagnosis (two earlier mis-diagnoses corrected):** NOT a `::` bug and NOT a
  "resume result not forced to ListV" bug. `resume(())` **correctly** returns the
  continuation's pure result `()` (Unit); `println(rest)` after `val rest = resume(())`
  prints `()`. The `algebraic-effects.ssc` Logger handler did `msg :: resume(())`, i.e.
  `msg :: ()` → "No method '::' on StringV" — it assumed `resume(())` of the final
  continuation would be `Nil`. That is the **deep-handler list-accumulation** pattern
  (Koka/Eff `return x => []`), which needs a handler **return clause**. ScalaScript's
  `handle` has **no return clause** (the spec's own Logger example just does `resume(())`,
  returning Unit), so the pattern is unsupported. **Example bug, not an interp bug.**
- **Fixed:** rewrote the Logger section to a working accumulator (append each msg + resume)
  producing the same `List(Hello, World!)`, with a comment on the return-clause gap.
  Also corrected the State section (stdlib `State` + `set`, dropped a broken parameterized
  redecl — see `interp-parameterized-effect-decl`).
- **Underlying language gap (future feature, not filed as a bug):** a handler **return
  clause** would make `msg :: resume(())` work (the spec types `resume` as returning the
  *handler body's* type, which requires bridging the pure/base case). Large feature
  (parser + typer + interp + 4 backends) — out of scope; noted in BACKLOG.

## interp-parameterized-effect-decl — `fixed` (2026-06-13, `2a818e45c`)

- **Fixed:** `Parser.effectLinePat` (the regex that rewrites `effect Name:` →
  `object Name { … }`) had no type-param clause after the name, so `effect State[S]:` /
  `effect Box[T]:` were left un-rewritten and reached the Scala parser as a bare
  `effect Name[T]` expression → `No method 'Name' on NativeFnV(<native:effect>)`. Added an
  optional `(?:\[[^\]]*\])?` after `(\w+)` (the `object` drops the type param; op
  signatures may still mention it — the interpreter erases types). Shared `lang/core`
  Parser, so all backends benefit. Regress: `StdEffectsTest` (`effect Box[T]:` decl + handle).

## interp-effect-multishot-in-subsection — `fixed` (2026-06-13, `2a818e45c`)

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

## interp-typed-data-not-callable (a.k.a. bare-fn-ref auto-invoke) — `fixed` (2026-06-13, `175c01d72`)

- **Root cause (narrowed):** NOT a rare typed-data construct — it was the common
  `xs.foreach(println)` idiom. Normalize rewrote **every** bare `println` → `Console.println`
  (a `Select` to an InstanceV native-fn field); the interpreter evaluates a bare member `a.b`
  as a 0-arg field access, so `Console.println` was auto-invoked → `()` → `Not callable: ()`.
  Minimal repro: `List("a","b").foreach(println)` and `val f = println; f("x")`.
- **Fixed:** Normalize now rewrites `println`/`print` to `Console.*` **only when applied**
  (a `(?=\s*\()` lookahead). A bare reference stays the plain name → every backend binds it
  to the intrinsic function value (interp globals, JVM Predef, JS `_println`, Rust intrinsic
  table). Surgical: only `println`/`print`, so paren-less 0-arg method calls like
  `gen.zipWithIndex` are untouched (an earlier dispatch-level `bareSelect` attempt regressed
  exactly those — reverted). Regress: `BugReproTest` (foreach(println), val-bound println,
  explicit `println()`/`println(x)`, `nanoTime()`); `examples/typed-data.ssc` runs end-to-end
  and is now in `ExamplesSmokeTest`'s curated run-set (which goes through Normalize); Rust +
  JS codegen + interp suites green.

## js-self-handling-cps-fn-not-run — `fixed` (2026-06-12)

- **Fixed:** `JsGen.runIfEffectful` wraps a non-CPS-context call to an effectful
  function in `_run`, so a self-handling CPS fn's lazy `_FlatMap` resolves at the
  value boundary (`println(workload())`). `_run` is idempotent on an already-resolved
  plain value (so a direct-runner result like `_handleOneShot(…)` is unaffected) and
  throws loudly on an unhandled effect; CPS-context calls go through `genCpsApply`,
  never `genApply`, so they're untouched. Verified via node: non-loop self-handling →
  3, multi-shot handled-in-while → 204, one-shot regression → 5. backendInterpreter 1678
  green. Regress: `JsEffectLoopTest` (self-handling + multi-shot). **effect-multishot now
  runs on JS.** Diagnosis below.
- **Found:** while landing `effect-cps-loops-js` (the perform-in-while lowering).
- **Symptom:** on the **JS backend only**, a function that handles its OWN effects
  internally (so it has no unresolved `perform`) but is still CPS-emitted (because its
  body contains `handle`/effect machinery) returns an **un-run lazy `_FlatMap`**. A
  value-position call to it (`println(workload())`) prints `[object Object]` instead of
  the result. Blocks the `effect-multishot` corpus on JS (and any self-handling block).
- **Repro (JS only; jvm + interp are correct):**
  ```scalascript
  multi effect NonDet:
    def choose(options: List[Int]): Int
  def program(): Int ! NonDet =
    val a = NonDet.choose(List(1, 2, 3))
    a
  def workload(): Int =
    val all = handle(program()) { case NonDet.choose(opts, resume) => opts.flatMap(opt => resume(opt)) }
    all.length
  println(workload())   // JS: prints [object Object]; expected 3
  ```
  Note: NO `while` needed — this is **not** a perform-in-loop bug; the `while` fix is
  orthogonal. `effect-oneshot` (where `workload` is a *direct* `handle(...)` → a runner
  call → plain value) works on JS.
- **Root cause:** JS `_bind(c, f)` is **always lazy** (`return new _FlatMap(c, f)`),
  unlike JVM's `_bind` which is eager on a non-`Perform` value. A CPS'd self-handling
  function's chain has no `Perform` nodes, so on JVM it eager-resolves to a plain value,
  but on JS it stays a lazy `_FlatMap` that nothing runs at the (non-CPS) call site.
- **Verified fix hypothesis:** wrapping the value-position call in `_run` resolves it
  (`_run(workload())` → 3 / 12 / 204). The fix is to emit `_run(...)` at a non-CPS value
  boundary for a call whose result is a CPS'd (effectful) function — `_run` is idempotent
  on plain values, so it's safe for the direct-runner case too. Needs care in `genApply`
  to avoid wrapping calls that are themselves inside a CPS context (those go through
  `genCpsApply`). HIGH-ish risk — gate on the full effect suite + node tests.
- **Status:** open. The `effect-cps-loops-js` perform-in-while lowering landed without
  it; `effect-multishot` stays `n/a` on JS until this is fixed.

## interp-module-loader-dedup — `done` (busi confirmed, rozum seq-137)

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
