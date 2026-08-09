# std-to-repo-root — the shared `.ssc` standard library leaves `v1/`

> Status: **in progress** (2026-08-09) · Claim: `std-to-repo-root`
> Decision: Sergiy, 2026-08-09 — "делать репо-широкий перенос std/ в корень".
> Companion: [`project-partitioning.md`](project-partitioning.md) §4 (which this refines, not
> contradicts), [`scljet-standalone-library.md`](scljet-standalone-library.md) (the precedent).

## 1. What moves, and what explicitly does not

`v1/runtime/std/` holds two unrelated populations that share a directory:

| Population | Count | Fate |
|---|---|---|
| `.ssc` standard-library modules — loose files plus `ui/`, `cluster/`, `mcp/`, `dsl/`, `parsing/`, `mapreduce/` | **108 files** | → root `std/` |
| `*-plugin/` sbt modules (Scala) | 39 | **stay** |
| `markup-js/`, `markup-node/`, `scljet-vfs-host/` — sbt modules that simply lack the `-plugin` suffix | 3 | **stay** |
| `scljet` — a symlink to the repo-root `scljet/` | 1 | **untouched** |

**The 39+3 Scala modules stay, and that is a correction to an earlier reading of mine.**
[`project-partitioning.md`](project-partitioning.md) §4 rules on them directly: *"The v1 side
reads the other way and it is not a defect… because `v1` as a whole IS the compatibility tier."*
v2 has its own std tree (`v2/runtime/std/`, 21 modules). Moving those 39 would merge two
deliberately separate things.

**The 108 `.ssc` files are a different case, and the reason this task exists.** They are the
*only* `.ssc` standard library in the repository — `v2/runtime/std/` contains **zero** `.ssc`
files — and `build.sbt:2360` stages **every one of them** into
`bin/lib/standard/native-front/runtime/std/`. The native (v2) front resolves `std/*` from files
whose path says `v1`. That name stopped being true and now misleads every reader.

Separability was measured, not assumed: **0 of the 108** live inside a `*-plugin/` directory.

## 2. Precedent — this shape already runs in production

`v1/runtime/std/scljet` is a **symlink to `../../../scljet`**, and `build.sbt` stages SclJet
straight from the repo root into `runtime/std/scljet/`. A first-class library living at the root
and appearing inside the `std/` namespace is therefore not an invention here; it is the pattern
the repository already chose once and documented in
[`scljet-standalone-library.md`](scljet-standalone-library.md). This task applies it to the rest.

## 3. The one real risk node — import resolution

Moving the files is trivial. Making them still resolve is not, and it touches the most
load-bearing path in the toolchain: every `.ssc` program resolves `std/*` through
`ImportResolver`.

Two consumers, and they fail differently:

- **Staging (`build.sbt:2360`).** `stdSourceRoot = root/"v1"/"runtime"/"std"` globs `**.ssc`.
  After the move that glob matches **zero files** — a silent, total loss of the staged std with
  no compile error anywhere. Repoint to `root/"std"`. One line, but the failure mode if missed
  is "the installed binary has no standard library".
- **Dev-tree discovery (`ImportResolver.discoverStdRoot`).** It walks up looking for
  `cur/"runtime"/"std"`, which exists via the top-level `runtime -> v1/runtime` compat symlink,
  and its own comment states the assumption out loud: *"a dev tree keeps its std at
  `v1/runtime/std`, so the root does NOT contain `std/`"*. This task inverts exactly that
  sentence. Fix: probe a root `std/` **first**, fall back to `runtime/std` unchanged — additive,
  so any tree that has not moved keeps working.

Installed mode resolves from the staged `…/native-front/runtime/std/`, so it is unaffected once
staging is repointed. The `runtime -> v1/runtime` symlink stays; it serves `v1/runtime/*` for
everything that is not std.

## 4. Verification — the gate has to be able to fail

The failure this change can cause is *silent*: a std module that no longer resolves shows up as
`Import not found`, and a staging glob that matches nothing shows up as nothing at all. So the
checks are about presence and count, not just about green.

1. **Staged count.** After `install.sh --dev`, the count of `*.ssc` under
   `bin/lib/standard/native-front/runtime/std/` **equals 108** (plus SclJet's, unchanged).
   Asserting a number, not non-emptiness — an empty glob and a partial glob both pass "exists".
2. **Dev-mode resolution.** `./bin/ssc run` on a script importing a moved module (`std/crypto`,
   `std/mcp/server`, `std/ui/*`) succeeds. This is precisely the failure
   [`v1-runtime-compat-symlink.md`](v1-runtime-compat-symlink.md) recorded when Phase 1 of the
   v1→v2 migration moved `runtime/` and forgot the resolver.
3. **Installed-mode resolution.** The same, through the staged tree rather than the source tree.
4. **Negative control.** Repoint the staging line back to `v1/runtime/std` and confirm the build
   fails — otherwise the guard was never reading what it claims to.
5. `scripts/smoke-ci`, and the conformance slice covering `std/*` imports.

### 4.1 Two ways the apparatus lied before it worked

Both were caught by running the negative control rather than by reasoning, and both are the
reason §4 is worded around *discrimination* instead of *green*.

**The first guard was useless.** It tested `nativeStdFiles.isEmpty`. Pointed back at
`v1/runtime/std` — the wrong root, the one this whole task moves away from — the deep glob still
matched **23** files, because `v1/runtime/std/scljet` is a symlink to the repo-root `scljet/`
and the glob follows it. A wrong source root would have staged 23 modules instead of 131 and
reported success. The working guard tests for **loose top-level `.ssc`**, which is the actual
discriminator: 58 in the real std tree, 0 in the old one.

**Then the control itself lied, twice, and it was the tooling.** `scripts/sbtc` is a thin client
onto a **warm sbt server**, and that server holds the build definition it loaded at startup. After
editing `build.sbt`, `sbtc` happily reported `[success]` for both arms of the control — and an
unconditional `sys.error` planted in the staging block was *not reached* through `sbtc` while a
fresh `sbt -batch` hit it immediately. Every `sbtc` measurement of a `build.sbt` change is a
measurement of the previous `build.sbt`.

**So: verify a `build.sbt` change with `sbt -batch`, never with `scripts/sbtc`.** `AGENTS.md`
recommends `sbtc` for speed, which is right for source edits and wrong for this one case.

## 5. Order of work

1. `git mv` the 108 files — pure renames, verified with `git diff -M --numstat` (0 insertions,
   0 deletions), the same proof used for `mcp-module-extraction`.
2. `build.sbt` staging repoint.
3. `ImportResolver` root-first discovery.
4. Sweep the remaining path references (18 `.scala`, 6 `.sh`, 3 `.tsv`, ~39 docs) — mechanical,
   but the `.tsv` fixtures feed `tests/e2e/project-partition-gate.sh` and will fail loudly if
   missed, which is the good kind of reference.
5. `project-partitioning.md` gains the new location so the map matches the tree.

## 6. Non-goals

- Moving the 39+3 Scala modules. Ruled on by `project-partitioning.md` §4.
- Removing the `runtime -> v1/runtime` compat symlink. It serves more than std.
- The eventual v1 cutover. This task makes the shared part shared; it does not retire `v1/`.
