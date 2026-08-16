## next-displays-a-defaulted-kind-as-if-declared — the tool that picks everyone's work asserted a field its data lacks

<!-- status: fixed
     lane: apparatus
     area: build
     kind: bug
     reported-by: claude-code
     reported-at: 2026-08-16
     confirmed: yes
     gate: scripts/next --self-test
     fixed-in: df09493f9 -->

**`scripts/next` ranks on `kind:` with `KIND_ORDER.get(r.get("kind") or "bug", 9)` — a sensible
ranking default — and the same `or "bug"` leaked into the DISPLAY:**

```python
print(f"      … kind:{r.get('kind') or 'bug'} · lane:… · confirmed:{r.get('confirmed') or '?'}")
```

So an entry that declares nothing printed `kind:bug`, indistinguishable from one that says so.
`confirmed` in the very same line renders `?` when absent, which is what makes this an oversight
rather than a convention.

**Measured 2026-08-16: 50 of 82 open entries declare no `kind:`, and 3 of the 8 this command was
recommending were among them** — including `f-gap-tail-2026-08-15`, shown as `kind:bug` with no
`kind:` anywhere in its header.

**Fixed by showing the assumption**: `kind:bug?` when defaulted, and a header line counting the debt
— `50 declare no kind: — ranked as bug and shown as bug?, so the assumption is visible`. Ranking is
unchanged; `bug` remains a reasonable prior, and now a reader can tell a prior from a statement.

**Why this rather than backfilling the 50.** Filling `kind:` on 50 of other agents' entries is
judgement applied to prose, which is exactly how this repository acquired a 191-entry board-routing
debt from a keyword heuristic (`board-routing-debt-191-entries-sit-where-their-fix-does-not`). One
line in the tool removes the false assertion and makes the debt countable, which is the part that was
actually missing — nobody could see there was a debt.

**Both new self-test rows observed FAILING** with the display default reverted to `"bug"`
(`got 'bug', wanted 'bug?'` and `declared bug and assumed bug differ: got False`), then passing —
because a row that has only ever been seen green is not a check.

## native-front-corpus-non-standard-mode-needs-a-module-that-was-deleted

<!-- status: open
     lane: apparatus
     kind: apparatus
     area: build
     reported-by: claude-code
     reported-at: 2026-08-16
     confirmed: yes
     gate: tests/e2e/v21-portable-gates-smoke.sh -->

`scripts/native-front-corpus` has two modes. The `--standard` one reads `bin/lib/standard` and works.
**The default one is dead**, and it fails in a way that tells the reader to do something that cannot
help:

```
native-front-corpus: staged v2 jars missing (run scripts/sbtc "installBin")
```

It requires `scalascript-v2-frontend-bridge_*.jar`, and then — past that check — runs
`ssc.bridge.bridgeCli`. **`v2/frontend-bridge`, the scalameta FrontendBridge tier, was REMOVED**
when every `run --v2` / `run-js --v2` / `--bytecode` path moved to the native ssc1 front; `build.sbt`
records the removal in a comment where the module used to be. No task can rebuild it, so the advice
in the refusal is unfollowable: **`scripts/sbtc "installBin"` succeeds in 61 s and the jar is still
absent** — measured, because the message deserved to be taken at its word once.

Removing the precondition does not fix it; it only moves the failure one step later, to
`Error: Could not find or load main class ssc.bridge.bridgeCli` with `strict-fail rows: 1`. The whole
non-standard path depends on the deleted tier.

`tests/e2e/v21-portable-gates-smoke.sh` is repaired by asking for `--standard`, and now PASSES in
26 s (wired to tier 2). **This entry is the residue**: the default mode of the script is still there,
still broken, and still advertising `installBin`. Either delete the non-standard branch or make it
an alias for `--standard`; whichever, the refusal must stop naming a task that cannot help.

Found by draining an orphan: the gate that exposed this is invoked by nothing, so the dead mode had
no way to be noticed.

## sbt-test-shard-enumeration-produces-zero-suites — all four shards refuse, and the refusal could not say why

<!-- status: fixed
     fixed-in: a79eaed07
     lane: apparatus
     area: build
     kind: bug
     reported-by: claude-code
     reported-at: 2026-08-15
     confirmed: yes
     gate: tests/e2e/sbt-test-shard-gate.sh -->

**Every `sbt test shard i/4` job in the dispatch tier fails identically:**

```text
sbt-test-shard: enumeration produced 0 suite(s) — refusing. sbt output unparsed or
  the test sources did not compile; running a slice of nothing would report green.
```

The refusal itself is CORRECT and is the gate working — `testOnly` with no arguments runs
everything, so a shard that selected nothing would look like a fast green. What is wrong is that
nobody can act on it.

**MEASURED, 2026-08-15 dispatch:** all four shards, ~7 minutes each before refusing, on a run whose
`sbt — compile and release gates` job SUCCEEDED. So the main sources compile; the two causes the
message names are not equally likely, and it cannot tell them apart.

**Fixed the half that is answerable: `enumerate()` was discarding stderr** (`2>/dev/null`), so the
evidence for choosing between "unparsed" and "did not compile" was thrown away at the moment it was
produced. It is now captured and the refusal prints its last 20 lines, or says explicitly that sbt
wrote nothing — which distinguishes "the build failed" from "sbt ran and this parser did not match
its output". The `--from` path says so too, rather than claiming sbt was silent when sbt was never
run.

**NOT diagnosed, and deliberately not guessed at.** Two candidates remain and the fix above is what
will separate them on the next dispatch:

1. **The parser no longer matches.** The awk wants `[info] <proj> / Test / definedTestNames`, and
   sbt now warns *"sbt 0.13 shell syntax is deprecated; use slash syntax instead"* for this exact
   query. If the header line moved, the enumeration is empty with a perfectly healthy build.
2. **The enumeration does not finish.** Locally `scripts/sbtc "show test:definedTestNames"` was still
   COMPILING after 9 minutes with zero errors — it must build every test source across ~250 projects
   first. On a cold runner 7 minutes may simply not be enough.

**A measurement I did NOT make, stated so it is not mistaken for one:** my local run was killed by my
own timeout before any `definedTestNames` line appeared, so "0 awk matches" over that log says
nothing about whether the pattern is broken. It never reached the subject.

**Acceptance test:** the next dispatch prints either sbt's stderr or the "sbt wrote nothing" line,
which names the cause. Then fix that cause and the four shards report a verdict instead of refusing.

### CLOSED 2026-08-15 — `a79eaed07`. Neither candidate was right: THE QUERY was dead, not the parser

`show test:definedTestNames` — the sbt 0.13 colon form — is ACCEPTED by sbt 1.10.7, warned about,
COMPILED for, reported `[success]` on, and prints **no value at all**. Measured on this build, same
warm server, the same key both ways:

| query | headers | `Vector(` lines | wall | verdict |
|---|---|---|---|---|
| `show test:definedTestNames` | **0** | **0** | 259 s | `[success]` |
| `show Test/definedTestNames` | **273** | 274 | 109 s | `[success]` |

A silent no-op that still pays for a full test-source compile — which is exactly why each shard
burned ~7 minutes before refusing, and why candidate 2 ("the enumeration does not finish") looked
plausible. It finished. It said nothing.

**END TO END ON REAL OUTPUT.** Feeding the actual sbt listing to the UNCHANGED parser via `--from`
enumerates **1147 suites across 273 projects**, and the four shards partition them exactly:
286 + 287 + 287 + 287 = 1147. The parser never needed touching.

**Both candidates this entry named are refuted, and so is a third I invented on the way.**
Candidate 1 said the parser no longer matches: it matches, and `show Test/sources` shows the same
`header` + `Vector(…)` shape independently. Candidate 2 said the enumeration does not finish: it
finishes in 109 s warm. My own detour was the project-name regex `[A-Za-z0-9_]+` — all **330**
project ids in `build.sbt` match it, because the hyphens live in `name :=` and `show` prints the id.

**What misled me, recorded because it would mislead the next reader too:** a SINGLE-project query,
`show authPlugin/Test/definedTestNames`, prints `[info] * <name>` — no header, no `Vector`. That
looks exactly like "the format changed under the parser". The AGGREGATED form does not; sbt renders
the two differently, and only the aggregated one is what this script asks for.

**THE GATE WAS GREEN THROUGHOUT AND COULD NOT HAVE BEEN OTHERWISE.** Every check in it runs on a
synthetic listing through `--from`, so it never sees which question the script asks sbt. It now
freezes the spelling — the part it can check without paying four minutes for a build — and that
check is proven in all three states: green on the fixed script, RED when the colon form is planted
in code, and not fooled by the script's own comment quoting the dead form. The first version of it
WAS fooled, because it did not strip comments before grepping.

# Build, CI and coordination tooling — bugs

Scope: defects whose FIX goes in `scripts/`. Layout and routing rules:
`specs/work-tracking-layout.md`. Entry format (the header is parsed, the prose is not):
`specs/bugs-index.md`. Query with `scripts/bugs-report --module scripts`, never by
grepping for status.

Newest first.

## build-slot-is-not-reentrant-so-one-install-holds-every-slot — and its own header recommended the nesting
<!-- status: fixed
     lane: apparatus
     area: build
     kind: bug
     gate: scripts/build-slot --self-test
     reported-by: claude-code
     reported-at: 2026-08-15
     confirmed: yes
     fixed-in: 72ae0ba313465691b920e45fa175ffeba6e40a23 -->

**Found 2026-08-15 by reading my own build log**, while making a real toolchain for the BigDecimal
contract decision. `scripts/build-slot ./install.sh --dev` printed, about itself:

```text
build-slot: slot 2/2 acquired after 0s
Toolchain cache MISS (0e05707d…) — building.
build-slot: all 2 slots busy — waiting (load 94.31)
build-slot: slot 1/2 acquired after 10s
```

Reproduced directly, with nothing else running:

```text
$ scripts/build-slot scripts/build-slot bash -c 'ls $SEM_DIR'
build-slot: slot 1/2 acquired after 0s
build-slot: all 2 slots busy — waiting (load 82.04)
build-slot: slot 2/2 acquired after 76s
slot.1 slot.2                    ← one logical build, both slots
```

**The documented usage was the cause.** `build-slot`'s header listed
`scripts/build-slot ./install.sh --dev` as THE way to run an install, and `install.sh` later grew a
slot of its own around `sbt cli/installBin`. Neither change is wrong alone; together the recommended
command consumes a `MAX=2` semaphore entirely and every other agent waits on one install.

**The 76 s matters more than the double-count.** The inner call did not simply take a spare slot — it
BLOCKED waiting for one it could never need. With the other slot held by a real build it would have
waited `SSC_BUILD_WAIT`, ninety minutes, before the run-anyway escape. A semaphore that blocks on
itself is worse than no semaphore, because the guard's own cost is invisible in the thing it guards.

**Fix:** a held slot marks itself in the environment; a nested acquire passes through and says so.
Scoped by construction — only the holder's process tree inherits the marker. The give-up path marks
it too, so one exhausted wait cannot become several. The header's usage line now says `install.sh`
takes its own slot and must not be wrapped.

**`--self-test`, and its first case is the one that passes BEFORE the fix.** A passthrough that
fired unconditionally would be indistinguishable from inside a nested call — no wait, no second
slot, all green, and the semaphore silently off for everyone. So the test asserts an ordinary call
still TAKES a slot, and that case passes against the pre-fix body; the nesting cases fail there:

```text
ok   an ordinary call takes a slot
FAIL a nested call held 2 slots — one logical build must hold one
FAIL the nested call passed through without saying so
ok   every slot released
```

It runs against its own `SSC_BUILD_SEMDIR`: this host is shared and a self-test reaching into the
real semaphore could reap or occupy a slot a live build is using.

**NOT WIRED YET, said out loud rather than left to be found.** `scripts/smoke-ci.ssc` and
`.github/workflows/ci.yml` are both held by live claims. Two of my gates now wait on the same line;
whoever holds `smoke-ci.ssc` next can add both:

```scala
Check("scripts", "build-slot-selftest", "scripts/build-slot", List("--self-test"), 60000),
Check("scripts", "coord-update-rolls-back", "tests/coord/coord-update-rolls-back.sh", List(), 60000),
```

## install-sh-rebuilds-a-digest-another-agent-just-cached — and publishLocal ran even when nothing was built
<!-- status: fixed
     lane: apparatus
     area: build
     kind: perf
     gate: none — the plant is in the commit message; see "verified in both directions"
     fixed-in: 5ff55fa1aef002dc693a4297085d06f63534c627 -->

**Found 2026-08-14 by being asked why an install in a worktree is expensive**, and the answer was two
independent things, both in `install.sh`.

**1. The toolchain cache was consulted once, BEFORE queueing.** Order of operations was: compute
digest (line ~129) → decide HIT/MISS (~142) → take a `scripts/build-slot` slot (~177). A slot wait
is minutes. Agents rebase onto the same new `main`, compute the same new digest and miss together,
so the second one waited ~8 minutes and then rebuilt what the first had published to the shared
cache *while it waited*. Two slots bounded CONCURRENCY without stopping both from building the SAME
SOURCES — which is exactly what `build-slot`'s own header names as the problem it was written for
("N full Scala builds of the same sources were").

Fixed by re-checking the cache after the slot is acquired. The step runs as an exported function so
the restore is the same code as the hit path, and it leaves a marker file: the `.build-stamp`
witness asserts a build RAN, and a restore is not a build, so without the marker the run dies with
"sbt reported success but cli/installBin did not run".

**2. `publishLocal` for the sbt interop plugin ran unconditionally, and outside the cache branch.**
So an install that HIT the cache — and therefore built nothing — still started a whole second sbt
JVM for a separate build. It was also the only build step NOT holding a slot: the one thing
guaranteed to run was the one thing not queued. Its output lands in `~/.ivy2/local`, which is
host-wide, so 92 worktrees on this machine were publishing identical bytes to a single shared path.

Now skipped when the artefact for the version this build produces is present and `find -newer` sees
no change under the plugin, with `SSC_SKIP_SBT_PLUGIN=1` as an explicit opt-out, and routed through
the slot when it does run. **Deliberately not deleted:** `ssc new` scaffolds five of six templates
with `addSbtPlugin(... sbt-scalascript-interop ...)`, and without the artefact a generated project
cannot load its build (`tests/BUGS.md` → `scaffolded-projects-cannot-load-their-build`). Only one
gate genuinely needs it — `scaffold-loads-its-build.sh`, which is CI-only — and the one plugin-related
gate in the per-push suite, `emitted-coordinate-is-published.sh`, is a pure text comparison of
version strings that touches no artefact at all.

**Also measured, and it corrected an assumption rather than confirming one:** the restore `cp -R` was
suspected of being slow. It is not — 176 MB copies in 0.37 s here, and `cp -Rc` (APFS clone) in
0.06 s. The clone went in for DISK (92 private copies of the same bytes), not speed, and it must
stay a COPY: `bin/lib/*/native-front/tower/` is read at runtime and editing a staged copy is normal
practice here, so a shared symlink would let one agent's experiment change what every other agent
runs.

**`scripts/build-slot` now prints the wait on every acquisition, `after 0s` included.** The cost of
this queue had only ever been argued from two constants in that file; nothing recorded what an agent
actually waits. Computed at acquisition rather than at the foot of the poll loop — read from there it
is one interval stale, and the first version printed 5 s for a 15 s wait.

**Verified in both directions for every branch**, since a cache that never builds is a worse defect
than one that builds twice:

| plant | expected | result |
|---|---|---|
| cache filled by a "sibling" while install waits for a slot | restore, no build | `another agent published this digest while we waited`, rc 0 |
| real miss, cache left empty | sbt actually runs | `[success] 44 s` (warm), entry published, rc 0 |
| cache hit | restore, skip build | as before |
| plugin source touched | publish runs | `Publishing…`, under a slot |
| `SSC_SKIP_SBT_PLUGIN=1` | skip | skipped, and it prints the scaffold consequence |

## coord-release-leaves-the-shared-checkout-dirty-when-it-dies-between-staging-and-commit

<!-- status: fixed
     lane: apparatus
     area: build
     kind: bug
     reported-by: claude-code
     reported-at: 2026-08-14
     confirmed: yes
     gate: tests/coord/coord-release-refuses-unpushed-work.sh
     fixed-in: f6e53a127399fb965a116b92b5292c7f49de2327 -->

> **FIXED 2026-08-14, exactly as this entry's acceptance test specified.** The staging window
> (`git rm` → `git commit`) now runs under an `EXIT HUP INT TERM` trap that restores the claim file
> and the ledger unless the commit was reached, and says so instead of leaving the tree to be
> discovered by the next agent. Targeted restore of those two paths only — never `git reset`, which
> here means "undo whatever the last agent did".
>
> **The negative control prints this entry's own two lines back.** Against the pre-fix script the new
> lab case leaves
>
> ```text
> M  .work/active/LEDGER.tsv
> D  .work/active/staging-trap.claim
> ```
>
> with the claim and its row gone from the tree — the blocked-next-agent state. Against the fixed
> script `git status --porcelain` is empty and both survive.
>
> The commit is forced to fail with a `pre-commit` hook, since the script passes no `--no-verify`:
> that lands the failure in the window rather than anywhere convenient. **The cause of the original
> run's death is still unknown and is not guessed at** — the case reproduces the WINDOW, which is
> what the fix closes.
>
> The case carries a control for itself: with the hook removed the SAME claim releases normally, so
> it cannot be satisfied by a script that refuses everything.

**Observed 2026-08-14, releasing a claim.** The first invocation printed **nothing at all** and did
not release. The second, identical, refused:

```text
coord-release: working tree is dirty
```

`git status` in the shared checkout showed the first run's leftovers, staged:

```text
M  .work/active/LEDGER.tsv
D  .work/active/pre-push-message-backticks.claim
```

**That is a release half-applied to a tree five other agents share.** `scripts/coord-release`
`git rm`s the claim at line 148 and `git add`s the rewritten ledger at line 171, then does other work
— deriving the landed shas from the branch — before committing at line 221. Anything that fails in
that window leaves the staging behind. Line 96 of the same script, and the equivalent in
`coord-claim`, then refuse to run at all while the tree is dirty: **one agent's interrupted release
blocks every agent's next claim and release**, with a message that names neither the owner nor the
files.

**Recovered by restoring exactly those two paths from HEAD** — `git restore --staged --worktree` —
after checking `HEAD == origin/main`, so the restore was lossless for everyone. A third invocation
then succeeded with exit 0. **Not `git reset`**, which in this checkout means "undo whatever the last
agent did" (see `shared-main-is-one-working-tree-for-every-agent`).

**WHAT KILLED THE FIRST RUN IS NOT KNOWN, and is stated as unknown rather than guessed.** Its exit
code was not captured, it emitted nothing on either stream, and the identical command succeeded
afterwards, so it did not reproduce. The window between line 148 and line 221 runs several `git`
commands against a shared index other agents are writing to, which is a plausible source and is not
evidence. What IS established is the damage and its blast radius, which do not depend on the cause.

**This is the same shape the script already guards against one step later, and says so in its own
comment.** `pre_release_sha` was added so that a refused PUSH does not leave the commit parked on
shared main — "a release commit parked here is refused for a stranger, and it blocks EVERY agent's
next claim until somebody finds and removes it." The staging window is that lesson applied one line
too late.

