# Value unification — collapse `interpreter.Value` and `backend.spi.SpiValue` into one data type

Status: **IN PROGRESS** (Slices 1-2 landed + Track-A Char/Vector + Slice-3 spike/decision done, 2026-06-23).
Task `core-min-value-unification` (SPRINT, roadmap A).

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

```
  module: value-data  (NEW, at the ir/backendSpi level — depends on nothing in core)
    enum DataValue:                 // the pure-data ADT — host-neutral, no Env/Computation/Term
      IntV | DoubleV | BigIntV | DecimalV | StrV | BoolV | CharV | UnitV | NullV
      | ListV | VectorV | ArrayV | LazyListV | OptionV | TupleV | MapV | SetV
      | InstanceV(typeName, fields)   // user records — pure data
      | Foreign(typeName, handle: Any) | MarkupV(doc) | DocV(parts)
    // pure-data intern pools (intV/charV/someV/singletons) live here

  module: backendSpi
    type SpiValue = DataValue        // SpiValue BECOMES DataValue (alias); the 9 effect plugins
                                     // keep matching the same case names. (SPI version note below.)

  module: core (interpreter)
    // Value = the data cases (re-exported from DataValue) PLUS the runtime carriers:
    sealed trait Value
    object Value:
      export DataValue.*             // IntV…MarkupV are Value cases via re-export/aliases
      case class FunV(... closure: Env, body: Term ...)   extends Value   // runtime carrier — stays
      case class NativeFnV(name, f: List[Value] => Computation) extends Value  // runtime carrier — stays
    // Computation, Env, FrameMap, signals live in their own files (Computation.scala, Env.scala)
```

The exact mechanism for "DataValue cases are also Value cases" (Scala 3 `export`, a union `type Value =
DataValue | FunV | NativeFnV`, or wrapping) is decided in Slice 3 — it is the load-bearing choice and gets
its own spike. The carriers (`FunV`/`NativeFnV`) are **not** part of the shared data ADT; at the SPI boundary
they remain `Opaque` (a closure is not host-neutral data), so `SpiValue`/`DataValue` never reference
`Env`/`Computation`/`Term`. This is what makes the low module possible.

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

- **Slice 4 — create the `value-data` module** with `DataValue` containing the *first* pure-data case
  (e.g. `IntV`) plus its pool, re-exported into `Value` via the Slice-3 mechanism. Prove the bridge end to
  end (4387 sites compile; suite green) on ONE case before migrating the rest.

- **Slices 5…N — migrate the remaining pure-data cases** case-by-case (or in small batches) into `DataValue`,
  each batch green. Move the matching pool with each case.

- **Slice N+1 — `type SpiValue = DataValue`** in `backendSpi`; delete `valueToSpi`/`spiToValue` (they become
  identity / no-ops). Update the 9 effect plugins if any case-name qualifier changed. SPI version bump.

- **Slice N+2 — cleanup.** Remove the (now-dead) conversion call sites and the `Opaque`-for-data fallback.

**Gate at every slice:** `backendInterpreter` full suite green + plugin-tests green; no `Env`/`Computation`/
`Term` reachable from the `value-data` module; the 4387 `Value.<Case>` sites compile unchanged.

## 5. Non-goals / explicitly out of scope

- The runtime carriers (`FunV`/`NativeFnV`) are NOT unified into the host-neutral data type — a closure is
  not host-neutral. They stay interpreter-private and cross the SPI as `Opaque`.
- No perf regression: every intern pool / `fieldsArr` JIT path is preserved (moved, not dropped).
- Other backends (JVM/JS/Rust/WASM) have their own value reprs and are untouched — this is interp+SPI only.

## 6. Verification

- Per-slice: `sbt backendInterpreter/test` + `sbt backendInterpreterPluginTests/test` green.
- Final: `valueToSpi`/`spiToValue` deleted; `grep` finds no `Env`/`Computation` reachable from `value-data`;
  full cross-backend property test unaffected (interp-vs-codegen — orthogonal).
