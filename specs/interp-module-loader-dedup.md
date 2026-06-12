# Interpreter module-loader dedup (diamond import memoization)

Slug: `interp-module-loader-dedup` · Reported by busi (rozum seq-132) · 2026-06-12

## Problem

The interpreter module loader (`SectionRuntime.runImport`) creates a **fresh
`Interpreter` and re-runs the imported module on every import edge** — there is no
cache keyed by module path. In a diamond import graph this re-evaluates shared
modules once per path through the DAG, which is **exponential in the number of
diamond layers**. Over a large module (busi's `dispatch.ssc`, ~7942 lines) a single
extra diamond edge tips it into OOM / hang at interpretation load time — 0 lines of
the program run.

`ssc check` (the typer) is unaffected: it memoizes module loading by name. Only the
**interpreter** loader re-evaluates. This blocks busi's monolith modularization
(ph-2): domains can't be split into modules that import shared logic via `[name](path)`.

### Repro (3 modules)

```
// big.ssc        — exports a value (stands in for the 7942-line module)
def bigValue: Int = 42

// spi.ssc         — imports big (a second edge onto big)
[bigValue](big.ssc)
def viaSpi: Int = bigValue

// entry.ssc       — imports BOTH big and spi → diamond on big
[bigValue](big.ssc)
[viaSpi](spi.ssc)
println(bigValue + viaSpi)
```

With N stacked diamond layers, `big` is evaluated 2^N times without the cache; once
with it. A top-level `println("loading big")` in `big.ssc` prints once per evaluation,
making the duplication directly observable in a test.

## Mechanism

`SectionRuntime.runImport` (per `[names](path)` statement):
1. resolves `path` → `resolvedPath`,
2. `Parser.parse(os.read(resolvedPath))`,
3. `val child = Interpreter(...)`  ← **fresh every time**,
4. `child.run(childModule)`        ← **re-executes the whole module subtree**,
5. merges the child's exports (`globals`/`parentTypes`/`typeMethods`/…) into the importer.

Steps 3–4 repeat for every edge. No shared cache means a module reachable by K paths
runs K times, and each of those runs re-runs *its* imports K' times, etc.

## Fix

Memoize the evaluated child interpreter **by resolved absolute path**, in a cache
**shared across the entire import graph** of one run:

- Add a constructor param `moduleCache: mutable.Map[os.Path, Interpreter]` to
  `Interpreter`, defaulting to a fresh empty map (so a top-level `Interpreter(...)`
  owns one cache for its whole run).
- In `runImport`, build/run the child via
  `interp.moduleCache.getOrElseUpdate(resolvedPath, { … new child, run it … })`, and
  **pass `interp.moduleCache` to the child constructor** so nested imports share the
  same cache. A module is then evaluated exactly once per run; subsequent edges reuse
  the cached child and only re-merge its (already-computed) exports into the importer.

Module-init side effects (top-level statements) now run **once per run**, matching the
typer and standard module semantics (ES/Python modules init once). Each importer still
performs its own binding/merge of the shared child's exports into its own tables, so
per-importer aliasing, shadowing, and content registration are unchanged.

### Edge cases / non-goals

- Cache key = `resolvedPath` (absolute, post-`ImportResolver`), so two spellings of the
  same file collapse correctly.
- `registerImportedContent` still re-parses the module for content registration (cheap,
  linear in edges; not the exponential cost). Optional: cache the parsed `Module` too.
- Cyclic imports: out of scope here (pre-existing behavior preserved); the cache does
  not by itself introduce or fix cycle handling.

## Verification

- 3-module diamond test (`InterpModuleDedupTest`): a top-level side effect in the shared
  module fires **exactly once** (assert a counter / captured stdout), and the program
  produces the correct result.
- A deeper (2–3 layer) diamond completes quickly (would hang/OOM pre-fix).
- Full `backendInterpreter/test` green (the change is additive memoization on the load
  path; existing single-edge imports behave identically).
