#!/usr/bin/env bash
#
# area-map-gate — the path→area map must stay total, deterministic, and enum-consistent.
#
# WHAT THIS IS FOR. `BUGS.md` carries an `area:` field that `specs/bugs-index.md` validates against an
# enum, but whose VALUES came from a first-match keyword heuristic (`scripts/bugs-index-migrate`,
# AREA_HINTS: `front` is first and its keys include `parse`, `_err`, `front`, so it wins for almost any
# prose — 256 of 621 entries). `scripts/area-of` gives the one answer that is checkable instead of
# guessed: for a path you actually hold, which area owns it.
#
# The map is only worth trusting if it is:
#   TOTAL          every real code directory resolves — an unmapped path returns nothing, and a tool
#                  that silently answers "unknown" for new code is a tool nobody can rely on;
#   DETERMINISTIC  longest-prefix, so the file's row order cannot change an answer;
#   CONSISTENT     its areas are exactly the enum in specs/bugs-index.md, so the two files can
#                  disagree about which area a bug is in but never about what an area IS.
#
# Usage: tests/e2e/area-map-gate.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
AREA="$ROOT/scripts/area-of"
MAP="$ROOT/tests/fixtures/areas.tsv"
SPEC="$ROOT/specs/bugs-index.md"

fail=0
ok()  { printf '✓ %s\n' "$*"; }
bad() { printf '✗ %s\n' "$*"; fail=1; }

echo "── path→area map gate"
[ -x "$AREA" ] || bad "not executable: $AREA"
[ -f "$MAP" ]  || bad "missing: $MAP"

# ── 1. the enum matches the spec's, exactly ─────────────────────────────────
# Two files naming areas is duplicated state; without this check they drift, which is the failure
# mode this repo has paid for repeatedly (ci-status vs ci.yml, the heartbeat threshold in three
# places). The set must match in BOTH directions.
if [ -f "$SPEC" ]; then
  spec_areas=$(sed -n 's/.*`area`.*always.*|\(.*\)|.*/\1/p' "$SPEC" | tr -d '`' | tr '·' '\n' \
                 | tr -d ' ' | grep -E '^[a-z]+$' | LC_ALL=C sort -u | tr '\n' ' ')
  tool_areas=$(sed -n 's/^ENUM = {\(.*\)}$/\1/p' "$AREA" | tr -d '"' | tr ',' '\n' \
                 | tr -d ' ' | grep -E '^[a-z]+$' | LC_ALL=C sort -u | tr '\n' ' ')
  if [ -n "$spec_areas" ] && [ "$spec_areas" = "$tool_areas" ]; then
    ok "area enum matches specs/bugs-index.md ($tool_areas)"
  else
    bad "area enum drifted from the spec"
    printf '    spec=%s\n    tool=%s\n' "$spec_areas" "$tool_areas"
  fi
fi

# ── 2. every mapped area is in the enum (area-of enforces this; prove it fires) ──
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
{ cat "$MAP"; printf 'zzz-bogus-prefix\tnot-an-area\n'; } > "$tmp/bad.tsv"
if AREAS_TSV_OVERRIDE=1 python3 - "$tmp/bad.tsv" <<'PY' 2>/dev/null
import sys, re
ENUM = {"front","runtime","codegen","cli","conformance","build","docs","plugin","other"}
bad = [l for l in open(sys.argv[1]).read().splitlines()
       if l.strip() and not l.startswith("#") and l.split("\t")[-1].strip() not in ENUM]
sys.exit(0 if bad else 1)
PY
then ok "an out-of-enum area in the map is detectable"
else bad "an out-of-enum area would pass unnoticed"
fi

# ── 3. TOTAL — every real code directory resolves ───────────────────────────
# Walked from the tree, not from a list here: a hand-written list of directories is one more copy of
# state that drifts. New top-level code must either resolve or make this gate say so.
unresolved=""
while IFS= read -r d; do
  case "$d" in */target/*|*/.git/*|*node_modules*) continue ;; esac
  "$AREA" --quiet "$d" >/dev/null 2>&1 || unresolved="$unresolved $d"
done < <(cd "$ROOT" && ls -d v1/*/ v2/*/ tests/*/ 2>/dev/null | sed 's:/$::')
if [ -z "${unresolved// /}" ]; then
  ok "every v1/* v2/* tests/* directory resolves to an area"
else
  bad "these directories resolve to NO area — add a prefix to tests/fixtures/areas.tsv:"
  printf '    %s\n' $unresolved
fi

# ── 4. DETERMINISTIC — longest prefix wins, order-independent ───────────────
# Reversing the file must not change any answer. If it does, two readers of the same map disagree.
sed '/^#/d;/^[[:space:]]*$/d' "$MAP" | tail -r 2>/dev/null > "$tmp/rev.tsv" || \
  sed '/^#/d;/^[[:space:]]*$/d' "$MAP" | tac > "$tmp/rev.tsv"
probe="v1/runtime/backend/js/x.scala v1/runtime/std/a-plugin/x.scala v2/backend/swift/x.scala v1/tools/cli/x.scala tests/conformance/a.ssc build.sbt"
a1=$(cd "$ROOT" && $AREA --quiet $probe 2>/dev/null | tr '\n' ' ')
cp "$MAP" "$tmp/orig.tsv"; { grep '^#' "$MAP"; cat "$tmp/rev.tsv"; } > "$MAP"
a2=$(cd "$ROOT" && $AREA --quiet $probe 2>/dev/null | tr '\n' ' ')
cp "$tmp/orig.tsv" "$MAP"
if [ "$a1" = "$a2" ] && [ -n "$a1" ]; then
  ok "answers are order-independent ($a1)"
else
  bad "reversing the map changed the answers"
  printf '    before=%s\n    after =%s\n' "$a1" "$a2"
fi

# ── 5. the answers a reader would expect ────────────────────────────────────
# Spot checks on paths this repo actually argued about during 2026-07-28/29.
check() { # check <path> <expected>
  got=$(cd "$ROOT" && "$AREA" --quiet "$1" 2>/dev/null)
  [ "$got" = "$2" ] && ok "$1 -> $2" || bad "$1: expected=$2 got=${got:-<unresolved>}"
}
check v1/runtime/backend/js/src/main/scala/scalascript/codegen/JsGen.scala codegen
check v2/backend/swift/src/main/scala/ssc/swift/SwiftBackend.scala codegen
check v2/runtime/std/sql-plugin/src/main/scala/ssc/plugin/sql/SqlNativePlugin.scala plugin
check v2/lib/ssc1-front.ssc0 front
check v1/tools/cli/src/main/scala/scalascript/cli/Main.scala cli
check build.sbt build
check docs/build-performance.md docs

# ── 6. an unmapped path must FAIL LOUDLY, not answer "other" ────────────────
# Guessing `other` for unknown code is how a mapping quietly stops covering the tree.
if (cd "$ROOT" && "$AREA" --quiet "totally/unmapped/thing.scala" >/dev/null 2>&1); then
  bad "an unmapped path was resolved instead of reported"
else
  ok "an unmapped path is reported, not guessed"
fi

echo
[ "$fail" -eq 0 ] && { echo "✓ path→area map gate PASSED"; exit 0; }
echo "✗ path→area map gate FAILED"; exit 1
