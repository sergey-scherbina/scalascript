# ssc 2.0 — Sprint (active task queue)

Self-contained queue for the **`v2/`** subproject. This file is the pattern the whole repo now
follows — see [`../specs/work-tracking-layout.md`](../specs/work-tracking-layout.md): every module
has its own sprint and backlog, and the repo-root `SPRINT.md` is a BOARD of what is in flight
rather than a second queue.

**Two states in this file, and no third:** `[~]` in progress, `[x]` done. Not-started work belongs
in [`BACKLOG.md`](BACKLOG.md) — the `[ ]` items still below predate that rule; move one to the
backlog (or start it as `[~]`) when you next touch its section, rather than in one sweep that would
lose the reasoning around them.

Milestone view: [`ROADMAP.md`](ROADMAP.md). Pipeline: `ssc0 → ir → ssc(VM) → cpu`. Work each slice
in its own worktree off `origin/main`.

## `a ++ b ++ c` on lists answered a tuple (claim `scljet-tuple4-instrumentation`)

- [x] `sconcat` read a `Cons` CELL as a pair, so the OUTER concat of a three-way list chain
  returned a `TupleN`: `List(1,2) ++ List(3) ++ List(4,5)` printed `(1, List(2, 3), 4, List(5))` on
  the v2 VM and `--bytecode` lanes, silently, exit 0. Three copies fixed (the prim, its binary
  fast-path twin, and the `JvmBackend` preamble); `tests/e2e/list-concat-chain-gate.sh` is wired
  into smoke as `v2/list-concat-chain` and was verified RED on the unpatched toolchain.
- [x] The front's part is left alone deliberately: `isConcatCode` reads `++` as evidence of a
  String, which is what routes a list chain into `sconcat` at all. Once the prim is total that
  inference costs nothing when wrong, and narrowing it would de-type real string chains.
- Downstream and NOT done — `scljet-crud`/`full`/`jdbc` now fail further in on
  `app: not a function: 0` (`v2/BUGS.md scljet-app-not-a-function-after-the-concat-fix`);
  `scljet-write-table` passes. Two more lanes carry a concat defect and are filed, one measured
  (js) and one inferred from source (rust).

## F coverage: the decline reasons left after 2026-08-08 (claim `f-underscore-global`)

Measured, not guessed: `ssc info --front-report` over 140 corpus files. After the arm-body fix
(`d7546f299`): **53 F, 17 GAP, 65 BOTH-UNBOUND, 5 ERROR**, from 38/32/64/6 before it — 15 files
GAP→F, one ERROR→BOTH-UNBOUND, and **no file regressed out of F**.

**The coverage number is not a correctness number, and here is its counterpart.** Ask the 53
F-verdict files a second question — does F PRINT what the reference front prints:

    after the arm-body fix (d7546f299)        34 agree   3 fail identically   16 DISAGREE   3 F-worse
    after curried defs + varargs (b32c9e663)  34 agree  11 fail identically    8 DISAGREE   3 F-worse
    after externs pass through (ce140eb69)    36 agree  11 fail identically    6 DISAGREE   1 F-worse

Measured again by the GATE, over its own 240-file rule-based set rather than the old hand sample:

    at the gate's first freeze            47 agree  23 fail identically   11 DISAGREE   1 F-worse
    after `++` chains stay lists          47 agree  24 fail identically    7 DISAGREE   1 F-worse

The last column is the one that matters and the only one that fell all the way: **F is observably
worse than the reference on 1 of the 53 files it claims**, down from 3. The remaining one is
`smoke-test.ssc`, held by the three std/ui gaps in
`f-std-ui-gaps-behind-the-curried-def-fix` — none of them F's lowering.

**It is a GATE now, not a thing I ran by hand** — `tests/e2e/f-output-agreement-gate.sh`, wired into
`ci.yml` along with the four F gates, which until 2026-08-09 were invoked by no workflow and no
suite. They are in the full suite rather than smoke because they cost ~414 s against smoke's ~233 s
of headroom. The new gate freezes three numbers: **F-worse ≤ 1** (tight — it can only shrink under
load), agreement ≥ 38 and subjects ≥ 68 (slack, so a busy runner does not turn it red for being
busy). Verified it can FAIL, not only pass.

Two lessons from building it, both cheap to repeat and expensive to discover:

- **A timeout is not a verdict.** The first version reported 8 files where F is worse; seven were
  slow files killed by a 15 s cap under `-P 4`, one of them with byte-identical output on both
  sides. A gate whose answer depends on how busy the host is gets believed on a quiet machine.
- **`-x bin/ssc` does not mean the toolchain runs.** A fresh worktree has the launcher and no jars,
  and all four F gates reported FAIL rather than SKIP there. `tests/e2e/lib/ssc-usable.sh` probes
  functionally instead; verified in both states, because a guard that skips everywhere is how a gate
  goes quiet.

**The instrument, which should have existed first.** Bootstrap F0 the way
`specs/v2.2-p6.5-corpus.sh` does and dump F's OWN IR beside the oracle's for a three-line program:

    FSUB_SRC=specs/v2.2-p6.5-fsub.ssc java -Dssc.stackSize=1073741824 -jar <kernel> run bin/<drv> > F0.ir
    java -Dssc.stackSize=1073741824 -jar <kernel> run-ir F0.ir probe.code

It takes about a minute, needs no toolchain rebuild, and it answered in ONE run what eight
hypotheses and several six-minute builds had not. Every "F does something different and I cannot see
what" question should start here.

The curried/vararg fix moved **eight files out of disagreement**: they now fail exactly the way the
reference front fails, on a shared TLS or duplicate-signal blocker, instead of on an arity error of
F's own. **The coverage census did not move at all — 53/17/65/5 before and after — and that is the
point of keeping both numbers.** A front-report verdict says F LOWERED the file; these calls were
being lowered before too, just to code that answered wrongly. A fix that removes wrong answers is
invisible to the coverage number by construction.

Of the 8 that still disagree, five fail under both fronts with different messages, and **F is
observably worse than the reference on 3 of the 53** — unchanged, because those three are not this
fix's: `_bug1b`/`_bug1c` are the dropped trailing block, and `smoke-test.ssc` stops on three std/ui
gaps behind it. Entries: `f-drops-a-trailing-block-argument-without-running-it`,
`f-std-ui-gaps-behind-the-curried-def-fix`.

**Rank by GAP only.** The earlier ranking in this section counted every row, so it was topped by
`(global _)` at 33 — but most of those are BOTH-UNBOUND rows, where the reference front declines
too and the work is not ours. Ranked over GAP alone, which is what F actually owes, the top reason
was `(global v)` at 16 of 32, and all 16 came from ONE module. A reason list that mixes the two
verdicts ranks other people's problems above your own.

Remaining, GAP only:

     4  (global Parser)
     2  (global handler)
     2  (global __u0)
     1  each: row, multi, grade, effect, css, _ssc_frontend_name

- [x] **`(global v)` — 16 of the 32 GAP files, all frontend examples.** Cause: an ordered-resolver
      arm body was parsed as a single expression, so an inline `val` leaked out of the arm and was
      read as a top-level val — see `tests/BUGS.md`
      `f-ordered-match-arm-body-is-not-a-statement-sequence`. It also silently returned the FIRST
      statement of a multi-statement arm body, which is the worse half and had no diagnostic at all.
      The 16 files never contained the construct: they import `runtime/std/ui/lower.ssc`, which does.
      Gate: `tests/e2e/f-global-v-gate.sh`.
- [ ] **`(global _)` still leads the BOTH-UNBOUND rows** and is NOT covered by the above. It stays
      open, but it is not F coverage work until the census below says whose it is.
- [x] **Censused the 65 `BOTH-UNBOUND`, 2026-08-08 — and the useful outcome WAS "not our work".**
      Ran all 65: **20 run cleanly** (the static pre-check refused them wrongly) and 45 do not — 21
      on an unbound global at RUNTIME, where the pre-check was right and the name is bound nowhere in
      this lane, 10 on an unhandled runtime effect, 2 on TLS, 12 other. That CORRECTS
      `tests/BUGS.md` `both-unbound-is-mostly-plugin-intrinsics-not-user-error`, which asserted these
      files run correctly: true for less than a third.
      **The denominator is the payoff.** None of the 65 turns on F's lowering, so F's real scope is
      140 − 65 = **75 files, of which F handles 53 — 71%, not the 38% that 53-of-140 suggests.**
      Quote the 75, or F's coverage reads as half of what it is. Achieved with no code at all.
- [ ] The GAP tail, now the whole of it: `Parser` (4), `handler` (2), `__u0` (2), and six singles.
      `Parser` is the one whose reduction failed to transplant twice; start it from the closure, not
      the module. `__u0` is `runtime/std/ui/content.ssc`, which has a verified seven-line in-place
      reduction where every line is load-bearing and eight hypotheses already eliminated.

Rule this section exists to carry, learned the expensive way: **the name F reports is not
necessarily where the fault is.** `Response` was three import levels from its cause and cost nine
measurement runs. `(global v)` was the same shape and cost none, because the first thing asked was
each module's own `--front-report` verdict rather than the failing file's. Ask that first.

Second rule, and this one cost a whole build: **which parser sees your construct is decided
somewhere you are not looking.** A match routes to the ordered or the ctor resolver by its FIRST
pattern, and the two have separate arm-body handlers. The fix to `armBodyExpr` was correct, built
clean, and changed nothing at all, because every probe matched on a String and went to the other
one. When a correct-looking fix moves no measurement, suspect the route before the reasoning.

## `package:` binds a namespace on the native lane (claim `native-package-namespace-impl`)

Spec: [`../specs/native-package-namespace.md`](../specs/native-package-namespace.md).
Entry: [`BUGS.md`](BUGS.md) `native-front-has-no-package-namespace`.

- [x] **both TRAILING argument forms flatten onto a curried callee** (`f-trailing-arg-curried-flatten`,
      `8abe91b6a`). `ap(3) { … }` and `ap(3): p => …` died with `arity: 2 expected, 1 given`:
      `ca8bb823e` made curried defs lower at TOTAL arity and taught the parenthesised call site to
      flatten to match, but both trailing spellings reach `blockArgApp`, which nested
      unconditionally. It takes `cx` now and flattens the same way. This was the LAST regression row
      in the corpus-contract nightly.
      **Two corrections to my own earlier BUGS entry**, both written into it: the break window and
      its two suspects were wrong (the row first appears on the 08-05 nightly, not 08-04, so the
      cause is `ca8bb823e` at 08-04 16:16), and "the colon spelling only" was wrong — the braced
      form fails identically.
      **Why the pair fell between two gates that each look complete:** `fewer-braces-colon` covers
      the colon on a NON-curried callee, `curried-def-clauses` covered the parenthesised list on a
      CURRIED one, and nobody covered the intersection. It is in `curried-def-clauses` now, with the
      returns-a-function discriminator repeated for BOTH forms — a fix keying on call SHAPE instead
      of the curried-name TABLE would pass the first rows and fail those. Revert the front with the
      gates kept and both cases fail on v2 while int stays green.

- [x] **N-1 — emit the namespace chain in `sscLoadMod`** (`v2/bin/ssc1-run.ssc0`). Read `package:`
      from the module's front matter, take member names from the ALREADY-PARSED `defs`, emit
      `def __pkgref_<prefix>__<n> = <n>` + `object <prefix>: def <n> = __pkgref_…` as text, parse it,
      append AFTER `defs` (the alias is a parameterless property, therefore eager). A dotted package
      needs one registration object PER PREFIX (`a`, `a_b`, `a_b_c`) — a missing intermediate is
      `unbound global: a` at the call site, not a missing member.
- [x] **N-2 — verify on BOTH fronts.** `SSC_FRONT=F` and `SSC_FRONT=legacy`, because the design this
      replaced was measured on F alone: there `object ns: def ui = ui` aliases, on legacy the same
      line is `unbound global: ns_ui` and its parameterful form HANGS. Every alias therefore goes
      through a top-level `__pkgref_…` indirection so no member shadows its own right-hand side.
- [x] **N-3 — the acceptance gate, and it was measuring two things it did not claim.** The row
      labelled `INT` ran `bin/ssc`, the NATIVE lane; the interpreter was never exercised. The `JVM`
      row ran `bin/sscc`, the COMPILER, which prints "artifact written to …" without compiling the
      generated Scala. Now four rows — INT / NATIVE / JS / JVM — and pointing the JVM one at a RUN
      exposed a real defect, filed as `jvm-package-import-qualifies-the-link-name` and declared
      known-red by slug (the gate fails if it starts passing). The JS row PASSES: the
      `const org = org.example.ui.org;` failure recorded when this was planned came from a STALE
      binary in the shared checkout, not from the js lane. Registered in `ci.yml` — 6.5 s local,
      too heavy for the smoke suite's 420 s cap, and it had been in neither tier.

## `as` in an import link must bind on native (claim `aliased-package-root`)

Entry: [`BUGS.md`](BUGS.md) `native-import-link-alias-is-ignored`.
Sibling, different cause, NOT this task: `js-aliased-package-root-import-is-unbound`.

Measured 2026-08-06 — every alias form fails on this lane, and `package:` is irrelevant to three of
the four rows:

```
[org as o]     the package root          int OK   native err   js err   jvm OK
[Card as C]    a member object           int OK   native err   js OK    jvm OK
[helper as h]  a member def              int OK   native err   js OK    jvm OK
[greet as g]   a module with NO package  int OK   native err   js OK    jvm OK
```

Control: the same link without `as` prints `hi-hi` on native.

- [x] **A-1 — capture the link LABEL, additively.** `sscImportPathsFrom`
      (`v2/bin/ssc1-run.ssc0:319`) reads `[...]` only to find its closing bracket and returns paths.
      Do NOT change what it returns: `sscImports` feeds the module graph, `sscOrderMod`, the content
      projection and the F runner, and widening its element type touches all of them. Add a PARALLEL
      scan returning only the aliased bindings — `Pair(path, Pair(origName, aliasName))` — so
      nothing that exists today changes shape.
- [x] **A-2 — emit, and the two sub-cases need different emissions.** A member alias is a value
      alias: `def g = greet`, the flat splice having already defined `greet`. The package ROOT is
      not — `org` is a synthetic namespace object and objects are NOT first-class values here
      (`val Card = Card` is `unbound global: Card`), so that row needs a second namespace CHAIN
      rooted at the alias whose members forward to the same `__pkgref_…` refs the real chain uses.
      Reuse `sscPkgNsSource` with the first segment replaced.
- [x] **A-3 — both fronts, and they need it in different places.** The legacy path threads parsed
      defs, so alias defs go between `impDefs` and `defs` in `sscLoadMod` / `sscLoadRoot`. F
      assembles from SOURCE (`sscConcatSources`, `ssc1-run-fsub.ssc0`), where they must be
      PREPENDED to the importing file's own source — appending would place them after that file's
      top-level expressions, and a value alias is eager. Same trap as the namespace work: a fix in
      the shared loader alone reaches one front.
- [x] **A-4 — gate before release.** The existing `package-keyword-smoke.sh` covers the unaliased
      forms only. Add the four alias rows, and verify with the ABSENT-state control (the same links
      without `as`) so the gate cannot pass by accident.

## open entries whose BODY claims done (claim `open-entries-that-claim-done`)

`scripts/bugs-report --drift` reports 0, and it is right for what it asks: it reads HEADINGS. Five
open entries make a bold done-claim in their BODY instead — the shape that had `v2-getorelse` closed
as "bookkeeping only" and then REOPENED because the fix had reached one front and not the other.

- [x] **D-1 — re-measure each, do not read it.** Triaged by reading first, which already collapses
      the list: four of the five are CORRECTLY open and the heuristic false-positived, because the
      bold claim scopes a PART, not the entry —
      `v2-native-charAt-toString-yields-code` says "ENGINE SIDE FIXED" and then "STILL REPRODUCES,
      re-verified 2026-08-02"; `native-lane-ignores-declarative-route-registration` says "HALF
      FIXED"; `sbt-test-7-failures…` dates a cluster; `v2-getorelse` carries a REOPENED block above
      its "ALREADY FIXED". Only `v2-infix-extension-operator-stringifies` claims completion
      ("PART 3 DONE … F is now at PARITY") with an `open` header and no `fixed-in`.
- [x] **D-2 — measure the infix table on the current build**, all three cells, on int, `SSC_FRONT=F`
      and `SSC_FRONT=legacy`. Close with the numbers if it holds; if F has drifted back, the entry is
      wrong for the second time and says so.
- [x] **D-3 — record what the heuristic costs.** Five hits, one real. Worth stating in the entry that
      survives, so the next person running the same scan does not re-triage the same four.

## front-matter `routes:` on the native lane (claim `native-fm-routes`)

Spec: [`../specs/native-frontmatter-routes.md`](../specs/native-frontmatter-routes.md).
Entry: [`BUGS.md`](BUGS.md) `native-lane-ignores-declarative-route-registration` — its second half.
Gate: `tests/e2e/fm-routes-smoke.sh`, NATIVE declared a gap, five rows red today.

The entry sized this as "a design choice about where that boundary sits". It is not: the crossing
exists and `databases:` uses it (`NativeV2Structural.runtimeConfig` -> `NativeRuntimeConfig` ->
`NativePluginHost.loadAll` -> `def databases = config.databases`), and `resolveGlobal` is already on
the SPI. Four edits, each beside a working precedent.

- [x] **R-1 — SPI:** `NativeRouteDecl`, a defaulted `routes` on `NativeRuntimeConfig`, and a
      defaulted `declaredRoutes` on `NativePluginContext` (defaulted for the same reason
      `resolveGlobal` is — mock and existing contexts stay source-compatible).
- [x] **R-2 — host:** `def declaredRoutes = config.routes`, one line beside `databases`.
- [x] **R-3 — CLI:** read `routes:` in `runtimeConfig` beside `databases:`. A SEQUENCE of mappings,
      not a mapping of mappings; all three fields required, and a missing one raises naming the file
      exactly as a database without a `url` does.
- [x] **R-4 — plugin:** register beside `registerHealthDefaults()`, before serve and only when
      absent, with a handler that resolves through `resolveGlobal` **at request time**. Lazy is the
      point: it is what makes a handler defined AFTER the `serve(...)` call still resolve, which is
      the interpreter's behaviour and the property an eager implementation passes the gate without.
- [x] **R-5 — delete the gate's NATIVE declaration.** Forced, not optional: the gate fails a
      declared row that starts passing.

## v2 source backend lanes (claim `v2-source-backend-lanes`)

- [x] **B-1 — `v2-source-backends-miss-autoOutput`.** `v2-jvm` and `v2-rust` could not run ANY
      program. Only PART was still open and the entry did not say so: `JvmBackend.scala` had already
      been given the arm by someone else. Measured before coding — the rust and js generators had
      ZERO occurrences of the name — and both now implement it. Both lanes run the entry's own
      repro.
- [x] **B-2 — the gate that was missing is why this was invisible.** `v2/backend/check.sh` compares
      every generator against the VM byte-for-byte, and **no fixture used `__autoOutput__`** — so
      the harness was green while two lanes were dead. New `v2/conformance/autooutput.coreir`
      (Int / Unit / String / nesting); fixtures are auto-discovered, so the file IS the
      registration. All four generators pass. The String row matters: the generators' `show` quotes
      strings and the VM's `out` does not.
