# UniML — sprint

This module's queue. **Two states, and there is no third:**

    - [~] SLUG — one line      in progress
    - [x] SLUG — one line      done

Anything not being worked on belongs in `uniml/BACKLOG.md`, not here — a queue with a
"planned" state just becomes a second backlog. A task being worked on also has a row on
the root `SPRINT.md` board and a live `.work/active/<slug>.claim`; all three are written
in one commit. Layout: `specs/work-tracking-layout.md`.

- [x] uniml-is-ssc3-frontend — recorded the ScalaScript 3 direction: UniML becomes the front
      end, parser AND AST. New spec `specs/uniml-ssc3-frontend.md`; UPR-8a and
      `project-partitioning.md` §8.3 both said the opposite and now point at it. Records the
      measured gap (the dialect is a 2,447-line TEST-scope spike over a subset; no `src/main`
      dialect exists) and the two UNMEASURED numbers that could still invalidate the plan —
      parse throughput and retained tree size versus `F`.

- [x] uniml-md-container-indent — a blank line held inside indented code was overtaken by the
      next line's container prefix; extending matchContainers' existing paragraph buffering to
      an open code block fixed seven of the ten remaining source failures. **source 10 → 3,
      tokens 10 → 3**, corpus 601 → 607 of 675. First attempt threw on example 231 — see
      BACKLOG: count exceptions, not just failures.

- [x] uniml-md-upr3a — UPR-3a DONE. The real WHATWG HTML5 entity table (2125 semicolon-
      terminated names) generated from a pinned snapshot, replacing a hand-typed ~250; a third
      controlled root in generate.py because the decoder is production code; decoding wired
      into destinations, titles and fence info strings. Entity section 5 → 0, corpus 595 → 601
      of 675.

- [x] uniml-md-upr3b — UPR-3b/3c. Corpus 552 → 595. ONE scanner for reference definitions
      replacing three that disagreed; a closing list item closes its list (`- one\n\n two` was
      LOSING `two`); fence bodies inside containers lose the prefix; reference labels match on RAW
      source; inline raw HTML got the CommonMark 6.6 tag grammar it never had (malformed tags were
      emitted unescaped). One rule tried and REVERTED (5.2 marker separation) — it moved
      source/tokens 10 → 12; recorded in BACKLOG with its measurement.
      Corpus is at 595 of 675; UPR-3 stays OPEN in `uniml/BACKLOG.md` (3e wants 652/652 exact).

- [x] uniml-md-upr3 — a SLICE of UPR-3, not its closure: corpus 460 → 552 passing of 675 in five
      fixes. Shortcut/collapsed refs resolved by the EMPTY key (+27); indented code coalesces with
      its four columns cut to trivia (+29, source/tokens 14 → 10 each); links stop nesting (+5);
      GFM extended autolinks implemented (+11, that section was 0 of 11); CommonMark's block
      trimming rule applied (+20, reaching six sections). UPR-3e wants 652/652 exact, so the item
      stays open in `uniml/BACKLOG.md` with the ranked remainder and which sub-item owns it.

- [x] markup-into-uniml — `markup/` → `uniml/markup`, grouped with the dialects that project
      onto its AST. Costs a `uniml/markup` carve-out in the partition gate's Part III regex
      (markup-core ships in the standard tier; `uniml/` is otherwise Part III) — carved out to
      `markup` EXACTLY, with a `--self-test` plant proving a sibling `uniml/*` is still caught.
      Reverses the decision recorded in `specs/project-partitioning.md` §8.7 hours earlier; §8.7
      now records the reversal and prices it.
