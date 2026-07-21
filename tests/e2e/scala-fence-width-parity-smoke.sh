#!/usr/bin/env bash
# scala-fence-width-parity — the assembled-launcher guard for W5
# (specs/w5-int-width-findings.md, Sergiy's Option C decision 2026-07-21).
#
# A ` ```scala ` fence and a ` ```scalascript ` fence containing the SAME
# computed integer overflow MUST produce byte-identical output on every lane.
# The `Int` width follows the BACKEND, not the fence tag (specs/numeric-widths.md
# §2: `Int` is 64-bit; the two expiring v1-codegen lanes are 32-bit, but a
# `scala` fence and a `scalascript` fence truncate *identically* there). Today a
# `scala` fence runs through the ScalaScript engine, byte-identical to a
# `scalascript` fence — the scalac/Scala.js routing that would make a `scala`
# fence carry Scala's 32-bit `Int` is unreachable dead code (findings §1).
#
# This guard therefore asserts scala-fence-output == scalascript-fence-output
# PER LANE — it is NOT a 64-bit assertion, so it holds on 32-bit and 64-bit
# lanes alike. It goes RED the moment anyone revives the dead Scala.js branch and
# a `scala` fence diverges from a `scalascript` fence, instead of one word
# silently meaning two widths (exit 0, corrupted). This is a silent-corruption
# class of bug, so the asserts print expected/got on mismatch — a "did not crash"
# check would be vacuous.
#
# Every value is COMPUTED and no literal exceeds 2^31-1, to dodge the v1-interp
# literal fail-open bug (BUGS.md -> v1-interp-int-literal-above-2^31-becomes-null,
# see tests/conformance/int-width.ssc). Do not "simplify" the body into a big
# literal.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/scala-fence-width-parity.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

# identical body; ONLY the fence tag differs (the whole point of the guard).
#   println(2147483647 + 1)  -> 2^31 : 64-bit=2147483648 ; 32-bit wraps=-2147483648
#   println(c * 2 + 1)       -> 64-bit=4294967295 ; 32-bit(JVM)=-1
body='val c: Int = 2147483647
println(2147483647 + 1)
println(c * 2 + 1)'

ss="$sandbox/scalascript.ssc"
sc="$sandbox/scala.ssc"
printf '```scalascript\n%s\n```\n' "$body" > "$ss"
printf '```scala\n%s\n```\n' "$body" > "$sc"

flat() { printf '%s' "$1" | tr '\n' '|'; }

# assert_parity <lane-label> <launcher> <args...>
# Runs the SAME two-file pair through <launcher> and asserts the ` ```scala `
# fence output equals the ` ```scalascript ` fence output. The `scalascript`
# fence is the reference ("expected"); the `scala` fence is "got".
assert_parity() {
  local label="$1"; shift
  local want got rcw rcg
  want=$(SSC_NO_CDS=1 "$@" "$ss" 2>/dev/null); rcw=$?
  got=$(SSC_NO_CDS=1 "$@" "$sc" 2>/dev/null);  rcg=$?
  if [[ $rcw -ne 0 || $rcg -ne 0 ]]; then
    echo "FAIL [$label] launcher error (scalascript rc=$rcw, scala rc=$rcg)"
    fails=$((fails+1)); return
  fi
  if [[ "$want" != "$got" ]]; then
    echo "FAIL [$label] scala fence != scalascript fence"
    echo "            expected (scalascript fence)='$(flat "$want")'"
    echo "            got      (scala fence)      ='$(flat "$got")'"
    fails=$((fails+1)); return
  fi
  echo "ok   [$label] scala == scalascript : $(flat "$want")"
}

# ── always-available lanes (pure JVM launchers) ─────────────────────────────
assert_parity "native (v2, bin/ssc run)"          "$ROOT/bin/ssc" run
assert_parity "interp (v1, ssc-tools run --v1)"   "$ROOT/bin/ssc-tools" run --v1

# ── JS lanes (need node) ────────────────────────────────────────────────────
if command -v node >/dev/null 2>&1; then
  assert_parity "js (v2, ssc-tools run-js --v2)"  "$ROOT/bin/ssc-tools" run-js --v2

  # v1 JS codegen: emit JS, run it under node, compare. (findings: emit-js is
  # byte-identical for the two fences today; this pins that.)
  wjs=$(SSC_NO_CDS=1 "$ROOT/bin/ssc-tools" emit-js "$ss" 2>/dev/null | node 2>/dev/null)
  gjs=$(SSC_NO_CDS=1 "$ROOT/bin/ssc-tools" emit-js "$sc" 2>/dev/null | node 2>/dev/null)
  if [[ "$wjs" != "$gjs" ]]; then
    echo "FAIL [js-codegen (v1, emit-js|node)] scala fence != scalascript fence"
    echo "            expected (scalascript fence)='$(flat "$wjs")'"
    echo "            got      (scala fence)      ='$(flat "$gjs")'"
    fails=$((fails+1))
  else
    echo "ok   [js-codegen (v1, emit-js|node)] scala == scalascript : $(flat "$wjs")"
  fi
else
  echo "skip [js lanes] node not on PATH"
fi

# ── JVM v1 codegen (needs scala-cli) ────────────────────────────────────────
if command -v scala-cli >/dev/null 2>&1; then
  assert_parity "jvm-codegen (v1, ssc-tools run-jvm)" "$ROOT/bin/ssc-tools" run-jvm
else
  echo "skip [jvm-codegen lane] scala-cli not on PATH"
fi

if [[ $fails -ne 0 ]]; then
  echo "scala-fence-width-parity-smoke: $fails check(s) FAILED" >&2
  exit 1
fi
echo "scala-fence-width-parity-smoke: all checks passed"
