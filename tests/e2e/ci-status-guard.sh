#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/ssc-ci-status.XXXXXX")"
FAKE_GH="$TMP/gh"
SHA="abc123abc123abc123abc123abc123abc123abc1"
CLAIM_WT=""
CLAIM_BRANCH=""

cleanup() {
  if [[ -n "$CLAIM_WT" ]]; then
    git -C "$ROOT" worktree remove --force "$CLAIM_WT" >/dev/null 2>&1 || true
  fi
  if [[ -n "$CLAIM_BRANCH" ]]; then
    git -C "$ROOT" branch -D "$CLAIM_BRANCH" >/dev/null 2>&1 || true
  fi
  rm -rf "$TMP"
}
trap cleanup EXIT

cat > "$FAKE_GH" <<'FAKE_GH'
#!/usr/bin/env bash
set -euo pipefail

mode="${FAKE_CI_MODE:?}"
args=" $* "
expected_sha="${FAKE_EXPECT_SHA:-abc123abc123abc123abc123abc123abc123abc1}"
# Which workflow this case is modelling. The DEFAULT is smoke.yml, because that is what
# `scripts/ci-status` with no arguments now asks about (the per-push verdict moved there on
# 2026-07-30). The ci.yml cases below set this explicitly AND pass `--workflow ci.yml`, so the two
# halves of every case agree about what is being simulated.
wf="${FAKE_CI_WORKFLOW:-smoke.yml}"

if [[ "$args" == *" run list "* ]]; then
  for required in "--workflow $wf" "--branch main" "--event push" \
                  "--commit $expected_sha"; do
    if [[ "$args" != *" $required "* ]]; then
      printf 'fake gh: missing exact-run filter %s in args: %s\n' "$required" "$*" >&2
      exit 64
    fi
  done

  case "$mode" in
    gh-fail)
      printf 'simulated GitHub query failure\n' >&2
      exit 1
      ;;
    no-run)
      exit 0
      ;;
    pending)
      status="in_progress"
      conclusion=""
      ;;
    red|missing)
      status="completed"
      conclusion="failure"
      ;;
    smoke-green|smoke-missing|push-lint-only)
      status="completed"
      conclusion="success"
      ;;
    smoke-red)
      status="completed"
      conclusion="failure"
      ;;
    push-no-sbt|sched-no-sbt|one-shard-missing|negtc-shard-missing|negtc-reduce-missing|tier2|tier2-no-examples)
      # `one-shard-missing` reports the RUN as successful on purpose: GitHub marks a run green when
      # every job it actually ran passed, so a matrix instance that never got created leaves a green
      # run with a hole in it. That is precisely the case ci-status must still call RED.
      status="completed"
      conclusion="success"
      ;;
    green)
      status="completed"
      conclusion="success"
      ;;
    *)
      printf 'fake gh: unknown mode %s\n' "$mode" >&2
      exit 64
      ;;
  esac

  printf 'RUN_ID=42\n'
  printf 'RUN_SHA=%s\n' "$expected_sha"
  printf 'RUN_STATUS=%s\n' "$status"
  printf 'RUN_CONCLUSION=%s\n' "$conclusion"
  printf 'RUN_URL=https://example.invalid/actions/runs/42\n'
  printf 'RUN_EVENT=%s\n' "${FAKE_CI_EVENT:-push}"
  exit 0
fi

shard_jobs() { # shard_jobs <status> <conclusion> — the 4-way conformance matrix, all alike
  printf 'Examples and launcher smokes|%s|%s\n' "$1" "$2"
  for i in 0 1 2 3; do printf 'Conformance shard %s/4|%s|%s\n' "$i" "$1" "$2"; done
}

# The SECOND matrix, added 2026-07-31 when the negative-toolchain release gate left the `sbt` job
# (negtc-gate-shard-reduce). Kept separate from `shard_jobs` rather than folded into it: the cases
# below deliberately vary ONE matrix at a time, and a helper that emitted both would make
# `one-shard-missing` unable to say which suite shrank.
negtc_jobs_no_reduce() { for i in 0 1 2 3; do printf 'negtc sweeps %s/4|completed|success\n' "$i"; done; }
negtc_jobs() { # negtc_jobs <status> <conclusion>
  for i in 0 1 2 3; do printf 'negtc sweeps %s/4|%s|%s\n' "$i" "$1" "$2"; done
  printf 'negtc release gate (reduce)|%s|%s\n' "$1" "$2"
}

if [[ "$args" == *" run view 42 "* ]]; then
  # smoke.yml has exactly ONE job, and ci-status judges it by the generic rule (every job in the run
  # succeeded) rather than a hard-coded name — so a rename of that job cannot make the verdict tool
  # stale, which is the failure mode the ci.yml branch has had three times.
  if [[ "$wf" == "smoke.yml" ]]; then
    case "$mode" in
      smoke-green)   printf 'smoke — the fast repo-wide suite|completed|success\n' ;;
      smoke-red)     printf 'smoke — the fast repo-wide suite|completed|failure\n' ;;
      pending)       printf 'smoke — the fast repo-wide suite|in_progress|\n' ;;
      red)           printf 'smoke — the fast repo-wide suite|completed|failure\n' ;;
      smoke-missing) : ;;   # a run that started no job at all
      *)             printf 'smoke — the fast repo-wide suite|completed|success\n' ;;
    esac
    exit 0
  fi

  # Lint is in every ci.yml run. Validate is NOT: since 2026-07-30 it carries
  # `if: github.event_name != 'push'`, so emitting it unconditionally would have made
  # `push-lint-only` a fixture that contradicts its own name — it would have proved a push run is
  # green WITH Validate present, which is not the shape being tested.
  printf 'Lint Markdown|completed|success\n'
  [[ "$mode" == "push-lint-only" ]] || printf 'Validate ScalaScript|completed|success\n'
  case "$mode" in
    green)
      shard_jobs completed success
      negtc_jobs completed success
      printf 'sbt — compile and test|completed|success\n'
      ;;
    red)
      # Only shard 2 is red. That is deliberate: a matrix makes it possible for the verdict to hinge
      # on ONE instance, and a check that only ever sees all-red or all-green cannot tell the
      # difference between "every shard failed" and "the list is not really per-shard".
      printf 'Examples and launcher smokes|completed|success\n'
      printf 'Conformance shard 0/4|completed|success\n'
      printf 'Conformance shard 1/4|completed|success\n'
      printf 'Conformance shard 2/4|completed|failure\n'
      printf 'Conformance shard 3/4|completed|success\n'
      negtc_jobs completed success
      printf 'sbt — compile and test|completed|cancelled\n'
      ;;
    pending)
      printf 'Examples and launcher smokes|completed|success\n'
      printf 'Conformance shard 0/4|completed|success\n'
      printf 'Conformance shard 1/4|completed|success\n'
      printf 'Conformance shard 2/4|in_progress|\n'
      printf 'Conformance shard 3/4|completed|success\n'
      negtc_jobs completed success
      printf 'sbt — compile and test|queued|\n'
      ;;
    missing)
      # A job that is required on EVERY event, so this case keeps meaning what it always meant.
      printf 'sbt — compile and test|completed|success\n'
      ;;
    push-no-sbt|sched-no-sbt)
      # Was "the shape a real push run has". Since 2026-07-30 it is the shape a SCHEDULED run has;
      # kept for the sbt-absence pair below, which still needs a run carrying everything but sbt.
      # negtc is present so `sched-no-sbt` isolates the ONE absence it names — otherwise the case
      # would pass on a substring while five other jobs were also missing.
      shard_jobs completed success
      negtc_jobs completed success
      ;;
    tier2-no-examples)
      # Tier 2 minus ONE job. Without this, dropping a name from ci-status's required list is
      # invisible: every remaining assertion still passes, because a green run with fewer
      # requirements is still green. MEASURED while writing this — deleting "Examples and launcher
      # smokes" from the list left the whole guard PASSING.
      printf 'Conformance shard 0/4|completed|success\n'
      printf 'Conformance shard 1/4|completed|success\n'
      printf 'Conformance shard 2/4|completed|success\n'
      printf 'Conformance shard 3/4|completed|success\n'
      ;;
    tier2)
      # THE shape a push run has since 2026-08-01: the default verdict is lint + validate + the four
      # conformance shards + examples. sbt and negtc are tier 3 and are absent here on purpose —
      # if ci-status ever demands one of those on a push, this case goes red.
      shard_jobs completed success
      ;;
    push-lint-only)
      # THE shape a real ci.yml PUSH run has since 2026-07-30 (smoke-ci): Validate, the four shards
      # and Examples each carry `if: github.event_name != 'push'`, so `Lint Markdown` is the only job
      # in the run. Nothing extra is printed here — the two lines above this `case` already emitted
      # Lint and Validate, so this branch deliberately adds none, and the case asserts GREEN. If
      # ci-status ever demands a non-push job on a push run again, this case goes red.
      :
      ;;
    one-shard-missing)
      # A dropped MATRIX INSTANCE. Before sharding this could not happen; now it is the most likely
      # way for the suite to silently shrink, so it gets its own case.
      printf 'Examples and launcher smokes|completed|success\n'
      printf 'Conformance shard 0/4|completed|success\n'
      printf 'Conformance shard 1/4|completed|success\n'
      printf 'Conformance shard 3/4|completed|success\n'
      negtc_jobs completed success
      ;;
    negtc-shard-missing)
      # The same failure as `one-shard-missing`, in the OTHER matrix. Two matrices now feed one
      # required-job list, and a case that only ever drops a conformance instance cannot tell
      # whether the list is really per-shard for negtc too.
      shard_jobs completed success
      printf 'sbt — compile and test|completed|success\n'
      printf 'negtc sweeps 0/4|completed|success\n'
      printf 'negtc sweeps 1/4|completed|success\n'
      printf 'negtc sweeps 3/4|completed|success\n'
      printf 'negtc release gate (reduce)|completed|success\n'
      ;;
    negtc-reduce-missing)
      # The verdict-carrying half absent while every shard is green — the shape a `needs:` skip
      # produces. It must read RED, not green-because-the-shards-passed.
      shard_jobs completed success
      printf 'sbt — compile and test|completed|success\n'
      negtc_jobs_no_reduce
      ;;
    *)
      printf 'fake gh: unexpected view mode %s\n' "$mode" >&2
      exit 64
      ;;
  esac
  exit 0
