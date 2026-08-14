#!/usr/bin/env bash
#
# policy-selftest-residue-gate.sh — an INTERRUPTED gate self-test must leave the checkout untouched.
#
#   ./tests/e2e/policy-selftest-residue-gate.sh              # check
#   ./tests/e2e/policy-selftest-residue-gate.sh --self-test  # prove it can FAIL, then check
#
# WHAT THIS IS ABOUT. `tests/e2e/policy-single-source.sh --self-test` has to make a fixture visible
# to `git ls-files`, because that is how the gate builds its document list. It used to do that with
# `git add -N` into the checkout's own index and unstage it three lines later. A run that never
# reaches the third line — a suite timeout, a Ctrl-C, a host that killed the process group — leaves
# an intent-to-add entry for a file that is no longer on disk, and the next `git rebase` in that
# checkout REFUSES TO START: "cannot rebase: You have unstaged changes", naming a path its owner has
# never seen. Reported 2026-08-14 by an agent it blocked; tests/BUGS.md
# `policy-selftest-stages-into-the-shared-index`.
#
# WHY THE OBVIOUS FIX IS NOT ENOUGH, and why this gate uses two signals. "Clean up in a trap" is the
# reflex, and a trap does not run when the process is killed — which is precisely how a suite
# timeout ends a check. So the fix is that the shared index is never written at all (the self-test
# drives a COPY through GIT_INDEX_FILE), with the trap and a `.gitignore` line as the second and
# third lines of defence for the file on disk. Three mechanisms, so this gate interrupts a REAL run
# at a known point with SIGTERM and again with SIGKILL, and asserts the reported symptom directly:
# `git rebase` must still start.
#
# HOW THE RUN IS INTERRUPTED AT A KNOWN POINT. Racing a `kill` against a sub-second run is a coin
# toss, so the lab puts a `git` shim first on PATH which passes everything through EXCEPT the first
# `ls-files` that happens once the fixture is on disk — that call is the child run inside the
# self-test, i.e. exactly the window where the state used to be shared. The shim parks there and the
# test kills the process group. Deterministic, no sleeps to tune.
#
# The lab is a throwaway repository, never this checkout: a gate that proves "an interrupted run
# leaves no residue" by leaving residue in the shared tree when it fails would be the same bug.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SUBJECT="$ROOT/tests/e2e/policy-single-source.sh"
FIXTURE="specs/_policy-single-source-selftest.md"
REAL_GIT="$(command -v git)"
fail=0

