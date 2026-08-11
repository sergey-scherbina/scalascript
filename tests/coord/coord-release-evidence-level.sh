#!/usr/bin/env bash
#
# coord-release-evidence-level.sh — `scripts/coord-release` parses its flags and REFUSES without
# `--level`, run for real in a throwaway repo.
#
# The defect this pins, measured 2026-07-30 on the tool's first real use:
#
#   $ scripts/coord-release v2js-unit-pattern --level 3 --note "contract green"
#   $ git log -1 --format=%s
#   release-claim: v2js-unit-pattern — --level [skip ci]
#
# The flags were never parsed — `slug="$1"; extra="$2"` — so `--level` landed in the message as a
# literal and the `3` and the note vanished. That is cosmetic only until you read POLICY.md §P-6.7,
# which requires a release to NAME which of the three evidence levels it has. A required field a tool
# silently discards is an optional field, and the release record is where the next agent looks to
# decide whether a result can be trusted.
#
# Follows `coord-claim-runs.sh`: a lab with a fake origin, running the REAL script. Testing release
# tooling by releasing something real is not an option, and asserting on the script's text instead of
# its behaviour is how the last gate in this family passed against a tool that aborted on line 128.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TOOL="${COORD_RELEASE:-$ROOT/scripts/coord-release}"   # overridable: point it at an OLD copy to see it fail
LAB=$(mktemp -d "${TMPDIR:-/tmp}/coord-release-evl.XXXXXX")
trap 'rm -rf "$LAB"' EXIT HUP INT TERM
G=(-c user.email=test@example.com -c user.name=test -c commit.gpgsign=false)
fail=0

check() {
  if [ "$2" = "$3" ]; then printf 'PASS  %s\n' "$1"
  else printf 'FAIL  %s\n        expected=%s\n        got=%s\n' "$1" "$2" "$3"; fail=1; fi
}
contains() {  # contains <name> <needle> <haystack>
  case "$3" in
    *"$2"*) printf 'PASS  %s\n' "$1" ;;
    *) printf 'FAIL  %s\n        expected to contain=%s\n        got=%s\n' "$1" "$2" "$3"; fail=1 ;;
  esac
}

cd "$LAB"
git init -q --bare -b main origin.git
git clone -q origin.git main 2>/dev/null
cd main
git symbolic-ref HEAD refs/heads/main
mkdir -p .work/active
printf '# generation: 1\n#slug\tagent\tstarted\titems\tpaths\n' > .work/active/LEDGER.tsv
seed_claim() {  # seed_claim <slug>
  printf 'slug: %s\nagent: labtest\nstatus: in-progress\n' "$1" > ".work/active/$1.claim"
  printf '%s\tlabtest\t2026-07-30T00:00:00Z\tI1\tfile:x\n' "$1" >> .work/active/LEDGER.tsv
  git add -A; git "${G[@]}" commit -qm "seed $1" --no-verify >/dev/null; git push -q origin main
}
git commit -q --allow-empty -m init --no-verify >/dev/null 2>&1 || true
git add -A; git "${G[@]}" commit -qm init --no-verify >/dev/null; git push -q origin main
git fetch -q origin

# ── 0. the release note must carry the shas the claim landed ──────────────────
#
# Measured 2026-08-11 over 45 days: 468 of 1107 release-claim messages named NO commit at all, 43%,
# so "what did this claim land?" was not answerable from the record. The shas are now derived from
# the branch rather than typed from memory.
#
# The scenario is built so the PATH FILTER has something to drop: the branch carries one commit in
# the claim's scope and one outside it, which is what a rebase pulling in a sibling's commit looks
# like. Asserting only "a sha appears" would pass with no filter at all.
printf 'a\n' > scope-file; printf 'b\n' > other-file
git add -A; git "${G[@]}" commit -qm "base for the sha test" --no-verify >/dev/null; git push -q origin main
SHA_START="$(date -u +%Y-%m-%dT%H:%M:%SZ)"; sleep 1

git checkout -q -b feature/shalab
printf 'in scope\n' >> scope-file
git add -A; git "${G[@]}" commit -qm "touches the claim scope" --no-verify >/dev/null
SHA_IN="$(git rev-parse --short=9 HEAD)"
printf 'out of scope\n' >> other-file
git add -A; git "${G[@]}" commit -qm "a sibling commit a rebase dragged along" --no-verify >/dev/null
SHA_OUT="$(git rev-parse --short=9 HEAD)"
git push -q origin feature/shalab:main
git checkout -q main; git fetch -q origin; git reset -q --hard origin/main

printf 'slug: shalab\nagent: labtest\nbranch: feature/shalab\nstarted: %s\nstatus: in-progress\npaths: file:scope-file\n' \
  "$SHA_START" > .work/active/shalab.claim
