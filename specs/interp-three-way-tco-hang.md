# Three-way mutual-TCO blow-up (interp)

Slug: `interp-three-way-tco-hang` · found 2026-06-12 while gating `interp-module-loader-dedup`

## Symptom

`InterpreterTest → "mutual-TCO — three-way ping-pong"` (`InterpreterTest.scala:1025`)
**hung the whole `backendInterpreter/test` suite** — deterministically, with the JIT on
(the default). This was previously mis-logged as a "flaky environmental hang at
InterpreterTest startup"; it is neither flaky nor environmental.

```
def ping(n: Int): String = if n == 0 then "ping" else pong(n - 1)
def pong(n: Int): String = if n == 0 then "pong" else pang(n - 1)
def pang(n: Int): String = if n == 0 then "pang" else ping(n - 1)
println(ping(99999))   // hangs
```

Two-way mutual TCO (`isEven/isOdd`) and self-TCO (`sumTco`) were fine. Only a cycle of
**≥3 functions where no function calls itself** blew up.

## Diagnosis

Not an infinite loop — a performance cliff: depth 30 000 → 29 ms, depth 60 000 →
**115 000 ms**. With `SSC_JIT=off` every depth (incl. 99 999) completes in ~390 ms, so
the tree-walk trampoline is correct and fast — the JIT path is the culprit.

A `jstack` of the slow run is thousands of nested `SscVm.exec(SscVm.scala:303)` frames —
the register-VM `CALL` opcode, which recurses via a real `exec(...)` JVM call.

Mechanism:
- The TCO trampoline (`TcoRuntime.tcoTrampoline`) handles a tail-call to another function
  by throwing `MutualTailCall(nextFn, args)`; the catch loops with `curFun = next`,
  constant stack. Correct for any cycle length.
- But the catch first tries a **JIT fast-path**: `if next has no non-tail self-call →
  JitRuntime.tryRunList(next, eager=true)`. The intent was the `workload() = sumTco(…)`
  case — `next` is self-tail-recursive, the JIT compiles its self-loop to a `while`/JMP
  and runs the whole hot loop in one constant-stack shot.
- The gate `noNonTailSelf` is **true for any function without a non-tail self-call** —
  including `pong`, which has *no* self-call at all (it only tail-calls `pang`). So the
  trampoline JIT-ran `pong`. The register VM (`VmCompiler.compileTail`) lowers a non-self
  tail call to a recursing `CALL` (it only turns *self* tail-calls into a loop). Running
  the 3-cycle in compiled code therefore recurses `ping→pong→pang→ping…` via nested
  `exec`, advancing `base` through the 64K register stack until `FrameOverflow`, at which
  point `runVm` grows the stack and **restarts `exec` from scratch** → O(n²) → "hang".
- Two-way `isEven/isOdd` avoided this only incidentally (its `next` is handled by the
  bytecode backend / bails before the register-VM CALL path).

## Fix

In `TcoRuntime.tcoTrampoline`'s `MutualTailCall` handler, only take the JIT fast-path
when `next` is **genuinely self-tail-recursive** (`tcoInfoFor(next).isSelfTailRec`, i.e.
it calls *itself* in tail position), not merely `noNonTailSelf`:

```scala
val jitResult =
  if nextInfo.isSelfTailRec then JitRuntime.tryRunList(next, mc.args, interp, eager = true)
  else null
if jitResult != null then return jitResult
// else fall through to `curFun = next` — stay on the constant-stack trampoline
```

- `workload → sumTco`: `sumTco` is self-tail-recursive → JIT fast-path preserved.
- `ping → pong → pang → ping`: each `next` is not self-tail-recursive → stays on the
  tree-walk trampoline (constant stack, ~390 ms for depth 99 999).
- `isEven/isOdd`: not self-tail-recursive → trampoline (correct, fast).

No change to `VmCompiler` — it still compiles mutual recursion (the `SscVmTest`
unit tests that call `VmCompiler.compile`/`JavacJitBackend.tryCompile` directly are
unaffected); the fix is purely in which path the *trampoline* dispatches to.

## Verification

- `ThreeWayMutualTcoTest`: 3-way (99 999), 2-way (100 000), and self-TCO-delegate
  (`workload→sumTco`) — each asserts correct output **and** a wall-clock bound (<8 s) so a
  re-introduced blow-up fails loudly instead of hanging.
- `InterpreterTest` now completes (141 passed) — previously hung at the three-way test.
- `SscVmTest` (mutual-recursion compiler unit tests) still green.
- Full `backendInterpreter/test` now runs to completion (was un-completable due to this
  hang).

## Follow-up (not done here)

The register VM still lowers a non-self tail call to a recursing `CALL` (`VmCompiler`
comment at `compileTail` acknowledges "pathologically deep mutual tail recursion may
overflow the host stack"). A real *n-way mutual-TCO JIT* (lower a non-self tail call to a
trampoline-return / function-switch loop) would let these cycles run compiled rather than
tree-walked. Low priority — the tree-walk path is correct and fast; filed as a perf idea.
