#!/usr/bin/env bash
#
# no-leaked-servers.sh — nothing this project starts may still be LISTENING when no test is running.
#
# WHY. Measured 2026-08-13: two `ssc_program` processes were still listening on 8493 and 8497 with
# no gate in flight. A leaked listener is not untidiness — a later run can talk to it and pass
# without having started anything, which is this suite's own version of "green because it did not
# look".
#
# IT LOOKS AT PROCESSES, NOT AT PORTS NAMED IN SOURCE, and that correction is the whole point. The
# first version of this check enumerated ports out of `tests/e2e/*.sh` and reported PASS while the
# two leaked servers were still listening in front of it — because NO GATE NAMES 8493 or 8497: they
# are chosen by the Rust lane's `target/debug/ssc_program` binaries, which no gate source mentions.
# A check whose population is derived from the wrong place reports green by looking somewhere else.
# That is the third time in two days the POPULATION was the defect rather than the check.
#
# WHAT IT IS NOT. It does not explain the `--evidence` verdict that moved between runs; that was
# first blamed on gates sharing hard-coded ports, and re-measuring with comments stripped and
# restricted to the gates that actually run gave ZERO wired collisions, so the explanation was
# withdrawn. This asserts only what was observed: our servers outlive their test.
# (wired-gates-share-hard-coded-tcp-ports.)
#
# LOCALLY IT CAN BE WRONG, AND SAYS SO. A sibling agent running a test on the same machine has a
# legitimately listening server, indistinguishable from a leak. In CI the job is isolated and the
# reading is exact; locally the failure text names the processes so a human can tell in one look.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

command -v lsof >/dev/null || { echo "no-leaked-servers: needs lsof" >&2; exit 2; }

ports_of() { # ports_of <file> -> one port per line, comments stripped
  sed 's/#.*//' "$1" 2>/dev/null \
    | grep -ohE 'PORT="?\$\{[A-Z_]+:-[0-9]{4,5}\}|PORT=[0-9]{4,5}|localhost:[0-9]{4,5}|127\.0\.0\.1:[0-9]{4,5}' \
    | grep -oE '[0-9]{4,5}' | sort -u
}

listening_on() { lsof -iTCP:"$1" -sTCP:LISTEN -P -n 2>/dev/null | awk 'NR>1{print $2" "$1}' | sort -u; }

if [[ "${1:-}" == "--self-test" ]]; then
  # A detector only ever observed staying quiet is not a detector, so both directions are asserted
  # against a listener this script starts and stops itself.
  probe=19731
  if listening_on "$probe" | grep -q .; then
    echo "no-leaked-servers self-test: port $probe already in use — cannot judge" >&2; exit 2
  fi
  # `nc -l` is not portable enough to rely on; a tiny python listener is.
  python3 -c "
import socket,time,sys
s=socket.socket(); s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
s.bind(('127.0.0.1',$probe)); s.listen(1); sys.stderr.write('up\n'); sys.stderr.flush()
time.sleep(20)
" 2>/dev/null &
  probe_pid=$!
  trap 'kill "$probe_pid" 2>/dev/null' EXIT
  for _ in 1 2 3 4 5 6 7 8 9 10; do listening_on "$probe" | grep -q . && break; sleep 0.3; done
  listening_on "$probe" | grep -q . \
    || { echo "SELF-TEST FAIL: a listener this script started is not seen — the detector is blind" >&2; exit 1; }
  echo "  ok   sees a listener that is there"
  kill "$probe_pid" 2>/dev/null; trap - EXIT
  for _ in 1 2 3 4 5 6 7 8 9 10; do listening_on "$probe" | grep -q . || break; sleep 0.3; done
  listening_on "$probe" | grep -q . \
    && { echo "SELF-TEST FAIL: reports a listener after it is gone — every gate would look leaky" >&2; exit 1; }
  echo "  ok   quiet once it is gone"
  echo "no-leaked-servers self-test: PASS (both directions)"
  # falls through to the real check, like v1-jit-size.sh: one invocation does both
