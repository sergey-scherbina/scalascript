#!/usr/bin/env bash
#
# coord-release-refuses-unpushed-work.sh — `scripts/coord-release` must not announce work that is
# not on `origin/main`, run for real in a throwaway repo.
#
# The defect this pins, observed 2026-08-07 on `uniml-corpus-floor-independent-oracle`:
#
#   $ git -C "$WT" push origin HEAD:main        # rejected, non-fast-forward: main moved
#   $ scripts/coord-release uniml-corpus-... --level 3 --note "Landed. ..."
#   ✓ released
#   $ git worktree remove --force "$WT"; git branch -D feature/...
#
# Three separate steps on three separate lines, so the rejected push stopped nothing. The claim came
# off the board, the branch was deleted, and the commit survived only as a dangling object — while
# the release record on `origin/main` said "Landed". Recovered with `git fsck --lost-found`. Commit
# messages cannot be edited, so the false record is permanent; that is what makes this worth a guard
# rather than a note to self.
#
# The caller-side fix is `&&` or `set -e` and it is correct, but it is the caller's to remember, the
# failure is silent, and the damage lands on the board every other agent reads.
#
# BOTH DIRECTIONS ARE CHECKED, and the second is the one that matters here: a guard on the release
# path can break every agent's release, so this file asserts that an ORDINARY release still works,
# that a claim with no branch field (the old single-line form) still releases, and that a claim whose
# branch is already deleted still releases. A refusal that fires on everything is not a fix.
#
# Follows `coord-release-evidence-level.sh`: a lab with a fake origin, running the REAL script.
# `COORD_RELEASE` points it at another copy — aim it at the pre-guard version and cases 1 and 2 fail.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TOOL="${COORD_RELEASE:-$ROOT/scripts/coord-release}"
LAB=$(mktemp -d "${TMPDIR:-/tmp}/coord-release-unpushed.XXXXXX")
trap 'rm -rf "$LAB"' EXIT HUP INT TERM
G=(-c user.email=test@example.com -c user.name=test -c commit.gpgsign=false)
fail=0

check() {
  if [ "$2" = "$3" ]; then printf 'PASS  %s\n' "$1"
  else printf 'FAIL  %s\n        expected=%s\n        got=%s\n' "$1" "$2" "$3"; fail=1; fi
}
contains() {  # contains <name> <needle> <haystack>
  case "$3" in
    *"$2"*) printf 'PASS  %s\n' "$1" ;;
    *) printf 'FAIL  %s\n        expected to contain=%s\n        got=%s\n' "$1" "$2" "$3"; fail=1 ;;
  esac
}

cd "$LAB"
git init -q --bare -b main origin.git
git clone -q origin.git main 2>/dev/null
cd main
git symbolic-ref HEAD refs/heads/main
mkdir -p .work/active
printf '# generation: 1\n#slug\tagent\tstarted\titems\tpaths\n' > .work/active/LEDGER.tsv
git add -A; git "${G[@]}" commit -qm init --no-verify >/dev/null; git push -q origin main
git fetch -q origin

# seed_claim <slug> [branch]  — writes the claim and its ledger row, pushed, on main.
seed_claim() {
  local slug="$1" branch="${2:-}"
  { printf 'slug: %s\nagent: labtest\nstatus: in-progress\n' "$slug"
    [ -n "$branch" ] && printf 'branch: %s\n' "$branch"
  } > ".work/active/$slug.claim"
  printf '%s\tlabtest\t2026-08-07T00:00:00Z\tI1\tfile:x\n' "$slug" >> .work/active/LEDGER.tsv
  git add -A; git "${G[@]}" commit -qm "seed $slug" --no-verify >/dev/null
  git push -q origin main
  git fetch -q origin
}

