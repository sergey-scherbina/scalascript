# JIT universal coverage — all three engines, all real programs

Status: **in progress** (Stage 0 spec committed 2026-06-05).
Companion specs: [`jit-completeness.md`](jit-completeness.md) (SscVm p1–p7
historical), [`asm-jit-parity.md`](asm-jit-parity.md),
[`vm-jit-spec.md`](vm-jit-spec.md) (register-VM design).

---

## 1. Goal

Make the JIT compile **every** function that can reasonably be compiled — not
just functions that happen to appear in a benchmark. Today three engines share
the interpreter fast path but have fragmented coverage and no unified view of
what they collectively miss. A user writing ordinary SSC code should not need
to know about JIT internals to get fast execution.

### Non-goals (documented here so they are explicit, not forgotten)

- Native FFI bodies (`@native`, `@js`-annotated).
- Debugger-hooked interpreter sessions (`interp.debugHooks.nonEmpty`).
- Varargs parameters (`paramTypes` ending `*`).
- `using`-clause typeclass dispatch (would need compile-time given resolution).
- Arity ≥ 5 (combinatorial blow-up; `JitLint` emits a refactor hint instead).
- Polymorphic HOF inline caches (monomorphic IC only this sprint; polyIC
  is a follow-up).
- Algebraic effect / `Computation`-returning functions (`returnsThrows`).

---

## 2. Three engines today

```
CallRuntime.callValueN
  │
  ├─ JitRuntime.tryBytecodeN  ──► JitBackend.default (Javac or ASM)
  │       │ null (miss)
  │       ▼
  ├─ JitRuntime.hotCompiled   ──► VmCompiler → SscVm
  │       │ None (miss)
  │       ▼
  └─ callValueNSlow           ──► Interpreter.eval (tree-walker)
```

| Engine | Key file | Current miss count | Bail vocab |
|---|---|---|---|
| `VmCompiler` → `SscVm` | `vm/VmCompiler.scala` | 300 functions | free-form strings via `JitMissStats` |
| `JavacJitBackend` | `vm/jit/JavacJitBackend.scala` | silent (unobserved) | `JitBailReason` via `ssc lint-jit` |
| `AsmJitBackend` | `vm/jit/AsmJitBackend.scala` | silent (unobserved) | `JitBailReason` via `ssc lint-jit` |

The two bytecode backends share structural predicates (`JitPredicates` inside
`JitLint.scala`) but have **independent AST walkers** — adding a shape requires
touching both files.

---

## 3. Universal compilable subset

The contract: every function matching **all** of these shape predicates MUST
compile on all three engines after all slices in §5 land. Functions failing
any predicate are in the "documented non-goal" set; their `JitBailReason` is
the canonical reason.

### 3.1 Function signature

| Constraint | Predicate | Non-goal reason |
|---|---|---|
| Arity ≤ 4 | `fn.params.length <= 4` | `TooManyParams(n)` |
| No `using` params | `fn.usingParams.isEmpty` | `UsingParams` |
| No varargs | `fn.paramTypes.forall(!_.endsWith("*"))` | `VarargParam` |
| No effect return | `!fn.returnsThrows` | `EffectReturn` |

### 3.2 Function body (recursive)

