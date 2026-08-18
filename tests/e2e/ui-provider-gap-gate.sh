#!/usr/bin/env bash
#
# ui-provider-gap-gate — five std/ui primitives are declared but not implemented on the native lane,
# and the program must be told THAT rather than "unbound global".
#
# `std/ui/primitives.ssc` declares 53 externs; `UiNativePlugin` implements all but `forJsonView`,
# `selectFromView`, `itemField`, `intervalTick` and `fetchStreamSignal`. Left unbound, calling one
# gave `ssc: unbound global: forJsonView` — which reads like a typo in the PROGRAM, when the truth is
# that the primitive exists and this lane does not provide it.
#
# A REFUSAL IS NOT HALF A PRIMITIVE, and that distinction is why these are throws and not
# constructors. A previous attempt wrote two of them as descriptors — packaging a DataV like their
# twin `forKeyedView` — and did not land them: a constructor with no renderer lets a program build a
# node nothing can draw, trading an honest failure at the call for a confusing one downstream. The
# rows below therefore demand a FAILURE with a specific message, not a success.
#
# THE ENTRY'S DAMAGE CLAIM WAS REFUTED BY MEASUREMENT, which is why the second half of this gate
# exists. `v2-ui-provider-lacks-forJsonView-and-blocks-eight-unrelated-tests` named eight corpus
# cases as blocked; measured 2026-08-18 all eight RUN, on F, under SSC_FRONT_STRICT. They import
# `std/ui/lower.ssc`, which does call `forJsonView` — but inside the `JsonForNode` arm they never
# reach, and `validateNoReader` accepts a DECLARED extern as a signature. The gap is LATENT: it bites
# when the arm executes, not when the module loads. `still-runs-*` are the rows that fail if
# registering these refusals ever turns a latent gap into a live one.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/ui-provider-gap.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

echo "── a declared-but-unimplemented std/ui primitive must refuse in its own words"
ssc_usable_or_skip ui-provider-gap-gate "$ssc"

refuses() {
  local name=$1 src=$2 out
  printf '%s\n' "$src" > "$sandbox/$name.ssc"
  out=$(SSC_NO_BUILD_CHECK=1 timeout 200 "$ssc" run "$sandbox/$name.ssc" < /dev/null 2>&1 | head -1)
  if [[ "$out" == *"unbound global"* ]]; then
    echo "  ✗ $name: still 'unbound global' — reads as a typo in the program"
    fails=$((fails + 1))
  elif [[ "$out" == *"not implemented on the native lane"* && "$out" == *"provider gap"* ]]; then
    echo "  ✓ $name: refuses in its own words"
  else
    echo "  ✗ $name: unexpected — $out"
    fails=$((fails + 1))
  fi
}

refuses forJsonView-refuses '[signal, forJsonView, element](std/ui/primitives.ssc)

def main(): Unit =
  val items = signal("items", "[]")
  println(forJsonView(items, "id", (row) => element("div", [], Map(), [])))'

refuses selectFromView-refuses '[signal, selectFromView](std/ui/primitives.ssc)

def main(): Unit =
  val items = signal("items", "[]")
  val sel = signal("sel", "")
  println(selectFromView(items, (a) => "k", (a) => ("v", "l"), sel, "", "", false))'

refuses itemField-refuses '[itemField](std/ui/primitives.ssc)

def main(): Unit = println(itemField("row", "name"))'

# ── the latent gap must stay latent ──────────────────────────────────────────────────────────────
#
# These import std/ui/lower.ssc, which CALLS forJsonView in an arm they do not reach. If registering
# the refusals ever made that call eager, these go red — which is the failure this gate is really
# guarding, since it would break eight working cases to improve a message.

for case in tkv2-button-size tkv2-select tkv2-keyed-for tkv2-tri-state; do
  subject="$ROOT/tests/conformance/$case.ssc"
  if [[ ! -f "$subject" ]]; then echo "  ⊘ still-runs-$case: subject absent"; continue; fi
  out=$(SSC_NO_BUILD_CHECK=1 SSC_FRONT_STRICT=1 timeout 200 "$ssc" run "$subject" < /dev/null 2>&1 | head -1)
  if [[ "$out" == ssc:* ]]; then
    echo "  ✗ still-runs-$case: $out"
    fails=$((fails + 1))
  else
    echo "  ✓ still-runs-$case: $out"
  fi
done

if [[ $fails -eq 0 ]]; then echo "✓ ui-provider-gap-gate PASSED"; exit 0; fi
echo "✗ ui-provider-gap-gate: $fails failure(s)"
exit 1
