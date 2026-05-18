# ScalaScript (`.ssc`) — Project Bootstrap Brief

> This file is the durable memory of pre-code design decisions.
> Every new Claude Code session should read it first.

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

## Open questions (resolve before first commit)

- [ ] Final project name (`ScalaScript` vs. trademark-safer alternative — “Scala” is an EPFL/Scala Center trademark)
- [ ] License (Apache 2.0 recommended for language projects: patent grant, OSS-standard)
- [ ] Repo visibility (public / private at start)
- [ ] Primary spec language (English-only vs. trilingual EN/UK/RU like the actor-model paper)

## Immediate next steps for Claude Code

1. Confirm the open questions with Sergiy.
2. `git init`; create remote via `gh repo create <name> --<public|private> --source=. --remote=origin --push`.
3. Scaffold (spec only, no implementation yet):
   - `README.md` — overview, motivation, links
   - `SPEC.md` — canonical language spec (lexical, syntactic, type system, semantics, module system)
   - `docs/markdown-as-syntax.md` — how each Markdown construct maps to AST nodes, with worked examples
   - `docs/targets.md` — backend translation model and target capability matrix
   - `docs/architecture.md` — pipeline: source → tokens → AST → typed IR → backend
   - `grammar/scalascript.ebnf` — formal grammar
   - `schemas/frontmatter.yaml` — JSON Schema for the module-manifest YAML
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

1. **Reuse, don’t invent.** If a problem has an established working solution (Markdown, YAML, EBNF, standard type theory), use it. Invention is reserved for the actual novelty: the unification itself.
2. **One source, many targets.** Source semantics are target-independent. Backends translate; they do not reinterpret.
3. **Human and machine readable.** Source must be pleasant for humans and trivially parseable for machines. Markdown gives both.
4. **No AI at runtime or compile time.** The language stands on its own.
5. **Each problem keeps its own dialect.** ScalaScript’s value is not replacing every language but providing a common spec/translation layer between them.

## Non-negotiable rules

These override any other guidance in this file:

1. **Mark the feature as landed in `MILESTONES.md` before pushing.**
   As soon as a feature is done (compiles, all tests pass), update the
   corresponding section in `MILESTONES.md` — add `✓ Landed` to the header
   and a brief landing note listing what was done.  Do this in the same
   commit as the feature, or as the immediately preceding commit.
   Never push to `main` with a finished feature that isn't marked in
   `MILESTONES.md`.

2. **Push to `origin/main` immediately after every completed, working feature.**
   As soon as a feature compiles, all tests pass, and MILESTONES.md is updated —
   push to `origin/main` right away.  No accumulation, no asking first,
   no exceptions.  If `origin/main` has moved, rebase first, then push.

3. **Every feature starts in a worktree branch and ends with that branch deleted.**
   At the start of work on a feature, create a fresh worktree on a
   dedicated branch off `origin/main` (`EnterWorktree(name)` →
   `.claude/worktrees/<name>/`).  All edits, commits, and validation
   happen there.  After the feature lands on `origin/main` (merged or
   fast-forwarded and pushed), delete the worktree branch:
   `ExitWorktree(action: "remove")` to drop the worktree, then
   `git branch -D <name>` locally and `git push origin --delete <name>`
   on the remote.  Never leave stale feature branches behind after
   merge.  Exceptions: one-shot bug fixes or trivial doc edits that
   ship in a single commit may go directly on `main` without a
   worktree — the rule is for anything that takes more than one
   commit.

## Long-running task strategy (worktree-isolated work)

When a task is large enough to span many commits or risks colliding with
parallel work on `main`, use this pattern:

1. **Enter a worktree on a dedicated branch** — `EnterWorktree(name)` puts
   the session in `.claude/worktrees/<name>/` on a fresh branch off
   `origin/main`. All edits, compiles, and benchmarks happen there;
   `main` keeps moving independently and the worktree branch can be
   rebased / cherry-picked when the user is ready.
