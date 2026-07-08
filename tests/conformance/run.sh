#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# `--server=false`: compile run.sc in-process. Without it `scala-cli run.sc` starts a bloop
# daemon (PPID 1, adopted by launchd) that lingers at multi-GB and never exits — the run.sc
# shebang's `--server=false` applies only to a DIRECT `./run.sc`, not to `scala-cli run.sc`,
# and the `~/.zshenv` wrapper only covers interactive zsh, not non-interactive shells / other
# agents. Always invoke conformance through this wrapper. (bloop-serverless-scripts)
exec scala-cli --server=false "$DIR/run.sc" -- "$@"
