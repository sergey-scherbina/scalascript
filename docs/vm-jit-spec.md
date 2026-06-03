# Hot-spot bytecode VM + run-time JIT for the interpreter — spec & POC

Status: **wired into production** (v0). The VM is invoked automatically from
the live interpreter call path via `JitRuntime` (§6); §7b has end-to-end
numbers. Disable with `SSC_JIT=off`.

## 1. Motivation

`RuntimeBench` (2026-05-31) shows the tree-walking interpreter is **350×–10700×**
slower than the JVM/JS code generators on integer-heavy workloads:

```
                interp (us)    JVM (us)   interp/JVM
arithLoop          85,241        247        345×
patternMatch    2,589,035        556      4,657×
recursionFib    7,233,045      1,280      5,651×
recursionTco      267,558         25     10,702×
```

The interpreter is a tree-walker over scalameta `Term`. Every `fib(n-1)` call
allocates a fresh `Env` (a `HashMap`/`FrameMap`), a `Computation` chain
(`Pure`/`FlatMap`), boxed `Value.IntV`, and runs through ~40-case `eval`
dispatch and a trampoline (`TcoRuntime.runUntilSuspension`). None of that work
is intrinsic to computing `fib`; it is interpreter overhead that a compiled
representation removes.

The idea: detect **hot functions** at run-time, compile their body **once** to
a compact register-based bytecode, and execute the bytecode on a tiny VM that
operates on unboxed `Long` registers with **zero per-call heap allocation**.
This is the classic tracing/method JIT shape (Lua, Dalvik, early V8), scoped
here to the integer subset the benchmark exercises.

## 2. Why a register VM (not stack)

A pure stack VM is the easiest target to compile a tree into (post-order emit,
push/pop), but it pays an extra memory traffic per operation (push operands,
pop, push result). A register VM addresses operands by slot index, so
`r2 = r0 + r1` is a single instruction with no stack juggling — fewer
instructions dispatched per expression, which is exactly the dispatch cost we
are trying to cut. We keep a **stack of register windows** (one contiguous
`Array[Long]` shared across frames, addressed by a per-frame base pointer), so
recursion costs a base-pointer bump, not an allocation. This is the
"stack-of-register-frames" hybrid.

## 3. Value model (v0)

Every register holds a `Long`. Booleans are `0`/`1`. Integer functions store
their values directly; **`Double`/`Float` functions store the IEEE-754 bits**
(`doubleToRawLongBits`) in the same `Long` register and operate on them via the
typed `F*` opcodes (§4), with `I2D` promoting an int register to double bits.
This covers `fib`, `sumTco`, `arithLoop` (int) and double hot loops like a
`Double`-accumulating tail recursion (~280× over tree-walk, measured 2026-05-31).
Strings, objects, closures-as-values, and effects remain **out of scope** — the
compiler bails (returns `None`) and the caller falls back to the tree-walker. No
semantic change is possible because an un-compilable function is never compiled.

The compiler tracks a per-register `Int`/`Double` type and classifies each
function's domain up front (a `Double`/`Float` param or a double literal in the
body). The actual return type is re-derived from the `RET` leaves and must agree
with that classification, and mixed-domain returns or branches bail — so a
misclassification falls back to the tree-walker, never miswraps a value. The JIT
bridge marshals each argument per a `paramIsDouble` flag on `CompiledFn` and
wraps the raw result as `IntV`/`DoubleV` per `retIsDouble`.

## 4. Instruction set (v0)

Instructions are stored in **parallel `Array[Int]`** (`op`, `a`, `b`, `c`) plus
an `Array[Long]` constant pool. Registers are slots in the frame window.
`a` is conventionally the destination register.

```
CONST   a, k          reg[a] = constPool[k]
MOVE    a, b          reg[a] = reg[b]
ADD/SUB/MUL/DIV/MOD a,b,c   reg[a] = reg[b] (op) reg[c]
LT/LE/GT/GE/EQ/NE   a,b,c   reg[a] = (reg[b] cmp reg[c]) ? 1 : 0
ADDI/SUBI/.../NEI   a,b,c   reg[a] = reg[b] (op) constPool[c]   (immediate RHS)
FADD/FSUB/.../FMOD  a,b,c   reg[a] = bits( dbl(reg[b]) (op) dbl(reg[c]) )  (double)
FLT/FLE/.../FNE     a,b,c   reg[a] = (dbl(reg[b]) cmp dbl(reg[c])) ? 1 : 0
I2D     a, b          reg[a] = bits( (double) reg[b] )   (int→double promote)
JMP     t             pc = t
JF      a, t          if reg[a] == 0 then pc = t      (jump-if-false)
CALL    a, b, t       reg[a] = exec(fn=callPool[t], window starting at reg[b])
RET     a             return reg[a]
```

