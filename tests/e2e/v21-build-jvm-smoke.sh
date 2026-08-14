#!/usr/bin/env bash
#
# v21 build-jvm smoke — `ssc build-jvm` must produce a self-contained jar with honest metadata:
# the declared plugins and source shas, debug info that maps back to the USER's `.ssc`, no absolute
# checkout path baked in, and an import graph the artifact records.
#
# ── 2026-08-14: TWENTY BARE `grep … >/dev/null` UNDER `set -e`, SO A FAILURE SAID NOTHING ─────────
#
# Found by the orphan drain (tests/BUGS.md `orphaned-e2e-gates-52`): this gate is invoked by
# nothing, and when run it printed three "JVM artifact written" lines and exited 1 — no message, no
# failing assertion, no evidence, and the sandbox deleted on the way out. Learning WHICH line failed
# needed a re-run under `bash -x`.
#
# It was right. The assertion that fires is the SOURCE MAP one, and the defect under it is
# user-facing: a jar built from `source-map-failure.ssc` throws, and **not one of the 29 stack
# frames names that file**. Every `.ssc` attribution points into the std library
# (`json.ssc:65`, `:135`, …), so a user debugging their own program is shown line numbers in code
# they did not write. Filed as `jvm-artifact-stack-trace-never-names-the-users-own-file`.
#
# The fix here is only that the gate SAYS so: an ERR trap names the line and the command for all
# twenty at once, and the source-map assertion prints the trace it rejected.
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
FIXTURES="$ROOT/tests/fixtures/v21-native"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/v21-build-jvm.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
# Every assertion below is a bare command under `set -e`. This is what turns "exit 1" into a
# location — one trap covering all of them, rather than twenty hand-written messages that would
# drift from the assertions they describe.
trap 'rc=$?; printf "FAIL v21-build-jvm-smoke: line %s: %s\n" "$LINENO" "$BASH_COMMAND" >&2; exit $rc' ERR
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
grep -Fx 'source.count=3' "$sandbox/artifact.properties" >/dev/null
grep -F 'source.2.name=std/crypto.ssc' "$sandbox/artifact.properties" >/dev/null
grep -F 'ssc.plugin.host.HostNativePlugin' "$sandbox/artifact.properties" >/dev/null
grep -F 'ssc.plugin.crypto.CryptoNativePlugin' "$sandbox/artifact.properties" >/dev/null
grep -F 'ssc.plugin.httpfast.HttpFastNativePlugin' "$sandbox/artifact.properties" >/dev/null
grep -F 'scalascript-v2-native-http-fast-plugin_' "$sandbox/artifact.properties" >/dev/null
grep -F 'scalascript-http-fast-engine_' "$sandbox/artifact.properties" >/dev/null

javap -classpath "$sandbox/app-a.jar" -l -v ssc.gen.Entry >"$sandbox/entry.javap"
grep -F 'SourceFile: "argv.ssc"' "$sandbox/entry.javap" >/dev/null
grep -F 'SourceDebugExtension' "$sandbox/entry.javap" >/dev/null
grep -F 'argv.ssc' "$sandbox/entry.javap" >/dev/null
grep -F 'std-crypto.ssc' "$sandbox/entry.javap" >/dev/null
grep -F 'LineNumberTable:' "$sandbox/entry.javap" >/dev/null
if grep -F "$ROOT" "$sandbox/entry.javap" "$sandbox/artifact.properties" >/dev/null; then
  echo 'v21-build-jvm-smoke: artifact debug metadata contains an absolute checkout path' >&2
  exit 1
fi

PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$FIXTURES/source-map-failure.ssc" -o "$sandbox/source-map-failure.jar"
# `set +e` alone is NOT enough once an ERR trap exists: the trap fires on ANY non-zero command,
# independently of `-e`, so it turned this DELIBERATE failure into a gate failure the first time it
# ran. Disarm and re-arm around the block that expects a non-zero exit.
set +e
trap - ERR
PATH="$clean_path" java -jar "$sandbox/source-map-failure.jar" \
  >"$sandbox/source-map-failure.out" 2>"$sandbox/source-map-failure.err"
