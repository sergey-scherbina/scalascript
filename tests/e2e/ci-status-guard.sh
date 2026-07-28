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

if [[ "$args" == *" run list "* ]]; then
  for required in "--workflow ci.yml" "--branch main" "--event push" \
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
    push-no-sbt|sched-no-sbt|one-shard-missing)
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

if [[ "$args" == *" run view 42 "* ]]; then
  printf 'Lint Markdown|completed|success\n'
  printf 'Validate ScalaScript|completed|success\n'
  case "$mode" in
    green)
      shard_jobs completed success
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
      printf 'sbt — compile and test|completed|cancelled\n'
      ;;
    pending)
      printf 'Examples and launcher smokes|completed|success\n'
      printf 'Conformance shard 0/4|completed|success\n'
      printf 'Conformance shard 1/4|completed|success\n'
      printf 'Conformance shard 2/4|in_progress|\n'
      printf 'Conformance shard 3/4|completed|success\n'
      printf 'sbt — compile and test|queued|\n'
      ;;
    missing)
      # A job that is required on EVERY event, so this case keeps meaning what it always meant.
      printf 'sbt — compile and test|completed|success\n'
      ;;
    push-no-sbt|sched-no-sbt)
      # The shape a real push run now has: the fast jobs plus the four shards, and no sbt job.
      shard_jobs completed success
      ;;
    one-shard-missing)
      # A dropped MATRIX INSTANCE. Before sharding this could not happen; now it is the most likely
      # way for the suite to silently shrink, so it gets its own case.
      printf 'Examples and launcher smokes|completed|success\n'
      printf 'Conformance shard 0/4|completed|success\n'
      printf 'Conformance shard 1/4|completed|success\n'
      printf 'Conformance shard 3/4|completed|success\n'
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
  set +e
  output="$(FAKE_CI_MODE="$mode" SSC_CI_GH="$FAKE_GH" \
    "$ROOT/scripts/ci-status" --sha "$SHA" 2>&1)"
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

run_case green 0 "CI GREEN $SHA" "Conformance shard 0/4: completed/success"
# Run as a SCHEDULED event so all four jobs are required — this case's second assertion is what pins
# "cancelled is RED, not neutral", and sbt is only in the required set on non-push events.
FAKE_CI_EVENT=schedule run_case red 1 "CI RED $SHA" "Conformance shard 2/4: completed/failure" \
  "sbt — compile and test: completed/cancelled"
run_case pending 2 "CI PENDING $SHA" "Conformance shard 2/4: in_progress/pending"
run_case missing 1 "CI RED $SHA" "missing required job: Conformance shard 0/4"
# `sbt — compile and test` runs only on non-push events (ci.yml `if:`), because at 196 min against a
# 7-min mean push interval at most 1 commit in 28 could ever reach a verdict (BUGS
# ci-sbt-job-is-28x-the-code-push-interval). These two pin BOTH directions of that: absent-on-push is
# legitimate, absent-on-schedule is still RED. Without the second case the first would silently excuse
# a genuinely dropped sbt job.
FAKE_CI_EVENT=push     run_case push-no-sbt  0 "CI GREEN $SHA" "Conformance shard 3/4: completed/success"
# A dropped matrix INSTANCE must be RED. Sharding created this failure mode; without this
# case a suite that quietly lost a quarter of the corpus would still report green.
FAKE_CI_EVENT=push     run_case one-shard-missing 1 "CI RED $SHA" "missing required job: Conformance shard 2/4"
FAKE_CI_EVENT=schedule run_case sched-no-sbt 1 "CI RED $SHA"   "missing required job: sbt — compile and test"
run_case no-run 2 "CI UNKNOWN $SHA" "no push ci.yml run found"
run_case gh-fail 2 "CI UNKNOWN $SHA" "gh run list failed"

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
  'claim: ci-red-main' \
  "branch: $CLAIM_BRANCH" \
  'agent: fixture' \
  'heartbeat: 2026-07-17T00:00:00Z' \
  'status: in-progress' \
  'done-so-far: fixture' \
  'next: verify exact branch matching' \
  > "$CLAIM_WT/.work/active/ci-red-main.claim"
