# Build, CI and coordination tooling — backlog

Can-wait and not-yet-started work whose code lives in `scripts/`. When an item is
picked up it moves to `scripts/SPRINT.md` as `[~]` and gets a row on the root board —
in the same commit as the claim. Layout: `specs/work-tracking-layout.md`.

Sections below were carried over whole from the flat root `SPRINT.md`/`BACKLOG.md`,
verbatim, on 2026-07-30.

## Two rules the 2026-07-30/31 session earned, for the `bugs` and `policy` skills

Both are for `.agents/plugins`, which is a submodule held by other claims — recorded here so they
are not lost, to be handed to whoever holds it.

### PROBE WIDER THAN THE HYPOTHESIS (`bugs` skill)

We have "a gate must distinguish the states it exists for". The missing pair is about the PROBE:
**it must ask what you do not already suspect** — the neighbouring types, the neighbouring lanes.

Measured, not asserted. Every entry I filed from a narrow probe turned out wrong within a day:
"an INT gap" was int AND js; "a uniform gap, all three lanes agree" was two-against-two and then
three-against-two; "a missing table arm" was a missing TYPE on two lanes. One extra line asking about
`Map` and `List` beside `Set` found two gaps that appeared in no entry at all.

So: **an entry carries the measured TABLE, not the single observation that prompted it.** The cost is
one command; the alternative is a tracker that describes symptoms and sends the next person down the
wrong road.

### A TOOL FINDING IS NOT WORK FOR THE SAME HOUR (`policy` skill)

If a defect in the tooling does NOT block anyone else, it gets a tracker entry and nothing more —
no claim, no gate, no script that hour.

Measured 2026-07-31: 75 % of commits on `main` since the previous evening were claim /
claim-update / release-claim. Six tool defects were found in one session; two genuinely blocked
others (a broken `coord-claim`, a submodule pointer to a commit that did not exist) and four did not.
Each non-blocking one still cost roughly six commits — claim, widen, script, gate, entry, release —
none of which improved the compiler. Batch them.

The pull is real and worth naming: infrastructure work always "succeeds", while a fix can end in a
revert. Twice that day the honest outcome was a reverted attempt and a diagnosis, which felt like
failure next to a green gate. The incentive is backwards and only a rule fixes it.

## 2026-07-29 — one machine-readable bug index (Sergiy: "уберём множественность источников и неоднозначность")

**Active claim:** `bugs-index-machine-readable`. Spec: `specs/bugs-index.md`.

**Measured, and the numbers are why this is worth doing:**

| | |
|---|---|
| entries in `BUGS.md` | 614 |
| **with no `Status:` line at all** | **108 (18%)** — invisible to every query |
| words meaning "closed": `FIXED` / `DONE` / `RESOLVED` | 332 / 67 / 3 |
| one-off freeform statuses (`STALE`, `LARGELY`, `MECHANISM`, `ENGINE`, `NO`, …) | 10 |
| entries with two `Status:` lines | 1 — so that was NOT the systemic problem I first assumed |

The consequence is concrete: every agent writes its own `awk` over the prose and they disagree.
That happened on 2026-07-28 when Sergiy asked for the list of remaining work and my query silently
omitted 108 entries.

- [ ] **BSI-1 — schema + gate, gate proven RED first.** `specs/bugs-index.md` defines a header in
      an HTML comment (renders as nothing, parses trivially): `status: open|fixed|wontfix|duplicate
      |unknown`, `lane:`, `area:`, `fixed-in: <sha>` (required when fixed), `gate:`,
      `duplicate-of:`. `tests/e2e/bugs-index-gate.sh` enforces: every `## ` entry has a header, the
      status is in the enum, `fixed` implies a `fixed-in` sha that resolves, slugs are unique.
- [ ] **BSI-2 — migrate all 614 in ONE atomic commit.** `scripts/bugs-index-migrate` derives the
      header from each entry's existing prose. **⚠️ Coordinate first:** `BUGS.md` is SHARED (the
      overlap guard deliberately never blocks on it), so a bulk rewrite silently conflicts with
      whoever is appending. Announced in rozum; wait for `v2-backend-matrix-gaps` to release before
      running it.
- [ ] **BSI-3 — `scripts/bugs-report`.** Derives the v2 view (and any lane/area view) from the
      headers live, so it cannot drift the way `bugs-v2.md` already has — that file still says
      "39 of 522" while several of those are closed.
- [ ] **BSI-4 — make it stick.** The rule goes in `AGENTS.md` and the `bugs` skill: a new entry
      without a valid header fails the gate. Also the prose rule that bit twice on 2026-07-28 —
      the original report is KEPT but under `**Original report (superseded YYYY-MM-DD):**` and in
      the past tense, so a grep cannot land on a stale sentence written in the present.

**Two decisions taken rather than asked** (Sergiy left them to me; recorded so they can be
overridden):
1. The 108 statusless entries migrate as `status: unknown`, not hand-classified now. Hand
   classification is hours at low confidence; `unknown` is queryable and gets resolved as each is
   touched. Classification queued below.
2. `bugs-v2.md` is NOT deleted in this pass. Phase 1 gives it a banner saying its numbers decay and
   points at `scripts/bugs-report`. Phase 2 (queued) folds its per-cluster prose into BUGS.md
   entries and removes the file — deleting human-written analysis for the sake of a principle is
   not a trade worth making blind.

- [ ] **BSI-5 (queued follow-up)** — classify the `unknown` entries; fold `bugs-v2.md`'s cluster
      prose into per-cause BUGS.md entries and delete the file.

## 2026-07-28 — the last two red gates (Sergiy: "что именно ещё осталось до полного зелёного CI")

**`ci.yml` is already green** — run `30363091300` on `c9788cf6f`. What is left is the two SCHEDULED
gates, which is exactly the class MILESTONES warns about: nothing automated reads them, so they can
sit red for days looking quiet. Measured 2026-07-28:

| workflow | latest real verdict | why |
|---|---|---|
| `CI` | ✅ success `30363091300` | — |
| `Lint Markdown (docs-only)` | ✅ success | — |
| `Pages` | ✅ success | — |
| `Corpus Contract` | ❌ failure `30334389306` | stale baseline + unclassified cases |
| `F4 Front Swap Gates` | ❌ failure `30337665226` | 4 genuine F disagreements |

- [x] **corpus-contract-refresh-freeze-0728** — **DONE: `Corpus Contract` is GREEN, all 4 shards** (run `30374351823` on `7fb1e9813`). This is its **first success in 24 runs** — the history is 10 `cancelled` (job timeouts, which GitHub reports as cancellation) + 13 `failure` + this one. Two commits: `81bb58b56` dropped 9 expired baseline rows, `7fb1e9813` rostered `list-apply-method` and `for-yield-layout` and dropped `wasm-scalascript v2`. Four of the nine expired rows were my own loose end — landing SC-2 deleted the `known-red:` front-matter but not the paired baseline rows, so conformance went green while the contract did not. **Removing a known-red means removing BOTH.** The reported `scljet-jdbc v2 TIMEOUT` did NOT recur and was A/B-measured as not-a-regression (26.03 s with SC-2 vs 26.07 s without) — filed as BUGS §`corpus-contract-scljet-jdbc-v2-timeout`. Edited surgically, NOT with `--update-baseline`: that regenerates the whole freeze from one run, and a full run currently drops one INT case with zero output, so it could have frozen a passing case as failing. Original entry:
      *Why:* it is bookkeeping, not a defect — the gate is doing its job and nobody has answered it.
      *What it is asking for, exactly* (shard 0 of 4; other shards similar):
      ```text
      ↕ 1 NON-PASS STATUS CHANGE:   rozum-agent-streaming  v2  FAIL → DIVERGE
      △ 4 IMPROVEMENT(S)/stale baseline — now PASS, still in baseline:
          dataset-from-generator  js      dataset-from-generator  v2
          std-ui-aggregator       v2      std-ui-extended-d       v2
        → re-run with --update-baseline to record the closed gaps
      + new cases needing classification, then refresh the paired freeze with one full run
      ```
      *How:* classify the new cases FIRST, then one full run with `--update-baseline`; the baseline
      and the roster freeze are paired, so a half-refresh leaves it red for a different reason.
      `FAIL → DIVERGE` on `rozum-agent-streaming v2` is a status change, not an improvement —
      decide what it is before recording it, or the refresh launders a regression into the baseline.
      *Gotcha:* `tests/conformance/corpus-baseline.tsv` + `contract-roster.tsv` are the contended
      files; they were UNCLAIMED at 2026-07-28T13:4x but two nearby claims list them, so re-check
      before starting. Reproduce `baseline-sha256` BEFORE writing a new one — that is the
      compare-first check that the refresh routine is the writer's routine.
      *Done-when:* one `Corpus Contract` run reaches `success` (all 4 shards).

- [ ] **f4-lib-variant-disagreements** — close the 4 F-vs-legacy disagreements.
      *STATUS 2026-07-31: BLOCKED ON THE GATE, not on the fix.* These four appear ZERO times in the
      latest classify run (30612605314) — not as MATCH, not as GAP, not as a disagreement. The gate
      compares 299 of 742 programs and drops 428 as `EXCL_ORACLE_ERR` **without printing their
      names**, so there is no way to tell from the report whether these four were fixed, regressed,
      or merely stopped being looked at. `ssc run` shows all four agreeing today, but that path takes
      the delegate-fallback and cannot see an F failure at all — it is not evidence either way.
      Do the visibility fix first (BUGS v2 `f4-classify-compares-40-percent-and-never-names-the-rest`);
      until then any "fixed" claim here is unfalsifiable.
      *Why:* these are `orc=0 frc=1` — the legacy front returns 0 where **F returns 1**. Not
      cosmetic: F is the default front, so each is a program that works on legacy and fails on the
      front that actually runs.
      *The four:*
      ```text
      std-ui-native-backticked-id-lib     std-ui-native-html-lambda-lib
      std-ui-native-pair-lib              wc-card
      ```
      *ANSWERED 2026-07-30 — the lead was half right: it is TWO causes, not one and not four.*
      Asked the compiler instead of guessing (`bin/ssc info --front-report <abs-path>`, one file per
      invocation — it mis-resolves a multi-file relative invocation):
      ```text
      std-ui-native-backticked-id-lib   GAP  unbound global: (global html)
      std-ui-native-html-lambda-lib     GAP  unbound global: (global html)
      wc-card                           GAP  unbound global: (global html)
      std-ui-native-pair-lib            GAP  unbound global: (global x)     <- DIFFERENT
      ```
      **Cause A (3 cases): F cannot lower the `html` string interpolator.** `html` is not an
      `extern def` anywhere in `std` — it is an interpolator, and F emits a reference to
      `(global html)` that `validateNoReader` then rejects. The `-lib` files are the LIBRARIES that
      CONTAIN the interpolator; their "fixed" non-lib siblings are only CONSUMERS
      (`println(HtmlList.render(...))`) and never touch it — which is exactly why those bugs read as
      closed while this stayed open.
      **Cause B (1 case): `std-ui-native-pair-lib` has no `html` at all** — it is a tuple
      destructuring bound from an if/else, with a defaulted param and a nested `val`, and F reports
      an unbound `(global x)` that does not appear in the source, so F is synthesising a binder it
      then cannot resolve. Separate investigation.
      *ATTEMPTED AND REVERTED 2026-07-30 — read this before trying cause A again.*
      Taught F to lex `html"…"` as its own interp token and desugar substitutions through
      `__htmlEscape__`, mirroring legacy's `buildHtmlInterp`. It WORKED on the narrow probe
      (`html"<p>${v}</p>"` with `v = &<>"x` produced `<p>&amp;&lt;&gt;&quot;x</p>`, byte-identical to
      INT) and moved 2 of the 4 gate cases from GAP to F. **It was still reverted, because it made
      things worse where it mattered:** `std-ui-native-html-lambda` (the CONSUMER) went from correct
      output to **`<closure>`**. Before the change F declined the library and delegated to legacy,
      which is correct; after it, F compiled the file and produced a wrong answer. Trading a working
      fallback for a silent wrong result is a regression even though the gate counter improves.
      **What the next attempt must start from:** F ALREADY HAS a prefixed-interpolator mechanism —
      `interpPfxNode` / `mkInterpPfx` (:546) — which my lexer change bypassed by intercepting
      `html"` earlier. Extend THAT path, do not add a second one.
      ⚠️ Two traps confirmed by measurement, both cheap to hit again:
      token kind **7 is the layout NEWLINE** (`lexNL2` :53, `layoutTok` :273) — using it made
      `parseAtom1` read every newline as an interpolation and F failed compiling ITSELF with
      `__arith__: unknown op > for String+Int`; 9/10/11 are also taken, 12 is free. And `wc-card`
      has a SECOND gap behind `html` (`unbound global: (global root)`), so cause A alone was never
      going to fix all three.
      **Verdict: fix F, do not bucket.** Both are `orc=0 frc=1` on real programs that legacy
      compiles; neither is out-of-scope-by-design. Cause A is the bigger prize — an interpolator is
      not an edge case.
      *Original lead (kept — it was the right question to ask):* three carry the `-lib` suffix, and their NON-lib siblings are
      recorded FIXED in BUGS — `v2-native-backticked-identifier`, `v2-native-html-interpolator-parse`,
      `v2-std-ui-closure-pair-match`. So the likely shape is one root cause: the fix landed on the
      path without a library import and not on the path with one. Confirm before assuming — if it
      is one cause, this is one fix and not four.
      *How:* the gate itself states the two legal outcomes — **fix F**, or add each to the manifest
      with a bucket (`GAP`/`OUT`/`DEFERRED`) *after confirming which it is*. Do not bucket to get
      green; a bucket is a claim about the case, and `orc=0 frc=1` on a default-front program is a
      strong prior for "fix F".
      *Done-when:* `F4 classify + dual-run` reports `green = 0 genuine-FAIL`.
      *Detail:* `specs/v2.2-p6.5-classify.expected` is the manifest; run `30337665226` is the
      failing evidence.

