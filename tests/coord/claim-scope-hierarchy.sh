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

if [ "$fail" -ne 0 ]; then printf '\nclaim-scope-hierarchy: FAIL\n' >&2; exit 1; fi
printf 'claim-scope-hierarchy: PASS\n'
