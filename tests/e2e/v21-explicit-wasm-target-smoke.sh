#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
TOOLS="$ROOT/bin/ssc-tools"
[[ -x $TOOLS ]] || { echo 'v21-explicit-wasm-target-smoke: run installBin first' >&2; exit 2; }
command -v node >/dev/null || { echo 'v21-explicit-wasm-target-smoke: node is required' >&2; exit 2; }

tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-explicit-wasm.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
mkdir "$tmp/pure" "$tmp/http"

(
  cd "$tmp/pure"
  "$TOOLS" emit-wasm "$ROOT/examples/wasm-scalascript.ssc"
  [[ -s main.wasm && -s wasm-scalascript.js && -s __loader.js ]]
  [[ ! -e module.wasm ]]
  [[ $(xxd -p -l 4 main.wasm) == 0061736d ]]
  grep -F '__load("./main.wasm"' wasm-scalascript.js >/dev/null
  node wasm-scalascript.js >actual.out
)
cat >"$tmp/pure/expected.out" <<'EOF'
Closest pair: (3, 4) — (3.1, 4.2)  (dist = 0.2236)
Centroid: (2.2666666666666666, 2.716666666666667)

All pairwise distances:
  (0, 0) ↔ (1, 0): 1.0000
  (0, 0) ↔ (0.5, 0.1): 0.5099
  (0, 0) ↔ (3, 4): 5.0000
  (0, 0) ↔ (3.1, 4.2): 5.2202
  (0, 0) ↔ (6, 8): 10.0000
  (1, 0) ↔ (0.5, 0.1): 0.5099
  (1, 0) ↔ (3, 4): 4.4721
  (1, 0) ↔ (3.1, 4.2): 4.6957
  (1, 0) ↔ (6, 8): 9.4340
  (0.5, 0.1) ↔ (3, 4): 4.6325
  (0.5, 0.1) ↔ (3.1, 4.2): 4.8549
  (0.5, 0.1) ↔ (6, 8): 9.6260
  (3, 4) ↔ (3.1, 4.2): 0.2236
  (3, 4) ↔ (6, 8): 5.0000
  (3.1, 4.2) ↔ (6, 8): 4.7802
EOF
cmp "$tmp/pure/expected.out" "$tmp/pure/actual.out"

(
  cd "$tmp/http"
  "$TOOLS" emit-wasm "$ROOT/examples/wasm-http.ssc"
  [[ -s main.wasm && -s wasm-http.js && -s __loader.js ]]
  [[ $(xxd -p -l 4 main.wasm) == 0061736d ]]
  grep -F '__load("./main.wasm"' wasm-http.js >/dev/null
  node --check wasm-http.js >/dev/null
)

echo 'PASS v21-explicit-wasm-target-smoke (2 exact rows: pure run, HTTP compile)'
