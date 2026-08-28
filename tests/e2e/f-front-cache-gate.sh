#!/usr/bin/env bash
#
# f-front-cache-gate — the F front is lowered ONCE and reused, and a changed front is never served
# from the old artifact.
#
# WHY THIS EXISTS. Measured 2026-08-16: `parse` + `lowerProg` of F's 392 KB source costs ~8 s and ran
# on EVERY invocation — a one-line `println(1)` and a 121-line program cost the same, because the
# work was never proportional to the subject file. `f-output-agreement-gate` pays it 639 times.
# Caching the lowered front takes a run from ~7.6 s to ~3.3 s.
#
# THE FAILURE THIS GUARDS IS NOT SLOWNESS. A mis-keyed front cache silently serves the OLD COMPILER.
# In a repo where the front changes every session that is the state in which every measurement
# becomes a lie, and no output gate can see it, because the front still "works" — it is just not the
# one you edited. This repo has paid for that shape twice already (a cache keyed on a directory path
# served the wrong state's classes; an exclusion in a cache key hid the default front). So the rows
# that matter here are `invalidates-on-front-change` and `no-textual-reader`, not the timing.
#
# `no-textual-reader` is the CONTRACT row. `#coreir.decode` would class-load `ssc.Reader`, and
# `v21-native-plugin-boundary-smoke` fails on exactly that by name; measured, `run-ir` loads it 23
# times. The cache therefore reads its artifact with the self-hosted `irTextToData`, which is what
# D2 wrote for this. If someone "simplifies" that to `#coreir.decode`, this row goes red here before
# the boundary smoke goes red over there.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/f-front-cache.XXXXXX")
fails=0

# The staged front is edited by `invalidates-on-front-change` and MUST be put back even on a kill —
# leaving a mutated compiler behind would break every later gate in the run and look like their bug.
staged=""
restore() {
  [[ -n "$staged" && -f "$sandbox/fsub.orig" ]] && cp "$sandbox/fsub.orig" "$staged"
  rm -rf "$sandbox"
}
trap restore EXIT HUP INT TERM

echo "── the F front is lowered once, and a changed front is never served from the old artifact"
ssc_usable_or_skip f-front-cache-gate "$ssc"

export SSC_CACHE_DIR="$sandbox/cache"
export SSC_NO_BUILD_CHECK=1

cat > "$sandbox/p.ssc" <<'EOF'
def main(): Unit =
  println(List(1, 2, 3).map(x => x * 2))
EOF

run_f() { SSC_FRONT_STRICT=1 timeout 300 "$ssc" run "$1" < /dev/null 2>&1; }

artifacts() { ls "$SSC_CACHE_DIR/front"/fsub-*.ir 2>/dev/null | sort; }

# ── the answer must not move ─────────────────────────────────────────────────────────────────────

off=$(SSC_FRONT_CACHE=off timeout 300 "$ssc" run "$sandbox/p.ssc" < /dev/null 2>&1)
cold=$(run_f "$sandbox/p.ssc")
warm=$(run_f "$sandbox/p.ssc")

if [[ "$off" == "$cold" && "$cold" == "$warm" ]]; then
  echo "  ✓ same-answer: cache off, cold and warm all print '$warm'"
else
  echo "  ✗ same-answer: off='$off' cold='$cold' warm='$warm'"
  fails=$((fails + 1))
fi

# ── the artifact is real ─────────────────────────────────────────────────────────────────────────

n=$(artifacts | wc -l | tr -d ' ')
if [[ "$n" == "1" ]]; then
  echo "  ✓ writes-one-artifact: $(basename "$(artifacts | head -1)")"
else
  echo "  ✗ writes-one-artifact: expected exactly 1 artifact, found $n"
  fails=$((fails + 1))
fi

# A warm run must not rewrite it — that is the difference between a cache and a temp file.
before=$(artifacts | head -1)
stamp_before=$(stat -f %m "$before" 2>/dev/null || stat -c %Y "$before" 2>/dev/null)
run_f "$sandbox/p.ssc" > /dev/null
stamp_after=$(stat -f %m "$before" 2>/dev/null || stat -c %Y "$before" 2>/dev/null)
if [[ "$stamp_before" == "$stamp_after" ]]; then
  echo "  ✓ warm-run-reuses: the artifact is read, not rewritten"
else
  echo "  ✗ warm-run-reuses: the artifact was rewritten on a warm run"
  fails=$((fails + 1))
fi

# ── THE ROW THIS GATE EXISTS FOR ─────────────────────────────────────────────────────────────────
#
# Edit the staged front and demand a DIFFERENT artifact. A key that ignores the front's content
# passes every row above and fails only this one — which is precisely the bug worth catching, since
# its symptom in the wild is "my fix did nothing" rather than any error.

# `install.sh --dev` stages the front into ONE tree now (specs/arch-lib-path-resolution.md §7 —
# no more "standard" tier duplicate for `RunNativeV2.nativeFrontLayout` to prefer).
staged="$ROOT/bin/lib/native-front/tower/bin/fsub.ssc"
if [[ -f "$staged" ]]; then
  cp "$staged" "$sandbox/fsub.orig"
  printf '\n// f-front-cache-gate probe\n' >> "$staged"
  changed=$(run_f "$sandbox/p.ssc")
  n2=$(artifacts | wc -l | tr -d ' ')
  cp "$sandbox/fsub.orig" "$staged"

  if [[ "$n2" == "2" ]]; then
    echo "  ✓ invalidates-on-front-change: a changed front produced a second artifact"
  else
    echo "  ✗ invalidates-on-front-change: expected 2 artifacts after editing the front, found $n2"
    echo "    the key does not cover the front's CONTENT — a changed compiler would be served stale"
    fails=$((fails + 1))
  fi
  if [[ "$changed" == "$warm" ]]; then
    echo "  ✓ invalidation-keeps-the-answer: '$changed'"
  else
    echo "  ✗ invalidation-keeps-the-answer: got '$changed', wanted '$warm'"
    fails=$((fails + 1))
  fi
else
  echo "  ✗ invalidates-on-front-change: staged front not found — cannot test the key"
  fails=$((fails + 1))
fi

# ── the contract row ─────────────────────────────────────────────────────────────────────────────

clog="$sandbox/classes.log"
JAVA_TOOL_OPTIONS=-verbose:class SSC_FRONT_STRICT=1 timeout 300 "$ssc" run "$sandbox/p.ssc" \
  < /dev/null > "$clog" 2>&1
if grep -q 'ssc\.Reader' "$clog"; then
  echo "  ✗ no-textual-reader: the warm cache path class-loaded ssc.Reader"
  grep -m3 'ssc\.Reader' "$clog"
  fails=$((fails + 1))
else
  echo "  ✓ no-textual-reader: ssc.Reader absent — the artifact is read by irTextToData"
fi

# ── the escape hatch must work, because a suspicious measurement needs one ────────────────────────

rm -rf "$SSC_CACHE_DIR"
SSC_FRONT_CACHE=off timeout 300 "$ssc" run "$sandbox/p.ssc" < /dev/null > /dev/null 2>&1
n3=$(artifacts | wc -l | tr -d ' ')
if [[ "$n3" == "0" ]]; then
  echo "  ✓ off-writes-nothing: SSC_FRONT_CACHE=off leaves no artifact"
else
  echo "  ✗ off-writes-nothing: found $n3 artifact(s) with the cache disabled"
  fails=$((fails + 1))
fi

if [[ $fails -eq 0 ]]; then echo "✓ f-front-cache-gate PASSED"; exit 0; fi
echo "✗ f-front-cache-gate: $fails failure(s)"
exit 1
