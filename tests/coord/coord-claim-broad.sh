#!/usr/bin/env bash
#
# coord-claim `--broad`: the justification must land on its OWN line, not on `paths:`.
#
# `mod:` and `repo:` scopes REQUIRE `--broad "<reason>"`. Between 2026-07-?? and 2026-08-02 the claim
# file was written from a quoted heredoc containing
#
#     paths: ${paths}${broad:+\nbroad: ${broad}}
#
# and inside a quoted heredoc `\n` is two literal characters. So the reason was appended to the
# `paths:` LINE. The pre-push guard compares that line against the LEDGER's paths column, they
# disagreed, and every `--broad` claim was refused — with a diagnostic showing the reason's words
# shuffled in among the paths. Net effect: the two scopes that need the flag were unusable.
#
# ASSERTED HERE, in a throwaway repo, touching nothing real:
#   1. `paths:` carries the paths and NOTHING else — this is the regression;
#   2. `broad:` exists as its own line and carries the whole reason, spaces intact;
#   3. the claim's `paths:` equals the LEDGER's paths column, which is what the pre-push guard
#      compares and what the bug broke;
#   4. without `--broad`, no `broad:` line appears at all.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LAB="$(mktemp -d "${TMPDIR:-/tmp}/coord-broad.XXXXXX")"
trap 'rm -rf "$LAB"' EXIT HUP INT TERM
G=(-c user.email=test@example.com -c user.name=test -c commit.gpgsign=false)
fail=0
ok()  { printf '✓ %s\n' "$*"; }
bad() { printf '✗ %s\n' "$*"; fail=1; }

echo "── coord-claim --broad"

# A repo shaped like the real one, far enough for coord-claim to run: a main branch, an origin it
# can fetch, and the two files it touches.
git init -q "$LAB/origin" --bare
git "${G[@]}" clone -q "$LAB/origin" "$LAB/work" 2>/dev/null
cd "$LAB/work"
mkdir -p .work/active scripts
cp "$ROOT/scripts/coord-claim" scripts/coord-claim
printf 'x\n' > README.md
git "${G[@]}" add -A >/dev/null
git "${G[@]}" commit -qm init
git "${G[@]}" branch -M main
git "${G[@]}" push -q origin main 2>/dev/null

git config user.email test@example.com; git config user.name test
git config commit.gpgsign false; git config core.hooksPath /dev/null

REASON='v3 does not exist yet and the module subtree is created by this very task'
# coord-claim also commits and pushes; in this sandbox that may fail and it does not matter — the
# claim FILE is what this gate is about and it is written before any of that.
SSC_AGENT_ID=test scripts/coord-claim probe --items I --paths 'mod:v3' --broad "$REASON" \
  >"$LAB/out" 2>&1 || true

CLAIM="$LAB/work/.work/active/probe.claim"
if [ ! -f "$CLAIM" ]; then
  bad "coord-claim wrote no claim file; output was:"; sed 's/^/      /' "$LAB/out" | head -8
  echo; echo "✗ coord-claim --broad gate FAILED"; exit 1
fi

paths_line="$(sed -n 's/^paths: //p' "$CLAIM")"
broad_line="$(sed -n 's/^broad: //p' "$CLAIM")"

# 1 — THE REGRESSION. Before the fix this read "mod:v3 does not exist yet and the module …".
if [ "$paths_line" = "mod:v3" ]; then ok "paths: carries the paths and nothing else"
else bad "paths: has the reason spliced into it: '$paths_line'"; fi

# 2 — the reason survives whole, spaces and all.
if [ "$broad_line" = "$REASON" ]; then ok "broad: is its own line and holds the whole reason"
else bad "broad: is '$broad_line'"; fi

# 3 — what the pre-push guard actually compares.
led="$(awk -F'\t' '$1=="probe" {print $5}' "$LAB/work/.work/active/LEDGER.tsv" 2>/dev/null)"
if [ -n "$led" ] && [ "$led" = "$paths_line" ]; then ok "claim paths == LEDGER paths column"
else bad "claim '$paths_line' vs ledger '$led' — this is what refused the push"; fi

# 4 — no flag, no line.
rm -f "$CLAIM"
SSC_AGENT_ID=test scripts/coord-claim plain --items I --paths 'file:a.txt' >/dev/null 2>&1 || true
if [ -f "$LAB/work/.work/active/plain.claim" ]; then
  if grep -q '^broad:' "$LAB/work/.work/active/plain.claim"; then
    bad "a claim with no --broad grew a broad: line"
  else ok "no --broad, no broad: line"; fi
else
  printf '  note: the no-flag claim was not written in this sandbox; skipped that half\n'
fi

echo
[ "$fail" -eq 0 ] && { echo "✓ coord-claim --broad gate PASSED"; exit 0; }
echo "✗ coord-claim --broad gate FAILED"; exit 1
