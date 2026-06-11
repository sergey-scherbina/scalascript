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
- [x] **Subprotocol echo — FIXED + verified (2026-06-11, `481190610`).** The
      JVM-codegen `/_ssc-actors` route now registers with
      `protocols = List("ssc-actors-v1")` (both codegen peer clients offer only v1),
      reusing the WS library's negotiation/echo; the no-op `onWebSocket` stub was
      widened to mirror the real signature so the actor runtime compiles with or
      without a serve runtime. Verified by running the matrix test with
      `-Dssc.lib.path` + `npm install ws`: the WS now connects and the JVM node
      reaches the JS peer and elects it (`leader=node-bbb`) — previously the upgrade
      was rejected. Full `backendInterpreter/test` 1612 green.
- [ ] **Remaining (next Tier-4 slice):** the JS-codegen node's `/_ssc-cluster/status`
      returns **empty during the election** (works in isolation per `NodeBackendTest`),
      so the matrix test's "both report the same non-empty leader" gate still fails
      (`jvm=leader:node-bbb`, `js=` empty). A JS clustering-under-load issue (HTTP
      status served while the async actor scheduler drives the election), distinct from
      the subprotocol fix. Test stays `ignore` with the precise diagnosis +
      re-enable/verify recipe (`-Dssc.lib.path` via `sbt installBin`). Tracked in
      `specs/cluster-codegen-gap.md`.

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

**T3.1 — subprotocol echo FIXED + verified 2026-06-11 (`481190610`); one further
slice remains.** Re-diagnosed and fixed the disabled `ClusterMultiBackendMatrixTest`
in layers: (1) the originally-documented JS `_runActors` event-loop block was already
FIXED (async scheduler + `setImmediate`); (2) `require('ws')` is worked around in the
test; (3) **fixed** the real WS handshake blocker — the JVM-codegen `/_ssc-actors`
route registered via the protocols-less emitted `onWebSocket`, so it never echoed
`ssc-actors-v1` and the JS `ws` client rejected ("Server sent no subprotocol"). Now
registers with `protocols = List("ssc-actors-v1")`; stub `onWebSocket` widened to
match. Verified end-to-end (matrix test, `-Dssc.lib.path` + `npm install ws`): WS
connects, JVM node reaches + elects the JS peer. Full suite 1612 green. **Remaining:**
the JS node's `/_ssc-cluster/status` is empty during the election (separate JS
clustering-under-load issue) — test stays `ignore` with the precise next-step
diagnosis; tracked in `specs/cluster-codegen-gap.md`.

**T3.2 — bindingIsRef — DONE 2026-06-11** (`000eaae13`; see entry above).
