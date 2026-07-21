# SclJet as a standalone version-independent `.ssc` library

## Goal

SclJet is a self-contained SQLite engine written entirely in ScalaScript. It
must be a **standalone library** — not organizationally owned by `v1/` — and
work on **every** ScalaScript tier (v1 interpreter, native VM, direct ASM, JS,
and the v2 standard/native-front tier), independent of the compiler version.

## Status: DONE — first-class library root, symlink dropped (2026-07-21)

The library source lives at the repo-root **`scljet/`** (standalone, not under
`v1/`) and is resolved as a **first-class library root** — no compatibility
symlink. The former `v1/runtime/std/scljet → ../../../scljet` symlink has been
**removed**; the resolvers map the `std/scljet/…` import to the standalone
`scljet/` directory directly (Option A, implemented):

- **`installBin`** stages the library from repo-root `scljet/` directly into
  `bin/lib/native-front/runtime/std/scljet` (and thus `standard/native-front`) —
  it no longer relies on a `**` glob following the symlink.
- **`ImportResolver`** discovers a first-class SclJet root (`scljetPath`, see
  `discoverScljetRoot`) and, when a `std/scljet/…` import is not found under the
  normal std root, re-resolves it against `<scljetRoot>/…`. A packaged/staged
  install keeps a real `runtime/std/scljet/` tree, so this fallback only fires in
  the dev tree; the installed layout stays byte-identical.
- **Native/JS loaders and `check-stdlib-interface-load`** resolve through
  `ImportResolver` (native front) / the staged `runtime/std/scljet` tree, so no
  per-loader special-case is needed once the two points above are in place.

Verified: full `scljet-*` conformance on `[int, js]`, native `ssc run`, and a
non-scljet std case still green. `scljet/` contains only SclJet with no `v1/`
path dependency, and no symlink remains.

## Substantive requirement is already met

The SclJet `.ssc` code is **already version-portable**: it runs on the v1
interpreter, the bytecode VM, the ASM JIT, the pure tree-walk fallback, JS
(6/6 conformance cases on `[int, js]`), and the native `ssc run` (v2 standard)
tier — verified via conformance (`scljet-*`) and the write-path examples running
under `bin/ssc run`. What remains is purely **organizational + resolution**: the
source lives under `v1/runtime/std/scljet/` and every tier resolves it as the
std import `std/scljet/…`.

## Why a naive `git mv` is not enough (verified 2026-07-13)

Moving `v1/runtime/std/scljet/` to a repo-root `scljet/` and adding an installBin
staging block makes the **native/v2** tier find it (`bin/lib/*/native-front/
runtime/std/scljet`), but breaks the **v1 interpreter**: `ImportResolver`
(`v1/lang/core/src/main/scala/scalascript/imports/ImportResolver.scala`) resolves
a bare `std/foo.ssc` against a `stdRoot` that is "the nearest ancestor containing
`runtime/std` → `<ancestor>/runtime`" — i.e. `runtime/std/scljet` (the `runtime`
symlink → `v1/runtime`). With scljet moved out, `--v1` throws
`Import not found: std/scljet/index.ssc`. The move was reverted to keep the
shared build green.

## The resolvers/consumers that must all change together

1. **build.sbt `installBin`** (~line 1946): globs `v1/runtime/std/**.ssc` into
   `bin/lib/native-front/runtime/std/`, then copies that tree into
   `bin/lib/standard/native-front/`. Must also stage the standalone scljet.
2. **`ImportResolver`** (`v1/lang/core/.../imports/ImportResolver.scala`): the
   v1 interpreter's `std/` → `runtime/std/` mapping. Needs to find scljet at the
   standalone location (a `ssc.std.path`-style additional root, or a package map).
3. **Native/JS resolution** and **`check-stdlib-interface-load`**
   (`v1/tools/cli/.../Main.scala` ~5750, `RunNativeV2.scala`): the static and
   native import loaders that also walk `runtime/std`.
4. **All consumers' import paths**: `tests/conformance/scljet-*.ssc`,
   `examples/scljet-*.ssc`, `tests/tools/scljet-*.ssc`, and the corpus tools use
   `[…](std/scljet/index.ssc)`. If the import path changes (Option B), every one
   updates.

## Options

- **Option A — keep the `std/scljet` import path, add a standalone source root.**
  ✓ **Implemented (2026-07-21).** Teach the resolver a first-class `scljet/`
  root for `std/scljet/*` in addition to `runtime/std/`. Consumers are unchanged.
  Smallest blast radius; the mapping `std/scljet → scljet/` is a resolver-level
  fallback (`ImportResolver.scljetPath`) plus a direct `installBin` staging step.
  The compat symlink is dropped.
- **Option B — a real library import (`scljet/index.ssc` or a dep spec).**
  Move to repo-root `scljet/`, change every consumer import from
  `std/scljet/index.ssc` to a library/dep path, and give the resolvers a library
  root. Cleaner conceptually (scljet is no longer "std"), larger consumer churn.

Recommendation: **Option A** first (minimal risk; delivers "not under v1" +
"resolvable everywhere"), with Option B as a later polish if scljet should stop
being a `std/` import at all.

## The JVM VFS adapter

`v1/runtime/std/scljet-vfs-plugin/` (Scala) is the JVM host that gives SclJet
real-file access — a host integration, not the pure library. It can stay a v1
plugin or move alongside as an optional host adapter; it is out of scope for the
pure-`.ssc` relocation.

## Done-when (satisfied 2026-07-21)

`installBin` stages scljet from the standalone location; `std/scljet` resolves on
`--v1`, native VM/ASM, JS, and `ssc run` (v2); full conformance (not just scljet)
is green; `scljet/` contains only SclJet with no `v1/` path dependency; and **no
compatibility symlink remains** — the resolvers know the first-class `scljet/`
root.
