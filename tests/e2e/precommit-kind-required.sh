#!/usr/bin/env bash
# The pre-commit hook must refuse an entry it ADDS without `kind:` — and must NOT refuse a commit
# that merely touches a board already full of kindless entries.
#
# ── WHY THE SECOND HALF IS THE IMPORTANT ONE ──────────────────────────────────────────────────────
#
# `kind:` became required 2026-08-16. Within hours three entries landed without one and each turned
# `bugs-index-gate` red on main for EVERY agent, because that gate runs inside smoke-ci and smoke-ci
# is the push path. Moving the check to commit time is the obvious fix.
#
# The obvious fix is also how you break the repo. **779 of 1052 entries carried no kind when the
# field was introduced**, and `classify-the-50-kindless` is still working through them. A hook that
# demanded `kind:` from every entry in a staged board would refuse nearly every commit that touches
# one. So the rule is "entries this commit ADDS", and the row that proves it is `legacy-untouched`:
# a board with a kindless entry already in it, edited elsewhere, must COMMIT CLEAN.
#
# A gate holding only the refusal would pass just as well on a hook that refuses everything.
#
# ── AND WHY IT RUNS THE REAL HOOK ─────────────────────────────────────────────────────────────────
#
# Against a throwaway git repo with `core.hooksPath` pointed at the repo's own `.githooks`, so what
# is asserted is the file that will actually run — not a copy of its logic. A self-test that
# re-implements the check cannot fail when the check is wrong.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HOOKS="$ROOT/.githooks"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/ssc-kindhook.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
pass=0; fail=0

[[ -f "$HOOKS/pre-commit" ]] || { echo "precommit-kind-required: no $HOOKS/pre-commit" >&2; exit 2; }

# A sandbox that uses the REAL hook, laid out the way the repo itself is: a base checkout plus a
# WORKTREE on a feature branch. Not cosmetic — the hook's first rule refuses any non-coordination
# commit made where `.git` is a directory or the branch is `main`, which is every plain `git init`.
# A sandbox that ignored that would fail every row for the wrong reason, and the fixture would be
# testing the shared-checkout guard instead of the kind check. (It did, on the first run; the
# "refused, but not by this check" arm below is what said so.)
#
# `specs/bugs-index.md` is copied in because the hook reads the allowed values from it.
setup_repo() {
  rm -rf "$WORK/base" "$WORK/repo"; mkdir -p "$WORK/base/specs"
  git -C "$WORK/base" init -q -b main
  git -C "$WORK/base" config core.hooksPath "$HOOKS"
  git -C "$WORK/base" config user.email "gate@example.com"
  git -C "$WORK/base" config user.name  "gate"
  cp "$ROOT/specs/bugs-index.md" "$WORK/base/specs/bugs-index.md"
  printf '%s\n' "$1" > "$WORK/base/BUGS.md"
  git -C "$WORK/base" add -A
  git -C "$WORK/base" commit -q --no-verify -m "base" 2>/dev/null
  git -C "$WORK/base" worktree add -q -b feature/gate "$WORK/repo" main 2>/dev/null
}

# try_commit <file-content-after-edit>  -> prints the hook's exit code
try_commit() {
  printf '%s\n' "$1" > "$WORK/repo/BUGS.md"
  git -C "$WORK/repo" add BUGS.md
  git -C "$WORK/repo" commit -q -m "add an entry" > "$WORK/out.log" 2>&1
  echo $?
}

# BIG ON PURPOSE. The first version of this fixture was a few hundred bytes and passed 4/4 against a
# hook that reported real entries as kindless: the check was `printf … | grep -q`, and SIGPIPE only
# fires when `printf` still has bytes to write when `grep` exits. On a 300-byte board it never
# blocks; on the repo's ~800 KB `tests/BUGS.md` it does every time. A fixture smaller than a pipe
# buffer cannot see that class of bug at all — so the padding below is the gate, not decoration.
PAD=""
for i in $(seq 1 400); do
  PAD="$PAD
## filler-entry-$i — padding so the staged board exceeds a pipe buffer

<!-- status: open
     kind: bug
     lane: apparatus
     area: docs -->

Body text repeated to give this file real size, because a fixture that fits in one write cannot
reproduce a SIGPIPE the real board triggers every time.
"
done

WITH_KIND='## base-entry — a starting point

<!-- status: open
     kind: bug
     lane: apparatus
     area: docs -->

Body.'"$PAD"

LEGACY_KINDLESS='## legacy-entry — filed before kind: was required

<!-- status: open
     lane: apparatus
     area: docs -->

Body.'

ADDED_NO_KIND='

## newly-added-entry — filed today, no kind

