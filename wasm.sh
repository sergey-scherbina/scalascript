#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec scala-cli "$ROOT/examples/run-wasm.sc" -- "$@"