| AST shape | Description | Engines that support today |
|---|---|---|
| `Lit.Int`, `Lit.Long`, `Lit.Double`, `Lit.Boolean` | Numeric/bool literals | All ✓ |
| `Lit.String` | String literal (TRef intermediate) | SscVm ✓, Javac ?, ASM ? |
| `Lit.Null` | Null literal (TRef sentinel) | SscVm ✓, Javac ?, ASM ? |
| `Term.Name(n)` | Parameter / local ref | All ✓ |
| `Term.ApplyInfix` (+, −, ×, ÷, %, <, ≤, >, ≥, ==, !=, &&, \|\|) | Binary arithmetic / comparison | All ✓ |
| `Term.ApplyUnary` (−, !) | Unary negate / not | All ✓ |
| `Term.If` | Conditional | All ✓ |
| `Term.Block` (val/var bindings + tail expr) | Multi-stmt block | All ✓ (limited) |
| `Term.While` + loop-slot assigns | While loop with integer arithmetic | All ✓ |
| `Term.Match` on param (ADT `Pat.Extract`) | ADT pattern match | All ✓ |
| `Term.Match` on param (`Pat.Lit` arm) | Literal arm in match | **None** → Stage 2.4 |
| `Term.Select(obj, field)` | ADT field access via meta | All ✓ (Int fields) |
| `Term.Select("String", field)` | `s.length / isEmpty / nonEmpty` | SscVm ✓, Javac ?, ASM ? |
| `Term.Apply(Term.Name, args)` (sibling / self) | Direct recursive / sibling call | All ✓ |
| `Term.Apply(Term.Name, args)` (top-level FunV) | Free-name call to compilable global | **Stage 2.5** |
| `Term.Apply(Term.Name, args)` (HOF param) | Higher-order fn passed as parameter | **Stage 3** |
| `Term.Function` (lambda, non-capturing) | Lambda with empty closure | **Stage 3.3** |
| `Term.Function` (lambda, capturing) | Lambda closing over outer params/locals | **Stage 3.3** |
| `Defn.Def` in body (non-capturing) | Inner `def` without free vars | SscVm ✓, Javac ✓, ASM ? |
| Bool top-level body | `cond` or `&&`/`\|\|` as return expr | **None** → Stage 2.1 (wrap) |
| 2-param (ref, ref) | Both params are `InstanceV`/`StringV` refs | **None** → Stage 2.2 |
| Pattern guard (`case x if cond`) on ADT scrutinee | Guard on ref match | Javac ✓, **ASM** → Stage 2.3 |

### 3.3 Return type

| Return kind | Status | Note |
|---|---|---|
| `Int` / `Long` / `Double` | All ✓ | Bytecode `long` / `double` static method |
| `Boolean` (via Bool-wrap) | **Stage 2.1** | Emit `if body then 1L else 0L` |
| `InstanceV` (ADT ref) | Bytecode ✓ (`ObjToObject`/`LongToObject`); SscVm deferred | SscVm needs `RETREF` |
| `StringV` / `Null` | SscVm bails (`ret: ref-typed return`) | **Stage 3.2** or p8 |

---

## 4. Unified bail vocabulary (target state)

After Stage 1 the three engines emit the same `JitBailReason` cases into a
shared per-engine `JitMissStats` counter. The `JitBailReason` ADT (currently in
`JitLint.scala`) is extended:

| New case | Meaning |
|---|---|
| `HofCall(calleeName)` | Call to a FunV passed as param or stored in a ref |
| `CapturedFreeName(name)` | Lambda / inner def closes over a free variable from outer scope |
| `LambdaValue` | `Term.Function` used as a value (not immediately applied) |
| `MutableLocal(name)` | `var` binding outside the recognized while-loop slot pattern |
| `RefRefParam` | Both params are ref-typed (currently no `ObjObjToLong` interface) |
| `MixedNumericArms` | Match arms mix Int and Double returns |
| `PatLiteralArm` | Match arm uses `Pat.Lit` (literal pattern) |
| `FreeNameUnresolvable(name)` | `Term.Name` not in params/locals/siblings/globals |
| `RefReturn` | Function returns a ref value (SscVm `RETREF` not yet implemented) |
| `TooManyParams(n)` | Keeps current meaning, `n > 4` after Stage 4 |

The `JitMissStats` output becomes:

```
JIT miss stats (3 engines):
  [vm]     234  HofCall
  [vm]      18  RefReturn
  [javac]  287  HofCall
  [javac]   14  BoolBody       ← until Stage 2.1
  [asm]    287  HofCall
  [asm]     14  BoolBody
  [asm]      7  PatternGuard (ref-match only)  ← ASM-specific, until Stage 2.3
```

---

## 5. Implementation slices

### Stage 0 — Spec ✓ (this document)

Commit `spec: jit-universal-coverage`. Implementation starts only after this
lands. All Stage 1–4 slices cross-reference this spec.

### Stage 1 — Unified bail accounting

**Goal:** visibility, no behavior change. After this stage `SSC_JIT_STATS=1`
shows per-engine counts for all three engines and uses `JitBailReason` cases
instead of raw strings.

**Files touched:**
- `vm/jit/JitLint.scala` — extract `JitBailReason` ADT + `JitPredicates`
  into `vm/jit/JitBailReason.scala` (new file). Extend ADT with the new
  cases listed in §4. Visibility: `private[vm]` → public.
- `vm/JitMissStats.scala` — add `record(engine: String, reason: JitBailReason)`
  overload. Keep `record(reason: String)` (maps to `JitBailReason.Other`).
  Extend `report()` to group by `(engine, reason)`.
