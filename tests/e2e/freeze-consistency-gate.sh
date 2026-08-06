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

# ── I0: the roster header's PAIRED digests match the two frozen files ───────────────────────────
#
# FIRST, because when this is wrong nothing else matters: `contract.sc` refuses to start at all —
# `[error] corpus contract freeze invalid: roster/baseline digest mismatch` — so the corpus contract
# produces NO verdict rather than a wrong one, for everyone, until someone notices.
#
# That happened: `f6e93154e` deleted a row from the baseline (correctly — a fix had landed) and left
# `baseline-sha256` in the roster header pointing at the old content. It survived because THIS gate
# passed: it related front-matter, baseline rows and negtc overrides, and never looked at the header
# that pairs the two files it was reading. The per-push corpus check does not go through
# `contract.sc`, so CI stayed green too; only the nightly Corpus Contract could have seen it, and
# that job's history is exactly why this gate exists.
#
# Canonical form, mirroring contract.sc `canonicalText`: non-empty lines joined by "\n", trailing
# "\n". The roster's own body starts at line 2 (line 1 IS the header).
# v2/BUGS.md — tests/conformance `corpus-contract-freeze-pairing-unchecked`.
sha256_canonical() { # $1 = file, $2 = first body line (1-based)
  if command -v sha256sum >/dev/null 2>&1; then
    tail -n "+$2" "$1" | grep -v '^$' | sha256sum | awk '{print $1}'
  else
    tail -n "+$2" "$1" | grep -v '^$' | shasum -a 256 | awk '{print $1}'
  fi
}
roster_header="$(head -1 "$ROSTER")"
want_baseline="$(printf '%s' "$roster_header" | sed -n 's/.*baseline-sha256=\([0-9a-f]\{64\}\).*/\1/p')"
want_roster="$(printf '%s'   "$roster_header" | sed -n 's/.*roster-sha256=\([0-9a-f]\{64\}\).*/\1/p')"
if [ -z "$want_baseline" ] || [ -z "$want_roster" ]; then
  bad "roster header carries no paired digests
        expected: # corpus-contract-roster-v1<TAB>baseline-sha256=<64 hex><TAB>roster-sha256=<64 hex>
        got:      $roster_header"
else
  got_baseline="$(sha256_canonical "$BASELINE" 1)"
  got_roster="$(sha256_canonical "$ROSTER" 2)"
  [ "$got_baseline" = "$want_baseline" ] || bad "corpus-baseline.tsv does not match the digest the roster header pairs it with
        header: $want_baseline
        actual: $got_baseline
        The corpus contract cannot START in this state. Whichever file you edited, recompute the
        header: both digests are over the non-empty lines joined by newlines, plus a trailing one."
  [ "$got_roster" = "$want_roster" ] || bad "contract-roster.tsv does not match its own digest in the header
        header: $want_roster
        actual: $got_roster"
  note "freeze pairing: roster header agrees with both files"
fi

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

# ── I5: every corpus case has a roster row ────────────────────────────────────────────────────
# `contract.sc` treats a case absent from the frozen roster as RED and exits 1, so drift here stops
# the Corpus Contract for EVERYONE — and it only surfaced in the nightly, long after the push that
# caused it. Measured 2026-07-28 it had reached 48 cases; by 2026-08-05, 2.
#
# The case set is ASKED OF contract.sc (`--list`), never re-derived here. Re-deriving it is exactly
# the mistake this check exists to catch: a hand-rolled `tests/conformance/*.ssc examples/*.ssc` glob
# over the same tree answered 593 where contract.sc says 558 — wrong by 35 — because the tool applies
# rules a glob does not know. `--list` costs 0.49 s warm, less than the rest of this gate.
#
# DERIVED, not frozen: this compares two sets. A frozen COUNT would go stale on the next case added,
# which is how the roster drifted in the first place.
if command -v scala-cli >/dev/null 2>&1; then
  # The breadcrumb is not decoration. This is the only step that can take minutes: `scala-cli` is
  # ~0 s here against a warm cache and a cold resolve-and-compile on a runner — measured 2 s local
  # against 94.5 s on CI for this whole gate, a 47x ratio. When the smoke runner's guard kills a
  # check it reports `exit code -1` and no output at all, so a line printed BEFORE the slow step is
  # the only thing that survives to say where it died.
  printf '  asking contract.sc for the case list (cold scala-cli dominates this gate on CI)\n'
  # `|| true` INSIDE the substitution, and it is load-bearing: `set -e` is on, and under `pipefail`
  # this pipeline exits non-zero whenever `grep -v` matches nothing — which is exactly the
  # produced-nothing case the very next `if` exists to report. Without it the script died here with
  # no message and no summary, so the handler below was unreachable code and a CI red showed four
  # stdout lines, `exit code 1`, and no reason. Reproduced with:
  #     set -euo pipefail; x="$(printf '' | grep -v '^$')"; echo unreachable
  listed="$(cd "$ROOT" && timeout 120 scala-cli tests/conformance/contract.sc -- --list 2>/dev/null \
            | sed 's/[[:space:]]*$//' | grep -v '^$' | LC_ALL=C sort || true)"
  if [ -z "$listed" ]; then
    printf '  note: contract.sc --list produced nothing — roster drift NOT checked this run\n'
  else
    # Same hazard, same guard: an empty roster body would otherwise abort here instead of reporting.
    rostered="$(tail -n +2 "$ROOT/tests/conformance/contract-roster.tsv" | grep -v '^$' | LC_ALL=C sort || true)"
    missing="$(comm -23 <(printf '%s\n' "$listed") <(printf '%s\n' "$rostered"))"
    orphan="$(comm -13 <(printf '%s\n' "$listed") <(printf '%s\n' "$rostered"))"
    if [ -n "$missing" ]; then
      printf 'FAIL  corpus case(s) with no roster row — contract.sc exits 1 on these, for everyone:\n' >&2
      printf '%s\n' "$missing" | sed 's/^/        /' >&2
      printf '      add the row, then refresh roster-sha256 in the header (both must move together).\n' >&2
      fail=1
    fi
    if [ -n "$orphan" ]; then
      printf 'FAIL  roster row(s) naming a case the corpus no longer has:\n' >&2
      printf '%s\n' "$orphan" | sed 's/^/        /' >&2
      fail=1
    fi
    [ -n "$missing$orphan" ] || printf '  roster covers every corpus case (%s)\n' "$(printf '%s\n' "$listed" | wc -l | tr -d ' ')"
  fi
else
  printf '  note: scala-cli absent — roster drift NOT checked this run\n'
fi

if [ "$fail" -ne 0 ]; then
  printf '\nfreeze-consistency-gate: FAIL — the freezes disagree about the same case.\n' >&2
  exit 1
fi
printf 'freeze-consistency-gate: PASS (front-matter, corpus baseline and negtc overrides agree)\n'
