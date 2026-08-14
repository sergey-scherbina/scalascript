#!/usr/bin/env bash
# v3 — the import resolver keeps up with `std-to-repo-root`, and its candidate ORDER is preserved.
#
# WHY A GATE AT ALL. Every other v3 front check is an output differential: two fronts print the
# same AST, two lanes print the same value. A resolver has nothing to compare against — it either
# finds a file or does not, and both fronts share this one code path, so no differential can see
# it. That blindness is not hypothetical: `std-to-repo-root` (2026-08-09) moved the dev tree's
# modules to the repo root and promoted `scljet/` out of `std/`, and v3's loader did not follow.
# Nothing went red. The corpus simply refused 116 files — 69% of every refusal it produced — with a
# diagnostic that read like a missing module rather than a stale search path, and the front-diff
# and capability gates stayed GREEN throughout, because a file NEITHER front can load produces no
# output for either to disagree about.
#
# WHAT IT CHECKS. Not "does an import work" — that passes on a resolver with the rule bolted on
# FIRST, which would shadow every real `std/` module with a same-named file at the repo root. The
# gate builds a tree where the two candidates BOTH exist and hold DIFFERENT definitions, so the
# answer names which candidate won. Three states, and a gate that cannot distinguish them is not a
# gate:
#
#   std/probe.ssc + probe.ssc   -> "std-copy"   the `std/` candidate still wins; strip is LAST
#   probe.ssc only              -> "root-copy"  the promoted-directory spelling resolves
#   neither                     -> refusal, and the message still lists where it looked
#
# The third is as load-bearing as the other two. A resolver that answers everything is worse than
# one that answers nothing, and the candidate list in the diagnostic is what makes a stale search
# path readable as a stale search path — which is exactly what was missing for the 116.
#
# SELF-TEST: `--self-test` plants the defect this gate exists to catch (the strip candidate removed,
# and the strip candidate moved to the FRONT) and requires the gate to go RED for each. A gate
# nobody has watched fail is a hypothesis.
set -u
cd "$(dirname "$0")/.." || exit 1

say() { printf '  %-6s %s\n' "$1" "$2"; }
SSC3="$PWD/v3/ssc3"
fail=0

