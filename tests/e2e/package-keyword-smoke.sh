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
# that actually runs the program — and with it the lane's real defect became visible: it emitted
# `import org.example.ui.{org}`, importing the package ROOT from the package. Fixed 2026-08-05
# (`jvm-package-import-qualifies-the-link-name`); the known-red declaration this row carried is gone
# because the gate FAILS a declared row that starts passing.
check "JVM" -  "$BIN/ssc-tools" run-jvm

# ── `as` in the link, four forms ────────────────────────────────────────────────────────────────
#
# A second consumer against the SAME module, plus one against a module with no `package:` at all —
# that last one is what separates "aliasing is broken" from "aliasing a package root is broken", and
# it is the row that reframed the whole task: native ignored `as` everywhere, not just on packages.
#
# Each row runs with the alias AND with the same link written without it. The unaliased run is the
# ABSENT-STATE CONTROL: if it were failing too, a green alias row would be meaningless, and if the
# alias row passed while the control failed the fixture would be lying about what it exercises.

cat > "$WORK/plain.ssc" <<'EOF'
---
name: plain
---
```scalascript
def greet(t: String): String = "hi-" + t
```
EOF

alias_case() {  # alias_case <name> <module> <link> <call> <plain-link> <plain-call> <expected>
    local name="$1" mod="$2" link="$3" call="$4" plink="$5" pcall="$6" exp="$7"
    cat > "$WORK/a_$name.ssc" <<EOF
---
name: a_$name
---
[$link]($mod)

\`\`\`scalascript
println($call)
\`\`\`
EOF
    cat > "$WORK/n_$name.ssc" <<EOF
---
name: n_$name
---
[$plink]($mod)

\`\`\`scalascript
println($pcall)
\`\`\`
EOF
    local got ctl
    got=$("$BIN/ssc" run "$WORK/a_$name.ssc" 2>"$WORK/a_$name.err" | grep -vE '^[[:space:]]*$' | tr '\n' '|')
    ctl=$("$BIN/ssc" run "$WORK/n_$name.ssc" 2>/dev/null | grep -vE '^[[:space:]]*$' | tr '\n' '|')
    if [ "$ctl" != "$exp|" ]; then
        echo "  [FAIL] alias/$name  — the CONTROL (no \`as\`) does not pass: got $ctl"
        echo "         A green alias row would prove nothing while this is red."
        fail=1
    elif [ "$got" = "$exp|" ]; then
        echo "  [PASS] alias/$name"
    else
        echo "  [FAIL] alias/$name  got: $got"
        [ -s "$WORK/a_$name.err" ] && sed 's/^/         stderr: /' "$WORK/a_$name.err" | head -2
        fail=1
    fi
}

echo
alias_case root    ./cards.ssc "org as o"    'o.example.ui.Card.render("hi")' "org"    'org.example.ui.Card.render("hi")' ui-card-hi
alias_case member  ./cards.ssc "Card as C"   'C.render("hi")'                 "Card"   'Card.render("hi")'                 ui-card-hi
alias_case plainfn ./plain.ssc "greet as g"  'g("hi")'                        "greet"  'greet("hi")'                       hi-hi

echo
if [ $fail -eq 0 ]; then
    echo "package: keyword resolves on every lane that does not carry a declared gap."
    exit 0
fi
exit 1
