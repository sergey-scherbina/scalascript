# Backlog

Open and planned milestones — what still needs to be done.
Active in-progress work is in [ACTIVE.md](ACTIVE.md).
Completed work is in [CHANGELOG.md](CHANGELOG.md).

## Open work — what's left (2026-06-15)

This backlog was tidied 2026-06-15: completed milestones moved to `CHANGELOG.md` + git
history; only sections with open `[ ]` items remain below. The full detailed history of the
55 archived milestones is recoverable from git (`git log -p BACKLOG.md`).

## Roadmap — agreed priority order (2026-06-17, with Sergiy)

Drive top-to-bottom, one major theme at a time. **Maven/centralized publication is dead
last — after everything else.**

1. **payments-reorg** ✓ DONE 2026-06-17 — all 24 payment-domain interp plugins moved under
   `payments/` (hybrid: `payments/processors/{spi,stripe,…}` for the 21 providers + SPI;
   `payments/crypto/plugin` + `payments/payment-request/plugin` next to their libs). Build-config
   only (git mv + `file()` paths); packages/services/val-names/aggregate/PluginSpec unchanged →
   user `.ssc` untouched. 5 slices, all compiled; sepa 71 / stripe 23 / crypto 58 tests green;
   installBin stages all plugins; 0 payment dirs left in runtime/std. spec `specs/payments-reorg.md`.
   **→ Next theme: agent-sdk-remainder (#2).**
2. **agent-sdk-remainder** (MINE) — the generic LLM-agent SDK is ~P0–P2 built
   (`runtime/std/agent.ssc`; specs `rozum-agent-{endpoint-pool,schema-derivation,streaming}`;
   4 interp test suites; 5 examples). Remaining: **P3** (embedded transport + MCP-server
   framework so external agents can drive an app), a **consolidated scalascript-side
   `specs/agent-sdk.md`** (mirroring rozum's `docs/specs/agent-sdk.md` + `integration.md` —
   the 3-contract model: ModelClient/AgentLoop/ToolRegistry/SchemaDerivation/EndpointPool/
   Transcript), and broader **conformance** (mock gateway + golden transcripts + live rozum).
   Coordinate via claims — core is shared with the rozum/busi effort.
   **Progress 2026-06-17:** ✓ consolidated `specs/agent-sdk.md` (P0–P2 confirmed shipped).
   ✓ **P3a MCP bridge COMPLETE (both directions)** — `runtime/std/agent-mcp.ssc`:
   `serveAgentToolsMcp(tools, transport)` (expose AgentTools over `mcpServer`) +
   `mcpToolSource(client)` (wrap an MCP server's tools as AgentTools; JSON→Map via the existing
   `jsonParse` intrinsic surfaced as a local extern; jvm/js only). Examples
   `agent-mcp-{server,toolsource}.ssc`; module + both examples `ssc check` OK; pushed. The two
   `ToolResult` types never meet by name → no collision. **Remaining:** (b) round-trip test
   (server+client; needs an MCP transport workable in a jvm/js test — Http is JS-only, Stdio blocks;
   mirror `McpEndToEndTest`); (c) conformance (mock gateway + golden transcripts). P3b Embedded =
   deferred (needs rozum `rozum-embed`).
3. **package-registry** — `specs/arch-registry.md`: GitHub-repo + Pages registry, `packages.yaml`,
   `ssc search`/`info`/`add`, PR-based publish + CI validate. Zero server. Self-contained, low-risk.
4. **sbt-plugin-finish** — `specs/arch-sbt-plugin.md` remaining surface: front-matter
   `dependencies:`→Coursier, cross-build targets (`sscBackends`), LSP/BSP polish. (Publication
   of the plugin itself = part of the deferred Maven step.)
5. **metaprogramming-v2** — `specs/arch-metaprogramming-v2.md`: P3 cross-module `inline`, P4
   restricted `QuotedMacro[A]` surface, P5 `Mirror`-derivation for user typeclasses. Largest/riskiest.
6. **deferred perf** — `hof-glue-jit-compile` (whole-fn JIT of `combineAll`, needs using/summon JIT;
   sub-15% ceiling) + `vectorize-pure-loop` (SIMD). Low ROI / high risk; revisit opportunistically.
7. **other extensibility themes** — A (stable Plugin SPI), B (build-time registry consolidation),
   E (`ssc new` + standalone install), F (DSL platform hooks), H (library modularity), J (lightweight FFI).
8. **arch-distribution-p3 / Maven Central + sbt Plugin Portal** — **LAST**, only on explicit go.

## Architecture Review follow-ups (2026-06-14)

Whole-project architecture survey (231 sbt modules, ~145K LOC main Scala). The project is
mature and low-debt (only 6 TODO/FIXME files, 21 "not yet supported"); these are *refinements*,
not blockers — hence BACKLOG, not SPRINT. Ordered by leverage/tractability. **#1 is the
recommended first pick** (bounded, measurable, compounds with the perf work).

- [ ] **module-graph-grouping** (Tier 3, low-pri) — 231 sbt modules, ~150 of them thin
      payments/wallet/blockchain/x402 SPI impls (`walletVault*`, `paymentsX`, `blockchain*`, `x402*`).
      Largely intentional (one module per SPI impl), but it's real build-graph + cognitive load and
      slows cold builds. HOW (if pursued): evaluate aggregate grouping or multi-target consolidation for
      the thinnest families WITHOUT collapsing the SPI boundaries. Investigate-first; may conclude "leave
      as-is" — the current shape works. Low priority.

- [ ] **remote-package-registry** (Tier 3, strategic/product) — the plugin ecosystem story is
      local-only (`~/.scalascript/registry.yaml` + `pkg:` resolver + `ssc install`, all LANDED). The SPI
      already supports third-party intrinsic packages (`.sscpkg`), but there's no `registry.scalascript.io`
      to discover/distribute them — deferred "no concrete demand yet". This is the missing piece to
      actually unlock the third-party ecosystem the SPI was built for. Product decision (build when there's
      a real external plugin author), not debt. Spec: extend `specs/arch-build-registry.md`.

## Native Platform follow-ups

- [ ] **std-nfc-packager-adapters** — Consume
      `scalascript.frontend.NativePlatformRequirements` in the SwiftUI/iOS,
      Android, and Web/PWA packagers, then implement real `std.nfc` read/write
      adapters where those targets exist. HOW: keep `runtime/std/nfc.ssc`
      unchanged; make native package generation use `Capability.NfcNdef` to
      emit Info.plist/entitlement, AndroidManifest, and Web permission/model
      declarations; add real device/browser harnesses for `readNdef()` and
      `writeNdef()`; check off the remaining hardware/manifest behavior items
      in `specs/std-nfc.md`. Deferred from `std-nfc-native-adapters` because
      the repo currently has the NFC API and requirements contract but no
      complete Android/Web-NFC packager integration path.

## Interpreter Performance — Open Targets

Baselines from `scripts/bench interp` run 2026-06-04 (Javac JIT backend, `-wi 3 -i 5 -f 1`).

- [ ] **hof-glue-jit-compile** (deep; reframed from `hof-dispatch-cpu-devirt`, investigated
      2026-06-13) — **PARTIAL interp slice landed 2026-06-13** (fused curried
      `List.foldLeft(z)(g)` fast-path in `evalApplyGeneral`: `typeclassFoldMacro` 1.259 → 1.127
      ms/op, **−10.5%**; `FusedFoldLeftTest`). The **full lever is still open.**
      `typeclassFoldMacro` (`combineAll[A: Monoid]` = `xs.foldLeft(empty)(combine)`, 300×).
      Investigation (spec `direct-style-eval-spec.md` §11.3) proved there is **no targeted
      ≥15% *devirt* win**: the inner `combine` is already bytecode-JIT'd (JIT on/off = 1.26 vs
      3.80 ms, 3×), and a fresh JFR CPU profile shows **78% leaf = `evalCore`** self-time (the
      megamorphic `term match`), with *no* devirtualizable callee — `trackPos` no-op and a
      `FunV` JIT-Entry cache (kill the `synchronized` `entryFor` lookup) both measured **0%**.
      The cost is the 300× tree-walk of `combineAll`'s HOF glue (the `foldLeft` Apply + the two
      `summon[Monoid[A]].{empty,combine}` Selects); the fused fast-path shaved the `foldLeft`
      dispatch portion (−10.5%) but the body is still re-interpreted 300×. The remaining lever
      is **compiling that glue**: `combineAll` bails the bytecode/VM JIT on the `foldLeft` HOF
      call (`call:no-compilable-target`, `VmCompiler.scala:521`). Closing it needs List-iteration
      opcodes in `SscVm` + a `foldLeft`-intrinsic recognizer in `VmCompiler` reusing the existing
      `CALLREF` opcode (the dual-bank `LExpr` roadmap, `project_dual_bank_lexpr`) so a
      `foldLeft`-with-a-runtime-monoid compiles to a tight loop. Large architectural effort, not
      a slice. A/B with `scripts/bench interp typeclassFoldMacro` (wall-clock).
      **Re-confirmed 2026-06-17 (perf-followups):** `CallRuntime.foldLeftReusing` ALREADY runs the
      fold as a native Scala `while` over a single reused `ReusableFrame2`, calling the
      bytecode-JIT'd `combine` per element (`JitRuntime.tryRun2`, CallRuntime.scala:221) — so the
      loop AND the combine are already fast. The residual is purely `combineAll`'s PER-CALL glue,
      tree-walked once per call: resolving the `using Monoid[A]` given + the two `summon`-member
      Selects + the `foldLeft` Apply dispatch. The only remaining lever is whole-function JIT of
      `combineAll` itself — which additionally needs **`using`-param + given-member-access support
      in the JIT** (not just a foldLeft recognizer). Confirmed DEFER: too large + too risky (JIT is
      on every hot path) for the ≤15% ceiling; revisit only with the dual-bank `LExpr` VM work.

