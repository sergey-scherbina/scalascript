#!/usr/bin/env bash
# own-server.sh — a gate that starts a server must be able to prove the server answering is ITS OWN.
#
# THE FAILURE THIS EXISTS FOR, and why it is green-coloured. A gate boots a server on a fixed port,
# polls `http://localhost:$PORT` until something answers, and treats an answer as proof its launcher
# worked. Anything already listening satisfies that: a leaked process, a sibling agent's run, a
# second gate on the same number. So a gate whose launcher is COMPLETELY BROKEN still reports
# success — the worst pairing there is, because nobody re-reads a green.
# (tests/BUGS.md `a-gate-that-starts-a-server-cannot-prove-it-is-talking-to-its-own`.)
#
# WHY OWNERSHIP AND NOT A NONCE. The entry offers two acceptance tests: a nonce echoed by a health
# route, or an OS-allocated port. Both need the SERVED PROGRAM to change — and the port these gates
# use is written in the example itself (`examples/health-defaults.ssc` ends in `serve(8769)`), so
# either one edits `examples/` for a test-harness problem. Asking the OS which process holds the
# socket needs nothing from the program and answers the same question: is the thing answering the
# thing I started?
#
# WHY A PROCESS TREE AND NOT A PID. `bin/ssc` is a launcher script; whether the JVM ends up as the
# same pid (exec) or a child (spawn) is an implementation detail of the launcher, and it differs
# between the four lanes these gates drive. Comparing bare pids would make this check a test of
# `exec` vs `&`. So a listener counts as ours when it IS the pid we started or DESCENDS from it.
#
# Usage, from a gate that already has `local pid=$!`:
#
#     . "$(dirname "${BASH_SOURCE[0]}")/lib/own-server.sh"
#     assert_own_listener "$PORT" "$pid" "NATIVE" || return 1
#
# Availability: needs `lsof`, which every gate here already uses to free its port. When `lsof`
# cannot answer at all, the check SKIPS with a printed note rather than failing — a harness that
# refuses to run on a box without `lsof` is a worse outcome than one that says it could not look.

# listener_pids <port> — pids LISTENing on the port, one per line.
listener_pids() {
  lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null | sort -u
}

# is_descendant <pid> <ancestor> — true when pid is ancestor, or below it in the process tree.
# Walks up rather than down: a parent chain is bounded and needs one `ps` per step, where
# enumerating descendants needs the whole process table.
is_descendant() {
  local pid="$1" ancestor="$2" hops=0
  while [[ -n "$pid" && "$pid" != "0" && "$pid" != "1" && $hops -lt 32 ]]; do
    [[ "$pid" == "$ancestor" ]] && return 0
    pid="$(ps -o ppid= -p "$pid" 2>/dev/null | tr -d ' ')"
    hops=$((hops + 1))
  done
  return 1
}

# assert_own_listener <port> <pid> [label] — 0 when a listener on <port> belongs to <pid>'s tree.
#
# Prints the foreign holder's identity when it fails, because "the port is not yours" is useless
# without "…it belongs to this instead": the two real causes, a leaked orphan and a sibling agent's
# live run, are told apart by the command and the age, not by the port number.
assert_own_listener() {
  local port="$1" pid="$2" label="${3:-server}"
  local pids p
  pids="$(listener_pids "$port")"

  if [[ -z "$pids" ]]; then
    # Nothing is listening. Either lsof cannot see sockets here, or the caller asked before the
    # server bound — both are the caller's problem to report, and neither is a foreign server.
    echo "  · $label: no listener visible on :$port — ownership NOT checked (lsof saw nothing)"
    return 0
  fi

  for p in $pids; do
    if is_descendant "$p" "$pid"; then
      return 0
    fi
  done

  echo "  [FAIL] $label: :$port is answering, but NOT from the process this gate started (pid $pid)."
  echo "         Whatever replied would have made this gate GREEN with its launcher broken."
  for p in $pids; do
    echo "         holder pid=$p  age=$(ps -o etime= -p "$p" 2>/dev/null | tr -d ' ')  cmd=$(ps -o command= -p "$p" 2>/dev/null | cut -c1-90)"
  done
  return 1
}
