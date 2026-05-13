#!/usr/bin/env bash
set -euo pipefail

# ScalaScript development environment setup

echo "Setting up ScalaScript development environment..."

# Check if scala-cli is already installed
if command -v scala-cli &> /dev/null; then
    echo "✓ scala-cli is already installed: $(scala-cli version --cli-version)"
else
    echo "Installing scala-cli..."

    if [[ "$OSTYPE" == "darwin"* ]]; then
        # macOS
        if command -v brew &> /dev/null; then
            brew install Virtuslab/scala-cli/scala-cli
        else
            curl -sSLf https://scala-cli.virtuslab.org/get | sh
        fi
    elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
        # Linux
        curl -sSLf https://scala-cli.virtuslab.org/get | sh
    else
        echo "Unsupported OS: $OSTYPE"
        echo "Please install scala-cli manually: https://scala-cli.virtuslab.org/install"
        exit 1
    fi

    echo "✓ scala-cli installed"
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
