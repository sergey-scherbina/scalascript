# ScalaScript (`.ssc`) — Project Bootstrap Brief

> This file is the durable memory of pre-code design decisions.
> Every new Claude Code session should read it first.

## MANDATORY: required skills

Two skills govern how all feature work is done. Read their full instructions
before starting any implementation. Skill files live in the submodule that is
**only initialized in the shared main repo** — always read from the absolute
main-repo path below, regardless of whether you are in a worktree:

| Skill | File | When to use |
|---|---|---|
| `spec-dev` | `/Users/sergiy/work/my/scalascript/.agents/plugins/spec-dev/commands/spec-dev.md` | Every new feature or change |
| `multi-agent` | `/Users/sergiy/work/my/scalascript/.agents/plugins/multi-agent/commands/multi-agent.md` | Autonomous loop / queue work |

**Do NOT run `git submodule update --init` inside a worktree** — the submodule
is already initialized in the shared main repo, and re-initializing it in each
worktree is unnecessary and error-prone. Always read skill files via the
absolute `/Users/sergiy/work/my/scalascript/.agents/plugins/…` path.

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
WT="/Users/sergiy/work/my/scalascript/.worktrees/$BRANCH"
git -C /Users/sergiy/work/my/scalascript fetch origin
git -C /Users/sergiy/work/my/scalascript worktree add "$WT" -b "$BRANCH" origin/main
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

## MANDATORY: persist everything needed to continue from a fresh context

The session that records is not the session that resumes. A parallel
agent — or yourself after a `/clear` — must be able to pick up the work
cold, without re-deriving baselines, re-discovering pitfalls, or
re-investigating decisions you already made. **Anything in your active
context that is not written down is one `/clear` away from being lost.**

Treat persistence as a **continuous activity**, not an end-of-session
chore. The moment you learn something durable, record it.

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

Skill location: `/Users/sergiy/work/my/scalascript/.agents/plugins/multi-repo/commands/multi-repo.md` (shared main repo).

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

## Immediate next steps for Claude Code

1. Confirm the open questions with Sergiy.
2. `git init`; create remote via `gh repo create <name> --<public|private> --source=. --remote=origin --push`.
3. Scaffold (spec only, no implementation yet):
   - `README.md` — overview, motivation, links
   - `SPEC.md` — canonical language spec (lexical, syntactic, type system, semantics, module system)
   - `docs/markdown-as-syntax.md` — how each Markdown construct maps to AST nodes, with worked examples
   - `docs/targets.md` — backend translation model and target capability matrix
   - `docs/architecture.md` — pipeline: source → tokens → AST → typed IR → backend
   - `lang/grammar/scalascript.ebnf` — formal grammar
   - `lang/schemas/frontmatter.yaml` — JSON Schema for the module-manifest YAML
   - `examples/hello.ssc`, `examples/typed-data.ssc`, `examples/imports.ssc`
   - `LICENSE`, `CONTRIBUTING.md`, `.gitignore`
4. First commit: **spec only, no compiler**. Compiler comes in subsequent milestones.
5. Set up minimal CI (GitHub Actions) that lints Markdown + validates YAML against the schema. No compilation yet — there is nothing to compile.

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

---

## Spec-driven development

See the `/spec-dev` skill for the full workflow (write → implement → verify).

Skill location: `/Users/sergiy/work/my/scalascript/.agents/plugins/spec-dev/commands/spec-dev.md` (shared main repo).

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

## Workflow for parallel agents

Multiple agents run independently in parallel, each in its own worktree.
To stay out of each other's way — five rules, all mandatory:

### 1. One agent = one worktree = one branch

`EnterWorktree(<descriptive-name>)` on a `feature/<name>` branch off
`origin/main`. All edits, commits, and `sbt compile` / `sbt test` runs
happen inside that worktree.  **Never edit, commit, or run tests
directly on `main`** — in this repo other agents are essentially
always running in parallel, and they will switch the shared checkout's
HEAD, stash your uncommitted state, and `git clean` your untracked
files (it has happened repeatedly).  Even "small" changes to `AGENTS.md`
/ `BACKLOG.md` / a one-line bug fix go through a worktree on a
feature branch.

The shared `main` checkout is only ever for three things:

1. Reading state (`git log`, `git status`, file viewing).
2. Fast-forward merge of *your* `feature/<name>` branch into `main`
   followed immediately by `git push origin main` (Rule 3).  No
   stops, no edits in between.
3. Coordination operations that must be visible on `origin/main`:
   task claims, claim releases, pause/resume files, and local `main`
   synchronization.  These operations stage only the coordination file
   they are changing and never touch sibling dirty work in shared `main`.

#### When EnterWorktree is unavailable — create one manually

`EnterWorktree` may be rejected with "Must not already be in a worktree"
even when the session's `Primary working directory` is not a real git
worktree (it's just a regular directory inside the main repo tree with no
`.git` file).  **Always verify before trusting the system prompt:**

```bash
# A real worktree has a .git FILE whose content starts with "gitdir:"
cat .git 2>/dev/null | head -1
# Real worktree → "gitdir: /Users/sergiy/work/my/scalascript/.git/worktrees/NAME"
# NOT a worktree → file missing, or .git is a directory
```

If the check shows you are **not** in a real worktree, create one now
with plain git — no tool needed:

```bash
BRANCH="feature/your-task-name"
WT="/Users/sergiy/work/my/scalascript/.worktrees/$BRANCH"
git -C /Users/sergiy/work/my/scalascript worktree add "$WT" -b "$BRANCH"
```

