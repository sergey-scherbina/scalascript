#!/usr/bin/env bash
#
# coord-claim-runs.sh — `scripts/coord-claim` actually RUNS, end to end, in a throwaway repo.
#
# The gap this closes, stated plainly because it is the interesting part:
#
#   `tests/coord/board-claim-parity.sh` was written for the change that made `coord-claim` write
#   the board row, was proven red in both directions, and is GREEN against a `coord-claim` that
#   aborts on line 128 with `root: unbound variable`. It reads `.work/active/` and `SPRINT.md` and
#   compares them; it never executes the tool. So a `set -u` abort, a shell syntax error, or a
#   broken heredoc in the tool are covered by nothing.
#
#   Measured 2026-07-30: that abort made claiming impossible for every agent, and because it fires
#   AFTER `git add "$claim" "$ledger"`, each attempt left a half-written claim staged in the shared
#   main checkout. Two other agents' claims were sitting there.
#
# The author's own rule — "do not test claim tooling by making a claim" — is right, and a lab with a
# fake origin is how you obey it while still running the real script.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TOOL="${COORD_CLAIM:-$ROOT/scripts/coord-claim}"   # overridable: point it at an OLD copy to see it fail
LAB=$(mktemp -d "${TMPDIR:-/tmp}/coord-claim-runs.XXXXXX")
trap 'rm -rf "$LAB"' EXIT HUP INT TERM
G=(-c user.email=test@example.com -c user.name=test -c commit.gpgsign=false)
fail=0

check() {
  if [ "$2" = "$3" ]; then printf 'PASS  %s\n' "$1"
  else printf 'FAIL  %s\n        expected=%s\n        got=%s\n' "$1" "$2" "$3"; fail=1; fi
}

# A minimal but REALISTIC main checkout: bare origin, hooks installed, an empty ledger and a board
# with the `## In flight` table the row-writer looks for.
cd "$LAB"
git init -q --bare -b main origin.git
git clone -q origin.git main 2>/dev/null
cd main
git symbolic-ref HEAD refs/heads/main
git config core.hooksPath .githooks
mkdir -p .githooks .work/active v2
cp "$ROOT/.githooks/pre-commit" "$ROOT/.githooks/pre-push" .githooks/ 2>/dev/null || true
chmod +x .githooks/* 2>/dev/null || true
printf '# generation: 1\n#slug\tagent\tstarted\titems\tpaths\n' > .work/active/LEDGER.tsv
cat > SPRINT.md <<'EOF'
# Sprint board

## In flight

| task | module | claim | state | notes |
|---|---|---|---|---|

## How a task gets onto this board
EOF
: > v2/kernel.ssc
git add -A; git "${G[@]}" commit -qm init --no-verify >/dev/null; git push -q origin main
git fetch -q origin

# The real script, run for real. It resolves MAIN from `git worktree list`, so running it with cwd
# inside this lab checkout is exactly the production path.
out=$(SSC_AGENT_ID=labtest bash "$TOOL" lab-slug \
        --items "L1 L2" --paths "file:v2/kernel.ssc" 2>&1) && rc=0 || rc=$?

check "coord-claim exits 0" 0 "$rc"
[ "$rc" -eq 0 ] || printf '        ─ output ─\n%s\n' "$out" | sed 's/^/        /'

check "wrote the claim file" \
      yes "$([ -f .work/active/lab-slug.claim ] && echo yes || echo no)"
check "wrote the ledger row" \
      1 "$(grep -c '^lab-slug	' .work/active/LEDGER.tsv || true)"
check "bumped the ledger generation" \
      "# generation: 2" "$(head -1 .work/active/LEDGER.tsv)"
check "pushed to origin" \
      1 "$(git -C ../origin.git log --oneline -1 --format=%s | grep -c 'claim: lab-slug' || true)"

# The board row is NOT asserted here: it was implemented, blocked by the worktree guardrail in
# `.githooks/pre-commit`, and backed out again in `0c8237e60`. The guardrail now exempts `SPRINT.md`
# so the row can be re-landed; when it is, add the two assertions this file used to carry —
#   the commit touches SPRINT.md, and the row names the claim, and a second run does not duplicate it.
# Stating the absent coverage beats a commented-out assertion nobody reads.

# The guardrail exemption itself, since it is what unblocks the above. Staged in the MAIN checkout:
# SPRINT.md must pass and an ordinary file must still be refused, or the guard has stopped being a
# guard.
: > SPRINT.md.tmp && mv SPRINT.md.tmp SPRINT.md.bak && cp SPRINT.md.bak SPRINT.md 2>/dev/null || true
rm -f SPRINT.md.bak
printf '\n<!-- board tick -->\n' >> SPRINT.md
git add SPRINT.md
git "${G[@]}" commit -qm "board: tick" >/dev/null 2>&1 && r1=allow || r1=refuse
check "the shared main checkout ACCEPTS a SPRINT.md-only commit (coord-claim's row)" allow "$r1"

printf 'x\n' >> v2/kernel.ssc
git add v2/kernel.ssc
git "${G[@]}" commit -qm "feature work in main" >/dev/null 2>&1 && r2=allow || r2=refuse
check "the shared main checkout still REFUSES ordinary feature work" refuse "$r2"
git reset -q --hard HEAD >/dev/null

echo
[ "$fail" -eq 0 ] && echo "coord-claim-runs: PASS" || echo "coord-claim-runs: FAIL"
exit $fail
