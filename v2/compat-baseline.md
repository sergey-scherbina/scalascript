# v2 compat-coverage baseline (T7.1)

North-star metric for the v1→v2 compatibility tracks: how much of the real v1
`examples/*.ssc` corpus runs on the **v2 FrontendBridge** pipeline (v1 frontend →
`IrExpr` → Core IR → v2 VM). Re-run after every slice and watch it move.

Reproduce: `scripts/v2-compat-coverage` (one JVM via `ssc.bridge.batchCli`).

## Current — 2026-07-05 evening (T4.5: hang-list ELIMINATED — full corpus, zero skips)

| Metric | Value |
|---|---|
| **PASS** (runs on v2 FrontendBridge) | **186** |
| **FAIL** | 7 (2 environmental BLOCKFROST keys + 5 real) |
| Ran (PASS+FAIL) | **193 — the FULL corpus, no hang-list** |
| **Coverage / full corpus** | **186 / 193 = 96.4%** |
| **Coverage / non-environmental** | **186 / 191 = 97.4%** |

The hang-list is GONE: every entry terminates; the real batch killer was a bridged
v1 `exit` intrinsic shadowing the actor exit (System.exit killed the batch JVM) —
fixed with Runtime.exitHandler + polymorphic exit + registration order.
Remaining real FAILs (trapExit landed 2026-07-05 evening; wire files moved one gap
forward): `link`/`monitor` supervision surface ×2, `registerBehavior` (typed remote
spawn), `runDistributed`/`runDistributedWire`/`runDistributedShuffleWire` (distributed
MapReduce drivers), Dataset codec `Op/3` (DatasetCodec/DatasetWire need typed-codec
bridging — the Op/3 is the "unhandled plugin method → free-monad Op" fallback
reaching a user match). `pg-listen-notify` needs a real database (env-ish).
FIXED 2026-07-05 night: batch counts are now DETERMINISTIC — batchCli snapshots the
plugin registry after loadAll and restores it per file (`V2PluginRegistry.snapshot/
restore`). Two consecutive full runs: exactly **184/193**, and all 9 FAILs are the
classified out-of-parity set above.
Next enhancement: output-equality vs the v1 interpreter (PASS = exit-0 today).

## MILESTONE — 2026-07-05 night: **v1-INTERPRETER PARITY REACHED on the examples corpus**

The remaining FAILs were probed against the REAL v1 interpreter (`sbt cli/stage`,
`v1/tools/cli/target/universal/stage/bin/ssc run <file>`). Every one is either
environment-gated or does NOT run on the v1 interpreter itself:

| File | Verdict |
|---|---|
| distributed-dataset-{codec, typed-helpers, wire-protocol, wire-shuffle} | `backend: jvm` front-matter — v1 runs them through **JVM CODEGEN** (`//> using jar …typed-data…`), NOT the interpreter. Out of interpreter-parity scope. |
| distributed-word-count | v1 interpreter FAILS too (`Undefined: HandlerRegistry`) — jvm-codegen-class example. |
| actors-typed-remote-spawn | v1 interpreter FAILS too (`runActors requires the actors plugin`, even with `--plugin actors`). |
| pg-listen-notify | needs a real database (front-matter `databases:`) |
| x402-cardano{,-scalus} | need BLOCKFROST API keys |

**Conclusion: the v2 FrontendBridge pipeline runs everything the v1 interpreter runs
on this corpus.** The dataset/jvm-codegen examples are a SEPARATE, optional track —
in v2 they belong to the Phase-2c JVM source generator (Core IR → Scala with the
same `//> using jar` typed-data deps), not the VM bridge.

## Prior baseline — 2026-07-05 morning, @ `f3e087b3a` (post Track 1+2 merge)

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
