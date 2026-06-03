# ASM JIT Parity Plan

## Goals

Bring `AsmJitBackend` back to parity with the optimization surface already
available through `JavacJitBackend`, so `SSC_JIT_BACKEND=asm` remains a
drop-in backend selector rather than a narrower experiment.

The immediate goal is parity for user-visible hot paths that already have
Javac support:

- Long/Double function body conveniences: shared boolean-return bails, unary
  `+`/`-`, and multi-statement blocks in expression position.
- Ref-returning match functions (`ObjToObject`) used by chained ref arguments
  such as `leafVal(getLeft(tree))`.
- Function-level sibling calls and mutual recursion in long-returning functions,
  including ref-param ADT match functions.
- While-JIT ref argument and fused foreach coverage that currently exists only
  in the Javac implementation.

## Non-goals

- Replacing `JavacJitBackend`; Javac remains the default until ASM has a full
  same-suite and same-benchmark parity record.
- Implementing new language shapes beyond the Javac subset. If Javac bails,
  ASM may bail too.
- Double-returning mutual-recursion cycles and `ObjToObject` mutual cycles
  unless a concrete benchmark requires them. Javac currently keeps these out
  of scope as well.
- Changing the runtime JIT SPI in this phase. New ASM logic should fit the
  existing `JitBackend`, `JitResult`, `WhileJitEntry`, and `JitGlobals` contracts.

## Current Gap Audit

The 2026-06-04 audit found that `AsmJitBackend` had the original
arithmetic/match/TCO/double coverage and pure-long while co-emission, but
lagged the recent Javac and dual-bank work in these areas:

| Area | Javac status | ASM status | Priority |
|---|---|---|---|
| Boolean-return guard | Uses shared `JitPredicates.isBoolReturning` | Implemented in `f48bcf1f` | P1 done |
| Unary `+`/`-` in function walkers | Supported in `walkLong` / `walkDouble` | Implemented in `f48bcf1f` | P1 done |
| Multi-statement block expressions | Supported via supplier/IIFE | Implemented in `f48bcf1f` | P1 done |
| `ObjToObject` ref-returning match functions | Supported by `walkRefMatchBody` | Implemented in `f48bcf1f` | P1 done |
| Function sibling / mutual co-emit | Supported for long-returning int/ref-param functions | Implemented in `f48bcf1f` | P1 done |
| Binding ref classification for sibling calls | Callee-param-aware | Implemented in `f48bcf1f` | P1 done |
| While-JIT ref args (`ObjToLong`, `ObjToObject`, field/select chains, inline match) | Supported in `tryCompileWhileLong` | Pure-long only | P2 |
| Fused while + foreach List/Set | Supported in `tryCompileWhileMixed` | Missing override | P2 |
| Fused while + Map.foreach | Still open in `WORK_QUEUE.md` for Javac too | Out of scope until Javac lands | Follow-up |

## Implementation Status

Phase 1 landed in `f48bcf1f`:

- `AsmJitBackend` now reuses `JitPredicates.isBoolReturning`.
- Function walkers support unary `+`/`-` and multi-statement expression blocks.
- Long-returning sibling calls and mutual recursion are co-emitted into the
  same ASM-generated class, including ref-param ADT match functions.
- Pattern binding ref classification is callee-param-aware rather than
  self-call-only.
- One-ref-param ref-returning match functions can compile to direct
  `ObjToObject`.
- The shared ASM string-chain fallback now clears the `typeName` value before
  jumping to arm labels, so string-switch fallback arms start with a clean
  operand stack.

Verified on 2026-06-04 with:

- `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/asm-jit-parity-optimizations-20260604 && sbt "backendInterpreter/compile"`
- `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/asm-jit-parity-optimizations-20260604 && sbt "backendInterpreter/testOnly scalascript.SscVmTest"` — 21/21
- `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/asm-jit-parity-optimizations-20260604 && SSC_JIT_BACKEND=asm sbt "backendInterpreter/testOnly scalascript.InterpreterTest"` — 139/139

