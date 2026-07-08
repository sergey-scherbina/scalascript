# v2 output-parity baseline (v1 vs v2, true output-equality)

Reproduce: `SSC="bin/ssc" scripts/v2-output-parity $(<terminating-list>)`
(build the runner first: `sbt installBin` — `cli` now `dependsOn v2FrontendBridge`, so `bin/ssc run --v2`
routes a source through the v1 frontend → FrontendBridge → the v2 VM).

This is the REAL "does v2 replace v1?" gate: each example is run on v1 (`ssc run`) AND v2 (`ssc run --v2`)
and stdout is diffed. It is far stricter than `scripts/v2-compat-coverage` (exit-0), which reports 96.4%.

## Latest full corpus re-measure — 2026-07-08, after content toolkit section fix

Built/staged from `/Users/sergiy/work/my/scalascript-wt-v2-prod-content-toolkit-section` with:

```bash
scripts/sbtc "installBin"
PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity --all
```

Current result:

| | count |
|---|---|
| ✅ output-identical | **57 / 81 = 70%** |
| ❌ mismatch | 8 |
| ⚠️ v2-error (v1 works, v2 empty) | 0 |
| v1-only (v2 works, v1 empty) | 16 |
| both-fail (not a v2 gap) | 44 |
| true-server skipped | 36 |
| long-running skipped | 0 |
| backend-lane skipped | 32 |
| nondeterministic-output skipped | 2 |
| total examples seen | 195 |

Confirmed improvements in this gate: `content-slot.ssc` and
`content-toolkit-yaml-controls.ssc` are now output-identical, closing the last
post-p3 v2-error and the sibling unsupported-AST mismatch. The earlier confirmed
matches still hold: `content-form-submit`, `content-live-rows`, `typed-sql-crud`,
`ui-fetch-json`, `ui-remote-table`, `rozum-agent`, `rozum-agent-pool`, and
`invoice-email.ssc`.

Closed in this slice (`7dee6daf0`):

- **Content toolkit section lowering** — `MinimalCtx` now lets real content-plugin
  lowering resolve/call imported toolkit builder globals such as `fieldColumn`, and
  FrontendBridge now desugars `[bodyEl]` after a spaced infix operator in
  `std/ui/lower.ssc`. Targeted parity for `content-slot.ssc` and
  `content-toolkit-yaml-controls.ssc` is **2/2 MATCH**; all `examples/content*.ssc`
  are **10/10 MATCH** with the expected `content-introspection` v1 timeout
  classification; affected conformance `content*` is **5/5** green.

Current production-relevant blockers:

- **Quoted macro body evaluation** — `quoted-macro-interpreter.ssc` still prints only
  `42` on v2, missing the computed-body outputs `literal: 7` and `x`.
- **Rozum schema/streaming scope** — `rozum-agent` and `rozum-agent-pool` match, but
  `rozum-agent-schema-derived.ssc` and `rozum-agent-streaming.ssc` still mismatch in
  this full run.

Non-blocker/scope categories still appearing as mismatches:

- `actors-pingpong.ssc` — v1 exit-cascade/missing final `done`;
- `async-parallel-demo.ssc` — wall-clock timing nondeterminism;
- `effects.ssc` — v2 prints the documented full output while v1 stops early;
- `os-env.ssc` — v2 resolves real platform values while v1 prints native placeholders;
- `dsl-calc-parser.ssc` — existing parser/DSL output-shape/v1-side bug classification
  needs to be kept out of the v2 default-switch decision unless reclassified.

## Full corpus re-measure — 2026-07-08, production-readiness baseline

Built/staged from `/Users/sergiy/work/my/scalascript-wt-v2-production-readiness` with:

```bash
scripts/sbtc "installBin"
PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity --all
```

Current result after the content bridge parity fix (`146779cb6`):

| | count |
|---|---|
| ✅ output-identical | **54 / 88 = 61%** |
| ❌ mismatch | 10 |
| ⚠️ v2-error (v1 works, v2 empty) | 1 |
| v1-only (v2 works, v1 empty) | 23 |
| both-fail (not a v2 gap) | 37 |
| true-server skipped | 36 |
| backend-lane skipped | 32 |
| nondeterministic-output skipped | 2 |
| total examples seen | 195 |

Before that slice, the same worktree measured **51/88 output-identical · 13 mismatch ·
1 v2-error · 23 v1-only**. The content cluster was the first contained v2 production
blocker and is now closed:

- `content-linked-namespaces.ssc` => MATCH;
- `content-tables.ssc` => MATCH;
- `content-to-markdown.ssc` => MATCH;
- all top-level `examples/content*.ssc` parity-check as 10/10 identical, with
  `content-introspection.ssc` classified long-running because the v1 side times out.

