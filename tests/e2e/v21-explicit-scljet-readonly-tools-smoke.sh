#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
exec "$ROOT/tests/e2e/scljet-readonly-jvm-vfs-smoke.sh"
