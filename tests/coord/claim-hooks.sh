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

# Overridable so the suite can be pointed at a PREVIOUS version of the hooks. That is not a
# convenience: a new case is only a gate if it fails against the code it was written for, and
# `HOOKS_SRC=/tmp/old-hooks bash tests/coord/claim-hooks.sh` is how that gets checked.
HOOKS_SRC=${HOOKS_SRC:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.githooks" && pwd)}
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
  # The live claim also declares SPRINT.md/BUGS.md — the realistic case, since nearly every claim
  # lists the board files. Before the exemption this made the board unclaimable by anyone else.
  printf 'slug: scljet-ipk\nitems: C1 C2\npaths: scljet/ tests/conformance/scljet- SPRINT.md BUGS.md\n' \
    > .work/active/scljet-ipk.claim
  # The ledger row MIRRORS the claim file. It used to be left empty here, which quietly made every
  # case a test of the .claim copy alone — the very asymmetry `claim-ledger-claimfile-scope-drift`
  # is about. `scripts/coord-claim` always writes both, so this is also the realistic state.
  printf '# generation: 1\n#slug\tagent\tstarted\titems\tpaths\n' > .work/active/LEDGER.tsv
  printf 'scljet-ipk\ttest\t2026-01-01T00:00:00Z\tC1 C2\tscljet/ tests/conformance/scljet- SPRINT.md BUGS.md\n' \
    >> .work/active/LEDGER.tsv
  : > scljet/sql.ssc; : > README.md
  git add -A; git "${G[@]}" commit -qm init --no-verify; git push -q origin main
  git fetch -q origin
}

# Try to push a NEW claim; echo allow|refuse.
try_claim() { # try_claim <slug> <items> <paths>
  cd "$LAB/w/main"
  printf 'slug: %s\nitems: %s\npaths: %s\n' "$1" "$2" "$3" > ".work/active/$1.claim"
  printf '%s\ttest\t2026-01-01T00:00:00Z\t%s\t%s\n' "$1" "$2" "$3" >> .work/active/LEDGER.tsv
  git add ".work/active/$1.claim" .work/active/LEDGER.tsv >/dev/null
  git "${G[@]}" commit -qm "claim: $1" >/dev/null
  if git push -q origin main 2>/dev/null; then echo allow; else echo refuse; fi
}

# Push a claim whose two copies DISAGREE — the hole this suite exists to close.
# <ledger-paths> is what the LEDGER row says; <claim-paths> is what the .claim says.
try_claim_drift() { # try_claim_drift <slug> <items> <claim-paths> <ledger-paths>
  cd "$LAB/w/main"
  printf 'slug: %s\nitems: %s\npaths: %s\n' "$1" "$2" "$3" > ".work/active/$1.claim"
  printf '%s\ttest\t2026-01-01T00:00:00Z\t%s\t%s\n' "$1" "$2" "$4" >> .work/active/LEDGER.tsv
  git add ".work/active/$1.claim" .work/active/LEDGER.tsv >/dev/null
  git "${G[@]}" commit -qm "claim: $1" >/dev/null
  if git push -q origin main 2>/dev/null; then echo allow; else echo refuse; fi
}

# Same, for the items: field — items are what the guard actually keys the work on, so a drift there
# is the more dangerous of the two.
try_claim_drift_items() { # try_claim_drift_items <slug> <claim-items> <ledger-items> <paths>
  cd "$LAB/w/main"
  printf 'slug: %s\nitems: %s\npaths: %s\n' "$1" "$2" "$4" > ".work/active/$1.claim"
  printf '%s\ttest\t2026-01-01T00:00:00Z\t%s\t%s\n' "$1" "$3" "$4" >> .work/active/LEDGER.tsv
  git add ".work/active/$1.claim" .work/active/LEDGER.tsv >/dev/null
  git "${G[@]}" commit -qm "claim: $1" >/dev/null
  if git push -q origin main 2>/dev/null; then echo allow; else echo refuse; fi
}

