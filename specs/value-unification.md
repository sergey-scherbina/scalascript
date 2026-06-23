# Value unification — collapse `interpreter.Value` and `backend.spi.SpiValue` into one data type

Status: **IN PROGRESS** (Slices 1-4 landed 2026-06-23; scope = SCALARS-ONLY per Sergiy's decision — full merge
off the table, see the container/closure obstacle in §3). Task `core-min-value-unification` (SPRINT, roadmap A).

## 1. Goal

Today the runtime carries **two** value representations:

- `scalascript.interpreter.Value` (in module `core`) — the interpreter's runtime value ADT
  (`lang/core/.../interpreter/Value.scala`). A `sealed trait` with ~25 cases.
- `scalascript.backend.spi.SpiValue` (in the *lower* module `backendSpi`) — a host-neutral, closed
  10-case data ADT used at the typed plugin SPI boundary (block-form effect handlers; `specs/polyglot-libraries.md §2d`).

The interpreter converts `Value ↔ SpiValue` at the SPI boundary (`Interpreter.valueToSpi` / `spiToValue`).
The **goal** is ONE value type for the *pure-data* subset, shared across interp + SPI + host libraries
(Task B, `specs/polyglot-libraries.md §4`), deleting the conversion.

This is **explicitly a multi-week migration filed as LATER** — it is lower-priority than the keystone
effect extractions, and (per the analysis below) **no early slice reduces duplication**; the payoff
(deleting the conversion) only lands at the final merge. It is specced here so it can be executed as a
sequence of safe, independently-shippable, always-green slices rather than one big-bang change.

## 2. Why it is hard — the structural findings (probed 2026-06-23, not assumed)

1. **Scale.** `Value.<Case>` is referenced at **4387 sites across 46 files** (interpreter + core). Any change
   to the case *names* or to whether a case is matchable as a `Value` ripples to all of them. Mitigation:
   keep the case names stable and re-home them with **type/term aliases** so existing `Value.IntV(n)` /
   `case Value.IntV(n)` sites keep compiling unchanged.

2. **Circular co-definition.** `Value.scala` co-defines, in one file/module:
   - the value ADT (`Value` + 25 cases),
   - the runtime monad `Computation` (`enum Computation { Pure(value: Value); Perform; FlatMap }`),
   - the call-env machinery `type Env = Map[String, Value]` + `FrameMap*` + `MutableEnvView`,
   - runtime signals (`InterpretError`, `ScriptException`, `ReturnSignal`, `TailCall`, …).
   `Value.NativeFnV` holds `List[Value] => Computation`; `Value.FunV` holds `closure: Env` + `body: scala.meta.Term`.
   So the *pure-data* cases are textually and structurally tangled with execution machinery.

3. **The sealed-cross-module wall.** A `sealed trait`/`enum`'s cases must live in the same file. So the
   pure-data cases cannot simply "move to a lower module" while still being cases of `Value` in `core`.
   And they **cannot `extend` a core type** (e.g. a marker `DataValue extends Value`) if they are to live
   *below* `core` — that would make the low module depend on `core` (the wrong direction; `core` depends on
   `backendSpi`, not vice-versa). **A `DataValue extends Value` marker is therefore the WRONG direction.**

4. **Perf coupling.** `Value` object holds hot intern pools tied to the concrete cases: `intV`/`charV`/`someV`
   pools, `_pureIntPool` (a `Computation.Pure` pool — references `Computation`), `DoubleZero/One`, singleton
   `True/False/NoneV/EmptyList/…`. `InstanceV` carries mutable `fieldsArr`/`fieldNames`/`typeTag` for the JIT.
   Any re-home must preserve these (pools that reference `Computation` stay in `core`; pure-data pools move
   with their cases).

5. **The conversion is *mostly* lossless — with two real gaps.** `valueToSpi` round-trips most cases via
   `SpiValue.Opaque(theValue)` (identity passthrough), BUT it *coerced* two pure-data cases: `Char`→`StrV`
   and `Vector`→`ListV` (so a `Char`/`Vector` crossing into a plugin handler and back changed type). The
   **SpiValue-completion track** (sibling, 2026-06-23) fixed this by adding `SpiValue.CharV`/`VectorV` and
   making the conversion faithful — see CHANGELOG "lossless `Char`/`Vector` across the SPI boundary". This
   IS load-bearing for the end-state ("the data ADT *is* `SpiValue`" requires `SpiValue` to faithfully cover
   every immutable data case), so it is a legitimate complementary track — not make-work. The general
   principle still holds: **slices must move/complete real structure, not duplicate it**; mutable `Array` and
   case instances correctly stay `Opaque` (ref-identity).

