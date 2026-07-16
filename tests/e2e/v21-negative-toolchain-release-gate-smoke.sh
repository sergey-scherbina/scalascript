#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
checker="$ROOT/scripts/v21-negative-toolchain-freeze"
tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-negative-freeze.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

canonical="$tmp/canonical.tsv"
{
  printf 'metric\tvalue\n'
  printf 'runtime.modules\tjava.base,java.sql,jdk.unsupported\n'
  printf 'default.launcher\tstandard\n'
  printf 'tools.present\tfalse\ncompiler.jars\t0\nscalameta.jars\t0\n'
  printf 'scala-cli.available\tfalse\nscalac.available\tfalse\njavac.available\tfalse\n'
  printf 'java.compiler.available\tfalse\njdk.compiler.available\tfalse\n'
  printf 'forbidden.references\t0\nfrontend.total\t207\nfrontend.ok\t206\nfrontend.non-code\t1\nchecker.ok\t206\n'
  printf 'parity.identical\t63\nparity.both-fail\t0\nparity.skipped\t129\nparity.delegated\t15\nparity.provider-lane\t8\nparity.target-lane\t7\nparity.mismatch\t0\nparity.one-sided\t0\n'
  printf 'runtime.blockers\t0\nprovider.smoke\tpass\nserver.smoke\tpass\nrelease.ready\ttrue\n'
} >"$canonical"

"$checker" "$canonical" >/dev/null

reject_change() {
  local name=$1 pattern=$2 replacement=$3
  sed "s/$pattern/$replacement/" "$canonical" >"$tmp/$name.tsv"
  if "$checker" "$tmp/$name.tsv" >/dev/null 2>&1; then
    echo "v21-negative-toolchain-release-gate-smoke: accepted $name drift" >&2
    exit 1
  fi
}

reject_change launcher 'default.launcher.standard' 'default.launcher.tools'
reject_change jar 'scalameta.jars.0' 'scalameta.jars.1'
reject_change tool 'scala-cli.available.false' 'scala-cli.available.true'
reject_change module 'java.base,java.sql' 'java.base,java.compiler,java.sql'
reject_change frontend 'frontend.ok.206' 'frontend.ok.205'
reject_change parity 'parity.one-sided.0' 'parity.one-sided.1'
reject_change blocker 'runtime.blockers.0' 'runtime.blockers.1'

cp "$canonical" "$tmp/duplicate.tsv"
printf 'runtime.blockers\t0\n' >>"$tmp/duplicate.tsv"
if "$checker" "$tmp/duplicate.tsv" >/dev/null 2>&1; then
  echo 'v21-negative-toolchain-release-gate-smoke: accepted malformed duplicate report' >&2
  exit 1
fi

echo 'PASS v21-negative-toolchain-release-gate-smoke'