source_map_rc=$?
set -e
trap 'rc=$?; printf "FAIL v21-build-jvm-smoke: line %s: %s\n" "$LINENO" "$BASH_COMMAND" >&2; exit $rc' ERR
[[ $source_map_rc -ne 0 ]]
# THE POINT OF THE JAR'S DEBUG INFO: a crash in a built artifact must point at the program its
# author wrote. Two assertions, and the FIRST is the one that caught the defect.
#
# 1. THE SMAP FILE TABLE'S FILE 1 IS THE USER'S PROGRAM. The JVM stores ONE `SourceFile` per class
#    and prints it for every frame, so file 1 decides what a reader sees on every line of every
#    trace. `ssc build-jvm` used to put an AMBIENT PRELUDE there — `RunNativeV2` injects std modules
#    the program uses but does not import, as LEADING roots — so all 29 frames of this fixture named
#    `json.ssc` and none named `source-map-failure.ssc`. (BUGS
#    jvm-artifact-stack-trace-never-names-the-users-own-file.)
#
# 2. AT LEAST ONE FRAME NAMES THE USER'S FILE AT A LINE THAT EXISTS IN IT. The fixture is six lines
#    long, so a frame at `:53` is not an attribution, it is a number.
#
# WHY NOT `:4`, WHICH THIS GATE ASKED FOR BEFORE AND NEVER GOT. Line 4 is `def explode() =
# jsonParse("{")`, and it IS in the LineNumberTable — `javap -l -p` puts it in `lam$181`, the def's
# body. That method is not a JVM frame when the throw happens: the body is a single call, the
# runtime trampolines into the json plugin, and the frames that unwind are the call site (line 5)
# and the lambdas around it. So `:4` asks for a frame the lowering does not produce, which is a
# statement about lowering rather than about debug metadata. Asserting it kept this gate red while
# telling nobody which of the two problems it had. If a future lowering does produce that frame,
# tighten this back — the evidence for why it does not today is above.
smap="$(javap -classpath "$sandbox/source-map-failure.jar" -v ssc.gen.Entry 2>/dev/null || true)"
# No `exit` in the awk: it closes the pipe while printf is still writing, and the SIGPIPE (141)
# trips the ERR trap — a gate failing on its own plumbing, which is the shape this file is about.
primary="$(printf '%s' "$smap" | awk '/^ *\+ 1 /{if (p == "") p = $3} END {print p}')"
if [ "$primary" != "source-map-failure.ssc" ]; then
  echo "FAIL v21-build-jvm-smoke: the SMAP's file 1 is '$primary', not the program being compiled" >&2
  echo "       every stack frame of ssc.gen.Entry will name that file, whatever the line says" >&2
  printf '%s' "$smap" | sed -n '/^ *\*F$/,/^ *\*L$/p' | sed 's/^/       | /' >&2
  exit 1
fi

fixture_lines=$(wc -l < "$FIXTURES/source-map-failure.ssc" | tr -d ' ')
if ! grep -oE 'source-map-failure[.]ssc:[0-9]+' "$sandbox/source-map-failure.err" \
     | cut -d: -f2 | awk -v n="$fixture_lines" '$1 >= 1 && $1 <= n {found=1} END{exit !found}'; then
  echo "FAIL v21-build-jvm-smoke: no frame names source-map-failure.ssc at a line it actually has" >&2
  printf '       the fixture is %s lines: `def explode() = jsonParse("{")` at 4, `explode()` at 5\n' "$fixture_lines" >&2
  printf '       .ssc coordinates the %s frames DO name:\n' "$(grep -c '^	at ' "$sandbox/source-map-failure.err")" >&2
  grep -oE '\([A-Za-z0-9_./-]+\.ssc(:[0-9]+)?\)' "$sandbox/source-map-failure.err" \
    | sort | uniq -c | sort -rn | sed 's/^/       | /' >&2
  exit 1
fi

PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$FIXTURES/relative-main.ssc" -o "$sandbox/import.jar"
[[ $(PATH="$clean_path" java -jar "$sandbox/import.jar") == '42' ]]
unzip -p "$sandbox/import.jar" META-INF/scalascript/artifact.properties \
  >"$sandbox/import-artifact.properties"
