#!/usr/bin/env bash
# v3's OWN JVM backend — stages 1-2 of v3/specs/70-jvm-backend.md §8.
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
# executor's output comes from `io.println`, `Prim` is stage 6, and stage 2 refuses `Prim` by name.
# Measured 2026-09-02: `ssc3 exec <f.ssir>` prints NOTHING — it returns the entry's value and does
# not render it — so there is no second lane to compare against yet even for these fixtures.
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
  echo "── self-test: planted defects must turn this gate RED ─────────────────────"
  src="v3/src/JvmBackend.scala"
  backup="$sandbox/JvmBackend.scala.orig"
  cp "$src" "$backup" || exit 2
  restore_src() { cp "$backup" "$src"; }
  trap 'restore_src; rm -rf "$sandbox"' EXIT HUP INT TERM

  # EVERY PLANT HERE STILL VERIFIES AND STILL RUNS. That is the point: a gate that only checked
  # "the JVM loaded it" would stay green on all of them, so only comparing the VALUE catches them.
  # One plant per stage, because a self-test that exercises stage 1's arithmetic says nothing about
  # whether stage 2's data path is being looked at.
  #
  #   name | perl s/// | proof it landed | fixture | class
  plant_one() {
    local what="$1" subst="$2" proof="$3" fixture="$4" cls="$5"
    restore_src
    perl -0pi -e "$subst" "$src"
    if ! grep -q "$proof" "$src"; then
      echo "  FAIL — could not plant '$what'; this self-test proves nothing" >&2
      restore_src
      return 1
    fi
    local out rc
    out="$(run_one "$ROOT/v3/tests/jvm/$fixture.ssir" "$(cat "$ROOT/v3/tests/jvm/$fixture.expected")" "$cls" 2>&1)"
    rc=$?
    restore_src
    if [ "$rc" -eq 0 ]; then
      echo "  FAIL — the gate PASSED with '$what' planted. It is not checking the answer." >&2
      echo "$out" >&2
      return 1
    fi
    echo "  ok   '$what' was caught:"
    echo "$out" | sed 's/^/       /'
    return 0
  }

  st_fails=0
  # Stage 1: `ladd` becomes `lsub`. Both are long-to-long, so the verifier is content.
  plant_one "ladd -> lsub" \
    's/case BinOp\.Add  => 0x61/case BinOp.Add  => 0x65/' \
    'BinOp.Add  => 0x65' arith Arith || st_fails=$((st_fails + 1))
  # Stage 2: a new array is filled with 1 instead of the executor's 0. Same types, same shape, no
  # exception — the class loads and runs and answers 705 where 704 is right.
  plant_one "NewArr fills with 1, not 0" \
    's/val zero = pushBoxedLong\(n\._1, 0L\)/val zero = pushBoxedLong(n._1, 1L)/' \
    'pushBoxedLong(n._1, 1L)' arr Arr || st_fails=$((st_fails + 1))

  if [ "$st_fails" -ne 0 ]; then
    echo "== v3 jvm-backend gate self-test: RED ($st_fails plant(s) went undetected) ==" >&2
    exit 1
  fi
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
echo "== v3 jvm-backend gate: GREEN ($ran fixture(s), stage 2: straight-line I64 + data) =="