- [ ] **B-3 — `js-compound-assign-dispatches` NOT fixed, and its header was wrong.** Re-measured:
      `x += 1` works on int and on the v2 native lane, and fails on the **v1 js** lane
      (`Method not found: += on 0`, thrown by JsGen's own `_dispatch`). So `lane: v2-jvm` was
      incorrect and the entry has been MOVED to `v1/runtime/backend/js/BUGS.md`, where its fix
      lives. Left open because `JsGen.scala` is held by the live `ssc3-core` claim.

## v2 object scope plumbing (claim `v2-object-scope-plumbing`)

Closes `v2-object-var-member-resolves-to-a-top-level-global`, the slice the previous batch scoped
out. Both fronts; all ten rows of `object-var-member-scope` match the jvm golden and the file moved
`GAP -> F` in the front report, so F's coverage hole is one file smaller too.

- [x] **P-1 — legacy: three reorderings, no new machinery.** `isActiveOwnerVar` already existed and
      was consulted AFTER `isTopVar`/`isTopVal`, so a colliding name was taken by the top-level
      branch. A member shadows an outer name inside the object body.
- [x] **P-2 — F, inside the body:** new `curObj` cx slot (deepest payload, `(retTab, curObj)`, same
      extension shape as DA10/E2/G3), set by `objectItem1` for member bodies only and consulted
      AFTER the local-env lookups so parameters and nested vals still shadow.
- [x] **P-3 — F, outside the body:** `postSel` emitted `(global O_v)` for every member, but a `var`
      member is `O_v__cell` — an unbound global, which is why F DECLINED the file. `objReg`'s
      payload is now `(memberNames, varMemberNames)`.
- [x] **P-4 — evidence.** Full corpus contract on int,js,v2 rather than a slice, because this
      changes NAME RESOLUTION in both fronts and a slice cannot speak for that.

Two mistakes recorded in the entry because both cost a build cycle: `curObjOf` written with one
`snd` too few returned the enclosing PAIR (symptom: `match: no arm for Tuple2/2`, far from the
accessor — count the chain against its neighbours), and a `match` nested inside a match ARM does not
lower in this file.

## object-var-member family (claim `object-var-member-family`)

One source shape, three lanes, three different causes — the entry said so and the measurement
confirmed it. Measured all four lanes on a clean build BEFORE coding, which again changed the plan.

- [x] **O-1 — js: `js-object-var-member-is-never-emitted` FIXED.** `genObjectAsExpr` had a
      `case Defn.Val` arm and NO `Defn.Var` arm, so the member was dropped entirely. Fixed with
      `let` (the methods mutate it) plus a **get/set accessor pair** in the returned literal — a
      shorthand property would copy the value at return time and the object would look immutable
      from outside while its own methods appeared to work. All 10 rows match the jvm golden; the
      `js FAIL` baseline row is removed.
- [x] **O-2 — int: nothing to do.** `object-var-member-assignment-writes-a-top-level-global` is
      already `status: fixed` (`b8a41142a`, `ObjectVarEnvView`), and the int lane reproduces the
      golden exactly. Claimed on the strength of the v2 entry's "three lanes" note without checking
      the int entry's own header first.
- [ ] **O-3 — v2: NOT taken, and now diagnosed.** `emitAssign` and `calleeOf` build the cell name
      from the BARE identifier, so a member body's `n = n + 1` writes the TOP-LEVEL `n__cell`.
      `objReg` already carries every object's member names; what is missing is any notion of the
      object currently being lowered. Threading it = a 14th `mkCx` slot (13 params, one construction
      site, and every later accessor's `snd` chain shifts) or an extra parameter through
      `objDefEmit` → `objDefE` → `emitDefBody` → `bodyExpr`. The legacy lowerer needs the same.
      That is a context-plumbing slice of its own; the `v2 DIVERGE` baseline row stays.

## v2 front triple (claim `v2-front-triple`)

Three entries claimed as one batch because they share `specs/v2.2-p6.5-fsub.ssc` and one build
cycle. **Plan written after the first measurement rather than before it, and the measurement is why:**
running the three repros on a clean build turned one of them into a bookkeeping fix and uncovered a
regression I had shipped myself the same day.

- [x] **U-1 — `unit-literal-pattern-diverges-two-lanes-against-two`.** Reproduced (int `UNIT`, v2
      `OTHER`). The arm is not mismatched, it is DROPPED: `()` in pattern position went to the
      empty-tuple path. Fix = `isUnitPatHead` + an `("lpat", ("unit", ""))` return in
      `parsePatAtom1`, a `"unit"` arm in `litPatF`, `parseGenUnit` in the ordered resolver, and the
      routing test in BOTH `parseMatchArms` and `caseLamIsGen`. The previous attempt (recorded in the
      entry) stopped at the first two edits; the routing is what it was missing.
- [x] **U-2 — `v2-getorelse-two-arg-falls-into-option-helper` was ALREADY FIXED.** All four receiver
      shapes the entry lists (bare var, field selector, literal, call result) plus the Option form
      answer identically on int, F and legacy. Fixed by `560ce09e9` ("getOrElse(k, default) stops
      falling into the Option helper — legacy front"); the entry was never moved off `open`.
      **I had already written an F-side fix for it and reverted it**: changing working code with no
      measured defect is a risk without a benefit. Bookkeeping only.
- [ ] **U-3 — `v2-object-var-member-resolves-to-a-top-level-global`: NOT taken, deliberately.**
      Reproduced (3 of 8 rows differ from the int golden). It is a scope-resolution fix in the
      object-member lowering for both fronts — F declines the file with
      `unbound global: (global Counter_n)`, i.e. it already mangles the name but nothing emits that
      global. Its own slice, and the entry says it is one of three lanes with three different causes.
- [x] **U-4 — NOT in the plan: a regression I shipped this morning.** The char-literal fix
      (`df5785f6f`) added `isNumLitHead` to `parseMatchArms` and NOT to its twin `caseLamIsGen`, so a
      char literal pattern inside a `case` LAMBDA fell through to the ctor path and died with
      `match: no arm for /-1`. An explicit `match` was fine, which is why the char gate could not see
      it. Both walkers are now on the shared predicates, and
      `tests/conformance/literal-pattern-in-case-lambda.ssc` gates char AND unit through BOTH.

## F reads `~`, `~>`, `<~` (claim `f-tilde-infix`, BUGS `f-tilde-infix-…` + `f-operator-ext-param-…`)

**Two entries, ONE commit, and the order is not a preference.** `f-tilde-infix-silently-miscompiled-as-bitwise-not`
is a SILENT wrong answer (F accepts `3 ~ 4`, exits 0, prints the wrong number);
`f-operator-ext-param-tilde-arrow-declines` is a decline, which is safe — the fallback front
compiles those files correctly. That decline is currently the only thing PROTECTING the ten corpus
files built on `std/parsing/combinators.ssc`. Fixing the decline alone would move all ten out of the
safe fallback into F's hands, where infix `~` is miscompiled: a visible gap traded for ten silently
wrong programs.

**The oracle already answers the design question.** `ssc1-front.ssc0:1591` `opPrec` gives `~` → 9,
`~>` → 9, `<~` → 5, and F's contract is to reproduce the oracle's lowering — so the precedences are
read off, not invented. F's scale is compressed (max 7 = `*` `/` `%`), so the ORDER is what carries
over: `~`/`~>` go ABOVE `*` (F 8, new top), `<~` joins the comparison group (F 4, where `<` already
is).

- [x] **T-1 — fail-first, measured.** `tests/conformance/f-tilde-extension-infix.ssc` already exists
      and is expected-red. Measured on this build: int `304`/`102`, F prints **`3`, `1`, `0` — three
      lines for a two-line program**, which is worse than the entry's recorded "v2: 0". A second case
      is needed for `~>`/`<~`, which no case covers.
- [x] **T-2 — lexer, single tokens.** `opCode` lexes ONE character, so `~>` becomes `~` then `>` and
      `<~` becomes `<` then `~`. New `lexTilde` (126: `~>` → **69**, else 68) and a `<~` → **70** arm
      in `lexLt`. Codes 69/70/71 verified free (the existing hits on those numbers are ASCII `E`/`F`
      in `isExpCh`/`isFloatSfx`, not op codes).
- [x] **T-3 — `opNameK`.** 68 → `~`, 69 → `~>`, 70 → `<~`. This is what registers the extension by
      name, and it is also the sibling's fix: with `~>` a SINGLE token, `extMembers` finds `(` where
      it used to find `>`, so it reaches `parseParams` and the member's parameter is bound.
- [x] **T-4 — infix reading.** `binPrecK` 68 → 8, 69 → 8, 70 → 4; `emitBin`/`emitBinT` arms that
      dispatch to `emitArithExtS` when the extension exists and `emitArithS` otherwise — the same
      shape `emitPP` already uses for `++`. Prefix `~` (`parseTilde`) STAYS: atom position vs.
      after-an-atom already distinguishes the two readings, exactly as it does for `-`.
- [x] **T-5 — evidence.** Both gates green on int/js/v2; the ten `combinators.ssc` importers must
      move **GAP → F** in `ssc info --front-report` (the number goes in the commit); F's own output
      compared against the oracle on those files, since matching the oracle is the contract;
      `scripts/smoke-ci`.
- [x] **T-6 — NOT in the plan: the runtime half.** With the front fixed, `3 ~ 4` still died with
      `__arith__: unknown op ~ for Int`. `__arithExt__`'s `primitiveWins` hands ANY numeric pair to
      the primitive, and `~`/`~>`/`<~` have no primitive arm at all, so the extension lost to an
      operator that does not exist. Located by measurement, not by reading: the SAME extension on a
      case class already dispatched (`D(3) ~ D(4)` -> 304), and a front bug cannot be
      receiver-type-selective. New `noPrimitiveArm` carve-out beside the existing one for `|`.
- [x] **T-7 — the ten files did NOT move to F, and that is the result.** `q` is gone from all ten
      importers; every one now reports `unbound global: loop` instead. F's GAP count is unchanged at
      10 for them, so anyone re-running the census would read the fix as a no-op — filed as
      `f-unbound-loop-is-the-new-top-gap` so that reading is not available. The safety property
      still lands: when `loop` is closed and those files reach F, `~` is no longer miscompiled.

## v2 Char is a value, not its code point (claim `v2-char-value`, BUGS `v2-char-is-an-int`)

The defect: `'x'` is the `Int` 120 on this lane in `println`, `.toString` and concatenation. Not a
`Show` difference — the value has already lost its identity, so a display special-case is explicitly
ruled out by [`../specs/v2-char-is-an-int.md`](../specs/v2-char-is-an-int.md).

**Shape chosen (owner's call 2026-07-31), and what it deliberately does NOT do.** A char literal
lowers to the existing `Prim` mechanism — `(prim char (lit (int 120)))` — so **`Const` and the frozen
`specs/12-ir-format.md` are untouched**; the grammar already admits any prim op (precedent:
`__autoOutput__`). In the runtime `CharV` EXTENDS `IntV`, so all 273 numeric `IntV` sites keep
working by construction: `s.charAt(i) == 34`, `lastIndexOf('\n')`, `case '\n' =>` and char
arithmetic need no edit and no enumeration. Only the text-producing sites learn about `CharV`.

Rejected, with the reason so it is not re-litigated: a real `Const.CChar` through the whole tower is
more correct by types but changes a FROZEN wire contract and obliges all six generators at once —
its own slice, not this one.

- [x] **C-1 — fail-first gate.** `tests/conformance/char-as-value.ssc`. **8 of its 14 rows failed
      on v2** before the fix; all pass on int/js/v2 after. Two findings while establishing the
      golden, both filed rather than absorbed: the case had to lift `wrap` to TOP LEVEL because a
      nested `def` makes F decline the whole file (a declined file is compiled by the fallback, so
      the case would have gated one front while reading green for both), and the repro line the
      BUGS entry records — `"s" + 'b'` — turned out to straddle a SECOND defect,
      `jvm-string-literal-s-concat-inserts-x` (a left operand of exactly `"s"` emits an extra `x`
      on the jvm lane, with a String right operand too, so it has nothing to do with Char).
- [x] **C-2 — runtime.** `IntV` `final` → `sealed` + `final class CharV(c: Char) extends
      IntV(c.toLong)`; prim `char` in both prim tables; `CharV` matched BEFORE `IntV` in `anyStr`,
      `show`, the `toString` method arm, and two new String+Char arms in `arithOp`.
- [x] **C-3 — legacy front.** `ssc1-front.ssc0` emits `mkTok("char", code)`; new `mkChar` expr tag;
      `ssc1-lower.ssc0` lowers it to `IrPrim("char", …)`. Literal patterns left as `int`, as planned.
- [x] **C-4 — F front.** `lexChar` emits kind **12**; `emitChar` beside `emitInt`; `parseAtom1`,
      `parsePatAtom1` and `eraseB` extended. **Not in the plan and needed:** kind 12 also had to be
      named at F's two kind-0 match-arm sites (new `isNumLitHead`), or the arm fell through to the
      ctor path and died at runtime with `no arm for` — caught by the gate, not predicted.
- [x] **C-5 — backends.** `char` added to the jvm/rust/js generators as a pass-through on the code
      point: those lanes have no Char in their value models, so they keep their PRE-FIX behaviour
      (no regression) instead of gaining `unknown prim1: char` on every program with a char literal.
      Char-in-text stays wrong there, recorded in the BUGS entry.
- [x] **C-6 — evidence.** `scripts/smoke-ci` 24/24; corpus contract GREEN on int/js/v2;
      `v2/backend/check.sh` red only on the pre-existing `backend-check-mutual-recursion-drops-output`
      (proven not mine: neither `mutual-recursion` fixture contains a single apostrophe, so the new
      prim is unreachable from it, and it is the same three generators and same missing line the
      entry records).
- [ ] **C-7 — the perf A/B is OWED, on a quiet host.** `IntV` stopped being `final`, and this repo
      does not accept "probably fine". It is **UNRESOLVED, not "no effect"**: at load 5.5 the host
      cannot resolve it, and the evidence is in this repo's own history rather than in my judgement
      — `lazylist-take` on the v2 lane measured **93.4 / 29.2 / 41.6** on IDENTICAL code
      (`315dbca16`, three rounds), with the BEST round at the HIGHEST load. What to measure when the
      host is quiet: `arith-loop` and `pattern-match-heavy` on the v2 lane, alternating A/B against
      a control build that restores `final class IntV` and drops `CharV`. Prior: exposure is
      narrower than "IntV is no longer final" sounds — field reads (`v.n`) are not virtual, and the
      hot `arithFastTyped` path goes through `IntV.unapply`, a static companion call that finality
      never affected. The three virtual methods (`equals`/`hashCode`/`toString`) are already called
      through `Value`.

## v2 perf — what is realistically doable after 2026-07-31 (claim `v2-perf-bench-front-column`)

Written after a day of measuring. Two constraints shape this list and are not negotiable by
enthusiasm:

- **This host cannot resolve an effect below ~2× with a whole-workload benchmark.** Identical code
  swings 2.5×. The condy prim-dispatch change (predicted ~18%) came back −17% … +42% and had to be
  recorded as UNRESOLVED rather than as "no effect".
- **5 of 36 corpus rows are compiled by the FALLBACK front, not F** — including `effect-stream`
  (303×) and two rows where v2 beats v1. Conclusions from those rows are about a different compiler.

- [ ] **P-1 — widen the unboxed-cell test to a CALL RETURN.** `parseBlockVarBind` already receives
      `cx`, and F already has `callRet(c, cx)` / `lookupRet` (the return-type table `operandTag`
      consults). A `var` initialised from an `Int`-returning `def` is a very common shape and today
      gets a BOXED cell. Same mechanism and same procedure as the landed 2.03× fix.
      **Blocked**: `specs/v2.2-p6.5-fsub.ssc` is under `f-set-empty-receiver`; four different claims
      have held it today. Patch shape is one clause: `if isIntLitCode(init) || callRet(init, cx) ==
      "Int" then parseBlockVarLc(…)`.
- [x] **P-2 DONE — NO EFFECT, and it caveats the 2.03× win.** BEFORE 39.0/38.7/41.3, AFTER
      38.9/40.0/38.4; medians 39.0 vs 38.9. `vector-index` declares `seed: Long`, F types only
      `Int`, so its initialiser stays untyped `__arith__` and the widened test still says no.
      **So the landed fix's corpus footprint is ONE row — `var-expr-init-int`, which I added for
      it.** Real code that declares `Int` gets the 2×; the corpus writes `seed: Long` almost
      everywhere and does not. The bottleneck for the corpus is F not typing `Long`, and the one
      attempt at that made F silently DECLINE programs (`v2/BUGS.md`).
      *(original note)* re-measure `vector-index` (47×). It is the ONLY remaining big row whose var is
      expression-initialised (`var s = (seed …`), so the landed cell fix may already have helped it
      for free. The other big rows (`list-fold`, `range-sum`, `pattern-match-heavy`, `float-fold`)
      all use literal initialisers and were already unboxed — the fix does nothing for them.
- [x] **P-3 DONE.** `bench/run.sc` now prints a `front` column on v2 lanes:

          | Workload         | front        | v2-bytecode (ms/iter) |
          | `arith-loop`     | F            |                 0.610 |
          | `effect-stream`  | BOTH-UNBOUND |                  4.77 |
          | `typeclass-fold` | GAP          |                 0.104 |

      Shown only when a v2 backend is measured — those are the only numbers a silent fallback can
      misattribute — so the reference lane pays no extra process. Two bugs found while building it,
      both worth the note: `sscPath` resolves to `bin/ssc-tools`, whose `info` is the ARTIFACT
      inspector and has no `--front-report`; and `corpusFiles` is a single-use Iterator already
      exhausted by table time, so the first version silently produced an empty map and the column
      vanished rather than erroring.
      *(original note)* print WHICH FRONT compiled each row in the bench table. The highest-value apparatus
      fix available: the fallback is silent by design, so a perf number can be of the wrong compiler
      and nothing looks wrong. Cost me two wrong conclusions in one sitting. One column, one
      `--front-report`-equivalent call per row.
- [x] **P-4 DONE (landed 97d8b71a0).** A JMH microbenchmark for v2 primitive dispatch. Unlocks everything below 2×, which
      this host currently cannot see at all. Without it the condy work stays permanently
      "unresolved" and no one can tell whether it was worth doing.
      *(claim `v2-perf-jmh-dispatch`, released level 3)* JMH infrastructure ALREADY EXISTED —
      `interpreterBench` (`v1/runtime/backend/interpreter-bench`) has 4 `@Benchmark` classes and the
      plugin enabled; it simply did not depend on `v2Core`, so `ssc.Prims`/`ssc.Value` were not on
      its classpath. One `dependsOn` line, not a new project.
      `V2DispatchBench` measures FIVE layers so the DIFFERENCES name each seam rather than one
      opaque total: `jvmAdd` (floor) → `boxedAdd` (Value boxing) → `arithFast` (the typed path the
      bytecode lane emits) → `preResolvedFn` (adds the `List[Value]` alloc + closure call) →
      `resolvePerCall` (adds the string-keyed lookup). `preResolvedFn − arithFast` is the calling
      convention's price; `resolvePerCall − preResolvedFn` is the lookup's.
      `@Param(cached)` is NOT a sensitivity knob: `Value.IntV` interns −128..4096, so a benchmark
      written only with small ints measures a program in which boxing is free — which nobody runs.
      ⚠️ COST NOTE: a cold `Jmh/compile` in a fresh worktree builds ~290 Scala sources of the
      dependency chain and exceeded 30 min on a contended host (rc=124). Not a hang — budget an hour
      for the first run, or run it in a worktree that already has warm `target/`.

      **RESULT (2026-07-31, JMH avgt, 3 warmup / 5 measurement iters, 1 fork):**

      | benchmark        | cached=true | cached=false | adds over the layer above |
      | ---------------- | ----------- | ------------ | ------------------------- |
      | `jvmAdd`         | 0.345 ± 0.112 | 0.348 ± 0.115 | — (floor) |
      | `boxedAdd`       | 1.329 ± 0.052 | 1.378 ± 0.018 | +0.98 — Value boxing |
      | `arithFast`      | 2.620 ± 0.023 | 2.398 ± 0.070 | +1.29 — typed arith |
      | `preResolvedFn`  | 3.979 ± 0.035 | 3.744 ± 0.066 | +1.36 — `List[Value]` + closure call |
      | `resolvePerCall` | 10.437 ± 0.096 | 10.249 ± 0.114 | +6.46 — string-keyed lookup |
      | `arithFastLoop`  | 2.532 ± 0.026 | 2.336 ± 0.020 | ≈ `arithFast` |

      Error bars are ±0.02–0.11 ns. The apparatus problem is SOLVED: effects far below 2× are now
      resolvable, where the whole-workload harness on this host swings 2.5× on identical code.

      **The largest number is NOT the hot path, and checking that was the point.**
      `resolvePerCall` (10.4 ns) would say the string lookup is 62% of dispatch. It is not:
      `Runtime.scala:1022` does `val fn = Prims.resolve(op)` OUTSIDE the returned closure, so the
      lookup is paid ONCE at compile time. Reporting that 62% would have been a wrong conclusion
      drawn from a correct measurement. The hot path is `preResolvedFn`.

      **What the numbers actually say:**
      - dispatch costs **3.98 ns against a 0.345 ns floor — 11.5×** — and only ~1 ns of that is
        `Value` boxing, which is the tier work already attacks;
      - `arithFastLoop ≈ arithFast` is a NEGATIVE CONTROL: no `DontCompileHugeMethods` cliff on this
        path, unlike `Prims.__method__` (see [[feedback_hugemethodlimit_silent_no_jit]]);
      - `cached` moves everything by ≤0.22 ns and not consistently in one direction, so `Value.IntV`'s
        intern table is not the lever here either way.

- [x] **P-5 — REFUTED 2026-07-31, and it would have been a REGRESSION.** ~~put `__arith__` in `resolve3`~~ (found by P-4, not guessed). `Prims.resolve2` already
      carries 25 arity-2 typed ops (`i.add`, `f.add`, …) that reach `fn2(l, r)` with NO list.
      `resolve3` carries only 4 (`sslice`, `map.put`, `arr.set`, `bslice`) — `__arith__` is not among
      them, so every UNTYPED arithmetic op takes 3 args down the generic path and allocates a
      `List[Value]` per call (built reversed, then `.reverse` — 2N conses). That is the +1.36 ns
      layer. Predicted gain **≈1.1–1.3 ns/op, ~30% of untyped arith dispatch**; `V2DispatchBench`
      can now confirm or refute it, which before P-4 it could not.
      Still matters after F5b: `__arith__` is what runs for anything F cannot type, for the legacy
      front, and for `+` on strings.
      ⚠️ touches `v2/src/Runtime.scala`, the SHARED kernel — needs its own claim and its own A/B.

      **THE PREMISE WAS WRONG. `__arith__` already avoids the list, by a better mechanism than the
      one I proposed, and my change would have made it SLOWER.**

      `Runtime.scala:1002`, inside compile's `case None` branch — i.e. reached precisely BECAUSE
      `resolve3` returns `None` for `__arith__` — there is already:

          if op == "__arith__" then a0 match
            case Lit(Const.CStr(fixedOp)) => … Prims.arithFast(fixedOp, value1, value2)

      The op string is resolved ONCE at compile time and captured. No list, no `Fn3` indirection, no
      per-call `StrV` match. My `resolve3` entry would have been consulted FIRST and hijacked that
      case, replacing a compile-time constant with a per-call pattern match.

      And the literal case is the only one that occurs: **both** emitters produce a literal op —
      F (`(prim __arith__ (lit (str "+")) L R)`, `specs/v2.2-p6.5-fsub.ssc:110`) and the legacy
      lowerer (`IrPrim("__arith__", Cons(IrLit(IrStr("+")), …))`, `v2/lib/ssc1-lower.ssc0:2736`).
      The generic list path for `__arith__` is dead code in practice.

      **So the corrected number: untyped arithmetic already costs the `arithFast` layer, 2.620 ns —
      not `preResolvedFn`'s 3.979 ns.** The 1.36 ns I proposed to remove was already absent. P-4's
      measurements were right; the inference I drew from them was not.

      **How the error happened, because it is a repeat.** I read `resolve3`'s table, saw four ops
      and no `__arith__`, and concluded the generic path was used — without reading the branch that
      runs when `resolve3` says `None`. That is the "there is probably a SECOND COPY of this thing"
      trap, on the same day it was written down. A table of what a lookup DOES contain does not tell
      you what happens when it misses.

      Cost: one reverted edit, no build, no landed regression. The catch came from grepping for the
      symbol in the file I was about to change, before changing it.

**Explicitly NOT on the P-list, and why:**
- the collection/closure cluster — now has its own plan below (`C-0`…`C-5`) rather than a one-line
  deferral; it is a programme, not a slice, and the plan says so;
- anything under 2× measured on whole workloads — see the first constraint; do P-4 first;
- adding a declared type to F's `knownTyName` — proven to make F silently DECLINE programs it used
  to accept (`v2/BUGS.md`).

## v2-perf-unboxed-cell-only-for-literal-init — 18×, the entry test is too narrow (2026-07-31)

Claim `v2-perf-var-cell-widen`. Entry: `BACKLOG.md`. Measured: a loop whose `var` is initialised
from an expression runs at **104.7 ns/iter** against **5.67** for the identical loop with a literal
initialiser — the unboxed `lcell`/`dcell` tier only fires on a syntactic literal.

- [x] **VC-1 — the corpus row FIRST. DONE** (`bench/corpus/var-expr-init.ssc`, BEFORE reading
      ssc 2.43 / v2-bytecode 82.6 = **34×**; `arith-loop` control 2.2×). `bench/corpus/var-expr-init.ssc`: the same loop with the var
      initialised from `seed`. Without it the win is invisible and the corpus keeps measuring only
      the case that already works, which is exactly how this hid.
- [x] **VC-2 — SUPERSEDED by the IR reading, before any code was written.** The planned widening
      ("accept `(prim i.…)`") would have been **INERT on the very row VC-1 added**. `SSC_DUMP_IR`
      shows F emits **no typed arithmetic at all** for this program:

          var s = (seed % N) + 1L   ->  cell.new( __arith__("+", __arith__("%", Local(0), Lit), Lit) )
          var i = 0                 ->  lcell.new( Lit(CInt(0)) )
          every operation            ->  __arith__ ;  zero i.* anywhere

      `__arith__` is genuinely untyped — it serves `+` on strings too — so it is NOT a provably-Int
      form and must not be accepted. There is no safe syntactic widening for this shape.

      *Why `arith-loop` is fast anyway, and it confirms the diagnosis:* `JvmByteGen.canLong` accepts
      `lcell.get(Local)` and int literals, so `__arith__` over an **lcell** still takes the Long fast
      path. It does **not** accept `cell.get`. So the CELL CHOICE decides whether the arithmetic can
      be fast — the boxed cell locks the whole loop out of the fast path.

- [x] **VC-2 LANDED (2026-07-31) — 2.03×, ranges disjoint (108.5/101.9/96.3 -> 49.2/50.2/51.9).**
      Two lines: `isIntLitCode`/`isFloatLitCode` now also accept `(prim i.`/`(prim f.`, F's TYPED IR.
      Proven live by IR dump: the same program went from `cell.new/get/set` to ALL `lcell.*`.
      Original note kept below.
- [x] **VC-2 REINSTATED — my supersede was wrong, and the probe is why.** I concluded "no safe
      syntactic widening exists" from a probe whose parameter was `Long`. With `Int` the picture is
      the opposite:

          def wl(seed: Int) …  var s = (seed % 46341) + 1
            initialiser  ->  Prim(i.add){ Prim(i.mod){ … } }   <- F DID type it
            s            ->  Prim(cell.new)                    <- and the cell test ignored it

      So `(prim i.…)` **is** emitted for Int-declared parameters, it **is** provably Int, and
      accepting it in `isIntLitCode` / `isIntLitExpr` is exactly the small safe widening originally
      planned. **It is inert for `Long`, not in general** — and that is a different gap, below.

- [~] **VC-2c — F does not type `Long` at all.** `knownTyName` (`specs/v2.2-p6.5-fsub.ssc`) is
      `Int | String | BigInt`. A parameter declared `Long` pushes a bare name, stays `"?"`, and
      every operation on it comes out untyped `__arith__`. **The bench corpus writes
      `def workload(seed: Long)`**, so every anti-fold row in the corpus is measuring the untyped
      path — which is also why my first probe looked hopeless.

      Adding `Long` to `knownTyName` is a one-line change with a wide blast radius (it turns on the
      typed regime for a type that has never had it), so it wants its own measurement and its own
      A/B, separately from VC-2.

**What the 18× is made of — measured, so the fix is aimed rather than guessed.** JFR allocation
profile, expression-init against literal-init:

    Value$IntV   381 samples  |  absent from the literal run's top 5   <- the boxing, per store
    Value[]      648          |  178
    Done         177          |  222   (compile phase in both)

`IntV` is the distinguishing allocation. So the fix must remove the BOX, not speed up dispatch —
which is what routing the var into `lcell` does, and it is why `canLong` accepting `lcell.get` but
not `cell.get` makes the cell choice decide the whole loop's speed.

- [ ] **VC-3 — the same treatment in the lowerer** (legacy front) once (a) or (b) is chosen.
- [ ] **VC-4 — prove LIVE, then measure.** Rename the emitted prim: the expression-init program MUST
      die and the literal one MUST keep working. Only then the alternating A/B, 3 rounds,
      `var-expr-init` + `arith-loop` as control.

⚠ **The risk that shapes VC-2/VC-3:** if the test says Int for something that is not, the var gets
an `lcell` and a non-Int store dies in `lcellAccum: non-Int result`. Fail-fast, but a correctness
regression. Accept only PROVABLY Int/Long forms — `(prim i.…)` qualifies because F emits it from
inferred type; "looks numeric" does not.

**Expected ~18× on affected loops. Disqualifying evidence:** if the widening does not move the
parameter-initialised probe, the cell is not the cost and the theory is wrong.

## v2 wide JIT — a run-time JIT for the VM lane, in v1's image (spec landed 2026-07-31)

Spec: [`../specs/v2-wide-jit.md`](../specs/v2-wide-jit.md) — read it before starting any `J-` slice;
this list is the queue, the spec is the contract (design, safety rules, gate definitions).

**The premise, in three facts.** (1) The VM lane is the DEFAULT (`bin/ssc run` → `RunNativeV2.runVm`)
and has had **no JIT at all** since `f5c-4` deleted FastCode/SelfRec on 2026-07-23. (2) The bytecode
lane is AOT and **all-or-nothing** — one `Unsupported` node anywhere and the whole program falls back
to the VM. (3) One dispatch on the VM lane costs **3.979 ns against a 0.345 ns floor** (P-4's
`V2DispatchBench`), and only ~1 ns of that is boxing.

**What makes it *wide*, and it is the one design idea worth remembering:** on this lane the
interpreter form of every subterm is already a callable `Code` closure, so a term the emitter cannot
compile becomes a **call back into that closure**, not a bail. v1 asks "can I compile this function?"
and answers `null` for most real code (`specs/jit-universal-coverage.md` §2: 300 missed functions on
one engine, "silent (unobserved)" on the other two). v2 asks which *parts* it compiles. Function-level
coverage is therefore 100 % by construction; `Unsupported` becomes a cost, not a verdict.

Two more things fall out of v2's shape and are why this is not a 12,400-line port: `Emit.LamFn` is
already the interface generated lambda bodies implement and `Emit.clos` already wraps one as a VM
`ClosV`, so **installing a compiled unit is a field assignment**; and a `Lam` body's `Code` is built
once per SITE, so the hot counter can *be* that `Code` (`Code = Env => Step` is a SAM) — no
`IdentityHashMap`, no leak, no change to the trampoline or to any call site.

- [~] **J-0 — baseline + apparatus, before any code** (claim `v2-wide-jit-j0`, 2026-07-31).
      **Apparatus landed:** `V2JitSiteBench` (`v1/runtime/backend/interpreter-bench/.../`) prices the
      J-1 node against the **real** VM call path — it compiles a Core IR program through
      `ssc.Compiler` and calls the resulting `ClosV` through `Runtime.run`, varying only what sits
      between the call and the body: `vmCallDirect` (today), `vmCallCounting` (J-1 tier-0),
      `vmCallInstalled` (J-1 steady state), `vmCallPlainField` (prices `@volatile` alone). Written
      as its own class, NOT a case in `V2DispatchBench`: that one's `@Param(cached)` fork is about
      `IntV` interning and does not apply here, so folding it in would double every run to vary a
      parameter that cannot move this number.
      **Design change it forced, before a line of J-1 was written:** the wrapper is installed only
      when the JIT is ARMED (decided once per site at program-compile time from `SSC_V2_JIT`), so
      with the JIT off the overhead is absent by construction rather than merely small. J-1's risk
      question changes from "does every program now pay" to "what does an armed site cost".
      **MEASURED (JMH, 5 wi / 10 i, 1 fork, 2026-07-31), and it settles three things:**

          vmCallDirect        9.469 ± 0.054   a whole VM call (arg array, trampoline, arithFast, IntV)
          vmCallDirectLoop    9.440 ± 0.067   negative control — no inlining cliff
          vmCallInstalled     9.819 ± 0.071   +0.350  steady state of a compiled site
          vmCallCounting      9.853 ± 0.059   +0.384  J-1 tier-0  (+4.1 %)
          vmCallCountingLoop  9.860 ± 0.089   +0.420
          vmCallPlainField    9.908 ± 0.108   +0.439  NON-volatile

      ① **J-1 is affordable: +0.384 ns, ~4 % of a call, ranges disjoint** — and 50× below what the
      whole-workload harness can see, which is why this bench had to exist at all.
      ② **`@volatile` is FREE — keep it.** The plain-field variant is *not faster* (9.908 vs 9.853,
      intervals overlap), so safe publication of the installed unit costs nothing measurable and the
      unsafe-publication fork closes before anyone opens it.
      ③ **The counter bump is free** — installed vs counting differ by 0.034 ns at ±0.06. The cost is
      the INDIRECTION, not the increment, so a cheaper counting scheme (sampling, every-Nth) buys
      nothing. Do not optimise it.
      ⚠ One monomorphic site, so the JVM inlines the wrapper: this is a **floor on the cost, not a
      ceiling**. J-1's own gate re-measures with the kernel wired.
      **Still open in J-0:** the four-row baseline below.
      *(original note)* Re-measure the four rows on today's main: the
      last recorded table (`pattern-match-heavy` v2 17.0 / `ssc` 0.059; `recursion-fib` 6.61 / 1.29;
      `recursion-tco` 0.275 / 0.031) is from 2026-07-10 and **predates the FastCode removal**, so it
      describes different code. Add a `jitSiteOverhead` case to `V2DispatchBench`. Record both in
      `specs/v2-wide-jit.md` §7 with the exact commands.
- [x] **J-1 — `JitSite` counters, NO compilation.** *(the `f-tilde-infix` lock on
      `v2/src/Runtime.scala` released, so this was unblocked and taken on the widened
      `v2-wide-jit-j0` claim rather than a second worktree — that one already had warm targets and a
      launcher built from the tree, which is what the gate needs.)*
      **Landed as `v2/src/Jit.scala` + four one-line call sites**, deliberately a new file: the
      kernel is the most contended file in the repo and every line added there is a future conflict
      for someone else.
      **Gate, and it can tell the two states apart** — which output alone cannot, since the
      interpreter prints the right answer either way:

          ssc run examples/hello.ssc                        -> "Hello, World!",  NO report line
          SSC_V2_JIT=on SSC_V2_JIT_STATS=1 …                -> "Hello, World!",  identical stdout
            stderr: ssc: jit tier-0 — 3082 sites armed, 676 reached the threshold (call 8 / loop 256)

      **3082 sites to compile hello-world, 676 of them hot** — because the F front itself runs on
      this VM, so the JIT's first customer is the compiler. That is a fact worth carrying into J-3:
      the win is not only in user loops.
      The report goes to **stderr, never stdout**: on stdout it would make the JIT-on run differ from
      JIT-off by construction and turn the parity gate into a lie (`BytecodeFallbackMarker` is on
      stderr for exactly this reason).
      **Correctness: `SSC_V2_JIT=on ./v2/conformance/check.sh` = 645 ok / 0 FAIL.** The ARMED config
      is the one worth running — with the JIT off the wrapper is not installed at all, so "off" is
      byte-identical to `origin/main` by construction, not by test.
      **Cost of arming, alternating A/B, 3 rounds, `recursion-tco` (ms/iter):**
      off 5.39 / 5.54 / 5.37 → on 5.68 / 5.69 / 5.68. Medians 5.39 → **5.68 = +5.4 %, disjoint**.
      Two independent apparatuses agree: JMH said +4.1 % per call on a synthetic site, the corpus
      says +5.4 % on a real call-heavy workload. That is the number J-3's win must beat before the
      default can flip.
      Wrap the `Lam` body `Code` at
      `Runtime.scala:652` (top-level defs), `:682` (`Lam`), `:738` (`LetRec`) and the `While` body at
      `:912`. Field never set; behaviour identical by construction.
      **Its own slice on purpose:** this is the one change that can slow down programs that never
      JIT, and it is exactly the size of effect this host's whole-workload harness cannot see (it
      swings 2.5× on identical code). Gate = JMH A/B vs `HEAD~1`, not a corpus row.
- [x] **J-2 — DONE except the ASM-isolation claim, which measurement KILLED.** `trait JitBackend` +
      by-name resolution in `v2/src/Jit.scala`; `v2/backend-jvm-bytecode/JitBytecodeBackend.scala`
      implements it (`onProgram` does the `Emit.globalsRef` bridge, `compileUnit` answers `null`
      until J-3); `RunNativeV2.runBytecode` calls `Jit.disarm()` so the two lanes can never both own
      that field.

          -verbose:class, bin/ssc run examples/hello.ssc          off    on
            ssc.jit.BytecodeJitBackend                              0      1     <- the seam is lazy
            library ASM (asm-9.7.jar) classes                      26     26     <- ALREADY LOADED
            ssc.bytecode.JvmByteGen classes                        56     56     <- ALREADY LOADED

      ⚠ **The gate as written in the spec could not have passed, and not because of my code:** the
      VM lane already loads ASM and the whole bytecode generator with the JIT off — the F front's own
      path reaches them. So "the JIT must not make the VM lane load ASM" was never the live invariant
      on this launcher. The one that IS live: `v2/src` names the backend only in a STRING, so the
      kernel-alone configuration still runs — `scala-cli run v2/src -- run-ir v2/conformance/fact.coreir`
      armed prints the same `120` and reports `backend none`. That is the run-ir / native-image case,
      now tested rather than assumed.
      ⚠ And how the wrong number nearly stood: my first probe grepped `org\.objectweb\.asm`, which
      also matches `jdk.internal.org.objectweb.asm` — the JVM's OWN bundled copy, in every Java
      process. It said "45 in both runs", which reads like a clean negative and was measuring
      something else entirely.
      *(original note)* Trait in `v2/src`, impl in
      `v2/backend-jvm-bytecode`, resolved BY NAME on the first hot site — `v21-plugin-backend-isolation`
      means the VM lane must not load ASM, and `RunNativeV2` already contorts a `catch` clause to
      avoid mentioning an ASM type. Point `Emit.globalsRef` at the VM's own globals `TrieMap` so both
      tiers share one namespace (including the `@`-cell globals both sides auto-create on first
      touch), and refuse to arm inside a `--bytecode` run, which RESETS that field.
      Flags are `SSC_V2_JIT*`, **not** `SSC_JIT*`: `bench/run.sc` sets `SSC_JIT_BACKEND` to select the
      v1 `ssc-asm` lane, so a shared name makes a bench row ambiguous about which JIT it measured.
      Gate: `-verbose:class` shows no `org.objectweb.asm` with `SSC_V2_JIT=off`.
- [x] **J-3 — DONE: units compile, `arith-loop` 120×, and the first run broke the program.**
      `JvmByteGen.emitUnit` compiles one `Lam` body to a class implementing `Emit$LamFn`, reusing
      `emitBody` + an extracted `drainPending` — the SAME emitter the AOT lane uses, so a shape
      either lane learns is learned by both. Refused outright, for correctness not difficulty: loop
      sites (J-6) and handler-dispatch roots (their unhandled-event probe is scoped by
      `Runtime.handlerClosure`; compiling one as an ordinary body drops it SILENTLY).

      ⚠ **The failure that taught the most.** First armed run: 61 units compiled, then
      `unbound global: sscNormSegs` and no output. **One process compiles SEVERAL programs** —
      `RunNativeV2` compiles the F tower (`:425`) then the user program (`:514`), each with its own
      globals map — while generated code resolves globals through the ONE static `Emit.globalsRef`.
      Bridging it once binds every unit to one program and kills the other. Fix: the map travels
      WITH the site (`JitSite.globals`), and a unit points the field at its own program before
      running. §3.6 had named this hazard as "two maps diverge" and still under-described it.

      ⚠ **And a correction to J-1's record: it wired 2 of the 4 sites, not 4.** The commit message
      and the entry above claimed three `Lam` points plus the `While` body; only the top-level-def
      and `Lam` cases were wrapped. `LetRec` and `While` are wired here. J-1's numbers stand but
      described a narrower population — armed sites 2222 → 3386.

      **Parity restored, and 722 of 722 hot sites compiled — no bails.**
      **Alternating A/B, 3 rounds, load 11.2 (ms/iter):**

          arith-loop           off 71.6/73.8/75.1   on 0.610/0.623/0.614   -> 120x
          pattern-match-heavy  off 90.4/75.4/79.6   on 38.4/32.4/30.8      -> 2.46x, disjoint
          recursion-fib        off 148.9/170.5/140.1 on 115.5/131.2/128.1  -> 1.16x, disjoint
          recursion-tco        off 6.16/7.76/6.25   on 6.05/6.08/5.34      -> NOT RESOLVED

      `recursion-tco` is called unresolved deliberately: ~3 %, edges overlap, and tier-0 arming
      alone costs 5.4 % — the compiled win and the arming tax cancel and this host cannot separate
      them. Against v1: `arith-loop` went from **301× off to 2.5× off**; the other three are still
      109–623× off.
      **Next lever, and it is a choice not a limit:** units compile without `selfGlobal`, so a
      recursive call goes `Emit.global` → `ClosV` → `Emit.app` instead of a direct self-invokestatic
      plus the unboxed `$long` entry. That is why `fib` barely moves. Backend-only change, but it
      alters rebinding semantics for a reassigned global, so it wants its own slice and gate.
      *(original note)* One `Lam` body → one hidden
      class (`Lookup.defineHiddenClass`, so a unit dies with its `LamFn`), boxed `Value` in/out, no
      residuals yet: unsupported ⇒ this site is not compiled. Gate = the parity gate below, plus
      ≥ 1 compiled unit on `recursion-fib`.
- [x] **J-3b DONE — self-calls: `fib` 111×, `tco` 212× and PAST v1.** `JitSite.selfName` (top-level
      defs only) + `emitUnit(body, selfName, arity)`. BOTH mechanisms are needed: `selfGlobal` gets
      the self-TAIL call (`Emit.rebind` + `GOTO`, no JVM frame), and registering the unit's own
      method in `defMethods` is what reaches the NON-TAIL one (`fib(n-1) + fib(n-2)`) — without it
      the call still went `Emit.global` → `ClosV` → `Emit.app`. With self-calls internal,
      `canParamLong` lifts the body onto the unboxed `$long(J…)J` entry.
      **The callee is frozen by this, so the name is VERIFIED, not assumed:** the def's `ClosV.code`
      IS the site, so identity comparison says whether the global still means this body. The AOT
      lane assumes it for every def; this checks.

          alternating A/B, 3 rounds, load 8.0 (ms/iter, medians)   off      on     vs v1 `ssc`
            arith-loop                                            74.6   0.611    2.5x off
            recursion-fib                                        142.5   1.28     1.09x off
            recursion-tco                                          5.88  0.0277   1.05x FASTER
            pattern-match-heavy                                   82.1  32.6      627x off

      `recursion-tco` is the first row where the v2 VM lane PASSES the v1 interpreter this programme
      set out to imitate — the self-tail `GOTO` loop does it.

- [x] **CENSUS — 36 rows: 131,578 armed, 37,324 hot, 37,317 compiled. 7 refusals = 0.019 %, ALL of
      them loop sites.** Zero handler-roots, zero `Unsupported`: **the emitter has no coverage
      failure.** This INVERTS the remaining order. J-4 exists to turn bails into partial
      compilation, which is v1's problem (300 missed functions on one engine, silent on two more);
      v2's JIT borrows the AOT emitter, already hardened by the whole-program bytecode work.
      **Next: J-6 (loop sites), then J-5 (type feedback); J-4 only if a real corpus grows an
      `Unsupported` histogram.** J-6 is doubly indicated — it is the only refusal class that exists,
      and `pattern-match-heavy`, the row J-3b did not move, is a `while` driving a `foreach`.

- [ ] **J-4 — residual callbacks (the wide step). DEFERRED by the census above, not by difficulty.** `Emit.residual(unitId, slot, env)` runs the
      interpreter `Code` for that subterm. **Non-tail positions only** — a residual runs its subterm
      to a `Value`, so one in tail position turns unbounded mutual tail recursion into JVM stack
      growth; if the tail position is unsupported, do not compile the unit (today's behaviour,
      localized to one site). Gate: residual histogram non-empty on a program J-3 refused, parity
      holds, and the revert-check — with residuals off, that program must go back to 0 units.
- [x] **J-3d DONE — the purity set. `pattern-match-heavy` 20.7 → 10.9; three rows now AT the AOT
      ceiling.** The inline `foreach`/`foldLeft` Cons-walks are gated on `pureNoEffect(body,
      g.pureDefs)`, and a JIT unit's `pureDefs` was EMPTY — so `area(s)` was not provably pure, the
      inline walk was declined, and every element paid a closure call. The emitter's own comment
      names this workload. The JIT reconstructs the set from what it can see (every top-level def is
      a `ClosV` whose `code` is the site holding its body) and runs the SAME fixpoint, extracted as
      `JvmByteGen.pureDefsOf`. Sharing it is not tidiness: a unit computing a DIFFERENT purity set
      would silently take a different path for the same program. Memoised on the globals map's
      identity — otherwise every per-site JIT event becomes a whole-program analysis.

          row                   J-0     JIT now    AOT     JIT/AOT   v1 `ssc`
          arith-loop           73.1     0.587     0.565    1.04x     0.243
          recursion-fib       137.8     1.16      1.15     1.01x     1.17
          recursion-tco         5.99    0.0241    0.0241   1.00x     0.029
          pattern-match-heavy  77.7    10.9       8.5      1.28x     0.052

      ⚠ **Read the last two columns together before planning more JIT work.** On
      `pattern-match-heavy` the v2 AOT lane is ITSELF 163x off v1, so closing the JIT's remaining
      1.28x leaves that row two orders of magnitude behind — the limit there is the EMITTER (v2
      bytecode boxes Doubles and allocates per match; v1's JIT has unboxed doubles + a monomorphic
      inline cache), not the JIT. That is a different programme, and it is where the one-walker
      decision pays: an emitter improvement lands in BOTH lanes at once.
      **For the other three rows there is nothing left for the JIT to win.**

- [ ] **J-5 — type feedback + unboxed entries + entry guards. Re-scope first:** three of four rows
      are already at the AOT ceiling, so type feedback can only help where the EMITTER is the limit
      — measure against AOT, not against `SSC_V2_JIT=off`, or the win will be misattributed. Per-parameter observed-tag profile
      recorded in tier 0; a parameter seen monomorphically as `IntV` gets the unboxed `(J…)J` entry
      `JvmByteGen.canParamLong` **already** emits, with the `INSTANCEOF IntV` guard it already emits.
      Guards are ENTRY-ONLY: nothing has been evaluated, so a miss cannot duplicate a side effect.
      This closes from the runtime side what VC-2c cannot close from the front side — F types only
      `Int | String | BigInt`, the corpus writes `Long`, and widening `knownTyName` made F silently
      DECLINE programs. Gate: `var-expr-init` + `arith-loop`, and a `GuardMiss` counter that proves
      the guard is live (rename-the-prim probe, same discipline as VC-4).
- [x] **MEASURED BEFORE BUILDING J-6, and it refuted the premise.** Compared every row against the
      SAME emitter running AOT (`--backend v2-bytecode`):

          row                   AOT      JIT     ratio   cross-def call in hot path?
          arith-loop           0.565    0.578    1.02x   no
          recursion-fib        1.15     1.17     1.02x   no (self only)
          recursion-tco        0.0241   0.0265   1.10x   no (self only)
          pattern-match-heavy  8.5     32.0      3.7x    YES — area(s) in the foreach lambda

      **Three rows are AT AOT PARITY. The only row that is not is the only one calling another def.**
      So per-site compilation loses nothing in general, and the LOOP DRIVER IS NOT THE PROBLEM —
      `arith-loop`'s loop lives inside a compiled def body and already runs at AOT speed. J-6 would
      not have moved `pattern-match-heavy`. One seam remains: a unit registers only its OWN name in
      `defMethods`, so every call to another def takes the generic `Emit.global` → `ClosV` →
      `Emit.app` path (lookup + dispatch + env alloc per call) where AOT emits `invokestatic`.

- [x] **J-3c DONE — cross-unit linking. `pattern-match-heavy` 32.6 → 20.7; three rows now within
      1.06–1.15× of the AOT lane.** A unit carries a static `callees: LamFn[]`; a linked
      `App(Global(g), args)` compiles to `GETSTATIC` + interface call + `unroll`. Cheap because a
      top-level def's closure env is EMPTY, so its `LamFn` takes exactly the argument array.
      TAIL position emits `Emit.bounce` instead — running the callee to completion there would trade
      the trampoline's constant stack for JVM frames. Linking freezes the callee, so it is verified
      the same way as the self-call.

          row                   off      on      AOT    JIT/AOT   vs v1 `ssc`
          arith-loop           74.2    0.603    0.565   1.07x     2.5x off
          recursion-fib       140.6    1.22     1.15    1.06x     1.04x off
          recursion-tco         6.23   0.0277   0.0241  1.15x     FASTER than v1
          pattern-match-heavy  78.7   20.7      8.5     2.4x      398x off (was 627x)

      NOT done on purpose: on-demand compilation of a COLD callee — it turns one JIT event into a
      call-graph walk, and the hit rate is worth measuring first (stats line now reports the link
      count: 1121 on a hello.ssc compile).
      ⚠ **One outlier, reported not averaged away:** a `recursion-fib` ON run came back 9.72 ms
      against 1.16–1.22. A follow-up 8-run sample was 1.18–1.28 with NO second mode, and the same
      batch spiked 2.4x on the OFF arm too, so it reads as contention. Written down because a
      bimodal `recursionFib` is a documented failure class here (`specs/wide-jit-typed-input.md`)
      and "probably noise" is how it would first look.
      **Next measurement, not next guess:** `pattern-match-heavy` is the only row short of its own
      emitter's AOT result (2.4x). Candidates: callers compiled before their callees (unlinked), and
      the AOT lane's `pureDefs` fixpoint enabling an inline-`foreach` path a single unit cannot know.

- [x] **J-3c ORIGINAL PLAN (superseded by the entry above).** A top-level def's closure env
      is empty, so its unit's `LamFn` takes exactly the argument array: the call is `fn.call(args)` +
      `Emit.unroll` — no lookup, no `ClosV`, no `Emit.app`. Give the unit a static table of callee
      `LamFn`s, filled after `defineClass` from callees already compiled, and emit `GETSTATIC` +
      `INVOKEINTERFACE` for a linked `App(Global(g), args)`. Same freezing question as the self-call,
      same answer: link only where the global still resolves to that body.

- [ ] **J-6 — loop back-edge sites. DEPRIORITISED by the measurement above.** A `While` body never re-enters the trampoline's `Call` bounce
      (`Runtime.scala:912` runs a plain Java `while`), so a call counter alone never sees a hot loop —
      v1 hit this and needed eager compilation for self-tail-recursive functions plus `WhileJitEntry`.
      Here it is the same `JitSite` with a back-edge threshold, and installation is the same field
      flip, so there is no OSR machinery: the next iteration reads the new field.
      Rows: `arith-loop`, `range-sum`, `nested-loop`.
- [ ] **J-7 — effect-aware units.** `OpAnfNative.lift`'s purity registry is a least fixpoint over the
      whole call graph and cannot be recomputed per site; `Compiler.compileWithGlobals` already knows
      every `Def`, so compute it once and hand it to the JIT. Gate: `effects`, `effects-handler`,
      `algebraic-effects`, `generators`, `async-demo` byte-identical + `./v2/conformance/check.sh`.
- [ ] **J-8 — `SSC_V2_JIT_STATS=1` (dynamic) + `ssc lint-jit` with `-v2` / `-v1` (static).** v1's
      `JitBailReason`/`JitMissStats`/`ssc lint-jit` is the part worth copying wholesale; the
      alternative is JFR archaeology. In v2 the vocabulary names residuals rather than failures:
      `Residual(termClass)`, `GuardMiss`, `TailUnsupported`, `SizeLimit`, `Budget` — and BOTH views
      print it, which is the unification v1's Stage 1 set out to retrofit across three engines and
      never finished. **The v2 diagnostic is a flag on the EXISTING command, not a new one**
      (`v1/tools/cli/.../LintJitCmd.scala`); `ssc check-jit-coverage` is a Stage-1 proposal that was
      never built, so do not go looking for it.
      **`-v2` is the DEFAULT** (Sergiy, 2026-07-31), `-v1` selects today's v1-interpreter report;
      mutually exclusive, both given is an error, and `--backend javac|asm|both` belongs to `-v1`
      only — with `-v2` it is an error, not a silent no-op (v2 has one backend). This **flips the
      default of a shipped command**, so it goes in `summary`, `details` and the release note: a
      diagnostic that quietly re-points at a different lane is the same class of defect as a
      fallback that does not announce itself. Six other commands spell the flag `--v2`/`--v1`
      (`Main.scala:1294` …) — the single-dash form here is a deliberate call, not an oversight.
- [x] **J-8 DONE — `ssc lint-jit` with `-v2` (default) / `-v1`, plus the compile-time counter.**
      Verdicts come from the SAME `JvmByteGen.emitUnit` the JIT calls with the same purity set, so
      `compiles` means it compiles at run time; and it does NOT run the program, unlike `-v1` which
      executes the module to read `interp.globals`. `--backend` is an error with `-v2`.
      ⚠ Known gap: `--backend asm` (space form) is eaten by the GLOBAL CLI parser before the command
      sees it, so the guard fires only on `--backend=asm`. Pre-existing, recorded not fixed.

- [x] **J-9 DONE — compilation moved off the critical path; default stays OFF, by arithmetic.**
      Background compile on one min-priority daemon thread (`SSC_V2_JIT_SYNC=1` forces sync so the
      A/B is possible). Wall median 1.69 → 1.59 s (−6 %) with **CPU unchanged** 7.00 → 6.96 — the
      work is MOVED, not removed. Ranges overlap, so: consistent with, not proof.
      Tower exclusion (`SSC_V2_JIT_TOWER=on` to restore): sites 3398 → 1907, units 725 → 276,
      compile 198 → 118 ms, rows unchanged. **The tower was 40 % of the cost, not all of it** — the
      remaining 276 units on a one-line program are the PRELUDE.
      **Why the default stays off:** a run that never gets hot pays ~118 ms CPU + the 5.4 % arming
      tax for a tier-up it cannot amortise, and that residue is structural. The two fixes that would
      close it — defer compilation until a program has run N ms, and a code cache amortising units
      ACROSS runs — are each larger than this slice, and are now specified by numbers.
      *(original)* **The blocking number now EXISTS and says: not yet.** A hello-world
      run spends **~187 ms compiling** (189/183/190) in a ~2.6 s total — invisible to wall-clock,
      which swings ±0.5 s (off 2.53–3.05 vs on 2.36–4.43, fully overlapping). Threshold sweep says
      the threshold is the WRONG lever: 8 → 2048 drops units 722 → 181 (−75 %) but cost only
      201 → 119 ms (−41 %), because the expensive units are the genuinely hot ones — and the win is
      unchanged (`fib` 1.21 at both ends). **The lever is moving compilation off the critical path
      (background thread), not tuning the threshold.** That is J-9's brief.
      *(original)* default-on decision, with the measured evidence, or a recorded reason to stay opt-in.

⚠ **The gate rule that decides whether any of this is provable.** An output gate is green BOTH ways:
the interpreter prints the right answer, so `SSC_V2_JIT=off` and on agree whether or not a single
unit compiled. Every parity run must therefore also assert `jit-report` shows ≥ 1 compiled unit —
without that clause it is `bc-parity-sweep` comparing a program against itself, which this repo has
already shipped once (`BUGS.md scljet-jdbc-facade-bytecode-class-too-large`).

**Explicitly out of scope:** replacing the `--bytecode` / `v2-jvm` / `v2-rust` routes or the route
policy in `specs/v2-vm-production-jit-gate.md`; a second Core IR walker (v1's two independent walkers
are the documented cause of its fragmented coverage — there will be exactly one, `JvmByteGen`,
extended); a portable register VM (parked for a non-JVM host); mid-body deoptimization.

## Done

- [x] **`getOrElse(k, default)` on a receiver that is not a bare variable** (2026-07-31, legacy
      front; F still open). Both fronts define one `_sel_getOrElse` and it is `lam 2` — the OPTION
      shape — so a Map's two-argument call arrived with three and died `arity: 2 expected, 3 given`.
      It survived because the lowerer tracks map VARIABLES: `m.getOrElse(k,d)` worked and
      `b.m.getOrElse(k,d)` did not. Same class as K62.32's `_sel_mkString`, whose remedy was never
      applied here. Gate `tests/conformance/map-getorelse-expr-receiver.ssc` (golden from the JVM
      oracle), `v2` frozen as a declared red until F is fixed. The rest of the class is SWEPT — 26
      helpers statically, then 39 probes x 5 lanes — and clean. `560ce09e9`, `f2e64fd69`.
- [x] **an object's `var` member is not scoped to the object** — the native half is filed, not
      fixed: `v2-object-var-member-resolves-to-a-top-level-global`. A member name that also exists
      at the top level resolves to the top-level one, so `Other.value()` answers `102` when
      `Other.n` is `50`; WITHOUT a collision it is correct, which is why it survived. Gate
      `tests/conformance/object-var-member-scope.ssc`, `8f161094f`; INT half fixed in `b8a41142a`.

- [x] Core IR **frozen v1** + `12-ir-format` + `15-ssc0` + `conformance/*.coreir` (K0,
      2026-06-25).
- [x] **runtime compiler `v2/ssc`** (2026-06-26) — one Scala 3 binary, `src/`: CoreIR
      (ir + reader/writer), Runtime (**compile-to-closures** VM + trampoline TCO + δ), Ssc0
      (lexer/parser/lower), Main (CLI `run`/`compile`/`run-ir`) + `./ssc` launcher. All
      modes green via `conformance/check.sh` (ssc0 examples + ir fixtures + `ssc0→ir` map-def
      reproduction; `tco` 1e6 deep in constant stack). Renamed `ssc2/ → v2/`. (Fused the
      previously-separate K1 VM and K-seed front into one binary.)

## Pending (K2 — grow the tower)

- [x] **ssct — the typed layer** (2026-06-27) — `lib/ssct.ssc0` (136 lines): a typed lambda
      calculus with `infer` (synthesis-only type checker) + erased `evalTerm`, **written in
      ssc0** (D1: types as an outer library, kernel stays untyped). `check` = type-check then
      run. Spec `40-typer-as-library.md`.
- [x] **ssct textual surface** (2026-06-27) — `lib/ssct-front.ssc0` (170 lines): a real
      **lexer + parser written in ssc0** for `.ssct` text → `Term`. Driver `bin/ssct.ssc0` +
      `v2/ssct` launcher: `./ssct examples/id.ssct` ⟶ text→lex→parse→typecheck→run, all ssc0.
      Examples id/cond (`Typed(...)`) + bad (`TypeError`) + conformance. **Kernel byte-for-byte
      unchanged** (still 851 lines). Deferred: erase-to-ir via `coreir.encode`, HM/unification.
- [x] **erase-to-ir + coreir.encode** (2026-06-27) — closes the loop `.ssct → ir → run-ir`.
      Kernel +`coreir.encode` prim (`IrEncode`: IR-as-Data tree → canonical bytecode; the ONE
      place the kernel grew, +~60 LOC → 911) + Main skips printing `Unit`. `lib/ssct-emit.ssc0`
      (~25 ssc0): `erase` (de Bruijn + drop types) + `emit`. `bin/ssctc.ssc0` + `v2/ssctc`
      launcher. `./ssctc id.ssct | ./ssc run-ir` ⟶ 42; conformance asserts exact bytecode +
      run-ir result. The typed program now runs on the real VM.
- [x] **delta-widen** (2026-06-26) — full `δ`: `big.*`, `f.*` + numeric conversions, string
      group (UTF-16 units), bytes, data reflection (`tagOf`/`arity`/`fieldAt`),
      `map.*`/`arr.*`/`cell.*` (Foreign mutable), I/O (`readFile`/`writeFile`/`env`/`exit`).
      +103 LOC (722→825). Examples greet/bigfact/mapdemo + conformance. Lexer fix: `#i->big`
      prim names. Still deferred: `coreir.encode/decode` (with self-hosting), `mathx.*`.
- [x] **ssc0-imports** (2026-06-26) — `import "path"` (flat global namespace) via `Loader`:
      relative resolution, load-once / cycle-safe, duplicate-def-name error. `lib/list.ssc0`
      + `examples/uselib.ssc0` (sum(range(100))=4950) + conformance.
- [x] **stdlib + interpreter** (2026-06-26) — `lib/list.ssc0` (foldl/foldr/map/filter/append/
      reverse/length/sum/head/range), `lib/option.ssc0`; `examples/pipeline.ssc0`
      (sum∘map∘filter∘range = 120) + `examples/calc.ssc0` — a real expression-language
      interpreter in ~20 lines of ssc0 (ADTs, match, env, let → 42). Lexer: `;` now an
      optional separator. Demonstrates the thesis: rich behaviour = small ssc0 on a tiny kernel.
- [x] **self-hosting — FIXPOINT REACHED** (2026-06-27) — `lib/ssc0c.ssc0`: the ssc0 compiler
      written in ssc0 (lex+parse+lower+emit). Differential invariant `ssc0c X == ssc compile X`
      holds byte-for-byte: M1 (fact/tco) + M2 (match/ctor/let/letrec/str → map/calc).
      **M4: `examples/ssc0c-self.ssc0` (lib + main), compiled by the Scala front then run on its
      OWN source, reproduces itself byte-for-byte (gen1==gen2==gen3, 20413 bytes) — a stable
      self-hosting fixpoint.** `bin/ssc0c.ssc0` + `v2/ssc0c` launcher (-Xss512m for deep non-tail
      recursion) + conformance + spec `20-bootstrap.md`. **Kernel: +0 lines (still 913).** Left:
      M3 (ssc0c `import` resolution → multi-file self-compile).

## K3 — ssc 1.0 feature parity (all libraries/elaborations on the frozen kernel)

- [x] **algebraic effects + handlers** (2026-06-27) — `lib/effects.ssc0` (pure/perform/bind +
      handlers). State (one-shot) + nondeterminism (**multi-shot** continuations) examples +
      conformance + spec `50-effects.md`. Kernel +0. Effects = data + closures, no kernel node.
- [x] **async / cooperative concurrency** (2026-06-27) — `lib/async.ssc0`: `yield`/`fork`/`log`
      ops + a round-robin scheduler handler on `lib/effects.ssc0`. Demos: async-tasks (two tasks
      interleaved → 1,10,2,20,3) + async-fork (spawn → 1,2,100,3,200). Spec `51-async.md`.
      Kernel +0 — concurrency is a library. NEXT in async: await/futures/channels/mailboxes.
- [x] **typeclasses** (2026-06-27) — (a) `lib/typeclass.ssc0`: standalone type-directed resolution
      + dict passing, incl. conditional instances `Show a => Show (List a)`. (b) **Integrated into
      the `ssct` typer**: `show e` use site is instance-agnostic; `infer` enforces the `Show`
      constraint (rejects `show` on a function) and `elaborate` resolves+inserts the dict from the
      *inferred* type (`ShowM` → `ShowDispatch(instFor(typeOf e), …)`). Examples tc-show-int/bool
      (`Typed("String", …)`) + tc-show-err (`TypeError`). Specs `52`. Kernel +0. NEXT: multi-method
      classes (Eq/Num/Ord) = multi-field dicts; polymorphism (type vars) for real generic constraints.
- [x] **actors** (2026-06-27) — `lib/actors.ssc0`: behavior `(state, msg) -> (state', [Msg])`
      + a delivery loop (route by id, per-actor state, enqueue outputs). Demo: ping-pong
      bounce → Ball 0..5. Spec `53-actors.md`. Kernel +0. NEXT: concurrent actors with blocking
      `receive` on the async scheduler; supervision; wire protocol via `coreir.encode`.
- [x] `do`-notation sugar + typed effect rows — **DONE in the ssct-hm surface** (`doE { x <- m ; … }`
      + `Comp {Eff} a` row syntax, K10/K11; row INFERENCE incl. effect-polymorphic HOFs in K41). The original
      lower `ssct` annotated layer is superseded by ssct-hm for these.

## K4 — backends (ir → target, each an ssc0 program; "one source, many targets")

- [x] **backends are multi-file** (2026-06-27) — `lib/loader.ssc0` (shared DFS import loader)
      wired into the JS + Rust drivers, so stdlib-importing programs compile to every target.
      `examples/quicksort-lib.ssc0` (imports `lib/list`) runs identically on VM / JS / Rust.
      Plus `examples/quicksort.ssc0` (self-contained) — a real algorithm on all 3. Kernel +0.

- [x] **backend: ir → JS** (2026-06-27) — `lib/backend-js.ssc0` (reuses ssc0c front; walks IR
      → JS). **Now TCO-correct**: tail-aware codegen (`genE` with a tail flag) emits `bounce(f,a)`
      for a tail `IrApp`; `app` trampolines in a `while`. `./ssc0-js f.ssc0 | node` == VM for
      fact/map/calc **and tco** (1e6 in constant stack). Spec `60-backend-js.md`. Kernel +0.
- [x] **backend: ir → Rust** (2026-06-27) — `lib/backend-rust.ssc0`: emit Rust over a dynamic
      `V` enum + `Rc<dyn Fn>` closures. **Now TCO-correct**: closures return `Step=Val|Bounce`,
      tail `IrApp`→`Step::Bounce`, `app` loops (genV/genT split). `./ssc0-rust f.ssc0 | rustc` →
      native binary; output == VM for fact/map/calc **and tco** (1e6, constant stack). Spec
      `61-backend-rust.md`. Kernel +0. **3 targets, all TCO-correct: JVM / JS / native Rust.**
- [x] **ssc0c multi-file imports (M3)** (2026-06-27) — `bin/ssc0c.ssc0` resolves `import "path"`
      (DFS, load-once loader; `parseImports` + `parseTop` skips imports). `uselib.ssc0` (imports
      `lib/list.ssc0`) compiles byte-identically to Scala; **multi-file fixpoint**: `bin/ssc0c.ssc0`
      compiles itself across the import, reproduces itself (22533 bytes). Self-hosting spans files.
## Backlog

- [~] bare-`#prim` η-expansion **DONE (K38)** + `v2-bin` compact binary ir **DONE (K40)**. Only **Array-env
      for speed** (a VM perf optimization, not a feature gap) remains here.
- [x] `mathx.*` transcendental floats — **DONE in ssct-hm (K33)**: `exp`/`ln`/`sin`/`cos`/`tan`/
      `pow`/`sqrt`/`pi` as a pure prelude (Taylor/Maclaurin series over `+ - * /` + the kernel's
      `fsqrt`/`fneg`; `ln` range-reduced by `e` so it's accurate for all `x>0`). 0 kernel change,
      0 backend change. Bit-identical across run-ir/JS/Rust (all IEEE-754 doubles, same op order).
      structural map keys **DONE (K37, lib/mapx.ssc0)**; `hash.sha256` **DONE (K36, lib/sha256.ssc0)**.
      (`10-core-ir.md §8`.)
- [x] K3 non-WASM breadth **DONE through K45**: stdlib, HM typed surface, effects/actors/async
      as libraries, and VM/JS/native-Rust backends as ssc-compiled programs.
- [x] **backend: ir → WASM** — ✅ DONE 2026-07-05: `rustup` is now present;
      `wasm32-wasip1` target installed, Rust backend reused as planned. `v2/ssc0-wasm`
      launcher + `scripts/run-wasi.mjs` (Node built-in WASI host — no wasmtime needed).
      quicksort byte-identical to VM; tco = 1e6 tail calls constant-stack; Mira programs
      work via the same target. Toolchain-gated checks in `conformance/check.sh`.

## K5 — Mira (ssct-hm): a Hindley-Milner typed language (DONE 2026-06-27)

- [x] **ssct-hm** — a complete HM-inferred typed FP language in ssc0 (`lib/ssct-hm*.ssc0`):
      Algorithm-W inference + let-polymorphism; Int/Bool/String; polymorphic lists `[a]` with
      literals; **user `data` types + pattern matching** (`match { | }`); full arith/cmp/string
      ops. Source text → infer → interpret OR erase → Core IR → **VM / JS / native Rust**.
      Showcases: factorial, map, quicksort (dups), a typed expression interpreter — all compile
      to native code. Spec `41-mira.md`. 161 conformance checks. Kernel +0 (still 913).

## K6 — Mira (ssct-hm): feature parity backlog (plan)

Toolchain confirmed: kernel has the full **`f.*`** float group + **`i->f`/`str->f`**, and
**reflection** (`tagOf`/`arity`/`fieldAt`) — so generic `show`/`eq`/`compare` are expressible.

- [x] **Float** (K6.1) — `TyFloat` + float literals (`3.14`) + float ops (`+. -. *. /.`, `<.` etc.) +
      `toFloat`/`floor`; add `f.*`/`i->f` to the JS + Rust genPrims. All 3 backends.
- [x] **Tuples** (K6.2) — `(a, b)` (n-ary) + projections; `TyTup [t]`; erase → IrCtor("Tuple", …).
- [x] **Records** (K6.5) — `{x = e, y = e}` + field access `r.x`; closed structural record types.
- [x] **Polymorphic `show` (Show typeclass)** (K6.3) — one generic structural renderer via `tagOf`/`arity`/`fieldAt`
      (+ those prims in JS/Rust gen); `show` works on any type, same output everywhere.
- [x] **Polymorphic `eq`/`compare` (Eq/Ord)** (K6.4) — generic structural equality + ordering via reflection;
      makes equality/ordering work on any type, consistent across VM/JS/Rust.
- [x] **User typeclasses** (K6.12) — `method m` + `instance m T = impl`, resolved monomorphically by the
      argument's type-head. (Full dictionary-passing with constraints-in-the-type is a deferred research
      item, same class as effect-rows below.)
- [x] **More surface features** (K6.13–K6.28, this batch) — boolean `&& || not`; string ops `strLen/charAt/
      substr`; currying (`fun x y =>`, `let f x y =`); pattern matching: wildcard `_`, variable catch-all,
      Int/String/Bool literals, **nested patterns** (backtracking compiler) + list `Cons`/`Nil`; `//`
      comments; **monadic do-notation**; **type ascription** `(e : T)`; a 32-function auto-injected prelude.
      Conformance 161 → 277, all 3 backends.

## K7 — Typed algebraic effects in the Mira surface

Bring ssc 1.0's signature feature — algebraic effects + handlers (one-shot AND multi-shot) — into the
TYPED surface, all 3 backends. The untyped library (`lib/effects.ssc0`, `Comp = Pure | Op(label,arg,
resume)`) already proves the mechanism on the VM. The blocker is that `Op`'s `arg`/`resume`-reply are
existential. Two complementary tracks (chosen design; full effect-row inference deferred as research):

Track P — **type-safe per-effect free monads** (no `Dyn`, no existentials): each effect is a free monad
over its own functor = a plain user `data` type with **function-typed fields**.
- [x] **P1. function types in data fields** — `data F a = Op (Int -> F a) | Ret a`; `parseFieldType`
      accepts `->` (right-assoc, `TyFun`). Function-in-ctor-field already runs at runtime; this enables it
      at the type level. All 3 backends.
- [x] **P2. State effect (one-shot)** — `data StateF a = Ret a | Get (Int -> StateF a) | Put Int (StateF a)`
      + `get`/`put` + `bindS`/`pureS` + `runState : StateF a -> Int -> Pair a Int`; via do-notation. Typed
      end-to-end (e.g. `get; put (get+1); …` ⇒ `Pair(2,2)`-style). All 3 backends.
- [x] **P3. Nondeterminism (MULTI-SHOT)** — `data NondetF a = Ret a | Choose [Int] ([Int]? )` (choose +
      runAll collecting every branch); verify multi-shot resume on run-ir / node / rust.

Track E — **universal `Comp` via a localized `Dyn` escape-hatch** (option B): one monad for all effects.
- [x] **E1. `Dyn` type** — `TyDyn`, unifies with any type (both directions); surface type `Dyn`;
      ascription round-trip `((x : Dyn) : Int)`. The single, documented unsafe escape-hatch.
- [x] **E2. universal `Comp` + `perform`/`pure`/`bind` + a multi-op handler** using `Dyn` payloads;
      typed operation wrappers (`get : Comp Int`, …) so user code stays type-safe.

- [x] **DOC/CONF** — `specs/50-effects.md` (typed-surface section) + `specs/41-mira.md`; conformance for
      State (one-shot) + Nondeterminism (multi-shot), both tracks, all backends.

OPEN (deferred, research): **full effect-row inference** — `Comp` tracks WHICH effects (row polymorphism,
Koka/Frank style). Disproportionate for the ~430-line inferrer; Track P already types effects per-effect.

## K8 — Overloaded numeric operators (Int + Float)

Make `+ - *` and `< =` work on **Float** as well as `Int`, resolved by operand type (today Float needs
`fadd`/`fsub`/`flt`/…). HM has no qualified types, so use the proven id-tagged-node + `tcReg` mechanism
plus **eager numeric defaulting**: at an operator node, unify the operands; concrete `Int`/`Float` ⇒ use
it; a still-unresolved type-var ⇒ default to `Int`; any other type ⇒ reject. Sound (a later non-numeric
constraint conflicts). One documented sharp edge: an all-type-var chain defaults to Int, so `r*r*pi`
needs a leading concrete float or `(r : Float)*…` (ascription). `fadd`/etc. stay as-is (back-compat).

- [x] **K8.1 — overloaded `+ - *`** — id-tag `Add`/`Sub`/`Mul`; `inferNum` resolves Int/Float (eager
      default), records the type in `tcReg`; erase emits `i.*`/`f.*` by the recorded type; eval value-
      dispatches (IntVal/FloatVal). `1.5 + 2.5` ⇒ Float, `1 + 2` ⇒ Int, all 3 backends. `"a" + "b"` rejected.
- [x] **K8.2 — overloaded `< =`** (and the derived `> <= >= <>`) — same mechanism for `Lt`/`Eq`; result
      Bool, operands Int or Float. `1.5 < 2.5`, `1.0 = 1.0` work; all 3 backends.
- [x] **K8.3 — overloaded `/`** — same id-tag / `inferNum` / eager-default mechanism for a new `Div(id,a,b)`
      node (lexer already emits `TPunct("/")`; `//` is the comment, caught first; `/` is multiplicative
      precedence, left-assoc). `20 / 6` ⇒ `Int` `3` (truncating), `9.0 / 2.0` ⇒ `Float` `4.5`, `9.0 / 2`
      rejected. All 3 backends. Found+fixed a latent JS bug: `i.div` emitted JS `/` (always float) → now
      `Math.trunc(a / b)` (truncates toward zero like JVM/Rust; the old `div` function was wrong on JS too).
      conformance +5.
- [x] **DOC/CONF** — spec 41 (numeric operators are overloaded; the defaulting note) + conformance.

OPEN (deferred): Fully-general numeric polymorphism (e.g. `r*r*pi` with `r` a param) needs qualified types
(`Num a =>`), the same research-level work as effect rows; eager defaulting is the sound pragmatic choice.

## K9 — Concurrency in the typed surface (on the typed effects)

- [x] **typed async** — `examples/hm-async.hm`: yield/log ops over the universal `Comp`, a round-robin
      `runSched` handler, cooperative interleaving of tasks ⇒ `[1, 2, 101, 102]`. All 3 backends.
- [x] **typed actors** — `examples/hm-actors.hm`: a stateful behavior `(state, msg) -> (state', out)`
      over a message stream ⇒ `[2, 3, 2]`. All 3 backends.

## Remaining (genuinely blocked, not "todo")

- **Effect rows** — now in progress as **K10** below (light version: track the effect *set*); the full
  Koka-style system (typed payloads) stays deferred.
- **WASM backend** — ✅ UNBLOCKED + SHIPPED 2026-07-05 (see K4): `rustup` appeared in the
  environment; wasm32-wasip1 + Rust-backend reuse + Node WASI host. Nothing remains blocked here.

## K10 — Effect rows (research; light implementation)

Track effects in the type: `Comp {get, put | ρ} a`; `run : Comp {} a -> a` enforces "no unhandled
effects". Chosen scope (agreed): **doc + spike + light** (rows track labels over the existing `Dyn`
`Comp`; payloads stay `Dyn`; full Koka-style typed-operations deferred). Spec `54-effect-rows.md`.

- [x] **K10.1 — row-unification spike** — Rémy-style row unification in ssc0, validated on the three
      canonical cases (`{get|ρ}~{put,get}`; `{get}~{put}` fails; `{get|ρ1}~{put|ρ2}` shares a tail).
- [x] **K10.2 — design doc** — `specs/54-effect-rows.md`.
- [x] **K10.3 — rows in the inferrer** — `TyRowEmpty`/`TyRowExt`/`TyRowVar` + `rowUnify`/`rowRewrite`
      (fresh row-vars via a global cell, based high) + `unify` dispatch (incl. `Comp[r,a]`) +
      appTy/occurs/freeTy/renameTy/showTyR. Validated; no regression.
- [x] **K10.4a — type-level effect surface** — built-in `pureE`/`bindE`/`getE`/`putE`/`runStateE`/`runE`
      with row-carrying types; `Comp {S | ρ}` tracks effects, `runStateE` removes `State`, `runE : Comp {}
      a -> a` so an **unhandled effect is a type error** (`runE getE` rejected). Demonstrated at the type
      level (`examples/hm-effrow.hm` ⇒ `(Int, Int)`; `runE getE` ⇒ TypeError). conformance +3.
- [x] **K10.4b — runtime (runs on all backends)** — `erase` lowers the effect built-ins to the universal
      `Comp` (`Pure`/`Op`) + global helper defs `__effBind`/`__effRun`/`__effRunSt` (hand-coded de Bruijn,
      appended by `progOf` when effects are used). Effectful programs now RUN, not just type-check:
      `examples/hm-effrow.hm` (put 5; get; return get+100, handled by `runStateE`, then `runE`) ⇒
      `Pair(105, 5)` on run-ir / node / rustc. conformance +3 (314 → 317).
- [x] **K10.4c — a SECOND effect (proves rows, not one monad)** — added a `Log` effect (`logE` /
      `runLogE : Comp {Log | ρ} a -> Comp ρ (Pair a [Dyn])`, collects logged values in emission order via
      an accumulator + `__effRevApp`; its handler forwards non-Log ops so it composes in either order).
      `examples/hm-eff2.hm` (put 3; **log 7**; get; return get+100) has row `{State, Log}` — the type tracks
      **both** and `runE` demands **both** handled: `runE (runLogE (runStateE … 0))` ⇒ type
      `((Int, Int), [Dyn])`, value `Pair(Pair(103, 3), Cons(7, Nil))` on run-ir / node / rustc; forgetting
      `runLogE` ⇒ `TypeError: effect not handled: Log`. This is the headline payoff of ROWS over a single
      effect monad. conformance +5 (317 → 322).
- [x] **K10.4d — USER-EXTENSIBLE effects (`perform` + general `handle`)** — effects are no longer hard-wired
      to the built-in State/Log demos. Two new primitives, no new keyword syntax (they reuse the application
      spine): `perform "Eff" "op" arg` performs any user effect (→ `EffOp`, row-tracked), and
      `handle "Eff" comp (v => ..) (op => arg => k => ..)` is a general **deep handler** — a literal effect
      label so `infer` does the row surgery (`Comp {Eff | ρ} a → Comp ρ b`), and at runtime it FORWARDS other
      effects so handlers compose. Because the resume `k` re-enters the handler, it is deep + **multi-shot**.
      Demo `examples/hm-eff-handle.hm` — nondeterminism: a user-written handler that calls `k` **twice**
      (`k true` ++ `k false`) over `flip; flip` ⇒ type `[Int]`, value `[3,2,1,0]` on run-ir / node / rustc;
      `runE (perform "Choose" "flip" …)` ⇒ `TypeError: effect not handled: Choose` (a *user* effect, tracked).
      Two supporting fixes: (1) **row-var generalization** — `appTy`/`renameTy` now re-tag a freshened
      (instantiated) ordinary var as a row var at row positions (`asRow`), so a `let`-bound polymorphic handler
      instantiates its row var as a row, not an ordinary var (was: `TypeError: not an effect row`). (2)
      **unified runtime convention** — the universal `Op`'s label is now the EFFECT name with the op name in a
      `Pair op arg` payload (handlers match by effect, dispatch by op); `__effRunSt`/`__effLogGo` updated to
      match. conformance +5 (322 → 327). NOTE: K9 async/actors use a *source-level* `Comp`, unaffected.
- [x] **K10.4e.1 — the general `handle` SUBSUMES the built-ins** — `examples/hm-eff-userstate.hm`
      re-implements `runState` **entirely in user source** with only `perform`/`handle`/`bindE`/`pureE` — a
      *parameterized* (state-threading) handler where the handled computation returns a function of the state
      (`b = Int -> Comp ρ (Pair a Int)`; `get` = `λs. (k s) s`, `put a` = `λs. (k ()) a`). ⇒ type `(Int, Int)`,
      value `Pair(105, 5)` on run-ir / node / rustc — identical to the built-in `runStateE`. ZERO compiler
      change: it shows the general deep handler already covers stateful effects, not just the pure/multi-shot
      ones. conformance +4 (327 → 331).
- [x] **K10.4e.2 — `doE` do-notation for effects** — `doE { x <- m ; … ; result }` desugars `<-` to `bindE`
      (and the final stmt is the result), so effect code reads top-to-bottom instead of nested `bindE (..)
      (fun x => ..)`. The existing `do` (→ `bind`, used by Option / the K9 async `Comp`) is untouched —
      `parseDoStmts` is now parameterized by the bind name. `examples/hm-eff-do.hm` (State) ⇒ `(Int, Int)` /
      `Pair(105, 5)`; `examples/hm-eff-do-nondet.hm` uses `doE` in BOTH the handler body and the computation ⇒
      `[Int]` / `[3,2,1,0]` — all on run-ir / node / rustc. conformance +7 (331 → 338).
## K11 — finish everything remaining (2026-06-28, user: "делай всё, не останавливайся")

Close out the whole remaining frontier. Ordered easy→hard; each slice ships green on all 3 backends.

- [x] **K11.1 — `effect` declaration sugar** — `effect Name op1 op2 in <body>` registers each `opK` as an
      operation of effect `Name` (`effOpReg` + `registerEffOp`/`effOpOf`/`isEffOp`/`effLabel`); `opK arg`
      desugars to `EffOp("Name","opK",arg)`, bare `opK` to `EffOp(..,Lit 0)`. `parseEffectDecl` (op-name list
      stops at `in`; `effect` is a keyword); applied ops intercepted in `desugar`'s App case before the
      bare-op Var rule. `handle` stays string-based. `hm-eff-decl.hm` (State) ⇒ `(Int,Int)`/`Pair(105,5)`;
      `hm-eff-decl-choose.hm` (`effect Choose flip` + multi-shot `handle`) ⇒ `[Int]`/`[3,2,1,0]`;
      declared-unhandled ⇒ `TypeError: effect not handled: Choose`. All 3 backends. conformance +8.
- [x] **K11.2 — row syntax in the type parser** — `{}` / `{l}` / `{l, m}` (closed) / `{l | r}` (open, fresh
      tail var) parse as *row* types in ascriptions, and `Comp {row} a` works: `(getE : Comp {State} Dyn)`,
      `(comp : Comp {State} Int)`. `parseRowType`/`parseRowMore`/`parseRowTail` added; `{` starts an asc atom.
      `showTyR` now renders rows as `{State}` / `{State, Log}` / `{State | eN}` (a single open label `{l | eN}`
      is unchanged → existing checks safe). Wrong ascription (`getE : Comp {Log} Dyn`) rejected.
      `examples/hm-eff-rowann.hm` (doE block ascribed `: Comp {State} Int`) ⇒ `(Int,Int)`/`Pair(105,5)`, all 3
      backends. Records (`{x = e}`) untouched (different parse position). conformance +7.
- [x] **K11.3 — NUMERIC POLYMORPHISM (light qualified types via inlining)** — the documented K8 sharp edge
      is GONE for the common case: `let twice = fun x => x + x in (twice 5, twice 2.25)` ⇒ `(Int, Float)` /
      `Pair(10, 4.5)` on all 3 backends. Done WITHOUT dictionary passing (no backend change), via two pieces:
      (1) **inlining** — a non-recursive `let f = <closed numeric fn> in …` is unfolded (pure beta) so each use
      of `f` is an independent copy with FRESH numeric ids; "closed" = `freeVars` empty (auto-excludes recursive
      / prelude-using fns) AND `containsNumOp` (so non-numeric helpers like `id` are untouched → no var-number
      churn). (2) **deferred numeric resolution** — an overloaded op on an unresolved var records the var (not
      eager-Int); it's resolved at let-generalization (`defaultPendingIn` → keeps NON-inlined numeric lets
      monomorphic-Int = sound) and finally at the top (`finalDefault` → Int); `resolveNum(s)` re-bakes `tcReg`
      through the final subst before erase, threaded into all drivers. So each inlined copy resolves its own
      Int/Float from its argument. conformance +5 (358 → 363); `int helper still Int` guards soundness.
- [x] **K11.3b — USER-TYPECLASS POLYMORPHISM (same inlining trick + method result sig)** — a user `method`
      with a RESULT-TYPE signature works POLYMORPHICALLY inside a closed function: `method describe : String
      in … let f = fun x => describe x in (f 5, f true)` ⇒ `(String, String)` / `Pair("an int", "a bool")` on
      all 3 backends — `describe` dispatches to the Int instance for `f 5` and the Bool instance for `f true`.
      `method m : R in` records the result type (`methodSigReg`); `MethodCall` on a still-variable receiver
      DEFERS (`pendingMethods`) returning `R`, and the instance is resolved at the top (`resolveMethods`, in
      `resolveNum`) once inlining has made the receiver concrete. Inlining is relaxed to allow free **method**
      names (capture-safe — methods are global) via `isMethodApp`/`allMethods`. Methods WITHOUT a signature
      keep resolving eagerly/monomorphically (regression `sz 5 => 6`). conformance +5. LIMITATION: only
      fixed-result methods (result not depending on the receiver, e.g. `show`/`compare`/`describe`) in CLOSED
      functions; non-closed method uses still need the full dict-passing design.
- [x] **K11.3c — RECEIVER-RESULT methods + impl soundness** — (1) `method m : self in` makes the result the
      RECEIVER type, so `method negate : self in … let f = fun x => negate x in (f 5, f 2.5)` ⇒ `(Int, Float)`
      / `Pair(-5, -2.5)` — `negate` polymorphic over Int & Float (`selfRes` returns the receiver var when the
      sig is `self`). (2) SOUNDNESS fix: the deferred path now TYPE-CHECKS the chosen impl with the concrete
      receiver (`checkImpl`), so an impl that itself uses an overloaded op (`0.0 - x`, `x < 3.0`) resolves to
      the right primitive (`f.sub`/`f.lt`) — previously it defaulted to `i.*` and crashed run-ir / panicked
      Rust. (3) `ascAtomStarts` stops at keywords so a method/op result type before `in` parses (`: self in`).
      conformance +4 (method-poly-ops, the op-impl soundness guard) +4 (method-self). All 3 backends.
- [x] **K11.4 — TYPED PAYLOADS (light)** — `effect Name { op : ArgT -> ReplyT , … } in …` gives each op a
      SIGNATURE (`effSigReg`); a typed op's arg is checked against `ArgT` and its reply is `ReplyT` (not `Dyn`),
      so effect code drops the `(x : Int)` / `(5 : Dyn)` ascriptions: `effect State { get : Dyn -> Int , put :
      Int -> Dyn } in doE { u <- put 5 ; x <- get ; pureE (x + 100) }` ⇒ `x : Int` inferred, `(Int, Int)` /
      `Pair(105, 5)` on all 3 backends; `put "x"` ⇒ `TypeError: effect op arg type mismatch`. `EffOp` infer
      uses the signature when present, falls back to `Dyn` otherwise (untyped decls + string `perform`
      unchanged). Built-in handlers (runStateE/…) are generic over the value type, so they consume typed
      replies unchanged. conformance +5. NOTE the **general `handle`** keeps a `Dyn` resume (typing a
      user-written handler's resume against the signature is the deeper Koka step, still open).
- [x] **K11.5 — spec sweep** — specs (41/50/54) updated to the shipped surface (earlier commit) + new
      `55-qualified-types.md` design + `54`'s "Path to full" updated (effect-decl & row-syntax landed; payloads
      build on K11.1/K11.3). Effect examples already demonstrate `doE` in dedicated files (`hm-eff-do*.hm`,
      `hm-eff-decl*.hm`); the older explicit-`bindE` examples are kept intentionally (they document the
      desugaring) rather than churned.

## K12 — mutual recursion

- [x] **K12.1 — `let rec f = .. and g = .. in ..`** — mutual recursion, a genuinely-missing feature. New
      `LetRecM(binds, body)` node (single `let rec` still → `LetRec`); `and` is a keyword. Infer binds all
      names to fresh vars in a shared env, infers each lam, unifies, then generalizes all (numeric vars
      defaulted at generalization, sound). Erases to the IR's existing list-form `IrLetRec` (last bind = idx
      0, matched by `prependAll`). run-ir + JS supported the multi-binding `IrLetRec` already; the **Rust
      backend only did single-binding (bailed to `V::U`) — extended it** with an n-way knot-tie (n
      `RefCell<V>` cells + n self-ref closures + write each real lam into its cell). `examples/hm-mutual.hm`
      (isEven/isOdd) ⇒ `Bool`/`true`; a 3-way `f→g→h→f` ⇒ `1`; all on run-ir / node / rustc. (The tree-walk
      interp `evalT` is `Stuck` for `LetRecM` — not conformance-exercised; effects run via the compile path.)
      conformance +5.
