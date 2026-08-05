# JVM backend — sprint

This module's queue. **Two states, and there is no third:**

    - [~] SLUG — one line      in progress
    - [x] SLUG — one line      done

Anything not being worked on belongs in this module's `BACKLOG.md`, not here — a queue with a
"planned" state just becomes a second backlog. A task being worked on also has a row on the root
`SPRINT.md` board and a live `.work/active/<slug>.claim`; all three are written in one commit.
Layout: `specs/work-tracking-layout.md`.

Created 2026-08-05: this module and the interpreter were the only two of the thirteen in
`tests/fixtures/modules.tsv` with a `BUGS.md` and no queue, so jvm work had nowhere to be planned
that a fresh agent would find.

## jvm-package-import-link-name (claim `jvm-package-import-link-name`)

Entry: [`BUGS.md`](BUGS.md) `jvm-package-import-qualifies-the-link-name`.
Gate: `tests/e2e/package-keyword-smoke.sh` — its JVM row, declared known-red by slug today.

**The measurement that scopes this.** Four lanes × four link-name shapes, one module declaring
`package: org.example.ui` with an `object Card` and a top-level `def helper`:

| link text | int | native | js | jvm |
|---|---|---|---|---|
| `[org]` — the package ROOT | `ui-card-hi` | `ui-card-hi` | `ui-card-hi` | **E008** |
| `[Card]` — a member object | ✓ | ✓ | ✓ | ✓ |
| `[helper]` — a member def | ✓ | ✓ | ✓ | ✓ |
| `[example]` / `[ui]` — inner segments | `not found` | — | — | error |
| `[cards]` — arbitrary | `not found` | `ui-card-hi` | `ui-card-hi` | error |

Only ONE row is this task: three lanes agree the package ROOT is importable and jvm does not.
Member names already work, so this is not "jvm cannot import packages". The inner segments and the
arbitrary name are rejected by the interpreter too, so there is nothing to reproduce there — and the
native/js permissiveness on the last row is filed separately as
`package-root-import-needs-an-exports-entry-on-int`.

- [~] **J-1 — `aliasBlock` must not import the package ROOT from the package.** `JvmGen.scala:2767`
      emits `import ${targetPkg.mkString(".")}.{$specText}` where the specs come from the markdown
      link's bindings. For `[org](./cards.ssc)` that is `import org.example.ui.{org}`. Read the
      GENERATED Scala rather than inferring: `object org {` is at line 7063 of it, top level, and the
      bad import is at 7072 — the object is ALREADY in scope, so the binding needs no import at all.
      Fix: drop a binding whose name equals `pkg.head`.
- [~] **J-2 — delete the known-red declaration from the gate.** Forced, not optional: the gate FAILS
      a declared row that starts passing, so leaving the slug in place turns green into red.
- [~] **J-3 — re-run the shape matrix**, not just the gate's one fixture: root / member / def must
      all be green on jvm, and the inner-segment and arbitrary rows must stay errors. A fix that
      makes the arbitrary name work would be jvm disagreeing with the interpreter in the other
      direction.
