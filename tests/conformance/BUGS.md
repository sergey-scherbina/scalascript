# Conformance corpus and its freeze — bugs

Scope: defects whose FIX goes in `tests/conformance/`. Layout and routing rules:
`specs/work-tracking-layout.md`. Entry format (the header is parsed, the prose is not):
`specs/bugs-index.md`. Query with `scripts/bugs-report --module tests/conformance`, never by
grepping for status.

Newest first.

## a-rostered-case-stayed-red-for-two-months-while-the-gate-that-owns-it-was-red-too

<!-- status: open
     lane: apparatus
     area: conformance
     fixed-in: -
     gate: - -->

**THE GATE IS GREEN AGAIN — 2026-08-07, run 31132402054 on `c5c3d7d27`, all four shards `success`.**
First green since 2026-08-01, so it was red for five consecutive nights. That sha carries both
fixes, verified by ancestry rather than by assuming: `8abe91b6a` (the `fewer-braces-colon`
regression) and `0f1f01111` (the stale baseline row). This is the level-1 evidence the two claims
that landed them could not obtain at the time, because every run attempted that evening came back
with shards `cancelled` and no verdict.

The entry STAYS OPEN. Green today does not discharge the third item below — the nightly still costs
27 minutes, and that cost is what made five red nights cheap to leave. What is closed is the
backlog of causes, not the property the entry is about.

`content-linked-namespaces` failed on every lane from 2026-06-05, the day it was added, until
2026-08-05. It is in `contract-roster.tsv` and has NO row in `corpus-baseline.tsv`, which is the
exact shape `contract.sc` calls a REGRESSION — so the gate that owns this corpus should have been
red about it for two months.

It probably was. `corpus-contract.yml` is `failure` on 5 of its last 8 nightly runs. A gate that is
red more often than green stops being read, and then a genuine regression inside it is
indistinguishable from the noise it already carries. That is the defect worth fixing here — not
the one case, which is now green on INT, JS and JVM.

**What this needs, and none of it is the case itself:**

- ~~why the nightly is red~~ — **ANSWERED 2026-08-06, and my own hypothesis above was wrong.**
- ~~whether any OTHER rostered case is in the same position~~ — **ANSWERED: no.**
- a smaller signal than a 27-minute nightly, since the cost of the current one is what makes a red
  cheap to leave. **STILL OPEN — this is what remains of this entry.**

Found while fixing the case, not by the gate reporting it.

### Why the nightly was red — measured, not inferred

The guess above was that the failing runs report jobs with no conclusion, i.e. a job TIMEOUT
surfacing as `cancelled` (`project_corpus_contract_gate_0727`), so the run status might not be
describing a corpus problem at all. **It does not describe any run in this window.** Every nightly
from 08-02 to 08-06 has four jobs with real conclusions, and not one `cancelled`:

    date        run       jobs (shard 0..3, order as reported)
    2026-08-06  failure   failure  success  success  failure
    2026-08-05  failure   success  failure  failure  failure
    2026-08-04  failure   failure  failure  success  failure
    2026-08-03  failure   failure  success  success  success
    2026-08-02  failure   success  success  failure  success
    2026-08-01  success   success  success  success  success

So the five reds are corpus CONTENT, every one of them, and the July timeout pattern is not what
has been happening. The content was the four stacked causes already named in `v2/BUGS.md`: a new
case absent from the roster, this `fewer-braces-colon` regression, a second regression, and an
improvement. Carrying a July explanation into an August window without re-measuring it is the
error worth naming here — it is the same shape as reading a regression row off a later run and
attributing it to an earlier break window, which happened to me on the same gate the same day.

### Is any OTHER rostered case rostered, non-PASS and unbaselined — no