**Acceptance test.** Stage the release inside a `trap … EXIT` that restores the claim file and the
ledger unless the commit succeeded, and assert it: run `coord-release` with the commit forced to
fail, then require `git status --porcelain` to be empty and the claim file to be present. The
negative control is the pre-fix script, which must leave both leftovers — otherwise the check cannot
fail. Same remedy as `clean-up-in-a-trap-because-the-leftover-blocks-the-next-agent`, and the third
tool in this repo to need it.

**Where the test goes, named 2026-08-14 so this entry is claimable.**
`tests/coord/coord-release-refuses-unpushed-work.sh` — the lab that already forces `coord-release`
down its failure branches, wired into `scripts/smoke-ci` as `coord-release-refuses-unpushed`. The
case to add is a new one there, not a new file: force the COMMIT to fail rather than the push, then
require `git status --porcelain` empty and the claim file present. It carries its own anti-constant
cases already, which is the shape the negative control above needs.

**Done when** that case passes against the fixed script and FAILS against the pre-fix one.

## build-ram-guard-selftest-measures-the-machine-not-itself — an intermittent red in the pre-push suite

<!-- status: fixed
     lane: apparatus
     area: build
     kind: bug
     reported-by: claude-code
     reported-at: 2026-08-14
     confirmed: yes
     gate: tests/e2e/build-ram-guard-gate.sh
     fixed-in: 9e338484a -->

**`build-ram-guard --self-test` asserted "killed nothing" by comparing a MACHINE-WIDE build-process
count before and after** — a global quantity, used to test a local property:

```sh
before="$(build_pids | wc -l)"
DRY=1 tier1_orphans >/dev/null; DRY=1 tier3_heaviest >/dev/null
after="$(build_pids | wc -l)"
[ "$after" -ge "$before" ] || bad "self-test KILLED something: before=$before after=$after"
```

**Both tiers run under `DRY=1`, so they cannot kill by construction** — `kill_pid` takes the
would-kill branch and returns. The count was therefore never measuring this script at all: it was
measuring whether any *other* agent's JVM happened to exit in those few hundred milliseconds. On a
box where several agents build, one does.

**Measured before touching it**, at load 45: **1 failure in 20** standalone runs, the failing line
being `✗ self-test KILLED something: before=10 after=9` — a count that dropped by exactly one — and
**2 reds across 11 smoke runs** the same day. Standalone it also passed 6/6 in one block, which is
why "it only fails inside smoke" was the first and wrong hypothesis; the 20-run loop is what
separated a suite property from a host property.

**The correct assertion already existed ONE LEVEL UP.** `tests/e2e/build-ram-guard-gate.sh` checks
the pids the dry run NAMED are still alive, and prints a NOTE rather than a pass when nothing was
named. The self-test's census was a flakier duplicate of a check done properly a few lines away, so
the fix is to do the same thing here, over the log lines this run appended.

**Verified by an INTERLEAVED A/B, not two blocks** — old and new alternating so both see the same
host: **old 3 failures / 20, new 0 / 20**. Run as separate blocks the new version scored 0/30, but
the load had fallen from 45 to 23 by then and that number proves nothing on its own.

**And the new assertion was shown able to FAIL on the real script**, not on a fixture: injecting a
`would-kill pid=<already-reaped pid>` line makes it report
`✗ self-test KILLED a process it only claimed it WOULD kill: 98666` and exit 1. Without that the
green would be worthless — on this host the check is often vacuous, and it now says so out loud
(`note: the dry tiers named no would-kill target`) instead of passing silently.

**Why it was fixed rather than filed:** it was the only red in an otherwise 96/97 smoke, and an
intermittent red in the PRE-PUSH suite is how a suite stops being read — the same mechanism that
kept `lint-markdown` red for seven commits, arriving from the other direction.

## editing-a-coordination-script-forces-a-compiler-rebuild — `scripts/` is a digest input wholesale

<!-- status: fixed
     lane: apparatus
     area: build
     kind: perf
     gate: tests/e2e/launcher-digest-gate.sh
     fixed-in: e67b75a146bf777290aa5597dc249ee51f03e1c4 -->

> **FIXED 2026-08-15, and step 1 of this entry's own plan is what shaped it.** "Establish which files
> the launcher actually derives from — from `install.sh` and the `installBin` templates, not from a
> guess." Done: `build.sbt:2039` writes `$_SSC_ROOT/scripts/launcher-input-digest` INTO the generated
> launcher, which runs it at startup for the staleness check, and the build runs it again to stamp
> the digest. Of everything under `scripts/`, the build definition and the launcher templates name
> EXACTLY that one file — so the obvious directory exclusion would have excluded the only script that
> genuinely reaches the launcher.
>
>     scripts/ inputs                 66 -> 41
>     scripts/coord-release           input -> excluded
>     scripts/smoke-ci.ssc            input -> excluded
>     scripts/launcher-input-digest   input -> INPUT
>
> **On step 2's wording, which cannot be followed literally.** It asks for "an allowlist of what CAN
> affect it, never a denylist" AND for "a new script added tomorrow must default to affects the
> launcher". Those are opposite: an allowlist makes a new file default to EXCLUDED, which is the
> unsafe direction. The goal is the one that matters, so this is a per-file DENYLIST — the default
> stays INCLUDED — plus a check that refuses to emit a digest if the build ever names an excluded
> file. A script wrongly included costs a rebuild; one wrongly excluded hides a stale toolchain, and
> this repo has paid for that twice.
>
> **Step 4, counted rather than claimed:** of the last 60 commits on `origin/main`, exactly ONE
> touched `scripts/` and nothing else, and its file was `scripts/smoke-ci.ssc`. Smaller than "paid
> twice in one session" suggests — that number belongs to sessions editing coordination tooling, not
> to main's traffic. Both files now have gate rows.
>
> **Step 3's A/B, and the first attempt of it measured NOTHING.** Running the pre-fix tool from a
> scratch directory died with `fatal: not a git repository` and printed nothing; two empty strings
> compare equal, so it reported "unchanged" for both tools and nearly had me conclude this entry's
> premise was wrong. From a copy placed inside the repo:
>
>     pre-fix  coord-release edited: MOVED  bd4641951 -> 3c05d6b2f
>     fixed    coord-release edited: UNCHANGED
>     fixed    v1/ source edited:    MOVED       (the true positive still fires)
>     fixed    digest tool edited:   MOVED       (still an input)

**Found 2026-08-13**, paid twice in one session. `scripts/launcher-input-digest` includes
`scripts/` in the launcher input set — 62 files — and that set includes things the launcher does
not contain and never loads:

```
scripts/coord-release   scripts/coord-claim   scripts/coord-status   scripts/coord-ledger
scripts/board           scripts/ci-status     scripts/smoke-ci       scripts/smoke-ci.ssc
```

So editing `scripts/coord-release` — a bash script that talks to `git` — makes `scripts/smoke-ci`
refuse to run ("the launcher was built from different sources than this tree"), and the only way to
a verdict is `./install.sh --dev`: a full sbt build. Adding six `Check(...)` rows to
`scripts/smoke-ci.ssc` costs the same. **Measured today: ~10 minutes of rebuild, twice, for edits to
two shell scripts and one suite declaration**, none of which is compiled into the launcher. Every
agent who pulls pays it again, and it busts the content-addressed toolchain cache.

**The refusal itself is right and must stay** — a verdict from a stale toolchain is a verdict about
the wrong code, and that guard has caught real staleness in this session alone. The question is only
which files can make a launcher stale.

**And the exclusions are already finer than the printed summary suggests, which is the first thing
to measure rather than assume.** The header prints `excluded: … + root *.md`, and I nearly filed
"editing `scripts/BUGS.md` also forces a rebuild" on the strength of that line. It does not: **0 of
the 62 `scripts/` inputs are `*.md`**, and all three markdown files there — `BUGS.md`, `SPRINT.md`,
`BACKLOG.md` — are excluded at depth. Verified by editing `scripts/BUGS.md` and watching
`smoke-ci --list` stay green (`rc=0`) rather than by reading the summary. So the work below is a
narrowing of what remains, not a first pass, and the summary line wants correcting too.

**Why this is NOT a two-line exclusion, and why it is filed instead of fixed.** A cache key is the
most dangerous thing in this repo to narrow casually: BUGS
`trace-a-cache-key-before-building-on-it` records a directory exclusion that hid the DEFAULT FRONT,
and `cache-a-file-not-a-directory-path` records a key that served the wrong state's classes. Some
of `scripts/` may genuinely reach the launcher. So the work is:

1. **Establish which files the launcher actually derives from** — from `install.sh` and the
   `installBin` templates in `build.sbt`, not from a guess.
2. Exclude the rest **by an allowlist of what CAN affect it**, never a denylist of what cannot: a
   new script added tomorrow must default to "affects the launcher" and be proven not to.
3. **A/B the guard before and after on a real staleness case**, e.g. a `v1/` edit, and require the
   refusal to still fire. An exclusion that also silences a true positive is the failure mode above.
4. Count the saving honestly: how many pushes in a week touch only excluded files.

**Where the test goes, named 2026-08-14 so this entry is claimable.**
`tests/e2e/launcher-digest-gate.sh` already asserts both directions of exactly this property — *"a
change under an INCLUDED path MUST change the digest"* and *"a change under an EXCLUDED path MUST
NOT"* — in a throwaway worktree, and it is wired into `ci.yml`. Every step above lands as rows
there: the new exclusions as EXCLUDED rows, and step 3's A/B as the INCLUDED row that must still
fire. No new gate file, and the guard that matters keeps its existing coverage.

**Done when** editing `scripts/coord-release` leaves the digest unchanged, a `v1/` edit still moves
it, and both are rows in that gate.

## hand-made-claim-updates-have-no-tool-and-so-no-rollback — the landmine class that is left

<!-- status: fixed
     lane: apparatus
     area: other
     kind: bug
     gate: tests/coord/coord-update-rolls-back.sh
     fixed-in: 891a62044747c5b7ad66120befe5df789b1111ef -->

> **FIXED 2026-08-15. `scripts/coord-update` exists and all four numbered requirements are cases in
> the gate this entry named.** Requirement 3 — refuse a widening onto another live claim's scope —
> is satisfied by NOT reimplementing it: the pre-push guard already refuses that, with a reader that
> handles the `repo:`/`mod:`/`file:` levels, the claim-vs-ledger union and the `verify-*` exemption.
> The push is the check; the rollback is what makes that refusal free. A second copy of the reader
> would be the very thing `claim-ledger-claimfile-scope-drift` is about.
>
> **The control is sharper than the requirement, and the difference is worth keeping.** Against a
> copy with both halves of the recovery removed, case 4 reports
>
> ```text
> FAIL  HEAD is back where it was — no parked commit
> PASS  the checkout is left CLEAN for every other agent      ← still passes
> ```
>
> `git status --porcelain` is EMPTY against the broken tool, because the commit consumed the index.
> The landmine this entry describes is a COMMIT, not a dirty tree, so a case that watched only the
> working tree would have let it through. That is why the case asserts HEAD.
>
> **Two defects the lab found in the tool that reading it would not have:** the no-op must be decided
> on the CLAIM before the ledger is regenerated (`coord-ledger --write` bumps `# generation:` every
> run, so the first version committed a bare generation bump — a counter whose whole purpose is to
> make two concurrent CLAIMS collide); and an empty staged set is the no-op, not a violation, which
> the "staged more than the claim and its ledger row" guard was refusing with an empty list under it.
>
> **NOT WIRED YET.** `tests/coord/` is run only from `scripts/smoke-ci.ssc`, held by the live claim
> `f-cons2-no-arm`. One line, beside its siblings, for whoever holds that file next:
>
> ```scala
> Check("scripts", "coord-update-rolls-back", "tests/coord/coord-update-rolls-back.sh", List(), 60000),
> ```
>
> Said here rather than left to be discovered, because an unwired gate is `orphaned-e2e-gates-52`.

**Found 2026-08-13** while fixing the sibling defect in `scripts/coord-release`, which now rolls its
commit back when the push is refused (`coord-claim` has done so since 2026-08-07). That leaves
exactly one way to produce the landmine BUGS `shared-main-is-one-working-tree-for-every-agent`
describes, and it is the one every agent uses several times a day:

```bash
# a heartbeat, a scope widening, a `next:` update — all of it, today:
git add .work/active/<slug>.claim .work/active/LEDGER.tsv
git commit -m "claim-update: …"
git push origin main          # refused? the commit stays, and now nobody can claim
```

**There is no `coord-update`.** `scripts/coord-claim` refuses a slug that already exists (it prints
the current claim), so widening a claim, bumping a heartbeat or rewriting `scope:`/`next:` is
hand-written editing plus a hand-made commit — with no rollback, and with `scripts/coord-ledger
--write` to remember separately. **I did this five times in one session** while fixing the other two
defects; each one was a coin flip on whether the push would be refused for somebody else's parked
commit.

**Why it matters more than the tidiness:** the shared checkout pushes ALL of local `main`, and
`.githooks/pre-push` validates every claim in `remote_tip..local_tip`. So a parked claim-update is
refused for a stranger, and it blocks EVERY agent's next claim until somebody finds and removes it.

**Acceptance test, so this is claimable rather than a note.** A `scripts/coord-update <slug>
[--heartbeat] [--status …] [--next …] [--paths …] [--items …] [--scope-file …]` that:

1. edits the claim, regenerates the ledger row (`coord-ledger --write`) and commits — one commit,
   `.work/` only, so `.githooks/pre-commit` stays satisfied;
2. on a refused push **rolls back to the pre-update sha and restores both files**, exactly as
   `coord-release` now does — the lab in `tests/coord/coord-release-refuses-unpushed-work.sh` cases
   6 and 7 is the shape to copy, including the anti-constant case;
3. refuses to widen `paths:` onto a scope another live claim holds, reusing the pre-push guard's
   own reader rather than a second copy of that logic;
4. is asserted by a case that FAILS against a version without the rollback.

**Not built with the release fix on purpose:** that claim was one script's push branch, and a new
command is new surface with its own gate. Filed with its acceptance test so the next agent can take
it without re-deriving any of this.

**Gate named 2026-08-14: `tests/coord/coord-update-rolls-back.sh`, which does not exist yet** — the
four numbered requirements above are its cases, and case 4 is the anti-constant one. It belongs
beside its siblings in `tests/coord/`, and the file to copy is
`coord-release-refuses-unpushed-work.sh` cases 6 and 7.

**Done when** `scripts/coord-update` exists and that gate passes, with case 4 failing against a
version whose rollback is removed.

## coord-status-activity-lookup-reads-the-callers-cwd — a live claim reads as stale depending on where you stood

<!-- status: fixed
     lane: apparatus
     area: other
     kind: bug
     gate: tests/e2e/ci-status-guard.sh
     fixed-in: 7f6c81efb -->

**Found 2026-08-13** while moving `tests/e2e/ci-status-guard.sh`'s fixture out of the shared
repository. `claim_activity_epoch` held **the only three `git log` calls in `scripts/coord-status`
without `-C "$ROOT"`**, so the commit-activity rule — the one that decides a claim is alive because
its branch has recent commits — resolved against the **caller's working directory** rather than the
repository the script belongs to. Each call is `2>/dev/null || true`, so looking at the wrong repo
(or none) returns "no activity" **silently**, and no activity reads as stale. The last call also
hardcoded `origin/main` where the rest of the file honours `$REMOTE_REF`, the seam every
deterministic test injects through.

**Measured on the live repo, same script, same claims, same instant:**

| invoked from | claims reported stale |
|---|---|
| the repository | 4 |
| `/tmp` | **5** |

**What that costs.** A claim reported stale is one the triage table says may be released and
reclaimed. So `cd` somewhere else, run the status command, and the tool offers you a live agent's
work — with no error, no warning, and a plausible-looking report.

**Why it survived.** A linked worktree shares `refs/heads/*` with the main checkout, and every
routine caller runs from inside the repo, so the accident held. It only became visible when a
caller appeared whose `$ROOT` was NOT the caller's CWD: a `coord-status` running inside a throwaway
clone, where the branch under test exists only in the clone.

**Fixed** by `-C "$ROOT"` on all three lookups and `$REMOTE_REF` in place of `origin/main`.

**The gate is `tests/e2e/ci-status-guard.sh`, and it was chosen because it can fail.** Its
commit-activity case now depends on a branch that exists only inside the clone, so re-introducing a
bare `git log` makes it red — observed, not assumed: that is exactly how this defect was found.
**`tests/coord/claim-activity-overrides-heartbeat.sh` is blind to it** and stayed green throughout,
because it runs from the repository, where the bug is invisible. Worth a CWD case of its own when
someone is next in that file.

## stale-build-refusal-reads-as-a-ten-minute-rebuild — so the rational move became measuring old code

<!-- status: fixed
     lane: apparatus
     area: build
     kind: bug
     gate: none
     fixed-in: 4c5c01cc6 -->