This is a large improvement over the corrected 2026-07-06 baseline (11/47 real-output
parity, then 21/47 after plugin packaging fixes). It also changes the immediate
production blocker order:

- `algebraic-effects.ssc` now **MATCHES**. The old State-effect divergence is not the
  first v2 production blocker anymore.
- `effects.ssc` still mismatches, but v1 prints only the first 3 documented lines while
  v2 prints the full documented output; classify as a v1-side follow-up, not a v2 blocker.
- `os-env.ssc` still mismatches because v1 prints unresolved `<native:...>` placeholders
  and v2 prints real platform data; classify as v2-better / v1 bug.
- `async-parallel-demo.ssc` differs only in wall-clock timings; classify as
  nondeterministic-output unless the example is normalized.
- Remaining v2 production blockers by cluster:
  - **v2-error class:** closed by `44f3d4a24`; no v2-error cases remain in the
    latest full sweep;
  - **parser/DSL output shape:** `dsl-calc-parser`;
  - **quoted macro body evaluation:** `quoted-macro-interpreter`;
  - **rozum server/batch behavior:** `rozum-agent`, `rozum-agent-pool`,
    `rozum-agent-schema-derived`, `rozum-agent-streaming` (needs a lane/scope decision
    because v1 emits server startup lines and v2 uses batch stubs).
  - **toolkit section lowering:** `contentToolkitSection` is deliberately still a
    batch stub in the v2 bridge. Do not remove it until section-level toolkit lowering
    is parity-checked against `content-slot`, `content-toolkit-yaml-controls`, and
    the other section-rendering examples.

Next code slice after this baseline: close the production-relevant plugin-boundary
gaps without reintroducing content structured-block regressions.

### Update — 2026-07-08, dataset stack-safety fix

`dataset-parallel-sum.ssc` is closed by `44f3d4a24`: v2 was crashing with
`StackOverflowError` in recursive `Prims.unlistPub` while converting the 100k-element
`List.range` passed into `Dataset.fromList`. `unlistPub` and `listOf` are now
iterative.

Verification:

```bash
PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/dataset-parallel-sum.ssc
# parity: 1/1 identical · 0 mismatch · 0 v2-error · 0 v1-only

scala-cli tests/conformance/run.sc -- --only 'dataset*' --no-memo
# Results: 15 passed, 0 failed out of 15 tests
```

Full corpus after the fix measured **54/88 identical · 11 mismatch · 0 v2-error ·
23 v1-only**. The extra mismatch versus the previous table was a transient
`invoice-email.ssc` generated byte-count difference (`2681` vs `2685`); an immediate
targeted rerun of `invoice-email.ssc` plus `dataset-parallel-sum.ssc` was **2/2 MATCH**.
The production-relevant change is that the full gate now has **zero v2-error cases**.

### Update — 2026-07-08, invoice email output stabilization

`examples/invoice-email.ssc` no longer prints the exact generated MIME message byte
count. The example still renders the PDF and builds the MIME message, but stdout now
uses the stable semantic line `MIME message assembled: PDF attached` after confirming
the message is non-empty. This closes the transient byte-count mismatch class without
normalizing the parity harness.

Verification:

```bash
PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/invoice-email.ssc
# repeated 5 times: parity: 1/1 identical · 0 mismatch · 0 v2-error · 0 v1-only

PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/invoice*.ssc examples/pdf-extract-demo.ssc
# parity: 3/3 identical · 0 mismatch · 0 v2-error · 0 v1-only

scala-cli tests/conformance/run.sc -- --only 'invoice*' --no-memo
scala-cli tests/conformance/run.sc -- --only '*pdf*' --no-memo
scala-cli tests/conformance/run.sc -- --only '*mime*' --no-memo
# each glob: 0 matching case(s)
```

## Result — 2026-07-05, 52 terminating examples (no server/actor/network)

| | count |
|---|---|
| ✅ output-identical | **27 / 52 = 52%** |
| ❌ mismatch | 16 |
| ⚠️ v2-error (empty output) | 9 |

The exit-0 coverage (96.4%) **massively overstates** real compatibility: barely half of even the pure,
terminating examples produce v1-identical output on v2.

## Divergence clusters (for Track-4 conformance)

1. **Plugin natives return `Stub`/`Op` instead of executing (largest cluster).** The v2 bridge doesn't run
   these plugin backends, so their calls surface as `Stub("…")` / `Op("….sql", …)`:
   - SQL: `sql-h2-quickstart`, `sql-sqlite-file`, `sql-browser-duckdb` (`Op("…​.sql", …)` not executed),
     `object-store-jdbc`, `typed-sql-crud` (empty).
   - Spark: `spark-udf-demo`, `spark-schema-mapping` (empty).
   - Content/rails: `content`, `content-linked-namespaces` (`Stub`), `international-bank-rails`
     (`Stub("SwiftProvider.…")`).
