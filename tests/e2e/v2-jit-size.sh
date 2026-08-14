#!/usr/bin/env bash
#
# v2-jit-size.sh — no method in the v2 runtime may exceed HotSpot's HugeMethodLimit.
#
#   ./tests/e2e/v2-jit-size.sh              # check the built v2 artifacts
#   ./tests/e2e/v2-jit-size.sh --self-test  # assert BOTH verdicts, then check the artifacts
#
# WHAT THIS GUARDS
#
# `-XX:+DontCompileHugeMethods` is ON by default in every production HotSpot. A method whose
# bytecode exceeds `-XX:HugeMethodLimit` (8000) is never JIT-compiled and runs interpreted
# forever. It fails GREEN: no warning, no test failure, only a 10-100x slowdown that nobody
# attributes to it. `ssc.Prims`'s `__method__` dispatch sat at 49,384 bytecodes — 6.2x the
# limit — so every `xs.map` / `s.split` / `n.toInt` in every ScalaScript program ran
# interpreted. Splitting it bought 2.4-10.8x across the bench corpus. This gate is what stops
# the next case landing back on the wrong side of the limit, because nothing else can see it:
# the split is invisible to every correctness test by construction.
#
# The margin matters as much as the limit. A part at 7,900 is one `case` from silently
# un-JITing itself, so the check also prints the largest methods under the limit — drift you
# can watch is drift you can act on.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

LIMIT=8000          # -XX:HugeMethodLimit
WATCH=6000          # print anything above this as approaching-the-limit context
CENSUS="$ROOT/scripts/bytecode-size-census"

SELF_TEST=0
[[ "${1:-}" == "--self-test" ]] && SELF_TEST=1

[[ -x "$CENSUS" ]] || { echo "missing $CENSUS" >&2; exit 2; }

# ── self-test: a detector only ever observed staying quiet is not a detector ──────────────
# Build one method that MUST trip the census and one that must NOT, so a census that silently
# stopped measuring (bad javap, changed output format, awk drift) cannot pass as "all clean".
if [[ "$SELF_TEST" == 1 ]]; then
  command -v javac >/dev/null || { echo "self-test needs javac" >&2; exit 2; }
  TMP="$(mktemp -d "${TMPDIR:-/tmp}/ssc-jit-size-selftest.XXXXXX")"
  trap 'rm -rf "$TMP"' EXIT

  # ~5000 `x += 1;` statements: iinc is 3 bytes, so this lands well over 8000 and well over
  # any plausible parser confusion. The small one is the same shape, 10 statements.
  gen() { # gen <name> <count>
    { printf 'public class %s { public static int f(int x) {\n' "$1"
      for ((i = 0; i < $2; i++)); do printf '    x += 1;\n'; done
      printf '    return x; } }\n'
    } > "$TMP/$1.java"
    javac -d "$TMP/classes-$1" "$TMP/$1.java"
  }

  gen Huge 5000
  gen Small 10

  if [[ -z "$("$CENSUS" "$TMP/classes-Huge" "$LIMIT")" ]]; then
    echo "SELF-TEST FAIL: the census stayed quiet on a method built to exceed $LIMIT bytecodes." >&2
    echo "  It is not measuring anything; a clean report from it would be meaningless." >&2
    "$CENSUS" "$TMP/classes-Huge" 0 >&2 || true
    exit 1
  fi
  echo "self-test: fires on a >$LIMIT-bytecode method — ok"

  if [[ -n "$("$CENSUS" "$TMP/classes-Small" "$LIMIT")" ]]; then
    echo "SELF-TEST FAIL: the census fired on a 10-statement method." >&2
    "$CENSUS" "$TMP/classes-Small" "$LIMIT" >&2
    exit 1
  fi
  echo "self-test: quiet on a small method — ok"
fi