fi

printf 'fake gh: unsupported args: %s\n' "$*" >&2
exit 64
FAKE_GH
chmod +x "$FAKE_GH"

run_case() {
  local mode="$1"
  local expected_code="$2"
  shift 2

  local output
  local code
  local wf_args=()
  # `CASE_WF` selects which workflow the case is about. Unset = the DEFAULT no-argument query, which
  # is smoke.yml since 2026-07-30. `CASE_WF=ci.yml` asks the old question explicitly, and passes the
  # same name to the fake so the simulated run has ci.yml's job shape rather than smoke's.
  [[ -n "${CASE_WF:-}" ]] && wf_args=(--workflow "$CASE_WF")
  set +e
  output="$(FAKE_CI_MODE="$mode" SSC_CI_GH="$FAKE_GH" FAKE_CI_WORKFLOW="${CASE_WF:-smoke.yml}" \
    "$ROOT/scripts/ci-status" --sha "$SHA" "${wf_args[@]}" 2>&1)"
  code=$?
  set -e

  if [[ "$code" -ne "$expected_code" ]]; then
    printf 'ci-status-guard[%s]: expected exit=%s, got=%s\n%s\n' \
      "$mode" "$expected_code" "$code" "$output" >&2
    exit 1
  fi

  local needle
  for needle in "$@"; do
    if [[ "$output" != *"$needle"* ]]; then
      printf 'ci-status-guard[%s]: expected output to contain=%q, got:\n%s\n' \
        "$mode" "$needle" "$output" >&2
      exit 1
    fi
  done
}

# ── the DEFAULT query: smoke.yml, the per-push verdict since 2026-07-30 ────────────────────────
# Both directions, because a one-sided check cannot tell "judged the run's own jobs" from "recognised
# nothing and defaulted to pass" — the same argument the corpus-contract cases below make.
run_case smoke-green 0 "CI GREEN $SHA" "smoke — the fast repo-wide suite: completed/success"
run_case smoke-red   1 "CI RED $SHA"   "smoke — the fast repo-wide suite: completed/failure"
run_case pending     2 "CI PENDING $SHA"
# A smoke run that started NO job is evidence of nothing, not "green because nothing failed".
run_case smoke-missing 1 "CI RED $SHA" "ZERO jobs"
# The headline must name the workflow it actually asked about. When the default was ci.yml and the
# per-push verdict silently moved, this line is what tells a reader which question was answered.
run_case no-run  2 "CI UNKNOWN $SHA" "no push smoke.yml run found"
run_case gh-fail 2 "CI UNKNOWN $SHA" "gh run list failed"

# ── ci.yml: the full-suite question, now asked explicitly ──────────────────────────────────────
# Every case below runs as a SCHEDULED event, because that is where Validate / the four Conformance
# shards / Examples / sbt actually run. Asserting them on a `push` run would be asserting the bug
# this commit fixes.
CASE_WF=ci.yml FAKE_CI_EVENT=schedule run_case green 0 "CI GREEN $SHA" "Conformance shard 0/4: completed/success"
# The second assertion pins "cancelled is RED, not neutral".
CASE_WF=ci.yml FAKE_CI_EVENT=schedule run_case red 1 "CI RED $SHA" "Conformance shard 2/4: completed/failure" \
  "sbt — compile and test: completed/cancelled"
CASE_WF=ci.yml FAKE_CI_EVENT=schedule run_case pending 2 "CI PENDING $SHA" "Conformance shard 2/4: in_progress/pending"
CASE_WF=ci.yml FAKE_CI_EVENT=schedule run_case missing 1 "CI RED $SHA" "missing required job: Conformance shard 0/4"
# THE CASE THIS COMMIT EXISTS FOR. A real ci.yml PUSH run now contains `Lint Markdown` and nothing
# else. Requiring Validate/Conformance/Examples there made the verdict tool report `missing required
# job` on every future green push run — the tool contradicting the workflow it verifies, for the third
# time in this one function. Green here, and RED on the schedule (next case), pins both directions:
# absent-on-push is legitimate, absent-on-schedule is still a dropped job.
# TIER 2 (ci-three-tiers, 2026-08-01): a push carries lint + validate + the shards + examples, so
# the lint-ONLY shape is now RED on a push too — it used to be the green one. Both directions are
# pinned: the tier-2 shape is green on a push, and a run missing the tier is red on every event.
CASE_WF=ci.yml FAKE_CI_EVENT=push     run_case push-lint-only 1 "CI RED $SHA" "missing required job: Validate ScalaScript"
CASE_WF=ci.yml FAKE_CI_EVENT=schedule run_case push-lint-only 1 "CI RED $SHA" "missing required job: Validate ScalaScript"
CASE_WF=ci.yml FAKE_CI_EVENT=push     run_case tier2 0 "CI GREEN $SHA" "Conformance shard 0/4: completed/success"
# Each tier-2 name must be REQUIRED, not merely present. A run missing one has to read RED, or the
# default verdict can quietly stop covering it. Validate is pinned by `push-lint-only` above and the
# shards by `one-shard-missing` below; this is the Examples half.
CASE_WF=ci.yml FAKE_CI_EVENT=push run_case tier2-no-examples 1 "CI RED $SHA" "missing required job: Examples and launcher smokes"
# `sbt — compile and test` runs only on non-push events (ci.yml `if:`), because at 196 min against a
# 7-min mean push interval at most 1 commit in 28 could ever reach a verdict (BUGS
# ci-sbt-job-is-28x-the-code-push-interval). These two pin BOTH directions of that: absent-on-push is
# legitimate, absent-on-schedule is still RED. Without the second case the first would silently excuse
# a genuinely dropped sbt job.
CASE_WF=ci.yml FAKE_CI_EVENT=push     run_case push-no-sbt  0 "CI GREEN $SHA" "Lint Markdown: completed/success"
# A dropped matrix INSTANCE must be RED. Sharding created this failure mode; without this
# case a suite that quietly lost a quarter of the corpus would still report green.
CASE_WF=ci.yml FAKE_CI_EVENT=schedule run_case one-shard-missing 1 "CI RED $SHA" "missing required job: Conformance shard 2/4"
# The same hole in the OTHER matrix, and in the job that carries the verdict. Both runs are
# GREEN as far as GitHub is concerned — every job that ran passed — which is exactly why a
# required-job list has to name them.
CASE_WF=ci.yml FAKE_CI_EVENT=workflow_dispatch run_case negtc-shard-missing 1 "CI RED $SHA" "missing required job: negtc sweeps 2/4"
CASE_WF=ci.yml FAKE_CI_EVENT=workflow_dispatch run_case negtc-reduce-missing 1 "CI RED $SHA" "missing required job: negtc release gate (reduce)"
CASE_WF=ci.yml FAKE_CI_EVENT=schedule run_case sched-no-sbt 1 "CI RED $SHA"   "missing required job: sbt — compile and test"
CASE_WF=ci.yml run_case no-run 2 "CI UNKNOWN $SHA" "no push ci.yml run found"

