# ScalaScript (`.ssc`) — Project Bootstrap Brief

> This file is the durable memory of pre-code design decisions.
> Every new Claude Code session should read it first.
>
> **The process RULES are in [`POLICY.md`](POLICY.md)** — claiming, boards, where an entry is
> recorded, what goes to the coordination room, what a gate has to prove. They are numbered
> (`P-3.5`) so they can be cited. This file is the working brief: build commands, sbt and
> worktree mechanics, architecture rules, and the detail behind each rule. Where the two
> overlap, `POLICY.md` is the source.
>
> `POLICY.md` is this project's instantiation of the generic
> [`policy`](.agents/plugins/policy/commands/policy.md) skill — Part A is the rules, Part B is
> the slots a project fills in. Read the skill when a rule is unclear or you are about to
> change one; read `POLICY.md` for what this repo actually decided. Performance work has its
> own discipline in [`performance`](.agents/plugins/performance/commands/performance.md):
> measure with the alternating A/B protocol, record every run in the history, pick the next
> task from the ratio table.

## ⚡ THE WORKFLOW — mechanics for [`POLICY.md`](POLICY.md) §P-1

**The rules are P-1.1 … P-1.6 and are not restated here.** This section is the commands and the
judgement calls that `POLICY.md` deliberately leaves out.

```bash
scripts/coord-claim <slug> --items "<SPRINT ids / BUGS slugs>" --paths "file:<path> …"  # P-1.1
scripts/new-worktree <slug>                                                            # P-1.3
tests/conformance/run.sh --only '<globs>'                                              # P-1.4
scripts/coord-release <slug> && scripts/rm-worktree <slug>                             # P-1.5
```

`--items` is the WORK, not the name: it is what lets the overlap guard notice a rival claim filed
under a *different slug*. `--paths` is what you may touch, enforced at commit time. A rejected push
means someone moved first — **re-read the queue, do not blindly rebase**, because your plan may now
be wrong.

### Planning (P-1.2), and why there are two sprint files

- **module `SPRINT.md`** (e.g. `v2/SPRINT.md`) is a QUEUE with exactly two states: `[~]` in
  progress, `[x]` done. Anything else lives in that module's `BACKLOG.md`.
- **the root `SPRINT.md`** is the in-flight board, and it is GENERATED — `scripts/board`. Do not
  hand-write a row (P-3.5).

### Before the push (P-1.4) — `scripts/smoke-ci`, then the affected conformance slice

```bash
scripts/smoke-ci              # THE pre-push suite, ~3-4 min
scripts/smoke-ci --list       # what it will run, without running it
scripts/full-ci               # the whole CI suite locally, on demand (needs `yq`)
scripts/full-ci validate      # one job of it
```

**`scripts/smoke-ci` is the same runner GitHub executes on your push** (`.github/workflows/smoke.yml`
runs this exact script), so a red locally is a red you were going to get anyway — five minutes
earlier, with the failing check named and the last lines of its output printed. Exit 0 means green;
exit 1 means a check failed OR the suite went over its time budget. It prints per-check timings and a
per-module rollup, so "which module did I slow down" is answerable from the output alone.

It **refuses to run against a launcher built from different sources than your tree** and tells you to
`./install.sh --dev`. That is deliberate: a verdict from a stale toolchain is a verdict about the
wrong code. `SSC_SMOKE_ALLOW_STALE=1` overrides it for a deliberate A/B; `SSC_SMOKE_BUDGET=<seconds>`
raises the time budget, which you should not need — if every check inflated together, the host is
loaded and the number is not the problem.

What is IN it and why each check is there: read the prose at the top of
[`scripts/smoke-ci.ssc`](scripts/smoke-ci.ssc). The suite is written in `.ssc` and runs on the v2
native lane, so the compiler under test executes the suite. Adding a check means editing that file;
its `module` field must name a row in `tests/fixtures/modules.tsv` or the runner refuses to start.

The conformance part: `tests/conformance/run.sh` is a serverless wrapper — it never spawns a bloop
daemon, memoized green runs skip, and the JVM lane is serverless by default (`--warm-jvm` is for
local speed probes only). The affected slice costs seconds, so a push without it is not acceptable.
Full corpus stays for CI.

### CI evidence before a release — the three levels (P-6.7)

**The rule — what counts as evidence and what a release must say about it — is
[`POLICY.md`](POLICY.md) §P-6.7.** How to obtain each level:

1. `scripts/ci-status --sha <landed-sha>` exit 0 — the gold standard when you get it. It asks about
   **`smoke.yml`**, which since 2026-08-01 is the ONLY thing a push runs. For a deeper verdict on a
   specific SHA there is no push run to read: dispatch one (`gh workflow run ci.yml --ref main`)
   and ask `scripts/ci-status --sha <sha> --workflow ci.yml --event any`.
2. `gh run view <run> --json jobs` — name the **specific job that would catch your change** (e.g. a
   `Conformance shard i/4` for a conformance change) and report its conclusion. A green descendant
   run counts, with the `merge-base` named.
3. Your local gates, listed by name and result.

#### The three tiers, and which one answers your question (2026-08-01, `ci-three-tiers`)

| | what runs | when | wall | read it for |
|---|---|---|---|---|
| **smoke** | `scripts/smoke-ci`, 27 checks | **every push — the only thing a push runs** | ~157 s local | your per-commit verdict |
| **main** | `ci.yml`: lint + validate + conformance ×4 + examples | **nightly 03:00** + PR + dispatch | ~14 min | the daily verdict on the corpus. Green BY CONSTRUCTION |
| **full** | the above **plus** `sbt — compile and test` (~152 min) and the negtc gate (~40 min) | **`workflow_dispatch` only** | ~2-3 h | before a release, or when your change is in `sbt test` / negtc territory |

**`ci.yml` has no `push:` trigger at all.** Do not look for a push run of it, and do not add one:
a workflow whose jobs all skip still reports `success`, which is the meaningless green this
structure exists to remove. `tests/e2e/ci-status-guard.sh` fails if the trigger comes back.
Markdown still lints on every push — `.github/workflows/lint-markdown.yml` does that independently.

So `scripts/ci-status --sha <sha>` (smoke.yml) is the per-commit answer, and for anything deeper you
either wait for the nightly or ask: `gh workflow run ci.yml --ref main`.

**"Green by construction" is a claim with a mechanism behind it, not a hope.** Every residual red in
the corpus is a `known-red:` declaration in the case's front-matter that names its BUGS slug and the
condition under which it expires, AND a matching `KNOWN-RED` row in `corpus-baseline.tsv` —
`tests/e2e/freeze-consistency-gate.sh` refuses to let those two disagree. A declared lane is still
RUN, still COMPARED and still DIFFED; only the bucket changes. And a known-red that starts PASSING
**fails the suite** until the declaration is deleted, so a suppression cannot outlive its bug. That
last property is not theoretical: on 2026-08-01 two entries marked `status: fixed` were found with
their own gates red, both fixed on one of two fronts.

**So: do not add a `known-red:` for a fresh regression.** The mechanism is for a tracked, filed gap
whose fix is somebody's queued work. A regression is reverted or fixed.

**Before settling for level 3, ASK FOR A RUN.** Since 2026-07-28 `workflow_dispatch` has its own
per-SHA concurrency group, so a dispatched run is never evicted by the next push:

```bash
gh workflow run ci.yml --ref main
scripts/ci-status --sha <sha> --event any        # --event any: the run is not a push run
```

That rung did not exist before, and its absence is why an entire session closed every claim at
level 3: dispatch shared the push group, so runs carrying specific commits were superseded before
starting a single job. Level 1 was unreachable by construction, not by luck.

One cost, stated rather than discovered: a dispatched `ci.yml` run is the **full** tier, so it also
runs `sbt — compile and test` (~152 min) and the negtc gate, and `ci-status --workflow ci.yml`
requires every one of them. If the tier-2 answer is enough — and for most changes it is, since the
push run now carries the corpus — just read the push run of `ci.yml` for your SHA. Dispatch when you
need `sbt test` or negtc specifically.

Before pushing at all, run `scripts/smoke-ci` locally. It is the same runner GitHub will run, so a
local green is the first real evidence you can get and it costs ~5 min — and it fails on a launcher
built from a different commit rather than measuring the wrong bytes.

Red is recorded in the module's `BUGS.md` and fixed in the real failing job.

Details: §"Workflow for parallel agents" below, [`specs/worktree-guardrail.md`](specs/worktree-guardrail.md).

## Deciding: [`POLICY.md`](POLICY.md) §P-4

