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

- [ ] **agent-sdk-remainder** (IN PROGRESS — claim `agent-sdk-remainder`) — the generic LLM-agent SDK.
      DONE: consolidated `specs/agent-sdk.md` (P0–P2 confirmed shipped); **P3a MCP bridge, both
      directions** — `runtime/std/agent-mcp.ssc` `serveAgentToolsMcp` (expose AgentTools over MCP) +
      `mcpToolSource` (use an MCP server's tools as agent tools); examples `agent-mcp-{server,toolsource}.ssc`;
      module + both examples `ssc check` OK. REMAINING:
      - round-trip test (server+client) — needs an MCP transport workable in a jvm/js test
        (Http is JS-only, Stdio blocks); mirror `McpEndToEndTest`.
      - conformance — mock gateway (canned Contract-1 text/tool_calls) + golden transcripts.
      - P3b embedded transport — DEFERRED until rozum ships the `rozum-embed` crate.
      spec `specs/agent-sdk.md`.

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

_Lower-priority / deferred stay in BACKLOG (not pulled into the sprint): deferred perf
(`hof-glue-jit-compile`, `vectorize-pure-loop`), extensibility themes A/B/E/F/H/J, wallet/MPC/Ledger
follow-ups, and **Maven Central + sbt Plugin Portal — LAST, explicit-go only**. See BACKLOG.md._
