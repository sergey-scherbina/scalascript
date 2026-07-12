#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
LAUNCHER="$ROOT/bin/ssc-provider"
PROVIDER="$ROOT/bin/lib/providers/pdf/jars"

[[ -x $LAUNCHER && -d $PROVIDER ]] || {
  echo 'v21-explicit-pdf-provider-smoke: run scripts/sbtc "installBin" first' >&2
  exit 2
}

find "$PROVIDER" -maxdepth 1 -type f -name '*.jar' -print | grep -F 'scalascript-v2-native-pdf-plugin_' >/dev/null
if find "$PROVIDER" -maxdepth 1 -type f -name '*.jar' -print | grep -Ei \
    'scalameta|scala3-compiler|compiler-driver|scalascript-(core|backend-interpreter|v2-plugin-bridge)' >/dev/null; then
  echo 'v21-explicit-pdf-provider-smoke: forbidden compatibility/compiler dependency' >&2
  exit 1
fi

tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-explicit-pdf.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

run_case() {
  local file=$1
  "$LAUNCHER" pdf run "$ROOT/examples/$file" >"$tmp/$file.vm" 2>"$tmp/$file.vm.err"
  "$LAUNCHER" pdf run --bytecode "$ROOT/examples/$file" >"$tmp/$file.asm" 2>"$tmp/$file.asm.err"
  cmp "$tmp/$file.vm" "$tmp/$file.asm"
}

run_case invoice-email.ssc
run_case invoice-pdf.ssc
run_case pdf-extract-demo.ssc

[[ $(cat "$tmp/invoice-email.ssc.vm") == 'MIME message assembled: PDF attached' ]]
grep -Ex 'PDF generated: [1-9][0-9]* base64 chars' "$tmp/invoice-pdf.ssc.vm" >/dev/null
[[ $(cat "$tmp/pdf-extract-demo.ssc.vm") == $'pages: 1\n--- extracted text ---\nPIT-11\nPodatnik: Jan Kowalski\nPrzychod: 84210.00 zl' ]]

if "$ROOT/bin/ssc" run "$ROOT/examples/invoice-pdf.ssc" >"$tmp/plain.out" 2>"$tmp/plain.err"; then
  echo 'v21-explicit-pdf-provider-smoke: plain ssc unexpectedly loaded PDF' >&2
  exit 1
fi
grep -F 'unbound global: htmlToPdfBase64' "$tmp/plain.err" >/dev/null

echo 'PASS v21-explicit-pdf-provider-smoke (3 exact rows, VM/ASM)'
