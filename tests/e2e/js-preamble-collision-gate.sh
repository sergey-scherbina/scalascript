#!/usr/bin/env bash
#
# js-preamble-collision-gate — every std name the js preamble also defines must be declared-bound.
#
# THE DEFECT CLASS. `JsGen.declaredBindings` lists names the js preamble already defines, so that an
# import of the same name does not emit `const X = …` beside `function X` and break the bundle with
#
#     SyntaxError: Identifier 'X' has already been declared
#
# It is HAND-MAINTAINED, and nothing checked that it was maintained. Census 2026-08-17: the preamble
# defines 222 top-level functions, `std/**/*.ssc` exports 1452 names, 47 overlap — and 30 of those
# were missing from the list. Every one of the 30 produced a bundle that does not parse: 30 of 30,
# not "some".
#
# THIS IS A CHECK, NOT A FIXTURE, and that is the whole point. A gate listing the 30 would go green
# and stay green while the NEXT preamble function quietly repeats the defect. This one recomputes the
# intersection from the tree every run, so a `function foo` added to the preamble beside an exported
# `foo` fails here instead of failing for whoever imports it.
#
# TWO COMMANDS, BECAUSE THEY CAN DISAGREE — and the disagreement is per-program, not per-command,
# which is the part worth stating precisely. `run-js` tree-shakes the preamble, so a colliding
# `function` is SOMETIMES dropped and the program runs anyway; `emit-js` keeps it. Measured: a
# one-line program importing only `env` PASSED under `run-js` and failed to parse under `emit-js`,
# while the four-import program below fails under both. So neither command is the safe one to test
# with — a probe of four names run only under `run-js` reported a clean all-clear that was an
# artefact of the lane chosen and of how little that program imported. Hence both, every run.
#
# COST: one emit for the preamble census, one emit + one node run for the functional rows. ~20 s.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
ssc="${SSC_BIN:-$ROOT/bin/ssc}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" && -x "$ssc" ]] || { echo "js-preamble-collision-gate: no launcher — run ./install.sh --dev" >&2; exit 2; }

if ! command -v node >/dev/null 2>&1; then
  echo "js-preamble-collision-gate: [skip] node is not on PATH. That is a SKIP, not a pass." >&2
  exit 0
fi

sandbox=$(mktemp -d "$ROOT/examples/_pream.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

printf 'def main(): Unit =\n  println("x")\n\nmain()\n' > "$sandbox/trivial.ssc"
if ! timeout 600 "$tools" emit-js "$sandbox/trivial.ssc" > "$sandbox/preamble.cjs" 2>/dev/null; then
  echo "  ✗ emit-js failed on a trivial program — the census cannot run" >&2
  exit 1
fi

echo "── every std name the preamble also defines is declared-bound"
python3 - "$ROOT" "$sandbox/preamble.cjs" <<'PY'
import re, sys, glob, os
root, bundle = sys.argv[1], sys.argv[2]
pre = set(re.findall(r'^function ([A-Za-z_$][A-Za-z0-9_$]*)\(', open(bundle, errors='ignore').read(), re.M))
exported = {}
for f in glob.glob(root + '/std/**/*.ssc', recursive=True):
    txt = open(f, errors='ignore').read()
    m = re.match(r'^---\n(.*?)\n---\n', txt, re.S)
    if not m: continue
    em = re.search(r'^exports:\n((?:\s+-\s+\S+\n)+)', m.group(1), re.M)
    if not em: continue
    for line in em.group(1).strip().split('\n'):
        exported.setdefault(line.strip().lstrip('- ').strip(), os.path.relpath(f, root))
src = open(root + '/v1/runtime/backend/js/src/main/scala/scalascript/codegen/JsGen.scala').read()
blk = re.search(r'declaredBindings: mutable\.Set\[String\] =\s*mutable\.Set\((.*?)\),\n', src, re.S).group(1)
listed = set(re.findall(r'"([^"]+)"', re.sub(r'//[^\n]*', '', blk)))
overlap = sorted(set(exported) & pre)
missing = [n for n in overlap if n not in listed]
print(f"  preamble functions {len(pre)}, std exports {len(exported)}, overlap {len(overlap)}, listed {len(listed)}")
if missing:
    print(f"  ✗ {len(missing)} exported name(s) the preamble defines are NOT in declaredBindings —")
    print("    each is a `SyntaxError: Identifier '<name>' has already been declared` for whoever imports it:")
    for n in missing:
        print(f"      {n}  ({exported[n]})")
    sys.exit(1)
print(f"  ✓ all {len(overlap)} overlapping name(s) are declared-bound")
PY
[[ $? -ne 0 ]] && fails=$((fails + 1))

# The census proves no name COLLIDES. It does not prove the names still ANSWER — `declaredBindings`
# changes what an import resolves to, so a name could stop colliding and start returning the wrong
# thing. These rows are compared against `run`, on BOTH js commands, because the two disagree.
# Every row returns a STRING or a Boolean on every lane. An earlier draft used
# `jsonParse(…).toString`, which the interpreter refuses (`no column 'toString' in row [a]`) — so the
# oracle stopped after three lines, and the loop below silently checked three rows instead of four
# while still printing PASS. Hence WANT_ROWS: a gate must fail when its own coverage shrinks, not
# quietly cover less than its header claims.
WANT_ROWS=4
cat > "$sandbox/f.ssc" <<'SSC'
[env, pathJoin, pathBasename, pathDirname](../../std/os.ssc)

def main(): Unit =
  println("env HOME set: " + env("HOME").isDefined)
  println("pathJoin    : " + pathJoin("a", "b"))
  println("pathBasename: " + pathBasename("/x/y/z.txt"))
  println("pathDirname : " + pathDirname("/x/y/z.txt"))

main()
SSC

int_out=$(timeout 600 "$ssc" run "$sandbox/f.ssc" 2>/dev/null)
if [[ -z "$int_out" ]]; then
  echo "  ✗ the interpreter produced nothing — the oracle is unusable, so this gate cannot decide" >&2
  exit 1
fi
got_rows=$(printf '%s\n' "$int_out" | grep -c ': ' || true)
if [[ "$got_rows" -ne "$WANT_ROWS" ]]; then
  echo "  ✗ the oracle produced $got_rows row(s), not $WANT_ROWS — a row stopped working, and the"
  echo "    comparison below would silently check fewer rows than this gate claims to cover"
  fails=$((fails + 1))
fi
runjs_out=$(timeout 600 "$tools" run-js "$sandbox/f.ssc" 2>&1)
emitjs_out=""
if timeout 600 "$tools" emit-js "$sandbox/f.ssc" > "$sandbox/f.cjs" 2>/dev/null; then
  emitjs_out=$(timeout 300 node "$sandbox/f.cjs" 2>&1)
fi

check_lane() { # $1 label, $2 output
  local label=$1 out=$2 row got
  while IFS= read -r row; do
    got=$(printf '%s\n' "$out" | grep -F "${row%%:*}:" || true)
    if [[ "$got" == "$row" ]]; then
      echo "  ✓ $label  ${row}"
    else
      echo "  ✗ $label  ${row%%:*}: got '${got#*: }', wanted '${row#*: }'"
      fails=$((fails + 1))
    fi
  done <<< "$int_out"
}
check_lane "run-js " "$runjs_out"
check_lane "emit-js" "$emitjs_out"

echo
if [[ "$fails" -ne 0 ]]; then
  echo "js-preamble-collision-gate: FAIL ($fails)" >&2
  exit 1
fi
echo "js-preamble-collision-gate: PASS"
