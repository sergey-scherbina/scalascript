#!/usr/bin/env bash
set -euo pipefail

# NFC on the STANDARD launcher.
#
# Replaces v21-explicit-nfc-provider-smoke.sh, whose last check required plain `ssc` to FAIL with
# `unbound global: nfcCapabilities`. NFC shipped as an opt-in provider under bin/lib/providers/nfc;
# the owner's decision on 2026-07-31 moved it into the standard graph, the same call taken for MCP
# earlier the same day. That assertion had to be inverted — but deleting a gate to make a change
# pass is how coverage disappears quietly, so everything else is carried over verbatim: the same
# example, the same exact expected row, the same VM/ASM equality. Only the launcher changed.
#
# What it protects: `bin/ssc` is the launcher the conformance contract drives, and `nfc-ndef` was
# red on v2 for no reason except this graph being absent from it.

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
LAUNCHER="$ROOT/bin/ssc"
STANDARD="$ROOT/bin/lib/standard/jars"
EXAMPLE="$ROOT/examples/nfc-ndef.ssc"

[[ -x $LAUNCHER && -d $STANDARD ]] || {
  echo 'v21-standard-nfc-smoke: run scripts/sbtc "installBin" first' >&2
  exit 2
}

# The plugin JAR must be in the STANDARD graph, and the provider directory must NOT come back —
# without both, this suite still passes on a build that quietly reverted to opt-in packaging.
find "$STANDARD" -maxdepth 1 -type f -name '*.jar' -print \
  | grep -F 'scalascript-v2-native-nfc-plugin_' >/dev/null || {
  echo 'v21-standard-nfc-smoke: the NFC plugin JAR is not in bin/lib/standard/jars' >&2
  exit 1
}
if [[ -d "$ROOT/bin/lib/providers/nfc" ]]; then
  echo 'v21-standard-nfc-smoke: bin/lib/providers/nfc is back — NFC would be staged twice' >&2
  exit 1
fi

# Carried over from the provider gate, and it matters MORE now: this JAR sits on every `ssc`
# invocation rather than an opt-in one.
if find "$STANDARD" -maxdepth 1 -type f -name '*.jar' -print | grep -Ei \
    'scalameta|scala3-compiler|compiler-driver|scalascript-(core|backend-interpreter|v2-plugin-bridge)' >/dev/null; then
  echo 'v21-standard-nfc-smoke: forbidden compatibility/compiler dependency in the standard graph' >&2
  exit 1
fi

tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-standard-nfc.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

want=$'NFC platform:  jvm-host\nNFC supported: false\nNDEF read:     false\nNDEF write:    false\nPermission:    NfcPermissionUnknown\nText record:   text en bytes=22\nURI record:    uri bytes=31\nMIME record:   application/octet-stream bytes=1,2,3,255\nScan skipped:  NFC hardware is unavailable on this backend'

run_mode() {
  local label=$1 out=$2
  shift 2
  if ! "$LAUNCHER" run "$@" "$EXAMPLE" >"$out" 2>"$out.err" </dev/null; then
    echo "v21-standard-nfc-smoke: $label run FAILED" >&2
    grep -v 'STALE BUILD' "$out.err" >&2 || true
    exit 1
  fi
}

run_mode VM  "$tmp/vm.out"
run_mode ASM "$tmp/asm.out" --bytecode

cmp "$tmp/vm.out" "$tmp/asm.out" || {
  echo 'v21-standard-nfc-smoke: VM/ASM output differs' >&2
  diff "$tmp/vm.out" "$tmp/asm.out" >&2 || true
  exit 1
}

# A bare `[[ $(…) == "$want" ]]` under `set -e` exits 1 printing NOTHING — the predecessor gate did
# exactly that, so a mismatch there looked like a silent crash.
got=$(cat "$tmp/vm.out")
if [[ $got != "$want" ]]; then
  echo 'v21-standard-nfc-smoke: wrong output' >&2
  echo '--- want' >&2; printf '%s\n' "$want" >&2
  echo '--- got'  >&2; printf '%s\n' "$got"  >&2
  diff <(printf '%s\n' "$want") <(printf '%s\n' "$got") >&2 || true
  exit 1
fi

# The interpreter is the reference the conformance contract uses; assert agreement with it too, so
# this cannot drift into agreeing only with the hand-copied string above.
#
# ⚠️ The two lanes disagree on ONE line BY DESIGN, and this gate states that rather than hiding it.
# `NfcIntrinsics.scala:100` hard-codes `platform = "interpreter"` and `NfcNativePlugin.scala:68`
# hard-codes `platform = "jvm-host"`; each is asserted by its own unit test. So the packaging change
# that put NFC in the standard graph cannot make `nfc-ndef` match int — that row stays red for this
# reason, which is a decision to take, not a defect to paper over. Pinning both constants here means
# the day either side changes its mind, this gate says so instead of silently agreeing again.
if ! "$ROOT/bin/ssc-tools" run --v1 "$EXAMPLE" >"$tmp/int.out" 2>"$tmp/int.err" </dev/null; then
  echo 'v21-standard-nfc-smoke: the interpreter reference run FAILED' >&2
  grep -v 'STALE BUILD' "$tmp/int.err" >&2 || true
  exit 1
fi

head -1 "$tmp/int.out" | grep -qx 'NFC platform:  interpreter' || {
  echo 'v21-standard-nfc-smoke: int no longer reports platform "interpreter"' >&2
  head -1 "$tmp/int.out" >&2
  exit 1
}
head -1 "$tmp/vm.out" | grep -qx 'NFC platform:  jvm-host' || {
  echo 'v21-standard-nfc-smoke: v2 no longer reports platform "jvm-host"' >&2
  head -1 "$tmp/vm.out" >&2
  exit 1
}

# Everything BELOW the platform line must agree exactly — that is the real parity claim.
diff <(tail -n +2 "$tmp/int.out") <(tail -n +2 "$tmp/vm.out") >/dev/null || {
  echo 'v21-standard-nfc-smoke: int and v2 disagree beyond the known platform line' >&2
  diff <(tail -n +2 "$tmp/int.out") <(tail -n +2 "$tmp/vm.out") >&2 || true
  exit 1
}

echo 'PASS v21-standard-nfc-smoke (1 exact row, VM/ASM, int == v2 below the known platform line)'
