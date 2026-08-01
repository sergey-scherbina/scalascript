#!/usr/bin/env bash
set -euo pipefail

# The JS tree shaker must not delete a side effect along with an unused binding.
#
# `val unused = eff()` binds a name nothing reads. The shaker dropped the declaration — and its
# INITIALISER with it — so `eff`'s `println` never ran and `eff` was not even emitted. Eliding a
# call is only sound when the call is pure, and nothing established that.
#
# THIS GATE MUST USE `emit-js`, AND THAT IS THE WHOLE POINT. Tree-shaking is active on the
# emit path and NOT on `run-js`, which is what the conformance runner uses — so a conformance
# case would have printed `SIDE-EFFECT` before the fix and after it, and gated nothing. The
# original entry recorded "js: end only" without naming the mode, which is why the shape looked
# like a plain codegen bug rather than a shaker one.
#
# Two assertions, because the first alone would pass a fix that merely stopped shaking:
#   1. the effect RUNS  — the defect;
#   2. a genuinely unused PURE binding is still dropped — the shaker still shakes.

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
TOOLS="$ROOT/bin/ssc-tools"

[[ -x $TOOLS ]] || { echo 'js-shaker-keeps-effectful-binding: run ./install.sh --dev first' >&2; exit 2; }

tmp=$(mktemp -d "${TMPDIR:-/tmp}/js-shaker.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

cat >"$tmp/case.ssc" <<'SSC'
def eff(): String =
  println("SIDE-EFFECT")
  "r"

val unused = eff()
val pureUnused = 42
println("end")
SSC

SSC_NO_BUILD_CHECK=1 timeout 200 "$TOOLS" emit-js "$tmp/case.ssc" >"$tmp/case.mjs" 2>"$tmp/emit.err" || {
  echo 'js-shaker-keeps-effectful-binding: emit-js FAILED' >&2
  cat "$tmp/emit.err" >&2
  exit 1
}

got=$(node "$tmp/case.mjs" 2>&1)
want=$'SIDE-EFFECT\nend'
if [[ $got != "$want" ]]; then
  echo 'js-shaker-keeps-effectful-binding: the effect was shaken away' >&2
  diff <(printf '%s\n' "$want") <(printf '%s\n' "$got") >&2 || true
  exit 1
fi

# The shaker must still be doing its job: a pure unused binding has no reason to survive.
if grep -q 'pureUnused' "$tmp/case.mjs"; then
  echo 'js-shaker-keeps-effectful-binding: a PURE unused binding survived — the fix is too broad,' >&2
  echo '  it stopped shaking rather than distinguishing effectful initialisers' >&2
  exit 1
fi

echo 'PASS js-shaker-keeps-effectful-binding (effect kept, pure unused binding still dropped)'