The launcher's staleness refusal ended at `Rebuild: (cd … && ./install.sh --dev). Silence:
SSC_NO_BUILD_CHECK=1`. Every reader prices that rebuild at ten minutes, so the rational move is the
`Silence:` — which measures **the old code**, the exact outcome the refusal exists to prevent. I made
that trade myself twice today rather than pay for a build to run a six-line repro.

It is usually not ten minutes. `install.sh` restores from a content-addressed toolchain cache, and
measured 2026-08-11 the shared checkout's own digest was **already in it** — 85 entries, 15 GB. The
refusal simply never said so.

**Fixed by asking the cache instead of guessing.** `_SSC_NOW_DIGEST` is already computed two lines
above for the staleness comparison, so one `-d` test answers it exactly. Both branches verified from
the built launcher:

```
… ./install.sh --dev) — the toolchain cache ALREADY HAS this tree, so that is a copy — seconds, not a build.
… ./install.sh --dev) — this tree is not in the toolchain cache yet, so that one is a full build.
```

The `Silence:` line now also says what silencing costs, rather than offering it as a peer option.

**FOUND WHILE TESTING, NOT FIXED — the refusal cannot see an uncommitted edit.** Verifying the hint
needed the message to fire, and appending to `v2/src/Runtime.scala` did not fire it; only moving
`HEAD` did. Reading the template: staleness is set by comparing the built commit with `HEAD`, and the
digest is used only to CLEAR it, never to set it. So **an agent who edits a compiler source and
measures without rebuilding is told nothing** — which is `rebuild-before-measuring` all over again,
and the digest that would catch it exactly is already in hand two lines earlier.

Not changed here because it would warn on every dirty compiler tree, which is the normal working
state of every agent, and turning that on for everyone is a decision rather than a drive-by. The
one-line shape is: also set `_SSC_STALE=1` when the digests differ, not only clear it when they
match.

## release-claim-messages-name-no-landed-sha — the record does not say what a claim landed

<!-- status: fixed
     lane: apparatus
     area: other
     kind: bug
     gate: tests/coord/coord-release-evidence-level.sh
     fixed-in: 38aeda378 -->

**Measured 2026-08-11 over 45 days: 468 of 1107 `release-claim:` messages — 43 % — name no commit at
all.** So "what did this claim actually land?" is not answerable from the record, and every later
question that needs commit-to-claim attribution starts by guessing. It cost real work the same day:
an analysis of claim overlap recovered 1054 shas from the release notes that *did* carry them and
still had to drop **1103 of 1246** candidate commit pairs for want of attribution.

**Fixed by deriving the shas instead of asking the author to type them.** `coord-release` already
knows the claim's `branch:` — it refuses to release with unpushed work on it — so the note now
carries the commits on that branch that are newer than where `origin/main` stood when the claim
STARTED, restricted to the claim's own declared paths.

**The path filter is not decoration.** A rebase pulls siblings' commits onto the branch, so the raw
range is not this claim's work. Measured on this very claim: 2 commits in range, 1 after filtering —
it dropped a sibling's commit the rebase had brought along. And since a `file:` scope became a
co-tenancy, a co-tenant's commit can legitimately land in the range, which is why the line reads
"commits on \<branch\> touching this claim's scope" rather than "by me".

**A defect in my own first version, caught by the lab and not by review:** the derivation read the
claim file where the message is composed — which is *after* the release has removed it. It died with
`sed: .work/active/<slug>.claim: No such file or directory`, and it would have done so on a live
release, after the work was already pushed. The fields are now read beside `branch:`, before
anything is removed.

**And a vacuous assertion, caught by watching it pass at the wrong moment.** "the out-of-scope sha is
absent" is TRUE of an empty note, and it passed on exactly the run where the derivation had crashed.
The two halves are now one check: the note must name the in-scope sha AND not the other, with a
distinct verdict for "names nothing at all".

A/B'd: dropping the path filter fails the check, disabling the derivation fails two.

## file-scope-refusal-blocks-work-that-would-not-conflict — a lock over 100% to prevent 30%

<!-- status: fixed
     lane: apparatus
     area: other
     kind: bug
     gate: tests/coord/claim-scope-hierarchy.sh
     fixed-in: ae64f4aad -->

A `file:` scope was an EXCLUSIVE lock: two claims naming the same path refused each other. Since
2026-08-11, on the project owner's decision, it is a **co-tenancy** — the push is admitted and the
hook names the other claim.

**The measurement that decided it.** Over 30 days, commit pairs from DIFFERENT claims touching the
same file within six hours: **143 pairs, 43 with overlapping line ranges — 30 %.** So the lock
refused ten rival edits to prevent a resolvable conflict in three, and bought nothing in the other
seven. Attribution came from the `Landed <sha>` references in `release-claim:` messages (1054 shas);
a first pass said 39 % because it counted consecutive commits, most of which are one agent iterating
on its own work — not concurrency.

And the direction of the risk is not what a lock protects against. A textual conflict is the
**visible** failure. The silent one is a clean merge that drops the other's work, which is what
happens on the files that never had a lock: the boards took 392 commits in 30 days and produced 13
repair commits for lost entries, every one a merge git was happy with. Locking files does not
address that; anchoring edits on structure does.

**What is NOT relaxed, and each for its own reason:**

| still refused | why |
| --- | --- |
| `items:` overlap | two agents on the same WORK — the failure the mutex exists for, and no merge fixes it |
| `mod:` / `repo:` overlap | an edit lock over a subtree (P-2.2); that is not two people in one file |
| `file:` inside a `mod:` its owner declared or touched | unchanged |

**Co-tenancy is loud, and that is half the change.** `coord-claim` used to delete the push log on
success, so an admitted claim that now shares a file would have said nothing — a relaxation nobody
is told about is indistinguishable from no guard. It now prints the hook's `CO-TENANT:` block, which
names the other claim, gives the message to post, and says to rebase *and re-run the measurements
after the rebase*, because a verdict taken before a rebase describes a tree that no longer exists.

**Gate re-aimed rather than agreed with.** `tests/coord/claim-scope-hierarchy.sh` asserted
"declared file vs same file → refused"; flipping that to `admitted` alone would have been a test
agreeing with whatever the code does. It now asserts admitted **and** that the hook SAYS so, via a
`verdict_says` helper that reads the hook's output. The old "a NON-bookkeeping file is still
exclusive" case was re-aimed, not deleted: its point was that the bookkeeping exemption must not
swallow real paths, and since both are now admitted the discriminator became the warning — a real
file is named as co-tenancy, a shared board is admitted in silence.

A/B'd both ways: reverting the hook to refuse makes three assertions fail; silencing the warning
while keeping the admission makes two fail. `claim-mutex-conflict`, `claim-hooks`, `coord-claim-runs`
and `coord-claim --self-test` all still pass.

## claim-overlap-tells-you-to-avoid-instead-of-talk — the checklist contradicted the policy

<!-- status: fixed
     lane: apparatus
     area: other
     kind: bug
     gate: tests/coord/claim-hooks.sh
     fixed-in: 8c39d9df1 -->

`POLICY.md` has said the right thing all along: **P-5.1** — "contested goes to the room — another
agent's claim in your way" — and **P-2.5**, "a refusal you believe is wrong is a conflict of
interest. Take it to the room." The room exists for exactly this.

`AGENTS.md` §"Before starting" said the opposite, in the one paragraph an agent reads immediately
before choosing work:

> If a sibling's branch name, modified files, or recent commits overlap with your candidate item —
> **pick a different item.** … **Don't coordinate through chat**; the git state is the contract.

Agents follow the checklist, not the policy, because the checklist is the operational text. The
observable result is work deferred that one message would have unblocked — twice in one session on
2026-08-10: `scripts/smoke-ci.ssc` (one task split into two claims an hour apart, the two edits in
different functions) and `.githooks/pre-push` (a one-line message fix left undone and filed as a
note instead).

**The sentence was half right, which is why it survived.** A claim IS only real when visible on
`origin/main` (P-2.4b) — a claim announced only in chat is not a claim. That is the RECORD. But
resolving an overlap is a different job from recording one, and the paragraph collapsed the two.

**MEASURED before changing anything, and the first number was wrong.**

| measurement | result |
| --- | --- |
| commit pairs from DIFFERENT claims, same file, within 6 h (30 days) | 143 |
| of those, touching overlapping line ranges | **43 — 30 %** |
| first attempt at the same number | 39 %, contaminated |
| distinct paths ever claimed / claimed by more than one claim | 244 / 82 |

The 39 % counted 1269 pairs of *consecutive* commits, most of which are one agent iterating on their
own work — that is not concurrency. Attributing commits to claims (via the `Landed <sha>` references
in `release-claim:` messages, 1054 shas recovered) and keeping only cross-claim pairs gives 30 %.

**So the lock blocks 100 % of rival edits to avoid a conflict in 30 %**, and in the other 70 % it
buys nothing. And a textual conflict is the *visible* case. The dangerous one is a clean merge that
silently drops the other's work — which is what actually happens on the files that have NO lock: the
boards took 392 commits in 30 days and produced 13 repair commits for lost entries, every one of them
a clean merge git was happy with.

**Fixed by changing what an overlap TELLS you to do, not by removing the lock** (30 % is not rare
enough for that, and it was my prior that it would be):

- `AGENTS.md` now separates the record from the resolution, keeps "a claim is real on `origin/main`",
  drops "pick a different item" and "don't coordinate through chat", and gives the message to post.
- `.githooks/pre-push` offers **asking in the room FIRST**, above "pick different work" and above the
  `verify-<slug>` escape, with the command and the 30 % figure so the reader knows the odds.

Gates: `tests/coord/{claim-mutex-conflict,claim-hooks,claim-scope-hierarchy,coord-claim-runs}.sh` all
pass, and the hook was executed the way git invokes it rather than only syntax-checked (P-6.2).

**Left open on purpose:** whether `file:` scopes should become advisory warnings rather than
refusals. The 30 % says that is a real trade, not a free one, and it changes the contract for every
agent — a decision, not a drive-by.

## coord-claim-items-prose-reserves-english-words — a claim written in prose reserves "a", "an", "the"

<!-- status: fixed
     lane: apparatus
     area: other
     kind: bug
     gate: none
     fixed-in: e341d8402 -->

**FIXED 2026-08-10 — refused at CLAIM time, where the message can be acted on.** `scripts/coord-claim`
now rejects `--items` tokens that are not id-shaped (a hyphen, underscore, slash or digit — what
every id in use carries) and the refusal carries the explanation that was missing: items are compared
as whitespace-separated tokens, so a sentence reserves each of its words. **The sentence is not
unwanted, it is in the wrong field** — the claim file already has `scope:` for prose, and it is never
compared with anything, so the message says to put it there.

That last point is what made option 1 viable rather than a ban on a habit. The objection to refusing
prose was that it refuses how the field is actually used; pointing at the field that already exists
for the purpose answers it.

**MY EARLIER NUMBER WAS THE WRONG UNIT and overstated the disruption by 5x.** This entry originally
said "261 of 370 distinct tokens are not id-shaped", which counts TOKENS and is therefore weighted by
how long each sentence was. The decision needs claims, not words. Re-measured over 200 revisions of
the ledger: **37 of 257 distinct claims — 14% — would have been refused, every one of them prose.**
A first attempt at that count said 80%, because it counted ledger ROWS, and a long-lived claim
appears in every revision it survives.

**Option 3 folded in rather than done separately.** Its content — say that items are tokenised — is
in this refusal. What is NOT done is the same sentence in `.githooks/pre-push`, which still matters
for the transition: live claims that already carry prose keep reserving their words until released,
and that guard is where the reader meets the symptom. That file is held by the live claim
`ledger-is-derived`, so it is one line for whoever holds it next.

**`--dry-run` added the same hour, for a reason worth recording.** Exercising the ACCEPT path of the
new check meant running the real tool — which claimed `probe-slug` and pushed it to `origin/main`
before I could stop it (removed immediately; no worktree, no branch, no commits). A tool whose only
mode is "do it" cannot be tested, so its accept path is the one nobody checks — and on a validator
that is exactly the half deciding whether the refusal is over-eager. `--dry-run` validates and prints
what would be written, touching nothing.

Six self-test checks now, two of them negative controls: prose is refused and named; every id shape
in use passes (`map-getorelse-expr-receiver`, `SSC3-J1c`, `P4b-4`, `mcp-2026-07-28`, `v2/BUGS.md`,
`E-4`, `smoke_budget`); and a sentence FOLLOWING a valid id is still caught, which a rule that only
examined the first token would have missed while passing the other two.


The overlap guard splits a claim's `items:` field on whitespace and treats every token as an item id.
`items:` is documented as a list of ids, but **prose is what agents actually write**, and then the
guard reserves every English word in the sentence.

**Measured 2026-08-10 over the last 120 revisions of `.work/active/LEDGER.tsv`: 370 distinct tokens
have appeared in `items` fields, and 261 of them are not id-shaped** — `a`, `an`, `and`, `because`,
`blow`, `already`. Prose is the norm, not one agent's slip.

**A SECOND agent hit this on 2026-08-04 and filed it separately — folded in here, because a
duplicate entry is the same failure this bug causes.** Claiming `v2-emitter-outline` with
`--items "E-4 outline the arm fallback, gate on emitted size"`:

```
✋ claim REFUSED — it overlaps a live claim. This is NOT a race; retrying will not help.
  item 'the' is already claimed by 'uniml-ssc3-frontend-readiness'
```

Their diagnosis adds what mine did not: **the refusal costs minutes because everything about it
points elsewhere.** The message asserts "this is NOT a race" while the observable symptom — a
rejected push — is exactly what a race produces, and four attempts in that session were
misdiagnosed as contention and retried, which the message explicitly says will not help. Naming
`'the'` as the colliding item reads as nonsense until you know items are tokenised, and nothing in
the output says so. Retrying with `--items E-4-outline-arm-fallback` succeeded immediately.

They also noted the worst property of the distribution: earlier claims in that same session used
prose `--items` and were ACCEPTED, purely because their words happened not to collide with a live
claim. The trap fires at random, and gets rarer as claims are released — which is the worst possible
schedule for learning it exists.

Two visible consequences, both observed today:

- `scripts/board` lists a live claim's task as **`a`** — the first word of
  `v3-lowerfail-names-the-right-file`'s sentence.
- A push was **refused** with `item 'an' is already claimed by 'v3-lowerfail-names-the-right-file'`.
  It was a redundant no-op push, so nothing was lost, but the refusal was entirely spurious.

**Why this is worse than cosmetic.** A guard that refuses pushes for spurious reasons teaches agents
to reach for `--no-verify`, and the next refusal it issues — a real one — gets overridden by reflex.
This guard has been right every other time it fired today, which is exactly the credibility being
spent.

**Two candidate fixes, and the measurement argues for the second.**

1. *Validate at claim time*: refuse `--items` tokens that are not id-shaped. Correct by the
   documentation, but 261 of 370 historical tokens fail it, so it refuses how the field is actually
   used and every agent hits it before learning why.
2. *Make the guard ignore non-id tokens*: prose stays allowed as a human note, and only tokens that
   look like ids (containing a hyphen, underscore, slash or digit, per the slugs and SPRINT ids in
   use: `smoke-suite-over-its-own-budget`, `SSC3-J1c`, `P4b-4`, `mcp-2026-07-28`) take part in
   overlap detection. Removes the harm without refusing anyone's habit.

3. *Say so in the refusal*: "items are compared as whitespace-separated tokens". The cheapest of
   the three and it does not change behaviour at all — it only makes the cause readable from the
   output, which is where the minutes actually go.

Not implemented here because the choice changes how every agent's claim is validated, and the guard
is the thing standing between two agents doing the same work — a blast radius worth a decision rather
than a drive-by.

**Duplicate folded in 2026-08-10:** `tests/BUGS.md
coord-claim-items-tokenised-so-prose-collides-on-stop-words`, filed 2026-08-04, now
`status: duplicate`. Canonical here because the fix is in `scripts/coord-claim` and
`.githooks/pre-push` — routing is by the module that owns the FIX, not by where the reporter
happened to be working.


## routing-authority-is-declared-but-not-implemented — `fixed-in` outranks `lane` on paper only, and nothing checks either

<!-- status: open
     lane: apparatus
     area: build
     kind: apparatus
     gate: tests/e2e/bugs-index-gate.sh
     fixed-in: - -->

Two findings, one cause: `POLICY.md` §P-3.3 describes a routing contract that no code implements.

**1 · `bugs-split` never reads `fixed-in`.** P-3.3 sets the authority order as *"`fixed-in` (a
resolvable sha) > a field a human declared > keyword extraction (NEVER)"*. `scripts/bugs-split`
routes on `lane:` alone — `grep fixed-in scripts/bugs-split` is empty. Its own comment says *"the
lane says which implementation misbehaves, which is the module where the fix goes"*, and that
premise is false whenever a defect is FILED against the lane where the symptom appeared and FIXED
somewhere else.

Concrete instance, and how this was found: `uniml-yaml-alias-resolution-last-wins` carries
`lane: js` and a `fixed-in` naming a commit that changed `uniml/yaml`. By P-3.2 it belongs to
`uniml`; by the tool it belongs to the js board, where it still sits. There is no lane that would
move it — see 2.

**2 · Six modules have no lane at all.** `tests/fixtures/modules.tsv` gives `-` to `v1/lang`,
`tests/conformance`, `scripts`, `scljet`, `uniml` and `payments`, and the `lane:` enum in
`specs/bugs-index.md` has no value for any of them. A defect whose fix lives in one of those six
cannot be routed there by lane, only placed by hand.

**3 · "the gate catches it" — no gate does.** P-3.3 closes with *"An entry whose location and
`lane:` disagree is a tracking bug, and the gate catches it."* `tests/e2e/bugs-index-gate.sh` (240
lines) never reads `modules.tsv` and never compares a lane to the file it is in. Measured today:
**69 entries disagree**, most of them a specific lane (`native`, `int`, `v2-rust`) sitting in the
ROOT board, which `modules.tsv` reserves for `multi` and `n/a`.

**Deliberately NOT fixed here, and the reason is the number.** A gate asserting the rule would go
red on 69 pre-existing entries the moment it landed — the "arrived red" shape that
`v3-ci-workflow-red-on-every-run-since-it-was-added` above is about. Whoever takes this has to
decide first whether those 69 are misfiled or whether the rule is wrong about them, and that is a
judgement, not a sweep. The cheap half is 2: adding the six missing lanes costs a row each and
makes the other two answerable.

**Gate named 2026-08-14: `tests/e2e/bugs-index-gate.sh`** — the gate `POLICY.md` §P-3.3 already
claims catches this and does not. It reads every entry's header on every push, so the comparison
belongs there and nowhere else; what it lacks is `tests/fixtures/modules.tsv` and a lane-vs-file
check.

**Done when** that gate compares `lane:` against `modules.tsv` and passes — which requires first
deciding the 69 disagreements, since a gate that arrives red on 69 pre-existing entries is the shape
this entry itself warns about. **The cheap half is separable and can land alone:** six missing lanes
in `modules.tsv` and in the `specs/bugs-index.md` enum, asserted by the enum check that gate already
performs.


## v3-ci-workflow-red-on-every-run-since-it-was-added — two causes, and the message named neither

<!-- status: fixed
     lane: apparatus
     area: build
     kind: apparatus
     gate: .github/workflows/v3.yml
     fixed-in: 1b20e4f1a -->

**FIXED 2026-08-08.** `.github/workflows/v3.yml` had failed on **all ten of its runs**, from the
commit that introduced it. Not a regression from anyone's later work — the job arrived red, so
there was no green run to compare against and nothing said "this never worked".

**What it printed was true of nothing:**

    FAIL the canonical form changed; review the diff and re-freeze deliberately
    ssc3: coursier ('cs') is needed to fetch the Scala 3.8.3 compiler — see setup.sh
    0a1,70

`0a1,70` is "expected empty, got 70 lines" — the frozen side was empty because `v3/ssc3` never
ran. Nothing about the canonical form had moved. **A gate that cannot tell "the tool is missing"
from "the output changed" sends the next reader to re-freeze a baseline that is fine.**

### Cause 1 — a misread invariant, not a missing line

The workflow installed only the JDK, with the comment *"the kernel builds with the JDK and nothing
else — invariant I-1 — so nothing is installed for these"*. `v3/specs/00-charter.md` defines I-1 as
an empty `libraryDependencies` and no kernel import outside `java.*`/`scala.*` — a property of the
ARTIFACT. It says nothing about build tooling and cannot: the kernel is written in Scala, so
compiling it needs a Scala compiler however dependency-free the result is. Installing `cs` does not
weaken I-1; the gate for I-1 is the dependency-list check the charter itself names.

The project had already recorded the answer — `v3/SPRINT.md` item 31e: `cs` became a first-class
requirement when v3 stopped going through `scala-cli`, and `setup.sh` installs it. The new step
mirrors that script's Linux branch rather than adding an action, so CI and a fresh checkout get it
the same way. The comment is replaced with this reasoning so the misreading does not recur.

### Cause 2 — a BSD-only `mktemp`, in seven places

Clearing cause 1 moved the red one step, to `mktemp: too few X's in template 'ssc3x'`. GNU requires
at least three trailing `X`; macOS does not. **This is why local verification could not have caught
it, and it is exactly `emulate-the-other-host-in-the-gate`.** Seven sites, all `mktemp -t <name>`:
`exec-gate.sh` (x2), `bridge-gate.sh`, `front-report-gate.sh`, `selftest.sh`, `toolchain-gate.sh`
and — the one that mattered most — `v3/ssc3` itself, the driver every gate runs through.

