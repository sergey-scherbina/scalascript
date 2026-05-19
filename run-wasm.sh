#!/usr/bin/env bash
# Run a single .ssc file through the WASM backend and execute with node.
#
# Usage: ./run-wasm.sh <file.ssc>
#
# Steps:
#   1. ssc emit-wasm <file>  →  module.wasm + <stem>.js + __loader.js  (in temp dir)
#   2. node <stem>.js

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SSC="$ROOT/bin/ssc"

if [[ ! -x "$SSC" ]]; then
  echo "error: bin/ssc not found — run ./install.sh first" >&2
  exit 2
fi

if ! command -v node &>/dev/null; then
  echo "error: node not found — install Node.js to run WASM bundles" >&2
  exit 2
fi

if [[ $# -eq 0 ]]; then
  echo "Usage: $0 <file.ssc>" >&2
  exit 1
fi

FILE="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
STEM="$(basename "$FILE" .ssc)"

TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

echo "Compiling $1 …" >&2
cd "$TMPDIR"
"$SSC" emit-wasm "$FILE"

echo "Running $STEM.js …" >&2
node "$STEM.js"
