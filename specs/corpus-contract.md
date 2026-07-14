# Spec: corpus contract — the always-on differential gate & the road to a minimal, portable v2.2

Written 2026-07-14 (opus) at Sergiy's direction ("scalascript v2.2 минимальный
самодостаточный и быстрый, все фичи на всех платформах, не переделывать всё время";
then "усиливаем, разбираем, портируем"). Grounded in `tests/conformance/contract.sc`,
`tests/conformance/CONTRACT.md`, `tests/conformance/V2-GAP.md`, and the corpus baseline.

## The problem this solves

Every feature's runtime semantics is duplicated **N backends × M features** — interp,
JS (`effects.mjs` / `core-*.mjs`), JVM, Rust, v2 VM. A single bug class (Long/BigInt,
effect lowering, tuple render, operator dispatch) lives independently in each and
drifts. That drift is the "переделывать всё время" tax: this arc alone fixed 12 such
JS-only bugs, each of a class that also exists in the other backends.

The fix is NOT one grand rewrite (see "portability wall" below — it's blocked). It is:
a **frozen differential contract** that makes any cross-backend divergence instantly
visible, so the necessarily-duplicated backends can be kept in lock-step and the v2
migration done as strangler-fig (grow v2 behind the gate, flip lanes case-by-case).

## What exists (LANDED)

### The gate — `tests/conformance/contract.sc`
One differential runner over BOTH corpora (`tests/conformance/*.ssc` + `examples/*.ssc`,
~390 cases after skips) × lanes `{int, js, v2}` (jvm via `--lanes`). Golden = an
`expected/<name>.txt` if present, else the live interpreter (double-run → auto-skips
non-deterministic cases). Each lane → `PASS/DIVERGE/FAIL/TIMEOUT`, compared to a frozen
`corpus-baseline.tsv`. RED on a regression (baseline PASS → non-PASS) or a
closed-but-unrecorded gap. Documented feature-gaps stay green. Bounded parallelism
(workers ≤ 4) + retry-on-timeout → ~8-10 min, stable; nightly workflow
`corpus-contract.yml`. Non-hermetic cases (Spark/SQLite/http/server) excluded via
`corpus-skip.txt`. Full mechanism: `CONTRACT.md`.

NOTE on the v2 lane: the contract's `v2` lane is `bin/ssc run --v2` (the v1-frontend →
v2-VM **bridge**). The self-hosted **`--bytecode`** lane (ssc1/native frontend → v2 VM)
is tracked separately under SPRINT `v2-native-conformance`. Both roll up to "v2 works".

### The v2 gap map (`V2-GAP.md`) — разбираем
67 v2-non-PASS cases clustered by root cause. **The v2 gap is integration, not
language:** ~57% is "register an existing v1 plugin/intrinsic/effect into v2" (unbound
global 25, Actors-scope 10, unhandled-effect 3 — content-toolkit→8, actor-cluster→10,
derived codecs→6, PDF→3, MCP→2); ~19% is v2 frontend parse gaps (the UniML/frontend
track); the rest is 5 rendering diffs + 2 example artifacts + ~4 one-offs.

### Portability fixes landed via the sweep
- `js-parenless-def-value` — a bare parenless-def reference now evaluates (`f`→`f()`).
- `js-user-operator-dispatch` — overloaded operators on user types (`++`/`/`/…) dispatch
  to their extension (`_tupleConcat`/`_arith` → `_dispatch` for `_type` objects), like
  the earlier `|`-as-bitwise fix. (Fixed dsl-ast-builder.)

## The portability wall (KEY FINDING — reshapes "portable runtime")

Effect runners (`runState`/`runLogger`/`runStream`) **cannot** be written as portable
`.ssc` functions. `handle` only intercepts effects from a *syntactic inline* effectful
expression; a body passed as a parameter (`handle(body)` / `handle{body}`, by-value or
by-name) is NOT caught — "Unhandled effect" on both int and js. Confirmed empirically;
there is no `def run…handle` anywhere in the codebase. This is why runners are native
block-forms (`state-effect-plugin` etc.), duplicated per backend. Making runners
portable would require dynamically-scoped effects (a deep, per-backend effect-core
change) — a separate foundational project, not a rewrite slice.

**Therefore "портируем the runtime" = eliminate cross-backend DIVERGENCES** (features
that run but differ), using the contract as the guardrail — not rewrite the runtime.
New pure library code should still be written once in `.ssc` where it has no effects and
no primitive dependency.

## Open work — see SPRINT `corpus-contract`

The baseline (int golden = 0; **js 34, v2 64** non-PASS) is the migration progress
bar; it shrinks case-by-case. The 2026-07-14 sweep #2 closed **26 js cases** — the
entire remaining *divergence/codegen* class: cons-infix patterns (`case h :: t`, the
big one — was wholesale broken), block-scoped destructuring `val (a,b)=e`, f-string
format specs, `summon[Mirror.Of[T]]`, `???`, actor-Option tags, `String.takeWhile`,
`List.splitAt`, actor leaderHistory, and 8 stale scljet entries (see SPRINT
`corpus-contract` → "js codegen/runtime bugs closed"). The **remaining 34 js entries
are genuine feature-gaps** (missing plugins on js: Graph, totp, crypto, fetchUrlSignal,
quoted-macro, JDBC/h2, MCP, typeddata codecs) — plugin ports, not codegen fixes.
Highest-leverage next: v2 plugin wiring (V2-GAP order) and promoting the contract to a
per-PR gate (memo F2 + batch F4, per `conformance-perf.md`).
