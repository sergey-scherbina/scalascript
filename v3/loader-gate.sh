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
  LOADER="v3/src/Loader.scala"
  GOOD='List(stdRoot + target, target, dir + "/" + target, target.substring("std/".length))'
  grep -qF "$GOOD" "$LOADER" || { echo "self-test: the candidate line is not where it was — refusing to patch blind"; exit 2; }
  cp "$LOADER" "$LOADER.selftest-bak"
  restore() { mv -f "$LOADER.selftest-bak" "$LOADER"; }
  trap restore EXIT INT TERM
  st_fail=0
  plant() {
    local name="$1" bad="$2"
    cp "$LOADER.selftest-bak" "$LOADER"
    python3 - "$LOADER" "$GOOD" "$bad" <<'PY'
import sys
p, good, bad = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(p).read()
assert s.count(good) == 1, "expected exactly one candidate line, found %d" % s.count(good)
open(p, "w").write(s.replace(good, bad))
PY
    if "$0" >/dev/null 2>&1; then
      printf '  %-6s planted defect NOT caught: %s\n' "FAIL" "$name"; st_fail=1
    else
      printf '  %-6s planted defect caught: %s\n' "ok" "$name"
    fi
  }
  echo "── v3 loader gate self-test ───────────────────────────────────────────────"
  plant "the strip candidate removed (the pre-migration resolver)" \
        'List(stdRoot + target, target, dir + "/" + target)'
  plant "the strip candidate tried FIRST (shadows every real std/ module)" \
        'List(target.substring("std/".length), stdRoot + target, target, dir + "/" + target)'
  restore; trap - EXIT INT TERM
  "$0" >/dev/null 2>&1 || { printf '  %-6s the gate does not pass on the restored tree\n' "FAIL"; st_fail=1; }
  echo
  [ "$st_fail" = 0 ] && echo "== v3 loader gate self-test: GREEN (2/2 caught) ==" || echo "== v3 loader gate self-test: RED =="
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
}

# Run from INSIDE the tree: every candidate but the first is relative to the working directory, so
# the working directory is part of what is being tested.
run_probe() { ( cd "$1" && "$SSC3" exec main.ssc 2>&1 | tail -1 ); }

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

echo
[ "$fail" = 0 ] && echo "== v3 loader gate: GREEN ==" || echo "== v3 loader gate: RED =="
[ "$fail" = 0 ]