- [ ] **vectorize-pure-loop** — Use `jdk.incubator.vector.LongVector` inside
      `tryCompileWhileLong` to batch 4–8 lanes when the body is pure arithmetic
      on the counter. Expected 4–8× speedup on `pureCallSumIf` (if the recognized
      grammar for `walkLinearPoly` is extended) and similar shapes. `pureCallSum*`
      are now at the algebraic floor via Gauss; vector would help non-polynomial
      cases. Caveats: `--add-modules jdk.incubator.vector`, JDK incubator ABI
      churn, tail-loop handling for non-aligned N. Revisit after extending the
      closed-form recognizer or when a concrete non-polynomial bench motivates it.

## Quality / Contracts / Type System

These items come from the 2026-05-30 project-state review. They are intentionally
ordered to reduce risk: spec and hygiene first, broad implementation only after
the contracts are explicit.

- [ ] **direct-style-eval** (DEFERRED — data-disproven) — migrate `eval(...): Computation`
      to direct-style `eval(...): Value` to kill per-call `Pure` allocation. **Re-validated
      2026-06-13** (`specs/direct-style-eval-spec.md` §11.1): on the representative tree-walked
      workload `Computation.Pure` is only ~16% of allocation; the dispatch machinery (~66%)
      dominates and `evalDirect` doesn't touch it, so the wall-clock ceiling is below the ≥15%
      gate against a 530-site, high-risk migration. **Do NOT start** without a real workload
      where `Pure` dominates a *tree-walked* path. The win these shapes actually want is
      `hof-dispatch-devirt` (SPRINT) — pursue that instead.

