#!/usr/bin/env bash
# What a DECLARED TYPE means, on every lane, in one table — frozen.
#
# THE CONTRACT (Sergiy, 2026-08-15, tests/SPRINT.md "TYPES MUST BE RIGHT"): a declared type is a
# constraint unconditionally, and a coercion hint where a coercion is admissible. The lanes do not
# implement that uniformly yet, so this gate does NOT demand agreement — a gate red on arrival is how
# a suite becomes noise. It freezes the CURRENT verdict of every lane and fails when one changes
# silently. The table can only shrink toward agreement, and every shrink is a deliberate edit here.
#
# Same shape as `no-orphan-gates`' frozen debt: hold the invariant, derive the rest.
#
# ── WHY A TABLE AND NOT AN ASSERTION ──────────────────────────────────────────────────────────────
#
# On 2026-08-15 the same three-line program got FOUR different treatments and two different VALUES:
#
#     def f(a: Int): Int = a ; println(f("x"))
#       native  x     interpreter  x     js  120     jvm  scalac rejects     v3  x
#
# `120` is the code point of 'x' — the js lane coerced a one-character String at a declared `Int`.
# That is fixed (3555c8fce, the helper was split so `Char -> Int` survives and `String -> Int` does
# not), and the native lane now CONSTRAINS a declared type at all three declaration sites
# (fab2ea769, c9ee83035). What is left is a single clean line rather than a four-way split:
#
#     native REJECTS; interpreter, js and v3 all AGREE with each other and accept.
#
# So the remaining work is "bring three lanes up to native", not "reconcile four behaviours" — and
# that is exactly what this table makes visible. Rows marked AGREE are done; rows marked DIVERGE are
# the backlog.
#
# ── WHY jvm IS NOT IN THE TABLE ───────────────────────────────────────────────────────────────────
#
# It rejects both ill-typed rows, via scalac rather than via anything this project wrote, and it
# costs ~40 s per program — three programs would triple this gate's runtime to assert a property
# owned by another compiler. Recorded here rather than measured on every run:
#     jvm REJECTS param-declared and use-derived, and prints 42 for the control ([E007]).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/ssc-lane-agreement.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
fail=0

# ── the battery: one typing question each, and a control that must stay green everywhere ─────────
prog_param_declared='def f(a: Int): Int = a
println(f("x"))'
prog_use_derived='def h(a: Int): Int = a + 1
println(h("x"))'
prog_control='def ok(a: Int): Int = a + 1
println(ok(41))'

# ── THE FROZEN TABLE. `REJECT` means the lane refused the program; anything else is what it PRINTED.
#    Editing a cell here is how a lane's behaviour changes — deliberately, in a diff somebody reads.
#    Measured 2026-08-15 on a freshly built toolchain.
#
#    program            native    v1        js        v3
#    param-declared     REJECT    x         x         x        <- DIVERGE: only native constrains
#    use-derived        REJECT    x1        x1        x1       <- DIVERGE: only native constrains
#    control-ok         42        42        42        42       <- AGREE
frozen() {
  # --self-test plants ONE wrong cell and requires this gate to fail on it. Without that, a table
  # that matches everything is indistinguishable from a table that compares nothing — the failure
  # mode this repository has paid for repeatedly (tests/BUGS.md orphaned-e2e-gates-52).
  if [ "${SSC_LANE_AGREEMENT_PLANT:-}" = "1" ] && [ "$1/$2" = "control-ok/native" ]; then
    echo "__planted_wrong__"; return
  fi
  case "$1/$2" in
    param-declared/native) echo REJECT ;;  param-declared/v1) echo x  ;;
    param-declared/js)     echo x      ;;  param-declared/v3) echo x  ;;
    use-derived/native)    echo REJECT ;;  use-derived/v1)    echo x1 ;;
    use-derived/js)        echo x1     ;;  use-derived/v3)    echo x1 ;;
    control-ok/native)     echo 42     ;;  control-ok/v1)     echo 42 ;;
    control-ok/js)         echo 42     ;;  control-ok/v3)     echo 42 ;;
    *) echo "__no_frozen_cell__" ;;
  esac
}

run_lane() {  # run_lane <lane> <file> -> REJECT, or the first line printed
  local lane="$1" f="$2" out
  case "$lane" in
    native) out="$(SSC_NO_CDS=1 timeout 150 "$ROOT/bin/ssc" run "$f" 2>&1 | head -1)" ;;
    v1)     out="$(SSC_NO_CDS=1 timeout 150 "$ROOT/bin/ssc-tools" run --v1 "$f" 2>&1 | head -1)" ;;
    js)     out="$(SSC_NO_CDS=1 timeout 300 "$ROOT/bin/ssc-tools" run-js "$f" 2>&1 | grep -v '^\[' | head -1)" ;;
    # 600, NOT 200, and the control probe above is why that is enough anywhere. `v3/ssc3` FETCHES a
    # compiler through coursier and packages both trees into `v3/.jars` on its first call — the
    # `Validate` job spends 5.3 min on exactly that in a step of its own. This gate runs in
    # `Examples and launcher smokes`, which never builds v3, so on a cold runner every v3 cell timed
    # out at 200 s and came back EMPTY. Measured on run 33053494265: `frozen '42', got ''` for the
    # control. The probe pays the cold build once; the three rows after it hit a warm `.jars`.
    v3)     out="$(SSC_NO_CDS=1 timeout 600 "$ROOT/v3/ssc3" run "$f" 2>&1 | tail -1)" ;;
  esac
  # A refusal is a refusal whatever wording the lane uses. Everything else is the printed value, and
  # comparing the VALUE is the point: two lanes that both "work" while printing different things is
  # the failure this table exists to make impossible to miss.
  if printf '%s' "$out" | grep -qE 'TYPEERR|type error|Type Mismatch'; then echo REJECT
  else printf '%s' "$out"; fi
}

