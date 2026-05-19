#!/usr/bin/env bash
# Run a single .ssc file through the WASM backend and execute with node.
#
# Usage: ./run-wasm.sh <file.ssc>
#
# Steps:
#   1. ssc emit-wasm <file>  →  module.wasm + <stem>.js + __loader.js  (in temp dir)
#   2. node <stem>.js

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SSC_WASM="$ROOT/bin/ssc-wasm"

if [[ ! -x "$SSC_WASM" ]]; then
  echo "error: bin/ssc-wasm not found — run ./install.sh first" >&2
  exit 2
fi

if [[ $# -eq 0 ]]; then
  echo "Usage: $0 <file.ssc>" >&2
  exit 1
fi

exec "$SSC_WASM" "$@"
