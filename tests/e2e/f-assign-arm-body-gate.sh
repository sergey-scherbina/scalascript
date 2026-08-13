#!/usr/bin/env bash
# A match arm whose body STARTS with an assignment must run the rest of the body too.
#
# WHY THIS EXISTS. `case "a" => acc = 5; acc` returned the assignment and dropped everything after
# it, so F answered `<closure>` where the reference front and the v1 interpreter both answer 5. A
# wrong answer at exit 0, not a refusal -- nothing was red.
#
# The rows are shaped around the two continuations the block path already distinguishes, because the
# fix mirrors that path rather than inventing one: a SIMPLE `x = e` continues as `(seq set rest)`
# with the scope unchanged, a COMPOUND `x += e` as `(let (set) rest)` pushing an anon slot. A gate
# with only the simple form would pass on a fix that got the compound scope wrong, and vice versa.
#
# `assign-only` carries the case with NO continuation: the shape that was always correct. Without it
# a "fix" that unconditionally appended a sequence would pass.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.." || exit 2
export SSC_NO_BUILD_CHECK=1
SELFTEST=0; [ "${1:-}" = "--self-test" ] && SELFTEST=1
. tests/e2e/lib/ssc-usable.sh 2>/dev/null || true
if [ $SELFTEST -eq 0 ] && command -v ssc_usable_or_skip >/dev/null 2>&1; then
  ssc_usable_or_skip f-assign-arm-body-gate ./bin/ssc || exit 0
fi

work=$(mktemp -d "${TMPDIR:-/tmp}/faab.XXXXXX"); trap 'rm -rf "$work"' EXIT HUP INT TERM
fail=0

# All three lanes must agree AND match the expected value. Agreement alone is not enough here: the
# defect was a wrong VALUE, and three lanes could in principle agree on a wrong one.
row() {
  local name=$1 want=$2 body=$3
  printf '%s\n' "$body" > "$work/$name.ssc"
  local f ref interp
  f=$(SSC_FRONT_STRICT=1 timeout 90 ./bin/ssc run "$work/$name.ssc" 2>&1 | head -1)
  ref=$(SSC_FRONT=legacy timeout 90 ./bin/ssc run "$work/$name.ssc" 2>&1 | head -1)
  interp=$(timeout 90 ./bin/ssc-tools run --v1 "$work/$name.ssc" 2>&1 | head -1)
  [ $SELFTEST -eq 1 ] && [ "$name" = "simple-then-expr" ] && f="<closure>"
  if [ "$f" = "$want" ] && [ "$ref" = "$want" ] && [ "$interp" = "$want" ]; then
    printf '  %-18s %s\n' "$name" "$want"
  else
    printf '  %-18s ждали %s   F: %s   эталон: %s   интерп: %s\n' "$name" "$want" "$f" "$ref" "$interp"
    fail=$((fail+1))
  fi
}

echo "── тело ветки, начинающееся с присваивания:"
row simple-then-expr 5 'def f(k: String): Int =
  var acc = 0
  k match
    case "a" => acc = 5; acc
    case _   => 0
def main() = println(f("a"))'
row compound-then-expr 5 'def f(k: String): Int =
  var acc = 0
  k match
    case "a" => acc += 5; acc
    case _   => 0
def main() = println(f("a"))'
row two-assigns 7 'def f(k: String): Int =
  var acc = 0
  k match
    case "a" => acc = 1; acc = acc + 6; acc
    case _   => 0
def main() = println(f("a"))'
row val-then-assign 5 'def f(k: String): Int =
  var acc = 0
  k match
    case "a" => val n = 2; acc = n + 3; acc
    case _   => 0
def main() = println(f("a"))'
# No continuation: the shape that always worked.
row assign-only 7 'def f(k: String): Int =
  var acc = 0
  k match
    case "a" => acc = 7
    case _   => acc = 0
  acc
def main() = println(f("a"))'

if [ $SELFTEST -eq 1 ]; then
  if [ $fail -eq 1 ]; then echo "✓ self-test: гейт краснеет на подложенном ответе"; exit 0
  elif [ $fail -gt 1 ]; then echo "✗ self-test: покраснело $fail строк вместо одной"; exit 1
  else echo "✗ self-test: подложил старый ответ, гейт остался зелёным"; exit 1; fi
fi
if [ $fail -gt 0 ]; then echo "✗ f-assign-arm-body-gate: провалов — $fail"; exit 1; fi
echo "✓ f-assign-arm-body-gate: присваивание в голове ветки не съедает остаток"
