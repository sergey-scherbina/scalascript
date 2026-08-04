# UniML — sprint

This module's queue. **Two states, and there is no third:**

    - [~] SLUG — one line      in progress
    - [x] SLUG — one line      done

Anything not being worked on belongs in `uniml/BACKLOG.md`, not here — a queue with a
"planned" state just becomes a second backlog. A task being worked on also has a row on
the root `SPRINT.md` board and a live `.work/active/<slug>.claim`; all three are written
in one commit. Layout: `specs/work-tracking-layout.md`.

- [x] UNIML-SSC3-CI — the dialect's tests must be run by CI. BOTH halves implemented; the
      CLOSED by a run that shows them executing, which is what this item required from the
      start rather than an assertion that the gate exists.
      (a) **DONE.** `unimlScalaCross` registered in the ROOT build (JVM + Scala.js, aggregated
          with its siblings). Root `uniml/test` is back from 15 to 81, JS 3. The partition gate
          agreed rather than being told: modules 260 → 261, standard tier UNCHANGED at 35 —
          the dialect is an additional library and must not enter the default distribution just
          because it became publishable. `specs/project-partitioning.md` carries the same
          arithmetic, Part III 143 → 144 in all four places it is stated.
      (b) **DONE — Smoke 30912304380 SUCCESS on 7e2812f7f, `ok uniml-standalone 106.3s`.** `smoke-ci` now runs `cd uniml && sbt test`
          (`tests/e2e/uniml-standalone-tests.sh`). Cost measured BEFORE claiming the budget:
          27.9s from clean, 7.2s warm, against 500s of which 234s was used. Runs the STANDALONE
          build on purpose — that build is UniML's proof it stands alone, so one command checks
          the tests and that property together. The script counts PASSING PROJECTS, because an
          aggregate that quietly stopped including projects would still exit 0 while testing
          less. Verified both ways: planted type error → exit 1, restored tree → exit 0.
          ⚠️ The first verification was WRONG — `script | tail` then `echo $?` reports TAIL's
          exit code, so a broken build read as a pass. Measure the script, not the pipeline.

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
