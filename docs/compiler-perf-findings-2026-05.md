# Compiler performance — measurement findings (2026-05-30)

A measurement-first investigation of the ScalaScript compiler pipeline,
following `docs/optimization-roadmap.md`. The headline result: **most of the
roadmap's compiler-perf items are already implemented**, the typer is
negligible, and parsing — though dominant — is largely irreducible
(scalameta). A small, safe preprocessor optimization was the only remaining
hot-path win and is included here.

## Methodology

- This machine is noisy. Wall-clock deltas are only trustworthy from
  back-to-back **same-session A/B** on a warm JVM; cross-run numbers drift
  ±15–40% under load (observed: identical code measured 52 ms one run, 84 ms
  the next).
- The reliable signals used here: per-phase timing via `--Ystats`
  (`CompileStats`), and **isolated per-call `nanoTime`** around individual
  sub-phases inside one warm process (8–10 warmup iters + 30–50 measured).
- Corpus: `runtime/std/` — 74 `.ssc` modules, ~8,800 lines.

## 1. Phase timing — the typer is not the hotspot

`--Ystats` was wired into the build path but not the typer (which runs only in
`ssc check`/`checkOneFile`). After instrumenting it (commit `0e883524`):

```
ssc check --Ystats runtime/std/   (74 modules, cold JVM)
parse  4503 ms  98.3%
typer    77 ms   1.7%
```

This **overturned the roadmap's assumption** that `SType.subst`/the unifier
was the hotspot. The parser dominates by ~58×.

## 2. Steady-state confirmation (warm JVM)

A throwaway in-process warm loop (8 warmup + 30 iters), parse-only vs
`typeCheckStrict`-only over the 74 modules:

```
warm parse   52–80 ms   98.7%
warm typer   0.7–1.1 ms  1.3%      ratio ≈ 76×
typer sanity: completed=74  threw=0  totalErrors=7
```

Both caveats from the cold run are ruled out: (1) cold JIT did not mask the
typer — warm parse fell 4503→52 ms but the ratio **held and widened** to 76×;
(2) the typer is not short-circuiting — all 74 modules fully type-check
(`threw=0`). **The parser genuinely dominates; the typer is not worth
optimizing.**

## 3. Roadmap audit — most items already shipped

A code read of `Parser`, `ModuleGraph`, and `Typer` shows the roadmap's
compiler-perf phases are already done:

| Roadmap item | Status | Evidence |
|---|---|---|
| 2a parser double-parse under `package:` | **DONE** | `extractSections(..., skipInitialParse = pkg.nonEmpty)` (commit `36deaca0`) — blocks parse once on both paths |
| 2b iterative `Scope.lookup` | **DONE** | same commit `36deaca0` |
| 2c cache `createPrelude` | **DONE** | `Typer.sharedPrelude` cached val, used when `extraBuiltins.isEmpty` |
| 3a parallel parse | **DONE** | `ssc check` uses a `FixedThreadPool`; build path uses `CompletableFuture` ("Phase 3a: parallel read+parse pre-pass") |
| 1 typer allocation (`subst`/unifier) | **not worth it** | typer is 1.3% of parse |

## 4. Parse sub-phase breakdown

Warm in-process timing of `Parser.parse` sub-phases over the corpus:

| sub-phase | ms/iter | share | nature |
|---|---|---|---|
| scalameta | ~38 | ~83% | **irreducible** — the Scala parser itself |
| preprocess | ~6.3 | ~13% | our code — the 9-pass `PreprocessorRegistry` chain |
| markdown (CommonMark) | ~2 | ~4% | library |
| misc (section walk, derivers) | ~7 | — | our code |

**Conclusion: parsing is the wall, but ~83% of it is scalameta and cannot be
optimized without replacing the parser backend.** The only meaningful
our-code lever is the preprocessor chain.

## 5. Preprocessor optimization (the one remaining hot-path win)

Per-pass timing (baseline) showed each pass re-scanning the full block, with
several passes **compiling regexes per call** and allocating
`linesIterator.toArray` before their guard, plus `applyAll` **re-sorting the
registry on every block**:

```
baseline per-pass:  extern 1.50  list 0.95  slash 0.95  numeric 0.75
                    effects 0.64  remote 0.50  inline 0.49  ...   (~6.1 ms)
```

Changes applied (all behavior-preserving):

1. **Hoist per-call regexes to object-level `val`s** — `extern*`, `bodyless*`,
   slash-import, effect, remote-def patterns now compile once, not per block.
2. **Cheap literal fast-guards** before the `linesIterator.toArray`
   allocation: `extern`, `import`, `effect`, `remote`, `](`. Each transform is
   a proven no-op when its trigger literal is absent, so the guard cannot
   change output. Token frequency in the corpus (per module): extern 23/74,
   import 14/74, effect 8/74, remote 6/74 — most blocks skip the pass entirely.
3. **Cache the priority-sorted preprocessor list** in `PreprocessorRegistry`,
   invalidated on `register` (registration is init/plugin-load, never hot).

Per-pass timing (optimized), guarded passes drop sharply:

```
optimized per-pass: extern 1.08 (−28%)  slash 0.48 (−49%)
                    effects 0.20 (−69%)  remote 0.29 (−42%)   (~4.8 ms)
```

**Caveat on aggregate impact:** because scalameta is ~83% of parse, shaving
~1.3 ms off the ~6 ms preprocess phase is only ~2–3% of total parse wall —
real but small, and below this machine's wall-time noise floor. The change is
justified as a pure micro-optimization (compile-once, skip-when-absent,
sort-once) that cannot regress, not as a headline speedup.

## 6. Bottom line / recommendation

The compiler-perf roadmap (Phases 1–3) is **effectively complete or
deliberately not worth pursuing**:

- Parsing dominates (98%) but is already single-pass and already parallelized
  across cores; the residual is intrinsic scalameta cost.
- The typer is 1.3% — not worth touching.
- The preprocessor chain is now hoisted/guarded/cached.

The only genuinely-untouched area is **Phase 4 (memory footprint)** — dropping
`Module.sourceText` after typing, packing `Position` into a `Long`, nulling
the scalameta tree after lowering. Its value is unknown without a `-prof gc`
allocation profile and would help GC pressure on large projects, not the parse
wall. **Recommend: do not invent further hot-path work; pursue Phase 4 only if
a GC/allocation profile justifies it.**
