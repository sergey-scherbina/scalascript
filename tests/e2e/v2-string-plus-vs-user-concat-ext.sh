#!/usr/bin/env bash
set -euo pipefail

# `+` on a string must NOT be dispatched to a user-defined `++` extension.
#
# `ssc1-lower.ssc0` rewrote `+` to `++` whenever it detected a string operand (a KC5
# micro-optimisation), and the `++` arm routes to `__arithExt__` when the program has an extension
# named `++` in scope. Ordinary string concatenation was therefore handed to that extension:
# with std/dsl/pretty.ssc imported (`extension (l: Doc) def ++`), `"concat=" + v` evaluated to
# `DocBeside(concat=, 1)` on v2 and `concat=1` on the interpreter.
#
# Two properties are asserted, because either alone can pass while the bug is present:
#   1. `+` on a string produces a string        — the defect itself;
#   2. `++` on the user type still builds a Doc — the optimisation's guard did not disable the
#      extension it was guarding against. A fix that simply stopped routing `++` to extensions
#      would satisfy (1) and silently break every pretty-printer.
#
# Driven against the REAL std modules rather than a hand-written extension: the bug needed
# std/parsing/combinators.ssc imported ALONGSIDE std/dsl/pretty.ssc to appear at all, and a
# self-contained fixture with one extension did NOT reproduce it. Reduced fixtures I wrote myself
# passed twice before the real imports exposed it.

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
V2="$ROOT/bin/ssc"
INT="$ROOT/bin/ssc-tools"

[[ -x $V2 && -x $INT ]] || {
  echo 'v2-string-plus-vs-user-concat-ext: run scripts/sbtc "installBin" first' >&2
  exit 2
}

tmp=$(mktemp -d "${TMPDIR:-/tmp}/v2-str-plus.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

cat >"$tmp/case.ssc" <<'SSC'
# string `+` vs a user `++` extension

[PSequence, PChoice, PMany, POpt, PMapped, PFlatMapped, PNamed, runParser](std/parsing/combinators.ssc)

[DocNil, DocText, DocLine, DocIndent, DocBeside, DocAbove, text, line, empty, indent, renderDoc, render](std/dsl/pretty.ssc)

```scalascript
val v: Int = 1
println("plus=" + v)
println("render=" + render(text("" + v)))
println("concatExt=" + render(text("a") ++ text("b")))
```
SSC

run_lane() {
  local launcher=$1 label=$2 out=$3
  shift 3
  # `run` FIRST, then the lane flag: `ssc-tools --v1 run f` is a usage error, and its diagnostic
  # goes to stdout, so the failure arrived as an empty stderr and looked like a crash.
  if ! timeout 120 "$launcher" run "$@" "$tmp/case.ssc" >"$out" 2>"$out.err" </dev/null; then
    echo "v2-string-plus-vs-user-concat-ext: $label run FAILED" >&2
    cat "$out.err" >&2
    exit 1
  fi
}

run_lane "$INT" int "$tmp/int.out" --v1
run_lane "$V2"  v2  "$tmp/v2.out"  --v2

# The interpreter is the reference, but assert the expected text too: if BOTH lanes regressed the
# same way, a lane-vs-lane diff alone would report agreement and call the bug fixed.
want=$'plus=1\nrender=1\nconcatExt=ab'
for lane in int v2; do
  got=$(cat "$tmp/$lane.out")
  if [[ $got != "$want" ]]; then
    echo "v2-string-plus-vs-user-concat-ext: wrong output on $lane" >&2
    echo "--- want" >&2; printf '%s\n' "$want" >&2
    echo "--- got"  >&2; printf '%s\n' "$got"  >&2
    diff <(printf '%s\n' "$want") <(printf '%s\n' "$got") >&2 || true
    exit 1
  fi
done

cmp "$tmp/int.out" "$tmp/v2.out" || {
  echo 'v2-string-plus-vs-user-concat-ext: int and v2 disagree' >&2
  diff "$tmp/int.out" "$tmp/v2.out" >&2 || true
  exit 1
}

echo 'PASS v2-string-plus-vs-user-concat-ext (3 rows, int == v2)'
