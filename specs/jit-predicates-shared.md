# Shared JIT shape predicates (`JitPredicates`)

## Overview

The two JIT backends — `AsmJitBackend` (emits JVM bytecode via `MethodVisitor`)
and `JavacJitBackend` (emits Java source strings) — each carry private copies of
the same AST *shape-classification* predicates. These predicates answer
backend-independent questions ("is this term Long-shaped?", "may this term fall
back to the object-ref path?") and are meant to be identical across backends,
because a program must JIT-classify the same way regardless of `SSC_JIT_BACKEND`.

In practice the copies drift. A concrete drift bug was fixed on 2026-06-11:
`looksLongValue` in Javac had fallen behind ASM (missing 2-arg `getOrElse`,
ref-receiver Long methods, `Math.max|min|abs`, val-bound lambda calls, and
global Long-returning `FunV` calls), so the same program classified differently
under `SSC_JIT_BACKEND=asm` vs the default Javac backend.

This feature removes the duplication for the two predicates that actually drifted
by lifting them into the existing `JitPredicates` object (the project already did
this for `isBoolReturning`), with a minimal backend-agnostic context interface.

## Interface

New, in `JitLint.scala` (same compilation unit as `object JitPredicates`):

```scala
/** Backend-agnostic view of a JIT compile context, exposing just the name- and
 *  global-resolution queries that shape predicates need. Implemented by each
 *  backend's private GenCtx. */
trait JitShapeCtx:
  def isRefName(n: String): Boolean      // n is a ref-typed param/binding
  def isStringName(n: String): Boolean   // n is a String-typed param
  def isLocalLong(n: String): Boolean    // n resolves to a local Long slot/var
  def isLambda(n: String): Boolean       // n is a registered val-bound lambda
  def globalIsIntV(n: String): Boolean   // top-level global n is an IntV
  def globalIsFunV(n: String): Boolean   // top-level global n is a FunV
```

Added to `object JitPredicates`:

```scala
def looksLongValue(t: Term, ctx: JitShapeCtx): Boolean
def objectRefFallbackAllowed(t: Term, ctx: JitShapeCtx): Boolean
```

Each backend keeps a private one-line delegate (signature unchanged for callers):

```scala
private def looksLongValue(t: Term, ctx: GenCtx): Boolean =
  JitPredicates.looksLongValue(t, ctx)
private def objectRefFallbackAllowed(t: Term, ctx: GenCtx): Boolean =
  JitPredicates.objectRefFallbackAllowed(t, ctx)
```

Both `GenCtx` classes gain `extends JitShapeCtx` plus the few new accessor
methods implemented in terms of their existing internals (ASM: `slotOf(n) >= 0`;
Javac: `resolveLocal(n) != null`; both: `interp.globals` lookups, `lambdas`).

## Behavior

- [x] `JitPredicates.looksLongValue` is the single definition; both backends delegate to it.
- [x] `JitPredicates.objectRefFallbackAllowed` is the single definition; both backends delegate to it.
- [x] The shared `looksLongValue` recognizes every case the pre-refactor ASM copy did: literals, single-stmt block, `If`, names (via `isLocalLong`/`globalIsIntV`), unary +/-, arithmetic/compare infix, `size`/`head`/`length` select, `toLong`/`toInt` select, 1-arg and 2-arg `getOrElse`, ref-receiver Long methods, `Math.max|min|abs`, val-bound lambda call, global `FunV` call.
- [x] The only per-backend difference (local-name resolution) is expressed solely through `JitShapeCtx.isLocalLong`; the predicate bodies are byte-identical.
- [x] `SscVmTest`, `InterpreterTest`, `JitLintTest` pass in the default (Javac) backend.
- [x] The same three suites pass under `SSC_JIT_BACKEND=asm`.
- [x] Full `backendInterpreter/test` passes in default mode (no behavioral regression).

## Out of scope

- Unifying the codegen *sinks* (`walkLong`/`walkRef`/`emit*`) — these genuinely
  differ (bytecode via `MethodVisitor` vs Java-source `String | Null`) and are
  not addressed here.
- Extracting the remaining shared *pure* predicates (`isNumericObjectReceiver`,
  `bindingIsRef`, `classifyParamRefs`, `asSelfRecur`, `isTupleMatch`,
  `peelMapUnary`, …). Deferred to a follow-up backlog item; this PR establishes
  the `JitShapeCtx` mechanism on the two predicates that demonstrably drifted.
- Any change to the interpreter core, register VM, or fast-tier.
- Any language-surface or runtime-contract change (SPEC.md untouched).

## Design

`JitPredicates` already lives in `JitLint.scala` and already hosts
backend-agnostic predicates (`isBoolReturning`, `classifyBailReasons`). It is the
established home; `isBoolReturning` is the precedent for the thin-delegate shape.

The one obstacle is that the predicates take the backend-private `GenCtx`. Rather
than share `GenCtx` itself (the two are structurally unrelated — slot indices vs
Java variable names), introduce the narrow `JitShapeCtx` trait capturing only the
queries the predicates make. Each `GenCtx` implements it with a handful of
forwarding methods over data it already holds.

## Decisions

- **Lift only `looksLongValue` + `objectRefFallbackAllowed`** — chosen because
  these are the two predicates with a *demonstrated* drift bug; establishing the
  mechanism on them is low-risk and immediately valuable. Rejected: lifting all
  ~15 shared predicates in one PR (larger blast radius, perf-sensitive
  `walk*`/`emit*` entanglement, no demonstrated need yet — recorded as backlog).
- **`JitShapeCtx` trait over sharing `GenCtx`** — chosen because the two GenCtx
  representations are fundamentally different (slot-based vs name-based). Rejected:
  a shared GenCtx (would force one representation on both backends).
- **`isLocalLong` abstracts the sole real difference** — `slotOf(n) >= 0` (ASM)
  and `resolveLocal(n) != null` (Javac) both mean "n is a local Long". Folding
  this into one trait method makes the predicate bodies byte-identical, so future
  drift is structurally impossible.

## Results

Landed 2026-06-11.

- `JitShapeCtx` trait + `looksLongValue`/`objectRefFallbackAllowed` moved into
  `object JitPredicates` (`JitLint.scala`). Both `GenCtx` classes now
  `extends JitShapeCtx` with six forwarding accessors each.
- Net deletion in the backends: ~62 duplicated predicate lines × 2 collapsed to
  a one-line delegate each (ASM keeps both delegates — it calls `looksLongValue`
  at three of its own sites; Javac keeps only `objectRefFallbackAllowed`, since
  it reaches `looksLongValue` only through it — the now-dead Javac
  `looksLongValue` delegate was removed to satisfy `-Werror`).
- Tests: `SscVmTest`+`InterpreterTest`+`JitLintTest` = 389 green in **both**
  default and `SSC_JIT_BACKEND=asm`. Full `backendInterpreter/test` = 1605 green
  (default). No behavioral change — pure consolidation.
- Drift is now structurally impossible for these two predicates: a single source,
  with the sole backend variation behind `isLocalLong`.

Follow-up (backlog `jit-predicates-shared-rest`): lift the remaining shared pure
predicates (`isNumericObjectReceiver`, `bindingIsRef`, `classifyParamRefs`,
`asSelfRecur`, `isTupleMatch`, `peelMapUnary`, …) the same way.
