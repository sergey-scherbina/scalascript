#!/usr/bin/env bash
#
# claim-scope-hierarchy.sh — the level-aware scope rules, asserted in BOTH directions.
#
# A gate that only shows the new permission working proves nothing about whether the mutex still
# holds. Every case below therefore comes in pairs: one that MUST be admitted and one that MUST be
# refused for the same shape.
#
# Runs entirely against a throwaway bare repo; touches nothing real.
set -euo pipefail
LAB=$(mktemp -d "${TMPDIR:-/tmp}/claim-scope-test.XXXXXX")
trap 'rm -rf "$LAB"' EXIT HUP INT TERM
G=(-c user.email=test@example.com -c user.name=test -c commit.gpgsign=false)
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fail=0

check() { # check <label> <expected> <got>
  if [ "$2" = "$3" ]; then printf 'PASS  %s\n' "$1"
  else printf 'FAIL  %s\n        expected: %s\n        got:      %s\n' "$1" "$2" "$3"; fail=1; fi
}

# ── unit level: the two pure functions, exercised directly ────────────────────────────────────────
lvl() { sed -n 's/^scope_level() {$/&/p' >/dev/null; :; }
probe() { # probe <scope> -> "<level> <path>"
  bash -c '
    . /dev/stdin <<EOF
$(sed -n "/^scope_level() {/,/^}/p;/^scope_path() {/,/^}/p" "'"$ROOT"'/.githooks/pre-push")
EOF
    printf "%s %s" "$(scope_level "'"$1"'")" "$(scope_path "'"$1"'")"'
}
check "level: file:"        "file v2/lib/a.ssc0" "$(probe 'file:v2/lib/a.ssc0')"
check "level: mod:"         "mod v2/lib"         "$(probe 'mod:v2/lib')"
check "level: repo:"        "repo "              "$(probe 'repo:')"
check "level: unprefixed"   "mod v2/lib"         "$(probe 'v2/lib')"
check "level: glob stripped" "mod v2/lib"        "$(probe 'mod:v2/lib/**')"
check "level: trailing slash" "mod v2/lib"       "$(probe 'mod:v2/lib/')"

# ── behaviour level: does a push get admitted or refused? ─────────────────────────────────────────
# One bare origin, an existing broad claim, then a second agent claiming a file inside it.
setup() { # setup <owner-scopes> <owner-touches-file|no>
  rm -rf "$LAB/o.git" "$LAB/A" "$LAB/B"; mkdir -p "$LAB"
  git init -q --bare -b main "$LAB/o.git"
  git clone -q "$LAB/o.git" "$LAB/A" 2>/dev/null; cd "$LAB/A"
  git symbolic-ref HEAD refs/heads/main
  mkdir -p .work/active v2/lib
  printf '# generation: 1\n#slug\tagent\tstarted\titems\tpaths\n' > .work/active/LEDGER.tsv
  printf 'x\n' > v2/lib/a.ssc0; printf 'y\n' > v2/lib/b.ssc0
  git add -A; git "${G[@]}" commit -qm init; git push -q origin main
  sleep 1; OWNER_TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"; sleep 1
  # owner claim
  printf 'slug: owner\nagent: t\nstarted: $OWNER_TS\nitems: i1\npaths: %s\n' "$1" \
    > .work/active/owner.claim
  printf 'owner\tt\t%s\ti1\t%s\n' "$OWNER_TS" "$1" >> .work/active/LEDGER.tsv
  [ "$2" = "touch" ] && printf 'owner-edit\n' >> v2/lib/a.ssc0
  git add -A; git "${G[@]}" commit -qm "claim: owner"; git push -q origin main
  cd "$LAB"
}
# The hook needs `origin/main` and the owner worktree convention; this lab cannot provide the
# worktree, so `owner_holds_file` is exercised through its FAIL-CLOSED branch here, and the
# declared/undeclared distinction is what this level asserts.
verdict() { # verdict <new-scope> -> admitted|refused
  cd "$LAB/A"
  printf 'slug: second\nagent: t\nstarted: 2100-01-01T00:00:00Z\nitems: i2\npaths: %s\n' "$1" \
    > .work/active/second.claim
  printf 'second\tt\t2100-01-01T00:00:00Z\ti2\t%s\n' "$1" >> .work/active/LEDGER.tsv
  git add -A >/dev/null; git "${G[@]}" commit -qm "claim: second" >/dev/null
  if git "${G[@]}" -c core.hooksPath="$ROOT/.githooks" push -q origin main 2>/dev/null; then
    printf 'admitted'
  else printf 'refused'; fi
  cd "$LAB"
}

setup 'file:v2/lib/a.ssc0' no
check "declared file vs same file → refused" refused "$(verdict 'file:v2/lib/a.ssc0')"

setup 'file:v2/lib/a.ssc0' no
check "declared file vs OTHER file → admitted" admitted "$(verdict 'file:v2/lib/b.ssc0')"

