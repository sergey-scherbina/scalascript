#!/usr/bin/env bash
#
# smoke-ci-precondition-gate.sh — `scripts/smoke-ci` must REFUSE an unbuilt checkout, not run it.
#
#   ./tests/e2e/smoke-ci-precondition-gate.sh              # check
#   ./tests/e2e/smoke-ci-precondition-gate.sh --self-test  # prove the check can fail, then check
#
# WHAT THIS GUARDS.
#
# `bin/ssc` is TRACKED IN GIT; `bin/lib/` is a build product and is not. So a fresh worktree from
# `scripts/new-worktree` has an executable launcher and no toolchain behind it. smoke-ci's launcher
# precondition (`[[ -x bin/ssc ]]`) passed there, and so did its staleness check — both of that
# check's branches are conditional on `bin/lib/.build-digest` or `.build-stamp` being READABLE, and
# in an unbuilt tree neither file exists, so both are skipped rather than failed. The suite then
# started, failed to load `scalascript.cli.StandardMain`, and exited 1 after running ZERO checks.
#
# THAT IS INDISTINGUISHABLE FROM A RED. The guard was careful about a STALE tower and blind to an
# ABSENT one — the strictly worse case, because a stale tower at least gives a verdict about some
# code, while an absent one gives a verdict about none while wearing a red's exit status.
#
# Measured 2026-08-31: two agents hit this independently within minutes, several cycles each. One of
# them was at that moment trying to determine whether the suite STALLS, where an instant exit 1 is
# the opposite failure mode — so the blindness cost an extra round just to tell the two apart.
#
# HOW THE ASSERTION IS SCOPED. Asserting that smoke-ci "fails" on an unbuilt tree would pass BEFORE
# the fix too — it always failed, just uselessly. So the load-bearing assertion is the negative one:
# the output must NOT contain `Could not find or load main class`, which is present exactly when the
# launcher was reached. That string can only disappear if execution stopped before it.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# A disposable repo with the real smoke-ci, a stub launcher, and deliberately no build products.
# smoke-ci derives its ROOT from `git rev-parse --show-toplevel` of its own directory, so this must
# be a real repository, and it must not be the one we are running in.
make_tree() {
  local tmp="$1" with_jar="$2"
  mkdir -p "$tmp/scripts" "$tmp/bin"
  cp "$ROOT/scripts/smoke-ci" "$tmp/scripts/smoke-ci"
  chmod +x "$tmp/scripts/smoke-ci"
  # The stub stands in for the launcher. If smoke-ci ever reaches it, it prints the SAME diagnostic
  # the real one printed in the incident, so the gate's negative assertion is testing the real
  # string rather than a paraphrase of it.
  cat > "$tmp/bin/ssc" <<'STUB'
#!/usr/bin/env bash
echo "Error: Could not find or load main class scalascript.cli.StandardMain" >&2
exit 1
STUB
  chmod +x "$tmp/bin/ssc"
  # A `scripts/smoke-ci.ssc` must exist only insofar as the launcher is asked for it; the stub never
  # reads it. Its absence must not be what stops us, so create it.
  : > "$tmp/scripts/smoke-ci.ssc"
  if [[ "$with_jar" == "with-jar" ]]; then
    mkdir -p "$tmp/bin/lib/standard"
    : > "$tmp/bin/lib/standard/ssc.jar"
  fi
  git -C "$tmp" init -q
  git -C "$tmp" add -A
  git -C "$tmp" -c user.email=gate@local -c user.name=gate commit -qm init
}

# Returns the combined output of running smoke-ci in a tree built to order.
run_in_tree() {
  local with_jar="$1" tmp
  tmp="$(mktemp -d)"
  make_tree "$tmp" "$with_jar"
  ( cd "$tmp" && timeout 60 scripts/smoke-ci --list 2>&1 )
  rm -rf "$tmp"
}

