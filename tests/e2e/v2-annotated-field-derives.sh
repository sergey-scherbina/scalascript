#!/usr/bin/env bash
set -euo pipefail

# A field annotation must not truncate the parameter list.
#
# `parseCaseParam` had no case for a leading `@`, so `case class M(@key a: String, b: String)`
# hit the `_` fallback WITHOUT ADVANCING: the parameter list ended after one phantom param, the
# class lowered at arity 1 regardless of how many fields it had, and `M("A","p")` died with
# `arity: 1 expected, 2 given` before the program ran a line.
#
# Only files the F front DECLINES reach this parser, which is why it stayed hidden — and a `derives`
# clause is what makes F decline, which is why the symptom looked like a `derives` bug.
#
# The shapes below are the ones that identified the cause, kept as the regression:
#   - annotation on the FIRST field, 2 and 3 fields   — arity was 1 in BOTH, so nothing was "lost",
#                                                       the list simply stopped;
#   - annotation on the SECOND field                  — produced a `Stub` sentinel instead, i.e. a
#                                                       DIFFERENT wrong answer, at exit 0;
#   - `@key val a`                                    — annotation before the val modifier, the order
#                                                       Scala actually writes;
#   - class-level annotation                          — the control: always worked (the statement
#                                                       parser skips those), so a "fix" that only
#                                                       repaired this one would prove nothing.

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
INT="$ROOT/bin/ssc-tools"
V2="$ROOT/bin/ssc"

[[ -x $INT && -x $V2 ]] || {
  echo 'v2-annotated-field-derives: run scripts/sbtc "installBin" first' >&2
  exit 2
}

tmp=$(mktemp -d "${TMPDIR:-/tmp}/v2-ann-derives.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

cat >"$tmp/case.ssc" <<'SSC'
# annotated fields with a derives clause

import scalascript.typeddata.{JsonCodec, VertexCodec, key, graphLabel}

```scalascript
case class A(@key a: String, b: String) derives JsonCodec
case class B(a: String, @key b: String) derives JsonCodec
case class C(@key a: String, b: String, c: String) derives JsonCodec
case class D(@key val a: String, b: String) derives JsonCodec

@graphLabel("E")
case class E(a: String, b: String) derives JsonCodec, VertexCodec

println(A("1", "2").b)
println(B("1", "2").b)
println(C("1", "2", "3").c)
println(D("1", "2").b)
println(E("1", "2").b)
```
SSC

want=$'2\n2\n3\n2\n2'

run_lane() {
  local launcher=$1 label=$2 out=$3 flag=$4
  if ! timeout 120 "$launcher" run "$flag" "$tmp/case.ssc" >"$out" 2>"$out.err" </dev/null; then
    echo "v2-annotated-field-derives: $label run FAILED" >&2
    grep -v 'STALE BUILD' "$out.err" >&2 || true
    exit 1
  fi
}

run_lane "$INT" int "$tmp/int.out" --v1
run_lane "$V2"  v2  "$tmp/v2.out"  --v2

for lane in int v2; do
  got=$(cat "$tmp/$lane.out")
  if [[ $got != "$want" ]]; then
    echo "v2-annotated-field-derives: wrong output on $lane" >&2
    echo '--- want' >&2; printf '%s\n' "$want" >&2
    echo '--- got'  >&2; printf '%s\n' "$got"  >&2
    diff <(printf '%s\n' "$want") <(printf '%s\n' "$got") >&2 || true
    exit 1
  fi
done

cmp "$tmp/int.out" "$tmp/v2.out" || {
  echo 'v2-annotated-field-derives: int and v2 disagree' >&2
  diff "$tmp/int.out" "$tmp/v2.out" >&2 || true
  exit 1
}

echo 'PASS v2-annotated-field-derives (5 shapes, int == v2)'
