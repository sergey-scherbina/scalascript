# UniML — sprint

This module's queue. **Two states, and there is no third:**

    - [~] SLUG — one line      in progress
    - [x] SLUG — one line      done

Anything not being worked on belongs in `uniml/BACKLOG.md`, not here — a queue with a
"planned" state just becomes a second backlog. A task being worked on also has a row on
the root `SPRINT.md` board and a live `.work/active/<slug>.claim`; all three are written
in one commit. Layout: `specs/work-tracking-layout.md`.

- [~] SSC3-P/typed-ast — **audit the projection against the CST's ROLES, then close real gaps.**
      Claim `uniml-typed-ast`. Picks up the 2026-08-05 hand-over in `uniml/BACKLOG.md`, whose
      instruction was: re-measure, audit role by role, only then model new constructs.
      **Re-measured first, inheriting nothing: 1185 files, 171,119 nodes, 2,943 gaps, 98.3%** —
      the hand-over's numbers reproduce.
      **The `if` bug is a class and reading the dialect found four more siblings before a line of
      code was written.** All four are silent: they produce a well-formed AST node whose contents
      are wrong or empty, and `Unsupported` never fires.
      1. `enum.case` is a role the dialect NEVER EMITS — cases are `spike.enumcase` child
         BRANCHES (`ScalaSpike.scala:883`), which is how `SpikeProject` reads them (:2334).
         So every `EnumDecl` in the corpus carries `cases = Vector.empty`.
      2. `spike.paren`/`spike.tuple` elements carry role `group.elem` (:1920) and may be TOKENS.
         `SpikeTyped` keeps only children with `kind != "token"`, so `(x)` projects as `UnitLit`
         and `(1, 2)` as an EMPTY `Tuple`.
      3. `spike.given` drops `given.body` and `given.type` — a whole expression subtree gone.
      4. `def.paramType` / `def.dflt` / `cc.method` / `cc.fieldType` are never read: `Param.tpe`
         is unconditionally `None` and a case class's methods vanish.
      **The metric itself is the deeper defect, and it is why these survived.** A dropped child
      produces no node, so it is missing from BOTH sides of the ratio — dropping a construct
      RAISES the coverage figure. The gate rewards the failure it exists to prevent.
      Plan, in order:
      a. **[x] a silent-drop census** in `SpikeTypedCoverageSpec`, walking CST→AST instead of
         AST-only: for every branch child, did it reach the AST (its span among the AST's spans)?
         Descent stops at a drop (recorded once, at the boundary) and at an honest `Unsupported`.
         Branch children only — a token is often consumed as a STRING (`def.name`), so counting
         tokens would report every identifier as dropped. Ceiling, not floor: may only go down.
         **Read 6,641 against 2,943 admitted gaps.** A first version judged a node dropped when its
         OWN span was absent, which called all 370 `spike.paren` unwraps a drop — a projection is
         allowed to unwrap transparently, and that would have sent the first fix at working code.
         Corrected to "nothing in the subtree survived", which still catches the real paren bug.
      b. **[x]** token-level drops asserted by SHAPE in `SpikeTypedRolesSpec` — the census is
         blind to them by construction, and `(x)` → `UnitLit` is one.
      c. **[x] all five fixed, 20 shape assertions, and the census re-measured to 0.**
         Nodes 171,119 → **197,773** (+26,654 subtrees that had never reached the projection),
         coverage 98.3% → **98.5%**, admitted gaps 2,943 → 2,964 — UP, and that is the point:
         what used to vanish now reports itself. **The biggest was the one nobody had named**:
         `case.pat` read with a helper returning `""` for a branch, so all 4,579 structured
         patterns in the corpus were the empty string. `Pattern` is now a real ADT.
         A/B, not assertion: re-planting `enum.case` turns the census red at 142 and fails two
         shape tests; restoring it returns 0.
      d. **[~] the real gaps, largest first** — `spike.narg` 772, `spike.interp` 589,
         `spike.blockapp` 511, `decl:spike.sealed` 382, `spike.listlit` 252. These are honestly
         reported and genuinely unmodelled, which is the difference between them and everything
         above. NOT started; this is where the next session picks up.
      **Boundary (`v3/specs/40-front-on-uniml.md` §4):** improving the projection is UniML's side;
      the lowering to SSC IR is `SSC3-4` under the live `ssc3-core` claim. Not started here.

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
      (2) **typed projection — 98.3%, after a real bug the floor was hiding.** `SpikeAst`/
          `SpikeTyped`. Every `if` in the corpus projected BOTH branches as
          `Unsupported("spike.kw")`: the CST names the keyword tokens `if.then`/`if.else` and the
          branches `if.thenE`/`if.elseE`, and the projection read the keyword roles. The `If` node
          was present, its children were the syntax that separates them.
          It hid behind a COUNT: 2,120 of 4,851 gaps, under a floor that only asked for >95%. A
          count cannot tell "unmodelled construct" from "modelled wrongly", so `SpikeTypedIfSpec`
          asserts on the SHAPE instead. Fixing it moved nodes 139,787 → 171,119 — the branches'
          whole subtrees were never reaching the projection — gaps 4,851 → 2,943, coverage
          96.5% → **98.3%**.
          Still true and still the point: **nothing consumes this AST**, so its coverage number is
          a self-report. Real gaps now, in order: `spike.narg` 772, `spike.interp` 583,
          `spike.blockapp` 509, `decl:spike.sealed` 382, `spike.listlit` 239.
      (3) **breadth — the probe was fixed first, then the biggest gap closed.** The headline could
          not tell a language fix from a permissiveness win: an untagged fence defaults to
          ScalaScript, so PROSE counts as broken code, and twice on 2026-08-04 a correct change was
          credited for making a protocol diagram parse. `SscBreadthSpec` now attributes per FENCE —
          1,650 tagged fences at 0.10 diagnostics each against 38 untagged ones at 2.53, **25x the
          density in 2.3% of the fences** — and the TAGGED column has its own floor.
          Then the first slice measured in the honest column: **a `def`'s result type is optional**,
          as in Scala. The dialect demanded `: T`, and that one omission was 76 of 172 tagged
          diagnostics. **tagged 172 → 94 (−45%), untagged 96 → 96 (UNCHANGED), clean files
          94.8% → 96.5%.** The untagged column not moving is the point: this fix is a language fix,
          and the split probe proves it rather than asserting it.
          Two more slices on the same evidence, each measured in the honest column:
          a **dotted name in a pattern refers** (`case scala.util.Failure(e) =>` — Scala binds only
          a SIMPLE identifier, so the first segment's case stops mattering once a `.` follows):
          tagged 94 → 86, untagged unchanged, and the file carrying the top three shapes went to
          ZERO diagnostics. Then **Scala 3 fewer-braces** (`xs.foreach: x =>`, `handle(x):` + case
          arms): tagged 86 → **71**, untagged 96 → 94, clean 96.9%.
          Fewer-braces is guarded three ways because `:` is the most overloaded token in the
          language — the receiver must be a call or a selection, the colon must open a block or a
          lambda, and the argument backtracks if it does not parse as one. The spec's controls are
          the ascriptions, not the new forms.
          A fourth slice came from checking a candidate instead of implementing it. `apiClients:` +
          indented block looked like invalid content in a fence tagged `scala @side=client`; the
          reference front PARSES it and fails at runtime with "Undefined: apiClients" — a type
          error, not a syntax one. **My fewer-braces guard was stricter than the reference**,
          requiring a call or a selection as the receiver. Relaxed to any expression, with
          `colonOpensBlockArg` alone holding the line against ascription:
          **tagged 71 → 69, untagged 87 → 76.**
          The standard this settles is worth more than the number: *the dialect matches the
          reference front's PARSE, and does not judge meaning.*
          A fifth: **an indented block inside parentheses ends at the closing paren.**
          `f(x =>` + indented body has its last line end with the `)` that closes the CALL, and a
          column-bounded block tries to start a statement there. `parseBlock` already had a
          `stopAtParen` flag — a lambda body passed it, an `if`/`then` branch did not, so ONE level
          of nesting lost it. Found in `scripts/smoke-ci.ssc`, a script that runs in CI and
          therefore parses fine for the reference front, which is what made it obviously a gap on
          our side rather than bad source. **tagged 69 → 65.**
          A sixth: **type ascription in EXPRESSION position** — `compute(1): Int`. The reference
          front accepts it (`val n = compute(1): Int` prints 1 on the interpreter); this dialect
          left the colon for the statement parser. It sits AFTER the fewer-braces branch, so the
          two cannot compete: a colon that opens a block or a lambda is an argument, anything else
          after an expression is an ascription. **tagged 65 → 52, untagged 76 → 52.**
          `SpikeFewerBracesSpec` had pinned this as an OPEN gap with an assertion that it still
          failed, and a note to whoever closed it. Closing it broke that test, exactly as designed —
          a control that quietly starts testing a different defect is worse than no control.
          **tagged 172 → 52 across six slices, untagged 96 → 52, clean files 94.8% → 97.5%.**
          **⚠️ The tagged column has its own floor above zero, and it is not only prose.** The next
          candidate by size — 16 diagnostics, `@side = server` in
          `examples/frontend/data-table/data-table.ssc` — turned out NOT to be a language gap: the
          construct exists only in a spec's "later phases can introduce" paragraph, the reference
          front rejects it identically, and no gate runs that file. Implementing it would have
          "improved" breadth by teaching the dialect to accept what the language refuses. Filed in
          the root `BUGS.md`; the dialect is right and the example is wrong.
          That is the third disguise of one trap in two days — markdown prose, then English
          sentences starting with `class`, now invalid code. **A corpus is an oracle only for
          constructs the language actually has**, and chasing the number without asking that
          question is how a parser gets taught someone's typo.
          Earlier slices, each measured: `case object`, `extern`+qualified def names (which only
          work TOGETHER), escaped identifiers, varargs, type ascription.
      (5) **measurement — DONE, and it removes the risk §4 opened with.** JMH both arms, alternated
          three rounds, medians: the composed front is **2.188 ms** on `actors.ssc` against v1's
          parser at **2.285 ms** — **0.96x, parity**, 13.5 MB/s over the file. Retained tree
          **29.8x** source, measured as the slope between ten held trees and one because the naive
          probe spread 11.8x-47.7x. Both disqualifying thresholds clear by a wide margin.
          I predicted 5-15x SLOWER and was qualitatively wrong; §4.2c says why.
          ⚠️ §4.2's `~0.9-1.0 MB/s` was an ARTIFACT — measured on an **84-byte** input, where fixed
          cost is the whole reading. Do not carry it forward. And the bare dialect's 0.713 ms is
          not a speed result: on a literate `.ssc` it is `Incomplete` with 46 diagnostics.
      (6) **v1/v2-independence — HOLDS, and is ENFORCED rather than merely observed.** Censused
          every import in `uniml/`: 118 `scalascript.uniml`, 2 `scalascript.markup` (which lives
          here), and 3 reaching v1 — all 3 inside `markdown/bridge`, whose entire purpose is that
          bridge. The bridge is defined ONLY in the root build and depends on `core`; the
          standalone build does not contain it.
          **The standalone build IS the gate**, which is better than a grep: planted
          `import scalascript.ast.DocumentContent` into `SscCompose` and it fails with "value ast
          is not a member of scalascript". `ci.yml`'s UniML job runs that build, so the invariant
          is checked on every PR and nightly.
          **2026-08-05 — the bridge MOVED and the invariant is now absolute.** `uniml/markdown/bridge`
          is `v1/lang/uniml-bridge`; every import under `uniml/*/src/` is `scalascript.uniml` (119)
          or `scalascript.markup` (2), which lives there. The partition gate's bridge exemption is
          DELETED rather than left true-by-accident — a dead exemption is where the next violation
          hides — and its self-test still catches the planted `uniml/core` → v1 dependency, 6 of 6.
          ⚠️ **Residual hole, recorded rather than glossed:** a NEW module added under `uniml/` to
          the ROOT build only would escape this entirely — it would never be compiled by the
          standalone build, so it could import v1 freely and nothing would notice. That is exactly
          the bridge's shape, sanctioned; an unsanctioned one would look identical. The isolation
          gate enumerates ten projects by name and would not fire either.
      (1) **publishable — WORKS, and consuming it found a defect nothing inside could see.**
          `cd uniml && sbt publishLocal` already produces all ten artifacts (five modules x JVM/JS)
          with poms, sources and javadoc, and the poms carry correct inter-module dependencies.
          But "the command succeeded" is a self-report, so the check is a CONSUMER: a scala-cli
          project outside this build, resolving `scalascript::scalascript-uniml-scalascript` from
          ivy2Local, parsing a `.ssc` and round-tripping it.
          **It printed `lossless=false`.** The composed tree APPENDED an injected subtree instead
          of splicing it where the fence body was, so the closing fence marker came out BEFORE the
          code — every character present, in the wrong order, which is why a length or multiset
          check would have passed. `SpikeLosslessSpec` cannot see it: it parses a bare dialect and
          never injects. Fixed; composed round-trip went to **1,173 of 1,179 exact** and is now
          gated by `SscComposedLosslessSpec`, frozen as a SET so a file leaving it is also news.
          The remaining 6 are a different defect — markdown indented code losing the four-space
          prefix on continuation lines — filed in `uniml/BACKLOG.md`.
          **Coordinates were WRONG, and only comparing the two builds showed it.** Each published
          successfully on its own, so nothing looked broken: the root produced
          `io.scalascript:scalascript-uniml_3:0.1.0` and this build
          `scalascript:scalascript-uniml_3:0.1.0-SNAPSHOT` — the same sources as two artifacts a
          consumer's build treats as unrelated. Fixed by matching the root, and `UnimlCoordinatesSpec`
          reads BOTH build files so they cannot drift again (verified by planting 9.9.9).
          License, homepage, scm and description added. Outside consumer re-verified at the new
          coordinates: `status=Complete diagnostics=0 lossless=true`.
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
