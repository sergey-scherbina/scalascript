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
# `--roadmap` answers the question `--reasons` only LOOKS like it answers, and the difference cost a
# day. A grouped reason counts each module once, by its FIRST refusal — so "sixteen modules want a
# no-paren collection member" was true and useless: the feature was built, ZERO of the sixteen
# compiled, and five turned into rustc errors. Two more columns per module fix it — how many defs
# refuse (`sites`) and in how many distinct ways (`shapes`) — and `--roadmap` ranks by the pair.
#
# NEITHER NUMBER IS A COUNT OF WORK REMAINING, because a refusal SHORT-CIRCUITS the walk: a def is
# abandoned at its first unlowerable thing, so everything behind it is unmeasured. `shapes` is a
# LOWER BOUND. What the pair genuinely separates is one shape at one site (std/fs.ssc — 2 rustc
# errors when lowered) from one shape at many (std/content-core.ssc — 207), and ranking clusters by
# size chooses the second kind every time.
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
# The launcher precondition is checked AFTER the read-only modes, not before. `--reasons` is
# documented as needing no build, and it does not — it reads the baseline — but demanding the
# launcher up front made it unusable from a fresh worktree, which is exactly where the question
# "what should I build next" gets asked. It also masked the control for the depth guard below: the
# missing-launcher message came out instead, so the guard could not be shown to fire.
require_launcher() {
  [[ -x $TOOLS ]] || { echo "rust-std-survey: no launcher at $TOOLS — run ./install.sh --dev" >&2; exit 2; }
}
# The baseline is required to CHECK and created by --update, so the precondition is checked after
# the mode is known: demanding it unconditionally made the first baseline impossible to produce.
[[ -r $BASE || "${1:-}" == "--update" ]] || {
  echo "rust-std-survey: no baseline at $BASE — create it with: $0 --update" >&2; exit 2; }
# A baseline written before the depth columns existed has no field 5, and both readers below would
# then print a confident table of zeroes and empties rather than saying they cannot answer. Silence
# is the failure mode these two modes exist to remove, so refuse instead.
has_depth() { awk -F'\t' '$2 == "REFUSED" && $5 != "" { found = 1 } END { exit !found }' "$BASE"; }
need_depth() {
  has_depth && return 0
  echo "rust-std-survey: this baseline predates the depth columns — no module carries sites/shapes." >&2
  echo "Re-measure before asking it what to build next: $0 --update" >&2
  exit 2
}

if [[ "${1:-}" == "--reasons" ]]; then
  [[ -r $BASE ]] || { echo "rust-std-survey: no baseline at $BASE" >&2; exit 2; }
  need_depth
  # TWO NUMBERS PER REASON, and the second is the one to read. `mentions` counts modules whose FIRST
  # refusal is this; `only` counts modules for which it is the ONLY distinct refusal shape. They are
  # not the same question and the gap between them is where a day went: the top reason had 16
  # mentions and 7 onlys, and even those 7 did not compile once it was lowered.
  echo "refusal reasons (from $BASE) — 'only' = modules with no OTHER refusal shape:"
  awk -F'\t' '$2 == "REFUSED" { m[$3]++; if ($5 == 1) o[$3]++ }
              END { for (k in m) printf "%d\t%d\t%s\n", m[k], o[k] + 0, k }' "$BASE" |
    LC_ALL=C sort -rn | awk -F'\t' '{ printf "%4d mentions %4d only   %s\n", $1, $2, $3 }'
  echo ""
  awk -F'\t' '{ c[$2]++ } END { for (k in c) printf "  %-9s %d\n", k, c[k] }' "$BASE"
  exit 0
fi

if [[ "${1:-}" == "--roadmap" ]]; then
  [[ -r $BASE ]] || { echo "rust-std-survey: no baseline at $BASE" >&2; exit 2; }
  need_depth
  # WHICH MODULES ARE ACTUALLY CLOSE, ranked by the pair (shapes, sites) — not by how big a cluster
  # looks. A module refusing in ONE shape at ONE site is one def away; a module refusing in one
  # shape at forty sites has forty defs that have never been walked past their first blocker, and
  # lowering that shape reaches all forty at once. Ranking by cluster size chooses the second kind.
  echo "modules nearest to COMPILES (from $BASE) — 'sites' defs refuse, in 'shapes' distinct ways:"
  echo ""
  printf '  %-34s %5s %6s  %s\n' module sites shapes "sole blocker (only shown when shapes = 1)"
  awk -F'\t' '$2 == "REFUSED" { printf "%s\t%s\t%s\t%s\n", $4, $5, $1, ($5 == 1 ? $3 : "") }' "$BASE" |
    LC_ALL=C sort -t"$(printf '\t')" -k2,2n -k1,1n |
    awk -F'\t' '{ printf "  %-34s %5d %6d  %s\n", $3, $1, $2, substr($4, 1, 76) }'
  echo ""
  echo "READ shapes AS A LOWER BOUND. A refusal short-circuits the walk — the def is abandoned at"
  echo "its first unlowerable thing, so whatever sits behind it was never reached and cannot appear"
  echo "here. std/content-core.ssc is one shape and produced 207 rustc errors when that shape was"
  echo "lowered; std/fs.ssc is one shape at one site and produced 2. The pair is the signal."
  exit 0
