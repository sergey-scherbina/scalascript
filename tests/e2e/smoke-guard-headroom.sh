#!/usr/bin/env bash
#
# smoke-guard-headroom — every smoke check's runaway guard must sit well clear of what that check
# actually costs, or it is a flake generator rather than a guard.
#
# WHY, MEASURED 2026-08-14 (tests/BUGS.md
# `a-smoke-guard-under-3-5x-its-own-baseline-is-a-flake-generator`). Four full `scripts/smoke-ci`
# runs on one dev host, four DIFFERENT failing sets, one of them on a pristine `origin/main`. Every
# failure but one was `TIMED OUT against its Ns guard`, and standalone on the same tree the same
# checks took a QUARTER of their guard:
#
#     stub-does-not-serialise    17 s and 24 s   against 60 s
#     entry-auto-invoke-once     29 s            against 120 s
#     run-lane-flags-are-flags   47 s            against 180 s
#
# The suite's TOTAL budget is already handled — the runner prints "over budget LOCALLY — reported,
# not failed" and means it. The per-check guards get no such treatment: they are absolute
# milliseconds compared against a wall clock that a neighbouring agent's build stretches 2-4x.
#
# THE THRESHOLD IS CHOSEN FROM A GAP IN THE DATA, NOT FROM TASTE. Sorted by guard/baseline, the
# checks are: six between 2.7x and 3.4x, then NOTHING until 5.3x. Three of those six timed out in
# four runs; none of the 69 at 5.3x or above timed out once. So the line goes at 4x — inside an
# empty band, with no check anywhere near it in either direction. Any threshold from 3.5x to 5x
# selects exactly the same six, which is what "the gap does the choosing" means.
#
# RAISING A GUARD COSTS THE BUDGET NOTHING. `timeoutMs` is a ceiling, not a cost, and
# `scripts/smoke-ci.ssc` already says so. A runaway guard exists to catch a HANG, and a hang is
# unbounded — any ceiling catches it. The only thing a generous ceiling costs is how long you wait
# before being told, which is why this gate demands headroom rather than tightness.
#
# THERE IS NO FROZEN LIST ON PURPOSE. The six were raised in the same commit that added this gate,
# so the debt is zero and an exemption list cannot rot into a permanent one. If you add a check with
# a tight guard, this fails and tells you the number to use.
#
# ── WHAT THIS GATE REFUSES TO GUESS ──────────────────────────────────────────────────────────────
#
# It reads the CheckList out of `scripts/smoke-ci.ssc`, and a parser over source is exactly the kind
# of instrument that silently under-counts. It did, three times, while being written:
#
#   a whole-file lazy regex   100 of 101   swallowed `corpus-lane-breadth` into a neighbour's match
#   a per-line regex           93 of 101   cannot see a Check written across lines
#   comments left in          103 of 102   counted the literal quoted in its OWN wiring comment
#
# The third is the same shape as `no-orphan-gates` counting a MENTION as a caller, and it is the one
# that would have been easiest to "fix" by loosening the count. So: comments are stripped, the spans
# are found by balancing brackets rather than by matching, and the COUNT IS ASSERTED — every
# `Check("` literal must parse, and a mismatch FAILS this gate instead of quietly shrinking its
# survey. Every one of those three is a self-test case below.
#
# It also cannot judge a check with no row in `tests/smoke-baseline.tsv` (a check added since the
# last `tests/smoke-baseline-harvest.sh`). Those are LISTED BY NAME rather than passed over: a
# survey that quietly drops its unmeasurable rows reads as complete when it is not.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SUITE="${SMOKE_GUARD_SUITE:-$ROOT/scripts/smoke-ci.ssc}"
BASE="${SMOKE_GUARD_BASELINE:-$ROOT/tests/smoke-baseline.tsv}"
MIN_RATIO="${SMOKE_GUARD_MIN_RATIO:-4}"

