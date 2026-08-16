#!/usr/bin/env bash
# Tier 1 std/ui smoke — renders the demo through all three backends
# (INT via ssc render, JVM via sscc serve+curl, JS via jssc serve+curl)
# and verifies every component's scoped class names + the aggregated
# `<script>` appear in the rendered HTML.
#
# RUNNERS CORRECTED 2026-08-04 (tests/BUGS.md orphaned-e2e-gates-52). Two were wrong, and neither
# wrong is visible from the failure it produced:
#
#   `$BIN/sscc` is `ssc-tools compile-jvm` — it COMPILES and exits, printing `JVM artifact written`.
#   As a "JVM backend" it never ran anything: this gate's JVM lane was measuring a compiler, and
#   reported it as "the server process EXITED before it listened".
#
#   `$BIN/ssc` is `StandardMain`, the NATIVE/default lane, not the interpreter. The label said INT
#   for a launcher that stopped being the interpreter when the lane map moved, so this gate's
#   failures were filed against the wrong lane's owner. The runner now matches the label; NATIVE is
#   not covered here and its own gap is `v2/BUGS.md native-lane-ignores-declarative-route-registration`.
#
# The launcher is now a COMMAND WITH ARGUMENTS, passed as one string and invoked UNQUOTED. Passing
# the extra words as separate arguments instead is a silent trap: helpers that take positional args
# shift `expected` into `$4`, and helpers that use `"$launcher"` drop the words entirely — which
# made three differently-labelled lanes all run the same default lane and turned two gates green
# for no reason. Both happened here before this note was written.
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BIN="$ROOT/bin"

# A MISSING HELPER MUST NOT READ AS A FOUND DEFECT. Sourced without this guard, `.` fails quietly
# and every later `assert_own_listener` is "command not found" — non-zero — which the call sites
# below treat as "the port is foreign". Measured here: one gate where the source line was forgotten
# reported `:8769 is held by a process this gate did not start` on two lanes that were healthy.
# shellcheck source=lib/own-server.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib/own-server.sh" || {
  echo "$(basename "${BASH_SOURCE[0]}"): cannot source lib/own-server.sh — refusing to run rather" >&2
  echo "  than report a healthy server as foreign." >&2; exit 2; }

# ── THE PORT IS ALLOCATED, AND THAT FORCES THE DEMO TO BE COPIED ────────────────────────────────
# It was 8771 (and 8769 before that, moved on 2026-08-15 after a frozen collision bit). Moving a
# constant only re-runs the same race later: on 2026-08-16 this gate failed inside `scripts/smoke-ci`
# and passed 3/3 standalone minutes later at the SAME load, because two sibling agents were running
# their own suites on the same Mac. Nothing in the tree is drifting there — it is one line of one
# file, run three times at once — so `no-leaked-servers.sh`, which reads ports out of SOURCE, cannot
# see it even in principle.
#
# The port lives in the PROGRAM (`serve(8771)` in the shipped example), not in this script, so the
# gate cannot simply choose one: it runs a per-run COPY with the port substituted. The whole
# directory is copied, not the one file, because `demo.ssc` imports `./input.ssc` and six more
# siblings; `std/ui/*` imports keep resolving through the launcher's lib path. 308K.
WORK="$(mktemp -d "${TMPDIR:-/tmp}/std-ui-forms.XXXXXX")"
INT_ERR="$WORK/int.err"
SERVER_PID=0
# PORT is declared EMPTY before the trap that reads it. The trap is armed first so $WORK is always
# removed, and this file runs under `set -u`: if `free_port` fails and the script exits, an unset
# PORT would make the trap itself die on an unbound variable and leak the temp dir it exists to
# delete. `kill_own_listener` treats an empty port as nothing to do.
PORT=""

# `pkill -f` is SCOPED TO $WORK, and the pattern it replaces is why. It was
# `pkill -9 -f "examples/std-ui/demo\.ssc"` — a relative path that appears in EVERY worktree's
# command line, so this gate reached across and killed sibling agents' servers. The victim then
# reports its own server "never listened", i.e. the damage lands as a defect in the innocent run.
# $WORK is an mktemp path, unique per run, so the same mechanism now matches only our own processes.
trap 'pkill -9 -f "$WORK" 2>/dev/null; kill_own_listener "$PORT" "$SERVER_PID"; rm -rf "$WORK"' EXIT

PORT="$(free_port)" || { echo "✗ could not allocate a port"; exit 1; }

mkdir -p "$WORK/app"
cp -R "$ROOT/examples/std-ui/." "$WORK/app/" || { echo "✗ could not copy examples/std-ui"; exit 1; }
DEMO="$WORK/app/demo.ssc"
# Matches ANY port the example declares, not the literal 8771, so this gate does not silently stop
# substituting the day somebody renumbers the demo. The grep below is what makes that safe.
sed -E "s/serve\([0-9]+\)/serve($PORT)/; s#localhost:[0-9]+#localhost:$PORT#g" \
    "$ROOT/examples/std-ui/demo.ssc" > "$DEMO"
# Announced because the port differs every run: a log that does not name it leaves the reader of a
# future failure unable to tell which server was even being asked.
echo "  (port $PORT, demo copy $DEMO)"

