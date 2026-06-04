# InstanceV positional array fields — spec

Direction B of the interpreter perf roadmap
(`~/.claude/plans/noble-discovering-knuth.md`). The biggest single
remaining bench gap (`recursiveEval` 13 ms, `recursiveEvalMixed`
13.6 ms) is dominated by `Map[String, Value]` field lookups inside
match arm bindings and direct field access. JFR-2026-06-02 confirmed
`Map$Map1`/`Map$Map2` together account for ~12% of `recursiveEval`'s
allocation. `interp.typeFieldOrder` already pre-builds the field-name
→ index map at type registration — the infrastructure for positional
reads exists; only the storage representation is missing.

## Goal

Replace `Value.InstanceV`'s `fields: Map[String, Value]` with a
positional `Array[Value]` indexed by the type's field order. All hot
match-arm and dispatch sites read by index; cold sites
(serialization, debug, REPL print) can continue via a lazy Map
synthesised on demand.

**Expected wins** (projected per JFR + benchmark theory):
- `recursiveEval` 13 → ~5-7 ms (2-2.5×)
- `recursiveEvalMixed` 13.6 → ~5-7 ms (2-2.5×)
- `instanceFieldAccess` 15.6 → ~8 ms (~2×) — knock-on from match-arm
  field reads
- `patternMatchHeavy` / `Set` / `Wide` — modest improvements where
  match arms read fields

## Non-goals

- Changing the public `Value.InstanceV(...)` API. Construction sites
  continue using the same constructor; the array is populated
  internally from the registered type's field order.
- Wire-format changes to `ValueSerializer`. The wire `inst` tag will
  continue carrying field names + values until the flag flip phase.
- Direct-style eval (Direction C). Out of scope.

## Phase ordering

Per `feedback-cross-module-commit-safety.md`, this is a 5-sub-item
project. Each sub-item is one or more focused commits with full
test+bench verification.

### Phase 1 — Infrastructure (single commit)

**File**: `lang/core/src/main/scala/scalascript/interpreter/Value.scala`

Changes:
1. Add `fieldsArr: Array[Value] | Null` to `InstanceV`. Default value
   `null` so existing construction sites that don't provide an array
   stay on the Map path.
2. Add capability flag `SSC_INSTANCEV_ARRAY` (default OFF) read into
   `Value.instanceVArrayEnabled` via the same pattern as
   `FastTier.enabled` (env var + `-D` system property).
3. Add helper `def fieldArrAt(idx: Int): Value` that reads from
   `fieldsArr` when non-null, else falls back to
   `fields.apply(typeFieldOrder(idx))` — a transparent shim for
   consumers during the migration.

No consumers touched. Tests stay green.

**Verify**:
- `cd <worktree> && sbt "backendInterpreter/test"` → 1205 green.
- `SSC_INSTANCEV_ARRAY=on sbt "backendInterpreter/test"` → 1205 green.
- No bench regression (the field isn't read yet).

**WORK_QUEUE item**: `phase-d-instancev-array-repr-infra`.

### Phase 2a — PatternRuntime integration (single commit)

**File**: `runtime/backend/interpreter/src/main/scala/scalascript/interpreter/PatternRuntime.scala`

Migrate `compileCase` / `buildPatEnv` to read from `fieldsArr[idx]`
when non-null, else fall back to `fields.apply(name)`. Use
`interp.typeFieldOrder(ctorName)` to map binding positions to indices
at compile time of the pattern (NOT at every match — the index is
fixed per arm).

**Verify**:
- 1205 green default AND `SSC_INSTANCEV_ARRAY=off`.
- `scripts/bench interp recursiveEval` — at this point construction
  still populates only Map, so the array path is inactive. Bench
  should be unchanged. The win comes in Phase 3.

**WORK_QUEUE item**: `phase-d-instancev-array-repr-integration-patternruntime`.

### Phase 2b — BytecodeJit integration (single commit)

**File**: `runtime/backend/interpreter/src/main/scala/scalascript/interpreter/vm/BytecodeJit.scala`

Migrate `walkArm` to emit `inst.fieldsArr()[idx]` instead of
`inst.fields().apply(name)`. The Java code generator already has the
field index from the pattern; the change is mechanical in the emit
template.

**Verify**:
- 1205 green default AND `SSC_INSTANCEV_ARRAY=off` (the JIT bails to
  SscVm.exec when bytecode compile fails).
- `scripts/bench interp recursiveEval` — same caveat as Phase 2a;
  bench unchanged until Activation.

**WORK_QUEUE item**: `phase-d-instancev-array-repr-integration-bytecodejit`.

### Phase 2c — DispatchRuntime integration (single commit)

**File**: `runtime/backend/interpreter/src/main/scala/scalascript/interpreter/DispatchRuntime.scala`

`dispatchInstance` uses the index path for typed dispatches. Mostly
for `inst.fieldName` style direct access.

**Verify**:
- 1205 green default AND `SSC_INSTANCEV_ARRAY=off`.

**WORK_QUEUE item**: `phase-d-instancev-array-repr-integration-dispatchruntime`.

### Phase 3 — Activation (single commit, mechanical but wide)

Every `InstanceV(...)` construction site populates `fieldsArr` from
`interp.typeFieldOrder`. The 378 construction sites fall into two
categories:
- **Eval-time construction** (~most): `EvalRuntime.evalApplyGeneral`
  for `case class` constructors, builtin returns in
  `DispatchRuntime.dispatchInstance` (`Some`/`None`/`Right`/`Left`/…).
  These have access to `interp.typeFieldOrder` directly.
- **Test/fixture construction**: unit tests that build `InstanceV`
  literals. These don't need `fieldsArr` populated (the Map fallback
  shim covers them).