The 08-06 nightly ran the WHOLE corpus across its four shards: two shards `✓ contract GREEN`, one
reporting exactly one REGRESSION row (`fewer-braces-colon v2 FAIL`) and one reporting exactly one
IMPROVEMENT (`rozum-agent-schema-derived js`). A case that is rostered, non-PASS and absent from
the baseline is precisely what `contract.sc` prints as a REGRESSION, so a full four-shard sweep
reporting one such row is the direct answer the entry says nobody had asked for. Both rows are now
closed — the regression in `8abe91b6a`, the stale baseline row in `0f1f01111`.

This answer has a shelf life of one corpus change and is not a gate; it says the queue was empty on
2026-08-06, not that it stays empty.

### A cancelled run is still not a verdict, and it happened while answering this

Two manual `workflow_dispatch` runs on 2026-08-06 19:21 and 19:38 could not produce a verdict: the
first reported all four shards `cancelled` at exactly 15:00 elapsed with ZERO steps recorded, the
second sat `queued` for over half an hour without starting, while Smoke and Lint Markdown ran
normally in the same window. That is the phenomenon the guess above describes — it just was not
what the nightlies were doing. It is recorded here because it is the trap this entry exists for: a
`cancelled` run reads like a verdict and is not one, so the fixes landed today still have no CI
confirmation, and the 04:00 UTC nightly is where it will come from.

## corpus-contract-freeze-pairing-unchecked — editing the baseline without the roster header bricks the whole gate, and the freeze gate does not notice
<!-- status: fixed
     lane: apparatus
     area: conformance
     fixed-in: 261607982
     gate: - -->

**Found 2026-08-01** by `js-compound-assign-and-gate-registry`, and only because that claim
reconstructs a freeze's CURRENT hashes and asserts they match before hand-editing it. The assert
fired on `origin/main`, not on my edit.

`contract-roster.tsv`'s header pairs the two frozen files:

```
# corpus-contract-roster-v1<TAB>baseline-sha256=<of corpus-baseline.tsv><TAB>roster-sha256=<of this file>
```

`f6e93154e` ("delete the v2 known-red — the suite required it") changed `corpus-baseline.tsv` and
did not update `baseline-sha256`. The result is not a wrong verdict, it is NO verdict:

```
$ scala-cli tests/conformance/contract.sc -- --lanes int --only char-as-value
[error] corpus contract freeze invalid: roster/baseline digest mismatch:
        roster=c3ac76fa… baseline=e7636fca…
```

**The whole corpus contract could not run, for anyone**, from that commit until this one.

**Why nothing caught it, and this is the part worth keeping.** `tests/e2e/freeze-consistency-gate.sh`
exists, is registered, and **PASSES** — it checks front-matter against the baseline and the negtc
overrides, not the header PAIRING. And the per-push suite's corpus check does not go through
`contract.sc`, so CI stayed green: the breakage was visible only to the nightly Corpus Contract,
which is the job this repo has already had to rescue once
([[corpus-contract-gate-0727]]-class). A gate next to the hole is not a gate over it.

**FIXED here** by recomputing `baseline-sha256` from the current baseline — the baseline edit itself
was correct (a row deleted because a fix landed), so the header was the stale half. `contract.sc`
runs again and is GREEN.

**The owed half is DONE 2026-08-02** by `gate-holes-sha-and-freeze`: `freeze-consistency-gate.sh`
gained invariant **I0**, checked FIRST because when it is wrong nothing else matters — the contract
cannot start at all.

Proven in both directions rather than asserted: the gate PASSES on the repaired freeze, FAILS with
the exact defect planted (delete a baseline row, leave the header — what `f6e93154e` did), and
PASSES again once restored. It names both digests and tells you the canonical form, because the
person who trips it will be mid-edit.

Why this gate and not another: it already exists to relate the freezes to each other, it runs in
seconds on every push, and its own header explains that `contract.sc`'s checks live in the nightly
and cannot stand in for it. The pairing was simply the one relation it did not look at — between the
two files it was already reading.