## 3. End-state architecture (decision)

**DECISION (Sergiy, 2026-06-23): SCALARS-ONLY partial unification.** Full unification (delete the conversion)
is NOT pursued — see the container/closure obstacle below. Only the **scalar leaf** cases are shared.

```
  module: value-data  (target — at the ir/backendSpi level; for now DataValue lives in core)
    enum DataValue:                 // the pure-data SCALAR LEAVES only — no Env/Computation/Term, no recursion
      IntV | DoubleV | BigIntV | DecimalV | StringV | BoolV | CharV | UnitV | NullV

  module: backendSpi
    // SpiValue's scalar cases become aliases of DataValue's → the scalar half of the conversion is identity.
    // SpiValue's CONTAINER cases (ListV/TupleV/OptV/MapV/VectorV) + Opaque stay distinct (see obstacle).

  module: core (interpreter)
    sealed trait ValueRest                       // containers + instances + runtime carriers
    type Value = DataValue | ValueRest           // union — DataValue <: Value (union member)
    object Value:
      export DataValue.*                          // so `Value.IntV(n)` / `case Value.IntV(n)` are unchanged
      case class ListV(items: List[Value]) extends ValueRest    // container — holds ANY Value incl. closures
      case class FunV(... closure: Env, body: Term ...) extends ValueRest   // carrier
      … (VectorV/ArrayV/MapV/SetV/OptionV/TupleV/DocV/InstanceV/MarkupV/Foreign/NativeFnV) …
      // intern pools (intV/charV/someV/singletons) + show() + smart ctors stay in object Value (core sees DataValue)
    // Computation, Env, FrameMap, signals live in Computation.scala / Env.scala
```

**THE CONTAINER/CLOSURE OBSTACLE (why full unification is off the table).** The interpreter stores arbitrary
values — *including closures* — inside containers: `List(() => 10)` is a `ListV(List(FunV(...)))` (real:
`AsyncTest` `Async.parallel(List(() => 10, …))`). So `ListV` must hold `List[Value]` where `Value` includes the
runtime carriers. For the data ADT to live *below* core and *be* `SpiValue`, its containers would have to be
self-contained (`List[DataValue]`) — which can't hold a closure. The only way to reconcile is to represent
closures as an `Opaque(handle)` case *inside* the data ADT, which forces a cast/unwrap on the **hot
function-dispatch path** (every call) + rewrites dozens of `case FunV` sites — a perf regression Sergiy
declined. Therefore: **containers + carriers stay in core `ValueRest`; only the scalar leaves are shared; the
`Value ↔ SpiValue` conversion shrinks (scalars become identity) but is NOT deleted.** This confirms, with a
concrete mechanism, the original "separate by necessity" rationale for `SpiValue`.

## 4. Slice sequence (each independently shippable + full-interp-suite-green)

Two tracks run independently until they meet at the merge: **Track A — SpiValue completion** (grow `SpiValue`
to faithfully cover every immutable `Value` data case; first step = `Char`/`Vector` lossless, DONE) and
**Track B — disentangle `Value.scala`** (the slices below). They converge at the `type SpiValue = DataValue`
step, where `DataValue` must equal the completed `SpiValue`.

- **Slice 1 — physical seam (DONE 2026-06-23).** Move `Computation` (the free monad + its companion +
  `pureFn`) and the runtime signal classes out of `Value.scala` into `Computation.scala` (same package, same
  module). Zero behavior change; `Value.scala` is left holding the value ADT + `Env`/`FrameMap`. This is the
  first textual decoupling of *data* from the *runtime monad*. Verify: full interpreter suite green.

- **Slice 2 — move `Env`/`FrameMap`/`MutableEnvView` into `Env.scala`** (same module). After this,
  `Value.scala` holds only the value ADT + its pools. Still zero behavior change.

