# v2 compat-coverage baseline (T7.1)

North-star metric for the v1→v2 compatibility tracks: how much of the real v1
`examples/*.ssc` corpus runs on the **v2 FrontendBridge** pipeline (v1 frontend →
`IrExpr` → Core IR → v2 VM). Re-run after every slice and watch it move.

Reproduce: `scripts/v2-compat-coverage` (one JVM via `ssc.bridge.batchCli`).

## Baseline — 2026-07-05, `origin/main` @ `f3e087b3a` (post Track 1+2 merge)

| Metric | Value |
|---|---|
| **PASS** (runs on v2 FrontendBridge) | **129** |
| **FAIL** | 49 |
| Ran (PASS+FAIL) | 178 |
| Skipped (hang-list) | ~16 |
| Corpus total | ~194 |
| **Coverage / runnable** | **129 / 178 = 72.5%** |
| **Coverage / full corpus** | **129 / 194 = 66.5%** |

Prior baseline via the *self-hosted `ssc1`* frontend (the other, non-bridge path):
**1 / 194 (0.5%)** — only `hello.ssc`. The FrontendBridge (reuse v1's parser+typer)
was the right bet: it lifted real coverage from ~0.5% to ~66% in one merge.

> "PASS" here = *runs to completion without error* on the v2 pipeline. Output-equality
> vs the v1 interpreter is a stronger check and a planned enhancement to the harness
> (diff stdout, not just exit status).

## The 49 failures, categorized

**Environmental (~7 — NOT real compat gaps; sandbox has no network/keys/servers):**
- `ConnectException` ×3, `mcpConnect: … client closed` ×2, `No HttpServerSpi impl 'jetty'` ×1,
  `Set … BLOCKFROST_KEY` / `XN_CARDANO_BLOCKFROST_KEY` ×2 (missing env/API keys).

**Real gaps (~42), clustered:**
- **content/markdown toolkit context (~10)** — `contentToolkitSection/contentSection/contentBlock(id)
  is only available while running a parsed .ssc module`. The bridge's `run-module` does not set up
  the content-toolkit runtime context. Fixable wiring gap; highest-leverage single cluster.
- **Spark plugin (8+)** — `unbound global: spark` ×8 plus several `__method__: no dispatch for
  .write/.toDF/.show on Op("Dataset.of"/"Dataset.fromList"/…)`. The Dataset/Spark free-monad DSL
  is not executed by the bridge. (Related distributed/dataset programs are on the hang-list.)
- **plugin-object method dispatch** — `__method__: no dispatch for .X on Op(...)` /
  `no field on named-method-obj`: Graph (`.neighbors.map`), SQL (`.rows`/`.show`), vaults
  (Fireblocks `.unlock`, Swift `.initiateTransfer`), `.resolve` on temp-dir Op. Needs richer
  method dispatch on plugin objects through the bridge.
- **minor bridge bugs** — `None.get` ×1, `expected Data, got ()` ×1, `__method__.put: no handler
  for DataV(Sync,…)` ×1.

## What this tells the plan

Track 1+2 cover **pure language + core plugins** (effects, http, sql-intrinsics). The remaining
~42 are concentrated in a few heavy stateful/DSL subsystems — attack in this order for the most
coverage per unit of work:
1. **content-toolkit run context** (~10 files, one wiring fix) — biggest single win.
2. **Spark/Dataset free-monad executor** (~8+ files) — a whole DSL family.
3. **plugin-object method dispatch** breadth (Graph/SQL/vault) — several files each.
4. minor bridge bugs (None.get / empty-Data) — cheap cleanups.

Skip-list (hang, excluded from the run): actors-pingpong, actors-typed-remote-spawn,
rozum-agent/meeting-demo, dataset-* / distributed-* / word-count (free-monad executor loops).
Un-hanging these needs the Dataset executor (gap #2) + actor-runtime lifetime handling.
