#!/usr/bin/env bash
# v3's OWN JVM backend — stage 1 of v3/specs/70-jvm-backend.md §8.
#
#   ./v3/jvm-backend-gate.sh              the fixtures
#   ./v3/jvm-backend-gate.sh --self-test  plant a defect and require this gate to catch it
#
# WHAT IT ASSERTS, narrowly, because the stage is narrow. For each `.ssir` fixture: v3 emits a class
# file with no ASM and no v2, `java` LOADS it — which is the verifier accepting bytes v3 wrote — and
# the program prints the value the fixture declares.
#
# THIS IS A FIXTURE GATE, NOT A DIFFERENTIAL, AND THE DIFFERENCE MATTERS. Every other v3 gate
# compares two lanes; this one compares against a number written by hand. It has to, for now: the
# executor's output comes from `io.println`, `Prim` is stage 6, and stage 1 refuses `Prim` by name.
# A hand-written expectation is weaker evidence than a differential — it can only catch a backend
# that disagrees with ME, not one that disagrees with v3 — and the honest reading is that this gate
# proves the class file is well-formed and the arithmetic is right, not that the two lanes agree.
# The differential arrives with `Prim`, and this comment is the marker for making it one.
#
# The expectations are computed by a SECOND implementation rather than by reading the fixture: the
# `.expected` files were produced by Python evaluating the same expression. An expectation derived
# from the thing under test is not an expectation.
#
# --self-test IS NOT OPTIONAL POLISH. The charter's rule is that a gate which cannot fail is not a
# gate, and three gates in this module have gone green while proving nothing. It corrupts one opcode
# in the emitter's output and requires a RED — if the planted defect passes, this gate is not
# looking at what it claims to look at.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 2

sandbox="$(mktemp -d "${TMPDIR:-/tmp}/jvmgate.XXXXXX")"
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

fails=0
ran=0

# One fixture: emit, load, run, compare. Prints its own verdict line either way.
run_one() {
  local ssir="$1" want="$2" name="$3"
  local out rc
  if ! out="$(cd "$sandbox" && timeout 300 "$ROOT/v3/ssc3" emit-jvm "$ssir" "$name" 2>&1)"; then
    echo "  FAIL $name — emit-jvm refused or crashed" >&2
    echo "    $out" >&2
    return 1
  fi
  if [ ! -s "$sandbox/$name.class" ]; then
    echo "  FAIL $name — emit-jvm exited 0 and wrote no class file" >&2
    return 1
  fi
  out="$(cd "$sandbox" && timeout 300 java -cp . "$name" 2>&1)"; rc=$?
  if [ "$rc" -ne 0 ]; then
    # A VerifyError lands here, and it is the failure this backend is most likely to produce.
    echo "  FAIL $name — the JVM refused the class v3 wrote (exit $rc)" >&2
    echo "    $out" >&2
    return 1
  fi
  if [ "$out" != "$want" ]; then
    echo "  FAIL $name — wanted '$want', got '$out'" >&2
    return 1
  fi
  echo "  ok   $name -> $out"
  return 0
}

if [ "${1:-}" = "--self-test" ]; then
  echo "── self-test: a planted opcode defect must turn this gate RED ─────────────"
  src="v3/src/JvmBackend.scala"
  backup="$sandbox/JvmBackend.scala.orig"
  cp "$src" "$backup" || exit 2
  restore_src() { cp "$backup" "$src"; }
  trap 'restore_src; rm -rf "$sandbox"' EXIT HUP INT TERM

  # `ladd` (0x61) becomes `lsub` (0x65). It still VERIFIES — both are long-to-long — so a gate that
  # only checked "the JVM loaded it" would stay green. Only comparing the VALUE catches this, which
  # is exactly the property being tested.
  perl -0pi -e 's/case BinOp\.Add  => 0x61/case BinOp.Add  => 0x65/' "$src"
  if ! grep -q 'BinOp.Add  => 0x65' "$src"; then
    echo "  FAIL — could not plant the defect; the self-test proves nothing" >&2
    restore_src
    exit 1
  fi
  planted_out="$(run_one "$ROOT/v3/tests/jvm/arith.ssir" "$(cat "$ROOT/v3/tests/jvm/arith.expected")" Arith 2>&1)"
  planted_rc=$?
  restore_src
  if [ "$planted_rc" -eq 0 ]; then
    echo "  FAIL — the gate PASSED with ladd replaced by lsub. It is not checking the answer." >&2
    echo "$planted_out" >&2
    exit 1
  fi
  echo "  ok   the planted lsub was caught:"
  echo "$planted_out" | sed 's/^/       /'
  echo "== v3 jvm-backend gate self-test: GREEN (it can fail) =="
  exit 0
fi

echo "── v3's own JVM backend: emit, load, run ──────────────────────────────────"
for ssir in "$ROOT"/v3/tests/jvm/*.ssir; do
  [ -e "$ssir" ] || continue
  base="$(basename "$ssir" .ssir)"
  expected="$ROOT/v3/tests/jvm/$base.expected"
  if [ ! -f "$expected" ]; then
    echo "  FAIL $base — no .expected beside it; a fixture with no expectation checks nothing" >&2
    fails=$((fails + 1)); continue
  fi
  # The class name must be a legal Java identifier; the fixtures are named for it.
  cls="$(echo "$base" | awk '{ print toupper(substr($0,1,1)) substr($0,2) }')"
  ran=$((ran + 1))
  run_one "$ssir" "$(cat "$expected")" "$cls" || fails=$((fails + 1))
done

if [ "$ran" -eq 0 ]; then
  echo "jvm-backend: FAIL — no fixtures ran. An empty population reporting green is the failure" >&2
  echo "  this repository keeps hitting; see v3/specs/00-charter.md." >&2
  exit 1
fi

if [ "$fails" -ne 0 ]; then
  echo "== v3 jvm-backend gate: RED ($fails of $ran) ==" >&2
  exit 1
fi
echo "== v3 jvm-backend gate: GREEN ($ran fixture(s), stage 1: straight-line I64) =="
