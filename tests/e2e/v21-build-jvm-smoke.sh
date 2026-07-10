#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
FIXTURES="$ROOT/tests/fixtures/v21-native"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/v21-build-jvm.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
mkdir -p "$sandbox/toolbin"
ln -s "$(command -v java)" "$sandbox/toolbin/java"
ln -s "$(command -v dirname)" "$sandbox/toolbin/dirname"
clean_path="$sandbox/toolbin:/bin"

[[ -x "$ROOT/bin/ssc" ]] || {
  echo 'v21-build-jvm-smoke: run scripts/sbtc "installBin" first' >&2
  exit 2
}
if PATH="$clean_path" command -v scala-cli >/dev/null 2>&1 ||
   PATH="$clean_path" command -v scalac >/dev/null 2>&1 ||
   PATH="$clean_path" command -v javac >/dev/null 2>&1; then
  echo 'v21-build-jvm-smoke: sanitized PATH unexpectedly contains a compiler' >&2
  exit 1
fi

build() {
  PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
    "$FIXTURES/argv.ssc" "$FIXTURES/std-crypto.ssc" -o "$1"
}

build "$sandbox/app-a.jar"
build "$sandbox/app-b.jar"
cmp -s "$sandbox/app-a.jar" "$sandbox/app-b.jar"

expected=$'one\ntwo\n2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824'
[[ $(PATH="$clean_path" java -jar "$sandbox/app-a.jar" -- one two) == "$expected" ]]

jar tf "$sandbox/app-a.jar" >"$sandbox/entries"
LC_ALL=C sort -c "$sandbox/entries"
grep -Fx 'ssc/gen/Entry.class' "$sandbox/entries" >/dev/null
grep -Fx 'META-INF/scalascript/artifact.properties' "$sandbox/entries" >/dev/null
if grep -Ei 'scala[.]meta|scala3-compiler|compiler-driver|ssc/bridge|scalascript/(ast|interpreter)' \
    "$sandbox/entries" >/dev/null; then
  echo 'v21-build-jvm-smoke: forbidden standard-tier entry in artifact' >&2
  exit 1
fi

unzip -p "$sandbox/app-a.jar" META-INF/scalascript/artifact.properties \
  >"$sandbox/artifact.properties"
grep -Fx 'format=scalascript-jvm-2.1' "$sandbox/artifact.properties" >/dev/null
grep -Fx 'source.count=2' "$sandbox/artifact.properties" >/dev/null
grep -F 'ssc.plugin.host.HostNativePlugin' "$sandbox/artifact.properties" >/dev/null
grep -F 'ssc.plugin.crypto.CryptoNativePlugin' "$sandbox/artifact.properties" >/dev/null

PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$FIXTURES/relative-main.ssc" -o "$sandbox/import.jar"
[[ $(PATH="$clean_path" java -jar "$sandbox/import.jar") == '42' ]]

PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$FIXTURES/sql-provider.ssc" -o "$sandbox/sql.jar"
[[ $(PATH="$clean_path" java -jar "$sandbox/sql.jar") == $'1\n7\nAda\ntrue' ]]
unzip -p "$sandbox/sql.jar" META-INF/scalascript/artifact.properties \
  >"$sandbox/sql-artifact.properties"
grep -Fx 'database.count=1' "$sandbox/sql-artifact.properties" >/dev/null
grep -Fx 'database.0.name=default' "$sandbox/sql-artifact.properties" >/dev/null
grep -F 'ssc.plugin.sql.SqlNativePlugin' "$sandbox/sql-artifact.properties" >/dev/null
sql_deps=$(jdeps --multi-release base --ignore-missing-deps -verbose:class "$sandbox/sql.jar")
if printf '%s\n' "$sql_deps" | grep -E 'javax[.]tools|jdk[.]compiler|java[.]compiler' >/dev/null; then
  echo 'v21-build-jvm-smoke: SQL artifact retained an optional compiler edge' >&2
  exit 1
fi

deps=$(jdeps --multi-release base --ignore-missing-deps -verbose:class "$sandbox/app-a.jar")
if printf '%s\n' "$deps" | grep -E \
    'scala[.]meta|dotty[.]tools|javax[.]tools|ssc[.]bridge|scalascript[.](ast|interpreter)' >/dev/null; then
  echo 'v21-build-jvm-smoke: forbidden standard-tier reference in artifact' >&2
  exit 1
fi

set +e
PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$FIXTURES/checker-invalid-numeric.ssc" -o "$sandbox/invalid.jar" \
  >"$sandbox/invalid.out" 2>"$sandbox/invalid.err"
invalid_rc=$?
set -e
[[ $invalid_rc -ne 0 ]]
grep -F 'TYPEERR:' "$sandbox/invalid.err" >/dev/null
[[ ! -e "$sandbox/invalid.jar" ]]

echo 'PASS v21-build-jvm-smoke'
