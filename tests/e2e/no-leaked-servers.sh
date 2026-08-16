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

# ── THE SECOND CHECK: no two gates may hard-code the same port ────────────────────────────────────
#
# `ports_of` sat here DEFINED AND CALLED BY NOTHING — the last remnant of this file's retracted first
# design, which tried to find leaks by reading ports out of gate sources. Finding leaks that way was
# wrong and the header above says why. Finding COLLISIONS that way is exactly right, because a
# collision IS a property of the sources: two gates naming one port collide whether or not either is
# running. So the function is kept and finally called, for the question it can actually answer.
#
# WHY THE PATTERN IS NARROW, and it is narrow on purpose — measured 2026-08-14 by widening it and
# watching it lie. Three numbers in `tests/e2e/*.sh` look like shared ports and are not:
#
#   8000  x2   v1-jit-size.sh, v2-jit-size.sh      HotSpot's HugeMethodLimit, not a port at all
#   8080  x6   six v21-* gates                     inside a YAML fixture's EXPECTED OUTPUT
#   9999  x2   bundle-smoke.sh, nested-build-smoke.sh   `serve(9999)` in a heredoc of source that
#                                                  those gates BUILD and RENDER and never RUN
#
# A bare `\b[89][0-9]{3}\b` reports six collisions, half of them fiction. Requiring the number to
# appear in a syntax that BINDS — `PORT=`, the `${VAR:-NNNN}` default form, or a `localhost:`/
# `127.0.0.1:` URL — reports three, and all three are real. The `${VAR:-NNNN}` arm is not decoration:
# `request-validation-family-gate.sh` writes `PORT="${SSC_REQUEST_VALIDATION_PORT:-8797}"`, and
# dropping that arm loses a genuine collision. Widen this pattern only with a counterexample in hand.
#
# FROZEN, AND IT IS A RATCHET IN BOTH DIRECTIONS. Today's three are recorded rather than fixed:
# renaming a port in a gate that a workflow pins by number is somebody's else's blast radius, and the
# value here is stopping the FOURTH. So a new collision FAILS, and a frozen collision that someone
# has since fixed ALSO fails — until its line is deleted from the list. A one-way threshold would let
# the list rot into a description of a repository that no longer exists, which is the failure
# `v1-jit-size.sh` documents on its own author.
#
# NONE OF THE THREE IS AN ACTIVE HAZARD TODAY, and that is why this is a ratchet and not a bug fix.
# For two gates to talk to each other's server they must run on one machine. Measured 2026-08-14:
#   8768  only components-smoke.sh is wired; render-smoke.sh and v21-native-entry-smoke.sh are orphans
#   8769  RESOLVED 2026-08-15 — std-ui-forms-smoke.sh moved to 8771. It was the collision that
#         actually bit: an ordinary smoke run had it answering on a neighbour's server. Kept as a
#         line rather than deleted, so the next reader sees a frozen pair CAN be retired.
#   8797  RETIRED 2026-08-16 — request-validation-family-gate.sh now ALLOCATES its port
#         (`free_port`, tests/e2e/lib/own-server.sh) instead of choosing one, so the pair no longer
#         exists and its line is deleted, as the both-ways ratchet requires.
# So the hazard is a LOCAL one (two suites at once on a dev box) plus the day a second gate on one of
# these ports gets wired into the suite that already has one. That day this check goes red first.
#
# WHAT THIS CHECK CANNOT SEE, and it is the one that actually bit. It compares ports ACROSS GATES IN
# ONE TREE. On 2026-08-16 the collision was ONE gate against ITSELF in three worktrees — three agents
# running `scripts/smoke-ci` on one Mac — and `std-ui-forms` and `request-validation-family` both
# failed there while passing standalone minutes later at the same load. Two copies of one line are
# not drift, so no amount of reading source finds it; the fix has to be allocation, and that is what
# `free_port` is for. New server gates should call it rather than earn a line in this list.
# NAMES ARE STORED WITHOUT THE `.sh`, AND THAT IS NOT COSMETIC. `no-orphan-gates.sh` decides whether
# a gate is wired by searching `.github`, `scripts` and `tests` for its basename and keeping matches
# that survive having the comment tail stripped. A frozen list written as `render-smoke.sh` is a
# STRING IN CODE, not a comment, so it survives — and the first version of this list made
# `render-smoke.sh` look invoked, failing that gate with "frozen orphan is now invoked — DELETE it
# from FROZEN". Storing the stem breaks the match while keeping the list readable.
#
# That gate's own header already warns twice about this shape — it matches itself, and a comment is
# not a caller. This is the third variant and the one it cannot defend against: a DATA string is not
# a caller either, and unlike a comment there is nothing about it to strip. Anything else that lists
# gate filenames as data belongs in a `.md`, which `callers_of` excludes, or here without the suffix.
FROZEN_COLLISIONS="8768 components-smoke render-smoke v21-native-entry-smoke"

