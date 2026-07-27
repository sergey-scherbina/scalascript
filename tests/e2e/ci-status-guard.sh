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
  exit 0
fi

if [[ "$args" == *" run view 42 "* ]]; then
  printf 'Lint Markdown|completed|success\n'
  printf 'Validate ScalaScript|completed|success\n'
  case "$mode" in
    green)
      printf 'Conformance Suite|completed|success\n'
      printf 'sbt — compile and test|completed|success\n'
      ;;
    red)
      printf 'Conformance Suite|completed|failure\n'
      printf 'sbt — compile and test|completed|cancelled\n'
      ;;
    pending)
      printf 'Conformance Suite|in_progress|\n'
      printf 'sbt — compile and test|queued|\n'
      ;;
    missing)
      printf 'Conformance Suite|completed|success\n'
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

run_case green 0 "CI GREEN $SHA" "Conformance Suite: completed/success"
run_case red 1 "CI RED $SHA" "Conformance Suite: completed/failure" \
  "sbt — compile and test: completed/cancelled"
run_case pending 2 "CI PENDING $SHA" "Conformance Suite: in_progress/pending"
run_case missing 1 "CI RED $SHA" "missing required job: sbt — compile and test"
run_case no-run 2 "CI UNKNOWN $SHA" "no push ci.yml run found"
run_case gh-fail 2 "CI UNKNOWN $SHA" "gh run list failed"

# ── descendant coverage (BUGS ci-status-sha-misses-commits-covered-by-a-later-tip) ─────────────
# GitHub creates ONE run per PUSH, attributed to the push's TIP. A code commit pushed together with
# a later docs commit therefore has no run of its own, and the old answer — CI UNKNOWN — was wrong
# in the expensive direction: it says "unverified" about a commit that was fully tested.
#
# These cases use REAL commits from this repository, because the ancestry test is real `git
# merge-base --is-ancestor`, not a string comparison. The negative case is the one that matters: a
# run whose head is NOT a descendant must still be UNKNOWN, or the fallback would accept any recent
# run as evidence for anything.
DESC_SHA="$(git -C "$ROOT" rev-parse origin/main 2>/dev/null || git -C "$ROOT" rev-parse HEAD)"
ANC_SHA="$(git -C "$ROOT" rev-parse "${DESC_SHA}~5" 2>/dev/null || echo "$DESC_SHA")"

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
  case "$mode" in
    desc-red) printf 'Conformance Suite|completed|failure
' ;;
    *)        printf 'Conformance Suite|completed|success
' ;;
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
STALE_NOW_EPOCH=1784247601  # 2026-07-17T00:20:01Z
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
stale_expected="potentially stale heartbeat: ci-red-main (heartbeat=2026-07-17T00:00:00Z age=1201s/20m reason=older-than-20m branch=live:$CLAIM_BRANCH)"
if [[ "$stale_code" -ne 0 || "$stale_output" != *"$stale_expected"* ]]; then
  observed_branches="$(git -C "$ROOT" worktree list --porcelain | sed -n 's/^branch refs\/heads\///p')"
  printf 'ci-status-guard[stale-heartbeat]: expected=%q got_output=%q exit=%s heartbeat=%s age=%ss expected_branch=%s observed_branches=%q\n' \
    "$stale_expected" "$stale_output" "$stale_code" '2026-07-17T00:00:00Z' 1201 \
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

printf 'ci-status-guard: PASS\n'