The rules — default to deciding, park the alternatives with their trade-offs, and the short list of
things still worth asking — are **P-4.1 … P-4.3**. Not restated here.

The part that is not a rule, and the reason the section used to be this long: *a parked alternative
with its trade-off costs nothing and is there the day it becomes right; the same alternative held
as "I should ask about this" is lost at the next reboot.*

Detail: [`scrumban`](.agents/plugins/scrumban/commands/scrumban.md) §"decide".

## The rozum room: [`POLICY.md`](POLICY.md) §P-5

One room, `scalascript`. What belongs there, what does not, and the duty to READ it and not only
write to it are **P-5.1 … P-5.5**. Not restated here.

**Mechanics, which are this file's half:**

```
mcp__rozum__meeting_submit      post
mcp__rozum__meeting_wait_my_turn  the authoritative delta (25 s long-poll)
rozum meetings inbox --as <handle>   messages addressed to you
```

Announce `working:` when you start something non-trivial and `done:` / `blocked:` when you stop, so
a sibling can see the board without reading it. Lead with the measurement; keep it short. Agents are
addressed by CLAIM SLUG (`@some-slug`) — if you hold no claim, say which handle to reply to, or your
question has no address.

Detail: [`rozum`](.agents/plugins/rozum/commands/rozum.md).

## Claim scope and LIFETIME: [`POLICY.md`](POLICY.md) §P-2

One claim is one TASK, released when it lands (P-2.6) — a claim covering a milestone holds its
paths for as long as its slowest part, and a claim open long enough to be reaped should have been
several. Work you find but will NOT do goes on the module's `BACKLOG.md` before you move on (P-2.7);
a module `SPRINT.md` has two states, `[~]` and `[x]`, so a task nobody is working on cannot go
there. Narrowest scope that covers the work, `mod:` is an edit lock rather than stewardship, bookkeeping
files are never claimed, and widening is a normal move — **P-2.1 … P-2.5**. Not restated here.

**The syntax, which is this file's half:**

```text
file:<path>   exactly one file      — the default
mod:<path>    a module subtree      — scripts/coord-claim REFUSES it without --broad "<reason>"
repo:         everything            — same requirement, and almost never right
<path>        unprefixed = mod:<path>, unchanged
```

Widening means editing **both** copies of the scope — `.work/active/<slug>.claim` and its
`LEDGER.tsv` row — and pushing; the guard refuses if they disagree. Mechanism and the measurements
behind the levels: [`specs/claim-mutex.md`](specs/claim-mutex.md) §Hierarchy.

## MANDATORY: required skills

All skills live in the **`.agents/plugins/` submodule** (the
[agent-plugins](https://github.com/sergey-scherbina/agent-plugins) repo). **Read its
index — `.agents/plugins/AGENTS.md` — for the full list of available skills and when
to use each**, then load the relevant skill's `commands/<name>.md` on demand. New
skills added to the submodule appear in that index automatically — no edit here, no
per-skill install. Update all skills with `git submodule update --remote`.

The submodule is **only initialized in the shared main repo** — do NOT run
`git submodule update --init` inside a worktree. From a worktree, find the main repo
and read skills from there:

```bash
MAIN=$(git worktree list | head -1 | awk '{print $1}')
# index:  $MAIN/.agents/plugins/AGENTS.md
# a skill: $MAIN/.agents/plugins/<name>/commands/<name>.md
```

## Build speed & hygiene (2026-07-06)

- **Prefer `scripts/sbtc "<command>"`** (sbt thin client) over `sbt -batch` for
  repeated commands: a cold batch invocation pays ~8 s wall / ~31 s CPU just
  loading the 259-module build; the client reuses the warm server (<1 s).
- **EXCEPT when you edited `build.sbt` — then use `sbt -no-colors -batch`.** The
  warm server holds the build definition it loaded at STARTUP, so `sbtc` gives
  you a verdict about the *previous* `build.sbt`, and gives it as `[success]`.
  Measured 2026-08-09 (`std-to-repo-root`): a negative control on a new
  `build.sbt` guard passed BOTH arms — correct and deliberately-wrong — and an
  unconditional `sys.error` planted in the same block was never reached under
  `sbtc` while a fresh `sbt -batch` hit it at once. Three measurements meant
  nothing. `install.sh` already calls plain `sbt`, which is why a full
  `install.sh --dev` sees a change the preceding `sbtc` loop did not.
- **Remove worktrees with `scripts/rm-worktree <name>`**, not bare `git worktree
  remove` — it also kills the worktree's sbt/bloop daemons (2-3 GB RSS each
  otherwise leak). `scripts/kill-stale-builders` finds orphans (--kill to stop).
- **Conformance loop**: `tests/conformance/run.sh --only 'glob*'` (the wrapper forces
  `--server=false` so no persistent bloop daemon is left behind — see bench.sh, bloop-serverless-scripts)
  runs just your cases; green runs are memoized (unchanged cases skip;
  `--no-memo` to force). The JVM lane is serverless by default; `--warm-jvm`
  or `SSC_SCALACLI_SERVER=1` opts into a warm compiler for local speed probes.
  RAM-bounded entrypoint: `scripts/conformance`.
- Forked test JVMs default to `-Xmx2g` (override `SSC_TEST_XMX`); do NOT rely
  on `JDK_JAVA_OPTIONS` for test heaps.
- **Host RAM is a SHARED budget across all your sibling agents, and it has run
  out twice** (2026-07-20 kernel panic; 2026-07-28 139,831 pageouts). Every
  per-process cap is fine — the *sum* is what overflows, and until 2026-07-28
  nothing printed that sum. When the machine feels slow, or before starting
  anything heavy:

  ```bash
  scripts/build-ram-report              # RESIDENT vs DECLARED vs HOST, per worktree
  scripts/kill-stale-builders --idle 30 # daemons nobody is building in (dry run; --kill to act)
  ```

  `scripts/sbtc` already routes through `scripts/build-guard`, which admits at
  most `(HOST − 8 GB) / 6 GB` concurrent guarded builds host-wide. If it says
  *"N guarded build(s) already running — waiting…"*, that is the guard working,
  not a hang. Do **not** diagnose memory pressure from
  `kern.memorystatus_level`: measured, it reads 74–93 % straight through an OOM
  event, which is why `jvm-mem-guard`'s log is empty. Read `compressor_mb` and
  `pageouts` instead. Full page: [`docs/build-performance.md`](docs/build-performance.md);
  measurements: [`specs/build-ram-budget.md`](specs/build-ram-budget.md).


### The skills (read on demand)

| Skill | When |
|---|---|
| [`scrumban`](.agents/plugins/scrumban/commands/scrumban.md) | **Always** — write the plan into the MODULE's `SPRINT.md`/`BACKLOG.md` before you execute, and put the in-flight task on the root board; triage discovered work (module SPRINT if urgent/critical/easy/needs-a-check, else that module's BACKLOG). |
| [`bugs`](.agents/plugins/bugs/commands/bugs.md) | Any bug (reported by busi in rozum, or found by you): track in `BUGS.md`, work the fix loop, reproduce in the **real harness**. |
| [`rozum`](.agents/plugins/rozum/commands/rozum.md) | Coordinating with `busi` (and the human) in the `scalascript` rozum room — the default coordination channel. |
| [`spec-dev`](.agents/plugins/spec-dev/commands/spec-dev.md) | Every feature / non-trivial change: `specs/<slug>.md` first, commit, implement against it. |
| [`multi-agent`](.agents/plugins/multi-agent/commands/multi-agent.md) | Autonomous-loop / parallel-agent work on shared `origin/main`: claim → implement → push → release. |
| [`multi-repo`](.agents/plugins/multi-repo/commands/multi-repo.md) | Treating several repos as a virtual monorepo (status / sync / update). |

The skills below are **non-negotiable on this project** — their rules are inlined here
so they bind even before you open the index:

**scrumban rules (non-negotiable):**
- **Write the plan before you execute it**, with enough "what + how" that a fresh agent —
  or you after a reboot mid-task — can finish it without you.
- **Which file: the module's, not the root's.** Work on `v2/` is planned in `v2/SPRINT.md`
  and queued in `v2/BACKLOG.md`. The ROOT `SPRINT.md` is the board of what is in flight
  (one row per live claim); the root `BACKLOG.md` is for cross-module or unscoped items
  only. Layout and the exact columns:
  [`specs/work-tracking-layout.md`](specs/work-tracking-layout.md).
- **Two states in a module sprint, not three:** `[~]` in progress, `[x]` done. Not-started
  work is a BACKLOG item, not a sprint item.
