#!/usr/bin/env bash
# Product gate for V-6b selective nested-F0 direct ASM.
#
# Compare observable output FIRST. Only after parity is established may the
# trace marker classify which compiler backend ran; otherwise a marker could
# pre-judge a broken result as a performance success.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
SSC="$ROOT/bin/ssc"
MARKER='[SSC_FRONT=F] nested F0 direct-ASM'
TMP=$(mktemp -d "${TMPDIR:-/tmp}/v2-f-nested-bytecode.XXXXXX")
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
fails=0

if [[ ! -x "$SSC" ]]; then
  echo "SKIP v2-f-nested-bytecode-fast-path: $SSC not built (run scripts/sbtc installBin)"
  exit 0
fi

run_front() {
  local label=$1 front=$2 file=$3
  local started=$SECONDS
  env SSC_NO_CDS=1 SSC_FRONT="$front" SSC_FRONT_TRACE=1 \
    "$SSC" run "$file" >"$TMP/$label.$front.out" 2>"$TMP/$label.$front.err"
  printf '%s\n' "$?" >"$TMP/$label.$front.rc"
  printf '%s\n' "$((SECONDS - started))" >"$TMP/$label.$front.seconds"
}

compare_pair() {
  local label=$1 file=$2
  run_front "$label" F "$file"
  run_front "$label" legacy "$file"

  local f_rc legacy_rc
  f_rc=$(cat "$TMP/$label.F.rc")
  legacy_rc=$(cat "$TMP/$label.legacy.rc")
  if [[ "$f_rc" != "$legacy_rc" || "$f_rc" != 0 ]]; then
    echo "FAIL [$label] exit mismatch: F=$f_rc legacy=$legacy_rc"
    echo "     F stderr:"
    sed -n '1,80p' "$TMP/$label.F.err"
    echo "     legacy stderr:"
    sed -n '1,80p' "$TMP/$label.legacy.err"
    fails=$((fails + 1))
    return 1
  fi

  if ! cmp -s "$TMP/$label.F.out" "$TMP/$label.legacy.out"; then
    echo "FAIL [$label] stdout differs before backend classification"
    diff -u "$TMP/$label.legacy.out" "$TMP/$label.F.out" | sed -n '1,120p'
    fails=$((fails + 1))
    return 1
  fi

  echo "ok   [$label] stdout exact; F=$(cat "$TMP/$label.F.seconds")s legacy=$(cat "$TMP/$label.legacy.seconds")s"
  return 0
}

hello="$ROOT/examples/hello.ssc"
scljet="$ROOT/examples/scljet-hello.ssc"

if [[ ! -f "$hello" || ! -f "$scljet" ]]; then
  echo "FAIL fixtures missing: hello=$hello scljet=$scljet"
  exit 1
fi

if compare_pair hello "$hello"; then
  if grep -Fxq "$MARKER" "$TMP/hello.F.err"; then
    echo "FAIL [hello] selected nested direct ASM despite the small-program policy"
    fails=$((fails + 1))
  else
    echo "ok   [hello] small first F0 stayed on VM"
  fi
fi

if compare_pair scljet "$scljet"; then
  if grep -Fxq "$MARKER" "$TMP/scljet.F.err"; then
    echo "ok   [scljet] large first F0 selected nested direct ASM"
  else
    echo "FAIL [scljet] exact output passed but no direct-ASM marker was emitted"
    echo "     F stderr:"
    sed -n '1,120p' "$TMP/scljet.F.err"
    fails=$((fails + 1))
  fi
fi

if [[ $fails -ne 0 ]]; then
  echo "v2-f-nested-bytecode-fast-path: $fails failure(s)"
  exit 1
fi

echo "v2-f-nested-bytecode-fast-path: all checks passed"
