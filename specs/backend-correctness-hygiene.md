# Backend correctness & architecture hygiene (Tier 3)

## Overview

Correctness loose ends and the largest remaining cross-backend duplication.
Smaller and more contained than Tiers 1–2, but they close real gaps.

## Items

### T3.1 — JVM↔JS cluster handshake — `cluster-jvm-js-handshake`

The only genuinely disabled test in the suite:
`ClusterMultiBackendMatrixTest` —
"JVM-codegen + JS-codegen nodes converge on a Bully leader (DISABLED — JVM↔JS
subprotocol handshake mismatch)". A real cross-backend cluster-protocol gap
hidden behind `ignore`.

**Acceptance:** either fix the subprotocol handshake so JVM and JS nodes converge
on one Bully leader and re-enable the test, or — if the mismatch is by-design —
replace the `ignore` with a documented, asserted negative test plus a
backlog/spec note explaining why convergence is not supported.

- [ ] Root-cause the handshake mismatch (message framing / field order / encoding).
- [ ] Fix + re-enable, OR document won't-fix with an asserting test.

### T3.2 — shared `bindingIsRef` — `jit-predicates-bindingisref`

Last JIT predicate still duplicated between `AsmJitBackend` and `JavacJitBackend`.
Left per-backend by `jit-predicates-shared-rest` because it reaches codegen state
via `callParamIsRef` → `MethodSig` (different arity per backend) +
`coEmit.signatures`. Optional, low priority.

**Acceptance:** add a `callArgIsRef(fnName, idx)` query to `JitShapeCtx`
(implemented per-backend over each `MethodSig`), move the pure tree-walk of
`bindingIsRef` into `JitPredicates`; `JitLintTest` green under both backends.

- [x] `JitShapeCtx.callArgIsRef` added; both `GenCtx` implement it (delegating to
      the enclosing backend's `callParamIsRef`, since `GenCtx` is nested in the
      backend `object`).
- [x] `bindingIsRef` body shared in `JitPredicates`; both backends delegate.
      Landed 2026-06-11: 389 tests green in both default and `SSC_JIT_BACKEND=asm`;
      full `backendInterpreter/test` 1605 green. This was the last duplicated JIT
      predicate — the shape-classifier drift surface is now fully closed.

### T3.3 — cross-backend method classifier — `cross-backend-method-classifier`

The collection-method name sets (`filter`/`filterNot`/`takeWhile`/`foldLeft`/…)
are duplicated across **all four** backends (JVM/JS/interp/Rust) with no shared
module in `lang/core`. Same drift class as the JIT predicates, but at the codegen
layer ×4. **Highest structural lever, highest effort/risk — gated:** do only if a
concrete codegen drift bug appears, or as a deliberate, well-tested pass.

**Acceptance:** a single `lang/core` classifier (e.g. `object CollectionMethods`)
owns the method-name taxonomy; each backend consults it; no per-backend literal
sets remain; all four backends' suites green.

- [ ] Shared classifier in `lang/core`.
- [ ] All four backends migrated; literal sets removed.

## Out of scope

- Behavioural changes to codegen beyond what re-enabling T3.1 requires.

## Decisions

- **T3.1 first** — it's a concrete, latent bug behind an `ignore`; the others are
  consolidation. A masked correctness gap outranks tidy-up.
- **T3.3 stays gated** — ×4 codegen-coupled migration is the biggest risk in the
  whole program; not worth it without a forcing function.

## Results

<!-- at verify -->