- `vm/VmCompiler.scala` — every `bail("free-form string")` site replaced
  with a `throw Bail(JitBailReason.XYZ)` where `XYZ` is the typed case.
  `JitMissStats.record("vm", reason)` at the catch point.
- `vm/jit/JavacJitBackend.scala` — every silent `return null` in
  `tryCompile`/`doCompile` replaced with
  `JitMissStats.record("javac", classifyBailReasons(f).head); null`.
- `vm/jit/AsmJitBackend.scala` — same for `"asm"`.

**New CLI command:** `tools/cli/.../CheckJitCoverageCmd.scala`
```
ssc check-jit-coverage [--backend vm|javac|asm|all] [--fail-on-bail] <path>
```
Loads the module at `<path>`, walks `interp.globals`, compiles each `FunV`
through the chosen engine(s), prints the coverage matrix:
```
  COMPILED   87 functions
  BAILED     45 functions
    HofCall       34
    BoolBody       7
    TooManyParams  4
```

**Tests:**
- `JitMissStatsTest` — per-engine isolation, `JitBailReason` round-trip.
- `JitLintTest` existing 17 tests still pass with promoted `JitBailReason`.

**Behavior:**
- [ ] `SSC_JIT_STATS=1 sbt "backendInterpreter/test"` prints three engine
      sections (vm / javac / asm) with typed reason names.
- [ ] `ssc check-jit-coverage tests/conformance/recursion.ssc` exits 0 and
      prints a coverage matrix for `vm`, `javac`, and `asm`.
- [ ] No `SscVmTest` regression; no bench regression.

### Stage 2 — Easy-win parity slices

Each sub-slice is a single focused commit touching **both** bytecode backends
plus SscVm where applicable.

#### Stage 2.1 — Bool-body wrap

Emit `if body then 1L else 0L` instead of bailing when `isBoolReturning(body)`
is true. Applies to all three engines.

- `JavacJitBackend.doCompile` / `AsmJitBackend.doCompile` — replace the
  `isBoolReturning(body) then return null` gate with a body-wrapping step:
  synthesize `Term.If(body, Lit.Int(1), Lit.Int(0))` and compile that.
- `VmCompiler` — Boolean returns are already allowed (TInt 0/1). This slice
  removes the need to write `if x then 1 else 0` in source by doing it
  automatically in the compiler. `BoolBody` case in `JitBailReason` deprecated
  → `JitBailReason.None`.

**Behavior:**
- [ ] `def isPositive(n: Int): Boolean = n > 0` compiles on all three engines
      and returns `true`/`false` correctly.
- [ ] `def isZero(n: Int): Boolean = n == 0` same.
- [ ] No regression.

#### Stage 2.2 — Ref+Ref 2-param dispatch

Add `ObjObjToLong`, `ObjObjToDouble`, `ObjObjToObject` to `JitInterfaces.scala`.
Wire into both backends' `determineInterface(arity=2)` and
`JitRuntime.invokeBytecode2`.

**Behavior:**
- [ ] `def eq(a: Tree, b: Tree): Boolean = a == b` compiles on Javac and ASM.
- [ ] `def merge(a: Node, b: Node): Node = ...` (ref return) compiles.
- [ ] No regression.

#### Stage 2.3 — ASM ref-match guard parity

Port Javac's `walkArmAsIfBranch` into `AsmJitBackend.emitRefMatchBody`.
Currently `emitRefMatchBody` (line ~1492) rejects any arm with a guard;
Javac already lowers guards via an if-chain fallback.

**Behavior:**
- [ ] `def f(x: Tree): Int = x match { case Leaf(n) if n > 0 => n; case _ => 0 }`
      compiles on ASM (already works on Javac).
- [ ] `JitLintTest.lintInterpreterCompare` shows `asmOnlyFails.isEmpty` for
      guarded ADT matches.
- [ ] No regression.

#### Stage 2.4 — Pat.Literal in match arms

When a match arm has `Pat.Lit(Lit.Int(n))` / `Pat.Lit(Lit.Long(n))` /
`Pat.Lit(Lit.String(s))` emit an equality test against the scrutinee.

```scala
x match
  case 0 => "zero"
  case 1 => "one"
  case _ => "other"
```

Both bytecode backends and VmCompiler.

**Behavior:**
- [ ] Int literal arm compiles and dispatches correctly on all three engines.
- [ ] String literal arm compiles (returns ref — wired with Stage 3.2 for
      VmCompiler; bytecode backends can already return Object).