2. **Short iterations, commit often.** Each meaningful change — a fast
   path, a cache, a refactor — is its own commit on the worktree branch
   with bench / conformance / examples numbers in the message. Easy to
   revert a single step if it regresses something.
3. **Do NOT push every commit to `main` during the run.** Keep the work
   on the worktree branch only. Push to origin only when the whole
   feature is finished and validated end-to-end, to avoid littering
   `main` history with intermediate steps and to avoid CI churn.
4. **Watch `main` for parallel edits.** Periodically `git fetch origin`
   and `git log origin/main` (or `gh run list`) — if the user lands
   commits that touch the same files (e.g. `Interpreter.scala`),
   rebase the worktree branch onto the new `origin/main` rather than
   building on a stale base.
5. **Final integration.** When the work is done: rebase the worktree
   branch onto current `origin/main`, run the full check suite (sbt
   compile, conformance, examples/run-all, bench), then either fast-
   forward `main` or open a single merge commit. Push once.
6. **Cleanup.** After the merge lands and the push to `origin/main`
   succeeds, exit the worktree (`ExitWorktree(action: "remove")` or
   via the CLI) AND delete the feature branch both locally
   (`git branch -D <name>`) and on the remote
   (`git push origin --delete <name>`).  Per non-negotiable rule #3,
   feature branches don't outlive their merge.

The opposite (small fix, no risk of collision, one or two commits) is
fine to do directly on `main` as before; the worktree pattern is for
multi-step refactors where intermediate commits aren't worth shipping
individually.

## MILESTONES-driven workflow

The two patterns above (worktree-isolated long-running work,
iteration-by-iteration landing) sit inside one larger loop:
**`MILESTONES.md` is the durable plan; main is the durable record;
worktrees are the working space between them.**

### The shape of the loop

1. **Capture work in `MILESTONES.md` as it surfaces.**  Whenever
   something is identified that should be done but isn't being
   done right now — a bug noticed in passing, a follow-up
   suggested by a review, a feature the user mentioned and
   deferred, a "known issue / latent flake" turned up by another
   change — record it in `MILESTONES.md` immediately, with enough
   context for a future session to act on it without re-reading
   the conversation.  Group related items under a versioned
   header (`## v0.X — title`) or under the `## Known issues /
   latent flakes` section; preserve the file's forward-reading
   convention (no checkboxes, prose only).

2. **Plan the next version as numbered sprints / iterations.**
   Inside a `## v0.X` section, break the work into session-sized
   sprints, and inside each sprint into numbered items that are
   meaningfully complete on their own.  This is what lets the
   iteration-discipline rule below fire.

3. **Pick a sprint, open a worktree, execute item-by-item.**  For
   anything bigger than a one-or-two-commit fix, use the worktree
   pattern (above) — fresh branch off `origin/main`, all
   intermediate commits live there, `main` keeps moving
   independently.  Inside the worktree branch, follow the
   iteration-discipline rule for each numbered item: implement,
   run the check suite, strike the item from `MILESTONES.md`,
   rebase onto `origin/main`, push to `main`.

4. **At sprint boundaries, exit the worktree.**  When every
   item in a sprint is done and landed, the worktree branch is
   empty (or only has merge-noise) — exit it
   (`ExitWorktree(action: "remove")`), record the sprint as
   complete in the relevant `## v0.X` section if anything
   project-level was learned, and decide on the next sprint.

5. **Push immediately, not at the end.**  Never accumulate
   multiple finished items locally hoping to push them all at
   once at the end of the sprint.  Each finished item should hit
   `origin/main` before the next one starts, so CI runs against
   it and the user can see progress one item at a time.
   **After every merge to `main`, always run `git push origin main`
   immediately — no exceptions, no asking first.**

### Why this shape

- `MILESTONES.md` survives every conversation rotation; the
  chat transcript doesn't.  Anything not written there is lost
  the moment the session ends.
