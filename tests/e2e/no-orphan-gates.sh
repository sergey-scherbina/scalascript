#!/usr/bin/env bash
#
# no-orphan-gates.sh — a NEW gate ANYWHERE IN THE REPOSITORY must be invoked by something.
#
# WHY THIS EXISTS. A gate nobody runs is not a weaker gate, it is a gate that reports GREEN by not
# existing, and this repo has paid for that repeatedly and recently:
#
#   * `v1-jit-size.sh` sat unwired from 2026-07-30. In that silence four frozen methods grew
#     (renderTerm by 3204 bytecodes), two new offenders appeared, and the largest method in the tree
#     had never been censused at all. Wiring it took an afternoon; the debt it should have stopped
#     took three splits to pay off.
#   * `orphaned-e2e-gates-52` (2026-08-02) found 52 of 126 scripts invoked by nothing, 33 of them
#     not even passing. That sweep wired 19 and fixed two mechanical causes.
#
# AND THE SWEEP DID NOT HOLD, which is the reason this file is a RATCHET and not another sweep.
# Re-censused 2026-08-13: 182 scripts, 39 orphans. The pile was drained by 13 and refilled by new
# arrivals — gates are written faster than they are wired. A one-off cleanup of an ongoing leak buys
# a few weeks. So: freeze today's orphans BY PATH, fail on a NEW one, and let the list only shrink.
# Same shape as `v1-jit-size.sh`'s frozen debt and the negtc release gate: freeze the hard invariant,
# derive the rest.
#
# TWO LISTS SINCE 2026-08-16, and the split is what makes the number mean something. `FROZEN` is
# DEBT: gates nobody runs, which can and should drain to zero. `MANUAL_TOOLS` is everything the
# extension filter sweeps up that is not a gate at all — a harness wanting `SSC_JAR`, a script
# wanting an argument, a build step that asserts nothing. Twelve of the fourteen entries were the
# second kind, and while they sat in FROZEN the count could never reach zero and every reader had to
# re-run them to discover why. Both lists obey the same ratchet rules; only the first is a backlog.
#
# WHAT COUNTS AS "INVOKED". A reference to the script's PATH — or to a segment-boundary suffix or
# prefix of it — from a file that can execute something: `.github/workflows/`, `scripts/`, another
# script, a `build.sbt`, or a `.tsv` manifest a runner reads. Prose does NOT count, whether it lives
# in a `.md`, in a `#` or `//` comment inside a script, or on a ` * ` line of a Scala doc comment.
# Documentation is how these rot in the first place: cited everywhere, run nowhere.
#
# SCOPE, since 2026-08-16: EVERY tracked `*.sh` in the repository, against EVERY tracked file that
# could execute one. Before that it was `tests/e2e/*.sh` against `.github`/`scripts`/`tests`, so a
# gate living anywhere else was not merely unwired — it was invisible, and the entry
# `orphaned-e2e-gates-52` had recorded that blind spot without being able to fix it here. Widening
# both sets took the census from 217 subjects / 8 orphans to 291 / 31, and made it FASTER: one pass
# over the corpus instead of one recursive grep per script, 26.6 s -> 3.5 s.
#
# WHAT THIS DOES NOT DO. It does not check that a gate PASSES, or that the suite it is wired into
# actually runs. Both are real and both have bitten: `v1-jit-size.sh` was first wired into ci.yml's
# `sbt` job, which is `workflow_dispatch`-only in a workflow with no `push:` trigger, so it was
# "wired" and still ran essentially never. Wiring is necessary, not sufficient — check the job's
# `if:` and the workflow's `on:` yourself.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

# Frozen orphans: gates that were already unwired when the scan last widened, listed so this gate is
# green on arrival. A gate red the day it lands is disabled within a day. DELETE from this list when
# you wire or remove one — the gate fails if a frozen entry stops being an orphan, so the list cannot
# rot into a permanent exemption.
#
# WHAT IS LEFT HERE IS DEBT, and as of 2026-08-16 it is TWO entries: `serve-view-frontend-v2-smoke`
# (red on the filed `content-current-section-native-unavailable`) and `v2/conformance/check.sh`
# (GREEN, 645 checks, 11 min — it wants a CI-budget decision between a schedule and a fixture-pattern
# subset, which is not an agent's to take). Everything else that was here is either wired or declared
# a non-gate below.
#
# REPO-RELATIVE PATHS since 2026-08-16, not basenames: the scan now covers the whole repository, and
# 291 scripts there carry only 284 distinct names. Widening took the census from 217 subjects / 8
# orphans to 291 / 31 — the 23 new ones were never wired and were never VISIBLE either, which is a
# different and worse thing. Several name themselves gates in their own first line:
# `v2/backend/check.sh` WAS on this list and is not any more: since 2026-08-16 ci.yml invokes it
# (filtered to the bigint fixtures, which had been skipped on the rust/wasm lanes until BigInt
# existed there). An exemption that outlives its need is the same rot as a stale known-red, which is
# what this gate said when it caught the leftover. The rest of the prose below is the original
# triage note and still applies to the others:
# `v2/backend/check.sh` is a parity harness over every `v2/conformance/*.coreir` fixture,
# `v2/conformance/coreir-name-guard.sh` says the Writer "must refuse" a name, `v3/extension-gate.sh`
# "keeps Lower's built-in vocabulary honest", `uniml/lint-portable-subset.sh` "guards" the portable
# subset. Others are manual reports and demos by design (`scripts/bundle-size.sh`,
# `v3/corpus-report.sh`, `v1/tools/scripts/v2-scale-bench.sh`, `specs/*-demo.sh`) — triaging which is
# which is the work this list exists to make visible, and it is NOT done here.
read -r -d '' FROZEN <<'EOF' || true
tests/e2e/serve-view-frontend-v2-smoke.sh
v2/conformance/check.sh
EOF

