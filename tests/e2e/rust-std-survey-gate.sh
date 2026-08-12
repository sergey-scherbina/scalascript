#!/usr/bin/env bash
# The std corpus through `build-rust`: the BADRUST column must not GROW.
#
# WHERE THIS CAME FROM. rozum surveyed all 131 `std/**/*.ssc` through `build-rust` — a measurement
# nobody here had taken, offered with the observation that five of their six defect reports were
# found the same way, by building a real program on this lane. Their table is `repro/std-rust-survey.tsv`.
#
# THE THREE COLUMNS MEAN DIFFERENT THINGS, and the middle one is the point of this gate:
#
#   REFUSED   the backend says it cannot lower this. CORRECT behaviour — a coverage gap, not a bug.
#   COMPILES  lowers, and rustc accepts it.
#   BADRUST   emits Rust that rustc REJECTS. This is the only column that is a defect by itself.
#
# So the useful assertion is not "everything compiles" — 57% is honestly refused and that is fine.
# It is that **a module never moves from REFUSED to BADRUST, and never leaves COMPILES**: a lowering that starts emitting where
# it used to refuse has replaced a clear message with a rustc error in generated code the user did
# not write. Movement the other way — BADRUST to REFUSED, or either to COMPILES — is progress, and
# this gate asks you to record it rather than blocking it.
#
# NOT ON THE PUSH PATH, deliberately: 131 modules is minutes, against a whole-suite budget of 943 s.
#
# AND THE COST GROWS WITH SUCCESS, which is worth stating because it surprised me: a REFUSED module
# costs a parse, a COMPILES module costs a full cargo build. Every module moved out of BADRUST makes
# this gate slower, so a `timeout` sized against an early run will start killing it — mine did, at
# 900 s, once the compiling set reached 44. Size the CI step generously; the suite is not flaky, it
# is doing more work than it used to. It belongs beside `backendRust/test` on the periodic job. Run it by hand after
# touching the Rust backend; `--update` rewrites the baseline once you have read the diff.
#
# `--reasons` prints what the refusals SAY, grouped, straight from the baseline — no build. That is
# the roadmap the REFUSED column is supposed to be, and it was unreadable while only the class was
# recorded.
#
# THE BASELINE IS NOT A GOLDEN OF ERROR TEXT. rustc messages change with the compiler; only the
# CLASSIFICATION is frozen, plus the first error code, which is what tells one shape from another.
#
# AND IT IS THIS GATE'S OWN BASELINE, not the reporter's table. Run against `repro/std-rust-survey.tsv`
# the first time, it reported two "regressions" that were nothing of the sort: `std/dsl/pretty.ssc`
# and `std/ui/theme.ssc` are OTHER there and BADRUST here, because their classifier and mine draw the
# line in different places (their prose counts both among the bad-Rust 25, so we agree about the
# modules and disagree about the label). A baseline and a run must come from the SAME instrument or
# the diff measures the instruments. The reporter's table stays exactly as they sent it — it is the
# `repro:` of the entry, and overwriting it would destroy the evidence.
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
TOOLS="$ROOT/bin/ssc-tools"
BASE="$ROOT/tests/rust-std-survey-baseline.tsv"
[[ -x $TOOLS ]] || { echo "rust-std-survey: no launcher at $TOOLS — run ./install.sh --dev" >&2; exit 2; }
# The baseline is required to CHECK and created by --update, so the precondition is checked after
# the mode is known: demanding it unconditionally made the first baseline impossible to produce.
[[ -r $BASE || "${1:-}" == "--update" ]] || {
  echo "rust-std-survey: no baseline at $BASE — create it with: $0 --update" >&2; exit 2; }
