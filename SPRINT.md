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

- [ ] **package-registry** (roadmap #3) — `specs/arch-registry.md`: GitHub-repo + GitHub-Pages registry,
      `packages.yaml` source of truth, `ssc search`/`info`/`add` CLI, PR-based publish + CI validate,
      lunr.js HTML index. Zero server. Self-contained, low-risk; doesn't touch the shared agent code —
      a good standalone next theme.

- [ ] **sbt-plugin-finish** (roadmap #4) — `specs/arch-sbt-plugin.md` remaining surface: front-matter
      `dependencies:` → Coursier, cross-build targets (`sscBackends` JVM/JS/WASM), LSP/BSP polish.
      (Phases 1–4 already landed.) Publishing the plugin artifact itself = part of the deferred Maven step.

- [ ] **metaprogramming-v2** (roadmap #5) — `specs/arch-metaprogramming-v2.md`: P3 cross-module `inline`,
      P4 restricted `QuotedMacro[A]` surface, P5 `Mirror`-derivation for user typeclasses. Largest/riskiest;
      spec itself says do it only "once the plugin ecosystem has validated demand" (i.e. after registry).

### Tier 2 — other actionable backlog items (after the roadmap top, 2026-06-17)

Pulled in per Sergiy "take everything you can do into the sprint." Work after Tier 1; each is
self-contained enough to slice + verify + push. Order roughly by leverage:

- [ ] **theme-a-stable-plugin-spi** — versioned, stable Plugin SPI contract (`specs/plugin-architecture.md`).
- [ ] **theme-h-library-modularity** — multi-file pure-`.ssc` package format, transitive dep
      propagation, access control, namespace collision, API lifecycle + versioning (`specs/arch-library-modularity.md`).
- [ ] **theme-j-lightweight-ffi** — `@jvm`/`@js` + glue.jar two-tier FFI so community libs can call
      Java/JS without a full backend plugin (`specs/arch-ffi.md`).
- [ ] **theme-e-ssc-new** — `ssc new` project scaffolding + standalone install (`specs/arch-ssc-new.md` / `arch-sbt-plugin`).
- [ ] **theme-b-build-registry-consolidation** — merge the build-time registries (`specs/arch-build-registry.md`).
- [ ] **theme-f-dsl-platform-hooks** — DSL extension points (`specs/arch-dsl-hooks.md`).
- [ ] **module-graph-grouping** (low-pri) — 231 sbt modules (~150 thin) → consolidate into grouped builds.
- [ ] **std-nfc-packager-adapters** — NFC packager adapters (native platform follow-up).
- [ ] **wallet-browser-ws-itest** — real browser-WebSocket integration testing for wallet-connect (scaffold; full run needs a browser).

### Excluded from the sprint (deferred / blocked — stay in BACKLOG, NOT actionable now)

- **Maven Central + sbt Plugin Portal** (roadmap #8 / Theme C) — LAST, explicit-go only.
- **direct-style-eval** — DEFERRED, data-disproven ("do not start").
- **hof-glue-jit-compile**, **vectorize-pure-loop** — deferred perf (sub-15% ceiling / speculative SIMD).
- **agent P3b embedded transport** — blocked on rozum shipping the `rozum-embed` crate.
- **WalletConnect project-ID** — blocked on an external decision.
- **Hardware-wallet Vault (Ledger)**, **MPC Vault** — need real hardware / external SDKs; can't verify autonomously.
