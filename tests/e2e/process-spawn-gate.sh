#!/usr/bin/env bash
#
# process-spawn-gate — `spawn` starts a child, returns at once, and the child OUTLIVES the parent.
#
# THE REQUEST (rozum, `process-needs-a-detached-spawn`, `impact: blocks`). They ported fifteen HTTP
# routes of a control console to ScalaScript. Every route that STARTS something — an agent run, a
# coder session, a terminal, a benchmark — hit the same wall, and it was not "no process control":
# `std/process` had exactly one primitive and it WAITS.
#
#     extern def exec(cmd, args, opts): ProcessResult   // stdout, stderr, exitCode
#
# By construction that cannot return before the child is finished, so a handler starting a
# five-minute job could hold the connection for five minutes or not start it. The boundary between
# the Rust half of their port and the ScalaScript half was exactly this primitive.
#
# ROW 4 IS THE ONE THAT COSTS SOMETHING TO ASSERT, and it is the row the report explicitly asked for:
# "the child must survive the server that started it". It cannot be checked from inside the program —
# the program is the parent. So the .ssc spawns a child that writes a marker file three seconds
# later, the gate waits for the PARENT to exit, and only then looks for the marker. An implementation
# that inherits the parent's pipes or process group passes rows 1–3 and fails this one.
#
# ROW 3 ("returned early") IS ASSERTED WITHOUT A CLOCK. Timing a call would measure JVM startup and
# host load as much as the primitive; instead the child sleeps 3s before writing, and the parent
# checks the marker is NOT there yet. That is the same property — `spawn` returned before the child
# finished — measured by a fact rather than by a stopwatch on a contended machine.
#
# THREE LANES: `bin/ssc run`, `ssc-tools run --v1`, `build-rust`. The jvm and js lanes implement
# `__spawnPid` too and are NOT exercised here; naming that is better than implying coverage this
# script does not have. The js lane additionally REFUSES `opts.stdin` for `spawn` (stdio is 'ignore',
# so there is no pipe) — a refusal rather than a silently dropped secret.
#
# COST: one cargo build, three runs, ~4 s of deliberate sleeping per lane. ~60 s.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
ssc="${SSC_BIN:-$ROOT/bin/ssc}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" && -x "$ssc" ]] || { echo "process-spawn-gate: no launcher — run ./install.sh --dev" >&2; exit 2; }

sandbox=$(mktemp -d "$ROOT/examples/_spawn.XXXXXX")
marker="$sandbox/outlived.txt"
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

cat > "$sandbox/d.ssc" <<SSC
[exec, spawn, ProcessOptions](../../std/process.ssc)

def marker(): String = "$marker"

def main(): Unit =
  exec("rm", List("-f", marker()), ProcessOptions())
  val c = spawn("sh", List("-c", "sleep 3; echo survived > " + marker()), ProcessOptions())
  println("pid is positive : " + (c.pid > 0))
  val early = exec("sh", List("-c", "test -f " + marker() + " && echo yes || echo no"), ProcessOptions())
  println("returned early  : " + (early.stdout.trim == "no"))
  val alive = exec("sh", List("-c", "kill -0 " + c.pid + " 2>/dev/null && echo yes || echo no"), ProcessOptions())
  println("child is alive  : " + (alive.stdout.trim == "yes"))

main()
SSC

want=$'pid is positive : true\nreturned early  : true\nchild is alive  : true'

check_lane() { # $1 label, $2 output
  local label=$1 out=$2 row got
  while IFS= read -r row; do
    got=$(printf '%s\n' "$out" | grep -F "${row%%:*}:" || true)
    if [[ "$got" == "$row" ]]; then
      echo "  ✓ $label  ${row}"
    else
      echo "  ✗ $label  ${row%%:*}: got '${got#*: }', wanted '${row#*: }'"
      fails=$((fails + 1))
    fi
  done <<< "$want"
}

# $1 label — run AFTER the parent has exited, which is the whole point of the row.
check_outlived() {
  local label=$1 waited=0
  while [[ $waited -lt 12 ]]; do
    [[ -f "$marker" ]] && break
    sleep 1
    waited=$((waited + 1))
  done
  if [[ -f "$marker" ]]; then
    echo "  ✓ $label  outlived the parent: $(cat "$marker")"
  else
    echo "  ✗ $label  outlived the parent: NO — the child died with the process that spawned it"
    fails=$((fails + 1))
  fi
  rm -f "$marker"
}

echo "── spawn returns at once and the child outlives the parent"

rm -f "$marker"
out=$(timeout 300 "$ssc" run "$sandbox/d.ssc" 2>/dev/null)
[[ -z "$out" ]] && { echo "  ✗ bin/ssc run produced nothing (hang or crash)"; fails=$((fails + 1)); }
check_lane "run    " "$out"; check_outlived "run    "

rm -f "$marker"
out=$(timeout 300 "$tools" run --v1 "$sandbox/d.ssc" 2>/dev/null)
[[ -z "$out" ]] && { echo "  ✗ ssc-tools run --v1 produced nothing (hang or crash)"; fails=$((fails + 1)); }
check_lane "--v1   " "$out"; check_outlived "--v1   "

if command -v cargo >/dev/null 2>&1; then
  if (cd "$sandbox" && timeout 900 "$tools" build-rust "$sandbox/d.ssc" >"$sandbox/build.log" 2>&1); then
    rm -f "$marker"
    out=$(timeout 300 "$sandbox/d" 2>/dev/null)
    [[ -z "$out" ]] && { echo "  ✗ the rust binary produced nothing (hang or crash)"; fails=$((fails + 1)); }
    check_lane "rust   " "$out"; check_outlived "rust   "
  else
    echo "  ✗ build-rust failed:"
    grep -m3 -E 'Generic\(|error\[E[0-9]+\]' "$sandbox/build.log" | cut -c1-120 | sed 's/^/      /'
    fails=$((fails + 1))
  fi
else
  echo "  [skip] cargo is not on PATH — the rust lane is a SKIP, not a pass." >&2
fi

echo
if [[ "$fails" -ne 0 ]]; then
  echo "process-spawn-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "process-spawn-gate: PASS"
