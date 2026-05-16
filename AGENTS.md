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

## Iteration discipline: land every closed iteration on `main`

When working through a multi-iteration plan (a milestone broken into
sprints, a refactor split into steps, a list of bugfixes), **merge
each iteration into `main` as soon as it is verifiably done** rather
than batching them into a single end-of-plan push.

Concretely, for each iteration:

1. Implement + commit on the local working branch (or on `main`
   directly for small fixes).
2. Run the full check suite (`sbt compile`, `sbt test`, conformance
   if touched, examples if touched) and verify any new tests cover
   the change.
3. Update `MILESTONES.md` — strike the closed iteration so the
   document keeps reading forward.
4. `git fetch origin main`; if `origin/main` has moved, rebase the
   local branch onto it and re-run the check suite on the rebased
   tip.
5. Push to `origin/main` (fast-forward or merge commit, depending on
   the work).

Why: each iteration is a self-contained piece of value; landing it
immediately means CI runs against it, future iterations build on
the rebased tip rather than a stale local branch, and the user can
review one iteration at a time instead of a wall of commits at the
end.

The exceptions are the worktree pattern above (intermediate steps
that aren't useful in isolation) and explicit user instruction
("squash the whole sprint into one commit before pushing", etc.).
