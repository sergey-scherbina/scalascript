#!/usr/bin/env bash
#
# rust-sibling-arg-coercion-gate — a call to a SIBLING member of the same object still coerces its
# arguments, and so does every def in a PACKAGED module.
#
# WHAT WAS BROKEN. `renderDef` registers every member of the owning object as an intrinsic that
# points back at the def this module generates, so a bare `digit(c)` inside `object Hex` reaches
# `Hex_digit`. That redirect lands in the intrinsic branch of `applyNonListCtor`, which built the
# call from the UNCOERCED argument list while the ordinary user-def path used the coerced one — so
# a sibling call silently lost the `Value` lift at an `Any` parameter, the `coerceFromValue`
# narrowing and the `SscChar` -> `i64` conversion.
#
# WHY IT IS NOT A NICHE. `package: std.json.core` makes the parser nest every code block in
# `object std: object json: object core:`, and those synthetic wrappers count as owners — so in a
# packaged module EVERY top-level def is a sibling and the module loses every coercion it needs.
# Fourteen of `std/json-core.ssc`'s thirty-two rustc errors were this.
#
# BOTH SHAPES ARE HERE ON PURPOSE. The object row is the RULE and needs no frontmatter; the
# packaged row is the reported IMPACT. A fix that covered only one would leave the other silent —
# and the packaged spelling is the one no reader would think to write as a test.
#
# COMPARED AGAINST `run`, not against itself: `hex` returns code-unit arithmetic and an uncoerced
# `Any` prints as a plain number, so a lane that passed the wrong bytes could still compile and
# print something plausible.
#
# COST: two cargo builds plus two interpreter runs, ~90 s.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
ssc="${SSC_BIN:-$ROOT/bin/ssc}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" && -x "$ssc" ]] || { echo "rust-sibling-arg-coercion-gate: no launcher — run ./install.sh --dev" >&2; exit 2; }
if ! command -v cargo >/dev/null 2>&1; then
  echo "rust-sibling-arg-coercion-gate: [skip] cargo is not on PATH. That is a SKIP, not a pass." >&2
  exit 0
fi

sandbox=$(mktemp -d "$ROOT/examples/_sibcoerce.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

# Row 1 — a user-written object. `digit(s.charAt(1))` is the SscChar family; `take(v)` with `v`
# declared `Any` is the Value family — a narrowing, which is the OTHER half of what json-core needs.
# Both are sibling calls, and both were emitted uncoerced.
cat > "$sandbox/obj.ssc" <<'SSC'
object P:
  def digit(c: Int): Int =
    if c >= 48 && c <= 57 then c - 48
    else if c >= 97 && c <= 102 then c - 87
    else -1

  def take(n: Int): Int = n + 1

  def go(v: Any): Int = take(v)

  def report(s: String): Int = digit(s.charAt(1))

def main(): Unit =
  println("digit: " + P.report("7f"))
  println("narrow: " + P.go(41))

main()
SSC

# Row 2 — the same two shapes with NO object in the source: `package:` supplies the wrappers, which
# is how std/json-core.ssc reaches this code path.
cat > "$sandbox/pkg.ssc" <<'SSC'
---
name: sibcoerce-pkg
package: std.sibcoerce.probe
exports:
  - report
---

```scalascript
def digit(c: Int): Int =
  if c >= 48 && c <= 57 then c - 48
  else if c >= 97 && c <= 102 then c - 87
  else -1

def take(n: Int): Int = n + 1

def go(v: Any): Int = take(v)

def report(s: String): Int = digit(s.charAt(1))

def main(): Unit =
  println("digit: " + report("7f"))
  println("narrow: " + go(41))

main()
```
SSC

echo "── a sibling call keeps its argument coercion"
for row in obj pkg; do
  want=$(timeout 600 "$ssc" run "$sandbox/$row.ssc" 2>/dev/null)
  if [[ -z "$want" ]]; then
    echo "  ✗ $row: the interpreter produced nothing — the oracle is unusable"; fails=$((fails + 1)); continue
  fi
  if ! (cd "$sandbox" && timeout 900 "$tools" build-rust "$sandbox/$row.ssc" >"$sandbox/$row.log" 2>&1); then
    echo "  ✗ $row: build-rust failed — a sibling call lost its coercion:"
    grep -m3 -E 'error\[E[0-9]+\]|Generic\(' "$sandbox/$row.log" | cut -c1-110 | sed 's/^/      /'
    fails=$((fails + 1)); continue
  fi
  got=$(timeout 300 "$sandbox/$row" 2>/dev/null)
  if [[ "$got" == "$want" ]]; then echo "  ✓ $row: $(printf '%s' "$want" | tr '\n' ' ')"
  else echo "  ✗ $row: rust '$got', interpreter '$want'"; fails=$((fails + 1)); fi
done

# The oracle must still be right: 'f' is 102, so hex(102) is 15 — not 102. Two lanes that had both
# regressed to passing the raw code unit would agree on every row above and both be wrong.
if timeout 600 "$ssc" run "$sandbox/obj.ssc" 2>/dev/null | grep -q 'digit: 15'; then
  echo "  ✓ the oracle itself is right: 'f' is 102 and digit(102) is 15, not 102"
else
  echo "  ✗ the interpreter no longer answers 15 — the oracle regressed"; fails=$((fails + 1))
fi

echo
if [[ "$fails" -ne 0 ]]; then echo "rust-sibling-arg-coercion-gate: FAIL ($fails)" >&2; exit 1; fi
echo "rust-sibling-arg-coercion-gate: PASS"