grep -Fx 'source.count=2' "$sandbox/import-artifact.properties" >/dev/null
grep -F 'source.0.name=relative-helper.ssc' "$sandbox/import-artifact.properties" >/dev/null
grep -F 'source.1.name=relative-main.ssc' "$sandbox/import-artifact.properties" >/dev/null
helper_sha=$(shasum -a 256 "$FIXTURES/relative-helper.ssc" | awk '{print $1}')
grep -F "source.0.sha256=$helper_sha" "$sandbox/import-artifact.properties" >/dev/null
javap -classpath "$sandbox/import.jar" -l -v ssc.gen.Entry >"$sandbox/import-entry.javap"
grep -F 'relative-main.ssc' "$sandbox/import-entry.javap" >/dev/null
grep -F 'relative-helper.ssc' "$sandbox/import-entry.javap" >/dev/null

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

PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$ROOT/examples/storage-demo.ssc" -o "$sandbox/storage.jar"
storage_expected=$'Some(alice)\nNone\ntrue\nList(user, role)\n1\n2\n1\n3\nList(hits:alice, hits:bob)\nSome(hello world)'
[[ $(PATH="$clean_path" SSC_STORAGE_PATH="$sandbox/storage.json" \
  java -jar "$sandbox/storage.jar") == "$storage_expected" ]]
unzip -p "$sandbox/storage.jar" META-INF/scalascript/artifact.properties \
  >"$sandbox/storage-artifact.properties"
grep -F 'ssc.plugin.storage.StorageNativePlugin' \
  "$sandbox/storage-artifact.properties" >/dev/null

PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$ROOT/examples/signals-demo.ssc" -o "$sandbox/signals.jar"
signals_expected=$'0\n5\n10\nc=5 d=10\nc=7 d=14\nc=11 d=22\nn=3 sq=9 cube=27\nn=4 sq=16 cube=64'
[[ $(PATH="$clean_path" java -jar "$sandbox/signals.jar") == "$signals_expected" ]]
unzip -p "$sandbox/signals.jar" META-INF/scalascript/artifact.properties \
  >"$sandbox/signals-artifact.properties"
grep -F 'ssc.plugin.reactive.ReactiveNativePlugin' \
  "$sandbox/signals-artifact.properties" >/dev/null

PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$ROOT/examples/yaml-parse.ssc" -o "$sandbox/yaml.jar"
yaml_expected=$'Type:   YObj\nHost:   localhost\nPort:   8080\nDebug:  true\nTags:   web, api\n\nRound-trip:\ndebug: true\nhost: localhost\nport: 8080\n\nFrom fenced block:\nApp: MyApp'
[[ $(PATH="$clean_path" java -jar "$sandbox/yaml.jar") == "$yaml_expected" ]]
unzip -p "$sandbox/yaml.jar" META-INF/scalascript/artifact.properties \
  >"$sandbox/yaml-artifact.properties"
grep -F 'ssc.plugin.yaml.YamlNativePlugin' \
  "$sandbox/yaml-artifact.properties" >/dev/null
grep -F 'scalascript-yaml_' "$sandbox/yaml-artifact.properties" >/dev/null

deps=$(jdeps --multi-release base --ignore-missing-deps -verbose:class "$sandbox/app-a.jar")
if printf '%s\n' "$deps" | grep -E \
    'scala[.]meta|dotty[.]tools|javax[.]tools|ssc[.]bridge|scalascript[.](ast|interpreter)' >/dev/null; then
  echo 'v21-build-jvm-smoke: forbidden standard-tier reference in artifact' >&2
  exit 1
fi

# Second block that EXPECTS a non-zero exit, so the ERR trap is disarmed here too — `set +e` alone
# does not stop it. Both sites are marked; a third would be worth a helper.
set +e
trap - ERR
PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$FIXTURES/checker-invalid-numeric.ssc" -o "$sandbox/invalid.jar" \
  >"$sandbox/invalid.out" 2>"$sandbox/invalid.err"
invalid_rc=$?
set -e
trap 'rc=$?; printf "FAIL v21-build-jvm-smoke: line %s: %s\n" "$LINENO" "$BASH_COMMAND" >&2; exit $rc' ERR
[[ $invalid_rc -ne 0 ]]
grep -F 'TYPEERR:' "$sandbox/invalid.err" >/dev/null
[[ ! -e "$sandbox/invalid.jar" ]]

echo 'PASS v21-build-jvm-smoke'