# ── descendant coverage (BUGS ci-status-sha-misses-commits-covered-by-a-later-tip) ─────────────
# GitHub creates ONE run per PUSH, attributed to the push's TIP. A code commit pushed together with
# a later docs commit therefore has no run of its own, and the old answer — CI UNKNOWN — was wrong
# in the expensive direction: it says "unverified" about a commit that was fully tested.
#
# These cases need REAL commits with a REAL parent edge, because `ci-status` answers with
# `git merge-base --is-ancestor` against this repository, not a string comparison. The negative
# case is the one that matters: a run whose head is NOT a descendant must still be UNKNOWN, or the
# fallback would accept any recent run as evidence for anything.
#
# The pair is BUILT here rather than read out of the ambient history, and that is the fix for
# `Validate ScalaScript` having been red on every CI run. It used to be:
#
#   DESC_SHA="$(git rev-parse origin/main 2>/dev/null || git rev-parse HEAD)"
#   ANC_SHA="$(git rev-parse "${DESC_SHA}~5" 2>/dev/null || echo "$DESC_SHA")"
#
# `actions/checkout` clones with `fetch-depth: 1`, so `<tip>~5` does not exist in CI. And
# `git rev-parse` WITHOUT `--verify` does not fail cleanly on an unresolvable revision: it ECHOES
# the argument to stdout and exits 128. The `||` therefore APPENDED the fallback instead of
# replacing it, and `ANC_SHA` became two lines — `<sha>~5` followed by `<sha>`. `ci-status` was
# then asked about the literal string `<sha>~5`, which of course has no run:
#
#   ci-status-guard[desc-green]: expected exit=0 got=2
#   CI UNKNOWN d684e68971c75ac11042f19f84fc32c1070fb064~5
#
# `commit-tree` writes two objects with an explicit parent edge straight into this repository's
# object database: no refs, no index, no working tree, nothing to clean up, and — the point —
# no dependence on how deep the checkout is. The identity is passed by env so the test does not
# require a configured `user.email`, which a CI checkout does not have.
GUARD_TREE="$(git -C "$ROOT" hash-object -t tree /dev/null)"
ANC_SHA="$(GIT_AUTHOR_NAME=ci-status-guard GIT_AUTHOR_EMAIL=guard@invalid \
           GIT_COMMITTER_NAME=ci-status-guard GIT_COMMITTER_EMAIL=guard@invalid \
           git -C "$ROOT" commit-tree "$GUARD_TREE" -m 'ci-status-guard fixture: ancestor' </dev/null)"
DESC_SHA="$(GIT_AUTHOR_NAME=ci-status-guard GIT_AUTHOR_EMAIL=guard@invalid \
            GIT_COMMITTER_NAME=ci-status-guard GIT_COMMITTER_EMAIL=guard@invalid \
            git -C "$ROOT" commit-tree "$GUARD_TREE" -p "$ANC_SHA" -m 'ci-status-guard fixture: descendant' </dev/null)"
# Fail loudly rather than silently degenerating: if these ever collapsed to the same commit, or the
# edge were not real, `desc-green` would pass for the wrong reason and `desc-none` would be vacuous.
[[ -n "$ANC_SHA" && -n "$DESC_SHA" && "$ANC_SHA" != "$DESC_SHA" ]] \
  || { printf 'ci-status-guard: could not build the ancestry fixture (anc=%q desc=%q)\n' "$ANC_SHA" "$DESC_SHA" >&2; exit 1; }
git -C "$ROOT" merge-base --is-ancestor "$ANC_SHA" "$DESC_SHA" \
  || { printf 'ci-status-guard: fixture ancestry is not real (%s is not an ancestor of %s)\n' "$ANC_SHA" "$DESC_SHA" >&2; exit 1; }
git -C "$ROOT" merge-base --is-ancestor "$DESC_SHA" "$ANC_SHA" \
  && { printf 'ci-status-guard: fixture ancestry is symmetric, so desc-none proves nothing\n' >&2; exit 1; } || true

FAKE_DESC="$TMP/gh-desc"
cat > "$FAKE_DESC" <<'FAKE_DESC_EOF'
#!/usr/bin/env bash
set -euo pipefail
mode="${FAKE_DESC_MODE:?}"
args=" $* "

if [[ "$args" == *" run list "* ]]; then
  # The exact-SHA query finds nothing — that IS the bug's situation.
  if [[ "$args" == *" --commit "* ]]; then exit 0; fi
  # The fallback query must NOT pin a commit and must ask for a real candidate window.
  if [[ "$args" != *" --limit 40 "* ]]; then
    printf 'fake gh: fallback query expected --limit 40: %s
' "$*" >&2; exit 64
  fi
  case "$mode" in
    desc-green) printf '%s|77|completed|success|https://example.invalid/actions/runs/77
' "$FAKE_DESC_HEAD" ;;
    desc-red)   printf '%s|77|completed|failure|https://example.invalid/actions/runs/77
' "$FAKE_DESC_HEAD" ;;
    desc-none)  printf '%s|77|completed|success|https://example.invalid/actions/runs/77
' "$FAKE_DESC_HEAD" ;;
  esac
  exit 0
fi

if [[ "$args" == *" run view 77 "* ]]; then
  printf 'Lint Markdown|completed|success
'
  printf 'Validate ScalaScript|completed|success
'
  printf 'Examples and launcher smokes|completed|success
'
  case "$mode" in
    desc-red) for i in 0 1 2 3; do printf 'Conformance shard %s/4|completed|failure
' "$i"; done ;;
    *)        for i in 0 1 2 3; do printf 'Conformance shard %s/4|completed|success
' "$i"; done ;;
  esac
  printf 'sbt — compile and test|completed|success
'
  exit 0
fi

printf 'fake gh: unsupported args: %s
' "$*" >&2
exit 64
FAKE_DESC_EOF
chmod +x "$FAKE_DESC"

desc_case() { # desc_case <mode> <head-sha> <requested-sha> <expected-code> <extra-args…|-> <needle…>
  local mode="$1" head="$2" want="$3" expected="$4" extra="$5"; shift 5
  local output code args=()
  [[ "$extra" != "-" ]] && args=("$extra")
  set +e
  output="$(FAKE_DESC_MODE="$mode" FAKE_DESC_HEAD="$head" SSC_CI_GH="$FAKE_DESC" \
    "$ROOT/scripts/ci-status" --sha "$want" "${args[@]}" 2>&1)"
  code=$?
  set -e
  if [[ "$code" -ne "$expected" ]]; then
    printf 'ci-status-guard[%s]: expected exit=%s got=%s\n%s\n' "$mode" "$expected" "$code" "$output" >&2
    exit 1
  fi
  local needle
  for needle in "$@"; do
    if [[ "$output" != *"$needle"* ]]; then
      printf 'ci-status-guard[%s]: expected output to contain=%q, got:\n%s\n' "$mode" "$needle" "$output" >&2
      exit 1
    fi
  done
}