---

## 2026-07-28 — four findings from the claim-triage sweep (Sergiy: "запиши все в спринт и делай по очереди")

Queued together because they came out of one sweep, but they are **independent** — different
paths, no shared state, any order works. Being run **sequentially, one claim at a time**, with
long measurements overlapped in the background: three of the four touch `tests/conformance/` or
`.github/`, and the claim overlap guard matches by path prefix, so holding two of these claims at
once would trip it against my own claim.

Context they all share: `main` reached its **first fully green CI run since 2026-07-27T16:31**
(`30354638253` on `4e57e7a60`, 651 commits later) once `Validate ScalaScript` stopped failing its
own self-tests. These four are what that sweep surfaced and did not fix.

- [x] **lint-markdown-own-trigger** — LANDED `680181feb`: `.github/workflows/lint-markdown.yml`,
      job `Lint Markdown (docs-only)`, `on: push/pull_request paths: ['**.md']`, own concurrency
      group, lint step byte-identical to `ci.yml`'s so the two triggers cannot drift.
      **Done-when relaxed, deliberately, and here is why.** The entry below asked for "a code commit
      still does not double-lint", which implied *moving* the job out of `ci.yml`. That is wrong:
      `scripts/ci-status` hard-codes a `required_jobs` list containing `"Lint Markdown"` and reports
      a run omitting a required job as RED, and `tests/e2e/ci-status-guard.sh` pins that behaviour —
      so moving the job would have turned every future green `ci.yml` run into
      `missing required job: Lint Markdown`. Added alongside instead. **Consequence accepted:** a
      commit touching both code and `.md` lints twice (same command, same whole-repo scope, so they
      cannot disagree; seconds of runner time, no build/cache/matrix). Un-breaking the health tooling
      to save that second is the bigger and riskier change; parked, not forgotten — if it is ever
      wanted, it is `required_jobs` in `scripts/ci-status` plus the guard's `missing` case that must
      move first.
      Original entry, kept for the reasoning:
      *Why:* `ci.yml` has `paths-ignore: ['**.md', '.work/**']`, and GitHub skips the run when
      EVERY changed path matches — so a markdown-only commit produces **no run at all**, including
      no `Lint Markdown`. The one job that checks `.md` is the one job a `.md` change cannot
      trigger. Not theoretical: `64a8a3339` (docs-only) introduced hard tabs in `SPRINT.md:137`
      with no run; the failure surfaced hours later on unrelated conformance commit `9f136e21f`
      because `markdownlint '**/*.md'` lints the whole repo at whatever SHA triggers it; and the
      repair `41541482d` was itself `.md`-only, so it could not be verified by CI either.
      *How:* a second workflow (or a `push`/`pull_request` entry with `paths: ['**.md']`) running
      **only** markdownlint. Seconds of runner time, so it does not reintroduce the queue pressure
      `paths-ignore` was added to relieve. Do NOT weaken `paths-ignore` itself — its measurement
      stands (43 of 58 non-`[skip ci]` commits in one hour changed only `.md`/`.work/`).
      *Done-when:* a `.md`-only commit produces a run that lints, and a code commit still does not
      double-lint. Verify with `markdownlint '**/*.md' --ignore node_modules` locally first
      (exit 0 today). *Detail:* BUGS.md §`lint-markdown-unreachable-from-markdown-commits`.

- [x] **conformance-known-red-v2-lane** — LANDED `895e5ecff`. Three probes: declared+failing -> suite green with `KNOWN-RED [V2 ]`; undeclared+failing -> red (so the first is not vacuous); declared+**passing** -> red with `STALE known-red` (self-expiry, the half that matters). `actors-bounded-mailbox` left at `backends: [jvm]` as the entry required. Original entry:
      *Why:* `tests/conformance/run.sc` checks six lanes; five call `checkLane(label, lane, …,
      knownRed)` and consult the map, the **V2 VM lane calls bare `check("V2 ", v2Out, expected)`**
      (~line 511 — no lane key, no map). `parseKnownRed` still parses, validates (exits 1 without a
      reason) and lowercases a `known-red: v2 — …` declaration, and **nobody ever consults it**:
      accepted, then ignored, no warning on any stream. Measured — identical FAIL output with and
      without the declaration. Consequence is structural, not cosmetic: enabling `backends: [.., v2]`
      for a *known* gap leaves only an undeclared red (indistinguishable from a regression) or
      leaving `v2` out (invisible, never expires). Silence is cheaper, so silence wins — a standing
      reason v2 corpus coverage stays low, while v2 self-hosting is stream 1 in MILESTONES.
      *How:* `checkLane("V2 ", "v2", v2Out, expected, knownRed)`.
      *Done-when:* proven **red-then-green** on a real case — declare `known-red: "v2 — …"` on
      `tests/conformance/actors-bounded-mailbox.ssc` with `backends: [jvm, v2]` (it fails with
      `ssc: Actors scope failed: unbound global: Overflow`), suite green; delete the declaration,
      suite red again. **And prove the stale direction**: a `known-red: v2` on a case that PASSES
      must FAIL the suite — that self-expiry is the only thing that makes the mechanism safe to use.
      *Gotcha:* leave the actors case at `backends: [jvm]` when done unless SC-2 lands — enabling it
      is the fail-first half of `v2-actors-bounded-mailbox`, not this task.
      *Detail:* BUGS.md §`conformance-known-red-silently-ignored-on-v2`.

- [x] **scljet-sc2-measure-and-decide** — LANDED `b9b060e6e`. The gate proved the candidate: with it applied the suite failed both cases as `STALE known-red … this lane now PASSES` on all four lanes, so both declarations are deleted in that commit — self-expiry doing its job. **Four A/B cells were needed and I got it wrong twice first**: single-case passes with the candidate; the full suite fails one INT case *both with and without* it, on a DIFFERENT case each time. Filed as BUGS §`scljet-full-suite-int-lane-drops-one-case`. NB the landing commit's message lost a backticked token to shell substitution (`command not found: known-red:`) — cosmetic, not amended because main is shared. Original entry:
      *Why:* two conformance cases are currently **declared `known-red: int,js`** (`9f136e21f`) —
      `scljet-sql-live-reclaim` and `scljet-freelist-write-corrupt`. They are fail-first tests whose
      expectations pin POST-fix behaviour; the candidate fix exists but is unmeasured, so the honest
      state is a declared red rather than shipped-unverified storage-engine code.
      *Baseline:* on `main` the reclaim case gives `update pages=14 … freelist=0` (file grows,
      nothing reused) against an expected `pages=25 … freelist=10`; the freelist case returns
      `RIGHT` for 16 negative observables that expect `left stable=true`.
      *How:* the candidate is branch `feature/scljet-production-completion` @ **`ee382f3f5`** —
      freelist validators (`mutableFreelistInfo`, `mutableStagedCapacity`, `readExistingFree`,
      `validateFreedPages`) plus `pagerDeleteRebalanced`, with `deleteRowidLoop`/`applyUpdates`
      repointed at it. Run `scripts/conformance tests/conformance --only 'scljet-*' --no-memo`
      FIRST and decide from the diff. It is ~340 unverified lines in a storage engine — do not
      green a red test by shipping it unmeasured.
      *Done-when:* either it lands and **both `known-red` declarations are deleted** (the suite
      forces this — a known-red that starts passing fails the run), or the measurement says why not
      and that goes in BUGS. *Gotcha:* the conformance semaphore is host-wide `MAX=1`; a sibling's
      full-corpus run will block you. `SSC_CONFORMANCE_NO_GUARD=1` with a small `SSC_TEST_XMX` is
      the documented opt-out for a handful of cases.

- [ ] **f-accept-declared-externs** — stop counting a legitimate `extern def` as an F coverage gap.
      *Why, MEASURED:* full-corpus census (347 files, `ssc info --front-report`, `d684e6897`) —
      **95 `F` / 213 `BOTH-UNBOUND` / 33 `GAP` / 6 `ERROR`**. So of 246 delegations, **213 (87%) are
      cases where BOTH fronts emit the same unbound global** and F is punished for what legacy does
      identically and is never validated for. `jvmVfsOpen` ALONE is 115 of the 213 (33% of the
      corpus). Accepting *declared* externs would move F's measured breadth **28% → ~90% without
      touching F**, and leaves the real backlog at 33 files (`q`×6, `handle`×6, `html`×4, `summon`×3,
      `effect`×3, `x`×3, then singles).
      *How:* `RunNativeV2.validateNoReader` currently accepts a global only via
      `defNames.contains(g) || g.startsWith("@")`. Accept names the program **declares** as
      `extern def`, keep rejecting unknown ones. **Do NOT simply loosen the check** — it exists
      because genuine F gaps also surface as unbound globals, and loosening trades a loud delegation
      for a silent wrong answer.
      *Gotcha / scope:* this changes the **F4a delegate-fallback contract**, which BUGS flags as an
      owner call; Sergiy approved queueing it here on 2026-07-28. Land it behind measurement: re-run
      the census after and expect `F` ≈ 308, `BOTH-UNBOUND` ≈ 0, `GAP` unchanged at 33 — if `GAP`
      moves, the check was loosened too far and that is the failure mode to catch.
      *Done-when:* census numbers as above + full conformance no worse than baseline.
      *Detail:* BUGS.md §`f-validateNoReader-rejects-plugin-externs`.