## corpus-contract-baseline-stale-after-improvements — the freeze records worse results than the code produces
<!-- status: open
     lane: apparatus
     area: conformance
     kind: apparatus
     gate: tests/conformance/contract.sc -->

**Status:** OPEN, filed 2026-07-30 while building the v2-vs-v1 backend matrix; not fixed.

Three `wasm-*` rows have IMPROVED since the baseline was frozen, and the corpus has cases with no
baseline row at all. Neither shows up as a failure, because the contract compares against the
frozen value and an improvement is not a mismatch it reports.

**Why this matters more than the three rows.** A baseline that records a worse result than the code
produces makes the NEXT regression invisible: the case can degrade all the way back to the frozen
value and still pass. The gate is green over a window it cannot see into, which is the same defect
class as a filter that reads one of three board types (`POLICY.md` P-6.3).

Fix is a scoped `--update-baseline` for the improved rows plus a roster pass for the unrostered
cases — but note `corpus-baseline-update-scoped-run-truncates`: a scoped update can erase
out-of-scope rows, so the two interact and the order matters.

## update-baseline-under-load-freezes-a-skip — a busy host turns a working case into a PERMANENT SKIP, on every lane
<!-- status: open
     lane: apparatus
     area: conformance
     gate: none -->

**Found 2026-07-30** by `v2-content-inlines`, in its own `--update-baseline` output. Adjacent to
[[corpus-contract-baseline-stale-after-improvements]] but a different failure: that one is the freeze
recording results WORSE than the code produces; this one is a refresh recording a case as UNMEASURABLE
when it is not. Caught only because
the diff was read line by line before committing.

**What happened.** The refresh produced two changed rows. One was the expected improvement. The other was

```
-rozum-agent-schema-derived	js	TIMEOUT
+rozum-agent-schema-derived	*	SKIP
```

A `*  SKIP` row means the case is dropped for **every** lane, because `contract.sc:690` establishes the
golden from the int lane BEFORE the `backends:` gate. So that single row would have removed a case from
int, js and v2 coverage at once — permanently, and silently, since a SKIP is a legitimate-looking row.

**It was an artifact of the run's own load.** Measured on a quiet host afterwards:

| check | result |
|---|---|
| `ssc-tools run --v1` twice | rc=0 both times, identical output |
| scoped contract run for the case | 2/3 PASS cells, **contract GREEN** against the OLD row |
| `run-js`, timed | 90 s wall at **4 % CPU** — it hangs, so `js TIMEOUT` is the true row |

The golden probe gets 30 s and the corpus runs cases in parallel, so a full `--update-baseline` competes
with itself. Any case near the probe budget can lose that race, and the loser is recorded as SKIP.

**Why this is worse than a flapping verdict.** A flap between `PASS` and `FAIL` is visible and gets
investigated. A flap into `SKIP` looks like a decision someone made, reads as "this case cannot run", and
suppresses all three lanes at once. Nothing in the pipeline distinguishes "int cannot run this" from "int
did not get enough CPU this time".

**Directions, cheapest first:**
1. `--update-baseline` should refuse to write a NEW `* SKIP` row for a case that previously had a lane
   verdict, and print what it would have written. Turning a measured case into an unmeasured one deserves
   a deliberate act, exactly like a golden move.
2. Give the golden probe a larger budget than a lane run rather than a smaller one — it gates every lane,
   so it is the least appropriate place to be stingy.
3. Record the host's load alongside the freeze, so a suspicious refresh can be re-judged later.

**Workaround that worked, for anyone hitting this before it is fixed:** hand-correct the row and recompute
the digests. The baseline file has no header and is plain sorted rows; canonicalisation is
`lines.mkString("\n") + "\n"` in UTF-8; `baseline-sha256` covers the baseline's lines and `roster-sha256`
the roster's NAME lines only, both stored in the roster header. The contract validates both before doing
anything else, so an incorrect digest fails loudly rather than silently — which is what makes the manual
route safe.

