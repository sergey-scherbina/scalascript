#!/usr/bin/env bash
#
# A triple-quoted literal whose CONTENT ends with a quote must still be a String.
#
#   val a = """x""""        content is  x"     — four closing quotes: one is content
#   println("[" + a + "]")  must print  [x"]
#
# WHY. On the interpreter, the js lane and the jvm lane that program prints `List(x")`. Not a
# String at all — the concatenation produces a LIST. Only the native lane prints `[x"]`. A control in the
# same fixture (`"""x"""`, no trailing quote in the content) is right on every lane, so this is the
# trailing quote and not triple-quoting in general.
#
# HOW IT WAS FOUND, because the path matters more than the repro. `std-ui-forms-smoke` failed with
# `InterpretError: Undefined: impl` — a name that appears in no source file in this repository, at a
# reported position (line 36, col 196) that does not exist in the file it names. Bisecting the
# component put it in `input.ssc`'s `render`, then in one line of it:
#
#   val inv = if error.nonEmpty then """ aria-invalid="true"""" else ""
#
# That literal ends with `"`. Everything downstream of it — three `${raw(...)}` in one `html`
# interpolation — then failed to resolve a name nobody wrote. The diagnostic is not just wrong, it
# is unrelated to the cause, which is why the gate that hit it sat red and unread for months.
#
# WHAT THIS CHECKS:
#   1. the literal is a String on every lane, and `"[" + a + "]"` yields `[x"]`;
#   2. the control literal without a trailing quote is correct too — so a failure in (1) accuses
#      the trailing quote rather than triple-quoting;
#   3. all four lanes, because THREE of them agree with each other and are all wrong. int, js and
#      jvm share the front that mis-lexes this; native has its own and is the only one that matches
#      Scala. A majority is not a verdict — the lane that is right here is outvoted 3 to 1.
#
# (BUGS.md triple-quoted-literal-ending-in-a-quote-is-not-a-string.)
#
# Usage: tests/e2e/triple-quote-trailing-quote-gate.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SSC="${SSC_BIN:-$ROOT/bin/ssc-tools}"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/ssc-tq.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

cat > "$TMP/tq.ssc" <<'EOF'
# triple-quoted literal whose content ends with a quote

```scalascript
val a = """x""""
println("[" + a + "]")
val b = """x"""
println("[" + b + "]")
```
EOF

fails=0
check() { # $1 label, rest = command
  local label="$1"; shift
  local out
  out="$(SSC_NO_BUILD_CHECK=1 timeout 180 "$@" "$TMP/tq.ssc" </dev/null 2>&1 || true)"
  local want_a='[x"]'
  local want_b='[x]'
  if printf '%s' "$out" | grep -qF "$want_a" && printf '%s' "$out" | grep -qF "$want_b"; then
    printf '  ok   %s\n' "$label"
  else
    printf '  FAIL %s — wanted %s then %s\n' "$label" "$want_a" "$want_b"
    printf '%s\n' "$out" | head -4 | sed 's/^/         | /'
    fails=$((fails + 1))
  fi
}

# ALL FOUR lanes are checked the same way as of 2026-08-05. int, js and jvm were declared KNOWN
# GAPS here — the defect was in the front the three of them share, and native has its own — and each
# cell was written to FAIL if it started passing, which is how this block announced its own removal.
# Fixed in the shared front: Scala ends a multi-line literal at the LAST quote of a maximal run, and
# four preprocessing scanners all stopped at the first `"""`.
# BUGS `triple-quoted-literal-ending-in-a-quote-is-not-a-string`.
#
# The declared cells also used `producer | grep -q` where `check` uses a command substitution. With
# the pipe, the js cell reported "still a gap" while the very same command captured to a variable
# printed the right answer — reproducible inside this gate, not reproducible outside it, and never
# explained. Rather than leave a cell whose verdict depends on the shape of its plumbing, all four
# now go through `check`, which captures. Worth knowing if a cell here ever disagrees with a manual
# run again.
check "int"    "$SSC" run --v1
check "js"     "$SSC" run-js
check "jvm"    "$SSC" run-jvm
check "native" "$ROOT/bin/ssc"

if [[ $fails -ne 0 ]]; then
  printf 'triple-quote-trailing-quote-gate: FAIL (%d cell(s))\n' "$fails" >&2
  exit 1
fi
printf 'triple-quote-trailing-quote-gate: OK (all four lanes agree with the jvm oracle)\n'