- Queue follow-ups/deferrals the moment you decide them; never carry them only in
  context. A reboot between "decide" and "finish" orphans unrecorded work.
- **Triage a problem the moment you find it:** the module's SPRINT if
  urgent/critical/easy/just-needs-a-check; that module's BACKLOG if not-urgent +
  not-critical + hard/unclear-but-maybe-useful.

**bugs rules (non-negotiable):**
- **A bug goes in the BUGS.md of the module that owns the FIX** — not the root one, not
  where the symptom shows, not where its gate lives. Layout and the routing table:
  [`specs/work-tracking-layout.md`](specs/work-tracking-layout.md). Short version, keyed on
  the entry's own `lane:`:

  | `lane:` | file |
  |---|---|
  | `int` | `v1/runtime/backend/interpreter/BUGS.md` |
  | `js` | `v1/runtime/backend/js/BUGS.md` |
  | `jvm` | `v1/runtime/backend/jvm/BUGS.md` |
  | `native` · `v2-jvm` · `v2-rust` | `v2/BUGS.md` |
  | `v3` | root `BUGS.md` — there is no `v3/BUGS.md` yet; create one and change this row when v3 has enough entries to want its own |
  | `apparatus` (+`area: conformance` / `build`) | `tests/BUGS.md` (`tests/conformance/` / `scripts/`) |
  | `multi` · `n/a` | root `BUGS.md` — genuinely more than one implementation, NOT a leftovers bin |

  The root file is for defects no single module owns. Putting a module's bug there is the
  thing this split exists to stop: before it, one 630-entry file meant every agent edited
  the same path (hence `BUGS.md` being declared SHARED, i.e. the overlap guard cannot help
  you) and a module's own state was unreadable from inside the module.
- Every bug — reported by busi in the rozum room, or found by you — gets an entry, and it
  **must carry the machine-readable header** defined in
  [`specs/bugs-index.md`](specs/bugs-index.md). `tests/e2e/bugs-index-gate.sh` walks EVERY
  `BUGS.md` and refuses an entry without one:

  ```markdown
  ## <slug> — <one line>
  <!-- status: open        · open|fixed|wontfix|duplicate|unknown
       lane: native        · native|int|js|jvm|v2-jvm|v2-rust|apparatus|multi|n/a
       area: front         · front|runtime|codegen|cli|conformance|build|docs|plugin|other
       gate: tests/e2e/…   · what would catch a regression; `none` if there is not one yet
       fixed-in: <sha> -->   · required when status: fixed
  ```

  The prose still carries repro / reporter / root cause — the header exists so a QUERY
  never has to read prose.
- **`done` is not a status.** This rule used to read `open → needs-info → fixed → done`,
  and that is exactly why the file ended up with three words for one state (measured
  2026-07-29: `FIXED` 332, `DONE` 67, `RESOLVED` 3, plus ten one-off freeform ones, and
  **108 of 614 entries with no status at all**). Closed is **`fixed`**; "the reporter has
  not confirmed yet" is `confirmed: no`, which is a different question from whether the
  defect is still present.
