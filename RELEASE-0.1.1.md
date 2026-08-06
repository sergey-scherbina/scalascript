# v0.1.1 — prepared, not published

`compile-jvm` is broken in **every** published v0.1.0 native binary, on all three platforms:

```
compile-jvm error: JVM runtime resource not found on classpath:
  /scalascript/jvm-runtime/stubServeRuntime
```

It works on the JVM launcher, so the defect is native-only: the command inlines Scala source
fragments held as classpath resources, and a native image carries only what `resource-config.json`
names. Nothing named them. Fixed on `main` in `1bd9374d4` (one pattern, `[A-Za-z0-9-]+-sources/.*`,
because three separate families were missing and each surfaced only as the next failure).

## What this branch contains

`ThisBuild / version := "0.1.1"` and nothing else. Every actual fix is already on `main`; this branch
exists so the release can be verified before anything is published.

The emitted dependency coordinate in `Main.scala` deliberately still says **0.1.0** — it names the
last PUBLISHED release, and 0.1.1 is not published yet. Bump it *after* this ships, not before, or
`ssc` writes a coordinate into user projects that resolves nowhere.

## Verified here, locally

| check | result |
| --- | --- |
| `install.sh --dev` | clean, 0 errors |
| `ssc-tools --version` | `ssc 0.1.1` |
| native image | built, 2m59s |
| **`compile-jvm` on the native binary** | **writes the artifact** (this is the fix) |
| `run --v2` / `run --v1` | 84 / 84 |
| `--bytecode` on the native binary | refuses, rc=2, empty stdout |
| qualifier self-test | 64 compare-first cases, green |
| smoke | 69/69 green |

One smoke run failed first at `response-transforms` ("server never listened"). It passes twice
standalone and the full suite is green on a re-run; the failing run took 697s against a 600s budget
on a host running several agents' builds. Contention, not this change — recorded rather than
silently retried.

## To publish (Sergiy's call — pushing the tag is what publishes)

```
git push origin release/0.1.1:main     # or merge the branch
git tag -a v0.1.1 -m "ScalaScript v0.1.1"   # on that commit
git push origin refs/tags/v0.1.1
```

A tag named `v*.*.*` triggers `native-release.yml`; the `release` job publishes only on a tag push,
so nothing here can publish by itself. A local `v0.1.1` tag exists in this worktree for verification
(the version gate requires a plain version to sit on its own tag) and has **not** been pushed.

Afterwards: bump `Main.scala`'s coordinate to 0.1.1, and return `ThisBuild / version` to
`0.2.0-SNAPSHOT`.
