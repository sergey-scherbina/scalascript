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

## Codebase architecture rules (binding)

### New intrinsics always go to `std/` plugins, never to core

When implementing a new `extern def` (intrinsic), **always** create or extend a
plugin in `std/<feature>-plugin/`, not the interpreter core.

**Wrong** — adding `NativeImpl` to any of:
- `backend/interpreter/src/.../intrinsics/*.scala`  (e.g. `Jdbc.scala`, `UiPrimitives.scala`)
- `core/` directly

**Right** — creating a new plugin:
1. `std/<feature>-plugin/src/main/scala/scalascript/compiler/plugin/<feature>/<Feature>Plugin.scala`
2. `std/<feature>-plugin/src/main/scala/scalascript/compiler/plugin/<feature>/<Feature>Intrinsics.scala`
3. `std/<feature>-plugin/src/main/resources/META-INF/services/scalascript.backend.spi.Backend`
4. Register in `build.sbt`: new `lazy val`, add `% Test` to `backendInterpreter`, add to root aggregate and CLI plugin list.

The plugin may import `scalascript.interpreter.{Value, InterpretError}` and
`scalascript.frontend.*` — those live in `core` / `frontendCore` which all
plugins already depend on.

Bridge hooks that the interpreter exposes *to* plugins (e.g. `NativeContext.dbConnect`,
`NativeContext.registerRoute`) are the only intrinsic-related code that belongs in
`backend/spi` or the interpreter — they are the SPI contract, not the intrinsics themselves.

**Examples of correct plugin layout:** `std/json-plugin`, `std/auth-plugin`,
`std/oauth-plugin`, `std/sql-plugin`, `std/ui-fetch-plugin`.

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
- Any new top-level milestone in `MILESTONES.md`

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
/ `MILESTONES.md` / a one-line bug fix go through a worktree on a
feature branch.

The shared `main` checkout is only ever for two things:

1. Reading state (`git log`, `git status`, file viewing).
2. Fast-forward merge of *your* `feature/<name>` branch into `main`
   followed immediately by `git push origin main` (Rule 3).  No
   stops, no edits in between.

#### The absolute-path trap (and how not to lose work to it)

The single most common way agents accidentally edit shared `main` is
the **absolute-path trap**: you're running in a worktree at
`/Users/sergiy/work/my/scalascript/.claude/worktrees/agent-XXX/`, but
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
