# import-cycle-diagnostic — friendly error for module import cycles

Status: **active**. Busi-reported (busi `p5` `dispatch.ssc` decomposition, 2026-06-14).

## Problem

A true module **import cycle** — `A` imports `B`, `B` imports `A` (directly or
transitively; e.g. a sub-module importing back from the facade that imports it) —
currently aborts with a bare `java.lang.StackOverflowError` and **no
module-resolution message**. The cause (a cycle) is invisible from the error, so an
author who accidentally introduces one has nothing to go on.

This is **distinct** from the already-fixed diamond/DAG re-evaluation (busi seq-132):
a *diamond* (`A→B`, `A→C`, `B→D`, `C→D`) is acyclic and is handled correctly by the
shared `moduleCache` dedup. A *cycle* is not — `moduleCache` doesn't catch it.

### Root cause

`SectionRuntime.runImport` loads an imported module via:

```scala
val child = interp.moduleCache.getOrElseUpdate(resolvedPath, {
  val c = Interpreter(...); c.run(childModule); c
})
```

`getOrElseUpdate` only **inserts the value after the thunk returns**. While the thunk
is still running (`c.run` evaluating the module body, which triggers nested imports),
the module is *not yet* in the cache. So a cyclic re-entry on `resolvedPath` misses the
cache and runs the module again → which re-imports its partner → … → unbounded
recursion → `StackOverflowError`.

## Fix

Track the modules **currently being loaded** (on the resolution stack) in a shared,
insertion-ordered set, threaded into child interpreters exactly like `moduleCache`:

- **`Interpreter`** gains a constructor param
  `moduleLoading: mutable.LinkedHashSet[os.Path] = mutable.LinkedHashSet.empty`,
  threaded into every child the same way `moduleCache` is (so the whole import-graph
  run shares one set). Ordered, so the cycle path can be rendered.

- **`SectionRuntime.runImport`**, before the `getOrElseUpdate`:
  ```scala
  if interp.moduleLoading.contains(resolvedPath) then
    val chain = (interp.moduleLoading.toList :+ resolvedPath).map(_.last).mkString(" → ")
    throw InterpretError(s"Import cycle detected: $chain")
  ```
  and wrap the load thunk so the path is marked in-progress only while it runs:
  ```scala
  val child = interp.moduleCache.getOrElseUpdate(resolvedPath, {
    interp.moduleLoading += resolvedPath
    try
      val c = Interpreter(..., moduleCache = interp.moduleCache, moduleLoading = interp.moduleLoading)
      c.run(childModule); c
    finally interp.moduleLoading -= resolvedPath
  })
  ```

The check runs **before** `getOrElseUpdate`, because on a cyclic re-entry the path is
not yet cached, so `getOrElseUpdate` would otherwise re-run the thunk. The `finally`
removes the path so a later *legitimate* import of the same module (after it finished
loading) still hits the cache and is unaffected.

## Non-goals / invariants

- **Purely diagnostic.** No semantic change for acyclic graphs or diamonds — the
  `moduleCache` dedup and re-export behaviour are untouched. `InterpModuleDedupTest`
  must stay green.
- We do **not** attempt to *support* cyclic imports (lazy bindings, forward refs);
  cycles remain an error — just a legible one.

## Verification

`InterpImportCycleTest` (multi-file, real harness — mirrors `InterpModuleDedupTest`):

1. **2-cycle** `a ↔ b` (`a` imports `b`, `b` imports `a`) → `InterpretError` whose
   message contains `cycle`, **not** `StackOverflowError`.
2. **facade↔leaf cycle** (`facade` imports `a` + `b`; `a` imports back from `facade`)
   → same.
3. **control**: an acyclic re-export (`user → facade → leaf`, leaf does not import back)
   still resolves and computes correctly (re-export unaffected).

Plus `InterpModuleDedupTest` re-run for no-regression.