## conformance-int-batch-false-fail-and-hidden-stderr — a case fails in the batch, passes everywhere else, and the reason is thrown away
<!-- status: open
     lane: apparatus
     area: conformance
     gate: tests/conformance/run.sh -->

**THE SECOND HALF IS CLOSED 2026-08-07 — the reason is no longer thrown away. The first half (why a
case goes silent inside a large batch) is NOT reproduced and this entry stays OPEN for it.**

Half of it had already been fixed since this was filed: when `run-batch` exits NON-zero, `batchLane`
keeps stderr, prints it, and drops the in-flight case so it re-runs alone. What remained is the shape
every one of the six sightings actually had — **the batch exits 0** and one case's section is empty.
That emptiness went straight to the comparison, which reported every line `got=<missing>` and named
no reason.

**The discriminator is the EXPECTED output**, which is why this is not "distrust every empty
section": a case whose golden is legitimately empty must not be forced onto the slow path on every
run — the same care the non-zero branch already takes by distrusting only the last case. An empty
section with a NON-empty golden is not a legitimate result under any reading. Those keys are now
dropped, so the caller falls back to `run(...)`, which carries `<exit:N>` and stderr.

Exercised rather than assumed, with a planted case that prints nothing against a non-empty golden:

```
[batch/v1]      run-batch exited 0 but 1 case(s) produced NO stdout: _zprobe-silent
                — re-running each on its own so the reason survives.
[batch/emit-js] run-batch exited 0 but 1 case(s) produced NO stdout: _zprobe-silent
```

The lane label is part of the fix: `batchLane` runs once per lane, so the unlabelled message printed
twice and said less than one labelled line does. **Which lane lost the output is the first question a
reader has.**

Regression: int lane 360/360 across eight shards, union checked against the unsharded total, and
zero `[batch]` notes on a clean corpus — the branch does not fire on legitimately-empty goldens.

**What this does NOT do:** it does not stop a case going silent in a big batch. That is the first
half, it has never reproduced in isolation, and it now arrives with its stderr and exit code
attached instead of as a bare `<missing>`. The next sighting should finally be diagnosable — which
is the whole reason this half was filed as certain while the other was not.

**Status:** OPEN, **NOT reproduced in isolation** (observed 2026-07-28 by `v2-native-error-diagnostic`
in a full 350-case local corpus run).

**SECOND INDEPENDENT OBSERVATION, 2026-07-28** (`scljet-sql-double-equals`, different worktree,
different build): a no-memo `run.sh --only 'scljet-*'` sweep — 116 cases — came back
`115 passed, 1 failed`, and the one failure was **`scljet-wal-recover` FAIL [INT] with all three
lines `<missing>`**, i.e. EMPTY output, while `PASS [JS]`. Run standalone on the same binary it
prints all three lines byte-identically to `expected/`. Same batch neighbourhood as the first
observation (which names `scljet-wal`), same shape: empty INT output, no reason retained. So the
first half is not a one-off either — it is reproducible at the batch level and only there. Anyone
sweeping scljet should re-run a lone INT failure standalone before believing it. Filed because the *second* half — the batch lane discarding
stderr — is what makes the first half undiagnosable, and that part is certain.

**Observed.** Full corpus, `tests/conformance/run.sh` on a build of `591ec033e`'s parent:

```
scljet-wal:
  FAIL [INT]
    line 1: expected=db wal-mode bytes 18,19: 2,2   got=<missing>
    line 2: expected=frames: 1, wal bytes: 568      got=<missing>
    line 3: expected=wal fingerprint: 2992441423 2922794968  got=<missing>
  PASS [JS ]
```

`<missing>`, not wrong — the case produced **no stdout at all** inside the batch JVM.