## Architecture & Extensibility Roadmap (v1.x–v2.x)

Cross-cutting improvements to make ScalaScript easier to extend, consume, and
distribute — identified in the 2026-05-28 architectural review.  Ten themes
(A–J), roughly ordered by impact and risk.  Companion plan:
`~/.claude/plans/glowing-swinging-river.md`.

### Theme C — Distribution ecosystem (multi-channel, not Maven-only)

- [ ] **arch-distribution-p3** — First-party Maven Central publication
  (deferred; not queued):
  `project/Publishing.scala`; `io.scalascript` group ID unified; publish
  `scalascript-core`, `scalascript-runtime`, `sbt-scalascript` on tag push;
  sbt Plugin Portal registration. Deferred until Sergiy explicitly asks to
  publish to Maven Central, sbt Plugin Portal, or other official centralized
  repositories.  Spec: `specs/arch-distribution.md §5 Phase 3`.

### Theme D — sbt-scalascript plugin completion

### Theme E — `ssc new` + standalone installation

### Theme B — Build-time registry consolidation

### Theme A — Stable Plugin SPI

### Theme F — DSL platform hooks

### Theme H — Library Modularity

Identified 2026-05-28. Six concrete gaps in the library system: no multi-file
pure-ScalaScript package format, no transitive dep propagation, no access
control, namespace collision risk, no API lifecycle annotations, no versioning
enforcement.  Full analysis in `specs/arch-library-modularity.md`.