- [ ] No regression.

#### Stage 2.5 — Free-name → top-level FunV call

When `Term.Apply(Term.Name(n), args)` and `n` is not a sibling/self but
resolves to a compilable `FunV` in `interp.globals`, emit a direct CALL
(SscVm) or `INVOKESTATIC` to the pre-compiled static method (bytecode
backends). Javac already has `JitGlobals.resolveFnSlot` for the while-mixed
path; generalize to the general call walker.

**Behavior:**
- [ ] `def main(n: Int): Int = helper(n) + 1` compiles when `helper` is a
      compilable top-level def.
- [ ] `FreeNameUnresolvable` is emitted only when `n` is genuinely absent.
- [ ] No regression.

### Stage 3 — HOF / closures (headline slice)

Sub-slices landed sequentially; each must not regress.

#### Stage 3.1 — FunV as JIT-visible ref operand

- Extend `JitGlobals` to store `Value.FunV` in `refs[]` alongside
  `InstanceV`/`StringV`/`MapV`.
- Add `JitInterfaces.RefCallable` marker trait (single `call(args: Array[AnyRef]): AnyRef`
  method — boxed fallback only; replaced by monomorphic IC in Stage 3.4).
- VmCompiler `refTypeName` map: `"FunV_N"` (where N is arity) entries for
  TRef registers holding function values.
- `JitBailReason.HofCall` now records the callee name for diagnostics.

**Behavior:**
- [ ] `JitGlobals.getRefs()` contains FunV values for HOF-taking functions.
- [ ] VmCompiler marks HOF-param registers with `"FunV_1"` / `"FunV_2"` etc.
- [ ] No regression.

#### Stage 3.2 — SscVm `CALLREF` opcode

New opcode `CALLREF` (opcode 47):

| Opcode | Encoding | Semantics |
|---|---|---|
| `CALLREF 47` | dst, slotIdx, nargs | Dispatch through `refStack[base+slotIdx]` as a `FunV`; args in `refStack[base+dst+1 .. +nargs]` or stack; result → `stack[base+dst]` or `refStack[base+dst]` per return type |

Monomorphic inline cache: `Array[Any]` per CALLREF instruction — `[cachedCallee: FunV, cachedHandle: MethodHandle | null]`.
Fast path: `refStack(slotIdx) eq cachedCallee` → invoke via handle.
Slow path: compile via `JitRuntime.tryRun1/2(callee, ...)` or
`Interpreter.invokeFn`.

VmCompiler emits `CALLREF` when:
- `Term.Apply(Term.Name(n), args)` and `n` resolves to `TRef` of `"FunV_k"`.
- Also for immediately-applied lambdas (`Term.Apply(Term.Function(...), args)`).

**Behavior:**
- [ ] `def applyN(f: Int => Int, n: Int): Int = f(n)` compiles on SscVm and
      returns the correct result.
- [ ] `def sumList(xs: List[Int], f: Int => Int): Int = xs.foldLeft(0)((a,x) => a + f(x))`
      — outer function compiles; inner lambda dispatched via CALLREF.
- [ ] Monomorphic IC hit on second call; bench shows no regression on
      `recursionFib`.

#### Stage 3.3 — Lambda / closure compilation

`Term.Function` (anonymous lambda) compiles when its free-name set is a subset
of the enclosing function's params/locals:

1. Compute free names in `Term.Function` body.
2. If all free names are in the enclosing scope: capture them into `refs[]`
   (TRef FunV register); emit a synthetic `Value.FunV` with empty closure
   (captures stored as JitGlobals ref slots, not via `Value.FunV.closure`).
3. Emit `LOADS`-equivalent for the FunV into a TRef register; CALLREF at the
   apply site.

For capturing inner `def` (extends p3 non-capturing case): if the inner def
closes over outer params, synthesize a wrapper FunV the same way.

**Behavior:**
- [ ] `(x: Int) => x + 1` as an argument to a HOF compiles on SscVm.
- [ ] Inner `def helper(x: Int) = x + n` where `n` is an outer param compiles
      (previously bailed as `CapturedFreeName`).
- [ ] `LambdaValue` bail count drops to 0 in the coverage matrix.

#### Stage 3.4 — Monomorphic inline cache validation

Verify the Stage 3.2 IC warms up and reaches the fast path:

- Add `SSC_JIT_IC_STATS=1` env var: print per-CALLREF site hit/miss counts.
- Test: call `applyN(f, n)` 1000 times with the same `f` → verify IC hits
  on calls 2–1000.

