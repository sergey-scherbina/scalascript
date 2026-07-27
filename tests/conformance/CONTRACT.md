# Corpus contract — the always-on differential gate

`contract.sc` is a single differential gate that runs **both** corpora —
`tests/conformance/*.ssc` and `examples/*.ssc` — through selected backend lanes
(`int`, `js`, `jvm`, `v2`) and diffs each lane against a **golden reference**. It
exists so that a runtime refactor (or the v1→v2 migration) cannot silently break a
backend: the moment an unrecorded difference appears, the gate is red.

This is the strangler-fig safety net. Keep v1/`int` as the golden, grow `v2`
behind the contract, and flip lanes case-by-case — the paired freeze tells you
exactly what still diverges and its non-PASS half shrinks toward zero as `v2`
catches up.

## Golden (reference semantics)

Per case, in order:

1. `expected/<name>.txt` if it exists (deterministic golden — the conformance
   corpus).
2. Otherwise the **live interpreter** (`int`) output, established by running `int`
   **twice** and requiring both runs to agree. This auto-skips non-deterministic
   cases (random / uuid / time). If `int` can't produce output at all
   (server / needs CLI args / times out) the case is **SKIP**ped entirely.

Every other lane is then diffed against that golden and classified
`PASS` / `DIVERGE` / `FAIL` / `TIMEOUT`.

## The paired freeze

One contract observation is stored in two files:

- `corpus-baseline.tsv` contains every **non-PASS**
  `(case, lane, status)` row plus a `(case, *, SKIP)` row for every skipped case.
- `contract-roster.tsv` contains every selected case name, including cases whose
  cells all passed. Its versioned header records SHA-256 digests of both the
  canonical baseline and the canonical roster body.

The roster header has this exact schema (the separators are literal tabs):

```text
# corpus-contract-roster-v1<TAB>baseline-sha256=<64hex><TAB>roster-sha256=<64hex>
```

The baseline digest covers the canonical sorted baseline rows, each terminated
by LF (or zero bytes for an empty baseline). The roster digest covers only the
canonical sorted name body, also with one final LF — not the header or the
whole roster file. Consequently
`sha256sum contract-roster.tsv` is not expected to equal `roster-sha256`.

On a normal gate, the parser validates the header, both digests, sorted
uniqueness, lane/status vocabulary, and that every baseline case is in the
roster before running a lane. A missing, malformed, unsorted, internally
inconsistent, or digest-mismatched pair exits 2; it is never treated as an
empty freeze. A coherent old pair still loads and participates in the ordinary
comparison (which is RED only when the live observation differs), while
deliberately editing both files and recomputing the hashes is not a supported
operator workflow. `--update-baseline` is the recovery/writer path: it
deliberately does not load a broken old pair and instead validates the newly
serialized pair in memory before writing it. `--list` and `--self-test` also
exit before loading the freeze.

The gate compares the live observation with both halves:

| Situation | Meaning | Gate |
|---|---|---|
| current non-PASS rows equal the scoped baseline, with no new or removed names | known feature-gaps and case-name coverage unchanged | **GREEN** |
| current case name is absent from the roster, even if all observed cells PASS | **NEW case** — not classified by the freeze | RED |
| a rostered case has a non-PASS cell absent from the baseline | **regression** | RED |
| an observed baseline non-PASS cell is now PASS | **improvement** — a gap closed | RED → refresh the pair |
| a known non-PASS changes kind (`DIVERGE` → `FAIL`) | behaviour drift | RED |
| a rostered name is absent from the full current selection | removed, renamed, or newly excluded coverage | RED |

An excluded backend cell is not called an improvement because it was not
observed. Likewise, a baseline wildcard `SKIP` improves only when the case now
has at least one eligible observed lane and all observed cells pass. These rules
keep a narrower run from manufacturing a false improvement.

The pair does **not** freeze a complete PASS `(case, lane)` presence matrix.
Changing `backends:` so that a previously passing lane stops running can
therefore remain green; only case names and non-PASS observed cells are frozen.

Documented feature-gaps (Spark / JDBC / PDF / native-crypto / macros / … on the
JS/Node lane, and everything `v2` does not yet support) therefore stay green,
while new coverage, regressions, behavior changes, closed gaps, and removed
coverage all require an explicit decision.

## Running it

```sh
# gate the whole corpus against the paired freeze (CI does this)
scala-cli tests/conformance/contract.sc

# fast fix→check loop on a subset
scala-cli tests/conformance/contract.sc -- --only 'lang-*,json*'

# add the JVM lane (slower — cold Scala-3 compile per case)
scala-cli tests/conformance/contract.sc -- --lanes int,js,jvm,v2

# after intentionally changing the known state (fixed a gap, added a case):
scala-cli tests/conformance/contract.sc -- --update-baseline

# one CI matrix slice (round-robin, idx % 4) — what the nightly workflow runs
scala-cli tests/conformance/contract.sc -- --shard 0/4

# which cases would a shard take? (prints names, runs nothing, needs no build)
scala-cli tests/conformance/contract.sc -- --shard 0/4 --list

# parser/classifier safety checks only (no build and no corpus lanes)
scala-cli tests/conformance/contract.sc -- --self-test

# keep the golden probe tight but allow slower comparison lanes more time
scala-cli tests/conformance/contract.sc -- --timeout 30 --lane-timeout 90
```

