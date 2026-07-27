#!/usr/bin/env bash
#
# claim-mutex layers 2 and 3: do the hooks actually REFUSE?
#
# A hook that has only ever been observed allowing things is not a gate (AGENTS.md §"measurement
# apparatus must COMPARE, never PRE-JUDGE"). Every case below asserts a specific verdict, and the
# refuse-cases come first because they are the ones that can silently rot into no-ops.
#
# Runs against a throwaway bare repo with the REAL hooks copied in — touches nothing in this repo.
set -euo pipefail

HOOKS_SRC=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.githooks" && pwd)
LAB=$(mktemp -d "${TMPDIR:-/tmp}/claim-hooks-test.XXXXXX")
trap 'rm -rf "$LAB"' EXIT HUP INT TERM
G=(-c user.email=test@example.com -c user.name=test -c commit.gpgsign=false)
fail=0

check() { # check <label> <expected> <got>
  if [ "$2" = "$3" ]; then printf 'PASS  %s\n' "$1"
  else printf 'FAIL  %s\n        expected=%s\n        got=%s\n' "$1" "$2" "$3"; fail=1; fi
}

# A fresh origin + main checkout with one live claim already on origin/main.
setup() {
  rm -rf "$LAB/w"; mkdir -p "$LAB/w"; cd "$LAB/w"
  git init -q --bare -b main origin.git
  git clone -q origin.git main 2>/dev/null; cd main
  git symbolic-ref HEAD refs/heads/main
  git config core.hooksPath .githooks
  mkdir -p .githooks .work/active scljet specs
  cp "$HOOKS_SRC/pre-commit" "$HOOKS_SRC/pre-push" .githooks/
  chmod +x .githooks/*
  printf 'slug: scljet-ipk\nitems: C1 C2\npaths: scljet/ tests/conformance/scljet-\n' \
    > .work/active/scljet-ipk.claim
  printf '# generation: 1\n#slug\tagent\tstarted\titems\tpaths\n' > .work/active/LEDGER.tsv
  : > scljet/sql.ssc; : > README.md
  git add -A; git "${G[@]}" commit -qm init --no-verify; git push -q origin main
  git fetch -q origin
}

# Try to push a NEW claim; echo allow|refuse.
try_claim() { # try_claim <slug> <items> <paths>
  cd "$LAB/w/main"
  printf 'slug: %s\nitems: %s\npaths: %s\n' "$1" "$2" "$3" > ".work/active/$1.claim"
  git add ".work/active/$1.claim" >/dev/null
  git "${G[@]}" commit -qm "claim: $1" >/dev/null
  if git push -q origin main 2>/dev/null; then echo allow; else echo refuse; fi
}

# Try to commit <file> in a feature worktree for <slug>; echo allow|refuse.
try_commit() { # try_commit <slug> <file>
  local wt="$LAB/w/wt-$1-$(echo "$2" | tr '/.' '__')"
  git -C "$LAB/w/main" worktree add -q "$wt" -b "feature/$1" origin/main 2>/dev/null
  mkdir -p "$(dirname "$wt/$2")"; echo change >> "$wt/$2"
  git -C "$wt" add "$2" >/dev/null
  if git -C "$wt" "${G[@]}" commit -qm "work on $2" >/dev/null 2>&1; then echo allow; else echo refuse; fi
}

# A claim whose paths are written with a trailing glob — the shape people actually type.
# Takes the file to commit, so the pair can assert BOTH directions: an inside path is allowed
# (normalisation works) and an outside one is refused (the claim was actually parsed and enforced).
# The allow-half alone could not tell "normalisation works" from "the guard is switched off".
try_commit_globclaim() { # try_commit_globclaim <file>
  cd "$LAB/w/main"
  printf 'slug: glob-claim\nitems: G1\npaths: scljet/**\n' > .work/active/glob-claim.claim
  git add -A >/dev/null; git "${G[@]}" commit -qm "claim: glob-claim" --no-verify >/dev/null
  git push -q origin main --no-verify 2>/dev/null || true
  git fetch -q origin
  try_commit glob-claim "$1"
}

echo "claim-mutex layer 2 — pre-push claim-overlap guard"
setup
check "refuses a same-work claim under a DIFFERENT slug (collision A)" \
      "refuse" "$(try_claim board-reconcile 'C1 C9' 'docs/')"
setup
check "refuses a claim whose paths fall inside a live claim's" \
      "refuse" "$(try_claim other-lane 'Z1' 'scljet/sql.ssc')"
setup
check "allows a genuinely disjoint claim" \
      "allow" "$(try_claim v2-typed-locals 'B1 B2' 'v2/')"
setup
check "allows a DELIBERATE verify-* overlap" \
      "allow" "$(try_claim verify-scljet-ipk 'C1 C2' 'scljet/')"
setup
check "normalises a trailing glob: 'scljet/**' still overlaps 'scljet/'" \
      "refuse" "$(try_claim glob-lane 'Z2' 'scljet/**')"

echo
echo "claim-mutex layer 3 — pre-commit claim-scope guard"
setup
check "refuses a staged path outside the claim's declared paths (collision B)" \
      "refuse" "$(try_commit scljet-ipk v2/kernel.ssc)"
setup
check "allows a staged path inside them" \
      "allow" "$(try_commit scljet-ipk scljet/sql.ssc)"
setup
check "allows the shared bookkeeping set" \
      "allow" "$(try_commit scljet-ipk CHANGELOG.md)"
setup
check "allows a branch with no claim at all (backward compatible)" \
      "allow" "$(try_commit unclaimed-lane v2/kernel.ssc)"
setup
check "normalises a trailing glob in the commit guard: inside is allowed" \
      "allow" "$(try_commit_globclaim scljet/sql.ssc)"
setup
check "normalises a trailing glob in the commit guard: outside is still refused" \
      "refuse" "$(try_commit_globclaim v2/kernel.ssc)"

exit $fail