# ── NOT GATES AT ALL: tools the extension filter sweeps up ───────────────────────────────────────
#
# This detector selects subjects by EXTENSION — every tracked `*.sh` — so it counts a parameterised
# harness or a build helper as a gate that nobody runs. Twelve of the 2026-08-16 census's fourteen
# frozen entries were that: they exit non-zero in 0 s with a usage line, because they want an
# argument or `SSC_JAR`, or they build an artifact and assert nothing.
#
# THEY WERE MAKING THE DEBT NUMBER MEANINGLESS. Held in FROZEN they can never drain — there is
# nothing to wire — so the ratchet's count could not reach zero and every reader of the list had to
# re-run them to find that out. Split out here WITH A REASON EACH, exactly as
# `GREEN_WITHOUT_LAUNCHER` does for the other axis. What is left in FROZEN is real debt.
#
# The rules are the ratchet's, unchanged: an entry that stops existing, or that something starts
# invoking, must be DELETED from this list. An exemption that outlives its need is the same rot as a
# stale known-red.
#
#   needs-an-argument   refuses in 0 s with a usage line; there is no argument-free run to wire
#   needs-SSC_JAR       refuses in 0 s: `SSC_JAR: set SSC_JAR to a run-ir-capable v2 kernel jar`
#   builds-an-artifact  produces a file and asserts nothing — a build step, not a check
read -r -d '' MANUAL_TOOLS <<'EOF' || true
examples/run-wasm.sh	needs-an-argument	Usage: ./run-wasm.sh <file.ssc>
scripts/bundle-size.sh	builds-an-artifact	reports JS/JVM bundle sizes and WRITES bench/BUNDLE_SIZES.md
specs/newfront-diff-multi.sh	needs-SSC_JAR	multi-file corpus byte-identity harness
specs/newfront-diff.sh	needs-SSC_JAR	corpus byte-identity harness
specs/v2-f5b-method-census.sh	needs-SSC_JAR	census of untyped __method__ sites
specs/v2.2-p6.0-spike-verify.sh	needs-SSC_JAR	P6.0/P6.1/P6.2 end-to-end verifier
specs/v2.2-p6.18-capstone.sh	needs-SSC_JAR	P6.18 capstone driver
specs/v2.2-p6.5-corpus.sh	needs-SSC_JAR	P6.5 real-corpus acceptance driver
specs/v2.2-p6.6-fixpoint.sh	needs-SSC_JAR	P6.6 self-compilation fixpoint driver
specs/v2.2-p6.6-selfcompile-demo.sh	needs-SSC_JAR	P6.6a self-compile demo
v1/tools/scripts/v2-scale-bench.sh	needs-an-argument	usage: v2-scale-bench.sh [path-to-ssc.jar]
v3/plugin-classpath.sh	builds-an-artifact	builds and caches v3/.jars/plugin.cp; asserts nothing
EOF

# ── THE SECOND AXIS: can a WIRED gate fail at all? ──────────────────────────────────────────────
#
# An orphan reports green by NOT RUNNING. A vacuous gate reports green by NOT LOOKING. The same
# defect measured two ways, so they live in ONE file: two frozen lists over one population kept in
# two places is a second decision site, and this repo has paid for those repeatedly.
#
# TWO DEPTHS, ONE TABLE. The wired axis is cheap (~3.5 s) and runs on every push. The evidence axis
# (`--evidence`) runs the suite again with the launcher removed, 15-20 minutes, so it belongs in
# tier 2. Same file, same lists, different depth — not a second gate with its own copy.
#
# GREEN_WITHOUT_LAUNCHER — gates that INVOKE a launcher, watch it fail, and pass anyway. Each carries
# with the reason it is allowed to. Measured 2026-08-13 over 112 wired gates: 90 went RED, and every
# one of the 22 that did not is explained below. NO GENUINELY VACUOUS GATE WAS FOUND, which is the
# result — the prediction written before the run was "0-3", and the answer is 0.
#
# THE FIRST VERSION OF THIS CHECK GUESSED, and guessed wrong. It selected gates by grepping for
# `bin/ssc` and friends, i.e. by a MENTION rather than an execution — the same error a sibling had
# already fixed on the orphan axis that morning ("a COMMENT is not a caller"). It caught 13 gates
# that never touch a launcher, including `v1-jit-size.sh`, which censuses JARS and needs no
# launcher at all, and `cds-archive-per-build.sh`, which reads `bin/ssc` AS TEXT.
#
# So the static filter is gone. The signal is now DYNAMIC and needs no regex: run every wired gate
# with the launcher removed, and freeze whatever stays green WITH ITS REASON. A new gate that stays
# green must be declared here; one that starts going red must be deleted from here.
#
#   skip-guarded          `ssc_usable_or_skip` prints SKIP and exits 0. Declining to judge, not
#                         vacuity — but it reports SUCCESS while testing nothing, so wire it only
#                         where a launcher is guaranteed.
#   no-launcher-needed    inspects artifacts (jars, staged files, launcher TEXT) and never runs a
#                         program. Removing the jars is not a mutation of anything it looks at.
read -r -d '' GREEN_WITHOUT_LAUNCHER <<'EOF' || true
f-alternative-pattern-gate.sh	skip-guarded	ssc_usable_or_skip prints SKIP and exits 0
f-bare-member-call-gate.sh	skip-guarded	ssc_usable_or_skip prints SKIP and exits 0
f-curried-def-gate.sh	skip-guarded	ssc_usable_or_skip prints SKIP and exits 0
f-global-v-gate.sh	skip-guarded	ssc_usable_or_skip prints SKIP and exits 0
f-nested-pattern-lambda-gate.sh	skip-guarded	ssc_usable_or_skip prints SKIP and exits 0
f-output-agreement-gate.sh	skip-guarded	ssc_usable_or_skip prints SKIP and exits 0
f-std-ui-gate.sh	skip-guarded	ssc_usable_or_skip prints SKIP and exits 0
f-trailing-block-gate.sh	skip-guarded	ssc_usable_or_skip prints SKIP and exits 0
launchers-are-not-dead-on-arrival.sh	declines	its own availability check, not ssc_usable_or_skip — read it before trusting
list-concat-chain-gate.sh	skip-guarded	ssc_usable_or_skip prints SKIP and exits 0
negtc-mapreduce-gate.sh	declines	its own availability check, not ssc_usable_or_skip — read it before trusting
ref-front-multiblock-gate.sh	skip-guarded	ssc_usable_or_skip prints SKIP and exits 0
ref-front-string-literal-gate.sh	skip-guarded	ssc_usable_or_skip prints SKIP and exits 0
ref-front-three-defects-gate.sh	skip-guarded	ssc_usable_or_skip prints SKIP and exits 0
EOF