judge() {  # judge <suite.ssc> <baseline.tsv> <min-ratio>  -> prints a verdict, exits 0/1
  python3 - "$1" "$2" "$3" <<'PY'
import re, sys

suite, baseline, min_ratio = sys.argv[1], sys.argv[2], float(sys.argv[3])
raw = open(suite).read()


# A `Check("` inside a COMMENT is not a check. This gate learned that about itself the hard way:
# the wiring comment in scripts/smoke-ci.ssc quotes the literal it looks for, so the population
# jumped to 103 against 102 real entries and the gate failed on its own prose. Same shape as
# `no-orphan-gates` counting a MENTION as a caller. Comments are stripped before anything is
# counted, string-aware so a `//` inside a literal survives.
def strip_comments(text):
    out, i, in_str = [], 0, False
    while i < len(text):
        c = text[i]
        if in_str:
            if c == '\\':
                out.append(text[i:i + 2]); i += 2; continue
            if c == '"':
                in_str = False
        elif c == '"':
            in_str = True
        elif c == '/' and text[i:i + 2] == '//':
            j = text.find('\n', i)
            i = len(text) if j < 0 else j
            continue
        out.append(c); i += 1
    return ''.join(out)


src = strip_comments(raw)

# Bracket-balanced from each `Check("` to ITS closing paren. Neither of the two obvious shortcuts
# works, and both were tried: a whole-file lazy regex swallows a neighbour's literal (100 of 101,
# `corpus-lane-breadth` gone), and a per-line regex cannot see a Check written across lines (93 of
# 101). The population is the thing this gate is most likely to get wrong, so it is derived by
# scanning rather than by matching, and then asserted.
def check_spans(text):
    for m in re.finditer(r'Check\("', text):
        i = m.end() - 2          # at the opening paren
        depth, j, in_str = 0, i, False
        while j < len(text):
            c = text[j]
            if in_str:
                if c == '\\':
                    j += 2
                    continue
                if c == '"':
                    in_str = False
            elif c == '"':
                in_str = True
            elif c == '(':
                depth += 1
            elif c == ')':
                depth -= 1
                if depth == 0:
                    yield text[i:j + 1]
                    break
            j += 1

guards = {}
spans = list(check_spans(src))
for span in spans:
    name = re.match(r'\(\s*"[^"]*"\s*,\s*"([^"]+)"', span)
    ms = re.findall(r',\s*(\d+)\s*(?:,|\))', span)
    if name and ms:
        guards[name.group(1)] = int(ms[-1])

literals = len(re.findall(r'Check\("', src))
if literals != len(guards):
    print(f"FAIL smoke-guard-headroom: parsed {len(guards)} checks out of {literals} "
          f'`Check("` literals in {suite}.')
    print("     A parser that sees only part of its population reports on all of it anyway.")
    print("     Fix the regex here — do NOT lower the count.")
    sys.exit(1)

base = {}
for line in open(baseline):
    if line.startswith("#"):
        continue
    parts = line.rstrip("\n").split("\t")
    if len(parts) >= 2 and parts[1].isdigit():
        base[parts[0]] = int(parts[1]) / 10.0   # deciseconds; the column sums to the header's sum-seconds

rated, unrated = [], []
for name, ms in sorted(guards.items()):
    if name in base and base[name] > 0:
        rated.append((ms / 1000.0 / base[name], name, base[name], ms / 1000.0))
    else:
        unrated.append(name)
rated.sort()

tight = [r for r in rated if r[0] < min_ratio]

print(f"smoke-guard-headroom: {len(guards)} checks, {len(rated)} with a baseline row, "
      f"threshold {min_ratio:g}x")
if unrated:
    print(f"  NOT JUDGED — no row in {baseline} yet ({len(unrated)}): {', '.join(unrated)}")
    print("  Refresh with tests/smoke-baseline-harvest.sh; until then these carry no verdict.")

if tight:
    print(f"  FAIL — {len(tight)} guard(s) sit under {min_ratio:g}x their own measured cost:")
    for ratio, name, b, g in tight:
        print(f"    {ratio:5.1f}x  {name:38} costs {b:6.1f}s, guard {g:6.0f}s"
              f"  -> raise to at least {int(b * min_ratio + 0.999):d}s")
    print("  A guard this close to the working range fires on host load, not on a hang.")
    print("  Raising it costs the budget NOTHING: timeoutMs is a ceiling, not a cost.")
    sys.exit(1)

if rated:
    r, n, b, g = rated[0]
    print(f"  OK — tightest is {n} at {r:.1f}x ({b:.1f}s against a {g:.0f}s guard)")
sys.exit(0)
PY
}

# ── --self-test: the gate must FAIL on a tight guard and PASS once it is raised ──────────────────
#
# Both directions on a synthetic suite, because a gate that has only ever been seen green is a gate
# nobody has watched work. The third case is the one that matters most here: a suite whose literals
# do not all parse must be a FAILURE, not a smaller survey.
if [ "${1:-}" = "--self-test" ]; then
    tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
    fail=0
    ok()  { printf '  ok   %s\n' "$*"; }
    bad() { printf '  FAIL %s\n' "$*"; fail=1; }

    printf '# baseline\nalpha\t100\t140\nbeta\t200\t280\n' > "$tmp/base.tsv"

    # alpha: 30 s guard against a 10.0 s cost -> 3.0x, under the line.
    cat > "$tmp/tight.ssc" <<'EOF'
  Check("m", "alpha", "a.sh", List(), 30000),
  Check("m", "beta", "b.sh", List(), 200000),