---

## 2026-07-28 — the negative-toolchain gate is 77 % of the sbt job

**Active claim:** `negtc-gate-shard`. Continues `build-ram-budget-and-speed` (which sharded the
conformance job) down the same list Sergiy asked for.

**MEASURED** (run 30305919516, the last ci.yml run to reach completion): `sbt — compile and test`
75.6 min, of which `ScalaScript 2.1 standard-only negative toolchain release gate` is **58.1 min —
77 %**. The gate itself is a 152-line wrapper; the time is in two full-corpus sweeps it calls,
`scripts/native-front-corpus --standard` and `scripts/bc-parity-sweep --strict`, each re-lowering
every example through F (the interpreted self-hosting front, ~2-4× the legacy front).

- [ ] **NGS-0 — `--shard i/N` in both sweeps.** Identical shape in both scripts
      (`for f in "$ROOT"/examples/*.ssc` + an `--only` glob filter), so one uniform change: index the
      cases that survive `--only` and keep those ≡ i (mod N). Round-robin, not blocks — same reason as
      `run.sc` and `contract.sc`: the corpus is name-sorted and slow cases cluster by name.
- [ ] **NGS-1 — `--list` in both, and a gate that PROVES the partition.** Sharding a release gate has
      one catastrophic failure mode and it fails GREEN: a scheme that drops cases reports success over
      less than it claims. `tests/e2e/negtc-shard-gate.sh` byte-compares union(shards) against the
      unsharded listing for BOTH sweeps, plus disjointness, balance, and rejection of an out-of-range
      `i/N`. `--list` must not need a built tower, so the gate stays cheap.
- [ ] **NGS-2 — the gate accepts and forwards `--shard`.** `tests/e2e/v21-negative-toolchain-release-gate.sh`
      passes it through to both sweeps and names the slice in its report path, so shards do not
      overwrite each other's TSV.

**NOT in this claim:** the `ci.yml` matrix that would actually spend the win —
`.github/workflows/ci.yml` is held by `v2-perf-prim-dispatch`. The scripts are the substantive part;
wiring is ~10 lines of workflow and lands when that claim clears. Queued in `BACKLOG.md` so it is not
lost if this session ends here.

## 2026-07-27 — native release qualification

**Claim RELEASED 2026-07-28 during triage** (heartbeat 2.7 h stale, worktree
clean, nothing uncommitted). The native release workflow has
never run: its only trigger is a publishing `v*.*.*` tag, so the current default
F frontend/direct-ASM product stack has no release-path evidence on Linux x86_64,
macOS arm64, or macOS x86_64. Visibility (`ci-status-all-workflows`) does not
qualify artifacts; `NEVER-RUN` is the blocker this slice closes.

> **Do not re-enter this task by "monitoring run 30316338197" — it is finished
> and RED.** That was the released claim's `next:` step, and it had already
> failed 2.7 h before the claim went silent, so the task was not blocked on a
> pending result; it was blocked on an unread one. All three qualify legs died
> in the sbt build compiling `testUtils` with an empty dependency classpath,
> before `native-image` ran at all. Exact evidence, and the one diagnostic that
> would explain it, are in BUGS.md §`native-release-unqualified-and-unrelocatable`.
> Whoever reclaims this starts there, not at a new dispatch.

- [ ] **NRQ-0 — specify a non-publishing release qualification contract.** Add
      `specs/native-release-qualification.md` before workflow code. A manual
      `workflow_dispatch` dry-run must exercise the same build/package matrix
      while making release publication structurally unreachable. Define the
      archive layout, launcher/version/hello execution, plugin-host discovery,
      checksum, fail-loud, and exact-run evidence requirements. Explicitly keep
      real tag publication and signing/notarization out of this slice.
- [ ] **NRQ-1 — build the compare-first qualification gate, then wire it.** Add
      `scripts/native-release-qualify` plus
      `tests/e2e/native-release-qualification.sh`; prove the gate red against
      missing/corrupt/wrong-layout/wrong-output fixtures before accepting a real
      package. Run it on each matrix runner before artifact upload, and add a
      credential-safe manual dry-run whose publish step cannot run on dispatch.
      Preserve the tag-triggered release path and make every mismatch print its
      expected/actual observable. Before dispatch, repair the shared
      native-image defaults: configuration files resolve from repository root,
      the invalid `HomeFinder` feature is absent, the runtime-init metadata
      covers `scalascript` and `ssc`, and packaging consumes the actual
      `scalascript-cli` output. `build.sbt` is temporarily held by the UniML
      claim; workflow-only overrides may prove the matrix while waiting, but
      NRQ-1 remains open until the shared default is fixed. Serialize each
      full-ref workflow, accept only exact stable SemVer publication tags, and
      replace existing-release `--clobber` with create-only fail-closed
      publication. Put the privileged mutation behind
      `scripts/native-release-publish <tag> <dist-dir>` and prove it through
      `tests/e2e/native-release-publication.sh`: compare the exact nine-file
      asset set and checksum bytes, require a confirmed release-by-tag 404
      before one exact `gh release create` invocation, and make malformed tags,
      pre-existing releases, ambiguous lookup failures, argv drift, and create
      failures red before any real tag is involved.
- [ ] **NRQ-2 — execute and record the real three-platform dry-run.** Validate
      workflow YAML and shell syntax locally, run the mandatory affected
      conformance slice, dispatch the dry-run, and require every declared
      platform job plus artifact inspection to succeed. Record the run id/SHA
      and inspect `gh run view <run-id> --json jobs`: the qualifier and all
      three matrix legs must succeed, while `Publish qualified tag` must be
      skipped on dispatch. A cancelled/skipped matrix leg is RED; the skipped
      publish job is required safety evidence. Do not call local Linux evidence
      a cross-platform qualification.

## ci-red-main — `origin/main` CI is red on EVERY run and nobody noticed (2026-07-16, found by the orchestrator)

**The headline: `origin/main` had 192 consecutive CI runs with ZERO green**, going back past
2026-07-15T01:59 (the limit of the `gh` fetch — it may be older). Every agent kept pushing "verified
green" work into a build that did not compile. Local gates were genuinely passing; CI was genuinely
failing; nobody was reading CI. **Treat "my slice is green locally" as meaningless until this lane
is closed** — see the platform trap below for why local green proves less than it looks.

Failures are LAYERED — fixing one reveals the next, so the run stays red until all are closed:

- [x] **1. `sbt — compile and test` — the build did not compile.** `case other =>` in
      `MarkdownInlines.scala:201` + `MarkdownLexer.scala:105` binds an unused variable; production
      code is `-Werror` (`build.sbt:53`), so `[E198] Unused Symbol` was FATAL. `unimlMarkdown` +
      `unimlMarkdownJs` failed `compileIncremental`, failing the job at its FIRST step
      (`sbt compile cli/assembly installBin`) — every later step never ran. FIXED `440297402`
      (rebased → `d129ea0db`): `case _ =>`. Verified: full `sbt compile` green, unimlMarkdown/test
      32/32. CI confirms the job now gets PAST compile.
- [x] **2. `Lint Markdown` — 3 violations.** `BUGS.md` (a wrap put `#16)` at line start → MD018 read
      it as a heading), `specs/v2.2-p6.0-spike-notes.md` (MD047 missing trailing newline),
      `v2/SPRINT.md` (the printf grammar `%[-#+ 0,(<]*[w][.p]<letter>` parsed as a reference link
      `[w][.p]` → MD052). FIXED `d129ea0db`; **CI now green on this job.**
- [x] **3. Conformance — all 51 `scljet-*` cases fail on Linux, pass on macOS.** NOT flakiness, NOT
      the memo. The launchers passed no `-Xss`, so the tree-walking interpreter ran on the JVM
      **default main-thread stack — 2m on macOS/arm64 vs 1m on Linux/x86_64**. scljet recurses past
      1m ⇒ `StackOverflowError` on Linux only. FIXED `6da679234` (`-Xss64m` in the `build.sbt`
      installBin templates, matching the 64m `RunNativeV2:194` already gives its interpreter thread)
      + `b6ca4b7b8` (regenerate the tracked `bin/ssc`). Full detail + the two reproduction traps:
      BUGS.md `cli-launcher-default-stack-platform-dependent`. **Awaiting CI proof.**
- [x] **4. `ScalaScript 2.1 compiler-free ASM artifact release gate` — FIXED, gate now PASSES (exit 0).**
      Was masked by failure 1. Root cause was TWO stale expectations pinning behaviour that was
      deliberately changed and never propagated here — plus a silence that hid them:
      - **`Pair(1, 3-1)` → `(1, 3-1)`**: `fa308e0da` (Sergiy, 07-13) unified tuple rendering to the
        Scala-correct `(a, b)` and updated the ~20 hardcoded `Pair(…)` wants in
        `v2/conformance/check.sh` — but missed the identical line in THREE e2e gates
        (`v21-build-jvm-release-gate`, `v21-slim-distribution-gate`, `v21-native-entry-smoke`).
      - **`PassError(name-resolve, …, 0, 0)` → `[name-resolve] undefined variable: z`**: `PassError`
        (`v1/runtime/std/dsl/passes.ssc:43`) defines `override def toString`, so the gates were
        pinning the OLD BUG where a custom `toString` was ignored.
      - **All 21 assertions were bare `[[ $(…) == "$want" ]]` under `set -e`** — exits 1 printing
        NOTHING (no check name, no diff), which is why the CI log just stopped after the last
        "JVM artifact written to …". Now routed through an `expect_out` helper that prints
        name/want/got/diff. **Apply the same treatment to any gate you touch.**
      Verified locally: `v21-build-jvm-release-gate` PASS, `v21-slim-distribution-gate` PASS,
      `v21-jre-module-gate` PASS.
- [x] **4b. `v21-explicit-lanes-gate` — FIXED, gate PASSES (exit 0, 15 exact rows: provider=8
      target=7).** The prime suspect was WRONG: `cf14fb5b4`/`preprocessEffects` is **exonerated** —
      `preprocessEffects` lives in `v1/lang/core`, which the mcp provider classpath physically
      forbids, and the minimal repro below contains no effect and no mcp at all.
      **Real root cause: the native lane never followed an import written INSIDE a code fence.**
      `v2/bin/ssc1-run.ssc0`'s `sscScanLines` only treats `[names](path)` as an import OUTSIDE a
      fence, and `ssc1-front.ssc0`'s `[` branch no-ops the same link on the assumption its names are
      plugin globals. True for externs like `mcpConnect`; false for a `.ssc` module's declarations —
      so `std/mcp/types.ssc` never loaded and `Transport` stayed unbound.
      **`unhandled runtime effect: X.y` is a RED HERRING** — on the native lane a *field access* on
      an unbound name says `unbound global`, but a *CALL* falls back to the ambient-plugin Op path
      and `V2Result.report` renders that Op as "unhandled runtime effect". Read it as
      "unbound qualified call", never as "the effects system is broken".
      Pre-existing, not a regression: the outside-fence-only rule dates to the native front's first
      commit (`0ccecb44d`). BOTH smoke cases failed for this one reason (the 2nd was masked behind
      the 1st): `agent-mcp-toolsource` took `Transport` from `std/mcp/server.ssc`, whose own import
      of `./types.ssc` is in-fence AND multi-line (a one-line grep misses it).
      **Fix shipped = the examples now import above the fence** (the form the rest of the corpus and
      the sibling example already use). The proper fix — teaching `sscScanLines` to scan
      `scalascript`/`scala` fences — WORKS and was written, but is NOT shippable yet: it widens the
      native module graph to `std/agent.ssc`, which the native front cannot parse. See BUGS.md
      `v2-native-front-in-fence-imports-not-followed` + the two parse gaps it is blocked on
      (`…-multiline-curried-def`, fix known+verified but unlanded; `…-try-catch`, undiagnosed).
      **CORRECTION (2026-07-22, `54eae3197`):** the "gate PASSES" above was verified LOCALLY on
      macOS only — the gate stayed CI-RED for an UNRELATED second reason. The sbt CI job failed the
      swift lane: `GPI hop: DEUTDEFF — ACCC` (em-dash) printed as `? ` on the Linux runner (JVM
      `System.out` fell back to the C-locale ASCII `native.encoding`). Fixed by pinning
      `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8` on all three installBin launchers. See BUGS
      `v21-explicit-lanes-gate-swift-em-dash-red`. Classic local-green≠CI-green — reproduced on
      Linux/C-locale via Docker before declaring green this time.
