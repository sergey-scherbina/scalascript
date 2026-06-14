# Cross-backend intrinsic-table parity gate

Status: **in progress 2026-06-15** (arch-review follow-up `build-time-cross-backend-parity`).
Owner: backend/compiler infra. Tracking: BACKLOG.md, SPRINT.md.

## Problem

Adding a platform intrinsic to one backend's table but forgetting the peer is a real, recurring
drift class. The `Compiler extensibility roadmap` in BACKLOG flagged it: backend drift is caught
only **post-hoc** by the conformance suite (`tests/conformance/run.sc`), not at build time. We want
a **build-time** gate that fails the test suite the moment the core JVM and JS intrinsic tables
diverge by an undocumented entry.

## What the backends actually expose (investigation 2026-06-15)

Each core backend exposes `Backend.intrinsics: Map[QualifiedName, IntrinsicImpl]`, composed from
parallel hand-maintained sub-tables:

- `JvmIntrinsics` (`scalascript.codegen`, `JvmCapabilities.scala`) = base ++ `Jvm{Http,Ws,Auth,Core,Json,Request,Mcp,Dataset,Payment}Intrinsics`. **147 keys.**
- `JsIntrinsics`  (`scalascript.codegen`, `JsCapabilities.scala`)  = base ++ `Js{…}Intrinsics`. **171 keys.**
- `InterpreterIntrinsics` (`scalascript.interpreter`) = **26 keys** — SPARSE: the interpreter
  dispatches the overwhelming majority of intrinsics via hardcoded `nativeP(...)` natives in
  `initBuiltins`/`DispatchRuntime`, NOT this SPI map.

Measured asymmetry (raw core vals): of 178 distinct names, 156 are not-on-all-3 — but that headline
is dominated by the interpreter's sparse map (122 names are on jvm+js but not the interp SPI map —
**not drift**, the interp just uses a different mechanism). The meaningful signal is **JVM vs JS**:

| diff | count | nature |
|---|---|---|
| jvm-only (not js core) | 3 | `ApplePay.decryptToken`, `ApplePay.validateMerchant`, `GooglePay.decryptToken` — **intentional**: JVM-native wallet-token crypto with no browser equivalent |
| js-only (not jvm core) | 27 | `sha256`/`hmacSha256`/`base64*`/`uuid*`/`GraphQL.*`/… — **JVM provides these via PLUGINS** (`crypto-plugin`, `uuid-plugin`, `graphql-plugin`), not the core table; JS bundles them into the core `JsIntrinsics` val. A registration-location inconsistency, not a capability gap |

## Scope (honest)

This gate covers the **two hand-maintained core codegen tables** `JvmIntrinsics` vs `JsIntrinsics` —
the concrete surface where "added to one, forgot the other" happens for core intrinsics. It is the
tractable 80/20.

**Explicitly OUT of scope** (different registration mechanisms; covered elsewhere or separate work):
- The **interpreter** SPI map (sparse; dispatches via hardcoded natives). Its execution parity is
  covered by the conformance suite, not this registry gate.
- **Plugin-overlay** intrinsics (`crypto`/`uuid`/`graphql`/…). Each plugin owns its own per-backend
  parity; they reach a backend via `BackendRegistry.applyTargetedIntrinsicOverlays`, not these vals.
- **Hardcoded codegen dispatch** residue and **interp natives** — not in any enumerable map.

## Mechanism

A ScalaTest suite in `backendInterpreter/src/test` (its Test classpath already has `backendJs` +
`backendJvm % Test`, so it imports both vals directly — no ServiceLoader). It computes:

```
jvmOnly = JvmIntrinsics.keySet -- JsIntrinsics.keySet
jsOnly  = JsIntrinsics.keySet  -- JvmIntrinsics.keySet
```

and asserts each **equals** a documented allowlist EXACTLY (a ratchet):
- `allowedJvmOnly` — the 3 intentional JVM-native wallet crypto ops.
- `allowedJsOnly`  — the 27 JS-core entries that JVM provides via plugins (each grouped + reasoned).

Exact-equality (not subset) makes the allowlist self-maintaining:
- a NEW undocumented divergence (real drift) → `jvmOnly`/`jsOnly` grows → **test fails** with the
  offending names + guidance.
- a FIXED divergence (someone adds the missing peer, or moves a plugin intrinsic into both cores) →
  the diff shrinks → test fails → prompts removing the now-stale allowlist entry.

The failure message names the unexpected additions/removals and points here.

## Follow-up surfaced (separate BACKLOG item)

The 27 js-only entries reveal that **JS bundles crypto/uuid/graphql into its CORE `JsIntrinsics`
table while JVM delegates the same to plugins.** Harmonising that (both delegate to plugins, or both
keep in core) would let the gate compare true capability parity over resolved sets (core + overlays)
and shrink the allowlist toward zero. Filed as `intrinsic-registration-harmonise` (low-pri).

## Test plan

`CrossBackendIntrinsicParityTest` (the gate itself) — green on the seeded allowlist; a deliberate
local edit (add a key to one table) flips it red. No behaviour change to any backend.