Default lanes are the canonical `int,js,v2`. `--timeout <s>` bounds the two
`int` attempts that establish a live golden and controls the timeout-to-`SKIP`
path. A non-zero interpreter exit independently produces `int-nonzero`, and
disagreement between successful probes independently detects non-determinism.
`--lane-timeout <s>` separately bounds each comparison lane; it defaults to
`max(90, --timeout)`. A timed-out probe or lane is retried once, so its
worst-case wall budget is twice the configured value. Both budgets must be
positive. Note the required `--` between scala-cli's options and the script's
options.

The default worker count is bounded to 2–4 according to available processors.
An explicit positive `--workers N` overrides that default without a cap; use it
only with enough CPU/RAM to avoid contention-induced timeout noise.

The gate also fails closed on unknown or duplicate options/lanes, missing or
empty option values, a selection of zero cases, and a run that observes neither
a lane cell nor a `SKIP` cell. Those are exit 2, not GREEN.

`--shard i/N` selects every `N`-th case from the sorted, deduped case list
(round-robin, **not** contiguous blocks — the corpus is name-sorted and the slow
cases cluster by name, so blocks give very uneven shards). The baseline comparison
is subset-safe: non-PASS comparisons are scoped to cells actually run. Removal
detection deliberately uses the complete pre-shard selection, so every
production shard still detects a rostered case disappearing from the corpus.
The `N` shard lists must be disjoint and their union must equal the unsharded
list; verify that property with `--list` rather than reimplementing selection.
An `--only` run is intentionally different: it suppresses global removal
inference because the operator explicitly requested an incomplete selection.

`--update-baseline` is the only writer for **both** freeze files. It refuses
`--only`, `--shard`, `--list`, `--self-test`, and any lane set other than the
canonical ordered `int,js,v2`, exiting 2 before either file is touched. A valid
update is one full, unsharded corpus observation; it writes the non-PASS matrix,
the complete selected-case roster, and digests binding the two.

## Adding / gating cases

- Drop a `.ssc` into `examples/` or `tests/conformance/` — it's picked up
  automatically. A conformance case with an `expected/<name>.txt` gets a
  deterministic golden; an example uses the live-`int` golden.
- Every newly selected name makes the contract RED as `NEW`, even when all its
  cells pass. Classify the case first, then refresh the paired freeze with one
  full run. This is what lets a later failure be called a regression rather than
  merely "new".
- Restrict which lanes a case runs on with `backends: [int, js]` frontmatter
  (same tokens as `run.sc`).
- A declared `known-red: "js — reason and expiry"` lane still runs and diffs;
  a non-PASS result is recorded as `KNOWN-RED`. If that lane starts passing, the
  old row becomes an `IMPROVEMENT`: delete the expired `known-red:` frontmatter
  before refreshing the freeze, rather than freezing a stale declaration.
- A genuinely non-deterministic example is auto-skipped; a server / arg-requiring
  one is auto-skipped when `int` can't run it.

## Which v2 does this gate execute?

The `v2` lane executes the exact standard product command
`bin/ssc run --v2`: `StandardMain → RunNativeV2 → native ssc1
frontend/checker → NativePluginHost`. In the default environment StandardMain
passes `bytecode=true`, so direct ASM is the primary backend, with
side-effect-safe link-time fallback to the VM for unsupported constructs.
Confirm the current default without running a case:

```sh
bin/ssc info --execution-plan --v2
# {"tier":"standard","frontend":"native","checker":"native","backend":"asm","compiler":false}
```

`--interpret` (or `SSC_EXEC=vm|interpret|interpreter`) changes the standard
command to the tree-walking VM. The Corpus Contract neither adds an override
nor clears an inherited `SSC_EXEC`, so record that environment when reproducing
a lane failure. Because the default ASM route may visibly fall back at link
time, this is a native product-output contract, not a strict bytecode-admission
gate.

`bin/ssc-tools run --v2` is not the old bridge either:
`FrontendBridge`/`RunV2` has been retired. The tools launcher now uses the same
native frontend and `RunNativeV2`, but its `--v2` route passes
`bytecode=false` and therefore selects the VM. It is still not the command this
contract labels `v2`.

## Relationship to the other runners

- `run.sc` — the expected-file conformance gate (INT/JS/JVM/V2 vs `expected/*.txt`),
  with memo/batch. Still the authority for cases that have golden files.
- `run-all.sc` — the older curated INT/JS/JVM differential (17 examples).
  `contract.sc` supersedes it: full corpus + the native `v2` lane + a paired
  freeze.
