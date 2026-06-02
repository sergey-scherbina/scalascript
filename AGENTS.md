# ScalaScript (`.ssc`) — Project Bootstrap Brief

> This file is the durable memory of pre-code design decisions.
> Every new Claude Code session should read it first.

## MANDATORY: first action in every session

Before any file read, planning, or write — verify whether you are in a worktree.

```bash
cat .git 2>/dev/null | head -1
```

| Output | Meaning | Action |
|--------|---------|--------|
| `gitdir: /path/.git/worktrees/NAME` | ✓ in a worktree | continue |
| file missing or `.git` is a directory | ✗ in shared main | **create a worktree right now** |

If you are **not** in a worktree — create one before doing anything else:

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
baselines in `WORK_QUEUE.md` / `docs/vm-jit-next.md`, **name the
`scripts/bench` command that produced the number** so the next agent
re-runs the same configuration.

## MANDATORY: write to `AGENTS.md` in English only

Project documentation in `AGENTS.md` is the durable session brief used by
every agent (and across languages). Keep it consistently in English even
when the surrounding conversation is in another language. The same rule
applies to all other shared documentation files (`docs/`, `BACKLOG.md`,
`docs/vm-jit-next.md`, etc.) unless they are explicitly localised.

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
| Ordered queue + per-task implementation notes + gotchas | `WORK_QUEUE.md` |
| Design specs, roadmaps | `docs/*.md` |
| Project-specific durable knowledge | `~/.claude/projects/.../memory/project_*.md` |
| Reusable methodology, user preferences | `~/.claude/projects/.../memory/feedback_*.md` |
| Point-in-time decisions tied to a specific change | Git commit messages |
| Non-obvious WHY in surprising code | Source-code comments (only when removing them would confuse a future reader) |

