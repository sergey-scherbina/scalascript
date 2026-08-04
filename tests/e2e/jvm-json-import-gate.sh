#!/usr/bin/env bash
#
# A program that imports `std/json.ssc` must COMPILE on the JVM lane.
#
# WHY. `ssc-tools run-jvm` emits Scala and hands it to the compiler. For anything importing
# `std/json.ssc` that Scala does not typecheck: 14 × `[E007] Type Mismatch`, every one of them a
# destructured binder that arrived as `Any` where `Int` or `List[Int]` was required —
# `(tail : Any)` ×7, `(unit : Any)` ×5, `(low : Any)` ×2, inside `jsonCoreParseStringLoop`.
#
# The blast radius is not json. `std/http.ssc` pulls json in, so the JVM lane could not compile a
# program that serves HTTP either — which is how this was found: `components-smoke` and
# `middleware-smoke` both reported `the server process EXITED before it listened`, a serving
# symptom for a defect that happens before a single line runs.
#
# WHAT THIS CHECKS, and why the control is half of it:
#   1. jsonParse on the JVM lane compiles and runs;
#   2. the SAME program runs on int, native and js — so a failure in (1) accuses the JVM lane
#      rather than the fixture or std/json itself;
#   3. a program WITHOUT the json import still compiles on the JVM lane — the absent-state control.
#      Without it, "run-jvm is simply broken today" and "run-jvm cannot digest json" produce the
#      same red, and only one of them is this entry.
#
# (v1/runtime/backend/jvm/BUGS.md jvm-lane-cannot-compile-a-json-import.)
#
# Usage: tests/e2e/jvm-json-import-gate.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SSC="${SSC_BIN:-$ROOT/bin/ssc-tools}"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/ssc-jvmjson.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

cat > "$TMP/with-json.ssc" <<'EOF'
# imports std/json

[jsonParse](std/json.ssc)

```scalascript
val v = jsonParse("{\"a\":1}")
println("parsed")
```
EOF

cat > "$TMP/no-json.ssc" <<'EOF'
# control: no json import

```scalascript
println("control ok")
```
EOF

fails=0
check() { # $1 label, $2 expected substring, rest = command
  local label="$1" want="$2"; shift 2
  local out
  out="$(SSC_NO_BUILD_CHECK=1 timeout 240 "$@" 2>&1 || true)"
  if printf '%s' "$out" | grep -q "$want"; then
    printf '  ok   %s\n' "$label"
  else
    printf '  FAIL %s (wanted %s)\n' "$label" "$want"
    printf '%s\n' "$out" | sed 's/\x1b\[[0-9;]*m//g' | grep -E "E007|Found:|Required:|error" | head -4 | sed 's/^/         | /'
    fails=$((fails + 1))
  fi
}

# The jvm+json cell is a KNOWN GAP, declared rather than left red on arrival. A gate nobody asked
# for that fails the day it lands is how a suite becomes noise people learn to ignore; a gap that is
# declared and CANNOT ROT is not. If this cell starts passing, the gate FAILS and says to delete the
# declaration — a known-red that quietly becomes a known-green is a permanent exemption for a fixed
# bug. (v1/runtime/backend/jvm/BUGS.md jvm-lane-cannot-compile-a-json-import.)
if SSC_NO_BUILD_CHECK=1 timeout 240 "$SSC" run-jvm "$TMP/with-json.ssc" 2>&1 | grep -q parsed; then
  printf '  FAIL jvm    with json now COMPILES — the gap closed.\n'
  printf '       Delete this known-red block, restore the plain check, and close\n'
  printf '       v1/runtime/backend/jvm/BUGS.md jvm-lane-cannot-compile-a-json-import.\n'
  fails=$((fails + 1))
else
  printf '  KNOWN GAP  jvm with json — jvm-lane-cannot-compile-a-json-import (declared, not counted)\n'
fi
check "jvm    control"   "control ok" "$SSC" run-jvm  "$TMP/no-json.ssc"
check "int    with json" parsed       "$SSC" run --v1 "$TMP/with-json.ssc"
check "js     with json" parsed       "$SSC" run-js   "$TMP/with-json.ssc"
check "native with json" parsed       "$ROOT/bin/ssc" "$TMP/with-json.ssc"

if [[ $fails -ne 0 ]]; then
  printf 'jvm-json-import-gate: FAIL (%d cell(s))\n' "$fails" >&2
  exit 1
fi
printf 'jvm-json-import-gate: OK (int/js/native run json; the jvm control compiles; jvm+json is a declared gap)\n'
