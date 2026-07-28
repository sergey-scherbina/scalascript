#!/usr/bin/env bash
#
# ssc1-front-annotation.sh — an annotation on its OWN LINE must not stop the legacy front.
#
#   ./tests/e2e/ssc1-front-annotation.sh              # check the current tree
#   ./tests/e2e/ssc1-front-annotation.sh --self-test  # prove the check can fail, then check
#
# WHY THIS IS AN e2e AND NOT A CONFORMANCE CASE — this is the load-bearing part.
#
# The gap is in `v2/lib/ssc1-front.ssc0` (the legacy/oracle front). The conformance suite's V2
# lane runs `ssc run --v2`, which uses **F** — and F never had this gap. A conformance case
# therefore passes identically with and without the fix: measured, `annotated-declaration.ssc`
# was green on V2 both ways. A gate that cannot distinguish the two states is not a gate.
#
# So this asserts on the legacy front DIRECTLY: lower each program with `v2/bin/ssc1-run.ssc0`
# and require that the emitted CoreIR contains no `_err` sentinel.
#
# WHAT BROKE: the `@` branch of `parseOneStmt` skipped `@Name(args)` correctly but not the
# statement separator the newline produces, so it then looked at a `;` instead of the annotated
# declaration and emitted `_err`. Three corpus cases (graph-storage, graph-codecs,
# typed-object-codec) were this one token. The SAME-LINE spelling always worked, which is why
# the gap survived: it only shows up in the spelling everybody actually writes.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

JAR="${SSC_JAR:-}"
if [[ -z "$JAR" ]]; then
  JAR="$(mktemp -t ssc-ann-XXXXXX).jar"
  scala-cli --power package v2/src --assembly -o "$JAR" --force >/dev/null 2>&1 \
    || { echo "FAIL: could not build the kernel jar (set SSC_JAR to skip this step)"; exit 1; }
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/ssc-ann.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

pass=0; fail=0

# probe <name> <expect-clean|expect-err> <source>
probe() {
  local name="$1" expect="$2" src="$3"
  printf '%s\n' "$src" > "$WORK/$name.ssc"
  local ir errs
  ir="$(java -Dssc.stackSize=1073741824 -jar "$JAR" run v2/bin/ssc1-run.ssc0 "$WORK/$name.ssc" 2>&1)"
  errs="$(printf '%s' "$ir" | grep -c '_err')"
  if [[ "$expect" == "expect-clean" && "$errs" -eq 0 ]]; then
    echo "ok   [$name] legacy front lowered it with no _err"
    pass=$((pass + 1))
  elif [[ "$expect" == "expect-err" && "$errs" -gt 0 ]]; then
    echo "ok   [$name] _err as expected (self-test)"
    pass=$((pass + 1))
  else
    echo "FAIL [$name] expected $expect, got $errs occurrence(s) of _err"
    echo "  source:"; printf '%s\n' "$src" | sed 's/^/    /'
    fail=$((fail + 1))
  fi
}

if [[ "${1:-}" == "--self-test" ]]; then
  # The check must be able to FAIL. A genuinely malformed program must produce `_err`;
  # if this passes, the probe is not looking at anything.
  echo "--- self-test: a malformed program must produce _err ---"
  probe selftest expect-err 'def broken(: = ='
  if [[ $fail -ne 0 ]]; then
    echo "SELF-TEST FAILED: a malformed program did not produce _err — this gate proves nothing"
    exit 1
  fi
  echo "--- self-test ok; running the real checks ---"
  pass=0; fail=0
fi

probe case-class-own-line expect-clean '@graphLabel("Node")
case class Node(id: String)
println(Node("n").id)'

probe def-own-line expect-clean '@inline
def doubled(n: Int): Int = n * 2
println(doubled(21))'

probe no-arg-annotation expect-clean '@key
case class Keyed(id: String)
println(Keyed("k").id)'

probe stacked-annotations expect-clean '@graphLabel("Edge")
@key
case class Edge(a: String, b: String)
println(Edge("x", "y").b)'

# The spelling that always worked — pinned so a fix cannot trade one for the other.
probe same-line expect-clean '@key case class Inline(v: Int)
println(Inline(7).v)'

echo
echo "ssc1-front-annotation: $pass ok, $fail FAIL"
[[ $fail -eq 0 ]]
