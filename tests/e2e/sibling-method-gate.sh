#!/usr/bin/env bash
#
# sibling-method-gate — a method may call another method of the same class, and the two lanes must
# answer alike.
#
# The method body's environment is the instance's FIELDS, so `n` resolved and `twice` did not:
#
#     case class Box(n: Int):
#       def twice(): Int = n * 2
#       def quad(): Int = twice() * 2      -> v1: Undefined: twice   native: 12
#
# Found by writing `Response.withSession` in std/http.ssc the obvious way — `withHeader(…)` — and
# watching it die on one lane while working on the other.
#
# ── WHAT THIS GATE DELIBERATELY DOES NOT ASSERT ──────────────────────────────────────────────────
#
# When a TOP-LEVEL function shares a name with a method, the lanes DISAGREE: native answers with the
# method (7, which is what Scala would do) and v1 with the top-level function (100). This fix did not
# change that and does not pin it, because pinning either answer would make one lane's behaviour the
# contract while the other is arguably the correct one. Filed as
# `two-fronts-disagree-on-name-resolution` instead — and the reason is sharper than a lane split:
# the native answer here depends on whether front F lowered the file, so this same fixture gives 7
# while the `Shadowed` class ALONE in a file gives 100.
#
# I first measured that case as "both lanes agree on 100" and was wrong: the native run prints two
# front-fallback notes before its output, so a `tail -3` read the wrong three lines. The gate found
# it on its first run, which is the argument for writing the gate before believing the measurement.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BIN="$ROOT/bin"
export SSC_NO_BUILD_CHECK=1
echo "── sibling method calls (both lanes)"

[ -x "$BIN/ssc" ] || { echo "✗ no launcher at $BIN/ssc — build first"; exit 1; }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

# One program, all cases, so each lane costs one launcher start.
cat > "$WORK/probe.ssc" <<'EOF'
def size(): Int = 100

case class Box(n: Int):
  def twice(): Int = n * 2
  def quad(): Int = twice() * 2
  def deep(): Int = quad() + twice()

case class Named(size: Int):
  def viaField(): Int = size * 2

case class Shadowed(n: Int):
  def size(): Int = n
  def viaGlobal(): Int = size()

def main() =
  println("twice=" + Box(3).twice().toString)
  println("quad=" + Box(3).quad().toString)
  println("deep=" + Box(3).deep().toString)
  println("field=" + Named(5).viaField().toString)
  println("global=" + Shadowed(7).viaGlobal().toString)
EOF

fail=0
run_lane() {  # $1 label, $2.. argv
  local label="$1"; shift
  local out; out="$(timeout 120 "$@" "$WORK/probe.ssc" 2>"$WORK/$label.err")"
  for want in "twice=6" "quad=12" "deep=18" "field=10"; do
    if printf '%s\n' "$out" | grep -qx "$want"; then
      echo "  ✓ $label $want"
    else
      local got; got="$(printf '%s\n' "$out" | grep -m1 "^${want%%=*}=" || true)"
      echo "  ✗ $label ${want%%=*}: got '${got:-<nothing>}', expected '$want'"
      grep -m1 -E "Undefined|Error" "$WORK/$label.err" 2>/dev/null | sed 's/^/        /'
      fail=1
    fi
  done
}

run_lane v1     "$BIN/ssc-tools" run --v1
run_lane native "$BIN/ssc" run

echo
if [ "$fail" -ne 0 ]; then
  echo "    A method body is evaluated with the instance's FIELDS as its environment, so a sibling"
  echo "    method is not in scope unless it is bound there — DispatchRuntime.bindSiblings, driven"
  echo "    by Interpreter.bareAppliedNames so only bodies that call a bare name pay for it."
  echo "✗ sibling-method-gate FAILED"
  exit 1
fi
echo "✓ sibling-method-gate PASSED"
