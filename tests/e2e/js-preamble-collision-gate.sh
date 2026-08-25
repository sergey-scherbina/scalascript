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
python3 - "$ROOT" "$sandbox/preamble.cjs" "$sandbox/missing.tsv" <<'PY'
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
with open(sys.argv[3], "w") as out:
    for n in missing:
        out.write(f"{n}\t{exported[n]}\n")
if missing:
    print(f"  … {len(missing)} overlapping name(s) are NOT in declaredBindings — verifying each below")
else:
    print(f"  ✓ all {len(overlap)} overlapping name(s) are declared-bound")
PY
[[ $? -ne 0 ]] && fails=$((fails + 1))

# ── VERIFY, do not assume: a name overlap is not a collision ─────────────────────────────────
#
# The census above used to FAIL on the overlap alone, in the words "each is a SyntaxError for whoever
# imports it". That claim was measured ONCE, for the thirty names the list was seeded from, and then
# frozen into a static rule that cannot tell a colliding name from a benign one.
#
# It cost three days of nightly red on 2026-08-25. `std/html.ssc` exports `raw`, the preamble defines
# `function raw`, and the bundle PARSES: four import shapes were tried (`[raw]`, `[html, raw]`, the
# whole module, and one binding `raw` through a local def) and every one emitted exactly ONE
# top-level `raw` — the preamble's — with no `const raw` beside it, and `node --check` was clean on
# all four. There was nothing to declare and nothing to fix; the gate was wrong about its subject.
#
# So the rule is now the MEASUREMENT the list was built from, applied to every name the static check
# flags: emit a one-line program importing it, run `node --check`, and fail only on a name that
# really produces the SyntaxError. A name that overlaps WITHOUT colliding is still REPORTED — it is
# worth seeing, because the import resolves to the preamble function rather than to the std def —
# but it does not turn the gate red.
#
# ADDING THE NAME TO `declaredBindings` WOULD HAVE BEEN THE WRONG FIX, and that is the part worth
# writing down: that list's header says every entry was MEASURED to break, so an entry that does not
# break destroys the one thing the list tells its next reader.
#
# AND THE LIST MAY BE PARTLY INERT, which is recorded here as an OBSERVATION and not as a claim about
# all 46 entries. Control run 2026-08-25: `exec` was removed from `declaredBindings`, the toolchain
# rebuilt, and the bundle for `[exec](std/process.ssc)` came out BYTE-IDENTICAL — no `const exec`
# appeared, so something other than this list is suppressing it. The emitter does still emit these
# bindings in general: `[htmlEscape](std/html.ssc)` produces `const htmlEscape = std.html.htmlEscape`
# at top level in the same build. Whether each of the other 45 entries is still load-bearing is one
# rebuild per name and has NOT been measured — do not read this note as permission to trim the list.
# SELF-TEST FIRST, because this check now DECIDES with `node --check` instead of with a name list,
# and a predicate that cannot say NO turns the whole gate into a pass generator. Two bundles are
# written by hand: one with the duplicate top-level declaration the gate exists to catch, one
# without. The predicate must reject the first and accept the second, or this script stops here.
#
# It is here rather than in a control run because a one-off control does not survive the next edit,
# and this exact gate was red for three days on a rule nobody re-measured.
printf 'function dup(x) { return x; }\nconst dup = 1;\n'      > "$sandbox/selftest-bad.cjs"
printf 'function dup(x) { return x; }\nconst other = 1;\n'    > "$sandbox/selftest-good.cjs"
if node --check "$sandbox/selftest-bad.cjs" 2>/dev/null; then
  echo "  ✗ SELF-TEST: node --check ACCEPTED a duplicate top-level declaration — this gate cannot fail" >&2
  exit 1
fi
if ! node --check "$sandbox/selftest-good.cjs" 2>/dev/null; then
  echo "  ✗ SELF-TEST: node --check REJECTED a clean bundle — every name would read as a collision" >&2
  exit 1
fi
echo "  ✓ self-test: the collision predicate rejects a duplicate declaration and accepts a clean one"

collide=0
benign=0
while IFS=$'\t' read -r cname cmodule; do
  [[ -n "$cname" ]] || continue
  printf '[%s](%s)\n\ndef main(): Unit = println("x")\n' "$cname" "$cmodule" > "$sandbox/c.ssc"
  if ! timeout 600 "$tools" emit-js "$sandbox/c.ssc" > "$sandbox/c.cjs" 2>/dev/null; then
    printf '      %-22s %s — emit-js FAILED, which is its own defect\n' "$cname" "$cmodule"
    collide=$((collide + 1)); continue
  fi
  if cerr=$(node --check "$sandbox/c.cjs" 2>&1); then
    printf '      %-22s %s — overlaps, does NOT collide (bundle parses)\n' "$cname" "$cmodule"
    benign=$((benign + 1))
  else
    printf '      %-22s %s — COLLIDES: %s\n' "$cname" "$cmodule" "$(printf '%s' "$cerr" | head -1)"
    printf '        add it to JsGen.declaredBindings, or the import is a SyntaxError for every user\n'
    collide=$((collide + 1))
  fi
done < "$sandbox/missing.tsv"

if [[ $collide -gt 0 ]]; then
  echo "  ✗ $collide undeclared name(s) really do break the bundle"
  fails=$((fails + 1))
elif [[ $benign -gt 0 ]]; then
  echo "  ✓ $benign undeclared overlap(s), none of them a collision"
fi

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
