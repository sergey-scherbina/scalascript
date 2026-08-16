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

  # EVERYTHING THIS FUNCTION PRINTS GOES TO STDERR, and that is not tidiness. Gates call their
  # lane function inside a command substitution — `V1=$(run_lane --v1)` — so anything written to
  # stdout is CAPTURED INTO THE RESULT STRING instead of shown. Measured on the first wiring: the
  # foreign-server diagnostic ended up embedded in the gate's own verdict line and printed twice.
  if [[ -z "$pids" ]]; then
    # Nothing is listening. Either lsof cannot see sockets here, or the caller asked before the
    # server bound — both are the caller's problem to report, and neither is a foreign server.
    echo "  · $label: no listener visible on :$port — ownership NOT checked (lsof saw nothing)" >&2
    return 0
  fi

  for p in $pids; do
    if is_descendant "$p" "$pid"; then
      return 0
    fi
  done

  echo "  [FAIL] $label: :$port is answering, but NOT from the process this gate started (pid $pid)." >&2
  echo "         Whatever replied would have made this gate GREEN with its launcher broken." >&2
  for p in $pids; do
    echo "         holder pid=$p  age=$(ps -o etime= -p "$p" 2>/dev/null | tr -d ' ')  cmd=$(ps -o command= -p "$p" 2>/dev/null | cut -c1-90)" >&2
  done
  return 1
}

# free_port — print a TCP port on 127.0.0.1 that nothing is listening on right now.
#
# WHY THIS EXISTS, and it is not the collision the sibling ratchet already guards. `no-leaked-
# servers.sh` reads PORTS OUT OF SOURCE and reports two DIFFERENT gates in ONE tree sharing a
# number. The collision that actually bit on 2026-08-16 was the SAME gate in THREE worktrees:
# `std-ui-forms` and `request-validation-family` both failed in a `scripts/smoke-ci` run while
# passing standalone minutes later at the same load, because two sibling agents were running their
# own suites on the same Mac. A source-reading check cannot see that by construction — the two
# ports are equal because it is the same line of the same file, which is not drift to detect.
#
# So the fix is the one that file's own text prescribes: "have it allocate one instead of choosing."
#
# THE PORT IS VERIFIED FREE, NOT ASSUMED. Binding :0 and reading the number back is the usual
# trick and it is a lie here — the kernel hands back a port it has just released, and between the
# close and the server's own bind a sibling suite can take it. That race is exactly the failure
# being fixed, so this asks whether anything is listening and retries if so. It is still a TOCTOU
# window, just a small one against a 16k-wide range instead of a certainty against one number.
free_port() {
  local p tries=0
  while [[ $tries -lt 40 ]]; do
    # THE KERNEL PICKS, NOT `$RANDOM`. The first version of this used
    # `$(( 20000 + RANDOM % 20000 ))` and handed out the SAME port on three consecutive calls:
    # a command substitution is a subshell, and zsh reseeds RANDOM identically in each one, so
    # every caller in a run would have got one number. That is the bug this function exists to
    # remove, reintroduced inside the fix. Asking the kernel for :0 cannot collide with a port
    # that is currently bound, which no amount of guessing can promise.
    p=$(python3 -c 'import socket
s = socket.socket()
s.bind(("127.0.0.1", 0))
print(s.getsockname()[1])
s.close()' 2>/dev/null) || p=""
    if [[ -n "$p" ]] && ! lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1; then
      printf '%s\n' "$p"; return 0
    fi
    tries=$(( tries + 1 ))
  done
  # REFUSE rather than fall back to a constant. A fallback would reintroduce the exact bug: a
  # caller that silently gets 8771 under load is the case this function was written to remove.
  echo "free_port: no free port found in 40 tries — refusing to return a fixed one" >&2
  return 1
}

# kill_own_listener <port> <pid> — kill whatever listens on <port>, but ONLY if it descends from
# <pid>, i.e. only if this gate started it.
#
# The shape it replaces was `lsof -ti :$PORT | xargs -r kill -9`, which every server gate here
# used both before booting and on cleanup. With a hard-coded port that is not a tidy-up, it is a
# gate REACHING INTO ANOTHER WORKTREE AND KILLING ITS SERVER — the sibling suite then reports its
# own server "never listened", so the damage surfaces as a defect in the innocent run. That is
# strictly worse than the port collision it accompanies, because a collision is at least visible
# to the gate that loses.
#
# Leaks still have to be cleaned, or `no-leaked-servers.sh` goes red for a real reason, so the
# answer is ownership rather than restraint: same lsof, same kill, filtered by `is_descendant`.
kill_own_listener() {
  local port="$1" own="${2:-0}" p
  [[ -z "$port" || "$own" == "0" ]] && return 0
  for p in $(lsof -ti :"$port" 2>/dev/null); do
    if is_descendant "$p" "$own"; then kill -9 "$p" 2>/dev/null; fi
  done
  return 0
}
