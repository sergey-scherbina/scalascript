#!/usr/bin/env bash
# `package:` front-matter smoke — a module declaring `package: org.example.ui` must be reachable as
# `org.example.ui.<Name>` from an importer, on every lane.
#
# Four rows, because the two the gate used to have were not the two it claimed. The row labelled
# `INT` ran `bin/ssc`, which is the NATIVE lane — the interpreter is `ssc-tools run --v1`, and it was
# never exercised here. Proof rather than assertion: the row went from red to green on a change that
# touches only `v2/bin/ssc1-run*.ssc0`, the native tower, which the interpreter does not read.
#
# Cost: ~4 s, dominated by the JVM row (`run-jvm` invokes scala-cli). Cheap enough to register.
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
echo "  package: front-matter smoke — four lanes"
echo "============================================================"
echo

fail=0

# check <label> <known-red-slug|-> <command…>
#
# A row may be declared a KNOWN gap by naming its BUGS slug instead of `-`. The declaration never
# skips the comparison — the row is run and compared exactly like any other, and only the BUCKET
# changes (AGENTS.md §"measurement apparatus must COMPARE, never PRE-JUDGE"). A declared row that
# starts PASSING FAILS the suite, so a suppression cannot outlive its bug.
#
# The slug is a positional argument rather than a `known_red=… check …` prefix: in bash an
# assignment prefixing a FUNCTION call persists after the call returns, so one declared row would
# silently declare every row after it.
check() {
    local label="$1"; shift
    local known_red="$1"; shift
    [ "$known_red" = "-" ] && known_red=""
    local expected="ui-card-hi"
    local got
    # stderr goes to a FILE, not to /dev/null. Discarding it is why the orphan census recorded this
    # gate as "empty output" and why nobody read the actual failure for weeks: the lane says
    # `ssc: unbound global: org` on stderr and prints nothing on stdout, so the two states —
    # "produced the wrong page" and "died before producing anything" — looked identical.
    got=$("$@" "$WORK/consumer.ssc" 2>"$WORK/$label.err" | grep -vE '^[[:space:]]*$' | tr '\n' '|')
    local ok=0
    [ "$got" = "$expected|" ] && ok=1
    if [ -n "$known_red" ]; then
        if [ $ok -eq 1 ]; then
            echo "  [FAIL] $label  — declared known-red ($known_red) but it PASSES."
            echo "         Delete the declaration in this file; a suppression must not outlive its bug."
            fail=1
        else
            echo "  [KNOWN-RED] $label  — $known_red"
            echo "         got: $got"
            [ -s "$WORK/$label.err" ] && sed 's/^/         stderr: /' "$WORK/$label.err" | tail -3
        fi
        return
    fi
    if [ $ok -eq 1 ]; then
        echo "  [PASS] $label"
    else
        echo "  [FAIL] $label  got: $got"
        [ -s "$WORK/$label.err" ] && sed 's/^/         stderr: /' "$WORK/$label.err" | head -3
        fail=1
    fi
}

check "INT"    -  "$BIN/ssc-tools" run --v1
check "NATIVE" -  "$BIN/ssc"       run
check "JS"     -  "$BIN/jssc"

# `sscc` maps to `compile-jvm`: it writes an artifact and prints where it put it, so comparing its
# output against the expected page failed for a reason that has nothing to do with `package:`
# (measured 2026-08-04, `got:` was "JVM artifact written to …/consumer.scjvm"). `run-jvm` is the row
# that actually runs the program — and with it the lane's real defect is visible: it emits
# `import org.example.ui.{org}`, qualifying the module's LINK NAME with the package it declares.
# Controlled: the identical import with no `package:` in the module prints `ui-card-hi`.
check "JVM" "jvm-package-import-qualifies-the-link-name" "$BIN/ssc-tools" run-jvm

echo
if [ $fail -eq 0 ]; then
    echo "package: keyword resolves on every lane that does not carry a declared gap."
    exit 0
fi
exit 1