- [x] **K12.2 — 4-tuples (Quad), fix silent truncation** — `(1, 2, 3, 4)` used to **silently drop the 4th
      element** (the tuple builder made a `Triple` from the first 3) — a data-loss bug. Now `(a,b,c,d)` builds a
      `Quad` (new built-in con, arity 4, added to `builtinCon`/showable/`showTyR`/value-show); arity ≥5 nests
      the tail (`Quad(a,b,c,(d,e,…))`) so no element is lost. `mkTuple` + `mkTuplePat` extended. `(1,2,3,4)` ⇒
      `(Int,Int,Int,Int)` / `Quad(1,2,3,4)`; `(a,b,c,d) => a+b+c+d` ⇒ `10`; a 5-tuple `(a,b,c,(d,e))` matches.
      All 3 backends. conformance +5. (NOTE: ssct-hm match syntax is `pat => body | …` — NO `case` keyword;
      var-catch-all and tuple patterns already worked.)
- [x] **K12.3 — pattern guards** — `match x { pat if cond => body | … }`: a failed guard falls through to the
      next arm. No new AST node — a guarded arm parses to `PatArm(pat, If(cond, body, Var "$guardfail"))`
      (forcing the compiler path), and `compileArms` substitutes `$guardfail` with that arm's fail
      continuation (`if cond then body else <next arm>`). Fixed a parser bug it exposed: `patStarts` now
      stops at keywords, so `Som x if x > 0` doesn't slurp `if x` as extra sub-patterns. `examples/hm-guard.hm`
      (a `classify` with `x<0`/`x>0`/else) ⇒ `0`; `Som x if x>10 => 100 | Som x => x` on `Som 5` ⇒ `5`
      (fallthrough). All 3 backends. conformance +6.
