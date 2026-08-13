#!/usr/bin/env bash
# The last non-Unit expression of EACH top-level code block prints, on every lane.
#
# WHY THIS EXISTS. The runner joins every code fence into ONE source before the lexer runs, so the
# block boundary survives only as the `__sscBlockEnd__` sentinel it appends per fence. F consumed it
# and wrapped the preceding statement in the `__autoOutput__` prim; the reference front did not emit
# the sentinel at all and printed only the whole PROGRAM's final value. A single-block document
# looked perfect either way -- which is exactly why this went unnoticed -- and every earlier block's
# tail was silently dropped.
#
# The one-block rows are not padding. They are the shape that passes on the bug, so a gate built
# only from multi-block documents would have been green before the fix.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.." || exit 2
export SSC_NO_BUILD_CHECK=1
SELFTEST=0; [ "${1:-}" = "--self-test" ] && SELFTEST=1
. tests/e2e/lib/ssc-usable.sh 2>/dev/null || true
if [ $SELFTEST -eq 0 ] && command -v ssc_usable_or_skip >/dev/null 2>&1; then
  ssc_usable_or_skip ref-front-multiblock-gate ./bin/ssc || exit 0
fi

work=$(mktemp -d "${TMPDIR:-/tmp}/rfmb.XXXXXX"); trap 'rm -rf "$work"' EXIT HUP INT TERM
fail=0

# `want` is the expected stdout with newlines written as `|`. All three lanes must produce it: the
# v1 interpreter is the independent arbiter, and it answered correctly throughout, which is how the
# reference front was identified as the wrong one rather than F.
doc() {
  local name=$1 want=$2 body=$3
  printf '%s\n' "$body" > "$work/$name.ssc"
  local ref f interp
  ref=$(SSC_FRONT=legacy    timeout 90 ./bin/ssc run "$work/$name.ssc" 2>&1 | head -6 | tr '\n' '|')
  f=$(SSC_FRONT_STRICT=1    timeout 90 ./bin/ssc run "$work/$name.ssc" 2>&1 | head -6 | tr '\n' '|')
  interp=$(timeout 90 ./bin/ssc-tools run --v1 "$work/$name.ssc" 2>&1 | head -6 | tr '\n' '|')
  [ $SELFTEST -eq 1 ] && [ "$name" = "two-blocks" ] && ref="20|"
  if [ "$ref" = "$want" ] && [ "$f" = "$want" ] && [ "$interp" = "$want" ]; then
    printf '  %-16s %s\n' "$name" "$want"
  else
    printf '  %-16s ждали %s\n    эталон: %s   F: %s   интерп: %s\n' "$name" "$want" "$ref" "$f" "$interp"
    fail=$((fail+1))
  fi
}

echo "── авто-вывод по блокам, три полосы:"
doc two-blocks '2|20|' '```scalascript
val a = 2
a
```

```scalascript
val b = a * 10
b
```'
# Blocks share one scope -- `b` sees `a` -- so the fix must not compile them independently.
doc three-blocks '2|20|explicit|' '```scalascript
val a = 2
a
```

```scalascript
val b = a * 10
b
```

```scalascript
println("explicit")
```'
# A Unit tail prints nothing extra. Unit-ness is decided at RUNTIME inside the prim; the first
# attempt at this in F used a source-level helper and the corpus caught it on the JS/v2 lane.
doc unit-tail 'explicit|' '```scalascript
println("explicit")
```'
# A definition is not an expression statement, so it is never auto-printed -- and being LAST it also
# pins that the program-level value stays silent instead of re-printing an earlier block'"'"'s tail.
doc def-tail '2|' '```scalascript
val a = 2
a
```

```scalascript
def unused(n: Int): Int = n + 1
```'
# ── the shapes that passed on the bug ──
doc one-block '2|' '```scalascript
val a = 2
a
```'
doc one-block-two-exprs '3|' '```scalascript
val a = 2
a
a + 1
```'

if [ $SELFTEST -eq 1 ]; then
  if [ $fail -eq 1 ]; then echo "✓ self-test: гейт краснеет на подложенном выводе"; exit 0
  elif [ $fail -gt 1 ]; then echo "✗ self-test: покраснело $fail строк вместо одной — гейт красный и без подложки"; exit 1
  else echo "✗ self-test: подложил старый ответ, гейт остался зелёным — он ничего не проверяет"; exit 1; fi
fi
if [ $fail -gt 0 ]; then echo "✗ ref-front-multiblock-gate: провалов — $fail"; exit 1; fi
echo "✓ ref-front-multiblock-gate: все блоки печатают свой хвост на всех полосах"