setup 'repo:' no
check "repo-level owner blocks a file" refused "$(verdict 'file:v2/lib/a.ssc0')"

setup 'mod:v2/lib' no
check "module owner vs module → refused" refused "$(verdict 'mod:v2/lib')"


# ── THE decisive pair: a file inside someone's MODULE ─────────────────────────────────────────────
# This is the permission the change exists for, so it is asserted in both directions. `owner_holds_file`
# looks for the owner's worktree next to the repo, so the lab builds a real one.
owner_wt() { # owner_wt <touch|no>
  rm -rf "$LAB/A-wt-owner"
  git -C "$LAB/A" worktree add -q -f -b owner-branch "$LAB/A-wt-owner" HEAD 2>/dev/null
  if [ "$1" = touch ]; then
    printf 'owner-edit\n' >> "$LAB/A-wt-owner/v2/lib/a.ssc0"
  fi
}

setup 'mod:v2/lib' no; owner_wt no
check "file inside a module the owner has NOT touched → ADMITTED" admitted "$(verdict 'file:v2/lib/a.ssc0')"

setup 'mod:v2/lib' no; owner_wt touch
check "file inside a module the owner HAS touched → refused" refused "$(verdict 'file:v2/lib/a.ssc0')"

setup 'mod:v2/lib' no; rm -rf "$LAB/A-wt-owner"
check "owner worktree ABSENT → fail-closed, refused" refused "$(verdict 'file:v2/lib/a.ssc0')"


# ── module bookkeeping files are SHARED, like the root ones ──────────────────────────────────────
# Regression guard for the per-module split: before 2026-07-30 `is_shared_bookkeeping` compared bare
# basenames against the whole scope, so only root SPRINT/BACKLOG/… were exempt and every module
# board became claimable — i.e. serialised. Asserted in both directions so the exemption cannot
# quietly swallow real paths.
setup 'file:scripts/BACKLOG.md' no
check "module BACKLOG.md is shared → admitted" admitted "$(verdict 'file:scripts/BACKLOG.md')"

setup 'file:v2/SPRINT.md' no
check "module SPRINT.md is shared → admitted" admitted "$(verdict 'file:v2/SPRINT.md')"

setup 'file:v2/lib/a.ssc0' no
check "a NON-bookkeeping file is still exclusive" refused "$(verdict 'file:v2/lib/a.ssc0')"

setup 'file:scripts/BACKLOG.md' no
check "same-name file elsewhere is not confused for a real path" admitted "$(verdict 'file:tests/BACKLOG.md')"

# ── the vocabulary is CLOSED, and coord-claim is where a typo has to die ──────────────────────────
# An entry outside the vocabulary cannot be distinguished from a real scope once it reaches the
# guards, and it fails in BOTH directions depending on the typo. Measured 2026-08-06 against
# scope_level/scope_path above:
#
#   dir:a/b  flie:a/b   level=mod, path "dir:a/b" — a path no file matches, so the scope is EMPTY:
#                       it reads as a claim to a human and protects nothing.
#   mod:  file:  mod:/  path EMPTY — and containment is `case $p_path in "$q_path"*`, so it matches
#                       EVERYTHING and conflicts with every other claim. Blocks the queue.
#   repo:x              level repo, path ignored — a typo claims the whole repository.
#
# So the assertion is on coord-claim's refusal, not on the guards' reading of a malformed scope:
# by the time the guards see it the information is already gone. Both directions, because a
# validator that refuses everything would pass a one-sided test.
# (scripts/BUGS.md coord-claim-accepts-an-unknown-path-prefix-and-both-guards-read-it-as-nothing.)
scope_accepted() { # scope_accepted <scope> -> admitted|refused (by the VOCABULARY, not by anything else)
  # Matched on the MESSAGE, not the exit code. `mod:` and `repo:` scopes also exit 2, from the
  # --broad requirement, and the first version of this helper read that as a vocabulary refusal and
  # failed two legal forms. Two different refusals sharing one exit code is exactly the ambiguity
  # this gate is here to catch, so it must not be reintroduced by the test itself.
  local err
  err="$("$ROOT/scripts/coord-claim" _vocab_probe --items x --paths "$1" 2>&1 >/dev/null)" || true
  case "$err" in
    *"not scopes the guards understand"*) printf 'refused' ;;
    *)                                    printf 'admitted' ;;
  esac
}
for bad in 'dir:a/b' 'flie:a/b' 'mod:' 'file:' 'repo:x'; do
  check "malformed scope '$bad' is refused at claim time" refused "$(scope_accepted "$bad")"
done
for good in 'file:a/b' 'mod:a/b' 'repo:' 'a/b'; do
  check "legal scope '$good' still admitted" admitted "$(scope_accepted "$good")"
done

if [ "$fail" -ne 0 ]; then printf '\nclaim-scope-hierarchy: FAIL\n' >&2; exit 1; fi
printf 'claim-scope-hierarchy: PASS\n'
