#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
valid="$ROOT/tests/conformance/v2-self-hosted-markdown-core.ssc"
invalid="$ROOT/tests/fixtures/v21-native/markdown-unterminated-fence.ssc"
tmp=$(mktemp -d "${TMPDIR:-/tmp}/ssc-v21-markdown-front.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class SSC_NO_CDS=1 \
  "$ROOT/bin/ssc" run --native "$valid" >"$tmp/valid.log" 2>&1
grep -F 'H1@1(Pricing|#pricing route=/pricing)' "$tmp/valid.log" >/dev/null
if grep -Ei 'org[./]commonmark|com[./]vladsch[./]flexmark' "$tmp/valid.log" >/dev/null; then
  echo 'standard native Markdown compilation loaded CommonMark/Flexmark' >&2
  exit 1
fi

if PATH=/usr/bin:/bin SSC_NO_CDS=1 "$ROOT/bin/ssc" run --native "$invalid" \
    >"$tmp/invalid.out" 2>"$tmp/invalid.err"; then
  echo 'unterminated Markdown fence unexpectedly compiled' >&2
  exit 1
fi
grep -F 'markdown-unterminated-fence.ssc:9:1: unterminated fenced block' \
  "$tmp/invalid.err" >/dev/null

while IFS= read -r jar_file; do
  if jar tf "$jar_file" | grep -Eq '^(org/commonmark|com/vladsch/flexmark)/'; then
    echo "standard distribution contains host Markdown classes: ${jar_file##*/}" >&2
    exit 1
  fi
done < <(find "$ROOT/bin/lib/standard" -type f -name '*.jar' -print)

echo 'PASS v21-self-hosted-markdown-frontend-smoke'
