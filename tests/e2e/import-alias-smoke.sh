#!/usr/bin/env bash
# Import-alias smoke — verifies `[Card as MyCard](./a.ssc)` produces the
# aliased binding on all three backends.  Also covers multi-binding
# imports where only SOME of the names carry an alias.
#
# RUNNERS CORRECTED 2026-08-04 (tests/BUGS.md orphaned-e2e-gates-52). Two were wrong, and neither
# wrong is visible from the failure it produced:
#
#   `$BIN/sscc` is `ssc-tools compile-jvm` — it COMPILES and exits, printing `JVM artifact written`.
#   As a "JVM backend" it never ran anything: this gate's JVM lane was measuring a compiler, and
#   reported it as "the server process EXITED before it listened".
#
#   `$BIN/ssc` is `StandardMain`, the NATIVE/default lane, not the interpreter. The label said INT
#   for a launcher that stopped being the interpreter when the lane map moved, so this gate's
#   failures were filed against the wrong lane's owner. The runner now matches the label; NATIVE is
#   not covered here and its own gap is `v2/BUGS.md native-lane-ignores-declarative-route-registration`.
#
# The launcher is now a COMMAND WITH ARGUMENTS, passed as one string and invoked UNQUOTED. Passing
# the extra words as separate arguments instead is a silent trap: helpers that take positional args
# shift `expected` into `$4`, and helpers that use `"$launcher"` drop the words entirely — which
# made three differently-labelled lanes all run the same default lane and turned two gates green
# for no reason. Both happened here before this note was written.
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BIN="$ROOT/bin"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/card.ssc" <<'EOF'
---
name: card
---
# Card
```scalascript
object Card:
  def render(t: String): String = "card-" + t
```
EOF

cat > "$WORK/lib.ssc" <<'EOF'
---
name: lib
---
# Lib
```scalascript
object Foo:
  def render(): String = "foo-out"
object Bar:
  def render(): String = "bar-out"
```
EOF

# Case A: single binding with alias
cat > "$WORK/single.ssc" <<'EOF'
---
name: single
---
# Single

[Card as MyCard](./card.ssc)

```scalascript
println(MyCard.render("hi"))
```
EOF

# Case B: multi binding with mix of aliased + bare
cat > "$WORK/multi.ssc" <<'EOF'
---
name: multi
---
# Multi

[Foo as F, Bar](./lib.ssc)

```scalascript
println(F.render())
println(Bar.render())
```
EOF

echo "============================================================"
echo "  Import-alias smoke — three backends"
echo "============================================================"
echo

fail=0
check() {
    local label="$1"
    local cmd="$2"
    local file="$3"
    local expected="$4"
    local got
    got=$($cmd "$file" 2>/dev/null | grep -vE '^\s*$' | tr '\n' '|')
    if [ "$got" = "$expected" ]; then
        echo "  [PASS] $label"
    else
        echo "  [FAIL] $label"
        echo "         expected: $expected"
        echo "         got:      $got"
        fail=1
    fi
}

echo "Case A: [Card as MyCard]"
check "INT" "$BIN/ssc-tools run --v1"        "$WORK/single.ssc" "card-hi|"
check "JVM" "$BIN/ssc-tools run-jvm"       "$WORK/single.ssc" "card-hi|"
check "JS"  "$BIN/ssc-tools run-js"       "$WORK/single.ssc" "card-hi|"

echo
echo "Case B: [Foo as F, Bar]"
check "INT" "$BIN/ssc-tools run --v1"        "$WORK/multi.ssc"  "foo-out|bar-out|"
check "JVM" "$BIN/ssc-tools run-jvm"       "$WORK/multi.ssc"  "foo-out|bar-out|"
check "JS"  "$BIN/ssc-tools run-js"       "$WORK/multi.ssc"  "foo-out|bar-out|"

echo
if [ $fail -eq 0 ]; then
    echo "All import-alias cases pass on INT/JVM/JS."
    exit 0
fi
exit 1
