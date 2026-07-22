#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
LAUNCHER="$ROOT/bin/ssc-provider"
PROVIDER="$ROOT/bin/lib/providers/swift/jars"

[[ -x $LAUNCHER && -d $PROVIDER ]] || {
  echo 'v21-explicit-swift-provider-smoke: run scripts/sbtc "installBin" first' >&2
  exit 2
}
command -v node >/dev/null || { echo 'v21-explicit-swift-provider-smoke: node is required' >&2; exit 2; }

# Every assertion below used to be a bare `[[ ... ]]` / bare `grep` / bare `cmp` /
# a launcher run with output redirected to files — all of which, under `set -e`,
# exit 1 printing NOTHING. That silence hid a real, deterministic regression: the
# pacs.008 `GPI hop: DEUTDEFF — ACCC` em-dash printed as `?` on Linux CI (JVM
# stdout falling back to a C-locale ASCII encoding) while passing on UTF-8-locale
# developer macs. The blank gate forced a manual Docker bisect. Name every check.
fail() {
  echo "v21-explicit-swift-provider-smoke: FAILED check '$1'" >&2
  shift; for f in "$@"; do [[ -s $f ]] && { echo "--- $f" >&2; cat "$f" >&2; }; done
  exit 1
}
expect_out() {
  local name=$1 want=$2 got=$3
  if [[ $got != "$want" ]]; then
    echo "v21-explicit-swift-provider-smoke: FAILED check '$name'" >&2
    echo '--- want' >&2; printf '%s\n' "$want" >&2
    echo '--- got'  >&2; printf '%s\n' "$got"  >&2
    diff <(printf '%s\n' "$want") <(printf '%s\n' "$got") >&2 || true
    exit 1
  fi
}

find "$PROVIDER" -maxdepth 1 -type f -name '*.jar' -print |
  grep -F 'scalascript-v2-native-swift-plugin_' >/dev/null ||
  fail 'swift plugin jar staged'
if find "$PROVIDER" -maxdepth 1 -type f -name '*.jar' -print | grep -Ei \
    'scalameta|scala3-compiler|compiler-driver|scalascript-(core|backend-interpreter|v2-plugin-bridge)' >/dev/null; then
  echo 'v21-explicit-swift-provider-smoke: forbidden compatibility/compiler dependency' >&2
  exit 1
fi
if find "$ROOT/bin/lib/standard/jars" -maxdepth 1 -type f -name '*.jar' -print |
    grep -F 'scalascript-v2-native-swift-plugin_' >/dev/null; then
  echo 'v21-explicit-swift-provider-smoke: provider leaked into standard jars' >&2
  exit 1
fi

tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-explicit-swift.XXXXXX")
server_pid=
cleanup() {
  [[ -z $server_pid ]] || kill "$server_pid" 2>/dev/null || true
  rm -rf "$tmp"
}
trap cleanup EXIT HUP INT TERM
node "$ROOT/examples/swift-server-tools.js" "$tmp/url" >"$tmp/server.out" 2>"$tmp/server.err" &
server_pid=$!
for _ in $(seq 1 100); do
  [[ -s $tmp/url ]] && break
  kill -0 "$server_pid" 2>/dev/null || { cat "$tmp/server.err" >&2; exit 1; }
  sleep 0.05
done
[[ -s $tmp/url ]] || { echo 'SWIFT fixture did not start' >&2; exit 1; }
url=$(cat "$tmp/url")

for mode in vm asm; do
  args=()
  [[ $mode == asm ]] && args+=(--bytecode)
  SWIFT_AGGREGATOR_URL="$url" SWIFT_API_KEY=test-swift-key \
    "$LAUNCHER" swift run "${args[@]}" \
      "$ROOT/examples/international-bank-rails.ssc" \
      >"$tmp/$mode.out" 2>"$tmp/$mode.err" ||
    fail "swift run ($mode)" "$tmp/$mode.err"
done
cmp -s "$tmp/vm.out" "$tmp/asm.out" || {
  echo "v21-explicit-swift-provider-smoke: FAILED check 'vm==asm bytes'" >&2
  diff "$tmp/vm.out" "$tmp/asm.out" >&2 || true
  exit 1
}
expect_out 'pacs.008 transfer output' \
  $'pacs.008 Transfer ID: swift-pacs-001\nUETR: 11111111-1111-4111-8111-111111111111\nStatus: Pending\nMT103 Transfer UETR: 22222222-2222-4222-8222-222222222222\nGPI hop: DEUTDEFF — ACCC at 2026-07-12T10:00:00Z' \
  "$(cat "$tmp/vm.out")"

if SWIFT_AGGREGATOR_URL="$url" SWIFT_API_KEY=test-swift-key \
    "$ROOT/bin/ssc" run "$ROOT/examples/international-bank-rails.ssc" \
    >"$tmp/plain.out" 2>"$tmp/plain.err"; then
  echo 'v21-explicit-swift-provider-smoke: plain ssc unexpectedly loaded SWIFT' >&2
  exit 1
fi
# Lane-agnostic: the plain (no-provider) run MUST be rejected because a SWIFT-provider global is
# unbound. WHICH global is named first is a codegen/link-order artifact (the interpreter reports
# SwiftProvider, the JVM-bytecode lane reports ChargeBearer — both are SWIFT-provider symbols that
# only exist with the explicit provider). The security intent is "no silent SWIFT load": the run
# above already fail-loud exits if plain ssc SUCCEEDS (rc 0), and this line additionally asserts the
# rejection reason is an unbound-global — so a non-rejection (SWIFT loaded, no `unbound global:`
# line) still trips one of the two checks. Pin the class, not the specific symbol.
grep -F 'unbound global:' "$tmp/plain.err" >/dev/null ||
  fail 'plain ssc rejects the SWIFT-requiring program (unbound provider global)' "$tmp/plain.err"

echo 'PASS v21-explicit-swift-provider-smoke (1 exact row, VM/ASM)'
