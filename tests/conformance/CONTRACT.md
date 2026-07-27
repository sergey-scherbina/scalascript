# Corpus contract — the always-on differential gate

`contract.sc` is a single differential gate that runs **both** corpora —
`tests/conformance/*.ssc` and `examples/*.ssc` — through every backend lane
(`int`, `js`, `jvm`, `v2`) and diffs each lane against a **golden reference**. It
exists so that a runtime refactor (or the v1→v2 migration) can't silently break a
backend: the moment any lane diverges from the reference on any case, the gate is
red.

This is the strangler-fig safety net. Keep v1/`int` as the golden, grow `v2`
behind the contract, and flip lanes case-by-case — the baseline tells you exactly
what still diverges and shrinks toward zero as `v2` catches up.

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

## The baseline (`corpus-baseline.tsv`)

A committed snapshot of every **non-PASS** `(case, lane, status)` plus every
skipped case. The gate compares the live matrix against it:

| Situation | Meaning | Gate |
|---|---|---|
| live == baseline | known feature-gaps unchanged | **GREEN** |
| a baseline-`PASS` cell is now non-PASS | **regression** | RED |
| a baseline non-PASS cell is now `PASS`/gone | **improvement** — a gap closed | RED → run `--update-baseline` |
| a known non-PASS changed kind (`DIVERGE`→`FAIL`) | behaviour drift | RED |

So documented feature-gaps (Spark / JDBC / PDF / native-crypto / macros / … on the
JS/Node lane, and everything `v2` doesn't yet support) keep the gate green, while
any *new* divergence — or any gap you accidentally *fixed* without recording —
turns it red. "Fix" a gap ⇒ delete its baseline line (via `--update-baseline`).

## Running it

```sh
# gate the whole corpus against the baseline (CI does this)
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
```

Default lanes are `int,js,v2` (fast). `--timeout <s>` bounds each run (servers
hang; the default skips them). Note the `--` separating scala-cli's own flags from
the script's.

`--shard i/N` selects every `N`-th case from the sorted, deduped case list
(round-robin, **not** contiguous blocks — the corpus is name-sorted and the slow
cases cluster by name, so blocks give very uneven shards). The baseline comparison
is subset-safe: it is scoped to the case names actually run, so a shard gates
honestly against its own slice and the `N` shards together cover the whole corpus.
Verify that property with `--list` (the `N` outputs must be disjoint and their union
must equal the unsharded list) rather than by re-deriving the partition by hand.

`--update-baseline` **refuses** to run under `--shard` (exit 2): it rewrites the whole
baseline file from the cases it just ran, so a sharded update would silently truncate
the baseline to `1/N` of itself. Update it unsharded, over the full corpus.

## Adding / gating cases

- Drop a `.ssc` into `examples/` or `tests/conformance/` — it's picked up
  automatically. A conformance case with an `expected/<name>.txt` gets a
  deterministic golden; an example uses the live-`int` golden.
- Restrict which lanes a case runs on with `backends: [int, js]` frontmatter
  (same tokens as `run.sc`).
- A genuinely non-deterministic example is auto-skipped; a server / arg-requiring
  one is auto-skipped when `int` can't run it.

## Relationship to the other runners

- `run.sc` — the expected-file conformance gate (INT/JS/JVM/V2 vs `expected/*.txt`),
  with memo/batch. Still the authority for cases that have golden files.
- `run-all.sc` — the older curated INT/JS/JVM differential (17 examples).
  `contract.sc` supersedes it: full corpus + the `v2` lane + a baseline.