- [x] **K12.4 — string escapes** — `\n` `\t` `\r` `\\` `\"` in string literals. The lexer un-escapes the
      source (and `scanStr` skips `\"` so it isn't a terminator); the JS/Rust gens re-escape the string VALUE
      on emit (`escapeStr` in emit, shared) so the generated source is valid; the Core IR already round-trips
      special chars (kernel `strLit`/`readString`), so run-ir needed no change. `strLen "a\nb\tc"` ⇒ `5`;
      `strLen "x\"y"` ⇒ `3`. All 3 backends. conformance +5.
- [x] **K12.5 — record update** — `{ r with f = v , … }` builds a new record like `r` with field `f`
      replaced (multiple fields chain into nested `RecUpd`). New `RecUpd(id, rc, field, val)` node (mirrors
      `FieldGet` across desugar/freeVars/inline/freshen/subst/containsNumOp); infer is type-directed — `r` must
      be a `TyRec` with the field, `val` is checked against the field's type, result = `r`'s type; erase
      matches `r` once (binding all `ar` fields) and rebuilds `__rec` with field `idx` replaced (`recUpdFields`
      + `prependN` to shift the value's scope past the `ar` field binders). `with` is a keyword.
      `{r with x = 9}` then `r2.x + r2.y` ⇒ `11`; `{r with x=9, y=8}` ⇒ `17`; `{r with z = …}` rejected. All 3
      backends (records are generic `__rec` Data → no backend change). conformance +5.
- [x] **K12.6 — negative literals** — unary minus on a numeric literal: `-5`, `-2.5`, usable at any operand
      position (`x * -2`, `6 / -2`, `f (-3)`, `[-1, -2]`). New `parseUMinus` sits between `parseMul` and
      `parseApp`: a leading `-` immediately followed by `TNum`/`TFloat` becomes `0 - n` (object `Sub`, since the
      ssc0 KERNEL has no infix `-`) / a negative float-string `FloatLit`. Used at EVERY operand slot (parseMul
      entry + `*`/`/` right operands in parseMulMore + `+`/`-` right operands already go through parseMul). `-`
      is *not* an `atomStart`, so application args never grab it and binary `f - 5` / `a - 1` stay subtraction.
      `-x` / `-(e)` are still not negation (write `0 - x`). `-3 * -2` ⇒ `6`, `-5 + 3` ⇒ `-2`. Pure front-end
      desugar → all 3 backends unchanged. conformance +5.
- [x] **K12.7 — let-binding type annotation** — `let x : T = e in body` ascribes the bound expr to `T`
      (no params). `parseLetBind` now intercepts a `:` immediately after the binder name, parses the type with
      the existing `parseAscType`, and wraps the bound expr in the existing `Ascribe(e, ty)` node (so the
      annotation is *enforced* — `let x : Int = true` is rejected with "type ascription mismatch"). Refactored
      into `parseLetBindPlain` (the old `name param* = e` path) + `parseLetBindAsc`. No regression: `let x = e`,
      `let f x = e` (fn sugar — token after `f` is a name, not `:`), and `let rec` are untouched. Reuses the
      `(e : T)` ascription machinery → 0 backend change. `let xs : [Int] = [1,2] in xs` works on all 3 backends.
      conformance +5.
BLOCKED (not doable here): **ir → WASM** — no `rustup`/`wasmtime`/`wabt` toolchain in this environment
(only node's WebAssembly API). Documented in K4; revisit when the toolchain is available.

## K13 — non-closed qualified types via dictionary passing (the one substantial remaining feature)

Status of qualified types today: the **closed** case is DONE (K11.3 light qualified types) — a polymorphic
helper whose whole body is visible is `inlineClosed`-unfolded (fresh ids per copy) and its overloaded
numerics / user-method calls are **deferred** (`pendingNum` / `methodSigReg`) and resolved per concrete use,
impls type-checked → sound. What's missing is the **non-closed** case: a top-level polymorphic binding used at
several concrete types **without** inlining (recursive, large, or exported across a module boundary). That
needs real **dictionary passing** (spec `specs/55-qualified-types.md`). The values stay concretely typed; only
the *operations* are passed, so a `Num`/`Ord` dict is just a **type-tag string** + global tag-dispatch helpers.

⚠️ This is a DEEP, PERVASIVE inferrer change (probed 2026-06-28), NOT a tail-of-session slice — do it as a
focused effort, gate conformance after EACH slice. Key cost: `Forall(qs, ty)` must gain a constraints field
→ touched at ~15+ sites (`generalize`, `instantiate`, `freeEnv`, `envWithRecVars`, every `Forall(Nil, …)` in
`infer`). `pendingNum` is today's Num-only side-channel — the seed of the constraint set, extend it.

- [x] **K13.0 — non-self-recursive `let rec` gets let-polymorphism** (2026-06-29). Empirically (2026-06-28) the
      whole practical qualified-types gap was `let rec` poly-numeric bindings used at 2+ types; most such cases
      are *gratuitous* `let rec` — the binding doesn't actually call itself (e.g. `let rec dbl = fun x => x + x
      in (dbl 3, dbl 2.5)`). `inlineClosed`'s `LetRec` case now checks `isParam(f, freeVars(l))`: if `f` is NOT
      free in its own body it's really a plain `let`, so it's rewritten to `Let(f, l, b)` and picks up the
      existing inlining-based let-polymorphism (incl. numeric). `dbl` now types `(Int, Float)` and runs on all 3
      backends; genuinely self-recursive functions (`fact`, `sum`, mutual rec) are untouched. conformance +5.
      **Still open (true dict-passing, K13.1+):** a GENUINELY self-recursive binding polymorphic over a numeric
      type with no anchoring literal, used at 2+ types — e.g. `let rec scale = fun x => fun n => if n=0 then x
      else x + scale x (n-1) in (scale 3 2, scale 2.5 1)` — still "cannot unify Int". This is extremely rare
      (the base case usually anchors the type) and remains sound-reject. It needs the inference change below
      (generalize the rec binding's Num var instead of defaulting) AND dict-param codegen, since one erased copy
      of `scale` can't use both `i.add` and `f.add`.

- [x] **K13.1 — DICT-PASSING FOR `Num` SHIPPED (2026-06-29)** — via a TARGETED design (simpler than the
      Forall-constraints-field plan below). A `let rec f` whose quantified type has a single still-**pending**
      (unanchored) numeric var `dv` is marked a **dict-fn** (`dictFnReg: name -> (dv, argPos)`) instead of
      defaulting `dv` to Int — so `f` generalises and is usable at multiple numeric types. Erase: a dict-fn's
      lam gets a leading `$dict` param (`λ$dict. <lam>`, `curDictV = Some(dv)` + `"$dict"` in scope while
      erasing its body); an overloaded op whose `instOf(id)` IS `dv` dispatches via `__nadd`/`__nsub`/`__nmul`/
      `__ndiv`/`__nlt` `(tag, a, b)` (global helpers that branch on `seq(tag,"Float")` → `f.*` else `i.*`). At a
      call site (`spineHead`/`spineArgs`), the dict is the **enclosing `$dict`** if inside a dict-fn body
      (recursive call), else a **literal tag** read from the dict-typed argument's syntactic form (`tagOfArg`:
      `Lit`→"Int", `FloatLit`→"Float", arith op→`instOf`, ascription→its type). **0 kernel / 0 backend change**
      — `__n*` are ordinary IR globals. `let rec scale = fun x => fun n => if n=0 then x else x + scale x (n-1)`
      used at BOTH Int (`scale 3 2`=9) and Float (`scale 2.5 1`=5.0) now runs on run-ir/JS/Rust; likewise `*`
      (`acc 2 3`=16, `acc 1.5 1`=2.25). Scoping is conservative: anchored numeric `let rec` (e.g. `sum`, `fact`)
      keep a resolved (non-pending) var → NOT dict-fns → unchanged; non-numeric polymorphic recursion (`map`)
      has no pending var → unchanged. **Limitations:** only the `Num` class; a single dict var per binding; the
      external-call tag must be readable from the argument's form (a bare `Var` of unknown concrete type
      defaults to "Int"); the dict-fn must be directly applied (not passed as a value). conformance +5.
- [x] **K13.4 — extend dict-passing to `Ord` (Int / Float / String) SHIPPED (2026-06-29)**. Two parts:
      (1) **Detection fix** — `firstPendingIn` checked `isPending` on the *quantified* vars, but a `<`-only
      dict-fn records its pending var on a var that unification later folds into a *different* representative,
      so it was missed (the `+` cases passed by luck). New `dictVarOf(s, pend, qs)` maps each pending var through
      the substitution and returns the representative that is in `qs`; `unpendRep` removes (by representative) so
      the top-level default leaves it alone. This alone fixed `<`-using dict-fns at Float.
      (2) **String/Ord helpers** — `tagOfType`/`tagOfArg` gained `String`; the comparison helpers became 3-way:
      `__nlt` = Float→`f.lt` / String→`__strLt` / Int→`i.lt`, and a new `__neq` = Float→`f.eq` / String→`seq` /
      Int→`i.eq` (Eq erase now dict-dispatches too). `__strLt` is auto-injected whenever the dict helpers are.
      A single recursive `maxOf` (using `<`) now runs at Int, Float AND String — `Triple(5, 9.5, "z")` — and a
      `countEq` (using `=`) at Int and String, all on run-ir/JS/Rust. conformance +5.
- [x] **K13.5 — USER-CLASS dict-passing SHIPPED (2026-06-29)**. A recursive function using a USER typeclass
      `method` on a still-polymorphic receiver now works (even at one type — it failed before, since
      `methodImplOf` can't resolve a polymorphic receiver). The **dict is the instance impl itself** (a
      function), which sidesteps pre-inferring impls. INFER: when the Num/Ord path doesn't fire, `methodDictOf`
      scans `pendingMethods` for a deferred method whose receiver var (after subst) is quantified → mark `f` a
      method-dict-fn (`methodDictReg: name → (method, argPos)`) and record the specific method-call ids that
      dispatch via the dict (`mdictCallReg`). ERASE: the lam gets a leading `$mdict` param; a method call whose
      id is in `mdictCallReg` becomes `$mdict arg`; at a call site (`eraseMethodDictCall`) the dict is the
      enclosing `$mdict` (recursive) or the instance impl `lookupInstance(method, tagOfArg(receiver))`.
      `tagOfArg` gained `Bool` and **user-ADT** support (`ConApp`/`Var` → `conTyName` via `conReg`’s `ConSig`).
      A recursive `acc` over a user method `tone` runs at user types `Color` AND `Shape` (and at Int/Bool/String)
      on run-ir/JS/Rust — `(acc Red 2, acc Dot 3)`. **Limitations:** one method dict per binding; impls that
      themselves use unresolved overloaded ops need pre-inference (no-ops / match / concrete-op impls are fine);
      a receiver whose type isn't readable from the argument's form (a bare polymorphic `Var` in a monomorphic
      external call) isn't supported. conformance +5. **Qualified types: closed (inlining) + non-closed
      dict-passing for Num, Ord, and user classes — COMPLETE.**
ORIGINAL PLAN (kept for reference — the targeted K13.1 above subsumes K13.1–K13.3/K13.5 for `Num`): a constraint
set threaded in `infer`, `Forall(qs, constraints, ty)`, generalize/instantiate/discharge/default, dict-passing
erase. The targeted approach reuses the existing `pendingNum`/`tcReg`/`instOf` machinery instead of adding a
`Forall` constraints field, so it touched far fewer sites and stayed green.

Designed-but-larger (not blocked): **typed handler resumes** (per-op typed resume, `specs/54`) — current
effects use a uniform `Dyn -> Comp` resume; typing the resume per op is a separate focused effort.

## K14 — string ordering (Ord/Eq for String)

- [x] **K14.1 — `=`, `<>`, `<`, `<=`, `>`, `>=` on String** (lexicographic). Probed (2026-06-28): strings had
      `strEq` but the *operators* only worked on Int/Float (`"a" < "b"` → "need Int or Float operands"); since
      ssct-hm has no module system, this — not dict-passing — was the most broadly-useful real gap. `mkCmp`
      already desugars all six comparison operators into `Lt`/`Eq`, and both route through `inferNum(_,"cmp")`,
      so a single `TyStr` branch in `inferNum` (guarded to the `"cmp"` kind, so string `+`/`-`/`*` stay
      rejected → "use ++") enables all six at once. Erase: `Eq`/`<>` reuse the kernel `seq` primitive; the
      `<`-family use a new `__strLt` IR helper (lexicographic via `slen`+`scodeAt`, recursing through
      `IrGlobal("__strLtGo")` — named-global recursion, no de Bruijn knot), injected like the eff helpers via
      a `usedStrLtCell`. Interp mirrors with a host `strLtV`. **0 kernel / 0 backend change** — `__strLt` is an
      ordinary IR global (like `__effBind`), so it runs identically on run-ir/JS/Rust. Lexicographic order
      verified incl. prefixes (`"ab" < "abc"`, `"abc" < "ab"`). conformance +5.

## K15 — list append (overload `++`)

- [x] **K15.1 — `++` on lists** = append (was String-only: `[1,2] ++ [3,4]` → "needs String operands"). Gave
      `Concat` an `id` (mirroring `Add`/`Lt`/`Eq` across desugar/freeVars/freshen/subst/containsNumOp/inline),
      so `inferConcat` can dispatch by operand type: both operands unify, then `TyStr`→String concat (`sconcat`
      prim), `TyList(e)`→list append (new `__append` IR helper), unresolved `TyVar`→**defaults to String** (so
      the old String-only behaviour is preserved exactly for ambiguous `++`). `__append` = `λxs.λys. match xs
      { Cons h t => Cons h (__append t ys) | Nil => ys }` recursing via `IrGlobal("__append")` (named-global,
      no de Bruijn knot), injected via `usedAppendCell`; interp mirrors with host `appendV`. **0 kernel / 0
      backend change** — `__append` builds plain `Cons`/`Nil` IR. `[1,2]++[3,4]`→`[1,2,3,4]`, `[]++[1]`,
      chaining, list-of-strings all green on run-ir/JS/Rust; string `++` and ambiguous-default unchanged.
      conformance +5.

## K16 — let destructuring

- [x] **K16.1 — `let (pat) = e in body`** (tuples, nested, ctor patterns). Probed (2026-06-28): this used to
      **crash** the compiler (`parseLetE` saw `(` after `let`, fell to `ParseErr`, and a downstream match had
      "no arm for ParseErr"). Now `parseLetE` dispatches a leading `(` to `parseLetDestructure`, which parses
      the parenthesised pattern with the existing `parseParenPat`, then desugars to `match e { pat => body }`
      via the existing `armOfPat` — so all the tuple/nested-pattern machinery (incl. `match`-compiler for
      non-simple sub-patterns) is reused. `let (a,b)=(3,4)`, `let (a,b,c)=…`, `let (a,(b,c))=…`, and
      destructuring a function result all work on run-ir/JS/Rust. 0 infer/erase/backend change (pure
      front-end desugar to `MatchT`). No regression: `let x=e`, `let f x=e`, `let x:T=e`, `let rec` untouched.
      conformance +5. (Other parser robustness gaps noted: `if` w/o else still Java-crashes rather than
      erroring cleanly — lower priority.)

## K17 — literal patterns (negative + float)

- [x] **K17.1 — negative-int / float / negative-float patterns.** Probed (2026-06-28): `match n { -1 => … }`
      mis-parsed (`-` became `PWild`), and `match x { 2.5 => … }` **Java-crashed** (`parseAtomPat` had no
      `TFloat` arm → "no arm for TFloat"). Fixed in `parseAtomPat`: `TFloat(s)` → `PLit(FloatLit s)`; a leading
      `-` on a numeric literal → `PLit(Sub(0, Lit n))` (int) / `PLit(FloatLit("-"++s))` (float) — reusing the
      same object-`Sub` trick as expression negative-literals (the ssc0 kernel has no infix `-`). `patStarts`
      now also admits `TFloat`/`-` so these work as constructor sub-patterns too. `litCond` gained a `FloatLit`
      arm → float patterns compile to `FEq` (float equality), not the Int-only 2-arg `Eq`; negative ints reuse
      the generic `Eq(sv, Sub …)` `_`-case. `match n { 0 => .. | -1 => .. | -5 => .. }`, `match 2.5 { 2.5 => .. }`,
      `match (0.0-2.5) { -2.5 => .. }` all green on run-ir/JS/Rust; positive int/str/bool/ctor/nested patterns
      unchanged. Pure front-end (0 backend change). conformance +5.

## K18 — list bracket patterns

- [x] **K18.1 — `[]`, `[a]`, `[a, b, …]` list patterns** in `match` (previously only `Cons`/`Nil` worked;
      `[a, b]` gave "unbound variable"). New `parseListPat`/`parseListPatElems` parse a `[ … ]` pattern and
      desugar to the nested `Cons`/`Nil` `PCon` chain (`[a,b]` → `PCon Cons [a, PCon Cons [b, PCon Nil []]]`),
      so the existing match-compiler handles length-checking and binding. Wired into `parseAtomPat` (a `[` after
      a pattern position) and `patStarts` (so list patterns nest inside ctor patterns). `[] => …`, `[a] => …`,
      `[a,b] => …` and a `Cons h t` fallthrough all compose in one match; nested element patterns
      (`[Some a, b]`) work. Pure front-end desugar (0 infer/erase/backend change). All green on run-ir/JS/Rust;
      `Cons`/`Nil` patterns, list-literal exprs, tuple patterns unchanged. conformance +5.

## K19 — record destructuring

- [x] **K19.1 — `let { f = v , … } = e in body`** (completes the destructuring story: K16 did tuples, this does
      records). Desugars to `let $r = e in let v = $r.f in … in body` — i.e. one binding per field via the
      existing type-directed `FieldGet`, so it is **order-independent** (binds by field name, not position) and
      fully type-checked, with no new pattern node or match-compiler/infer/erase change. `parseLetE` dispatches a
      leading `{` (after `let`) to `parseLetRecDestr`; `parseRecDestrFields` collects the `field = var` pairs and
      `buildRecDestr` folds them into nested `Let`s over a fresh `$rd<n>` bound to `e` (so `e` is evaluated once).
      `let {x=a, y=b} = r`, `{y=b, x=a}` (order-free), single-field, and destructuring a function's record result
      all green on run-ir/JS/Rust. No regression: record literals + `.f` access, record update, tuple
      destructure, plain `let` untouched. conformance +5.

## K20 — parse-error robustness (clean error, no crash)

- [x] **K20.1 — `ParseErr` → clean type error instead of a Java crash.** `if true then 1` (no `else`), an empty
      `let` body, etc. produced a `ParseErr` node that reached a `match` with no arm → uncaught
      `RuntimeException: match: no arm for ParseErr`. Added `case ParseErr => ErrI("parse error: incomplete or
      malformed expression …")` to `infer` (covers the type-check path) and `case ParseErr => ParseErr`
      passthrough to `desugar` (codegen path). Now any incomplete/malformed input reports a clean
      `TypeError: parse error …` on both `ssct-hm` and `ssctc-hm`. Valid programs unchanged. conformance +1.

## K21 — char literals

- [x] **K21.1 — `'a'` char literals → Int char code.** ssct-hm has no `Char` type and `charAt`/`scodeAt` already
      return Int codes, so a char literal is just its code: `'a'` = 97, `'0'` = 48, `'\n'` = 10. Pure lexer
      change — `lexFrom` gets a `'` (code 39) arm that reads one char (handling a `\`-escape via the existing
      `unEscCode`) and emits `TNum(code)`, so downstream everything treats it as an ordinary `Lit`/Int (no
      type, infer, erase, or backend change). Enables char arithmetic (`'z' - 'a'` = 25), range tests
      (`ch >= 'A'`), and comparison against `charAt` results (`charAt s i = 'a'`). All green on run-ir/JS/Rust.
      conformance +5.

## K22 — float math (prelude)

- [x] **K22.1 — `fabs` / `fmin` / `fmax` / `fsign`** added to the prelude (alongside the existing `fsqrt`/`fneg`).
      All are definable from the kernel float prims that exist (`flt`, `fneg`): `fabs = if flt x 0 then fneg x
      else x`, `fmin`/`fmax` via `flt`, `fsign` via two `flt`s. Pure prelude (ssct-hm source) addition — auto-
      injected only when used, no infer/erase/backend change — so they run identically on run-ir/JS/Rust.
      **`floor`/`ceil`/`round` are NOT added: they need a float→int truncation primitive the frozen ssc0 kernel
      doesn't expose** (kernel float prims are only `add/sub/mul/div/lt/eq/neg/sqrt`; `ToFloat` is Int→Float
      only). conformance +5.

## K23 — record patterns in match arms

- [x] **K23.1 — `match r { {f = subpat, …} => body }`** (records in match, completing record patterns alongside
      K19's `let`-destructure). New `PRec(fields)` pattern node parsed by `parseRecPat`/`parseRecPatFields`
      (`{` wired into `parseAtomPat` + `patStarts` so it also nests, e.g. `{x = Some n}`). Records are
      single-constructor (always match), so `compilePat`'s new `PRec` case binds each field via the
      type-directed `FieldGet` (`compileRecFields`) — **order-independent** (by name), supports nested
      sub-patterns (a `PVar` binds directly; anything else binds to a fresh var then `compilePat`s the
      sub-pattern), and `compileSubs` also gets a `PRec` arm so a record pattern can nest inside a constructor
      pattern. `armOfPat` routes `PRec` to `PatArm` (→ the match compiler); `patVars` gets a `PRec` arm.
      `{x=a, y=b}`, `{y=b, x=a}` (order-free), `{x = Some n}` (nested) all green on run-ir/JS/Rust. **Shared
      pre-existing limitation** (not new): matching a record pattern against a *polymorphic function parameter*
      fails with "field access on a non-record" — exactly like `.f` access and `let`-destructure on a fn param,
      since HM here doesn't infer a record type from field access (would need row polymorphism). The common
      concrete-record-scrutinee case works. No regression: ctor/tuple/list/literal patterns unchanged.
      conformance +5.

## K24 — prelude combinators + utilities

- [x] **K24.1 — `compose` / `flip` / `min` / `max` / `elem` / `notElem` / `product` / `last` / `null` / `join`.**
      Probed (2026-06-29): all were unbound. All are one-liners definable in ssct-hm, added to the auto-injected
      prelude (no infer/erase/backend change). `min`/`max` use overloaded `<` so they are polymorphic via
      inlining (work on Int/Float/String — `min "banana" "apple"` = "apple"). `elem`/`notElem` use overloaded
      `=`. `join` concatenates a `[String]` with a separator via `++`. ⚠️ GOTCHA: the prelude injects so that a
      *depender* must come BEFORE its dependency in the list (like `concatMap` before `append`) — `notElem`
      (uses `elem`) had to be listed before `elem`, else "unbound variable". Also: don't reuse an existing
      example filename (`hm-prelude2.hm` already existed — conformance caught the clobber; used
      `hm-preludecombi.hm`). All green on run-ir/JS/Rust. conformance +5.

## K25 — as-patterns

- [x] **K25.1 — `name@pat`** binds the whole matched value to `name` AND destructures via `pat`. New `PAs(name,
      sub)` pattern node. `parseFullPat` handles a lowercase name followed by `@` (sub = a full pattern, e.g.
      `all@(Cons h t)`, `n@5`, `xs@Nil`); `parseAtomPat` also handles `@` (sub = an atom) so an as-pattern works
      as a *constructor sub-pattern* (`Some xs@(Cons h t)`). `compilePat`'s `PAs` case is `Let(name, scrut,
      compilePat(scrut, sub, …))` — bind then match; `compileSubs` gets a `PAs` arm (nested), `armOfPat` routes
      `PAs`→`PatArm`, `patVars` gets a `PAs` arm. Real `dedup` using `Cons a rest@(Cons b t)` runs on all 3
      backends. No regression. conformance +5. (Note: `match <scalar> { x => … }` — a single var-only arm on a
      scalar — still crashes at run-ir; that's a PRE-EXISTING issue, unrelated to as-patterns.)

## K26 — operator sections

- [x] **K26.1 — right operator sections `(op e)`** = `fun x => x op e`, for point-free `map`/`filter`:
      `(+ 1)`, `(* 2)`, `(/ 2)`, `(< 5)`, `(>= 0)`, `(= 0)`, `(<> 3)`, `(++ "!")`. `parseParenOrTuple` now peeks
      the first token after `(`: if it's a section op (`isSectionOp` = `+`/`*`/`/`/`++` ∪ the comparison ops),
      it parses `op e )` into `Lam("$sec", mkSectionOp(op, Var "$sec", e))`; otherwise the original paren/tuple/
      ascription parser (`parseParenBody`) runs. `-` is deliberately excluded (it's negation — `(-5)` stays a
      negative literal). `map (+ 1) [1,2,3]`→`[2,3,4]`, `filter (< 3) …`, `(++ "!") "hi"`→`"hi!"` all green on
      run-ir/JS/Rust. No regression: `(e)`, `(a, b)`, `(e : T)`, negation, binary subtraction unchanged. Pure
      front-end desugar (0 backend change). conformance +5.

- [x] **K26.2 — left operator sections `(e op)`** = `fun x => e op x`, the complement to K26.1:
      `(10 -)`, `(100 /)`, `(2 *)`, `(5 <)`, `("hi" ++)`. The `-` ambiguity that excludes `-` from *right*
      sections doesn't apply here — in a left section `-` *follows* the operand, so `(10 -)` is unambiguously
      `fun x => 10 - x` (never a negative literal). Parse strategy = **try-then-fallback** (no lookahead needed):
      `tryLeftSection` runs `parseApp` on the paren body to grab an application-level left operand `e`, then
      `isLeftSection` checks the remainder is exactly `<op> )` (a `isLeftSectionOp` punct = `-` ∪ `isSectionOp`,
      then `)`); on a hit it builds `Lam("$lsec", mkSectionOp(op, e, Var "$lsec"))` and drops the `)`, otherwise
      it re-parses from the *original* tokens via `parseParenBody`. `mkSectionOp` gains a `-`→`Sub` case (reached
      only from left sections; right sections never pass `-`). `parseParenOrTuple`'s non-right-section branches
      now call `tryLeftSection`. No false positives: `(a < b)`, `(a, b)`, `(a : T)`, `(3 + 4)`, `(- 5)` negation,
      binary `10 - 3`, nested `(2 * (3 + 4))` all unchanged (verified — the `parseApp` probe either fails the
      `isLeftSection` shape or is discarded by the fallback re-parse). `map (100 -) [10,20,30]`→`[90,80,70]`,
      `(20 /) 4`→`5`, `filter (10 <) …` all green on run-ir/JS/Rust. Pure front-end desugar (0 backend change).
      conformance +5.

## K27 — string split / words / lines

- [x] **K27.1 — `split` / `words` / `lines`** (prelude). `split c s` splits string `s` at every occurrence of
      separator **char code** `c` (so `split ',' s` works thanks to K21 char literals = Int codes, or `split 44
      s`); returns `[String]`. Implemented in ssct-hm via a `let rec go` that walks the string with `charAt` +
      `substr` + `strLen`. `words = split 32` (space), `lines = split 10` (newline). Roundtrips with `join`
      (`join "," (split ',' "x,y,z")` = `"x,y,z"`). `words`/`lines` are listed BEFORE `split` (depender-before-
      dependency). Pure prelude — auto-injected when used, 0 backend change, all green on run-ir/JS/Rust.
      conformance +5. (Basic splitter: consecutive separators yield empty fields, like a naive `split`.)

## K28 — single var-arm match (robustness)

- [x] **K28.1 — `match scrut { x => body }` no longer crashes.** A match whose only arm is a variable
      catch-all desugared (via `mkMatch`) to `match x { _ => body }` — a `match` with NO constructor arms — and
      the VM crashed inspecting a scalar scrutinee's (nonexistent) tag. `mkMatch` now special-cases a single
      var arm (`dropLast(arms)` is `Nil`) to `Let(x, scrut, body)` — i.e. exactly `let x = scrut in body`, no
      match node. `match 7 { x => x+1 }` ⇒ 8 on all 3 backends; multi-arm matches with a trailing var arm,
      ctor/literal/list/tuple/as-patterns all unchanged. (⚠️ ssc0 kernel pattern parser rejects a bare
      lowercase var pattern like `case others =>` — use `case _ =>`.) conformance +5.

## K29 — prelude, batch 2

- [x] **K29.1 — `takeWhile` / `dropWhile` / `span` / `partition` / `scanl` / `lookup` / `maximum` / `minimum` /
      `count` / `nub` / `enumerate`** added to the prelude (all were unbound). One-liner ssct-hm defs; inserted
      at the TOP of the `prelude` list so they can reference the existing combinators (depender-before-dependency
      injection rule). `maximum`/`minimum` (over `<`) and `lookup`/`nub` (over `=`) are polymorphic and ride the
      Num/Ord dict-passing. `span`/`partition` return a tuple; `enumerate` = `zip (range 0 (length xs)) xs`;
      `lookup` returns an `Option`. All green on run-ir/JS/Rust. conformance +5.

## K30 — string functions, batch 2

- [x] **K30.1 — `startsWith` / `endsWith` / `strContains` / `trim`** (prelude). All built from the existing
      `substr` / `charAt` / `strLen` intrinsics + string `=` (K14) — no new kernel/IR primitive, so they run on
      run-ir/JS/Rust unchanged. `trim` walks in from both ends skipping spaces (code 32) then `substr`s;
      `strContains` is a sliding `substr =` search. `trim "  hi  "` ⇒ `"hi"`, `startsWith "he" "hello"` ⇒ true.
      (⚠️ `toUpper`/`toLower` NOT added — they need to rebuild a string from char codes via `sfromCodes`, which
      the JS/Rust gen `genPrim` doesn't expose as an IR prim; would need backend support.) conformance +5.

## K31 — empty match (robustness)

- [x] **K31.1 — `match s { }` (no arms) → clean error, not a crash.** `parseArms` always tried to parse ≥1 arm,
      choking on the closing `}` → a `ParseErr` that crashed downstream. Now `parseArms` returns no arms when the
      next token is `}`, so `inferArms` reaches its existing `Nil`→`ErrI("empty match")` branch and reports a
      clean `TypeError: empty match`. Non-empty matches (ctor/literal/var/guard/as/record/tuple) unchanged.
      conformance +1.

## K32 — string building (fromCodes → toUpper/toLower/chr)

- [x] **K32.1 — `fromCodes : [Int] -> String` intrinsic + `chr` / `toUpper` / `toLower`.** This was the one
      true *capability* gap (the SPRINT K30 note flagged toUpper/toLower as backend-blocked). The kernel run-ir
      already has the `sfromCodes` IR prim, so the work was: a new `FromCodes(e)` node (recognized as a 1-arg
      intrinsic in `dsApp`, like `strLen`; infer `[Int]->String`; erase → `IrPrim("sfromCodes", …)`; interp via
      `hostCodesToKernel` + `#sfromCodes`), plus **backend support** — JS `genPrim` emits an inline IIFE that
      walks the `{t,f}` Cons-list `String.fromCharCode`-ing each, and the Rust `genPrim`/preamble gain an
      `sfromcodes(&V)` helper that walks the `V::D("Cons", …)` list building a `String`. Prelude then defines
      `chr c = fromCodes (cons c nil)` and `toUpper`/`toLower` (walk the string, shift `a..z`/`A..Z` codes,
      `fromCodes` the result). `toUpper "ab3X!z"` ⇒ `"AB3X!Z"`, `toLower (toUpper "Hi") ` round-trips; all green
      on run-ir/JS/Rust. conformance +5. (String-building is now general — any char-list transform works.)

## K33 — transcendental floats (mathx: exp / ln / sin / cos / tan / pow / sqrt / pi)

- [x] **K33.1 — `exp` / `ln` / `sin` / `cos` / `tan` / `pow` / `sqrt` / `pi`.** The kernel exposes only
      `fsqrt`/`fneg` as float prims; `fabs`/`fmin`/`fmax`/`fsign` were already prelude-built. This closes the
      `mathx.*` Backlog open — and entirely as **pure ssct-hm prelude source** (0 kernel change, 0 backend
      change): each transcendental is a finite Taylor/Maclaurin series over `+ - * /` plus `fneg`, driven by a
      `let rec go` term-accumulator (`exp` 30 terms, `sin`/`cos` 20, `ln` 40). `ln` is **range-reduced by `e`**
      first (`let rec red` divides/multiplies `y` by `e` into `[1/e, e]`, accumulating the count `k`, then runs
      the `atanh`-series `2·Σ t^(2n+1)/(2n+1)` which converges fast there) — so it's accurate for *all* `x>0`,
      not just `x≈1` (the naive series diverges at `ln (exp 5)≈148`; range reduction fixed `1011111`→`1111111`).
      `pow x y = exp (y · ln x)`, `tan = sin / cos`, `sqrt = fsqrt`, `pi` a constant. Each is monomorphic
      `Float -> Float` (the float literals + `flt` anchor the type — no numeric-overload ambiguity). Prelude
      ordering is depender-before-dependency (`pow` above `exp`/`ln`, `tan` above `sin`/`cos`).
      **Cross-backend: bit-identical.** run-ir (Scala `Double`), JS (`Number`), Rust (`f64`) are all IEEE-754
      doubles and the op order is fixed by the shared source, so `exp 1.0` = `2.7182818284590455` *exactly* on all
      three (conformance asserts this directly). The mathx example sums seven `near`-comparisons → `1111111` on
      run-ir/JS/Rust; extra checks cover `ln 1000`, `ln 0.01`, `pow 3 4`. conformance +5.

## K34 — float rounding (floor / ceil / round / trunc / rint)

- [x] **K34.1 — `floor` / `ceil` / `round` / `trunc` / `rint`.** The K6 float-math note (and the conformance
      comment) claimed *"floor/ceil need a kernel prim"* — **wrong** (another "too-hard" misjudgment): they need
      no float→int conversion at all. `rint` (round to nearest integer, ties-to-even) is the classic IEEE trick
      `(x + 1.5·2^52) - 1.5·2^52` where `1.5·2^52 = 6755399441055744.0`. Adding the magic number forces `x` into
      `[2^52, 2^53)` where the ULP is exactly `1.0`, so the IEEE round-to-nearest-even of the addition lands on
      the nearest integer; subtracting it back recovers `rint x` (valid for `|x| < 2^51`). Then `floor x = let r =
      rint x in if x < r then r-1 else r`, `ceil` symmetric, `round x = floor (x + 0.5)` (half up toward +∞),
      `trunc x = if x<0 then ceil x else floor x`. **First magic constant `2^52` alone is wrong for negatives**
      (`x+2^52 < 2^52` drops into the ULP=0.5 band → off by 0.5); `1.5·2^52` keeps it in the ULP=1.0 band for both
      signs. All pure prelude (0 kernel/backend change), monomorphic `Float -> Float`. Round-to-nearest-even is
      the IEEE default on run-ir (`Double`), JS (`Number`), Rust (`f64`), so results are **identical across all 3
      backends** (`rint 2.5 = 2.0`, `rint 3.5 = 4.0` — banker's rounding everywhere). Example sums seven
      near-checks → `1111111` on all backends. conformance +4.

## K35 — math library complete (inverse trig / hyperbolic / log bases / cbrt / hypot)

- [x] **K35.1 — `atan`/`asin`/`acos`, `sinh`/`cosh`/`tanh`, `cbrt`, `hypot`, `log2`/`log10`/`logBase`; `exp`
      hardened.** Rounds the mathx float library out to typical-stdlib parity, all pure prelude (0 kernel/backend
      change), all via the K33 series pattern. **`atan`** is the only non-trivial one: the Maclaurin series
      `x - x³/3 + …` converges hopelessly near `|x|=1` (Leibniz), so it's preceded by a **double half-angle
      reduction** `atan x = 4·atanSeries(r2)` where `r = a/(1+√(1+a²))` applied twice — shrinks any argument
      (even `atan 10`) into the fast-converging zone. `asin x = atan(x/√(1-x²))`, `acos = π/2 - asin`. Hyperbolics
      are one-liners off `exp` (`sinh = (eˣ-e⁻ˣ)/2`, etc.; `tanh = sinh/cosh` is even self-correcting for large
      `x` since `e⁻ˣ→0`). `cbrt` = sign-aware `exp(ln|x|/3)`, `hypot = √(a²+b²)`, `log2`/`log10` divide `ln x` by
      the constant, `logBase b x = ln x / ln b`. **`exp` is now range-reduced by halving** (`exp x = exp(x/2)²`
      recursing until `|x|≤1`, then the 30-term series) so it (and `sinh`/`cosh`/`pow`) is accurate for large `|x|`
      too — and `exp 1.0` stays *bit-identical* (`|1.0|>1.0` is false → still the plain series). All deterministic
      across run-ir/JS/Rust (IEEE-754, fixed op order); a 14-check example sums to `14` identically on all three.
      conformance +4. **The float-math story is now complete: arithmetic + comparison + abs/sign/min/max +
      rounding + sqrt/cbrt + exp/ln/log-bases + full trig + inverse trig + hyperbolic + pi/hypot.**

## K36+ — clear the remaining backlog (everything except WASM) — user mandate 2026-06-29

User: "бери все, занеси в спринт, и делай … все кроме wasm". All non-WASM Backlog/Remaining items, planned as slices:

- [x] **K36 — `hash.sha256` DONE** (conformance +1). From-scratch SHA-256 in raw ssc0 (`lib/sha256.ssc0`,
      ~70 defs) over the kernel's bitwise (`#i.and/or/xor/shl/ushr/not`) + byte (`#str->utf8`/`#blen`/`#bget`) +
      mutable-array (`#arr.new/push/get/set/len`) prims, masked to 32 bits (`#i.and(x, 4294967295)`); imperative
      sieve-style threading (`let u = #arr.set(…) in …`). `rotr`/`shr`/`Ch`/`Maj`/`Σ`/`σ` helpers, 64-word
      schedule, 64-round compression, padded big-endian length, hex via `#sfromCodes`. **VM-only (run-ir) BY
      DESIGN** — JS bitwise is 32-bit-signed vs run-ir/Rust 64-bit, so raw bitwise isn't cross-backend-sound
      (same reason `#arr`/`#map` programs are VM-only). **Vector-gated**: `sha256Hex` of `""`/`"abc"`/`"hello"`/
      an 85-byte multi-block input all match the standard vectors (self-check returns 4). `sha256Hex "abc"` =
      `ba7816bf…20015ad`. Kernel +0 (still 913).
- [x] **K37 — structural map keys DONE** (conformance +1). `lib/mapx.ssc0` = a structural-equality `valEq` +
      an immutable assoc-list map (`mapxInsert/Lookup/Has/Remove/Keys/Size/FromList`) keyed by it, so ANY value —
      ints, strings, tuples, ADTs, nested — is a valid key. **`valEq` trick:** the frozen kernel has no generic
      equality prim, and `#tagOf` errors on scalars (no typeof to discriminate scalar-vs-Data), BUT the mutable
      `#map` is a Scala `HashMap[Value,Value]` that compares keys STRUCTURALLY — so
      `valEq a b = let m=#map.new() in (#map.put(m,a,0); #map.has(m,b))` uses a one-shot map as an equality
      ORACLE (true iff `a` structurally equals `b`). VM-only (like all `#map`/`#arr` programs). Demo:
      tuple/triple/int/nested-ADT keys looked up by FRESHLY-BUILT equal keys (structural, not identity) →
      `pair|triple|int|nested|?`; overwriting a key keeps `size=4` (structural dedup). Kernel +0.
- [x] **K38 — bare-`#prim` η-expansion DONE** (conformance +1). A bare `#op` used as a *value* (not applied)
      used to error ("wrap it"). The self-hosted **`lib/ssc0c.ssc0`** now lowers it to `(x0..xn-1) => #op(x0..xn-1)`
      via a `primArity` table (`isArity0`/`isArity1`/`isArity3` sets over the kernel prims, default 2 = the binary
      majority; max arity 3 → fixed param names `$e0..$e2`, no string-building). So primitives are first-class:
      `map #i.neg [1,2,3]` → `[-1,-2,-3]`, `foldl #i.add 0 [1,2,3,4]` → `10`. **Kept the frozen Scala bootstrap
      front untouched** (still 913, still rejects bare prims by design) — ssc0c is the real compiler, so the demo
      compiles via `bin/ssc0c.ssc0` → ir → run-ir. The self-hosting fixpoint + differential checks still pass
      (the new η-code itself uses no bare prims, so the Scala front and ssc0c agree on it). Kernel +0.
- [x] **K39 — typed handler resumes DONE** (bounded, sound; conformance +5). The general `handle` op clause was
      typed `String -> Dyn -> (Dyn -> Comp r b) -> Comp r b` (op arg AND resume input both untyped `Dyn`). Now,
      for a **single-op TYPED effect** `effect L { op : A -> R }`, `singleOpSigOf(L)` finds the lone op's
      signature and the clause is typed `String -> A -> (R -> Comp r b) -> Comp r b` — so the handler's op-arg is
      `A` and its **resume `k` is `R -> Comp r b`** (typed!), with no `Dyn` ascriptions. Multi-op / untyped
      effects keep the Dyn fallback (one op-clause lambda can't type resumes for several differently-typed ops —
      that's the per-op-syntax research case, left as the boundary). **Purely static** (erase/runtime unchanged →
      all 3 backends): `effect Ask { ask : Int -> String }` + a handler using `(a + 1)` (a:Int) and
      `k (showInt …)` (k:String→) type-checks → `2` on run-ir/JS/Rust; resuming with the wrong type
      (`k (a + 1)` where k expects String) is a clean `TypeError`. SAFE: `TyDyn` unifies with anything and every
      existing effect test uses untyped/multi-op decls, so none is affected. Kernel +0.
- [x] **K40 — `v2-bin` compact binary IR DONE** (conformance +2). `lib/irbin.ssc0` = a bidirectional binary
      codec for the Core-IR Data tree: `binEncode : ir -> #arr of bytes`, `binDecode : #arr -> ir`. Each of the
      14 node tags + 6 const tags + `Cons`/`Nil` + `Some`/`None` gets a 1-byte tag; integers are **LEB128
      varints** (unsigned for tags/arities/locals/lengths, **zig-zag** for signed int literals) so small values
      are 1 byte (compact); strings = uvarint char-count + one uvarint per UTF-16 code unit (`#scodeAt`/
      `#sfromCodes` — no UTF-8 juggling); floats via `#f->str`/`#str->f`; BigInt via `#big->i`/`#i->big`. The
      decoder threads a position (`Pair(node, pos')`). **Round-trip invariant** `#coreir.encode(binDecode(
      binEncode(ir))) == #coreir.encode(ir)` holds on a tree exercising EVERY node type; the binary is **108
      bytes vs 334 S-expr chars** (~3× smaller). Plus an EXECUTABLE round-trip: a runnable program's IR →
      binary → back to S-expr → `run-ir` → `42` (the format preserves executable semantics). VM-only (`#arr`);
      the kernel still reads S-expr — bin is a tooling layer. Kernel +0. GOTCHAS: raw ssc0 has no `-` literal
      (use `#i.neg(1)`); no `;` sequencing in arm bodies (use `let u = … in`); `#str->f`/`#str->i` return
      `Option` (unwrap); emit raw via `#io.print` (not a bare String, which prints quoted).
- [x] **K41 — effect-row inference DONE** (conformance +5). On investigation this was already substantially
      **implemented by K10/K11** and is more complete than the "research/deferred" note suggested — so this slice
      verifies it at its hardest point and documents it. The HM layer has full **Rémy/scoped-label row
      unification** (`rowUnify`/`rowRewrite`/`rowFresh`), computation types `Comp ρ a`, row vars generalized at
      `let` (`freeTy` counts `TyRowVar`), and `runE` demanding `TyRowEmpty`. Concretely it ALREADY infers
      (no annotation): `getE : Comp {State | e0} Dyn` (polymorphic tail); `runE getE` → "effect not handled:
      State"; two effects track `{State, Log}` with partial handling rejected; AND — the hardest case —
      **effect-POLYMORPHIC higher-order functions**: `traverseE` infers `(a -> Comp e b) -> [a] -> Comp e [b]`,
      the row var `e` threading from the callback through the whole traversal. The new `hm-eff-traverse.hm`
      runs a State-performing traversal (running prefix-sum) → `([1,3,6], 6)` identically on run-ir/JS/Rust, and
      the type-level check asserts `traverseE`'s principal type carries the propagated row var. **Boundary**
      (genuinely the research frontier, not shipped): nothing blocking found at this level — what remains is only
      exotica like first-class effect-row abstraction beyond `let`-polymorphism. Kernel +0.

## K42+ — K3 breadth roadmap (stdlib + showcases) — user mandate 2026-06-29

User: "Сделай K3 breadth roadmap". The actionable breadth is stdlib + real showcase programs (the backends —
JS/Rust as ssc0 programs — are done; WASM toolchain-blocked; JVM = the VM itself). Slices:

- [x] **K42 — tuple types in ADT field positions DONE** (conformance +4). `data T = C (A, B)` used to fail
      ("unbound variable"): `parseFieldType`'s `(` case ran `parseFnType` + `drop1` (closing paren), handling
      `(a -> b)` / `(F a)` but treating a comma as garbage. New `parseParenType` parses `T (',' T)*  ')'` and
      builds `TyCon("Pair", [t1,t2])` / `TyCon("Triple", [t1,t2,t3])` (matching value-tuple desugaring), falling
      back to the bare parenthesized type when there's no comma — so single fn-type fields (`Box (Int -> Int)`)
      are unchanged. `data Rec = Rec (String, Int) (Int, Int, Int)` → Pair + Triple fields, 5+1+2+3 = 11 on
      run-ir/JS/Rust; nested ADTs + existing tuple tests unaffected. Unblocks idiomatic ADTs incl. JSON's
      `[(String, Json)]`. Kernel +0.
- [x] **K43 — JSON library showcase DONE** (conformance +4). `examples/hm-json.hm`: `data Json = JNull |
      JBool Bool | JNum Int | JStr String | JArr [Json] | JObj [(String, Json)]` (uses K42 tuple fields) + a
      recursive **serializer** `showJson` (compact) + a full **recursive-descent parser** (mutual recursion
      `parseValue`/`parseArr`/`parseObj`, char-code dispatch via `charAt`/`strLen`, whitespace skipping, strings,
      signed numbers, bool/null) + accessors (`lookupJ`/`numOf`). Roundtrips a whitespace-formatted
      `{ "name": "ada", … "neg": -7 }` → compact `{"name":"ada",…,"neg":-7}` (len 72), idempotently, extracting
      `age`=36. Encoded as Int **3610072** (= 36·1e5 + idempotent·1e4 + 72) — Int dodges the cross-backend
      string-DISPLAY divergence (run-ir shows inner quotes raw, JS/Rust escape; value identical). Green on
      run-ir/JS/native-Rust. Big program → VM-interpreted typechecker needs `-Xss512m` (like the ssc0c fixpoint).
      GOTCHAS: ssct-hm equality is `=` (not `==`); comments `//`; `\"` escapes work.
- [x] **K44 — Either / Result combinators DONE** (conformance +4). 8 prelude functions over the built-in
      `Left`/`Right`: `mapRight`/`mapLeft` (map one side), `either` (eliminate), `isLeft`/`isRight`,
      `fromRight`/`fromLeft` (with default), `partitionEithers : [Either a b] -> ([a], [b])` (via `foldr`). All
      properly polymorphic (`mapRight : (b -> c) -> Either a b -> Either a c`). Error-handling breadth alongside
      the Option combinators. Example exercises all 8 -> `143` on run-ir/JS/Rust. Kernel +0.
- [x] **K45 — `lib/set.ssc0` structural Set DONE** (conformance +1). A set keyed by any value (int/str/tuple/
      ADT/nested) over the K37 `#map` equality oracle: `setEmpty`/`setInsert`/`setMember`/`setFromList`/
      `setToList`/`setSize`/`setUnion`/`setInter`/`setDiff`/`setSubset`. VM-only (like `#map`/`#arr`). Demo:
      `{1,2,3}` (deduped from `[1,2,3,2,1]`) vs `{2,3,4}` → ∪=4, ∩=2, ∖=1, member ✓; plus structural tuple dedup
      (`{(1,2),(1,2),(3,4)}` → size 2). Result `234211`. Kernel +0.

- [x] **K46 — reconcile stale v2 status docs + async/actor breadth DONE** (claim:
      `v2-k46-async-actors-roadmap`). Reconciled `v2/ROADMAP.md`, `v2/README.md`,
      `specs/10-core-ir.md`, and `specs/60-backend-js.md` with K45 reality. Added
      `specs/56-async-actors-breadth.md`, then shipped `runAsync` in `lib/async.ssc0`:
      futures/promises (`future`/`await`), buffered integer channels (`send`/`recv`), and
      mailbox aliases (`mailboxSend`/`mailboxReceive`) on the existing `Comp` effect model.
      Examples: `async-future`, `async-channel`, `async-channel-buffer`, `async-mailbox`;
      all run on VM/JS/Rust via `conformance/check.sh`. Kernel +0. Gotcha: direct `yield =
      Op(...)` avoids eager top-level value ordering issues in generated JS; JS/Rust generation for
      the richer raw scheduler uses `java -Xss512m -jar` like the JSON showcase.

- [x] **K48 — Multi-op typed handler resumes DONE** (2026-06-29; spec:
      `specs/57-multi-op-handler-resumes.md`). Added `handleM "L" m { | op1 a k => b1 |
      op2 a k => b2 } retf`, a total per-operation handler form for declared effect ops. Each
      arm's arg/resume comes from that op's signature (`ask`: `k : Int -> Comp`; `tell`:
      `k : String -> Comp`), and type checking rejects missing, duplicate, unknown, or foreign-label
      arms so the generated dispatcher's fallback is unreachable. Erases to existing `__effHandle`;
      no kernel/backend change. Added `examples/hm-eff-multiop.hm` and conformance coverage for HM
      type, run-ir, JS, Rust, row composition with `Log`, wrong-resume, missing-arm, foreign-arm, and
      duplicate-arm negatives. Targeted verification passed via launchers and `/tmp/ssc-conformance.jar`:
      `"Int"`, VM/JS/Rust `42`, negatives as `TypeError`. Full `conformance/check.sh` exposed an
      unrelated intermittent empty-output/rustc flake, queued as K49 below.

- [x] **K49 — full conformance intermittent empty-output flake DONE** — `./conformance/check.sh`
      twice produced a contiguous block of unrelated `got []` failures after an unrelated Rust
      backend `(rustc err)` while direct reruns of the first failing examples passed. Observed
      2026-06-29 while testing K48: first run failed around `hm-method-self`/mutual/quad; second
      run failed around `hm-eff-handle` through K48 happy-path checks, then recovered. Targeted K48
      checks via both launchers and the assembled `/tmp/ssc-conformance.jar` passed (`"Int"`,
      VM/JS/Rust `42`, negatives as TypeError). Likely harness/tooling flake, not a feature
      regression. Done when `check.sh` captures per-command stderr/logs (especially Java/rustc),
      avoids opaque empty stdout failures, and a full run is stable across two consecutive runs.
      Closed 2026-07-01 in `d4ca120bf`: diagnostics reproduced the real cause as the shared
      `/tmp/ssc-conformance.jar` being overwritten/corrupted by concurrent or repeated harness runs
      while Java was still executing it (`NoClassDefFoundError: ssc/Program$`, then `Invalid or
      corrupt jarfile`). The harness now builds the jar under its unique
      `$TMPDIR/ssc-conformance-logs-$$/` directory, captures Java/Rust stderr and stdout artifacts,
      retries empty Java stdout once, and prints a diagnostic summary on failure. Verification:
      `bash -n v2/conformance/check.sh`; two consecutive full `cd v2 && ./conformance/check.sh`
      runs passed after the per-run jar change (`run1 exit=0`, `run2 exit=0`); after rebasing on
      KC7, a final full run including KC7 checks also passed (`final exit=0`).

- [x] **K47 — Array-env VM optimization DONE** (`type Env = Array[Value]` replacing
      `List[Value]`; `Local(i)` is now O(1) via `env(env.length - 1 - i)` instead of
      O(i) linked-list scan). `extend`/`appendOne` replace `prepend`; de Bruijn convention
      unchanged (last binding = Local(0), achieved by appending in order). `LetRec` cyclic
      frame-tie unchanged (still `var env`). All changes in `v2/src/Runtime.scala` +
      `v2/src/Main.scala`. `conformance/check.sh` all green. Kernel +0.

- [x] **K50 — binary method signatures (`method m : self -> R`)** — DONE 2026-06-30 (commit 96475b20e).
      Two fixes: (1) `selfRes` now recurses into `TyFun`/`TyList`/`TyCon` args; (2) `parseMethodDecl` uses
      `parseFnType` instead of `parseAscType` so `->` parses in method sigs. Example: `hm-method-binary.hm`
      (`method smaller : self -> Bool`; `myMin`; type `(Int, Float)`; all 3 backends → `Pair(3, 1.5)`).

- [x] **K51 — ssct-hm stdlib expansion** — DONE 2026-06-30. Added 13 prelude functions in two groups:
      (a) Assoc-list map ops: `assocInsert`, `assocDelete`, `assocMapKV`, `assocUnionWith` (lookup was existing).
      (c) Parser combinators: `pResult`, `pChar`, `pStr`, `pDigit`, `pSeq`, `pAlt`, `pMap`, `pMany`, `pInt`.
      (b+d) `sortBy` was already in prelude; Writer/Reader effect wrappers deferred to BACKLOG.
      Fix: injectPrelude is a left-fold so new entries must precede their prelude dependencies in the list.
      `assocUnionWith` works on JS (polymorphic `===`); VM/Rust get "Int" type tag for String keys (light-qt limit).
      Examples: `hm-stdlib-map.hm` (30055, JS-only full test) + `hm-parser-comb.hm` (11, all 3 backends).
      Conformance: chk_hm + JS for map; chk_hm + run-ir + JS + Rust for parser-comb (all pass). 2c0824c73.

- [ ] **K51-followup — tagOfArg element-type inference for literal list/pair args** — salvaged from the
      dropped duplicate-K51 branch `feature/v2-dict-pass-showcases` (deleted 2026-07-07 hygiene sweep; K51
      itself landed independently). Its unique fix: dict-fns called with LITERAL list/alist arguments
      default the element type to Int. Add to `tagOfArg` in `v2/lib/mira-emit.ssc0` two cases:
      `case LCons(h, t) => tagOfArg(h)` and inside ConApp:
      `case ConApp(name, args) => (if #seq(name, "Pair") then tagOfArg(nthArg(args, 0)) else conTyName(name))`.
      Repro/gate: a dict-fn over a literal `[("a",1)]`-style alist with String keys must infer "String", not
      "Int"; add a conformance case (the dropped branch verified green on run-ir/JS/Rust with this shape).

- [x] **K52 — showcase programs** — DONE 2026-06-30. Two self-contained programs on all 3 backends:
      (a) `hm-lambda.hm`: lambda calculus interpreter (ADTs + subst + reduce + showE); `(const (id a) b)` → `"a"`.
      (b) `hm-arith-parser.hm`: recursive-descent arithmetic parser; `"1+2*3"` → 7 (correct * > + precedence).
      Both have conformance tests in check.sh (type check + run-ir + JS + Rust).

- [x] **K53 — benchmarks / profiling DONE** 2026-06-30. (a) `scripts/bench interp` post-K47 baseline
      captured (29 InterpreterBench benchmarks; key: `recursionFib`=1.176ms, `typeclassFoldMacro`=1.350ms,
      `tupleMonoid`=0.007ms, `valIntermediate`=0.254ms). Full table in `v2/specs/k53-bench-baseline.md`.
      (b) ssct-hm on hm-json.hm: ~3s wall / ~0.5s user CPU; JVM startup ~2.5s dominates. Hot path =
      HM unifier + let-poly over 90-fn prelude + 300-node JSON program. (c) No >20% CPU win
      identified from timing alone — JFR of short-lived scala-cli process is non-trivial; optimization
      deferred to BACKLOG. Merged: `feature/v2-k53-bench-profile` (964b28113).

**K3 BREADTH STATUS:** the actionable K3 roadmap is substantially delivered — stdlib now has list/string/map/
mapx/set/option/stream + a ~90-fn Mira prelude (incl. Either + full math); the type system is a complete
HM language (now with tuple-typed ADT fields); effects/actors/async are libraries (K46 adds futures/channels/
mailboxes); backends JS+Rust are ssc0 programs; and the JSON showcase proves a real program compiles to all
3 targets. Remaining is open-ended breadth (more libs/showcases on demand) + WASM (toolchain-blocked).

---

## K60 — Mira rename + fence language registry

- [x] **K54 — rename ssct-hm → Mira DONE** 2026-07-01. 66 files changed: lib/ssct-hm*.ssc0 →
      lib/mira*.ssc0; bin/ssct-hm*.ssc0 → bin/mira*.ssc0/mirac.ssc0; launchers v2/ssct-hm → v2/mira
      + v2/mira-js + v2/mira-rust; specs/41-ssct-hm.md → specs/41-mira.md; all imports+comments
      updated; conformance green (all 568+ ok). `v2/mira examples/hm-fact.hm` → "Int";
      `v2/mira-js` → 120 (node); `mirac` → Core IR → run-ir → 120. Merged: 84d6b28c6.

- [x] **K55 — Markdown extractor DONE** 2026-07-01. `lib/mira-md.ssc0` (ssc0, 130 lines):
      splitLines/stripYaml/startsWith3bt/isClosingFence/getFenceLang/go/extractFences.
      `bin/ssc-front.ssc0` driver + `v2/ssc-front` launcher. Conformance: `ssc run
      bin/ssc-front.ssc0 examples/hm-md-demo.ssc` → 2 blocks (mira + ssc0), YAML skipped.
      Implementation in ssc0 (not Mira) — avoids cross-language FFI; same pattern as mira.ssc0.
      Spec: `specs/61-fence-languages.md`.

---

## K61 — v1.0-compat frontend (KC1–KC8)

Goal: run existing v1.0 `.ssc` files on the v2 kernel (functional subset first, OOP later).
All written in Mira. Spec: `specs/60-compat-frontend.md`.
Prerequisite: K55 (Markdown extractor).

- [x] **KC2 — v1.0 lexer DONE** 2026-07-01. `examples/hm-lex.mira` (Mira, 130 lines):
      Token ADT (TKw/TId/TUId/TOp/TInt/TStr/TLParen-TRBrace/TComma/TDot/TColon/TSemi/TEq/TArrow/
      TUArrow/TAt/THash/TColonColon/TEof), skipWS+line-comments, scanEnd, scanStr/buildStr,
      parseIntR, lexPunct/lexOp helpers (split to reduce HM unifier depth), lex1 main loop.
      `lex "def f(x: Int) = x + 1"` → 12-token list. VM+JS+Rust all pass.
      Needs `-Xss512m` for type-checking (same as hm-json.hm). Conformance in check.sh.

- [x] **KC3 — v1.0 parser (functional subset) DONE** 2026-07-01. `lib/ssc1-front.ssc0` (ssc0,
      ~350 lines): combined KC2+KC3 lexer+parser. Lexer: 26 token kinds (includes `==`, `=>`, `->`,
      `::`). Parser: recursive-descent, tag-encoded AST (`Pair(tag, data)` — avoids ssc0 ADT
      limitations). Handles: `def`/`val` stmts, infix precedence climbing (prec 3–8), postfix
      `.field`/`(args)`/`[types]`, `if/then/else`, tuples, string/int/bool literals, prefix `-`/`!`.
      Type annotations stripped. Multi-stmt: semicolon-separated. ssc0 patterns: avoid nested
      constructor patterns (use nested match), avoid `-1` literal (use `#i.neg(1)`).
      Conformance: `ssc run examples/kc3-test.ssc0` → `SDef("f",[x],EInfix("+",EVar(x),EInt(1)))`.
      Tests: factorial, main(println(f(5))), multi-stmt parsing all pass.

- [x] **KC4 — functional lowering → Core IR DONE** 2026-07-01. `lib/ssc1-lower.ssc0` (~200 lines
      ssc0): de Bruijn name resolution (`lookupVar`), all arithmetic/comparison/boolean/string ops →
      Core IR prims, `def`→IrDef+IrLam, `val`→IrDef, `if`/`app`/`tup`/`pre`/infix all lowered.
      Injected builtins: `println`/`print` → `IrPrim("io.print")`. Entry = `IrApp(IrGlobal("main"),Nil)`.
      `bin/ssc1c.ssc0` + `v2/ssc1c` launcher. ssc0 GOTCHA: `_` inside constructor patterns (`case
      Cons(_, t)`) is INVALID in kernel parser — use real var names (`u`, `bodyIgnored`, etc.).
      Done-when test: `kc4-hello.ssc` ("Hello, World!") + `kc4-fact.ssc` (120) both run via `ssc run-ir`.

- [x] **KC6 — intrinsics mapping** — Map v1.0 stdlib calls to v2 primitives.
      Implemented as a **resolve-pass** (`resolveE`) in `lib/ssc1-lower.ssc0` that pre-processes
      the KC3 AST before de Bruijn lowering. No kernel changes needed — prims `slen`/`scodeAt`/
      `sslice`/`str->i`/`sconcat` all already existed.
      **New AST tags:** `"ctorap"` (IrCtor), `"prim"` (IrPrim/IrApp-to-helper).
      **Resolved:** `None`→IrCtor("None",[]), `Nil`→IrCtor("Nil",[]), `Some(x)`→IrCtor("Some",[x]),
      `List(...)` → nested Cons/Nil, `Left(x)/Right(x)/Cons(h,t)`.
      **String fields:** `.length/.size` → `slen`, `.substring(f,t)` → `sslice`,
      `.charAt(i)` → `scodeAt`, `.toString` → `i->str`, `.toInt` → helper `__str_toInt`.
      **List fields:** `.head/.tail/.isEmpty/.nonEmpty` → injected helper defs.
      **List methods:** `.map(f)/.filter(f)` → injected 2-arg `_sel_map/_sel_filter` defs.
      `.foldLeft(z)(f)` → curried 2-arg+1-arg `_sel_foldLeft` def (all with letrec, de Bruijn).
      **Infix `::` added:** `elem :: list` → `IrCtor("Cons",[elem,list])`.
      **Conformance:** kc6-str ("hello".length=5), kc6-substr (substring→"ell"),
      kc6-list (List.map.head=20), kc6-fold (List.foldLeft sum=6) — all green.
      **Deferred:** string `+` (type-ambiguous without KC5), list `.length` (vs string `.length`),
      `str.split`, `str.toUpperCase/toLowerCase`, `list.append(++)`.
      Done-when: ✓ string length/charAt/substring + List.map/filter/foldLeft + ctors.

- [x] **KC5 — type checker** DONE 2026-07-02. `lib/ssc1-check.ssc0` (425 lines): HM type
      inference (Algorithm W) over ssc1-front Pair-tagged AST. Types: Int|Str|Bool|Float|Dyn|
      Var(n)|Fun(a,b)|List(e)|Tup(es). TyDyn = escape hatch for OOP/constructors/builtins.
      Two-pass: collect all names → TyDyn, then infer bodies. Let-generalization + fresh vars
      (global cell). Context dict params (__tc_*) filtered before inference. Operators: `+`
      unifies operands (Int+Str → error); `-/*///%` force Int; `==/</>` same-type → Bool.
      ssc1c.ssc0 exits 1 with clear error on type mismatch. All 21 KC examples pass.
      conformance/check.sh: kc5 type-error 1+"a" test added.

- [x] **KC7 — OOP lowering DONE** 2026-07-01. Match expressions + case class → Core IR.
      **Parser** (`ssc1-front.ssc0`): `parsePat` (cpat/vpat/wpat), `parseMatchArm`,
      `parseMatchArms`, `parseMatchExpr` (prefix `match e {}`), postfix `e match {}` in
      `buildPostfix`, `parseCaseClass`, `skipToStmt`, `parseOneStmt` extended for
      `case class`, `sealed`, `abstract`, `object` (skipped). Two new AST tags: `"match"`,
      `"casecls"`.
      **Lowering** (`ssc1-lower.ssc0`): `appendL` (global), `buildCtorArgs` (builds
      `[IrLocal(n-1)..IrLocal(0)]`), `lowerMatch` (→ `IrLet + IrMatch`; pure vpat/wpat
      catch-all skips `IrMatch` to avoid crashing on non-Data scrutinee), `lowerCaseCls`
      (injects constructor `IrDef` + `_sel_field` accessor defs), `lowerStmtToList` (replaces
      `lowerStmt`; `casecls` emits multiple defs via `appendL`). `resolveE` + `lowerE` handle
      `"match"` tag recursively. `lowerProg` uses global `appendL`.
      **De Bruijn conventions** (arm scope): `appendL(revL(patVars), letScope)` — last field of
      ctor = local 0; first field = local(arity-1). vpat default uses `Cons(varName, scope)`
      so the variable maps to local 0 without IrMatch.
      **Conformance:** kc7-match (List head via Cons/Nil match=42), kc7-casecls (Point(3,4).x+y=7),
      kc7-opt (vpat+list head=10) — all green.
      **Deferred:** nested patterns, object methods (bodies are skipped), full inheritance.

- [x] **KC10 — var/while loops + if-without-else DONE** 2026-07-01.
      `var x = e` → `cell.new(e)` with scope entry `"@x"`. `x = v` → `cell.set`. Reads of `x`
      check for `"@x"` in scope → `cell.get`. `while (cond) body` → IrLetRec([IrLam(0,
      IrIf(cond, IrLet([body], recurse-via-Local(1)), Unit))], call). `if (cond) sideEffect`
      without else → else branch = mkTup(Nil) = Unit. All lowered in `lowerBlock`+`lowerE`.
      Done: kc10-while (sumTo(5)=10), kc10-ifnoelse (positivedone).

- [x] **KC9 — block expressions DONE** 2026-07-01.
      `{ val x=e; def f(p)=body; sideEffect; result }` in function bodies.
      Parser: `parseBlock` on `{` in `parseAtom` (val/def/expr stmts until `}`).
      Lowering: `lowerBlock(scope, stmts)` — val→IrLet, def→IrLetRec (self-scope for recursion),
      side-effect expr → IrLet with `_blk_` discard, final expr → lowerE directly.
      resolveE handles `"block"` recursively (each item's subexpressions resolved).
      Done: kc9-block (49), kc9-sideeffects (abc), kc9-localdef (49).

- [x] **KC5-micro + KC7b — string `+` heuristic + object methods DONE** 2026-07-01.
      **KC5-micro**: In `resolveE`, for `inf("+")`, if either side is a string literal/prim
      → upgrade op to `"++"` (sconcat). Handles `"Hello, " + name + "!"` without type env.
      `isStrExpr(e)` checks tag="str", or prim op in {i->str, sslice}.
      **KC7b**: Parse `object O { defs }` body into `Pair("object", Pair(name, stmts))` instead
      of skipping. Resolver: uid receiver in `resolveMethodCall` → static dispatch `O_method(args)`
      instead of `_sel_method(O, args)`. Lowering: `lowerStmtToList("object")` prefixes each def
      as `O_def → IrDef("O_def", IrLam(...))`. Add `skipToBrace` parser helper.
      Done-when: `kc5-strcat` ("Hello, World!") + `kc7b-object` (Math.square+double=31) pass.

- [x] **KC11 — lambda expressions + return DONE** 2026-07-01.
      **Lambda**: `(x: T) => body` and `x => body` (param type annotations stripped).
      `tryLamParams` speculatively parses `(name [: T], ...)` list → `Some(names)` or `None`;
      `parseExpr` checks `id =>` and `(params) =>` before falling to `parseInfix`.
      `return` in `parseAtom`: keyword → `Pair("return", parseExpr)`.
      **Lowering**: `lowerE` for `"lam"` → `IrLam(n, lowerE(appendL(revL(params), scope), body))`.
      `lowerBlock` for `"return"` → evaluate return value (ignore remaining stmts).
      `if (cond) return e; rest` in block → `IrIf(cond, e, lowerBlock(rest))`.
      GOTCHA: ssc0 pattern match can't have string literals as pattern args (`case Pair("if", x)`
      is invalid — use variable + `#seq` guard).
      Done: `kc11-lambda.ssc` (`compose(double, inc)(5)` = 12),
            `kc11-return.ssc` (`abs(-7) + abs(3)` = 10).

- [x] **KC8 — `given`/`using` DONE** 2026-07-02.
      `given name: T = body` → `val name = body` via `parseOneStmt` `given` branch.
      `(using p: T, ...)` in def param lists → `parseUsingParams` helper in `parseDef`.
      `f(a, b)(using sep)` at call sites: `buildPostfix` strips `using` keyword and
      merges the using arg list into the preceding call (`append(eargs, newArgs)`) so the
      runtime sees a single N-arg call (no partial application needed).
      GOTCHA: `appendL` does not exist in `ssc1-front.ssc0` — use `append` from `list.ssc0`.
      Done: `kc8-given.ssc` prints "hello, world".

- [x] **KC12 — string interpolation DONE** 2026-07-02.
      `s"Hello, $name!"` → `"Hello, " ++ name ++ "!"` concatenation AST via
      `readInterpId`/`interpParts`/`partsToExpr`/`buildSInterp` in `ssc1-front.ssc0`.
      `parseAtom` detects `id("s"|"f"|"raw")` + next token `str` → `buildSInterp`.
      `++` lowered to `IrPrim("sconcat", ...)` already in place (KC5-micro).
      Only `$identifier` works; `${expr}` is skipped as literal.
      Done: `kc12-interp.ssc` prints "Hello, World!".

- [x] **KC5 — context bounds + given auto-injection DONE** 2026-07-02.
      **Parser** (`ssc1-front.ssc0`): `parseTypeParams` extracts `[A: TC, ...]` bounds → prepend
      `"__tc_TC"` dict params to `allParams`. `readTypeStr` collects type annotation tokens (uses
      `tokKind` for punctuation since `tokVal=""` for `[`, `]`, etc.). `given` branch captures
      type string as 3rd Pair field: `Pair("given", Pair(name, Pair(typeStr, body)))`.
      `skipTypeArgs` skips `[...]` after TC name in bound position.
      `joinStrs` concatenates token values.
      **Lowering** (`ssc1-lower.ssc0`): `kc5GivenCell`/`kc5SigCell` mutable cells.
      `parseGivenType("Show[Int]")` → `Pair("Show","Int")` (scans for `[`, slices).
      `buildGivenTable` indexes `given` stmts by `(TC, Type)` key.
      `buildSigTable` collects `__tc_`-prefixed params per def → maps fn→[TC...].
      `isCtxParam`/`ctxParamTC` identify/extract TC from `__tc_TC` names.
      `findGiven` looks up `(TC, typeName)` or wildcard `*`.
      `typeOfExpr` heuristic: `"int"`→"Int", `"str"`→"String", `"bool"`→"Bool", else"*".
      `injectGivens` called in `lowerE` app case: prepends given globals before user args.
      `lowerProg` initializes both cells before lowering statements.
      **Runtime** (`v2/src/Runtime.scala`): added `io.println` primitive (print + newline);
      `printlnDef` updated to use `io.println`.
      **GOTCHA**: `tokVal=""` for `[`, `]` punctuation — must use `tokKind` in `readTypeStr`.
      Done: `kc5-typeclass.ssc` prints "shown\nshown" ✓. `kc5-strcat` + `kc7b-object` still pass.

## [x] KC13 — end-to-end `.ssc` runner + `${ident}` interpolation fix (2026-07-03)

`ssc run bin/ssc1-run.ssc0 examples/kc13-hello.ssc | ssc run-ir /dev/stdin` → "Hello, World!" ✓.
Conformance clean (also fixed 3 pre-existing harness bugs: kc5-type-error, kc9-sideeffects, kc10-ifnoelse).

**Goal:** `v2/ssc1 file.ssc` runs a real v1.0 `.ssc` Markdown file end-to-end on the v2 kernel.

**Slices:**

1. **`${ident}` interpolation** — KC12 only handles `$name` (bare). `examples/hello.ssc` uses
   `s"Hello, ${name}!"`. Fix `readInterpId` in `ssc1-front.ssc0`: if next char after `$` is `{`
   read the identifier inside braces, skip the `}`. Pure front-end; no backend/kernel change.

2. **Multi-block concatenation** — real `.ssc` files have multiple `scalascript` fenced blocks.
   `bin/ssc1-run.ssc0` (ssc0): import `mira-md.ssc0` + `ssc1-front.ssc0` + `ssc1-lower.ssc0`;
   read file → `extractFences` → filter `Pair("scalascript",src)` blocks → join with `"\n\n"` →
   lex → parse → lower → emit Core IR. Reuses all KC3-KC12 machinery; no new language features.

3. **`v2/ssc1` launcher** (bash): `exec scala-cli run "$DIR/src" -- run "$DIR/bin/ssc1-run.ssc0" "$@"`

4. **`v2/examples/kc13-hello.ssc`** — the canonical Markdown-wrapped hello example (YAML front-matter +
   two `scalascript` blocks, `greet` + `main`). Tests `${ident}` + multi-block.

5. **Conformance** — `check.sh` entry: `ssc run bin/ssc1-run.ssc0 examples/kc13-hello.ssc | ssc run-ir`
   → "Hello, World!".

**How / files to touch:**
- `v2/lib/ssc1-front.ssc0`: fix `readInterpId` for `${...}` (add `{`/`}` branch).
- `v2/bin/ssc1-run.ssc0` (NEW): Markdown → `extractFences` → filter scalascript → join → parse/lower/emit.
- `v2/ssc1` (NEW launcher script).
- `v2/examples/kc13-hello.ssc` (NEW).
- `v2/conformance/check.sh` (append KC13 check).

**Done-when:** `cd v2 && ssc run bin/ssc1-run.ssc0 examples/kc13-hello.ssc | ssc run-ir /dev/stdin`
outputs "Hello, World!"; `./conformance/check.sh` exits 0.

---

## K62 — scalameta-free frontend parity (measured 2026-07-09)

Goal: bring the **native** (scalameta-free) `.ssc` frontend tower
(`mira-md` → `ssc1-front` → `ssc1-check` → `ssc1-lower`) to parity with the v1
scalameta parser, so scalameta can eventually be dropped from `v1/lang/core` and
the `v2FrontendBridge` seam retired. Spec: `specs/62-scalameta-free-frontend-parity.md`.

**Baseline (measured, native parse+lower over the real 195-file `examples/*.ssc`
corpus):** 186/195 = 95.4% PASS after the fence-tag fix + `-Xss16m`. The parser is
NOT the hard part — scalameta is only parser+typer, and the native parser already
covers 95% of the corpus surface. Full method + numbers in the spec.

Reproduce the measurement:
`scala-cli --power package v2/src --assembly -f -o /tmp/ssc.jar`, then loop
`java -Xss16m -jar /tmp/ssc.jar run bin/ssc1-run.ssc0 <examples/*.ssc>` (exit 0 ⇔
frontend accepted the file).

- [x] **K62.0 — fence-tag policy fix DONE** 2026-07-09. `bin/ssc1-run.ssc0`:
      broadened the block filter from `#seq(lang,"scalascript")` to also accept
      `scala` (both are executable ScalaScript in v1 — `Lang.isParseable`).
      Moved 32 corpus files from FAIL→PASS. `ssc0` note: `#or` is not a primitive;
      use a nested `if` to build the boolean.

- [x] **K62.1 — `Pair/2` DONE** 2026-07-09. Root cause was NOT "assign mid-block"
      (that already works). `buildPostfix` (`ssc1-front.ssc0`) never consumed a
      trailing `{ block }` arg, so top-level `route(...) { req => … }` parsed as a
      bare call + a *separate* standalone block; inside it, `id = expr` became
      `idx_assign` whose lowering does `match ldata { case Pair(arrFn, idxArgs) }`
      on a bare var → `no arm for Pair/2`. Fix: add a trailing-`{` arm to
      `buildPostfix` that consumes the block via lambda-aware `parseBlockArg`
      (`e { body } → e(body)`). All 6 files pass; conformance 640/640 green.

- [x] **K62.2 — `Nil/0` DONE** 2026-07-09. Root cause was single-arg
      `String.substring(from)`. `resolveMethodCall` matched only two-arg substring
      (`match r0 { case Cons(too, r1) => … }`), no `Nil` arm → `no arm for Nil/0`.
      (The tuple/`var`/`while` context was a red herring; atomic repro:
      ``val s="abc"`` ⏎ ``s.substring(1)``.) Fix: add the `Nil` arm —
      `substring(from) == substring(from, length)` → `sslice(s, frm, slen(s))`.
      Verified `"hello".substring(2)` → `"llo"`. Both files pass.

- [x] **K62.3 — compile-recursion robustness DONE** 2026-07-09. Added `-J-Xss512m`
      to the `v2/ssc` and `v2/ssc1` launchers (matching the existing `ssc0c` /
      `sscx` convention) so the VM's deep `Compiler.compile`/`FastCode` recursion on
      large programs (`control-center-live`, `auth-full`, `x402-cardano-scalus`)
      no longer StackOverflows. **Parse+lower now 194/195** — only `deploy.ssc`
      (sh-only, no code) remains, correctly out of scope.

- [x] **K62.4 — axis 2 (native type-checker) MEASURED** 2026-07-09.
      `bin/ssc1-check-run.ssc0` over the corpus: **162/195 pass, 32 false-positive
      rejections** in ~4 operator-inference categories (`++`/`+` concat ×11; Float
      `/`/`%`/`*` ×8; String/Int/Bool unify ×9; if-branch ×4). The checker is
      Dyn-lenient elsewhere (doesn't reject `val x: Int = "hello"`). **Off the
      critical path** — `ssc1-run` skips type-check — so it doesn't block dropping
      scalameta; it's a quality gate to close before making `ssc1-check` mandatory.

- [x] **K62.5 — axis 3 (native end-to-end run) MEASURED** 2026-07-09.
      `ssc1-run` → `run-ir` over the corpus: **3/195 run to completion.** Errors split:
      **Class A (~40 files)** — hidden parse-completeness gaps surfacing as
      `unbound global: _err`/bare-keyword. Roots (instrumented `parseAtom`): bitwise
      ops (`& | ^ ~ << >>`), `@` annotations, `$`, char literals, Markdown-link
      imports. **Class B (~150 files)** — missing stdlib/plugin/effect intrinsics
      (http `route`/`authServer`, `Dataset_*`/`spark`, `runActors`/`runAsync`/
      `signal`, `Graph_*`/`Db_query`/`IndexedDb_store`, `mcpConnect`/`agentTool`,
      crypto `verifyEd25519`/`totp`/`uuidV7`, …). Full breakdown in spec 62.

### K62 remaining (concrete, prioritized — the real path to scalameta-free)

- **K62.6 — parse-completeness (Class A).** Bounded frontend work in `ssc1-front.ssc0`.
      Native end-to-end (plugin runtime) 3 → 14 across these slices; conformance stays 640/640.
  - [x] **K62.6 DONE** 2026-07-09: skip Markdown-link imports `[a,b](path)` (23 files)
        + Scala `import a.b.{x,y}` (both were keyword/`_err` leaks).
  - [x] **K62.6b DONE** 2026-07-09: top-level `var` + assignment (global cells via
        `topVarsCell`; init in doc order; refs/assigns from def bodies resolve).
  - [x] **K62.6d DONE** 2026-07-09: skip `@Name` / `@Name("args")` annotations.
  - [x] **K62.6c-ops DONE** 2026-07-09: wildcard `import a.b.*`, cons `::`, pair
        `->`, char literal `'x'`/`'\n'`, bitwise `& | ^`, shifts `<< >> >>>`, prefix
        `~` (VM had `i.and/or/xor/shl/shr/ushr/not`). End-to-end 14→18, `_err` 20→11.
  - [x] **K62.6c-for DONE** 2026-07-09: `for x <- xs [if g] yield/do e` (single
        generator + guard) → map/foreach/filter; lex `<-`.
  - [x] **K62.6c-map DONE** 2026-07-09: `Map(k -> v, …)` initial entries (was
        silently empty — `map.put` mutates+returns Unit, so built via IIFE), `new
        Foo(x)` == `Foo(x)`, multi-generator `for` (flatMap; + `_sel_flatMap`
        List/Cons default arm → `_list_flatMap`). End-to-end 18→22.
  - [x] **K62.6c-under DONE** 2026-07-09: underscore placeholder `filter(_ % 2 == 0)`
        → `filter(x => x % 2 == 0)` (arg-level tree-walk `exprHasPh`/`replacePh`,
        wrap compound-ph args in a lambda; bare `f(_)` left as-is). Closed the `_` class.
  - [x] **K62.6c-indent DONE** 2026-07-09: **significant indentation**. Lexer emits
        `NL <indent>`; a layout pass (offside → virtual `{ ; }`) converts brace-less
        indented blocks (`def f() = <indent> stmts`, `if/while/for` bodies), with
        continuation handling (`else`/infix/`.` on a new line). Also: while-parser
        skips `do`. **End-to-end 22→33** (+11). Conformance 640/640.
  - [~] **K62.6c-rest**: takeWhile/dropWhile DONE (list→__method__ element pred, str char-code); `$`
        multi-generator `for` with pattern binders. `throw` needs a VM error prim.
        Pre-existing (not indentation): match-in-def returns the wrong arm (`v2` block
        value / match-dispatch bug — verify on `def f(x)=x match {...}`).
  - [ ] **K62.6e — field access `_sel_get`/`_sel_env` → `__method__`.** Tried (route
        `resolveField` fallback for non-case-fields through `__method__`, with a
        `caseFieldsCell` registry to keep case-class field projection). REVERTED:
        net −1 because it triggers the same latent bug as below. Re-land after fixing it.
  - [ ] **K62.7a-fix — latent `match: scrutinee not Data: "__method__"`** (VM compile,
        Runtime.scala:577). A `__method__`-dispatched call feeding a match scrutinee
        FRONTERRs 3 files (`mcp-search-server`, `traditional-payments`, `x402-metamask`).
        Blocks K62.6e too. Needs VM-side investigation, not a frontend fix.
- **K62.7 — dispatch alignment (Class B). SUPERSEDES the old "re-grow stdlib"
      framing** — see K62.5b: the v1 stdlib ALREADY exists in the v2 runtime
      (`V2PluginRegistry`, loaded by `PluginBridge.loadAll()`; the bridge path uses
      it, busi 61/61). Native failures were a name/dispatch mismatch, not missing
      intrinsics. My bare-VM 3/195 measurement had an empty registry — artifact.
  - [x] **K62.7a DONE** 2026-07-09: `ssc1-lower` generic `_sel_<method>` fallback →
        `IrPrim("__method__", [str name, recv, args])`, matching FrontendBridge +
        Runtime dispatch (unhandled → free `Op`). Conformance 640/640. Added
        `BridgeCli run-ir` (loadAll + run native IR) to measure against the
        plugin runtime.
  - [x] **K62.7b DONE** 2026-07-09: uid-static `Foo.method(args)` on an UNKNOWN
        uppercase object → `IrPrim(__method__, [str method, Ctor(Foo,[]), args])`, so
        the plugin runtime's `__fallback__.Foo.method` resolves it. User objects
        (isKnownObject via a `collectObjects` pre-scan) keep static `Foo_method`.
        Pure lowering, no runtime change. **End-to-end 33→38** (+5). Conformance 640/640.
        Also A: `for (a,b) <- pairs` tuple-pattern binder. NOTE: `System.out` /
        `_sel_get` FIELD access on uid still fails (blocked by the K62.7a `__method__`
        VM-scrutinee bug — sibling-owned); takeWhile/dropWhile on strings = follow-up.
  - [ ] **K62.7c** — run native front → plugin-enabled runtime for real: wire the
        plugin registry into the `ssc`/`ssc1` launchers (or use `BridgeCli run-ir`),
        then re-measure end-to-end.
- [ ] **K62.8 — (optional) close type-check false positives (K62.4).** Only needed
      if `ssc1-check` becomes mandatory.

**Bottom line (revised, K62.5b):** dropping scalameta is NOT a stdlib rewrite — the
stdlib already exists in the v2 runtime and the bridge uses it. It reduces to two
bounded, scalameta-independent frontend/lowering jobs: **K62.6 parse-completeness**
(~40 `_err` files) and **K62.7 dispatch alignment** (started). They compound per
file, so end-to-end pass-count lags until both close. See spec 62.

## K63 — Conformance runner speedup (v2/conformance/check.sh)

Baseline: ~12 min, **0 parallelism on 14 cores**, everything sequential. Measured
costs: rustc 240 compiles (~2-3 min, `ld` slow+flaky), ~300+ cold `java -jar` starts
(219 ms each ⇒ ~1.5-2 min), assembly-jar build (~2-3 min), node 177 (~30 s), wasm 9
+ compute (~2 min). Do INCREMENTALLY, each slice keeps the default run at 640/640 and
identical pass/FAIL set (diff old-vs-new output before landing).

- [x] **K63.1 (89120ab3e) — fast mode `CONF_FAST=1`**: guard the rustc/node/wasm blocks
      (`[ -z "$CONF_FAST" ]`) so front/lower iteration runs only the VM (run-ir) lane.
      Lowest risk (default unchanged). ~12 min → ~4 min for iteration. VERIFY:
      `CONF_FAST=1` skips Rust/JS/WASM; default still runs+passes all 640.
- [x] **K63.2 — robust+fast rustc**: install-guarded `-C link-arg=-fuse-ld=lld` (if
      `lld` present) + `RUSTC_WRAPPER=sccache` (if present); no-op when absent. Kills
      the `ld: file is empty` disk-pressure flakes + caches repeat compiles. VERIFY:
      rustc lane still green; 2nd run faster.
- [x] **K63.3 — batch run-ir into one JVM** (bridgeCli run-ir-batch; byte-for-byte 14/14, ~36x): add `ssc run-ir-batch <list>` (one JVM
      runs many IRs) or a persistent JVM; replace 316× cold `java -jar run-ir`. ~1.5-2
      min saved. VERIFY: batched stdout matches per-invocation, byte-for-byte.
- [x] **K63.4 — OPTIONAL parallelism (opt-in `CONF_JOBS=N`, default 1=sequential)**:
      infra = bounded bg pool + barrier; landed for the stateless `chk`/`chk_hm` VM lane
      (188 tests) → parallel fast-mode 210s→111s (~2×), IDENTICAL 406/0 set. Sequential
      default byte-identical. Fix/expand the other lanes gradually:
  - [x] K63.4a+b — parallelize the inline rustc (62) + node (62) blocks: wrap each
        `if have_X; then <body> fi` with a sequential/parallel split; the parallel branch
        runs `<body>` in a bg subshell with an ISOLATED `TMPDIR=$_PAR_DIR/<n>` (no temp
        collisions) + captured output. Structural blocks (fn-defs / else-branch) skipped.
        Validated CONF_JOBS=6 → 640/0. NOTE: backend jobs are HEAVY (JVM emit + rustc);
        keep CONF_JOBS ≈ cores/2 to avoid oversubscription (CONF_JOBS=14 thrashed).
  - [x] K63.4c — ORDERED output in parallel mode via exec-redirect SEGMENTS. In CONF_JOBS>1
        mode stdout is redirected to an indexed segment file; each enqueue seals the current
        segment (inline output since the last job), runs the job into the next index, then
        opens a fresh segment (`_pseg`). The barrier restores real stdout (fd 3) and cats
        every `[0-9]*` file in index order → inline headers/tests and backgrounded job
        results interleave EXACTLY as sequential. stderr stays live (one-line note). Verified:
        FAST SEQ vs FAST PAR6 stdout diff = IDENTICAL. Tradeoff: parallel stdout is buffered
        until the barrier (silent run + note), which is the standard output-sync cost.
- [x] **K63.5 — cache the assembly jar**: hash `src/` → skip `scala-cli package` when
      unchanged (keyed jar in a stable cache dir). ~2-3 min/iteration. VERIFY: rebuilds
      on any src change, reuses otherwise; stale-cache guard.

## K62.13 — native enum support (parser-axis) — 2026-07-10

Measured native-frontend coverage (`-Xss512m`): **stage1 parse+lower = 192/195** (parser
axis essentially closed); the real gap is stage2 execution. Genuine frontend blockers:
`_err` fallback (13 files: annotations/`derives`), **enum `case` (4 files)**, dsl no-arm.
Enum is the memory-flagged ceiling and unblocks the `=>`/`match` layout openers (they were
net-negative ONLY because enum cases are skipped → `North` unbound). Slices:

- [x] E1 — front: parse `enum E[T](p) <: S:` / `enum E { … }`, then `case X` (nullary) and
      `case X(p1: T, p2: T)` (parametrized, multi-field) → `("enum", Pair(name,
      [Pair(caseName, params)…]))`. Layout `;` between case lines; loop skips semis, reads
      while next kw is `case`, stops at first non-case. Braced form skips to matching `}`.
- [x] E2 — lower: expand the `enum` node in the stmt→def pass. Nullary case → `IrDef(name,
      IrCtor(name, Nil))` (VALUE — bare `North` → IrGlobal → ctor value). Parametrized case →
      `lowerCaseCls` (ctor fn + `_sel_` accessors).
- [x] E2b — front: add `match` to `isLayoutOpener` so brace-less `def f = e match`⏎<arms>
      opens a layout block that closes at dedent — the arms no longer swallow the following
      top-level statements (a PRE-EXISTING bug this enum work exposed; braced `match {…}`
      unaffected). This is the last `=>`/`match` opener the spec flagged; enum support made it
      net-positive (was net-negative only because enum cases were skipped → `North` unbound).
- [x] E3 — lower: extend `collectCaseFields` + `collectCaseClassOrder` to walk `enum` cases
      (named-args reorder + accessor-routing parity with case classes).
- [x] E4 — verify: enums.ssc Direction block runs (`North -> South …`), was fully broken.
      Fast conformance 406/0 (parity w/ origin/main), full corpus stage1 192/195 = zero parse
      regressions. Shape/Tree still blocked on stdlib `math` + literal-pattern `case 0` (both
      PRE-EXISTING, non-parser gaps).
- [ ] E5 (stretch) — subtypesOf registration so `case _: Shape` type-tests resolve on enums.

## K62.14 — native literal patterns in match (parser-axis correctness) — 2026-07-10

- [x] Integer/char/string/bool/float LITERAL patterns (`case 0`, `case '+'`, `case "s"`,
      `case true`) were silently lowered as catch-all defaults (lowerMatch else-branch), so
      `case 0 => a; case _ => b` always returned the LAST default (b). The front already
      emits `("lpat", Pair(ty, v))`; the lower ignored it. Fix (ssc1-lower only): `hasLpat`
      detects a literal match → `lowerLitArms` builds an `IrIf(__eq__(scrutinee, litIr))`
      fallthrough chain (structural `__eq__`, all value types; vpat/wpat terminates; a ctor
      arm becomes a one-arm IrMatch with the rest as default). Chars tokenize as int codes,
      so char patterns are int lpat and are handled too.
- [x] Verified: synthetic int + char (`sign 0/1/9`→zero/one/many, `op '+'/'-'/'*'`→
      add/sub/other) all correct. Non-literal matches skip the new branch (unchanged).
      The 3 corpus files with literal patterns (data-types, dsl-calc-parser,
      actors-typed-remote-spawn) stay compound-blocked by UNRELATED gaps (Person case-class
      match, Parser_regex/runActors plugins) — the fix is a correctness improvement, not a
      corpus flip.

## K62.15 — merged field accessors across shared field names (parser-axis) — 2026-07-10

- [x] Two case classes sharing a field name (`Person(name,age)` + `Student(name,grade)`)
      each emitted `IrDef("_sel_name", …)` in lowerCaseCls; the SECOND overwrote the first,
      so `.name` on the earlier class hit `no arm for <Ctor>/N`. Fix (ssc1-lower only):
      lowerCaseCls emits only the ctor def; buildMergedAccessors generates ONE `_sel_<field>`
      per unique field with a match arm for EVERY ctor declaring it (each returning its own
      index) from caseFieldOrderCell (covers case classes + enum cases + imported modules).
- [x] Verified: synthetic Person/Student/Company (`name` at idx 0,0,1) → all correct incl.
      the index-1 case; data-types.ssc now runs 8+ lines (Point, Person, enum toHex colors,
      Shape/area) — was crashing at Person immediately. Compounds with K62.13 (enum) + K62.14
      (literal patterns). ~7 corpus files had shared field names. Conformance + stage1 pending.

## K62.16 — structural ==/!= (string equality) — 2026-07-10

- [x] `==`/`!=` lowered to `IrPrim("i.eq",…)`; the VM i.eq compile path (Runtime.scala:1725)
      coerces operands via asInt, so `"a"=="a"` and any string equality CRASHED ("expected
      Int, got …"). Corpus-wide correctness bug. Fix (ssc1-lower only): lower `==`/`!=` via
      the structural `__eq__` prim (Runtime.scala:2174 — value equality over StrV/IntV/FloatV/
      BoolV/DataV). Trade-off: an int `==`/`!=` loop condition no longer matches the i.eq JIT
      fast-path (falls to the correct general interpreter). Verified conformance-neutral.
- [x] Verified: `"foo"=="foo"`→T, `!="bar"`→T, int/float/char == still correct; recursion.ssc
      (100k-iter recursions w/ `==` base cases) 0.5s + correct; fast conformance 406/0 @164s
      (JVM-startup-dominated, no perf regression). String `<`/`<=`/`>=` (ordering) still use
      i.eq/i.lt — out of scope (rare; needs a string-compare prim).

## K62.17 — call-site default-parameter synthesis — 2026-07-10

The honest BridgeCli metric (RUN=42/194) flagged `arity: N expected, M given` (17 content-*/
datatable files) as the #1 blocker: K62.6g PARSED `def f(x=v)` defaults but discarded them,
so calling with omitted trailing args crashed. Fix (front + lower, zero ripple — a SHARED cell
since ssc1-lower imports ssc1-front and both run in one ssc0 process):

- [x] FRONT: `paramDefaultsCell` (per-param-list accumulator) + `funcDefaultsCell`
      (funcName → positional defaults). parseParam records `Pair(name, dfltExpr)` on a default
      (return unchanged, zero ripple). parseDef clears the accumulator before its params and
      snapshots `Pair(name, positionalDflts)` into funcDefaultsCell after — nested defs clear
      before the outer reads the body, so no interference.
- [x] LOWER: `padDefaults(fn, rargs)` at the resolveE app binding — a var/uid call whose name
      is registered and given < arity gets its omitted TRAILING defaults appended (resolveE'd).
      Constants (`[]`/`""`) are the common toolkit case; scope-independent.
- [x] Verified: synthetic `greet("Alice")`→"Hello, Alice!" (2 defaults), `greet("Bob","Hi")`,
      `tally(List(1,2,3))`→0 all correct; content-data-source / content-slot / datatable-static-spa
      PROGRESS PAST arity (→ next blockers `unbound null` / plugin module-context). Remaining:
      content-introspection is CURRIED (`contentComponent(name)(render)`), a separate gap.
      Conformance + stage1 pending.

## K62.18 — null + throw literals (bridge parity) — 2026-07-11

Blockers revealed behind the K62.17 arity fix (honest BridgeCli measure). Both mirror the
scalameta bridge exactly:
- [x] `null` → `None` — parseAtom kw branch → `mkUVar("None")` (front already lowers `None`
      → `ctorap None`). Bridge: FrontendBridge Lit.Null → Ctor("None"). Verified:
      `val x = null; x match { case None => … }` → "was null"; content-data-source /
      datatable-static-spa PROGRESS PAST `unbound null` (→ deeper arity residuals).
- [x] `throw e` → `__throw__(e)` — parseAtom id branch (`throw` is an id, not a kw). Bridge:
      Term.Throw → App(Global("__throw__")). __throw__ is registered by the plugin runtime
      (loadAll), so it resolves under BridgeCli (the bare VM has no throw). inline (macros,
      `${}` quote/splice) and ctx (context var) stay out of scope.

## K62.19 — native tuple field access ._N → _sel__N (fix recursion conformance red) — 2026-07-11

Native v2/conformance red `recursion.ssc via ssc1` (405/406 fast): the Collatz longest-sequence
finder returned (1,0) not (871,178). Root: tuple positional field access `._N` returned a Stub.
selOrMethod routes `._N` → __method__ (unchanged since K62.6e), but the VM only resolves `_N` via
a Long fast-path (Runtime.scala:1256) that fires in numeric contexts — so `println(x._2)` and
`best._2` in the fold lambda got a Stub (methodOp has no general `_N` handler). A VM fast-path
change stopped `best._2` (in `s > best._2`) resolving.

- [x] FIX (ssc1-lower, lower-only, no kernel rebuild): selOrMethod routes `_1`/`_2`/`_3`/`_4` to
      `_sel_<field>` — the built-in prelude accessors `_sel__1.._sel__4` (match Pair/Tuple4),
      correct in ALL contexts, no fast-path dependency. Verified: (5,7)._1/._2 → 5/7,
      foldLeft tuple accumulator → correct, recursion.ssc via ssc1 → Collatz (871,178) GREEN.
- NOTE for kernel owners: the VM `methodOp` (Runtime.scala:3225) lacks a general `_N` positional
      handler; `__method__("_2", Pair)` returns Stub outside the Long fast-path. A VM fix there
      would also cover any other `._N`→__method__ path (e.g. records with `_N` fields).

## Native-front correctness program (2026-07-11, opus) — accurate worklist + K62.20 tuples

Accurate native-parity (ssc1-run → BridgeCli run-ir WITH plugins vs tests/conformance/expected):
MATCH=44, MISMATCH=110. Categories: Stub-dispatch 7 (bimap/fmap/copy — extension/typeclass, v2.1
lane), Op-unperformed 11 (plugin), empty/early-halt 70 (bulk, mostly plugin-runtime + a few native
early-halts like Array.tabulate curried-static). The native front is far less correct on the broad
corpus than v2/conformance 406/0 (curated) suggests — this is a multi-session program.

- [x] K62.20 — 3+ tuples: FLAT `Tuple${N}` ctors, not nested Pairs. lowerTuple built
      `Pair(a,Pair(b,c))` for 3-tuples while parsePat + the bridge use `Tuple3` → `case (a,b,c)`
      never matched and `._2`/`._3` were wrong. Fix: lowerTuple emits `Tuple${N}` for N≥3 (2-tuples
      stay Pair, shared with `->`), + Tuple3 arms on _sel__1/_sel__2/_sel__3. Verified: 2/3/4-tuple
      `._N`, `val (a,b,c)=` destructure, and `case (n,s,flag)=>` all correct; tuples.ssc GREEN.
- [ ] Remaining program (v2.1 track's lane — coordinate): extension/typeclass dispatch Stub
      (bimap/fmap/copy), plugin-Op unperformed, per-file early-halts (Array.tabulate static+curried,
      Option ops, string %-format). RECOMMEND: wire the native-parity check into CI (ssc1-run vs
      expected/, non-plugin subset) so these regressions are caught.

## conf-flake + native correctness (2026-07-11, opus) — 4 fixes, conformance 406/0

- [x] K63.4d — parallel temp-file race. run_logged/run_stdout_logged named temp files
      `<label>-$RANDOM.<ext>`; bash subshells (parallel `&` jobs) INHERIT the parent's $RANDOM
      seed → two jobs forked together collide on the same O_TRUNC file → truncated captured
      output → false conformance fails with mangled strings ("anches" vs "if-branches"). Fixed
      with `$BASHPID-$RANDOM` (BASHPID unique per subshell).
- [x] K62.21 — None.isEmpty returned false. `.isEmpty` lowers to __list_isEmpty for List AND
      Option (no type info); it only matched Nil→true. Added None→true arm (+ symmetric nonEmpty).
- [x] K62.22 — println(Some("x")) printed Some("x") not Some(x). Native println → io.println →
      `out`, whose container branch used Show.show (quotes StrV children) instead of anyStr (the
      parity renderer the bridge already uses via __autoPrint__). Fixed `out` → anyStr. Verified
      by rebuilding v2Core + native/bridge spot checks (bridge 10/10 unchanged, no regression).
- [x] K62.23 — kc3 build-break (`unbound variable: lenL`) from sibling 7f6821856's flat-tuple
      pattern code in ssc1-front.ssc0. A bare `lenL` was unbound standalone; a plain `def lenL`
      duplicated ssc1-lower's when both imported (25-fail cascade). Fixed with a front-local
      uniquely-named `lenLF`. Coordinated in rozum.

## native Array methods (2026-07-11, opus) — array-companion-statics green, conformance 406/0

- [x] K62.24 — Array.tabulate/fill(...).mkString(sep) crashed ("scrutinee not Data: <foreign>").
      Array statics return a ForeignV(ArrayBuffer); applied .mkString lowers to prelude
      _sel_mkString (List-only Nil/Cons). Fix: _sel_mkString default arm → __method__ fallback
      (runtime handles mkString on isList = List AND ArrayBuffer). Lists unchanged (zero risk).
- [x] K62.25 — Array.empty[T] crashed ("unbound global: Array"). Bare companion static reaches
      selOrMethod with robj=uid(Array) → unbound global; applied path already wraps as
      ctorap(Array). Do the same for 0-arg, gated on isCollectionCompanion (enum cases untouched).
- [ ] Follow-up: Map.empty/List.empty resolve receiver to a <closure> (not uid) via a different
      path — separate companion-receiver bug. Also: standard-scala-multifence %-format specifiers.

## nested-pattern guards (2026-07-11, opus) — K62.26, conformance 406/0

- [x] K62.26 — guard on a nested-destructuring pattern was SILENTLY DROPPED:
      `case (ah::at, bh::_) if ah<=bh =>` took the arm regardless of the guard. Root: front
      guardablePat/flatGuardFields only allowed flat vpat/wpat fields → the `if …` was never
      parsed into a gpat; and the lowerer bound cpat fields flat, so nested binders weren't in
      scope for the guard. Fix: flatGuardFields recurses into cpat fields; new dischargeObsOrGuard
      mirrors the working non-guard nested path (dischargeObsOr) with the guard checked at the
      fully-discharged point (guard-false falls through like a nested mismatch). Flat patterns
      degenerate to the old IrIf(guard, body, fallback) — backward compatible. Verified: the bug
      case + guard-true + multi-arm fallthrough + all flat guards; v2/conformance 406/0.
- [ ] Follow-up: standard-scala-multifence now differs ONLY on the %-format line
      (f"${x}%-4s=${v}%.1f" — printf specifiers not applied). One fix from fully green.

## native f-interpolation (2026-07-11, opus) — K62.26b, standard-scala-multifence FULLY green

- [x] K62.26b — f"${x}%-4s=${v}%.1f" emitted the printf specs as literal text (native lowered f""
      like s"" = plain ++ concat). Fix: buildFInterp peels the spec off the front of each
      post-interpolation literal (splitFFormatPrefix, grammar `%[-#+ 0,(<]*[w][.p]<letter>`, default
      %s) and emits app(var(__fInterpolate__), [head, spec,arg,rest, …]); resolveE routes it to the
      existing __fInterpolate__ prim (runtime String.format, Locale.US). s""/raw"" unchanged.
      Verified: %-4s/%.1f/%d/%5s specifiers correct; s-strings unchanged; v2/conformance 406/0.
      With K62.26 (nested-pattern guards) this makes standard-scala-multifence FULLY green.

## native Map.empty + type-aware isEmpty (2026-07-11, opus) — K62.27, conformance 406/0

- [x] K62.27 — Map.empty[K,V] crashed ("no dispatch for .empty on <closure>"): Map missing from
      isCollectionCompanion (List/Seq/Vector fixed by K62.25) → receiver stayed unbound global Map
      (ctor closure) not ctorap(Map). Added Map. That exposed .isEmpty→__list_isEmpty (Nil/None
      only, flat false otherwise) giving Map.empty.isEmpty=false and "".isEmpty=false; fixed
      __list_isEmpty default → runtime __method__ isEmpty (type-aware), __list_nonEmpty → NOT(that).
      Verified Map/List/Option/String/Array isEmpty+nonEmpty; conformance 406/0.

## native field-name registry + head/tail (2026-07-11, opus) — K62.28, head-field-shadow green

- [x] K62.28 — head-field-shadow: `.head` on a LIST crashed / a case-class `head` field returned
      Stub when a module defined `case class Ref(name, head)`. `.head` has no type info at
      lowering (List.head vs a field literally named head). Fix (2 parts): (1) __list_head/
      __list_tail keep the fast Cons arm, default → runtime __method__ (polymorphic: list head for
      Cons, by-name field for a DataV, clean Nil error); (2) the native front now REGISTERS each
      case class's field names — new __regfields__(tag,[names]) prim emitted from caseFieldOrderCell
      at program start + a Runtime prim calling V2PluginRegistry.registerFieldNames (the registry
      FrontendBridge populates on the scalameta path). Closes the NATIVE side of the field-name
      registry family. Verified head-field-shadow MATCH (native + bridge), List.head/.tail
      unchanged, conformance 406/0, bridge spot unchanged (v2Core recompiled, 5s).

## multi-placeholder + ambiguous ++.length (2026-07-11, opus) — K62.29/30, collections green

- [x] K62.29 — `_ + _` was a ONE-arg lambda (all `_` shared one param) → foldLeft/reduce crashed
      "arity: 1 expected, 2 given". Each `_` is a DISTINCT param left-to-right: wrapPhArg counts
      placeholders + binds __u<i> in order → N-arg lambda. Single-placeholder unchanged.
- [x] K62.30 — `a ++ b` ambiguous (String/List); isStrExpr called every `++` a string so
      `(xs++ys).length` → slen crash. ++ receiver for .length/.size → polymorphic __method__;
      slen fast path kept for unambiguous strings.
      Both verified; collections.ssc FULL MATCH; v2/conformance 406/0.
- [ ] Follow-up: `.reduce` returns Stub (native prelude/runtime gap, separate from placeholders).

## native List.reduce (2026-07-11, opus) — K62.31, conformance 406/0

- [x] K62.31 — `.reduce`/`.reduceLeft`/`.reduceRight` returned a silent Stub (native routes them
      to __method__, runtime had no handler). Added reduce/reduceLeft/reduceRight for isList
      (List + ArrayBuffer). Verified reduce/reduceLeft/reduceRight (incl. _ ++ _ list-concat, and
      the K62.29 multi-placeholder _ + _); sum/foldLeft/max unchanged; conformance 406/0, bridge
      unchanged. (v2Core recompiled.)

## Native-front correctness — remaining-work plan (2026-07-11, opus; after 13 fixes K62.21..31)

Audit of the ~22 still-failing native-parity tests, categorized by ROOT blocker. Tractable,
non-colliding SOLO native slices are now essentially EXHAUSTED — the rest needs the v2.1 lane or
the plugin lane.

### A. Extension / typeclass-method dispatch cluster (~15 tests) — v2.1 track's ACTIVE lane, COORDINATE
Root: an extension/typeclass method (`.fmap`/`.bimap`/`.combine`/`.copy`) defined in a
`given T with extension … def m` block is NOT dispatched by the native front — the call lowers to
`__method__("m", recv)` → runtime `Stub("Type.m")`. Needs given-instance resolution BY RECEIVER
TYPE + extension-method dispatch (ssc1-front/lower own extensionMethodsCell already; dispatch is
the gap). Blocks: std-functor-applicative-monad, std-foldable-traversable, std-selective,
std-bifunctor, std-semigroup-monoid, std-index, std-monaderror, tagless-multi-file/program/
resolution, typeclass-extension, optic-polish, lenses, prisms, traversal, optics-index-at.
  → Post in rozum before touching the extension paths (v2.1 owns them).

### B. Plugin / std-global lane (unbound global or plugin Op, not native lowering)
signals (unbound `Signal`), optional (unbound `Focus`), dataset-*/sql-*/mcp-types (plugin Op),
json-value (jsonRead + i->str), litdoc (markup API arity), actors-*/cluster-*/distributed-*.

### C. Low test-value native completeness (correctness wins, ~0 test greening)
3-arg mkString(pre,sep,post) → route to __method__ (dataset-shape only); other edge-cases.

CONCLUSION: 13 native fixes landed this session (K62.21..31, conformance 406/0, ~10 tests green).
Further native TEST-greening requires the v2.1 extension-dispatch lane (coordinate) or plugin work.

## native mkString arities (2026-07-11, opus) — K62.32
- [x] K62.32 — 0-arg / 3-arg mkString crashed (arity-2 _sel_mkString); route both to __method__,
      keep 1-arg on the fast path. List + Array, all arities; conformance 406/0. (SPRINT §D item.)

## native Signal ctorap + #1 finding (2026-07-11, opus) — K62.33

- [x] K62.33 — Signal(x)/ComputedSignal(x) → ctorap (compiler special-case, matches bridge).
      Basic Signal.get/.set work on the native path; conformance 406/0.
- [#1 FINDING] "run-ir NativePlugin loading" is NOT a simple addition: bridgeCli run/run-ir use
      PluginBridge.loadAll() (v1-compat: Backend ServiceLoader + builtins like computed/effect-stub).
      Native PRODUCTION uses NativePluginHost.loadAll() (ServiceLoader classOf[NativePlugin] →
      ReactiveNativePlugin/Content/Json/etc.), which CLEARS V2PluginRegistry first and enforces
      EXCLUSIVE ownership (claim() throws on a name registered by two providers — e.g. `effect` is
      registered by BOTH PluginBridge (stub) and ReactiveNativePlugin (real)). So the two plugin
      systems are mutually exclusive; you cannot just call NativePluginHost after PluginBridge.
      CONSEQUENCE: my parity audit (bridgeCli run-ir + PluginBridge) tests native IR against the
      WRONG plugin system → signals/content/json-value "native failures" are AUDIT-SETUP artifacts,
      NOT native-front or native-production bugs (production runs them via NativePluginHost). The
      accurate fix is an audit-runner using NativePluginHost, not a native-front change. Deferred:
      it's audit-tooling + a Scala runner + architectural (which plugin set the audit should mirror).

## run-ir-native audit mode (2026-07-12, opus) — #1 DELIVERED
- [x] bridgeCli `run-ir-native` (NativePluginHost) — accurate native-production plugin mirror,
      vs run-ir (v1-compat PluginBridge). Added frontend-bridge deps v2NativePluginSpi (compile) +
      v2NativeReactivePlugin (Test). PROVED signals.ssc is a native FULL MATCH under run-ir-native
      (was a run-ir "MISMATCH" = audit artifact of the wrong plugin system). V2ConformanceTest
      126/128 unchanged (2 pre-existing reds verified on clean main). Closes #1 accurately.

## native-front parity — session 2026-07-12/13 (opus): 11 fixes + remaining plan

Full-plugin run-ir-native audit established TRUE native parity ≈ run-ir-batch + 3 artifacts.
Every gate below = v2/conformance native 406/0 + native run-ir audit zero-regression +
V2ConformanceTest (3 pre-existing scalameta-lane reds: companion-case-class-order, scljet-*).

### Landed this session (all origin/main, all gates green)
- [x] list runtime handlers — headOption/lastOption/indexWhere/span/sliding/scanLeft + foldRight curry (d85a1e903)
- [x] native effect-HANDLER parsing — `handle {body}{case Op(a,resume)=>…}` (e83d3be0c) — 4 tests
- [x] `_sel_<field>` runtime field-by-name fallback — plugin records e.g. ProcessResult (34a12c839) — 5 tests
- [x] enum companion — `EnumName.Case`→`(ctor Case)`, `.values` (e29aca5ba) — 3 tests
- [x] comma enum-cases + sealed-trait/enum type-ascription expansion (afe991dc8) — v2-type-ascription-pattern
- [x] directory imports — `[names](./dir)`→`dir/index.ssc` (920150b80) — import-dir
- [x] case-class BODY methods — custom `override def toString` (in 2df8f6e3c) — dsl-multi-pass
- [x] object-level `var` members — cell-backed reads/writes (5f6c377ac) — unblocks distributed `entries`
- [x] expression-position effect-CPS — lift Ops over fast arith/cmp + `if` (1e0131569) — js-applyunary-effect-cps + head-field-effect-shadow
- [x] `:=` DSL operator — infix method not a var-store (04ab6d88e) — correctness (greens 0, DSL-runtime-blocked)
- [x] REVERTED pair-render (fff5231b8) — it broke 19 v2/conformance kernel-demo tests hardcoding `Pair(a,b)`

### Remaining — status (native-front parts DONE; the rest is the plugin/runtime lane)
- [x] **0-param empty-parens object method** (64ca9b491) — `def f()` empty-parens was an eager property → `WorkerProtocol.handleMessages(): Unit = receive{…}` ran receive at global-reg time. Now empty-parens→IrLam(0,body). **Greened distributed-shuffle** + unblocked the receive-outside-runActors error for the others.
- [x] **tuple-rendering split** (fa308e0da) — re-applied pair-render + `sed 's/Pair(/(/g'` on check.sh's 20 hardcoded wants, same commit. **Greened rest-validate**; v2/conf native 406/0.
- [ ] **distributed-map/failure-*/heterogeneous** (4) — receive-blocker GONE (empty-parens fix); now blocked DOWNSTREAM: distributed-map RUNS but the map-reduce result is wrong (actors-computation ordering / message delivery under run-ir-native), distributed-heterogeneous needs a `Op("Random.uuid")` handler (Random effect/plugin). Deep actors-runtime + Random-plugin lane — NOT native-front. Needs the actors/random plugin owner.
- [ ] **sql** (sql-basic/browser-basic/transaction) — native front lowers `sql` fences to `Db.sql(…)` but SKIPS the `databases:` front-matter (bin/ssc1-run.ssc0:214 skipYaml), so no DB is registered → `Op("Db.sql", …)` unhandled. Wiring needs: front parses `databases:` → emits a `__regdb__(name,url)` node + a VM prim calling `PluginBridge.registerDb` — but Runtime(v2Core) does NOT depend on plugin-bridge (cross-module/circular), so this is a module-structure + sql-plugin(H2/JDBC) change. Plugin/runtime lane.
- [ ] **dataset** (dataset-agg/error/from-generator/shape) — `unbound global: try` (try/catch is a real native-front gap, but…) + `Dataset.fromGenerator requires the standard generator plugin` + content module-context. Multi-blocked by the dataset/generator plugin. Plugin/runtime lane.

CONCLUSION: 13 native-front fixes landed this session (86→~116 run-ir-batch parity, zero net regressions). The native-front GREENING lane is complete — every remaining conformance fail is the plugin/runtime lane (actors-runtime, sql/H2, dataset/generator, Random effect) or v2.1's typeclass lane, each owned by that plugin's author + often cross-module. Handing off in rozum.

## C — the collection/closure cluster: a plan, not a slice (written 2026-07-31)

`lazylist-take` and `list-fold` are the two largest remaining v2 gaps. This is the programme for
them. Each item needs its OWN claim; nothing here is claimed by writing it down.

### C-0 — re-establish the numbers BEFORE designing anything (blocking prerequisite)

The headline figures are not currently evidence. `bench/history.tsv` carries these two rows as
`back-filled`, with `sha unrecorded` AND `ms unrecorded` — only a ratio — while the prose elsewhere
quoted different figures again (566×/115× vs the file's 395/146). A programme sized off numbers
whose commit is unknown is a programme sized off nothing.

What is verified as of 2026-07-31: **both rows are compiled by F**, not the fallback
(`ssc info --front-report` → `F`, `F`). So the numbers do belong to the front we mean to improve —
that confound, at least, is closed.

Do: re-measure both rows with `sha`, `load`, alternating A/B, and `bench.sh --strict-front` (which
did not exist when the originals were taken). Record in `bench/history.tsv`. **If the gap turns out
smaller than recorded, that is the result** — say so and re-rank the cluster against P-5.

**DONE 2026-07-31, sha `315dbca16`.** 3 alternating rounds, `--strict-front`, both rows `front=F`,
all 12 rows in `bench/history.tsv`. The gap is NOT smaller than recorded.

| round | load | `lazylist-take` ssc / v2 / v2-bytecode | `list-fold` ssc / v2 / v2-bytecode |
| ----- | ---- | ------------------------------------- | ---------------------------------- |
| 1 | 9.35  | 0.067 / 93.4 / 75.0 | 0.0064 / 6.53 / 1.03 |
| 2 | 15.76 | 0.058 / 29.2 / 29.6 | 0.0057 / 5.03 / 0.959 |
| 3 | 11.80 | 0.061 / 41.6 / 38.5 | 0.0065 / 6.10 / 0.940 |

Median ratios vs `ssc`: `lazylist-take` **682× (VM) / 631× (bytecode)**;
`list-fold` **938× (VM) / 161× (bytecode)**.

**First measurement had to be thrown away, and that is the point of C-0.** Round 0 ran against a
toolchain built from `da5932514` while the repo was at `315dbca16`; the harness's STALE BUILD
warning was correct and the delta touched `BlockRuntime`/`DispatchRuntime`/`EvalRuntime`/`FastTier`/
`Interpreter` — i.e. the `ssc` REFERENCE lane. Rebuilt, re-ran. A stale reference lane silently
moves every ratio in the table.

**Finding 1 — the two rows do not belong in one bucket, now with data.** The bytecode lane wins
**6.3×** on `list-fold` (6.53 → 1.03) and **essentially nothing** on `lazylist-take` (93.4 → 75.0;
29.2 → 29.6; 41.6 → 38.5 — inside that row's own spread). Whatever `lazylist-take` pays, compiling
the program to bytecode does not remove it. This is C-4's split, promoted from a guess to a result:
`list-fold`'s remaining 161× is a dispatch/calling-convention problem, `lazylist-take`'s 631× is not.

**Finding 2 — `lazylist-take`'s v2 numbers are unstable by 3.2×, and the instability is evidence.**
Across the same three rounds the `ssc` lane held ±8% (0.058–0.067) while v2 swung 29.2–93.4. And it
does NOT track load: the WORST round (93.4) ran at the LOWEST load (9.35), the BEST (29.2) at the
HIGHEST (15.76). CPU contention does not behave that way; heap state and GC timing do. That points
C-2 at ALLOCATION as the first thing to measure, and it is a much sharper prior than "collections
are slow".

**Provenance note, no regression claimed:** the old back-filled row said 395× for
`lazylist-take`/v2-bytecode where the median is now 631×, but that row has no sha and this row's own
round-to-round spread is 2.5×. Not enough to call a regression; enough to distrust the old row.
`list-fold`/v2-bytecode reproduces (146 → 161), which is a mild check that the old numbers were not
nonsense — just unsourced.

### C-1 — v1 ALREADY SOLVED `lazylist-take`, and how it did matters more than that it did

`specs/jit-collection-ops.md`: 190 → 0.058 ms (~3275×), via *pipeline fusion* —
`JitHofShape.lazyFromMapTake` recognises `LazyList.from(start).map(unary)?.take(n)` as the receiver
of a terminal `.sum`, and `JitHofDispatch.lazyFromMapTakeSum` forces only the n-element prefix in a
tight loop. Two facts from that spec carry directly:

1. **It had been declined as "inherent cost" and that was wrong.** The gap was LazyList machinery —
   cons thunks, memoisation — NOT the arithmetic. Do not re-derive that conclusion; it is settled.
2. **It is a shape-matching peephole.** It earns 3275× on this benchmark because the benchmark has
   exactly that shape. Porting it to v2 would make the ROW fast without making `LazyList` fast.

So C-1 forces a declared choice, and the choice must be in the commit message:
- **(a) port the peephole** — cheap, large number on the board, honest ONLY if labelled as a
  benchmark-shaped fusion. Legitimate as a stopgap; dishonest if reported as "LazyList is fast now".
- **(b) fix the machinery** — cons-thunk and memoisation cost in the v2 runtime, which is what the
  row is a proxy for. Slower, no headline multiple, generalises.
Default is **(b)**; (a) only with the label. The gate must show which was done.

**REVISED by C-2's measurement (2026-07-31).** The (a)/(b) split assumed "the machinery" was
something v2 owns and could fix. It is not: v2 delegates to `scala.collection.immutable.LazyList`
wholesale (`Runtime.scala` ~2456), and that library is 64% of the time and 1824 of 2848 bytes. So:
- **(b) as originally written — "fix the machinery" — means writing v2's own lazy-sequence
  representation.** That is a large project and should not be entered by accident.
- **There is a third option the measurement suggests, and it is the better one.** v2's `map` arm
  builds a NEW Scala LazyList per `.map`; `take(n)` then forces the whole stack. If `map` instead
  accumulated a pending function and `take`/`sum` applied the composition WHILE forcing n elements,
  the intermediate LazyLists disappear — one allocation instead of a stack of them. That is fusion,
  but at the RUNTIME level, in the `map`/`take`/`sum` arms, rather than v1's syntactic shape-match.
  It generalises to any pipeline of the same algebra, not just the one the benchmark writes.
- The VM closure call (37%, ~19 ns/element) is a SEPARATE lever and belongs with P-5, not here.

Recommendation: **(c) runtime-level fusion in the `ForeignV(LazyList)` arms.** It gets the 64%
without the shape-matching fragility and without a representation rewrite.

**PROBED 2026-07-31 — and (c) as I specified it is REFUTED. My own estimate was wrong by 2.5×, and
the probe caught it before any kernel edit.** All layers one run, `-prof gc`:

| layer | ns/op | B/op | vs today |
| ----- | ----- | ---- | -------- |
| `vmClosureLazyList` (today) | 435.3 ± 10.2 | 2848 | — |
| `fusedOneMemoisingChain` | 312.3 ± 13.1 | 2112 | **1.39×** |
| `fusedIterator` | 87.4 ± 13.2 | 384 | **5.0×** |
| `fusedManual` | 82.6 ± 12.0 | 384 | 5.3× |
| `floorLoop` | 0.97 | ≈0 | — |

I predicted collapsing three chains into one would give ~3.5×. It gives **1.39×**. The cost is
MEMOISATION ITSELF — the cons cell and the lazy state per element — not the number of chains. One
memoising chain still allocates 2112 of the 2848 bytes.

**So C-1 is a product decision, not a perf fix, and the fork is now priced:**
- **keep `LazyList` memoising** → ceiling **1.39×** on this row, zero semantic risk;
- **drop memoisation** (iterator adapters) → **5.0×**, and `fusedIterator ≈ fusedManual` means a
  single non-memoising chain gets essentially the whole win — no hand-rolled loop needed.

The semantic cost of dropping it is precise: a `LazyList` bound to a `val` and consumed twice would
re-run its `map` function, so a side-effecting map fires twice, and the second consumption of an
exhausted source is wrong. **JS and Rust ALREADY made this trade** (`specs/lazylist-all-backends.md`:
iterator adapters, with the reuse case "deferred (documented) — typical code chains-then-forces").
The VM is the case where it was NOT made, because the VM is the reference the other lanes are
compared against.

**Not implementing either until the choice is made.** Landing the 5.0× silently would change
language semantics on the reference lane; landing the 1.39× would burn the shared kernel for a
number the row cannot feel under its own 2.5× spread.

After whichever is chosen, ~83 ns and 384 B remain, all of it the VM closure call (8 × ~10 ns / 48
B). That is P-5's lever, not this one — which C-0's Finding 1 already predicted.

### C-2 — attribute the cost with the harness that now exists (do before either C-1 branch)

P-4 landed `V2DispatchBench` with ±0.02–0.11 ns resolution. Add `V2CollectionBench` beside it
measuring the LazyList primitives DIRECTLY — cons-thunk allocation, memoisation write, force of one
element, and a strict `List` fold step for `list-fold` — against a raw-JVM floor, in layers, the
same way `V2DispatchBench` decomposes dispatch. A whole-workload ratio cannot attribute; that is the
entire reason the cluster stalled at "architecture line".

Cost note from P-4: a cold `Jmh/compile` in a fresh worktree builds ~290 Scala sources and exceeds
30 min on a contended host. Budget it; it is not a hang.

**DONE 2026-07-31.** `V2CollectionBench` landed. Times from the plain run, bytes from `-prof gc`
(the profiler adds overhead, so the two columns come from the two runs and are not mixed):

| layer | ns/op | B/op | what it adds |
| ----- | ----- | ---- | ------------ |
| `floorLoop`         | 0.809 ± 0.005 | ≈0 | — (8 multiply-adds, no collection) |
| `scalaLazyList`     | 261.6 ± 1.4 | 1824 | **Scala's LazyList machinery** |
| `boxedLazyList`     | 256.4 ± 1.3 | 1824 | v2 boxing — **nothing** |
| `vmClosureLazyList` | 407.8 ± 6.4 | 2848 | the VM closure call, ×8 |
| `strictFoldStep`    | 99.7 ± 3.3 | 640 | (`list-fold`'s shape, for contrast) |

**Attribution of what v2 pays for `LazyList.from(s).map(_*2).take(8).sum`:**
- **64% of the time and 1824 of the 2848 bytes is `scala.collection.immutable.LazyList` itself** —
  paid before v2 does anything. 228 B and ~33 ns PER ELEMENT to deliver one multiply.
- **the VM closure call is 37%** — +151 ns and +1024 B for 8 elements, i.e. ~19 ns / 128 B each.
- **the arithmetic is 0.2%.** 0.809 ns, and it allocates nothing.

**Boxing is REFUTED, unambiguously.** `boxedLazyList` allocates `1824.002 B/op` and `scalaLazyList`
allocates `1824.002 B/op` — identical to the milli-byte — and the boxed version is 5 ns FASTER.
`Value.IntV` interns −128..4096, so the corpus range costs nothing. Any future proposal to attack
this row by reducing boxing is answered here; do not re-run that hypothesis.

**C-0's Finding 2 now has its mechanism.** The row allocates at ~6.4 GB/s. A workload with that
allocation rate is governed by GC timing and heap state, not by CPU share — which is exactly why the
whole-workload number swung 3.2× and swung OPPOSITE to load. The instability was not noise to be
averaged away; it was the allocation rate showing through.

**This confirms v1's conclusion by an independent route.** `specs/jit-collection-ops.md` found the
gap was "the LazyList machinery (cons thunks, memoisation), NOT the arithmetic" — reached there by
fusing and observing the win. Reached here by decomposition, before changing anything, and it also
rules out boxing, which v1 never had to test.

### C-3 — the discipline this cluster specifically needs

Three separate times a fat profile frame did NOT pay out by its weight: `dataFields` 28% of profile
→ 20% gain; kind-dispatch parts 25% → nothing; `Value[]` 43% → nothing
(`specs/v2-vs-v1-backend-matrix.md`: *"Treat a hot frame as a place to look, never as a size of
prize"*). Those are the three killed hypotheses this cluster is already carrying.

So: **no C-item starts from "this frame is hot".** Start from an allocation that DISTINGUISHES the
slow run from a fast control — the method that actually worked on the 18× (`Value$IntV` 381 samples
in the slow run, absent from the fast run's top 5). If no allocation distinguishes them, the cost is
not allocation and the plan changes rather than the code.

### C-4 — `list-fold` is a DIFFERENT shape; do not assume the LazyList answer transfers

Strict `List` fold, no laziness, no memoisation, no thunks. It shares a bucket with `lazylist-take`
only because both say "collection". It needs its own attribution pass (C-2 covers both), and it may
well land on the closure-call seam rather than the collection seam — in which case it belongs with
P-5 and the dispatch work, not here.

### C-1 — DECIDED 2026-07-31: declined, on the measurement

Sergiy chose **neither option**: keep memoisation, do not spend the shared kernel on 1.39×, move to
P-5. The reasoning is in the numbers above — 1.39× is invisible under this row's own 2.5× spread, and
5.0× would change reference-lane semantics for one row when the remaining 83 ns is a lever that
moves EVERY row.

This is a measured decline, not an abandonment: the cost is attributed, the ceiling for both options
is priced, and the reason for not acting is written down. If someone later wants `lazylist-take`
faster, the work is specified and the trade is already costed — nothing needs re-deriving.

### C-4 — RESOLVED 2026-07-31: `list-fold` moves to P-5

C-0 showed the bytecode lane wins 6.3× on `list-fold` and ~nothing on `lazylist-take`; C-2 showed
`strictFoldStep` costs 108 ns / 640 B with no thunks and no memoisation, i.e. it is closure calls and
`Value` traffic, not collection machinery. `list-fold` therefore belongs with the dispatch work. It
is not a collection problem and should not be tracked as one.

### C-5 — exit criteria (what makes this cluster CLOSED rather than abandoned)

- both rows re-measured with sha + load, in `bench/history.tsv`;
- for each row, one sentence naming WHERE the cost is, backed by a JMH layer or a distinguishing
  allocation — not by a profile share;
- either a landed fix with a before/after and a gate that fails without it, or a recorded decision
  that the cost is structural, with the measurement that supports it. **A measured negative closes
  an item.** Ranking it below other work is a legitimate outcome; leaving it unmeasured is not.

**NOT in this plan, deliberately:** rewriting the v2 collection representation, porting v1's whole
JIT tier, or any change to the shared kernel that is not preceded by C-2's attribution.

### CLUSTER CLOSED 2026-07-31 — against C-5's criteria, item by item

- **both rows re-measured with sha + load** — C-0, 12 rows in `bench/history.tsv` at `315dbca16`. ✓
- **one sentence per row naming WHERE the cost is, backed by a layer or a distinguishing
  allocation, not a profile share** —
  `lazylist-take`: 64% of time and 1824 of 2848 B is `scala.collection.immutable.LazyList` itself,
  37% is the VM closure call, 0.2% is the arithmetic; boxing is 0 and is refuted to the milli-byte.
  `list-fold`: no thunks, no memoisation, 640 B — closure calls and `Value` traffic. ✓
- **a landed fix, or a recorded decision that the cost is structural with the measurement behind
  it** — the latter, deliberately: C-1 declined with both options priced (1.39× / 5.0×). ✓

**Cost of the whole cluster: zero lines of kernel code.** Three hypotheses were killed by
measurement instead of by implementation — boxing (refuted, identical bytes), chain-count fusion
(1.39×, not the ~3.5× I predicted), and "collections are inherently slow" (they are not; 5.0× is
available, it is simply not worth its semantic price today).

**What it produced for other work:** `V2CollectionBench` is permanent apparatus; the closure call is
now measured at ~10–19 ns / 48–128 B per invocation from two independent shapes, which is the number
P-5 will be judged against.