[ -f "$SUBJECT" ] || { echo "✗ missing subject: $SUBJECT" >&2; exit 1; }
[ -f "$ROOT/.gitignore" ] || { echo "✗ missing $ROOT/.gitignore" >&2; exit 1; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/policy-selftest-residue.XXXXXX")"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT INT TERM HUP

# HERMETIC, because a lab that inherits the runner's git config answers a different question on a
# dev box than on CI — three coord labs went red on the runner within an hour of being wired for
# exactly that reason (tests/BUGS.md `coord-labs-inherit-the-dev-boxs-git-config…`). This is the
# environment that entry measured as an exact match for a runner: no global or system config, and
# `user.useConfigOnly` so git will NOT invent an identity from `user@host`. The labs below set
# their own, which is what makes them work anywhere.
export HOME="$WORK/home"
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=user.useConfigOnly GIT_CONFIG_VALUE_0=true
mkdir -p "$HOME"

# ── the lab ───────────────────────────────────────────────────────────────────────────────────────
#
# POLICY.md is GENERATED FROM THE SUBJECT'S OWN PINS rather than written out here. A hand-copied
# list would rot the first time a pin changes, and it would rot silently: the lab's POLICY.md would
# stop containing a phrase, the subject would report "pin is stale", and the run would end before it
# ever reached the fixture — a green from a gate that never tested anything.
build_lab() {
  local script="$1" lab="$2"
  mkdir -p "$lab/tests/e2e" "$lab/specs" "$lab/bin"
  cp "$script" "$lab/tests/e2e/policy-single-source.sh"
  chmod +x "$lab/tests/e2e/policy-single-source.sh"

  awk '/^PINS=/{f=1;next} f&&/^EOF$/{exit} f' "$SUBJECT" | cut -f2- > "$lab/POLICY.md"
  local pins; pins=$(grep -c . "$lab/POLICY.md" || true)
  if [ "${pins:-0}" -lt 2 ]; then
    echo "✗ could not read the pins out of $SUBJECT — the lab would test nothing" >&2
    return 1
  fi

  # The REAL ignore rules, not a summary of them: whether a SIGKILL leftover is inert is a property
  # of the file this repository ships.
  cp "$ROOT/.gitignore" "$lab/.gitignore"

  cat > "$lab/bin/git" <<'SHIM'
#!/usr/bin/env bash
# Test double for git: pass everything through, but PARK the run at one known point — the first
# `ls-files` that happens once the self-test's fixture exists on disk. That call is the child run
# inside self_test(), which is the window where the fixture is staged and the interruption hurts.
if [ -n "${SHIM_PARK_AT:-}" ] && [ "${1:-}" = "ls-files" ] && [ -e "$SHIM_PARK_AT" ]; then
  : > "$SHIM_PARKED"
  sleep 300
fi
exec "$REAL_GIT" "$@"
SHIM
  chmod +x "$lab/bin/git"

  "$REAL_GIT" -C "$lab" init -q
  # A lab that inherits no identity fails with "Author identity unknown" on CI and passes on a dev
  # box — three coord labs went red exactly that way on 2026-08-14.
  "$REAL_GIT" -C "$lab" config user.email policy-residue@example.invalid
  "$REAL_GIT" -C "$lab" config user.name  "policy residue lab"
  "$REAL_GIT" -C "$lab" add -A >/dev/null
  "$REAL_GIT" -C "$lab" commit -qm "lab" --no-verify >/dev/null
}

# ── running the subject in the lab ────────────────────────────────────────────────────────────────
run_clean() {
  local lab="$1"
  ( cd "$lab" && PATH="$lab/bin:$PATH" REAL_GIT="$REAL_GIT" \
      bash tests/e2e/policy-single-source.sh --self-test ) >/dev/null 2>&1
}

# Kills the whole PROCESS GROUP, which is what a suite timeout and a Ctrl-C both do — and it is also
# what makes the TERM case meaningful: bash defers a trap while it waits on a foreground child, so
# signalling the parent alone would prove nothing about cleanup.
run_interrupted() {
  local lab="$1" sig="$2" pid waited=0
  rm -f "$lab/.parked"
  set -m
  (
    cd "$lab"
    PATH="$lab/bin:$PATH" REAL_GIT="$REAL_GIT" \
      SHIM_PARK_AT="$lab/$FIXTURE" SHIM_PARKED="$lab/.parked" \
      exec bash tests/e2e/policy-single-source.sh --self-test
  ) >/dev/null 2>&1 &
  pid=$!
  set +m
  while [ ! -e "$lab/.parked" ]; do
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "  ✗ the run finished before it reached the parking point — nothing was interrupted" >&2
      return 1
    fi
    sleep 0.1
    waited=$((waited + 1))
    if [ "$waited" -gt 300 ]; then
      kill -KILL -"$pid" 2>/dev/null || true
      echo "  ✗ the run never reached the parking point within 30 s" >&2
      return 1
    fi
  done
  kill -"$sig" -"$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  return 0
}

# ── what "no residue" means ───────────────────────────────────────────────────────────────────────
#
# Prints one line per problem; empty output means the checkout is as the run found it. The last of
# the three is the reported symptom itself rather than a proxy for it.
residue() {
  local lab="$1" want_file_gone="$2" out
  out="$("$REAL_GIT" -C "$lab" ls-files --cached -- "$FIXTURE")"
  [ -z "$out" ] || echo "the index still carries $FIXTURE — this is what blocks the next rebase"
  out="$("$REAL_GIT" -C "$lab" status --porcelain)"
  [ -z "$out" ] || echo "the checkout is dirty: $(printf '%s' "$out" | tr '\n' ';')"
  if ! "$REAL_GIT" -C "$lab" rebase HEAD >/dev/null 2>&1; then
    echo "\`git rebase\` refuses to start — the reported symptom, exactly"
    "$REAL_GIT" -C "$lab" rebase --abort >/dev/null 2>&1 || true
  fi
  if [ "$want_file_gone" = yes ] && [ -e "$lab/$FIXTURE" ]; then
    echo "the fixture file survived a signal a trap can catch"
  fi
}

expect_clean() {
  local label="$1" lab="$2" want_file_gone="$3" out
  out="$(residue "$lab" "$want_file_gone")"
  if [ -z "$out" ]; then
    printf '  ✓ %s\n' "$label"
  else
    printf '  ✗ %s\n' "$label" >&2
    printf '%s\n' "$out" | sed 's/^/        /' >&2
    fail=1
  fi
}

# ── the checks ────────────────────────────────────────────────────────────────────────────────────
check_subject() {
  local script="$1" tag="$2"

  build_lab "$script" "$WORK/$tag-clean"
  if run_clean "$WORK/$tag-clean"; then
    printf '  ✓ the self-test still passes in the lab\n'
  else
    printf '  ✗ the self-test FAILED in the lab — the run under test never worked, so an\n' >&2
    printf '    interruption result from it would mean nothing\n' >&2
    fail=1
  fi
  expect_clean "a completed run leaves nothing" "$WORK/$tag-clean" yes

  build_lab "$script" "$WORK/$tag-term"
  run_interrupted "$WORK/$tag-term" TERM || fail=1
  expect_clean "killed with SIGTERM: the trap cleans up, index untouched" "$WORK/$tag-term" yes

  build_lab "$script" "$WORK/$tag-kill"
  run_interrupted "$WORK/$tag-kill" KILL || fail=1
  # The file on disk cannot be helped by anything the process does — SIGKILL runs no code. What must
  # hold is that the leftover is INERT: ignored, so `status` is clean and `rebase` still starts.
  expect_clean "killed with SIGKILL: index untouched, leftover inert" "$WORK/$tag-kill" no
}

# ── self-test: the same subject WITHOUT the fix must be caught ────────────────────────────────────
#
# The control is the real script minus the one mechanism under test, not a miniature written to
# fail — a hand-written stand-in proves the checker works on a stand-in. The transformation is
# asserted to have BITTEN, because a sed that silently matched nothing would make the control
# identical to the fixed script and this self-test would then be green for the wrong reason.
self_test() {
  echo "── self-test: without the private index, the residue must be SEEN ──"
  local legacy="$WORK/legacy-policy-single-source.sh"
  sed -e '/^  trap _st_cleanup /d' -e 's/GIT_INDEX_FILE="$_st_idx" //g' "$SUBJECT" > "$legacy"
  if cmp -s "$legacy" "$SUBJECT"; then
    echo "✗ the control transform changed nothing — the fix it removes is no longer spelled" >&2
    echo "  the way this expects, so the control is a copy of the fixed script." >&2
    return 1
  fi
  # Comments naturally still discuss it; what must be gone is every line that RUNS.
  if grep -v '^[[:space:]]*#' "$legacy" | grep -q 'GIT_INDEX_FILE'; then
    echo "✗ the control still drives a private index — the transform did not remove the fix" >&2
    return 1
  fi

  local bad=0 out
  build_lab "$legacy" "$WORK/legacy-term"
  run_interrupted "$WORK/legacy-term" TERM || bad=1
  out="$(residue "$WORK/legacy-term" yes)"
  if [ -z "$out" ]; then
    echo "✗ SELF-TEST FAILED: SIGTERM on the unfixed script left nothing this gate can see." >&2
    bad=1
  else
    printf '  ✓ SIGTERM on the unfixed script is caught:\n'
    printf '%s\n' "$out" | sed 's/^/        /'
  fi

  build_lab "$legacy" "$WORK/legacy-kill"
  run_interrupted "$WORK/legacy-kill" KILL || bad=1
  out="$(residue "$WORK/legacy-kill" no)"
  if [ -z "$out" ]; then
    echo "✗ SELF-TEST FAILED: SIGKILL on the unfixed script left nothing this gate can see." >&2
    bad=1
  else
    printf '  ✓ SIGKILL on the unfixed script is caught:\n'
    printf '%s\n' "$out" | sed 's/^/        /'
  fi
  return $bad
}

if [ "${1:-}" = "--self-test" ]; then
  self_test || exit 1
  echo
fi

echo "── an interrupted self-test leaves this checkout as it found it ──"
check_subject "$SUBJECT" fixed

echo
if [ "$fail" -ne 0 ]; then
  echo "policy-selftest-residue: FAIL — an interrupted gate leaves state in a SHARED place" >&2
  exit 1
fi
echo "policy-selftest-residue: PASS"
