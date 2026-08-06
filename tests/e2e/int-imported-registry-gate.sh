#!/usr/bin/env bash
# An imported module's mutable state must be SHARED with the importer, on every lane.
#
# `v1/runtime/backend/interpreter/BUGS.md int-imported-module-mutable-registry-not-shared`: the
# interpreter bound an imported OBJECT as an import-time snapshot, so `Reg.add(x)` from the importer
# mutated a copy and `Reg.count()` read the copy back — 0 where native and js said 1. INT is the
# conformance suite's reference lane, so a case built on this pattern was graded against the one
# lane that got it wrong.
#
# FOUR rows, because three of them are what makes the fourth meaningful:
#
#   object   the defect — the importer reaches the state through the OBJECT binding
#   function the CONTROL — the same module, the same `var`, reached through a top-level function.
#            It always worked, and it is what proved the boundary was the import rather than the
#            `var`: same file, same state, one character of difference in how it is reached.
#   nopkg    the same shape with no `package:` in the module, which is what ruled the package wrap
#            out. Both rows are kept so a future fix cannot pass one and break the other.
#   pure     a module with NO mutable state at all. Its exports are DELIBERATELY bound as a
#            snapshot (running them in the child would change their effect/plugin context), so this
#            row fails if a fix widens the rebinding to modules that must not get it.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BIN="$ROOT/bin"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

mklib() {  # mklib <file> <extra-front-matter>
    cat > "$WORK/$1" <<EOF
---
name: ${1%.ssc}
$2---
\`\`\`scalascript
object Reg:
  var entries: List[String] = Nil
  def add(s: String): Int =
    entries = entries ++ List(s)
    entries.length
  def count(): Int = entries.length

def addVia(s: String): Int = Reg.add(s)
def countVia(): Int = Reg.count()
\`\`\`
EOF
}
mklib lib.ssc ""
mklib libpkg.ssc "package: lib.reg
"

cat > "$WORK/pure.ssc" <<'EOF'
---
name: pure
---
```scalascript
def twice(n: Int): Int = n * 2
```
EOF

consumer() {  # consumer <file> <binding> <module> <body>
    cat > "$WORK/$1" <<EOF
---
name: ${1%.ssc}
---
[$2]($3)

\`\`\`scalascript
$4
\`\`\`
EOF
}
consumer c_object.ssc   "Reg"              "./lib.ssc"    'Reg.add("a")
println(Reg.count())'
consumer c_function.ssc "addVia, countVia" "./lib.ssc"    'addVia("a")
println(countVia())'
consumer c_nopkg.ssc    "Reg"              "./libpkg.ssc" 'Reg.add("a")
println(Reg.count())'
consumer c_pure.ssc     "twice"            "./pure.ssc"   'println(twice(21))'

echo "============================================================"
echo "  imported mutable state is shared with the importer"
echo "============================================================"
echo

fail=0
check() {  # check <label> <file> <expected>
    local label="$1" file="$2" want="$3" got_int got_nat
    got_int=$("$BIN/ssc-tools" run --v1 "$WORK/$file" 2>"$WORK/$label.int.err" | head -1)
    got_nat=$("$BIN/ssc" run        "$WORK/$file" 2>/dev/null | grep -vE '^ssc:' | head -1)
    if [ "$got_int" = "$want" ] && [ "$got_nat" = "$want" ]; then
        printf '  [PASS] %-10s int=%-6s native=%s\n' "$label" "$got_int" "$got_nat"
    else
        printf '  [FAIL] %-10s int=%-6s native=%-6s expected %s on both\n' \
               "$label" "${got_int:-<none>}" "${got_nat:-<none>}" "$want"
        [ -s "$WORK/$label.int.err" ] && sed 's/^/         int stderr: /' "$WORK/$label.int.err" | head -2
        fail=1
    fi
}

check object   c_object.ssc   1
check function c_function.ssc 1
check nopkg    c_nopkg.ssc    1
check pure     c_pure.ssc     42

echo
if [ $fail -eq 0 ]; then
    echo "imported mutable state is shared on both lanes; a pure module still binds by snapshot."
    exit 0
fi
exit 1