- Numbered items in a sprint are an explicit contract: each
  is small enough to land on its own, big enough to be worth a
  commit.  Resists both end-of-sprint mega-commits and 50-step
  bikeshed sequences.
- Item-by-item push means the user reviews one self-contained
  change at a time and can redirect the plan after any item
  ("scratch sprint 3, do this other thing instead").
- Striking items from `MILESTONES.md` in the same commit that
  closes them means the document stays in sync with `main`
  automatically — no separate "tidy MILESTONES" step.

### When the loop doesn't apply

- **One-shot bugfix or trivial change**:  skip the `MILESTONES.md`
  entry, skip the worktree, commit directly to `main` with a
  descriptive message.  The loop is for plans, not chores.
- **User explicitly directs otherwise**:  "squash the whole
  sprint into one commit", "don't push until I review", "work
  on a feature branch and open a PR" — follow the instruction.
  The loop is the default, not a law.

### Before picking the next task — sync and check

Multiple agents may be working in parallel, each in its own
sibling worktree under `.claude/worktrees/<name>/` and on its
own branch.  And `origin/main` keeps moving independently as
other agents push their finished items.  **Before picking up
a new item from `MILESTONES.md`, both** (a) sync with
`origin/main` and review what's already landed, **and** (b)
check what other agents are currently working on (including
uncommitted work).  Skipping either one costs hours of
duplicate work; running both takes seconds.

#### Step 1 — Sync and review `origin/main` for already-landed work

`MILESTONES.md` is the durable plan, but **the source of
truth for what's actually done is `origin/main`**.  The
backlog file can be stale — items may have landed in
recent commits before the file was updated, or a milestone
may have been re-scoped by a sibling agent.  Always pull
fresh and review before claiming anything.

```bash
# 1. Sync — non-destructive; updates remote-tracking branches
git fetch origin

# 2. Bring local main up to date
git checkout main && git pull --ff-only origin main

# 3. Review what landed since the last session.
#    20 lines is usually enough; expand if it's been a while.
git log origin/main --oneline -20

# 4. Read the latest MILESTONES.md, AGENTS.md, and any new
#    docs/*.md.  Recent design docs may have re-scoped the
#    item you were about to take.
git diff HEAD@{1.day.ago}..HEAD -- MILESTONES.md docs/

# 5. Search for the candidate item's key terms across recent
#    commits — catches "landed but MILESTONES.md not yet
#    updated" cases.
git log --oneline --all --grep="<item-keyword>" -20
```

What to look for:

1. **The item itself was already landed** — `git log` shows
   a commit like `feat(v1.5/B.3): httpGetStream …` and your
   item was "v1.5 Tier 2 #7 streaming response bodies".
   Skip it; mark `MILESTONES.md` as landed if it isn't
   already; pick the next item.
2. **A prerequisite was added that changes your approach** —
   e.g., your item is "v1.20.2 indentation-aware parsing"
   and a new commit refactored `Parser` ADT.  Re-read the
   relevant phase before starting.
3. **A new design doc supersedes the item** — e.g., `docs/`
   gained a new file that explicitly deferred or re-scoped
   your candidate.  Honour the doc.
4. **A sibling agent's PR was merged** — the
   `Recommended implementation sequence` may have shifted
   (item numbers reflow); re-check what's actually next.

#### Step 2 — Check sibling worktrees for in-progress work

After confirming the item isn't already landed, check that
no other agent is currently working on it.  Sibling agents
have their own worktrees, often with uncommitted changes
that won't show up anywhere in `origin/main` or even in
remote branches yet.

Concretely, before claiming the next backlog item, run:

