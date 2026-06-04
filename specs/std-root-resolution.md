# Standard-Library Root Resolution (Specification)

Status: **planned**. Tracked as `std-root-resolution` in `BACKLOG.md` /
`WORK_QUEUE.md`. Companion: [`specs/arch-distribution.md`](arch-distribution.md).

---

## 1. Goal

Let any `.ssc` program import the standard library with a **bare, well-known
path** — `import` linking to `std/money.ssc`, `std/ui/primitives.ssc`, etc. —
**without** relative `../../` navigation and **without** the project having to
set `ssc.std.path` / `ssc.lib.path` by hand.

This is what makes an *external* project (e.g. the `busi` accounting app, which
lives outside the ScalaScript tree) able to consume std modules like
`std/money.ssc`.

## 2. Current state (the gap)

`ImportResolver` resolves a bare `std/foo.ssc` import against `stdPath`:

```
stdPath = -Dssc.std.path   orElse  -Dssc.lib.path / $SSC_LIB_PATH
```

- The dev launcher sets `-Dssc.std.path=<repo>/runtime` (so `std/foo.ssc` →
  `<repo>/runtime/std/foo.ssc`).
- An installed launcher sets `ssc.lib.path` to the install root.
- **With neither set, a bare `std/foo.ssc` import fails** with "file not found".

So a project that just runs `java -jar ssc.jar app.ssc` (no launcher props) has
no std root. That is the gap.

## 3. Design — discovery with override

`stdPath` becomes a **discovery chain**. The first candidate that exists wins.
`stdPath` always denotes the directory that *contains* a `std/` subdirectory
(matching today's `<repo>/runtime` convention).

| # | Source | Root returned |
|---|---|---|
| 1 | `-Dssc.std.path=<dir>` (**override**) | `<dir>` |
| 2 | `$SSC_STD_PATH` (**override**) | `<dir>` |
| 3 | `-Dssc.lib.path` / `$SSC_LIB_PATH` (existing) | that root |
| 4 | **jar-dir/std** — directory of the running `ssc.jar`, if it has `std/` | `<jar-dir>` |
| 5 | **dev walk-up** — nearest ancestor of the jar dir containing `runtime/std` | `<ancestor>/runtime` |
| 6 | **`~/.scalascript/std`** — home install, if it has `std/` | `~/.scalascript` |

- **Override (1, 2)** always wins, so existing launchers and tests are
  unaffected and a user can always point elsewhere.
- **jar-dir (4)** supports a packaged release that ships `std/` beside `ssc.jar`.
- **dev walk-up (5)** makes the repo's own `tools/cli/target/.../ssc.jar` work
  with zero configuration (it finds `<repo>/runtime/std`) — this is what lets
  `busi` run against the dev jar immediately.
- **home (6)** supports an explicit per-user install (`~/.scalascript/std`),
  populated by a release installer or `cp -R runtime/std ~/.scalascript/std`.

The resolution *usage* (`stdPath / "std/foo.ssc"`, plus the existing
`libPath/runtime/...` secondary fallback) is unchanged — only how the default
root is discovered changes.

## 4. Implementation

- `ImportResolver`:
  - Extract a **pure, testable** `discoverStdRoot(prop, env, lib, jarDir, home)`
    implementing the table above.
  - `stdPath` calls it with the real system property / env / jar location / home.
  - `jarDir` derives from `getProtectionDomain.getCodeSource.getLocation`
    (the running jar, or the classes dir in dev), guarded against null/errors.
- No change to launchers; `ssc.std.path` remains the authoritative override.

## 5. Verification

- Unit tests for `discoverStdRoot` covering each precedence rule with temp dirs:
  override beats lib beats jar-dir beats walk-up beats home; missing candidates
  are skipped; nothing set → `None`.
- An end-to-end check that a bare `std/…` import resolves when only the
  jar-dir / home candidate is present.
- No regression in existing import/resolution tests.

## 6. Out of scope

- A release installer that populates `~/.scalascript/std` (distribution concern;
  `arch-distribution.md`). This spec makes the *resolution* work; shipping the
  files is separate.
- `std:` URI scheme or package-style `import std.money` syntax — the link target
  stays a path (`std/money.ssc`).
