#!/usr/bin/env bash
#
# v2-extern-default-args-gate — an `extern def`'s declared default must be filled at the call site,
# and NOTHING ELSE about an extern call may be rewritten.
#
# THE DEFECT (v2/BUGS.md v2-extern-default-argument-is-never-filled-so-a-plugin-native-needs-full-arity).
# An extern is a signature, so the front skipped the whole declaration and never parsed its
# parameter list; its defaults therefore never reached the registry the call site fills from:
#
#     extern def contentToolkitBlock(id: String, options: ContentToolkitOptions = …): TkNode
#     contentToolkitBlock("t1")     v2:  ssc: arity: 2 expected, 1 given
#                                   int: runs
#
# The arity in that message is the PLUGIN's registered arity, so the program was refused for
# calling the signature it was written against. Every `extern def` in std/ with a default was
# affected — nfc, actors, streams-bridge, openapi, ui/content, ui/primitives, cluster.
#
# WHY THE ROWS BELOW ARE HALF ANTI-ROWS. `specs/v2.2-p6.5-fsub.ssc` excludes externs from that same
# registry ON PURPOSE, and its comment carries the measurements: rewriting an extern call to match a
# source signature the native never sees is what made `examples/_bug1b.ssc` silently drop a block
# (clause flattening) and what turned `va(1, 2)` into a Cons list (vararg collapse). The fix keeps
# both exclusions and adds back exactly one thing, because a default is different in kind: the
# missing argument's VALUE is an expression written in ssc source, which only a front can evaluate,
# and the native's own registered arity says it wants that argument.
#
# So: the first rows prove the default is filled; the last prove the call is otherwise untouched. A
# fix that "simplifies" this by putting externs back into the whole registry passes the first and
# fails the last.
#
# NOT ASSERTED, and said out loud rather than implied: the vararg half. `extern def va(xs: Int*)`
# has no registered native anywhere in this repo, so there is nothing to run — the exclusion is
# enforced in the code (`externDfltE3` requires an actual default) and reviewed, not measured here.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
fails=0
export SSC_NO_BUILD_CHECK=1

# The probes live under examples/ because they import std/ by a RELATIVE path, exactly as
# examples/_bug1b.ssc does; from a temp dir those imports do not resolve and every row fails for a
# reason that has nothing to do with the front. Unique per run: two concurrent runs sharing one
# fixed directory is a race that has already cost this repo a mystery failure (f-trailing-block-gate
# records it), and CI runs jobs in parallel.
sandbox=$(mktemp -d "$ROOT/examples/_xdflt.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

# A `@ui=toolkit` block is what `contentToolkitBlock` selects; without one the native raises
# "no block with id", which is a DIFFERENT failure and would make a green here meaningless.
TOOLKIT_DOC='```yaml @id=t1 @ui=toolkit
signals:
  a: 1
controls:
  type: text
  text: hi
```
'

runs_as() { # $1 name, $2 expected FULL stdout (newlines as |), $3 source
  local name=$1 want=$2 src=$3 out
  printf '%s\n' "$src" > "$sandbox/$name.ssc"
  out=$(timeout 200 "$ssc" run "$sandbox/$name.ssc" 2>&1 | head -6 | tr '\n' '|')
  if [[ "$out" == "$want" ]]; then
    echo "  ✓ $name: $out"
  else
    echo "  ✗ $name: got '$out', wanted '$want'"
    fails=$((fails + 1))
  fi
}

runs_as_v1() { # the oracle lane, for the row where the two lanes disagreed
  local name=$1 want=$2 src=$3 out
  printf '%s\n' "$src" > "$sandbox/$name.ssc"
  out=$(timeout 200 "$tools" run --v1 "$sandbox/$name.ssc" 2>&1 | head -6 | tr '\n' '|')
  if [[ "$out" == "$want" ]]; then
    echo "  ✓ $name (--v1): $out"
  else
    echo "  ✗ $name (--v1): got '$out', wanted '$want'"
    fails=$((fails + 1))
  fi
}

# ── self-test: the comparison must be able to say NO ──────────────────────────────────────────────
#
# Runs a program whose output is known and asserts a DELIBERATELY WRONG expectation, which must be
# reported as a failure. Without it, a `runs_as` that silently stopped running anything would report
# every row green.
self_test() {
  echo "── self-test: a wrong expectation is caught"
  local before=$fails
  runs_as _selftest 'this-is-not-the-output|' 'def main(): Unit = println("ok")' >/dev/null 2>&1
  if [[ "$fails" -eq "$before" ]]; then
    echo "  ✗ SELF-TEST FAILED: a wrong expectation was accepted." >&2
    return 1
  fi
  fails=$before
  echo "  ✓ a wrong expectation is reported as a failure"
  return 0
}

echo "── an extern's declared default reaches the call site"
ssc_usable_or_skip v2-extern-default-args-gate "$ssc"

if [[ "${1:-}" == "--self-test" ]]; then
  self_test || exit 1
  echo
fi

# THE DEFECT. One argument for a two-parameter extern whose second parameter has a default.
runs_as extern-default-filled 'ok|' "$TOOLKIT_DOC"'
[contentToolkitBlock](../../std/ui/content.ssc)

```scalascript
val n = contentToolkitBlock("t1")
println("ok")
```'

# The lane that was right all along — the same source, the interpreter. This row is why the fix is
# a fix and not a preference: the two lanes disagreed about a documented signature.
runs_as_v1 extern-default-filled 'ok|' "$TOOLKIT_DOC"'
[contentToolkitBlock](../../std/ui/content.ssc)

```scalascript
val n = contentToolkitBlock("t1")
println("ok")
```'

# Full arity must keep working — a fix that only handles the short spelling would break every call
# site that already passes the argument, which is all of them today.
runs_as extern-default-full-arity 'ok|' "$TOOLKIT_DOC"'
[contentToolkitBlock, ContentToolkitOptions](../../std/ui/content.ssc)

```scalascript
val n = contentToolkitBlock("t1", ContentToolkitOptions())
println("ok")
```'

echo "── and nothing else about an extern call is rewritten"

# THE ANTI-ROW, and the reason this gate exists next to the fix rather than after it. A curried
# extern must still be applied one clause at a time: flattening it is what dropped the block in
# examples/_bug1b.ssc, with no error and exit 0.
runs_as extern-curried-still-nested 'inside-block|after|' '[httpClient](../../std/http.ssc)
def main(): Unit =
  httpClient("http://example.invalid") {
    println("inside-block")
  }
  println("after")'

# The ordinary path, untouched: a plain def with a default still fills. If this ever fails, the fix
# leaked out of the extern branch.
runs_as ctl-plain-default 'hi bob|' 'def greet(name: String, greeting: String = "hi"): String = greeting ++ " " ++ name
def main(): Unit = println(greet("bob"))'

echo
if [[ "$fails" -ne 0 ]]; then
  echo "v2-extern-default-args-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "v2-extern-default-args-gate: PASS"
