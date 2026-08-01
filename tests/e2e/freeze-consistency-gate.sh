#!/usr/bin/env bash
#
# ARCH-1 — the missing invariant ACROSS the freezes that record "this case is expected to fail".
#
# WHY THIS EXISTS. That single fact is recorded in four places:
#
#   1. `known-red:` front-matter in tests/conformance/<case>.ssc   (read by run.sc AND contract.sc)
#   2. tests/conformance/corpus-baseline.tsv                       (read by contract.sc)
#   3. tests/conformance/contract-roster.tsv                       (read by contract.sc)
#   4. tests/fixtures/v21-sentinel-taxonomy/overrides.tsv          (read by the negtc gate)
#
# `contract.sc` relates (1)(2)(3). **Nothing at all relates (4) to the rest.** That gap cost two
# incidents on 2026-07-28, both of which took an hour to attribute:
#
#   * SC-2 landed and deleted `known-red:` from two cases — which is what the conformance suite
#     reads — but the paired rows in (2) stayed. Conformance went GREEN while the corpus contract
#     went RED. Half a landing that looked whole.
#   * `wasm-scalascript` was dropped from (2) as an expired entry, and its twin in (4) was left
#     behind, so `sbt — compile and test` stayed red on
#     `stale or reclassified override row: wasm-scalascript.ssc` — a failure surfaced by the
#     SLOWEST job in the repo, an hour after the change that caused it.
#
# This gate deliberately changes NO format. The defect was never the file layout, it was the
# absence of an invariant between the files; collapsing the formats is a later, bigger step and it
# should be taken on top of a working invariant, not instead of one.
#
# NOT A DUPLICATE of contract.sc's own checks: that script only runs inside the (long, sharded,
# nightly) Corpus Contract workflow and cannot see (4) at all. This is seconds long and can run on
# every push.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CASES="$ROOT/tests/conformance"
BASELINE="$ROOT/tests/conformance/corpus-baseline.tsv"
ROSTER="$ROOT/tests/conformance/contract-roster.tsv"
OVERRIDES="$ROOT/tests/fixtures/v21-sentinel-taxonomy/overrides.tsv"

fail=0
note() { printf '  %s\n' "$*"; }
bad()  { printf 'FAIL  %s\n' "$*" >&2; fail=1; }

for f in "$BASELINE" "$ROSTER" "$OVERRIDES"; do
  [ -f "$f" ] || { printf 'FAIL  missing freeze: %s\n' "${f#"$ROOT"/}" >&2; exit 1; }
done

# The corpus contract observes `int, js, v2` by default (contract.sc: `val canonicalLanes`). `jvm`
# is an ALLOWED lane but not a default one, so a `known-red: jvm` declaration legitimately has no
# baseline row and comparing it would be a false positive — four cases on main are in exactly that
# state today (coroutine-native-lifecycle, deep-tail-recursion, int-width, native-import-in-fence).
# Read the list from contract.sc rather than hard-coding it, so the two cannot drift apart.
CANONICAL_LANES="$(sed -n 's/^val canonicalLanes *= *List(\(.*\))/\1/p' "$ROOT/tests/conformance/contract.sc" \
                   | tr -d '" ' | tr ',' ' ')"
[ -n "$CANONICAL_LANES" ] || { printf 'FAIL  could not read canonicalLanes from contract.sc\n' >&2; exit 1; }
note "canonical lanes (from contract.sc): $CANONICAL_LANES"

is_canonical() { case " $CANONICAL_LANES " in *" $1 "*) return 0;; *) return 1;; esac; }

# ── I1: `known-red:` front-matter <-> KNOWN-RED baseline rows, on canonical lanes only ──────────
#
# This is incident #1 in both directions: a declaration without its row, and a row without its
# declaration. contract.sc catches the second only when a full nightly run happens to execute that
# case; this catches both in seconds.
declared=""
while IFS= read -r file; do
  case_name="$(basename "$file" .ssc)"
  raw="$(sed -n 's/^known-red: *//p' "$file" | head -1)"
  [ -n "$raw" ] || continue
  body="${raw%\"}"; body="${body#\"}"
  lanes_part="${body%%—*}"
  for lane in $(printf '%s' "$lanes_part" | tr ',' ' '); do
    lane="$(printf '%s' "$lane" | tr -d ' ' | tr '[:upper:]' '[:lower:]')"
    [ -n "$lane" ] || continue
    is_canonical "$lane" || continue
    declared="$declared$case_name/$lane "
    grep -qF "$(printf '%s\t%s\tKNOWN-RED' "$case_name" "$lane")" "$BASELINE" \
      || bad "known-red declared but NOT in the corpus baseline: $case_name / $lane
        the case's front-matter says this lane is a declared red; corpus-baseline.tsv does not.
        Add the row, or delete the declaration — do not leave the two disagreeing."
  done
