#!/usr/bin/env bash
# `package:` front-matter smoke — verifies that declaring
# `package: org.example.ui` in a module wraps its top-level
# declarations in nested objects so they're accessible as
# `org.example.ui.<Name>` on all three backends.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$ROOT/bin"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/cards.ssc" <<'EOF'
---
name: cards
package: org.example.ui
---
# Cards
```scalascript
object Card:
  def render(t: String): String = "ui-card-" + t
```
EOF

cat > "$WORK/consumer.ssc" <<'EOF'
---
name: consumer
---
# Consumer

[org](./cards.ssc)

```scalascript
println(org.example.ui.Card.render("hi"))
```
EOF

echo "============================================================"
echo "  package: front-matter smoke — three backends"
echo "============================================================"
echo

fail=0
check() {
    local label="$1"
    local cmd="$2"
    local expected="ui-card-hi"
    local got
    got=$($cmd "$WORK/consumer.ssc" 2>/dev/null | grep -vE '^\s*$' | tr '\n' '|')
    if [ "$got" = "$expected|" ]; then
        echo "  [PASS] $label"
    else
        echo "  [FAIL] $label  got: $got"
        fail=1
    fi
}

check "INT" "$BIN/ssc"
check "JVM" "$BIN/sscc"
check "JS"  "$BIN/jssc"

echo
if [ $fail -eq 0 ]; then
    echo "package: keyword resolves on all three backends."
    exit 0
fi
exit 1
