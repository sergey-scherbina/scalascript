#!/usr/bin/env bash
#
# ref-front-qualified-ctor-pattern-gate — `case Owner.Case(x)` is not the bare stable-identifier
# pattern `ckCtorTag` exists to refuse, and the reference front must lower it.
#
# `ref-front-refuses-a-qualified-constructor-pattern`. `parsePatAtom` keeps only the LAST segment of
# a qualified pattern, because that is the arm tag the runtime matches on — a local
# `enum E { case A }` lowers `case E.A(n)` to `(arm A 1 …)` and always has. Dropping the qualifier
# also dropped the one fact that separates `case JsonValue.Str(v)` from the typo `case Nope =>`:
# the author named a namespace. `ckCtorTag` then refused the name, and a refusal there kills the
# WHOLE FILE, not the arm.
#
# v1's typer draws the line where this gate draws it — `Typer.scala` matches `case n: Term.Name`
# and never a `Term.Select`, and the comment above it opens with "a capitalised BARE name". The
# tower front was stricter than v1 by accident. Measured on the 2026-08-27 negtc corpus: 9 of the
# 18 remaining front errors were exactly this shape (`JsonValue.Str`, `PaymentIntent.Succeeded`,
# `BankRailsEvent.AchTransferSettled`, `TJsonValue.Obj`, `RdfNode.Literal`), every owner a type
# declared in a JVM plugin package the standard tier cannot see and does not need to.
#
# WHAT THIS GATE DRIVES, and why not the launcher: the surface the defect was measured on is
# `scripts/native-front-corpus`, which runs `v2/bin/ssc1-run.ssc0` under `ssc.cli` straight from the
# checkout. Driving the same thing here means no staged copy of the front can be stale, which is the
# failure `ref-front-string-literal-gate` spends twenty lines guarding against — there, the launcher
# is the surface, so it must.
#
# F IS NOT ASSERTED, deliberately. F has its own `ckCtorF` with the same gap and still DECLINES
# these files; the lane's F4a fallback then hands them to the reference front, so F's refusal is
# invisible and asserting it here would fail on a defect this change does not claim to fix. It is
# filed as `f-refuses-a-qualified-constructor-pattern`.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
jars="${SSC_JARS:-$(dirname -- "$ssc")/lib/jars}"
runner="${SSC_TOWER_RUN:-$ROOT/v2/bin/ssc1-run.ssc0}"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/ref-qctor.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0
export SSC_NO_BUILD_CHECK=1

echo "── a qualified constructor pattern lowers; a BARE unknown one is still refused"

core_jar=$(find "$jars" -maxdepth 1 -name 'scalascript-v2-core_*.jar' -print -quit 2>/dev/null)
if [[ -z $core_jar || ! -r $runner ]]; then
  echo "  · SKIP — no staged v2 core jar under $jars (run scripts/sbtc installBin)"
  echo "✓ ref-front-qualified-ctor-pattern-gate SKIPPED"
  exit 0
fi
echo "  · front: $runner   jars: $jars"

# front <name> <source> -> writes $sandbox/<name>.ir, echoes the exit code, keeps stderr
front() {
  local name=$1 src=$2
  printf '# %s\n\n```scala\n%s\n```\n' "$name" "$src" > "$sandbox/$name.ssc"
  timeout 200 java -Xss512m -cp "$jars/*" ssc.cli run "$runner" "$sandbox/$name.ssc" \
    > "$sandbox/$name.ir" 2> "$sandbox/$name.err"
  echo $?
}

# $1 name, $2 source — the front must lower it, and must emit no `_err` sentinel while doing so.
lowers() {
  local name=$1 src=$2 rc
  rc=$(front "$name" "$src")
  if [[ $rc -ne 0 ]]; then
    echo "  ✗ $name: front exited $rc — $(head -1 "$sandbox/$name.err" | cut -c1-100)"
    fails=$((fails + 1))
  elif grep -q '(global _err)' "$sandbox/$name.ir"; then
    echo "  ✗ $name: lowered, but emitted the _err sentinel (a partial IR is not a success)"
    fails=$((fails + 1))
  else
    echo "  ✓ $name: lowered ($(wc -c < "$sandbox/$name.ir" | tr -d ' ') bytes of IR)"
  fi
}

