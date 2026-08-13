#!/usr/bin/env bash
#
# no-orphan-gates.sh — a NEW gate in tests/e2e must be invoked by something.
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
# a few weeks. So: freeze today's orphans BY NAME, fail on a NEW one, and let the list only shrink.
# Same shape as `v1-jit-size.sh`'s frozen debt and the negtc release gate: freeze the hard invariant,
# derive the rest.
#
# WHAT COUNTS AS "INVOKED". A reference to the script's basename from anything executable —
# `.github/workflows/`, `scripts/`, another `tests/e2e/` script. Prose in a `.md` does NOT count:
# documentation is how these rot in the first place, cited but never run.
#
# WHAT THIS DOES NOT DO. It does not check that a gate PASSES, or that the suite it is wired into
# actually runs. Both are real and both have bitten: `v1-jit-size.sh` was first wired into ci.yml's
# `sbt` job, which is `workflow_dispatch`-only in a workflow with no `push:` trigger, so it was
# "wired" and still ran essentially never. Wiring is necessary, not sufficient — check the job's
# `if:` and the workflow's `on:` yourself.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

# Frozen orphans: gates that were already unwired on 2026-08-13, listed so this gate is green on
# arrival. A gate red the day it lands is disabled within a day. DELETE from this list when you wire
# or remove one — the gate fails if a frozen entry stops being an orphan, so the list cannot rot into
# a permanent exemption.
read -r -d '' FROZEN <<'EOF' || true
actors-pingpong-smoke.sh
install-sh-reports-failure-gate.sh
negtc-shard-gate.sh
render-smoke.sh
serve-view-frontend-v2-smoke.sh
typeerr-names-both-types.sh
v21-build-jvm-smoke.sh
v21-native-content-smoke.sh
v21-native-doc-render-smoke.sh
v21-portable-gates-smoke.sh
v21-typeclass-dictionary-smoke.sh
v21-unhandled-effect-smoke.sh
wc-card-smoke.sh
EOF

# ── THE SECOND AXIS: can a WIRED gate fail at all? ──────────────────────────────────────────────
#
# An orphan reports green by NOT RUNNING. A vacuous gate reports green by NOT LOOKING. The same
# defect measured two ways, so they live in ONE file: two frozen lists over one population kept in
# two places is a second decision site, and this repo has paid for those repeatedly.
#
# TWO DEPTHS, ONE TABLE. The wired axis is cheap (~25 s) and runs on every push. The evidence axis
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
callers_of() { # callers_of <root> <basename> -> "path:line:text", one per line (empty = orphan)
  local r="$1" b="$2" out
  out="$(grep -rnF --exclude-dir=.git --exclude="$b" --exclude="$SELF" "$b" \
           "$r/.github" "$r/scripts" "$r/tests" 2>/dev/null || true)"
  printf '%s' "$out" | { grep -v '^[^:]*\.md:' || true; } | while IFS= read -r hit; do
    [[ -n "$hit" ]] || continue
    local text code
    text="${hit#*:}"; text="${text#*:}"   # strip `path:lineno:`
    code="${text%%#*}"; code="${code%%//*}"
    [[ "$code" == *"$b"* ]] && printf '%s\n' "$hit"
  done
  return 0
}

