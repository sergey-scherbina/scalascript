#!/usr/bin/env bash
#
# silent-assertion-gate.sh — no NEW gate in tests/e2e may assert without saying what failed.
#
# THE TRAP. The habit here is a bare `[[ … ]]` on its own line under `set -e`. The exit status is
# correct, so the gate does fail — but it prints NOTHING: no check name, no expected, no got. The
# log just stops at an unexplained non-zero. [[project_ci_red_192_runs_0716]] records what that
# costs: two trivially stale expectations hidden for days and a manual bisect to find them.
#
# WHAT WAS ACTUALLY MEASURED, because the rhetoric was worse than the truth. Across 102 gates on
# 2026-07-29:
#
#     63  already report properly (name / expected / got)
#     19  mixed — some named reporting, some bare
#     10  assert ONLY through bare [[ ]], with no named reporting anywhere
#     10  no explicit assertions (smoke-only: they catch crashes, not wrong output)
#      0  incapable of failing
#
# That last number matters and it corrects an earlier claim of mine. The two suspects —
# ssc1-front-annotation.sh and v2-front-coverage.sh — have no `set -e`, but their last line is
# `[[ $fail -eq 0 ]]`, and a script's exit status IS its last command's. They are correct, and they
# print a summary first. Not every bare `[[ ]]` is a defect: as the FINAL status line after a
# printed summary it is the right idiom.
#
# So this gate targets the narrow real thing: an INLINE bare `[[ ]]` assertion in a file that never
# names a failure. It freezes today's offenders by name and fails on an eleventh — and, like a
# known-red, it also fails when a frozen file stops offending, so the list can only shrink.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

# ── self-test: prove the helper this gate points people at actually reports and actually fails ──
# A helper only ever observed staying quiet is not a helper. Runs assert.sh in a subshell so its
# `exit 1` cannot take this script with it.
if [ "${1:-}" = "--self-test" ]; then
  out="$(bash -c '
    . "'"$ROOT"'/tests/e2e/lib/assert.sh"
    assert_eq       "equal values"   "a"      "a"
    assert_eq       "differing"      "got-X"  "want-Y"
    assert_contains "missing needle" "hello"  "zzz"
    assert_done     "selftest"
  ' 2>&1)" && rc=0 || rc=$?     # NOT `; rc=$?` — under set -e the failing substitution kills us first
  [ "$rc" -ne 0 ] || { echo "SELF-TEST FAIL: assert_done exited 0 with 2 failures" >&2; exit 1; }
  for needle in "FAIL  differing" "expected: want-Y" "got:      got-X" "FAIL  missing needle" "1 ok, 2 FAIL"; do
    case "$out" in *"$needle"*) ;; *) echo "SELF-TEST FAIL: report missing '$needle'" >&2
                                     printf '%s\n' "$out" >&2; exit 1;; esac
  done
  ok="$(bash -c '. "'"$ROOT"'/tests/e2e/lib/assert.sh"; assert_eq "fine" "x" "x"; assert_done "selftest"' 2>&1)" && rc2=0 || rc2=$?
  [ "$rc2" -eq 0 ] || { echo "SELF-TEST FAIL: all-passing run exited $rc2" >&2; exit 1; }
  echo "silent-assertion-gate self-test: PASS (helper reports name/expected/got and fails; clean run exits 0)"
  exit 0
fi

# Frozen debt: gates whose only assertions are bare `[[ ]]`. Shrinking this is the point.
read -r -d '' FROZEN <<'EOF' || true
v2-swiftui-apple.sh
v21-default-launcher-cutover-smoke.sh
v21-explicit-graph-provider-smoke.sh
v21-explicit-nfc-provider-smoke.sh
v21-explicit-pdf-provider-smoke.sh
v21-explicit-quoted-tools-smoke.sh
v21-explicit-x402-tools-smoke.sh
v21-native-plugin-boundary-smoke.sh
v21-plugin-backend-isolation-smoke.sh
v21-runtime-taxonomy-smoke.sh
EOF

# A file "reports" if it ever prints a named failure with context.
reports()  { grep -qE 'expected=|expected:|got=|got:|FAIL' "$1"; }
# An INLINE bare `[[ ]]` — excluding the final-status idiom, which is the last significant line.
inline_bare() {
  local f="$1" last
  last="$(grep -nvE '^\s*(#|$)' "$f" | tail -1 | cut -d: -f1)"
  awk -v last="$last" 'NR!=last && /^[[:space:]]*!?[[:space:]]*\[\[ .* \]\][[:space:]]*$/ {n++} END{print n+0}' "$f"
}

# Any bare `[[ ]]` at all, including the final-status one.
any_bare() { grep -cE '^[[:space:]]*!?[[:space:]]*\[\[ .* \]\][[:space:]]*$' "$1" || true; }

offenders=""
for f in "$ROOT"/tests/e2e/*.sh; do
  b="$(basename "$f")"
  [ "$b" = "silent-assertion-gate.sh" ] && continue
  reports "$f" && continue
  # A file that never names a failure is an offender if it asserts with a bare `[[ ]]` ANYWHERE.
  # The final-status idiom is only acceptable BECAUSE a summary is printed just before it — without
  # that summary it exits non-zero saying nothing, which is the whole defect. Checking `inline_bare`
  # alone let exactly that case through; found by probing this gate rather than by reading it.
  if [ "$(any_bare "$f")" -gt 0 ]; then offenders="$offenders$b "; fi
done

fail=0
for b in $offenders; do
  case " $(printf '%s' "$FROZEN" | tr '\n' ' ') " in
    *" $b "*) ;;
    *) printf 'FAIL  NEW gate asserts silently: %s\n' "$b" >&2
       printf '        Its bare `[[ … ]]` assertions fail without printing a name, an expected or\n' >&2
       printf '        a got — the log just stops. Source tests/e2e/lib/assert.sh and use\n' >&2
       printf '        assert_eq / assert_empty / assert_contains / assert_done instead.\n' >&2
       fail=1 ;;
  esac
done

while read -r b; do
  [ -n "${b:-}" ] || continue
  case " $offenders " in
    *" $b "*) ;;
    *) printf 'FAIL  frozen gate no longer asserts silently — DELETE it from FROZEN: %s\n' "$b" >&2
       printf '        (an exemption that outlives its need rots exactly like a stale known-red)\n' >&2
       fail=1 ;;
  esac
done <<< "$FROZEN"

[ "$fail" -eq 0 ] || { printf '\nsilent-assertion-gate: FAIL\n' >&2; exit 1; }
printf 'silent-assertion-gate: PASS (%s frozen silent gate(s), none new, none stale)\n' \
  "$(printf '%s' "$FROZEN" | grep -c . || true)"
