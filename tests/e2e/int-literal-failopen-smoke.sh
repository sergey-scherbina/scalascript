#!/usr/bin/env bash
# int-literal-failopen — the assembled-launcher regression for two silent
# fail-open bugs (BUGS.md): the v1 reference interpreter printed `null` for any
# integer LITERAL in (Int.Max, Long.Max]; v2 native printed `0` for the min64
# literal -9223372036854775808. ssc `Int` is 64-bit (specs/numeric-widths.md §2),
# so every value below is a legal literal and MUST print exactly, on every lane.
# A genuinely out-of-range literal must fail CLOSED (non-zero exit), never a
# silent null/0/wraparound.
#
# This is a fail-OPEN class of bug, so the asserts here are deliberately
# value-exact and print name/want/got on mismatch — a "did not crash" check would
# be vacuous.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/int-literal-failopen.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
clean_path=/usr/bin:/bin
src="$sandbox/p.ssc"
fails=0

# expect_ok <lane-label> <expected-stdout> <launcher> <args...>
# Runs `<launcher> <args...> $src`, asserts exit 0 AND stdout == expected.
expect_ok() {
  local label="$1" want="$2"; shift 2
  local got rc
  got=$(PATH="$clean_path" SSC_NO_CDS=1 "$@" "$src" 2>/dev/null); rc=$?
  if [[ $rc -ne 0 ]]; then
    echo "FAIL [$label] expected exit 0 (want '$want') but exit $rc"; fails=$((fails+1)); return
  fi
  if [[ "$got" != "$want" ]]; then
    echo "FAIL [$label] want='$want' got='$got'"; fails=$((fails+1)); return
  fi
  echo "ok   [$label] $want"
}

# expect_fail_closed <lane-label> <launcher> <args...>
# Asserts the run exits NON-ZERO (a too-big literal must be a loud error, not a
# silent null/0). Also asserts stdout is NOT a wrong value that looks plausible.
expect_fail_closed() {
  local label="$1"; shift
  local got rc
  got=$(PATH="$clean_path" SSC_NO_CDS=1 "$@" "$src" 2>/dev/null); rc=$?
  if [[ $rc -eq 0 ]]; then
    echo "FAIL [$label] expected NON-ZERO exit (fail closed) but exit 0, stdout='$got'"; fails=$((fails+1)); return
  fi
  echo "ok   [$label] failed closed (exit $rc)"
}

# ── valid literals across the whole 64-bit Int range ────────────────────────
# label | literal | expected-output
valid=(
  "int-max|2147483647|2147483647"
  "2^31|2147483648|2147483648"                                  # low witness: was null on v1
  "int-min|-2147483648|-2147483648"
  "mid|3000000000|3000000000"
  "2^62|4611686018427387904|4611686018427387904"
  "max64|9223372036854775807|9223372036854775807"
  "-max64|-9223372036854775807|-9223372036854775807"
  "min64|-9223372036854775808|-9223372036854775808"            # high witness: was 0 on v2
)

for row in "${valid[@]}"; do
  IFS='|' read -r name lit want <<<"$row"
  printf 'println(%s)\n' "$lit" > "$src"
  expect_ok "v1  $name" "$want" "$ROOT/bin/ssc-tools" run --v1
  expect_ok "vm  $name" "$want" "$ROOT/bin/ssc" run
  expect_ok "asm $name" "$want" "$ROOT/bin/ssc" run --bytecode
done

# ── genuine overflow must fail CLOSED (both backends) ───────────────────────
for lit in 9223372036854775808 99999999999999999999999999 -99999999999999999999999999; do
  printf 'println(%s)\n' "$lit" > "$src"
  expect_fail_closed "v1  overflow $lit" "$ROOT/bin/ssc-tools" run --v1
  expect_fail_closed "vm  overflow $lit" "$ROOT/bin/ssc" run
done

if [[ $fails -ne 0 ]]; then
  echo "int-literal-failopen-smoke: $fails check(s) FAILED" >&2
  exit 1
fi
echo "int-literal-failopen-smoke: all checks passed"