check() {
  local out rc=0
  out="$(run_in_tree no-jar)"

  # THE LOAD-BEARING ASSERTION. Reaching the launcher is what must not happen.
  if grep -q "Could not find or load main class" <<<"$out"; then
    echo "smoke-ci-precondition: FAIL — smoke-ci reached the launcher on an UNBUILT tree." >&2
    echo "  It must refuse before running anything: a suite that executes zero checks and" >&2
    echo "  exits 1 is indistinguishable from a real red." >&2
    echo "  --- got ---" >&2
    sed 's/^/  /' <<<"$out" >&2
    rc=1
  fi

  # And the refusal has to SAY what is wrong, or the next agent debugs the suite instead of building.
  if ! grep -q "never built" <<<"$out"; then
    echo "smoke-ci-precondition: FAIL — refused, but without saying the checkout was never built." >&2
    echo "  A bare non-zero exit sends the reader to debug the suite. Name the cause." >&2
    echo "  --- got ---" >&2
    sed 's/^/  /' <<<"$out" >&2
    rc=1
  fi

  # The positive direction: a tree that HAS the artifact must get past this guard. Without it the
  # gate would be satisfied by a smoke-ci that refuses unconditionally.
  local out2
  out2="$(run_in_tree with-jar)"
  if ! grep -q "Could not find or load main class" <<<"$out2"; then
    echo "smoke-ci-precondition: FAIL — smoke-ci did NOT reach the launcher on a tree that has" >&2
    echo "  bin/lib/standard/ssc.jar. The guard is over-broad and would block real runs." >&2
    echo "  --- got ---" >&2
    sed 's/^/  /' <<<"$out2" >&2
    rc=1
  fi

  [[ $rc -eq 0 ]] && echo "smoke-ci-precondition: OK — refuses an unbuilt tree, passes a built one."
  return $rc
}

# ── --self-test: the check must FAIL against a smoke-ci with the guard removed ────────────────────
#
# Without this, a gate that never looks reads identically to a gate that looks and is satisfied.
self_test() {
  local tmp stripped rc=0
  tmp="$(mktemp -d)"
  stripped="$tmp/smoke-ci-without-guard"
  # Recreate the PRE-FIX script by deleting the guard block, keyed on the sentinel string the guard
  # prints. If this stops matching, the self-test fails loudly rather than silently testing nothing.
  if ! grep -q "never built" "$ROOT/scripts/smoke-ci"; then
    echo "smoke-ci-precondition --self-test: REFUSING — no guard found in scripts/smoke-ci to strip." >&2
    echo "  Cannot construct the negative control, so a pass here would prove nothing." >&2
    rm -rf "$tmp"; return 2
  fi
  awk '/^STANDARD_JAR=/{skip=1} skip && /^fi$/{skip=0; next} !skip' \
    "$ROOT/scripts/smoke-ci" > "$stripped"
  if grep -q "never built" "$stripped"; then
    echo "smoke-ci-precondition --self-test: REFUSING — strip did not remove the guard." >&2
    rm -rf "$tmp"; return 2
  fi

  # Run the same check against the stripped script and require it to FAIL.
  local probe; probe="$(mktemp -d)"
  mkdir -p "$probe/scripts" "$probe/bin"
  cp "$stripped" "$probe/scripts/smoke-ci"; chmod +x "$probe/scripts/smoke-ci"
  cat > "$probe/bin/ssc" <<'STUB'
#!/usr/bin/env bash
echo "Error: Could not find or load main class scalascript.cli.StandardMain" >&2
exit 1
STUB
  chmod +x "$probe/bin/ssc"; : > "$probe/scripts/smoke-ci.ssc"
  git -C "$probe" init -q; git -C "$probe" add -A
  git -C "$probe" -c user.email=gate@local -c user.name=gate commit -qm init
  local out; out="$( cd "$probe" && timeout 60 scripts/smoke-ci --list 2>&1 )"
  if ! grep -q "Could not find or load main class" <<<"$out"; then
    echo "smoke-ci-precondition --self-test: FAIL — the guard-less script did not reach the" >&2
    echo "  launcher, so this gate's central assertion cannot tell the two apart." >&2
    rc=1
  else
    echo "smoke-ci-precondition --self-test: OK — without the guard, the launcher IS reached."
  fi
  rm -rf "$tmp" "$probe"
  return $rc
}

if [[ "${1:-}" == "--self-test" ]]; then
  self_test || exit $?
fi
check
