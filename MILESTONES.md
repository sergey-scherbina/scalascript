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

**Front swap DONE at the default level (2026-07-23, `56d7d705f`):** `F` is the default native front
(`RunNativeV2.frontIsF` opt-OUT; `SSC_FRONT=legacy` reverts), CI-green on the flipped tree.
`ssc1-front`/`ssc1-lower` remain only as the F4a delegate-fallback; deleting them (F4 step 5) is
Sergiy's call. The residual cost is perf — F is interpreted and ~2-4× slower — which is the F5b
typed-IR arc, also the prerequisite for both kernel-shrink levers. See `BACKLOG.md` §`v2-f4-flip`.

**Single v2 entry point: `SPRINT.md` §"2026-07-27 — v2: the one board".** Read it before anything
below — it carries a correction (the FastCode-removal premise is refuted by measurement, and the
~2,800 kernel target is not currently reachable) and it is where the pickable v2 items live.

Two independent threads run under this stream — don't confuse them:

- **newfront** — the clean front replacing `ssc1-front`+`ssc1-lower`, byte-identical on the corpus.
  **Status: Phase 1 at MATCH 478/499 (96%)** — DROP 2, HOLE 4, DIFF 15 (measured 2026-07-16 via
  `specs/newfront-diff.sh`; re-measure, don't trust this line). Then: Phase 2 imports → 3 self-host
  subset → 4 rewrite in the subset → 5 clean lowerer → 6 cutover (`bin/ssc` behind a flag, corpus
  green, then default).
- **P6.5 → X1 — THE KEYSTONE.** The subset compiler written in the subset, compiling itself.
  P6.6's `C_min` fixpoint (stage1 == stage2, no quine) already proved the concept; F1/F2/F3/L1 cores
  landed; what's left is bounded mechanical breadth (~1–3k subset lines, no unknowns per SPRINT).
  **X1 also unblocks the whole deep half of stream 3** — see stream 3 below. If you only have room
  for one self-hosting task, this is the one with leverage.
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
- **✅ GATE LIFTED 2026-07-16 (Sergiy's call).** This stream's deep half waited on stream 1's
  `P6.5 X1`; X1 now holds — verified independently, twice, from a clean build: **89 ok / 0 FAIL**,
  `F(F_src) == ssc1-front(F_src)` byte-identical (79,667 B), `stage1 == stage2`. **Boundary:** the
  fixpoint is real but scope-bounded (`F` compiles the subset it is written in, not all of
  ScalaScript) — P6.5 stays `[~]` and its HONEST BOUNDARY note is the authority. Rationale for
  lifting: X1 proves the Core IR byte contract is stable and self-consistent, which is what these
  items depend on; breadth grows in parallel.
- **Unblock order (do not skip):** `coreir-canonical-contract-reconcile` +
  `coreir-canonical-codec-hardening` + `numeric-width-reconciliation` → `save()`/`run()` →
  `control-interop-examples`.
- **`control-interop-examples` is still LAST, not now.** Measured 2026-07-16: no `SavedContinuation`
  is constructible on ANY lane (every `Continuation.save()` performs `Save.Rejected(UnmanagedCapture)`),
  `.ssc` has no `shift`/`reset` surface (`unbound global`), vectors 14/17 are `pending-codec` on all
  9 lanes. It was once queued ahead of its own prerequisites — don't repeat that.
- Genuinely independent (not X1-gated): host/runner profile delivery (JS/TS, Rust, Swift,
  WASM-WASI), the N×M matrix, mixed-build interface extraction — planning, descriptors, reference
  API and semantic vectors "may proceed now" per SPRINT.
- Detail: `SPRINT.md` §`control-interoperability`, `specs/control-interoperability.md`.

### Health (check before trusting any gate)

**First fully-green run on `main`: 2026-07-23** — run `30020319173` on `18ee1c21a`, all four jobs
(`Conformance Suite`, `sbt — compile and test`, `Validate ScalaScript`, `Lint Markdown`). That ends a
streak of **192 consecutive red runs** (through 2026-07-16) that nobody read, during which failures
stacked and each one masked the next.

The rule outlives the streak: **a local green does not imply CI green.** The launchers passed no
`-Xss`, so the interpreter inherited the JVM default main-thread stack (2m on macOS, 1m on Linux), and
every `scljet-*` case passed on developer macs while StackOverflowError-ing in CI. Check
`gh run list --workflow=ci.yml --branch=main` before claiming a lane green, and note that
`[skip ci]` bookkeeping commits leave HEAD without a run of its own — verify the newest *code* commit.
Detail: `SPRINT.md` §`ci-red-main`.

**`cancelled` is RED, and the scheduled gates are outside every automated check.**
`scripts/ci-status` only ever looks at `ci.yml` + `--event push`, so the nightly workflows
(`Corpus Contract`, `F4 Front Swap Gates`) are read by nobody unless a human asks. That is how the
corpus gate managed to run **13 times without one green verdict** (2026-07-14 → 07-27) while looking
untroubled: GitHub reports a **job timeout as `cancelled`, not `failure`**, so a gate that had
outgrown its budget scrolled past as "someone cancelled it" for 12 days — and the F4 front-flip
landed without the differential net that exists to catch exactly what we then found by hand. When
sweeping health, run `gh run list --limit 25 --json workflowName,conclusion,createdAt` over **all**
workflows and count `cancelled` as red; a scheduled gate that never reports `success` is broken, not
quiet. Detail: `SPRINT.md` §Batch E `corpus-contract-shard-fix`.

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
