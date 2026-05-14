#!/usr/bin/env bash
# Build ssc as a self-contained binary
# Requires scala-cli with --power mode.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$SCRIPT_DIR")"
COMPILER="$ROOT/compiler"
DEST="${1:-$ROOT/bin/ssc}"

mkdir -p "$(dirname "$DEST")"

echo "Building standalone ssc binary..."
scala-cli --power package "$COMPILER" --standalone --output "$DEST" -f
chmod +x "$DEST"
echo "Installed: $DEST"
echo "Test: $DEST examples/hello.ssc"
