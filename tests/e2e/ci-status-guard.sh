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
  SSC_COORD_REF="$live_sha" "$ROOT/scripts/coord-status" --no-fetch 2>&1)"
live_code=$?
set -e
if [[ "$live_code" -ne 0 || "$live_output" == *"maybe stale: ci-red-main"* ]]; then
  observed_branches="$(git -C "$ROOT" worktree list --porcelain | sed -n 's/^branch refs\/heads\///p')"
  printf 'ci-status-guard[live-claim]: expected=live got=stale exit=%s expected_branch=%s observed_branches=%q\n%s\n' \
    "$live_code" "$CLAIM_BRANCH" "$observed_branches" "$live_output" >&2
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
  SSC_COORD_REF="$missing_sha" "$ROOT/scripts/coord-status" --no-fetch 2>&1)"
missing_code=$?
set -e
if [[ "$missing_code" -ne 0 || "$missing_output" != *"maybe stale: ci-red-main"* ]]; then
  observed_branches="$(git -C "$ROOT" worktree list --porcelain | sed -n 's/^branch refs\/heads\///p')"
  printf 'ci-status-guard[missing-claim]: expected=stale got=live exit=%s expected_branch=%s observed_branches=%q\n%s\n' \
    "$missing_code" "$missing_branch" "$observed_branches" "$missing_output" >&2
  exit 1
fi

printf 'ci-status-guard: PASS\n'