printf 'shalab\tlabtest\t%s\tI1\tfile:scope-file\n' "$SHA_START" >> .work/active/LEDGER.tsv
git add -A; git "${G[@]}" commit -qm "seed shalab" --no-verify >/dev/null; git push -q origin main

out=$(bash "$TOOL" shalab --level 3 --note "sha derivation" 2>&1) && rc=0 || rc=$?
check "release with a branch exits 0" 0 "$rc"
[ "$rc" -eq 0 ] || printf "        ─ output ─
%s
" "$out" | sed "s/^/        /"
body=$(git log -1 --format=%B)
contains "the note names the in-scope commit" "$SHA_IN" "$body"
# ONE check over both halves, because "SHA_OUT is absent" is TRUE of an empty note and would have
# passed vacuously — it did, on the run where the derivation crashed and the note was empty.
case "$body" in
  *"$SHA_OUT"*) filt="the out-of-scope sha leaked in — the path filter is not applied" ;;
  *"$SHA_IN"*)  filt=ok ;;
  *)            filt="the note names no sha at all — this assertion would be vacuous" ;;
esac
check "the path filter keeps the in-scope sha and drops the other" ok "$filt"

# ── 1. the reported defect: flags are parsed, nothing is swallowed ─────────────
seed_claim flagform
out=$(bash "$TOOL" flagform --level 3 --note "contract green" 2>&1) && rc=0 || rc=$?
check "--level/--note form exits 0" 0 "$rc"
[ "$rc" -eq 0 ] || printf '        ─ output ─\n%s\n' "$out" | sed 's/^/        /'
msg=$(git log -1 --format=%s)
contains "the level reaches the message" "[evidence: level 3]" "$msg"
contains "the note reaches the message"  "contract green"      "$msg"
case "$msg" in
  *"— --level"*|*" --note"*)
    printf 'FAIL  a flag leaked into the message as a literal\n        got=%s\n' "$msg"; fail=1 ;;
  *) printf 'PASS  no flag leaked into the message as a literal\n' ;;
esac
check "the claim file is gone"  no "$([ -f .work/active/flagform.claim ] && echo yes || echo no)"
check "the ledger row is gone"  0  "$(grep -c '^flagform	' .work/active/LEDGER.tsv || true)"

# ── 2. the required field is REQUIRED ─────────────────────────────────────────
seed_claim needslevel
out=$(bash "$TOOL" needslevel --note "no level given" 2>&1) && rc=0 || rc=$?
check "missing --level is refused" 2 "$rc"
contains "refusal cites the policy rule" "P-6.7" "$out"
check "a refused release leaves the claim in place" \
      yes "$([ -f .work/active/needslevel.claim ] && echo yes || echo no)"

# A FRESH claim per refusal case. Without this the earlier case has already consumed `needslevel`
# and the tool exits 2 with "no such claim" — so the check passes for the wrong reason, which is
# indistinguishable from passing for the right one. Caught by running this file against the OLD tool:
# two checks were green there while the defect was fully present.
seed_claim badlevel
out=$(bash "$TOOL" badlevel --level 9 2>&1) && rc=0 || rc=$?
check "--level 9 is refused" 2 "$rc"
contains "the refusal shows the bad value" "9" "$out"
check "a refused release leaves that claim in place" \
      yes "$([ -f .work/active/badlevel.claim ] && echo yes || echo no)"

# ── 3. fail CLOSED on an unknown flag ─────────────────────────────────────────
# The old parser assigned any extra word to `extra`, so a typo'd flag decorated the message instead
# of stopping the release. A release record is not a place to discover a typo.
seed_claim unknownflag
out=$(bash "$TOOL" unknownflag --level 3 --bogus x 2>&1) && rc=0 || rc=$?
check "an unknown flag is refused" 2 "$rc"
check "the unknown-flag refusal leaves the claim in place" \
      yes "$([ -f .work/active/unknownflag.claim ] && echo yes || echo no)"
contains "the refusal names the offending flag" "--bogus" "$out"

# ── 4. the old positional note keeps working ──────────────────────────────────
# Existing habits must not break just because the parser grew flags; only `--level` is newly required.
seed_claim positional
out=$(bash "$TOOL" positional --level 1 "landed abc1234" 2>&1) && rc=0 || rc=$?
check "positional note form exits 0" 0 "$rc"
[ "$rc" -eq 0 ] || printf '        ─ output ─\n%s\n' "$out" | sed 's/^/        /'
msg=$(git log -1 --format=%s)
contains "positional note reaches the message" "landed abc1234"      "$msg"
contains "positional form still records the level" "[evidence: level 1]" "$msg"

if [ "$fail" -eq 0 ]; then echo "coord-release-evidence-level: OK"; else echo "coord-release-evidence-level: FAILED" >&2; fi
exit "$fail"