fi

# Anything of OURS still listening. `ssc_program` is the Rust lane's built binary; a JVM carrying
# `ssc.lib.path` is a gate's server. Both are ours and neither should outlive its test.
#
# AND EVERY LEAK IS DATED AND PLACED, because the first two this check caught were neither what nor
# where the entry assumed. Measured 2026-08-13, the same two pids it had named hours earlier:
#
#     pid 44704  ssc_program  *:8497   started Sat Aug 8 19:25  age 5d3h  ppid 1
#                cwd /private/tmp/claude-501/-Users-sergiy-work-my-rozum/…/scratchpad/probe3-rust
#     pid 48753  ssc_program  *:8493   started Sat Aug 8 19:32  age 5d3h  ppid 1
#                cwd …/-Users-sergiy-work-my-rozum/…/scratchpad/probe6-rust
#
# FIVE DAYS old, reparented to init, and started from ANOTHER PROJECT's scratchpad — ad-hoc probes
# whose launcher was killed, not this suite's gates. The entry's remaining work reads "the Rust
# lane's cleanup path must stop its server on every exit"; `RunRustCmd` already installs a shutdown
# hook and cleans up in its catch, and no hook survives SIGKILL, which is what killing an agent's
# probe does. So AGE + CWD are printed for every hit: without them this reads as our gates leaking
# and sends the next person into `RunRustCmd`.
#
# The verdict is scoped rather than softened. On CI every process on the runner is this job's, so
# any hit fails. Locally a hit is only OURS when its cwd is inside this checkout; anything else is
# printed as a NOTE with its provenance and does not fail, because a five-day-old process from a
# different repository is not something a run of this suite can fix — and a check that is
# permanently red for someone else's mess stops being read at all.
in_ci="${CI:-}"
ours="$(lsof -iTCP -sTCP:LISTEN -P -n 2>/dev/null \
        | awk 'NR>1{print $2"\t"$1"\t"$9}' | sort -u || true)"
leaks=""; foreign=""
while IFS=$'\t' read -r pid comm addr; do
  [[ -n "${pid:-}" ]] || continue
  cmd="$(ps -o command= -p "$pid" 2>/dev/null || true)"
  case "$cmd" in
    *ssc_program*|*ssc.lib.path*) ;;
    *) continue ;;
  esac
  age="$(ps -o etime= -p "$pid" 2>/dev/null | tr -d ' ')"
  cwd="$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -1)"
  row="$pid\t$comm\t$addr\t${age:-?}\t${cwd:-?}\t$cmd\n"
  if [[ -n "$in_ci" || "${cwd:-}" == "$ROOT"* ]]; then leaks="$leaks$row"; else foreign="$foreign$row"; fi
done <<< "$ours"

show_rows() { # $1 = rows, tab-separated
  printf "$1" | while IFS=$'\t' read -r pid comm addr age cwd cmd; do
    [[ -n "${pid:-}" ]] || continue
    printf '        pid %-7s %-12s %-22s age %-12s %s\n' "$pid" "$comm" "$addr" "$age" "$(printf '%s' "$cmd" | cut -c1-52)" >&2
    printf '            cwd %s\n' "$cwd" >&2
  done
}

if [[ -n "$foreign" ]]; then
  echo "NOTE  listening, ours by binary but NOT from this checkout — not this run's to reap:" >&2
  show_rows "$foreign"
fi

if [[ -n "$leaks" ]]; then
  echo "FAIL  a server this project started is still LISTENING with no test running:" >&2
  show_rows "$leaks"
  echo "        A later run can talk to this and pass without starting anything." >&2
  echo "        Whatever started it must stop it on EVERY exit path, not only the happy one." >&2
  echo "        Check the AGE first: seconds means a live sibling test, days means a real orphan." >&2
  exit 1
fi
echo "no-leaked-servers: PASS (nothing of ours is listening from this checkout)"
