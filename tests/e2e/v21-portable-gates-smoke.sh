#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-portable-gates.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

if [[ ! -x "$ROOT/bin/ssc" ]]; then
  echo 'v21-portable-gates-smoke: run scripts/sbtc "installBin" first' >&2
  exit 2
fi

set +e
"$ROOT/scripts/run-with-timeout" 1 sh -c 'sleep 2'
timeout_rc=$?
set -e
[[ $timeout_rc -eq 124 ]] || {
  echo "expected timeout exit 124, got $timeout_rc" >&2
  exit 1
}

(
  cd "$tmp"
  "$ROOT/scripts/bc-parity-sweep" --only hello.ssc --timeout 20 \
    --report "$tmp/bytecode.tsv" --strict
  "$ROOT/scripts/native-front-corpus" --only hello.ssc --timeout 20 \
    --report "$tmp/native.tsv" --strict
)

grep -F $'hello.ssc\tidentical\t0\t0' "$tmp/bytecode.tsv" >/dev/null
grep -F $'hello.ssc\tOK\tclear\tOK\tOK\t0\t0\t0' "$tmp/native.tsv" >/dev/null

echo 'v21 portable gates smoke: PASS'
