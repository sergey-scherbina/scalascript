#!/usr/bin/env bash
#
# vm-companion-ctor-gate — a companion object must not overwrite its case class's constructor.
#
# THE DEFECT (v2/BUGS.md scljet-jdbc-v2-applies-a-foreign-value-as-a-function). `case class B` and
# `object B` both want the bare global `B`. The VM installs top-level defs in TWO passes
# (v2/src/Runtime.scala): lambdas first, values second. The companion lowers to a `__mk_method_obj__`
# member record — a VALUE — so it lands second and overwrites the constructor closure. `B(x)` then
# applies a member record:
#
#     ssc: app: not a function: <foreign> — applied to 1 argument(s): 7
#
# WHY IT SURVIVED A FULL CORPUS. The BYTECODE lane binds the two separately and is unaffected, and
# `ssc run` uses that lane by default. Only a program that trips ClassTooLargeException and falls
# back to the VM ever showed it — which is why the report arrived as a 74-line scljet example and
# read like an scljet bug. `--vm` reaches the same lane in three lines, and that is the first row
# below; the last row is the real input that reported it.
#
# BOTH FRONTS EMIT THE DEF, so both carry the guard: `ssc1-lower.ssc0` (`declaredCtorTag`) and
# `specs/v2.2-p6.5-fsub.ssc` (`objValueDef`/`isCC`). A fix in one leaves the other compiling the
# bug — which front compiles a given file is not a choice (tests/BUGS.md two-front-bug-pairs).
#
# THE ROWS.
#   * `ctor` — the defect: construct through the companion, on the VM lane, and read a field back.
#   * `members` — the companion's own members must still resolve. The guard drops the object VALUE;
#     the prefixed member globals it keeps are what `B.mk` has always used, and this row is what
#     stops the guard from being widened into dropping those too.
#   * `plainobj` — THE ANTI-ROW. An object with NO case class of that name must still be a VALUE:
#     `takes(O)` passes it as an argument. That binding is the whole reason the def exists
#     (collect-css-and-collect-js-exist-on-three-lanes-and-not-on-native), so it passes with the fix
#     REVERTED and fails if the guard is written as "never emit the object value".
#   * `scljet` — the reported input, unreduced, on its own lane. Skipped when the example is absent.
#
# Every row runs the DEFAULT lane too: the bytecode lane always answered correctly here and must
# keep doing so, so a fix that "works" by pushing programs onto one lane is visible.
#
# COST: three programs x two lanes plus one example, ~40 s.

set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$ssc" ]] || { echo "vm-companion-ctor-gate: no launcher at $ssc — run ./install.sh --dev" >&2; exit 2; }

sandbox=$(mktemp -d "$ROOT/examples/_companion.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

cat > "$sandbox/ctor.ssc" <<'SSC'
case class B(x: Int)

object B:
  def mk(i: Int): B = B(i)

def main(): Unit = println(B.mk(7).x)
main()
SSC

cat > "$sandbox/members.ssc" <<'SSC'
case class B(x: Int)

object B:
  def zero: B = B(0)
  def mk(i: Int): B = B(i)
  def sum(a: B, b: B): Int = a.x + b.x

def main(): Unit =
  println(B.zero.x)
  println(B.mk(4).x)
  println(B.sum(B.mk(1), B.mk(2)))
main()
SSC

# The anti-row: no case class of this name, so the object must still be a first-class VALUE.
cat > "$sandbox/plainobj.ssc" <<'SSC'
object Cfg:
  def name: String = "cfg"

def takes(o: Any): String = "passed"

def main(): Unit =
  println(Cfg.name)
  println(takes(Cfg))
main()
SSC

lane_says() { # $1 label, $2 want, $3.. command
  local label=$1 want=$2; shift 2
  local out
  out=$(timeout 300 "$@" 2>/dev/null | head -8 | tr '\n' '|')
  if [[ "$out" == "$want" ]]; then
    echo "  ✓ $label: $out"
  else
    echo "  ✗ $label: got '$out', wanted '$want'"
    fails=$((fails + 1))
  fi
}

row() { # $1 case name, $2 expected output
  local name=$1 want=$2
  echo "── $name"
  lane_says "VM lane " "$want" "$ssc" run --vm "$sandbox/$name.ssc"
  lane_says "default " "$want" "$ssc" run "$sandbox/$name.ssc"
}

row ctor     '7|'
row members  '0|4|3|'
row plainobj 'cfg|passed|'

echo "── scljet (the reported input)"
if [[ -f "$ROOT/examples/scljet-jdbc.ssc" ]]; then
  out=$(timeout 900 "$ssc" run "$ROOT/examples/scljet-jdbc.ssc" 2>&1)
  if grep -q "not a function: <foreign>" <<<"$out"; then
    echo "  ✗ examples/scljet-jdbc.ssc still applies a foreign value"
    grep "not a function" <<<"$out" | head -1 | sed 's/^/      /'
    fails=$((fails + 1))
  elif grep -q "columns: 3" <<<"$out"; then
    echo "  ✓ examples/scljet-jdbc.ssc runs to its last line"
  else
    echo "  ✗ examples/scljet-jdbc.ssc did not reach its last line"
    tail -3 <<<"$out" | sed 's/^/      /'
    fails=$((fails + 1))
  fi
else
  echo "  – skipped: examples/scljet-jdbc.ssc is absent"
fi

echo
if [[ "$fails" -ne 0 ]]; then
  echo "vm-companion-ctor-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "vm-companion-ctor-gate: PASS"
