#!/usr/bin/env bash
#
# The @scalascript/control-direct suite must RUN, and must be green.
#
# WHY THIS EXISTS. c19d42401 (2026-07-15) landed the direct-transform repair together with 496 lines
# of tests — transform.test.js, cli.test.js, package.test.js. Nothing invoked them. `control-direct`
# appeared in no workflow, not in scripts/smoke-ci.ssc, and in no script under scripts/ or tests/e2e/.
# Eleven js-control-direct-* entries in v1/runtime/backend/js/BUGS.md therefore sat `open` for three
# weeks with no signal either way: the work to close them was in fact done, and nobody could tell.
# (v1/runtime/backend/js/BUGS.md js-control-direct-tests-never-run.)
#
# They also could not run as checked out. Every fixture that mentions a marker type pulled in this
# package's own index.d.ts, whose `import type … from "@scalascript/control"` resolves by walking up
# from the PACKAGE ROOT — not from the fixture directory, where the harness had symlinked the sibling.
# So 16 of 39 died on `TS2307` plus a cascade of implicit-any. Fixed by supplying the sibling through
# TypeScript `paths`, which is the mechanism README names and tsconfig.json already used for
# `npm run typecheck`. NOT by adding a dependency: test/package.test.js freezes this package at zero
# local dependencies and rejected exactly that, which is how the design defended itself.
#
# WHAT THIS CHECKS, and why the count is half of it:
#   1. `npm install` ALONE makes the suite runnable — no hand-made symlink, no checkout-specific
#      setup. The clean-room case is the one that rotted.
#   2. zero failures;
#   3. AT LEAST $MIN_TESTS tests actually ran. This is the cell that makes the gate a gate. `node
#      --test` over a glob that matches nothing exits 0, and so does a suite whose files were
#      renamed out from under it — both would read as success. A count floor cannot be satisfied by
#      absence. If tests are deliberately removed, lower the floor in the same commit and say why.
#
# Usage: tests/e2e/js-control-direct-gate.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PKG="$ROOT/v2/host/js/control-direct"

# The floor, not the exact count: adding tests must never fail this gate.
MIN_TESTS=39

if ! command -v npm >/dev/null 2>&1; then
  if [[ "${CI:-}" == "true" ]]; then
    printf 'js-control-direct-gate: FAIL — npm is required on CI and was not found\n' >&2
    exit 1
  fi
  printf 'js-control-direct-gate: SKIP (no npm locally; this gate is enforced on CI)\n'
  exit 0
fi

cd "$PKG"

if ! npm install --no-audit --no-fund >/tmp/js-control-direct-install.log 2>&1; then
  printf 'js-control-direct-gate: FAIL — npm install\n' >&2
  tail -20 /tmp/js-control-direct-install.log >&2
  exit 1
fi

out="$(timeout 600 npm test 2>&1 || true)"

ran="$(printf '%s\n' "$out"  | sed -n 's/^ℹ tests \([0-9][0-9]*\)$/\1/p' | tail -1)"
pass="$(printf '%s\n' "$out" | sed -n 's/^ℹ pass \([0-9][0-9]*\)$/\1/p'  | tail -1)"
fail="$(printf '%s\n' "$out" | sed -n 's/^ℹ fail \([0-9][0-9]*\)$/\1/p'  | tail -1)"

# An unparsed summary is a failure, not a pass. Without this, a runner change that alters the
# summary format would leave all three empty and every comparison below would be skipped.
if [[ -z "$ran" || -z "$pass" || -z "$fail" ]]; then
  printf 'js-control-direct-gate: FAIL — could not read the node --test summary\n' >&2
  printf '%s\n' "$out" | tail -25 >&2
  exit 1
fi

if [[ "$fail" -ne 0 ]]; then
  printf 'js-control-direct-gate: FAIL — %s of %s tests failed\n' "$fail" "$ran" >&2
  printf '%s\n' "$out" | grep -E '^✖' | head -12 >&2
  exit 1
fi

if [[ "$ran" -lt "$MIN_TESTS" ]]; then
  printf 'js-control-direct-gate: FAIL — only %s tests ran, floor is %s.\n' "$ran" "$MIN_TESTS" >&2
  printf '       A suite that stops running is the defect this gate was written for; it does not\n' >&2
  printf '       announce itself, it just goes quiet. If tests were removed on purpose, lower\n' >&2
  printf '       MIN_TESTS in the same commit and say why.\n' >&2
  exit 1
fi

printf 'js-control-direct-gate: OK (%s tests ran from a clean install, %s passed)\n' "$ran" "$pass"
