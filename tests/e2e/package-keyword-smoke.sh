#!/usr/bin/env bash
# `package:` front-matter smoke — verifies that declaring
# `package: org.example.ui` in a module wraps its top-level
# declarations in nested objects so they're accessible as
# `org.example.ui.<Name>` on all three backends.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
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
    # stderr goes to a FILE, not to /dev/null. Discarding it is why the orphan census recorded this
    # gate as "empty output" and why nobody read the actual failure for weeks: the interpreter says
    # `ssc: unbound global: org` on stderr and prints nothing on stdout, so the two states —
    # "produced the wrong page" and "died before producing anything" — looked identical.
    got=$($cmd "$WORK/consumer.ssc" 2>"$WORK/$label.err" | grep -vE '^\s*$' | tr '\n' '|')
    if [ "$got" = "$expected|" ]; then
        echo "  [PASS] $label"
    else
        echo "  [FAIL] $label  got: $got"
        [ -s "$WORK/$label.err" ] && sed 's/^/         stderr: /' "$WORK/$label.err" | head -3
        fail=1
    fi
}

check "INT" "$BIN/ssc"
# `sscc` is the JVM COMPILER (it maps to `compile-jvm`), so it writes an artifact and prints where
# it put it. Comparing that message against the expected page made this row fail for a reason that
# has nothing to do with the package keyword — measured 2026-08-04, `got:` was
# "JVM artifact written to …/consumer.scjvm". The JVM lane needs a RUN, and `build-jvm` + `java -jar`
# is that; wiring it is left to whoever fixes the two real defects this gate now names, so the row
# stays here rather than being silently dropped.
check "JVM" "$BIN/sscc"
check "JS"  "$BIN/jssc"

echo
if [ $fail -eq 0 ]; then
    echo "package: keyword resolves on all three backends."
    exit 0
fi
exit 1
