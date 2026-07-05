# v1-runtime-compat-symlink

## Problem

`b433a41e4` ("Phase 1 of v1→v2 migration") moved `runtime/` (and `lang/`, `tools/`) to `v1/runtime/`
and updated `build.sbt`, `install.sh`, and in-repo dev tooling accordingly. It did not update
`ImportResolver.scala`'s **installed-mode** stdlib fallback, which still hardcodes the relative
segment `"runtime"`:

```scala
// resolve(), installed-mode fallback:
libPath.map(_ / "runtime" / os.RelPath(pathThroughDep)).filter(os.exists)
```

`bin/ssc`'s launcher sets only `-Dssc.lib.path=$_SSC_ROOT` (the directory containing `bin/`), not
`ssc.std.path` — so any freshly-installed `ssc` binary looks for stdlib at
`$ssc.lib.path/runtime/std/*.ssc`, which no longer exists (it's `$ssc.lib.path/v1/runtime/std/*.ssc`
now). Confirmed empirically: `install.sh --dev` followed by
`./bin/ssc <script importing std/crypto.ssc>` fails with `Import not found: std/crypto.ssc`.

This is not sbt-test-suite-visible: `TestPaths.repoRoot` walks up to the directory containing
`build.sbt` (still at the top level) and several tests reference `repoRoot / "runtime"` directly —
those may already be failing too, or may be passing via a different resolution path not audited
here. Out of scope for this fix; flagged in the claim note.

## Fix

Add a top-level `runtime -> v1/runtime` symlink, committed to git. Zero source changes, zero risk to
the in-flight v1→v2 migration's own phases — a pure backward-compat shim for any consumer relying on
the pre-Phase-1 top-level layout (installed `bin/ssc` builds, external submodule pins like busi's).

Remove this symlink whenever a later migration phase either updates `ImportResolver.scala` to resolve
`v1/runtime` directly, or removes the `v1/` layout entirely (v2 cutover).

## Verify

- `./bin/ssc <script importing any std/*.ssc>` succeeds after a fresh `install.sh --dev`.
- `sbt compile` / existing test suites unaffected (symlink is inert to anything that doesn't resolve
  through the installed-mode `ssc.lib.path` fallback).
