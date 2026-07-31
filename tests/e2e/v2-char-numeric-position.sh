#!/usr/bin/env bash
set -euo pipefail

# A Char in NUMERIC position must keep answering as its code point — on BOTH v2 fronts.
#
# This is the control half of `v2-char-is-an-int`. Giving `'x'` its own identity in the VM
# (`CharV`) is only correct if everything that treated a Char AS a number still does:
# `'x' == 120`, `'a'.toInt`, `'a' + 1`, comparisons, and a literal `case '\n' =>`. The fix
# achieves that by making `CharV` extend `IntV`, so those properties hold by construction
# rather than by enumerating 273 match sites — and this gate is what proves the construction.
#
# Why here and not in the corpus case: `tests/conformance/char-as-value.ssc` runs on int, js
# and v2, and TWO of these rows cannot pass on js — a Char is a plain String there, so
# `'x' == 120` is `false` and `'a'.toInt` is `NaN`. That is the separate, already-filed
# `js-char-is-a-plain-string`. Putting them in the corpus case would leave it permanently red
# on js for a defect it does not exist to gate; putting them in a v2-only gate keeps both
# honest. The corpus case owns Char-in-TEXT, this one owns Char-in-NUMERIC-position.
#
# BOTH FRONTS ARE RUN, and that is not decoration: an F decline is a SILENT downgrade onto
# the fallback front, so a gate that only exercises the default front reads green when F is
# broken and the fallback is fine (`bench-corpus-five-rows-measure-the-FALLBACK-front-not-F`).
# The char literal reaches the token stream through a DIFFERENT lexer in each front.

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
V2="$ROOT/bin/ssc"

[[ -x $V2 ]] || {
  echo 'v2-char-numeric-position: run ./install.sh --dev first' >&2
  exit 2
}

tmp=$(mktemp -d "${TMPDIR:-/tmp}/v2-char-numeric.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

cat >"$tmp/case.ssc" <<'SSC'
# Char in numeric position

```scalascript
def classify(c: Char): String = c match
  case '\n' => "newline"
  case '\t' => "tab"
  case _    => "other"

def main() =
  println('x' == 120)
  println('a'.toInt)
  println('a' + 1)
  println('a' < 'b')
  println('x' == 'x')
  println("a\nb".lastIndexOf('\n'))
  println(classify('\t'))
  println(classify('z'))
```
SSC

want=$'true\n97\n98\ntrue\ntrue\n1\ntab\nother'

run_front() {
  local label=$1 out=$2
  local -a env=()
  [[ $label == legacy ]] && env=(SSC_FRONT=legacy)
  if ! env SSC_NO_BUILD_CHECK=1 "${env[@]}" timeout 180 \
        "$V2" run --v2 "$tmp/case.ssc" >"$out" 2>"$out.err" </dev/null; then
    echo "v2-char-numeric-position: $label front run FAILED" >&2
    grep -v 'STALE BUILD' "$out.err" >&2 || true
    exit 1
  fi
}

run_front F      "$tmp/f.out"
run_front legacy "$tmp/legacy.out"

for front in f legacy; do
  got=$(cat "$tmp/$front.out")
  if [[ $got != "$want" ]]; then
    echo "v2-char-numeric-position: wrong output on the $front front" >&2
    diff <(printf '%s\n' "$want") <(printf '%s\n' "$got") >&2 || true
    exit 1
  fi
done

# The file must actually be lowered by F. Without this the gate is satisfied by the fallback
# compiling it twice, which is precisely the failure mode the header warns about.
report=$(SSC_NO_BUILD_CHECK=1 "$V2" info --front-report "$tmp/case.ssc" 2>/dev/null | tail -1)
case $report in
  *"	F"*) ;;
  *) echo "v2-char-numeric-position: F did not lower the case — the run above measured the" >&2
     echo "  fallback front on both passes. front-report said: $report" >&2
     exit 1 ;;
esac

echo 'PASS v2-char-numeric-position (both fronts, Char keeps its code point where it must)'