# A green run on a DESCENDANT covers the ancestor — and must say so in the headline, never silently.
desc_case desc-green "$DESC_SHA" "$ANC_SHA" 0 - "CI GREEN (descendant)" "covered by: $DESC_SHA"
# A red descendant is red for the ancestor too.
desc_case desc-red   "$DESC_SHA" "$ANC_SHA" 1 - "CI RED (descendant)"
# THE NEGATIVE THAT MATTERS: the only run available is an ANCESTOR, not a descendant. It proves
# nothing about the requested commit, so the answer must stay UNKNOWN.
desc_case desc-none  "$ANC_SHA"  "$DESC_SHA" 2 - "CI UNKNOWN"
# --exact-only restores the strict answer, which also proves the three verdicts above came from the
# fallback rather than from some unrelated path.
desc_case desc-green "$DESC_SHA" "$ANC_SHA" 2 --exact-only "CI UNKNOWN"

# An ABBREVIATED --sha must resolve to the full commit before anything compares it. GitHub returns
# 40-char head SHAs and the exact-match test is a string comparison, so `--sha <9 chars>` matched
# nothing, fell through to the descendant fallback, and announced
# `CI GREEN (descendant) … covered by: <the same commit> — this commit has no run of its own`
# about a commit that had a run of its own. Green either way; the sentence was false, and a
# release-claim quoting it would be repeating a claim about the wrong relationship.
#
# This uses the REAL fixture commit built above, because an abbreviation can only be expanded against
# an object this repository actually has — the 40-char fake SHA the cases above use is not one.
set +e
abbrev_out="$(FAKE_CI_MODE=smoke-green FAKE_EXPECT_SHA="$DESC_SHA" SSC_CI_GH="$FAKE_GH" \
  "$ROOT/scripts/ci-status" --sha "${DESC_SHA:0:9}" 2>&1)"
abbrev_code=$?
set -e
if [[ "$abbrev_code" -ne 0 || "$abbrev_out" != *"CI GREEN $DESC_SHA"* || "$abbrev_out" == *"(descendant)"* ]]; then
  printf 'ci-status-guard[abbrev-sha]: expected an EXACT green for the full SHA, got exit=%s:\n%s\n' \
    "$abbrev_code" "$abbrev_out" >&2
  printf '  requested=%s expanded-to=%s\n' "${DESC_SHA:0:9}" "$DESC_SHA" >&2
  exit 1
fi

# A run with ZERO jobs is a run that never started one — today, queue eviction or a concurrency
# supersede. It must NOT be reported as "the workflow dropped four required jobs", which sends the
# reader after a config problem that does not exist. Observed on the real run 30310314697.
FAKE_NOJOBS="$TMP/gh-nojobs"
cat > "$FAKE_NOJOBS" <<'FAKE_NOJOBS_EOF'
#!/usr/bin/env bash
set -euo pipefail
args=" $* "
if [[ "$args" == *" run list "* ]]; then
  printf 'RUN_ID=88\n'
  printf 'RUN_SHA=%s\n' "${FAKE_NOJOBS_SHA:?}"
  printf 'RUN_STATUS=completed\n'
  printf 'RUN_CONCLUSION=cancelled\n'
  printf 'RUN_URL=https://example.invalid/actions/runs/88\n'
  exit 0
fi
if [[ "$args" == *" run view 88 "* ]]; then exit 0; fi   # cancelled before any job existed
printf 'fake gh: unsupported args: %s\n' "$*" >&2
exit 64
FAKE_NOJOBS_EOF
chmod +x "$FAKE_NOJOBS"

set +e
nojobs_out="$(FAKE_NOJOBS_SHA="$SHA" SSC_CI_GH="$FAKE_NOJOBS" "$ROOT/scripts/ci-status" --sha "$SHA" 2>&1)"
nojobs_code=$?
set -e
if [[ "$nojobs_code" -ne 1 ]]; then
  printf 'ci-status-guard[no-jobs]: expected exit=1, got=%s\n%s\n' "$nojobs_code" "$nojobs_out" >&2
  exit 1
fi
if [[ "$nojobs_out" != *"ZERO jobs"* ]]; then
  printf 'ci-status-guard[no-jobs]: expected the zero-jobs explanation, got:\n%s\n' "$nojobs_out" >&2
  exit 1
fi
if [[ "$nojobs_out" == *"missing required job"* ]]; then
  printf 'ci-status-guard[no-jobs]: reported missing jobs for a run that never started one:\n%s\n' "$nojobs_out" >&2
  exit 1
fi

# ── non-ci workflows: the blind spot (BUGS ci-status-blind-to-non-ci-workflows) ────────────────
# ci-status hardcoded `--workflow ci.yml --event push`, so the four other workflows were invisible
# to every automated check. Measured 2026-07-27: corpus-contract.yml had 0 successes in 12 runs, all
# scheduled. These cases assert that a scheduled workflow can now be queried AND judged — and, just
# as importantly, that its verdict is derived from its OWN jobs rather than ci.yml's four names,
# which no other workflow has.
FAKE_WF="$TMP/gh-wf"
cat > "$FAKE_WF" <<'FAKE_WF_EOF'
#!/usr/bin/env bash
set -euo pipefail
mode="${FAKE_WF_MODE:?}"
args=" $* "

if [[ "$args" == *" run list "* ]]; then
  # --all-workflows asks per file with --limit 1 and no branch/event/commit filter.
  if [[ "$args" == *" --limit 1 "* ]]; then
    case "$args" in
      *" --workflow ci.yml "*)              printf 'completed|success|push|abc123abc|2026-07-27T10:00:00Z|https://example.invalid/1\n' ;;
      *" --workflow corpus-contract.yml "*) printf 'completed|cancelled|schedule|def456def|2026-07-27T03:00:00Z|https://example.invalid/2\n' ;;
      *" --workflow never-run.yml "*)       printf 'NONE\n' ;;
      *)                                    printf 'completed|success|push|abc123abc|2026-07-27T10:00:00Z|https://example.invalid/3\n' ;;
    esac
    exit 0
  fi
  # Single-workflow query. A scheduled run must NOT be filtered by --event push, and --latest must
  # not pin a --commit: assert both, so a regression to the old hardcoded filters fails here.
  if [[ "$args" == *" --event push "* ]]; then
    printf 'fake gh: --event push must not be sent for a scheduled query: %s\n' "$*" >&2; exit 64
  fi
  if [[ "$args" == *" --commit "* ]]; then
    printf 'fake gh: --latest must not pin a commit: %s\n' "$*" >&2; exit 64
  fi
  if [[ "$args" != *" --workflow corpus-contract.yml "* ]]; then
    printf 'fake gh: expected the requested workflow in args: %s\n' "$*" >&2; exit 64
  fi
  printf 'RUN_ID=99\n'
  printf 'RUN_SHA=fedcba987654321fedcba987654321fedcba9876\n'
  printf 'RUN_STATUS=completed\n'
  case "$mode" in
    wf-green) printf 'RUN_CONCLUSION=success\n' ;;
    wf-red)   printf 'RUN_CONCLUSION=failure\n' ;;
  esac
  printf 'RUN_URL=https://example.invalid/actions/runs/99\n'
  exit 0
fi

if [[ "$args" == *" run view 99 "* ]]; then
  case "$mode" in
    wf-green) printf 'Corpus Contract (shard 1/4)|completed|success\n'
              printf 'Corpus Contract (shard 2/4)|completed|success\n' ;;
    wf-red)   printf 'Corpus Contract (shard 1/4)|completed|success\n'
              printf 'Corpus Contract (shard 2/4)|completed|failure\n' ;;
  esac
  exit 0
fi

printf 'fake gh: unsupported args: %s\n' "$*" >&2
exit 64
FAKE_WF_EOF
chmod +x "$FAKE_WF"