Then do **all** work (reads, writes, compiles, tests, commits) from `$WT`
using relative paths or `$WT`-prefixed absolute paths.  The absolute-path
trap applies here too — use `$WT/...`, never `/Users/sergiy/work/my/scalascript/...`.

Push when done — **directly from the worktree branch**, skipping the
shared `main` checkout entirely:

```bash
git -C "$WT" push origin "$BRANCH:main"
```

Clean up afterward:

```bash
git -C /Users/sergiy/work/my/scalascript worktree remove "$WT"
```

This pattern — `worktree add` → work → `push branch:main` → `worktree remove` —
is the safe path when `EnterWorktree` is not available.  It produces
exactly the same isolation guarantee.

#### The absolute-path trap (and how not to lose work to it)

The single most common way agents accidentally edit shared `main` is
the **absolute-path trap**: you're running in a worktree at
`/Users/sergiy/work/my/scalascript/.worktrees/agent-XXX/`, but
you call `Write(file_path="/Users/sergiy/work/my/scalascript/docs/foo.md", ...)`
out of habit — the project lives at that root, and the path looks
right.  The Write tool happily writes to shared `main` instead of
your worktree.  You don't notice because `sbt` still passes (it runs
out of shared `main`, which now silently has your edits), but
`git status` inside your worktree is clean — your "commit" never
makes it into your feature branch.

Every subagent we've launched has hit this at least once.  Prevention,
self-cleanup, and what NOT to do follow.

**Prevention — make the trap impossible:**

- Run `pwd` as the first command in your worktree session, and
  prefer **relative paths** for every Write / Edit / Read of project
  files.  `Write(file_path="docs/foo.md")` resolves against the
  worktree CWD; `Write(file_path="/Users/sergiy/.../docs/foo.md")`
  doesn't.  Relative wins by construction.
- If you must use an absolute path (some tools / scripts require it),
  build it from `$(pwd)` first — never hand-type the full project
  root in a `Write` / `Edit` call.  Hardcoded
  `/Users/sergiy/work/my/scalascript/...` strings in tool arguments
  are the smell.
- After each edit, glance at `git status` *inside your worktree*.
  Clean status after you just edited something means you wrote to
  the wrong place.

**Context compaction — when summaries carry stale paths:**

Long sessions get compacted: a summary replaces the full transcript,
and the next session reads that summary as its starting point.
Summaries routinely contain absolute paths like
`/Users/sergiy/work/my/scalascript/payments/foo/Bar.scala` — the paths
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
   `/Users/sergiy/work/my/scalascript/` but does *not* start with
   `/Users/sergiy/work/my/scalascript/.worktrees/<name>/` is a
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

- "Always use **relative paths** for Write / Edit / Read of project
  files; verify `pwd` once at the start and don't hand-type
  `/Users/sergiy/work/my/scalascript/...` into tool arguments.  The
  absolute-path trap (described in AGENTS.md §1) hits shared `main`
  silently."
- "If a leak into shared `main` is confusing to clean up, prefer
  `git push origin <your-branch>:main` from inside the worktree — it
  lands your work without touching the shared checkout.  Do **not**
  `git reset --hard` / `git stash` / `git checkout -- .` on shared
  `main` to 'tidy up' — that destroys sibling agents' in-flight work."

These two lines in a sub-agent's prompt prevent the most common
parallel-coordination failure mode we've seen.

### 2. Before starting — sync + check (≤ 5 seconds)

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

If a sibling's branch name, modified files, or recent commits overlap
with your candidate item — pick a different item. Check both
`git worktree list` (active worktrees) and
`git ls-tree origin/main .work/active/` (authoritative remote claims) before
deciding what's free. Don't coordinate through chat; the git state is the
contract.

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

Every user-facing feature must ship with matching doc updates in the same
push.  Four places to check:

| Doc | When to update |
|-----|---------------|
| `README.md` | Every feature — add or update the capabilities-table row, CLI flag line, or examples-table entry |
| `docs/user-guide.md` | New block type, new front-matter key, new CLI flag, new API — add a subsection under the relevant section |
| `docs/tutorial.md` | Feature changes a pattern users follow step-by-step — update the relevant tutorial |
| `docs/<feature>.md` | Feature has its own spec doc — keep it in sync with what was actually built (see "Keep the spec in sync" above) |

A feature with no doc update is **incomplete** — treat it the same as a
failing test.

**MANDATORY: doc updates go in their own separate commit** (not mixed with
feature code).  Example:

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
git -C /Users/sergiy/work/my/scalascript fetch origin
git -C /Users/sergiy/work/my/scalascript merge --ff-only origin/main
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
cd /Users/sergiy/work/my/scalascript             # go to shared main repo
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

Skill location: `/Users/sergiy/work/my/scalascript/.agents/plugins/multi-agent/commands/multi-agent.md` (shared main repo).

Key invariants:
- Claim from the **main checkout** (`/Users/sergiy/work/my/scalascript`) only — never from a worktree
- A claim is valid only when `.work/active/<slug>.claim` is visible on `origin/main`
- Files in `.work/active/` without `.claim` suffix are invalid markers — report or repair before starting
- Never assume a claim is yours; read the `agent:` field first
- Heartbeat > 20 min = potentially orphaned; run `/multi-agent triage <slug>` before touching

Quick reference:
- `/multi-agent status` — active claims, heartbeat ages, pending tasks
- `/multi-agent claim <slug>` — claim a task
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