# ── one helper, used by BOTH the self-test and the census, so they cannot drift ──────────────────
#
# The exit status of `grep` is DELIBERATELY discarded and the OUTPUT is what decides. Two ways it
# lies here, both hit while writing this file:
#   * `grep` exits 1 on zero matches, and under `set -euo pipefail` that aborts the script.
#   * it also exits NONZERO when any search path does not exist — while still printing the matches
#     it DID find. The self-test tree has no `.github`, so the first version of this reported a
#     correctly-wired script as an orphan, with a match sitting in the output it had just thrown away.
#   * AND IT MATCHES ITSELF. Every name in FROZEN above is a literal string inside THIS file, so a
#     search for it finds this file and calls the orphan "wired". The first run reported 1 orphan
#     out of 183 and demanded 38 frozen entries be deleted as "now invoked" — a detector that had
#     silently become a rubber stamp for exactly the list it exists to hold. Same shape as a
#     `pgrep -f PATTERN` waiter matching its own command line. SELF is excluded by basename, taken
#     from BASH_SOURCE so a rename cannot re-open the hole, and the self-test asserts it.
SELF="$(basename "${BASH_SOURCE[0]}")"
#   * AND A COMMENT IS NOT A CALLER EITHER. Excluding `.md` files stops PROSE from counting, but
#     prose does not only live in `.md`: a `#` line inside a sibling gate, or inside `scripts/*`,
#     read as an invocation just the same. Measured 2026-08-13, right after this gate landed: it
#     reported 35 orphans and 38 was the true number. `bytecode-fallback-visible.sh`,
#     `negtc-shard-gate.sh` and `ssc1-front-annotation.sh` were each held "wired" by a sentence
#     MENTIONING them — and two of the three are named in a BUGS.md entry's `gate:` field, so those
#     entries claimed coverage from a script nothing runs. The tell is that the search is for a
#     STRING, while the thing being detected is an INVOCATION, and every commented-out or
#     described call looks identical to a real one.
#     So the match must survive having the comment tail removed. A trailing comment on a real call
#     (`run x.sh   # why`) still counts — the self-test asserts that direction too, because the
#     over-strict fix that drops it would silently turn working gates into "orphans" to be frozen.
#   * AND THE SEARCH USED TO COVER ONE DIRECTORY. Until 2026-08-16 the subjects were `tests/e2e/*.sh`
#     and the callers were `.github`, `scripts`, `tests` — so a gate living anywhere else was not
#     merely unwired, it was INVISIBLE. `v2/backend/check.sh` runs every `v2/conformance/*.coreir`
#     fixture through the VM and diffs four generators against it, exits non-zero on a mismatch, and
#     is invoked by nothing; the entry `orphaned-e2e-gates-52` recorded it as the detector's own blind
#     spot and could not fix it here. Widening BOTH sets to the whole repository took the census from
#     217 subjects / 8 orphans to 291 / 30.
#   * KEYED BY PATH, NOT BY BASENAME, and that is forced by the widening: 291 scripts carry only 284
#     distinct names. `v2/backend/check.sh` and `v2/conformance/check.sh` are two different gates, and
#     under a basename key a call to either marks BOTH as wired — the masking this gate exists to
#     stop, one level up. Matching is at a SEGMENT BOUNDARY in both directions, because both occur:
#     `"$ROOT/tests/e2e/x.sh"` (token carries a prefix) and a relative tail after a `cd`. A BARE
#     basename is ambiguous by construction and marks every candidate — the conservative direction,
#     since a ratchet must never invent an orphan. Substring matching, which is what the old
#     basename search did, is not merely loose: `v3/extension-gate.sh` read as WIRED because
#     `single-line-extension-gate.sh` ends with its name.
#   * ONE PASS, NOT ONE PER SUBJECT. The old shape ran a recursive `grep` per script — 217 walks of
#     the same tree, 26.6 s. Reading the corpus once and looking up whole `*.sh` tokens in a hash is
#     3.5 s over 7198 files and 291 subjects: an order of magnitude faster while covering three times
#     the population. It also lets ONE classifier serve both the census and `--evidence`, instead of
#     two call sites that can drift.
#   * `LC_ALL=C`, because macOS awk aborts with `towc: multibyte conversion failure` on the first
#     binary fixture it meets (`tests/fixtures/scljet/m2/valid/overflow-thresholds.db`) and would
#     take the whole census down with it.
#   * REGULAR FILES ONLY, and this is not defensive tidying — it was a silent `exit 1` with no
#     output at all. `git ls-files` lists a SUBMODULE gitlink (`.agents/plugins`) and a SYMLINK TO A
#     DIRECTORY (`v1/runtime/plugins/scljet -> ../../../scljet`) as ordinary paths; awk cannot open
#     either, exits 1, and `pipefail` takes the whole census down before it prints a line. Filtering
#     by index MODE also stops a symlinked script from becoming a second subject for one file.
tracked_files() { # tracked_files <root> -> repo-relative paths of REGULAR tracked files
  git -C "$1" ls-files -s |
    awk -F'\t' '{ split($1, m, " "); if (m[1] != "100644" && m[1] != "100755") next; print $2 }'
}
subjects_of() { # subjects_of <root> -> repo-relative paths of every candidate gate
  tracked_files "$1" | awk '/\.sh$/{print}'
}
# A CALLER IS CODE OR CONFIGURATION, NEVER A DATA FIXTURE, and this became load-bearing the moment
# the corpus stopped being "shell and yaml". The repository holds 2528 `.scala`, 1364 `.ssc` and
# hundreds of `.event`/`.out`/`.expected`/`.coreir` fixtures, and COMMENT SYNTAX VARIES BY FILE TYPE:
# `;` opens a comment in `.coreir` and separates commands in shell, so it cannot be stripped
# globally. Measured: `v2/conformance/autooutput.coreir` says "which is why check.sh — a harness
# that otherwise compares…" in a `;` comment, and that sentence alone marked BOTH `v2/backend/
# check.sh` and `v2/conformance/check.sh` as invoked.
#
# AN ALLOWLIST, NOT A DENYLIST, because the two errors are not equally bad. Missing a caller makes a
# wired gate look like an orphan — loud, and fixed by adding a type here. Counting a comment as a
# caller hides a REAL orphan — silent, and it is the exact defect this gate exists to catch. So a
# new file type is excluded until someone shows it invokes something.
#
# `.tsv` IS ON THE LIST, AND THAT IS THE INTERESTING ENTRY. The line is not code-vs-data, it is
# EXECUTED-vs-PROSE: `tests/fixtures/v21-explicit-lanes/manifest.tsv` names four
# `v21-explicit-*-smoke.sh` gates in a column, and `tests/e2e/v21-explicit-lanes-gate.sh` reads that
# column and runs them. Leaving `.tsv` out turned four correctly-wired gates into orphans — caught
# by the regression check that the `tests/e2e` subset of the new census must still equal the eight
# this gate already knew about, which is the only reason the widening did not ship with them.
corpus_of() {  # corpus_of <root> -> repo-relative paths of everything that could name one
  tracked_files "$1" |
    awk -v self="$SELF" '
      # NO APOSTROPHES IN THIS PROGRAM: it is a single-quoted shell string, so one closes it and the
      # script dies with a syntax error 6 lines further down.
      # .work/ IS COORDINATION STATE, NOT CODE. .work/active/LEDGER.tsv lists the paths each claim
      # reserves (... file:v3/plugin-classpath.sh ...), which reads exactly like an invocation and
      # marked a real orphan as wired the first time this ran against a live ledger. Caught by the
      # ratchet minutes after the widening landed, which is the ratchet earning its keep.
      /^\.work\// { next }
      { n = split($0, a, "/"); f = a[n]
        if (f == self) next
        ext = ""; if (f ~ /\./) { m = split(f, e, "."); ext = e[m] }
        if (ext == "sh" || ext == "bash" || ext == "zsh" || ext == "yml" || ext == "yaml" ||
            ext == "ssc" || ext == "sbt" || ext == "scala" || ext == "json" || ext == "mk" ||
            ext == "py" || ext == "rb" || ext == "js" || ext == "ts" || ext == "tsv" || ext == "" ||
            f == "Makefile" || f == "Dockerfile") print }'
}
orphans_of() { # orphans_of <root> -> repo-relative paths invoked by nothing, sorted
  local r="$1" pats seen corpus
  pats="$(mktemp)"; seen="$(mktemp)"; corpus="$(mktemp)"
  subjects_of "$r" | LC_ALL=C sort -u > "$pats"
  corpus_of "$r" > "$corpus"
  : > "$seen"
  # `xargs` with empty input still runs the utility once, and awk would then read STDIN and hang.
  if [[ -s "$corpus" ]]; then
    ( cd "$r" && LC_ALL=C xargs awk -v patfile="$pats" '
        BEGIN {
          while ((getline s < patfile) > 0) {
            if (s == "") continue
            n = split(s, a, "/"); bybase[a[n]] = bybase[a[n]] " " s
          }
        }
        {
          # A Scala/Java DOC-COMMENT continuation carries no `#` and no `//`, so it survived both
          # strippers: `v2/src/Runtime.scala` mentions `specs/coreir-inventory-gate.sh` on a ` * `
          # line and that alone marked the gate as invoked.
          if ($0 ~ /^[ \t]*\*/) next
          code = $0
          sub(/\/\*.*\*\//, "", code)     # a one-line block comment
          sub(/#.*/, "", code); sub(/\/\/.*/, "", code)
          while (match(code, /[A-Za-z0-9._+\/-]+\.sh/)) {
            t = substr(code, RSTART, RLENGTH); code = substr(code, RSTART + RLENGTH)
            n = split(t, a, "/"); b = a[n]
            if (!(b in bybase)) continue
            m = split(bybase[b], cand, " ")
            for (i = 1; i <= m; i++) {
              s = cand[i]
              if (s == "" || s == FILENAME) continue     # a script naming itself is not its caller
              if (t == b || s == t) { seen[s] = 1; continue }
              # Deleting either of the next two lines turns a self-test assertion red, which is how
              # they were shown to matter. Replacing them with a plain `index(s, t)` does NOT — and
              # that is a fact about the code, not a gap: candidates are pre-filtered by BASENAME, so
              # a substring hit that is not a segment-boundary suffix cannot occur here. The strict
              # form is kept because the pre-filter is the only thing making it safe.
              if (length(t) > length(s) && substr(t, length(t) - length(s)) == "/" s) { seen[s]=1; continue }
              if (length(s) > length(t) && substr(s, length(s) - length(t)) == "/" t) seen[s] = 1
            }
          }
        }
        END { for (s in seen) print s }
      ' < "$corpus" ) 2>/dev/null | LC_ALL=C sort -u > "$seen"
  fi
  LC_ALL=C comm -23 "$pats" "$seen"
  rm -f "$pats" "$seen" "$corpus"
}

# ── self-test: a detector only ever observed staying quiet is not a detector ─────────────────────
# Asserts BOTH verdicts against files it creates itself, because the interesting failure mode here
# is a search that quietly matches nothing — which is exactly how the thing being detected survives.
if [[ "${1:-}" == "--self-test" ]]; then
  TMP="$(mktemp -d "${TMPDIR:-/tmp}/no-orphan-selftest.XXXXXX")"
  trap 'rm -rf "$TMP"' EXIT
  mkdir -p "$TMP/tests/e2e" "$TMP/scripts" "$TMP/v9/backend" "$TMP/v9/conformance"
  printf '#!/usr/bin/env bash\ntrue\n' > "$TMP/tests/e2e/wired-example.sh"
  printf '#!/usr/bin/env bash\ntrue\n' > "$TMP/tests/e2e/orphan-example.sh"
  printf '#!/usr/bin/env bash\ntrue\n' > "$TMP/tests/e2e/commented-example.sh"
  printf '#!/usr/bin/env bash\ntrue\n' > "$TMP/tests/e2e/trailing-example.sh"
  printf 'run tests/e2e/wired-example.sh\n'                > "$TMP/scripts/caller"
  # THE PROSE FIXTURE MUST LIVE WHERE THE SEARCH LOOKS. It was `$TMP/prose.md`, at the root — and
  # the search only read `.github`, `scripts` and `tests`, so that file was never opened and the
  # ".md does not count" assertion passed whether or not the `.md` filter existed. A probe whose
  # subject is unreachable WITHOUT the thing under test measures nothing.
  printf 'see tests/e2e/orphan-example.sh for details\n'   > "$TMP/tests/prose.md"
  # A comment is prose that happens to live in a script — the case that made this fix necessary.
  printf '# see tests/e2e/commented-example.sh for the shape\n' > "$TMP/scripts/mentions"
  # …and the opposite direction, so the fix cannot be "drop anything near a #".
  printf 'run tests/e2e/trailing-example.sh   # why we run it\n' > "$TMP/scripts/trailing-caller"

  # ── the 2026-08-16 widening, asserted where it can actually fail ────────────────────────────────
  # Two subjects with the SAME BASENAME in different directories, one of them called by path. Under
  # the basename key this gate used until then, the call marked both and the second was invisible.
  printf '#!/usr/bin/env bash\ntrue\n' > "$TMP/v9/backend/dup-example.sh"
  printf '#!/usr/bin/env bash\ntrue\n' > "$TMP/v9/conformance/dup-example.sh"
  # A CALLER OUTSIDE `.github`/`scripts`/`tests`, invoking with a `$ROOT/` prefix — the direction
  # where the TOKEN is longer than the subject, which a suffix test written one way only will miss.
  printf 'exec "$ROOT/v9/backend/dup-example.sh"\n'        > "$TMP/v9/run-backend"
  # Suffix collision: `collide-example.sh` is a SUFFIX of `long-collide-example.sh`, so substring
  # matching reports the short one wired off the long one's call. Real instance: `v3/extension-gate.sh`
  # read as wired because `single-line-extension-gate.sh` ends with its name.
  # The OTHER suffix direction: a caller that `cd`s first names a RELATIVE TAIL, so the SUBJECT is
  # the longer string. Without a fixture here that branch was unreachable from the self-test — found
  # by deleting the branch and watching every assertion still pass, which is the only way to learn
  # that a line is untested.
  printf '#!/usr/bin/env bash\ntrue\n' > "$TMP/v9/backend/tail-example.sh"
  printf 'cd v9 && bash backend/tail-example.sh\n'         > "$TMP/v9/run-tail"
  printf '#!/usr/bin/env bash\ntrue\n' > "$TMP/v9/collide-example.sh"
  printf '#!/usr/bin/env bash\ntrue\n' > "$TMP/v9/long-collide-example.sh"
  printf 'bash v9/long-collide-example.sh\n'               > "$TMP/v9/run-collide"

  # THE COORDINATION LEDGER IS NOT A CALLER. `.work/active/LEDGER.tsv` reserves paths per claim, and
  # a `.tsv` is on the allowlist because a manifest can drive a runner — so without the `.work/`
  # exclusion a claim row marks every script it reserves as invoked. This happened for real:
  # `v3/plugin-classpath.sh` flipped to "wired" the moment a sibling claimed it.
  mkdir -p "$TMP/.work/active"
  printf '#!/usr/bin/env bash\ntrue\n' > "$TMP/v9/claimed-example.sh"
  printf 'some-slug\tclaude-code\t2026-08-16T00:00:00Z\tan-item\tfile:v9/claimed-example.sh\n' \
    > "$TMP/.work/active/LEDGER.tsv"

  # A GIT REPOSITORY, because `subjects_of`/`corpus_of` enumerate with `git ls-files` — the branch
  # that runs in production is the branch the self-test must exercise. `git add` alone populates the
  # index; no commit is needed, so no user identity is required.
  git -C "$TMP" init -q
  git -C "$TMP" add -A

  ORPHANS_CACHE=""
  probe() { # probe <root> <repo-relative path> -> "wired" | "orphan"
    local r="$1" p="$2"
    [[ -n "$ORPHANS_CACHE" ]] || ORPHANS_CACHE="$(orphans_of "$r")"
    grep -qxF "$p" <<<"$ORPHANS_CACHE" && printf orphan || printf wired
  }
  [[ "$(probe "$TMP" tests/e2e/wired-example.sh)"  == wired  ]] \
    || { echo "SELF-TEST FAIL: a script named by an executable caller was called an orphan" >&2; exit 1; }
  [[ "$(probe "$TMP" tests/e2e/commented-example.sh)" == orphan ]] \
    || { echo "SELF-TEST FAIL: a script named ONLY by a COMMENT was called wired. Excluding .md is" >&2
         echo "  not enough — prose also lives inside scripts, and that is how three real orphans" >&2
         echo "  (bytecode-fallback-visible, negtc-shard-gate, ssc1-front-annotation) read as wired." >&2; exit 1; }
  [[ "$(probe "$TMP" tests/e2e/trailing-example.sh)" == wired ]] \
    || { echo "SELF-TEST FAIL: a REAL call carrying a trailing comment was called an orphan — the" >&2
         echo "  comment rule is over-strict and would freeze working gates as debt." >&2; exit 1; }
  [[ "$(probe "$TMP" tests/e2e/orphan-example.sh)" == orphan ]] \
    || { echo "SELF-TEST FAIL: a script named ONLY by a .md was called wired — prose is not a caller," >&2
         echo "  and treating it as one is how these gates rot: cited everywhere, run nowhere." >&2; exit 1; }

  # ── the widening, both directions ───────────────────────────────────────────────────────────────
  [[ "$(probe "$TMP" v9/backend/dup-example.sh)" == wired ]] \
    || { echo "SELF-TEST FAIL: a gate OUTSIDE tests/e2e, called by a file OUTSIDE .github/scripts/tests" >&2
         echo "  with a \$ROOT/ prefix, was called an orphan. The suffix test must work in the direction" >&2
         echo "  where the TOKEN is longer than the subject, not only the other one." >&2; exit 1; }
  [[ "$(probe "$TMP" v9/conformance/dup-example.sh)" == orphan ]] \
    || { echo "SELF-TEST FAIL: a gate was called wired because a DIFFERENT gate with the same basename" >&2
         echo "  is invoked. 291 scripts here carry 284 distinct names — v2/backend/check.sh and" >&2
         echo "  v2/conformance/check.sh are two gates, and a basename key hides one behind the other." >&2; exit 1; }
  [[ "$(probe "$TMP" v9/claimed-example.sh)" == orphan ]] \
    || { echo "SELF-TEST FAIL: a script was called wired because a CLAIM reserved its path in" >&2
         echo "  .work/active/LEDGER.tsv. Coordination state is not an invocation — v3/plugin-classpath.sh" >&2
         echo "  flipped to wired for real the moment a sibling claimed it." >&2; exit 1; }
  [[ "$(probe "$TMP" v9/backend/tail-example.sh)" == wired ]] \
    || { echo "SELF-TEST FAIL: a gate named by a RELATIVE TAIL from a caller that cd'd first was" >&2
         echo "  called an orphan. The suffix test must also work where the SUBJECT is longer than" >&2
         echo "  the token — the mirror of the \$ROOT/ case above." >&2; exit 1; }
  [[ "$(probe "$TMP" v9/collide-example.sh)" == orphan ]] \
    || { echo "SELF-TEST FAIL: a gate was called wired off a LONGER name that ends with its own —" >&2
         echo "  substring matching, not a segment boundary. Real instance: v3/extension-gate.sh read" >&2
         echo "  as wired because single-line-extension-gate.sh ends with it." >&2; exit 1; }
  [[ "$(probe "$TMP" v9/long-collide-example.sh)" == wired ]] \
    || { echo "SELF-TEST FAIL: the LONG name in the collision pair was called an orphan — the boundary" >&2
         echo "  rule is over-strict and would freeze a working gate as debt." >&2; exit 1; }

  # THIS GATE MUST NOT COUNT ITSELF. Every frozen name is a literal string in this file; without
  # excluding SELF the search finds it and every orphan reads as wired. Measured, not imagined: the
  # first run of this gate reported 1 orphan out of 183 and asked for 38 frozen entries to be
  # deleted as "now invoked".
  cp "$ROOT/tests/e2e/$SELF" "$TMP/tests/e2e/$SELF"
  printf 'tests/e2e/orphan-example.sh\n' >> "$TMP/tests/e2e/$SELF"
  git -C "$TMP" add -A
  ORPHANS_CACHE=""          # the tree changed; a cached verdict would answer about the old one
  [[ "$(probe "$TMP" tests/e2e/orphan-example.sh)" == orphan ]] \
    || { echo "SELF-TEST FAIL: a name appearing only in THIS gate's own frozen list was called wired." >&2
         echo "  The detector is matching itself, so its whole list reads as already fixed." >&2; exit 1; }
  echo "no-orphan-gates self-test: PASS (an executable caller counts — with or without a trailing" \
       "comment; a .md mention and a commented-out one do not)"
  # falls through to the census, like v1-jit-size.sh: one invocation does both
fi

# ── `--evidence`: a gate that RUNS the toolchain must fail when the toolchain fails ──────────────
#
# 15-20 minutes, so this runs in tier 2, not on the push path. The cheap wired axis below runs on
# every push. Same file, same population, two depths.
#
# THE MEMBERSHIP TEST IS THE HARD PART, and two designs were measured and thrown away first:
#
#   1. Grep each gate for `bin/ssc` and friends. That selects on a MENTION, not an execution — the
#      identical error a sibling had fixed on the orphan axis hours earlier ("a COMMENT is not a
#      caller"). 13 false positives, including `v1-jit-size.sh`, which censuses JARS and never runs
#      a launcher, and `cds-archive-per-build.sh`, which greps `bin/ssc` AS TEXT.
#   2. Drop the filter and run everything. Then 51 of 151 wired gates stay green — correctly, since
#      a board or shell gate does not care whether a launcher exists — and a declared list of 51
#      buries the handful of cases that mean anything.
#
# So the launcher is REPLACED BY A RECORDING STUB rather than removed. A gate that invokes it leaves
# a line in the log; a gate that does not, does not. Membership stops being a guess and becomes an
# observation, and the same substitution is the failure injection: the stub exits 1.
if [[ "${1:-}" == "--evidence" ]]; then
  elog="$(mktemp)"
  ECAP="${SSC_EVIDENCE_CAP:-240}"   # per gate; raised from 120 because a cut-off gate yields no verdict
  elaunchers=()
  for l in "$ROOT/bin/ssc" "$ROOT/bin/ssc-tools" "$ROOT/bin/ssc-standard"; do
    [[ -f "$l" ]] && elaunchers+=("$l")
  done
  [[ ${#elaunchers[@]} -gt 0 ]] || { echo "no-orphan-gates --evidence: no launchers in bin/ — build first" >&2; exit 2; }
  # RESTORE IN A TRAP, not at the end. A run interrupted half-way would otherwise leave the tree
  # with stub launchers, and the next person's failure would look like a product defect.
  erestore() { for l in ${elaunchers[@]+"${elaunchers[@]}"}; do [[ -f "$l.evidence-bak" ]] && mv -f "$l.evidence-bak" "$l"; done; return 0; }
  trap erestore EXIT HUP INT TERM
  for l in "${elaunchers[@]}"; do
    mv "$l" "$l.evidence-bak"
    printf '#!/usr/bin/env bash\necho "%s" >> "%s"\necho "stub launcher: deliberately broken for the evidence audit" >&2\nexit 1\n' \
      "$(basename "$l")" "$elog" > "$l"
    chmod +x "$l"
  done

  declared="$(printf '%s\n' "$GREEN_WITHOUT_LAUNCHER" | grep -v '^$' | cut -f1 | sort)"
  efail=0; invoked=0; blind=(); noverdict=()
  EVIDENCE_ORPHANS="$(orphans_of "$ROOT")"
  for g in "$ROOT"/tests/e2e/*.sh; do
    b="$(basename "$g")"
    [[ "$b" == "$SELF" ]] && continue
    # An orphan's evidence is moot until it is wired. ONE classifier for both axes: the set is
    # computed once, above, by the same `orphans_of` the census uses — two call sites asking the
    # question two ways is how the two halves of this file would drift apart.
    grep -qxF "tests/e2e/$b" <<<"$EVIDENCE_ORPHANS" && continue
    : > "$elog"
    timeout "$ECAP" "$g" >/dev/null 2>&1 && rc=0 || rc=$?
    # ── A TIMEOUT IS A THIRD OUTCOME, and folding it into "red" is what made this unreproducible ──
    #
    # Measured 2026-08-13: two consecutive runs reported 105 invoked / 14 blind, then 104 / 15. The
    # cause is that a gate cut off at the cap lands in a DIFFERENT BUCKET depending on whether the
    # clock beat it to its first launcher call: cut off before, it looks like "never touched one"
    # and leaves the population; cut off after, it looks like it FAILED and counts as healthy. Both
    # readings are the apparatus reporting a verdict it did not obtain — the exact shape this whole
    # audit exists to find, in the audit itself.
    #
    # So a timeout now asserts NOTHING. It is named in the output so the run's coverage is visible
    # rather than assumed, and it never lands in the frozen list in either direction.
    if [[ $rc -eq 124 ]]; then noverdict+=("$b"); continue; fi
    [[ -s "$elog" ]] || continue                          # never touched a launcher: wrong mutation
    invoked=$((invoked + 1))
    [[ $rc -eq 0 ]] && blind+=("$b")
  done
  # ── A BLIND VERDICT FROM THE SWEEP IS NOT TRUSTED UNTIL IT REPRODUCES ALONE ──────────────────
  #
  # Measured 2026-08-13: with the timeout hole closed the population went stable at 105, but the
  # blind count still moved, 15 then 14. The cause is NOT a flaky instrument and NOT a flaky gate —
  # both suspects failed 5 times out of 5 when run ALONE, with durations steady to the second
  # (61s, 61s, 61s, 61s, 61s). They only ever pass INSIDE the sweep.
  #
  # Because gates hard-code TCP ports and several share one: 8768 is used by three wired gates,
  # 8766/8767/8769 by two each. A server left listening by a neighbour answers the next gate's
  # requests, so a gate whose own launcher is broken can still get 200s back and pass. This project
  # already has that lesson written down as "a probe measures the PORT, not the lane".
  #
  # So a sweep verdict of "blind" is a CANDIDATE. Re-run each one alone, and classify only what
  # reproduces. Costs a handful of runs, not another full pass.
  if [[ ${#blind[@]} -gt 0 ]]; then
    confirmed=()
    for b in "${blind[@]}"; do
      : > "$elog"
      if timeout "$ECAP" "$ROOT/tests/e2e/$b" >/dev/null 2>&1 && [[ -s "$elog" ]]; then
        confirmed+=("$b")
      else
        echo "  (sweep said $b was blind; alone it is not — neighbour interference, not a verdict)"
      fi
    done
    blind=(${confirmed[@]+"${confirmed[@]}"})
  fi

  erestore; trap - EXIT HUP INT TERM; rm -f "$elog"

  echo "no-orphan-gates --evidence: $invoked wired gate(s) INVOKED a launcher; ${#blind[@]} passed anyway"
  if [[ ${#noverdict[@]} -gt 0 ]]; then
    echo "  NO VERDICT for ${#noverdict[@]} gate(s) — cut off at ${ECAP}s, so this run learned nothing about them:"
    for b in "${noverdict[@]}"; do echo "    $b"; done
    echo "  They are not counted healthy and not counted blind. Raise SSC_EVIDENCE_CAP to cover them."
  fi
  for b in ${blind[@]+"${blind[@]}"}; do
    grep -qxF "$b" <<<"$declared" && continue
    echo "FAIL  it RAN the launcher, the launcher FAILED, and the gate passed: $b" >&2
    echo "        Nothing has shown this gate can fail. Either it swallows the failure — fix that —" >&2
    echo "        or it declines to judge on purpose, in which case declare it in" >&2
    echo "        GREEN_WITHOUT_LAUNCHER with the REASON, so the next reader can tell the two apart." >&2
    efail=1
  done
  while read -r b; do
    [[ -n "$b" ]] || continue
    printf '%s\n' ${blind[@]+"${blind[@]}"} | grep -qxF "$b" || {
      echo "FAIL  $b now fails when the launcher does — DELETE it from GREEN_WITHOUT_LAUNCHER" >&2
      echo "        (an exemption that outlives its need is the same rot as a stale known-red)" >&2
      efail=1; }
  done <<<"$declared"

  [[ $efail -eq 0 ]] || { echo "" >&2; echo "no-orphan-gates --evidence: FAIL" >&2; exit 1; }
  echo "no-orphan-gates --evidence: PASS ($((invoked - ${#blind[@]})) proved they can fail, ${#blind[@]} declared, ${#noverdict[@]} no verdict)"
  exit 0
fi

observed="$(mktemp)"; trap 'rm -f "$observed"' EXIT
orphans_of "$ROOT" > "$observed"

frozen="$(mktemp)"; printf '%s\n' "$FROZEN" | grep -v '^$' | LC_ALL=C sort > "$frozen"
tools="$(mktemp)";  printf '%s\n' "$MANUAL_TOOLS" | grep -v '^$' | cut -f1 | LC_ALL=C sort > "$tools"
debt="$(mktemp)"
trap 'rm -f "$observed" "$frozen" "$tools" "$debt"' EXIT

# The tools are subtracted BEFORE anything is compared: they are not gates, so they are not debt.
# Everything below therefore talks about gates only, and the number it prints can reach zero.
LC_ALL=C comm -23 "$observed" "$tools" > "$debt"

n_obs="$(wc -l < "$debt" | tr -d ' ')"
echo "no-orphan-gates: $(subjects_of "$ROOT" | wc -l | tr -d ' ') scripts, $n_obs GATES invoked by nothing, $(wc -l < "$frozen" | tr -d ' ') frozen, $(wc -l < "$tools" | tr -d ' ') not gates"

fail=0
while read -r p; do
  [[ -n "$p" ]] || continue
  grep -qxF "$p" "$frozen" || {
    echo "FAIL  NEW orphan — nothing invokes it, so it reports green by not running:" >&2
    echo "        $p" >&2
    echo "        Wire it into scripts/smoke-ci.ssc (per push) or a tier-2 job in ci.yml, and CHECK" >&2
    echo "        that job's \`if:\` and the workflow's \`on:\` — v1-jit-size.sh was once wired into a" >&2
    echo "        workflow_dispatch-only job and still ran essentially never. Or delete it." >&2
    fail=1; }
done < "$debt"

while read -r p; do
  [[ -n "$p" ]] || continue
  if ! grep -qxF "$p" "$observed"; then
    if [[ -f "$p" ]]; then
      echo "FAIL  frozen orphan is now invoked — DELETE it from FROZEN: $p" >&2
      echo "        (an exemption that outlives its need is the same rot as a stale known-red)" >&2
    else
      echo "FAIL  frozen orphan no longer exists — DELETE it from FROZEN: $p" >&2
    fi
    fail=1
  fi
done < "$frozen"

# The tools list obeys the same ratchet rules as FROZEN, for the same reason: an exemption that
# outlives its need is a stale known-red. A tool that something starts invoking is no longer swept up
# by the extension filter and does not need the entry; a tool that stops existing never did.
while read -r p; do
  [[ -n "$p" ]] || continue
  if [[ ! -f "$p" ]]; then
    echo "FAIL  MANUAL_TOOLS names a file that no longer exists — DELETE it: $p" >&2
    fail=1
  elif ! grep -qxF "$p" "$observed"; then
    echo "FAIL  MANUAL_TOOLS entry is now INVOKED by something — DELETE it: $p" >&2
    echo "        The extension filter no longer sweeps it up, so the exemption does nothing." >&2
    fail=1
  fi
done < "$tools"

[[ $fail -eq 0 ]] || { echo "" >&2; echo "no-orphan-gates: FAIL" >&2; exit 1; }
echo "no-orphan-gates: PASS ($n_obs known orphan gates, none new, none stale; $(wc -l < "$tools" | tr -d ' ') non-gates declared)"