# ── locate the built v2 artifacts ─────────────────────────────────────────────────────────
# Prefer sbt's class output (present after any `sbt v2Core/compile`); fall back to the staged
# jars that `sbt installBin` writes. Never pass without having measured something.
# Glob the Scala version rather than pinning it: a bumped scalaVersion would otherwise
# leave this finding nothing and exiting 2 in CI — loud, but for the wrong reason.
# PER MODULE, and that word is the fix. The fallback to the staged jars used to fire only when
# ALL THREE class directories were missing, so the ordinary post-`install.sh` state — where
# `v2/src/target/.../classes` is absent and the two small modules' are present — produced a
# NON-EMPTY target list that did not contain `ssc.Prims`. The gate then measured two minor modules,
# found nothing over the limit, and printed PASS. That is the exact shape it exists to prevent, one
# level up: not "no measurement", but a measurement of the wrong thing wearing a green.
#
# Measured 2026-08-14: `rm -rf v2/src/target/scala-*/classes && ./install.sh --dev` then this gate
# reported `PASS` while `ssc.Prims.methodDispatch8` was 8406 bytecodes — the very defect that made
# the split below necessary, invisible to the check that owns it.
#
# So each module resolves independently: its class directory, else its staged jar, else the gate
# REFUSES and names the module. `bytecode-size-census` reads a jar as readily as a directory.
declare -a TARGETS=()
missing=()
resolve_module() { # $1 module dir under v2/, $2 staged jar prefix
  local d j
  for d in "$ROOT/v2/$1"/target/scala-*/classes; do
    if [[ -d "$d" ]]; then TARGETS+=("$d"); return 0; fi
  done
  j="$(find "$ROOT/bin/lib/jars" -name "$2*.jar" 2>/dev/null | sort | head -1)"
  if [[ -n "$j" ]]; then TARGETS+=("$j"); return 0; fi
  missing+=("v2/$1 (no target/scala-*/classes, no $2*.jar)")
}
resolve_module src                  scalascript-v2-core
resolve_module backend-jvm-bytecode scalascript-v2-jvm-bytecode
resolve_module jvm-runtime          scalascript-v2-jvm-runtime

if [[ ${#missing[@]} -gt 0 ]]; then
  cat >&2 <<EOF
v2-jit-size: cannot measure $( printf '%s ' "${missing[@]}" )

  Each v2 module is looked for as target/scala-*/classes first, then as its staged jar in
  bin/lib/jars. A module reachable as NEITHER is not measured, and a gate that skips the
  module carrying \`ssc.Prims\` while reporting on its neighbours is worse than no gate.

  Build first: \`./install.sh --dev\` (stages the jars) or \`sbt v2Core/compile\`.
EOF
  exit 2
fi

# ── the check ─────────────────────────────────────────────────────────────────────────────
over=0
for t in "${TARGETS[@]}"; do
  label="${t#$ROOT/}"
  hits="$("$CENSUS" "$t" "$LIMIT")"
  if [[ -n "$hits" ]]; then
    over=1
    echo "FAIL  $label — method(s) over HugeMethodLimit ($LIMIT); these are NEVER JIT-compiled:" >&2
    printf '%s\n' "$hits" >&2
  fi
  watch="$("$CENSUS" "$t" "$WATCH")"
  if [[ -n "$watch" ]]; then
    echo "note  $label — largest methods (>= $WATCH of $LIMIT):"
    printf '%s\n' "$watch"
  else
    echo "ok    $label — no method >= $WATCH bytecodes (limit $LIMIT)"
  fi
done

if [[ "$over" == 1 ]]; then
  cat >&2 <<EOF

expected= no method over $LIMIT bytecodes
got=      the method(s) listed above

Split the offending method into sequential parts that each stay under $LIMIT, exactly as
ssc.Prims.methodDispatch1..N does: part N tries its cases in the ORIGINAL order and falls
through to part N+1, so the first matching case is still the one the single match chose.
Nothing may be reordered and nothing duplicated.
EOF
  exit 1
fi

echo "v2-jit-size: PASS — every method in the v2 runtime is under HotSpot's $LIMIT limit."
