#!/usr/bin/env bash
#
# A CONSTRUCTOR PATTERN INSIDE A CLASS METHOD RESOLVES — on both fronts, with and without `package:`.
#
# WHY THIS EXISTS. `case P(n) =>` inside a `class` method was refused with
# `ssc: "unknown constructor 'P' in a pattern"`. It was filed as a `package:` defect, because that
# frontmatter line is what made it appear on the DEFAULT lane — but `package:` is only what makes F
# decline into the F4a fallback. Under `SSC_FRONT=legacy` the same program fails with `name:` alone
# and with NO front matter at all, so the cap is not the frontmatter: it is every constructor
# pattern in a class method on that lane.
#
# THE MECHANISM, traced rather than guessed. `--structural` runs THREE lowerings in one process —
# the user's program, the front-matter yaml module, and the content-core module — and they share the
# process-global `caseFieldOrderCell`. A program's defs lower LAZILY, so the class-method bodies
# were reached after the yaml lowering had replaced the registry with its own:
#
#     LOWERPROG ctors=[P Holder ]                      the user's program registers
#     LOWERPROG ctors=[YamlNull YamlBool YamlInteger…] the front-matter parser REPLACES it
#     CKDBG     ctors=[YamlNull YamlBool YamlInteger…] the class-method body lowers NOW
#
# THE ROWS COME IN PAIRS, one per front, and that is the point rather than thoroughness: the default
# lane passed three of these before the fix and the legacy lane passed none, so a single-lane gate
# would have called the bug fixed while half of it stood.
#
# THE LAST ROWS ARE THE CONTROL. The fix makes the registry a UNION across the process instead of a
# replacement, so a check that used to reject valid names could now accept invalid ones. A genuinely
# undeclared constructor must still be REFUSED, on both fronts.
#
# Usage: tests/e2e/class-method-ctor-pattern-gate.sh
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT" || exit 2
SSC="${SSC:-bin/ssc}"
[ -x "$SSC" ] || { echo "class-method-ctor-pattern-gate: no launcher at $SSC"; exit 2; }

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
fails=0

# STDOUT ONLY, and it is not laziness about error capture. The default lane prints
# `ssc: F did not lower this file; compiled with the reference front` on STDERR when the F4a
# fallback fires, which is a NOTICE about which front ran and not a failure — the program still
# answers correctly through the reference front. Folding stderr in made three rows fail for the
# announcement rather than for the answer, which is the gate reporting on the wrong thing. Stderr is
# still shown when a row fails, so a real error is never swallowed.
both() { # name, source, expected — asserted on BOTH fronts
  printf '%s\n' "$2" > "$TMP/t.ssc"
  local d l derr lerr
  d="$(timeout 200 "$SSC" run "$TMP/t.ssc" 2>"$TMP/d.err" | head -1)"; derr="$(head -1 "$TMP/d.err")"
  l="$(SSC_FRONT=legacy timeout 200 "$SSC" run "$TMP/t.ssc" 2>"$TMP/l.err" | head -1)"; lerr="$(head -1 "$TMP/l.err")"
  if [ "$d" = "$3" ] && [ "$l" = "$3" ]; then
    printf '  ok   %-32s %s (both fronts)\n' "$1" "$3"
  else
    printf '  FAIL %-32s want [%s]  default=[%s]  legacy=[%s]\n' "$1" "$3" \
      "$(printf '%s' "$d" | cut -c1-46)" "$(printf '%s' "$l" | cut -c1-46)"
    [ -n "$derr" ] && printf '         default stderr: %s\n' "$(printf '%s' "$derr" | cut -c1-90)"
    [ -n "$lerr" ] && printf '         legacy  stderr: %s\n' "$(printf '%s' "$lerr" | cut -c1-90)"
    fails=$((fails + 1))
  fi
}

BODY='case class P(n: Int)

class Holder(h: Int):
  def read(v: Any): Int =
    v match
      case P(n) => n + h
      case _ => -1

println(Holder(10).read(P(5)))'

echo "== a case-class pattern in a class method, across the frontmatter shapes =="

both 'no front matter'    "$BODY" '15'
both 'front matter, no package' "---
name: t
---
$BODY" '15'
both 'front matter with package'  "---
name: t
package: demo
---
$BODY" '15'

echo "== the same for a case OBJECT, which uses the other registry =="

OBJBODY='sealed trait K
case object Alpha extends K

class Holder(h: Int):
  def read(v: K): Int =
    v match
      case Alpha => 1 + h

println(Holder(10).read(Alpha))'

both 'case object, no front matter' "$OBJBODY" '11'
both 'case object with package' "---
name: t
package: demo
---
$OBJBODY" '11'

echo "== the pattern in the places that already worked, which must keep working =="

both 'pattern in a top-level def' 'case class P(n: Int)
def read(v: Any): Int =
  v match
    case P(n) => n
    case _ => -1
println(read(P(7)))' '7'

both 'pattern in an object member' 'case class P(n: Int)
object O:
  def read(v: Any): Int =
    v match
      case P(n) => n
      case _ => -1
println(O.read(P(8)))' '8'