# ── self-test ─────────────────────────────────────────────────────────────────────────────────────
# Both defects are REAL shapes, not typos. The first is the state this gate was written against —
# the resolver as it stood before the migration was followed. The second is the fix applied
# CARELESSLY, which is the more interesting failure: it makes every check that only asks "does the
# import work" pass, while silently shadowing 64 real modules. A gate that catches only the first
# would have let the second through.
if [ "${1:-}" = "--self-test" ]; then
  # TWO FILES, because the loader's decisions are not all in `Loader.scala`. `Source.scalaImportPath`
  # is what decides whether a line IS an import at all, and section 5's subject lives there — a
  # `plant` that could only patch `Loader.scala` would have failed to find its anchor, and a
  # self-test that cannot plant its defect reports on a file it did not change.
  SELFTEST_FILES="v3/src/Loader.scala v3/src/Source.scala"
  for f in $SELFTEST_FILES; do cp "$f" "$f.selftest-bak"; done
  restore() { for f in $SELFTEST_FILES; do mv -f "$f.selftest-bak" "$f"; done; }
  trap restore EXIT INT TERM
  st_fail=0
  # `good` is checked for EXACTLY ONE occurrence before anything is written. A self-test that
  # patches blind reports on a file it did not change.
  #
  # The FILE is the first argument, and every planted defect is applied to a tree restored from the
  # backups first, so two plants in different files cannot accumulate.
  plant() {
    local file="$1" name="$2" good="$3" bad="$4"
    for f in $SELFTEST_FILES; do cp "$f.selftest-bak" "$f"; done
    python3 - "$file" "$good" "$bad" <<'PY' || { printf '  %-6s could not plant: %s\n' "FAIL" "$2"; exit 2; }
import sys
p, good, bad = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(p).read()
assert s.count(good) == 1, "expected exactly one occurrence of the anchor, found %d" % s.count(good)
open(p, "w").write(s.replace(good, bad))
PY
    if "$0" >/dev/null 2>&1; then
      printf '  %-6s planted defect NOT caught: %s\n' "FAIL" "$name"; st_fail=1
    else
      printf '  %-6s planted defect caught: %s\n' "ok" "$name"
    fi
  }
  echo "── v3 loader gate self-test ───────────────────────────────────────────────"
  LOADER="v3/src/Loader.scala"
  SOURCE="v3/src/Source.scala"
  CAND='List(stdRoot + target, target, dir + "/" + target, target.substring("std/".length))'
  plant "$LOADER" "the strip candidate removed (the pre-migration resolver)" "$CAND" \
        'List(stdRoot + target, target, dir + "/" + target)'
  plant "$LOADER" "the strip candidate tried FIRST (shadows every real std/ module)" "$CAND" \
        'List(target.substring("std/".length), stdRoot + target, target, dir + "/" + target)'
  # The rename switched off entirely — the state the tree was in before P-6 was fixed.
  plant "$LOADER" "the collision rename disabled (a unit calls another module's function)" \
        'if targets.isEmpty then units' 'if true then units'
  # THE MORE INTERESTING FAILURE, and the reason section 2's third check exists: the rename working
  # while the OWNER is chosen wrongly. Every "does my own module win" check still passes; what
  # changes is the answer a unit gets when it declares the name NOWHERE and simply calls it.
  plant "$LOADER" "the owner chosen as the LAST declarer instead of the first" \
        'else declarers(n).head' 'else declarers(n).last'
  # SSC3-14c, and it is in the OTHER file — see `SELFTEST_FILES` above. Section 5 asserts a selector
  # list resolves the same module as `.*`; this is the proof that it would notice if the
  # normalisation went away, which is the state the tree was in before this landed.
  plant "$SOURCE" "selector-list normalisation removed (a list is refused again)" \
        'if open < 0 || !p.endsWith("}") then p else p.substring(0, open) + ".*"' \
        'p'
  # THE CARELESS VERSION of the same fix, which is the more interesting failure here: normalising
  # WITHOUT requiring the list to be closed. Every "does a selector list work" check still passes;
  # what changes is that `import std.probe.{probeName` — half a line — is accepted as a path.
  plant "$SOURCE" "normalisation without the closing-brace guard (half a path is accepted)" \
        'if open < 0 || !p.endsWith("}") then p else p.substring(0, open) + ".*"' \
        'if open < 0 then p else p.substring(0, open) + ".*"'
  restore; trap - EXIT INT TERM
  "$0" >/dev/null 2>&1 || { printf '  %-6s the gate does not pass on the restored tree\n' "FAIL"; st_fail=1; }
  echo
  [ "$st_fail" = 0 ] && echo "== v3 loader gate self-test: GREEN (6/6 caught) ==" || echo "== v3 loader gate self-test: RED =="
  exit "$st_fail"
fi

# The probe tree is built fresh per run. It lives outside the repo so a stray `probe.ssc` can never
# be mistaken for a source file, and so the run has no opinion about the repo's own layout.
build_tree() {
  local t="$1"
  mkdir -p "$t/std"
  printf 'def probeName(): String = "std-copy"\n'  > "$t/std/probe.ssc"
  printf 'def probeName(): String = "root-copy"\n' > "$t/probe.ssc"
  printf '[probeName](std/probe.ssc)\n\ndef main(): Unit = println(probeName())\n' > "$t/main.ssc"
  # THE THREE SPELLINGS OF ONE IMPORT, each in a FENCED file because `Loader.imports` only reads a
  # Scala-style import inside a code fence — a bare `.ssc` never sets `inCode`, so its import line
  # is blanked and never resolved, and a probe written bare would pass without resolving anything.
  printf '# star\n\n```scalascript\nimport std.probe.*\ndef main(): Unit = println(probeName())\n```\n' \
    > "$t/star.ssc"
  printf '# sel\n\n```scalascript\nimport std.probe.{probeName, absentName}\ndef main(): Unit = println(probeName())\n```\n' \
    > "$t/sel.ssc"
  # A rename INSIDE the list. `std/mapreduce/shuffle.ssc:46` writes one, and the names are never
  # read, so it must cost nothing.
  printf '# selas\n\n```scalascript\nimport std.probe.{probeName, absentName as a}\ndef main(): Unit = println(probeName())\n```\n' \
    > "$t/selas.ssc"
  # NOT an import: the brace is unclosed. Here so a widening that accepts selector lists cannot
  # quietly start accepting half a path — the check below asserts this one still REFUSES.
  printf '# bad\n\n```scalascript\nimport std.probe.{probeName\ndef main(): Unit = println(probeName())\n```\n' \
    > "$t/bad.ssc"
}

# Run from INSIDE the tree: every candidate but the first is relative to the working directory, so
# the working directory is part of what is being tested.
run_probe() { ( cd "$1" && "$SSC3" exec main.ssc 2>&1 | tail -1 ); }