fi

require_launcher

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
    #
    # AND THE DEPTH, in two more columns, because the reason ALONE is a misleading roadmap and it
    # misled me for a day. `--reasons` said sixteen modules wanted a no-paren collection member; I
    # built it, and ZERO of them compiled while five turned into rustc errors. The reason column
    # counts each module ONCE, by its FIRST refusal — so a module with that gap and six others
    # behind it is indistinguishable from a module for which it is the only thing left.
    #
    #   sites   how many defs refuse at all. std/fs.ssc is 1; std/agent.ssc is 18.
    #   shapes  how many DISTINCT reasons those refusals have. fs 1, agent 6.
    #
    # READ `shapes` AS A LOWER BOUND, NEVER AS A COUNT OF WORK REMAINING. A refusal SHORT-CIRCUITS
    # the walk: the def is abandoned at the first thing that cannot be lowered, so anything behind
    # it in that def has never been reached and cannot appear here. std/content-core.ssc is one
    # shape over many sites and produced 207 rustc errors the moment that shape was lowered.
    # The pair is what discriminates: one shape over ONE site is close, one shape over forty is not.
    local refusals shapes sites
    refusals=$(grep -oE '\[error\] (Generic|Unsupported)\(.*' <<< "$out" \
      | sed -e 's/^\[error\] //' -e 's/`[^`]*`/`_`/g' -e 's/,Some(rust)).*$//' | cut -c1-110)
    # `grep -c` EXITS 1 ON ZERO MATCHES, and a failed command substitution under `set -e` kills the
    # script — so both counts are guarded even though this branch cannot be reached with no refusal.
    sites=$(grep -c . <<< "$refusals" || true)
    shapes=$(LC_ALL=C sort -u <<< "$refusals" | grep -c . || true)
    printf 'REFUSED\t%s\t%s\t%s' "$(head -1 <<< "$refusals")" "$sites" "$shapes"
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

# THE DEPTH COLUMNS CHECK THEMSELVES, before anything is written or compared. A column that is
# written but never read passes every byte-equality gate no matter what is in it, and these two are
# read by humans deciding what to build next — the most expensive kind of wrong number. So assert
# the INVARIANTS rather than fixture values: a module's counts move as the backend improves, and a
# self-test frozen to `std/fs.ssc is 1,1` would fail the day someone fixes std/fs.ssc, which is
# backwards. Each of these fails under a DIFFERENT wrong implementation:
#
#   shapes <= sites always      — nonsense otherwise
#   some module has shapes < sites   — otherwise `sort -u` is not deduplicating
#   some module has sites > 1        — otherwise sites is `head -1` counted, i.e. always 1
#
depth=$(awk -F'\t' '
  $2 == "REFUSED" {
    n++
    if ($4 == "" || $5 == "")     { bad = bad "  " $1 ": empty depth columns\n" }
    else if ($5 > $4)             { bad = bad "  " $1 ": shapes " $5 " > sites " $4 "\n" }
    else if ($5 < 1)              { bad = bad "  " $1 ": shapes " $5 " < 1\n" }
    if ($5 < $4) dedup++
    if ($4 > 1)  multi++
  }
  END {
    # ORDER MATTERS, and only because the checks are not independent: `shapes < sites` needs some
    # module with sites >= 2, so a `sites` capped at 1 fails BOTH and the dedup message would be
    # reported for a defect that is not dedup. Asking the more specific question first is what keeps
    # each message true of the thing it names — and keeps the second check reachable at all.
    if (n == 0)      bad = bad "  no REFUSED module at all — cannot validate the depth columns\n"
    else if (!multi) bad = bad "  no module has sites > 1 — sites is counting only the first refusal\n"
    else if (!dedup) bad = bad "  no module has shapes < sites — sort -u is not deduplicating\n"
    printf "%s", bad
  }' "$tmp/now.tsv")
if [[ -n "$depth" ]]; then
  echo "rust-std-survey: the depth columns do not mean what --roadmap says they mean" >&2
  printf '%s' "$depth" >&2
  exit 1
fi

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
