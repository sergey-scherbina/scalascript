#!/usr/bin/env bash
#
# `bugs-report` must keep noticing entries whose HEADING says fixed while their header does not.
#
# WHY THIS IS A GATE AND NOT A NUMBER. The drift count is 118 today and every migration lowers it,
# so freezing it would make the gate red for doing the right thing — the exact mistake the negtc
# gate made when it froze corpus counts that breadth reclassifies. What must not rot is the
# DETECTOR: if it silently stops matching, `--status fixed` goes back to under-reporting by ~118
# and nothing on screen says so. So this plants entries with known answers and checks all four.
#
# The third fixture is not hypothetical. The first version of the detector used `\bfixed\b`, which
# matches the FIELD NAME inside the slug `bugs-index-fixed-in-checks-resolvable-not-reachable`
# (`-` is a word boundary) — a legitimately-open entry that talks ABOUT `fixed-in`. It shipped one
# permanent false positive until `(?!-)` was added, and a report with a standing false positive is
# a report people learn to skip.
#
# Usage: tests/e2e/bugs-status-drift-gate.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/ssc-drift.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

cat > "$TMP/BUGS.md" <<'EOF'
# planted fixtures

## drift-entry — `fixed` (2026-01-01, `deadbeef`)
<!-- status: unknown
     lane: int
     area: front -->

Heading claims fixed, header does not. MUST be detected.

## honest-open-entry — the thing is still broken
<!-- status: open
     lane: int
     area: front -->

Says nothing about being fixed. MUST NOT be detected.

## a-slug-with-fixed-in-checks — a gate reads `fixed-in` wrongly
<!-- status: open
     lane: apparatus
     area: build -->

The word `fixed` appears only as part of the field name `fixed-in`. MUST NOT be detected —
this is the false positive the detector shipped with.

## honest-fixed-entry — `fixed` (2026-01-02)
<!-- status: fixed
     lane: int
     area: front
     fixed-in: 0000000000000000000000000000000000000000 -->

Heading and header agree. MUST NOT be detected.
EOF

out="$("$ROOT/scripts/bugs-report" --file "$TMP/BUGS.md" 2>&1 || true)"

fail() { printf 'bugs-status-drift-gate: FAIL — %s\n\n--- report ---\n%s\n' "$1" "$out" >&2; exit 1; }

# 1. it must report drift at all, and count exactly the one planted entry
line="$(printf '%s' "$out" | grep 'status drift' || true)"
[[ -n "$line" ]] || fail 'no "status drift" line at all — the detector is not running'
n="$(printf '%s' "$line" | sed -E 's/.*status drift: ([0-9]+) .*/\1/')"
[[ "$n" == "1" ]] || fail "expected exactly 1 drifting entry, report says $n
  (2 usually means the \`fixed-in\` compound is being matched again — see the header of this file)"

# 2. and it must name the bucket it drifted FROM, so the reader knows what to filter on
printf '%s' "$line" | grep -q 'unknown=1' || fail 'the line does not say which status bucket the drift sits in'

# 3. the clean file must produce no drift line at all — a detector that always fires is not one
cat > "$TMP/clean.md" <<'EOF'
# clean

## honest-open-entry — the thing is still broken
<!-- status: open
     lane: int
     area: front -->
EOF
clean="$("$ROOT/scripts/bugs-report" --file "$TMP/clean.md" 2>&1 || true)"
printf '%s' "$clean" | grep -q 'status drift' && {
  out="$clean"; fail 'a board with no drift still printed a drift line'
}

printf 'bugs-status-drift-gate: OK (detects the planted drift, ignores fixed-in, silent when clean)\n'