# The same, for a named file — the selector-list spellings each need their own.
run_file() { ( cd "$1" && "$SSC3" exec "$2" 2>&1 | tail -1 ); }

check() {
  local label="$1" want="$2" got="$3"
  if [ "$got" = "$want" ]; then
    say ok "$label -> $want"
  else
    say FAIL "$label: expected '$want', got '$got'"
    fail=1
  fi
}

echo "── v3 import resolver: promoted directories, and candidate order ──────────"

T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
build_tree "$T"

# 1. BOTH present. The `std/` copy must win. If the strip candidate were tried first — or anywhere
#    before the literal `std/` path — every one of the 64 real `std/*.ssc` modules would be
#    shadowed by any same-named file at the repo root, which is a silent WRONG ANSWER rather than a
#    refusal, and no test in the tree would notice.
check "both candidates present, std/ wins" "std-copy" "$(run_probe "$T")"

# 2. Only the promoted copy. This is `std/scljet/index.ssc` in miniature: the import keeps the
#    `std/` prefix it always had, the directory no longer lives under `std/`, and the resolver is
#    the one place that knows both spellings name one module.
rm -f "$T/std/probe.ssc"
check "promoted directory resolves" "root-copy" "$(run_probe "$T")"

# 3. Neither. Refusal, and the candidate list must survive — that list is the difference between a
#    reader editing their import and a reader editing the resolver.
rm -f "$T/probe.ssc"
out="$(run_probe "$T")"
case "$out" in
  *"cannot find the import 'std/probe.ssc'"*"looked in"*)
    say ok "absent module refuses, and the diagnostic still says where it looked" ;;
  *"cannot find the import"*)
    say FAIL "refused without naming the candidates: $out"; fail=1 ;;
  *)
    say FAIL "an absent module did not refuse: $out"; fail=1 ;;
esac

# 4. The corpus claim this was measured against. Not a count — counts go stale by construction and
#    this one is a moving target as Tier-0 gaps close. The claim is narrower and stays true: the
#    canonical spelling the corpus actually writes must resolve on the DEFAULT front.
canon="tests/conformance/scljet-address-write.ssc"
if [ -f "$canon" ]; then
  if "$SSC3" ast "$canon" uniml >/dev/null 2>&1; then
    say ok "the corpus spelling std/scljet/... loads on the default front"
  else
    say FAIL "the corpus spelling std/scljet/... does not load: $("$SSC3" ast "$canon" uniml 2>&1 | tail -1)"
    fail=1
  fi
else
  say note "$canon is absent, so the corpus spelling was not checked"
fi

# 5. A SELECTOR LIST NAMES THE WHOLE MODULE, and must therefore resolve to exactly what `.*` does.
#
# `import a.b.{X, Y}` is what people write and what three modules under `std/mapreduce/` write; it
# was refused, with a message arguing that a list "has no meaning here because an import brings the
# WHOLE module either way". The semantics in that sentence are right, so the list is now normalised
# to `.*` (`Source.selectorsToStar`) and the names are never read.
#
# CHECKED AS AN EQUALITY against the `.*` spelling rather than against a literal, because the point
# is that the two spellings cannot diverge — a check that only asserted `std-copy` would still pass
# if the list took a different resolution path that happened to land on the same file here.
build_tree "$T"
star="$(run_file "$T" star.ssc)"
check "the .* spelling resolves"                  "std-copy" "$star"
check "a selector list resolves the same module"  "$star"    "$(run_file "$T" sel.ssc)"
check "a rename INSIDE the list costs nothing"    "$star"    "$(run_file "$T" selas.ssc)"

# THE NEGATIVE HALF, and without it this section would pass on a scanner that accepts anything
# beginning `import`. An unclosed brace is not a path and must still be refused BY NAME.
bad="$(run_file "$T" bad.ssc)"
case "$bad" in
  *"an \`import\` line must be"*)
    say ok "an unclosed selector list still refuses, by name" ;;
  *) say FAIL "an unclosed selector list was accepted: $bad"; fail=1 ;;
esac