The same fact can — and often should — live in two places. A benchmark
baseline written into both `WORK_QUEUE.md` (where the next agent looks
first when asked "what to do") and `docs/vm-jit-next.md` (where the
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

All non-trivial new features start with a **spec document** in
`docs/<feature>.md` **before** any implementation work begins. The
spec PR lands on its own; implementation PRs reference the spec by
path and may amend it as reality diverges — but the spec remains the
source of truth.

What counts as non-trivial (spec required):

- A new module / package in `build.sbt`
- A new SPI trait or contract other code will depend on
- A cross-cutting refactor that touches more than one backend
- Any new top-level milestone added to `BACKLOG.md`

A spec covers, at minimum:

1. **Goals** — what the feature is for; what problem it solves.
2. **Non-goals** — what is explicitly out of scope (prunes review
   feedback, protects the spec from scope creep).
3. **Architecture** — module layout, contract surface (trait
   signatures, key types), composition with existing code.
4. **Migration** — what changes for callers of related existing
   code; how the transition happens (one-shot replace / adapter
   shim / parallel module).
5. **Phases** — concrete iterations, each independently shippable
   per Rule 3 below.
6. **Testing strategy** — what level of testing each phase needs.
7. **Open questions** — decisions required before Phase 1 starts.

Trivial changes (bug fixes, single-file refactors, dependency bumps,
doc edits, hardcoded-value tweaks) do **not** need a spec.

### Keep the spec in sync with the implementation

If reality diverges from the spec during implementation (an API turns
out differently, a design choice changes, a phase gets split or
merged), **update the spec in the same commit** — not in a follow-up.
A spec that describes what was originally planned rather than what was
actually built is worse than no spec: it misleads future agents.

What "spec drift" looks like and what to do:

- API signature changed → update `docs/<feature>.md` architecture section
- Phase split into two → update phases list + `BACKLOG.md` / `ACTIVE.md` phases
- A non-goal turned out to be necessary → move it to Goals, note why
- An approach was abandoned → add a "Design notes" section explaining
  what was tried and why it was replaced

Never leave a "TODO: update spec" comment — do it now.

### Every user-facing feature needs an example

Any feature that a `.ssc` user would call directly gets a working
example in `examples/`. The example must:

- Be self-contained (runnable with `ssc run examples/<name>.ssc`)
- Demonstrate the golden path, not edge cases
- Be referenced from the feature's spec and from `README.md` examples table

Minimum: one `.ssc` file. For multi-file features (plugin + consumer,
REST API + client): a subdirectory `examples/<feature>/`.

This is not optional for features shipped in a milestone. A feature
with no example is considered incomplete for milestone-closure purposes.

Existing specs to mirror in style and depth:
[`docs/backend-spi.md`](docs/backend-spi.md),
[`docs/x402.md`](docs/x402.md),
[`docs/runtime-server-strategic-plan.md`](docs/runtime-server-strategic-plan.md).

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

The shared `main` checkout is only ever for two things:

1. Reading state (`git log`, `git status`, file viewing).
2. Fast-forward merge of *your* `feature/<name>` branch into `main`
   followed immediately by `git push origin main` (Rule 3).  No
   stops, no edits in between.

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
`git worktree list` (active worktrees) and `.work/active/` (claimed tasks)
before deciding what's free. Don't coordinate through chat; the git state
is the contract.

If your item already landed on `origin/main` (search recent commits),
mark it done in `BACKLOG.md`/`ACTIVE.md` + add a line to `CHANGELOG.md`, then move on.

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
git diff HEAD~1..HEAD -- AGENTS.md MILESTONES.md BACKLOG.md ACTIVE.md CHANGELOG.md
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
failing test.  The doc commit may land on the same branch as the code,
pushed together or as a follow-up commit in the same push.

#### 3b. Update milestone files

Mark the phase or item landed in the same push:
- Open item in `BACKLOG.md` or `ACTIVE.md` → update with `✓ Landed (YYYY-MM-DD)` and summary.
- Milestone fully complete → remove from `BACKLOG.md`/`ACTIVE.md`, add one-liner to `CHANGELOG.md`.

#### 3c. Merge and push

```bash
git fetch origin
git rebase origin/main           # if origin/main moved
# re-run the suite if the rebase touched anything
<merge or fast-forward into main>
git push origin main
git branch -f main origin/main   # keep local main in sync after push
```

No "accumulate and push at the end of the sprint". Each piece gets its
own CI run; the user sees progress item-by-item and can redirect after
any of them.

The `git branch -f main origin/main` line is mandatory: when pushing
from a worktree with `git push origin <branch>:main`, the local `main`
branch is **not** updated automatically.  Without this step the next
session sees a stale local `main`, thinks the work was not pushed, and
may redo it.

### 4. After merge — delete worktree + branch immediately

**This is mandatory, not optional.** The moment work is merged and pushed
to `origin/main`, clean up:

```bash
# From inside the worktree:
git push origin main                              # if not already pushed
cd /Users/sergiy/work/my/scalascript             # go to main repo
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
| `MILESTONES.md` | Navigation index — quick status, links to the three files below |
| `BACKLOG.md` | Open and planned milestones with full detail — what still needs doing |
| `ACTIVE.md` | Milestones currently in progress (synced with `WORK_QUEUE.md`) |
| `CHANGELOG.md` | Completed milestones, compact, newest first |

Use them to:

- **Pick** the next item: read `BACKLOG.md` top-to-bottom; check `WORK_QUEUE.md` for claimed slugs.
- **Mark items landed** in the same commit that closes them — never push
  a finished feature whose milestone entry is still open.
- **Mark phases complete** after each iteration: update the entry in `BACKLOG.md`
  (or `ACTIVE.md`) with `✓ Landed (YYYY-MM-DD)` and a short summary of what was built.
  Do this in the same push as the implementation — not later.
- **Mark a milestone complete**: remove its entry from `BACKLOG.md`/`ACTIVE.md`
  and add a one-line summary entry (newest-first) in `CHANGELOG.md`.
- **Capture follow-ups** discovered while working: append to the relevant section
  in `BACKLOG.md` or to the "Known issues / latent flakes" section before moving on.
  See §"Recording tech debt and improvements" below for the exact protocol.

### In one sentence

**Work in your own worktree, push the moment it's shippable, clean up
after yourself.**

---

## Task claiming protocol (multi-agent coordination)

`WORK_QUEUE.md` lists tasks in priority order. Multiple agents can run
simultaneously without stepping on each other by using this protocol.

### How it works

The coordination primitive is **`git push origin main`**: only one agent
can land a fast-forward push at a time. A claim is a tiny push that adds
one file; the loser's push gets rejected and they retry with a fresh view.

Active claims live in `.work/active/`. Each file is named `<task-slug>.claim`
and contains the worktree name of the agent working on it. File names are
unique by task slug, so two agents can never produce a git conflict when
adding different tasks' claim files. Conflicts arise only if two agents
claim the *same* task simultaneously — but the rejected push prevents that.

### Claiming a task (step by step)

Run these commands from the **main checkout** (not inside a worktree):

```bash
# ══ MUST run from the MAIN CHECKOUT (/Users/sergiy/work/my/scalascript) ══
# ══ NEVER run from a worktree — commits there go on the feature branch,  ══
# ══ not on main, so the claim file never reaches origin/main.            ══

# 1. Fetch fresh remote state
git fetch origin

# 2. Read what's available — use git show/ls-tree (safe from any context)
git show origin/main:WORK_QUEUE.md              # ordered pending list
git ls-tree origin/main .work/active/           # currently claimed slugs
# ⚠ Do NOT use `cat WORK_QUEUE.md` or `ls .work/active/` — those read from
#   the local branch, which may be stale or a worktree's feature branch.

# 3. Pick the highest-priority Pending task whose slug has no .claim file
#    (if every Pending task is claimed, wait or read BACKLOG.md for unlisted work)
TASK_SLUG="v1.46-phase5-derivation"   # example
WORKTREE_NAME="feature+phase5-derivation"

# 4. Sync the main checkout, then write the claim file
git reset --hard origin/main          # ← only safe on the main checkout
echo "$WORKTREE_NAME $(date -u +%Y-%m-%dT%H:%M:%SZ)" > ".work/active/${TASK_SLUG}.claim"
git add ".work/active/${TASK_SLUG}.claim"
git commit -m "claim: ${TASK_SLUG}"

# 5. Atomic push — this is the mutex
git push origin main
# SUCCESS → you own the task, proceed to step 6
# REJECTED (non-fast-forward) → another agent moved main first; go back to step 1
```

**Why this works:** If agents A and B both try to claim the same task at the same
instant, both push. Git allows only one to fast-forward; the other is rejected
with "Updates were rejected because the tip of your current branch is behind".
The loser refetches, sees the winner's claim file, and picks a different task.

**Why worktrees break claiming:** Inside a worktree, `git commit` writes to the
worktree's feature branch (e.g. `feature/v153`). Then `git push origin main`
pushes the LOCAL `main` ref — which has no new commit. The push succeeds silently,
the claim file is invisible on `origin/main`, and another agent picks the same task.

### Starting work after a successful claim

```bash
# Claim succeeded — now enter a worktree off the fresh origin/main
# (which already includes your claim commit)
EnterWorktree("${WORKTREE_NAME}")
# ... do the work normally per Rules 1–5 ...
```

### Completing a task

In your worktree, before the final push:

```bash
# Remove your claim file
git rm ".work/active/${TASK_SLUG}.claim"

# Mark the task done in WORK_QUEUE.md (change [ ] → [x])
# ... edit WORK_QUEUE.md ...
git add WORK_QUEUE.md

# Include these in your final commit (or as a separate follow-up commit)
git commit -m "done: ${TASK_SLUG} — <one-line summary>"
git push origin "${WORKTREE_NAME}:main"
```

Removing the claim and marking done **must be in the same push as the feature
work** so there is never a window where the task looks incomplete while the
code is already on main.

### Checking who's doing what

Preferred quick check:

```bash
scripts/coord-status
```

It reads `origin/main` for the queue and claims, shows local worktrees,
flags stale-looking claims, marks pending items that already look occupied by
live worktrees/branches, and avoids shell-specific pitfalls in the manual
commands below.

```bash
git fetch origin
git ls-tree origin/main .work/active/   # all active claims on remote (authoritative)
# or parse the content of each claim:
git ls-tree origin/main .work/active/ | awk '{print $4}' | grep '\.claim$' | while read claim_file; do
  printf "%-40s %s\n" "$(basename "$claim_file" .claim)" "$(git show "origin/main:$claim_file")"
done
# ⚠ Do NOT use `ls .work/active/` or `cat .work/active/*.claim` from a worktree —
#   those read from the local branch which may predate recent claim commits.
# ⚠ In zsh, do not name the loop variable `path`: it is tied to `PATH` and can
#   make commands like `git` and `basename` disappear inside the loop.
```

### Stale claims (agent died, timed out, or was interrupted)

A claim file with no corresponding live worktree is stale. To release it:

```bash
git fetch origin && git reset --hard origin/main
git rm ".work/active/${TASK_SLUG}.claim"
git commit -m "release-claim: ${TASK_SLUG} (agent gone)"
git push origin main
```

Only do this if you are certain the agent that wrote the claim is no longer
running (check `git worktree list` and the timestamp inside the file).

---

## Autonomous continuous-delivery flow

### Status command

When the user says **"статус"** / **"status"** / **"план"** / **"что делаем"**:

1. `git fetch origin` — get fresh state
2. Read `WORK_QUEUE.md` groups + `ls .work/active/`
3. Print a structured summary (do NOT start working):

```
ACTIVE: <slug> [direction]      ← if something is claimed, else "nothing active"

Frontend & Clients    1 pending
Language & Compiler   2 pending
Database              4 pending
Payments & Blockchain 6 pending
Native Platform       1 pending

Next up: <slug> — <one-line description>
```

Show counts per direction, highlight active claims, name the next task to pick.
All directions are independent — multiple agents can work in parallel, one per direction.

### Starting the loop

The user starts the loop by saying any of:

| Phrase | Meaning |
|--------|---------|
| "работай" / "go" / "start" | Start from the top of WORK_QUEUE.md |
| "продолжай" / "continue" | Resume — skip already-done tasks, pick next pending |
| "работай над X" / "do X" | Start with a specific task slug, then continue the queue |
| "работай над Database" / "do Payments" | Work only tasks in that direction, then stop |

When the loop starts, **announce the first claimed task** before doing any work:

```
▶ <slug> [group] — <one-line description>
```

Then work silently. On each task completion report:

```
✓ <slug> — <one-line summary>
▶ <next-slug> [group] — <description>   ← if continuing; omit if stopping
```

### Stopping the loop

**To stop after the current task finishes (graceful):**
Send any message containing "стоп", "stop", "pause", "хватит", "достаточно".
The agent finishes the task it is currently working on (commit + push), then
stops and waits.

**To stop immediately (abort):**
Send "стоп сейчас" / "stop now" / "abort". The agent stops at the next safe
checkpoint (after the current compile/test run). If the work-in-progress is
green, it is committed and pushed before stopping. If red, the worktree is
left open with uncommitted changes reported to the user.

**File-based pause (for unattended sessions):**
Create the file `.work/paused` in the repo and push it to `origin/main`:

```bash
# To pause:
touch .work/paused
git add .work/paused && git commit -m "pause: autonomous queue"
git push origin main

# To resume:
git rm .work/paused && git commit -m "resume: autonomous queue"
git push origin main
```

The agent checks `.work/paused` at the **start of each iteration** (step 1
below). If the file is present on `origin/main`, the agent stops the loop,
reports the last completed task, and waits for the user to resume. This is
the preferred mechanism when you want the loop to finish the current task
then pause — it survives context rotations and works across sessions.

**Why file-based pause is reliable:**
- Sending a chat message only stops the current session's agent
- `.work/paused` on `origin/main` is visible to every agent reading the repo,
  including agents in parallel sessions or after context compaction

### The loop

> **⚠️ CRITICAL — worktree vs main checkout (most common double-claim bug)**
>
> Steps 1–4 and 10–12 MUST operate on the **main checkout**
> (`/Users/sergiy/work/my/scalascript`), never from a worktree path.
>
> Inside a worktree, `git commit` writes to the **feature branch**, not to
> `main`. Then `git push origin main` pushes the unchanged local `main` —
> succeeds silently — and the claim file **never reaches `origin/main`**.
> Other agents fetch, see no claim, and pick the same task.
>
> For reading state (steps 1–3) always use `git ls-tree origin/main` and
> `git show origin/main:` — these return the authoritative remote view and
> are safe from any context (worktree or main checkout).

```
LOOP:
    1.  # ── From the MAIN CHECKOUT, not from any worktree ──
        git fetch origin
        # Re-read AGENTS.md — pick up protocol changes without restarting:
        Read git show origin/main:AGENTS.md and apply any updated rules
        # Check paused and queue via remote — safe from any context:
        git ls-tree origin/main .work/ | grep -q paused → STOP (announce, await user)
        if user sent a stop signal in the last message → STOP

    2.  # ── Read authoritative state from origin/main ──
        git show origin/main:WORK_QUEUE.md          # pending list
        git ls-tree origin/main .work/active/       # currently claimed slugs
        # Do NOT use `cat WORK_QUEUE.md` or `ls .work/active/` — those read
        # from the local branch, which may be stale or a worktree branch.
        if no unclaimed Pending tasks → propose from BACKLOG (see §"Empty queue protocol")

    3.  Pick the highest-priority unclaimed Pending task
        if task is genuinely ambiguous (design decision, unclear scope) →
            ask the user ONE clear question, wait, then proceed

    4.  # ── Claim — MUST happen from the MAIN CHECKOUT ──
        git reset --hard origin/main   # sync main checkout before writing
        echo "<worktree-name> <timestamp>" > .work/active/<slug>.claim
        git add .work/active/<slug>.claim && git commit -m "claim: <slug>"
        git push origin main
        if push rejected → go to step 1 (lost the race to another agent)

    5.  EnterWorktree("<worktree-name>") off the now-updated origin/main

    6.  Implement, run tests, fix until green
        (if tests are red and unfixable → leave worktree open, report, STOP)

    7.  Update docs: README.md + docs/user-guide.md + docs/<feature>.md (Rule 3a)

    8.  In the final commit:
          git rm .work/active/<slug>.claim
          mark task [x] in WORK_QUEUE.md
          update BACKLOG.md / ACTIVE.md / CHANGELOG.md as appropriate

    9.  Rebase on origin/main if it moved; push to origin/main
   10.  ExitWorktree(remove)
   11.  Report: "✓ <task-slug>: <one-line summary>"
   12.  Go to LOOP
```

**Progress cadence**: one short message per shipped item, no wall-of-text
summaries. "✓ fix(SupervisorTest): OneForOne restart specs now pass" is
enough. Detailed context goes in the commit message and BACKLOG.md.

### Recording tech debt and improvements

When you notice tech debt, a missed optimisation, or a future improvement
**while working on any task**, record it immediately — don't rely on memory.

**What to record**: anything non-trivial that is out of scope for the current
task but worth doing later. Examples:
- A function that's getting too large and should be split
- A repeated pattern that deserves an abstraction
- A performance issue you noticed but aren't fixing right now
- A missing test family you spotted while adding one test
- An API that would be cleaner with a different shape

**How to record it**:

1. Add an entry to `BACKLOG.md` in the appropriate section (or create a new
   `## Known issues / latent flakes` / `## Tech debt` subsection if none fits):

   ```markdown
   ### [short title]

   Found while working on <slug>. <One paragraph: what the issue is, why it
   matters, rough effort.> No blocker — record and move on.
   ```

2. Optionally add a one-liner to `WORK_QUEUE.md` if it's small and actionable:

   ```markdown
   - [ ] **tech-debt-slug** — Short description
     _Context: found during <slug>. Spec: BACKLOG.md §[title]._
   ```

3. Include this in the **same commit** as the work that surfaced it (or as a
   follow-up commit in the same push). Never leave it in a comment or TODO in code.

**Do NOT**:
- Stop the current task to fix it
- Add it to `WORK_QUEUE.md` unless it's small and clearly actionable
- Ask the user for permission — just record it

The user curates `BACKLOG.md` and decides priority. Your job is to surface the
finding; their job is to decide when (or whether) it gets done.

### Empty queue protocol

When `WORK_QUEUE.md` has no unclaimed pending tasks, **do not stop silently**.
Instead:

1. Read `BACKLOG.md` — identify the top 3 most actionable items not yet in the queue.
2. Present them to the user with a one-line rationale and rough effort for each:

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

3. Wait for the user's decision. **Do not add anything to `WORK_QUEUE.md`
   without explicit instruction.** Priorities are the user's call.

4. Once the user says which tasks to add (e.g. "добавь v1.26 и ergonomics"),
   add them to the correct group in `WORK_QUEUE.md`, commit, push, and continue
   the loop.

**Why agents don't set priorities unilaterally**: "do payments or SQL first?"
is a product decision involving roadmap, user demand, and dependency chains the
agent may not fully know. Surface options; let the user choose.

### 7. After a complete task — name the next work

Once a task or feature is committed, pushed to `origin/main`, local `main`
is synced, milestone files are updated, and the worktree is deleted, the
status message to the user must also include:

- the next planned tasks/features visible in `BACKLOG.md` and `WORK_QUEUE.md`
- the one task/feature you recommend doing next, with a short reason

Do this immediately after reporting what landed.  The user should not have
to ask "what next?" after every completed slice.

### 8. After a complete task — suggest `/compact` to the user

Once **all** of the following are true:
- Code committed and pushed to `origin/main`
- Local `main` synced (`git branch -f main origin/main`)
- Milestone files updated (`BACKLOG.md`/`ACTIVE.md`/`CHANGELOG.md` as appropriate)
- Worktree deleted (Rule 4)

…and you are reporting "done" to the user — add this one line at the end
of your status message if the session has been long (multi-phase feature,
autonomous loop, or any task with significant context accumulated):

> To free context for the next task, run `/compact` in the prompt.

**When to suggest:**
- After completing a multi-phase milestone or a long autonomous loop
- After any session you estimate is more than ~50% of context capacity
- Any time you feel the next task would benefit from a clean context

**When NOT to suggest:**
- Mid-task — in-flight details not yet in git may be lost in the summary
- Before pushing — if the summary forgets what was built, the next session
  may redo it (this is exactly the stale-main problem from AGENTS.md §2)
- After a trivial single-file change — not worth the overhead

**Why the agent cannot do it itself:** `/compact` is a user CLI command.
The agent has no way to invoke it. Without a timely suggestion from you,
context accumulates silently until the system is forced to auto-compact —
which often loses more detail than a deliberate compaction would.