Now `mktemp "${TMPDIR:-/tmp}/<name>.XXXXXX"`, which is portable and drops `-t` entirely.

**The driver's site hid behind a redirect.** `parity-gate` went red under the emulation while
reporting ZERO mktemp errors, because it calls the driver with `2>/dev/null` — the same masking
that made the v2 backend harness report "the expected line is absent" for a crash this morning.
Two harnesses, one habit.

**Verified against the other host without waiting for it.** A GNU-strict `mktemp` shim on `PATH`
reproduces the CI failure exactly: the pre-fix `exec-gate.sh` emits 7 of the CI error under it, the
fixed one emits 0 and is GREEN over 58 cases; all six gates the job runs are GREEN under the shim.

### Causes 4 and 5 — the OTHER job, which I had not read

`front-capability` runs `v3/uniml-classpath.sh`, which builds an sbt project; the runner ships no
sbt, and the script reported `sbt failed; see /tmp/tmp.XXXXXX` **0.02 s after the step began** —
`command not found` wearing the costume of a build failure, naming a log the runner then threw
away. It now checks for sbt by name, says which action to add, and on a real failure prints the
last 20 lines. Verified by running it with sbt off `PATH`.

Then the gate still said *"only these fronts are registered: none"*. Cause 5: the kernel is built
from `v3/src` **plus `alphabet/src`**, and the second front from `v3/src` plus `v3/uniml` — without
the alphabet. When the two copies of the lexical alphabet were merged into one shared directory,
the kernel's compilation unit was updated and this one was not, so `Lexer.scala`'s reference to
`scalascript.alphabet.Alphabet` stopped resolving. **A compile error arrived three layers later as
a missing registration**, because the driver's fallback is silent by design and the gate reads
`ssc3 front` through `2>/dev/null`.

### And one that was mine

I added the coursier step to ONE job. The second failed the same way, on the message I had just
fixed next door. Both jobs have it now, with a comment saying so.

**FOUR OF THE FIVE HID BEHIND A `2>/dev/null`.** That is the through-line, not the individual bugs:
"the canonical form changed" was a missing compiler, "the bridge did NOT overflow" was an overflow,
"sbt failed, see /tmp/…" was sbt absent with the log already gone, "registered: none" was a compile
error two layers down. Every one reported an interpretation instead of an observation, and every
one cost a round-trip through CI.

**VERDICT, from CI rather than from me.** On `3702149f4`, which carries all of the above:
`the two fronts accept the same programs` — **success**; `v3 gates` — one FAIL, and it is not this
entry's: `object-nested-class`, added by `c71b58e28`, ships an `.uniml-only` marker and needs the
second front, which that job deliberately does not build (the sbt cost is why it was split out).
Reported to its author in the room rather than patched — whether to skip the fixture or give that
job the build is their design call.

**Worth doing and not done here:** keeping the GNU-strict `mktemp` shim as a standing gate would
stop the next BSD-only assumption reaching CI. It is a separate slice — this entry is the evidence
that it would pay. Three of the five causes needed Linux and one needed a pipe larger than 64 KB;
none could be seen on the developer host.



## a-shared-board-file-has-no-guard-against-a-stale-copy-overwrite

<!-- status: fixed
     lane: apparatus
     area: cli
     kind: bug
     gate: .githooks/commit-msg --self-test (smoke: board-deletion)
     fixed-in: fd593e7cd -->

**The incident, 2026-08-08.** `78077acd7` — subject `fix(frontend/custom): jsLiteral encodes case
classes, maps and tuples` — removed **65 lines from `v3/BACKLOG.md` and added none**, deleting two
whole entries written the same day: `THE TYPE CHECKER — the decision v3 has not made` (`467adf641`)
and `v3 carries its own copy of the character alphabet`. Nothing in that commit's subject, its other
three files or its message concerns either. It wrote a STALE COPY of a shared board over the current
one. Restored in `3c086798c`.

**Why no guard fired, and this is the point.** The overlap guard says so itself, in the message it
prints:

    Note: SPRINT.md / BACKLOG.md / CHANGELOG.md / BUGS.md / MILESTONES.md / README.md are SHARED
    and are never an overlap — if one of those is in the list above, this hook has a bug.

That exemption is correct for its own purpose: everyone appends to these files and treating every
touch as a conflict would stop the queue. But it means **the files every agent's work is recorded in
are the only ones with no protection at all** — not the claim-scope check, not the overlap check.
A worktree holding a copy from before someone else's edit commits it back and the edit is gone, with
no diff anyone reads because the commit is about something else entirely.

**FIXED 2026-08-08 — `.githooks/commit-msg`, the first option below.** A staged shared board may not
DELETE a `## ` heading that exists on `origin/main` unless the message NAMES it: the entry's slug,
or any word of it that is at least eight characters and contains a letter. Deletion is the shape
that hurts; appends are the normal traffic and are never touched. It is a `commit-msg` hook rather
than `pre-commit` for one reason — only there is the message available, and "say what you removed"
IS the mechanism.

**Validated against the incident itself**: replaying `78077acd7`'s staged file with `78077acd7`'s
own message is refused, and the refusal names both destroyed entries. And in the other direction —
a message naming them passes, an append passes, a no-op passes.

**Its self-test caught a defect in the rule, which is why it exists.** The first version keyed on the
LONGEST token of the heading, and `THE TYPE CHECKER — the decision v3 has not made, framed
2026-08-08` made that the DATE. No message about removing an entry contains a date, so a
correctly-worded deletion was refused while the incident was still caught — a guard tested only on
the case that motivated it is a guard tested in one direction. The rule now takes ANY token of ≥8
characters CONTAINING A LETTER, and a second self-test case pins the date shape.

Registered in `scripts/smoke-ci.ssc` as `board-deletion`.

**The options as first written, in rough order of cost:**

- a pre-commit check that a shared board file's staged version does not DELETE a `## ` heading that
  exists in `origin/main`, unless the commit's message names it. Cheap, and deletion is the shape
  that hurts — appends are fine and are the normal traffic;
- the same as a pre-push check against the pushed range, which catches a rebase that resurrects an
  old copy;
- `scripts/board --check`-style drift detection extended from `.work/active/` to the boards.

**Found by accident**, which is the argument for a gate: I went to `v3/BACKLOG.md` to record three
measurements, found the section I meant to edit absent, and only then traced it. Nobody was looking
for it, and the deletion had already been on `main` for hours.

**The header said `open` with `gate: none` while the prose above said FIXED — closed 2026-08-08 after
checking, not after reading.** The self-test passes all six directions it distinguishes, and it is
registered in `scripts/smoke-ci.ssc` as `board-deletion`, so the gate field was wrong too.

**It has now fired on a real commit, which is worth more than the self-test.** A different agent's
`BUGS.md` was 23 commits stale after a rebase, and the commit-msg hook refused it and NAMED the three
entries that would have been deleted — `front-diff-cannot-finish-when-the-second-front-does-not-compile`,
`v3-has-no-scala-style-import`, `v3-refuses-a-default-argument-inside-an-enum-case`. The message it
prints is what made the fix obvious: *"your tree predates someone else's edit: rebase and re-apply"*,
which is exactly what happened and exactly what was done. Without it those three would have gone the
way the incident above describes, inside a commit about `val a, b = 1`.

**Two of the three options remain unbuilt, deliberately.** The pre-push variant against the pushed
range would catch a rebase that resurrects an old copy, and `scripts/board --check` drift detection
would cover the boards as well as `.work/active/`. Neither is the defect this entry filed — that one
was "no guard at all" — so they belong to whoever wants that hardening, not to this entry staying
open and reading as unprotected.


## launchers-not-dead-red-in-every-fresh-worktree — the gate refuses on an empty bin/, by design, every time

<!-- status: fixed
     kind: apparatus
     lane: apparatus
     area: build
     gate: tests/e2e/launchers-are-not-dead-on-arrival.sh
     fixed-in: 8fb4327f5 -->

`scripts/new-worktree` gives a checkout whose `bin/` holds `ssc` and none of the delegating
launchers, so discovery found zero subjects and the gate FAILED rather than passing vacuously — the
property it was built with, and correct as far as it went. The consequence was that every smoke run
from a fresh worktree was red on this one check, in roughly fifteen runs over three days. A red that
appears unconditionally is one people learn to skip, and the next real failure goes with it.

**FIXED as the entry prescribed: SKIP is not the same verdict as PASS, and neither is FAIL.** The
launcher sources are tracked at `v1/tools/scripts/launchers/`. If they exist and `bin/` holds NONE of
them, the checkout is partially built and the gate now says so and exits 0, in the same shape
`f-bare-member-call-gate` and its siblings already use for `$ssc not built`. If `bin/` holds SOME but
fewer than the floor, discovery is what broke and that is still a hard fail, and the message says
explicitly that this is not the fresh-worktree case.

**Three states, all measured — the middle one is why this is not just "let empty pass".**

| `bin/` holds | verdict | exit |
| --- | --- | --- |
| no delegating launchers, sources present | `SKIP … no launchers installed` | 0 |
| all five (`jssc ssc-js ssc-spark ssc-wasm sscc`) | asserts each, PASSED | 0 |
| two of five | `✗ found only 2 … discovery broke` | **1** |

The second and third states were produced by staging launchers into a fresh worktree's `bin/` and
then removing three, so the gate was watched crossing the boundary in both directions rather than
argued about.

**This entry was filed late and said so, which is the part worth keeping.** It was hit on 2026-08-05
and mentioned in commit messages perhaps fifteen times without ever being filed, because each time it
was "the known one" and not the thing being worked on. It was hit twice more on 2026-08-08 by an
agent who did not know the entry existed — once as two `StandardMain` traces in a scratchpad, once as
a smoke suite that died before its first check in an unbuilt worktree — and diagnosed from scratch
both times.

## url-import-flakes-under-suite-load — green standalone, red inside the smoke suite

<!-- status: fixed
     kind: apparatus
     lane: apparatus
     area: build
     gate: tests/e2e/url-import-smoke.sh
     fixed-in: fd070f4f7 -->

`url-import` FAILED inside a full smoke run and PASSED when the same script was run directly on the
same tree seconds later. Recorded as load- or timing-sensitive and not narrowed further.

**It is neither load nor timing: the gate is not safe against a second copy of ITSELF, and the suite
is simply where a second copy is likely — several agents run it at once in this repo.** Four things
were shared between concurrent runs, and all four are per-run now:

| was | now |
| --- | --- |
| `PORT=9870`, fixed | a free port probed from a random offset, and a hard failure if none is free |
| `/tmp/url-smoke-consumer.ssc` | inside the run's own `mktemp -d` |
| `/tmp/url-smoke-http.log` | likewise |
| `trap … lsof -ti :$PORT \| xargs kill -9` | kills only this run's server, by pid |
| `rm -rf ~/.cache/ssc` — the whole tree | `~/.cache/ssc/http/127.0.0.1:$PORT`, this run's entry |

**The last row is the one that mattered most, and the first attempt at it was wrong.** I tried to
isolate the cache with `SSC_CACHE_DIR`; that is bin/ssc's ARTIFACT cache, while the import cache is
hard-coded at `ImportResolver.scala:27` as `os.home / ".cache" / "ssc"` with no override. But it is
keyed by scheme/authority/path and the authority carries the port, so once the port was per-run each
instance's ENTRY was already private — what was still shared was the WIPE, which deleted every
instance's entries. A concurrent run removed this one's between its fetch and its "cache hit (server
stopped)" case, which is exactly the case that failed.

**Measured, with the protocol stated because it decides the answer.** Two instances started ONE
SECOND APART: before, 2/4 and 0/4 with both exiting 1, reproduced 3 trials out of 3; after, 4/4 and
4/4 across 3 trials. Three instances started SIMULTANEOUSLY passed even before the fix — which is
why this presented as a flake rather than a broken gate, and why a single passing run would have
been the wrong thing to conclude from.

Also fixed while in here: the readiness loop waited 10 s for the local server and then fell THROUGH,
so a server that never came up surfaced as four unrelated case failures. It waits 30 s and exits with
the server log now. And `BIN` is overridable, which is how the before/after above were run against
the two toolchains.

## coord-release-does-not-check-the-work-landed — a claim can be released, and its record written, over a branch that was never pushed

<!-- status: fixed
     lane: apparatus
     area: build
     kind: apparatus
     gate: tests/coord/coord-release-refuses-unpushed-work.sh
     fixed-in: 090ce006f7eac699ab015a84602c0781521ee11f -->

**FIXED 2026-08-07.** `coord-release` now reads `branch:` from the claim and refuses when that
branch has commits `origin/main` does not, BEFORE it removes anything or commits. An abandoned
branch is still releasable with `COORD_RELEASE_ALLOW_UNPUSHED=1`, which says so on stderr rather
than passing quietly. Two cases stay notes rather than refusals, because refusing them would break
ordinary use: a claim with no `branch:` (the old single-line form) and a branch already deleted
(merged-and-deleted is the normal order). Both print what could not be checked — a check that cannot
run must not look like one that ran.

**The A/B is the evidence, not the green.** `tests/coord/coord-release-refuses-unpushed-work.sh`
against the PRE-guard script: 11 checks fail, and the 6 controls pass on both. The controls are the
point — a guard on the release path can break every agent's release, so the file asserts that an
ordinary pushed release still works, that the level still reaches the message, and that the two note
cases still release. All 11 files in `tests/coord/` are green.

`scripts/coord-release` fetches origin and fast-forwards main, then removes the claim file and
writes the release record. **It never checks that the claim's own work is reachable from
`origin/main`.** So a release can announce "Landed" for a branch whose push was rejected, and the
board — the thing agents read to know what is done — records the opposite of what happened.

**Observed 2026-08-07, by me, on `uniml-corpus-floor-independent-oracle`.** The push was rejected
non-fast-forward because main had moved during the test run. The release ran anyway, the claim came
off the board, and the worktree and branch were deleted, leaving the commit reachable only as a
dangling object. Recovered from `git fsck --lost-found` and landed as `2fedc4c07`; the false record
is `5e84d0dfe`, and commit messages cannot be edited, which is why this entry exists.

**The proximate cause was mine and is worth naming separately from the tooling gap**: I chained
`push`, `release` and `worktree remove` on separate lines instead of with `&&`, so a non-zero exit
did not stop the sequence. That is the same shape as `piping-a-gate-masks-its-exit-code` — a failing
step whose status nothing acts on. `set -e` or `&&` is the fix on the caller's side, and I have
started using it.

**But the tooling should not depend on the caller getting that right**, because the failure is
silent and the damage is to a shared board. A cheap guard, in `coord-release` before it removes
anything:

    # the claim names a branch; refuse if that branch has commits origin/main does not have
    ahead="$(git rev-list --count "origin/main..$branch" 2>/dev/null || echo 0)"
    [ "$ahead" = 0 ] || die "branch $branch has $ahead commit(s) not on origin/main — push before releasing"

The claim file already carries `branch:`, so nothing new has to be recorded for this. A release of a
branch that was deliberately abandoned is still possible with an explicit flag; what must stop is
doing it by accident and writing "Landed" about it.

**Worth checking while fixing:** `git worktree remove --force` on an unmerged branch, and
`git branch -D`, are the two steps that turn "not pushed" into "not reachable". Refusing there — or
just printing the sha before deleting — would have made this recoverable without `git fsck`.

## uniml-ci-count-floor-went-slack-by-five — the check that guards the aggregate had stopped guarding it

<!-- status: fixed
     lane: apparatus
     area: build
     kind: apparatus
     gate: .github/workflows/ci.yml
     fixed-in: 1a166df03 -->

**FIXED 2026-08-07.** The `uniml:` job asserts that the standalone aggregate reports one
`All tests passed` line per project, because a build that quietly stopped aggregating still exits 0
while testing less — the failure an exit code cannot see. It compared `passed -lt 10`.

**The aggregate is 15.** `markupCoreCross`, `unimlXmlCross`, `unimlAddress` and the two JS siblings
were added on 2026-08-06/07 and the number was never moved with them, so the check tolerated losing
a third of the module in silence. Verified against the OLD predicate rather than reasoned about:
at `passed` = 14, 11 and 10 it returned green.

    passed   old (-lt 10)   new (-ne 15)
    15       green          green
    14       green          RED
    11       green          RED
    10       green          RED
    16       green          RED

Measured, not guessed: `cd uniml && sbt -batch test` reports 15, exit 0, 103 s.

**Now EXACT rather than a floor, and that is the fix.** A floor only ratchets when someone remembers
to ratchet it, and across five additions nobody did — the same shape as the breadth ceilings
corrected in `49b3e86a5` the same morning. Equality means losing a project goes red AND adding one
goes red until the number is bumped in the same commit. One line of friction, bought against a check
that had stopped checking.

**Deriving the count from `.aggregate(...)` was tried and rejected.** It reads 15 today and looks
like the self-maintaining answer, but the failure being guarded against is a project dropping OUT of
that list — an expectation read from the same list falls with it and stays green. The expectation
has to be frozen where the sabotage cannot reach. Recorded in the step itself so the next reader
does not re-derive it and think it an improvement.

Found while answering a different question — whether `uniml/build.sbt` could be deleted. It cannot,
and why is in `uniml/BACKLOG.md`; this was noticed on the way.

## launcher-digest-includes-nested-specs-so-a-doc-commit-forces-a-rebuild

<!-- status: fixed
     lane: apparatus
     area: build
     kind: bug
     gate: none
     fixed-in: 8265d8208 -->

**FIXED 2026-08-10 — and `kind` moved from `friction` to `bug`, because tracing it found the same
mistake pointing the other way, where it is dangerous.** The exclusions matched the FIRST path
component only. That is why `v3/specs/*.md` forced rebuilds. It is also why
**`specs/v2.2-p6.5-fsub.ssc` — the F FRONT — was not in the digest at all.**

Measured: appending a line to the front left the digest unchanged, while
`bin/lib/*/native-front/tower/bin/fsub.ssc` is byte-identical to it. So `smoke-ci`'s staleness
refusal could not see a change to the DEFAULT FRONT: an edited F ran as the old F and the suite
reported green. Since 2026-08-09 two more things key on this digest — the shared toolchain cache and
the conformance memo — so by the time it was found the hole served the previous front to every
worktree that hit the cache.

Both halves fixed in `is_excluded_path`: the doc and corpus directory names are matched at ANY depth
now, and `.ssc`/`.ssc0` directly under `specs/` are exempted from the exclusion. The exemption is
scoped rather than by extension because `tests/` holds ~400 corpus cases that are data the launcher
READS — re-including those would rebuild on every case edit, which is the friction this entry was
filed about, reintroduced.

**Six controls, because two of them are the ones that make it wrong in the other direction:**

| edit | digest | |
| --- | --- | --- |
| `specs/v2.2-p6.5-fsub.ssc` (the F front) | changes | the hole, closed |
| a compiler source under `v1/` | changes | control |
| a template `README.md` under `src/main/resources` | **changes** | this file's own warning: sbt packages it into the jar |
| `v3/specs/*.md` | unchanged | the filed friction |
| top-level `specs/*.md` | unchanged | |
| a `tests/conformance/*.ssc` case | unchanged | corpus is data, not code |

