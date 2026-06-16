#!/usr/bin/env bash
set -euo pipefail

# ScalaScript development environment setup

echo "Setting up ScalaScript development environment..."

if command -v scala-cli &> /dev/null; then
    echo "Updating scala-cli..."
    if [[ "$OSTYPE" == "darwin"* ]] && command -v brew &> /dev/null; then
        brew upgrade Virtuslab/scala-cli/scala-cli || brew install Virtuslab/scala-cli/scala-cli
    else
        curl -sSLf https://scala-cli.virtuslab.org/get | sh
    fi
else
    echo "Installing scala-cli..."
    if [[ "$OSTYPE" == "darwin"* ]]; then
        if command -v brew &> /dev/null; then
            brew install Virtuslab/scala-cli/scala-cli
        else
            curl -sSLf https://scala-cli.virtuslab.org/get | sh
        fi
    elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
        curl -sSLf https://scala-cli.virtuslab.org/get | sh
    else
        echo "Unsupported OS: $OSTYPE"
        echo "Please install scala-cli manually: https://scala-cli.virtuslab.org/install"
        exit 1
    fi
fi

# Verify installation
echo ""
echo "Verifying installation..."
scala-cli version

# ── Git submodules ─────────────────────────────────────────────────────────────
echo ""
echo "Initialising git submodules..."
git -C "$(dirname "${BASH_SOURCE[0]}")" submodule update --init --recursive
echo "✓ submodules ready"

# ── Agent skills ──────────────────────────────────────────────────────────────
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKILLS_SRC="$ROOT/.agents/plugins"
if [ -d "$SKILLS_SRC" ]; then
  echo ""
  echo "Installing agent skills..."
  DEST="$HOME/.claude/commands"
  mkdir -p "$DEST"
  for skill_dir in "$SKILLS_SRC"/*/; do
    name="$(basename "$skill_dir")"
    src="$skill_dir/commands/$name.md"
    if [ -f "$src" ]; then
      cp "$src" "$DEST/$name.md"
      echo "  ✓ $name → $DEST/$name.md"
    fi
  done
else
  echo ""
  echo "⚠ .agents/plugins not found — run again after pushing agent-plugins to GitHub"
  echo "  Then: git submodule update --init"
fi

echo ""
echo "✓ Setup complete!"
echo ""
echo "You can now run:"
echo "  scala-cli --server=false run scripts/validate-frontmatter.scala"
