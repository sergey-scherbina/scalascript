# Sprint

Agent task queue — **active pending tasks only**. A task is "available" only if
`.work/active/<slug>.claim` does not exist on `origin/main`. Completed work lives in
`CHANGELOG.md`; open (not-yet-started) work lives in `BACKLOG.md`.

**Loop control** — pause: push `.work/paused` to `origin/main`; resume: remove it and push.
Start: tell the agent "go" / "работай". Status: ask "status" / "статус".

---

## Active tasks

Driven by the agreed roadmap (BACKLOG.md → "Roadmap — agreed priority order, 2026-06-17").
Work top-to-bottom, one major theme at a time. **Maven/centralized publication is LAST.**

- [x] **agent-sdk-remainder** ✓ DONE 2026-06-17 (actionable scope) — consolidated `specs/agent-sdk.md`
      + **P3a MCP bridge both directions** (`runtime/std/agent-mcp.ssc`: `serveAgentToolsMcp` +
      `mcpToolSource`; examples `agent-mcp-{server,toolsource}.ssc`; all `ssc check` OK). Loop
      conformance already covered by `AgentSdkInterpreterTest`. DEFERRED (reasons in spec): bridge
      round-trip test (heavy jvm/js infra for thin glue), golden transcripts, P3b embedded (blocked
      on rozum `rozum-embed`). spec `specs/agent-sdk.md`. → **Next: package-registry.**

- [x] **package-registry** (roadmap #3) ✓ DONE 2026-06-17 — found ALREADY BUILT (spec was stale):
      `ssc search`/`info`/`add` over `RegistryClient` (URL-priority + 1h-TTL cache + `--refresh`) +
      seed `registry/packages.yaml`. spec `specs/arch-registry.md` reconciled. Added the minor
      `--offline` flag (cached-only search, `RegistryClient.loadOffline()`). REMAINING (external only):
      the `scalascript/registry` GitHub repo + Pages HTML + validate/publish CI.

- [ ] **sbt-plugin-finish** (roadmap #4) — `specs/arch-sbt-plugin.md` remaining surface: front-matter
      `dependencies:` → Coursier, cross-build targets (`sscBackends` JVM/JS/WASM), LSP/BSP polish.
      (Phases 1–4 already landed.) Publishing the plugin artifact itself = part of the deferred Maven step.

- [ ] **metaprogramming-v2** (roadmap #5) — `specs/arch-metaprogramming-v2.md`: P3 cross-module `inline`,
      P4 restricted `QuotedMacro[A]` surface, P5 `Mirror`-derivation for user typeclasses. Largest/riskiest;
      spec itself says do it only "once the plugin ecosystem has validated demand" (i.e. after registry).

### Tier 2 — AUDIT 2026-06-17: most "themes" are already BUILT (specs stale)

While pulling these in I audited each against the code — and like agent-sdk + package-registry,
most are already implemented; the specs/BACKLOG were stale. So Tier 2 is mostly **reconcile +
verify residuals**, NOT from-scratch builds:

- [~] **theme-a-stable-plugin-spi** — `BackendRegistry` exists; reconcile spec, verify versioning residual.
- [~] **theme-f-dsl-platform-hooks** — spec "implemented through Phase 4" (`InterpolatorRegistry`). Reconcile.
- [~] **theme-h-library-modularity** — spec "implemented through Phase 6" (`SsclibManifest`). Reconcile.
- [~] **theme-j-lightweight-ffi** — `GlueClasspathRegistry`/`GlueJsPreambleRegistry` + `@jvm`/`@js` +
      `examples/js-glue-component.ssc` exist; spec stale at "planned". Reconcile + verify `@rust`/`@wasm` residual.
- [~] **theme-e-ssc-new** — `ssc new`/install present in `Main`. Reconcile + verify completeness.
- [ ] **theme-b-build-registry-consolidation** — spec "Phase 1 landed; Phase 2 open" → genuine Phase-2 build.
- [ ] **module-graph-grouping** (low-pri) — 231 sbt modules (~150 thin) → consolidate into grouped builds.
- [ ] **std-nfc-packager-adapters** — NFC packager adapters (native platform follow-up).
- [ ] **wallet-browser-ws-itest** — real browser-WebSocket integration testing for wallet-connect (scaffold; full run needs a browser).

**Genuine remaining BUILD work** (across Tiers): `sbt-plugin-finish`, `build-registry` Phase 2,
`metaprogramming-v2` (large/gated), + the small residuals above. See BACKLOG "Roadmap reality check".

### Excluded from the sprint (deferred / blocked — stay in BACKLOG, NOT actionable now)

- **Maven Central + sbt Plugin Portal** (roadmap #8 / Theme C) — LAST, explicit-go only.
- **direct-style-eval** — DEFERRED, data-disproven ("do not start").
- **hof-glue-jit-compile**, **vectorize-pure-loop** — deferred perf (sub-15% ceiling / speculative SIMD).
- **agent P3b embedded transport** — blocked on rozum shipping the `rozum-embed` crate.
- **WalletConnect project-ID** — blocked on an external decision.
- **Hardware-wallet Vault (Ledger)**, **MPC Vault** — need real hardware / external SDKs; can't verify autonomously.