git -C "$CLAIM_WT" add .work/active/ci-red-main.claim
GIT_AUTHOR_NAME=fixture GIT_AUTHOR_EMAIL=fixture@example.invalid \
GIT_COMMITTER_NAME=fixture GIT_COMMITTER_EMAIL=fixture@example.invalid \
  git -C "$CLAIM_WT" commit -q -m 'test: live zero-token claim fixture'

live_sha="$(git -C "$CLAIM_WT" rev-parse HEAD)"
set +e
live_output="$(FAKE_CI_MODE=red FAKE_EXPECT_SHA="$live_sha" SSC_CI_GH="$FAKE_GH" \
  SSC_COORD_REF="$live_sha" SSC_COORD_NOW_EPOCH="$FRESH_NOW_EPOCH" \
  "$ROOT/scripts/coord-status" --no-fetch 2>&1)"
live_code=$?
set -e
if [[ "$live_code" -ne 0 || "$live_output" == *"maybe stale: ci-red-main"* ||
      "$live_output" == *"potentially stale heartbeat: ci-red-main"* ]]; then
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
stale_expected="potentially stale heartbeat: ci-red-main (heartbeat=2026-07-17T00:00:00Z age=${STALE_AGE_SECS}s/$((STALE_AGE_SECS / 60))m reason=${STALE_REASON} branch=live:$CLAIM_BRANCH)"
if [[ "$stale_code" -ne 0 || "$stale_output" != *"$stale_expected"* ]]; then
  observed_branches="$(git -C "$ROOT" worktree list --porcelain | sed -n 's/^branch refs\/heads\///p')"
  printf 'ci-status-guard[stale-heartbeat]: expected=%q got_output=%q exit=%s heartbeat=%s age=%ss expected_branch=%s observed_branches=%q\n' \
    "$stale_expected" "$stale_output" "$stale_code" '2026-07-17T00:00:00Z' "$STALE_AGE_SECS" \
    "$CLAIM_BRANCH" "$observed_branches" >&2
  exit 1
fi

missing_branch="feature/ci-red-main-missing-fixture-$$"
sed "s|^branch: .*|branch: $missing_branch|" \
  "$CLAIM_WT/.work/active/ci-red-main.claim" > "$TMP/missing.claim"
mv "$TMP/missing.claim" "$CLAIM_WT/.work/active/ci-red-main.claim"
git -C "$CLAIM_WT" add .work/active/ci-red-main.claim
GIT_AUTHOR_NAME=fixture GIT_AUTHOR_EMAIL=fixture@example.invalid \
GIT_COMMITTER_NAME=fixture GIT_COMMITTER_EMAIL=fixture@example.invalid \
  git -C "$CLAIM_WT" commit -q -m 'test: missing zero-token claim fixture'

missing_sha="$(git -C "$CLAIM_WT" rev-parse HEAD)"
set +e
missing_output="$(FAKE_CI_MODE=red FAKE_EXPECT_SHA="$missing_sha" SSC_CI_GH="$FAKE_GH" \
  SSC_COORD_REF="$missing_sha" SSC_COORD_NOW_EPOCH="$FRESH_NOW_EPOCH" \
  "$ROOT/scripts/coord-status" --no-fetch 2>&1)"
missing_code=$?
set -e
if [[ "$missing_code" -ne 0 || "$missing_output" != *"maybe stale: ci-red-main"* ||
      "$missing_output" == *"potentially stale heartbeat: ci-red-main"* ]]; then
  observed_branches="$(git -C "$ROOT" worktree list --porcelain | sed -n 's/^branch refs\/heads\///p')"
  printf 'ci-status-guard[missing-claim]: expected=missing-worktree got=other exit=%s heartbeat=%s age=%ss expected_branch=%s observed_branches=%q\n%s\n' \
    "$missing_code" '2026-07-17T00:00:00Z' 600 "$missing_branch" "$observed_branches" "$missing_output" >&2
  exit 1
fi