2. **Effects output shape** — `algebraic-effects`, `signals-demo` (v2 stops early / wrong shape).
3. **Derives / mirror** — `custom-derives-mirror`: v1 prints the union `String|Int`, v2 widens to `Any|Any`.
4. **Quoted macros unsupported** — `quoted-macro-constfold`, `quoted-macro-interpreter`
   (`Unsupported: TermSplicedMacroExprImpl`).
5. **v2-error (empty output, 9)** — `default-params`, `dsl-calc-parser`, `graph-codecs`, `index`,
   `object-store-jdbc`, `spark-schema-mapping`, `typed-object-codec`, `typed-sql-crud`, `ui-fetch-json`.
6. **Not real bugs (v2 is fine/better) — ~2 false mismatches:**
   - `uuid-v7`: UUIDs differ because they are time/random-based (non-deterministic; not a defect).
   - `os-env`: v1 prints unresolved `<native:platform>` placeholders, v2 correctly resolves them to
     `JVM` / real cwd / `/` — v2 is **more** correct here.

So the honest semantic parity is ≈ 27 exact + 2 v2-fine ≈ **29/52 (~56%)** — still far below the exit-0 96%.
Biggest lever by count: execute the SQL/Spark/content plugin natives on v2 instead of returning `Stub`/`Op`.

## Re-measure — 2026-07-06 (after T4.4 conformance 22→59/59 GREEN + batch 144→154/193)

Re-ran the identical sweep on a fresh `bin/ssc` built from origin/main **after** the sibling's T4.4 wave-7/8
+ corpus fixes landed. Result: **27/52 identical** (15 mismatch, 10 v2-error) — **unchanged**.
**Key insight:** the conformance suite hitting 59/59 green and the batch corpus reaching 154/193 (exit-0) did
NOT move the real `examples/` output-parity. The team's current gates (conformance-suite + exit-0 batch)
and true output-equality measure different things — corpus-tails work should also be gated on this harness.
(One regression: `fs-roundtrip` MATCH → v2-error, `unbound global: mkdirs`.)

### v2-error root causes (diagnosed via `bin/ssc run --v2`, for the `v2-corpus-tails` owner)

| example | root cause | category |
|---|---|---|
| `uuid-v7` | `unbound global: uuidV7` | plugin native not bridged (uuidPlugin) |
| `fs-roundtrip` | `unbound global: mkdirs` | plugin native not bridged (fsPlugin) — REGRESSION |
| `dsl-calc-parser` | `unbound global: ws` | parser-combinator std fn unbound |
| `ui-fetch-json` | parse error `<input>:334: '=>' expected but '(' found` | v2 frontend parser gap |
| `index` | `requirement failed: /hello is not a relative path` | bridge path handling (abs vs rel) |
| `default-params` | empty output (no exception) | default-param exprs not evaluated |
| `graph-codecs`, `object-store-jdbc`, `spark-schema-mapping`, `typed-object-codec` | empty output | plugin natives (jdbc/spark/codec) silently not executed |

The `unbound global` ones (`uuidV7`/`mkdirs`/`ws`) are the same class as the webauthn/html-dsl plugin-gating:
their natives are `InlineCode`/`RuntimeCall` or interpreter-builtins that the PluginBridge ServiceLoader
loop skips — register them in `PluginBridge`. (`mkdirs` + the fs builtins are now bridged — 2026-07-06.)

## Full corpus (`--all`) — 2026-07-06, authoritative number

`SSC="bin/ssc" scripts/v2-output-parity --all` over all **193** examples (server/actor/dataset programs that
never terminate are auto-skipped):

| | count |
|---|---|
| runnable (terminating) | **63** |
| ✅ output-identical | **30 / 63 = 48%** |
| ❌ mismatch | 22 |
| ⚠️ v2-error | 11 |
| skipped (server/actor/dataset) | 130 |

**The real "does v2 replace v1?" answer: ~48% of runnable examples produce v1-identical output** — vs the
96.4% exit-0 coverage. `fs-roundtrip` is now ✅ (fs builtins bridged). The dominant remaining gap is still
plugin natives returning `Stub`/`Op` (SQL/Spark/content/rails) plus effects shape, derives/mirror, quoted
macros, and the `validate` language form. The 130 skipped need a terminating harness (server-with-timeout,
bounded actor runs) to be measured — future work.

### CORRECTION 2026-07-06 — the 48% was inflated by "both-fail" false matches

