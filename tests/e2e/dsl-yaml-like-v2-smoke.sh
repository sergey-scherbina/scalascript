#!/usr/bin/env bash
# v2-only regression for std/parsing/layout + examples/dsl-yaml-like.ssc.
#
# v1 is not a clean reference for this example: it currently fails to import
# withIndent from std/parsing/layout.ssc. This smoke pins the production v2 path
# that used to parse the document, then crash on fieldAt(YStr, 1).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$ROOT/../bin/ssc"
EXAMPLE="$ROOT/../examples/dsl-yaml-like.ssc"

OUT=$("$BIN" run --v2 "$EXAMPLE")
printf '%s\n' "$OUT"

case "$OUT" in
  *"Parsed successfully."*"server.host = localhost"*"database.name = myapp"*"database.pool.max = 10"*)
    echo "dsl-yaml-like-v2-smoke PASS" ;;
  *)
    echo "dsl-yaml-like-v2-smoke FAIL: expected parsed YAML queries in --v2 output" >&2
    exit 1 ;;
esac