# Make the LIVE claim on origin/main inconsistent, then try to claim <paths> against it.
# Which copy carries the extra scope is the parameter: both directions must be caught, because a
# guard that reads only one copy passes whichever test matches the copy it happens to read.
try_claim_vs_drifted_live() { # try_claim_vs_drifted_live <where:claim|ledger> <extra-path> <rival-paths>
  cd "$LAB/w/main"
  if [ "$1" = claim ]; then
    printf 'slug: scljet-ipk\nitems: C1 C2\npaths: scljet/ %s\n' "$2" > .work/active/scljet-ipk.claim
  else
    printf '# generation: 1\n#slug\tagent\tstarted\titems\tpaths\n' > .work/active/LEDGER.tsv
    printf 'scljet-ipk\ttest\t2026-01-01T00:00:00Z\tC1 C2\tscljet/ %s\n' "$2" >> .work/active/LEDGER.tsv
  fi
  git add -A >/dev/null; git "${G[@]}" commit -qm "drift the live claim" --no-verify >/dev/null
  git push -q origin main --no-verify 2>/dev/null || true
  git fetch -q origin
  try_claim rival Z9 "$3"
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
# The shared board files are NOT work. Layer 3 always allowed them; layer 2 refused a claim that
# merely named one, so a finished task could not be ticked off — on 2026-07-27 four completed items
# sat open on the board because of exactly this. The pair below is the point: the board is shared,
# and real work is still guarded.
setup
check "allows a claim that also names the shared board files" \
      "allow" "$(try_claim board-tick 'Z3' 'SPRINT.md BUGS.md CHANGELOG.md')"
setup
check "allows shared board files ALONGSIDE a disjoint work path" \
      "allow" "$(try_claim v2-lane 'Z4' 'v2/ SPRINT.md')"
setup
check "still refuses real work even when the board files are also named" \
      "refuse" "$(try_claim sneaky 'Z5' 'SPRINT.md scljet/sql.ssc')"

# ── ledger/.claim consistency (BUGS.md claim-ledger-claimfile-scope-drift) ──────────────────────
# Scope is stored twice and nothing checked that the copies agree, so a path present in only one of
# them was a hole a rival could claim through. Observed for real at `0fade8820`, and again on
# 2026-07-27 with a live claim whose .claim said `v1/runtime/**` while its ledger row said
# `v1/runtime/backend/interpreter/**`.
setup
check "refuses a push whose own .claim and ledger row disagree on paths" \
      "refuse" "$(try_claim_drift drifty 'Z6' 'v2/ specs/' 'v2/')"
setup
check "refuses a push whose own .claim and ledger row disagree on items" \
      "refuse" "$(try_claim_drift_items drifty2 'Z7 Z7b' 'Z7' 'v2/')"
setup
check "allows a push whose two copies agree (the guard is not just always-refuse)" \
      "allow" "$(try_claim consistent 'Z8' 'v2/')"
# The union rule, both directions. Either copy alone would pass one of these and fail the other.
setup
check "a live claim's LEDGER-only path still blocks a rival (union, ledger side)" \
      "refuse" "$(try_claim_vs_drifted_live ledger 'tests/conformance/corpus-baseline.tsv' 'tests/conformance/corpus-baseline.tsv')"
setup
check "a live claim's CLAIM-only path still blocks a rival (union, claim side)" \
      "refuse" "$(try_claim_vs_drifted_live claim 'v1/runtime/' 'v1/runtime/backend/js/')"

# ── glob expansion (same fail-open family) ─────────────────────────────────────────────────────
# `for p in $paths` is unquoted, so a claim path written `foo/**` was expanded against the working
# tree and became only the directories that exist TODAY. A rival claiming a sibling path under the
# same root then slipped through. `set -f` in the hook is what this asserts.
setup
mkdir -p "$LAB/w/main/broad/alpha" "$LAB/w/main/broad/beta"
: > "$LAB/w/main/broad/alpha/f.txt"
cd "$LAB/w/main"; git add -A >/dev/null; git "${G[@]}" commit -qm "dirs" --no-verify >/dev/null
printf 'slug: broad-lane\nitems: W1\npaths: broad/**\n' > .work/active/broad-lane.claim
printf 'broad-lane\ttest\t2026-01-01T00:00:00Z\tW1\tbroad/**\n' >> .work/active/LEDGER.tsv
git add -A >/dev/null; git "${G[@]}" commit -qm "claim: broad-lane" --no-verify >/dev/null
git push -q origin main --no-verify 2>/dev/null || true; git fetch -q origin
check "a 'broad/**' claim still covers a path that does not exist on disk yet" \
      "refuse" "$(try_claim newcomer 'W2' 'broad/gamma/')"

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
# The per-module split (specs/work-tracking-layout.md) moved bookkeeping OUT of the root, and both
# hook layers exempt it by name. Layer 2 was fixed on 2026-07-30 to match the basename; layer 3 was
# not, so `v2/BUGS.md` was shared at push time and exclusive at commit time — and every case above
# uses a ROOT file, so the whole suite was blind to it. These two are the module-level twins.
setup
check "allows a MODULE board (v2/BUGS.md), not just the root one" \
      "allow" "$(try_commit scljet-ipk v2/BUGS.md)"
setup
check "allows a nested module board (tests/conformance/SPRINT.md)" \
      "allow" "$(try_commit scljet-ipk tests/conformance/SPRINT.md)"
# …and the exemption must stay a whitelist, not "any path ending in .md".
setup
check "a non-bookkeeping file in the same directory is still refused" \
      "refuse" "$(try_commit scljet-ipk v2/NOTES.md)"
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
