#!/usr/bin/env bash
#
# heartbeat-threshold-single-source — the staleness threshold must say the same thing everywhere.
#
# BUGS.md `heartbeat-threshold-stated-in-two-repos`. The number that decides whether a claim is
# ORPHANED is written down in three places across TWO repositories: `scripts/coord-status` (the code
# that actually decides), `AGENTS.md`, and the multi-agent skill inside the `.agents/plugins`
# submodule. Raising it here from 20 to 45 minutes on 2026-07-28 left the submodule saying 20.
#
# That is not a documentation nit. An agent following the skill would declare a claim orphaned at
# minute 21 and take work that AGENTS.md says is still live — the exact collision the claim mutex
# exists to prevent, arrived at by reading the rules rather than by ignoring them.
#
# Duplicated state does not stay consistent by intention (same lesson as
# `claim-ledger-claimfile-scope-drift`). Since the copies cannot be removed from here — one of them
# lives in another repository — the next best thing is that a divergence CANNOT go unnoticed. The
# executable value in `scripts/coord-status` is the source of truth; every prose copy must match it.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fails=0

# ── the source of truth: the comparison the code actually performs ───────────────────────────────
# Deliberately read from the CODE, not from a comment beside it. A comment that drifted from its own
# condition would make this gate certify a number nothing enforces.
# BSD sed has no \+ in basic regex — use [0-9][0-9]* so this works on macOS and Linux alike.
seconds="$(sed -n 's/.*heartbeat_age_seconds" -gt \([0-9][0-9]*\).*/\1/p' "$ROOT/scripts/coord-status" | head -1)"
if [[ -z "$seconds" ]]; then
  echo "FAIL cannot find the staleness comparison in scripts/coord-status"
  echo "     expected a line matching: heartbeat_age_seconds\" -gt <seconds>"
  exit 1
fi
minutes=$(( seconds / 60 ))
echo "source of truth: scripts/coord-status enforces ${seconds}s = ${minutes} min"

# ── every prose copy must agree ──────────────────────────────────────────────────────────────────
# PHRASES, not lines. Two earlier drafts failed in opposite directions and both are worth recording,
# because each would have made this gate useless in its own way:
#
#   1. "any minute-figure on a line mentioning heartbeat" flagged the heartbeat CADENCE ("run this
#      whenever work has been sitting for more than ~10 minutes") — a different quantity that is
#      legitimately not 45. A gate that cries about a correct line is one people learn to ignore.
#   2. Widening the trigger to any `<`/`>` beside a figure then flagged "can't separate confidently
#      in <5 minutes" in AGENTS.md — prose that has nothing to do with claims.
#
# The killer case is a single line in the skill carrying BOTH: "sits for more than ~10 minutes.
# Older than ~20 minutes → treat as potentially [stale]". One clause is right and one is wrong, so
# no line-level rule can be both correct and complete. The patterns below match the PHRASE that
# states the threshold, which is what actually needs to agree.
THRESHOLD_PHRASES=(
  'older than ~?[0-9]{1,3} ?(min|minutes)'                 # "older than 20 minutes"
  'heartbeat ?[>≥] ?~?\*{0,2}[0-9]{1,3} ?(min|minutes)'     # "heartbeat > 20 min"
  'stale[^.]{0,40}?[0-9]{1,3} ?(min|minutes)'              # "stale claims (… 20 min)"
  '^\|.*[<>] ?\*{0,2}~?[0-9]{1,3} ?(min|minutes)'            # triage table rows: "| … | > 20 min | … |"
)

check_file() { # check_file <label> <path>
  local label="$1" path="$2"
  if [[ ! -f "$path" ]]; then
    echo "SKIP $label: not present at $path"
    return
  fi
  local hits="" pat found bad=""
  for pat in "${THRESHOLD_PHRASES[@]}"; do
    found="$(grep -noiE "$pat" "$path" 2>/dev/null || true)"
    [[ -n "$found" ]] && hits+="$found"$'\n'
  done
  hits="$(printf '%s' "$hits" | grep -v '^$' | sort -t: -k1,1n -u || true)"
  if [[ -z "$hits" ]]; then
    echo "ok   $label states no staleness threshold (nothing to disagree with)"
    return
  fi
  bad="$(printf '%s\n' "$hits" | grep -viE "[^0-9]${minutes} ?(min|minutes)" || true)"
  if [[ -n "$bad" ]]; then
    echo "FAIL $label states a staleness threshold that is not ${minutes} min:"
    printf '%s\n' "$bad" | sed 's/^/       /'
    echo "     fix: make it ${minutes}, or better, drop the number and point at scripts/coord-status"
    fails=$((fails + 1))
  else
    echo "ok   $label agrees (${minutes} min)"
  fi
}

# A feature WORKTREE does not get the submodule checked out, so looking only under $ROOT made this
# gate SKIP the one file that was actually wrong — a checker that skips the failing case is worse
# than no checker. Fall back to the primary checkout, which is where the submodule lives.
SKILL_REL=".agents/plugins/multi-agent/commands/multi-agent.md"
skill_path="$ROOT/$SKILL_REL"
if [[ ! -f "$skill_path" ]]; then
  primary="$(git -C "$ROOT" worktree list 2>/dev/null | head -1 | awk '{print $1}')"
  [[ -n "$primary" && -f "$primary/$SKILL_REL" ]] && skill_path="$primary/$SKILL_REL"
fi

check_file "AGENTS.md" "$ROOT/AGENTS.md"
check_file "multi-agent skill (submodule .agents/plugins)" "$skill_path"

# ── the gate must be able to FAIL ────────────────────────────────────────────────────────────────
# A checker only ever observed passing is not a checker. This builds a fixture stating a wrong
# number and asserts the same logic rejects it — so "all files agree" means the comparison ran,
# not that the grep silently matched nothing.
self_dir="$(mktemp -d "${TMPDIR:-/tmp}/heartbeat-threshold.XXXXXX")"
trap 'rm -rf "$self_dir"' EXIT HUP INT TERM
printf 'A claim is stale if its heartbeat is older than 20 minutes.\n' > "$self_dir/wrong.md"
printf 'A claim is stale if its heartbeat is older than %s minutes.\n' "$minutes" > "$self_dir/right.md"

# The self-test's own output is SUPPRESSED. It legitimately prints "FAIL", and a green run that
# contains the word FAIL is read as a red one by every human and every `grep -c FAIL` in this repo.
before="$fails"
check_file "self-test (deliberately wrong fixture)" "$self_dir/wrong.md" >/dev/null
if [[ "$fails" -eq "$before" ]]; then
  echo "FAIL self-test: a fixture saying 20 min was accepted — this gate cannot detect drift"
  exit 1
fi
fails="$before"   # the deliberate failure is not a real one
echo "ok   self-test: the gate rejects a wrong figure and accepts the right one"

check_file "self-test (matching fixture)" "$self_dir/right.md" >/dev/null
if [[ "$fails" -ne "$before" ]]; then
  echo "FAIL self-test: a fixture stating the correct value was rejected — the gate is over-eager"
  exit 1
fi

if [[ "$fails" -ne 0 ]]; then
  echo
  echo "heartbeat-threshold-single-source: $fails file(s) disagree with the enforced threshold."
  echo "See BUGS.md heartbeat-threshold-stated-in-two-repos."
  exit 1
fi
echo "heartbeat-threshold-single-source: all copies agree"