**SIXTH OCCURRENCE 2026-07-28 — and the first with a CONTROLLED contrast that isolates the batch
itself.** `scljet-update-ipk-moves-rowid` FAIL [INT], all six lines `<missing>`, byte-exact in
isolation. What makes this one decisive: **CI ran the same corpus on the same commit
(`cd14a97d5`, run 30370607077) and all four Conformance shards passed.** Same code, same cases,
same goldens — the only difference is that CI shards the corpus 4 ways and the local run does not.
Combined with the 116-case scljet-only sweep that DID trigger it, size alone is not the whole
story, but "sharded green / unsharded red on one SHA" is the cleanest evidence yet that the trigger
is the batch process and not any change under test.

**Practical consequence for anyone reading a local red:** before treating an INT `<missing>` as a
regression, re-run that case standalone and check whether CI's shards were green on the same SHA.
Five of the six sightings would have been mis-attributed to an unrelated change otherwise.

**FOURTH OCCURRENCE 2026-07-28, reported independently in rozum by another agent** — and it is the
strongest data point, because it is a different worktree, a different build and a much smaller
selection: a no-memo `--only 'scljet-*'` sweep (116 cases) came back 115/1, the one failure being
`scljet-wal-recover` FAIL [INT] with all three lines `<missing>` while PASS [JS]; standalone on the
same binary it prints all three byte-identically. So the effect is NOT limited to the ~250-case
full-corpus batch — 116 cases is enough to trigger it — and it is reproducible at the batch level
across independent agents.

**THIRD OCCURRENCE 2026-07-28:** `scljet-wal-read` again, on the `v2-program-tail-string-render` corpus run — a change confined to the v2 CLI's result printer, which the INT lane does not execute. Three sightings now, on three unrelated changes, always `<missing>` stdout in the big INT batch and always byte-exact in isolation. That is the batch, not the changes.

**SECOND OCCURRENCE, 2026-07-28, same shape, different case in the same family.** A later full-corpus
run (for `v2-auto-output-prim`) produced:

```
scljet-wal-read:
  FAIL [INT]
    line 1: expected=page2 from WAL frame: true, differs from base: true   got=<missing>
    line 2: expected=page1 from base: true                                 got=<missing>
  PASS [JS ]
```

`bin/ssc-tools run --v1 tests/conformance/scljet-wal-read.ssc` in isolation prints both lines
byte-exactly. So this is not a one-off: it is the `scljet-wal*` family losing its stdout inside the
~250-case INT batch specifically. Both observations are on changes that cannot touch the INT lane
(a v2 runtime edit and a v2 codegen edit), which is further evidence the trigger is the batch
itself. Whatever it is stays undiagnosable until the batch lane keeps stderr — see below.

**What was tried, and passed every time** (same build, same staged `bin/`):

| probe | result |
|---|---|
| `bin/ssc-tools run --v1 tests/conformance/scljet-wal.ssc` (isolation) | byte-identical to the golden |
| `run-batch` with 2 innocuous cases before it | correct |
| `run-batch` after `scljet-freelist-write-corrupt` (a currently-red case) | correct |
| `run-batch` after BOTH currently-red scljet cases | correct, and stderr empty |
| `run-batch` over the whole `scljet-wal*` + `scljet-readonly-pager-btree` family | correct |

So the trigger is something about the ~250-case batch that a 3–5 case batch does not reproduce —
accumulated process state, a file left by an earlier case, or a limit. **Not** the two red scljet
cases on their own.

**The part that is certain, and the reason this is filed.** `batchLane`
(`tests/conformance/run.sc:443`) calls `run-batch` with `stderr = os.Pipe` and passes only
`res.out.text()` to `splitBatch`. **Stderr is dropped on the floor.** A case that dies inside the
batch JVM therefore reports as "expected X, got `<missing>`" with its actual error — the one line
that would explain it — discarded. The individual-run path does the opposite: `outputWithFailureContext`
deliberately folds stderr and the exit code into the compared text.