SELF="$(basename "${BASH_SOURCE[0]}")"

collisions_in() { # collisions_in <dir> -> "<port> <gate> <gate> …" per shared port, sorted
  local d="$1" f p
  for f in "$d"/*.sh; do
    [[ -f "$f" ]] || continue
    # THIS FILE IS NOT IN ITS OWN POPULATION, and it learned that the hard way: the first working
    # version reported 8769 and 8797 as colliding with `no-leaked-servers.sh` itself. Both plants in
    # the self-test below are literal `PORT=8769` / `PORT=8797` in real code lines, and `ports_of`
    # strips comments but cannot strip a string that is genuinely there. That is a MENTION, not a
    # USE — this gate binds nothing — and it is the same mistake, in the checker this time, that the
    # entry above names three separate times: a mention counted as a caller, a mention counted as an
    # execution, ports read out of source instead of processes read off the machine.
    [[ "$(basename "$f")" == "$SELF" ]] && continue
    for p in $(ports_of "$f"); do printf '%s %s\n' "$p" "$(basename "$f" .sh)"; done
  done | sort | awk '{ g[$1] = g[$1] " " $2; n[$1]++ }
                     END { for (p in n) if (n[p] > 1) print p g[p] }' | sort
}

check_collisions() { # check_collisions <dir> -> 0 ok, 1 drifted
  local got want rc=0
  got="$(collisions_in "$1")"
  want="$(printf '%s\n' "$FROZEN_COLLISIONS" | sort)"
  local new gone
  new="$(comm -13 <(printf '%s\n' "$want") <(printf '%s\n' "$got"))"
  gone="$(comm -23 <(printf '%s\n' "$want") <(printf '%s\n' "$got"))"
  if [[ -n "$new" ]]; then
    echo "FAIL  a NEW port collision: two gates hard-code one port, so either can pass by" >&2
    echo "      talking to the server the other left listening." >&2
    printf '%s\n' "$new" | sed 's/^/        /' >&2
    echo "      Give the new gate its own port, or have it allocate one instead of choosing." >&2
    rc=1
  fi
  if [[ -n "$gone" ]]; then
    echo "FAIL  a frozen collision is GONE — somebody fixed it. Delete these lines from" >&2
    echo "      FROZEN_COLLISIONS in $0 so the list keeps describing this repository:" >&2
    printf '%s\n' "$gone" | sed 's/^/        /' >&2
    rc=1
  fi
  [[ $rc -eq 0 ]] && echo "  ok   port collisions: $(printf '%s\n' "$got" | grep -c .) frozen, none new"
  return $rc
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
  # ── and the collision half, planted BOTH ways on a COPY ─────────────────────────────────────────
  # On a copy, never on `tests/e2e` itself: a sibling agent is reading those files, and a check that
  # edits the tree it is judging has already failed. The plants are the two failures that matter —
  # one collision that appeared, one that went away — because a ratchet that only notices additions
  # rots into a description of a repository that stopped existing.
  plant="$(mktemp -d)"; trap 'rm -rf "$plant"' EXIT
  cp tests/e2e/*.sh "$plant"/ 2>/dev/null

  check_collisions "$plant" >/dev/null 2>&1 \
    || { echo "SELF-TEST FAIL: an unmodified copy does not match FROZEN_COLLISIONS — the frozen list is stale" >&2; exit 1; }
  echo "  ok   an untouched copy matches the frozen list"

  # Rule 1 — a NEW collision must be caught. 8769 is already used by two gates, so a third file
  # naming it is a collision the frozen line does not cover: the list is compared by its whole row,
  # so a changed membership reads as one row gone and one row new.
  #
  # THE PLANTS ARE ASSEMBLED, NEVER WRITTEN OUT. `printf 'PORT=%s' "$dup"` rather than the literal,
  # so that no binding-syntax port literal exists anywhere in this file. Written the obvious way it
  # did, and this gate then reported ITSELF colliding with the two gates on 8769 and 8797. The
  # self-exclusion in `collisions_in` also covers that, but a check whose correctness depends on
  # skipping one filename is one rename away from lying, and defence in depth is cheap here.
  dup=8769
  printf 'PORT=%s\n' "$dup" > "$plant/zz-planted-collision-probe.sh"
  if check_collisions "$plant" >/dev/null 2>&1; then
    echo "SELF-TEST FAIL: a planted third gate on port 8769 was NOT reported — the ratchet is blind" >&2
    exit 1
  fi
  echo "  ok   catches a port collision that was not there before"
  rm -f "$plant/zz-planted-collision-probe.sh"

  # Rule 2 — a collision that got FIXED must also fail, until its line is deleted. Planted by
  # rewriting one of the two gates on 8797 to a port nobody uses.
  if [[ -f "$plant/route-params-v2-smoke.sh" ]]; then
    was=8797; now=8399   # assembled, not spelled out — see the note on rule 1
    sed -i.bak "s/PORT=$was/PORT=$now/" "$plant/route-params-v2-smoke.sh" && rm -f "$plant"/*.bak
    if check_collisions "$plant" >/dev/null 2>&1; then
      echo "SELF-TEST FAIL: a frozen collision was fixed and the list still passed — one-way ratchet" >&2
      exit 1
    fi
    echo "  ok   catches a frozen collision that has been fixed and not deleted"
  fi
  rm -rf "$plant"; trap - EXIT

  # ── ownership: a gate must know its own server from anybody else's ─────────────────────────────
  #
  # The sibling defect (`a-gate-that-starts-a-server-cannot-prove-it-is-talking-to-its-own`) is
  # about a gate that polls a port and calls whatever answers a success. It is asserted HERE, and
  # the plant is the whole point: the failing case is a gate whose launcher is BROKEN while a
  # foreign server holds the port, which is exactly the state that used to read GREEN. Observing
  # `assert_own_listener` stay quiet on a healthy run would prove nothing at all.
  . "$(dirname "${BASH_SOURCE[0]}")/lib/own-server.sh"
  oport=19741
  if listening_on "$oport" | grep -q .; then
    echo "no-leaked-servers self-test: port $oport already in use — ownership half skipped" >&2
  else
    listener_cmd="import socket,time,sys
s=socket.socket(); s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
s.bind(('127.0.0.1',$oport)); s.listen(1); sys.stderr.write('up\n'); sys.stderr.flush()
time.sleep(20)"

    # NEGATIVE — somebody else holds the port and our launcher produced nothing that listens.
    python3 -c "$listener_cmd" 2>/dev/null &
    foreign_pid=$!
    sleep 5 & broken_launcher_pid=$!          # what a broken launcher leaves behind: alive, deaf
    for _ in 1 2 3 4 5 6 7 8 9 10; do listening_on "$oport" | grep -q . && break; sleep 0.3; done
    if assert_own_listener "$oport" "$broken_launcher_pid" "PLANT" >/dev/null 2>&1; then
      kill "$foreign_pid" "$broken_launcher_pid" 2>/dev/null
      echo "SELF-TEST FAIL: a FOREIGN server on :$oport was accepted as this gate's own — the" >&2
      echo "  ownership check cannot tell a leaked/sibling process from the one it started" >&2
      exit 1
    fi
    kill "$broken_launcher_pid" 2>/dev/null
    echo "  ok   refuses a foreign server holding the port"

    # POSITIVE, and it must go through a CHILD. `bin/ssc` is a launcher script, so on some lanes the
    # JVM is the same pid and on others it is a descendant; a check that only accepted an exact pid
    # would be testing `exec` versus `&` rather than ownership. The listener here is deliberately a
    # grandchild of the pid handed in.
    kill "$foreign_pid" 2>/dev/null
    for _ in 1 2 3 4 5 6 7 8 9 10; do listening_on "$oport" | grep -q . || break; sleep 0.3; done
    bash -c "python3 -c \"\$1\" 2>/dev/null" _ "$listener_cmd" &
    own_pid=$!
    for _ in 1 2 3 4 5 6 7 8 9 10; do listening_on "$oport" | grep -q . && break; sleep 0.3; done
    if ! assert_own_listener "$oport" "$own_pid" "PLANT"; then
      kill "$own_pid" 2>/dev/null; pkill -P "$own_pid" 2>/dev/null
      echo "SELF-TEST FAIL: a server this script started as a CHILD was called foreign — every" >&2
      echo "  gate wiring this in would go red on a healthy run" >&2
      exit 1
    fi
    kill "$own_pid" 2>/dev/null; pkill -P "$own_pid" 2>/dev/null
    echo "  ok   accepts a server started underneath the pid it was given"
  fi

  echo "no-leaked-servers self-test: PASS (leaks both directions, collisions both directions, ownership both directions)"
  # falls through to the real check, like v1-jit-size.sh: one invocation does both
fi

# The static half runs first: it costs milliseconds, it needs nothing listening, and its verdict is
# about the sources rather than about whatever happens to be running on this machine right now.
collision_rc=0
check_collisions "$ROOT/tests/e2e" || collision_rc=1

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
# BOTH halves decide the exit code. The leak half returns above on failure; the collision half is a
# static verdict taken before it, and swallowing it here would make a red check print PASS — which is
# the shape this suite has been bitten by often enough to have a memory of it.
exit "$collision_rc"
