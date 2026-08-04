#!/usr/bin/env bash
# uniml-standalone-tests — run the UniML standalone build's tests.
#
# WHY THIS EXISTS. Until 2026-08-04 `grep uniml .github/workflows/` returned
# NOTHING: the standalone build (`cd uniml && sbt test`, ten projects) was run by
# no automated gate at all, and the root build's `sbt — compile and test` job is
# `workflow_dispatch` only, taking over three hours. So any of the ten UniML
# projects could be broken on a push and nothing would say so.
#
# It runs the STANDALONE build deliberately, not `unimlScala/test` in the root
# one. That build is UniML's own proof that it stands alone — it must compile
# with zero dependency on the ScalaScript trees — so running it here checks the
# tests and that property in the same command.
#
# COST, measured rather than assumed: 27.9s wall from `clean` on a warm
# dependency cache, ten projects, ~300 tests. smoke's budget is 500s and was
# using 234s before this check.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT/uniml"

if ! command -v sbt > /dev/null 2>&1; then
  echo "uniml-standalone-tests: sbt not on PATH" >&2
  exit 2
fi

log=$(mktemp -t uniml-standalone.XXXXXX)
trap 'rm -f "$log"' EXIT

if sbt -batch test > "$log" 2>&1; then
  passed=$(grep -c "All tests passed" "$log" || true)
  # Ten projects report separately; a build that silently stopped aggregating
  # would still exit 0 with FEWER reports, which is the failure this counts.
  if [ "$passed" -lt 8 ]; then
    echo "uniml-standalone-tests: only $passed project(s) reported passing — expected the whole aggregate" >&2
    grep -E "Tests: |error" "$log" | tail -20 >&2
    exit 1
  fi
  echo "uniml-standalone-tests: OK ($passed projects)"
  exit 0
fi

echo "uniml-standalone-tests: FAILED" >&2
grep -E "\*\*\* FAILED|Tests: |^\[error\]" "$log" | tail -25 >&2
exit 1
