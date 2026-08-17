#!/usr/bin/env bash
#
# f-ui-signal-counter-gate — the two fronts must emit the SAME definitions in the SAME ORDER.
#
# WHY AN ORDER GATE, for a UI-signal defect. `NativeUiSites.annotate` names every anonymous std/ui
# signal `d<ordinal>:<owner>/<path>`, and `ordinal` is literally the INDEX of the definition in
# `program.defs`:
#
#     program.defs.zipWithIndex.map { case (definition, ordinal) => … }
#
# So the identity of a generated signal depends on how many definitions the front emitted before it.
# Measured 2026-08-16, three corpus files reported the same refusal with different numbers —
# F `__computed__d346` against the reference's `d364`, `d342`/`d360`, `d263`/`d272`. Both lanes were
# refusing for an unrelated reason (a genuine duplicate signal), which is the only thing that made
# the divergence visible at all: while both refuse, nobody compares the names.
#
# THE DIVERGENCE IS GONE — measured 2026-08-17, all three files now agree on d360/d364/d272, F having
# moved UP to the reference's numbers, and the two def lists are identical in name AND order (481 of
# them). It closed as a side effect of F's coverage work rather than by anything aimed at it.
#
# WHICH IS EXACTLY WHY THIS GATE EXISTS. The fragility is structural and still present: an ordinal is
# positional, so any future divergence in what the two fronts emit — one dropped definition, one
# reordering — comes back as differently-named globals for the same program, silently, on programs
# that RUN. Changing the id format would paper over it and break the backends that assert it
# (`SwiftBackendTest` pins `__computed__10:localeText:0`). Guarding the property is cheaper and
# catches the real failure: divergent IR.
#
# The instrument this rests on had to be fixed first. `SSC_DUMP_DEFS` printed the F lane always and
# the reference lane only in the delegate-fallback — so for any file F compiles, the reference side
# came back EMPTY, and an empty list reads as "zero definitions" rather than "this path was never
# wired". That is why this gate compares counts explicitly before comparing content: a silent zero
# must fail here, loudly.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/f-ui-signal.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

echo "── the two fronts emit the same definitions in the same order"
ssc_usable_or_skip f-ui-signal-counter-gate "$ssc"

# Subjects chosen for what they exercise, not for being green: all three USE anonymous std/ui signals
# and all three are among the files that showed the divergence. Two of them still refuse at run time
# on the duplicate-signal defect, which does not matter here — this gate reads the DEFINITION LIST
# each front produced, which exists whether or not the program goes on to run.
subjects="examples/frontend/form-demo/form-demo.ssc
examples/frontend/busi-home-demo/busi-home-demo.ssc
examples/frontend/std-ui/styled-primitives.ssc"

while IFS= read -r rel; do
  f="$ROOT/$rel"
  name=$(basename "$rel" .ssc)
  [[ -f "$f" ]] || { echo "  ✗ $name: subject missing at $rel"; fails=$((fails + 1)); continue; }

  SSC_DUMP_DEFS=1 SSC_NO_BUILD_CHECK=1 SSC_FRONT_STRICT=1 timeout 300 "$ssc" run "$f" < /dev/null 2>&1 \
    | sed -n 's/^F-DEF //p' > "$sandbox/$name.F"
  SSC_DUMP_DEFS=1 SSC_NO_BUILD_CHECK=1 SSC_FRONT=legacy timeout 300 "$ssc" run "$f" < /dev/null 2>&1 \
    | sed -n 's/^REF-DEF //p' > "$sandbox/$name.R"

  nf=$(wc -l < "$sandbox/$name.F" | tr -d ' ')
  nr=$(wc -l < "$sandbox/$name.R" | tr -d ' ')

  # A ZERO IS THE INSTRUMENT FAILING, NOT AN ANSWER. Checked before the comparison, because two
  # empty lists compare equal and would report this gate green while measuring nothing at all.
  if [[ "$nf" == "0" || "$nr" == "0" ]]; then
    echo "  ✗ $name: dumped $nf definitions from F and $nr from the reference — a zero means"
    echo "    SSC_DUMP_DEFS is not wired on that lane, not that the front emitted nothing."
    fails=$((fails + 1))
    continue
  fi

  if [[ "$nf" != "$nr" ]]; then
    echo "  ✗ $name: F emitted $nf definitions, the reference $nr"
    echo "    Every generated UI signal after the first difference is now named differently by the"
    echo "    two fronts, for the same program. Missing on one side:"
    diff <(sort "$sandbox/$name.F") <(sort "$sandbox/$name.R") | grep '^[<>]' | head -5 | sed 's/^/      /'
    fails=$((fails + 1))
    continue
  fi

  if diff -q "$sandbox/$name.F" "$sandbox/$name.R" > /dev/null; then
    echo "  ✓ $name: $nf definitions, identical in name and order"
  else
    echo "  ✗ $name: same $nf definitions, DIFFERENT ORDER — the names match as a set, so a count"
    echo "    check alone would pass. First divergence:"
    diff <(cat -n "$sandbox/$name.F") <(cat -n "$sandbox/$name.R") | head -6 | sed 's/^/      /'
    fails=$((fails + 1))
  fi
done <<< "$subjects"

# The property that actually broke, read end to end rather than inferred from the lists above.
probe="examples/frontend/busi-home-demo/busi-home-demo.ssc"
if [[ -f "$ROOT/$probe" ]]; then
  idf=$(SSC_NO_BUILD_CHECK=1 SSC_FRONT_STRICT=1 timeout 300 "$ssc" run "$ROOT/$probe" < /dev/null 2>&1 \
        | grep -oE '__[a-z]+__d[0-9]+' | head -1)
  idr=$(SSC_NO_BUILD_CHECK=1 SSC_FRONT=legacy timeout 300 "$ssc" run "$ROOT/$probe" < /dev/null 2>&1 \
        | grep -oE '__[a-z]+__d[0-9]+' | head -1)
  if [[ -n "$idf" && "$idf" == "$idr" ]]; then
    echo "  ✓ generated-signal-id: both fronts name it $idf"
  elif [[ -z "$idf" && -z "$idr" ]]; then
    echo "  ⊘ generated-signal-id: neither front reached a generated signal id — subject changed"
  else
    echo "  ✗ generated-signal-id: F says '${idf:-none}', the reference says '${idr:-none}'"
    fails=$((fails + 1))
  fi
fi

if [[ $fails -eq 0 ]]; then echo "✓ f-ui-signal-counter-gate PASSED"; exit 0; fi
echo "✗ f-ui-signal-counter-gate: $fails failure(s)"
exit 1