`CALL` references a callee `CompiledFn` via a per-function `callPool`. Self-calls
resolve to the function itself; calls to *other* integer functions (siblings /
top-level `def`s, including mutually recursive ones) resolve through a
caller-supplied name resolver and are compiled on demand into the same
compilation context, so a cyclic call graph terminates (each function compiled
once). Arguments must be laid out in a **contiguous register window**
`[b, b+arity)`; the compiler guarantees this by moving each argument into
consecutive temp registers immediately before the `CALL`.

## 5. Compilation (`VmCompiler`)

Input: a `Value.FunV` whose params are all `Int`/`Long`/`Double`/`Float` and
whose body is in the supported subset. Output: `Option[CompiledFn]` — `None` on
any unsupported node (safe fallback).

Supported `Term` subset (v0):

- `Lit.Int` / `Lit.Long`  → `CONST`
- `Lit.Double` → `CONST` of the IEEE-754 bits; promotes its operations to `F*`
- `Term.Name` referring to a param or a `val`/`var` local → register read
- `Term.ApplyInfix` with one arg and op in `+ - * / % < <= > >= == !=` → binary
  op; chooses int or double (`F*`) opcodes by operand type and `I2D`-promotes a
  mixed int/double pair
- `Term.If` (as an expression) → `JF`/`JMP` with branch merge into a result reg
- `Term.Apply(Term.Name(f), args)` where `f` is the function's own name (self,
  compiled to a TCO loop in tail position) **or** another compilable integer
  function reachable via the name resolver (sibling / mutual recursion) →
  `CALL`. A param/local of the same name shadows the function and bails.
  Supported arity is 1..`MaxArity` (8).
- `Term.Block(stats)` → compile leading `val`/`var`/assign/`while` statements,
  final statement is the result expression
- `Term.While(cond, body)` → back-edge `JF`/`JMP` (enables `arithLoop`)
- `Term.Assign(name, rhs)` to a known local → register write

Register allocation: params occupy `r0..r(arity-1)`; locals and temporaries get
fresh ascending slots via a bump counter. `numRegs` = high-water mark. No reuse
in v0 (simple; frames are small).

**Destination-passing.** Expressions compile via `compileInto(term, dst)`, which
emits the result straight into a caller-chosen register. Call arguments are
written directly into their slot in the contiguous arg window, both `if`
branches write the same result register, and `val`/`var`/assignment write the
bound register in place. This removes the extra `MOVE` a return-a-register
scheme emits at every use site — cutting the hot dispatch loop's instruction
count (measured: `recursionFib` −12% end-to-end; self-tail-call loops are
byte-identical and unaffected).

**Immediate-RHS ops.** When an infix op's right operand is an integer literal,
the compiler emits the `*I` variant (`SUBI`, `LEI`, …) whose `c` operand is a
constPool index, folding away the `CONST` that would otherwise load the literal
into a register. This pays off most in tight call-free loops: `recursionTco`
(`n <= 0`, `n - 1`) measured −24% end-to-end. `recursionFib` is neutral — it is
dominated by `CALL`/recursion overhead, so arithmetic folding barely moves it.

Anything else — method calls, doubles, pattern matches, captured free
variables other than self, effects — makes the compiler return `None`.

## 6. Run-time integration (IMPLEMENTED)

Wired into the live interpreter via `JitRuntime`
(`runtime/backend/interpreter/.../vm/JitRuntime.scala`):

1. **Side table, identity-keyed.** `Value.FunV` is a Scala 3 enum case and
   cannot carry mutable state, so per-closure JIT state lives in a synchronized
   `IdentityHashMap[FunV, Entry]`. `Entry` holds `calls`, the compiled
   `CompiledFn|Null`, and a `disabled` flag. Identity keying avoids structural
   equality over the function's AST body.
2. **Warm-up + compile.** `CallRuntime.callValue1/callValue2/callFun` call
   `JitRuntime.tryRun{1,2,List}` on entry. Each counts calls; at `threshold`
   (default 8, `SSC_JIT_THRESHOLD`) it attempts `VmCompiler.compile` once. A
   failed compile sets `disabled` so it is never retried. Anonymous lambdas
   (`name.isEmpty`) early-out — never cached, never compiled.