wf_case() { # wf_case <mode> <expected-code> <needle…>
  local mode="$1" expected="$2"; shift 2
  local output code
  set +e
  output="$(FAKE_WF_MODE="$mode" SSC_CI_GH="$FAKE_WF" \
    "$ROOT/scripts/ci-status" --workflow corpus-contract.yml --event any --latest 2>&1)"
  code=$?
  set -e
  if [[ "$code" -ne "$expected" ]]; then
    printf 'ci-status-guard[%s]: expected exit=%s got=%s\n%s\n' "$mode" "$expected" "$code" "$output" >&2
    exit 1
  fi
  local needle
  for needle in "$@"; do
    if [[ "$output" != *"$needle"* ]]; then
      printf 'ci-status-guard[%s]: expected output to contain=%q, got:\n%s\n' "$mode" "$needle" "$output" >&2
      exit 1
    fi
  done
}

# GREEN and RED for the SAME workflow: a one-sided check could not tell "judged its own jobs" from
# "found no job it recognised and defaulted to pass".
wf_case wf-green 0 "CI GREEN" "Corpus Contract (shard 1/4): completed/success"
wf_case wf-red   1 "CI RED"   "Corpus Contract (shard 2/4): completed/failure"

# --all-workflows: a nightly that is cancelled every run must make the sweep RED, and a workflow
# that has never run must be reported without being counted as a failure.
set +e
all_output="$(FAKE_WF_MODE=wf-green SSC_CI_GH="$FAKE_WF" "$ROOT/scripts/ci-status" --all-workflows 2>&1)"
all_code=$?
set -e
if [[ "$all_code" -ne 1 ]]; then
  printf 'ci-status-guard[all-workflows]: expected exit=1 (a cancelled nightly is RED), got=%s\n%s\n' \
    "$all_code" "$all_output" >&2
  exit 1
fi
for needle in "corpus-contract.yml" "RED" "WORKFLOWS RED"; do
  if [[ "$all_output" != *"$needle"* ]]; then
    printf 'ci-status-guard[all-workflows]: missing %q in:\n%s\n' "$needle" "$all_output" >&2
    exit 1
  fi
done

remote_sha="$(git -C "$ROOT" rev-parse origin/main)"
set +e
coord_output="$(FAKE_CI_MODE=red FAKE_EXPECT_SHA="$remote_sha" SSC_CI_GH="$FAKE_GH" \
  "$ROOT/scripts/coord-status" --no-fetch 2>&1)"
coord_code=$?
set -e
if [[ "$coord_code" -ne 0 || "$coord_output" != *"CI RED $remote_sha"* ||
      "$coord_output" != *"== active claims =="* ]]; then
  printf 'ci-status-guard[coord-status]: expected exit=0 plus red CI and claims output, got exit=%s:\n%s\n' \
    "$coord_code" "$coord_output" >&2
  exit 1
fi

# A claim may use a slug whose every heuristic token is filtered out. The explicit
# branch metadata remains an exact observable and must win before legacy slug matching.
CLAIM_WT="$TMP/claim-wt"
CLAIM_BRANCH="feature/ci-red-main-final-fixture-$$"
# The slug is UNIQUE per run, and that is load-bearing too.
#
# The fixture used the literal slug `ci-red-main`, which is a REAL claim on origin/main. The
# commit-activity rule also consults `git log origin/main -- .work/active/<slug>.claim`, and the real
# file was last touched 2026-07-19 — AFTER the 2026-07-17 NOW this test injects. So the fixture
# inherited a stranger's history, the age went negative, clamped to 0, and the claim read as live no
# matter how its own commits were dated. Same failure mode as the undated commit above, different
# source: a fixture must own every input its assertion depends on.
# Every token must still be filtered out by `significant_tokens` — that is the premise of this
# case. `ci` is under 3 chars, `red`/`main`/`test` are stopwords and a bare number is dropped, so
# the legacy slug heuristic finds nothing and the declared branch is the only observable left.
# `fixture` would NOT be filtered, which is why the slug is not simply the branch name.
FIXTURE_SLUG="ci-red-main-test-$$"
FIXTURE_CLAIM=".work/active/$FIXTURE_SLUG.claim"
if git -C "$ROOT" cat-file -e "origin/main:$FIXTURE_CLAIM" 2>/dev/null; then
  printf 'ci-status-guard: fixture slug %s already exists on origin/main; it must be unused\n' \
    "$FIXTURE_SLUG" >&2
  exit 1
fi
# Every fixture commit is dated BEFORE the injected NOW. `coord-status` treats a branch commit
# newer than the staleness window as proof the claim is live and then IGNORES the heartbeat —
# correct, since commits are not hand-maintained. But an UNDATED fixture commit lands at real
# wall-clock, which is in the FUTURE relative to the injected 2026-07-17 now; the age goes
# negative, clamps to 0, and every claim reads as "live, last commit 0m ago". The
# stale-heartbeat case then asserted an outcome the code was right not to produce.
FIXTURE_DATE='2026-07-16T00:00:00Z'
FRESH_NOW_EPOCH=1784247000  # 2026-07-17T00:10:00Z

# The staleness threshold is READ FROM THE CODE THAT ENFORCES IT, never restated here.
#
# It was restated here, and on 2026-07-28 that cost a red gate: `scripts/coord-status` raised the
# threshold 20m -> 45m (2700s) deliberately, this file still asserted `age=1201s/20m
# reason=older-than-20m`, and 1201s stopped being stale. So `Verify exact-SHA CI status guard`
# failed on EVERY push -- the Validate job red for an expectation that was simply out of date, which
# is the same "apparatus lies" class the fixture exists to prevent. BUGS.md
# `heartbeat-threshold-stated-in-two-repos` had already fixed two copies of this constant and added
# `tests/coord/heartbeat-threshold-single-source.sh`; that gate reads the enforcing code, but did not
# know about this THIRD copy. Deriving it is the only version that cannot drift again.
STALE_THRESHOLD_SECS="$(sed -n 's/.*heartbeat_age_seconds" -gt \([0-9]*\) \].*/\1/p' \
  "$ROOT/scripts/coord-status" | head -1)"
STALE_REASON="$(sed -n 's/.*heartbeat_reason="\(older-than-[0-9]*m\)".*/\1/p' \
  "$ROOT/scripts/coord-status" | head -1)"
if [[ -z "$STALE_THRESHOLD_SECS" || -z "$STALE_REASON" ]]; then
  printf 'ci-status-guard[threshold-unreadable]: could not read the staleness threshold from scripts/coord-status.\n' >&2
  printf '  expected=a `-gt <secs>` test and an `older-than-<n>m` reason; got secs=%q reason=%q\n' \
    "$STALE_THRESHOLD_SECS" "$STALE_REASON" >&2
  exit 1
fi
# One second past the threshold: the smallest input that must be reported stale.
STALE_AGE_SECS=$(( STALE_THRESHOLD_SECS + 1 ))
STALE_NOW_EPOCH=$(( 1784246400 + STALE_AGE_SECS ))   # heartbeat is 2026-07-17T00:00:00Z = 1784246400
git -C "$ROOT" worktree add -q -b "$CLAIM_BRANCH" "$CLAIM_WT" HEAD
mkdir -p "$CLAIM_WT/.work/active"
printf '%s\n' \
  "claim: $FIXTURE_SLUG" \
  "branch: $CLAIM_BRANCH" \
  'agent: fixture' \
  'heartbeat: 2026-07-17T00:00:00Z' \
  'status: in-progress' \
  'done-so-far: fixture' \
  'next: verify exact branch matching' \
  > "$CLAIM_WT/$FIXTURE_CLAIM"
git -C "$CLAIM_WT" add "$FIXTURE_CLAIM"
# The commit is DATED, and that is now load-bearing.
#
# `coord-status` gained a rule: a claim whose branch has a commit newer than the staleness window is
# "live by COMMIT activity" and its heartbeat field is ignored — commits are stronger evidence than a
# field somebody forgot to bump, so the rule is right. But this fixture committed at wall-clock NOW
# while injecting a NOW of 2026-07-17, so its commit was permanently "0m ago" and the claim was
# always live. The stale-heartbeat case then asserted an outcome the code was correct not to produce,
# and `Verify exact-SHA CI status guard` went red on every push.
#
# Dating the commit a day before the injected NOW puts it outside the activity window, so the
# heartbeat is what decides — which is what this case is for. The commit-activity rule gets its own
# case below, so both halves of the new behaviour are pinned instead of one being an accident.
GIT_AUTHOR_NAME=fixture GIT_AUTHOR_EMAIL=fixture@example.invalid \
GIT_COMMITTER_NAME=fixture GIT_COMMITTER_EMAIL=fixture@example.invalid \
GIT_AUTHOR_DATE="$FIXTURE_DATE" GIT_COMMITTER_DATE="$FIXTURE_DATE" \
  git -C "$CLAIM_WT" commit -q -m 'test: live zero-token claim fixture'

