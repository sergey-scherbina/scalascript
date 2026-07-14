# v2 gap map — what "v2 catches up to INT" actually costs

Derived from `corpus-baseline.tsv` (the corpus contract) on 2026-07-14 by running
every v2-non-PASS case through `bin/ssc run --v2` and clustering the failure by root
cause. **67 v2 non-PASS cases (62 FAIL, 5 DIVERGE); INT golden = 0.**

The headline: **the v2 gap is integration, not language.** ~76% of it is wiring
*existing* plugins/intrinsics into v2 or closing v2 *frontend parse* gaps — not
reimplementing language semantics. The v2 core is largely sound.

## By root cause

| Class | Cases | What it is | Nature |
|---|---:|---|---|
| **`unbound global`** | 25 | plugin/intrinsic not registered in v2 (content-toolkit ×8, `JsonCodec_derived`/`ObjectCodec_derived` ×6, `htmlToPdfBase64` ×3, `mcpServer` ×2, NFC, Widget, `validate`, …) | **mechanical** — register the plugin; one plugin closes many cases |
| **`Actors scope failed`** | 10 | actor-cluster methods missing from v2's actor scope (`electLeader`, `useRaftLeaderElection`, `clusterConfigSet`, `requestGossip`, …) | **mechanical** — one plugin (actor-cluster) closes all 10 |
| **`native frontend rejected` / `checker exit`** | 13 | the v2 self-hosted FRONTEND can't parse/scope some constructs (incomplete parse) | **← the UniML / frontend track directly** |
| **std-ui** | 5 | std/ui stack on v2 | one class |
| **`OUTPUT-DIVERGE`** | 5 | v2 runs but output differs (content-tables/-to-markdown, dsl-ast/-calc parser render, os-env) | minor rendering |
| **`unhandled runtime effect`** | 3 | native effect op not wired (`SeedResolver.staticList`, `IndexedDb.*`) | **mechanical** — register the effect handler |
| **json self-hosted** | 2 | `jsonParse is self-hosted; import std/json.ssc` — example needs the import | example-migration artifact, **not a v2 bug** |
| genuine one-offs | ~4 | `quoted-macro-constfold` (Range out of bounds), `std-index` (arity 2 vs 3), generator provider, `content-linked-namespaces` | real v2 bugs |

## Strategic read

- **~38 cases (57%) = "register an existing plugin/intrinsic/effect in v2".** Mechanical,
  high-leverage: content-toolkit → 8, actor-cluster → 10, derived codecs → 6, PDF → 3,
  MCP → 2. These are the SAME plugins v1 already has; they just aren't wired into the v2
  registry. This is the biggest, cheapest bucket.
- **~13 cases (19%) = v2 frontend parse gaps** — exactly what the UniML / self-hosted
  frontend track closes. So finishing that frontend directly retires a fifth of the gap.
- **~5 rendering diffs + ~2 example artifacts + ~4 one-offs** — the small tail.

So "all features work on v2" is dominated by integration wiring + the frontend track,
**not** by reimplementing the language. The corpus contract will shrink the v2 column
of `corpus-baseline.tsv` case-by-case as each plugin is wired — that's the measurable
progress bar for the migration.

## Suggested order (highest leverage first)

1. **actor-cluster plugin → v2 scope** (10) and **content-toolkit → v2** (8) — two
   registrations, 18 cases.
2. **derived codecs** (`JsonCodec`/`ObjectCodec`) in v2 (6).
3. **native effect handlers** (SeedResolver / IndexedDb) (3) + the small plugin singles
   (PDF/MCP/NFC) — though several of those are the same feature-gaps as JS/Node.
4. **v2 frontend parse gaps** (13) — the UniML track; land as it matures.
5. one-offs + rendering diffs.
