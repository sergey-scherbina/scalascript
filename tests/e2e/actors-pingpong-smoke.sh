#!/usr/bin/env bash
# v1.6 Phase 1 actor cross-backend smoke — asserts the same source produces the same observable
# output on INT (the v1 interpreter), JS (node) and JVM (scala-cli). Output ordering is part of the
# contract: spawn / send / receive / self / exit / timeout-receive are all order-sensitive.
#
# ── 2026-08-14: THE GATE OUTLIVED ITS RUNNER, AND EVERY ASSERTION RAN AGAINST AN EMPTY STRING ─────
#
# It drove `scala-cli run "$ROOT/compiler" --main-class scalascript.cli.ssc` — an sbt project that no
# longer exists anywhere in the repository — with stderr sent to /dev/null. So all three lanes
# produced NOTHING and all three "failed", which reads like a product catastrophe and was a dead
# command. `ROOT` was also one `..` short (`tests/`, from `d0665660a` moving the gates a level down),
# so even the source path was wrong. Found by the orphan drain: this gate is invoked by nothing, and
# it and `wc-card-smoke.sh` were the only two scripts in tests/e2e still naming that project.
# (tests/BUGS.md `orphaned-e2e-gates-52`, batch 4, the "outlived its runner" group.)
#
# TWO THINGS CHANGED IN THE EXPECTATION, and both are corrections backed by a measurement rather
# than by making the gate pass:
#
# 1. The `[exit] actor=3 reason=kill` line is GONE from the expectation. Nothing in the tree emits
#    it — `grep` over every .scala/.ssc/.ssc0 finds no producer — and the program never traps exits
#    or links, so there is nothing that SHOULD print on `exit(w, "kill")`. It is a v1.6-era runtime
#    trace that outlived its emitter, and the gate never ran to notice. What the entry still asserts
#    is the observable consequence that matters: the killed worker's `receive` never runs, so
#    `worker: should never print` must be ABSENT — that is now an explicit check rather than an
#    implicit gap in a diff.
#
# 2. The JS arm no longer needs its own expectation. It carried a substitution for a real
#    divergence — `"after timeout: " + None` printed `[object Object]` on JS — and that divergence
#    is FIXED: all three lanes now print `None` and `Some(got delivered)`. Measured on all three
#    before the substitution was removed. One expectation for three lanes is also the stronger
#    assertion, since agreement is the property this gate exists for.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SSC_TOOLS="$ROOT/bin/ssc-tools"
SRC="$ROOT/examples/actors-pingpong.ssc"

[[ -x "$SSC_TOOLS" && -f "$SRC" ]] || {
    echo "actors-pingpong-smoke: need $SSC_TOOLS and $SRC — run ./install.sh --dev first" >&2
    exit 2
}

echo "============================================================"
echo "  v1.6 Phase 1 — actor ping-pong cross-backend smoke"
echo "============================================================"
echo

expected=$(cat <<'EOF'
pong: one
pong: two
pong: three
after timeout: None
before timeout: Some(got delivered)
done
EOF
)

fail=0

# stderr is dropped on purpose HERE, and only here: the JVM lane's scala-cli prints an
# "Unreachable case" warning for the generated CPS match, which is noise about generated code rather
# than about this program. It is dropped AFTER the command is known to exist — the old version
# dropped it around a command that did not, which is how three empty outputs went unexplained.
run_int() { "$SSC_TOOLS" run --v1 "$SRC" 2>/dev/null; }
run_js()  { "$SSC_TOOLS" run-js   "$SRC" 2>/dev/null; }
run_jvm() { "$SSC_TOOLS" run-jvm  "$SRC" 2>/dev/null; }

check() {
    local name="$1" got="$2" exp="$3"
    if [ "$got" = "$exp" ]; then
        echo "  [PASS] $name"
    else
        echo "  [FAIL] $name"
        echo "  --- diff (expected vs got) ---"
        diff <(printf '%s\n' "$exp") <(printf '%s\n' "$got") | sed 's/^/         /'
        fail=1
    fi
    # The killed worker must never run its receive. Asserted per lane rather than left to the diff:
    # a lane that printed it AND diverged elsewhere would report one failure for two defects.
    if printf '%s' "$got" | grep -q 'worker: should never print'; then
        echo "  [FAIL] $name — a worker killed by exit() still ran its receive"
        fail=1
    fi
}

check "INT" "$(run_int)" "$expected"
check "JS"  "$(run_js)"  "$expected"
check "JVM" "$(run_jvm)" "$expected"

echo
if [ $fail -eq 0 ]; then
    echo "All three backends agree on observable output."
    exit 0
fi
exit 1