live_sha="$(git -C "$CLAIM_WT" rev-parse HEAD)"
set +e
live_output="$(FAKE_CI_MODE=red FAKE_EXPECT_SHA="$live_sha" SSC_CI_GH="$FAKE_GH" \
  SSC_COORD_REF="$live_sha" SSC_COORD_NOW_EPOCH="$FRESH_NOW_EPOCH" \
  "$ROOT/scripts/coord-status" --no-fetch 2>&1)"
live_code=$?
set -e
if [[ "$live_code" -ne 0 || "$live_output" == *"maybe stale: $FIXTURE_SLUG"* ||
      "$live_output" == *"potentially stale heartbeat: $FIXTURE_SLUG"* ]]; then
  observed_branches="$(git -C "$ROOT" worktree list --porcelain | sed -n 's/^branch refs\/heads\///p')"
  printf 'ci-status-guard[live-claim]: expected=live got=stale exit=%s heartbeat=%s age=%ss expected_branch=%s observed_branches=%q\n%s\n' \
    "$live_code" '2026-07-17T00:00:00Z' 600 "$CLAIM_BRANCH" "$observed_branches" "$live_output" >&2
  exit 1
fi

set +e
stale_output="$(FAKE_CI_MODE=red FAKE_EXPECT_SHA="$live_sha" SSC_CI_GH="$FAKE_GH" \
  SSC_COORD_REF="$live_sha" SSC_COORD_NOW_EPOCH="$STALE_NOW_EPOCH" \
  "$ROOT/scripts/coord-status" --no-fetch 2>&1)"
stale_code=$?
set -e
stale_expected="potentially stale heartbeat: $FIXTURE_SLUG (heartbeat=2026-07-17T00:00:00Z age=${STALE_AGE_SECS}s/$((STALE_AGE_SECS / 60))m reason=${STALE_REASON} branch=live:$CLAIM_BRANCH)"
if [[ "$stale_code" -ne 0 || "$stale_output" != *"$stale_expected"* ]]; then
  observed_branches="$(git -C "$ROOT" worktree list --porcelain | sed -n 's/^branch refs\/heads\///p')"
  printf 'ci-status-guard[stale-heartbeat]: expected=%q got_output=%q exit=%s heartbeat=%s age=%ss expected_branch=%s observed_branches=%q\n' \
    "$stale_expected" "$stale_output" "$stale_code" '2026-07-17T00:00:00Z' "$STALE_AGE_SECS" \
    "$CLAIM_BRANCH" "$observed_branches" >&2
  exit 1
fi

missing_branch="feature/ci-red-main-missing-fixture-$$"
sed "s|^branch: .*|branch: $missing_branch|" \
  "$CLAIM_WT/$FIXTURE_CLAIM" > "$TMP/missing.claim"
mv "$TMP/missing.claim" "$CLAIM_WT/$FIXTURE_CLAIM"
git -C "$CLAIM_WT" add "$FIXTURE_CLAIM"
GIT_AUTHOR_NAME=fixture GIT_AUTHOR_EMAIL=fixture@example.invalid \
GIT_COMMITTER_NAME=fixture GIT_COMMITTER_EMAIL=fixture@example.invalid \
GIT_AUTHOR_DATE="$FIXTURE_DATE" GIT_COMMITTER_DATE="$FIXTURE_DATE" \
  git -C "$CLAIM_WT" commit -q -m 'test: missing zero-token claim fixture'

missing_sha="$(git -C "$CLAIM_WT" rev-parse HEAD)"
set +e
missing_output="$(FAKE_CI_MODE=red FAKE_EXPECT_SHA="$missing_sha" SSC_CI_GH="$FAKE_GH" \
  SSC_COORD_REF="$missing_sha" SSC_COORD_NOW_EPOCH="$FRESH_NOW_EPOCH" \
  "$ROOT/scripts/coord-status" --no-fetch 2>&1)"
missing_code=$?
set -e
if [[ "$missing_code" -ne 0 || "$missing_output" != *"maybe stale: $FIXTURE_SLUG"* ||
      "$missing_output" == *"potentially stale heartbeat: $FIXTURE_SLUG"* ]]; then
  observed_branches="$(git -C "$ROOT" worktree list --porcelain | sed -n 's/^branch refs\/heads\///p')"
  printf 'ci-status-guard[missing-claim]: expected=missing-worktree got=other exit=%s heartbeat=%s age=%ss expected_branch=%s observed_branches=%q\n%s\n' \
    "$missing_code" '2026-07-17T00:00:00Z' 600 "$missing_branch" "$observed_branches" "$missing_output" >&2
  exit 1
fi

sed -e "s|^branch: .*|branch: $CLAIM_BRANCH|" \
    -e 's|^heartbeat: .*|heartbeat: not-a-time|' \
  "$CLAIM_WT/$FIXTURE_CLAIM" > "$TMP/invalid-heartbeat.claim"
mv "$TMP/invalid-heartbeat.claim" "$CLAIM_WT/$FIXTURE_CLAIM"
git -C "$CLAIM_WT" add "$FIXTURE_CLAIM"
GIT_AUTHOR_NAME=fixture GIT_AUTHOR_EMAIL=fixture@example.invalid \
GIT_COMMITTER_NAME=fixture GIT_COMMITTER_EMAIL=fixture@example.invalid \
GIT_AUTHOR_DATE="$FIXTURE_DATE" GIT_COMMITTER_DATE="$FIXTURE_DATE" \
  git -C "$CLAIM_WT" commit -q -m 'test: invalid claim heartbeat fixture'

invalid_sha="$(git -C "$CLAIM_WT" rev-parse HEAD)"
set +e
invalid_output="$(FAKE_CI_MODE=red FAKE_EXPECT_SHA="$invalid_sha" SSC_CI_GH="$FAKE_GH" \
  SSC_COORD_REF="$invalid_sha" SSC_COORD_NOW_EPOCH="$FRESH_NOW_EPOCH" \
  "$ROOT/scripts/coord-status" --no-fetch 2>&1)"
invalid_code=$?
set -e
invalid_expected="potentially stale heartbeat: $FIXTURE_SLUG (heartbeat=not-a-time age=unknown reason=invalid branch=live:$CLAIM_BRANCH)"
if [[ "$invalid_code" -ne 0 || "$invalid_output" != *"$invalid_expected"* ]]; then
  observed_branches="$(git -C "$ROOT" worktree list --porcelain | sed -n 's/^branch refs\/heads\///p')"
  printf 'ci-status-guard[invalid-heartbeat]: expected=%q got_output=%q exit=%s expected_branch=%s observed_branches=%q\n' \
    "$invalid_expected" "$invalid_output" "$invalid_code" "$CLAIM_BRANCH" "$observed_branches" >&2
  exit 1
fi

sed '/^heartbeat:/d' "$CLAIM_WT/$FIXTURE_CLAIM" > "$TMP/missing-heartbeat.claim"
mv "$TMP/missing-heartbeat.claim" "$CLAIM_WT/$FIXTURE_CLAIM"
git -C "$CLAIM_WT" add "$FIXTURE_CLAIM"
GIT_AUTHOR_NAME=fixture GIT_AUTHOR_EMAIL=fixture@example.invalid \
GIT_COMMITTER_NAME=fixture GIT_COMMITTER_EMAIL=fixture@example.invalid \
GIT_AUTHOR_DATE="$FIXTURE_DATE" GIT_COMMITTER_DATE="$FIXTURE_DATE" \
  git -C "$CLAIM_WT" commit -q -m 'test: missing claim heartbeat fixture'

