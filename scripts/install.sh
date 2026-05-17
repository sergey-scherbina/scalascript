#!/usr/bin/env bash
# Build ssc as a self-contained binary and stage the repo-local launchers.
# Requires scala-cli with --power mode.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$SCRIPT_DIR")"
COMPILER="$ROOT/compiler"
DEST="${1:-$HOME/.local/bin/ssc}"
BIN_DIR="$(cd "$(dirname "$DEST")" 2>/dev/null && pwd || echo "$(dirname "$DEST")")"

mkdir -p "$BIN_DIR"

echo "Building standalone ssc binary..."
scala-cli --power package "$COMPILER" --standalone --output "$DEST" -f --main-class scalascript.cli.ssc
chmod +x "$DEST"
echo "Installed: $DEST"
echo "Test: $DEST examples/hello.ssc"
echo ""

# Stage the repo-local bin/ with launcher symlinks. The launchers shell out
# to scala-cli using paths relative to the repo, so they only work when
# invoked from inside this checkout.
mkdir -p "$ROOT/bin"
for launcher in "$ROOT"/scripts/launchers/*; do
    name="$(basename "$launcher")"
    ln -sf "../scripts/launchers/$name" "$ROOT/bin/$name"
done
# Also expose the freshly built ssc inside the repo's bin/ so the wrappers
# and conformance runner can pick it up. Skip when DEST already *is* the
# repo's bin/ssc — that file is the build output itself.
DEST_ABS="$(cd "$(dirname "$DEST")" && pwd)/$(basename "$DEST")"
if [ "$DEST_ABS" != "$ROOT/bin/ssc" ]; then
    ln -sf "$DEST_ABS" "$ROOT/bin/ssc"
fi
echo "Wired repo-local bin/ launchers (symlinks to scripts/launchers/*)."
echo ""

# Determine shell config file
if [[ "$SHELL" == */zsh ]]; then
    RC_FILE="$HOME/.zshrc"
elif [[ "$SHELL" == */bash ]]; then
    if [[ "$(uname)" == "Darwin" ]]; then
        RC_FILE="$HOME/.bash_profile"
    else
        RC_FILE="$HOME/.bashrc"
    fi
else
    RC_FILE="$HOME/.profile"
fi

EXPORT_CMD="export PATH=\"$BIN_DIR:\$PATH\""

if [[ ":$PATH:" == *":$BIN_DIR:"* ]]; then
    echo "$BIN_DIR is already in PATH."
    exit 0
fi

# If sourced — export directly into the current session
if [[ "${BASH_SOURCE[0]}" != "${0}" ]]; then
    export PATH="$BIN_DIR:$PATH"
    echo "Added $BIN_DIR to PATH for this session."
else
    echo "To add ssc to the current session, run:"
    echo "  $EXPORT_CMD"
    echo "(or source the script: source scripts/install.sh)"
fi

# Offer to persist in shell config
echo ""
if [[ -t 0 ]]; then
    read -rp "Add to $RC_FILE for future sessions? [y/N] " answer
    if [[ "${answer:-}" =~ ^[Yy]$ ]]; then
        # Don't add duplicate lines
        if grep -qF "$BIN_DIR" "$RC_FILE" 2>/dev/null; then
            echo "$RC_FILE already contains $BIN_DIR, skipping."
        else
            printf '\n# scalascript\n%s\n' "$EXPORT_CMD" >> "$RC_FILE"
            echo "Added to $RC_FILE."
        fi
    fi
fi

echo ""
echo "━━━ Run this now to use ssc in this terminal: ━━━"
echo "  $EXPORT_CMD"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