EOF
    if SMOKE_GUARD_SUITE="$tmp/tight.ssc" SMOKE_GUARD_BASELINE="$tmp/base.tsv" \
       judge "$tmp/tight.ssc" "$tmp/base.tsv" 4 > "$tmp/tight.out" 2>&1; then
        bad "a 3.0x guard was ACCEPTED — this gate cannot fail"
        sed 's/^/       | /' "$tmp/tight.out"
    else
        grep -q "alpha" "$tmp/tight.out" && ok "a 3.0x guard fails, and the message names it" \
                                         || bad "it failed without naming alpha"
        grep -q "raise to at least 40s" "$tmp/tight.out" && ok "it says the number to use" \
                                         || bad "it did not print the required guard"
    fi

    # Same suite with alpha's ceiling raised to 4x. Nothing else changes.
    sed 's/30000/60000/' "$tmp/tight.ssc" > "$tmp/loose.ssc"
    if judge "$tmp/loose.ssc" "$tmp/base.tsv" 4 > "$tmp/loose.out" 2>&1; then
        ok "raising the ceiling to 6.0x makes it pass"
    else
        bad "a suite with headroom was still rejected"
        sed 's/^/       | /' "$tmp/loose.out"
    fi

    # A literal the parser cannot read must FAIL, not shrink the survey. A Check split across lines
    # is NOT the case to use — the balanced scan handles that, and an earlier version of this test
    # asserted the old per-line parser's weakness and went stale the moment the parser improved.
    # The case that still bites is a guard written as a NAMED CONSTANT rather than a literal, which
    # is an ordinary refactor away: there is no integer to read, so the check silently leaves the
    # population.
    { cat "$tmp/loose.ssc"; printf '  Check("m", "gamma", "g.sh", List(), TIMEOUT_LONG),\n'; } > "$tmp/partial.ssc"
    if judge "$tmp/partial.ssc" "$tmp/base.tsv" 4 > "$tmp/partial.out" 2>&1; then
        bad "an unparsed Check literal was silently ignored — the population is wrong and it passed"
        sed 's/^/       | /' "$tmp/partial.out"
    else
        grep -q "out of 3" "$tmp/partial.out" && ok "an unparsed literal fails the gate, counted" \
                                              || bad "it failed, but not for the parse-count reason"
    fi

    # A `Check("` inside a COMMENT must not join the population — the mistake this gate made on its
    # own wiring comment, and the same "a mention is not a caller" shape the orphan ratchet had to
    # learn. The control is a comment that quotes a Check literal AND a `//` inside a real string,
    # which a naive stripper would eat.
    { cat "$tmp/loose.ssc"; printf '  // see Check("m", "ghost", "g.sh", List(), 1000) for the shape\n'; } > "$tmp/comment.ssc"
    judge "$tmp/comment.ssc" "$tmp/base.tsv" 4 > "$tmp/comment.out" 2>&1
    rc=$?
    if [ $rc -eq 0 ] && ! grep -q "ghost" "$tmp/comment.out"; then
        ok "a Check literal inside a comment is not counted"
    else
        bad "a commented-out Check joined the population (rc=$rc)"
        sed 's/^/       | /' "$tmp/comment.out"
    fi

    printf '  Check("m", "slashy", "s.sh", List("http://x//y"), 800000),\n' >> "$tmp/loose.ssc"
    judge "$tmp/loose.ssc" "$tmp/base.tsv" 4 > "$tmp/slashy.out" 2>&1
    grep -q "parsed" "$tmp/slashy.out" \
        && { bad "a // inside a string literal broke the parse"; sed 's/^/       | /' "$tmp/slashy.out"; } \
        || ok "a // inside a string literal is not treated as a comment"

    # A check with no baseline row is reported by NAME, never passed over in silence.
    printf '  Check("m", "delta", "d.sh", List(), 500000),\n' >> "$tmp/loose.ssc"
    judge "$tmp/loose.ssc" "$tmp/base.tsv" 4 > "$tmp/unrated.out" 2>&1
    grep -q "NOT JUDGED" "$tmp/unrated.out" && grep -q "delta" "$tmp/unrated.out" \
        && ok "a check with no baseline row is named, not swallowed" \
        || { bad "an unmeasurable check vanished from the report"; sed 's/^/       | /' "$tmp/unrated.out"; }

    echo
    [ $fail -eq 0 ] && { echo "smoke-guard-headroom --self-test: all directions hold"; exit 0; }
    echo "smoke-guard-headroom --self-test: FAILED"; exit 1
fi

judge "$SUITE" "$BASE" "$MIN_RATIO"
