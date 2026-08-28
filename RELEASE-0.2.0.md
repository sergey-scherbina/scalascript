# v0.2.0 — prepared, not published

## What this branch contains

`ThisBuild / version := "0.2.0"` in the root and in `uniml/build.sbt` (they must agree —
`UnimlCoordinatesSpec` checks it, and passes here). Every actual fix is already on `main`; this
branch exists so the release can be verified before anything is published.

The emitted server-backend dependency coordinate in `Main.scala` deliberately still says
**0.1.1** — it names the last PUBLISHED release, and 0.2.0 is not published yet
(`emitted-server-backend-coordinate-resolves-nowhere`, `tests/e2e/emitted-coordinate-is-published.sh`).
Bump it *after* this ships and the Maven tree for 0.2.0 is committed under `releases/maven/`
(`sbt publishServerBackends`), not before.

## Scope since v0.1.1

**3,807 commits.** `CHANGELOG.md`'s "0.2.0 development" entry covers 2026-08-06 → 2026-08-18 at
feature granularity (3,351 commits, reconstructing them individually would be invention — the
per-bug record is `BUGS.md` and its eight sibling boards, 1,218 entries). The stretch from
2026-08-18 to today is not yet folded into the changelog as per-task entries; `git log
v0.1.1..v0.2.0 --oneline` and the BUGS boards are the record for that window until it is.

The one-line version: the self-hosted front (`F`) is the default native compiler front, v3's
corpus score moved from 183 to 259/369 on the executor, and — this week specifically — the
`v2.1` negative-toolchain release gate went from reporting `cancelled` (no verdict at all, for
weeks) to green, via seven fixes, most of which turned out to be visibility problems rather
than new breakage: a stale `std/` root, a dropped constructor-pattern qualifier, a registry
scoped to the wrong lowering call, a cache that starved a fast path, and a CLI message ordered
by host instead of by the program's own defect.

## Verified here, locally

| check | result |
| --- | --- |
| `install.sh --dev` | clean, 0 errors |
| `ssc-tools --version` | `ssc 0.2.0` |
| `UnimlCoordinatesSpec` | passes — root and uniml agree at `0.2.0` |
| full CI tier, on the commit this branch is built from (`8db21a8f1`) | **green, 20/20 jobs** — `Validate`, `negtc release gate (reduce)`, all 4 conformance shards, all 4 sbt test shards |
| `negtc release gate`'s own verdict | `release.ready true`, `parity.mismatch 0`, `parity.one-sided 0`, `runtime.blockers 0`, `frontend.ok` 205/214 (floor 200) |
| smoke-ci | (recorded when this file is finalized — see below) |

No native-image build was run locally for this file; that is what the tag push exercises on all
three release runners (`native-release.yml`), and it is not skippable — see `AGENTS.md` on
capacity gaps in that job before assuming a local `sbt cli/nativeImage` stands in for it.

## To publish (Sergiy's call — pushing the tag is what publishes)

```
git push origin release/0.2.0:main     # or merge the branch
git tag -a v0.2.0 -m "ScalaScript v0.2.0"   # on that commit
git push origin refs/tags/v0.2.0
```

A tag named `v*.*.*` triggers `native-release.yml`: three ~1-3h `native-image` builds
(linux-x86_64, macos-arm64, macos-x86_64), each qualified against an isolated checkout, then a
`release` job that publishes a GitHub Release only on the tag push — nothing here can publish by
itself, and nothing publishes if any platform's qualification fails.

Afterwards: bump `Main.scala`'s coordinate to `0.2.0` (after `releases/maven/` carries 0.2.0
artifacts), and return `ThisBuild / version` to `0.3.0-SNAPSHOT` in both build files.
