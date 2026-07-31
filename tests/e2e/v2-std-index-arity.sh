#!/usr/bin/env bash
set -euo pipefail

# A context-bound instance passed EXPLICITLY must not be injected a second time — and must land in
# the right parameter slot.
#
# `def combineAll[A: Monoid](xs: List[A])` assembles to arity 2 with the layout
# [ctxParams, regular] — instance FIRST. The source convention is instance LAST
# (`combineAll(xs, intSum)`), which is what tests/conformance/std-index.ssc writes. The fallback
# front prepended the summoned instance unconditionally, so the call went out 3 args wide:
# `arity: 2 expected, 3 given`, with no output at all.
#
# Two assertions, because the first fix I wrote satisfied one and broke the other:
#   1. the explicit call works             — suppressing the double injection;
#   2. the IMPLICIT call still works       — the injection itself is still there when the caller
#                                            omits the instance. A guard that simply stopped
#                                            injecting would pass (1) and break every idiomatic
#                                            `combineAll(xs)` in the std library.
# Merely suppressing the injection also bound monoid=xs and xs=instance, turning the arity error
# into `Index -1 out of bounds for length 2` — hence the rotation, and hence assertion (1) checks
# the VALUE, not just that the program runs.

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
V2="$ROOT/bin/ssc"
INT="$ROOT/bin/ssc-tools"

[[ -x $V2 && -x $INT ]] || {
  echo 'v2-std-index-arity: run scripts/sbtc "installBin" first' >&2
  exit 2
}

tmp=$(mktemp -d "${TMPDIR:-/tmp}/v2-ctxbound.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

cat >"$tmp/case.ssc" <<'SSC'
# context-bound instance: explicit and implicit

[intSum, combineAll, combineAllOption](std/semigroup-monoid.ssc)

```scalascript
val xs: List[Int] = List(1, 2, 3, 4, 5)
println(combineAll(xs, intSum))
println(combineAll(xs))
println(combineAllOption(xs, intSum))
```
SSC

run_lane() {
  local launcher=$1 label=$2 out=$3 flag=$4
  if ! timeout 120 "$launcher" run "$flag" "$tmp/case.ssc" >"$out" 2>"$out.err" </dev/null; then
    echo "v2-std-index-arity: $label run FAILED" >&2
    grep -v 'STALE BUILD' "$out.err" >&2 || true
    exit 1
  fi
}

run_lane "$INT" int "$tmp/int.out" --v1
run_lane "$V2"  v2  "$tmp/v2.out"  --v2

want=$'15\n15\nSome(15)'
for lane in int v2; do
  got=$(cat "$tmp/$lane.out")
  if [[ $got != "$want" ]]; then
    echo "v2-std-index-arity: wrong output on $lane" >&2
    echo "--- want" >&2; printf '%s\n' "$want" >&2
    echo "--- got"  >&2; printf '%s\n' "$got"  >&2
    diff <(printf '%s\n' "$want") <(printf '%s\n' "$got") >&2 || true
    exit 1
  fi
done

cmp "$tmp/int.out" "$tmp/v2.out" || {
  echo 'v2-std-index-arity: int and v2 disagree' >&2
  diff "$tmp/int.out" "$tmp/v2.out" >&2 || true
  exit 1
}

echo 'PASS v2-std-index-arity (explicit + implicit context bound, int == v2)'
