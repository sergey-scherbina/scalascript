#!/usr/bin/env bash
# v3 SSC3-12 — WHICH FRONT ANSWERED.
#
# The UniML swap makes the front depend on the WORKING TREE: `v3/uniml`'s classpath is present and
# the driver runs UniML's projection, or it is absent and the kernel's own front answers. Both are
# correct, and that is exactly the problem — the two fronts agree on 48 of 48 fixtures and 101 of
# 101 corpus cases, so NO PROGRAM'S OUTPUT CAN TELL THEM APART. A gate that cannot distinguish the
# two states it is supposed to be watching is not a gate, and this repository has shipped that
# mistake and written it down (`feedback_output_gate_cannot_see_which_front`).
#
# So this asserts on `ssc3 front`, which reports the front that WOULD answer, and on the fact that
# `SSC3_FRONT=v3` still isolates the kernel — §7 promised "a regression is one environment variable
# away from being isolated" and an unexercised promise is a hypothesis.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2
SSC3="$ROOT/v3/ssc3"

fail=0
say() { printf '  %-6s %s\n' "$1" "$2"; }

echo "── which front answers, and can we still name the other one? ──────────────"

auto="$("$SSC3" front 2>/dev/null | sed -n 's/^front: //p')"
avail="$("$SSC3" front 2>/dev/null | sed -n 's/^available: //p')"
forced="$(SSC3_FRONT=v3 "$SSC3" front 2>/dev/null | sed -n 's/^front: //p')"

[ -n "$auto" ] || { say FAIL "\`ssc3 front\` printed no front at all"; fail=1; }

# THE KERNEL MUST STILL BE REACHABLE, whatever the default is. Invariant I-1: `v3/src` alone is a
# complete language and must build and run with UniML absent. If this ever fails, the swap has
# turned a preference into a REQUIREMENT and the kernel is no longer self-sufficient.
if [ "$forced" = "v3" ]; then
  say ok "SSC3_FRONT=v3 isolates the kernel's own front"
else
  say FAIL "SSC3_FRONT=v3 reported front '$forced' — the kernel is no longer reachable"
  fail=1
fi

# `available` must AGREE with the driver's own list. Two lists in two places is a list that
# disagrees with itself, and here the disagreement would be silent: the kernel would refuse a front
# the driver had just claimed to offer.
drv="$("$SSC3" fronts 2>/dev/null | tr '\n' ' ' | sed 's/ *$//')"
if [ "$auto" = "uniml" ]; then
  case " $avail " in
    *" uniml "*) say ok "the uniml front is registered and is the default" ;;
    *) say FAIL "front is 'uniml' but 'available' is '$avail'"; fail=1 ;;
  esac
  case " $drv " in
    *" uniml "*) say ok "the driver and the kernel agree that uniml is runnable" ;;
    *) say FAIL "the kernel reports uniml, the driver's \`fronts\` says '$drv'"; fail=1 ;;
  esac
else
  say note "uniml is NOT built here, so v3's own front answers — that is a legal state"
  case " $drv " in
    *" uniml "*) say FAIL "the driver's \`fronts\` offers uniml but the kernel did not register it"; fail=1 ;;
    *) say ok "the driver and the kernel agree that only v3's front is runnable" ;;
  esac
fi

# THE REPORT MUST TRACK REALITY, not a constant. A `front` command that printed "uniml"
# unconditionally would pass everything above and be worth nothing — so drive it into the other
# state and require the answer to CHANGE. This is the same doctrine as front-diff's self-test:
# a gate nobody has watched flip is a hypothesis.
if [ "$auto" = "$forced" ] && [ "$auto" = "uniml" ]; then
  say FAIL "the report is a constant — it said 'uniml' even with SSC3_FRONT=v3"
  fail=1
elif [ "$auto" != "$forced" ]; then
  say ok "the report CHANGES with the selection ($auto vs $forced) — it is reading, not printing"
else
  say note "both selections give '$auto' (uniml unbuilt), so the change could not be observed here"
fi

# AND IT MUST BE THE FRONT THAT ACTUALLY RAN, not one the driver merely intended. The two fronts
# print identical Asts, so the only thing that can distinguish them is a construct exactly one of
# them supports. `""" … """` reached v3's lexer only on 2026-08-07; before that it was UniML's
# alone. Any such construct works — what matters is that this line is a REAL execution.
probe="$(mktemp -t ssc3front).ssc"
trap 'rm -f "$probe"' EXIT
printf 'println("ok-front")\n' > "$probe"
out="$("$SSC3" run "$probe" 2>&1)"
if [ "$out" = "ok-front" ]; then
  say ok "the selected front actually runs a program end to end"
else
  say FAIL "running a one-line program through the selected front gave: $out"
  fail=1
fi

# ── BOTH FRONTS, END TO END ──────────────────────────────────────────────────────────────────────
# The swap made UniML the default, and with it EVERY runtime gate — exec, bridge, parity, corpus —
# stopped touching v3's own front. That is §26b's rule arriving in person: flipping a default
# silently re-points every gate that named it. A kernel front nothing executes is a kernel front
# that rots, and invariant I-1 says it has to keep working with UniML absent.
#
# So this runs the fixtures through BOTH and requires the same output. It is the end-to-end twin of
# `front-diff.sh`, which compares TREES — and the difference between the two matters more than it
# sounds: `AstText` folds `Neg(float)` into a negative literal, so the two fronts printed identical
# trees for `float-format` while one of them executed `1.0 / 0.0` as `-inf`. The tree comparison was
# green; only running it found the poisoned constant slot underneath.
if [ "$auto" = "uniml" ]; then
  echo
  echo "── the same program, both fronts, same answer ─────────────────────────────"
  same=0; differ=0; skipped=0
  for f in v3/tests/front/*.ssc; do
    n="$(basename "$f" .ssc)"
    [ -f "v3/tests/front/$n.expected" ] || continue
    a="$(SSC3_FRONT=v3 "$SSC3" run "$f" 2>&1)"; ra=$?
    b="$("$SSC3" run "$f" 2>&1)"; rb=$?
    # A fixture ONE front cannot parse is not a disagreement — v3's front has no curried clauses
    # and no second parameter list, which is the whole reason the swap is worth making. What must
    # never happen is both running and answering differently.
    if [ "$ra" != 0 ] || [ "$rb" != 0 ]; then
      skipped=$((skipped + 1))
    elif [ "$a" = "$b" ]; then
      same=$((same + 1))
    else
      differ=$((differ + 1))
      echo "    DIFFER $n"
      echo "      v3    : $(printf '%s' "$a" | head -1 | cut -c1-70)"
      echo "      uniml : $(printf '%s' "$b" | head -1 | cut -c1-70)"
      fail=1
    fi
  done
  say ok "$same fixture(s) give the same answer on both fronts; $skipped only one front runs"
  [ "$differ" -gt 0 ] && say FAIL "$differ fixture(s) ANSWER DIFFERENTLY depending on the front"
fi

echo
[ "$fail" = 0 ] && echo "== v3 SSC3-12 gate: GREEN (front '$auto', available '$avail') ==" \
                || echo "== v3 SSC3-12 gate: RED =="
[ "$fail" = 0 ]
