#!/usr/bin/env bash
#
# check-accepts-what-the-runtime-rejects-gate — `ssc check` says OK on programs that then FAIL.
#
#   ./tests/e2e/check-accepts-what-the-runtime-rejects-gate.sh
#   ./tests/e2e/check-accepts-what-the-runtime-rejects-gate.sh --self-test
#
# THIS GATE PINS A DEFECT, IT DOES NOT ASSERT CORRECTNESS. Both subjects below are programs the
# static check accepts and the runtime rejects — `tests/BUGS.md`
# `check-accepts-names-the-v1-runtime-does-not-have` and
# `a-flat-def-passed-where-a-curried-type-is-declared`. Both entries carried `gate: none`, which is
# why they could sit open with nothing connecting them to a run.
#
# WHEN THE DEFECT IS FIXED THIS GATE GOES RED, ON PURPOSE. That is the whole point: `check` starting
# to refuse these is exactly the improvement the entries ask for, and the gate failing is how
# whoever makes it finds them. Do not "repair" a red here by relaxing it — close the BUGS entry and
# move the subject to a gate that asserts the refusal.
#
# THE TWO ASSERTIONS PER SUBJECT ARE A PAIR, and neither is worth anything alone.
#
#   * "check says OK" alone would pass on a build where check does nothing at all.
#   * "run fails" alone would pass on a program that is broken for some unrelated reason — and the
#     runtime message is asserted, not merely the exit code, because `arity: 2 expected, 1 given`
#     and `unbound global: vstack` are what make these the RIGHT programs. A different failure
#     would mean the subject drifted.
#
# Together they say the thing that is actually wrong: the two tiers disagree about the same file,
# and the one that runs FIRST is the one that is wrong.
#
# WHY `ssc-tools` AND NOT `ssc`: `check` lives in the optional tools/compatibility tier. Plain
# `bin/ssc check` answers "requires the optional ScalaScript tools tier" AND EXITS 0 — so a gate
# written against `bin/ssc` would read that as success and pass while checking nothing. Measured
# while writing this file.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
ssc="${SSC:-$ROOT/bin/ssc}"
sandbox="$(mktemp -d "${TMPDIR:-/tmp}/check-accepts.XXXXXX")"
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

cat > "$sandbox/missing-name.ssc" <<'EOF'
```scalascript
val page = vstack("a")
println(page)
```
EOF

cat > "$sandbox/flat-for-curried.ssc" <<'EOF'
```scalascript
def flat(a: Int, b: Int): Int = a + b
def use(f: Int => Int => Int): Int = f(1)(2)
println(use(flat))
```
EOF

# subject <file> <expected runtime message fragment> <BUGS slug>
subject() {
  local file="$1" wanted="$2" slug="$3" name; name="$(basename "$file" .ssc)"

  local check_out check_rc
  check_out="$(timeout 240 "$tools" check "$file" 2>&1)"; check_rc=$?
  if [ "$check_rc" -ne 0 ]; then
    echo "  ✓ $name: check REFUSES it — the defect is fixed" >&2
    echo "    This gate pinned the old behaviour. Close tests/BUGS.md $slug and replace this" >&2
    echo "    subject with one that asserts the refusal." >&2
    fails=$((fails + 1)); return
  fi
  case "$check_out" in
    *OK*) : ;;
    *)    echo "  ✗ $name: check exited 0 but did not say OK — the subject has drifted" >&2
          echo "    got: $check_out" >&2
          fails=$((fails + 1)); return ;;
  esac

  local run_out run_rc
  run_out="$(timeout 240 "$ssc" run "$file" 2>&1)"; run_rc=$?
  if [ "$run_rc" -eq 0 ]; then
    echo "  ✗ $name: it RUNS now. check was right and the entry is stale, or the program changed." >&2
    echo "    got: $run_out" >&2
    fails=$((fails + 1)); return
  fi
  case "$run_out" in
    *"$wanted"*) echo "  ✓ $name: check says OK, run fails with '$wanted' — defect still present" ;;
    *) echo "  ✗ $name: it fails, but not with '$wanted' — the subject has drifted" >&2
       echo "    got: $run_out" >&2
       fails=$((fails + 1)) ;;
  esac
}

if [ "${1:-}" = "--self-test" ]; then
  # The gate must be able to SAY NO. A program that both tiers agree on must not satisfy it —
  # otherwise the checks above would pass on anything.
  cat > "$sandbox/agreed.ssc" <<'EOF'
```scalascript
println(1 + 1)
```
EOF
  before=$fails
  subject "$sandbox/agreed.ssc" "unbound global" "n/a" >/dev/null 2>&1
  if [ "$fails" -eq "$before" ]; then
    echo "check-accepts --self-test: FAIL — a program both tiers accept was counted as a defect." >&2
    echo "  The subject predicate does not discriminate; every assertion below is meaningless." >&2
    exit 1
  fi
  fails=$before
  echo "check-accepts --self-test: OK — a program both tiers agree on is rejected as a subject"
fi

echo "── check says OK; the runtime disagrees ──────────────────────────────"
subject "$sandbox/missing-name.ssc"     "unbound global: vstack"      "check-accepts-names-the-v1-runtime-does-not-have"
subject "$sandbox/flat-for-curried.ssc" "arity: 2 expected, 1 given"  "a-flat-def-passed-where-a-curried-type-is-declared"

if [ "$fails" -ne 0 ]; then
  echo "check-accepts-what-the-runtime-rejects-gate: FAIL ($fails)" >&2
  exit 1
fi
echo "check-accepts-what-the-runtime-rejects-gate: OK (2 subjects, defect still present)"