# make_branch <name> <pushed|unpushed> — a branch with one commit on it.
make_branch() {
  local name="$1" mode="$2"
  git branch -q "$name" main
  git "${G[@]}" -c advice.detachedHead=false checkout -q "$name"
  echo "work" > "work-${name//\//-}.txt"   # a branch name has a slash in it; a filename must not
  git add -A; git "${G[@]}" commit -qm "work on $name" --no-verify >/dev/null
  [ "$mode" = pushed ] && { git push -q origin "$name:main"; }
  git checkout -q main
  git fetch -q origin
  git merge --ff-only -q origin/main
}

# ── 1. THE DEFECT: a branch with commits origin/main does not have ────────────
seed_claim unpushed feature/unpushed
make_branch feature/unpushed unpushed
out=$(bash "$TOOL" unpushed --level 3 --note "claims to have landed" 2>&1) && rc=0 || rc=$?
check "an unpushed branch is refused" 2 "$rc"
contains "the refusal names the branch"      "feature/unpushed" "$out"
contains "the refusal counts the commits"    "1 commit"         "$out"
contains "the refusal says how to fix it"    "Push first"       "$out"
check "a refused release leaves the claim in place" \
      yes "$([ -f .work/active/unpushed.claim ] && echo yes || echo no)"
check "a refused release leaves the ledger row in place" \
      1 "$(grep -c '^unpushed	' .work/active/LEDGER.tsv || true)"
# No release commit may have been written either — a tool that refuses AFTER committing has already
# done the damage the refusal exists to prevent.
check "a refused release writes no commit" \
      "seed unpushed" "$(git log -1 --format=%s)"

# ── 2. the deliberate escape hatch, so an abandoned branch is still releasable ─
out=$(COORD_RELEASE_ALLOW_UNPUSHED=1 bash "$TOOL" unpushed --level 3 --note "abandoning" 2>&1) && rc=0 || rc=$?
check "COORD_RELEASE_ALLOW_UNPUSHED releases anyway" 0 "$rc"
contains "and says so rather than passing silently" "COORD_RELEASE_ALLOW_UNPUSHED set" "$out"
check "the claim is gone" no "$([ -f .work/active/unpushed.claim ] && echo yes || echo no)"

# ── 3. THE CONTROL: an ordinary, fully-pushed release still works ─────────────
# Without this the guard could refuse everything and cases 1 and 2 would still be green.
seed_claim pushedok feature/pushedok
make_branch feature/pushedok pushed
out=$(bash "$TOOL" pushedok --level 3 --note "really landed" 2>&1) && rc=0 || rc=$?
check "a pushed branch releases normally" 0 "$rc"
[ "$rc" -eq 0 ] || printf '        ─ output ─\n%s\n' "$out" | sed 's/^/        /'
contains "the level still reaches the message" "[evidence: level 3]" "$(git log -1 --format=%s)"
check "the claim is gone"     no "$([ -f .work/active/pushedok.claim ] && echo yes || echo no)"
check "the ledger row is gone" 0 "$(grep -c '^pushedok	' .work/active/LEDGER.tsv || true)"

# ── 4. backward compatibility: the old single-line claim has no `branch:` ──────
seed_claim nobranch
out=$(bash "$TOOL" nobranch --level 3 --note "old-form claim" 2>&1) && rc=0 || rc=$?
check "a claim with no branch still releases" 0 "$rc"
contains "but the tool says the check could not run" "names no branch" "$out"

# ── 5. the branch is already deleted — ambiguous, so a note and not a refusal ──
# Merged-and-deleted is the ordinary order; refusing here would break it.
seed_claim goneb feature/goneb
out=$(bash "$TOOL" goneb --level 3 --note "branch already tidied" 2>&1) && rc=0 || rc=$?
check "a claim naming a deleted branch still releases" 0 "$rc"
contains "and the silence is named"  "is gone locally" "$out"

if [ "$fail" -ne 0 ]; then echo "coord-release-refuses-unpushed-work: FAIL"; exit 1; fi
echo "coord-release-refuses-unpushed-work: OK"