<!-- status: open
     lane: apparatus
     area: docs -->

Body.'

ADDED_WITH_KIND='

## newly-added-entry — filed today, with a kind

<!-- status: open
     kind: bug
     lane: apparatus
     area: docs -->

Body.'

echo "============================================================"
echo "  pre-commit refuses a kindless entry it ADDS — and only that"
echo "============================================================"
echo

# 1. THE REFUSAL — an added entry with no kind.
setup_repo "$WITH_KIND"
rc="$(try_commit "$WITH_KIND$ADDED_NO_KIND")"
if [ "$rc" -ne 0 ] && grep -q 'declares no `kind:`' "$WORK/out.log"; then
  echo "  ok   [added-kindless] refused at commit time"; pass=$((pass + 1))
elif [ "$rc" -ne 0 ]; then
  echo "  FAIL [added-kindless] refused, but not by this check — the fixture trips something else"
  sed 's/^/         /' "$WORK/out.log" | head -4; fail=$((fail + 1))
else
  echo "  FAIL [added-kindless] COMMITTED — main goes red on the next push"; fail=$((fail + 1))
fi

# 2. The paired acceptance. Without it a hook that refuses everything passes row 1.
setup_repo "$WITH_KIND"
rc="$(try_commit "$WITH_KIND$ADDED_WITH_KIND")"
if [ "$rc" -eq 0 ]; then
  echo "  ok   [added-with-kind] committed"; pass=$((pass + 1))
else
  echo "  FAIL [added-with-kind] refused a correct entry"; sed 's/^/         /' "$WORK/out.log" | head -4; fail=$((fail + 1))
fi

# 3. THE ROW THIS GATE EXISTS FOR: a board that ALREADY contains a kindless entry, edited elsewhere.
#    779 of 1052 entries were kindless the day the field landed. If this fails, the hook refuses
#    nearly every board commit in the repo and is worse than the problem it solves.
setup_repo "$WITH_KIND

$LEGACY_KINDLESS"
rc="$(try_commit "$WITH_KIND

$LEGACY_KINDLESS

Appended prose, no new entry.")"
if [ "$rc" -eq 0 ]; then
  echo "  ok   [legacy-untouched] a pre-existing kindless entry does not block an unrelated edit"; pass=$((pass + 1))
else
  echo "  FAIL [legacy-untouched] the hook fails on entries it did not add — it would block everyone"
  sed 's/^/         /' "$WORK/out.log" | head -6; fail=$((fail + 1))
fi

# 4. And an added entry is still caught when the file ALSO holds legacy kindless ones — the two
#    rules must not cancel out.
setup_repo "$WITH_KIND

$LEGACY_KINDLESS"
rc="$(try_commit "$WITH_KIND

$LEGACY_KINDLESS$ADDED_NO_KIND")"
if [ "$rc" -ne 0 ] && grep -q 'newly-added-entry' "$WORK/out.log"; then
  echo "  ok   [added-beside-legacy] the added entry is named, the legacy one is not"; pass=$((pass + 1))
else
  echo "  FAIL [added-beside-legacy] legacy entries mask a new one"; sed 's/^/         /' "$WORK/out.log" | head -4; fail=$((fail + 1))
fi

# 5. AN ADDED ENTRY AT THE TOP OF A LARGE BOARD — the shape that actually reproduces the SIGPIPE,
#    and the reason rows 1-4 were not enough. `awk` stops at the entry's `-->`; if that entry is at
#    the END of the file awk has already read everything and the writer never blocks. Put the entry
#    FIRST and awk exits with hundreds of kilobytes still unwritten — which is precisely how entries
#    get filed (newest at the top) and precisely how this hook's first version reported its own
#    kind-bearing entry as kindless.
setup_repo "$WITH_KIND"
rc="$(try_commit "## added-at-top — filed at the head of a large board

<!-- status: open
     kind: bug
     lane: apparatus
     area: docs -->

Body.

$WITH_KIND")"
if [ "$rc" -eq 0 ]; then
  echo "  ok   [added-at-top-of-large-board] a kind-bearing entry at the head of a big file commits"; pass=$((pass + 1))
else
  echo "  FAIL [added-at-top-of-large-board] a correct entry was called kindless — the reader is"
  echo "         losing its status on a large file (SIGPIPE: never \`… | grep -q\` for a verdict)"
  sed 's/^/         /' "$WORK/out.log" | head -5; fail=$((fail + 1))
fi

echo
if [ $fail -eq 0 ]; then
  echo "precommit-kind-required: OK ($pass checks)"
  exit 0
fi
echo "precommit-kind-required: $pass ok, $fail FAIL"
exit 1
