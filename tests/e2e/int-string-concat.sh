#!/usr/bin/env bash
set -euo pipefail

# `"a" ++ "b"` must CONCATENATE on every lane.
#
# The interpreter built a PAIR: `(x, y)`. `DispatchRuntime`'s binary-operator table for `++` lists
# List/Set/Map/Tuple/Unit shapes and ends in `case _ => Pure(Value.TupleV(lhs :: rhs :: Nil))`, and
# two Strings match none of the listed shapes. v2 and js both concatenated, so the CORPUS GOLDEN was
# the wrong lane — the third time in one session that v2 turned out to be right.
#
# Three assertions, because the first two alone would pass a fix that broke something else:
#   1. String ++ String concatenates          — the defect;
#   2. List ++ List still concatenates        — the arm sits directly above the new one, and a
#                                               mis-ordered pattern would swallow it;
#   3. Tuple ++ Tuple still concatenates      — the `++`-as-tuple-append behaviour this operator
#                                               ALSO has, which the fix must not disturb.
# Assertions 2 and 3 pass both before and after the fix. That is the point: they are the guard, not
# the evidence.

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
INT="$ROOT/bin/ssc-tools"
V2="$ROOT/bin/ssc"

[[ -x $INT && -x $V2 ]] || {
  echo 'int-string-concat: run scripts/sbtc "installBin" first' >&2
  exit 2
}

tmp=$(mktemp -d "${TMPDIR:-/tmp}/int-string-concat.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

cat >"$tmp/case.ssc" <<'SSC'
# string ++ parity

```scalascript
val s: String = "x"
println(s ++ "y")
println("lit" ++ "eral")
println(List(1, 2) ++ List(3))
println((1, 2) ++ (3, 4))
```
SSC

want=$'xy\nliteral\nList(1, 2, 3)\n(1, 2, 3, 4)'

run_lane() {
  local launcher=$1 label=$2 out=$3 flag=$4
  if ! timeout 120 "$launcher" run "$flag" "$tmp/case.ssc" >"$out" 2>"$out.err" </dev/null; then
    echo "int-string-concat: $label run FAILED" >&2
    grep -v 'STALE BUILD' "$out.err" >&2 || true
    exit 1
  fi
}

run_lane "$INT" int "$tmp/int.out" --v1
run_lane "$V2"  v2  "$tmp/v2.out"  --v2

for lane in int v2; do
  got=$(cat "$tmp/$lane.out")
  if [[ $got != "$want" ]]; then
    echo "int-string-concat: wrong output on $lane" >&2
    echo '--- want' >&2; printf '%s\n' "$want" >&2
    echo '--- got'  >&2; printf '%s\n' "$got"  >&2
    diff <(printf '%s\n' "$want") <(printf '%s\n' "$got") >&2 || true
    exit 1
  fi
done

cmp "$tmp/int.out" "$tmp/v2.out" || {
  echo 'int-string-concat: int and v2 disagree' >&2
  diff "$tmp/int.out" "$tmp/v2.out" >&2 || true
  exit 1
}

echo 'PASS int-string-concat (string/list/tuple ++, int == v2)'
