#!/usr/bin/env bash
# v3 SSC3-2 gate — the IR core: round-trip, opcode coverage, verifier.
#
# Three layers, and they check different things:
#   1. the in-process self-test (round-trip as a property, planted defects per validation rule)
#   2. the CLI on a real file, because a gate about a TOOL must RUN that tool (POLICY.md P-6.2) —
#      checking the objects it produces covers neither a crash nor a broken argument path
#   3. the frozen canonical form, so a change to `.ssir` shows up as a reviewable diff instead of
#      moving silently under every gate that compares .ssir
#
# Both halves of layer 1 have been observed FAILING (P-6.1), on 2026-08-01:
#   blinding the register check      -> "rule 1 ... ACCEPTED — the verifier cannot see this defect"
#   making the writer drop NumKind   -> "read(write(m)) == m — structural mismatch"
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2
SC="scala-cli run v3/src --server=false --quiet --"
fail=0

echo "── 1. in-process self-test ─────────────────────────────────────────────"
$SC selftest || fail=1

echo
echo "── 2. the CLI, on a real file ──────────────────────────────────────────"
if $SC check v3/tests/sample.ssir; then echo "  ok   check accepts the sample"; else echo "  FAIL check rejected the sample"; fail=1; fi

# A file the verifier must REFUSE, so layer 2 is not green in both directions. `br 0` at the top
# level of a function leaves a region that does not enclose it.
bad=$(mktemp -t ssc3bad); trap 'rm -f "$bad"' EXIT
cat > "$bad" <<'EOF'
(module
  (consts (int 1))
  (types)
  (globals)
  (prims)
  (funcs
    (func "boom" 0 1
      (br 0)))
  (entry 0))
EOF
if $SC check "$bad" >/dev/null 2>&1; then
  echo "  FAIL check ACCEPTED a module with an escaping br — the CLI is not verifying"
  fail=1
else
  echo "  ok   check refuses an escaping br: $($SC check "$bad" 2>&1 | tail -1)"
fi

echo
echo "── 3. the frozen canonical form ────────────────────────────────────────"
if $SC fmt v3/tests/sample.ssir | diff -q - v3/tests/sample.ssir >/dev/null; then
  echo "  ok   fmt(sample) == sample — the canonical form is stable"
else
  echo "  FAIL the canonical form changed; review the diff and re-freeze deliberately:"
  $SC fmt v3/tests/sample.ssir | diff - v3/tests/sample.ssir | head -20
  fail=1
fi

echo
[ "$fail" = 0 ] && echo "== v3 SSC3-2 gate: GREEN ==" || echo "== v3 SSC3-2 gate: RED =="
exit "$fail"
