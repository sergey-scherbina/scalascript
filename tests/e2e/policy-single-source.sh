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
  hits=$(docs | while read -r f; do grep -Fq -- "$phrase" "$f" && echo "$f"; done || true)
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

self_test() {
  echo "── self-test: a duplicated rule must make this RED ──"
  local tmp="specs/_policy-single-source-selftest.md"
  printf '# scratch\n\nA gate about a TOOL must RUN that tool.\n' > "$tmp"
  git add -N "$tmp" >/dev/null 2>&1 || true
  local rc=0
  "$0" >/dev/null 2>&1 || rc=$?
  git rm -q --cached "$tmp" >/dev/null 2>&1 || true
  rm -f "$tmp"
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
