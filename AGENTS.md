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
6. **Cleanup.** After the merge lands, exit the worktree
   (`ExitWorktree(action: "remove")` or via the CLI) — keep main's
   working tree free of stale worktrees.

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

### Iteration-discipline checklist

The mechanical steps that the loop above implies for *every*
numbered item.  Keep them in this order:

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
   message and **push to the feature branch** (not to `main`).

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

6. **Merge to `main` only at the very end.**  When every stage
   is complete, every iteration green, **every test passes**,
   **all documentation is updated** (README, SPEC, MILESTONES,
   any docs/*.md the feature touches), and the plan document
   has nothing outstanding — then:
     a. `git fetch origin && git rebase origin/main`
     b. Re-run the full check suite (`sbt compile`, `sbt test`,
        `scala-cli conformance/run.sc`, relevant e2e smokes).
     c. Open one merge commit (or fast-forward if linear) into
        `main`.  Push once.
     d. Delete the plan document or move its lessons into
        MILESTONES, then `ExitWorktree(action: "remove")`.

7. **Keep up with `main`.**  Periodically `git fetch origin` and
   rebase the feature branch onto current `origin/main` so
   integration at the end is small.  Resolve conflicts as they
   appear, not all at once at the end.

### Why this differs from the MILESTONES loop

The MILESTONES loop pushes each item to `main` immediately
because each item is independently shippable.  A big-feature
branch's intermediate iterations are **not** independently
shippable — half-an-SPI on `main` would break every existing
backend.  So intermediate commits land on the branch (for
durability, review, CI on the branch, and so the user can pull
the branch to inspect WIP) but only the finished feature lands
on `main`.
