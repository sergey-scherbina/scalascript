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

echo ""
echo "✓ Setup complete!"
echo ""
echo "You can now run:"
echo "  scala-cli run scripts/validate-frontmatter.scala"