done < <(grep -l '^known-red:' "$CASES"/*.ssc 2>/dev/null || true)

while IFS=$'\t' read -r case_name lane status; do
  [ "$status" = "KNOWN-RED" ] || continue
  is_canonical "$lane" || continue
  case " $declared " in
    *" $case_name/$lane "*) ;;
    *) bad "baseline says KNOWN-RED but the case does NOT declare it: $case_name / $lane
        corpus-baseline.tsv freezes this lane as a declared red; the case's front-matter has no
        matching \`known-red:\`. This is the shape that kept the corpus contract red after SC-2:
        removing a known-red means removing BOTH halves." ;;
  esac
done < "$BASELINE"

# ── I2: every negtc override must correspond to a case the corpus baseline still knows ──────────
#
# This is incident #2. `wasm-scalascript` started passing, its baseline rows were removed, and the
# override row was orphaned — detectable only by the negtc gate inside the hour-long sbt job.
# Rationale for the rule: an override exists to excuse a case that does not pass cleanly; a case
# with no non-PASS row anywhere in the corpus baseline has nothing left to excuse. All three live
# override rows satisfy it today (quoted-macro-constfold, quoted-macro-interpreter, x402-client).
#
# If a legitimate case ever needs an override while passing every corpus lane, do NOT delete this
# check — add the case to the explicit allowance below with a reason, so the exception is visible.
OVERRIDE_ALLOWANCE=""   # space-separated case names, each with a comment above saying why

while IFS=$'\t' read -r file category reason; do
  [ "$file" = "file" ] && continue           # header
  [ -n "$file" ] || continue
  case_name="${file%.ssc}"
  case " $OVERRIDE_ALLOWANCE " in *" $case_name "*) continue;; esac
  if ! grep -q "^$(printf '%s' "$case_name" | sed 's/[.[\*^$]/\\&/g')$(printf '\t')" "$BASELINE"; then
    bad "stale negtc override — no corpus-baseline row for it: $file
        tests/fixtures/v21-sentinel-taxonomy/overrides.tsv excuses this case, but the corpus
        baseline records nothing to excuse. Either the case started passing and the override is
        expired (delete it), or it belongs in OVERRIDE_ALLOWANCE with a stated reason."
  fi
done < "$OVERRIDES"

# ── I3: an override naming a case that does not exist is dead weight ─────────────────────────────
while IFS=$'\t' read -r file _rest; do
  [ "$file" = "file" ] && continue
  [ -n "$file" ] || continue
  [ -f "$CASES/$file" ] || [ -f "$ROOT/examples/$file" ] \
    || bad "negtc override names a case that does not exist: $file"
done < "$OVERRIDES"

# ── I4: a `known-red:` must point at an OPEN bug ─────────────────────────────────────────────
#
# A declared red is a TRACKED red — that is the entire difference between declaring one and hiding
# one. If the entry it names says `status: fixed`, the record has it both ways: the bug is closed
# and the lane it closed is still failing, and each half makes the other invisible.
#
# NOT hypothetical. Both entries behind the declarations added 2026-08-01 said `fixed` while their
# own gates were RED, because each had been fixed on ONE of two paths — long arithmetic on the v1
# JS emitter but not the v2 front's, `getOrElse` on the legacy front but not F. They were reopened
# by hand before the declarations went in; this makes the next one impossible to forget.
#
# WHY NOT COMPARE LANES INSTEAD, which was the first idea: the BUGS `lane:` vocabulary
# (int/js/jvm/native/front/…) and the baseline's lane names (int/js/js-v2/jvm/jvm-v2/v2) do not line
# up, and inventing a mapping either MISSES the two real cases (`lane: js` vs a red `js-v2`,
# `lane: native` vs a red `v2`) or flags two legitimate ones — `int-width` is genuinely fixed on
# `int` while `js`/`jvm` are separately declared for a different reason. Measured both ways before
# choosing this rule, which needs no mapping at all.
for case_file in "$CASES"/*.ssc; do
  grep -q '^known-red:' "$case_file" 2>/dev/null || continue
  decl="$(sed -n 's/^known-red:[[:space:]]*//p' "$case_file")"
  case_name="$(basename "$case_file" .ssc)"
  named=0
  for slug in $(printf '%s' "$decl" | grep -oE '[a-z][a-z0-9]+(-[a-z0-9]+){2,}' | sort -u); do
    # `|| true` is load-bearing under `pipefail`: most tokens the regex pulls out of the
    # declaration prose are NOT bug slugs (case names, spec names), so this grep finding
    # nothing is the COMMON path. Without it the gate exits silently at the first such
    # token — printing neither FAIL nor PASS, which is the worst way for a gate to stop.
    hdr="$(grep -rA6 "^## ${slug} " "$ROOT"/BUGS.md "$ROOT"/*/BUGS.md "$ROOT"/*/*/*/BUGS.md 2>/dev/null            | grep -m1 -oE 'status:[[:space:]]*[a-z]+' | awk '{print $2}' || true)"
    [ -n "$hdr" ] || continue
    named=1
    if [ "$hdr" = "fixed" ]; then
      bad "known-red points at a bug marked FIXED: $case_name -> $slug
        A declared red is a tracked red. An entry that says \`fixed\` while the lane it names is
        still failing hides the bug twice — reopen it, or delete the declaration."
    fi
  done
  [ "$named" -eq 1 ] || printf '  note: known-red on %s names no BUGS slug — it cannot be tracked back\n' "$case_name"
done

if [ "$fail" -ne 0 ]; then
  printf '\nfreeze-consistency-gate: FAIL — the freezes disagree about the same case.\n' >&2
  exit 1
fi
printf 'freeze-consistency-gate: PASS (front-matter, corpus baseline and negtc overrides agree)\n'