3. **Guarded execution.** The VM runs only when the function is compiled **and**
   every argument is `IntV`. The `Long` result is wrapped via
   `Computation.pureIntV`. A `FrameOverflow` grows the thread-local stack and
   retries; any other `Throwable` returns `null` → caller tree-walks. Because
   the compiled subset is pure, re-running on the tree-walker is value-identical.
4. **Concurrency.** The frame stack is a `ThreadLocal[Array[Long]]`, so
   concurrent actor calls cannot corrupt each other. The `calls` counter races
   benignly (a lost increment only delays compile by one call).

Disable globally with `SSC_JIT=off`.

**Known limitation (follow-up):** the identity cache holds strong refs to FunV
keys. Named top-level `def`s are long-lived so this is bounded in practice, and
anonymous/per-request lambdas are excluded entirely — but a long-running process
that mints many distinct *named* closures would accumulate entries. A weak
identity map is the eventual fix.

## 7. Verification

- **Correctness**: `SscVmTest` compiles `fib` and `sumTco` from real parsed
  source and asserts VM results equal the known values (`fib(30)=832040`,
  `sumTco(100000,0)=5000050000`) and equal the tree-walker's own result.
- **Speed**: historical `VmJitBench` (deleted 2026-06-02 after `BytecodeJit`
  superseded `SscVm.exec` on the hot path; see commit history) compared
  `treeWalkFib30` vs `vmFib30` (and the `sumTco` pair) under JMH. Goal for v0:
  demonstrate ≥ 10× on `fib`. Equivalent regression coverage today: run
  `InterpreterBench.recursionFib` with `SSC_JIT_BYTECODE=off` (forces fall-back
  to `SscVm.exec`) and with both flags off (`SSC_JIT=off SSC_JIT_BYTECODE=off`,
  pure tree-walk).

## 7a. Results (v0, measured 2026-05-31)

`VmJitBench` (JMH, 5 warmup + 10 measured iters, 1 fork), same machine as
`RuntimeBench`. `treeWalk*` run the whole module through the interpreter;
`vm*` run only the compiled VM (the closure is compiled once in `@Setup`, as a
run-time JIT would after the hot threshold). VM results are asserted equal to
the known values in `@Setup`, so the timings are over verified-correct output.

```
Benchmark               Mode  Cnt         Score      Error  Units
VmJitBench.treeWalkFib  avgt   10  7,366,874.3 ± 28886.2  us/op
VmJitBench.vmFib        avgt   10     35,965.3 ±   471.9  us/op   → 204.8× faster
VmJitBench.treeWalkTco  avgt   10  2,684,505.0 ± 21821.8  us/op
VmJitBench.vmTco        avgt   10     10,526.3 ±   174.1  us/op   → 255.0× faster
```

- **`fib(30)`: 205× over the tree-walker.** The remaining gap to the JVM code
  generator (`jvm_recursionFib` ≈ 1,280 us in `RuntimeBench`) is ~28× — i.e.
  the VM closes **~99.5 %** of the interpreter→JVM gap (was 5,651×, now ~28×)
  with a ~250-line compiler + VM.
- **`sumTco(1,000,000)`: 255× over the tree-walker**, and runs in constant
  host-stack depth because the self-tail-call is compiled to an in-place arg
  update + jump-to-start (§5) — the same loop shape the JVM backend emits, which
  is why the tree-walker's worst case (10,702× off native) is the VM's best
  relative win.

The residual ~28× vs native is expected for a bytecode interpreter: per-instruction
`@switch` dispatch and the non-tail `CALL` arg-window copy dominate. Eliminating
it would require actual machine-code generation (out of scope; the JVM backend
already exists for AOT). The point of v0 is proven: a hot-spot VM removes the
bulk of tree-walking overhead while remaining a transparent, fall-back-safe
add-on.

## 7b. Results (production wiring, measured 2026-05-31)

End-to-end through the live interpreter (`JitRuntime` wired into
`CallRuntime`, §6) — the program is run unmodified; the JIT triggers itself.
`InterpreterBench` (JMH, 3 warmup + 5 measured iters, 1 fork), A/B on the same
warm machine, baseline = `SSC_JIT=off`:

```
Benchmark                      JIT off       JIT on     Speedup
InterpreterBench.recursionFib  7311.4 ms     37.0 ms    198×
InterpreterBench.recursionTco   269.8 ms      0.97 ms   278×
```