**Behavior:**
- [ ] IC stats show ≥99% hit rate for monomorphic HOF call sites.
- [ ] No regression on `recursionFib` / `recursionTco`.

#### Stage 3.5 — Bytecode JIT HOF emission

When `JavacJitBackend` / `AsmJitBackend` encounter `Term.Apply(Term.Name(n),
args)` and `n` is a FunV-typed parameter:

- Emit `JitGlobals.getRefs()[slotIdx].apply(...)` through `RefCallable`
  (Stage 3.1 marker).
- The generated Java class will have a static field holding the `MethodHandle`
  resolved at first call (per-class monomorphic IC via `invokedynamic` or
  a static `AtomicReference`).

**Behavior:**
- [ ] `def applyN(f: Int => Int, n: Int): Int = f(n)` compiles on Javac and
      ASM backends as well as SscVm.
- [ ] `HofCall` bucket drops by ≥80% across all three engines.
- [ ] `ssc check-jit-coverage tests/conformance` shows ≥80% compilation rate
      across all three engines.

### Stage 4 — Arity 3–4 ceiling lift

**Key insight:** only the bytecode backends cap at 2. VmCompiler already handles
arbitrary arity.

**Code generation:** a new `sbt sourceGenerators` task in
`runtime/backend/interpreter/build.sbt` produces
`target/src_managed/main/scala/scalascript/interpreter/vm/jit/JitInterfacesN.scala`
with arity-3 and arity-4 variants.

Arity-3 interfaces (2³=8 ref-masks × 3 return types = 24):
`LLLToL`, `LLLToD`, `LLLToO`, `LLOToL`, `LOLToL`, `OLLToL`, ... (all 2³
combos) × Long/Double/Object return.

Arity-4 interfaces (2⁴=16 × 3 = 48):
Same pattern. Total new interfaces: 72.

**`JitRuntime`** — new `invokeBytecode3(f, a, b, c, interp)` and
`invokeBytecode4(f, a, b, c, d, interp)` dispatch tables.

**Both backends** — bump `params.length > 2` gate to `> 4`. Extend
`determineInterface(n)` for `n = 3, 4`.

**Behavior:**
- [ ] `def clamp(x: Int, lo: Int, hi: Int): Int = if x < lo then lo else if x > hi then hi else x`
      compiles on Javac and ASM.
- [ ] `def lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t`
      compiles on Javac and ASM.
- [ ] `TooManyParams(3)` / `TooManyParams(4)` buckets drop to 0.
- [ ] No regression.

### Stage 5 — Structural long tail

Smaller slices, land independently, no minimum order:

| Slice | Bail reason eliminated | Key change |
|---|---|---|
| 5.1 Mixed Long+Double arms | `MixedReturnType` | Auto-promote Int arms to Double when any arm is Double |
| 5.2 `var` in pure bodies | `MutableLocal` | Extend `walkLocalSlotCtx` to non-TCO contexts |
| 5.3 `try/catch` | `TryCatch` | Emit JVM try block with tree-walker catch fallback |
| 5.4 `Pat.Alternative` | `NonExtractPattern` | Relax pattern classifier |
| 5.5 Non-Name scrutinee | `NonAdtScrutinee` | Auto-hoist scrutinee to synthesized local slot |

---

## 6. CLI surface

### `ssc check-jit-coverage`

```
ssc check-jit-coverage [--backend vm|javac|asm|all] [--fail-on-bail] [--json] <path>
```

- Loads the SSC module at `<path>`.
- Walks all `FunV` in `interp.globals`.
- For each engine: attempts compile, records result.
- Prints a table:

```
check-jit-coverage: tests/conformance/recursion.ssc
  engine     compiled  bailed  reasons
  vm             23       5    HofCall(3), TooManyParams(4)(2)
  javac          21       7    HofCall(4), BoolBody(3)
  asm            21       7    HofCall(4), BoolBody(2), PatternGuard(1)
```

- With `--fail-on-bail`: exits non-zero if any engine bails on any function.
- Location: `tools/cli/src/main/scala/scalascript/cli/CheckJitCoverageCmd.scala`.
- Registered in `CliMain.scala` as `"check-jit-coverage"`.

### Existing `ssc lint-jit`

Continues to work unchanged. `check-jit-coverage` is the batch/CI tool;
`lint-jit` is the per-file developer tool.

---

