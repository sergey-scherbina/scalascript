#!/usr/bin/env bash
# Portable-CodeMode capsule — the fresh-process cross-host-resume FOUNDATION (vector 15).
#
# Freezes a capsule whose resume PROGRAM travels as closed CoreIR bytes, then admits and
# runs it in a SEPARATE JVM process that holds NO machine — proving control-interoperability
# §14.3 items 10-11 (a run does not need the original process or artifact). This is the
# VM-side Portable counterpart of the host SDK's ExactArtifact cross-host test
# (v2/host/scala/control/.../CrossHostResumeTest.scala), where the machine stays in memory.
#
# It does NOT flip conformance vector 15-cross-host-resume: the §10.2 pass that GENERATES a
# closed resume program from an arbitrary .ssc saveable region, and a second admitting
# backend for the full §14.4 cross-backend N→M matrix, remain separate work. The resume
# program here is hand-authored: (frame, input) => frame*10 + input.
set -euo pipefail
cd "$(dirname "$0")"          # v2/conformance
SRC=../src

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
JAR="$TMP/ssc.jar"

# Build the assembly jar, cached by a hash of src/ (mirrors check.sh). ~2-3 min cold.
CACHE_DIR="${SSC_CONF_CACHE:-$HOME/.cache/ssc-conf}"
mkdir -p "$CACHE_DIR"
HASH="$(find "$SRC" -type f \( -name '*.scala' -o -name '*.sc' \) -exec shasum {} + 2>/dev/null | shasum | awk '{print $1}')"
CACHED="$CACHE_DIR/ssc-$HASH.jar"
if [ -z "${SSC_CONF_NOCACHE:-}" ] && [ -n "$HASH" ] && [ -s "$CACHED" ]; then
  cp "$CACHED" "$JAR"
else
  echo "building ssc ..." >&2
  scala-cli --power package "$SRC" -o "$JAR" -f --assembly --server=false -q >/dev/null 2>&1
  [ -n "$HASH" ] && cp "$JAR" "$CACHED" 2>/dev/null || true
fi
ssc() { java -jar "$JAR" "$@"; }

fail=0
check() { # name got want
  if [ "$2" = "$3" ]; then printf 'ok   %-30s => %s\n' "$1" "$2"
  else printf 'FAIL %-30s got [%s] want [%s]\n' "$1" "$2" "$3"; fail=1; fi
}

CAP="$TMP/demo.portable"

# process 1: FREEZE the capsule (frame captured = 4).
ssc freeze-capsule "$CAP" 4 >/dev/null

# the resume program travels as closed CoreIR in the bytes (no machine in the capsule).
if grep -q '(resume (program' "$CAP"; then printf 'ok   %-30s => yes\n' "resume program travels in bytes"
else printf 'FAIL %-30s\n' "resume program travels in bytes"; fail=1; fi

# processes 2 & 3 (SEPARATE JVMs, holding no machine): admit + run. frame*10 + input.
check "fresh-process run input=2" "$(ssc run-capsule "$CAP" 2)" "42"
check "fresh-process run input=5" "$(ssc run-capsule "$CAP" 5)" "45"  # multi-shot, independent

# integrity: tampering the resume program is rejected at admission, before any run.
sed 's/i.mul/i.sub/' "$CAP" > "$CAP.tampered"
if ssc run-capsule "$CAP.tampered" 2 >/dev/null 2>&1; then
  printf 'FAIL %-30s tampered resume admitted\n' "tamper rejected"; fail=1
else
  printf 'ok   %-30s => rejected\n' "tamper rejected"
fi

if [ "$fail" -eq 0 ]; then echo "portable-capsule: PASS"; else echo "portable-capsule: FAIL"; exit 1; fi
