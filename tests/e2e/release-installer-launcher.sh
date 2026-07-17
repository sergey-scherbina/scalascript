#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/ssc-release-installer.XXXXXX")"
FAKE_BIN="$TMP/bin"
PREFIX="$TMP/prefix"

cleanup() {
  rm -rf "$TMP"
}
trap cleanup EXIT

mkdir -p "$FAKE_BIN"
cat > "$FAKE_BIN/curl" <<'FAKE_CURL'
#!/usr/bin/env sh
set -eu
out=""
while [ "$#" -gt 0 ]; do
  if [ "$1" = "-o" ]; then
    shift
    out="$1"
  fi
  shift
done
[ -n "$out" ]
: > "$out"
FAKE_CURL

cat > "$FAKE_BIN/java" <<'FAKE_JAVA'
#!/usr/bin/env sh
set -eu
: "${JAVA_ARGS_OUT:?}"
printf '%s\n' "$@" > "$JAVA_ARGS_OUT"
FAKE_JAVA
chmod +x "$FAKE_BIN/curl" "$FAKE_BIN/java"

expect_eq() {
  local name="$1"
  local expected="$2"
  local actual="$3"
  if [[ "$actual" == "$expected" ]]; then
    return
  fi
  printf 'release-installer-launcher[%s]: expected=%q actual=%q\n' \
    "$name" "$expected" "$actual" >&2
  diff -u <(printf '%s\n' "$expected") <(printf '%s\n' "$actual") >&2 || true
  exit 1
}

PATH="$FAKE_BIN:/usr/bin:/bin" PREFIX="$PREFIX" sh "$ROOT/releases/install.sh" >/dev/null
launcher="$PREFIX/bin/ssc"
if [[ ! -x "$launcher" ]]; then
  printf 'release-installer-launcher: expected executable launcher at %s\n' "$launcher" >&2
  exit 1
fi

expected_line='exec java -Xss"${SSC_XSS:-64m}" -jar "'"$PREFIX"'/lib/scalascript/ssc.jar" "$@"'
actual_line="$(grep '^exec java ' "$launcher" || true)"
expect_eq "generated source" "$expected_line" "$actual_line"

default_args="$TMP/default.args"
JAVA_ARGS_OUT="$default_args" PATH="$FAKE_BIN:/usr/bin:/bin" "$launcher" one two
expect_eq "default argv" \
  $'-Xss64m\n-jar\n'"$PREFIX"$'/lib/scalascript/ssc.jar\none\ntwo' \
  "$(cat "$default_args")"

override_args="$TMP/override.args"
JAVA_ARGS_OUT="$override_args" SSC_XSS=256k PATH="$FAKE_BIN:/usr/bin:/bin" "$launcher" arg
expect_eq "override argv" \
  $'-Xss256k\n-jar\n'"$PREFIX"$'/lib/scalascript/ssc.jar\narg' \
  "$(cat "$override_args")"

printf 'release-installer-launcher: PASS\n'
