#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
TOOLS="$ROOT/bin/ssc-tools"
[[ -x $TOOLS ]] || { echo 'v21-explicit-wasm-target-smoke: run installBin first' >&2; exit 2; }
command -v node >/dev/null || { echo 'v21-explicit-wasm-target-smoke: node is required' >&2; exit 2; }

command -v xxd >/dev/null || { echo 'v21-explicit-wasm-target-smoke: xxd is required' >&2; exit 2; }

# `emit-wasm` produces a WasmGC module. A V8 without WasmGC enabled by default
# (node 20 and older) dies with a cryptic `CompileError: Unknown type code 0x5e,
# enable with --experimental-wasm-gc` from deep inside the generated loader. Say so
# up front instead. The flag is not a portable workaround: modern node REJECTS it
# ("bad option") because WasmGC is standard there.
node_major=$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)
if [[ ${node_major:-0} -lt 22 ]]; then
  echo "v21-explicit-wasm-target-smoke: node >= 22 required (found $(node --version 2>/dev/null))" >&2
  echo '  the wasm target emits WasmGC; node 20 needs --experimental-wasm-gc' >&2
  exit 2
fi

tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-explicit-wasm.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
mkdir "$tmp/pure" "$tmp/http"

# Every assertion below used to be a bare `[[ ... ]]` under `set -e`, which exits 1
# printing NOTHING — the CI log just stopped after "Wrote .../__loader.js" with no
# clue which check failed. Name each one.
check() {
  local name=$1; shift
  if ! "$@"; then
    echo "v21-explicit-wasm-target-smoke: FAILED check '$name'" >&2
    echo "--- cwd: $PWD" >&2
    ls -la >&2 || true
    exit 1
  fi
}
expect_out() {
  local name=$1 want=$2 got=$3
  if [[ $got != "$want" ]]; then
    echo "v21-explicit-wasm-target-smoke: FAILED check '$name'" >&2
    echo "--- want: $want" >&2
    echo "--- got:  $got"  >&2
    exit 1
  fi
}

(
  cd "$tmp/pure"
  "$TOOLS" emit-wasm "$ROOT/examples/wasm-scalascript.ssc"
  check 'pure: main.wasm non-empty'           test -s main.wasm
  check 'pure: wasm-scalascript.js non-empty' test -s wasm-scalascript.js
  check 'pure: __loader.js non-empty'         test -s __loader.js
  check 'pure: no stray module.wasm'          test ! -e module.wasm
  expect_out 'pure: main.wasm magic' 0061736d "$(xxd -p -l 4 main.wasm)"
  check 'pure: js loads ./main.wasm' grep -qF '__load("./main.wasm"' wasm-scalascript.js
  if ! node wasm-scalascript.js >actual.out 2>node.err; then
    echo 'v21-explicit-wasm-target-smoke: FAILED check pure: node run' >&2
    cat node.err >&2; exit 1
  fi
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
if ! cmp -s "$tmp/pure/expected.out" "$tmp/pure/actual.out"; then
  echo "v21-explicit-wasm-target-smoke: FAILED check 'pure: output'" >&2
  diff "$tmp/pure/expected.out" "$tmp/pure/actual.out" >&2 || true
  exit 1
fi

(
  cd "$tmp/http"
  "$TOOLS" emit-wasm "$ROOT/examples/wasm-http.ssc"
  check 'http: main.wasm non-empty'   test -s main.wasm
  check 'http: wasm-http.js non-empty' test -s wasm-http.js
  check 'http: __loader.js non-empty' test -s __loader.js
  expect_out 'http: main.wasm magic' 0061736d "$(xxd -p -l 4 main.wasm)"
  check 'http: js loads ./main.wasm' grep -qF '__load("./main.wasm"' wasm-http.js
  if ! node --check wasm-http.js >/dev/null 2>node.err; then
    echo 'v21-explicit-wasm-target-smoke: FAILED check http: node --check' >&2
    cat node.err >&2; exit 1
  fi
)

echo 'PASS v21-explicit-wasm-target-smoke (2 exact rows: pure run, HTTP compile)'