# A VERDICT FROM A STALE TOOLCHAIN IS A VERDICT ABOUT THE WRONG CODE, and this gate learned it the
# expensive way: three modules were recorded `OTHER` in the baseline and classify `REFUSED` on a
# rebuilt launcher. The baseline is the thing everything else is compared against, so a wrong row in
# it is worse than a wrong run. `scripts/smoke-ci` has guarded this since 2026-08-04; the survey,
# which takes six minutes and writes a file, did not.
if [[ "${SSC_SURVEY_ALLOW_STALE:-}" != "1" && -r "$ROOT/bin/lib/.build-digest" ]]; then
  built="$(<"$ROOT/bin/lib/.build-digest")"
  want="$("$ROOT/scripts/launcher-input-digest" 2>/dev/null || true)"
  if [[ -n "$built" && -n "$want" && "$built" != "$want" ]]; then
    echo "rust-std-survey: the launcher was built from different sources than this tree." >&2
    echo "  staged inputs: ${built:0:12}" >&2
    echo "  this tree:     ${want:0:12}" >&2
    echo "Rebuild before measuring: ./install.sh --dev   (override: SSC_SURVEY_ALLOW_STALE=1)" >&2
    exit 2
  fi
fi

command -v cargo >/dev/null 2>&1 || {
  echo "rust-std-survey: no cargo — this gate cannot tell BADRUST from COMPILES here, and says so" >&2
  echo "rather than passing quietly. Install cargo or run it where one exists." >&2
  exit 2; }

update=0
[[ "${1:-}" == "--update" ]] && update=1

# `--reasons` reads the BASELINE and prints what the refusals actually say, most common first. No
# build, no cargo, seconds — the point is that the question "what should we implement next" is now
# answerable from a file instead of from a five-minute run.
if [[ "${1:-}" == "--reasons" ]]; then
  [[ -r $BASE ]] || { echo "rust-std-survey: no baseline at $BASE" >&2; exit 2; }
  echo "refusal reasons, most common first (from $BASE):"
  awk -F'\t' '$2 == "REFUSED" { print $3 }' "$BASE" | sort | uniq -c | sort -rn |
    awk '{ n = $1; $1 = ""; printf "%4d  %s\n", n, substr($0, 2) }'
  echo ""
  awk -F'\t' '{ c[$2]++ } END { for (k in c) printf "  %-9s %d\n", k, c[k] }' "$BASE"
  exit 0
fi

