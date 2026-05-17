#!/usr/bin/env bash
# Import-alias smoke — verifies `[Card as MyCard](./a.ssc)` produces the
# aliased binding on all three backends.  Also covers multi-binding
# imports where only SOME of the names carry an alias.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
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
check "INT" "$BIN/ssc"        "$WORK/single.ssc" "card-hi|"
check "JVM" "$BIN/sscc"       "$WORK/single.ssc" "card-hi|"
check "JS"  "$BIN/jssc"       "$WORK/single.ssc" "card-hi|"

echo
echo "Case B: [Foo as F, Bar]"
check "INT" "$BIN/ssc"        "$WORK/multi.ssc"  "foo-out|bar-out|"
check "JVM" "$BIN/sscc"       "$WORK/multi.ssc"  "foo-out|bar-out|"
check "JS"  "$BIN/jssc"       "$WORK/multi.ssc"  "foo-out|bar-out|"

echo
if [ $fail -eq 0 ]; then
    echo "All import-alias cases pass on INT/JVM/JS."
    exit 0
fi
exit 1
