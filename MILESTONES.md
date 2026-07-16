# Milestones

> Navigation index. Full content lives in three files:

| File | Contents |
|------|----------|
| [BACKLOG.md](BACKLOG.md) | Open and planned milestones — what still needs to be done |
| [SPRINT.md](SPRINT.md) | Agent task queue — active pending tasks |
| [CHANGELOG.md](CHANGELOG.md) | Completed milestones, newest first |

---

## Where we are going (2026-07-16, confirmed with Sergiy)

**Three streams, run in parallel. All three are current — none is a side quest.**

### 1. v2 self-hosting — one chain

Retire scalameta and the old ssc0 front; ScalaScript compiles itself, on its own toolchain.
This is the spine: the `C_min` compiler-for-L-in-L fixpoint already holds (stage1 == stage2,
byte-identical, no quine), and the **new self-hosting front** is replacing `ssc1-front`+`ssc1-lower`
byte-identically against the frozen Core IR.

- **Status: newfront Phase 1 at MATCH 478/499 (96%)** — DROP 2, HOLE 4, DIFF 15 (measured
  2026-07-16 via `specs/newfront-diff.sh`; re-measure, don't trust this line).
- Then: Phase 2 imports → 3 self-host subset → 4 rewrite in the subset → 5 clean lowerer →
  6 cutover (`bin/ssc` behind a flag, corpus green, then default).
- Adjacent: retire the v1/scalameta hybrid tier (`SwiftV2Commands`→`RunNativeV2`, delete
  `RunV2`/bridge); v2 native/bytecode lane coverage.
- Detail: `SPRINT.md` §`new-self-hosting-front`, `specs/newfront-*`.

### 2. Dogfood — serious systems software written in `.ssc`

The proof the language is real, and the libraries that make it useful.

- **scljet** — a SQLite-compatible engine in pure `.ssc`: pager/btree/WAL/journal + a full SQL
  layer, plus two front doors (portable JDBC façade, typed SQL surface). *Open engine bug:
  `INTEGER PRIMARY KEY` must alias the rowid — see BUGS.md; our files are wrong for real SQLite.*
- **uniml** — a standalone lossless token→tree markup framework (JSON/XML/YAML/Markdown),
  dual-compiling on v2 == JVM.
- Detail: `SPRINT.md` §`scljet-*`, §`uniml-*`.

### 3. Control / interop ABI across hosts

A target-neutral control ABI (`reset`/`shift`, continuations, effects) plus host profiles so
other languages can drive — and be driven by — ScalaScript.

- Landed 2026-07-16: Scala 3 direct-style control macros, JS/TS direct control host, the
  `ssc-api-descriptor-v3` interop surface.
- Open: host/runner profiles (JS/TS, Rust, Swift, WASM-WASI), the N×M interop matrix,
  saved-continuation format, mixed-build interface extraction.
- Detail: `SPRINT.md` §`control-interoperability`, `specs/control-interoperability.md`.

### Health (blocks everything — check before trusting any gate)

`origin/main` CI was **red for 192 consecutive runs** (through 2026-07-16) and nobody read it, so
failures stacked: each one masked the next. **A local green does not imply CI green** — the
launchers passed no `-Xss`, so the interpreter inherited the JVM default main-thread stack (2m on
macOS, 1m on Linux), and every `scljet-*` case passed on developer macs while StackOverflowError-ing
in CI. Check `gh run list --workflow=ci.yml --branch=main` before claiming a lane green.
Detail: `SPRINT.md` §`ci-red-main`.

---

## Not current

The earlier direction tables (payments rails, graph-storage, FX provider, agent-sdk,
package-registry, sbt-plugin) described May–June and no longer match what is being built. Their
open items remain in `BACKLOG.md` and are recoverable, but they are **not** the current direction —
do not pick from them without asking. See `BACKLOG.md` §"Roadmap — agreed priority order
(2026-06-17)" for that history.

## For agents

- **Pick next task**: read [SPRINT.md](SPRINT.md); claim per `AGENTS.md` §"Task claiming protocol".
  A claim file existing does **not** mean someone is alive — **check its heartbeat age** (on
  2026-07-16 every one of 8 claims was orphaned, the newest 21 h stale).
- **Mark landed**: update `BACKLOG.md` + add a one-liner to `CHANGELOG.md`.
- **Start new milestone**: add it under the matching stream above.