Strategy:
- Add a `mkInstanceV(typeName: String, namedFields: Map[String, Value])`
  factory on `Interpreter` that resolves the index order from
  `typeFieldOrder` and populates both `fields` AND `fieldsArr`.
- Migrate the few hot construction sites to use this factory.
- Leave cold sites on the `InstanceV(...)` direct constructor for
  now; they'll get `fieldsArr == null` and consumers will fall back
  to Map.

**Verify**:
- 1205 green default AND `SSC_INSTANCEV_ARRAY=off`.
- **`scripts/bench interp recursiveEval`** — A/B confirms the
  projected 2-2.5× win.
- `scripts/bench profile recursiveEval` — `Map$Map1`/`Map$Map2`
  drop out of the top-10 allocators.

**WORK_QUEUE item**: `phase-d-instancev-array-repr-activation`.

### Phase 4 — Flag flip + final pass (deferred 1-2 weeks)

After 1-2 weeks of bench stability:
1. Flip `SSC_INSTANCEV_ARRAY` default ON.
2. Run full bench suite + JFR profiles for one more week to confirm
   no regressions.
3. Drop the `fields: Map` field from `InstanceV`. Replace all
   `fields.apply(name)` calls with index lookups via `typeFieldOrder`.
4. Update `ValueSerializer`'s `inst` tag to write field values
   positionally + a one-time field-order header per type.

**Verify**:
- Full 1205-test suite green at every step.
- `ValueSerializer` round-trip tests pass with both old (Map-tagged)
  and new (array-tagged) wire formats during the transition.

**WORK_QUEUE item**: `phase-d-instancev-array-repr-flag-flip`.

## Migration matrix per consumer

| Consumer | Hot path? | Phase to migrate |
|---|---|---|
| `PatternRuntime.compileCase` | hot | 2a |
| `PatternRuntime.buildPatEnv` | hot | 2a |
| `BytecodeJit.walkArm` field emit | hot | 2b |
| `DispatchRuntime.dispatchInstance` | hot | 2c |
| `EvalRuntime` field access (`inst.x`) | hot | 2c (covered by dispatchInstance) |
| `ValueSerializer` wire format | cold | 4 (flag flip) |
| `Profiler` actor metadata extraction | cold | stays on Map |
| Test/fixture `InstanceV(...)` literals | cold | stays on Map (fieldsArr == null) |
| Debug/REPL print | cold | stays on Map |

## Risk gates

- **JFR-profile-first at the start of Phase 3** to confirm HashMap
  field reads are still the dominant lever (the spec was written
  before `phase-d-while-jit` and `phase-d-foreach-hoist`; if those
  shifted the bottleneck, re-prioritise).
- Run `sbt "backendInterpreter/test"` AND
  `SSC_INSTANCEV_ARRAY=off` at every commit.
- Any commit that changes wire format goes through a roundtrip test
  with mixed old/new instances.

## ValueSerializer wire format checklist (Phase 4 only)

When the Map field is dropped (Phase 4):
1. Bump the serializer version byte.
2. New format: `inst:<typeName:string>:<n:int>:<value_0>...<value_{n-1}>`
   where the field order is determined by `typeFieldOrder(typeName)`.
3. Old format reader: continue accepting the named-field encoding
   for backwards compat. Map field name → index via `typeFieldOrder`
   at deserialization.
4. Round-trip tests with both formats.

## Reuse from existing infrastructure

- `interp.typeFieldOrder: Map[String, List[String]]` — field-name
  ordering per type, populated at type registration.
- `Value._intVPool` / `Computation._pureIntPool` — model for
  positional caches.
- `BytecodeJit.cache` — model for compile-once memoisation of typed
  classes (extend per-type if a per-type field-index cache helps).
- `FastTier.accSlotTls` — model for capability-flag-driven hot-path
  switch.

## Related plans / specs

- `~/.claude/plans/noble-discovering-knuth.md` — parent strategic
  roadmap (Directions A+B+C).
- `specs/vm-jit-next.md` — Direction A slices reference this spec at
  slice A.4 (foreach as static Java loop).
- `docs/interpreter-perf-findings-2026-06.md` — JFR survey that
  identified the HashMap field read as the next lever.
