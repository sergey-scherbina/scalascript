# Shared JIT shape predicates — remaining pure predicates

Follow-up to [`jit-predicates-shared`](jit-predicates-shared.md), which lifted
`looksLongValue` + `objectRefFallbackAllowed` into `JitPredicates` behind the
`JitShapeCtx` trait. This pass lifts the remaining *pure* shape predicates that
were still duplicated between `AsmJitBackend` and `JavacJitBackend`.

## Overview

The two JIT backends carried byte-near-identical private copies of several pure
AST classifiers. "Pure" here means: a total function of scala.meta AST nodes
(and at most `Value.FunV`), with no `MethodVisitor`, no `GenCtx`, and no backend
codegen state. Such predicates have a single correct answer regardless of backend
and must not drift. This pass consolidates them into `object JitPredicates`; each
backend keeps a thin private delegate (same shape as `isBoolReturning`).

## Interface

Added to `object JitPredicates` (all pure, no new context needed):

```scala
def isNumericObjectReceiver(recv: Term): Boolean
def isNumericObjectValueShape(t: Term): Boolean   // BigInt(_)/Decimal(_,_) shape
def peelMapUnary(t: Term): (Term, JitHofShape.UnaryLong) | Null
def isTupleMatch(tm: Term.Match): Boolean
def asSelfRecur(t: Term, fnName: String): Option[List[Term]]
def isLiteralIntMatch(tm: Term.Match): Boolean
def classifyParamRefs(f: Value.FunV): Array[Boolean]   // uses isLiteralIntMatch
```

Each backend retains private one-line delegates (signatures unchanged for callers),
e.g. `private def isTupleMatch(tm: Term.Match) = JitPredicates.isTupleMatch(tm)`.
A delegate is dropped only if the backend no longer calls it (to satisfy `-Werror`).

## Behavior

- [ ] Each of the seven functions has a single definition in `JitPredicates`; both backends delegate.
- [ ] `classifyParamRefs` and `isLiteralIntMatch` move together (the former calls the latter).
- [ ] No `JitShapeCtx` extension is needed — all seven are pure (Term/Match/FunV → result).
- [ ] Behavior is unchanged: the moved bodies are the pre-existing ones (cosmetic naming reconciled only).
- [ ] `SscVmTest`, `InterpreterTest`, `JitLintTest` pass in the default (Javac) backend.
- [ ] The same three suites pass under `SSC_JIT_BACKEND=asm`.
- [ ] Full `backendInterpreter/test` passes in default mode.

## Out of scope

- **`bindingIsRef`** — deferred. It depends on `callParamIsRef`, which consults
  `coEmit.signatures` typed by `MethodSig` — a backend-specific type with
  different arity (ASM's carries `staticMethodName`, Javac's does not) — plus the
  ASM-only `staticMethodName(fn)`. It is genuinely coupled to backend codegen
  state, not a pure predicate. Sharing it would require pushing a
  `callArgIsRef(fnName, idx)` query onto `JitShapeCtx` and keeping `callParamIsRef`
  per-backend; that is a larger abstraction with marginal payoff (the only shared
  part is a ~12-line tree walk). Recorded as a possible future item, not done here.
- `callParamIsRef`, `MethodSig`, and all `walk*`/`emit*` codegen sinks — backend-specific by nature.

## Decisions

- **Move only verified-pure predicates** — chosen because each was confirmed (by
  diffing both copies) to be a total function of AST/`FunV` with no codegen
  coupling, so the move is behavior-preserving and needs no interface. Rejected:
  also moving `bindingIsRef` (would drag in `MethodSig`/`callParamIsRef`).
- **Reconcile cosmetic naming to one form** — `asSelfRecur` (`fn`/`fnN` →
  `funName`/`fn`) and `classifyParamRefs` (`markRef` → `markRefScrutinees`)
  differed only in identifiers/formatting; the merged copy picks one spelling.

## Results

<!-- filled in at verify step -->
