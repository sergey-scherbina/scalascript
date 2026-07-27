#!/usr/bin/env bash
#
# claim-mutex layer 1: does the shared ledger actually make concurrent claims collide?
#
# This asserts BOTH directions, because a test that only shows the fix passing proves nothing about
# whether it was needed (specs/claim-mutex.md §Verification):
#
#   CONTROL  — the pre-2026-07-27 scheme (each claim writes its own disjoint file): the loser of the
#              push race must rebase CLEANLY, i.e. never learn a rival claim exists. This is the bug.
#   MUTEX    — with `.work/active/LEDGER.tsv` and its `# generation: N` header that every claim
#              bumps: the loser's rebase must CONFLICT, forcing it to read the winner's claim.
#
# Runs entirely in a throwaway directory against a local bare repo — touches nothing real.
set -euo pipefail

LAB=$(mktemp -d "${TMPDIR:-/tmp}/claim-mutex-test.XXXXXX")
trap 'rm -rf "$LAB"' EXIT HUP INT TERM
G=(-c user.email=test@example.com -c user.name=test -c commit.gpgsign=false)
fail=0

note() { printf '  %s\n' "$*"; }
check() { # check <label> <expected> <got>
  if [ "$2" = "$3" ]; then
    printf 'PASS  %s\n' "$1"
  else
    printf 'FAIL  %s\n        expected=%s\n        got=%s\n' "$1" "$2" "$3"
    fail=1
  fi
}

# Race two clones from the same base. Echoes what happened to the LOSER: "pushed" (no race),
# "clean" (rebased without noticing) or "conflict" (forced to look).
race() { # race <setup-fn>
  local setup="$1" lab="$LAB/$2"
  rm -rf "$lab"; mkdir -p "$lab"; cd "$lab"
  git init -q --bare -b main origin.git
  git clone -q origin.git seed 2>/dev/null
  cd seed; git symbolic-ref HEAD refs/heads/main; mkdir -p .work/active
  "$setup" seed
  git add -A; git "${G[@]}" commit -qm init; git push -q origin main
  cd "$lab"; git clone -q origin.git A 2>/dev/null; git clone -q origin.git B 2>/dev/null

  cd "$lab/A"; "$setup" alpha; git add -A; git "${G[@]}" commit -qm "claim: alpha"
  git push -q origin main
  cd "$lab/B"; "$setup" beta;  git add -A; git "${G[@]}" commit -qm "claim: beta"

  if git push -q origin main 2>/dev/null; then echo "pushed"; return; fi
  git fetch -q origin
  if git rebase origin/main >/dev/null 2>&1; then echo "clean"
  else git rebase --abort >/dev/null 2>&1 || true; echo "conflict"; fi
}

# ── CONTROL: one disjoint file per claim, no ledger ────────────────────────────
control_setup() { [ "$1" = "seed" ] && { touch .work/active/_placeholder; return; }; printf 'slug: %s\n' "$1" > ".work/active/$1.claim"; }

# ── MUTEX: the same, plus the shared ledger with a bumped generation ───────────
mutex_setup() {
  if [ "$1" = "seed" ]; then
    printf '# generation: 1\n#slug\tagent\tstarted\titems\tpaths\n' > .work/active/LEDGER.tsv
    return
  fi
  printf 'slug: %s\n' "$1" > ".work/active/$1.claim"
  python3 - "$1" <<'PY'
import sys, re
p = ".work/active/LEDGER.tsv"
lines = [l for l in open(p).read().split("\n") if l.strip() != ""]
gen = int(re.search(r"# generation: (\d+)", lines[0]).group(1))
lines[0] = f"# generation: {gen + 1}"
lines.append(f"{sys.argv[1]}\ttest\t2026-01-01T00:00:00Z\t{sys.argv[1]}\tsrc/")
open(p, "w").write("\n".join(lines) + "\n")
PY
}

echo "claim-mutex layer 1 — do concurrent claims collide?"
note "CONTROL (no ledger): the loser must rebase CLEAN — that is the defect being fixed"
check "control: loser never sees the rival claim" "clean" "$(race control_setup control)"
note "MUTEX (ledger + generation): the loser must hit a CONFLICT"
check "mutex: loser is forced to see the rival claim" "conflict" "$(race mutex_setup mutex)"

exit $fail