**Fix direction.** Make the batch lane keep stderr the way the individual path does: attribute it
per case (the `<<<SSC-BATCH-CASE:` delimiter is already emitted on stdout, so the runner needs the
same marker on stderr, or a per-case capture), and fold it into the compared output. Then re-run
the full corpus and the `scljet-wal` cell will state its own cause instead of `<missing>`. This is
the AGENTS.md apparatus rule in its inverted form: a gate that fails **loudly but blankly** trains
readers to skip it, exactly as a gate that passes blankly does.

**Independent of the change it was found under:** `v2-native-error-diagnostic` edits
`v2/src/Runtime.scala`, which the INT lane (v1's interpreter) does not execute. The same corpus run
was otherwise 323 passed / 3 failed, the other two being cases already declared known-red on `main`
in `9f136e21f`.

## uniml-yaml-official-conformance-gap — YAML 1.2.2 strict corpus is 126/402
<!-- status: open
     lane: apparatus
     area: conformance -->

**Status:** OPEN (accepted 2026-07-28 as UPR-2 from the pinned compare-first gate).

**Real-harness reproduction.**

```bash
sbt -batch \
  "unimlYaml/Test/runMain scalascript.uniml.dialect.yaml.yamlOfficialCorpusStrict"
```

The starting gate compared all 402 cases at source/chunks 402/402, validity 210/402,
semantics 128/402, strict 112/402, 290 differing cases, and zero crashes. UPR-2a.1
(`3341a35a9`) moved the exact current census to validity 214/402, semantics 138/402,
strict 126/402, actual errors 216, 276 differing cases, and zero crashes. Its frozen
baseline SHA-256 is `7cd2d76efd0e26252097722ac1fb7d577936fb9f665ae6e852c811a9098123e3`;
category SHA-256 is
`25d786c1259235b820b550e58c67ef0c31e513771d226bc3ba60ed52d77fe5ad`.
All 94 upstream-invalid cases remain event-red; the current AST post-walk always
synthesizes balanced closing events and therefore cannot represent their official
partial event prefixes.

**Impact.** The safe M3 subset is lossless but is not production YAML 1.2.2 conformance.

**Fix acceptance.** Complete UPR-2a–e without exclusions: exact validity and normalized event
semantics for all 402 cases on JVM and Scala.js, source/chunks 402/402, zero crashes, portable lint,
Core Schema differential, standalone/root transition, and affected conformance green.

## coord-status-ledger-invalid-marker — the required claim ledger is reported as an invalid marker
<!-- status: fixed
     lane: apparatus
     area: conformance
     fixed-in: cfcdae1ab
     confirmed: no -->

**Status:** **FIXED 2026-07-28** in `cfcdae1ab` (reported by the production-readiness coordination
audit; baseline `d900d00cf9`, awaiting reporter confirmation).

**Reported real-script reproduction.**

```bash
scripts/coord-status --no-fetch
```

The status checker reports `.work/active/LEDGER.tsv` as an invalid active marker and suggests
`git mv .work/active/LEDGER.tsv .work/active/LEDGER.tsv.claim`. That advice contradicts the claim
mutex contract: `scripts/coord-claim` deliberately maintains `LEDGER.tsv` as the shared generation
ledger, not as an agent claim.

**Root cause and verification.** The invalid-marker predicate knew about `_placeholder` and
`.claim` files, but was not updated when the claim mutex introduced its required `LEDGER.tsv`.
The fix exempts that exact full path, not the `.tsv` suffix. The end-to-end regression executes the
real script in a throwaway repository: it failed first with both `LEDGER.tsv` and `rogue.tsv`, then
passed with only `rogue.tsv` plus its exact repair command. All four `tests/coord/*.sh` gates pass;
the live `scripts/coord-status --no-fetch` invalid-marker section now prints `none`.

## corpus-contract-freeze-digest-unbound — roster edits do not invalidate the paired freeze
<!-- status: fixed
     lane: apparatus
     area: conformance
     fixed-in: fc5f07f28 -->

**Status:** **FIXED 2026-07-27** in `fc5f07f28` (reporter confirmation
pending). Found by Codex review on `e124cc20f`.

**Reproduce.** Add `coroutine-demo` at the sorted position in
`contract-roster.tsv` without changing its header. The first implementation's
header hashes only `corpus-baseline.tsv`; sorted/unique/subset validation still
passes because `coroutine-demo` has no baseline row. NEW classification has
silently changed while the supposedly paired freeze remains "valid".

**Root cause.** The digest binds the roster header to the failure matrix but
does not bind the roster body itself. Exact checkout-byte hashing also makes an
otherwise valid LF commit fail on a CRLF checkout.

**Fix / done-when.** The versioned header carries both a canonical-baseline
SHA-256 and a canonical-roster-body SHA-256. Canonical LF serialization keeps
the content identity portable across checkout EOL settings. Mutating a roster
name without refreshing the header must fail closed; focused tests also cover
unsorted/duplicate names, a baseline name missing from the roster, and
unknown/untrimmed baseline lanes that would otherwise remain permanently
outside every observed scope.

## v2-trusted-html-isolation-contract-gaps — first WKWebView plan leaves stale and navigation authority ambiguous
<!-- status: fixed
     lane: apparatus
     area: conformance
     fixed-in: 7cc1ff978 -->

**Status:** done (2026-07-11, `7cc1ff978`); reported by `nativeui-reviewer` in the
`scalascript` Rozum room during the read-only design checkpoint for SPRINT plan
`9533d30b5`.

- **Design repro:** a single global "first navigation" allowance can authorize
  a stale `loadHTMLString` after descriptor replacement; target `_blank` can
  double-open or bypass the main-frame delegate; asynchronous rule compilation
  can load current HTML without an installed blocker or publish an obsolete
  failure; and an unscoped size observer can publish after dismantle.
- **Expected:** every HTML generation owns exactly one renderer-originated
  main-frame in-memory load after its rule is installed. Stale compile/load/
  finish/size callbacks are inert; only `linkActivated` absolute http/https or
  non-empty mailto taps reach the shared external-URL predicate and SwiftUI
  `openURL` exactly once, including `_blank`; every other navigation cancels.
- **Isolation/diagnostic gap:** compiled rules match only network subresource
  schemes/types, preserve inline/data resources, and gate the first load.
  Compilation failure yields bounded sourced Unsupported without loading.
  iOS/macOS use explicit finite positive height clamps and dismantle every
  observer/delegate. Forged `NativeUiTrustedHtml` and malformed rawHtml
  sentinels must remain exact sourced diagnostics.
- **Real WebKit rule repro:** the first generated macOS probe correctly withheld
  the HTML load, but `WKContentRuleListStore` rejected the grouped
  `^(http|https|ws|wss|ftp)://` filter because its regex subset does not support
  disjunctions. Emit one independent rule per network scheme with the same
  subresource-only type list and require real compilation before load.
- **Real height repro:** `documentElement.scrollHeight` and `body.scrollHeight`
  retain the current WKWebView viewport as a floor, so a 420-point fragment
  replaced by a 24-point fragment never shrinks. The isolated observer must
  publish a `Range` over body contents plus child bounds independent of the
  current viewport (the body rectangle itself also inherits the viewport
  floor), then apply the frozen finite clamp.
- **Real iOS strict-concurrency repro:** the iOS 16 Simulator typecheck rejects
  direct access to coordinator state and `UIScrollView.contentSize` from the
  Sendable KVO callback. UIKit emits that observation on its main actor; enter
  it explicitly with `MainActor.assumeIsolated` before reading size, checking
  generation/mount identity, and publishing.
- **Rozum implementation review round 1 (BLOCKED):** retain the exact
  `WKNavigation` handle with its generation and authenticate every finish/fail
  callback, so a stopped prior load cannot fail the new source or install its
  iOS observer. Do not remove the currently installed content rule while a
  replacement rule compiles; the old live document must stay network-blocked
  until the latest rule succeeds. Failure recovery keys SwiftUI state by the
  exact `(html, source)` pair, not HTML alone. Expand executable gates for a
  lazy network resource during pending replacement, source-only recovery,
  programmatic cancellation and `_blank`/main handoff authority, data-image
  `naturalWidth`, forged arity/site/source, and coordinator deinit without an
  explicit dismantle.
- **Rozum implementation review round 2 (BLOCKED):** `issuedGeneration` still
  cannot distinguish a queued old `about:blank` policy action from the current
  one. Serialize a single awaiting document-policy generation: a stale action
  consumes/cancels only its old token, and a prepared current load cannot start
  until that token clears. Production must not expose the injectable rule/
  navigation loaders used by probes; compile them only under
  `SSC_NATIVEUI_HTML_PROBE`. Both main-frame and `_blank` delegates must call
  the one handoff function. Execute same-HTML/new-source failure recovery,
  forced old finish/fail, and nil-navigation-start seams rather than asserting
  key inequality alone.
- **Plan/done-when:** freeze these four rules in
  `specs/v2-swift-swiftui-native.md` before code, obtain a second Rozum design
  APPROVE, then implement and execute loopback-zero-hit, data/inline,
  navigation, replacement grow/shrink, stale/dismantle, malformed descriptor,
  macOS, and iOS 16 gates.
- **Fix/verification:** one serialized outstanding document-policy generation
  now gates the latest prepared rule/document load; exact WKNavigation identity
  authenticates terminal callbacks, and compiler/loader seams compile only
  under `SSC_NATIVEUI_HTML_PROBE`. Both delegates share one handoff. The real
  probe executes delayed blocker retention, source-only recovery, forced stale
  terminals, nil load, grow/shrink, teardown, macOS WebKit, and production iOS
  16 typecheck. `nativeui-reviewer` confirmed round-3 APPROVE in Rozum; Swift
  backend 41/41 and `tkv2-*` 12/12 are green.

## v2-http-json-renderer-test-contract — native HTTP test omits the required self-hosted renderer
<!-- status: fixed
     lane: apparatus
     area: conformance
     fixed-in: ff3a52eba
     confirmed: no -->

**Status:** fixed (2026-07-10, `ff3a52eba`); waiting for reporter confirmation
before `done`. Found by codex while verifying the portable Decimal/JSON/HTTP
boundary; regression source `ed945466d`.

- **Real-harness repro:** `scripts/sbtc
  "v2NativeJsonPlugin/test;v2NativeSqlPlugin/test;v2NativeHttpPlugin/test"`
  reaches `HttpNativePluginTest` and fails `Response builders reuse native JSON
  and cache helpers preserve fields` with `self-hosted JSON renderer is not
  installed; import std/json.ssc`.
- **Expected:** provider-level tests obey the post-cutover contract: production
  JSON has no host fallback, and a test that calls `Response.json` installs an
  explicit renderer through `__jsonCoreInstallRenderer` before asserting the
  HTTP bridge output.
- **Root cause:** `ed945466d` correctly made `NativeJsonCodec.stringify`
  require the self-hosted renderer, but the HTTP unit fixture still installs
  only `HttpNativePlugin` and assumes the removed host renderer exists.
- **Fix:** the provider fixture now installs `JsonNativePlugin` and a bounded
  deterministic renderer through `__jsonCoreInstallRenderer`; production
  `NativeJsonCodec` retains the no-host-fallback rule.
- **Verified:** `v2NativeJsonPlugin/test` 3/3 and
  `v2NativeHttpPlugin/test` 4/4 passed in the final 94-test focused gate.