- **Do not widen the `lane:` / `area:` enums to fit an entry.** Both were caught within an
  hour of the split: a sibling wrote `lane: v2` (the enum's name for that lane is `native`)
  and `area: tooling` (it is `cli`). Fix the entry. Two names for one thing is exactly the
  `FIXED`/`DONE`/`RESOLVED` drift the header was introduced to end.
- **Do not grep the prose for status — run `scripts/bugs-report`** (it walks every file and
  takes `--module`).** Hand-rolled queries
  over prose disagreed with each other; on 2026-07-28 one silently omitted 108 entries
  and missed every `DONE` while answering a direct question about remaining work.
  `scripts/bugs-report --status open --lane native`, `--v2`, `--no-gate`.
- A superseded report is **kept but demoted**: `### Original report (superseded YYYY-MM-DD)`,
  in the past tense. Twice on 2026-07-28 a preserved present-tense report was read as
  current truth — once a regression was waved through on it.
- Reproduce from the reporter's minimal repro **in the real harness / assembled jar**,
  not `ssc run`/`runMain` (which can disable the JIT via classpath and hide the bug).
  A wrong "your binary is stale" reply once had to be retracted for exactly this.
- Cross-module bug ⇒ a **multi-file** regression test (a single-file test passes while
  the real bug lives at the import boundary).
- Report `done:` in rozum with the SHA + the actual root cause; if you find a bug,
  announce it in the room to the owning project.

**rozum rules:** the `scalascript` room is the **default coordination channel** with
busi. Sweep it **periodically, not constantly — when no other task is in flight**, and
**MANDATORILY at the end of every finished item** (§THE WORKFLOW step 6): read it, then
act on what is there or queue it — a post nobody reads is not coordination.
Address with `@name` (agent/human) and `@project` (broadcast). Post `working:` before
long offline work and `done:` on return.

**scrumban decide-rule (non-negotiable):** default to deciding; park the alternatives on
the board instead of blocking on a question. See §"MANDATORY: decide it yourself" above.

**spec-dev rules (non-negotiable):**
- Read `specs/jit-completeness.md` (or the relevant feature spec) before starting any implementation.
- If no spec exists for the task: write it first, commit `spec: <slug>`, then implement.
- Never start coding without a committed spec.
- After implementation: run verify step, check off behavior items.

**multi-agent rules:** follow the queue discipline in `multi-agent.md` for
all autonomous loop work (claim → implement → push → release).

---

## MANDATORY: first action in every session

**If the conversation begins with a context summary (the previous session ran out of context and was compressed), treat it as a new session start: re-read this file (AGENTS.md) before any other action. The summary does not guarantee AGENTS.md was read correctly or that it has not changed.**

Before any file read, planning, or write — verify whether you are in a worktree.

```bash
cat .git 2>/dev/null | head -1
```

| Output | Meaning | Action |
|--------|---------|--------|
| `gitdir: /path/.git/worktrees/NAME` | ✓ in a worktree | continue |
| file missing or `.git` is a directory | ✗ in shared main | create a worktree before normal project work; see the coordination exception below |

**Coordination exception:** task claims, claim releases, pause/resume files,
status checks, and local `main` synchronization are **main-checkout
operations**. Do not create a worktree before claiming. A claim committed from a
worktree is invalid because it stays on the feature branch and never becomes
visible on `origin/main`.

If you are **not** in a worktree and you are about to do ordinary project work
(code, docs, tests, implementation planning tied to edits) — create one before
continuing:

```bash
BRANCH="feature/your-task-name"
WT=".worktrees/$BRANCH"                 # relative to the main checkout (run from there)
git fetch origin
git worktree add "$WT" -b "$BRANCH" origin/main
```

Then do all work from `$WT`. Details and common traps — in §"Workflow for parallel agents" below.

## MANDATORY: always invoke sbt with an explicit `cd` to the worktree

The harness keeps a persistent shell working directory across `Bash` tool
calls. If a previous command happened to run from the main repo, a later
`sbt …` call will silently pick up the **main repo's** `build.sbt` and
target instead of the worktree — your edits will appear to have no effect,
benches will measure the wrong code, and tests may pass on stale bytecode.

Always invoke sbt as:

```bash
cd <absolute-worktree-path> && sbt "…"
```

Trust the line `set current project to root (in build file:<path>/)` in
sbt's startup output: if `<path>` is NOT your worktree, the previous shell
CWD leaked. Re-run with explicit `cd`.

## MANDATORY: benchmarks go through `scripts/bench`

For any perf A/B work, use the `scripts/bench` wrapper instead of typing
raw `sbt "interpreterBench/Jmh/run …"` invocations. One command per case:

```bash
scripts/bench interp [pat]     # InterpreterBench microbenchmarks
scripts/bench cross [pat]      # cross-backend execution (RuntimeBench)
scripts/bench gen [pat]        # codegen-time (CrossBackendBench)
scripts/bench compile [pat]    # parser/typer/unifier (CompilerBench)
scripts/bench off <pat>        # interp bench with BYTECODE+FASTTIER off
scripts/bench profile <pat>    # interp bench + JFR alloc + GC profile
scripts/bench smoke            # one-iter JMH smoke
scripts/bench wall             # cross-language wall-clock
scripts/bench help / list      # usage / list every @Benchmark
```

The canonical reference is [`docs/benchmarks.md`](docs/benchmarks.md): what
each bench measures, when to use it, how to add a new one, and the gotchas
(e.g. `Set(...)` does not work in the bench harness because
`BuiltinsRuntime.initBuiltins` is skipped; use `.toSet`). When recording
baselines in `SPRINT.md` / `specs/vm-jit-next.md`, **name the
`scripts/bench` command that produced the number** so the next agent
re-runs the same configuration.

## MANDATORY: write to `AGENTS.md` in English only

Project documentation in `AGENTS.md` is the durable session brief used by
every agent (and across languages). Keep it consistently in English even
when the surrounding conversation is in another language. The same rule
applies to all other shared documentation files (`docs/`, `BACKLOG.md`,
`specs/vm-jit-next.md`, etc.) unless they are explicitly localised.

## MANDATORY: measurement apparatus must COMPARE, never PRE-JUDGE

**Our single most recurring failure mode is not buggy code — it is measurement apparatus that
decides the answer before comparing.** It is insidious because it fails *green*: the gate says
"pass" (or "known failure") while the truth is the opposite, so nobody looks. It cost us days on
three independent lanes in one day (2026-07-16):

| Apparatus | How it pre-judged | Truth it hid |
|---|---|---|
| `specs/newfront-diff.sh` | `__notImplemented__` in the projection ⇒ **HOLE**, before comparing | `???` is a legit expression that LOWERS to that prim — the program was **byte-identical** and reported as a gap for 2 rounds |
| `specs/newfront-diff.sh` | `proj == "Nil"` ⇒ **DROP** | doc-only `.ssc` extracts to a legitimately EMPTY program; `Nil` lowers to the bare prelude — both were **byte-identical** |
| v21 e2e gates | bare `[[ … ]]` under `set -e`, printing **nothing** on mismatch | trivially-stale expectations, red for days |
| CI vs local | local launchers inherited a bigger default JVM stack | a whole family passed locally, `StackOverflowError` in CI for **192 consecutive runs** |

**The rules (binding):**

1. **Compare first, classify after.** Compute both sides, byte-compare, and only then bucket the
   result. A marker/heuristic (`__notImplemented__`, `Nil`, a size threshold) is a *triage hint* for
   an already-failing case — never a reason to skip the comparison.
2. **Every check prints its diff.** A gate that can fail silently will. `[[ x == y ]]` under `set -e`
   is not a test; print `expected=… got=…` on mismatch.
3. **Green from a proxy is not green.** Byte-equality (or the real observable) is the ground truth;
   a passing gate that never ran the comparison proves nothing. Ask: *if this were broken right now,
   would my apparatus actually say so?* If not, fix the apparatus **first** — a phase built on a
   blind gate produces a confident lie, not progress.
4. **Suspect the apparatus when a result looks impossible** ("this program can't have a hole") — and
   when adding a new phase/lane, **build its gate before its feature**.

## MANDATORY: understand it before you fix it, then finish it

**Can you actually fix the thing you are working on? Do you understand the problem yet?** Answer
those two questions honestly *before* writing code. The first plausible cause is very often a
symptom, and a fix aimed at a symptom lands, goes green, and leaves the defect in place under a
new name.

**Diagnose first. The obvious fix is frequently the wrong one.** Measured 2026-08-12: a client
method had shipped with a written-down gap — no test could reach it, because the JDK provides a
WebSocket client and no server. The obvious fix was to hand-roll RFC 6455 in a test. Looking
first showed the method was one of THREE copies of the same rule, two of them identical apart
from whitespace, and the unreachable copy was the one containing nothing of its own. The correct
fix was to delete the duplication, after which there was no unreachable code left to test. The
same day, twice, a blocker was described wrongly in a spec — the missing test double existed
already, and the real obstacle was file-private visibility. **If you are about to build
scaffolding to reach some code, first ask whether that code should exist.**

**The rules, and they are not negotiable:**

1. **Investigate with read-only tools and NO claim.** A claim reserves paths against other
   agents; reading, grepping and reasoning reserve nothing. Claim at the moment you are about to
   change a file, not when you start thinking about it. An early claim blocks a sibling for no
   benefit and makes your scope look wider than your work.
2. **Claim NARROWLY.** The files you will actually edit. Widening later is normal and cheap
   (§"Claim scope and LIFETIME"); starting wide is neither.
3. **Do not get in other agents' way.** Their red is theirs; their in-flight files are theirs.
   Report what you find, and fix your own. Guessing a value into somebody else's durable record —
   a `fixed-in` sha, a `lane:` — writes a plausible lie that outlives the guess.
4. **Work in a worktree, on a branch.** Never in the shared `main` checkout (§"MANDATORY: first
   action in every session"). This is enforced by a hook, and the hook is right.
5. **Carry it to a finished result.** Landed on `origin/main`, gates named and green, claim
   released, worktree removed. Work that stops at "it compiles" is not done, and neither is work
   whose verification you intended to run later.

**And say which it was.** If you fixed the symptom because the real fix is out of scope, write
that down as a symptom fix with the real cause named — a repair recorded as complete when it is
partial is worse than an open bug, because the next reader stops looking.

## MANDATORY: persist everything needed to continue from a fresh context

The session that records is not the session that resumes. A parallel
agent — or yourself after a `/clear` — must be able to pick up the work
cold, without re-deriving baselines, re-discovering pitfalls, or
re-investigating decisions you already made. **Anything in your active
context that is not written down is one `/clear` away from being lost.**

Treat persistence as a **continuous activity**, not an end-of-session
chore. The moment you learn something durable, record it.

**Persist the *plan*, not just the *findings*.** The section above is about
recording what you have *learned*; this is about recording what you are *about
to do*. **Before you start a task, write it into `SPRINT.md` (do-soon) or
`BACKLOG.md` (can-wait)** with enough "what + how" that a fresh agent — or you
after a reboot — can pick it up and finish it. A machine can reboot, or your
context can clear, *between deciding to do something and finishing it*; if the
plan only lived in your head, that work is orphaned. Rule of thumb: **if you want
to do something, queue it first, then calmly do it.** Discoveries you make
mid-task (a follow-up, a deferred edge case) get the same treatment — queue the
follow-up the moment you decide to defer it. The full discipline (the scrumban
board: SPRINT vs BACKLOG, write-before-do, claim/done hygiene) is in the
`scrumban` skill.

**What to persist:**

- *Decisions and rejected alternatives.* What you picked, what you
  rejected, and the one-sentence reason for each. Save the next agent
  from re-investigating the same fork.
- *Baselines and measurements.* Current bench numbers, test count,
  observed behaviour. The next agent needs a "before" to A/B against
  without re-running expensive setup.
- *Gotchas you hit or nearly hit.* Subtle bug patterns caught at the
  verify step (boolean-return mis-wrap, stale TLS slot, forgotten
  `case _ => null`, etc.), plus the pattern that catches them.
- *State of toggles and defaults.* Which env vars / flags are on, off,
  recently flipped. A bench under different default flags is a
  different bench.
- *Open questions and explicit non-goals.* What you didn't do and why
  (so the next agent does not redo speculative work you already
  rejected).
- *Reusable wisdom across tasks.* Methodology that applies to more
  than one work item belongs in
  `~/.claude/projects/.../memory/feedback_*.md` so it survives this
  project's lifetime.

**Where to persist:**

| Type of info | Location |
|---|---|
| Project rules, mandatory practices | `AGENTS.md` (this file) |
| Open work + status, high-level | `BACKLOG.md` |
| Pending task queue + per-task implementation notes + gotchas | `SPRINT.md` |
| Completed tasks, newest first | `CHANGELOG.md` |
| Design specs, roadmaps | `specs/*.md` |
| Project-specific durable knowledge | `~/.claude/projects/.../memory/project_*.md` |
| Reusable methodology, user preferences | `~/.claude/projects/.../memory/feedback_*.md` |
| Point-in-time decisions tied to a specific change | Git commit messages |
| Non-obvious WHY in surprising code | Source-code comments (only when removing them would confuse a future reader) |

The same fact can — and often should — live in two places. A benchmark
baseline written into both `SPRINT.md` (where the next agent looks
first when asked "what to do") and `specs/vm-jit-next.md` (where the
spec is self-contained reading) survives a careless edit to one of them.
Defense in depth.

**When to persist:** continuously, not as a wrap-up step. Specifically:

- whenever you make a non-obvious decision,
- whenever you measure something the next agent will need,
- whenever you discover a gotcha you nearly missed,
- whenever you find yourself thinking "I will remember this" — that is
  the cue you will not, and the next agent definitely will not.

**Validate before considering work complete (or before a `/clear`):**
ask yourself "if my context cleared right now, could a fresh agent
pick up this task cold without losing information?" If the honest
answer is "only if they re-derive X" — record X first, then continue.

The persistent files are the contract between parallel agents and
between sessions. Treat them as load-bearing.

---

## Workspace and repositories

`REPOS.md` in this repo lists all submodules (agent-plugins).
Use the `/multi-repo` skill to manage them:

- `/multi-repo status` — state of all submodules
- `/multi-repo sync` — fetch + pull + pinned `git submodule update --init --recursive`
- `/multi-repo update` — intentionally advance submodules to remote heads
- `/multi-repo clone` — init missing submodules from scratch

Skill location: `.agents/plugins/multi-repo/commands/multi-repo.md` (in main repo — use `$MAIN` from above).

---

## What this project is

ScalaScript is a meta-programming / specification language with extension `.ssc` that:

- Has a **hybrid syntax**: Markdown constructs (headings, lists, links, fenced code blocks, YAML front-matter) are first-class language syntax, not decoration. Headings define namespaces/scopes; links define imports/references; fenced code blocks are typed expression units; YAML front-matter is the module manifest. Inside code regions the syntax and type system are Scala-flavored.
- Is **fully autonomous**: real compilation, real execution. AI/LLMs are used only for language design and tooling, never at runtime or compile time. Compiled artifacts have no AI dependency.
- Is **target-agnostic**: same `.ssc` source, multiple backends. Semantics and type checking are defined once at the IR level; backends are translators.
- Prefers **existing well-understood technology** over invention. Markdown for structure, YAML for metadata, EBNF for grammar, standard typed lambda calculus + Scala-style type system, existing runtimes (JVM, browser JS engine, WASM) as targets.

## Decisions already made

- **Extension**: `.ssc`
- **Syntax model**: hybrid — all three Markdown integration modes coexist:
  1. Markdown structure as language structure (headings = scopes, links = imports, etc.)
  2. Fenced code blocks as typed expression units
  3. Inline `${expr}` interpolation in prose
- **Initial backends (in order)**:
  1. **JVM via Scala-CLI** — fastest path to a working interpreter; mature ecosystem
  2. **JavaScript in browser** — zero-install distribution; broad reach
- **Future backends**: WASM, native, embedded — added incrementally without changing source semantics.
- **AI role**: development-time only (spec authoring, linting, dialect translation assistance). Not in the compiler pipeline. Not at runtime.

## Bootstrap decisions (resolved)

- **Name**: ScalaScript (trademark risk acknowledged and accepted)
- **License**: Apache 2.0
- **Repo**: private
- **Spec language**: English (primary), Russian in comments / design discussions

## What to work on next

**`scripts/next`.** It reads every board's headed entries through the same parser as
`scripts/bugs-report` (no second parser over the same headers), drops anything a live claim names,
and ranks what is left on signals anyone can recompute: does the entry name a GATE — the acceptance
test, without which "done" is undefined and the item is not claimable — then `confirmed:`, then
`kind:`, then age, oldest first so the pile drains instead of churning.

It also prints the entries that have NO acceptance test. That is a second, different kind of work:
giving one a gate is what turns a note back into a task. Measured 2026-08-13: 42 of 76 unclaimed
entries were ready to take, 34 were not.

**What used to be here was dead.** This section carried the project's 2025 bootstrap steps —
`git init`, "scaffold README.md", "write the EBNF" — for months after all of them were done. The one
section whose title promised an answer to "what next" answered with nonsense, which is why every
agent re-derived the survey by hand or asked Sergiy.

## Milestones (proposed)

- **M0 — Spec freeze v0.1**: all of the above, no executable code.
- **M1 — JVM frontend**: lexer + parser + typer in Scala 3, running under Scala-CLI. Output: typed IR (JSON or tree-text).
- **M2 — JVM interpreter**: walk the typed IR, produce real output for `examples/*.ssc`.
- **M3 — JS backend**: same IR, translated to JS that runs in a vanilla browser page.
- **M4 — Conformance suite**: shared test set, both backends must agree bit-for-bit on observable output.

## Author / context

- Author: Shcherbyna Sergiy Victorovych (Sergiy)
- Background: Scala 3, type theory, functional programming
- Communication: Russian preferred; also Ukrainian and English

## Design principles (binding)

1. **Reuse, don't invent.** If a problem has an established working solution (Markdown, YAML, EBNF, standard type theory), use it. Invention is reserved for the actual novelty: the unification itself.
2. **One source, many targets.** Source semantics are target-independent. Backends translate; they do not reinterpret.
3. **Human and machine readable.** Source must be pleasant for humans and trivially parseable for machines. Markdown gives both.
4. **No AI at runtime or compile time.** The language stands on its own.
5. **Each problem keeps its own dialect.** ScalaScript's value is not replacing every language but providing a common spec/translation layer between them.

## Codebase architecture rules (binding)

### New intrinsics always go to `runtime/std/` plugins, never to core

When implementing a new `extern def` (intrinsic), **always** create or extend a
plugin in `runtime/std/<feature>-plugin/`, not the interpreter core.

**Wrong** — adding `NativeImpl` to any of:
- `runtime/backend/interpreter/src/.../intrinsics/*.scala`  (e.g. `Jdbc.scala`, `UiPrimitives.scala`)
- `core/` directly

**Right** — creating a new plugin:
1. `runtime/std/<feature>-plugin/src/main/scala/scalascript/compiler/plugin/<feature>/<Feature>Plugin.scala`
2. `runtime/std/<feature>-plugin/src/main/scala/scalascript/compiler/plugin/<feature>/<Feature>Intrinsics.scala`
3. `runtime/std/<feature>-plugin/src/main/resources/META-INF/services/scalascript.backend.spi.Backend`
4. Register in `build.sbt`: new `lazy val`, add `% Test` to `backendInterpreter`, add to root aggregate and CLI plugin list.

The plugin may import `scalascript.interpreter.{Value, InterpretError}` and
`scalascript.frontend.*` — those live in `core` / `frontendCore` which all
plugins already depend on.

Bridge hooks that the interpreter exposes *to* plugins (e.g. `NativeContext.dbConnect`,
`NativeContext.registerRoute`) are the only intrinsic-related code that belongs in
`backend/spi` or the interpreter — they are the SPI contract, not the intrinsics themselves.

**Examples of correct plugin layout:** `runtime/std/json-plugin`, `runtime/std/auth-plugin`,
`runtime/std/oauth-plugin`, `runtime/std/sql-plugin`, `runtime/std/ui-fetch-plugin`.

### Platform types are forbidden in `.ssc` — compile error

`.ssc` user code must **never** import or reference platform-specific types
(`java.*`, `javax.*`, `scala.*`, `process.env`, etc.) in a regular
`scalascript` fenced block.  This is a **compile-time error**, not a warning.

Decision order for any platform-specific operation:
1. **`std.*`** — `std.fs`, `std.os`, `std.process`, `std.crypto`, etc.
2. **Plugin intrinsic** — `extern def` implemented in `runtime/std/<feature>-plugin/`.
3. **`@jvm("...")` / `@js("...")` annotation** — lightweight one-liner FFI.
4. **Backend-specific fenced block** — `scala`, `java`, `javascript`, `rust`
   tags for ad-hoc multi-line native code that stays isolated to one target.

Never add a suppression annotation.  Never use `java.*` in `.ssc` directly.

Note: `.sc` Scala-CLI host scripts (`bench/run.sc`, etc.) are JVM tooling,
not `.ssc` user code — they may use `java.*` freely.

Full spec: [`specs/backend-specific-blocks.md`](specs/backend-specific-blocks.md).
Companion: [`specs/std-fs-os.md`](specs/std-fs-os.md), [`specs/arch-ffi.md`](specs/arch-ffi.md).

---

## Spec-driven development

See the `/spec-dev` skill for the full workflow (write → implement → verify).

Skill location: `.agents/plugins/spec-dev/commands/spec-dev.md` (in main repo — use `$MAIN` from above).

Non-trivial in this project (spec required):
- A new module / package in `build.sbt`
- A new SPI trait or contract other code will depend on
- A cross-cutting refactor that touches more than one backend
- Any new top-level milestone added to `BACKLOG.md`

Trivial changes (bug fixes, single-file refactors, dep bumps, doc edits) do **not** need a spec.

Update the spec in the same commit if reality diverges — never leave "TODO: update spec".

### Every user-facing feature needs an example

Any feature that a `.ssc` user would call directly gets a working example in
`examples/` — self-contained, runnable with `ssc run examples/<name>.ssc`,
referenced from the spec and `README.md`. A feature with no example is
incomplete for milestone-closure purposes.

Existing specs to mirror in style:
[`specs/backend-spi.md`](specs/backend-spi.md),
[`specs/x402.md`](specs/x402.md),
[`specs/runtime-server-strategic-plan.md`](specs/runtime-server-strategic-plan.md).

## Workflow for parallel agents — mechanics for [`POLICY.md`](POLICY.md) §P-1.3

Many agents run at once, each in its own worktree. **Where work may happen, and what the shared
`main` checkout may be used for, is [`POLICY.md`](POLICY.md) §P-1.3.** Below is how to get into a
worktree when the tooling will not, and how to check what the neighbours are doing.

### 1. When `EnterWorktree` is unavailable

`EnterWorktree` may be rejected with "Must not already be in a worktree"
even when the session's `Primary working directory` is not a real git
worktree (it's just a regular directory inside the main repo tree with no
`.git` file).  **Always verify before trusting the system prompt:**

```bash
# A real worktree has a .git FILE whose content starts with "gitdir:"
cat .git 2>/dev/null | head -1
# Real worktree → "gitdir: <repo-root>/.git/worktrees/NAME"
# NOT a worktree → file missing, or .git is a directory
```

If the check shows you are **not** in a real worktree, create one now.
**Preferred — the packaged helper** (resolves the main checkout, fetches,
creates the worktree at an external prune-safe path, ensures the hook):

```bash
scripts/new-worktree your-task-name        # → ../<repo>-wt-your-task-name on feature/your-task-name
```

Or by hand:

```bash
MAIN=$(git worktree list | head -1 | awk '{print $1}')
git -C "$MAIN" fetch origin
git -C "$MAIN" worktree add "$(dirname "$MAIN")/$(basename "$MAIN")-wt-NAME" -b feature/NAME origin/main
```

**Do NOT put the worktree under `.worktrees/`** — sibling agents prune that
directory (`git worktree prune` / `rm -rf .worktrees`) and it has killed
in-flight worktrees mid-task.  Use an **external** path (sibling of the repo),
which is what `scripts/new-worktree` does.

Then do **all** work (reads, writes, compiles, tests, commits) from the
worktree.  The absolute-path trap applies — use the worktree path, never a
bare `<repo-root>/...`.

**Guardrail (enforced):** a `pre-commit` hook (`.githooks/pre-commit`, activated
by `scripts/setup-hooks` / `core.hooksPath=.githooks`) **refuses** a non-`.work/`
commit made in the shared `main` checkout or on the `main` branch — so a feature
commit that drifts into shared `main` fails loudly instead of silently parking the
checkout on a feature branch (which has happened).  Coordination commits touching
only `.work/` are allowed; `git commit --no-verify` is the escape hatch.  See
[`specs/worktree-guardrail.md`](specs/worktree-guardrail.md).

Push when done — **directly from the worktree branch**, skipping the
shared `main` checkout entirely:

```bash
git -C "$WT" push origin "$BRANCH:main"
```

Clean up afterward:

```bash
git -C "$MAIN" worktree remove "$WT"
```

This pattern — `worktree add` → work → `push branch:main` → `worktree remove` —
is the safe path when `EnterWorktree` is not available.  It produces
exactly the same isolation guarantee.

#### The absolute-path trap (and how not to lose work to it)

The single most common way agents accidentally edit shared `main` is
the **absolute-path trap**: you're running in a worktree at
`<repo-root>/.worktrees/agent-XXX/`, but
you call `Write(file_path="<repo-root>/docs/foo.md", ...)`
out of habit — the project lives at that root, and the path looks
right.  The Write tool happily writes to shared `main` instead of
your worktree.  You don't notice because `sbt` still passes (it runs
out of shared `main`, which now silently has your edits), but
`git status` inside your worktree is clean — your "commit" never
makes it into your feature branch.

Every subagent we've launched has hit this at least once.  Prevention,
self-cleanup, and what NOT to do follow.

**Prevention — make the trap impossible:**

> **CORRECTED 2026-07-16 — this section used to advise the OPPOSITE, and the advice
> was itself causing the trap.**  It said "prefer relative paths … relative wins by
> construction".  **That is false in this harness, and it is the worst option.**
> Measured, not reasoned: `cd`-ing into a worktree in Bash and then calling
> `Write(file_path="_probe.txt")` created the file in **shared main**, not the
> worktree.  The harness **resets the shell CWD to the Primary working directory
> (shared main) after every Bash call**, and Write/Edit/Read resolve relative paths
> against that — not against wherever your last `cd` went.  So the old advice sent
> every agent's edits straight into the shared checkout.  Several did; each spent
> the same confused minutes rediscovering it.

- **Use ABSOLUTE paths rooted at YOUR WORKTREE** for every Write / Edit / Read of
  project files: `Write(file_path="/abs/path/to/<repo>-wt-<name>/docs/foo.md")`.
  Get the root once (`git rev-parse --show-toplevel` from inside the worktree, or
  the path `scripts/new-worktree` printed) and build every tool argument from it.
- **The smell is not "an absolute path" — it is an absolute path that starts with
  the SHARED MAIN root.**  `<repo-root>/docs/foo.md` is the bug;
  `<repo-root>-wt-<name>/docs/foo.md` is correct.  Check the prefix, not the shape.
- `pwd` in Bash tells you about the *Bash* call only, and even that resets.  It does
  **not** tell you where a Write will land.  Do not rely on it.
- After each edit, glance at `git status` *inside your worktree*.
  Clean status after you just edited something means you wrote to
  the wrong place.  This check is what actually catches it — it caught the
  2026-07-16 case in seconds.

**Context compaction — when summaries carry stale paths:**

Long sessions get compacted: a summary replaces the full transcript,
and the next session reads that summary as its starting point.
Summaries routinely contain absolute paths like
`<repo-root>/payments/foo/Bar.scala` — the paths
where the earlier session was (incorrectly) writing.  A new session
that blindly follows those paths writes to shared `main` again,
perpetuating the mistake across context boundaries.

How to break the cycle after compaction:

1. Read the `Primary working directory` line from the **system prompt**
   (not from the summary) — it is the authoritative CWD for this
   session.
2. Run `pwd` once.  If the output matches `Primary working directory`,
   you are in the worktree.  If not, `cd` there first.
3. Scan the summary for absolute paths.  Any path that starts with
   `<repo-root>/` but does *not* start with
   `<repo-root>/.worktrees/<name>/` is a
   shared-main path.  Treat it as a hint about *which file*, never as
   the write destination.  Rebuild the correct path by prepending the
   worktree root from step 1.
4. Your first Write / Edit call is the moment the cycle breaks or
   repeats.  Check the path one final time before sending it.

One quick sanity check: after your first edit, run `git status` inside
the worktree.  If the status is clean, you wrote to the wrong place —
stop and move the file per the self-cleanup steps below.

**Self-cleanup if it already happened (don't panic — files are not
lost yet):**

1. From shared `main`, list exactly what leaked: `git status -s`.
   Anything you recognise as **your** work (created or modified for
   the task you're on) is what you'll move; everything else belongs
   to a sibling agent and is **off-limits**.
2. For each of your files, `mv` (plain shell `mv`, not `git mv`) the
   file from the shared-main path to the matching worktree path.
   `git mv` would touch shared main's index — you want only working
   tree to move.
3. In shared `main`, unstage and restore only your files:
   `git restore --staged <file>` then `git restore <file>` (for a
   modification) or `rm <file>` (for a brand-new file you've already
   moved).  Touch only paths you own.
4. In your worktree, `git status` should now show your changes; stage
   and commit per Rule 3.
5. In shared `main`, `git status` should now show only the sibling
   agents' in-flight state — exactly as it was before you arrived.

**What NOT to do — these destroy other agents' work:**

- `git reset --hard` on shared `main` — wipes every sibling agent's
  staged refactor in one shot.  Never.
- `git stash` on shared `main` — stashes everyone's work together
  into a single opaque blob that is painful to untangle by author.
- `git checkout -- .` / `git restore .` / `git clean -fd` without
  a path argument — same problem, blanket destruction.
- Deleting whole directories you don't recognise (`rm -rf <dir>`) —
  sibling agents create new directories (cross-build subdirs,
  generated test fixtures, etc.) as part of legitimate work.

The rule of thumb: in shared `main`, you may only touch paths that
appear in your own worktree's diff against `origin/main`.  If
shared `main` shows extra files you didn't put there, those belong
to a sibling — leave them alone.

**If you can't safely clean up — report up, don't fix down:**

If the leak is intertwined with a sibling agent's work in a way you
can't separate confidently in <5 minutes, stop trying.  Two safe
exits:

1. **Push from your worktree directly:**
   `git push origin <your-feature-branch>:main` from inside the
   worktree skips the shared `main` checkout entirely.  Works as long
   as your branch is a fast-forward over `origin/main` (rebase first
   if not).  This lets your change land while shared `main` still has
   the sibling's uncommitted state, untouched.
2. **Report the leak in your task result.**  Tell the parent agent
   exactly which files in shared `main` look unfamiliar so it knows
   not to attribute them to you and can decide when (and by whom)
   they should be cleaned up.  The sibling agent will usually
   self-recover once it finishes its own work.

The point of all this: every sibling agent has work in flight that
you can't see.  Treat shared `main` as a hot kitchen — touch only
your own pots, leave the rest to their cooks.

**When launching sub-agents — brief them on this up front.**  A parent
that spawns parallel sub-agents owns the parallel-safety contract on
their behalf.  In every sub-agent prompt, surface the two non-obvious
rules that catch every first-time sub-agent:

- "Use **absolute paths rooted at YOUR WORKTREE** for every Write / Edit /
  Read of project files.  Relative paths do **NOT** resolve against your
  worktree: the harness resets the shell CWD to shared `main` after every
  Bash call, so a relative Write lands in the SHARED checkout (measured
  2026-07-16).  A path starting with the shared-main root is the bug; the
  same path under `<repo-root>-wt-<name>/` is correct.  After your first
  edit, run `git status` inside the worktree — a clean status means you
  wrote to the wrong place."
- "If a leak into shared `main` is confusing to clean up, prefer
  `git push origin <your-branch>:main` from inside the worktree — it
  lands your work without touching the shared checkout.  Do **not**
  `git reset --hard` / `git stash` / `git checkout -- .` on shared
  `main` to 'tidy up' — that destroys sibling agents' in-flight work."

These two lines in a sub-agent's prompt prevent the most common
parallel-coordination failure mode we've seen.

### 2. Before starting — sync + check (≤ 5 seconds)

**And to decide WHAT to work on: `scripts/next`.** One command. It prints how many problems exist,
which are unclaimed AND have an acceptance test (so you can tell when you are done), and the
ready-to-paste `coord-claim` line. Take the top row unless you were given a task.

This line is HERE, in the checklist, and not in POLICY.md, deliberately: measured on this project,
when the two disagreed the checklist won for weeks, because the checklist is what gets read before
starting. `scripts/next --self-test` asserts the ranking it claims to apply.


```bash
git fetch origin
git log origin/main --oneline -20      # what's already landed?
git worktree list                      # who's doing what?
for wt in $(git worktree list --porcelain | awk '/^worktree / {print $2}'); do
  [ "$wt" = "$PWD" ] && continue
  echo "=== $wt ==="
  git -C "$wt" branch --show-current
  git -C "$wt" status -s | head -5
  git -C "$wt" log --oneline origin/main..HEAD 2>/dev/null | head -5
done
```

Check both `git worktree list` (active worktrees) and
`git ls-tree origin/main .work/active/` (authoritative remote claims) before
deciding what's free.

**Git state is the RECORD of who holds what; the room is where an overlap gets
RESOLVED.** These are different jobs and this paragraph used to conflate them —
it said "pick a different item" and "don't coordinate through chat", which is the
opposite of POLICY §P-5.1 ("contested goes to the room — another agent's claim in
your way") and §P-2.5. Agents followed this checklist, because this is what gets
read before starting, and the result was work deferred that one message would
have unblocked.

So: a claim is only real when it is visible on `origin/main` (§P-2.4b) — that part
of "the git state is the contract" stands, and a claim announced only in chat is
not a claim. But when a sibling's scope is in your way, the move is to **say so in
the room**, naming the file and what you need:

```
@their-slug I need ~10 lines in budgetFor() of scripts/smoke-ci.ssc.
You're in the check list. Widen to share, or shall I wait?
```

Measured 2026-08-10, over 30 days: of 143 pairs of commits from DIFFERENT claims
touching the same file within six hours, **43 (30%) touched overlapping line
ranges** — so seven times in ten a rival edit would have merged with no conflict
at all, and the block bought nothing. In the other three, the two agents needed
to talk anyway. Picking a different item is right only when the work itself is
duplicated (§P-2.4c), not when the paths merely touch.

Waiting is also usually short: one claim is one task (§P-2.6), so the honest
answer to "shall I wait?" is frequently minutes.

If your item already landed on `origin/main` (search recent commits),
mark it done in `BACKLOG.md` + add a line to `CHANGELOG.md`, then move on.

**Returning to an existing branch / worktree between iterations.**  If
you're continuing work in a `feature/<name>` worktree you opened in a
previous turn, do the same sync at the start of *every* iteration,
then rebase your branch on the freshly-fetched `origin/main` before
touching anything:

```bash
git fetch origin
git rebase origin/main
```

Parallel agents may have pushed runtime / API / SPI changes that your
in-flight edits depend on; skipping the rebase means you're building
on stale assumptions and will hit merge conflicts later.  Cheap to do,
expensive to skip.

**`git log origin/main` is the ground truth — not `git log`.**  After a
context-overflow session, local `main` may lag behind `origin/main` by
one or more commits that were already pushed.  `git log` shows the
*local* branch HEAD; `git log origin/main -10` shows what actually
landed on the remote.  Always use the latter to decide whether a task
is already done before starting it.

**Re-read `AGENTS.md` and the milestone files after every rebase.**  Both
are living documents — workflow rules and the backlog change between sessions.
After `git rebase origin/main`, check whether any key file was updated:

```bash
git diff HEAD~1..HEAD -- AGENTS.md MILESTONES.md BACKLOG.md SPRINT.md CHANGELOG.md
```

If `AGENTS.md` changed: re-read it fully before proceeding — new rules
may affect how you should do the current task.  If any milestone file
changed: re-read the relevant sections to pick up scope changes, completed
phases from other agents, or new follow-ups that were appended.  Never assume
your in-memory picture of the backlog or the rules is still current after a rebase.

If your worktree was switched to `main` between turns (this happens —
other agents do `git checkout main` in the shared repo), **don't start
editing on main**.  Switch back to your feature branch first per Rule 1:

```bash
git checkout feature/<name>           # back to your branch
git fetch origin && git rebase origin/main
# then re-read AGENTS.md + MILESTONES.md as above
```

### 3. Every finished piece → straight to `origin/main`

The moment a piece is independently shippable (compile clean + tests pass),
run this checklist **before** merging:

#### 3a. Update documentation

**When a doc update is required, and how it is committed, is [`POLICY.md`](POLICY.md) §P-1.4.**
Which doc to touch:

| Doc | When to update |
|-----|---------------|
| `README.md` | Every feature — add or update the capabilities-table row, CLI flag line, or examples-table entry |
| `docs/user-guide.md` | New block type, new front-matter key, new CLI flag, new API — add a subsection under the relevant section |
| `docs/tutorial.md` | Feature changes a pattern users follow step-by-step — update the relevant tutorial |
| `docs/<feature>.md` | Feature has its own spec doc — keep it in sync with what was actually built (see "Keep the spec in sync" above) |

```bash
git commit -m "docs(<slug>): update user-guide + spec for <feature>"
git push origin <branch>:main
```

#### 3b. Update milestone files

**MANDATORY: queue/milestone updates go in their own separate commit**,
after the doc commit (or after the feature commit if no doc changes).
Never bundle queue bookkeeping into a feature or doc commit.

Steps:
- Remove item from `SPRINT.md` (delete the `[ ]` entry).
- Open item in `BACKLOG.md` → update with `✓ Landed (YYYY-MM-DD)` and summary.
- Milestone fully complete → remove from `BACKLOG.md`, add one-liner to
  `CHANGELOG.md` (newest-first).
- Prepend a done-entry to `CHANGELOG.md` for the completed task.

Example final two commits after every piece of work:

```bash
# Commit 1 — documentation (if any doc changed)
git add docs/ README.md
git commit -m "docs(<slug>): <what changed>"
git push origin <branch>:main

# Commit 2 — queue / milestone bookkeeping (always)
git add SPRINT.md BACKLOG.md CHANGELOG.md   # whichever apply
git commit -m "docs: mark <slug> done in SPRINT + CHANGELOG entry"
git push origin <branch>:main
```

Both commits must be pushed before the task is considered finished.

#### 3c. Merge and push

```bash
git fetch origin
git rebase origin/main           # if origin/main moved
# re-run the suite if the rebase touched anything
<merge or fast-forward into main>
git push origin main
git -C "$MAIN" fetch origin
git -C "$MAIN" merge --ff-only origin/main
```

No "accumulate and push at the end of the sprint". Each piece gets its
own CI run; the user sees progress item-by-item and can redirect after
any of them.

The local `main` fast-forward sync is mandatory: when pushing from a worktree
with `git push origin <branch>:main`, the checked-out shared `main` working tree
is **not** updated automatically. Without this step the next session sees a
stale local `main`, thinks the work was not pushed, and may redo it. Do not use
`git branch -f main origin/main` while `main` is checked out in the shared repo;
Git rejects that. Use the explicit `git -C ... merge --ff-only origin/main`
sync above.

### 4. After merge — delete worktree + branch immediately

**This is mandatory, not optional.** The moment work is merged and pushed
to `origin/main`, clean up:

```bash
# After the feature branch has been pushed to origin/main and local main synced:
cd "$MAIN"                                       # go to shared main repo
git worktree remove --force <path-to-worktree>   # remove working dir
git branch -D <branch-name>                      # delete local branch
git push origin --delete <branch-name>           # delete remote branch (if pushed)
```

Or equivalently via the tool:
```bash
ExitWorktree(action: "remove")
git branch -D feature/<name>
```

No dangling feature branches. The worktree branch dies with the merge.

**Why this matters:** orphaned worktree directories accumulate on disk,
pollute `git worktree list`, and mislead the next session into thinking
work is still in progress.  A clean repo state is part of "done".

### 5. Exception — large features with intermediate broken state

If a single iteration leaves existing functionality broken (mid-refactor
of an SPI, half-migrated runtime, etc.), commit it to the feature branch,
not to `main`. As soon as a later iteration restores green, push to
`main` per rule 3 and keep going from there. The feature branch exists
as a safety net for unshippable intermediate states — **not** as a place
to accumulate shippable work.

### 6. Large features / milestones — iterate the loop

For multi-iteration work (a whole milestone, a cross-backend feature,
a sweep through a test family), don't ask "what's next?" after each
shipped piece. Run the loop yourself:

```
while milestone has open work:
    pick next slice
    do the work (in worktree per rule 1)
    run the relevant tests
    if green: commit, merge to main, push, delete worktree (per rules 3-4)
    if red:   fix or revert; don't push a broken slice
```

Stop only when:
- the milestone's "What landed" checklist is complete, **or**
- you hit something genuinely ambiguous and need user input on direction
  (not "should I continue?" — yes, continue), **or**
- the user interrupts.

Don't pause between slices to summarise or ask permission. The user
sees progress through commits and pushes; ask only when you need a
decision they have to make.

### Milestone files are the durable plan

The milestone files survive context rotations — chat history doesn't.

| File | Purpose |
|------|---------|
| `MILESTONES.md` | Navigation index — quick status, links to the files below |
| `BACKLOG.md` | Open and planned milestones with full detail — what still needs doing |
| `SPRINT.md` | Agent task queue — active pending tasks |
| `CHANGELOG.md` | Completed milestones, compact, newest first |

Use them to:

- **Pick** the next item: read `SPRINT.md` first; if empty, read `BACKLOG.md` top-to-bottom; check `.work/active/` for claimed slugs.
- **Mark items landed** in the same commit that closes them — never push
  a finished feature whose milestone entry is still open.
- **Mark phases complete** after each iteration: update the entry in `BACKLOG.md`
  with `✓ Landed (YYYY-MM-DD)` and a short summary of what was built.
  Do this in the same push as the implementation — not later.
- **Mark a milestone complete**: remove its entry from `BACKLOG.md`
  and add a one-line summary entry (newest-first) in `CHANGELOG.md`.
- **Capture follow-ups** discovered while working: append to the relevant section
  in `BACKLOG.md` or to the "Known issues / latent flakes" section before moving on.
  See §"Recording tech debt and improvements" below for the exact protocol.

### In one sentence

**Work in your own worktree, push the moment it's shippable, clean up
after yourself.**

---

## Task claiming protocol (multi-agent coordination)

See the `/multi-agent` skill for the full protocol (claim, heartbeat, triage, release).

Skill location: `.agents/plugins/multi-agent/commands/multi-agent.md` (in main repo — use `$MAIN` from above).

**The rules are [`POLICY.md`](POLICY.md) §P-2** — claim from the main checkout before planning via
`scripts/coord-claim` (P-1.1, P-2.4b); a claim counts when it is on `origin/main`; declare `items:`
and `paths:`; `verify-<slug>` for a deliberate cross-check (P-2.4c); a stale heartbeat is not
liveness (P-2.5). Not restated here.

**Operational detail, which is this file's half:**

- **The 45-minute rule, and it needs BOTH signals cold.** A claim is a triage candidate only when
  the `heartbeat` field is older than 45 min (or missing) **AND** no commit for it landed in that
  window — neither the branch tip, local or remote, nor the claim file. `scripts/coord-status`
  applies this and prints `live by COMMIT activity (stale heartbeat field, ignored)`; read that
  line as *do not touch*. Only when both are cold, run `/multi-agent triage <slug>`.
  (Measured 2026-07-30: a claim carried a **10.7-hour-old** field while landing 13 commits in the
  hour it was reported stale. It was triaged as orphaned twice, and a manual `git log` is what
  stopped an edit landing in a file that agent was actively working in. BUGS
  `heartbeat-stale-while-active`.)
- **Heartbeat on a material status change, not as running commentary.** The threshold is a floor,
  not a target. Raised from 20 min on 2026-07-28 — 202 of 253 commits in one 6-hour window carried
  no code. **Do not "fix" a stale field by heartbeating more often**: the commits you are making
  anyway are the liveness signal, and the field is only for an agent with nothing to commit yet
  (planning, a long build).
- Files in `.work/active/` without a `.claim` suffix are invalid markers — report or repair before
  starting. Never assume a claim is yours: read the `agent:` field first.

Quick reference:
- `scripts/coord-claim <slug> --items … --paths …` — claim (preferred; keeps the ledger correct)
- `/multi-agent status` — active claims, heartbeat ages, pending tasks
- `/multi-agent claim <slug>` — claim a task (legacy path; does not bump the ledger)
- `/multi-agent triage <slug>` — assess a foreign claim
- `/multi-agent heartbeat` — refresh your heartbeat
- `/multi-agent release <slug>` — release a stale claim
- `scripts/coord-status` — read-only status check (preferred)

How to read agent status:

| Signal | Meaning |
|--------|---------|
| `<slug>.claim` in `git ls-tree origin/main .work/active/` | In progress by another agent |
| `release-claim: <slug>` in `git log origin/main` | Done — released |
| Worktree exists but no claim on origin/main | Cleanup artifact |

`git ls-tree origin/main .work/active/` is the only authoritative source — not `ls .work/active/`, `git worktree list`, or `SPRINT.md`.

---

## Autonomous continuous-delivery flow

See the `/multi-agent` skill for the full loop protocol (status, start/stop, loop steps, empty queue, tech debt, /compact).

### Status format

```
ACTIVE: <slug> [direction]      ← or "nothing active"

Frontend & Clients    1 pending
Language & Compiler   2 pending
Database              4 pending
Payments & Blockchain 6 pending
Native Platform       1 pending

Next up: <slug> — <one-line description>
```

Directions are independent — multiple agents can work in parallel, one per direction. Extra start phrase: "работай над Database" / "do Payments" — work only that direction, then stop.

### Documentation updates (loop step 7)

| Doc | When to update |
|-----|----------------|
| `README.md` | Every feature — capabilities table, CLI flag, examples table |
| `docs/user-guide.md` | New block type, front-matter key, CLI flag, API |
| `docs/tutorial.md` | Feature changes a step-by-step pattern users follow |
| `docs/<feature>.md` | Feature has its own spec — keep in sync |

### Bookkeeping commit (loop step 8)

```bash
git rm .work/active/<slug>.claim
# remove item from SPRINT.md (delete the [ ] entry)
# update BACKLOG.md / CHANGELOG.md as appropriate
git commit -m "docs: mark <slug> done in SPRINT + CHANGELOG entry"
```

### Empty queue example

```
Очередь пуста. Из BACKLOG предлагаю добавить:

  Database:    v1.26-sql-jdbc — JDBC sql blocks (~1 неделя)
               Разблокирует v1.27 (browser SQL) и v1.31 (transactions).

  Payments:    v1.38-payment-request — Payment Request API (~3 дня)
               Standalone — не зависит от x402 или blockchain SPI.

  Compiler:    interpreter-ergonomics — better errors + REPL (~2 дня)
               Маленькая задача, хорошо подходит для параллельного агента.

Что добавить в очередь?
```
