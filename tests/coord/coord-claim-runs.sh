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
# The TOOL UNDER TEST runs its own `git commit`, so the lab repo must carry an identity —
# the `-c user.email=…` array above only covers the commits this file makes itself. A CI
# runner has no global identity and cannot derive one from user@host the way a dev box
# does, so without this the real script exits 128 with "Author identity unknown" and the
# case reports whatever it was actually asserting. Reproduce that here with:
#   GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_COUNT=1 \
#   GIT_CONFIG_KEY_0=user.useConfigOnly GIT_CONFIG_VALUE_0=true bash <this file>
git config user.email test@example.com
git config user.name test
git config commit.gpgsign false
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

# An UNQUOTED multi-value list must be refused, not silently renamed. `--items` takes one argument,
# so `--items A B` leaves `B` as a positional; the catch-all used to assign it over the slug and the
# tool exited 0, printing `✓ claimed B`. Measured 2026-07-30 — a real claim landed under a name the
# caller never typed, and the only clue was that printed slug.
#
# Run it in a subshell with its own cwd so a stray success cannot disturb the checks above.
un_out=$(SSC_AGENT_ID=labtest bash "$TOOL" wanted-slug \
           --items I1 I2 --paths "file:v2/kernel.ssc" 2>&1) && un_rc=0 || un_rc=$?

# ── a REFUSED claim must leave the shared checkout exactly as it found it ─────
#
# The failure this pins, measured 2026-07-31: when the push is refused, `coord-claim` used to keep
# its commit. In the SHARED main checkout that commit outlives the agent who made it — the next one
# to `git push origin main` either carries a stranger's claim onto main or is refused for an overlap
# that is not theirs. Four occurrences in two days. The tool printed "reset this claim and redo it"
# as advice, and advice is not a mechanism.
#
# A bare origin with a pre-push hook that always refuses is the cheapest way to produce a refusal
# that is not a race: any push fails, so what is asserted is purely the rollback.
refuse_lab=$(mktemp -d "${TMPDIR:-/tmp}/coord-claim-refuse.XXXXXX")
(
  cd "$refuse_lab"
  git init -q --bare -b main origin.git
  git clone -q origin.git main 2>/dev/null
  cd main
  git symbolic-ref HEAD refs/heads/main
  # The TOOL UNDER TEST runs its own `git commit`, so the lab repo must carry an identity —
  # the `-c user.email=…` array above only covers the commits this file makes itself. A CI
  # runner has no global identity and cannot derive one from user@host the way a dev box
  # does, so without this the real script exits 128 with "Author identity unknown" and the
  # case reports whatever it was actually asserting. Reproduce that here with:
  #   GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_COUNT=1 \
  #   GIT_CONFIG_KEY_0=user.useConfigOnly GIT_CONFIG_VALUE_0=true bash <this file>
  git config user.email test@example.com
  git config user.name test
  git config commit.gpgsign false
  mkdir -p .work/active
  printf '# generation: 1\n#slug\tagent\tstarted\titems\tpaths\n' > .work/active/LEDGER.tsv
  : > marker.txt
  git add -A; git -c user.email=t@e -c user.name=t commit -qm init --no-verify >/dev/null
  git push -q origin main
  # The refusing hook goes on AFTER the seed push. Installing it first meant `origin/main` never
  # existed, so `coord-claim` died at `git merge --ff-only origin/main` and never reached the commit
  # — and every rollback assertion below then passed against the OLD tool, for the wrong reason.
  # Caught by running this file against the pre-fix `coord-claim` and getting zero failures.
  cat > ../origin.git/hooks/pre-receive <<'HOOK'
#!/bin/sh
echo "refused by the lab hook" >&2
exit 1
HOOK
  chmod +x ../origin.git/hooks/pre-receive
) >/dev/null 2>&1
cd "$refuse_lab/main" 2>/dev/null || true
# An UNRELATED uncommitted change: the rollback must not touch it. A `reset --hard` would.
printf 'work in progress\n' > marker.txt
before_head="$(git rev-parse HEAD)"
SSC_AGENT_ID=labtest bash "$TOOL" refused-slug --items R1 --paths "file:marker.txt" >/dev/null 2>&1 || true
check "a refused claim leaves no commit behind" "$before_head" "$(git rev-parse HEAD)"
check "a refused claim leaves no claim file" \
      no "$([ -f .work/active/refused-slug.claim ] && echo yes || echo no)"
check "a refused claim leaves no ledger row" \
      0 "$(grep -c '^refused-slug\t' .work/active/LEDGER.tsv || true)"
check "the rollback does NOT touch unrelated uncommitted work" \
      "work in progress" "$(cat marker.txt)"
cd "$LAB/main"
rm -rf "$refuse_lab"

check "an unquoted multi-value --items is REFUSED, not silently renamed" \
      refused "$([ "$un_rc" -ne 0 ] && echo refused || echo accepted)"
check "it did NOT create a claim named after the stray word" \
      no "$([ -f .work/active/I2.claim ] && echo yes || echo no)"
check "it did NOT create the wanted claim either (nothing half-done)" \
      no "$([ -f .work/active/wanted-slug.claim ] && echo yes || echo no)"
check "the refusal explains the quoting" \
      yes "$(printf '%s' "$un_out" | grep -qi 'quoted' && echo yes || echo no)"
check "the refusal names BOTH candidates so the caller can see what happened" \
      yes "$(printf '%s' "$un_out" | grep -q 'wanted-slug' && printf '%s' "$un_out" | grep -q 'I2' && echo yes || echo no)"

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
