#!/usr/bin/env bash
#
# board-generated.sh — `scripts/board` must derive the In-flight table correctly, and `--check` must
# actually notice when SPRINT.md disagrees.
#
# A generator nobody has seen produce a WRONG answer is not trusted, it is just unexamined. Every
# case below is paired: a derivation that must come out one way, and a drift that must be caught.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LAB="$(mktemp -d "${TMPDIR:-/tmp}/board-gen.XXXXXX")"
trap 'rm -rf "$LAB"' EXIT HUP INT TERM
fail=0
check() { if [ "$2" = "$3" ]; then printf 'PASS  %s\n' "$1"
          else printf 'FAIL  %s\n        expected: %s\n        got:      %s\n' "$1" "$2" "$3"; fail=1; fi }

mkdir -p "$LAB/active"
printf '# generation: 1\n#slug\tagent\tstarted\titems\tpaths\n' > "$LAB/active/LEDGER.tsv"
add() { # add <slug> <status> <items> <paths> <next>
  printf '%s\tt\tT\t%s\t%s\n' "$1" "$3" "$4" >> "$LAB/active/LEDGER.tsv"
  printf 'slug: %s\nstatus: %s\nitems: %s\npaths: %s\nnext: %s\n' "$1" "$2" "$3" "$4" "$5" > "$LAB/active/$1.claim"
}
gen() { SSC_LEDGER="$LAB/active/LEDGER.tsv" SSC_ACTIVE="$LAB/active" SSC_BOARD="$LAB/B.md" "$ROOT/scripts/board" "$@"; }
col() { gen | awk -F'|' -v s="$1" '$4 ~ s {gsub(/^ +| +$/,"",$3); print $3}'; }

add solo    in-progress            item-solo  "file:v2/lib/a.ssc0 file:v2/src/b.scala"        "x"
add mixed   in-progress            item-mixed "file:v2/lib/a.ssc0 file:scripts/x"             "x"
add bookish in-progress            item-book  "file:v2/lib/a.ssc0 file:v2/BUGS.md file:SPRINT.md" "x"
add unplan  claimed-before-planning item-unpl "file:tests/conformance/c.ssc"                  "x"

check "one module → that module"                  '`v2`'          "$(col 'solo')"
check "two modules → repo-wide"                   '*(repo-wide)*' "$(col 'mixed')"
check "bookkeeping paths ignored when deriving"   '`v2`'          "$(col 'bookish')"
check "nested module wins over its parent"        '`tests/conformance`' "$(col 'unplan')"

# Superseded by POLICY.md P-3.7: the column used to render `status:` directly. It now reports what is
# OBSERVED and keeps the self-report as a parenthetical, so a stale one reads AS stale.
st=$(gen | awk -F'|' '$4 ~ /unplan/ {gsub(/^ +| +$/,"",$5); print $5}')
check "self-report is a parenthetical, not the state" "no worktree (claimed-before-planning)" "$st"

add nowt in-progress item-nowt "file:v2/lib/a.ssc0" "x"
st=$(gen | awk -F'|' '$4 ~ /nowt/ {gsub(/^ +| +$/,"",$5); print $5}')
check "no worktree → says so, with the stale self-report shown" "no worktree (in-progress)" "$st"

# ── --check must catch drift in BOTH directions ─────────────────────────────────────────────────
gen > "$LAB/table.md"
{ printf '# b\n\n## In flight\n\n'; cat "$LAB/table.md"; printf '\n## How\n'; } > "$LAB/B.md"
gen --check >/dev/null 2>&1 && r=insync || r=drift
check "identical table → in sync" insync "$r"

python3 -c "
import sys
p='$LAB/B.md'
L=open(p).read().split(chr(10))
i=[k for k,l in enumerate(L) if l.startswith('## How')][0]
L.insert(i-1, '| \`ghost\` | \`v2\` | \`ghost\` | in progress | x |')
open(p,'w').write(chr(10).join(L))"
gen --check >/dev/null 2>&1 && r=insync || r=drift
check "extra row in SPRINT.md → drift" drift "$r"

{ printf '# b\n\n## In flight\n\n'; head -3 "$LAB/table.md"; printf '\n## How\n'; } > "$LAB/B.md"
gen --check >/dev/null 2>&1 && r=insync || r=drift
check "missing rows in SPRINT.md → drift" drift "$r"

[ "$fail" -eq 0 ] || { printf '\nboard-generated: FAIL\n' >&2; exit 1; }
printf 'board-generated: PASS\n'
