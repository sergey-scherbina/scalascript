# JS backend — backlog

Can-wait and not-yet-started work whose code lives in `v1/runtime/backend/js/`. When an item is
picked up it moves to `v1/runtime/backend/js/SPRINT.md` as `[~]` and gets a row on the root board —
in the same commit as the claim. Layout: `specs/work-tracking-layout.md`.

Sections below were carried over whole from the flat root `SPRINT.md`/`BACKLOG.md`,
verbatim, on 2026-07-30.

## js-examples-sweep-44-failures-unreduced — the survey ran, the 44 were never reduced
<!-- status: open
     lane: js
     area: front
     kind: apparatus
     gate: none -->

**DEFERRED DELIBERATELY, 2026-08-04, by agreement — recorded so the cost is visible before anyone
starts.** `js-examples-differential-sweep-0713` in `v1/runtime/backend/js/BUGS.md` is the only entry
on any board still carrying `status: unknown` for a js reason, and this is why: the sweep itself
finished and its clean systemic bugs were fixed, each with its own commit and conformance case. What
never happened is the reduction of the remainder.

**The numbers, from that entry** — INT-vs-JS over the 205-case top-level examples corpus
(`ssc-tools run --v1` golden vs `run-js`):

| outcome | count |
|---|---|
| PASS | 55 |
| DIVERGE | 11 |
| JS-FAIL | 44 |
| SKIP-INT | 95 (INT itself non-deterministic — servers/actors/async/arg-requiring) |

**Why it is expensive rather than hard.** 55 cases need reducing by hand and each is its own bug
until proven otherwise; the entry's own fixed list shows the shape — a BigInt/Number mix, a missing
CPS seed, a duplicate `const`, an operator mangling — no two shared a cause. Budget it as a survey,
not as a bug.

**Two things to settle BEFORE starting, both cheap:**

1. **Re-run the sweep first.** It is from 2026-07-13 and a great deal of js work has landed since,
   including `js-worker-source-joined-with-literal-backslash-n`, which made *every* outbound HTTP
   request on the lane return `status 0 / "timeout"` for six weeks — any example that touches the
   network was measured against a broken client. A census of failure messages has a shelf life
   measured in commits.
2. **Decide what `unknown` should become.** An entry that says `unknown` tells a reader nothing and
   is counted as nothing; whatever the re-run says, the status should end up `open` with a number or
   `fixed` with evidence.

## Custom-backend (StaticJsEmitter) correctness (2026-07-13)

- [ ] **custom-jsemitter-signal-list-literal** — `frontend/custom/StaticJsEmitter.scala`'s
      `jsLiteral` (`registerSignal`, called from `compileEventHandler`) has no
      `List`/`Seq` (or `InstanceV`) case — only bare scalars. Any program
      where a `Signal[List[_]]` (of scalars or case-class instances) is
      referenced by an event handler crashes `ssc run` (both the default
      v2-VM/`custom`-frontend path and `--v1`) at startup, before serving
      anything. Not new — already affects the previously-shipped
      `examples/frontend/keyed-for-demo/keyed-for-demo.ssc` the same way
      (its own docstring's `ssc run` instructions are currently stale).
      `emit-js`/`emit-spa` (the production static-compile pipeline) are
      unaffected. Found 2026-07-13 building `select-from-signal`
      (`specs/std-ui-select.md` § "Reactive options (selectFrom)"); not
      fixed there — see `BUGS.md` § `custom-jsemitter-signal-list-literal`
      for the full repro. A real fix means teaching `jsLiteral` to
      recursively encode `List`/`InstanceV`/`Map` values.
