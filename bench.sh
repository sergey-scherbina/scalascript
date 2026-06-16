#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# `--server=false`: compile run.sc in-process. Without it scala-cli starts a bloop daemon
# (PPID 1, adopted by launchd) that lingers at multi-GB and never exits — the `~/.zshenv`
# wrapper only covers interactive zsh, NOT this `#!/bin/bash` script. (bloop-serverless-scripts)
exec scala-cli --server=false "$ROOT/bench/run.sc" -- "$@"
