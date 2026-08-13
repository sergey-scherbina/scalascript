#!/usr/bin/env bash
# An EMPTY code fence must not break the file.
#
# WHY THIS EXISTS. The runner joins every code fence into one source and appends a
# `__sscBlockEnd__` sentinel per fence, because that is all the block boundary leaves behind.
# F consumes the sentinel in walkTopStep2 -- AFTER parseTopItem has run. An empty fence produces a
# sentinel with no item in front of it, so it was parsed as an ordinary expression and reached the
# program as `unbound global: (global __sscBlockEnd__)`: F refused the whole file over a name the
# user never wrote, and fell back to the reference front. The OUTPUT stayed correct, which is why
# nothing caught it -- only F's coverage was lost, and an output gate cannot see which front ran.
#
# So this gate asserts the FRONT, not the output. `ssc info --front-report` names it per file.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.." || exit 2
export SSC_NO_BUILD_CHECK=1
SELFTEST=0; [ "${1:-}" = "--self-test" ] && SELFTEST=1
. tests/e2e/lib/ssc-usable.sh 2>/dev/null || true
if [ $SELFTEST -eq 0 ] && command -v ssc_usable_or_skip >/dev/null 2>&1; then
  ssc_usable_or_skip f-blockend-sentinel-gate ./bin/ssc || exit 0
fi

work=$(mktemp -d "${TMPDIR:-/tmp}/fbes.XXXXXX"); trap 'rm -rf "$work"' EXIT HUP INT TERM
fail=0

# The verdict lives in a TSV line whose first field is the path. Anything else in the output is
# preamble -- and the preamble contains the word "F" ("F did not lower this file"), so grepping for
# a bare word reads GAP as F. An EMPTY line means bin/ssc is not built: a broken run, not a verdict.
verdict() {
  local f=$1 line
  line=$(timeout 90 ./bin/ssc info --front-report "$f" 2>&1 | grep -F "$f	" | head -1)
  [ -n "$line" ] || { echo "__NO_REPORT__"; return; }
  printf '%s' "$line" | cut -f2
}

doc() {
  local name=$1 body=$2
  printf '%s\n' "$body" > "$work/$name.ssc"
  local v out
  v=$(verdict "$work/$name.ssc")
  out=$(SSC_FRONT_STRICT=1 timeout 90 ./bin/ssc run "$work/$name.ssc" 2>&1 | head -1)
  [ $SELFTEST -eq 1 ] && [ "$name" = "empty-fence-last" ] && v="GAP"
  if [ "$v" = "F" ] && [ "$out" = "a" ]; then
    printf '  %-20s F\n' "$name"
  else
    printf '  %-20s вердикт: %s   вывод: %s\n' "$name" "$v" "$out"
    fail=$((fail+1))
  fi
}

echo "── пустая ограда, F должен лоурить файл сам:"
doc empty-fence-last '```scalascript
def main() = println("a")
```

```scalascript
```'
doc empty-fence-first '```scalascript
```

```scalascript
def main() = println("a")
```'
doc empty-fence-middle '```scalascript
def main() = println("a")
```

```scalascript
```

```scalascript
def unused(): Int = 1
```'
doc two-empty-fences '```scalascript
```

```scalascript
```

```scalascript
def main() = println("a")
```'
# The shape that always worked: without it a fix that simply stopped emitting the sentinel would
# pass this gate while silently taking per-block auto-output down with it.
doc no-empty-fence '```scalascript
def main() = println("a")
```'

if [ $SELFTEST -eq 1 ]; then
  if [ $fail -eq 1 ]; then echo "✓ self-test: гейт краснеет на подложенном вердикте"; exit 0
  elif [ $fail -gt 1 ]; then echo "✗ self-test: покраснело $fail строк вместо одной"; exit 1
  else echo "✗ self-test: подложил GAP, гейт остался зелёным — он ничего не проверяет"; exit 1; fi
fi
if [ $fail -gt 0 ]; then echo "✗ f-blockend-sentinel-gate: провалов — $fail"; exit 1; fi
echo "✓ f-blockend-sentinel-gate: пустая ограда не мешает F"
