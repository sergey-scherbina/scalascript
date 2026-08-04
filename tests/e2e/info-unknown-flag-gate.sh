#!/usr/bin/env bash
# `ssc info` must not turn a FLAG into a PATH.
#
# `ssc-tools info --front-report FILE` used to fall through the argument loop's catch-all and be
# collected as a path. The two diagnostics that followed each pointed away from the cause:
# "ignoring 1 extra path(s)" called the real FILE the extra one, and "file not found:
# --front-report" called a FLAG a file. A corpus sweep built on that reads as a clean empty result —
# the entry's author nearly recorded "F declines nothing" from it.
# BUGS `ssc-tools-info-rejects-front-report-at-exit-0`.
#
# Two properties, and the second is the general one: the same flag must work on BOTH launchers, and
# an unknown flag must be REJECTED as a flag rather than mistaken for a file.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
FILE="tests/conformance/std-index.ssc"
fails=0

# 1. both routes answer identically — the tools route knew nothing about the flag before.
a="$(SSC_NO_BUILD_CHECK=1 timeout 180 ./bin/ssc      info --front-report "$FILE" 2>/dev/null | tail -1)"
b="$(SSC_NO_BUILD_CHECK=1 timeout 180 ./bin/ssc-tools info --front-report "$FILE" 2>/dev/null | tail -1)"
if [[ -n "$a" && "$a" == "$b" ]]; then
  echo "ok   both launchers agree: $a"
else
  echo "FAIL --front-report differs between launchers"
  echo "  ssc:       ${a:-<empty>}"
  echo "  ssc-tools: ${b:-<empty>}"
  fails=$((fails + 1))
fi

# 2. an unknown flag is rejected AS A FLAG, non-zero, and the message must not send the reader
#    after a missing file — that misdirection is the defect, not the exit code alone.
out="$(SSC_NO_BUILD_CHECK=1 timeout 180 ./bin/ssc-tools info --definitely-not-a-flag "$FILE" 2>&1)"; rc=$?
if [[ $rc -ne 0 ]] && printf '%s' "$out" | grep -q "unknown flag" && ! printf '%s' "$out" | grep -q "file not found"; then
  echo "ok   an unknown flag is named as a flag (exit $rc)"
else
  echo "FAIL an unknown flag was not rejected as a flag"
  echo "  exit: $rc"
  printf '%s\n' "$out" | sed 's/^/  | /'
  fails=$((fails + 1))
fi

echo
if [[ $fails -eq 0 ]]; then
  echo "info-unknown-flag-gate: OK"
  exit 0
fi
echo "info-unknown-flag-gate: FAIL ($fails)"
exit 1