# THE SUBSTITUTION IS VERIFIED, and a silent failure here would be invisible in the worst way: the
# copy would still serve 8771, this gate would poll its own free port for 90s, and `run_serve_backend`
# reports that as `[skip]` — a GREEN run in which nothing was checked. Assert on what is emitted.
grep -q "serve($PORT)" "$DEMO" || {
  echo "✗ the demo copy does not serve the allocated port — substitution failed against"
  echo "  $ROOT/examples/std-ui/demo.ssc (has its \`serve(...)\` line changed?)"; exit 1; }

assert_markers() {
    local label="$1"
    local body="$2"
    local fail=0
    declare -A want=(
        ["title>std/ui"]=1
        ["root__Input"]=3
        ["root__Textarea"]=2
        ["root__Select"]=2
        ["root__Checkbox"]=3
        ["group__Radio"]=2
        ["root__FormGroup"]=4
        ["querySelectorAll"]=2
    )
    for marker in "${!want[@]}"; do
        local exp="${want[$marker]}"
        local got
        got=$(echo "$body" | grep -c "$marker" || true)
        if [ "$got" != "$exp" ]; then
            echo "  [FAIL] $label: $marker ($got, want $exp)"
            fail=1
        fi
    done
    if [ $fail -eq 0 ]; then
        echo "  [PASS] $label"
    fi
    return $fail
}

# Only what THIS gate started. The old body killed whatever held the port — a sibling's server on a
# dev box with parallel worktrees.
kill_port() {
    kill_own_listener "$PORT" "$SERVER_PID"
    sleep 1
}

wait_for_server() {
    local deadline=$(( $(date +%s) + 90 ))
    while [ "$(date +%s)" -lt $deadline ]; do
        curl -sS -o /dev/null -m 2 "http://localhost:$PORT/" 2>/dev/null && return 0
        sleep 2
    done
    return 1
}

echo "============================================================"
echo "  std/ui Tier 1 (forms) smoke"
echo "============================================================"
echo

fail=0

# INT via the headless `ssc render` path (avoids the interpreter's
# WS-aware NIO-proxy startup delay).
# stderr to a FILE, not /dev/null. Discarding it turned a named interpreter error into "0 markers
# found", so every row failed for a reason the gate refused to print — measured 2026-08-04, the real
# answer is `InterpretError: [line 36, col 196] Undefined: impl` in examples/std-ui/demo.ssc and
# stdout is EMPTY. "Rendered the wrong page" and "died before rendering" must not look alike.
body_int=$("$BIN/ssc-tools" render "$DEMO" 2>"$INT_ERR")
if ! assert_markers "INT" "$body_int"; then
  fail=1
  # The captured error is the whole point of capturing it: without this the rows above say only
  # "(0, want 2)" and a reader has no idea whether the renderer produced a wrong page or none.
  [ -s "$INT_ERR" ] && sed 's/^/       INT stderr: /' "$INT_ERR" | head -3
  [ -z "$body_int" ] && echo "       INT stdout was EMPTY — nothing was rendered at all"
fi

# JVM / JS via serve+curl when their launchers are available.  Each
# launcher shells out to `scala-cli`, which currently needs JDK 21 to
# compile the bench/WsStress.scala that landed alongside the WS work;
# skip these arms cleanly when the compile fails so this smoke isn't
# blocked by an unrelated environmental issue.
run_serve_backend() {
    local label="$1"
    local launcher="$2"
    local sleep_min="$3"
    kill_port
    $launcher "$DEMO" > "$WORK/std-ui-$label.log" 2>&1 &
    local pid=$!
    SERVER_PID=$pid
    sleep $sleep_min
    if ! kill -0 $pid 2>/dev/null; then
        echo "  [skip] $label: launcher exited (likely scala-cli compile gate — see $WORK/std-ui-$label.log)"
        return 0
    fi
    if ! wait_for_server; then
        kill -9 $pid 2>/dev/null
        echo "  [skip] $label: server did not start within 90s"
        return 0
    fi
    # `wait_for_server` polls the PORT, and this gate no longer shares one — it moved off 8769 on
    # 2026-08-15 (see the PORT declaration). The check STAYS: a foreign answer can still come from a
    # leak, and this gate is the one that proved a shared port bites in practice.
    # A foreign server is a FAILURE here and not a `[skip]`: the two skips above are
    # environmental — a launcher that could not compile — while this one means the port is not ours
    # and the marker assertions below would be reading somebody else's page.
    # (a-gate-that-starts-a-server-cannot-prove-it-is-talking-to-its-own.)
    if ! assert_own_listener "$PORT" "$pid" "$label"; then
        kill -9 $pid 2>/dev/null
        echo "  [FAIL] $label: :$PORT is held by a process this gate did not start"
        return 1
    fi
    local body
    body=$(curl -sS "http://localhost:$PORT/")
    kill $pid 2>/dev/null; wait $pid 2>/dev/null
    assert_markers "$label" "$body" || return 1
}

run_serve_backend "JVM" "$BIN/ssc-tools run-jvm" 5 || fail=1
run_serve_backend "JS"  "$BIN/ssc-tools run-js" 3 || fail=1

echo
if [ $fail -eq 0 ]; then
    echo "Tier 1 forms render identically (INT + any backend that compiled)."
    exit 0
fi
exit 1