echo
echo "── v3 module graph: WHICH declaration a call means ───────────────────────"
#
# THE LOADER'S SECOND JOB. Resolution above answers "which FILE"; this answers "which DECLARATION",
# and until 2026-08-11 nothing asked. `Loader.merge` concatenates every unit's `def`s into one flat
# table and `Lower` resolves a call with `fns.indexOf` — the FIRST match — so two modules declaring
# one name meant the loser's own calls went to the winner's function. Real in this tree:
# `std/scljet/mutate.ssc` declares `filterRows(rows, drop)`, `std/scljet/sql.ssc` declares
# `filterRows(rows, where, colNames)`, and `sql.ssc` imports `mutate.ssc`.
#
# THE PROBE TREE IS A CHAIN, m1 <- m2 <- m3 <- main, because the three interesting positions are
# different: m1 and m2 each declare `shared` at a different arity and each call their own; m3
# declares it NOWHERE and calls it, which is the case the rename must NOT move; and the root sits
# above all of them.
#
# `tag` is the same shape at the SAME ARITY and it is the more serious half. An arity collision
# refuses and says so; a same-arity one runs the wrong function and prints a plausible answer, which
# is a DIFF rather than an UNSUPPORTED, and no arity check anywhere can see it.
M="$(mktemp -d)"
trap 'rm -rf "$T" "$M"' EXIT

# `rec` is the colliding name called RECURSIVELY: a declaration is renamed and its own recursive
# call is a reference like any other, so it must move with it. `Holder`/`NS` put the call inside a
# class method and an object member, which are separate rewrite sites from a plain `def` body — and
# a class method is the one that must NOT be rewritten by name, since `Lower.selfCalls` reads an
# unqualified call to a SIBLING method as `this.m(…)`. `m2Local` takes a PARAMETER named `shared`:
# renaming that would redirect a call to a local, which is P-1's defect wearing a different hat.
cat > "$M/m1.ssc" <<'EOF'
def shared(a: Int): Int = a * 100
def tag(x: Int): String = "m1"
def rec(n: Int): Int = if n <= 0 then 0 else rec(n - 1) + 1

def m1Uses(): Int = shared(2)
def m1Tag(): String = tag(0)
def m1Rec(): Int = rec(3)
EOF

cat > "$M/m2.ssc" <<'EOF'
[m1](./m1.ssc)

def shared(a: Int, b: Int): Int = a + b
def tag(x: Int): String = "m2"
def rec(n: Int, acc: Int): Int = if n <= 0 then acc else rec(n - 1, acc + 10)

def m2Uses(): Int = shared(3, 4)
def m2Tag(): String = tag(0)
def m2Rec(): Int = rec(3, 0)

def m2Local(shared: Int): Int = shared + 1

case class Holder(v: Int):
  def viaMethod(): Int = shared(3, 4)

object NS:
  def viaObject(): Int = shared(5, 6)
EOF

cat > "$M/m3.ssc" <<'EOF'
[m2](./m2.ssc)

def m3Uses(): Int = shared(5)
EOF

cat > "$M/main.ssc" <<'EOF'
[m3](./m3.ssc)

def main(): Unit =
  println(m1Uses())
  println(m2Uses())
  println(m3Uses())
  println(m1Tag())
  println(m2Tag())
  println(m2Local(9))
  println(m1Rec())
  println(m2Rec())
  println(Holder(0).viaMethod())
  println(NS.viaObject())
EOF

want="200
7
500
m1
m2
10
3
30
7
11"
got="$(SSC3_PRELUDE= "$SSC3" exec "$M/main.ssc" 2>&1 | tail -10)"
if [ "$got" = "$want" ]; then
  say ok "each module calls its own declaration; a module that declares none keeps the first"
else
  say FAIL "collision rename: expected [$(echo "$want" | tr '\n' ' ')], got [$(echo "$got" | tr '\n' ' ')]"
  fail=1
fi

# BOTH LANES, because a rename that happens in the loader must be invisible to the choice of lane —
# invariant I-3, and the merge is upstream of both.
gotb="$(SSC3_PRELUDE= "$SSC3" run --bridge "$M/main.ssc" 2>&1 | tail -10)"
if [ "$gotb" = "$want" ]; then
  say ok "the bridge lane agrees — the rename is upstream of the lane split"
else
  say FAIL "bridge lane disagrees: [$(echo "$gotb" | tr '\n' ' ')]"
  fail=1
fi

# P-4, WHICH THIS MUST NOT UNDO. A name the ROOT declares still displaces every module's, and the
# root's own call still reaches the root's own function. The two rules are asked in order and the
# second must not reopen the first.
cat > "$M/rootwins.ssc" <<'EOF'
[m3](./m3.ssc)

def shared(a: Int): Int = 42

def main(): Unit = println(shared(1))
EOF
rw="$(SSC3_PRELUDE= "$SSC3" exec "$M/rootwins.ssc" 2>&1 | tail -1)"
check "the root's own def still wins over every module's (P-4)" "42" "$rw"

echo
[ "$fail" = 0 ] && echo "== v3 loader gate: GREEN ==" || echo "== v3 loader gate: RED =="
[ "$fail" = 0 ]
