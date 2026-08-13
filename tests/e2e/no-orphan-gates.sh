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
components-smoke.sh
f-char-escape-gate.sh
f-front-delegation-visible.sh
fm-routes-smoke.sh
health-defaults-smoke.sh
import-parse-error-gate.sh
info-unknown-flag-gate.sh
install-sh-reports-failure-gate.sh
int-imported-registry-gate.sh
js-char-classification-parity.sh
jvm-json-import-gate.sh
keyword-import-missing-module.sh
member-beats-toplevel-gate.sh
middleware-smoke.sh
multi-name-val-gate.sh
no-paren-sibling-gate.sh
object-var-mutation-gate.sh
pattern-undefined-name-gate.sh
render-smoke.sh
req-type-collision-v2-smoke.sh
route-params-v2-smoke.sh
selfhost-front-gate.sh
serve-view-frontend-v2-smoke.sh
triple-quote-trailing-quote-gate.sh
typeerr-names-both-types.sh
upload-smoke.sh
v21-build-jvm-smoke.sh
v21-native-content-smoke.sh
v21-native-doc-render-smoke.sh
v21-portable-gates-smoke.sh
v21-typeclass-dictionary-smoke.sh
v21-unhandled-effect-smoke.sh
validation-smoke.sh
wc-card-smoke.sh
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
callers_of() { # callers_of <root> <basename> -> paths, one per line (empty = orphan)
  local r="$1" b="$2" out
  out="$(grep -rlF --exclude-dir=.git --exclude="$b" --exclude="$SELF" "$b" \
           "$r/.github" "$r/scripts" "$r/tests" 2>/dev/null || true)"
  printf '%s' "$out" | { grep -v '\.md$' || true; }
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
  printf 'run tests/e2e/wired-example.sh\n'                > "$TMP/scripts/caller"
  printf 'see tests/e2e/orphan-example.sh for details\n'   > "$TMP/prose.md"

  probe() { # probe <root> <basename> -> "wired" | "orphan"
    local r="$1" b="$2" hits
    hits="$(callers_of "$r" "$b")"
    [[ -n "$hits" ]] && printf wired || printf orphan
  }
  [[ "$(probe "$TMP" wired-example.sh)"  == wired  ]] \
    || { echo "SELF-TEST FAIL: a script named by an executable caller was called an orphan" >&2; exit 1; }
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
  echo "no-orphan-gates self-test: PASS (an executable caller counts, a .md mention does not)"
  # falls through to the census, like v1-jit-size.sh: one invocation does both
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
