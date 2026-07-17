#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TRACKED_LAUNCHERS=(bin/ssc)

# Run after cli/installBin or install.sh. git diff performs the comparison and
# prints the exact patch before this gate classifies the result.
if git -C "$ROOT" diff --exit-code -- "${TRACKED_LAUNCHERS[@]}"; then
  printf 'staged-launchers-clean: PASS\n'
else
  printf 'staged-launchers-clean: FAIL: generated launchers differ from tracked bytes\n' >&2
  exit 1
fi