sed -e "s|^branch: .*|branch: $CLAIM_BRANCH|" \
    -e 's|^heartbeat: .*|heartbeat: not-a-time|' \
  "$CLAIM_WT/.work/active/ci-red-main.claim" > "$TMP/invalid-heartbeat.claim"
mv "$TMP/invalid-heartbeat.claim" "$CLAIM_WT/.work/active/ci-red-main.claim"
git -C "$CLAIM_WT" add .work/active/ci-red-main.claim
GIT_AUTHOR_NAME=fixture GIT_AUTHOR_EMAIL=fixture@example.invalid \
GIT_COMMITTER_NAME=fixture GIT_COMMITTER_EMAIL=fixture@example.invalid \
  git -C "$CLAIM_WT" commit -q -m 'test: invalid claim heartbeat fixture'

invalid_sha="$(git -C "$CLAIM_WT" rev-parse HEAD)"
set +e
invalid_output="$(FAKE_CI_MODE=red FAKE_EXPECT_SHA="$invalid_sha" SSC_CI_GH="$FAKE_GH" \
  SSC_COORD_REF="$invalid_sha" SSC_COORD_NOW_EPOCH="$FRESH_NOW_EPOCH" \
  "$ROOT/scripts/coord-status" --no-fetch 2>&1)"
invalid_code=$?
set -e
invalid_expected="potentially stale heartbeat: ci-red-main (heartbeat=not-a-time age=unknown reason=invalid branch=live:$CLAIM_BRANCH)"
if [[ "$invalid_code" -ne 0 || "$invalid_output" != *"$invalid_expected"* ]]; then
  observed_branches="$(git -C "$ROOT" worktree list --porcelain | sed -n 's/^branch refs\/heads\///p')"
  printf 'ci-status-guard[invalid-heartbeat]: expected=%q got_output=%q exit=%s expected_branch=%s observed_branches=%q\n' \
    "$invalid_expected" "$invalid_output" "$invalid_code" "$CLAIM_BRANCH" "$observed_branches" >&2
  exit 1
fi

sed '/^heartbeat:/d' "$CLAIM_WT/.work/active/ci-red-main.claim" > "$TMP/missing-heartbeat.claim"
mv "$TMP/missing-heartbeat.claim" "$CLAIM_WT/.work/active/ci-red-main.claim"
git -C "$CLAIM_WT" add .work/active/ci-red-main.claim
GIT_AUTHOR_NAME=fixture GIT_AUTHOR_EMAIL=fixture@example.invalid \
GIT_COMMITTER_NAME=fixture GIT_COMMITTER_EMAIL=fixture@example.invalid \
  git -C "$CLAIM_WT" commit -q -m 'test: missing claim heartbeat fixture'

missing_heartbeat_sha="$(git -C "$CLAIM_WT" rev-parse HEAD)"
set +e
missing_heartbeat_output="$(FAKE_CI_MODE=red FAKE_EXPECT_SHA="$missing_heartbeat_sha" SSC_CI_GH="$FAKE_GH" \
  SSC_COORD_REF="$missing_heartbeat_sha" SSC_COORD_NOW_EPOCH="$FRESH_NOW_EPOCH" \
  "$ROOT/scripts/coord-status" --no-fetch 2>&1)"
missing_heartbeat_code=$?
set -e
missing_heartbeat_expected="potentially stale heartbeat: ci-red-main (heartbeat=missing age=unknown reason=missing branch=live:$CLAIM_BRANCH)"
if [[ "$missing_heartbeat_code" -ne 0 || "$missing_heartbeat_output" != *"$missing_heartbeat_expected"* ]]; then
  observed_branches="$(git -C "$ROOT" worktree list --porcelain | sed -n 's/^branch refs\/heads\///p')"
  printf 'ci-status-guard[missing-heartbeat]: expected=%q got_output=%q exit=%s expected_branch=%s observed_branches=%q\n' \
    "$missing_heartbeat_expected" "$missing_heartbeat_output" "$missing_heartbeat_code" \
    "$CLAIM_BRANCH" "$observed_branches" >&2
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

printf 'ci-status-guard: PASS\n'
