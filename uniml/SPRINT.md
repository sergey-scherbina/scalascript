# UniML — sprint

This module's queue. **Two states, and there is no third:**

    - [~] SLUG — one line      in progress
    - [x] SLUG — one line      done

Anything not being worked on belongs in `uniml/BACKLOG.md`, not here — a queue with a
"planned" state just becomes a second backlog. A task being worked on also has a row on
the root `SPRINT.md` board and a live `.work/active/<slug>.claim`; all three are written
in one commit. Layout: `specs/work-tracking-layout.md`.

- [~] UNIML-SSC3-CI — **the dialect's tests must be run by CI, and today nothing runs them.**
      MINE, and prioritised at Sergiy's request 2026-08-04. Two gaps, only one of which I made:
      (a) **`unimlScala` is not registered in the ROOT `build.sbt`**, so after UPR-4a moved the
          dialect out of `uniml/core`'s test scope, root `uniml/test` went from ~75 tests to 15.
          I did not hide this; it is the price of making the dialect production code, and the
          fix is one project definition plus an aggregate entry. BLOCKED: root `build.sbt` is
          held by the live `release-v0-1-0` claim (started 10:48Z, `claimed-before-planning`).
          Not releasing another agent's claim on a 70-minute heartbeat; asked in the room.
      (b) **the STANDALONE build has never been in CI at all** — `grep uniml .github/workflows`
          returns nothing, and the root `sbt — compile and test` job is `workflow_dispatch` only
          (3h16m). So the 81 dialect tests that DO exist are run by no automated gate on any
          push, and were not before my change either. This is UPR-9's open row, and it is the
          bigger of the two: (a) restores a dispatch-only job, (b) is the one that would catch a
          regression on the day it lands.
      **Plan:** take (a) the moment `release-v0-1-0` frees `build.sbt`; propose (b) as a smoke-ci
      check (`cd uniml && sbt test` is ~2 min warm) rather than waiting for the 3h job. Neither
      is done until a RUN shows the dialect's tests executing.

- [~] UNIML-SSC3 — **UniML must be ready to serve as ScalaScript's parser AND AST**, for
      language version 3. Direction: `specs/uniml-ssc3-frontend.md`; the seam with v3's SSC IR
      is §3.1 there. Decomposition in `uniml/BACKLOG.md` under UNIML-SSC3.
      **Progress 2026-08-01/02, by criterion:**
      (4) **losslessness — DONE AND GATED.** Every one of 1,146 `.ssc` files reconstructs
          exactly; `SpikeLosslessSpec` checks reconstruction, chunk-invariance and
          no-duplicate-tokens, verified in both directions with a planted defect.
      (2) **typed projection — FIRST CUT.** `SpikeAst`/`SpikeTyped`, 96.3% of what the dialect
          parses, gated with floors. Nothing consumes it yet — no lowering to SSC IR.
      (3) **breadth — 1,278 → 483 diagnostics, 92.1% of files completely clean.** Five slices,
          each measured: `case object`, `extern`+qualified def names (which only work TOGETHER),
          escaped identifiers, varargs, type ascription.
      (5) **measurement — partly.** Throughput ~0.9-1.0 MB/s on a loaded host; the ratio against
          `F` is still owed, and retained tree size is unmeasured.
      (1) publishable cross-built dialect and (6) v1/v2-independence: untouched.
      **Next, from probes rather than guesses:** type aliases (`opaque`/`infix type`),
      function and tuple types in a parameter, and an interpolator prefix the lexer does not
      know (`html"""…"""`). ⚠️ The diagnostic-position probe used to pick these maps spans
      through the wrong coordinate space — fix it before trusting its line numbers.

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
