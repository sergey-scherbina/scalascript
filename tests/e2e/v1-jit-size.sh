#!/usr/bin/env bash
#
# v1-jit-size.sh — no NEW method in the v1 tree may exceed HotSpot's HugeMethodLimit.
#
# WHY THIS EXISTS, and why it is separate from v2-jit-size.sh.
#
# `-XX:+DontCompileHugeMethods` is ON by default, and a method whose bytecode exceeds
# `-XX:HugeMethodLimit` (8000) is NEVER JIT-compiled — not by C1, not by C2. It runs in the
# bytecode interpreter for the life of the process. No warning, no log line, no correctness
# signal. In v2 exactly this cost 2.4–10.8× until `Prims.__method__` (49 384 bytecodes) was
# split, which is why `tests/e2e/v2-jit-size.sh` exists.
#
# That gate scans `v2/{src,backend-jvm-bytecode,jvm-runtime}` ONLY. Nobody ever pointed it at v1 —
# the tree that is 4.3× larger (302 210 lines vs 70 844). Censused 2026-07-29: SEVEN methods over
# the limit, four of them the interpreter's core dispatch, which every INT conformance case and
# every `ssc run` goes through:
#
#   28036  ActorScheduler.handleActorOp
#   24984  JsGen.genExpr
#   21114  DispatchRuntime.infix2
#   16346  RustCodeWalk.renderTerm
#   15330  EvalRuntime.evalCore
#   14696  DispatchRuntime.dispatchList
#    9839  DispatchRuntime.dispatchString
#
# WHY A FROZEN DEBT LIST AND NOT A HARD FAIL. Seven pre-existing offenders cannot be fixed in the
# commit that adds the gate, and a gate that is red on arrival gets disabled within a day. So the
# known seven are frozen BY NAME with their measured size; the gate fails on an EIGHTH, and it also
# fails when a frozen method GROWS. It is the shape already used by the negtc release gate: freeze
# the hard invariant, derive the rest.
#
# It also fails when a frozen method DISAPPEARS from the census — that means someone fixed it, and
# the freeze must shrink rather than quietly keep granting an exemption nobody needs. Same
# self-expiry as a `known-red:` declaration.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

LIMIT=8000
CENSUS="$ROOT/scripts/bytecode-size-census"
[[ -x "$CENSUS" ]] || { echo "missing $CENSUS" >&2; exit 2; }

# Frozen debt: <bytecodes> <fully.qualified.Class::method>. Sizes are the 2026-07-29 measurement;
# growth beyond the recorded number is a regression even while the method stays exempt.
# SHRINKING this list is the goal. Do not add to it without a measured reason in the commit.
read -r -d '' FROZEN <<'EOF' || true
28036 scalascript.interpreter.ActorScheduler::handleActorOp
24984 scalascript.codegen.JsGen::genExpr
21114 scalascript.interpreter.DispatchRuntime$::infix2
16346 scalascript.codegen.rust.RustCodeWalk$::renderTerm
15330 scalascript.interpreter.EvalRuntime$::evalCore
14696 scalascript.interpreter.DispatchRuntime$::dispatchList
9839 scalascript.interpreter.DispatchRuntime$::dispatchString
EOF

# ── self-test: a detector only ever observed staying quiet is not a detector ─────────────────
# Same reasoning as v2-jit-size.sh: prove the census still measures before trusting a clean report.
if [[ "${1:-}" == "--self-test" ]]; then
  command -v javac >/dev/null || { echo "self-test needs javac" >&2; exit 2; }
  TMP="$(mktemp -d "${TMPDIR:-/tmp}/ssc-v1-jit-selftest.XXXXXX")"
  trap 'rm -rf "$TMP"' EXIT
  gen() { { printf 'public class %s { public static int f(int x) {\n' "$1"
            for ((i = 0; i < $2; i++)); do printf '    x += 1;\n'; done
            printf '    return x; } }\n'; } > "$TMP/$1.java"
          javac -d "$TMP/classes-$1" "$TMP/$1.java"; }
  gen Huge 5000; gen Small 10
  [[ -n "$("$CENSUS" "$TMP/classes-Huge" "$LIMIT")" ]] \
    || { echo "SELF-TEST FAIL: census stayed quiet on a method built to exceed $LIMIT" >&2; exit 1; }
  [[ -z "$("$CENSUS" "$TMP/classes-Small" "$LIMIT")" ]] \
    || { echo "SELF-TEST FAIL: census flagged a 10-statement method" >&2; exit 1; }
  echo "v1-jit-size self-test: PASS (census detects over-limit and stays quiet under it)"
  exit 0
fi

dirs=()
while IFS= read -r d; do dirs+=("$d"); done < <(find "$ROOT/v1" -type d -name classes -path '*target/scala-*' 2>/dev/null | sort)
if [[ ${#dirs[@]} -eq 0 ]]; then
  echo "v1-jit-size: no compiled v1 classes found — build first (bash install.sh --dev)" >&2
  echo "  looked for: v1/**/target/scala-*/classes" >&2
  exit 2
fi
echo "v1-jit-size: scanning ${#dirs[@]} class dir(s), limit $LIMIT"

observed="$(mktemp)"; trap 'rm -f "$observed"' EXIT
for d in "${dirs[@]}"; do "$CENSUS" "$d" "$LIMIT" 2>/dev/null || true; done \
  | sed -E 's/^ *([0-9]+) +([A-Za-z0-9_.$]+) :: .*[ (]([A-Za-z0-9_$]+)\(.*/\1 \2::\3/' \
  | grep -E '^[0-9]+ ' | sort -u > "$observed"

fail=0
declare -A frozen_size=()
while read -r size name; do [[ -n "${name:-}" ]] && frozen_size["$name"]="$size"; done <<< "$FROZEN"

# NEW offenders, and frozen ones that GREW
while read -r size name; do
  [[ -n "${name:-}" ]] || continue
  if [[ -z "${frozen_size[$name]+x}" ]]; then
    echo "FAIL  NEW method over HugeMethodLimit — it will NEVER be JIT-compiled:" >&2
    echo "        $size  $name" >&2
    echo "        Split it, or add it to FROZEN with a measured reason in the commit." >&2
    fail=1
  elif (( size > ${frozen_size[$name]} )); then
    echo "FAIL  frozen method GREW: $name  ${frozen_size[$name]} -> $size" >&2
    fail=1
  fi
done < "$observed"

# Frozen entries that no longer appear — the exemption expired, shrink the list
while read -r size name; do
  [[ -n "${name:-}" ]] || continue
  grep -qE " ${name//$/\\$}$" "$observed" \
    || { echo "FAIL  frozen method is no longer over the limit — DELETE it from FROZEN: $name" >&2
         echo "        (an exemption that outlives its need is the same rot as a stale known-red)" >&2
         fail=1; }
done <<< "$FROZEN"

if [[ "$fail" -ne 0 ]]; then
  echo "" >&2
  echo "v1-jit-size: FAIL" >&2
  exit 1
fi
echo "v1-jit-size: PASS ($(wc -l < "$observed" | tr -d ' ') known over-limit method(s), none new, none grown)"
