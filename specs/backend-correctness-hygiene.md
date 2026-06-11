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

- [x] **Root-caused (2026-06-11).** Not framing/field-order/encoding — it is WS
      **subprotocol negotiation**. The JS-codegen node's `connectNode` (`ws` client)
      dials `/_ssc-actors` offering `ssc-actors-v1` and, per RFC 6455, *requires* the
      server to echo a chosen `Sec-WebSocket-Protocol` (else "Server sent no
      subprotocol" → socket closes → no Bully convergence). The **interpreter** node
      echoes (registers with `protocols = ActorWireProtocol.serverProtocols`, via
      `WsHandshake.negotiateSubprotocol`/`upgradeResponse`). The **JVM-codegen** node
      registers the route through the emitted `onWebSocket(path)(handler)` helper,
      which carries **no protocols list**, so it negotiates the empty set and never
      emits the header. (Two earlier blockers are resolved: the JS `_runActors`
      event-loop block is FIXED — now `async` + `setImmediate` yield; `require('ws')`
      is worked around by `npm install ws` in the test sandbox.) Diagnosis recorded
      in the test's doc comment + `specs/cluster-codegen-gap.md`.
- [ ] **Fix (scoped Tier-4, not landed here):** give the emitted JVM serve runtime a
      protocols-aware WS registration for `/_ssc-actors` that negotiates + echoes
      `ActorWireProtocol.serverProtocols` (reusing the interpreter's `WsHandshake`
      shape), then flip `ignore(...)`→`test(...)`. Deferred because it is a
      cross-cutting change to the emitted JVM serve runtime (shared by every JVM
      HTTP/WS program + MCP's `onWebSocket`) whose only real verification is the
      heavy/flaky multi-process matrix test (JVM-bytecode node + Node node + npm +
      HTTP poll) — landing an *unverified* cross-backend protocol change to shared
      `main` is unsafe. Tracked as cross-backend envelope reconciliation.

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

**T3.1 — root-caused 2026-06-11 (fix scoped to Tier-4).** Re-diagnosed the disabled
`ClusterMultiBackendMatrixTest`. The originally-documented cause (JS `_runActors`
event-loop block) is FIXED (async scheduler + `setImmediate` yield), and the
`require('ws')` issue is worked around in the test. The genuine remaining blocker is
WS **subprotocol negotiation**: JVM-codegen registers `/_ssc-actors` via the emitted
`onWebSocket(path)(handler)` (no protocols list), so it never echoes the
`ssc-actors-v1` the JS `ws` client requires — unlike the interpreter, which registers
with `protocols = ActorWireProtocol.serverProtocols`. Fix = protocols-aware WS
registration in the emitted JVM serve runtime; not landed because it is a
cross-cutting emitted-runtime change whose only real verification is the heavy/flaky
multi-process matrix test — unsafe to push unverified. Stale docs corrected in the
test comment + `specs/cluster-codegen-gap.md`. Test stays `ignore` (genuine open bug,
not by-design) with an accurate diagnosis + re-enable recipe.

**T3.2 — bindingIsRef — DONE 2026-06-11** (`000eaae13`; see entry above).
