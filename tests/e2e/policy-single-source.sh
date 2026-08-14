#!/usr/bin/env bash
#
# policy-single-source.sh — a rule is stated in POLICY.md and nowhere else.
#
#   ./tests/e2e/policy-single-source.sh              # check
#   ./tests/e2e/policy-single-source.sh --self-test  # prove it can FAIL, then check
#
# WHAT THIS CAN AND CANNOT DO — read this before trusting it.
#
# It cannot detect a paraphrase. Deciding whether two paragraphs state the same rule is not
# mechanical, and a keyword heuristic here would be the same mistake that made `area:` read `front`
# for 256 of 621 entries: a classification nothing verifiable produced.
#
# What it DOES catch is the failure actually observed on this project — verbatim duplication, and
# links that rot. Each rule below is pinned by one distinctive phrase that must appear EXACTLY ONCE
# across the tracked docs. That is decidable, and it is what happens when someone copies a section
# instead of linking to it: the list of module directories lived twice in one file; the
# shared-bookkeeping set drifted between two hook layers three times in a day;
# `corpus-baseline.tsv` and the negtc overrides disagreed about the same case twice in a week.
#
# So: green here means "no verbatim copy and the pointers resolve", not "no rule is restated".
# The stronger property is a review question, and P-7.1 is how it is stated to reviewers.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
fail=0

POLICY="POLICY.md"
[ -f "$POLICY" ] || { echo "✗ $POLICY is missing — it is the single source for the rules" >&2; exit 1; }

# Docs that are allowed to be searched. The plugins submodule is another repository, and
# CHANGELOG.md is a HISTORY of what was written on a given day — a rule quoted in a past entry is a
# record, not a second source, and rewriting history to satisfy a gate would be the wrong fix.
docs() {
  git ls-files '*.md' \
    | grep -v '^\.agents/' \
    | grep -v '^CHANGELOG\.md$' \
    | grep -v '^specs/.*-archive' || true
}

# A pin goes STALE when the rule is legitimately rewritten — P-3.5 changed the same day this
# gate landed, when the board became generated instead of hand-written, and the gate said
# `phrase not found at all` within the minute. That verdict is deliberately distinct from
# `stated in N places`: one means the gate is out of date, the other means the repo is.
# rule-id<TAB>phrase that must be unique. Phrases are chosen to be distinctive enough that an
# accidental match is implausible, and short enough to survive reflowing.
PINS=$(cat <<'EOF'
P-2.3	are shared at **every** level, root and per-module
P-3.2	An entry belongs to the module where the FIX goes
P-3.5	The in-flight board is GENERATED
P-3.6	Subtype is a FIELD, never a second directory
P-4.1	Default to deciding; asking is the exception
P-6.1	A gate nobody has seen fail is a
P-6.2	A gate about a TOOL must RUN that tool
P-6.4	needs one vocabulary on both sides, or it is not one guard but two
EOF
)

echo "── each rule's phrase appears exactly once ──"
while IFS=$'\t' read -r rule phrase; do
  [ -n "$rule" ] || continue
  # ONE grep per pin, not one per FILE. This loop was 8 pins x 564 docs = 4512 grep spawns, and it
  # was 65.4s — the single most expensive check in a suite that had just gone over its cap. Measured
  # 2026-08-05: 5.13s -> 0.28s for one pin, same file list. -Fl over a NUL-delimited list keeps the
  # exact semantics (fixed string, names of matching files) and survives spaces in paths; xargs may
  # split into batches, which changes nothing because -l prints per file and the union is the same.
  hits=$(docs | tr '\n' '\0' | xargs -0 grep -Fl -- "$phrase" 2>/dev/null || true)
  n=$(printf '%s\n' "$hits" | grep -c . || true)
  case "$n" in
    1) if printf '%s\n' "$hits" | grep -qx "$POLICY"; then
         printf '  ✓ %-6s unique, in %s\n' "$rule" "$POLICY"
       else
         printf '  ✗ %-6s stated in %s, but NOT in %s — POLICY.md must be the source\n' \
                "$rule" "$hits" "$POLICY" >&2; fail=1
       fi ;;
    0) printf '  ✗ %-6s phrase not found at all — the pin is stale, fix this gate or the rule\n' \
              "$rule" >&2; fail=1 ;;
    *) printf '  ✗ %-6s stated in %s places:\n' "$rule" "$n" >&2
       printf '%s\n' "$hits" | sed 's/^/        /' >&2
       printf '        Replace the copy with a link to %s#%s (P-7.1).\n' "$POLICY" \
              "$(printf '%s' "$rule" | tr 'A-Z.' 'a-z-')" >&2
       fail=1 ;;
  esac
