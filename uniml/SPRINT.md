# UniML — sprint

This module's queue. **Two states, and there is no third:**

    - [~] SLUG — one line      in progress
    - [x] SLUG — one line      done

Anything not being worked on belongs in `uniml/BACKLOG.md`, not here — a queue with a
"planned" state just becomes a second backlog. A task being worked on also has a row on
the root `SPRINT.md` board and a live `.work/active/<slug>.claim`; all three are written
in one commit. Layout: `specs/work-tracking-layout.md`.

- [~] UNIML-SSC3-ALPHABET — **one character classifier, no host `Char` calls.** v3 handed this
      over as a requirement on UniML (`v3/specs/40-front-on-uniml.md` §4: "the lexer may not use
      host `Char` classification"), and `20-core-language.md` §3 decides the alphabet: whitespace
      = space/tab/CR/LF/FF; digit = `0`-`9`; ident-start = `a-zA-Z_$` **or any code point ≥
      U+0080**; ident-part = start or digit; operator = `+-*/%<>=!&|^~:#@?`. Every line a range
      comparison, no tables on any host.
      **Why it is a compiler question, not tidiness.** `Character.isLetter` answers from Unicode
      tables. Route the alphabet through the host and the same source lexes differently on JVM,
      JS and the v2 VM — the language's syntax becomes host-dependent.
      **⚠️ Correction to my own number.** I first reported "59 host classification calls across 7
      modules" and put it in the landing commit. It is wrong and roughly double: that pattern had
      no word boundary, so it counted every local `def isDigit` — helpers that are already
      tableless — as if it were a host call, and later counted `UniAlphabet.isDigit` as one too.
      Counted properly (dot-prefixed calls and `Character.*`, excluding the new module's own
      qualified uses and comment lines): **28 across the module, 19 of them in the ScalaScript
      dialect.**
      **State now: 0 on the ScalaScript front's own path** — core, json, yaml, markdown and scala,
      which is what `unimlScalaCross` depends on. What remains is off that path and not blocking
      v3: `markup` 4, `address` 1, `markdown/bridge` 1.
      **Predictions, written before the change (§1.3 of the `performance` skill):**
      - losslessness stays 1,146/1,146. Reconstruction concatenates lexemes; which token a char
        lands in may move, the concatenation may not.
      - breadth may only IMPROVE or hold. The new alphabet is strictly MORE permissive than
        `isLetter` (it accepts every code point ≥ U+0080, letter or not). **Any file that parses
        today and stops parsing is a bug in the migration, not a consequence of the decision** —
        that is the disqualifying evidence.
      - the sweep gate finds divergences from Java in exactly two directions: code points ≥
        U+0080 that Java says are not letters (we accept, Java rejects — intended), and nothing
        in the other direction. **A divergence where Java accepts and we reject is a defect.**
      **CASE — DECIDED by Sergiy 2026-08-05: use the Unicode table.** The tableless answer was
      implemented first, measured, and rejected on the measurement.
      **What the measurement showed, and it is not what §3 argued.** §3 refuses host classification
      because "the same source would lex differently on the JVM, on JS and on the v2 VM". As
      compile hosts that is FALSE: JVM and Scala.js agree exactly — 1,169 uppercase code points,
      identical hash, checked character by character (`HostCaseAgreementSpec`, now frozen on both
      lanes as a canary). The real divergence is between ScalaScript's OWN runtimes: the
      interpreter delegates to `Character.isUpperCase`, the js backend tests `/\p{Lu}/u`, and
      **42 BMP characters differ** — Java counts `Other_Uppercase`, `\p{Lu}` does not. Roman
      numerals are the readable example: `case Ⅷ =>` would MATCH on the interpreter and BIND on js.
      Same source, different meaning, silently. So the conclusion §3 reached is right and its stated
      reason was not; a baked table is what makes every lane agree.
      **Cost, measured rather than asserted.** 1,143 code points in **606 ranges** — MORE fragmented
      than the 378 ranges covering all 48,913 non-ASCII letters, because case alternates character
      by character through Latin Extended. ~4.8 KB as `Vector[Int]`. Consulted only after an ASCII
      fast path, and once per identifier TOKEN rather than per character, so the "hottest loop"
      objection does not apply to this predicate. The pattern is already proven here:
      `MarkdownLexer.punctRanges` is 199 generated ranges with a host-parity test.
      **The gate got stronger, not weaker.** It used to assert a one-directional divergence; it now
      asserts EQUALITY with the host over the whole `Char` range, with `Ч`/`ч` and U+2167 named. A
      JDK whose Unicode version moves turns it red instead of letting the table go stale.
      **⚠️ This contradicts `v3/specs/20-core-language.md` §3, which bans tables outright, and v3
      carries its own copy of the alphabet in `Chars`.** §3 needs amending and ssc3-core needs to
      decide whether to adopt the shared module. Raised with them; not edited unilaterally, since
      `paths: v3` is theirs.

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