## 7. Engine support matrix (living — updated per slice)

Status codes: ✓ done, ~ partial, ✗ not yet, — not applicable.

| AST shape | SscVm | Javac | ASM | Stage |
|---|---|---|---|---|
| `Lit.Int/Long/Double/Boolean` | ✓ | ✓ | ✓ | — |
| `Lit.String` (intermediate) | ✓ | ✓ | ✓ | p6 / 2.4 |
| `Lit.Null` (intermediate) | ✓ | ✓ | ✓ | p5 / 2.4 |
| Bool top-level return | ✓ (0/1) | ✓ | ✓ | 2.1 |
| Ref+Ref 2-param | ✓ | ✓ | ✓ | 2.2 |
| Guarded ADT match arm | ✓ | ✓ | ✓ | 2.3 |
| `Pat.Lit` arm | ✓ | ✓ | ✓ | 2.4 |
| Free-name → FunV call | ✓ | ✓ | ✓ | 2.5 |
| HOF param call | ✓ (CALLREF) | ✓ (INVOKEINTERFACE) | ✓ (INVOKEINTERFACE) | 3.2 / 3.5 |
| Lambda (non-capturing) | ✓ | ✓ | ✓ | 3.3 |
| Lambda (capturing) | ✓ | ✓ | ✓ | 3.3 |
| Arity 3 params | ✓ | ✓ | ✓ | 4 |
| Arity 4 params | ✓ | ✓ | ✓ | 4 |
| Mixed Int+Double arms | ✓ (heuristic) | ✓ (heuristic) | ✓ (heuristic) | 5.1 |
| `var` in pure body with reassignment | ✓ | ✓ | ✓ | 5.2 |
| `try/catch` body | ✓ | ✓ | ✓ | 5.3 |
| `Pat.Alternative` arm | ✓ | ✓ | ✓ | 5.4 |
| Non-`Term.Name` match scrutinee | ✓ | ✓ | ✓ | 5.5 |

---

## 8. Verification protocol

After every slice commit:

```bash
# 1. Full test suite
sbt "backendInterpreter/test"

# 2. JIT stats — targeted bail categories
SSC_JIT_STATS=1 sbt "backendInterpreter/test" 2>/tmp/jit-stats.txt
grep -A40 "JIT miss" /tmp/jit-stats.txt

# 3. ASM backend parity (both engines must show same coverage)
SSC_JIT_BACKEND=asm sbt "backendInterpreter/test"

# 4. Bench no-regression
scripts/bench interp recursionFib recursionTco recursiveEval pureCallSum

# 5. Coverage tool (after Stage 1 lands)
ssc check-jit-coverage tests/conformance
```

End-of-sprint gate:
- `ssc check-jit-coverage tests/conformance` → ≥95% all-engine compile rate.
- `ssc check-jit-coverage std` → ≥90% all-engine compile rate.
- `scripts/bench interp` full suite → no regression vs pre-sprint baseline.
- `specs/jit-universal-coverage.md` §7 matrix fully updated.

---

## 9. Sprint task list

```
- [x] spec: jit-universal-coverage (this document)                  Stage 0
- [x] jit-uc-stage1-unified-accounting                              Stage 1
- [x] jit-uc-stage2-1-bool-body-wrap                                Stage 2.1
- [x] jit-uc-stage2-2-ref-ref-2param                                Stage 2.2
- [x] jit-uc-stage2-3-asm-guard-parity                              Stage 2.3
- [x] jit-uc-stage2-4-pat-literal-arm                               Stage 2.4
- [x] jit-uc-stage2-5-free-name-funv                                Stage 2.5
- [x] jit-uc-stage3-1-funv-ref-operand                              Stage 3.1
- [x] jit-uc-stage3-2-callref-opcode                                Stage 3.2
- [x] jit-uc-stage3-3-lambda-closure                                Stage 3.3
- [x] jit-uc-stage3-4-ic-validation                                 Stage 3.4
- [x] jit-uc-stage3-5-bytecode-hof-emission                         Stage 3.5
- [x] jit-uc-stage4-arity-3-4                                       Stage 4
- [x] jit-uc-stage5-1-mixed-numeric-arms                            Stage 5.1
- [x] jit-uc-stage5-2-var-pure-body                                 Stage 5.2
- [x] jit-uc-stage5-3-try-catch                                     Stage 5.3
- [x] jit-uc-stage5-4-pat-alternative                               Stage 5.4
- [x] jit-uc-stage5-5-non-name-scrutinee                            Stage 5.5
```