missing_heartbeat_sha="$(git -C "$CLAIM_WT" rev-parse HEAD)"
set +e
missing_heartbeat_output="$(FAKE_CI_MODE=red FAKE_EXPECT_SHA="$missing_heartbeat_sha" SSC_CI_GH="$FAKE_GH" \
  SSC_COORD_REF="$missing_heartbeat_sha" SSC_COORD_NOW_EPOCH="$FRESH_NOW_EPOCH" \
  "$ROOT/scripts/coord-status" --no-fetch 2>&1)"
missing_heartbeat_code=$?
set -e
missing_heartbeat_expected="potentially stale heartbeat: $FIXTURE_SLUG (heartbeat=missing age=unknown reason=missing branch=live:$CLAIM_BRANCH)"
if [[ "$missing_heartbeat_code" -ne 0 || "$missing_heartbeat_output" != *"$missing_heartbeat_expected"* ]]; then
  observed_branches="$(git -C "$ROOT" worktree list --porcelain | sed -n 's/^branch refs\/heads\///p')"
  printf 'ci-status-guard[missing-heartbeat]: expected=%q got_output=%q exit=%s expected_branch=%s observed_branches=%q\n' \
    "$missing_heartbeat_expected" "$missing_heartbeat_output" "$missing_heartbeat_code" \
    "$CLAIM_BRANCH" "$observed_branches" >&2
  exit 1
fi

# ─────────────────────────────────────────────────────────────────────────────
# The OTHER half of the same rule: a stale heartbeat FIELD next to a RECENT COMMIT is not a stale
# claim, and `coord-status` says so instead of reporting it.
#
# This case exists because the rule was landed untested and then silently ate the stale-heartbeat
# assertion above: the fixture's commits carried real wall-clock dates while the test injected a NOW
# of 2026-07-17, so every fixture commit was permanently in the future, clamped to "0m ago", and
# every claim read as live. The assertion that was supposed to observe staleness could not fail for
# the right reason — it failed for this one, on every push. A rule with no case of its own is a rule
# whose regressions land as someone else's mysterious failure.
#
# Same claim, same stale heartbeat, one commit inside the window. The heartbeat-stale case above and
# this one now differ ONLY in the commit date, so each pins exactly one branch of the decision.
git -C "$CLAIM_WT" checkout -q "$CLAIM_BRANCH" 2>/dev/null || true
ACTIVE_DATE="@$(( STALE_NOW_EPOCH - 60 ))"   # 1 min before the injected NOW: well inside 45 min
GIT_AUTHOR_NAME=fixture GIT_AUTHOR_EMAIL=fixture@example.invalid \
GIT_COMMITTER_NAME=fixture GIT_COMMITTER_EMAIL=fixture@example.invalid \
GIT_AUTHOR_DATE="$ACTIVE_DATE" GIT_COMMITTER_DATE="$ACTIVE_DATE" \
  git -C "$CLAIM_WT" commit -q --allow-empty -m 'test: recent commit under a stale heartbeat'

# Restore the stale-but-parseable heartbeat the earlier cases used; the previous case deleted it,
# and `reason=missing` would take a different branch than the one under test.
printf '%s\n' \
  "claim: $FIXTURE_SLUG" \
  "branch: $CLAIM_BRANCH" \
  'agent: fixture' \
  'heartbeat: 2026-07-17T00:00:00Z' \
  'status: in-progress' \
  'done-so-far: fixture' \
  'next: verify commit activity overrides a stale heartbeat field' \
  > "$CLAIM_WT/$FIXTURE_CLAIM"
git -C "$CLAIM_WT" add "$FIXTURE_CLAIM"
GIT_AUTHOR_NAME=fixture GIT_AUTHOR_EMAIL=fixture@example.invalid \
GIT_COMMITTER_NAME=fixture GIT_COMMITTER_EMAIL=fixture@example.invalid \
GIT_AUTHOR_DATE="$ACTIVE_DATE" GIT_COMMITTER_DATE="$ACTIVE_DATE" \
  git -C "$CLAIM_WT" commit -q -m 'test: stale heartbeat field, live by commit activity'

active_sha="$(git -C "$CLAIM_WT" rev-parse HEAD)"
set +e
active_output="$(FAKE_CI_MODE=red FAKE_EXPECT_SHA="$active_sha" SSC_CI_GH="$FAKE_GH" \
  SSC_COORD_REF="$active_sha" SSC_COORD_NOW_EPOCH="$STALE_NOW_EPOCH" \
  "$ROOT/scripts/coord-status" --no-fetch 2>&1)"
active_code=$?
set -e
active_expected="live by COMMIT activity (stale heartbeat field, ignored): $FIXTURE_SLUG"
if [[ "$active_code" -ne 0 || "$active_output" != *"$active_expected"* ||
      "$active_output" == *"potentially stale heartbeat: $FIXTURE_SLUG"* ]]; then
  printf 'ci-status-guard[commit-activity]: expected=%q and NO stale report, got_output=%q exit=%s\n' \
    "$active_expected" "$active_output" "$active_code" >&2
  exit 1
fi


# ─────────────────────────────────────────────────────────────────────────────
# The required-job list is DUPLICATED state: `scripts/ci-status` names the jobs it demands, and
# `.github/workflows/ci.yml` names the jobs that exist. Nothing checked that they agree, and on
# 2026-07-28 they stopped agreeing the moment the conformance job became a matrix — the verdict tool
# would have reported `missing required job` on every green run from then on. Duplicated state with
# no consistency check does not stay consistent; this is that check.
#
# It compares against the WORKFLOW, not against a second copy of the expectation, so it fails when
# someone renames a job or changes the matrix width without touching the verdict tool.
CI_YML="$ROOT/.github/workflows/ci.yml"

# Matrix width, straight from the workflow: `shard: [0, 1, 2, 3]` -> 4
yml_shards="$(sed -n 's/^ *shard: *\[\(.*\)\] *$/\1/p' "$CI_YML" | head -1 | tr -cd ',' | wc -c | tr -d ' ')"
yml_shards=$(( yml_shards + 1 ))
status_shards="$(sed -n 's/^ *conformance_shards="\${SSC_CI_CONFORMANCE_SHARDS:-\([0-9]*\)}".*$/\1/p' \
  "$ROOT/scripts/ci-status" | head -1)"
if [[ "$yml_shards" != "$status_shards" ]]; then
  printf 'ci-status-guard[shard-width-drift]: ci.yml declares %s conformance shards, scripts/ci-status requires %s.\n' \
    "$yml_shards" "$status_shards" >&2
  printf '  expected=%s got=%s — the verdict tool and the workflow must agree, or every run reports a missing job.\n' \
    "$yml_shards" "$status_shards" >&2
  exit 1
fi

# Every non-matrix job name ci-status requires must actually exist in the workflow.
for required in "Lint Markdown" "Validate ScalaScript" "Examples and launcher smokes"; do
  if ! grep -qF "name: $required" "$CI_YML"; then
    printf 'ci-status-guard[job-name-drift]: scripts/ci-status requires a job named %q, but ci.yml has no such `name:`.\n' \
      "$required" >&2
    printf '  jobs declared in ci.yml:\n' >&2
    sed -n 's/^ *name: \(.*\)$/    \1/p' "$CI_YML" | sort -u >&2
    exit 1
  fi
done

# And the matrix job's name template must still produce `Conformance shard <i>/<N>`.
if ! grep -qF 'name: Conformance shard ${{ matrix.shard }}/4' "$CI_YML"; then
  printf 'ci-status-guard[job-name-drift]: ci.yml no longer names the matrix job `Conformance shard ${{ matrix.shard }}/4`.\n' >&2
  printf '  scripts/ci-status builds its required list from that exact shape; update both together.\n' >&2
  exit 1
fi