### Theme I — Package Registry (discoverability)

Identified 2026-05-28. Without a registry the ecosystem cannot grow: users
cannot find libraries, authors cannot reach users.  Solution: GitHub repo +
GitHub Pages static site, zero server infrastructure, PR-based publishing.
Full spec: `specs/arch-registry.md`.

### Theme J — Lightweight FFI (@jvm / @js + glue.jar)

Identified 2026-05-28. Community libraries cannot call Java or JS APIs today —
only `std/` plugins can.  Two-tier FFI closes the gap without requiring a full
`BackendRegistry` plugin.  Full spec: `specs/arch-ffi.md`.

### Theme G — Metaprogramming v2.x (deferred)

---

## Blockchain SPI — chain abstraction for x402 + wallet

Spec in [`specs/blockchain-spi.md`](specs/blockchain-spi.md). Defines a
shared chain-abstraction layer (`ChainAdapter` / `ChainId` / `Asset`
/ `TypedData` / `recover` / queries) consumed by both `wallet-*` and
`x402-*`. Sits above a lower-level `crypto-spi` (BouncyCastle on JVM,
`@noble/curves` on Scala.js).

Fixes four concrete bugs in current x402:

- `EvmFacilitator.verify` never checks the signature
  (`x402-facilitator-evm/.../EvmFacilitator.scala:23-38`)
- `EvmFacilitator.settle` returns `0x00…00` as stub tx hash
  (`:40-43`)
- Hand-coded `0x70a08231` selector for `balanceOf` (`:32`)
- x402-client SHA-256 stubs (companion fix in
  [`specs/wallet-spi.md`](specs/wallet-spi.md))

### Phase 0 — Spec ✓ Landed (2026-05-19)

### Phase 1 — SPI + crypto + blockchain-evm minimum + x402 facilitator verify fix ✓ Landed (2026-05-19)

  - [ ] `EvmFacilitator.tokenBalance` to use blockchain-evm typed
        proxy — deferred to Phase 2 (depends on full ABI codec)
### Phase 2 — blockchain-evm full ChainAdapter + real x402 settle ✓ Landed (2026-05-19)

Shipped as four slices: RLP+broadcast (29344e6), ABI codec
(3679e68), typed Erc20 proxy + event decoder (a97e7e6), real
relayer-backed x402 settle (cbec71c). ~40 new tests, full Phase 1
regression test green.

  - [ ] End-to-end Anvil integration test deferred — mock-RPC test
        exercises the exact JSON-RPC sequence an Anvil node would
        receive; real network round-trip is a follow-on slice.

### Phase 3 — blockchain-solana ✓ Landed (2026-05-20)

### Phase 4 — Scala.js CryptoBackend ✓ Landed (2026-05-20)

### Phase 5 — blockchain-bitcoin ✓ Landed (2026-05-27)

### Phase 6 — blockchain-cardano + x402 Cardano facilitator ✓ Landed (2026-05-20)

### Phase 7 — blockchain-cosmos ✓ Landed (2026-05-27)

---

## Wallet SPI — Scala.js cross-compile ✓ Sprint complete (2026-05-20)