Phase 2 remains open: while-JIT ref args, ref-returning chains, inline match
helpers, and List/Set fused foreach in ASM.

## Architecture

### Phase 1: Function Backend Parity

Extend the direct function compiler in `AsmJitBackend`:

- Reuse `JitPredicates.isBoolReturning`.
- Add `MethodSig` and `CoEmitState` parallel to Javac's compile-unit state.
- Prefix generated static method names to avoid collisions with the public
  interface `apply` bridge and Java/JVM reserved names.
- Replace self-call-only emission with `emitLongCall(fnName, args, ctx, mv)`:
  self-calls emit to the current static method; sibling calls recursively
  co-emit a compatible top-level `FunV` into the same class.
- Classify pattern bindings as refs when a binding is passed to any JIT-able
  call position whose callee param is ref-typed, not only when it is passed to
  the current function.
- Add an ASM `ObjToObject` path for 1-ref-param ref-returning match bodies,
  including extract arms and wildcard bind defaults.
- Add unary and multi-statement expression block support in function walkers.

All Phase 1 changes are local to `AsmJitBackend` plus focused tests.

### Phase 2: While-JIT Parity

Port Javac's richer while emitter to ASM:

- `WhileCtx` gains ref names, `ObjToLong` names, and `ObjToObject` names.
- Generated ASM while methods load `JitGlobals.getRefs()`,
  `getRefFns()`, and `getRefObjFns()` once before the loop, mirroring Javac's
  local preamble.
- `walkWhileSlot` probes ref args before pure-long args and supports:
  `Term.Name` ref globals, `Term.Select` field refs, `Term.Apply` ref-returning
  chains, and inline `Term.Match` helpers.
- Returned `WhileJitEntry` carries the resolved ref names and function arrays.
- Add a real ASM `tryCompileWhileMixed` for ListV/SetV foreach fusion, using
  the existing `refDoubleFns` slot for Double accumulators.

### Follow-up: Map.foreach

When `while-jit-map-foreach` lands for Javac, repeat the same audit and port the
Map-specific iterator emission into ASM. This spec intentionally does not invent
that shape ahead of the canonical Javac implementation.

## Migration

No caller migration is required. `JitBackend.default` continues selecting ASM
only when `SSC_JIT_BACKEND=asm`; the default backend remains Javac.

ASM must keep the same fallback contract: unsupported shapes return `null`,
allowing the existing interpreter/register-VM path to run.

## Testing Strategy

Phase 1:

- Add direct ASM tests in `SscVmTest` for sibling pure-int calls, pure-int mutual
  recursion, ref-param mutual match recursion, and `ObjToObject`.
- Run `sbt "backendInterpreter/testOnly scalascript.SscVmTest"`.
- Run `SSC_JIT_BACKEND=asm sbt "backendInterpreter/testOnly scalascript.SscVmTest scalascript.InterpreterTest"`.

Phase 2:

- Re-run existing while/ref tests under `SSC_JIT_BACKEND=asm`:
  `InterpreterTest` ref-chain/inline-match tests and the JIT-specific suites.
- Benchmark with `scripts/bench interp nestedMatchExpr`, `scripts/bench interp refFieldArg`,
  `scripts/bench interp refChainArg`, and `scripts/bench interp patternMatch`.
  Record every command and number in `WORK_QUEUE.md`.

Final gate:

- `sbt "backendInterpreter/test"` in default mode.
- `SSC_JIT_BACKEND=asm sbt "backendInterpreter/testOnly scalascript.SscVmTest scalascript.InterpreterTest scalascript.JitLintTest"`.

## Open Questions

- Whether ASM should eventually become the default backend once P1/P2 parity and
  full-suite ASM mode are stable.
- Whether the function-level compile-unit state should be extracted and shared
  conceptually with Javac to avoid future parity drift.
- Whether `ObjToObject` mutual cycles are worth supporting before a benchmark
  exposes a real need.
