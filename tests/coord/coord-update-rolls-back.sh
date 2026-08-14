#!/usr/bin/env bash
#
# coord-update-rolls-back.sh — `scripts/coord-update` edits a claim in ONE commit and, when the push
# is refused, leaves the shared checkout exactly as it found it.
#
# THE DEFECT IT OWNS (scripts/BUGS.md `hand-made-claim-updates-have-no-tool-and-so-no-rollback`).
# Before this tool a heartbeat, a scope widening or a rewritten `next:` was hand-edited and
# hand-committed, several times a day, by every agent — and a refused push left the commit parked on
# a checkout five agents share. `.githooks/pre-push` validates every claim in
# `remote_tip..local_tip`, so a parked claim-update is refused for a STRANGER and blocks their next
# claim until somebody finds it.
#
# Follows `coord-release-refuses-unpushed-work.sh`: a lab with a fake origin, running the REAL
# script. `COORD_UPDATE` points it at another copy — aim it at a version with the rollback removed
# and case 4 fails, which is the point of case 4.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TOOL="${COORD_UPDATE:-$ROOT/scripts/coord-update}"
LAB=$(mktemp -d "${TMPDIR:-/tmp}/coord-update.XXXXXX")
trap 'rm -rf "$LAB"' EXIT HUP INT TERM
G=(-c user.email=test@example.com -c user.name=test -c commit.gpgsign=false)
fail=0

