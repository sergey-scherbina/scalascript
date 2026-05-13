#!/usr/bin/env bash
# Install ssc as a self-contained binary to /usr/local/bin/ssc
# Requires scala-cli with --power mode.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPILER="$(dirname "$SCRIPT_DIR")/compiler"
DEST="${1:-/usr/local/bin/ssc}"

echo "Building standalone ssc binary..."
scala-cli --power package "$COMPILER" --standalone --output "$DEST" -f
chmod +x "$DEST"
echo "Installed: $DEST"
echo "Test: ssc examples/typeclass.ssc"
