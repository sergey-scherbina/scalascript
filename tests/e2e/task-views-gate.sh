#!/usr/bin/env bash
#
# task-views-gate.sh — the generated regions inside TASK/*.md agree with the module boards.
#
#   ./tests/e2e/task-views-gate.sh              # check the working tree
#   ./tests/e2e/task-views-gate.sh --self-test  # prove the check can FAIL, then check
#
# A TASK document is prose with a generated hole in it (scripts/task-views). The prose is the
# valuable half and nothing here touches it. The hole is an index into the boards, and this gate is
# what stops it from becoming a stale second copy — which is the entire objection that was raised
# against per-type files in the first place.
#
# --self-test exists because of this project's most expensive recurring defect: apparatus that is
# green because it cannot see. A golden diff over ZERO regions passes forever and proves nothing, so
# the self-test corrupts a region and asserts the gate goes red on it.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

self_test() {
  echo "── self-test: the gate must FAIL on a corrupted region ──"
  local f
  f="$(grep -rl 'task-views:begin' TASK/*.md 2>/dev/null | head -1 || true)"
  if [ -z "$f" ]; then
    echo "✗ self-test cannot run: no TASK/*.md contains a generated region."
    echo "  A gate with nothing to check is not a passing gate — it is an absent one."
    return 1
  fi
  local backup; backup="$(mktemp)"
  cp "$f" "$backup"
  # shellcheck disable=SC2064
  trap "cp '$backup' '$f'; rm -f '$backup'" RETURN
  printf '%s\n' "| \`not-a-real-slug\` | \`nowhere/BUGS.md\` | open | — |" >> "$f"
  # The injected row lands after the end marker, so move it inside: append before the last marker.
  python3 - "$f" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1]); lines = p.read_text().split("\n")
bogus = lines.pop(-1) if lines and lines[-1] == "" else None
row = None
for i in range(len(lines) - 1, -1, -1):
    if lines[i].startswith("| `not-a-real-slug`"):
        row = lines.pop(i); break
for i, ln in enumerate(lines):
    if ln.strip() == "<!-- task-views:end -->":
        lines.insert(i, row); break
if bogus is not None: lines.append(bogus)
p.write_text("\n".join(lines))
PY
  if scripts/task-views --check >/dev/null 2>&1; then
    echo "✗ SELF-TEST FAILED: a bogus row inside a generated region did not make the gate red."
    echo "  The gate does not distinguish the two states it exists to separate."
    return 1
  fi
  echo "✓ self-test: corrupted region detected"
  return 0
}

if [ "${1:-}" = "--self-test" ]; then
  self_test || exit 1
  echo
fi

echo "── checking TASK/ generated regions against the boards ──"
if ! scripts/task-views --check; then
  cat <<'EOF'

The generated index in a TASK document no longer matches the module boards.
This is not a merge conflict to hand-resolve — regenerate it:

    scripts/task-views --write

Only the region BETWEEN the markers is generated. Prose outside them is yours and is never touched.
EOF
  exit 1
fi