check() {
  if [ "$2" = "$3" ]; then printf 'PASS  %s\n' "$1"
  else printf 'FAIL  %s\n        expected=%s\n        got=%s\n' "$1" "$2" "$3"; fail=1; fi
}
contains() {
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
# A CI runner has no global git identity and the real script commits, so the lab repo carries one —
# the `-c` array above only covers the commits this file makes itself. Without it the tool exits 128
# with "Author identity unknown" and every case reports that instead of what it asserts.
git config user.email test@example.com
git config user.name test
git config commit.gpgsign false
# The tool calls `$root/scripts/coord-ledger`, so the lab needs the REAL one: the ledger is derived,
# and a stub here would let a case pass against a tool that never regenerated the row.
mkdir -p .work/active scripts
cp "$ROOT/scripts/coord-ledger" scripts/coord-ledger
chmod +x scripts/coord-ledger
printf '# generation: 1\n#slug\tagent\tstarted\titems\tpaths\n' > .work/active/LEDGER.tsv
git add -A; git "${G[@]}" commit -qm init --no-verify >/dev/null; git push -q origin main
git fetch -q origin

seed_claim() {  # seed_claim <slug> <paths>
  { printf 'slug: %s\nagent: labtest\nstarted: 2026-08-15T00:00:00Z\n' "$1"
    printf 'heartbeat: 2026-08-15T00:00:00Z\nstatus: in-progress\nitems: I1\npaths: %s\n' "$2"
    printf 'next: the first step\n'
  } > ".work/active/$1.claim"
  scripts/coord-ledger --write >/dev/null
  git add -A; git "${G[@]}" commit -qm "seed $1" --no-verify >/dev/null
  git push -q origin main; git fetch -q origin
}

# ── 1. it edits the claim, regenerates the ledger, and commits ONCE ───────────
seed_claim alpha "file:a.txt"
before_count=$(git rev-list --count HEAD)
out=$(bash "$TOOL" alpha --heartbeat --next "the second step" 2>&1) && rc=0 || rc=$?
check "an ordinary update exits 0" 0 "$rc"
[ "$rc" -eq 0 ] || printf '        ─ output ─\n%s\n' "$out" | sed 's/^/        /'
check "it is ONE commit, not two" 1 "$(( $(git rev-list --count HEAD) - before_count ))"
contains "the claim carries the new next:" "next: the second step" "$(cat .work/active/alpha.claim)"
# The heartbeat must have MOVED, not merely be present — a setter that appended a second line, or
# matched nothing, would leave the seeded value and pass a `grep heartbeat`.
check "the heartbeat moved off the seeded value" 0 \
      "$(grep -c '^heartbeat: 2026-08-15T00:00:00Z$' .work/active/alpha.claim || true)"
check "there is exactly ONE heartbeat line" 1 "$(grep -c '^heartbeat:' .work/active/alpha.claim)"
# `.work/` only: `.githooks/pre-commit` refuses anything else in the shared checkout.
check "the commit touches only .work/" "" \
      "$(git show --name-only --format= HEAD | grep -v '^\.work/' | tr '\n' ' ' | sed 's/ *$//')"
check "the tree is clean afterwards" "" "$(git status --porcelain)"

# ── 2. a widening reaches the LEDGER too, not just the claim ──────────────────
# The ledger is a derived copy; an update that edited only the claim would leave the mutex reading
# the OLD scope, which is `claim-ledger-claimfile-scope-drift` — the exact defect duplicated state
# produces here.
out=$(bash "$TOOL" alpha --paths "file:a.txt file:b.txt" 2>&1) && rc=0 || rc=$?
check "widening --paths exits 0" 0 "$rc"
contains "the claim has the wider scope" "file:b.txt" "$(cat .work/active/alpha.claim)"
contains "and so does the LEDGER row"    "file:b.txt" "$(grep '^alpha	' .work/active/LEDGER.tsv)"

# ── 3. nothing to change is not a commit ──────────────────────────────────────
# A claim-update that changes nothing is noise in a log everybody reads, and it is also a push for
# no reason — which is a chance to lose a race for no reason.
before_count=$(git rev-list --count HEAD)
cur_next="$(sed -n 's/^next: //p' .work/active/alpha.claim | head -1)"
out=$(bash "$TOOL" alpha --next "$cur_next" 2>&1) && rc=0 || rc=$?
check "a no-op update exits 0" 0 "$rc"
[ "$rc" -eq 0 ] || printf '        ─ output ─\n%s\n        (next was: %s)\n' "$out" "$cur_next" | sed 's/^/        /' 
check "and writes NO commit" 0 "$(( $(git rev-list --count HEAD) - before_count ))"

# ── 4. THE ANTI-CONSTANT CASE: a refused push must leave nothing behind ───────
#
# This is the case that fails against a tool without the rollback, and it is why the whole file
# exists. The push is refused with a `pre-push` hook — the real refusals are the pre-push CLAIM
# guard and a non-fast-forward, and both arrive the same way: a non-zero push after a local commit.
seed_claim beta "file:c.txt"
printf '#!/bin/sh\nexit 1\n' > .git/hooks/pre-push; chmod +x .git/hooks/pre-push
before_sha=$(git rev-parse HEAD)
before_claim=$(cat .work/active/beta.claim)
before_ledger=$(cat .work/active/LEDGER.tsv)
out=$(bash "$TOOL" beta --status blocked --next "will not land" 2>&1) && rc=0 || rc=$?
rm -f .git/hooks/pre-push

check "a refused push does not report success" 1 "$([ "$rc" -ne 0 ] && echo 1 || echo 0)"
check "HEAD is back where it was — no parked commit" "$before_sha" "$(git rev-parse HEAD)"
porcelain="$(git status --porcelain)"
check "the checkout is left CLEAN for every other agent" "" "$porcelain"
[ -z "$porcelain" ] || printf '        ─ left behind ─\n%s\n' "$porcelain" | sed 's/^/        /'
check "the claim file is byte-for-byte what it was" "$before_claim" "$(cat .work/active/beta.claim)"
check "the ledger is byte-for-byte what it was"     "$before_ledger" "$(cat .work/active/LEDGER.tsv)"
contains "and it says which refusal it was" "push refused" "$out$(printf '\n')"

# THE CONTROL FOR THE CONTROL: with the hook gone the same update lands. Without this, case 4 would
# pass against a tool that refused everything.
out=$(bash "$TOOL" beta --status blocked --next "will not land" 2>&1) && rc=0 || rc=$?
check "the same update lands once the push can succeed" 0 "$rc"
[ "$rc" -eq 0 ] || printf '        ─ output ─\n%s\n' "$out" | sed 's/^/        /'
contains "and the status is the new one" "status: blocked" "$(cat .work/active/beta.claim)"

# ── 5. it refuses a slug that has no claim, and an update with no edits ───────
out=$(bash "$TOOL" nosuchslug --heartbeat 2>&1) && rc=0 || rc=$?
check "an unknown slug is refused" 2 "$rc"
contains "and names the file it looked for" ".work/active/nosuchslug.claim" "$out"
out=$(bash "$TOOL" alpha 2>&1) && rc=0 || rc=$?
check "an update with no edit flags is refused" 2 "$rc"
out=$(bash "$TOOL" alpha --bogus x 2>&1) && rc=0 || rc=$?
check "an unknown flag is refused rather than ignored" 2 "$rc"
contains "and names the offending flag" "--bogus" "$out"

if [ "$fail" -ne 0 ]; then echo "coord-update-rolls-back: FAIL"; exit 1; fi
echo "coord-update-rolls-back: OK"
