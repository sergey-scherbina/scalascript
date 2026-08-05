#!/usr/bin/env bash
# A `var` inside an `object` must keep its value across calls.
#
#   object org:
#     var hits = 0
#     def bump(): Int = { hits = hits + 1; hits }
#     def peek(): Int = hits
#   org.bump(); org.bump(); org.peek()      native: 1 2 2      int: 1 1 0
#
# On the interpreter each call reads 0, and `peek` returns the value from before either call — the
# writes land where the reads cannot see them, at exit 0. BUGS `int-object-var-mutation-does-not-persist`.
#
# The `int` cell is a DECLARED GAP and FAILS IF IT STARTS PASSING, so the day it is fixed this gate
# says so instead of quietly agreeing. That shape is used elsewhere in this suite
# (triple-quote-trailing-quote-gate did exactly this and announced its own removal).
#
# No conformance case: the corpus grades by comparing lanes, so a case whose int cell diverges is
# simply RED and says nothing about WHICH lane is wrong. Here native is the reference — it agrees
# with the semantics of the same code written outside an object, which both lanes get right.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/ssc-objvar.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
fails=0

cat > "$TMP/ov.ssc" <<'EOF'
```scalascript
object org:
  var hits = 0
  def bump(): Int =
    hits = hits + 1
    hits
  def peek(): Int = hits
def main() =
  println(org.bump())
  println(org.bump())
  println(org.peek())
```
EOF

want="1
2
2"

# CONTROL: the same code with no object around it. Both lanes get this right, which is what makes
# `object` the variable under test rather than `var` itself.
cat > "$TMP/flat.ssc" <<'EOF'
```scalascript
var hits = 0
def bump(): Int =
  hits = hits + 1
  hits
def main() =
  println(bump())
  println(bump())
  println(hits)
```
EOF

nat="$(SSC_NO_BUILD_CHECK=1 timeout 180 "$ROOT/bin/ssc" "$TMP/ov.ssc" 2>/dev/null || true)"
if [ "$nat" = "$want" ]; then
  echo "ok   native keeps object state across calls"
else
  echo "FAIL native — wanted [$want], got [$nat]"
  fails=$((fails + 1))
fi

for lane in "int:run --v1" "js:run-js" "jvm:run-jvm"; do
  label="${lane%%:*}"; cmd="${lane#*:}"
  # shellcheck disable=SC2086
  got="$(SSC_NO_BUILD_CHECK=1 timeout 180 "$ROOT/bin/ssc-tools" $cmd "$TMP/flat.ssc" </dev/null 2>/dev/null || true)"
  if [ "$got" = "$want" ]; then
    echo "ok   $label keeps state without an object (control)"
  else
    echo "FAIL $label control — a plain top-level var already fails; wanted [$want], got [$got]"
    fails=$((fails + 1))
  fi
done

got_int="$(SSC_NO_BUILD_CHECK=1 timeout 180 "$ROOT/bin/ssc-tools" run --v1 "$TMP/ov.ssc" </dev/null 2>/dev/null || true)"
if [ "$got_int" = "$want" ]; then
  echo "FAIL int now PASSES — the gap closed. Delete this block, let int count with native,"
  echo "     and close BUGS.md int-object-var-mutation-does-not-persist."
  fails=$((fails + 1))
else
  echo "KNOWN GAP  int — int-object-var-mutation-does-not-persist (declared, got [$(printf '%s' "$got_int" | tr '\n' ' ')])"
fi

echo
if [ "$fails" -eq 0 ]; then
  echo "object-var-mutation-gate: OK (native correct; the flat control passes on every lane; int is a declared gap)"
  exit 0
fi
echo "object-var-mutation-gate: FAIL ($fails)"
exit 1
