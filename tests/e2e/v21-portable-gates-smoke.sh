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
  "$ROOT/scripts/bc-parity-sweep" --only distributed-word-count.ssc \
    --report "$tmp/nondeterministic.tsv" --strict
  "$ROOT/scripts/bc-parity-sweep" --only v2-http-sql-demo.ssc \
    --report "$tmp/external-http.tsv" --strict
  "$ROOT/scripts/bc-parity-sweep" --only paginated-typed-client.ssc \
    --report "$tmp/frontend-specific.tsv" --strict
  "$ROOT/scripts/bc-parity-sweep" --only x402-metamask.ssc \
    --report "$tmp/target-specific.tsv" --strict
)

grep -F $'hello.ssc\tidentical\t0\t0' "$tmp/bytecode.tsv" >/dev/null
grep -F $'hello.ssc\tOK\tclear\tOK\tOK\t0\t0\t0' "$tmp/native.tsv" >/dev/null
grep -F $'distributed-word-count.ssc\tskipped-nondeterministic' "$tmp/nondeterministic.tsv" >/dev/null
grep -F $'v2-http-sql-demo.ssc\tskipped-nondeterministic' "$tmp/external-http.tsv" >/dev/null
grep -F $'paginated-typed-client.ssc\tskipped-backend' "$tmp/frontend-specific.tsv" >/dev/null
grep -F $'x402-metamask.ssc\tskipped-backend' "$tmp/target-specific.tsv" >/dev/null

echo 'v21 portable gates smoke: PASS'