echo "== F must not DECLINE these files — the answer alone cannot see it =="

# EVERY ROW ABOVE PASSED WHILE HALF THE BUG STOOD, and this section is why it now cannot.
# `both` reads STDOUT, and stdout is right whichever front produced it: when F refuses a file the
# lane hands it to the reference front and the program answers correctly. So the rows above went
# green on 2026-08-27 with F still refusing `case P(n)` in a class method under `package:` — the
# measurement was of the OTHER front, silently.
#
# `SSC_FRONT_STRICT=1` makes F's refusal a reported `reason:` instead of a silent fallback. The
# assertion is narrow on purpose: only `unknown constructor` counts. F declining for some other
# reason is coverage, which this gate is not about, and folding that in would make the rows fail
# for the wrong thing the day F's coverage moves.
#
# THE MECHANISM, traced 2026-08-27 rather than guessed. Under `--structural` the F path lowers the
# frontmatter and content modules with `lowerProg`, and `package:` makes the runner parse the
# program a second time to build its namespace source. The user's class-method bodies were then
# lowered with `caseFieldOrderCell` holding `std/yaml-core.ssc`'s constructors — 78 patterns
# checked in one run, 77 of them Yaml* and the 78th the user's — while the user's program had never
# been through a `lowerProg` of its own. Recording the name where it is DECLARED, in the parser,
# is what makes the registry total; `declaredCtorNamesCell` in `v2/lib/ssc1-front.ssc0`.
f_lowers() { # name, source, expected stdout
  printf '%s\n' "$2" > "$TMP/t.ssc"
  local out reason
  out="$(SSC_FRONT_STRICT=1 timeout 200 "$SSC" run "$TMP/t.ssc" 2>"$TMP/s.err" | head -1)"
  reason="$(grep -m1 "unknown constructor" "$TMP/s.err" || true)"
  if [ -n "$reason" ]; then
    printf '  FAIL %-32s F REFUSED it: %s\n' "$1" "$(printf '%s' "$reason" | cut -c1-60)"
    fails=$((fails + 1))
  elif [ "$out" != "$3" ]; then
    printf '  FAIL %-32s want [%s] got [%s]\n' "$1" "$3" "$(printf '%s' "$out" | cut -c1-40)"
    fails=$((fails + 1))
  else
    printf '  ok   %-32s F lowered it (%s)\n' "$1" "$3"
  fi
}

f_lowers 'class method, package'  "---
name: t
package: demo
---
$BODY" '15'

f_lowers 'CASE class method, package' "---
name: t
package: demo
---
case class P(n: Int)

case class Holder(h: Int):
  def read(v: Any): Int =
    v match
      case P(n) => n + h
      case _ => -1

println(Holder(10).read(P(5)))" '15'

# An ENUM case is the other registry `lowerProg` fills, and it was NOT covered by the first version
# of the parser-side fix. Measured: `case class` went green while this stayed red, which is the
# spelling-matrix failure this repo keeps paying for. The row exists so it cannot happen quietly.
f_lowers 'enum case in a class method, package' "---
name: t
package: demo
---
enum C:
  case Red(n: Int)
  case Blue

class Holder(h: Int):
  def read(v: Any): Int =
    v match
      case Red(n) => n + h
      case Blue => 0
      case _ => -1

println(Holder(10).read(Red(5)))" '15'

f_lowers 'case object in a class method, package' "---
name: t
package: demo
---
$OBJBODY" '11'

echo "== CONTROL: an undeclared constructor is still refused =="

# A REFUSAL is stderr, so this one folds the streams on purpose — the opposite of `both` above and
# for the same reason: each reads the stream its subject actually lives in.
refuses() { # name, source
  printf '%s\n' "$2" > "$TMP/t.ssc"
  local d l
  d="$(timeout 200 "$SSC" run "$TMP/t.ssc" 2>&1 | grep -m1 "unknown constructor" || true)"
  l="$(SSC_FRONT=legacy timeout 200 "$SSC" run "$TMP/t.ssc" 2>&1 | grep -m1 "unknown constructor" || true)"
  case "$d$l" in
    *"unknown constructor"*"unknown constructor"*)
      printf '  ok   %-32s refused on both fronts\n' "$1" ;;
    *) printf '  FAIL %-32s should be refused  default=[%s]  legacy=[%s]\n' "$1" \
         "$(printf '%s' "$d" | cut -c1-40)" "$(printf '%s' "$l" | cut -c1-40)"
       fails=$((fails + 1)) ;;
  esac
}

refuses 'undeclared ctor in a class method' 'class Holder(h: Int):
  def read(v: Any): Int =
    v match
      case Nope(n) => n + h
      case _ => -1

println(Holder(10).read(1))'

refuses 'undeclared ctor at top level' 'def read(v: Any): Int =
  v match
    case Nope(n) => n
    case _ => -1
println(read(1))'

if [ "$fails" -ne 0 ]; then
  echo "class-method-ctor-pattern-gate: $fails FAILED"
  exit 1
fi
echo "class-method-ctor-pattern-gate: all rows pass on both fronts"