- **`fib(30)`: 198× end-to-end**, matching the §7a direct-VM result — the
  warm-up (8 internal self-calls before compile) is negligible against ~2.7 M
  recursive calls, all of which then run on the VM.
- **`sumTco(100000)`: 278×**, now *faster than the JVM-codegen backend*
  (`jvmGen_recursionTco` ≈ 2.27 ms) — the whole tail loop compiles to a single
  VM loop and runs in one `exec`. This required eager compilation for
  self-tail-recursive functions: the trampoline loops internally, so such a
  function enters `callFun` only once and would otherwise never reach the
  call-count threshold (§6.2).
- **No regression**: full `backendInterpreter` suite (1199 tests) green with the
  JIT enabled. All non-integer / effectful / one-shot code is untouched.

## 8. Explicitly out of scope (v0)

Strings/objects/collections; effect operations; pattern matching;
closures captured as values; `Boolean`-typed params/return (needs a return-type
flag — `FunV` has none today); deoptimization; on-stack replacement; a
weak-identity JIT cache (§6 known limitation). These are follow-ups; the
production call-path wiring (§6) is done.

**Now in scope (coverage broadening, 2026-05-31):** arbitrary integer arity
(1..8); calls to other integer functions, including mutual recursion, compiled
on demand through a name resolver (§4, §5); **`Double`/`Float` arithmetic,
ordering and Int→Double promotion** (typed `F*` opcodes + `I2D`, double-bits
register model), including double-domain self-tail and sibling calls.

---

## 9. Phase E — Dual-bank LExpr + JIT-ability lint (2026-06-03)

Five-commit set on main closes the structural cliff where the JIT
backend compiles a function but the LExpr-fold call site never
reaches it. See `~/.claude/plans/noble-discovering-knuth.md` for
the strategic plan and `docs/vm-jit-next.md` "Phase E" for the
detailed per-commit summary.

### What changed

- `LExpr.eval(slots, refs)` dual-bank signature (mirrors
  `SscVm.exec(stack, refStack)`).
- New `LRefExpr` hierarchy: `LRefConst`, `LRefVar`, `LRefFieldGet`.
- New `LApplyR1(LRefExpr, ObjToLong)` + `LApplyR2LongObj`
  / `LApplyR2ObjLong` for 1- and 2-arg ref-mixed calls.
- New typed interfaces (4): `LongObjToLong`, `ObjLongToLong`,
  `LongObjToDouble`, `ObjLongToDouble`.
- `JavacJitBackend.determineInterface` now covers all 2-arg
  (paramIsRef × isDouble) combos.
- `JitRuntime.invokeBytecode2` typed-direct dispatch for all 4
  mixed cases (was MH fallback).
- `compileRefExpr` recogniser: `Term.Name` (val-bound InstanceV)
  + `Term.Select` (static field index from val type).
- `JitLint` analyser + `ssc lint-jit` CLI command for static
  bail-reason reporting.

### Cumulative wins (InterpreterBench, ms/op)

| Bench | Pre-session | Post Phase E | Δ |
|---|---:|---:|---|
| `nestedMatchExpr` | 2700 | 8.5 | 318× |
| `refFieldArg` | ~2700 | 14 | ~170× |
| `recursionFibMul` | 5.96 | 1.29 | 4.6× |
| `recursiveEvalMixed` | 8.0 | 3.67 | 2.2× |
| `recursiveEval` | 7.5 | 3.7 | 2.0× |
| `instanceFieldAccess` | 16.5 | 8.4 | -49% |

Tests: 1226/1226 green.

### Now in scope

- Ref subterms in the LExpr fold (via the dual-bank LExpr +
  `LRefExpr` siblings) — was a silent bail before.
- 2-arg ref-mixed JIT-call sites (`gEval(scale, e)` shape) — was
  evalCore tree-walk before.
- Static lint reporting per-Defn-def with structural fix
  suggestions (was JFR-archaeology before).

### Still out of scope (deferred — see WORK_QUEUE.md)

- `LApplyR1ToRef` — ref-returning JIT'd fn calls. Blocked on
  `ObjToObject` typed interface (JIT walker doesn't emit ref
  returns today).
- `LRefMatch` — match-returning-ref in LExpr position.
- Pattern guards in JIT walker (`case x if cond =>`).
- Pure-predicate extraction of `JavacJitBackend.tryCompile` bail
  set so JitLint reports specifics instead of `UnknownShape`.
- Direction C — direct-style eval (architectural multi-week, see
  `direct-style-eval-spec` WORK_QUEUE item, not yet written).