Spec in [`specs/wallet-spi-scalajs.md`](specs/wallet-spi-scalajs.md).
Six-stage migration that takes the wallet-spi track from JVM-only to
JVM + Scala.js so the same SPI artefacts power browser PWA wallets,
in-page dApp connectors (EIP-1193 / WalletConnect / Solana Wallet
Standard), and the x402 client in a browser context. Builds on the
existing wallet-spi (§ "Wallet SPI — key management + dApp
connectivity") which lands the JVM side first.

### Stage 1 — Plugin setup + cross-compile wallet-spi ✓ Landed (2026-05-20)

### Stage 2 — Scala.js CryptoBackend (crypto-noble-js) ✓ Landed (2026-05-20)

Resolves the `Scala.js registry pattern` open question
([`specs/wallet-spi.md`](specs/wallet-spi.md) §11.1) — first impl module
that registers itself through the Stage 1 cross-platform
`object CryptoBackend.register(...)`.

### Stage 3 — Strategy + connector cross-compile ✓ Landed (2026-05-20)

### Stage 4 — `wallet-strategy-erc4337` cross-compile ✓ Landed (2026-05-20)

### Stage 5 — `wallet-vault-encrypted` cross-compile ✓ Landed (2026-05-20)

Stage 5a — light up the deferred KDF + AEAD primitives in
`crypto-noble-js`:

### Stage 6 — `wallet-connect` cross-compile ✓ Landed (2026-05-20)

Stage 6a — extend `CryptoBackend` SPI with the primitives WC needs
(additive only — no existing-method breakage):

- [ ] **Real browser-WebSocket integration testing** — JS tests
      currently mock `BrowserWebSocket` (Node has no native
      `WebSocket` in the test runtime).  Live integration against
      `wss://relay.walletconnect.com` lands in the future PWA-wallet
      sprint that surfaces WC v2 in an actual browser.

Sprint closure: every wallet-spi-track module that has a JS-relevant
surface now cross-compiles JVM + Scala.js.  All future
`CryptoBackend` implementations are mandated to implement
ChaCha20-Poly1305, X25519, and the Stage 5 AEAD / KDF set in
addition to the original signing / hash / KDF surface — see
[`specs/wallet-spi-scalajs.md`](specs/wallet-spi-scalajs.md) §6 for
the full SPI checklist a new backend has to cover.

---

## Wallet SPI — key management + dApp connectivity

Spec in [`specs/wallet-spi.md`](specs/wallet-spi.md). Sits above
blockchain-spi. Two extension axes: key management (`Vault` /
`RawSigner` / `AccountStrategy`) and dApp connectivity
(`DappConnector`: EIP-1193, Wallet Standard, WalletConnect v2).

Replaces the SHA-256 stub in `x402-client.PrivateKeyWallet` with real
secp256k1 ECDSA via an adapter shim — x402's public API is unchanged.

### Phase 1 — Skeleton SPI + EOA strategy + x402-client shim ✓ Landed (2026-05-19)

Landed in tandem with blockchain-spi Phase 1.

### Phase 2 — Encrypted Vault ✓ Landed JVM + Scala.js core (2026-05-20)

### Phase 3 — DappConnector EIP-1193 ✓ Scaffold landed (2026-05-20)

### Phase 4 — DappConnector WalletConnect v2 (scaffold landed 2026-05-20)

- [ ] WC project-ID open question — still pending; the transport
      accepts a `projectId` argument on both platforms but CI does
      not yet provision one. To resolve before first production
      deployment.

### Phase 5 — Solana DappConnector ✓ Landed (2026-05-27)

### Phase 6 — ERC-4337 SmartAccountStrategy ✓ Landed (2026-05-20)

### Phase 7 — Hardware wallet Vault (Ledger multi-chain)

Architecture in [`specs/wallet-spi.md`](specs/wallet-spi.md) §5.1. One
device, one seed, per-chain on-device apps; the Vault routes
`getSigner(curve, path)` to the right active app and surfaces
`AppSwitchRequired` to the host when the user must change apps.

### Phase 8 — MPC Vault

- [ ] FROST-Ed25519 and future MPC variants — deferred until a concrete
      production use case or partner request arrives.

---

## Strategic-review proposals (2026-06-15)

The feature roadmap is built out (729/740 done, 127 conformance cases, ~70 property/fuzz suites,
comprehensive docs). These are the higher-leverage *productization/hardening/enablement* directions.
The two active ones are in SPRINT (`compile-time-at-scale`, `xbackend-property-equivalence`).

- [ ] **real-workload-perf** — micro-throughput is at floor, but real-workload perf dimensions are
      unmeasured: (a) cold-start / GraalVM native-image startup time, (b) long-running-server memory
      footprint over hours, (c) GC behaviour under sustained load. Build a startup-time + steady-state-RSS
      measurement; profile + cut the worst. Complements `compile-time-at-scale` (the other unmeasured axis).
- [ ] **xbackend-property-equivalence (full suite)** — slice 2 ✓ LANDED 2026-06-15 (broadened to 6 kinds: arith/List/match/enum/String/case-class; 48 JS + 6 JVM all agree). REMAINING: effects, Option/Either, nested data, closures-as-values (each needs per-class determinism care); wire into CI. ORIGINAL — beyond SPRINT slice 1: broaden the generator to
      collections, ADTs, pattern matching, effects, closures; wire it into CI as a standing cross-backend
      differential. The definitive guarantee for a one-source-many-targets language.
- [ ] **registry.scalascript.io (remote package registry)** — the strategic platform move: the SPI +
      `.sscpkg` + `pkg:` resolver + `ssc install` are all built and local-only; a remote registry is the
      missing piece to unlock the third-party plugin ecosystem the SPI was designed for. Product decision
      (build when there's a real external plugin author). Extends `specs/arch-build-registry.md`. (Dup of
      the existing `remote-package-registry` backlog item — consolidate when picked up.)
- [ ] **demand-driven-from-busi** (ongoing, not a discrete task) — the `busi` rozum channel is the live
      testbed and the highest-signal priority source; it is currently quiet. Proactively building one
      comprehensive real app (or asking busi what's painful) surfaces the gaps that matter more than any
      speculative backlog item. Keep sweeping the room per the rozum skill.

## Completed milestones — archived 2026-06-15 (detail in CHANGELOG.md + git history)

- Language Surface — Markdown Frontend from Content
- Codegen-time perf — jvmGen ~100× slower than jsGen (survey 2026-06-14)
- JS Codegen Performance
- Conformance Fixes — cross-backend gaps (2026-06-02)
- Tooling
- UUID Library — v1.65
- Crypto primitives — v1.66 ✓ DONE
- Codebase Maintenance / Architecture Hygiene
- Exact Numerics — BigInt, Decimal, Money (v1.64 ✓ DONE — all phases landed 2026-06; verified 2026-06-14)
- Distributed Runtime (v1.63 planned)
- Distributed Wire Protocol (v1.62 planned)
- Compiler extensibility roadmap
- Recommended implementation sequence
- v0.7 — Reusable libraries and packaging
- v0.13 — Component theming variants
- v1.12 — Typed Algebraic Effects
- v1.51 — Streams with Backpressure
- v1.52 — Deploy to Hostings, Clouds & Kubernetes-like Environments
- v1.53 — Traditional Payment Processors
- v1.60 — Tuple Monoid ✓ Landed 2026-05-28
- v1.61 — Performance & Memory Optimization
- Interpreter performance — next phases (post VM 2a)
- v1.55 — First-class XML / Generic Markup
- v2.1 — Distributed Streams (Beam-style)
- v2.0 — Separate compilation of modules
- Interpreter ergonomics — carried over from v1.1
- Known issues / latent flakes
- CLI — native binary (GraalVM native-image)
- Optimization and modularity roadmap
- Scala ↔ ScalaScript interop — Tiers 1 + 2 landed
- Next wave — post-v1.24 plan
- Beyond
- Speculative — Smart contracts backend
- Speculative — Apache Spark backend
- v1.26 — `sql` fenced code blocks (JDBC)
- v1.27 — browser-side SQL (sql.js / DuckDB-Wasm)
- Infrastructure clients — general-purpose ScalaScript libraries
- x402 — HTTP payment protocol
- MCP × x402 × Wallet — agentic payments
- Micropayment Platform — channel-based fee amortisation for microtransactions
- v1.30 — `@side=client|server` for SQL blocks in full-stack modules
- OpenAPI 3.1
- GraphQL
- v1.48 — SwiftUI Native Frontend (iOS + macOS)
- v1.48.1 — `ssc run` one-command wrapper for SwiftUI targets
- v1.48.2 — `ssc run --target ios` (iOS Simulator)
- v1.48.3 — `ssc run --target ios --device` (real device via ios-deploy)
- v1.48.4 — `ssc package --target ios` → distributable .ipa
- v1.48.5 — `ssc publish --target ios` (TestFlight + App Store via fastlane)
- v1.49 — macOS distribution: notarize + DMG + Mac App Store
- v1.65 — `ssc emit --frontend swiftui` pathway ✓ Landed 2026-06-02
- v1.66 — SwiftUI typed JSON models (`@model` + `FetchJsonSignal`)
- Backend-specific fenced blocks + platform-type ban
- std.fs / std.os / std.process — filesystem, OS & process abstraction
- Requested by busi (real testbed) — 2026-06-09