```bash
# 1. List sibling worktrees — each is another agent's workspace
git worktree list

# 2. For each sibling worktree, check what they're doing:
for wt in $(git worktree list --porcelain | awk '/^worktree / && $2 != "'"$(git rev-parse --show-toplevel)"'" { print $2 }'); do
    echo "=== $wt ==="
    # Branch name often encodes the task
    git -C "$wt" branch --show-current

    # Uncommitted changes — files actively being edited
    git -C "$wt" status -s

    # Recent commits on that branch (vs origin/main)
    git -C "$wt" log --oneline origin/main..HEAD 2>/dev/null | head -10

    # Latest scratch files that hint at intent (design docs, examples)
    git -C "$wt" diff --name-only origin/main..HEAD 2>/dev/null | head -20
done

# 3. Cross-check with active remote branches (pushed but not merged)
git fetch origin
git branch -r --no-merged origin/main | grep claude/ | head -20
```

Signals that an item is already being worked on, in priority
order:

1. **A sibling worktree's branch name contains a related keyword**
   (e.g., `claude/std-mcp-server-sgnXX` when you were about to
   pick up "v1.17 Phase 2 — JS server intrinsic").  Strongest
   signal — don't take it.
2. **Uncommitted changes in a sibling worktree touch files
   that overlap with your candidate item** — e.g., the file
   list from `git status` includes `std/mcp/server.ssc` and
   that's where your item would land.  Strong signal.
3. **Recent commits on a sibling branch reference the same
   milestone item** — read the commit messages with
   `git log --oneline origin/main..HEAD`.  Strong signal.
4. **Scratch / draft files in a sibling worktree** (design
   docs, examples named after the item) — moderate signal;
   the agent may be still designing, not implementing yet.
5. **A remote `claude/*` branch (unmerged) with a name that
   matches** — they pushed and stopped; could be abandoned
   or paused.  Read the latest commit; if recent, assume
   active; if days old with no progress, ask the user.

**What to do when overlap is detected**:

- **Pick a different item** from the same sprint, or a
  different sprint within the same milestone.  Most
  milestones have ≥3 independent phases; another phase is
  usually safe.
- **If everything in the current milestone is taken**, move
  to the next milestone in the recommended sequence (per
  `MILESTONES.md` § "Recommended implementation sequence").
- **If nothing is free**, report to the user with a
  one-paragraph summary of who's doing what, and ask which
  unblocked item to take next (or whether to wait).

**What NOT to do**:

- Don't merge / cherry-pick from a sibling worktree branch
  to "help" — those commits aren't yours; the other agent
  may rebase or revert them.
- Don't push to a sibling agent's branch.
- Don't `rm -rf` a sibling worktree, even if it looks abandoned
  — ask the user before reclaiming worktree space.
- Don't assume "no remote branch = item is free" — uncommitted
  local work is the most common case and won't show up
  without `git worktree list`.

Both Step 1 and Step 2 are **fast** — together usually
under five seconds — and save hours of duplicate work
when they catch an overlap or a stale-plan mismatch.

### Iteration-discipline checklist

The mechanical steps that the loop above implies for *every*
numbered item.  Keep them in this order:

0. **Before claiming the item**, run BOTH pre-checks from
   the previous section: (a) sync with `origin/main` and
   review what's already landed, and (b) check sibling
   worktrees for in-progress work.  If overlap is detected
   or the item is already done, pick a different item.
   Don't skip this step — it takes seconds and prevents
   hours of duplicate work.  **For pick-the-next-item
   strategy, consult `MILESTONES.md` § "Parallel-safe work
   plan (for multi-agent execution)"** — it groups
   milestones into tracks (Track A through F) so two agents
   on different tracks don't conflict on files / typer /
   runtime, with a cross-track conflict-surface matrix and
   an agent decision tree for picking.
1. Implement + commit on the local branch (worktree for
   multi-step work; `main` directly for small fixes).
2. Run the full check suite (`sbt compile`, `sbt test`,
   conformance if touched, examples if touched).  Add or update
   any test that covers the change.
3. Update `MILESTONES.md` — strike the closed item so the
   document keeps reading forward.  If new follow-ups surfaced
   while doing the item, append them under the appropriate
   sprint / known-issues section before moving on.
4. `git fetch origin main`; if `origin/main` has moved, rebase
   the local branch onto it and re-run the check suite.
