#!/usr/bin/env bash
#
# Who gets stdin — the program, or the CLI? (BACKLOG.md `ssc-tools-stdin-belongs-to-the-program`, S4)
#
# WHY THIS EXISTS. `std.os.readLine` shipped and worked on `ssc run` while returning `None` under
# `ssc-tools run`, because the tools CLI reads stdin to EOF as a sops secrets document before the
# program starts (`Main.scala:72`). Nobody noticed for as long as the feature had existed, and the
# reason is exact: until `readLine` existed, no lane could read stdin, so no lane could tell that
# stdin was gone. A behaviour with no reader is a behaviour with no test.
#
# WHAT IT PINS, including the part that is currently WRONG. Three facts, and the middle one is
# deliberately an assertion about today rather than about what we want:
#
#   1. the DEFAULT lane always hands stdin to the program;
#   2. the tools route WITHOUT `--secrets-file` still swallows it — the behaviour S3 will flip;
#   3. the tools route WITH `--secrets-file` hands it to the program.
#
# Pinning (2) is the point of the whole file. When S3 flips that default, this gate goes red, and
# whoever flips it must come here and say so — which is the difference between a deliberate change
# and a silent one. A gate that only asserted the desired end state would be red for months and
# ignored, and then would not be read on the day it mattered.
#
# Usage: tests/e2e/stdin-belongs-to-the-program.sh
# Exit:  0 ok · 1 an assertion failed · 2 the launchers are not built (SKIP is not silent).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
SSC="$ROOT/bin/ssc"
TOOLS="$ROOT/bin/ssc-tools"

for b in "$SSC" "$TOOLS"; do
  if [ ! -x "$b" ]; then
    printf 'stdin-belongs-to-the-program: %s not built — run `bash install.sh --dev` first.\n' "$b" >&2
    printf '  Refusing to pass without running: a stdin test that never launched anything is the\n' >&2
    printf '  exact shape of green-because-it-could-not-see.\n' >&2
    exit 2
  fi
done

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

cat > "$TMP/echo-line.ssc" <<'SSC'
[readLine](std/os.ssc)

```scalascript
def main() =
  readLine() match
    case Some(line) => println("PROGRAM-GOT:" + line)
    case None       => println("PROGRAM-GOT-NOTHING")
```
SSC

cat > "$TMP/secrets.yaml" <<'YAML'
db:
  password: "s3cr3t"
YAML

fail=0
check() {  # check <name> <expected> <actual>
  if [ "$2" = "$3" ]; then printf 'PASS  %s\n' "$1"
  else printf 'FAIL  %s\n        expected=%s\n        got=%s\n' "$1" "$2" "$3" >&2; fail=1; fi
}

# `SSC_NO_BUILD_CHECK=1` only silences the launcher's staleness warning; it changes no behaviour
# under test. Stderr is dropped because the JVM prints `NOTE: Picked up JDK_JAVA_OPTIONS` and the
# assertion is about the PROGRAM's stdout.
run_line() {  # run_line <binary> <args…>  — feeds one line, returns the program's stdout
  printf 'the-line\n' | SSC_NO_BUILD_CHECK=1 timeout 300 env SSC_NO_BUILD_CHECK=1 "$@" 2>/dev/null |
    grep -E '^PROGRAM-GOT' | head -1
}

check "the default lane hands stdin to the program" \
      "PROGRAM-GOT:the-line" \
      "$(run_line "$SSC" run "$TMP/echo-line.ssc")"

# TODAY'S BEHAVIOUR, asserted so that changing it cannot be accidental. When S3 lands, this line
# becomes PROGRAM-GOT:the-line and this expectation must be edited in the same commit.
check "tools route WITHOUT --secrets-file still swallows stdin (pre-S3 behaviour, pinned on purpose)" \
      "PROGRAM-GOT-NOTHING" \
      "$(run_line "$TOOLS" run --v1 "$TMP/echo-line.ssc")"

check "tools route WITH --secrets-file hands stdin to the program" \
      "PROGRAM-GOT:the-line" \
      "$(run_line "$TOOLS" --secrets-file "$TMP/secrets.yaml" run --v1 "$TMP/echo-line.ssc")"

# The named channel must also be honoured when it is a process substitution, since that is the shape
# the documentation recommends for sops and it is a different code path from a plain file.
check "--secrets-file accepts a process substitution" \
      "PROGRAM-GOT:the-line" \
      "$(run_line "$TOOLS" --secrets-file <(cat "$TMP/secrets.yaml") run --v1 "$TMP/echo-line.ssc")"

if [ "$fail" -eq 0 ]; then echo "stdin-belongs-to-the-program: OK"; else echo "stdin-belongs-to-the-program: FAILED" >&2; fi
exit "$fail"