# ─────────────────────────────────────────────────────────────────────────────
# THE INVARIANT THAT WAS MISSING, and it cost a live regression on 2026-07-30.
#
# `scripts/ci-status` keeps a required-job list; `ci.yml` decides per JOB, via `if:`, which events it
# runs on. Nothing compared the two. The three existing drift checks below compare NAMES — they would
# not have noticed that `Validate ScalaScript` had stopped running on push while ci-status still
# demanded it there, because the name was still in the file. Every future green push run would have
# been reported `missing required job`.
#
# This compares BEHAVIOUR, not two parses:
#   * ci.yml side  — the job names whose body carries `if: github.event_name != 'push'`.
#   * ci-status side — the `missing required job:` lines it actually emits for a run containing only
#     `Lint Markdown`, once as `schedule` and once as `push`.
# Those two sets must be equal on schedule, and the push set must be empty. Deriving the tool's side
# by RUNNING it is the part that matters: a parse of its source would drift the same way the list did.
job_names_gated_off_push() {
  awk '
    /^  [a-z0-9_-]+:[[:space:]]*$/ { job = $1; name[job] = ""; gated[job] = 0; next }
    job != "" && /^    name:/      { sub(/^    name:[[:space:]]*/, ""); name[job] = $0; next }
    job != "" && /^    if:[[:space:]]*github\.event_name[[:space:]]*!=[[:space:]]*.push./ { gated[job] = 1; next }
    END { for (j in gated) if (gated[j] && name[j] != "") print name[j] }
  ' "$CI_YML" |
  while IFS= read -r n; do
    # Expand the matrix template the same way ci.yml does.
    if [[ "$n" == *'${{ matrix.shard }}'* ]]; then
      for i in $(seq 0 $((yml_shards - 1))); do printf '%s\n' "${n//\$\{\{ matrix.shard \}\}/$i}"; done
    else
      printf '%s\n' "$n"
    fi
  done | LC_ALL=C sort -u
}

missing_jobs_for_event() { # missing_jobs_for_event <event>
  # `ci-status` exits 1 when it reports RED, which is the WHOLE POINT of the schedule call — and under
  # `set -euo pipefail` that non-zero head of the pipeline aborted this script with no output at all.
  # Exactly the silent-assertion shape this file's own fixtures exist to prevent, so the exit code is
  # captured explicitly instead of leaking into control flow.
  local out
  set +e
  # The probe run carries TIER 2 and nothing else, so "what ci-status still calls missing" is exactly
  # the tier-3 set — which is what `if: github.event_name != 'push'` marks in ci.yml, and what this
  # comparison is about. It used to be `push-lint-only`, correct while lint was the whole push run;
  # once tier 2 moved back onto push that fixture made the missing set tier2+tier3 and the invariant
  # compared two different things.
  out="$(FAKE_CI_MODE=tier2 FAKE_CI_EVENT="$1" FAKE_CI_WORKFLOW=ci.yml SSC_CI_GH="$FAKE_GH" \
    "$ROOT/scripts/ci-status" --sha "$SHA" --workflow ci.yml 2>&1)"
  set -e
  printf '%s\n' "$out" | sed -n 's/^  missing required job: //p' | LC_ALL=C sort -u
}

gated_expected="$(job_names_gated_off_push)"
gated_observed="$(missing_jobs_for_event schedule)"
if [[ -z "$gated_expected" ]]; then
  printf 'ci-status-guard[event-gating]: found NO ci.yml job carrying `if: github.event_name != '"'"'push'"'"''"'"'.\n' >&2
  printf '  Either the extraction broke, or the gating was removed. An empty expected set makes this\n' >&2
  printf '  comparison vacuous, so it is a failure rather than a pass.\n' >&2
  exit 1
fi
if [[ "$gated_expected" != "$gated_observed" ]]; then
  printf 'ci-status-guard[event-gating]: ci.yml gates these jobs off push, ci-status requires these on schedule.\n' >&2
  printf '  gated in ci.yml   =%s\n' "$(printf '%s' "$gated_expected" | tr '\n' '|')" >&2
  printf '  required by tool  =%s\n' "$(printf '%s' "$gated_observed" | tr '\n' '|')" >&2
  printf '  A job gated off push must be required on non-push events and NOT on push. Update both together.\n' >&2
  exit 1
fi

# ── DISPATCH-ONLY jobs (negtc, since 2026-08-01) ──────────────────────────────────────────────
#
# `job_names_gated_off_push` only sees `if: github.event_name != 'push'`, so a job written
# `== 'workflow_dispatch'` is INVISIBLE to it — it would silently leave the cross-check without ever
# saying so, which is the failure mode this whole file exists to refuse. Extracted and checked
# separately, in both directions: required when dispatched, absent otherwise.
dispatch_only_jobs() {
  awk '
    /^  [a-z0-9_-]+:[[:space:]]*$/ { job = $1; name[job] = ""; d[job] = 0; next }
    job != "" && /^    name:/      { sub(/^    name:[[:space:]]*/, ""); name[job] = $0; next }
    job != "" && /^    if:[[:space:]]*github\.event_name[[:space:]]*==[[:space:]]*.workflow_dispatch./ { d[job] = 1; next }
    END { for (j in d) if (d[j] && name[j] != "") print name[j] }
  ' "$CI_YML" |
  while IFS= read -r n; do
    if [[ "$n" == *'${{ matrix.shard }}'* ]]; then
      for i in $(seq 0 $((yml_shards - 1))); do printf '%s\n' "${n//\$\{\{ matrix.shard \}\}/$i}"; done
    else printf '%s\n' "$n"; fi
  done | LC_ALL=C sort -u
}
dispatch_expected="$(dispatch_only_jobs)"
if [[ -z "$dispatch_expected" ]]; then
  printf 'ci-status-guard[dispatch-only]: found NO ci.yml job carrying `if: github.event_name == %s`.\n' \
    "'workflow_dispatch'" >&2
  printf '  Either the extraction broke or negtc stopped being dispatch-only. An empty expected set\n' >&2
  printf '  makes the comparison vacuous, so it is a failure rather than a pass.\n' >&2
  exit 1
fi
# DISPATCH minus SCHEDULE, not dispatch alone: `sbt` is required on both, so it shows up as missing
# from this probe either way. The difference is exactly the set that dispatch adds.
dispatch_observed="$(comm -13 <(missing_jobs_for_event schedule) <(missing_jobs_for_event workflow_dispatch))"
if [[ "$dispatch_expected" != "$dispatch_observed" ]]; then
  printf 'ci-status-guard[dispatch-only]: ci.yml runs these ONLY on dispatch, ci-status requires these there.\n' >&2
  printf '  dispatch-only in ci.yml =%s\n' "$(printf '%s' "$dispatch_expected" | tr '\n' '|')" >&2
  printf '  required by tool        =%s\n' "$(printf '%s' "$dispatch_observed" | tr '\n' '|')" >&2
  exit 1
fi
sched_missing="$(missing_jobs_for_event schedule)"
if printf '%s\n' "$sched_missing" | grep -qFx "negtc release gate (reduce)"; then
  printf 'ci-status-guard[dispatch-only]: a SCHEDULED run has no negtc job, yet ci-status demands one.\n' >&2
  printf '  That reports `missing required job` on every nightly — the verdict tool contradicting\n' >&2
  printf '  the workflow it verifies, for the fourth time in this function.\n' >&2
  exit 1
fi

push_missing="$(missing_jobs_for_event push)"
if [[ -n "$push_missing" ]]; then
  printf 'ci-status-guard[event-gating]: on a PUSH run carrying only `Lint Markdown`, ci-status still demands:\n' >&2
  printf '    %s\n' "$push_missing" >&2
  printf '  Every one of those is gated off push in ci.yml, so this reports `missing required job` on\n' >&2
  printf '  every green push run — the verdict tool contradicting the workflow it verifies.\n' >&2
  exit 1
fi

# The default query must name a workflow that EXISTS. Pointing the per-push verdict at a file that is
# not there would answer `CI UNKNOWN` forever, which reads as "not verified yet" rather than "broken".
default_wf="$(sed -n 's/^WORKFLOW="\(.*\)"$/\1/p' "$ROOT/scripts/ci-status" | head -1)"
if [[ ! -f "$ROOT/.github/workflows/$default_wf" ]]; then
  printf 'ci-status-guard[default-workflow]: scripts/ci-status defaults to %q, which does not exist in .github/workflows/.\n' \
    "$default_wf" >&2
  exit 1
fi

printf 'ci-status-guard: PASS\n'
