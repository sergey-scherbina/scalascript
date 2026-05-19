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

- [ ] Final project name (`ScalaScript` vs. trademark-safer alternative — "Scala" is an EPFL/Scala Center trademark)
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

1. **Reuse, don't invent.** If a problem has an established working solution (Markdown, YAML, EBNF, standard type theory), use it. Invention is reserved for the actual novelty: the unification itself.
2. **One source, many targets.** Source semantics are target-independent. Backends translate; they do not reinterpret.
3. **Human and machine readable.** Source must be pleasant for humans and trivially parseable for machines. Markdown gives both.
4. **No AI at runtime or compile time.** The language stands on its own.
5. **Each problem keeps its own dialect.** ScalaScript's value is not replacing every language but providing a common spec/translation layer between them.

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
/ `MILESTONES.md` / a one-line bug fix go through a worktree on a
feature branch.

The shared `main` checkout is only ever for two things:

1. Reading state (`git log`, `git status`, file viewing).
2. Fast-forward merge of *your* `feature/<name>` branch into `main`
   followed immediately by `git push origin main` (Rule 3).  No
   stops, no edits in between.

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
with your candidate item — pick a different item from `MILESTONES.md`.
Don't coordinate through chat; the worktree state is the contract.

If your item already landed on `origin/main` (search recent commits),
mark it done in `MILESTONES.md` and move on.

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

If your worktree was switched to `main` between turns (this happens —
other agents do `git checkout main` in the shared repo), **don't start
editing on main**.  Switch back to your feature branch first per Rule 1:

```bash
git checkout feature/<name>           # back to your branch
git fetch origin && git rebase origin/main
```

### 3. Every finished piece → straight to `origin/main`

The moment a piece is independently shippable (compile clean + tests pass
+ `MILESTONES.md` updated to mark the item landed):

```bash
git fetch origin
git rebase origin/main           # if origin/main moved
# re-run the suite if the rebase touched anything
<merge or fast-forward into main>
git push origin main
```

No "accumulate and push at the end of the sprint". Each piece gets its
own CI run; the user sees progress item-by-item and can redirect after
any of them.

### 4. After merge — delete worktree + branch

```bash
ExitWorktree(action: "remove")          # or: git worktree remove <path>
git branch -D feature/<name>
git push origin --delete feature/<name>
```

No dangling feature branches. The worktree branch dies with the merge.

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

### MILESTONES.md is the durable plan

`MILESTONES.md` is the persistent backlog. Chat history doesn't survive
context rotations — `MILESTONES.md` does. Use it to:

- **Pick** the next item (consult § "Parallel-safe work plan" for tracks
  that don't conflict).
- **Mark items landed** in the same commit that closes them — never push
  a finished feature whose milestone entry is still open.
- **Capture follow-ups** discovered while working: append to the right
  sprint or to "Known issues / latent flakes" before moving on.

### In one sentence

**Work in your own worktree, push the moment it's shippable, clean up
after yourself.**