tmp=$(mktemp -d "${TMPDIR:-/tmp}/rust-survey.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

# Classify one module. The distinction that matters is REFUSED (our diagnostic) vs BADRUST (rustc's),
# and it is read off the LOG, not the exit code: a library module has no `main`, so `build-rust`
# exits non-zero with "expected binary not found" AFTER compiling it cleanly. Counting exit codes is
# what made the reporter's first pass say 131/131 failing; they caught it themselves and said so.
classify() { # classify <file> → "<class>\t<detail>"
  local f=$1 out rc
  set +e
  out=$(cd "$tmp" && "$TOOLS" build-rust "$ROOT/$f" 2>&1); rc=$?
  set -e
  # HERE-STRINGS, not `printf … | grep`. Under `set -o pipefail` a pipeline reports the LAST
  # non-zero status, and `grep -q` exits the moment it matches — which kills `printf` with SIGPIPE
  # (141). The pipeline then reads as FAILURE even though the pattern MATCHED, so a module was
  # classified by a branch that had already said yes. Reproduced deliberately: an early match in a
  # 400 KB output reads as "no match", and the same test with a here-string matches. It is why three
  # modules sat in `OTHER` that classify `REFUSED` when measured one at a time, and why CI logged
  # `printf: write error: Broken pipe` from a step that reported success.
  if   grep -qE '^error\[E[0-9]+\]|^error: expected|^error: could not compile' <<< "$out"; then
    printf 'BADRUST\t%s' "$(grep -oE '^error\[E[0-9]+\]' <<< "$out" | head -1)"
  elif grep -qE '\[error\] (Generic|Unsupported)\(' <<< "$out"; then
    # The REASON, normalised, not just the class. Recording `[error] Generic(` made the REFUSED
    # column a number and nothing else: 82 modules the backend cannot lower, with no way to ask WHAT
    # they need, so the roadmap it was supposed to be could not be read off it. Identifiers between
    # backticks are replaced with `_` so the same gap in twenty modules groups as one line.
    printf 'REFUSED\t%s' "$(grep -oE '\[error\] (Generic|Unsupported)\(.*' <<< "$out" | head -1 \
      | sed -e 's/^\[error\] //' -e 's/`[^`]*`/`_`/g' -e 's/,Some(rust)).*$//' \
      | cut -c1-110)"
  elif [[ $rc -eq 0 ]] || printf '%s' "$out" | grep -q 'expected binary not found'; then
    printf 'COMPILES\t'
  else
    printf 'OTHER\t%s' "$(head -1 <<< "$out" | cut -c1-40)"
  fi
}

modules=$(cd "$ROOT" && find std -name '*.ssc' | LC_ALL=C sort)
echo "rust-std-survey: $(printf '%s\n' "$modules" | grep -c .) modules — this takes several minutes" >&2

: > "$tmp/now.tsv"
for f in $modules; do
  printf '%s\t%s\n' "$f" "$(classify "$f")" >> "$tmp/now.tsv"
done

if [[ $update -eq 1 ]]; then
  cp "$tmp/now.tsv" "$BASE"
  echo "rust-std-survey: baseline rewritten — read the diff before committing it"
  awk -F'\t' '{c[$2]++} END {for (k in c) printf "  %-9s %d\n", k, c[k]}' "$BASE"
  exit 0
fi

# Compare CLASSIFICATIONS only.
join -t"$(printf '\t')" -j1 \
  <(cut -f1,2 "$BASE"    | LC_ALL=C sort) \
  <(cut -f1,2 "$tmp/now.tsv" | LC_ALL=C sort) > "$tmp/pairs.tsv" || true

regressions=$(awk -F'\t' '$2 != "BADRUST" && $3 == "BADRUST" {print "  " $1 ": " $2 " -> BADRUST"}' "$tmp/pairs.tsv")
# A module leaving COMPILES is a CAPABILITY LOSS and reads as progress under the BADRUST rule alone.
# Measured 2026-08-11: a refusal written against DECLARATIONS rather than against what is actually
# rendered took nine modules out of COMPILES while the BADRUST column fell — the gate said
# "IMPROVED" and only the baseline diff, read by hand, showed it. A gate that can be satisfied by
# giving up capability is the failure it exists to prevent.
lost=$(awk -F'\t' '$2 == "COMPILES" && $3 != "COMPILES" {print "  " $1 ": COMPILES -> " $3}' "$tmp/pairs.tsv")
progress=$(awk -F'\t'   '$2 == "BADRUST" && $3 != "BADRUST" {print "  " $1 ": BADRUST -> " $3}' "$tmp/pairs.tsv")
newmods=$(comm -13 <(cut -f1 "$BASE" | LC_ALL=C sort) <(cut -f1 "$tmp/now.tsv" | LC_ALL=C sort))

if [[ -n "$lost" ]]; then
  echo "rust-std-survey: a module that COMPILED no longer does" >&2
  echo "$lost" >&2
  echo "" >&2
  echo "The BADRUST column falling does not pay for this. A refusal is better than bad Rust, but" >&2
  echo "not better than working Rust — check the refusal is scoped to what is actually EMITTED." >&2
  exit 1
fi

if [[ -n "$regressions" ]]; then
  echo "rust-std-survey: a module that used to be REFUSED now emits Rust that rustc rejects" >&2
  echo "$regressions" >&2
  echo "" >&2
  echo "A refusal is a message the user can act on; bad generated code is not. If the lowering is" >&2
  echo "genuinely right and rustc's complaint is about something else, fix that first." >&2
  exit 1
fi

if [[ -n "$newmods" ]]; then
  echo "rust-std-survey: modules not in the baseline — classify them with --update:" >&2
  printf '  %s\n' $newmods >&2
  exit 1
fi

[[ -z "$progress" ]] || {
  echo "rust-std-survey: modules IMPROVED since the baseline — record it with --update:"
  echo "$progress"
  exit 1
}

awk -F'\t' '{c[$2]++} END {for (k in c) printf "  %-9s %d\n", k, c[k]}' "$tmp/now.tsv"
echo "rust-std-survey: ok — the BADRUST column has not grown"