- **Slice 3 (SPIKE + decision) — DONE 2026-06-23. DECISION: union `Value` + `export`.** A throwaway
  scala-cli spike validated the load-bearing mechanism:
  ```scala
  enum DataValue:                              // lives in the LOW module — extends nothing from core
    case IntV(v: Long); case StrV(v: String); case ListV(items: List[DataValue]); …
  sealed trait Callable                        // the runtime carriers (core)
  final case class FunV(…) extends Callable
  final case class NativeFnV(…) extends Callable
  type Value = DataValue | Callable            // union — DataValue <: Value (union member)
  object Value:
    export DataValue.*                          // so `Value.IntV(n)` construct + `case Value.IntV(n)` keep working
  ```
  Validated (all compile + run, scala-cli Scala 3.8.3): (1) `val a: Value = Value.IntV(3)` — construction via the
  existing `Value.IntV` syntax works (DataValue is a union member, so `DataValue <: Value`); (2)
  `case Value.IntV(n)` patterns match a union-typed scrutinee alongside carrier patterns `case f: FunV`;
  (3) **exhaustiveness checking is PRESERVED** — a non-exhaustive `match` on `Value` is flagged
  (`[E029] match may not be exhaustive`, an error under `-Werror`). So the 4387 `Value.<Case>` sites should
  survive the migration largely unchanged, and the data ADT can live in a module *below* core. **Rejected
  alternatives:** a `DataValue extends Value` marker (wrong dependency direction — §2.3); a bare union without
  `export` (would force `DataValue.IntV` at all 4387 sites). **Open detail for Slice 4+:** `InstanceV` carries
  mutable `fieldsArr`/`fieldNames`/`typeTag` (JIT) — decide whether it joins `DataValue` (host-neutral records)
  or stays a core carrier; the intern pools that reference `Computation.Pure` (`_pureIntPool`) stay in core,
  the pure-data pools (`intV`/`charV`/`someV`) move down with their cases.

- **Slice 4 — union flip, scalars to `DataValue` (DONE 2026-06-23).** Created `DataValue.scala` =
  `enum DataValue` with the 9 scalar leaves (`IntV/DoubleV/BigIntV/DecimalV/StringV/BoolV/CharV/UnitV/NullV`,
  exact Value names); flipped `Value.scala` to `sealed trait ValueRest` (the 14 container/instance/carrier
  cases) + `type Value = DataValue | ValueRest` + `export DataValue.*` in `object Value` (pools/`show`/smart
  ctors unchanged). `DataValue` lives in core *for now* (Slice 5 moves it to a low module). **Result:
  astonishingly clean** — across the ~4387 `Value.<Case>` sites the ONLY friction was a single
  `java.util.Arrays.sort(arr: Array[Value], …)` in `DispatchRuntime` (Java-generic inference can't bound a
  union array → cast to `Array[AnyRef]`). Everything else compiled unchanged via `export`. Verified: core +
  backendInterpreter + all plugins + interpreter-server + dap compile; **core/test 1019/0, plugin-tests
  712/0, broad interp/value/effects 218/0, numeric/collection/JIT 77/0** (~2026 green, 0 fail).

- **Slice 5 — move `DataValue` to a low `value-data` module** (file move + build wiring; it extends nothing
  core, so this is mechanical) so `backendSpi` can see it.

- **Slice 6 — alias `SpiValue`'s scalar cases to `DataValue`** so the *scalar* half of `valueToSpi`/`spiToValue`
  becomes identity (no realloc/rewrap). Container cases of the conversion stay (the obstacle). SPI version note.

**Gate at every slice:** `backendInterpreter` + plugin-tests green; the `Value.<Case>` sites compile unchanged.

## 5. Non-goals / explicitly out of scope

- The runtime carriers (`FunV`/`NativeFnV`) are NOT unified into the host-neutral data type — a closure is
  not host-neutral. They stay interpreter-private and cross the SPI as `Opaque`.
- No perf regression: every intern pool / `fieldsArr` JIT path is preserved (moved, not dropped).
- Other backends (JVM/JS/Rust/WASM) have their own value reprs and are untouched — this is interp+SPI only.

## 6. Verification

- Per-slice: `sbt backendInterpreter/test` + `sbt backendInterpreterPluginTests/test` green.
- Final: `valueToSpi`/`spiToValue` deleted; `grep` finds no `Env`/`Computation` reachable from `value-data`;
  full cross-backend property test unaffected (interp-vs-codegen — orthogonal).
