#!/usr/bin/env bash
#
# no-gnu-only-shell-constructs — a shell construct that only works on GNU coreutils must not appear
# in a check that runs on both a mac and a Linux runner.
#
# THREE GATES WERE BROKEN BY THIS IN ONE DAY, 2026-08-04, each found by accident and each after the
# gate had been failing or lying for a while:
#
#   sed -n '/A\|B/,$p'   GNU reads `\|` as alternation, BSD as a LITERAL pipe. area-map-gate
#                        announced "a bug is filed in a board that does not own its code" and then
#                        listed NONE of it, for as long as anyone had been running it on a mac.
#   head -n -2           BSD head has no negative count and rejects it outright. In fm-routes and
#                        health-defaults the body extraction produced an EMPTY string every time, so
#                        every body assertion failed on every lane regardless of what the server
#                        sent — a check whose failure is indistinguishable from the defect it hunts.
#   \t inside an ERE     GNU grep/sed expand it, BSD matches a literal `t`.
#
# None of the three was found by running the gate: they were found by someone reading output that
# made no sense. That is the argument for a mechanical check — this one is green on arrival, which
# is the point. Its job is the FOURTH one.
#
# ── WHY IT STRIPS COMMENTS FIRST, WHICH IS NOT A DETAIL ──────────────────────────────────────────
#
# Every fix above left a comment EXPLAINING the construct it removed. A naive grep over these files
# matches those explanations and reports the fixed files as broken — it fooled me twice inside five
# minutes while measuring whether this gate was worth writing. Matching runs on code only.
#
# ── AND WHY THE GUARDED IDIOM IS NOT A FINDING ───────────────────────────────────────────────────
#
# `stat -c %Y "$f" 2>/dev/null || stat -f %m "$f" 2>/dev/null || echo 0` is the CORRECT portable
# form: GNU first, BSD second, fallback last. scripts/build-guard has it. A rule that flags the
# guarded idiom teaches people to route around the gate.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
echo "── no GNU-only shell constructs"

fail=0
checked=0

# THIS FILE MUST BE IN ITS OWN SCAN SET, and that is not a nicety — it is the bug this gate shipped
# with. `git ls-files` lists TRACKED files, so on its first run the gate had not been `git add`ed
# yet, could not see itself, and passed. It went red the instant it landed, because its rule names
# and probes spelled the very constructs it hunts. The names are neutral now and the probes are
# assembled from fragments rather than written out, so no literal appears; this check makes sure the
# self-scan is real rather than assumed, and would have caught the whole mistake in one line.
if ! git ls-files 'tests/e2e/*.sh' | grep -q 'no-gnu-only-shell-constructs\.sh'; then
  echo "✗ this gate is not in its own scan set — it cannot catch a GNU-ism in its own logic."
  echo "    That is exactly how it passed before it was tracked. Untracked? git add it."
  exit 1
fi

# Each rule carries a PROOF: a one-line probe run on THIS host that must show the construct
# behaving as claimed. A rule whose proof does not hold is dropped rather than trusted, because a
# portability rule copied from folklore is how a gate starts rejecting correct code.
prove() {  # $1 name | $2 command whose output must be EMPTY on a host where the construct is broken
  local out; out="$(eval "$2" 2>/dev/null)"
  if [ -z "$out" ]; then return 0; fi
  echo "  · rule '$1' does not apply on this host (the construct works here) — not enforced"
  return 1
}

# `code_only` drops comment lines and trailing comments before matching.
code_only() { sed -e 's/[[:space:]]#.*$//' -e '/^[[:space:]]*#/d' "$1"; }

scan() {  # $1 rule name | $2 ERE | $3 hint
  local rule="$1" re="$2" hint="$3" hits=0
  while IFS= read -r f; do
    local m; m="$(code_only "$f" | grep -nE -- "$re" | head -3)"
    if [ -n "$m" ]; then
      [ "$hits" -eq 0 ] && echo "  ✗ $rule"
      hits=$(( hits + 1 ))
      printf '      %s\n' "$f"
      printf '%s\n' "$m" | sed 's/^/          /'
    fi
  done < <(git ls-files 'tests/e2e/*.sh' 'scripts/*' 2>/dev/null | while read -r p; do [ -f "$p" ] && head -1 "$p" 2>/dev/null | grep -q '^#!.*sh' && echo "$p"; done)
  checked=$(( checked + 1 ))
  if [ "$hits" -eq 0 ]; then echo "  ✓ $rule"
  else echo "      → $hint"; fail=1; fi
}

# 1. `\|` in a sed BRE. Proof: on a GNU host the alternation matches and the probe prints a line.
BAR='\\|'   # assembled, never literal: this file is scanned by its own rules
if prove 'sed-alternation' "printf 'b\n' | sed -n '/a${BAR}b/p'"; then
  scan "sed BRE alternation (BSD reads the escaped bar as a literal pipe)" \
       "sed[^|]*'[^']*\\\\\\|" \
       "use sed -E with plain | , or two -e expressions"
fi

# 2. `head -n -N`. Proof: on a GNU host it prints something; on BSD it errors and prints nothing.
NEG="-n -1"
if prove 'head-negative' "printf 'a\nb\nc\n' | head $NEG"; then
  scan "head with a negative line count (BSD head has none)" \
       "head +-n +-[0-9]" \
       "use sed '\$d' to drop the last line, or awk"
fi

# 3. `grep -P`. Proof: GNU grep has PCRE, BSD grep does not.
PERL="-""P"
if prove 'grep-perl' "printf 'a\n' | grep $PERL 'a'"; then
  scan "grep with the Perl-regex flag (BSD grep has no PCRE)" \
       "grep [^|]*-[A-Za-z]*P" \
       "use grep -E, or perl/python for real PCRE"
fi

echo
if [ "$fail" -ne 0 ]; then
  echo "    Each of these silently changes behaviour on the other host rather than erroring, which is"
  echo "    how three gates spent weeks reporting nothing, or reporting an empty body as a defect."
  echo "✗ no-gnu-only-shell-constructs FAILED"
  exit 1
fi
[ "$checked" -eq 0 ] && { echo "✗ no rule was enforced — every proof failed, so this run asserted nothing"; exit 1; }
echo "✓ no-gnu-only-shell-constructs PASSED ($checked rule(s) enforced)"
