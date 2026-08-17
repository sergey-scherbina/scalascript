#!/usr/bin/env bash
#
# ui-computed-signal-gate — a helper that wraps an anonymous signal may be called more than once.
#
# THE DEFECT, measured 2026-08-17. `std/ui/form.ssc` names it exactly:
#
#     def fieldError(f: Form, name: String): Any = {
#       …
#       computedSignal(() => vf(sp, d()))
#     }
#
# `fieldError` is called once per field, so every call reached the SAME lexical `computedSignal`
# site and got the same id. `makeSignal` reuses a cell when kind and declared default match — which
# is what makes a re-render idempotent — and throws when they differ. So a form with two fields died:
#
#     ssc: duplicate native UI signal '__computed__d64:doubled/root/body' in scope 'root'
#          has conflicting kind/default
#
# Reduced to eight lines, which is `two-computed-from-one-helper` below: `def doubled(s) =
# computedSignal(() => s() * 2)`, called twice. The first call prints, the second throws.
#
# TWO CELLS, NOT ONE. A census of the plugin found exactly two primitives that name a signal from its
# POSITION rather than from something the program supplies — `computedSignal` and `eqSignal`.
# Everything else takes a caller-supplied name (`signal`, `persistedSignal`, the fetch family) or is
# a singleton (`__hash__`, `__online__`), which is why only these two ever collided. Fixing
# `computedSignal` alone moved `styled-primitives` from failing on `__computed__` to failing on
# `__equality__` — the same defect one primitive over, and the reason this gate covers both.
#
# THE SUFFIX STARTS AT THE SECOND OCCURRENCE, so every id that works today is byte-identical
# tomorrow. That is deliberate: these ids are compared between the two fronts (see
# f-ui-signal-counter-gate) and asserted in backend tests, so renaming every anonymous signal would
# be a much larger change than the defect. `single-occurrence-still-runs` is the row that fails if
# somebody "simplifies" the suffix to be unconditional and the corpus ids all shift.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/ui-computed.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

echo "── a helper wrapping an anonymous signal may be called more than once"
ssc_usable_or_skip ui-computed-signal-gate "$ssc"

run() {
  local name=$1 want=$2 src=$3 out
  printf '%s\n' "$src" > "$sandbox/$name.ssc"
  out=$(SSC_NO_BUILD_CHECK=1 timeout 200 "$ssc" run "$sandbox/$name.ssc" < /dev/null 2>&1 | tr '\n' '|')
  if [[ "$out" == "$want" ]]; then
    echo "  ✓ $name: $want"
  else
    echo "  ✗ $name: got '$out', wanted '$want'"
    fails=$((fails + 1))
  fi
}

# ── the defect, both cells ───────────────────────────────────────────────────────────────────────
#
# The two values differ, so a fix that collapsed both calls onto one signal would answer '2|2|'
# rather than failing — a row asserting only "it does not throw" would pass for that.

run two-computed-from-one-helper '2|20|' '[signal, computedSignal](std/ui/primitives.ssc)

def doubled(s: Any): Any = computedSignal(() => s() * 2)

def main(): Unit =
  val a = signal("a", 1)
  val b = signal("b", 10)
  println(doubled(a)())
  println(doubled(b)())'

run three-computed-from-one-helper '2|20|200|' '[signal, computedSignal](std/ui/primitives.ssc)

def doubled(s: Any): Any = computedSignal(() => s() * 2)

def main(): Unit =
  val a = signal("a", 1)
  val b = signal("b", 10)
  val c = signal("c", 100)
  println(doubled(a)())
  println(doubled(b)())
  println(doubled(c)())'

run two-equality-from-one-helper 'true|false|' '[signal, eqSignal](std/ui/primitives.ssc)

def isZero(s: Any): Any = eqSignal(s, 0)

def main(): Unit =
  val a = signal("a", 0)
  val b = signal("b", 7)
  println(isZero(a)())
  println(isZero(b)())'

# ── the shape that must not move ─────────────────────────────────────────────────────────────────

run single-occurrence-still-runs '6|' '[signal, computedSignal](std/ui/primitives.ssc)

def main(): Unit =
  val a = signal("a", 3)
  val d = computedSignal(() => a() * 2)
  println(d())'

# A signal read twice is ONE signal: the occurrence counter must be consumed when the primitive is
# reached, not when the signal is read, or every read would mint a new cell.
run repeated-read-is-one-signal '4|4|' '[signal, computedSignal](std/ui/primitives.ssc)

def main(): Unit =
  val a = signal("a", 2)
  val d = computedSignal(() => a() * 2)
  println(d())
  println(d())'

# ── the corpus file this unblocked ───────────────────────────────────────────────────────────────

subject="$ROOT/examples/frontend/std-ui/styled-primitives.ssc"
if [[ -f "$subject" ]]; then
  out=$(SSC_NO_BUILD_CHECK=1 SSC_FRONT_STRICT=1 timeout 300 "$ssc" run "$subject" < /dev/null 2>&1 | head -1)
  case "$out" in
    *"duplicate native UI signal"*)
      echo "  ✗ styled-primitives: still '$out'"; fails=$((fails + 1)) ;;
    *)
      echo "  ✓ styled-primitives: $out" ;;
  esac
else
  echo "  ⊘ styled-primitives: subject absent"
fi

if [[ $fails -eq 0 ]]; then echo "✓ ui-computed-signal-gate PASSED"; exit 0; fi
echo "✗ ui-computed-signal-gate: $fails failure(s)"
exit 1