Today exactly one file matches the exemption; the rule is written so a second would be picked up
without anyone noticing it needed to be.

`scripts/smoke-ci` refuses to run against a launcher built from different sources than the tree, and
that refusal is right — a verdict from a stale toolchain is a verdict about the wrong code. What is
wrong is WHAT counts as a source. Measured 2026-08-06 by rebasing onto `e4cc9f706`, a commit that
changes three markdown files and nothing else, all under `v3/`:

```
digest before the rebase : 1cf3378a3a295ca1…
digest after             : dae666de72ac3aac…
launcher-input-digest --explain | grep -c v3/specs/50-uniml-projection.md  ->  1
```

`--explain` prints its exclusions as `.work .github .agents TASK docs site specs bench releases
scratch bin examples tests + root *.md`, and every one of those is matched as a TOP-LEVEL path. So
`specs/` is excluded and `v3/specs/` is not; `docs/` is excluded and a module's `docs/` would not
be. **A sibling agent landing a spec or a design note in any module costs the next agent who rebases
a ~7-minute `./install.sh --dev` before smoke-ci will say anything at all.**

Worth stating plainly because the failure is confusing rather than loud: the message names two
digests and tells you to rebuild, and nothing in it suggests the cause was somebody else's markdown.
On 2026-08-05 I hit this, guessed that module `BUGS.md`/`SPRINT.md` under `v1/` were the cause,
checked before filing, found they are NOT in the digest, and released the claim saying the cause was
unknown (`jvm-package-import-link-name`). The guess was wrong about which file; the shape was right.

Not fixed here because the fix is a decision about the exclusion rule, not a patch: matching those
names at ANY depth would also exclude a module's `tests/` and `bin/`, which may be load-bearing for
some module's build. A narrower rule — exclude `**/specs/**` and `**/docs/**` only — is probably
right and wants whoever owns the digest to say so.

## build-ram-guard-gate-fails-under-ambient-load — same tree, two verdicts, minutes apart

<!-- status: fixed
     kind: apparatus
     lane: apparatus
     area: build
     gate: tests/e2e/build-ram-guard-gate.sh
     fixed-in: 170a2cb52 -->

Found 2026-08-04 by a smoke run that went red on a change (Set operators) that cannot touch a JVM
memory guard. The failing assertion is load-dependent:

```
✗ dry-run KILLED builders: expected>=5 got=4
```

**The evidence that it is the gate and not the tree** — three runs, same commit, minutes apart:

| run | where | result |
|---|---|---|
| inside `scripts/smoke-ci` | worktree | **FAIL** `expected>=5 got=4` |
| `tests/e2e/build-ram-guard-gate.sh` directly | shared main (unmodified) | PASS |
| the same gate directly | the SAME worktree, same commit | PASS |

Identical code, opposite verdicts; the only variable is how many builder JVMs exist on the host at
that instant — and this host runs several agents building concurrently. The other twelve assertions
in the gate passed every time, so the guard itself is fine; it is this one count that is written
against ambient state.

Why it matters beyond the annoyance: a red smoke run is the signal every agent uses to decide
whether their own change broke something. One that fires on load teaches people to re-run until
green, which is exactly how a real red gets waved through. Related in kind to
`smoke-suite-over-its-own-budget` — both are the suite measuring the host rather than the tree.