# ── self-test: a detector only ever observed staying quiet is not a detector ─────────────────────
# Asserts BOTH verdicts against files it creates itself, because the interesting failure mode here
# is a search that quietly matches nothing — which is exactly how the thing being detected survives.
if [[ "${1:-}" == "--self-test" ]]; then
  TMP="$(mktemp -d "${TMPDIR:-/tmp}/no-orphan-selftest.XXXXXX")"
  trap 'rm -rf "$TMP"' EXIT
  mkdir -p "$TMP/tests/e2e" "$TMP/scripts"
  printf '#!/usr/bin/env bash\ntrue\n' > "$TMP/tests/e2e/wired-example.sh"
  printf '#!/usr/bin/env bash\ntrue\n' > "$TMP/tests/e2e/orphan-example.sh"
  printf '#!/usr/bin/env bash\ntrue\n' > "$TMP/tests/e2e/commented-example.sh"
  printf '#!/usr/bin/env bash\ntrue\n' > "$TMP/tests/e2e/trailing-example.sh"
  printf 'run tests/e2e/wired-example.sh\n'                > "$TMP/scripts/caller"
  # THE PROSE FIXTURE MUST LIVE WHERE THE SEARCH LOOKS. It was `$TMP/prose.md`, at the root — and
  # `callers_of` only reads `.github`, `scripts` and `tests`, so that file was never opened and the
  # ".md does not count" assertion passed whether or not the `.md` filter existed. A probe whose
  # subject is unreachable WITHOUT the thing under test measures nothing.
  printf 'see tests/e2e/orphan-example.sh for details\n'   > "$TMP/tests/prose.md"
  # A comment is prose that happens to live in a script — the case that made this fix necessary.
  printf '# see tests/e2e/commented-example.sh for the shape\n' > "$TMP/scripts/mentions"
  # …and the opposite direction, so the fix cannot be "drop anything near a #".
  printf 'run tests/e2e/trailing-example.sh   # why we run it\n' > "$TMP/scripts/trailing-caller"

  probe() { # probe <root> <basename> -> "wired" | "orphan"
    local r="$1" b="$2" hits
    hits="$(callers_of "$r" "$b")"
    [[ -n "$hits" ]] && printf wired || printf orphan
  }
  [[ "$(probe "$TMP" wired-example.sh)"  == wired  ]] \
    || { echo "SELF-TEST FAIL: a script named by an executable caller was called an orphan" >&2; exit 1; }
  [[ "$(probe "$TMP" commented-example.sh)" == orphan ]] \
    || { echo "SELF-TEST FAIL: a script named ONLY by a COMMENT was called wired. Excluding .md is" >&2
         echo "  not enough — prose also lives inside scripts, and that is how three real orphans" >&2
         echo "  (bytecode-fallback-visible, negtc-shard-gate, ssc1-front-annotation) read as wired." >&2; exit 1; }
  [[ "$(probe "$TMP" trailing-example.sh)" == wired ]] \
    || { echo "SELF-TEST FAIL: a REAL call carrying a trailing comment was called an orphan — the" >&2
         echo "  comment rule is over-strict and would freeze working gates as debt." >&2; exit 1; }
  [[ "$(probe "$TMP" orphan-example.sh)" == orphan ]] \
    || { echo "SELF-TEST FAIL: a script named ONLY by a .md was called wired — prose is not a caller," >&2
         echo "  and treating it as one is how these gates rot: cited everywhere, run nowhere." >&2; exit 1; }

  # THIS GATE MUST NOT COUNT ITSELF. Every frozen name is a literal string in this file; without
  # excluding SELF the search finds it and every orphan reads as wired. Measured, not imagined: the
  # first run of this gate reported 1 orphan out of 183 and asked for 38 frozen entries to be
  # deleted as "now invoked".
  cp "$ROOT/tests/e2e/$SELF" "$TMP/tests/e2e/$SELF"
  printf 'orphan-example.sh\n' >> "$TMP/tests/e2e/$SELF"
  [[ "$(probe "$TMP" orphan-example.sh)" == orphan ]] \
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
  for g in "$ROOT"/tests/e2e/*.sh; do
    b="$(basename "$g")"
    [[ "$b" == "$SELF" ]] && continue
    [[ -n "$(callers_of "$ROOT" "$b")" ]] || continue    # an orphan's evidence is moot until wired
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
for g in tests/e2e/*.sh; do
  b="$(basename "$g")"
  # `--exclude` drops the script's own file, so a gate naming itself in a usage line is not its own
  # caller. Same helper as the self-test asserts, by construction.
  [[ -n "$(callers_of "$ROOT" "$b")" ]] && continue
  printf '%s\n' "$b" >> "$observed"
done
sort -o "$observed" "$observed"

frozen="$(mktemp)"; printf '%s\n' "$FROZEN" | grep -v '^$' | sort > "$frozen"
trap 'rm -f "$observed" "$frozen"' EXIT

n_obs="$(wc -l < "$observed" | tr -d ' ')"
echo "no-orphan-gates: $(ls tests/e2e/*.sh | wc -l | tr -d ' ') scripts, $n_obs invoked by nothing, $(wc -l < "$frozen" | tr -d ' ') frozen"

fail=0
while read -r b; do
  [[ -n "$b" ]] || continue
  grep -qxF "$b" "$frozen" || {
    echo "FAIL  NEW orphan — nothing invokes it, so it reports green by not running:" >&2
    echo "        tests/e2e/$b" >&2
    echo "        Wire it into scripts/smoke-ci.ssc (per push) or a tier-2 job in ci.yml, and CHECK" >&2
    echo "        that job's \`if:\` and the workflow's \`on:\` — v1-jit-size.sh was once wired into a" >&2
    echo "        workflow_dispatch-only job and still ran essentially never. Or delete it." >&2
    fail=1; }
done < "$observed"

while read -r b; do
  [[ -n "$b" ]] || continue
  if ! grep -qxF "$b" "$observed"; then
    if [[ -f "tests/e2e/$b" ]]; then
      echo "FAIL  frozen orphan is now invoked — DELETE it from FROZEN: $b" >&2
      echo "        (an exemption that outlives its need is the same rot as a stale known-red)" >&2
    else
      echo "FAIL  frozen orphan no longer exists — DELETE it from FROZEN: $b" >&2
    fi
    fail=1
  fi
done < "$frozen"

[[ $fail -eq 0 ]] || { echo "" >&2; echo "no-orphan-gates: FAIL" >&2; exit 1; }
echo "no-orphan-gates: PASS ($n_obs known orphans, none new, none stale)"