done <<< "$PINS"

# The other half: a document that USED to carry the rules must point at the file that now does.
# Without this, "moved the rules out" degrades into "deleted the rules from where people read".
echo "── the entry points link to POLICY.md ──"
for f in AGENTS.md README.md specs/claim-mutex.md specs/work-tracking-layout.md specs/bugs-index.md; do
  [ -f "$f" ] || continue
  if grep -Fq "POLICY.md" "$f"; then printf '  ✓ %s\n' "$f"
  else printf '  ✗ %s does not mention POLICY.md — a reader starting there never finds the rules\n' \
            "$f" >&2; fail=1; fi
done

# THE FIXTURE IS NEVER PUT IN THE SHARED INDEX. It has to be VISIBLE to `git ls-files`, because
# that is how docs() builds the list — a file merely sitting on disk is invisible to the check it
# exists to trip. It used to get there with `git add -N` into the checkout's own index, unstaged
# three lines later. Between those two lines the state lives in the one place every agent in this
# checkout shares, and a run that does not reach the third line leaves an intent-to-add entry for a
# file that is no longer on disk: `git status` shows ` D specs/_policy-single-source-selftest.md`
# and `git rebase` REFUSES TO START — "cannot rebase: You have unstaged changes" — about a path its
# owner has never heard of. Reported 2026-08-14 by an agent whose smoke died on an unrelated check
# under host load; entry `policy-selftest-stages-into-the-shared-index` in tests/BUGS.md.
#
# A trap alone would not have fixed it. The interruption that actually happens here is a suite
# timeout or a killed process group, and a SIGKILL runs no trap. So the shared index is not written
# at all: GIT_INDEX_FILE points this function, and the child run it drives, at a COPY. Whatever
# signal arrives, the index the next `git rebase` reads is the one it started with.
#
# The trap is still worth having for the file on disk, and `.gitignore` carries the same path so a
# leftover from a SIGKILL is inert — invisible to `git status`, unsweepable by a sibling's
# `git add -A`. tests/e2e/policy-selftest-residue-gate.sh interrupts a real run at a known point,
# with both signals, and asserts all three.
_st_tmp="specs/_policy-single-source-selftest.md"
_st_idx=""
_st_cleanup() {
  rm -f "$_st_tmp"
  [ -n "$_st_idx" ] && rm -f "$_st_idx"
  return 0
}

self_test() {
  echo "── self-test: a duplicated rule must make this RED ──"
  _st_idx="$(mktemp "${TMPDIR:-/tmp}/policy-single-source-index.XXXXXX")"
  trap _st_cleanup EXIT INT TERM HUP
  cp "$(git rev-parse --git-path index)" "$_st_idx"
  printf '# scratch\n\nA gate about a TOOL must RUN that tool.\n' > "$_st_tmp"
  # Not `|| true`: if the fixture never reaches the index copy the child sees ONE copy of the
  # phrase, exits 0, and the failure below reads "the duplicate did not turn this gate red" — a
  # true statement about a run that never had a duplicate in it. `-f` because .gitignore covers
  # this path on purpose.
  if ! GIT_INDEX_FILE="$_st_idx" git add -f -N "$_st_tmp" >/dev/null 2>&1; then
    echo "✗ SELF-TEST FAILED: could not stage the fixture into the private index copy." >&2
    _st_cleanup
    trap - EXIT INT TERM HUP
    return 1
  fi
  local rc=0
  GIT_INDEX_FILE="$_st_idx" "$0" >/dev/null 2>&1 || rc=$?
  _st_cleanup
  trap - EXIT INT TERM HUP
  if [ "$rc" -eq 0 ]; then
    echo "✗ SELF-TEST FAILED: a verbatim second copy of P-6.2 did not turn this gate red." >&2
    return 1
  fi
  echo "✓ self-test: duplicate detected"
  return 0
}

if [ "${1:-}" = "--self-test" ]; then
  self_test || exit 1
  echo
  exec "$0"
fi

echo
if [ "$fail" -ne 0 ]; then
  echo "policy-single-source: FAIL — see P-7.1 in POLICY.md" >&2
  exit 1
fi
echo "policy-single-source: PASS"