# $1 name, $2 expected message fragment, $3 source — the front must REFUSE.
refuses() {
  local name=$1 want=$2 src=$3 rc
  rc=$(front "$name" "$src")
  if [[ $rc -eq 0 ]]; then
    echo "  ✗ $name: front ACCEPTED it; expected a refusal naming $want"
    fails=$((fails + 1))
  elif ! grep -q "$want" "$sandbox/$name.err"; then
    echo "  ✗ $name: refused with the wrong message: $(head -1 "$sandbox/$name.err" | cut -c1-100)"
    fails=$((fails + 1))
  else
    echo "  ✓ $name: refused — $want"
  fi
}

# THE DEFECT. `Wire` is declared nowhere; a JVM-plugin type reaches the front exactly like this.
lowers qualified-unknown-owner 'def f(x: Any): String = x match
  case Wire.Str(v) => v
  case other => other.toString
def main(): Unit = println(f(1))'

# Nested inside another qualified pattern — the shape graph-rdf4j-storage.ssc uses.
lowers qualified-nested 'def f(x: Any): String = x match
  case RdfNode.Literal(JsonValue.Str(n)) => n
  case other => other.toString
def main(): Unit = println(f(1))'

# A qualified NULLARY pattern: no argument list, so it parses through the other finishCtorPat arm.
lowers qualified-nullary 'def f(x: Any): Int = x match
  case Status.Done => 1
  case _ => 0
def main(): Unit = println(f(1))'

echo "── controls: the refusal this check exists for is UNCHANGED"

# The typo the check was built for. If this ever passes, the fix has become fail-open.
refuses bare-unknown-ctor "unknown constructor 'Nope'" 'def main(): Unit =
  val x: Any = 1
  x match
    case Nope => println("BOUND")
    case _ => println("NO MATCH")'

# …and it must stay refused in a file that ALSO contains qualified patterns of OTHER names, which is
# what makes the acceptance list a list and not a switch.
refuses bare-unknown-beside-qualified "unknown constructor 'Nope'" 'def main(): Unit =
  val x: Any = 1
  x match
    case Wire.Str(v) => println(v)
    case Nope => println("BOUND")
    case _ => println("NO MATCH")'

echo "── controls: a name the front DOES know is unaffected, tag and all"

# The qualifier is still dropped: `case E.A(n)` must lower to the arm tag `A`, exactly as before.
lowers local-enum-qualified 'enum E:
  case A(x: Int)
  case B
def f(e: E): Int = e match
  case E.A(n) => n
  case E.B => 0
def main(): Unit = println(f(E.A(7)))'
if [[ -s "$sandbox/local-enum-qualified.ir" ]]; then
  if grep -q '(arm A 1' "$sandbox/local-enum-qualified.ir" &&
     grep -q '(arm B 0' "$sandbox/local-enum-qualified.ir"; then
    echo "  ✓ local-enum-qualified: arm tags are still A/1 and B/0 — the qualifier is dropped"
  else
    echo "  ✗ local-enum-qualified: expected (arm A 1 and (arm B 0 in the IR"
    fails=$((fails + 1))
  fi
fi

lowers local-case-class-bare 'case class Box(v: Int)
def f(b: Box): Int = b match
  case Box(n) => n
def main(): Unit = println(f(Box(3)))'

lowers builtin-ctor-bare 'def main(): Unit =
  val x: Option[Int] = Some(3)
  println(x match
    case Some(v) => v
    case None => 0)'

# ── THE KNOWN WIDENING, asserted so it cannot change silently ─────────────────────────────────
# The acceptance set is a list of NAMES, not name+owner pairs, so once any `X.Str(…)` has been
# parsed a BARE `case Str(v)` is accepted too. That is deliberate and far narrower than failing open
# on an empty registry (rejected in `f-refuses-jvmvfsread-in-a-pattern` for letting every genuine
# typo through), but it IS a cost, and a cost nobody asserts is a cost nobody notices. Tightening
# this to a name+owner pair is an improvement — it must then flip this row, on purpose.
lowers widening-bare-name-already-qualified-elsewhere 'def f(x: Any): String = x match
  case Wire.Str(v) => v
  case other => other.toString
def g(x: Any): String = x match
  case Str(v) => v
  case other => other.toString
def main(): Unit = println(f(1) + g(2))'

if [[ $fails -eq 0 ]]; then echo "✓ ref-front-qualified-ctor-pattern-gate PASSED"; exit 0; fi
echo "✗ ref-front-qualified-ctor-pattern-gate: $fails failure(s)"
exit 1
