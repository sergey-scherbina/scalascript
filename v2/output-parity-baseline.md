# v2 output-parity baseline (v1 vs v2, true output-equality)

Reproduce: `SSC="bin/ssc" scripts/v2-output-parity $(<terminating-list>)`
(build the runner first: `sbt installBin` — `cli` now `dependsOn v2FrontendBridge`, so `bin/ssc run --v2`
routes a source through the v1 frontend → FrontendBridge → the v2 VM).

This is the REAL "does v2 replace v1?" gate: each example is run on v1 (`ssc run`) AND v2 (`ssc run --v2`)
and stdout is diffed. It is far stricter than `scripts/v2-compat-coverage` (exit-0), which reports 96.4%.

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
loop skips — register them in `PluginBridge`.