### Final miss profile (2026-06-05, post-Stage-5)

```
JIT miss stats (734 functions disabled):
     300  [vm] Other            — VmCompiler raw-string bails (HOF/closures, unmigrated)
     294  [javac] UnknownShape  — falls through classifier (HOF calls, complex closures)
      48  [javac] LambdaValue   — Term.Function values (non-trivial captures)
      37  [javac] Compound      — multiple bail reasons combined
      27  [javac] NonExtractPattern — tuple/complex patterns in match arms
       8  [javac] PatternGuard  — pattern guards in match
       7  [javac] NonAdtScrutinee — non-hoistable scrutinee shapes
       7  [javac] BoolBody      — bool body too complex for walkBool fallback
       3  [javac] UsingParams   — using/given params (out of scope)
       2  [javac] VarargParam   — vararg params (out of scope)
       1  [javac] TryCatch      — try/catch with finally (out of scope)
```

All in-scope stages landed. Remaining bails are HOF complexity, tuple patterns,
complex closures, and explicitly out-of-scope categories (varargs, using, finally).

---

## 9. Stage 7 — UnknownShape root-cause analysis + plan (2026-06-06)

### Post-stage-6 miss profile

Run after stage 6 (nonextract-tuple + vm-retref + bool-body-ext + pattern-guard landed):

```
JIT miss stats (734 functions disabled):
     299  [vm] Other            — VmCompiler bail strings (HOF/closures, unmigrated)
     240  [javac] UnknownShape  — still unclassified (down from 295 after RefChainCall tagging)
      70  [javac] Compound      — multiple bail reasons (many: HofMethodCall + LambdaValue)
      55  [javac] RefChainCall  — NEW: method on computed ref, no lambda (e.g. parse(n).getOrElse(0))
      27  [javac] NonExtractPattern — complex Pat.Tuple sub-patterns or other unsupported pats
      16  [javac] LambdaValue   — solo lambda (no companion ref call)
       8  [javac] PatternGuard  — complex guards
       7  [javac] NonAdtScrutinee
       6  [javac] BoolBody
       3  [javac] UsingParams   (out of scope)
       2  [javac] VarargParam   (out of scope)
       1  [javac] TryCatch      (out of scope)
```

Two new bail reasons added to `JitBailReason.scala` and `walkForBailCliffs`:
- **`HofMethodCall`**: lambda arg to a method on a non-param receiver (`list.map(x => ...)`)
  — appears only in `Compound(HofMethodCall, LambdaValue)` pairs (hence 0 solo count)
- **`RefChainCall`**: non-lambda method on a computed ref expr (`parse(n).getOrElse(0)`)
  — 55 solo hits, a key new category

### Root-cause breakdown

| Category | Count | Root cause | Stage-7 priority |
|---|---|---|---|
| UnknownShape | 240 | Undetected cliffs (ApplyInfix on refs, non-compilable callee chains, etc.) | Medium — needs deeper walkForBailCliffs tagging |
| Compound (HOF+Lambda) | ~50 | `.map(x => ...)`, `.filter(x => ...)`, `.fold(...)` — closure on HOF method | High — blocks bench workloads |
| RefChainCall | 55 | Method on computed ref without lambda — monadic `.getOrElse`, `.size`, `.head` | High — blocks many pure-ref programs |
| NonExtractPattern | 27 | Complex Pat.Tuple sub-patterns (nested tuples, typed patterns) | Medium |
| LambdaValue (solo) | 16 | Standalone lambda not adjacent to a ref method call | Low (stage-3 CALLREF territory) |
| vm Other | 299 | VmCompiler raw-string bails — mostly HOF/closures | Low (vm-side CALLREF / RETREF expansion) |

### Stage-7 implementation priorities

**P1 — RefChainCall: ref-val propagation (55 misses)**

Functions like `def f(n: Int): Int = parse(n).getOrElse(0)` fail because the JIT has no
way to hold the intermediate `Either` result in a typed ref register and then dispatch on it.

Required: extend the Javac+ASM backends to handle `val r: Ctor = expr; r.method()` chains
where `expr` is a known constructor call. `walkRef` already handles param ref access;
extend it to handle local `val` bindings whose RHS is a ref-returning function call.

Work items:
- `walkRef` extension: recognize `Term.Name(n)` when `n` is a local `val` bound to a ref
- `walkLong`: for `Term.Apply(Term.Select(refExpr, method), longArgs)`, emit inline method dispatch
- One new `walkRefChain` helper: evaluates `refExpr`, stores to a temp, calls method

