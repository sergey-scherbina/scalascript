#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
LAUNCHER="$ROOT/bin/ssc-provider"
PROVIDER="$ROOT/bin/lib/providers/graph-rdf4j/jars"

[[ -x $LAUNCHER && -d $PROVIDER ]] || {
  echo 'v21-explicit-graph-provider-smoke: run scripts/sbtc "installBin" first' >&2
  exit 2
}
command -v node >/dev/null || { echo 'v21-explicit-graph-provider-smoke: node is required' >&2; exit 2; }
find "$PROVIDER" -maxdepth 1 -type f -name '*.jar' -print |
  grep -F 'scalascript-v2-native-graph-rdf4j-plugin_' >/dev/null
if find "$PROVIDER" -maxdepth 1 -type f -name '*.jar' -print | grep -Ei \
    'scalameta|scala3-compiler|compiler-driver|scalascript-(core|backend-interpreter|v2-plugin-bridge)' >/dev/null; then
  echo 'v21-explicit-graph-provider-smoke: forbidden compatibility/compiler dependency' >&2
  exit 1
fi
if find "$ROOT/bin/lib/standard/jars" -maxdepth 1 -type f -name '*.jar' -print |
    grep -F 'scalascript-v2-native-graph-rdf4j-plugin_' >/dev/null; then
  echo 'v21-explicit-graph-provider-smoke: provider leaked into standard jars' >&2
  exit 1
fi

tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-explicit-graph.XXXXXX")
server_pid=
cleanup() {
  [[ -z $server_pid ]] || kill "$server_pid" 2>/dev/null || true
  rm -rf "$tmp"
}
trap cleanup EXIT HUP INT TERM
node "$ROOT/examples/rdf4j-server-tools.js" "$tmp/url" >"$tmp/server.out" 2>"$tmp/server.err" &
server_pid=$!
for _ in $(seq 1 100); do
  [[ -s $tmp/url ]] && break
  kill -0 "$server_pid" 2>/dev/null || { cat "$tmp/server.err" >&2; exit 1; }
  sleep 0.05
done
[[ -s $tmp/url ]] || { echo 'RDF4J fixture did not start' >&2; exit 1; }
url=$(cat "$tmp/url")

for mode in vm asm; do
  args=()
  [[ $mode == asm ]] && args+=(--bytecode)
  RDF4J_URL="$url" RDF4J_USER=admin RDF4J_PASS=secret \
    "$LAUNCHER" graph-rdf4j run "${args[@]}" \
      "$ROOT/examples/graph-rdf4j-http-storage.ssc" \
      >"$tmp/$mode.out" 2>"$tmp/$mode.err"
done
cmp "$tmp/vm.out" "$tmp/asm.out"
[[ $(cat "$tmp/vm.out") == $'Stored two books.\nFound 2 books:\n  Crime and Punishment — Fyodor Dostoevsky\n  Moby Dick — Herman Melville\nInserted Frankenstein via SPARQL Update.\nTotal triples with ssc:title: 3' ]]

if "$ROOT/bin/ssc" run "$ROOT/examples/graph-rdf4j-http-storage.ssc" \
    >"$tmp/plain.out" 2>"$tmp/plain.err"; then
  echo 'v21-explicit-graph-provider-smoke: plain ssc unexpectedly loaded RDF4J' >&2
  exit 1
fi
grep -F 'unhandled runtime effect: Sparql.select' "$tmp/plain.err" >/dev/null

echo 'PASS v21-explicit-graph-provider-smoke (1 exact row, VM/ASM)'
