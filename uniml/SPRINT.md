# UniML — sprint

This module's queue. **Two states, and there is no third:**

    - [~] SLUG — one line      in progress
    - [x] SLUG — one line      done

Anything not being worked on belongs in `uniml/BACKLOG.md`, not here — a queue with a
"planned" state just becomes a second backlog. A task being worked on also has a row on
the root `SPRINT.md` board and a live `.work/active/<slug>.claim`; all three are written
in one commit. Layout: `specs/work-tracking-layout.md`.

- [~] uniml-md-upr3 — UPR-3 CommonMark/GFM. Corpus 460 → 516 passing of 675 so far: shortcut/
      collapsed reference links resolved by the EMPTY key (+27); indented code now coalesces into
      one block with its four columns cut to trivia (+29, and source/tokens non-pass 14 → 10 each).
      Frozen baseline moved the prescribed way (dump → repin → generate.py --write → --check), JVM
      and Scala.js 41/41 both. Next by census: Links 25, List items 20, GFM autolinks 11 (absent).

- [x] markup-into-uniml — `markup/` → `uniml/markup`, grouped with the dialects that project
      onto its AST. Costs a `uniml/markup` carve-out in the partition gate's Part III regex
      (markup-core ships in the standard tier; `uniml/` is otherwise Part III) — carved out to
      `markup` EXACTLY, with a `--self-test` plant proving a sibling `uniml/*` is still caught.
      Reverses the decision recorded in `specs/project-partitioning.md` §8.7 hours earlier; §8.7
      now records the reversal and prices it.