if [ "${1:-}" = "--self-test" ]; then
  echo "--- self-test: a planted wrong cell must FAIL this gate ---"
  if SSC_LANE_AGREEMENT_PLANT=1 "$0" > "$WORK/plant.out" 2>&1; then
    echo "SELF-TEST FAILED: the gate PASSED with a deliberately wrong frozen cell — it compares nothing"
    sed 's/^/       | /' "$WORK/plant.out"
    exit 1
  fi
  grep -q "control-ok/native" "$WORK/plant.out" \
    && echo "ok   the planted cell is rejected, and the message names it" \
    || { echo "SELF-TEST FAILED: it failed, but did not name the planted cell"; exit 1; }
  echo "--- self-test ok; running the real table ---"
  echo
fi

echo "============================================================"
echo "  what a declared type means, per lane — frozen table"
echo "============================================================"
echo

# A LANE IS JUDGED ON THE CONTROL FIRST, and the other two rows are only compared if it passed.
# On run 33046772911 `v3/ssc3` produced nothing in the `Examples and launcher smokes` job and this
# gate reported THREE divergences — `frozen 'x', got ''` twice plus `frozen '42', got ''` for the
# control itself. One absent binary, counted three times, with the row that would have explained it
# buried among the two that could not.
#
# NOT A SKIP, and that distinction is the whole design: a lane failing its control still FAILS this
# gate, once, naming the control. What it stops doing is claiming the lane disagreed about the
# ill-typed rows — it never answered them. A lane that cannot print 42 has said nothing about types.
printf '```scalascript\n%s\n```\n' "$prog_control" > "$WORK/__probe.ssc"
broken=""
for lane in native v1 js v3; do
  probe="$(run_lane "$lane" "$WORK/__probe.ssc")"
  if [ "$probe" != "$(frozen control-ok "$lane")" ]; then
    broken="$broken $lane"
    echo "  FAIL [control-ok/$lane] frozen '$(frozen control-ok "$lane")', got '$(printf '%s' "$probe" | cut -c1-70)'"
    echo "         This lane cannot answer the CONTROL, so its other cells are not compared — they"
    echo "         would report a disagreement it never expressed. Fix the lane, or the harness."
    fail=1
  fi
done
[ -n "$broken" ] && echo

agree=0; diverge=0
for name in param-declared use-derived control-ok; do
  case "$name" in
    param-declared) src="$prog_param_declared" ;;
    use-derived)    src="$prog_use_derived"    ;;
    control-ok)     src="$prog_control"        ;;
  esac
  printf '```scalascript\n%s\n```\n' "$src" > "$WORK/$name.ssc"
  row=""; distinct=""
  for lane in native v1 js v3; do
    case " $broken " in *" $lane "*)
      row="$row $(printf '%-8s' '-')"
      continue ;;
    esac
    got="$(run_lane "$lane" "$WORK/$name.ssc")"
    want="$(frozen "$name" "$lane")"
    if [ "$want" = "__no_frozen_cell__" ]; then
      echo "  FAIL [$name/$lane] no frozen cell — add one, deliberately"
      fail=1
    elif [ "$got" != "$want" ]; then
      echo "  FAIL [$name/$lane] frozen '$want', got '$got'"
      echo "         A lane changed. If that was intended, edit the frozen table and say why."
      fail=1
    fi
    row="$row $(printf '%-8s' "$got")"
    case "$distinct" in *"|$got|"*) ;; *) distinct="$distinct|$got|" ;; esac
  done
  n_distinct=$(printf '%s' "$distinct" | tr '|' '\n' | grep -c . || true)
  if [ "$n_distinct" -eq 1 ]; then verdict="AGREE  "; agree=$((agree + 1))
  else verdict="DIVERGE"; diverge=$((diverge + 1)); fi
  printf '  %-16s %s   %s\n' "$name" "$row" "$verdict"
done

echo
echo "  rows in agreement: $agree    diverging: $diverge"
echo "  (jvm is recorded in this file's header rather than run: it rejects both ill-typed rows via"
echo "   scalac, at ~40 s per program.)"
echo
if [ $fail -eq 0 ]; then
    echo "declared-type-agreement: OK — every lane matches its frozen cell"
    exit 0
fi
echo "declared-type-agreement: FAILED"
exit 1