5. Push to `origin/main` (fast-forward or merge commit,
   depending on the work).

## Big-feature workflow (long-lived branch)

Backend SPI, a new compiler tier, a cross-backend rewrite — work
that spans many sessions, touches multiple subsystems, and is
**not safe to land item-by-item on `main`** because intermediate
states would break consumers, leave the docs lying, or churn CI
for weeks.  This is the explicit override of the
"push-immediately" rule from the MILESTONES-driven loop.

### Rules

1. **One worktree, one long-lived branch.**  `EnterWorktree(name)`
   on a descriptive branch (`feature/backend-spi`,
   `feature/wasm-target`, …) off `origin/main`.  The branch lives
   for the entire feature — weeks if needed.  All work stays
   here.

2. **Write the plan first, commit it, push it.**  Before any
   implementation code, draft a plan document inside the worktree
   (e.g. `docs/<feature>-plan.md`): the stages, the iterations
   inside each stage, acceptance criteria per iteration, open
   questions surfaced while reading the spec.  Commit and push the
   plan as the **first commit on the branch** so the user can
   review and redirect before any code is written.

3. **Stages → iterations.**  Each stage is a coherent slice (e.g.
   "Phase 1 — SPI skeleton", "Phase 2 — IR codec").  Each
   iteration inside a stage is a session-sized unit that ends in
   a green check suite.  Commit each iteration with a meaningful
   message.  **If the iteration is self-contained and the full
   check suite passes without breaking any existing
   functionality, push it to `main` immediately and continue from
   there** (same as the MILESTONES loop).  Push to the feature
   branch only when the iteration is an intermediate,
   non-shippable state (e.g. half-refactored internals, a stub
   that makes callers fail).

4. **Track progress in the plan, not the chat.**  After each
   iteration, edit the plan document in the same commit: mark
   the iteration done (or strike it), add any newly discovered
   work as additional iterations under the right stage.  The
   plan is the durable record of what is left.

5. **New findings → MILESTONES.md *in the branch*.**  If the
   work surfaces something that belongs in a different
   milestone (a follow-up that ships separately, a latent flake,
   a deferred polish), add it to `MILESTONES.md` on the feature
   branch.  It travels back to `main` with the final merge.

6. **Land to `main` as soon as each iteration is ready.**  Don't
   wait for the whole feature to be done.  After each iteration:
     a. Run the full check suite (`sbt compile`, `sbt test`,
        `scala-cli conformance/run.sc`, relevant e2e smokes).
     b. If everything is green and nothing is broken,
        `git fetch origin && git rebase origin/main`, then
        fast-forward / merge into `main` and push immediately.
     c. Update `MILESTONES.md` in the same commit.
     d. Continue the next iteration (still in the worktree or
        directly on `main` for the next small piece).

   When **every** stage is complete and the feature is fully
   done, ensure all documentation is updated (README, SPEC,
   MILESTONES, any docs/*.md the feature touches), then delete
   the plan document or merge its lessons into MILESTONES, and
   `ExitWorktree(action: "remove")`.

7. **Keep up with `main`.**  Periodically `git fetch origin` and
   rebase the feature branch onto current `origin/main` so
   integration at the end is small.  Resolve conflicts as they
   appear, not all at once at the end.

### How this relates to the MILESTONES loop

Both workflows share the same core rule: **push to `main` as
soon as a unit of work is independently shippable.**  The only
difference is granularity.

- MILESTONES-loop items are always shippable by design (each is
  scoped to be so).
- Big-feature iterations are *sometimes* shippable (a new
  intrinsic table entry, a finished codec phase, a standalone
  refactor that leaves callers intact) and *sometimes* not (a
  half-migrated internal that would break existing backends).

For the shippable ones: treat them exactly like a MILESTONES
item — run the suite, rebase, push, continue.  For the
non-shippable ones: commit on the feature branch and keep going
until the blocking intermediate state resolves into something
green.  The feature branch exists as a safety net for those
moments, not as a gate for everything.