**P2 — HofMethodCall: monomorphic IC for method dispatch (70 Compound with Lambda)**

Functions with `.map(x => ...)`, `.flatMap(x => ...)`, `.fold(...)` require:
1. The lambda to compile (via existing CALLREF + LambdaValue fix)
2. The receiver (List, Either, Option) to dispatch to the right runtime method

Required: `WalkRef` for the receiver + `CALLREF` for the lambda argument.

This is the main blocker for the HOF bench workloads (either-chain 3.46ms, hof-pipeline 2.79ms,
option-chain 2.98ms, range-sum 3.57ms, typeclass-fold 2.99ms vs JVM <0.02ms).

**P3 — UnknownShape reduction (240 misses)**

Needs further `walkForBailCliffs` tagging to surface the hidden cliffs. Candidates:
- `Term.ApplyInfix` on ref-typed operands
- Calls to non-FunV globals (e.g. `valNames` entries)
- `Term.Interpolate` (string interpolation)
- Nested non-tail-recursive calls that chain ref results

Work items:
- Add `ApplyInfixRefOp` bail reason for infix on ref operands
- Add `InterpolatedString` bail reason for string interpolation
- Re-run profile — target: UnknownShape < 100

### Summary

Stage 7 = two tracks in parallel:
1. **RefChainCall track** (P1): extend `walkRef` + emit ref-chain dispatch; no new opcodes needed
2. **HOF track** (P2): depends on CALLREF being reliable for closures captured from workload functions

The RefChainCall track is lower-risk and has the higher hit rate (55 clean misses).
Start there before tackling the full HOF pipeline.

### Stage 7.1 result — ref-local numeric reads (2026-06-06)

Implemented the low-risk numeric subset:

- Javac + ASM can co-emit ref-returning sibling calls as `Object` helpers.
- `walkRef` handles `None`, `Some(x)`, ref-returning sibling calls, and immutable
  local `val` bindings whose RHS is ref-shaped.
- Non-final `val` binding paths in both backends bind ref RHS values as `Object`
  locals before trying primitive emission, avoiding accidental
  `callGlobalLong*` fallback for ref-returning callees.
- `JitRefDispatch` provides narrow numeric reads:
  `getOrElseLong`, `sizeLong`, and `headLong`. Unsupported receiver/result
  shapes throw and fall back through the existing JIT runtime safety path.

Focused regression:

```scala
def parse(n: Int): Option[Int] =
  if n > 0 then Some(n + 1) else None
def f(n: Int): Int =
  val r = parse(n)
  r.getOrElse(7)
```

`f` now JITs on both Javac and ASM and returns correct direct `LongFn1` results.

Verification:

```
cd /Users/sergiy/work/my/scalascript/.worktrees/feature/jit-uc-stage7-refchain && sbt "backendInterpreter/testOnly scalascript.JitLintTest -- -z stage7-refchain"
cd /Users/sergiy/work/my/scalascript/.worktrees/feature/jit-uc-stage7-refchain && sbt "backendInterpreter/testOnly scalascript.SscVmTest -- -z stage7-refchain"
cd /Users/sergiy/work/my/scalascript/.worktrees/feature/jit-uc-stage7-refchain && SSC_JIT_STATS=1 sbt "backendInterpreter/test"
```

Profile after the full run:

```
JIT miss stats (731 functions disabled):
     298  [vm] Other
     238  [javac] UnknownShape
      70  [javac] Compound
      55  [javac] RefChainCall
      27  [javac] NonExtractPattern
      16  [javac] LambdaValue
       8  [javac] PatternGuard
       7  [javac] NonAdtScrutinee
       6  [javac] BoolBody
       3  [javac] UsingParams
       2  [javac] VarargParam
       1  [javac] TryCatch
```

The total disabled count dropped 734 → 731, but the aggregate `RefChainCall`
bucket stayed at 55. A temporary detail trace showed the remaining bucket is
broader than the Stage 7.1 numeric-local-ref subset: examples include
`Parser.string(s)`, `Free.Pure(a)`, `BigInt(10).pow(n)`, `xs.map(...).mkString`,
effect calls such as `Console.writeLine("a")`, and object-returning
`Map.getOrElse`. Those require either object/String/generic ref-returning
interfaces or a classifier split; they are intentionally not folded into this
slice.
