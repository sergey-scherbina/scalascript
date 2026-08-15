#!/usr/bin/env bash
# A declared parameter type is a CONSTRAINT on the native lane.
#
# THE CONTRACT (Sergiy, 2026-08-15, tests/SPRINT.md "TYPES MUST BE RIGHT"): a declared type is a
# constraint unconditionally, and a coercion hint where a coercion is admissible. This gate holds the
# first half on the lane that now implements it. The second half — the other three lanes agreeing —
# is `tests/e2e/declared-type-agreement.sh`, which is NOT written yet because it would arrive red:
# js, v1 and v3 still treat the same declaration three other ways.
#
# WHAT WAS TRUE BEFORE THIS LANDED, and is what the gate exists to stop returning:
#
#     def f(a: Int): Int = a ; println(f("x"))   ->  prints x        (native, and v1, and v3)
#                                               ->  prints 120      (js: char code, via a coercion)
#                                               ->  scalac rejects  (jvm)
#
# The native checker never saw the annotation: two parsers exist in that tier and only the LOWERER's
# keeps types — `ssc1-front.ssc0` erases them with `skipTypeAnnot`, and the checker imports that
# front. (tests/BUGS.md `no-two-type-checkers-in-this-repo-agree`.)
#
# THE RECOGNISED SET IS NARROW ON PURPOSE and this gate pins its EDGES, not just its middle: `Int`,
# `String`, `BigInt`, and only when the type is immediately closed by `,` or `)` — the same boundary
# F draws for lowering. A type outside that set must behave exactly as before rather than
# half-constraining, so the rows below include a `Double` parameter that must still be accepted.
# Two extractions that disagreed would be worse than one that is narrow.
#
# The measured cost of turning this on: **zero of 399 conformance cases**, 148 of which declare a
# parameter in the recognised form. The corpus was already type-correct; nothing checked it.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SSC="$ROOT/bin/ssc"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/ssc-declared-type.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
pass=0; fail=0

[[ -x "$SSC" ]] || { echo "declared-type-is-a-constraint: no $SSC — run ./install.sh --dev" >&2; exit 2; }

# run <name> <src> -> the first line of what the native lane says
run_src() {
    printf '```scalascript\n%s\n```\n' "$2" > "$WORK/$1.ssc"
    SSC_NO_CDS=1 timeout 120 "$SSC" run "$WORK/$1.ssc" 2>&1 | head -1
}

want_rejected() {   # want_rejected <name> <src>
    local out; out="$(run_src "$1" "$2")"
    if printf '%s' "$out" | grep -q 'TYPEERR'; then
        echo "  ok   [$1] rejected: $out"; pass=$((pass + 1))
    else
        echo "  FAIL [$1] a declared type was NOT enforced"
        echo "         got: $out"; fail=$((fail + 1))
    fi
}

want_accepted() {   # want_accepted <name> <expected-output> <src>
    local out; out="$(run_src "$1" "$3")"
    if [ "$out" = "$2" ]; then
        echo "  ok   [$1] accepted: $out"; pass=$((pass + 1))
    else
        echo "  FAIL [$1] a program that must still compile was rejected or changed"
        echo "         wanted: $2"
        echo "         got:    $out"; fail=$((fail + 1))
    fi
}

echo "============================================================"
echo "  a declared parameter type is a constraint (native lane)"
echo "============================================================"
echo

# ── the constraint itself ────────────────────────────────────────────────────────────────────────
want_rejected string-into-int \
'def f(a: Int): Int = a
println(f("x"))'

want_rejected int-into-string \
'def f(a: String): String = a
println(f(1))'

# ── AND THE OTHER DIRECTION, which is what stops this from being a checker that rejects everything ─
want_accepted correct-int 42 \
'def ok(a: Int): Int = a + 1
println(ok(41))'

want_accepted correct-string 'hi!' \
'def ok(a: String): String = a + "!"
println(ok("hi"))'

# ── THE EDGES OF THE RECOGNISED SET. A type outside it must behave exactly as before. `Double` is
#    not in knownTyName, so this program is unconstrained and must still run — if it starts being
#    rejected, the set widened without the consumer census that widening needs.
want_accepted unrecognised-type-still-passes 1 \
'def f(a: Double): Double = a
println(f(1))'

# A generic type is not in the simple position either, and must stay unconstrained.
want_accepted generic-type-still-passes 1 \
'def f(a: List[Int]): Int = 1
println(f(List(1, 2)))'

echo
if [ $fail -eq 0 ]; then
    echo "declared-type-is-a-constraint: OK ($pass checks)"
    exit 0
fi
echo "declared-type-is-a-constraint: $pass ok, $fail FAIL"
exit 1
