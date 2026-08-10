#!/usr/bin/env bash
# `def main()` is auto-invoked ONLY when the program does not call it itself — on every lane.
#
# THE RULE (owner's decision, 2026-08-10): one run when nothing calls `main`, one run when the
# program calls it once, and N runs for N explicit calls. `ssc run` invokes the entry point, so an
# explicit top-level `main()` used to be a SECOND call: `bin/ssc` and `--bytecode` printed the body
# twice while `--v1` printed it once.
#
# It is not a curiosity. Every `repro/` file a user has sent ends with `main()`, so every lane
# comparison meant discounting a doubled block by eye — which is how a real divergence turns into
# background noise. It was watched three times over two days before being written down.
#
# THREE CASES, and the third is the one that keeps this honest: a fix that simply suppressed every
# explicit call would pass the first two and silently break a program that means to run main twice.
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
SSC="$ROOT/bin/ssc"
TOOLS="$ROOT/bin/ssc-tools"
for l in "$SSC" "$TOOLS"; do
  [[ -x $l ]] || { echo "entry-auto-invoke-once: no launcher at $l — run ./install.sh --dev" >&2; exit 2; }
done

tmp=$(mktemp -d "${TMPDIR:-/tmp}/entry-once.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
failed=0

printf 'def main(): Unit =\n  println("run")\n'                     > "$tmp/none.ssc"
printf 'def main(): Unit =\n  println("run")\nmain()\n'             > "$tmp/one.ssc"
printf 'def main(): Unit =\n  println("run")\nmain()\nmain()\n'     > "$tmp/two.ssc"

check() { # check <file> <expected-count>
  local file=$1 want=$2 name; name=$(basename "$file")
  local out n
  for lane in "ssc" "v1" "bytecode"; do
    case $lane in
      ssc)      out=$("$SSC" run "$file" 2>/dev/null) ;;
      v1)       out=$("$TOOLS" run --v1 "$file" 2>/dev/null) ;;
      bytecode) out=$("$TOOLS" run --bytecode "$file" 2>/dev/null) ;;
    esac
    n=$(printf '%s\n' "$out" | grep -c '^run$' || true)
    if [[ $n -ne $want ]]; then
      echo "entry-auto-invoke-once: FAILED [$lane] $name ran $n time(s), expected $want" >&2
      failed=1
    fi
  done
}

check "$tmp/none.ssc" 1   # nothing calls main -> the entry is auto-invoked, once
check "$tmp/one.ssc"  1   # the program calls it -> no auto-invoke on top
check "$tmp/two.ssc"  2   # two explicit calls mean two runs; suppressing them would be wrong

[[ $failed -eq 0 ]] || { echo "entry-auto-invoke-once: FAILED" >&2; exit 1; }
echo "entry-auto-invoke-once: OK (0/1/2 explicit calls -> 1/1/2 runs, on all three lanes)"
