# UniML — sprint

This module's queue. **Two states, and there is no third:**

    - [~] SLUG — one line      in progress
    - [x] SLUG — one line      done

Anything not being worked on belongs in `uniml/BACKLOG.md`, not here — a queue with a
"planned" state just becomes a second backlog. A task being worked on also has a row on
the root `SPRINT.md` board and a live `.work/active/<slug>.claim`; all three are written
in one commit. Layout: `specs/work-tracking-layout.md`.

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

- [x] UNIML-SSC3-CI — the dialect's tests must be run by CI. **Route (ii) taken and verified.**
      `ci.yml` job `UniML — standalone build`, run 30937360765: `UniML: 10 project(s) reported
      passing`, 2m07s. The count is asserted, not just the exit code — an aggregate that quietly
      stops including projects exits 0 while testing less. That first run immediately caught my
      own floor of `>= 8` against an actual 10: a threshold below the observed count tolerates
      exactly the silent loss it claims to catch. Floored at 10 — adding a project stays green,
      losing one goes red.
      Cost, stated rather than hidden: this repo pushes straight to `main`, so `pull_request`
      rarely fires and in practice it is the NIGHTLY. Regressions surface in hours, not minutes.
      That is the honest price of a module shipping nothing into the staged toolchain.
      (a) **DONE.** `unimlScalaCross` registered in the ROOT build (JVM + Scala.js, aggregated).
          Root `uniml/test` back from 15 to 81, JS 3. The partition gate agreed rather than being
          told: modules 260 → 261, standard tier UNCHANGED at 35. `project-partitioning.md`
          carries the same arithmetic, Part III 143 → 144.
      (b) **REOPENED — my first answer was wrong and its green was luck.** I added a smoke check
          running `cd uniml && sbt test`. It passed ONCE and failed every push after:
          `smoke.yml`'s `Setup sbt` is CONDITIONAL on a toolchain-cache MISS, so on a hit there is
          no sbt at all. I cited that one run as proof the gate worked; it proved the gate works
          on a cache miss. Reverted — making it skip when sbt is absent would turn every cache
          hit into a silent pass, which is worse than no check.
          **The contract I broke was unwritten, and is now written** in `scripts/smoke-ci.ssc`:
          a smoke check CONSUMES the staged toolchain and never builds one; sbt is unavailable;
          only Node, Scala CLI and Java 21 are unconditional.
          **What that leaves.** UniML ships in no staged artefact, so under this contract it
          cannot be smoke-gated at all yet. Two honest routes, and the choice is a real one:
          (i) get UniML into the staged toolchain so a check can consume it — larger, and it
              couples a Part III library to the default distribution, which §7 invariant 1
              deliberately forbids; or
          (ii) gate it where BUILDING is allowed — `ci.yml`'s Validate job, or the nightly. Not
              on the push path, so a regression is caught in hours rather than minutes, which is
              the honest cost of a module that ships nothing.
          **Resolved as (ii)** — see the header above. Sergiy released `.github/workflows/ci.yml`.
          Route (i) stays rejected on the §7 invariant, not on effort.

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
