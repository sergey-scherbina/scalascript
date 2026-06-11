# Intrinsic / user-definition name-collision policy

Source: `busi-p3-ratelimit-intrinsic-shadow` (busi feedback wave 2026-06-06).

## Problem

A plugin intrinsic (e.g. `rateLimit`, provided by the `auth-plugin`) and a
user-defined top-level `def rateLimit(...)` share the same bare name. Plugin
intrinsics are registered into `interp.globals` by `installNativeIntrinsicEntries`
(eagerly when an imported `extern def` triggers `ensurePluginsLoaded`, or lazily
on a later name miss). A user `def` is registered into `globals` while the module
body runs.

Because both write the same `globals(name)` slot, the **last writer wins**, and
which one that is depends on lazy-load timing — non-deterministic from the user's
point of view. In the busi repro the intrinsic silently shadowed the user's
`rateLimit`, with no diagnostic.

## Policy: user wins + warning

The **user definition always wins**; a warning is emitted so the shadow is never
silent. This mirrors ordinary lexical shadowing in Scala (a local/explicit
definition shadows an imported one) and never breaks existing user code.

Two orderings produce the same observable outcome:

1. **Native installed first, user `def` later** (the common case — imports are
   processed before the body). The user `def` overwrites the plugin
   `NativeFnV` in `globals`. We detect this in `StatRuntime` (`case Defn.Def`,
   top-level only) and emit a warning before letting the overwrite proceed.

2. **User `def` first, plugins load later** (lazy load after a body name miss).
   `installNativeIntrinsicEntries` finds a user `FunV` already in `globals`. It
   **keeps the user binding** (does not install the native) and emits a warning.

In both cases the call site resolves to the user's function.

## Scope and guards

- Only **bare-name** plugin intrinsics participate (names tracked in
  `pluginNativeNames`). Qualified intrinsic access is unaffected.
- Only **top-level** user defs (`env eq interp.globals`) collide. A `def` local
  to a function/block is normal lexical scoping and never warns.
- `extern def` stubs do **not** create a `FunV` (they are skipped so the
  intrinsic binding survives) — they are not user definitions and never warn.
- Each colliding name warns **once** per interpreter (tracked in
  `shadowedIntrinsicWarnings`) to avoid noise from repeated lazy loads.

## Warning format

```
[warn] 'rateLimit' shadows plugin intrinsic 'rateLimit' — user definition wins
```

Emitted to `System.err`. Also recorded in `interp.shadowedIntrinsicWarnings`
(a `LinkedHashSet[String]` of colliding names) so tests and tools can assert on
the set without scraping stderr.

## Behavior checklist

- [ ] User top-level `def rateLimit` is the function called, regardless of
      plugin load timing.
- [ ] The colliding name is recorded in `shadowedIntrinsicWarnings`.
- [ ] A `[warn]` line is printed to stderr exactly once per name.
- [ ] A local `def rateLimit` inside another function does not warn and does not
      affect the global intrinsic.
- [ ] A user module that does *not* redefine an intrinsic still resolves the
      intrinsic normally and produces no warning.
