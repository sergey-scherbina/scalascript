# assert.sh — shared assertions for tests/e2e that SAY WHAT FAILED.
#
# WHY. The habit across this directory is a bare `[[ … ]]` on its own line under `set -e`. It does
# fail the script — the exit status is right — but it prints NOTHING: no check name, no expected,
# no got. The log simply stops. That is recorded in [[project_ci_red_192_runs_0716]] as having hidden
# two trivially stale expectations for days and forced a manual bisect.
#
# Measured 2026-07-29 across 102 gates: 63 already report properly, 10 assert only through bare
# `[[ ]]` with no named reporting at all, and 19 mix the two.
#
# Usage — source it, then assert with a NAME:
#
#   . "$(dirname "${BASH_SOURCE[0]}")/lib/assert.sh"
#   assert_eq   "schema row"      "$(cat "$tmp/out")"  "$expected"
#   assert_empty "no stderr"      "$tmp/err"
#   assert_contains "banner"      "$out"               "ready in"
#   assert_done                  # prints the summary and sets the exit status
#
# Every failure prints name / expected / got and keeps going, so ONE run tells you everything that
# is wrong instead of the first thing. `assert_done` is what fails the script.
_assert_fail=0
_assert_pass=0

_assert_report() { # _assert_report <name> <expected> <got>
  printf 'FAIL  %s\n' "$1" >&2
  printf '        expected: %s\n' "$2" >&2
  printf '        got:      %s\n' "$3" >&2
  _assert_fail=$((_assert_fail + 1))
}

assert_eq() { # assert_eq <name> <got> <expected>
  if [ "$2" = "$3" ]; then _assert_pass=$((_assert_pass + 1))
  else _assert_report "$1" "$3" "$2"; fi
}

assert_contains() { # assert_contains <name> <haystack> <needle>
  case "$2" in
    *"$3"*) _assert_pass=$((_assert_pass + 1)) ;;
    *) _assert_report "$1" "output containing '$3'" "$2" ;;
  esac
}

assert_empty() { # assert_empty <name> <file>
  if [ ! -s "$2" ]; then _assert_pass=$((_assert_pass + 1))
  else _assert_report "$1" "empty $2" "$(head -c 400 "$2")"; fi
}

assert_absent() { # assert_absent <name> <path>
  if [ ! -e "$2" ]; then _assert_pass=$((_assert_pass + 1))
  else _assert_report "$1" "no such path: $2" "exists"; fi
}

assert_done() { # assert_done [label]
  local label="${1:-$(basename "${0:-e2e}")}"
  if [ "$_assert_fail" -ne 0 ]; then
    printf '%s: %s ok, %s FAIL\n' "$label" "$_assert_pass" "$_assert_fail" >&2
    exit 1
  fi
  printf '%s: %s ok\n' "$label" "$_assert_pass"
}