The harness counted "both runners print nothing" as a MATCH. But many examples **fail on v1 too** under the
default `ssc run` because they need a `--plugin` the default set doesn't load (e.g. `sha256` is `Undefined`
on v1 without the crypto plugin). Those are NOT v2 gaps and must not count as parity. The harness now
distinguishes `both-fail` / `v2-error` / `v1-only` / match / mismatch. Corrected full-corpus number:

| | count |
|---|---|
| ✅ identical | **11** |
| ❌ mismatch | 17 |
| ⚠️ v2-error (v1 works, v2 empty) | 11 |
| v1-only (v2 works, v1 empty) | 8 |
| both-fail (needs `--plugin` on both — NOT a v2 gap) | 16 |
| skipped (server/actor/dataset) | 130 |
| **parity of examples with real output** | **11 / 47 ≈ 23%** |

**The honest number is ~23%, not 48%.** The 11 REAL v2-only gaps (v1 works, v2 produces nothing):
`content-form-submit`, `content-live-rows`, `content-slot`, `default-params`, `graph-codecs`,
`object-store-jdbc`, `spark-schema-mapping`, `typed-object-codec`, `ui-fetch-json`, `ui-remote-table`,
`uuid-v7`. Clusters: content/ui plugin natives, jdbc/spark Op-execution, codec/derives, the FrontendBridge
parser (`ui-fetch-json`), and `default-params` (default-arg evaluation). These + the 17 mismatches are the
true v1→v2 gap.

### Fixes landed 2026-07-06 (`feature/v2-main-entry`, FrontendBridge)

- **user `def main()` now invoked** — was skipped because the html `<main>` tag global shadowed it.
- **`def main()` called even with top-level stmts** — the entry was either/or, so `def main()` + a def that
  emits entry stmts (case-class/enum default params) ran nothing. Now appends the `main()` call. (default-params ✅)
- **user def wins over ALL html tag globals** (main/label/title/form/table/…), generalizing the above. (data-types ✅)
- **Mirror.elemTypes uses real field types** (String/Int) not hardcoded `Any`. (custom-derives-mirror ✅)


### BREAKTHROUGH 2026-07-06 — most "v2-errors" were a bin/ssc PACKAGING artifact, not v2 gaps

`ssc run --v2` on `bin/ssc` could not load ANY plugin native (`signal`/`element`/…): the launcher keeps
plugin jars off the startup classpath (they ship as `.sscpkg`, lazy-loaded for v1), but `PluginBridge.loadAll`
finds Backends via ServiceLoader on the classpath. So all 10 output-parity "v2-errors" were this artifact —
the SAME programs run fine on the full classpath (bridgeCli). Fixed in `RunV2` (extract each `.sscpkg`
intrinsics jar into a URLClassLoader before `loadAll`). Result: **parity 16/46 → 21/47 (35% → 45%)**, v2-error
10 → 4. Session total: **11/47 → 21/47 (23% → 45%)**. Remaining 4 v2-errors (graph-codecs / typed-object-codec /
object-store-jdbc / spark-schema-mapping) are source-dump measurement artifacts or opt-in (`plugin-available`)
plugins; the 14 mismatches are the real engine gaps (effects shape, SQL/Spark `Stub`/`Op`, content, macros).

### HIGH-VALUE VM bug — foldLeft with a conditional lambda over Doubles (3+ elems) returns the last element

`List(4.0,1.0,10.0).foldLeft(99.0)((a,b) => if a<b then a else b)` → v2 gives **10** (last element), v1 gives
**1**. Standalone `4.0 < 7.0` is correct, 2-element Double folds are correct, and 8-element INT folds are
correct — so it's Double + 3+ iterations (a fast-loop/JIT fold path miscompiling the conditional-accumulator).
Breaks `imports` (min/max) and any Double reduction. **v2 VM territory** (not FrontendBridge) — high priority.

### HIGH-VALUE root cause — v2 does not invoke `def main()`

Diagnosed `default-params` (and it generalizes): a program whose entry is `def main(): Unit = …` produces
NOTHING on v2 — worse, `bin/ssc run --v2` of a bare `def main() = println("x")` prints `_Raw("<main></main>")`
instead of running it (v1 correctly prints `x`). Top-level statements (no `main`) DO run on v2. So the
FrontendBridge/entry path (a) does not call the user's `main()` the way v1's `ssc run` does, and (b) resolves
the name `main` to an HTML `<main>` element that shadows the user function. This one gap silently breaks
EVERY `def main()`-entry example — a high-leverage fix for the FrontendBridge entry semantics + global
resolution (engine work). Fix: after loading, invoke the user `main()` if defined, and don't let an html
tag shadow it.