- [x] **4c. The never-run CI gate steps — all run locally now. Two more real bugs found behind them.**
      - `v21-direct-asm-recursion-smoke.sh` — PASS, but it was a **false green**: it pins
        `JAVA_TOOL_OPTIONS=-Xss256k` to prove the compiled lanes need no big stack, and an explicit
        `-Xss` on the java command line BEATS `JAVA_TOOL_OPTIONS`, so `-Xss64m` silently made it test
        64m. Launchers now take `-Xss"${SSC_XSS:-64m}"` (**both generators at the time** — build.sbt
        AND install.sh; the latter then overwrote the former in CI) and the gate sets
        `SSC_XSS=256k`. Measured: `SSC_XSS=256k` → ThreadStackSize 256, unset → 65536.
        The duplicate install.sh generator was later removed by 5h (`b829c8264`).
      - `v21-negative-toolchain-release-gate.sh` — was RED twice, for two different reasons:
        1. `parity.one-sided != 0`, naming a *different* scljet case each run (looks like flaky
           infra; is not). **Not a scljet bug**: `RunNativeV2` built the tower thread with a
           hardcoded **64m** stack, and the tower runs the front + `Compiler.compile` — so
           `-Xss`/`SSC_XSS` never reached the thread that overflowed (raising to `-Xss1g` changed
           nothing, which is what makes this look like unbounded recursion). "Exception in thread
           main" LIES: `runTower` catches and rethrows on the joining thread. Fixed → 512m,
           deliberately NOT sharing a knob with `-Xss` (that knob bounds the USER program on main;
           this bounds the COMPILER — sharing it makes the 256k gate above starve the compiler).
           scljet-bytes `--bytecode` 8/8, was 2/8. BUGS.md `tower-thread-hardcoded-64m-stack`.
        2. then a **stale frozen baseline** (`frontend.total 200→207`, `frontend.ok`/`checker.ok`
           `199→206`, `parity.identical 56→63`) — the corpus grew by 7 examples while the gate never
           ran. Same numbers were hardcoded in the sibling
           `v21-negative-toolchain-release-gate-smoke.sh`; the "same stale string in 2-3 gates" rule
           held again. Both refreshed together.
      - `sbt test` — RUN, and it **FAILS** (as predicted, more masked breakage). CI's `Test via sbt`
        step sits AFTER the explicit-lanes gate, so it has never once executed in CI either.
        **Cause: the Scala.js LINKER, not a test assertion.**
        `uniml/core/src/test/scala/scalascript/uniml/spike/ScalaSpikeSpec.scala` (newfront Phase 0
        harness, `747dbcd9b`) uses `java.nio.file.{Files, Paths}` (line 5) and
        `new java.io.File(dir).listFiles()` (line 539) — but `unimlCross` is a crossProject
        (`build.sbt:567`), so `uniml/core/src/test/` is SHARED and gets linked for Scala.js, where
        those classes do not exist:
        `[error] Referring to non-existent class java.io.File … called from core module analyzer`.
        **NOT FIXED BY ME — `ScalaSpike*` is the live newfront lane and I stayed out of it.**
        Reported to that agent in rozum (2026-07-16/19). Standard fix: move the spec to the JVM-only
        source dir `uniml/core/.jvm/src/test/scala/...` — the corpus harness is filesystem-bound and
        JVM-only by nature.
        The full run then took 43 min and ended `exit 1` with **5 failing suites**, all in other
        agents' lanes — none fixed here, all recorded so they are not lost. **Caveat: this was a
        macOS run; CI is ubuntu, so some of these may be local-only. Re-check on CI before acting.**
        **Read the CI verdict instead of re-running 43 min locally:** with all six v21 gates now
        green, `Test via sbt` finally EXECUTES in CI. It was still in flight when this lane stopped —
        `gh run view 29501968735 --log-failed` (or any newer run) is the first place to look, and it
        settles the macOS-vs-ubuntu question for all 5 at once. Prediction to check, not to trust:
        #2 (the `scalascript.interpreter` import guard) is a pure source scan and should fail there
        too; #4 Swift/Xcode and #5 SQLite-host-lock are the likely macOS-only ones.
        1. `uniml / ScalaSpikeSpec` — besides the JS linker, `C_min … projects cleanly (P6.21)`
           fails on a **CWD-dependent** lookup: *"specs/v2.2-p6.6-cmin.L not found — set CMIN_L or
           run from the repo root / uniml dir"* (`ScalaSpikeSpec.scala:493`). sbt's per-project CWD
           is not the repo root, so it cannot find its own fixture. (newfront lane)
        2. `backendInterpreterPluginTests` — *"value-surface plugins depend only on
           scalascript-plugin-api (no scalascript.interpreter)"* fails: the **scljet-jdbc-plugin**
           (6 files: ScljetEngine/ResultSet/Statement/Catalog/Connection/Driver) imports
           `scalascript.interpreter.{Interpreter, Value}`. An **architecture guard the scljet-jdbc J4
           lane broke** — this one is a pure source scan, so it WILL fail on CI too. (scljet lane)
        3. `cli` — *"standalone release fixtures provide the three documented install channels"*.
        4. `v2SwiftBackend / SwiftBackendTest:149` — *"SwiftUI renderer inventory covers every
           shipped lowerer tag and CSS property"*, `missing SwiftUI tag inventory`.
        5. `scljetVfsPlugin / SclJetJvmVfsHostTest:167` — *"exclusive host lock blocks official
           SQLite in another process"*, `process.isAlive() was false`. (scljet lane; may need a real
           sqlite3 binary — likely environment-sensitive)
      **Current local aggregate (2026-07-17):** `scripts/sbtc "test"` at code SHA `aca439fcc`
      (later commits through `edbff55d4` were docs/coord only) finished naturally in 39m17s with
      exit 1. Four XML suites fail exactly in the claimed lanes above: `ScalaSpikeSpec`,
      `StableSpiEnforcementTest`, `SclJetJvmVfsHostTest`, and `SwiftBackendTest`. The same run also
      reproduces the newfront Scala.js linker failure on JVM filesystem classes and the Swift real
      fixture's generated-Scala errors (`selected()` / missing `selectFromView`) followed by its
      in-process `System.exit`. No additional unclaimed failing suite appeared. Individual BUGS
      entries now cover every observed failure; clean newfront/SclJet claims and dirty Swift
      subagent worktrees still require explicit takeover direction before implementation.
- [~] **5. CI re-audit — ALL SIX v21 GATES NOW PASS IN CI. Two jobs still red; not green overall.**
      **CI-CONFIRMED green (run 29501968735, observed step-by-step — this is proof, not inference):**
      `Lint Markdown` · `Validate ScalaScript` · and in the sbt job, in order: `compiler-free ASM
      artifact release gate` · **`explicit provider and target lanes`** (the 4b deliverable, red
      through all 192 runs) · `physically slim standard distribution gate` · `JRE-shaped no-javac
      module gate` · **`standard-only negative toolchain release gate`** · **`direct-ASM local
      recursion gate`**. The last two had NEVER executed in CI before — their green is the CI proof
      of the tower-stack (64m→512m), freeze-refresh (200→207) and `SSC_XSS` fixes.
      **Still red, both understood and recorded:**
      - `Conformance Suite`: **279/281** (from 228/53), zero scljet — the 2 are the JS pair below.
      - `Test via sbt`: the 5 suites in 4c, all in other agents' lanes.
      Observed, not assumed:
      - `Lint Markdown` GREEN. `Validate ScalaScript` GREEN.
      - `Conformance Suite`: **228 passed/53 failed → 279 passed/2 failed of 281. Zero scljet
        failures** (run 29495952418) — the `-Xss64m` fix is CI-confirmed. The SPRINT's guess about
        `std-monaderror`/`dataset-error`/`coroutine-error` was wrong: they pass. The real remaining
        2 are both JS-lane, both pre-existing, both reproduce locally:
        - `deep-tail-recursion` — NOT a node-stack analogue (that hypothesis is falsified).
          `run-js --v2` exits **1 for every program**, including `println("hi")`, while node itself
          exits 0. BUGS.md `run-js-v2-always-exits-1` has the full ruled-out list + the one loose
          thread; the next thing to check is `ssc.Runtime.exitHandler`'s DEFAULT.
        - `dataset-from-generator` — a separate JS runtime dispatch gap (`Method not found`).
      - `sbt — compile and test`: my mcp fix is **CI-CONFIRMED** (`PASS v21-explicit-mcp-provider-smoke`,
        all 8 provider smokes green in run 29495815304). The job then died one step further on
        `Cannot run program "scala-cli"` — the sbt job had no `Setup Scala CLI` step yet runs a gate
        whose lane is explicitly `wasm-target-tools`. Added it. **The objection that this breaks the
        compiler-free premise is unfounded and was tested, not argued**: `v21-build-jvm-release-gate`
        and the negative gate each build their OWN `toolbin` sandbox PATH and assert scala-cli is
        unreachable *there*; my machine has scala-cli and both gates PASS on it.
        With scala-cli present the gate reached the **wasm target smoke**, which died with a bare
        `##[error]Process completed with exit code 1` and no output. Pushed named diagnostics
        (`5428bf566`) instead of guessing — and the very next run named it:
        ```
        v21-explicit-wasm-target-smoke: FAILED check pure: node run
        [CompileError: WebAssembly.instantiate(): Unknown type code 0x5e,
         enable with --experimental-wasm-gc @+15]      Node.js v20.20.2
        ```
        **The emitted wasm needs a modern V8 twice over.** CI found the two reasons one at a
        time, each time named by the diagnostics rather than guessed:
        - **node 20 → WasmGC**: `CompileError: Unknown type code 0x5e`. Not fixable with
          `--experimental-wasm-gc` (**measured**: modern node rejects that flag, `bad option`,
          because WasmGC is standard there).
        - **node 22 → JS string builtins**: `TypeError: Import #120 module="": module is not an
          object or function`. Diagnosed: the module has **484 imports, 364 with module name `""`**
          (rest = `__scalaJSHelpers` 41 / `__scalaJSCustomHelpers` 70 / `wasm:js-string` 9), and
          those 364 are satisfied by the option the generated `__loader.js` passes —
          `{ builtins: ["js-string"], importedStringConstants: "" }`, the imported-string-constants
          proposal. A V8 that ignores it leaves all 364 unbound.
        A pure works-on-my-machine gap: it passes locally only because dev node is newer (mine is
        v26, which has both) — invisible until now because this gate had never run in CI.
        Fixed in `21f87ac22`: **sbt job → node 24** (has WasmGC + JS string builtins); lint and
        conformance stay on node 20 on purpose — neither runs the wasm gate and conformance is at a
        hard-won 279/281. The smoke also guards up front (`node >= 24 required (found vX)` + both
        reasons), so this cannot regress into another cryptic loader error.
        **Awaiting CI proof — verify it.** node 22 was verified WRONG by CI, so do not assume 24 is
        right until a run says so.
      - **Still unverified: no fully green run has been observed.** The last pushes had not completed
        CI when this lane stopped. Next agent: `gh run list --workflow=ci.yml --branch=main`, and
        expect conformance to stay red at 279/281 until the two JS bugs above are fixed.