Fix direction (owner's call): have the assertion count only the builders the gate itself spawned,
or make it a floor derived from what it observed rather than a fixed `>=5`.


**FIXED 2026-08-05 — the assertion now names its subjects instead of counting the host.**

It sampled `ps ax | grep -c 'sbt-launch|bloop|sbt/standalone'` before and after the dry run and
required `after >= before`. That counts every builder on the machine, other agents' included, so a
sibling's compile finishing NORMALLY between the two samples dropped the count and this cell blamed
the dry run — which is exactly the three-run table above: one tree, two verdicts.

The guard already answers the real question in its own log:

```
[ts] DRY T2-idle would-kill pid=54466 (rss=6978MB no-cpu-in-1s cwd=…)
```

So the check is now: **every process the guard said it WOULD kill must still be alive.** Stronger
than the count — it verifies the specific processes rather than the population — and independent of
what else the host is doing.

Two details that make it honest rather than merely green:

- With NO named target the cell prints `note: dry-run named no would-kill target — this check had
  nothing to verify` instead of passing. A vacuous pass reads as evidence and is not.
- The failure branch was exercised rather than assumed: fed a log naming a PID that had already
  exited, the same logic reports `bad`. Testing it by killing a real builder was not an option —
  the processes on this host belong to other agents, which is the whole point of the entry.

The fix direction the entry offered as an alternative — deriving the floor from what was observed —
was rejected on purpose: a threshold that adapts to the ambient state would mask a real regression
in the guard, which is the one thing this gate exists to catch.

## bugs-index-gate-allows-a-detached-header — an unterminated header passes the gate and is invisible to every query
<!-- status: fixed
     kind: apparatus
     lane: apparatus
     area: build
     fixed-in: 363b53267
     gate: tests/e2e/bugs-index-gate.sh -->

**Found 2026-08-04** by `bugs-header-adjacency`, from a two-line discrepancy nobody was looking at:
`scripts/bugs-report` counted **2 entries as MISSING-HEADER** while `bugs-index-gate` reported
**0 problems** over the same eight files. Two tools, one data set, different answers — which is the
only reason this was visible at all.

**Both tools use the same regex and only one of them bounds it.**

```python
re.match(r"\s*<!--(.*?)-->", body, re.S)
```

In the gate, `body` is the WHOLE entry, so an entry whose `-->` is missing runs on through its own
prose and matches the **next entry's** terminator. The fields parse, the gate passes. `bugs-report`
stops after 13 lines, finds no terminator, and files the entry under `MISSING-HEADER` — a bucket its
own `--status` filter does not accept, so the entry is invisible to every query.

**The two entries were mine**, both closed the day before, both mangled by a script I used to write
`fixed-in` after a rebase: it reassembled the header around `s.find("-->")` and dropped the
terminator. So the gate that exists to keep this index queryable passed on damage done by the
tooling around it.

**Fix:** a header is a compact block, so a blank line inside the match means the terminator is
somewhere it does not belong. Bounded, and the message says what it costs ("invisible to
bugs-report") rather than only what is wrong.

**Proven in both directions**, and with the real file rather than only the self-test: removing a
terminator from a live entry makes the gate exit 1 naming that slug; restoring it returns exit 0.
A fifth planted defect was added to the self-test so it cannot regress silently.

After the repair `bugs-report` counts 587 fixed where it counted 585 — the two entries were not lost,
they were unqueryable.

## bugs-index-selftest-cannot-pass-in-a-shallow-clone — main red on EVERY push, on the gate's own self-test
<!-- status: fixed
     kind: apparatus
     lane: apparatus
     area: build
     fixed-in: f078f9a35
     gate: tests/e2e/bugs-index-gate.sh -->

**Found 2026-08-02** by `gate-holes-sha-and-freeze`, from a CI red on a commit whose diff could not
have caused it.

`9194a90c7` replaced the `isdigit` run-id heuristic with a REACHABILITY check, on the argument that
reachability "subsumes the guard completely". It does — **in a full clone**. CI checks out at
`fetch-depth: 1`, and there the entire reachability branch is skipped, so the self-test's planted
run id (`30484689408`) passes on shape alone and the gate cannot emit the message its own self-test
demands:

```
SELF-TEST FAILED: expected a problem mentioning 'not a commit sha'
```

Every push failed. Locally every developer saw GREEN, because a working checkout is not shallow.

**Reproduced in CI's environment rather than reasoned about** — `git clone --depth 1`, the method
this repo already learned to use for this class:

| gate version | full clone | shallow clone |
|---|---|---|
| pre-fix (`main`) | exit 0 | **exit 1 — the exact CI error** |
| fixed | exit 0 | exit 0, all 4 planted defects caught |

**Fix.** A LENGTH-BOUNDED shape rule, checked unconditionally: all digits AND `len >= 11`. That is
not the guard that was deleted — the deleted one had no length bound and rejected the real 9-digit
abbreviations `611795277` and `261607982` (the false positive that motivated its removal). A GitHub
run id is eleven digits; this repo abbreviates to 7-10. The bound separates them, and it works where
it is needed most: the environment that cannot check reachability.

**Same shape as [[project_validate_job_red_on_own_selftests_0728]]** — a job red on its OWN
self-test rather than on the repository, invisible to every local run.


## launcher-digest-changes-when-you-COMMIT-unchanged-content — a rebuild per commit cycle
<!-- status: fixed
     lane: apparatus
     kind: bug
     area: build
     fixed-in: 69f4e3cd2
     gate: tests/e2e/launcher-digest-gate.sh -->

**FIXED.** `inputs()` now emits ONE canonical line per path — `<content-sha><TAB><path>`, sorted —
so the three sources say where a path's bytes come from and no longer decide how the line is
spelled. Working tree takes precedence over HEAD, because a path that is both committed and
modified must digest as what is on disk: that is what a build would compile.

The gate carries the exact sequence that exposed it — a new file appears, then is staged. The first
step MUST change the digest and the second MUST NOT. Verified in both directions: reinstating the
state-dependent spelling turns the gate red and prints both digests.

**Found 2026-08-01** under `ssc3-core`: built, tested green, committed nothing but the files already
tested, and `smoke-ci` then refused with *"the launcher was built from different sources than this
tree"*. The bytes were identical; only where git reports them from had changed.

`scripts/launcher-input-digest` hashes a LINE PER INPUT, and an input is encoded differently
depending on whether it is committed:

```sh
git ls-tree -r HEAD …                     →  "<mode> <type> <sha>\t<path>"
git diff HEAD --name-only … | …           →  "dirty <path> <sha>"
git ls-files --others … | …               →  "untracked <path> <sha>"
```

A file therefore moves between spellings as its GIT STATE changes, with its content untouched, and
the digest changes each time. So the guard fires on the actions that provably cannot alter a build
input. Measured on 2026-08-01, a single unchanged file shifted the digest **twice**: once on
`git add` (untracked -> dirty) and again on `git commit` (dirty -> ls-tree).

The cost is exactly what the digest exists to prevent: this tool was written because
`.build-stamp` (the HEAD sha) forced *"a ~3.5 min rebuild for nothing"* on a docs-only commit — and
the replacement forces a full rebuild after **every** commit, for the same reason in a different
disguise. Measured here at ~10 min per rebuild, four times in one session. The workaround that actually
works is to COMMIT first and verify second, which inverts the order POLICY.md P-1.4 asks for.

Fix: emit one canonical form per path — content sha keyed by path, with no state prefix — so the
same bytes hash the same however git currently reports them. The three sources stay; only their
spelling is unified. A self-test is cheap and is what would have caught it: digest a clean tree,
touch nothing, `git commit`, digest again, assert equal. Note P-6.2 — it has to RUN the tool.

## coord-claim-broad-reason-lands-inside-the-paths-field — every `--broad` claim is refused
<!-- status: fixed
     lane: apparatus
     kind: bug
     area: build
     fixed-in: 3325b91e3
     gate: tests/coord/coord-claim-broad.sh -->

> **FIXED 2026-08-02.** The `broad:` line is built before the heredoc now, with a real newline.
> Verified END TO END rather than only in a sandbox, because the symptom was a refused push:
> `coord-claim broad-probe-3 --paths mod:payments/mx-spei --broad "…"` reached `origin/main` with a
> clean `paths:` and `broad:` on its own line. A first attempt on `mod:v3` was still refused — as a
> genuine overlap with the live `ssc3-core` claim, which is the guard working, not this bug.
> Gate `tests/coord/coord-claim-broad.sh` is in `scripts/smoke-ci` and fails on three of its four
> assertions against the unfixed script.

**Found 2026-08-01** filing the `ssc3-core` claim, which needed `mod:v3` for a directory that did
not exist yet.

`scripts/coord-claim:108` writes the claim file from a quoted heredoc:

```sh
paths: ${paths}${broad:+\nbroad: ${broad}}
```

Inside a quoted heredoc `\n` is two literal characters, not a newline, so the justification is
appended to the **`paths:` line** rather than starting a `broad:` line. The pre-push guard then
compares that line against the `LEDGER.tsv` paths column, they disagree, and the push is refused —
with a diagnostic that shows the reason's words sorted into the path list:

```text
✋ pre-push: a claim disagrees with its LEDGER row.
  'ssc3-core' paths disagree:
    .claim : (Sergiy's 3 ScalaScript being created direct does exist existing …
```

Consequence: **`mod:` and `repo:` scopes are unusable**, because those are exactly the scopes
`--broad` is mandatory for (P-2.1). The only way through is the grandfathered unprefixed path — so
the level prefixes P-2.1 introduced push callers toward the *unprefixed* form, which is the opposite
of the intent. A guard whose documented path is its only failing one gets routed around rather than
obeyed.

Adjacent to `coord-claim-accepts-an-unknown-path-prefix-and-both-guards-read-it-as-nothing` below —
same tool, same field, both about a `paths:` value the two guards read differently. Worth fixing in
one pass, with a self-test that actually RUNS `coord-claim` (P-6.2): checking the files it writes
covers neither this nor that one.

## coord-claim-accepts-an-unknown-path-prefix-and-both-guards-read-it-as-nothing
<!-- status: fixed
     kind: apparatus
     lane: n/a
     area: build
     fixed-in: 7c8b3eda8
     gate: tests/coord/claim-scope-hierarchy.sh -->

**FIXED 2026-08-06 — and it is THREE shapes, not one.** `coord-claim` now refuses any `--paths`
entry outside the vocabulary, at claim time, where the message can name the right one. Measured
against pre-push's own `scope_level`/`scope_path`, every shape one plausible typo away and all
silent:

| written | the guards see | consequence |
|---|---|---|
| `dir:a/b`, `flie:a/b` | `level=mod`, path `dir:a/b` | a path no file can match — an **EMPTY scope**. Reads as a claim to a human, protects nothing. *(the filed shape)* |
| `mod:`, `file:`, `mod:/` | path **empty** | containment is `case $p_path in "$q_path"*`, so it matches **everything** and conflicts with every other claim — the queue stops. The opposite failure |
| `repo:x` | `level=repo`, path ignored | a typo claims the **whole repository** |

So the filed direction was the harmless one, as the entry suspected, and the other two were not
listed. The second is worth naming: a claim that silently blocks every other claim looks like the
mutex working.

**Gate:** the cases went into the EXISTING `tests/coord/claim-scope-hierarchy.sh` rather than a new
file — a second gate would have been a second vocabulary, which is the defect this entry is about.
Both directions: five malformed forms refused, four legal ones (`file:a/b`, `mod:a/b`, `repo:`,
`a/b`) still admitted. Reverted, exactly those five fail and nothing else does.

**The gate caught a defect in its own test first**, which is worth recording: the helper read ANY
exit 2 as a vocabulary refusal, and `mod:`/`repo:` also exit 2 from the `--broad` requirement — two
different refusals sharing one code. It now matches on the message. That is the same ambiguity this
gate exists to catch, arriving inside the test.

**Still open, and the entry named it:** the vocabulary lives in `coord-claim`, `.githooks/pre-commit`
and `.githooks/pre-push` separately. This closes the hole at the entry point, where a typo is cheap
to reject; it does not merge the three definitions, so they can still drift. Same shape as the
`--no-gate` predicate written twice in `bugs-report`, where fixing one copy left the other printing
the old number.

### Original report (superseded 2026-08-06)

`scripts/coord-claim … --paths "dir:v1/runtime/backend/interpreter/src/main/scala/scalascript/interpreter"`
is accepted without a word. The supported vocabulary is `repo:` / `mod:<path>` / `file:<path>` —
`.githooks/pre-push` lines 226-228 — and there is no `dir:`.

Both guards then read the entry as NOTHING:

  * `.githooks/pre-commit` strips `repo:`/`mod:`/`file:` (line 117) and leaves anything else
    literal, so it compares `dir:v1/…` against the staged `v1/…` and never matches. It refused a
    commit that was inside the declared directory.
  * `.githooks/pre-push` classifies unknown prefixes into no bucket, so the overlap check has
    nothing to compare.

THE HARMLESS DIRECTION IS THE ONE I SAW. The other one is not: an unrecognised prefix reads as a
claim to a human and as an EMPTY SCOPE to the overlap guard, so a second agent can claim the same
files and nothing says so. That is the failure `specs/claim-mutex.md` exists to prevent, arriving
through the front door.

FIX: `coord-claim` should REFUSE a `--paths` entry whose prefix is not in the vocabulary, at claim
time, where the message can name the right one. Silently accepting it is what makes it dangerous;
refusing it costs one line of the agent's time.

Note `.githooks/pre-commit` already carries a comment saying pre-push learned `repo:`/`mod:`/`file:`
on 2026-07-30 and "this layer did not", which is the same defect one prefix earlier — the two layers
learn the vocabulary separately, so they will keep drifting until it lives in one place.

## coord-claim-broad-flag-writes-a-claim-its-own-ledger-contradicts
<!-- status: fixed
     kind: apparatus
     lane: n/a
     area: build
     fixed-in: 3325b91e3
     gate: scripts/coord-claim --self-test -->

**Fixed in `3325b91e3`, and this entry was stale for long enough to send the next reader back to
work already done — which is what a `status: open` with an empty `fixed-in` does.** I read it,
claimed it, opened the file to fix it and found the fix already there with a comment naming this
exact cause. Verified rather than taken from the commit message: reproducing the heredoc with the
same variables now writes `paths:` and `broad:` on separate lines, and the claim's `paths:` line is
byte-equal to what the ledger writer records.

**It had no gate, which is why it could have come back in one edit.** `scripts/coord-claim
--self-test` now asserts the property the pre-push guard actually compares, and carries its own
NEGATIVE CONTROL: it re-creates the pre-fix spelling — a literal `\n` inside the quoted heredoc —
and requires that to be detected as disagreeing. A green from it therefore cannot mean "the check
did not look".

### Original report (superseded 2026-08-10)

`scripts/coord-claim <slug> --items … --paths … --broad "dir:…"` writes a claim file whose `paths:`
and `broad:` lines disagree with the LEDGER row it writes in the same run, so the very next push is
refused by pre-push's claim/ledger consistency check:

    ✋ pre-push: a claim disagrees with its LEDGER row.
      'int-module-mutable-registry' paths disagree:
        .claim : dir:… file:tests/conformance/contract-roster.tsv …
    broad: file:tests/conformance/expected/… file:v1/…

The claim file is written by a heredoc containing `${paths}${broad:+\n broad: ${broad}}` — the
`\n` is LITERAL inside a quoted heredoc, so the two fields run together and the ledger writer,
which reads them separately, records a different split.

`--broad` is not optional decoration: `mod:<path>` — the only vocabulary-correct way to claim a
subtree — REQUIRES a `broad:` justification. So the correct way to claim a directory is unusable,
which is exactly why the wrong way above gets reached for.

## bugs-index-gate-rejects-an-all-digit-sha — a real abbreviated sha can look like a CI run id
<!-- status: fixed
     kind: apparatus
     lane: apparatus
     area: build
     fixed-in: 9194a90c7
     gate: tests/e2e/bugs-index-gate.sh -->

**FIXED 2026-08-02** in `9194a90c7` — by another claim, and the entry was left `open`. Verified
here rather than taken on trust: `261607982` (a real abbreviated sha, every character a digit) is
rejected by the pre-fix gate and accepted by the current one, `0 problems`.

The `isdigit` heuristic is gone and the reachability check subsumes it — a CI run id is not an
ancestor of HEAD either, and now it is refused BY NAME instead of by a guess with a false positive.

I walked into this one the day before it was fixed and worked around it with the full 40-char form.
That workaround has been reverted here: the abbreviated sha is back, which is also what re-proves
the fix.

**Found 2026-07-31.** `tests/e2e/bugs-index-gate.sh` rejects a `fixed-in` whose value is all digits:

```python
if not re.fullmatch(r"[0-9a-f]{7,40}", sha) or sha.isdigit():
    problems.append((slug, f"fixed-in `{sha}` is not a commit sha"))
```

The rule is well-reasoned and its comment says why — an 11-digit CI run id matches `[0-9a-f]{7,40}`
perfectly, and three of them were sitting in the file as `fixed-in` values before the migration. But
a git sha can be all digits too: `033928567` (commit `03392856735469545ed5665943a7bd624ef213e0`) was
rejected as "not a commit sha" while being exactly that. `--short=10/11/12` are all-digit as well, so
abbreviating harder does not help; only the full 40 characters happen to contain a letter here.

Worked around by writing the full sha. The proper fix is to stop guessing from SHAPE: the gate runs
inside the repository, so `git cat-file -e <sha>^{commit}` answers the question exactly, and would
also reject a run id — which resolves to nothing — without a heuristic. Shape can distinguish neither
direction reliably; a 40-hex run id would pass today's check, and a valid sha fails it.

Not urgent: the workaround is one longer string, and the rule catches the case it was written for.
Recorded because the failure message says "is not a commit sha" about something that IS one, which
sends the reader to look for a mistake that does not exist.

## corpus-breadth-slice-bloop-server-timeout — Bloop takes over 30 s to start on a 2-core runner
<!-- status: fixed
     kind: apparatus
     fixed-in: 1bd6b0984
     lane: apparatus
     area: build
     gate: none -->

**Found and fixed 2026-07-31**, both by the same method: six `workflow_dispatch` runs of ONE commit,
which turns "sometimes red" into a rate.

```
FAIL corpus-breadth-slice              93.9s
  Starting compilation server
  Exception in thread "main" java.util.concurrent.TimeoutException: Future timed out after [30 seconds]
    at bloop.rifle.internal.Operations$.about(Operations.scala:529)
```

Bloop failed to become responsive inside scala-cli's 30 s window on a hosted 2-core runner. Not the
artifact download — that was a separate defect fixed the same morning (Coursier cache restored
unconditionally); this is the server start itself.

The breadth check runs `--lanes int,js,v2` — no JVM lane at all — so Bloop was pure overhead: the
server started only because scala-cli compiles a script through a build server by default.
`tests/e2e/build-conformance-shard-gate.sh` has always passed `--server=false` for this reason and
has never flaked. `SSC_CONF_WARM_JVM=1` stays: it governs the CHILD `ssc-tools run-jvm` processes,
where warm measured 14.0 s against 27.4 s.

MEASURED, same commit, six dispatches each time:

| | green | red |
|---|---|---|
| before (`cf3fad14f`) | 3 | 3 |
| after (`179932c5b`) | **6** | **0** |

The change was deliberately deferred that morning — locally the flag is noise (15.1/32.2 s with the
server against 23.4/24.0 s without) — with "if it recurs, that is the next thing to try", and NOT
bundled with the Coursier fix so that attribution stayed clean. It recurred in half of all runs, and
the two fixes are now separately attributable: the first removed the download failures, this one
removed the timeouts.

## reaper-dry-run-count-flaps-when-a-sibling-builder-exits — the assertion samples a host-wide count twice

<!-- status: open
     kind: apparatus
     lane: apparatus
     area: build
     fixed-in: -
     gate: tests/e2e/build-ram-budget-gate.sh -->

Seen 2026-08-04 in a local smoke run, 57/58 with only this red:

    ✗ reaper dry-run KILLED builders: expected>=5 got=4
    build-guard: 1 guarded build(s) already running host-wide (host 36864 MB, 6144 MB/slot) — waiting…

Green on a re-run by hand, in the same tree, minutes later.

`build-ram-budget-gate.sh:205-209` counts builders BEFORE the reaper dry-run and again AFTER, then
asserts `after >= before` — "a dry run kills nothing". The count is host-wide, and on a machine
running several agents a sibling's builder exits on its own between the two samples. Nothing was
killed; one process finished. The gate reads a normal event as the failure it was written to catch.

**Sibling of the entry below, not the same defect.** `reaper-aborts-when-a-builder-exits-mid-scan`
is fixed and was about `set -e` aborting the scan when a pid vanished mid-`lsof`; this is the
count comparison that brackets the scan. Same cause in the world — sibling builders come and go —
and the same tell: green by hand, red under load. The comment right above the assertion already
records that an earlier attempt at this case was withdrawn for interacting with the semaphore, so
it is a known-awkward spot rather than an oversight.

What it needs is a count that cannot move for reasons outside the reaper: either restrict the
sample to builders this gate started (its own `SSC_BUILD_SEMDIR` / a marker in the command line),
or assert on what the dry run REPORTS it would kill rather than on a population count.

Left as an observation from a bystander: this is not my gate and a wrong fix here has already cost
one revert.

## reaper-aborts-when-a-builder-exits-mid-scan — set -e turns a routine race into a red gate
<!-- status: fixed
     kind: apparatus
     fixed-in: b3c7fc250
     lane: apparatus
     area: build
     gate: tests/e2e/build-ram-budget-gate.sh -->

**Found 2026-07-31** as `build-ram-guards-guard` failing in the smoke suite with
`reaper dry-run exits 0: expected=0 got=1` and nothing else. Run by hand the reaper always exited 0,
which made it read as host noise.

`scripts/kill-stale-builders` snapshots builder pids with `ps`, then per pid runs `lsof -p <pid>` and
`ps -o time= -p <pid>`. A builder that exits in between makes those exit 1; `pipefail` propagates it
and `set -e` aborts the whole scan. With sibling agents starting and stopping builders continuously,
one is routinely gone. Every such call is followed by a guard written for exactly this case
(`[ -z "$cwd" ] && continue   # exited on its own`) — unreachable, because `set -e` fires first.

Fixed at all three call sites with `|| true`, plus a DETERMINISTIC regression test: a decoy carrying
`bloop` in its command line exits 0.4 s into a 3 s sample window, so the pid is always gone when its
CPU time is read.

Method note, the part worth keeping: two convincing diagnoses were wrong and were discarded by
testing them, not by arguing —
`rss=$(( <empty> / 1024 ))` does NOT abort (bash reads an empty operand as 0), and the fake GNU
`stat` on `PATH` does NOT leak (the override is scoped to one command). The answer came from tracing
the reaper with the stderr the gate had been discarding. **A check that throws away the output of the
thing it checks cannot explain its own failure.**

## corpus-breadth-slice-crashes-scala-cli-on-ci — Bloop downloaded on every cache-hit run
<!-- status: fixed
     kind: apparatus
     fixed-in: 5d397bd26
     lane: apparatus
     area: build
     gate: none -->

**Found 2026-07-31, diagnosed the same day.** Three of fifteen smoke runs died on
`corpus-breadth-slice` with a scala-cli stack trace. Not reproducible locally, and the first two
occurrences were undiagnosable because the runner printed only the last 8 lines of the failing
check — eight JVM stack frames, the message discarded. With the reporting fixed, the cause was
captured on the very next occurrence:

```
Failed to download https://.../ch/epfl/scala/bloop-frontend_2.12/2.1.0/bloop-frontend_2.12-2.1.0.pom
```

**My regression, from the launcher-cache change.** It made `Cache Coursier/sbt` conditional on a
toolchain-cache MISS, reasoning that "sbt and its caches are only needed to BUILD".
`~/.cache/coursier` is not a build cache: it is the artifact cache **scala-cli** uses at RUN time,
and every conformance check in this suite runs scala-cli. On a toolchain-cache HIT — the common path,
the one the caching exists to produce — scala-cli had to fetch Bloop from Maven every run, and that
download fails intermittently. The optimisation aimed a flake at the majority of runs.

Confirmed rather than inferred: two DISPATCHED runs of the same commit, 30608116959 (failed) and
30608120697 (passed), both with `Cache Coursier/sbt` skipped. Same shape, different luck.

Fixed in 5d397bd26 by restoring the cache unconditionally. Deliberately ONE change: adding
`--server=false` to the parent `scala-cli` invocations would remove another Bloop bootstrap path
(`tests/e2e/build-conformance-shard-gate.sh` already does it), but it measured as noise locally —
15.1/32.2 s with the server against 23.4/24.0 s without — and landing both at once would make it
impossible to say which stopped the flake. If it recurs, that is the next thing to try.

Two method notes worth keeping:

  * the flake was caught DELIBERATELY rather than waited for — four `gh workflow run smoke.yml`
    dispatches, one of which reproduced it in ~6 minutes. A 20 %-per-run failure does not need to be
    waited out.
  * a conditional on a cache step deserves the question "what reads this at RUN time, not just at
    build time". The `if:` was added with a one-line justification that was true of sbt and false of
    coursier, and nothing about the shape of the change made that visible.

## git-stash-is-repo-global-across-worktrees — an A/B stash can pop ANOTHER agent's work into your tree
<!-- status: fixed
     kind: apparatus
     lane: apparatus
     area: build
     gate: scripts/wt-stash --self-test
     fixed-in: 1184b6e58 -->

**Guarded 2026-08-11 by `scripts/wt-stash`**, which implements the check this entry itself proposed:
refuse `pop` when `stash@{0}`'s `On <branch>` is not the current branch. It also refuses `push` when
there is nothing to save, because that quiet no-op is the TRIGGER — the entry above traces the whole
failure to it.

`push` asks `git status --porcelain --untracked-files=no`, the same question `git stash push`
answers, which matters: with untracked files counted, the check saw "dirty" while `git stash` saw
"nothing to save" — the exact state it exists to refuse, passed by its own guard on the first run.

**Its `--self-test` caught the guard being silently OFF.** The branch parser was a `sed` script using
`\(On\|WIP on\)`, and `\|` is a GNU extension while macOS ships BSD sed — so it matched nothing
here, and `pop` would have refused EVERY entry including your own. A false refusal is the failure
that teaches people to bypass the tool, which is worse than the hole. It is pure shell now, with a
negative control: an unreadable line must yield NOTHING, so `pop` refuses rather than comparing
against an empty string and passing.

**Found 2026-07-31**, the hard way. `git stash` is **per repository, not per worktree** — every
worktree pushes onto and pops from the same stack. On a repo where several agents each hold a
worktree, that stack is shared state nobody treats as shared.

**How it bites, and it needs no mistake to trigger.** The A/B for "is this contract IMPROVEMENT mine
or somebody else's" is: stash the change, rebuild, re-run, restore. If the change is already
COMMITTED, the tree is clean, `git stash` saves nothing **and says so quietly** — and the following
`git stash pop` then pops whatever is on top of the stack, which is another agent's work-in-progress.

Observed exactly that: `stash@{0}` was `reactive-attr-wip` from
`worktree-agent-afc6a6b4eb2917149`, and popping it dropped 429 lines of a Swift renderer test into a
worktree that had never touched Swift, as an unresolved `UU` conflict. Nothing was lost — the pop
conflicted, so the entry stayed on the stack, and `git reset --hard HEAD` was safe because my own
work was committed. Had it applied CLEANLY, the other agent's stash would have been consumed and
their work would now be sitting in my branch.

**What to do instead** (I am changing my own habit; recording it because it is not specific to me):

- do the A/B on an **uncommitted** change, or
- use `git stash push -- <explicit paths>` and `git stash pop stash@{0}` only after checking
  `git stash list`, or better
- avoid the stack entirely: `git worktree add` a second checkout, or `git checkout <sha> -- <paths>`
  to swap just the files under test.

**Guard worth having, and cheap:** refuse `stash pop` when the top entry's `On <branch>` does not
match the current worktree's branch. Same rule as everything else this week — the tool's answer
("popped") is identical whether it restored YOUR work or somebody else's, and those are very
different states.

## submodule-pointer-not-a-real-commit — main records a `.agents/plugins` commit that does not exist
<!-- status: fixed
     kind: apparatus
     fixed-in: d5149ee32
     lane: apparatus
     area: build
     gate: none -->

**Found 2026-07-30.** `git submodule update` fails for everyone, the working tree stays permanently
dirty, and `scripts/coord-release` refuses to run because of it — so this blocks releasing any claim,
not just the submodule.

```
main records:      fe840592b21ca4e2f9d0aa8d69b5a3a9a2ff5ba0
actually on origin: fe84059ec273af52bef87dcbf5409f69262c5d80   (refs/heads/main of the plugins repo)

$ git submodule update --init .agents/plugins
fatal: remote error: upload-pack: not our ref fe840592b21ca4e2f9d0aa8d69b5a3a9a2ff5ba0
```

Both start with **`fe84059`**. The 40-character SHA was not taken from the repository — it was
extended by hand (or by something) from the 7-character abbreviation and diverged after it. The
bump commit is `8b9470e62` ("submodule: bump plugins to fe84059"), and its message describes exactly
the content of the real `fe84059ec`, so the intent is not in question — only the recorded bytes are
wrong.

**The reusable lesson, and it is about the command rather than this incident.**
`git update-index --cacheinfo 160000,<sha>,<path>` accepts **any** 40-character string and does not
check that the commit exists, in the submodule or anywhere. That is how a fabricated SHA reaches
`main` silently. It is the standard way to bump a pointer without a submodule checkout — I used it
three times myself today — and it is safe only when the SHA comes from
`$(git -C <submodule> rev-parse HEAD)`. An abbreviation is fatal.

**Gate: `tests/e2e/submodule-gitlink-resolves.sh`** (added 2026-07-30). Every mode-160000 entry in
the INDEX is probed with `git fetch --depth 1 <url> <sha>` into a scratch repo. 2.2 s for the whole
run including its self-test.

Two alternatives were rejected for a measured reason each, so nobody re-litigates them:
`git ls-remote <url> <sha>` matches only REF TIPS, so a legitimate pointer at a non-tip commit would
fail it; and `git -C <sub> cat-file -e` needs the submodule checked out, which **CI does not do** —
no workflow passes `submodules:` to `actions/checkout`, so an object-store check would have verified
nothing there while looking green. The fetch probe is the same request `git submodule update` makes,
so it fails exactly when the real operation would.

Verified against this incident's own two SHAs, in both directions:

```
fe840592b…  ->  fatal: remote error: upload-pack: not our ref     (the fabricated one)
fe84059ec…  ->  fetched                                           (the real one)
```

and mutation-checked by REPRODUCING the outage — `git update-index --cacheinfo 160000,fe840592b…`
puts the tree back into the 2026-07-30 state, and the gate exits 1 naming the path, the SHA, the
remote, and the repair (derived from `git -C <path> rev-parse origin/HEAD`, never typed). Its
`--self-test` proves the probe can fail before trusting it to pass: it flips one hex digit of the
recorded SHA — well-formed, only its existence differs — and asserts the refusal, then asserts the
real SHA is accepted.

**The pointer itself was repaired by `d5149ee32`**, so the dirty-tree/`coord-release` blockage is over.

**NOT DONE, handed over:** wiring the gate into a workflow. `scripts/smoke-ci` (the push path) is held
by the `smoke-launcher-cache` claim; at ~2 s it belongs there, and the current smoke budget has room
(a green run measured 259.3 s of 330 s). Until it is wired, the gate exists and passes but nothing
runs it — which is the same hole this entry is about.

## coord-release-drops-the-evidence-level — the tool swallows its own flags, and AGENTS.md requires that field
<!-- status: fixed
     kind: apparatus
     fixed-in: f7e1e687e
     lane: apparatus
     area: build
     gate: tests/coord/coord-release-evidence-level.sh -->

**Found 2026-07-30** on the first real use of `scripts/coord-release`.

```
$ scripts/coord-release v2js-unit-pattern --level 3 --note "contract green"
$ git log -1 --format=%s
release-claim: v2js-unit-pattern — --level [skip ci]
```

`--level` landed in the message as a literal; the `3` and the `--note` vanished. Cosmetic on its own
— but **AGENTS.md §4c requires a release-claim to say which of the three evidence levels it has**,
and never to write "green" for a run that produced no verdict. A tool that silently drops that field
turns a required statement into an optional one, and the release record is where the next agent looks
to decide whether a thing is trustworthy.

Suggested shape: parse the flags, or **refuse without `--level`**. The second is stronger — it makes
forgetting impossible rather than merely discouraged.

**FIXED 2026-07-30 — both, since the second needs the first.** `coord-release` now parses
`--level <1|2|3>` and `--note "<text>"`, REQUIRES `--level` (the refusal cites §P-6.7 and prints the
three levels), fails CLOSED on an unknown flag instead of decorating the message with it, and keeps
the old positional-note form working so existing habits do not break. The level lands in a fixed
position — `release-claim: <slug> [evidence: level N] — <note>` — so the record is greppable:
`git log --grep='release-claim:'` now shows every release with the evidence it claimed.

Gate: `tests/coord/coord-release-evidence-level.sh`, a lab with a fake origin running the REAL
script (the `coord-claim-runs.sh` idiom — a text-inspecting test in this family once passed against a
tool that aborted on line 128). Against the OLD tool it fails **14 of its checks** and reproduces the
report's message verbatim: `release-claim: flagform — --level [skip ci]`.

⚠️ Worth copying: the first version of that gate had **two checks passing for the wrong reason**.
`--level 9 is refused` and `an unknown flag is refused` were green against the old tool — not because
it refused anything, but because the previous case had already consumed the claim and it exited 2
with `no such claim`. Same exit code, different reason, indistinguishable from a real pass. Found by
running the gate against the old tool and reading WHICH checks passed rather than counting them; the
fix is a fresh claim per case, and the failure count went 10 → 14.

## module-sprint-item-written-by-nobody — two of the three artefacts are automated, so the third is forgotten by construction
<!-- status: open
     kind: apparatus
     lane: apparatus
     area: build
     gate: tests/coord/board-generated.sh -->

**Found 2026-07-30**, in the same session that automated the second artefact.

`specs/work-tracking-layout.md` says a task appears in THREE places in one commit: the claim, the row
on the root board, and `[~]` in that module's `SPRINT.md`. `coord-claim` writes the claim and the
board row; `scripts/board` now derives the board from `.work/active/`. **Nobody writes the module
item** — `coord-release` even prints "STILL BY HAND: mark the finished item `[x]` in its module
SPRINT.md", and checking two of my own finished claims, the `[~]` had never been written either.

This is the same argument that justified automating the board row: a step that depends on every agent
remembering it is a wish, not a rule. Two options, and the second looks better on this project's
evidence — generated state has not drifted here, hand-maintained state has:

1. `coord-claim` / `coord-release` write the module item too (the module is derivable from
   `tests/fixtures/modules.tsv`);
2. the module sprint becomes DERIVED from the claims, exactly as the root board now is, and `[~]`/`[x]`
   stop being written by hand at all.

**MEASURED 2026-08-08, and it is worse than "nobody writes it": nobody CLEARS it either.** Fourteen
items sit at `[~]` across the module sprints, and **not one is held by a live claim**. At least eight
say DONE in their own text while still marked in progress — `J-2 — DONE except…`, `J-3 — DONE:`,
`J-3b DONE`, `J-3c DONE`, `J-3d DONE`, `J-8 DONE`, `J-9 DONE`, `K38 DONE`. So `[~]` does not mean
in-progress; it means "someone wrote a line here once".

**Option 1 as written is not available, and the reason is a deliberate guard.** `coord-release` runs
in the SHARED checkout, where `.githooks/pre-commit` refuses any staged path outside `.work/` — the
script says so in its own comment, and that confinement is what keeps a release to one reviewable
shape. A release commit therefore cannot carry a module `SPRINT.md`. Writing the item would mean
either weakening that guard or doing it in a second commit from a worktree, which is the manual step
again under another name.

**DONE instead, because it costs nothing and removes the wish half of the problem:** `coord-release`
now NAMES the line — `STILL BY HAND: mark it [x] — scripts/SPRINT.md:56` — by searching the module
sprints for a `[~]` line matching the slug or any of the claim's `items:`, and when there is none it
says *"no module SPRINT item names this slug or its items — nothing to mark"*. That last case is the
common one: work arriving from a `BUGS.md` entry has no sprint item at all, and the old blanket
instruction fired anyway, every single time. An instruction that is usually unactionable is how a
step becomes a wish — this is the same argument the entry already makes about the board row, applied
to the message rather than to the writing.

Verified in three directions: slug matches an item, `items:` matches an item while the slug does not,
and neither matches. The `items:` are read while the claim file still exists, since `coord-release`
`git rm`s it well before this point.

**The fourteen were audited one by one 2026-08-09, and "fourteen stale" was my word, not the
finding.** Nine were done and are now `[x]`. **Five were never stale at all** — each names its own
remainder in its own text, which is `[~]` meaning exactly what it should:

| still `[~]` | what it says is left |
| --- | --- |
| `ci-crossbackend-differential-runtime` | "sharding the test phase is still open" |
| `smoke-budget-drift` | "Step 2 waits for CI samples" |
| `VC-2c` | adding `Long` to `knownTyName` "wants its own measurement and its own A/B" |
| `J-0` | "**Still open in J-0:** the four-row baseline below" |
| K38/K40 | "Only **Array-env for speed** … remains here" |

So the marker is not meaningless; it is UNRELIABLE, which is a weaker and more accurate claim. What
the audit does support is the original one: nothing clears a marker when the work finishes, and eight
of the nine said DONE in their own first line while still reading as in-progress.

**Each of the nine was verified against the artefact it names, not closed on its prose** — a
distinction this repo has had to reopen entries over. `v2/src/Jit.scala` and its call sites (J-1),
`JitBackend` (J-2), `compileUnit` (J-3), `JitSite.selfName` (J-3b), the static `callees` table with
`GETSTATIC` (J-3c), `JvmByteGen.pureDefsOf` (J-3d), `SSC_V2_JIT_SYNC` (J-9), `ssc-tools lint-jit -v2`
answering `64 defs compile, 0 refused` (J-8), and `examples/enums.ssc` printing `North -> South` (E4).
Two of those checks failed on my FIRST attempt for reasons that were mine: I grepped `Jit.scala` for
J-3c's `callees`, which lives in `JvmByteGen.scala`, and I typed `--v2` where the usage says `-v2`.
Both would have read as "not done" if I had stopped at the first result.

**Still open**, because the mechanism is untouched: nothing stops a tenth marker going stale the same
way, and the audit is a one-off. Option 2 — deriving the module sprint from the claims, as the root
board already is — is the one this project's evidence supports, and it would make both the writing
and the clearing impossible to forget rather than merely easier to remember.

**Gate named 2026-08-14: `tests/coord/board-generated.sh`** — the gate that already proves the ROOT
board is derived rather than hand-written, and option 2 makes the module sprints the same kind of
artefact. Extending it is the whole point: two derived boards checked by one gate, rather than a
second gate over the same population, which this repo has paid for as a second decision site.

**Done when** a module `SPRINT.md` marker is derived from `.work/active/` and that gate fails on a
hand-written one. The audit above is explicitly NOT the fix — it is a one-off, and a tenth marker
can go stale the same way tomorrow.

## coord-bookkeeping-needs-a-claim — the per-module split made FILING A BUG require a claim, and mid-migration nobody could file at all
<!-- status: open
     kind: apparatus
     lane: apparatus
     area: build
     gate: tests/coord/claim-scope-hierarchy.sh -->

**Found 2026-07-30** by hitting it twice in a row, in a session that produced fourteen entries.

The claim scope guard exempts the shared bookkeeping files by BARE NAME — `SPRINT.md`, `BACKLOG.md`,
`BUGS.md`, `CHANGELOG.md`, `MILESTONES.md`, `README.md`. The per-module split (`5612b3d0c`) moved every bug
entry into `<module>/BUGS.md`, and those paths do not match that list. Consequences, both measured:

1. **Filing any bug now needs a claim widening.** `git add v1/runtime/backend/js/BUGS.md` is refused as
   "outside what claim … declared", so recording a finding costs a claim round-trip that recording it in
   the old flat file did not.
2. **During the migration itself, no js/jvm/v2 bug could be filed by anyone**, because the migrating claim
   held every `BUGS.md` in the repo at once. Two findings had to be parked in `specs/` instead
   (`specs/json-number-policy.md`, `specs/v2-char-is-an-int.md`) and routed into entries hours later, after
   that claim released.

**Why it reads as an oversight rather than a decision:** the guard prints *"SPRINT.md / BACKLOG.md /
CHANGELOG.md / BUGS.md … are SHARED and are never an overlap — if one of those is in the list above, this
hook has a bug."* It says the intent plainly; the pattern just no longer matches where the files live.

**Two ways to close it:**
1. match on the BASENAME (or `*/BUGS.md`), which restores the stated intent for the new layout;
2. or state in `specs/work-tracking-layout.md` that bug filing is claimed work now, and accept the
   round-trip — defensible, but it should be written down rather than discovered.

A related shape, worth deciding together: `SPRINT.md` is exempt as SHARED, yet the shared-main pre-commit
guard refuses anything outside `.work/`, so `coord-claim`'s own "claim and board row in ONE commit" contract
was unimplementable from the main checkout. That was found the same day (`bfbc42fe6`, backed out in
`0c8237e60`) and is the same disagreement between two guards about what counts as bookkeeping.

**Gate named 2026-08-14: `tests/coord/claim-scope-hierarchy.sh`** — the scope guard's own lab, wired
into `scripts/smoke-ci` as `claim-scope-hierarchy`. Option 1 is a basename match in the guard, so
the case is one row there: staging `v1/runtime/backend/js/BUGS.md` under an unrelated claim must be
ALLOWED, while staging a non-bookkeeping file under that claim must still be refused — both
directions, because an exemption that swallows the refusal is worse than the round-trip it removes.

**Done when** that pair of cases passes, or — if option 2 is chosen instead — when
`specs/work-tracking-layout.md` says bug filing is claimed work and the guard's own message stops
claiming otherwise. Either is a close; leaving the guard's printed intent contradicting its
behaviour is not.

## coord-claim-second-positional-overwrites-slug — an unquoted `--items A B` claimed under a name the caller never typed
<!-- status: fixed
     kind: apparatus
     lane: apparatus
     area: build
     fixed-in: unrecorded
     gate: tests/coord/coord-claim-runs.sh -->

**FIXED 2026-07-30**, reported by Sergiy after I hit it.

`--items` and `--paths` each take exactly ONE argument — the usage line documents them as quoted lists
(`--items "<id> …"`). Written unquoted, the extra words stay positional, and the old catch-all was:

```bash
*) slug="$1"; shift ;;
```

No "already set" check, so the LAST bare word won. `coord-claim v2-diverge-dsl-parsers --items A B`
therefore assigned `slug=v2-diverge-dsl-parsers`, then overwrote it with `B` — creating
`.work/active/B.claim` with `items: A`, printing `✓ claimed B`, and exiting 0.

**Why it is worth a fix rather than a note in the usage text.** The tool did the right-looking thing: a
claim WAS created, the push succeeded, the ledger row matched. The only signal was a slug the caller never
typed, in a line that normally scrolls past. That is the same failure shape as the `$root` outage in
`bfbc42fe6` — quiet wrongness — except this one leaves a claim under the wrong name, which is precisely
what the mutex is supposed to make impossible to get wrong by accident.

**Fix:** a second positional argument is refused, naming both candidates and showing the quoted form.

**Gate:** `tests/coord/coord-claim-runs.sh` gained five checks — refused, no claim under the stray word, no
half-written claim under the intended slug, the message mentions quoting, and it names both candidates.
Proven fail-first by pointing the gate's own `COORD_CLAIM` override at the pre-fix copy: four checks fail,
and the decisive one is `it did NOT create a claim named after the stray word` -> **got=yes**, i.e. the lab
reproduces the incident rather than merely describing it.

## install-sh-exits-0-when-sbt-project-load-fails — a build that produced nothing reported success
<!-- status: fixed
     kind: apparatus
     lane: apparatus
     area: build
     gate: tests/e2e/install-sh-reports-failure-gate.sh
     fixed-in: bdc665aa6 -->

**FIXED 2026-08-06 — and it was still LIVE, which took three measurements to establish.**

The original path stopped reproducing, and that nearly closed this as stale. Re-measured on this
host (sbt runner 2.0.1, project sbt 1.10.7): **sbt now exits 1** on a project that will not load. It
still prints `Project loading failed: (r)etry, (q)uit, (l)ast, or (i)gnore?` and still takes the
default on EOF — only the status changed. `set -euo pipefail` then does the rest, so
`./install.sh --dev` over a broken `build.sbt` exits 1 today. **Nothing in this repo earned that.**

The first reproduction also passed for the WRONG reason and would have been a false all-clear: in a
fresh worktree `bin/lib` does not exist, so the artifact-existence checks caught the failure. The
entry's own text names the state that matters — "the next command runs whatever was in `bin/lib`
before" — so the faithful setup is a broken build.sbt on top of a build that already SUCCEEDED. Built
that, and it still exited 1.

**What settled it was asking the question without sbt in the way.** With a stub `sbt` on PATH:

```
sbt fails loudly  (exit 1)   ->  install.sh exit 1     ok, via set -e
sbt fails QUIETLY (exit 0)   ->  install.sh exit 0     REPORTED SUCCESS  <- the defect, alive
```

The hole was never sbt's exit code; it was that install.sh verified its output EXISTS and never that
the build RAN. Stale artifacts from any previous build satisfy existence. So the defect was one sbt
version away from returning, and nothing would have noticed.

**Fix:** `cli/installBin` rewrites `bin/lib/.build-stamp`, so its mtime changing is evidence the task
ran. install.sh now captures it before sbt and refuses to report success if it did not move. Only
the mtime is used, never the CONTENT — and the control proves why: a real rebuild at an unmoved HEAD
writes the SAME sha, so a content check would have failed a good build. (The stamp's sha was
superseded by `scripts/launcher-input-digest` for the "is this stale" question; that decision is not
reopened here.) `stat` is read through a helper because BSD spells it `-f %m` and GNU `-c %Y`, and
CI is Linux while this host is macOS.

**Gate:** `tests/e2e/install-sh-reports-failure-gate.sh`, both rows, 2 s — it uses the stub rather
than a real build. The second row is the regression guard proper: it holds even when sbt lies,
because that is exactly what sbt used to do.

### 2026-08-14 — THE GATE STOPPED REACHING THIS ENTRY THREE DAYS AFTER IT WAS WRITTEN

**The fix is intact. The gate that guards it was measuring a path install.sh no longer takes**, and
that is the finding. Turned up by the orphan drain (`tests/BUGS.md orphaned-e2e-gates-52`): this
gate is invoked by nothing, so five days passed with nobody looking at it.

The toolchain cache landed in `install.sh` on **2026-08-09**, three days after the gate. On a
`scripts/launcher-input-digest` HIT it restores `bin/lib` and **skips `sbt cli/installBin`
entirely** — and the witness this entry is about lives on the build path. Measured today in a
throwaway clone, three arms, one variable:

| arm | what install.sh did | exit | what the gate concluded |
|---|---|---|---|
| cache ON, stub sbt exits **0** | cache HIT, sbt never called | **0** | "the defect is BACK" — false |
| cache ON, stub sbt exits **1** | cache HIT, sbt never called, then died at the LATER `sbt-plugin publishLocal`, which the stub also intercepts | 1 | "row 1 holds" — true by accident, about a different command |
| cache OFF, stub sbt exits **0** | built, **witness fired**: `cli/installBin did not run` | 1 | the subject, intact |

So one row was a false RED and the other a false GREEN, from **one** cause, and neither row had
anything to say about the witness. The exit code alone cannot separate the two situations; the
mechanism string can, which is the general lesson and is now enforced.

**Three changes in the gate, and the second is the one worth copying.**

1. `SSC_TOOLCHAIN_CACHE_OFF=1` on both rows — the build path IS the subject.
2. **Reachability is asserted, not assumed.** Each row first requires the run to have printed
   `Staging ssc …` and not `cache HIT`; otherwise it reports *"never reached the build path — this
   gate measured nothing"* and fails. A gate that cannot reach its subject must say so rather than
   hand back whatever exit code it happened to get.
3. Each row asserts the MECHANISM, not the sign of the exit code: row 2 requires the witness's own
   words, so "exited 1 for some other reason" is now a failure. That is precisely what row 1 was
   doing under the cache.

**And the gate was destructive in the shared checkout, which nothing declared.** The HIT path does
`rm -rf bin/lib` followed by a 176 MB restore — twice per run, in whichever tree the gate is invoked
from. With the cache off install.sh touches neither, and the gate now asserts it at the end: the
`.build-stamp` mtime must be unchanged, i.e. *the toolchain this gate ran against is the toolchain
it left behind*. Re-running the pre-fix form makes that check fire, which is how it was confirmed.

**Both directions verified, on the real install.sh rather than a fixture:**

- fixed gate, real tree → both rows PASS in 2 s, each naming the mechanism it went through;
- the cache-off flag removed → both rows refuse (`never reached the build path`) **and** the
  toolchain-unchanged check fires;
- the witness deleted from `install.sh` (`if [ -z "$_stamp_after" ] …` → `if false`) → row 2 goes
  RED with the original defect's exact signature, row 1 still passes. The gate covers the defect,
  not merely the environment.

### Original report (superseded 2026-08-06)

**Found 2026-07-30** while adding the stale-build stamp. I put Scala 3 syntax (`then`, braceless
`catch case`) into `build.sbt`, which is **Scala 2.12**. sbt refused to load the project:

```
[error] [.../build.sbt]:1874: illegal start of simple expression
[warn] Project loading failed: (r)etry, (q)uit, (l)ast, or (i)gnore? (default: r)
```

It got EOF on that prompt, and **`./install.sh --dev` exited 0** — sbt returned 0 in that
situation at the time; it returns 1 now. No launchers were written, no
`bin/lib` at all — and the script said success.

I noticed only because I grepped the produced artifact instead of trusting the exit code. Anyone who
trusts it gets something worse than a failed build: `bin/ssc` is TRACKED, so a checkout still has a
launcher, `git status` stays clean, and the next command runs **whatever was in `bin/lib` before** —
i.e. a silent fall back to an older toolchain, which is precisely the class of failure the stamp was
being added to close.

**Where to look** (the diagnosis was right about the second half and wrong about the first):
`install.sh --dev` invokes sbt and does not propagate its exit status; the
interactive `Project loading failed:` prompt on EOF is the specific shape that escapes, because sbt
exits 0 after choosing the default. Two things to fix, not one: pass `-batch` (or set
`onLoadFailure`) so the prompt cannot appear, AND check the status. Better still, assert the artifact
exists afterwards — `bin/lib/.build-stamp` now makes that a one-line check, and an exit code is a
claim while a produced file is evidence.

## uniml-root-standalone-target-cache-collision — prescribed root→standalone verification corrupts UniML compilation state
<!-- status: fixed
     kind: apparatus
     lane: apparatus
     area: build
     fixed-in: 3e52e6909 -->

**Status:** FIXED in `3e52e6909` (found 2026-07-28 while qualifying UPR-1 after the root
JVM/Scala.js suites were green).

**Reproduction.** In one UniML worktree, first run the root build:

```bash
scripts/sbtc ";unimlYaml/test;unimlYamlJs/test"
```

Then run the standalone build exactly as required by the production gate:

```bash
cd uniml && sbt -batch test
```

The standalone build reuses target directories under `uniml/<module>/.jvm|.js/target` but loads
them through a different sbt build definition. The observed run failed with hundreds of false
`Not found: Limits` / `Not found: VmToken` compile errors plus JVM
`NoClassDefFoundError: scalascript/uniml/SourceId$` and
`NoClassDefFoundError: scalascript/uniml/VmInstruction`, even though the same root projects had
just passed. This is a real-harness failure; no clean was inserted between the two prescribed
gates.

**Impact.** Root and standalone verification are not composable in the documented order. A clean
workspace can hide the collision, so CI/local evidence depends on which build touched the shared
incremental products last.

**Fix acceptance.** Reproduce and isolate the exact target/analysis collision, then give the root
and standalone builds disjoint products (or prove their module definitions byte-identical) without
requiring `clean`. Run root YAML/Markdown JVM+Scala.js tests followed by standalone `sbt test`,
then repeat the root slice and standalone test a second time; all four transitions must be green.

**Root cause and verification.** The root build and `uniml/build.sbt` described the same nine
cross-project source trees with incompatible Zinc/export settings while writing into the same
`target` products. The standalone projects now use nine distinct `target/standalone` namespaces,
and their aggregate test is guarded by a fail-closed path-isolation check. Injecting one shared
target makes that check fail with the complete offending path set. The no-clean
`scripts/verify-uniml-dual-build` transition gate ran
root YAML/Markdown JVM+Scala.js → standalone aggregate twice; all four transitions passed and the
second round compiled zero sources. Independent review accepted the isolation and transition gate.

## ci-status-sha-misses-commits-covered-by-a-later-tip — a code commit can be TESTED and still report "no run"
<!-- status: fixed
     kind: apparatus
     lane: apparatus
     area: build
     fixed-in: unrecorded -->

**Status:** **FIXED 2026-07-28** by opus (`ci-status-descendant-fallback`). Found 2026-07-28 by
`ci-bookkeeping-floods-verdicts` while verifying the `paths-ignore` change on live traffic; not
caused by it, only made easier to notice.

**Fix.** When the exact-SHA query finds nothing, `scripts/ci-status` now looks for the NEAREST run
whose head is a descendant of the requested commit — real `git merge-base --is-ancestor`, not string
matching — and reports its verdict labelled `(descendant)` with a `covered by: <sha>` line. The label
is in the HEADLINE, not a trailing note: "tested as part of a later tip" is genuinely weaker evidence
than "tested alone" and must never read as the latter. `--exact-only` restores the strict answer.

**Verified on the exact commit this entry names.** `scripts/ci-status --sha 2adeef250` went from
`CI UNKNOWN` to a real verdict, `covered by d11a7746…`.

**A second defect found by running it — in my own output.** That covering run is
`completed/cancelled` with **ZERO jobs** (queue eviction), and the report said
`missing required job: Lint Markdown` ×4 — which reads as "the workflow dropped its jobs" and sends
the reader after a config problem that does not exist. A run with no jobs now says so plainly:
`run completed/cancelled with ZERO jobs — it never started one, so it is evidence of nothing`, and
points at `ci-runs-cancelled-under-churn`.

**Gate.** Four new cases in `tests/e2e/ci-status-guard.sh`, using REAL commits from this repository
because the ancestry check is real git. The one that makes it a gate rather than a demo is the
NEGATIVE: when the only available run is an ANCESTOR, the answer must stay `CI UNKNOWN` — otherwise
the fallback would accept any recent run as evidence for anything. Plus `--exact-only` must return
UNKNOWN where the fallback returns GREEN, which proves the other verdicts came from the fallback and
not from some unrelated path, and a zero-jobs case asserting the run is RED **without** the
misleading missing-job list. A/B: against the previous script `desc-green` fails with
`expected exit=0 got=2` — exactly the false UNKNOWN this entry describes.

**What happens.** GitHub creates ONE run per push, attributed to the push's TIP commit, and applies
`paths` filtering to the push as a whole. So a push of `code-commit` followed by `docs-commit`
produces a single run named after the DOCS commit — the code was fully tested, but
`scripts/ci-status --sha <code-sha>` finds nothing and reports `CI UNKNOWN`.

**Observed.** `100c20676` (md-only) → `2adeef250` (`specs/v2.2-p6.5-fsub.sh` + `.ssc`, real code) →
`d11a77460` (BUGS.md only) landed as one push. Exactly one CI run was created, on `d11a77460`.
Reading the per-commit table naively says "a docs commit triggered a run and a code commit did not",
which is wrong on both halves and was my first reading of it.

**Why it matters.** AGENTS.md §4c tells you to verify the newest CODE commit by exact SHA. Under
batched pushes that instruction can report UNKNOWN for a commit that was actually covered, which
pushes people toward re-running or toward accepting weaker evidence. The green-descendant ladder
already handles it — a descendant run covers its ancestors — but `ci-status --sha` does not say so.

**Fix direction.** Have `scripts/ci-status` fall back: when no run exists for the exact SHA, look
for the nearest run whose head is a DESCENDANT of it and report that explicitly as
"covered by <sha> (descendant)" rather than UNKNOWN. That is the evidence ladder AGENTS.md already
describes, made mechanical instead of manual.

## ci-runs-cancelled-under-churn — most commits get no verdict, and `cancelled` is RED
<!-- status: fixed
     kind: apparatus
     lane: apparatus
     area: build
     fixed-in: 2f7052ba3 -->

**VERIFIED 2026-08-02 against this entry's own falsifiable criterion**, which is why the criterion
was worth writing down: *"`gh run list --workflow=ci.yml` should show at most one `in_progress` plus
one `queued` run for `refs/heads/main`, and completed runs should start appearing again."*

Measured over the 40 most recent `ci.yml` runs on `main`: **40 of 40 `completed`** — zero `queued`,
zero `in_progress`, 29 success / 11 failure. The 55-deep queue is gone and every run reaches a
verdict. The entry said to reopen it if the queue were still tens deep an hour later; it is not.

**The same measurement surfaced something else, and it is filed rather than mentioned in passing:**
`smoke.yml` — the per-push gate — had failed on **26 of its last 40 main runs**, from two causes in
sequence. The first was its `bugs-index` self-test, which cannot pass under CI's `fetch-depth: 1`
(it plants a "not a commit sha" defect that a shallow clone can only check for SHAPE), fixed in
`f078f9a35`. The second was a roster/baseline digest mismatch that made `freeze-consistency` red for
everyone, repaired in `25843fb0e`. Neither is this entry's mechanism — a queue that never drains and
a gate that fails fast look nothing alike, and conflating them would have closed this on the wrong
evidence.

**Status:** **MECHANISM ESTABLISHED, fix landed 2026-07-27** (opus, `ci-queue-concurrency`,
`2f7052ba3`) — **verification deliberately still pending, see the criterion at the end.** Originally
recorded the same day while waiting on exact-SHA CI for four landed batches, without guessing a cause.

**Re-measured ~21:20Z, and it is worse than the first observation.** Of the last 60 CI runs **ZERO
had completed** — 55 `queued`, 5 `in_progress` — with the oldest queued run waiting since 18:13Z,
over three hours. Across the last 100 runs of all workflows, **16 CI runs were `in_progress`
simultaneously**.

**Mechanism.** 16 concurrent runs × 4 jobs ≈ 64 concurrent jobs, above the account's concurrent-job
budget. `ci.yml` had no `concurrency:` block, so nothing superseded anything: every push started a
fresh run, the budget stayed saturated by `sbt` jobs (`timeout-minutes: 300`), and newer runs queued
behind them until evicted or cancelled. The `cancelled` outcomes are queue eviction, not flaky gates.

**Fix.** `concurrency: {group: ci-${{ github.ref }}, cancel-in-progress: false}`. The `false` is the
point: a run already EXECUTING is never killed, so a long `sbt` job still reaches its verdict; what
GitHub cancels is the previously PENDING run in the group. The queue collapses from 55 deep to at
most one running plus one pending per ref, and the newest commit is always next to run.

**Cost, stated plainly:** intermediate commits get no verdict of their own — but they get none today
either, so this trades a silent red for an explicit supersede, which is what makes the
green-descendant ladder in AGENTS.md §4c usable.

**⚠️ NOT VERIFIED YET, and this must not be quietly dropped.** A workflow-level `concurrency` block
cannot be exercised locally; the file was checked structurally (top-level keys, block shape, no tabs)
and nothing more. **Falsifiable success criterion:** `gh run list --workflow=ci.yml` should show at
most one `in_progress` plus one `queued` run for `refs/heads/main`, and completed runs should start
appearing again. If the queue is still tens deep an hour after this landed, the mechanism above is
WRONG and this entry must be reopened, not closed.

**Interim observation, ~10 min after landing (recorded because a promise to verify is worthless
unless the check is actually written down).** `main` runs now read `pending: 1, cancelled: 1,
queued: 38`. The `pending` + `cancelled` pair is the new group engaging exactly as described — one
run held pending, a previously pending one superseded. The 38 are the PRE-EXISTING backlog: GitHub
runs each with the workflow definition from its own commit, so runs created before `2f7052ba3` keep
the old unbounded behaviour and must drain on their own. **This is not yet the success criterion
above** — that needs the backlog gone. They were deliberately NOT mass-cancelled: any one of them
may be the exact-SHA verdict another agent is waiting on, and destroying a sibling's evidence to
make my own fix look verified is the opposite of the point. Draining them is the CI owner's call.

**Fresh exact-SHA instance, Corpus Contract E7.** Run `30307158170` for final
SHA `9975a0c0c` was superseded and completed `cancelled` with zero jobs. Per
AGENTS.md this is RED/no verdict, never neutral: E7 released on evidence level
3 (its named local classifier, real-corpus, conformance, routing, and doc gates),
not on a fictional CI green.

**Fresh exact-SHA instance, V-6b.** Run `30308711327` for final SHA
`42c4f487f` was superseded and completed `cancelled` with zero jobs. This is
again RED/no verdict. V-6b releases on evidence level 3: focused unit tests
11/11, exact no-fallback and product parity gates, backend isolation,
fallback visibility, affected conformance 1/1, and full-repository markdownlint.

**Observed on `ci.yml` / `main`, 2026-07-27 ~16:59Z:**

- **6 of the last 14 runs ended `cancelled`** (`b672b0d41`, `bb78a98ea`, `309011c05`, `1ad34d8ec`,
  `4e0c17737`, `32214b697`, `adba80d36`). In the one inspected (`30286193700`) all four jobs
  cancelled together, ~8.5 min in — not an instant supersede.
- The queue was **8 runs deep**; the three oldest (`1ebb8bf1e`, `a02036602`, `e0529a53b`) had been
  `in_progress` for **80+ minutes**.
- **`ci.yml` has no `concurrency:` block**, so GitHub is not auto-cancelling superseded runs.
  `pages.yml` is the only workflow with one and it is `cancel-in-progress: false`.

**Why this is a bug and not just slowness.** `MILESTONES.md` already records that a gate reporting
`cancelled` is **RED, not neutral**. `AGENTS.md` §4c makes an exit-0 exact-SHA run the *only* green
verdict and says pending/unknown keeps a claim open. With four agents pushing, that verdict is
effectively unreachable: a claim either stays open forever or is released on evidence the rule does
not accept. Both happened today. This is the same shape as the entries in
`feedback_measurement_must_compare_not_prejudge` — the apparatus that is supposed to establish trust
is the thing that is broken, and it fails *quietly* (a cancelled run looks like "not red").

**Interim practice** (what this session actually did, stated so it is auditable rather than implied):
verify via a **green descendant** run plus `gh run view --json jobs` for the specific job that could
catch the change, and say in the release-claim which evidence exists. Example: for the scljet batch
the run was still `in_progress` while its `Conformance Suite` job already read `success` — that is
the job that would catch a scljet regression, and the 300-min `sbt` job is the long pole.

**Candidate fixes for whoever owns CI** (each needs the mechanism confirmed first): add a
`concurrency` group with `cancel-in-progress: false` so queued runs are not lost; or split the
300-min `sbt` job so a verdict arrives before the queue laps it; or gate the fast jobs
(Conformance / Validate / Lint) as the per-push verdict and run `sbt` on a schedule.

## swiftui-real-fixture-swift-without-swiftui — Linux `swift` is not a SwiftUI capability
<!-- status: fixed
     kind: apparatus
     lane: apparatus
     area: build
     fixed-in: c278b4b37 -->

**Status:** DONE (2026-07-21, `c278b4b37`; exact containing SHA `1f5e55b44`, run
`29805732016`; found and confirmed by codex while closing F7 exact CI). Earlier run `29775034983`
at `1fbe993b4` completed every preceding job/gate, then failed exactly one named test:
`SwiftUiRealFixtureBuildTest` tried to build the macOS package on Ubuntu and `swiftc` reported
`ContentView.swift:3:8: error: no such module 'SwiftUI'`. The final aggregate was 619 succeeded,
1 failed, 22 canceled; this was the final full-CI blocker.

**Root cause / repro.** The test's `swiftAvailable` predicate checks only `swift --version`. GitHub's
Linux image has a working Swift compiler but no Apple SwiftUI SDK, so that proxy passes and the
macOS-only build starts. The real observable is whether the compiler can typecheck `import SwiftUI`:
write a temporary `.swift` probe and run `swiftc -typecheck <probe>`, capturing exit/stdout/stderr.

**Fix / verification.** `SwiftUiRealFixtureBuildTest` now typechecks a temporary `import SwiftUI`
probe with `swiftc` and reports exit/stdout/stderr when the real package build is canceled. A direct
impossible-module regression proves the probe compares module availability. The deliberately-invalid
staged `.ssc` regression remains outside that gate, so Linux still catches generated-Scala failures.
The focused suite is 3/3 on macOS (including the real `swift build`); with a Linux-shaped `swiftc`
that cannot import SwiftUI it is 2 passed / 1 named canceled, and the generated-Scala regression still
runs. The complete v2 gate is 644 ok / 0 FAIL and shared `v2-*` conformance is 11/11. Exact run
`29805732016` for containing SHA `1f5e55b447f6a2e28c2fd3efe2e5599d99f6a8bd` completed all four
jobs successfully, including the full Linux sbt test; `scripts/ci-status` returned 0.

## scljet-vfs-exclusive-lock-subprocess-exits-linux — official SQLite does not wait on host lock
<!-- status: fixed
     kind: apparatus
     lane: apparatus
     area: build
     fixed-in: unrecorded -->

**Status:** FIXED (2026-07-18, `scljet-xprocess-lock`). It was a **test bug, not a lock-interop bug** —
the cross-process lock protocol in `SclJetJvmVfsHost.scala` is correct and needed no change. Root cause:
the subprocess probe (`SclJetSqliteLockProbe`) set `busy_timeout=0`, which tells SQLite to return
`SQLITE_BUSY` *immediately* on a lock conflict and never wait; the test then asserted `process.isAlive`
after a 500 ms sleep, expecting the query to be blocked. With `busy_timeout=0` it can never block — it
returns in ~2 ms and the subprocess exits, so `process.isAlive()==false`.

**Evidence (macOS, instrumented `LockDiag` harness).** With scljet holding the Exclusive host lock:
- `busy_timeout=0` → subprocess prints `busy after 2ms: [SQLITE_BUSY] The database file is locked` —
  SQLite **detects** scljet's lock but returns instantly.
- `busy_timeout=5000` → subprocess **blocks** for the whole window the lock is held (`exited within
  1500ms? false`), then prints `ok after 1266ms` **only after** scljet releases.

So the official xerial `sqlite-jdbc` driver *does* genuinely wait on scljet's fcntl POSIX write-lock
(PENDING byte + SHARED range) cross-process — the JVM `FileChannel.tryLock` and SQLite's Unix VFS
`fcntl(F_SETLK)` locks interoperate exactly as designed.

**Fix.** In the test only: the probe now sets `busy_timeout=30000` (so SQLite enters its busy-retry
loop and waits), and prints a `querying` signal immediately before the blocking read. The test
synchronizes on that signal (no sleep), then asserts `!process.waitFor(2, SECONDS)` — proving the query
stays blocked while the lock is held — releases the lock, and asserts the query completes with `ok`.
Deterministic: `busy_timeout` (30 s) ≫ the 2 s window, and the query cannot return until scljet releases.
`scljetVfsPlugin/test` 6/0 (×3), `scljetJdbcPlugin/test` 57/0. No production code changed.

## ci-sbt-outer-timeout-cancels-bounded-test-step — job budget expires before the suite can report
<!-- status: fixed
     kind: apparatus
     lane: apparatus
     area: build
     fixed-in: 90c5599dc
     confirmed: no -->

**Status:** FIXED (revised 2026-07-17 in `884832696`; awaiting Linux confirmation). The first fix
`90c5599dc` raised only the outer cap; completed Linux run `29545769651` then proved the 60-minute
step cap independently insufficient. Originally found by `ci-red-main` in completed run
`29544412767`, SHA `73407430457effd61bb96307c4bb41c6d3df3179`, job `87773372863`. The job-level
timeout cancelled the test suite before the separately bounded test step could finish, so CI could
not reveal the complete failure set or prove the sbt job green.

**Real-harness repro.** The sbt job started at `00:18:32Z`. Setup, compile/assembly, and all six
v2.1 release gates ran through `00:53:54Z`; `Test via sbt` then ran until `01:48:45Z`, when GitHub
cancelled the whole job exactly at its 90-minute budget. The test step therefore received only
54m51s of its explicit 60-minute allowance and was still executing. `gh run view 29544412767
--json jobs` records the outer job as `cancelled` and the test step as `cancelled`, not as a test
failure or success.

**Expected/fix plan.** Keep the 60-minute test-step cap as the hang detector, but raise the outer
job budget to 120 minutes so measured setup/gates plus the bounded test have headroom for runner
variance. Document the timing next to the workflow setting. Acceptance requires a current Linux
run to reach the test step's natural verdict; extending the timeout alone is not evidence of green.

**Fix/result.** The sbt job now has a 120-minute outer budget while `Test via sbt` retains its
60-minute cap. Workflow YAML parses locally. Status remains awaiting confirmation until a run
containing `90c5599dc` or later reaches a natural test success/failure instead of cancellation.

**Correction from the next completed Linux tail.** Run `29545769651`, SHA `893bf2632`, job
`87777659720`, reached `Test via sbt` at `01:17:59Z` and GitHub stopped that step at `02:18:11Z`
with the explicit diagnostic `The action 'Test via sbt' has timed out after 60 minutes`. The suite
was not hung: it was still reporting green `CrossBackendPropertyTest` cases, but had completed only
12 of that suite's 16 ordered cases; the uncompleted tail includes both generated-program matrices.
Thus the landed 120/60 configuration remains deterministically red. The next fix is 150 minutes for
the outer job and 90 for the test step, retaining bounded hang detection while giving the measured
tail 30 additional minutes. That revision landed in `884832696`; only a later Linux natural verdict
closes confirmation.

## v2-swiftui-online-derived-owner-gap — computed online readers do not own monitoring
<!-- status: fixed
     kind: bug
     lane: apparatus
     area: build
     fixed-in: 0ade8bf7c -->

**Status:** done (2026-07-11, `0ade8bf7c`); reported and confirmed fixed by `nativeui-reviewer` in the
`scalascript` Rozum review of the persisted/online Apple slice.

- **Real-harness repro:** subscribe only to a computed/equality signal whose
  closure reads `onlineSignal`. Dependency commit owns fetch families but has
  no online-family ownership, so no monitor starts and the derived cell cannot
  react to connectivity changes.
- **Expected:** active transitive readers acquire exactly one refcounted online
  owner; callbacks recompute/publish the derived cell, and last derived
  unsubscribe cancels the monitor.
- **Plan/done-when:** mirror dependency fetch ownership for online dependencies
  and gate computed-only first/last subscription plus publication.
- **Root cause/fix:** dependency commits tracked fetch families only. Online
  dependencies now have symmetric acquire/release/disposal ownership, so a
  computed-only subscriber starts and stops the shared monitor correctly.

## v2-swiftui-keyed-fetch-metadata-stale — surviving fetch keeps its first request descriptor
<!-- status: fixed
     kind: bug
     lane: apparatus
     area: build
     fixed-in: 5c0b38ad9 -->

**Status:** done (2026-07-11; fixed in `5c0b38ad9`, `068e8b62d`, and
`03f2f1fcf`; `nativeui-reviewer` confirmed with final APPROVE in the
`scalascript` Rozum room).

- **Real-harness repro:** reconstruct a fetch with the same keyed/component
  `(scope,id,kind,default)` but change its literal URL, or swap a literal URL for
  a scoped URL signal. Host reuses the live cell while ignoring new metadata;
  Store retains the first wrapper in its stable observable cell. An active A
  request is therefore neither cancelled nor restarted as B, and later refresh
  dependencies continue to use stale A metadata. Conversely, comparing the new
  wrappers with ordinary equality treats regenerated read/write closures as a
  false change and restarts identical fetches.
- **Expected/root-cause direction:** canonicalize every nested NativeUiSignal
  reference in request metadata to validated `(scope,id,kind)`, retain current
  metadata for the live family, and restart an observed family only after a
  committed structural metadata change. Generation checks make late A inert;
  identical registration preserves one stable cell/task.
- **Plan/done-when:** strict generated Swift drives same-key scoped fetch
  reconstruction through identical A, literal A→B, and literal→signal-ref B;
  one transaction that registers the same live key as intermediate A then final
  B must coalesce to one B restart (and no restart when final B equals the
  pre-transaction descriptor);
  assert exact cancellation/request counts, late-A inertness, current value,
  dependency ownership, and bounded task metadata. Keep `fixed` until the Rozum
  reporter confirms the regression.
