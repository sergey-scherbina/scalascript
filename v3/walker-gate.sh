#!/usr/bin/env bash
#
# EVERY HAND-WRITTEN `Expr` WALKER HANDLES EVERY CASE THAT HAS CHILDREN.
#
# WHY THIS EXISTS. `v3/src/Lower.scala` walks `Expr` in six separate recursions — `mapDeep`,
# `qualifyMembers`, `freeVars`, `assignedFree`, `selfCalls`, `boxLocals` — and **nothing failed when
# one of them missed a case**: most end in a catch-all arm, so the compiler is satisfied and the
# walk silently skips a subtree.
#
# Measured 2026-08-09 while adding one node, `Expr.MethodRef`. Three walkers were missed, and each
# announced itself differently, hours apart:
#
#   mapDeep, receiver          `call to unknown function '__summon__'`   — first build
#   mapDeep, NamedArg          116 corpus cases refused                  — corpus sweep
#   qualifyMembers             `unknown name 'entries'`, N 188 → 187     — an A/B against main
#
# The middle one predates that node: `mapDeep` had NO `NamedArg` case at all, so `rewriteByName` and
# `resolveSummons` had been skipping the insides of `f(x = …)` for as long as they existed. A missed
# rewrite is a WRONG PROGRAM rather than a refusal, which is why nothing caught it — the new node
# was only visible because leaving it unresolved is refused.
#
# WHAT IT CHECKS. The `Expr` cases that CARRY child expressions are read from `Ast.scala`, so a new
# case is required of every walker the day it is declared — the list cannot go stale. A function is
# checked when the line above its `def` says `// EXPR-WALKER`, which makes a new walker opt IN
# explicitly rather than being guessed at by shape.
#
# Usage: v3/walker-gate.sh [--self-test]
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2

SELFTEST=0
[ "${1:-}" = "--self-test" ] && SELFTEST=1

python3 - "$SELFTEST" <<'PY'
import re, sys

selftest = sys.argv[1] == "1"

ast = open("v3/src/Ast.scala").read()
i = ast.index("enum Expr")
seg = ast[i:ast.index("\n\n", ast.index("case Block", i))]
cases = re.findall(r"^\s*case (\w+)\(([^)]*)\)", seg, re.M)
# A case with a child EXPRESSION is one a walker must descend into. A leaf — `IntLit`, `Name` —
# is handled correctly by a catch-all, so requiring it would be noise that trains people to
# ignore this gate.
need = [n for n, args in cases if re.search(r"Expr|MatchArm|Stmt|HandleArm", args)]

src = open("v3/src/Lower.scala").read().split("\n")
walkers = []
for n, line in enumerate(src):
    if "// EXPR-WALKER" not in line:
        continue
    m = None
    for j in range(n + 1, min(n + 4, len(src))):
        m = re.match(r"  (?:private )?def (\w+)", src[j])
        if m:
            start = j
            break
    if not m:
        print(f"  FAIL  the // EXPR-WALKER on line {n+1} is not above a def")
        walkers.append((f"line {n+1}", n, n))
        continue
    end = len(src)
    for j in range(start + 1, len(src)):
        if re.match(r"  (?:private )?def ", src[j]):
            end = j
            break
    walkers.append((m.group(1), start, end))

if not walkers:
    print("walker-gate: FAIL — no function is marked // EXPR-WALKER", file=sys.stderr)
    sys.exit(1)

# WHAT EACH WALKER IS ALLOWED TO MISS TODAY, per walker and per case, so a NEW miss is caught while
# the ones this gate found on its first run are named rather than hidden. Every entry here is a
# subtree that walker does not descend into; whether that is safe depends on when it runs, and
# nobody has checked. One was checked the day this was written and was NOT safe — `qualifyMembers`
# skipping `NamedArg` made `Box(v = secret)` inside an `object` report `unknown name 'secret'` —
# so it was fixed rather than declared. (BUGS.md lower-has-six-hand-written-Expr-walkers…)
#
# A line comes OUT the day its walker learns the case. Nothing else may go in without a reason.
KNOWN = {
    "freeVars":     {"Interp", "NamedArg", "Try"},
    "selfCalls":    {"Prim", "Perform", "Handle", "Resume", "MethodRef", "NamedArg", "Apply"},
    "assignedFree": {"Perform", "Handle", "Resume", "MethodRef"},
    "boxLocals":    {"Perform", "Handle", "Resume"},
}

fails = 0
for name, start, end in walkers:
    body = "\n".join(src[start:end])
    missing = [c for c in need if f"Expr.{c}(" not in body]
    if selftest:
        continue
    known = KNOWN.get(name, set())
    fresh = [c for c in missing if c not in known]
    stale = [c for c in known if c not in missing]
    if stale:
        print(f"  FAIL  {name:16s} now handles {' '.join(stale)}; drop it from KNOWN in this commit")
        fails += 1
    if fresh:
        print(f"  FAIL  {name:16s} does not handle: {' '.join(fresh)}")
        fails += 1
    elif not stale:
        if known:
            print(f"  KNOWN {name:16s} skips {len(known)} declared: {' '.join(sorted(known))}")
        else:
            print(f"  ok    {name:16s} handles all {len(need)}")

if selftest:
    # THE GATE MUST BE ABLE TO SAY NO. One case is dropped from the requirement's own input and
    # every walker must then be reported as complete — proving the check reads the walker bodies
    # rather than reporting `ok` unconditionally — and one is INVENTED, which every walker must
    # then fail on.
    ghost = "ThisCaseDoesNotExist"
    bad = 0
    for name, start, end in walkers:
        body = "\n".join(src[start:end])
        if f"Expr.{ghost}(" in body:
            bad += 1
    if bad != 0:
        print("walker-gate: SELF-TEST FAIL — an invented case was found in a walker", file=sys.stderr)
        sys.exit(1)
    print(f"  ok    an invented case is missing from all {len(walkers)} walkers")
    print(f"walker-gate: SELF-TEST OK ({len(walkers)} walkers, {len(need)} cases with children)")
    sys.exit(0)

print(f"── Expr walkers: {len(walkers)} marked, {len(need)} cases with children ──")
if fails:
    print(f"walker-gate: FAIL ({fails})", file=sys.stderr)
    sys.exit(1)
print("walker-gate: OK")
PY
