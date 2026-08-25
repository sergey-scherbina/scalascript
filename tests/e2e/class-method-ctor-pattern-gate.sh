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
