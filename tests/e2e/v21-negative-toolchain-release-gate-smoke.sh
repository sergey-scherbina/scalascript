#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
checker="$ROOT/scripts/v21-negative-toolchain-freeze"
tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-negative-freeze.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

# THE CANONICAL REPORT MUST PASS, and for months it did not: `frontend.total` sat at 207 against a
# floor of 210 and `parity.delegated` at 15 against a manifest that is 12. Under `set -e` the very
# first invocation below exited 1, so NONE of the drift rejections underneath it ever ran — and
# nothing invoked this file, so nothing said so. Numbers here now match the real corpus (214) and
# the real manifest (12 = provider 5 + target 7), and the accounting closes: 63 + 139 + 12 = 214.
canonical="$tmp/canonical.tsv"
{
  printf 'metric\tvalue\n'
  printf 'runtime.modules\tjava.base,java.sql,jdk.unsupported\n'
  printf 'default.launcher\tstandard\n'
  printf 'tools.present\tfalse\ncompiler.jars\t0\nscalameta.jars\t0\n'
  printf 'scala-cli.available\tfalse\nscalac.available\tfalse\njavac.available\tfalse\n'
  printf 'java.compiler.available\tfalse\njdk.compiler.available\tfalse\n'
  printf 'forbidden.references\t0\nfrontend.total\t214\nfrontend.ok\t213\nfrontend.non-code\t1\nchecker.ok\t213\n'
  printf 'parity.identical\t63\nparity.both-fail\t0\nparity.skipped\t139\nparity.delegated\t12\nparity.provider-lane\t5\nparity.target-lane\t7\nparity.mismatch\t0\nparity.one-sided\t0\n'
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
# Was `frontend.ok 206 -> 205`, which this freeze does NOT promise to reject — the floor is 200 and
# drift below it is the whole point of the file's HARD/FLOOR split. So the assertion asserted
# nothing; it simply never ran. Now it crosses the floor, which is a guarantee.
reject_change frontend 'frontend.ok.213' 'frontend.ok.199'
reject_change parity 'parity.one-sided.0' 'parity.one-sided.1'
reject_change blocker 'runtime.blockers.0' 'runtime.blockers.1'

# `parity.delegated` is derived from the explicit-lane manifest (scripts/v21-negative-toolchain-freeze),
# so BOTH directions must be refused — and the second is the one that matters, because the floor this
# replaced would have waved it through. Delegated is load-bearing in two other rules
# (provider+target == delegated, and the accounting closure), so a bare edit would be rejected for
# the wrong reason; these compensate both so the manifest equality is the ONLY thing left to fail.
too_few=$tmp/delegated-low.tsv
sed -e 's/parity.skipped\t139/parity.skipped\t140/' \
    -e 's/parity.delegated\t12/parity.delegated\t11/' \
    -e 's/parity.provider-lane\t5/parity.provider-lane\t4/' "$canonical" >"$too_few"
if "$checker" "$too_few" >/dev/null 2>&1; then
  echo 'v21-negative-toolchain-release-gate-smoke: accepted delegated BELOW the manifest' >&2
  exit 1
fi

too_many=$tmp/delegated-high.tsv
sed -e 's/parity.skipped\t139/parity.skipped\t138/' \
    -e 's/parity.delegated\t12/parity.delegated\t13/' \
    -e 's/parity.provider-lane\t5/parity.provider-lane\t6/' "$canonical" >"$too_many"
if "$checker" "$too_many" >/dev/null 2>&1; then
  echo 'v21-negative-toolchain-release-gate-smoke: accepted delegated ABOVE the manifest (a floor would have)' >&2
  exit 1
fi

cp "$canonical" "$tmp/duplicate.tsv"
printf 'runtime.blockers\t0\n' >>"$tmp/duplicate.tsv"
if "$checker" "$tmp/duplicate.tsv" >/dev/null 2>&1; then
  echo 'v21-negative-toolchain-release-gate-smoke: accepted malformed duplicate report' >&2
  exit 1
fi

echo 'PASS v21-negative-toolchain-release-gate-smoke'