- [ ] **5a. Establish the live CI baseline before changing code.** Let the newest relevant
      `origin/main` run settle, then inspect every job with `gh run view <id> --json jobs` and the
      failed logs. Record the exact SHA, failing steps, and output here. Do not infer today's state
      from run `29501968735`: many independent lanes have landed since it. A failure with no named
      expected/got diff is itself an apparatus defect and must be made diagnostic before guessing.
      Local assembled baseline at `771b67d45`: `deep-tail-recursion` is PASS INT/JVM and FAIL JS
      solely with a phantom fourth line `<exit:1>` after byte-correct stdout; `dataset-from-generator`
      is PASS INT/JVM and FAIL JS with `Error: Method not found: += on 1`. Both were forced with
      `--no-memo`. GitHub run `29545769651` (`893bf2632`) had lint/validation green while its two
      long jobs were still running; newer claim-only SHAs do not replace the need to inspect it.
      **Live Linux baseline update:** run
      [`29547121050`](https://github.com/sergey-scherbina/scalascript/actions/runs/29547121050), SHA
      `1e6ccb394`, proves the corpus itself green at **282/282 (+2 pending)**; its conformance job then
      fails in `Run examples on all three backends` because all JS/JVM commands use standard
      `bin/ssc`. Run `29547476776`, SHA `0018dbf0c`, independently reaches the sbt release gates and
      dies in `v21-slim-distribution-gate` with no diagnostic. Both runs' sbt/conformance siblings
      were still in progress when recorded.
      **Completed Linux sbt baseline:** run
      [`29544412767`](https://github.com/sergey-scherbina/scalascript/actions/runs/29544412767),
      SHA `73407430457effd61bb96307c4bb41c6d3df3179`, job `87773372863`, ran all six v2.1
      release gates green, then entered `Test via sbt` at `00:53:54Z`. Before the outer job timeout
      cancelled it at `01:48:45Z`, the log recorded: two claimed newfront filesystem failures,
      one claimed Swift inventory failure, one claimed SclJet VFS host-lock failure, the now-fixed
      standalone installer fixture, and four unclaimed `V2TuplePatternCliTest` failures. Every tuple
      case reports the same apparatus error: `native frontend requires a staged installation
      (ssc.lib.path is unset); run ... installBin and use bin/ssc`. The run cannot be the final
      four-job baseline because its conformance SHA predates the JS fixes and the sbt job itself was
      cancelled; 5m/5n below make the two unclaimed findings actionable.
- [x] **5b. Close `run-js-v2-always-exits-1` in the real launcher.** Reproduce with the assembled
      `bin/ssc-tools run-js --v2` tiny-program matrix from `BUGS.md`, trace the JVM exit after node
      returns 0, add a regression that asserts both stdout and process exit, then run
      `tests/conformance/run.sh --only 'deep-tail-recursion' --no-memo`. Keep node's exit and the
      CLI process exit as two separately printed observables so the gate cannot pre-judge success.
      **DONE `8333cf97a`:** the same-line catch compiled `System.exit(1)` outside its exceptional
      arm; installed-launcher regression added, focused conformance 1/1 INT/JS/JVM.
- [x] **5c. Close the independent `dataset-from-generator` JS dispatch failure.** First add the
      missing durable `BUGS.md` entry with the assembled-launcher repro and current SHA. Fix the
      owning runtime/plugin boundary (not a test expectation), add a faithful regression, and run
      `tests/conformance/run.sh --only 'dataset-from-generator' --no-memo` on its declared lanes.
      **DONE `1e6ccb394`:** generator-only statement emission now mirrors interpreter compound
      assignment; `GeneratorTest` 15/15 and focused conformance 1/1 INT/JS/JVM.
- [ ] **5d. Reconcile the `sbt test` tail against current CI.** Re-measure all suites from the newest
      completed run; fix every still-red unclaimed suite with a focused regression/gate. Do not edit
      files owned by a live claim (`p65-fixpoint`, newfront, Swift, etc.); consume their landed fixes
      and verify them instead. For an environment-only test, make the prerequisite explicit or skip
      with a proven capability check—never weaken a real assertion to get green. The first completed
      Linux tail is run `29544412767` / job `87773372863`; its only still-unclaimed test family is
      `V2TuplePatternCliTest` (four cases, one missing-staging root), queued as 5m. Its outer job was
      cancelled before the suite completed, so later failures remain unknown until 5n lets a current
      exact-SHA run reach the natural sbt result. The three residual claimed families now also have
      durable BUGS entries: `newfront-scala-spike-fixture-paths-linux`,
      `swift-renderer-inventory-missing-shipped-tag`, and
      `scljet-vfs-exclusive-lock-subprocess-exits-linux`; consume their owners' landed fixes rather
      than overlapping dirty newfront/Swift/SclJet scopes. The current aggregate at `aca439fcc`
      reconfirms a second newfront blocker already described in 4c but previously missing from the
      bug ledger: shared JVM-filesystem `ScalaSpikeSpec` makes `unimlJS / Test / fastLinkJS` fail on
      non-existent `java.io.File` / `java.nio.file.Files`; tracked now as
      `newfront-scala-spike-jvm-test-links-on-js` under the same stale clean newfront claim.
- [ ] **5e. Prove green on GitHub, not by proxy.** Rebase each finished slice on current
      `origin/main`, run its affected conformance/test gate, push separately, and finally wait for a
      CI run containing all fixes. Done means all four jobs (`Lint Markdown`, `Validate ScalaScript`,
      `Conformance Suite`, `sbt — compile and test`) report `success`; record the run URL and SHA.
- [x] **5f. Make the documented local build command work from a worktree.** On a fresh worktree the
      conformance wrapper says to run `bash install.sh --dev`, but `install.sh:56` unconditionally
      executes `git submodule update --init --remote --recursive`, violating the project rule that
      the skills submodule is initialized only in shared main. Pin the failure in a cheap shell gate,
      skip submodule mutation automatically when `.git` is a worktree file, and verify install still
      updates it from the shared main checkout. Until fixed, build locally with explicit-worktree
      `scripts/sbtc "compile cli/assembly installBin"`; never initialize the submodule here.
      **DONE `0018dbf0c`:** linked worktrees resolve the shared git dir and skip submodule mutation;
      the real `bash install.sh --dev` build/staging command completes from this worktree. A CI-wired
      shell gate creates a real temporary worktree, checks both classifications, and proves the
      submodule stays uninitialized.
- [x] **5g. Close the staged-v2-JS `__regfields__` crash exposed by the faithful launcher test.**
      `V2JsLaneCliTest` now runs `bin/ssc-tools`; its imported case-class/companion shape reaches
      node and dies at startup because JsBackend falls through to `$prim("__regfields__")`. Define
      the operation explicitly (no-op only if field accesses are already index-resolved, as on
      Swift), add a direct codegen assertion, and keep the installed `0 / 5 / 8` e2e green.
      **DONE `2f23fd9ec`:** explicit index-resolved no-op + direct assertion; installed suite 3/3.
- [x] **5h. Keep a successful dev install byte-clean.** The first full worktree install after
      `0018dbf0c` succeeds but rewrites tracked `bin/ssc`: only the AppCDS comment and two blank lines
      differ. This proves `build.sbt`'s `installBin` launcher template and `install.sh`'s subsequent
      overwrite are not byte-identical despite the load-bearing "change both or neither" rule.
      Compare the two generated launchers, make one canonical output, then run `cli/installBin` and
      the full `bash install.sh --dev`; done means `git diff --exit-code -- bin/ssc` is green and any
      mismatch gate prints the actual diff.
      **DONE `b829c8264`:** `cli/installBin` is now the sole launcher generator; `install.sh` verifies
      its three executables instead of rewriting them. The new CI gate was proven red on the old
      comments-only diff, prints that patch, and is green after a full install. Both focused
      conformance cases remain 1/1 on INT/JS/JVM.
- [x] **5i. Route the all-examples matrix through the correct installed tiers.** CI run
      `29547121050` has a green 282/282 corpus, then every one of 17 JS/JVM examples fails because
      `examples/run-all.sc` invokes tools-only `emit-js`/`run-jvm` through standard `bin/ssc`.
      Require `bin/ssc-tools`, route INT through `run --v1` and JS/JVM through the matching tools
      commands, and run the full matrix. Done means all 17 print byte-identical output on three v1
      lanes and a missing launcher names the exact path.
      **Diagnosis correction:** moving only JS/JVM removed all 34 standard-tier command failures but
      compared v2 native `bin/ssc` against v1 codegens, reaching 16/17. The matrix historically means
      legacy INT/JS/JVM (the same family as conformance), so INT must also use tools `run --v1`.
      The separately exposed v2 auto-output gap stays open in 5k and is not hidden as "fixed" by this.
      **DONE `aea328279` (after routing slice `ef335ee2c`):** all three lanes use installed
      `ssc-tools`; the complete 17-file matrix exits 0 and reports byte-identical output.
- [x] **5j. Make `v21-slim-distribution-gate` diagnostic before interpreting its Linux failure.**
      Run `29547476776` reaches the gate after two release gates pass, spends 70 seconds, and exits 1
      with literally no check name/diff. Replace every bare assertion that can abort with named
      expected/actual/exit diagnostics, prove the gate still passes locally, then let Linux identify
      the real residual. Never refresh expected output from a silent assertion.
      **DONE `68ff5dacd`, awaiting diagnostic-run confirmation:** all semantic/file/negative checks
      now name the observable and print expected/actual/exit/diff before failing; the complete gate
      passes locally. Later Linux run `29547740771` (`b829c8264`) passes the same slim gate, proving
      the earlier underlying mismatch was not persistent. Run `29548820854`, the first SHA with the
      new diagnostics, is queued and remains the platform confirmation.
- [ ] **5k. Restore v2 native auto-output at every runnable block boundary.** Standard `bin/ssc`
      omits `2`, squares, and `HELLO!` for the three non-Unit fences in `examples/content.ssc`, while
      all v1 lanes print them. Initial "interpreter" attribution was wrong: `bin/ssc` is v2 native;
      legacy interpreter tests are green. Add a v2 multi-block regression (including silent
      Unit/definition tails) and fix the native execution boundary, but **do not touch the current
      live `v2-native-stack-overflow` scope**. This remains a real user bug even after the v1 examples
      matrix is green.
- [x] **5l. Reconcile the standalone release fixture with the stack-safe launcher.** Focused current
      `StandaloneInstallFixturesTest` is 1/2: it looks for stale adjacent `exec java -jar`, while the
      release installer emits `exec java -Xss64m -jar`. Give the generated standalone launcher the
      same `SSC_XSS` override as staged launchers and assert the real java/stack/jar contract; do not
      remove the safe stack default merely to satisfy a substring.
      **DONE `5bde29d37`:** fixture 2/2 plus a real generated-launcher shell e2e; default 64m,
      `SSC_XSS=256k` override, jar path, and argv all exact. The shell gate runs in CI validation.
- [x] **5m. Run `V2TuplePatternCliTest` through the staged native launcher.** In completed Linux
      job `87773372863`, all four tests (typed tuple patterns, nested tuple patterns, tuple val
      destructuring, map-reduce worker calls) abort before semantics with `ssc.lib.path is unset`.
      Reproduce with `scripts/sbtc "cli/testOnly scalascript.cli.V2TuplePatternCliTest"`, inspect its
      process helper, and route it through the real installed `bin/ssc` contract (or the shared
      faithful staged-launcher helper) instead of a fat-jar proxy. Do not change `v2/src` or loosen
      tuple assertions: those files belong to the live native-stack claim and the observed failure
      is test setup. Done means all four cases pass against staged current bits and an affected
      conformance slice remains green.
      **First faithful-launcher result:** 3/4 immediately pass. The map-reduce case reaches native
      execution, prints the expected registry count `3`, then reports `unhandled runtime effect:
      WorkerProtocol.applyStage`. Source inspection corrected the first attribution: this is not a
      Markdown link hidden inside a fence. Native `parseOneStmt` consumes every Scala-style
      `import a.b.*` as a parse-only no-op and assumes names already exist in globals; registry-backed
      `HandlerRegistry` happens to exist, while module-defined `WorkerProtocol` does not. Track that
      separately as `v2-native-scala-import-parse-only-noop`. Keep the map-reduce assertions, but
      express this fixture's dependencies through the currently supported outside-fence Markdown
      links to `std/mapreduce/handlers.ssc` and `distributed.ssc`; do not expand this CI slice into
      the native module-import feature.
      **DONE `e9567c555`:** the suite locates installed `bin/ssc`, preserves stdout/stderr/exit
      diagnostics, and uses explicit Markdown module links for the unrelated map-reduce fixture.
      Current rebased bits pass 4/4; focused `tuples` is green INT/JS/JVM and `distributed-map` is
      green on its declared JVM lane.
- [ ] **5n. Give the sbt job enough outer budget to reach its bounded test verdict.** Run
      `29544412767` started the sbt job at `00:18:32Z`; setup/build/release gates consumed until
      `00:53:54Z`, then the 90-minute outer job timeout cancelled `Test via sbt` at `01:48:45Z`
      after only 54m51s, before its own 60-minute timeout and before the suite completed. Raise the
      outer job budget to 120 minutes while retaining the 60-minute test-step cap, document the
      measured reason beside the workflow setting, and validate the YAML/diff. Done means a current
      Linux run reaches a natural sbt success/failure rather than outer-job cancellation.
      **IMPLEMENTED `90c5599dc`, awaiting Linux proof:** workflow YAML parses, the measured timing is
      documented beside the 120-minute outer cap, and the narrower 60-minute test cap is unchanged.
      **Linux correction before proof:** completed run
      [`29545769651`](https://github.com/sergey-scherbina/scalascript/actions/runs/29545769651),
      SHA `893bf2632`, job `87777659720`, reached `Test via sbt` at `01:17:59Z` and then emitted
      `The action 'Test via sbt' has timed out after 60 minutes` at `02:18:11Z`. At that point
      `CrossBackendPropertyTest` had completed only 12 of its 16 ordered cases; the final four include
      both generated-program matrices. Therefore 120/60 still cannot produce a verdict. Revise the
      measured budget to **150-minute outer / 90-minute test step**: it preserves a bounded hang
      detector, adds 30 minutes for the observed tail, and leaves 30 minutes beyond the release-gate
      + test maxima. Revalidate YAML and require a later Linux run to prove the suite completes.
      **REVISED `884832696`, awaiting Linux proof:** outer/test caps are now 150/90, the measured
      rationale is adjacent to the workflow values, YAML parses, and focused conformance stays green.
      **Final measured correction (`e25faeb79`), awaiting an exact-SHA green run:**
      `29713194883` (`f8e688308`) completed lint, validation, and conformance, then the sbt job hit
      its 150-minute **job** cap while the 150-minute `Test via sbt` step was still running. All
      printed tests were passing; the apparent tail at `GeneratorNativePluginTest` is not a hang
      (the isolated suite is 5/5 in 0.7s). The workflow now uses bounded 240-minute job / 200-minute
      test caps, covering the measured ~45-50-minute release gates plus the >105-minute differential
      test phase. No suite is disabled; the optimization remains in BACKLOG
      `ci-crossbackend-differential-runtime`.
      **First complete-tail result (`29719191208`, `eb879b2c2`):** the raised budget worked: all
      preceding jobs/gates completed and `sbt test` returned a natural verdict. Its only failures
      were three generated scala-cli e2e cases unable to resolve the unpublished
      `scalascript-wire-core` snapshot from a clean runner. This is the test-resource omission tracked
      in BUGS `jvmgen-forked-e2e-wire-core-unpublished-snapshot`, not a frontend-TUI regression.
      **FIXED `2e097848b`:** the established local-runtime-JAR harness now includes `wireCore`; the
      exact failed slice is 3/3 and the complete affected suites are 4/4 + 35/35 with local-Ivy
      resolution disabled. A new exact-SHA run remains required before the claim is released.
- [x] **5o. Make bytecode runtime-separation tests consume the staged compiler drivers instead of
      cancelling.** Linux run `29545769651`, job `87777659720`, runs `installBin` before `sbt test`,
      and its staging log lists `bin/lib/compiler/jars/ (6 JARs incl. compiler-driver)`. Nevertheless
      all five `JvmBytecodeRuntimeSeparationTest` cases report `CANCELED` with `compiler-driver jars
      not staged (run sbt cli/stage)`, so the aggregate can look healthier without comparing the
      bytecode/runtime observables. Reproduce with `scripts/sbtc "cli/assembly; installBin;
      cli/testOnly scalascript.cli.JvmBytecodeRuntimeSeparationTest"`, inspect its prerequisite
      locator, and make it use the same installed tools/compiler-driver contract CI built. Do not
      turn a missing prerequisite into success: a staged run must execute all five assertions and
      print stdout/stderr/exit on failure. Run an affected JVM conformance slice before push.
      **Source-inspection gotcha:** after the driver check, the end-to-end case has a second latent
      Linux skip: `scalaStdlibClasspath()` hardcodes macOS `~/Library/Caches/Coursier`, while GitHub
      uses Linux cache layout. Resolve Scala 3 + 2.13 library locations from the current test JVM's
      loaded class code sources instead of guessing an OS/cache path, so `java -cp` remains the real
      observable on both platforms.
      **Faithful-run result after removing both skips:** 5 tests execute, 0 pass. Four call
      `ujson.read` on current binary `.scjvm`/`.scjvm-runtime` artifacts and fail at byte zero; the
      end-to-end link succeeds but its JAR is 762,184 bytes versus a stale `<400,000` absolute bound.
      Before changing expectations, find and use the production artifact decoder, then replace the
      frozen size threshold with a comparison that still proves runtime separation against the
      artifacts produced in that same run (or document why a current canonical bound is stable).
      Re-run all five to completion; a green skip or an unexplained baseline refresh is not done.
      **DONE `1c109e49e`:** staged `ssc-tools` supplies the real driver/library root; Scala runtime
      paths come from the test JVM on every OS; `JvmArtifactIO` decodes MessagePack; module/runtime
      size is compared relationally within one build. Focused suite executes 5/5 with zero cancels
      and passes; `dataset-parallel-jvm` passes on its declared JVM lane.
- [x] **5p. Make the tracked package-registry seed test run from every sbt project CWD.** Linux run
      `29545769651`, job `87777659720`, cancels `RegistrySchemaTest`'s seed validation because
      `registry/packages.yaml` is resolved relative to a module working directory. The file is part
      of the checkout, so this is not an optional external integration. Reproduce with the focused
      `scalascript.imports.RegistrySchemaTest`, inspect its path candidates, and resolve the repository
      root from stable class/Git/worktree context rather than guessing one parent. Missing a tracked
      fixture after checkout must fail with searched paths; a current checkout must execute the
      schema assertions. Run the focused suite and a small conformance slice before push.
      **Locator gotcha caught by its regression:** a fixed 16-step `Iterator.iterate(path)(_ / ..)`
      eventually asks `os.Path` to move above filesystem root and throws before comparison. Bound
      each walk to `start.segments.length + 1`, which includes root exactly once on every depth.
      **DONE `a99973c16`:** ancestor search starts from process CWD, `user.dir`, and test-class
      location, includes filesystem root once, and explicitly verifies the `v1/lang/core` CWD shape.
      Missing tracked data fails with all candidates. Focused suite passes 15/15 with zero cancels;
      `arithmetic` remains green INT/JS/JVM.
- [x] **5q. Make the real SwiftUI fixture report subprocess failure instead of killing its test
      JVM.** Linux job `87777659720` prints three generated-Scala errors (`selected()` arity and
      missing `selectFromView`) under `SwiftUiRealFixtureBuildTest`, then moves to another suite
      without a named ScalaTest failure. The test itself documents why: in-process
      `buildSwiftUIPackage` calls `System.exit(1)` on compiler failure. After the active Swift owner
      lands the underlying generation fix, route this test through the real staged tools launcher
      in a subprocess, capture exit/stdout/stderr, and preserve its package/file assertions. Do not
      land a red diagnostic-only test or edit production Swift files concurrently; first consume the
      owner fix, then prove a deliberately failing fixture produces a named assertion rather than a
      vanished fork. **DONE (`frontend-tui-fetch-refresh`):** the JVM bridge exports a snapshot
      `selectFromView`, its erased-signal call bridge is scoped to `std.ui.lower`, and the test uses
      staged `ssc-tools package --v1 --target macos`. The real package plus intentional compiler-error
      subprocess tests pass 2/2; SwiftUI is 118/118, TUI 36/36, and browser select reconciliation 1/1.
- [x] **5r. Audit sibling JVM bytecode suites for the same hidden staging/cache assumptions.** The
      60-minute Linux tail reached `JvmBytecodeRuntimeSeparationTest` but timed out before suites
      including `JvmBytecodeLinkCliTest`, `JvmDirectDriverTest`, `ReproducibilityTest`,
      `JvmSmapStackTraceTest`, `SourceMapJvmTest`, and `ClusterMultiBackendMatrixTest`. Source search
      finds the same `ImportResolver.libPath` / `run sbt cli/stage` gates and macOS-only Coursier
      paths in that family. Run each focused suite after `cli/assembly; installBin`, record executed
      versus cancelled counts, and migrate only reproduced false skips to a shared staged-launcher /
      test-JVM-classpath helper. Preserve legitimate missing-tool cancellation for ad-hoc runs; in
      the CI staging shape every available bytecode observable must compare and print its mismatch.
      **First staged baseline:** the first five suites total 6 executed/pass and 13 cancelled:
      `JvmBytecodeLinkCliTest` 1/4, `JvmDirectDriverTest` 0/3, `ReproducibilityTest` 4/1,
      `JvmSmapStackTraceTest` 1/2, and `SourceMapJvmTest` 0/3 (executed/cancelled). Eight false gates
      inspect `ImportResolver.libPath` / the obsolete `cli/stage` shape; five run the fat JAR without
      the installed launcher's `ssc.lib.path`. Source inspection also finds stale JSON readers behind
      those gates even though `.scjvm` is now binary. Record `ClusterMultiBackendMatrixTest`
      separately before deciding whether it belongs in the repair.
      **Comparison audit:** the reproducibility helper compares SHA strings rather than bytes and
      discards ZIP insertion order via `.toMap`; make byte equality/order primary and keep SHA only
      in mismatch diagnostics. SMAP/source-map suites must also fail (with exit/stdout/stderr) on a
      staged compiler command error instead of reclassifying it as unavailable.
      **First faithful staged result:** 19 execute, 15 pass, 4 fail, 0 cancel. Direct driver is 3/3,
      reproducibility 5/5, source-map 3/3, SMAP 2/3, and bytecode-link 2/5. Three failures show the
      first runtime-classpath helper is incomplete (`scala.Predef$` / `scala.Option` absent from
      spawned Java); measure actual test JVM/classpath locations and keep the real run assertions.
      The remaining failure finds `_ssc_runtime.tasty`, `a_sc.tasty`, and `b_sc.tasty` in the linked
      JAR against an old no-TASTY assertion. Inspect the linker spec/history and downstream contract
      before changing either side; do not declare current output wrong from a stale test alone.
      **DONE `11a9e80e2`:** the five suites use installed `ssc-tools`, production binary decoders,
      actual byte/order comparisons, resource-URL Scala runtime JARs, and staged failures with full
      process diagnostics. Tier 5/spec history proves linked TASTY is required, so that stale
      assertion now verifies `a`, `b`, and shared-runtime TASTY. Focused result: 19/19, zero cancels;
      runtime/facade regressions 12/12; `dataset-parallel-jvm` passes.
- [x] **5s. Repair the JS actor `BigInt`/`Number` mismatch exposed by the real multi-backend
      cluster matrix.** The separately staged `ClusterMultiBackendMatrixTest` executes 1 test with
      zero cancellations, starts both generated programs, then the node backend exits in
      `handleActorOp` with `TypeError: Cannot mix BigInt and other types`; its advertised HTTP port
      never becomes connectable and Bully convergence fails. First inspect authoritative actor/JS
      claims, then trace the generated expression back to typed source/IR. If unclaimed, fix the
      shared codegen boundary with a focused regression and rerun the full two-process matrix plus
      the affected actor conformance slice. If claimed, record/consume the owner's landed repair
      rather than editing concurrently. Preserve exit/log/readiness diagnostics; no fixture-only
      coercion or weakened readiness check.
      **Overlap audit:** the only dirty JS worktree (`agent-af1f0d3d39673a833`) is based on
      `46e91144c` from 2026-07-13 and contains an uncommitted predecessor of the already-landed
      `70dfb5a1f` Long/BigInt change; current `origin/main` has hundreds of newer JsGen lines. It is
      a stale duplicate, not an active actor/JS claim. Leave it untouched and diagnose only current
      origin when 5s starts.
      **Root cause:** `500L` is correctly emitted as JS `500n`, then actor `sendAfter` computes
      `Date.now() + delayMs` without crossing the host timer boundary through `Number`; `sendInterval`
      and timed receive have the same latent shape. Convert all three host-millisecond inputs and add
      a Node integration covering Long one-shot + interval delivery. Also migrate this matrix from
      fat-JAR/macOS-Coursier assumptions to the shared installed launcher/resource-classpath helper;
      on staged CI command failures must fail with exit/stdout/stderr, not cancel.
      **DONE `4a4425f68`, `74ab54c90`:** all three host timer inputs are explicitly converted at the
      boundary; the real Node fixture covers one-shot, interval, timed receive, and clean stop; the
      matrix uses installed tools and portable runtime discovery. Node is 60/60, the staged
      JVM-codegen + JS-codegen matrix is 1/1 with zero cancellations, and actor supervision passes
      INT/JS/JVM.
- [x] **5u. Lower actor `stop()` inside generated JS CPS continuations.** The new Long-timer Node
      regression proves all three millisecond conversions by printing `once`, `interval`, and
      `timeout`, then source `stop()` is emitted as a raw JS call and crashes with
      `ReferenceError: stop is not defined`. Trace normal/CPS actor bare-name dispatch and emit the
      Actor effect operation; preserve `stop()` in the real Node fixture and require clean process
      completion. This is a separate exposed layer, not a reason to weaken the timer regression.
      **DONE `4a4425f68`:** bare CPS `stop()` now lowers to `Actor.stop()`; the unchanged real Node
      fixture reaches clean process completion after proving all three timer paths. Included in the
      60/60 Node, 1/1 staged cluster, and three-lane supervision results above.
- [x] **5v. Repair JVM single-node leader history before activating its conformance oracle.** A
      compare-first run of installed INT, JS, and JVM lanes for `actors-leader-protocol` found
      byte-identical output except `hist1`: INT/JS report `1`, JVM reports `0`. Reproduce with
      `SSC_SCALACLI_SERVER=0 bin/ssc-tools run-jvm tests/conformance/actors-leader-protocol.ssc`,
      trace `electLeader()`/`leaderHistory()` against the actor cluster spec and working lanes, add
      a faithful JVM regression at the lowest shared layer, and rerun all three raw outputs before
      writing expected data. The missing expected file is intentionally retained until equality.
      **DONE `34685277c`:** JVM recorded history only when the leader value changed; empty-id
      single-node claims therefore vanished. History now records every accepted claim while events
      remain change-gated. The faithful e2e failed `0 != 1` before the fix; full generated JVM
      runtime is 35/35, and rebuilt raw INT/JS/JVM outputs are byte-identical.
- [x] **5t. Activate the tracked actor leader conformance cases.** Both `actors-leader-protocol` and
      `actors-cluster-leader` sources are found but skipped because their expected files are absent,
      each reporting 0 passed and 0 failed. Check actor ownership, execute every declared backend,
      and add expected fixtures only after their real stdout agrees; then force the wrapper and
      require non-zero executed counts. Do not derive expected output from a single failing lane or
      count either current skip as a pre-push verification.
      **Compare-first finding:** `actors-leader-protocol` is not ready for an oracle: INT/JS emit
      `hist1=1`, while JVM emits `hist1=0`; all other lines match. Complete 5v, repeat all three
      lanes, then measure `actors-cluster-leader` rather than guessing either fixture.
      **DONE `f403cb952`:** after 5v, both cases have one normalized SHA across all three raw lanes.
      Their measured fixtures activate the runner: 2/2 execute, and each passes INT/JS/JVM with
      `--no-memo`; neither source can silently finish as 0/0 now.
- [x] **5w. Route CI example type-checking through the installed tools tier.** Linux run
      `29549382274` reaches conformance 282/282 and green all-examples parity, then invokes
      compiler-free `./bin/ssc check examples/*.ssc` and fails with the expected tools-tier
      rejection. Local `./bin/ssc-tools check examples/*.ssc` checks the same corpus successfully.
      Change only the workflow command to `ssc-tools`; keep the standard negative contract intact,
      run focused conformance, and require a later Linux run to pass this step and continue.
      **DONE `a421d9077`:** the workflow now names and runs the tools launcher. The standard
      negative command still rejects `check`, the replacement checks the full examples glob, and
      focused conformance is 2/2 on INT/JS/JVM. Linux exact-SHA confirmation remains part of 5a.
- [ ] **6. Prevent the recurrence.** Long-red CI is what let all of this pile up. Decide + record a
      cheap guard (e.g. the loop checks `gh run list` before claiming a lane green, or a CI-status
      line in the claim protocol). Recorded as a question for Sergiy, not a unilateral process change.
      **Evidence for the conversation, from this lane:** every single bug found here was invisible
      because something silently swallowed it — bare `[[ ]]` under `set -e` printing nothing, a gate
      that never ran because an earlier step died, a frozen baseline nobody re-ran, a `-Xss` that
      never reached the thread it was meant for, and a "fix" in one of two launcher generators. The
      guard worth having is probably "a gate that cannot fail loudly is not a gate".
      **Decision (2026-07-17):** Sergiy's explicit "Сделай чтобы все работало" authorizes the cheap
      visibility/completion guard in `specs/ci-exact-sha-status.md`: add an exact-SHA
      `scripts/ci-status` with explicit green/red/pending/unknown exits, fixture-test all result
      classes, surface it from `scripts/coord-status`, add the main badge, and require exact-SHA
      green before releasing a final task claim. Do not mutate GitHub runs or mistake another SHA's
      result for proof.
      **Pre-land apparatus correction:** the fake-gh matrix initially passed while the first real
      invocation rejected malformed `--jq` quote escaping. Track it as
      `ci-status-fixture-accepts-invalid-jq`; a real authenticated query is now an acceptance gate,
      not an optional smoke.
      **IMPLEMENTED `c43d8f523`, docs `0fe5e5f0d`, partial spec verify `2b89ba52c`:** six result
      classes plus red-but-non-aborting `coord-status` pass under the fake, and the real authenticated
      CLI returns exact-SHA `PENDING` with all four jobs. Remains open until the CI run containing
      this wiring completes and the exact final SHA is all-green.
- [x] **6a. Stop `coord-status` from reporting a live zero-token claim as stale.** Current exact
      repro: `scripts/coord-status --no-fetch` prints `maybe stale: ci-red-main` even though the
      authoritative claim declares `branch: feature/ci-red-main-final` and that exact clean
      worktree exists. `significant_tokens` removes every slug token (`ci` is too short;
      `red`/`main` are stop words), so the heuristic cannot succeed. Parse explicit `branch:` claim
      metadata and compare it exactly to live worktree branches before falling back to the legacy
      slug heuristic. Add a hermetic zero-token/live-branch regression plus a missing-branch case;
      mismatch output must show expected branch and observed branches. Track root cause and result
      in `BUGS.md`; keep this separate from GitHub run mutation or stale foreign-claim takeover.
      **DONE `8ad5f4d1e`:** explicit `branch:` metadata is compared exactly before legacy heuristics.
      The hermetic gate proves both live and missing zero-token branches with expected/observed
      diagnostics, cleans temporary Git state, and the real claim no longer appears stale.
- [x] **6b. Make `coord-status` enforce the 20-minute heartbeat rule independently of worktree
      presence.** Current exact repro at `5d932f6a4`: `scripts/coord-status --no-fetch` reports
      `no stale-looking claims` although newfront/SclJet/Swift heartbeats are hours or days old,
      because the stale loop never parses timestamps and suppresses every claim with a matching
      branch. Add strict cross-platform UTC parsing, a fixed-now test hook, and separate observable
      classifications: fresh heartbeat + live branch is live; stale/missing/invalid heartbeat is
      potentially orphaned even with a live branch; fresh heartbeat + missing branch retains the
      missing-worktree warning. Hermetic tests must print heartbeat, computed age, expected branch,
      and observed branches on mismatch. Do not release or modify any foreign claim in this slice.
      **DONE `52e1d0814`:** strict GNU/BSD UTC parsing and a fixed-now gate now classify
      fresh/live, stale/live, fresh/missing-worktree, invalid, and missing-heartbeat cases
      independently. Real output names five old claims with timestamp, exact age/reason, and branch;
      the fresh current claim is not listed. No foreign claim was mutated.

## build/CI RAM + speed — residuals from `build-ram-budget-and-speed` (2026-07-28)

Landed there: `.jvmopts` periodic GC (−1145 MB per idle sbt server, measured), `scripts/build-guard`
host-wide admission, `scripts/build-ram-report`, `kill-stale-builders --idle`, 4-way conformance
sharding in CI (37.7 → ~13 min), and gates for both. Full measurements:
[`specs/build-ram-budget.md`](specs/build-ram-budget.md).

Each item below was deliberately NOT done there — it sits in another agent's live claim, or is its
own arc. None is speculative: every one has a measured number attached.

- [ ] **`negtc-override-rows-are-a-treadmill`** — the negative-toolchain gate is now red for
      BOOKKEEPING, not for regressions, and each round costs a ~46-minute run. Every failure unwound
      on 2026-07-28 was the gate's hand-maintained data lagging a product that got BETTER:

      | run | sentinel | run-ok | strict-fail | gate said |
      |---|---|---|---|---|
      | 30375095267 | 24 | 80 | 122 | `stale override: wasm-scalascript.ssc` |
      | 30380264986 | 24 | 90 | 112 | (that row removed) |
      | 30384832575 | 21 | 93 | 109 | `stale override: wasm-http.ssc` |

      `run-ok` climbed 80 → 93 as front fixes landed (`9f6eb6e1f` for/yield layout, `3e3024991`
      colon trailing lambda), and each improvement stranded another row in
      `tests/fixtures/v21-sentinel-taxonomy/overrides.tsv`. Removing them one per CI cycle is a race
      against a corpus that is improving faster than the loop closes.

      BUGS.md `negtc-gate-self-maintaining` already made the count side self-maintaining by
      auto-classifying instead of freezing exact numbers; the override rows are the part that stayed
      manual. The same treatment applies: a row that no longer applies means a case IMPROVED, which
      is never a reason to fail a release gate. Suggested shape — WARN on a stale override and keep
      failing on an *unclassified* sentinel (the direction that actually protects the release), so
      the gate reports drift without blocking on it.

      NOT done here on purpose: that is a policy change to someone else's release gate, and the
      honest call is the owner's, not a passing agent's.
- [x] **`negtc-gate-shard-reduce`** — **DONE 2026-07-31.** `ci.yml` wiring landed: `negtc-map` is a
      4-way matrix running `--sweeps-only --shard i/4` and uploading its two partial TSVs, then
      `negtc-reduce` downloads all four, merges and runs `--reduce`. The gate left the `sbt` job.

      **The wall-clock arithmetic was NOT what this item assumed, and the correction is the more
      useful half.** "Expected ~58 -> ~15 min" below compares the gate against itself. What actually
      dominated was that the gate is a SEQUENTIAL step ahead of `Test via sbt`. Measured on run
      30597944542, the last full run that reached a verdict: gate 3236 s, `Test via sbt` 8088 s,
      whole `sbt` job 208 min — the suite's critical path. So the split buys ~154 min for that job
      (−26 % on the suite) and the negtc verdict on its own ~35-40 min path, not a 4x suite.

      Beware the 53-min `ci.yml` runs visible on 2026-07-31: those are runs that DIED in this gate
      and never reached `sbt test` at all. A faster-looking suite that is really an aborted one.

      Blocked for a day on `bc-parity-explicit-manifest-second-copy` (SPRINT) — the gate had been
      red since 06:25 that day, so the wiring could not be verified green until that was fixed.

      **MEASURED on the first dispatched run, 30649090567** — and the shape is better than the
      estimate above, because the fixed cost turned out to be ~0:

      | shard | sweep | job | rows |
      |---|---|---|---|
      | 0/4 | 986 s | 23.5 min | 54 |
      | 1/4 | 934 s | 23.1 min | 54 |
      | 2/4 | 724 s | 19.7 min | 53 |
      | 3/4 | 806 s | 19.7 min | 53 |

      214 rows merged = `examples/*.ssc` exactly, so the partition is exact and round-robin balances
      to x1.36 worst-to-best. Per job: 45 s setup + 318-368 s `installBin` + the sweep. Solving
      `S + W = 3236` (the last green unsharded step) against `S + W/4 = 806` gives **S ≈ 0**: the
      sandbox and slim-dist prologue are free and the step is almost entirely the two sweeps, so the
      split scales linearly and re-running the prologue per job costs nothing. Sum of the four
      sweeps is 3450 s against 3236 s unsharded — ~7 % overhead for 4x the parallelism.

      ⚠️ **The first run still ended red, in `negtc-reduce`, and the cause was the wiring not the
      gate**: `--report` was accepted only as argument ONE and ci.yml passed it last, so the reduce
      job died in 0 s on `usage:` after the four shards had done 23 minutes of correct work. Fixed
      in the script (any position, and an unrecognised argument is now NAMED) plus a check in
      `tests/e2e/negtc-mapreduce-gate.sh` that parses the invocations READ OUT OF ci.yml. Worth
      knowing that the first version of that check passed against the broken script — see the commit
      for the two vacuity traps it had to close.

      Original framing kept for the record:

      The gate now has the two modes the split needs: `--sweeps-only --shard i/N --native-out A
      --parity-out B` (map: only the sweeps over one slice) and `--reduce --native-in A --parity-in B`
      (skips the sweeps, runs taxonomy + freeze + assertions + metrics once on merged reports).
      `scripts/negtc-merge-reports` merges the shards with one header, a refusal on a duplicated case,
      and sorted rows so the merged bytes do not depend on which shard finished first.

      `--strict` is deliberately NOT passed in map mode: a shard's strict verdict would be about a
      slice. Strictness belongs to the merged whole.

      Proven, not asserted — `tests/e2e/negtc-mapreduce-gate.sh`: map+merge over N shards is
      byte-identical to one unsharded sweep (9 real cases), plus the merge invariants both ways.

      ⚠️ **That gate caught itself passing vacuously on its first run**: "0 rows, byte-identical",
      exit 0, because `bc-parity-sweep`'s `--only` is a shell `case` pattern whose `|`-alternation form
      selects NOTHING. It now refuses to pass when the comparison covers fewer than N rows. Remember
      it when writing the workflow — **a shard that selects nothing is green.**

      REMAINING: an N-way matrix running `--sweeps-only` + artifact upload, then one `needs:` job that
      downloads, merges and runs `--reduce`. Expected ~58 -> ~15 min.
      — DONE, and the "a shard that selects nothing is green" warning above became a per-shard row
      check in the workflow. The `~15 min` estimate is superseded by the measurement at the top of
      this entry. Reduce also asserts the four artifacts arrived: a lost download is invisible to
      the merge, and a "more than half the corpus" floor would not see it (3 of 4 shards is 75 %).
- [ ] **`ssc-fork-heap-entitlement`** — `bin/ssc` (launcher template in `build.sbt`) passes `-Xss64m`
      and **no `-Xmx`**, so every fork takes the JVM's ergonomic ¼-of-RAM default = **9,216 MB** here,
      and a contract run makes ~1,669 of them. MEASURED 2026-07-28: six live at once; one resident at
      **8,090 MB**, 22 % of the host, while the machine was swapping.

      **Do NOT just put a small `-Xmx` in the template** — that was this item's first framing and it
      is wrong. `bin/ssc` is the PRODUCT launcher; a fixed low ceiling there is a product decision
      that would OOM a legitimately large user program, and it would be verified against a corpus
      that is not representative of user workloads. The harness path is already capped: both
      `scripts/conformance` (4g) and `scripts/build-guard` (2g) cap forks through `JDK_JAVA_OPTIONS`,
      which these forks honour *precisely because* they set no `-Xmx` of their own — adding one to the
      template would BREAK that mechanism.

      What is actually worth doing, in order: (1) find out what the 8,090 MB fork was — one fork at
      8 GB among five at ~250 MB is an outlier, and if it is a runaway that is a bug, not a budget;
      (2) measure real peak RSS across the corpus (`scripts/build-ram-report --watch` alongside a
      full run) so any ceiling is chosen from data; (3) only then decide between an
      `-XX:MaxRAMPercentage` default, an opt-in `SSC_XMX`, or leaving the product default alone and
      relying on the harness caps that already exist.
- [~] **`test-fork-budget-has-no-host-wide-coordination`** — **partially measured 2026-07-30; the
      default was deliberately NOT changed.**

      `build.sbt` declares `Tags.limit(Tags.Test, 4)` × `-Xmx2g` = 8 GB per worktree. Measured what a
      forked test JVM actually uses, by diffing the JVM set during a forced `core/test` run:

      | | peak RSS |
      |---|---|
      | forked test JVMs (`core`, 6 observed) | **126-203 MB** |
      | declared per fork | 2048 MB |

      ~10x over-declared *for that project*. **Not acted on, on purpose:** a cap has to survive the
      TAIL, not the median, and `core` is among the lightest suites. The heavy ones are the
      cross-backend differential suites (`CrossBackendPropertyTest` and siblings), which spawn
      `scala-cli` children per generated program — those are what must be measured before the default
      moves. This is the same shape as `ssc-fork-heap-entitlement` (median 163 MB, tail 4,650 MB) and
      as `build-guard`'s own guessed 2g, which a single tail case proved too small.

      **Two traps found while measuring, both worth knowing before repeating it:**
      1. **sbt SKIPS up-to-date tests.** Repeated `core/test` invocations were no-ops, so the
         observation window was empty and it looked as though `Test / fork := true` did not fork.
         `show core/Test/fork` says `true`; the forks are simply absent when nothing needs running.
         Force a real run (touch a test source) before sampling.
      2. `grep ForkMain` **matches your own command line.** The pattern appears in the invoking
         shell's argv, so it reports 1-2 MB "JVMs" that are your own `zsh -c`. Build the literal at
         runtime, or diff the JVM pid set instead — which is what finally worked.

      Host-wide coordination across worktrees (the item's actual title) remains unaddressed and is the
      hard part: `Tags.limit` bounds forks within ONE sbt server, and N servers do not see each other.
- [ ] **saved-continuation-once-policy** — add an explicitly selected one-shot workflow mode
      with a linearizable cross-machine claim. The mode must be chosen before any reusable run
      (or use a distinct saved type); crash after claim is terminal `Unknown`, and the guarantee
      is at-most-one body start, never exactly-once external effects.
- [ ] **saved-continuation-version-migration** — opt-in, audited migrations between compatible
      CoreIR/control ABI, frame, codec, and plugin versions. Base behavior rejects a mismatch and
      retains/loads the exact artifact when the payload is not a fully portable capsule.
- [ ] **saved-continuation-durable-state-graph** — extend the baseline immutable/codec-safe
      captured graph with richer explicit alias-preserving codecs for selected cyclic mutable
      state. The base `DurableRef` contract remains available for explicit resolvers; neither
      mechanism infers serialization of arbitrary host objects or live resources.
- [ ] **saved-continuation-effect-delivery** — optional idempotency keys, outcome persistence,
      transactional outbox/inbox, and effect journals for applications that need stronger delivery
      protocols. These remain application/runtime protocols, not a claim that continuation resume
      itself makes external effects exactly once.

## smoke CI — the remaining gap between the suite and the job

- [ ] **smoke-ci-launcher-build-dominates-the-job** — the smoke SUITE is 250 s (CI-measured, run
      30545125102) but the JOB is 9.4 min: ~5.2 min of it is runner setup plus
      `install.sh --dev` (sbt-assembly), which the suite needs because the runner is a `.ssc`
      program executing on the v2 native lane. The push interval on `main` under several parallel
      agents is ~3-7 min, so a 9.4-min job still gets superseded while PENDING more often than it
      completes — measured right after the switch to `cancel-in-progress: false`: one success, four
      superseded. That is a large improvement on the 0-in-100 it replaced, and it is not the target.
      Candidates, cheapest first: cache the STAGED LAUNCHER (`bin/lib`) keyed on the SHAs of the
      sources that produce it, so an unchanged compiler is downloaded rather than rebuilt; split the
      suite so the ~145 s of shell gates run in a job with no build at all and only the corpus slice
      waits for the launcher; or accept the build and shorten the slice. The first keeps all the
      coverage and is the only one that does.
      Do NOT "fix" this by raising `SSC_SMOKE_BUDGET` — the budget governs the suite, and the suite
      is already inside it. The job is what is long.
