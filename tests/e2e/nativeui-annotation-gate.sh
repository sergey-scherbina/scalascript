#!/usr/bin/env bash
#
# nativeui-annotation-gate — `serve` is a name TWO plugins answer to, and the diagnosis must say
# which one the call reached.
#
# THE DEFECT, measured 2026-08-18 as the single largest failure bucket in the frontend corpus —
# 10 of 96 files:
#
#   serve(lower(tree, defaultTheme), 8080)
#   -> ssc: native TLS server requires a future server-host extension
#
# a sentence about a feature the program never mentions. `std/ui/primitives.ssc` declares
# `serve(tree: View, port: Int, extraCss: String = "")`; `std/http`'s plugin `serve` takes a PORT
# alone. On the native lane the ui primitive is rewritten to an internal name only for
# `computedSignal` and `eqSignal` — `RunNativeV2` keeps `annotatableSignals` deliberately narrow —
# so a ui `serve(view, port)` stays a plain global and lands in the http plugin, which reports the
# only thing it can imagine a second argument being.
#
# BROADENING THE ANNOTATION WAS TRIED AND REJECTED BY MEASUREMENT, which is why this gate is about a
# MESSAGE and not about coverage. Setting `annotatableSignals` to the full `NativeUiSites`
# annotated set does route the call to the ui plugin — and it then answers `native JVM serve is
# unavailable`. Accurate, and not one of the ten files runs. Buying a better sentence by changing the
# lowering of every site-native primitive is the wrong trade, so the gap stays and the diagnosis is
# fixed where it was wrong.
#
# The blocker recorded against broadening was ALSO re-measured, because a not-fixable verdict is
# dated evidence: BUGS said `arity: 2 expected, 1 given` on `examples/control-center-live.ssc`.
# Today both fronts say `setSignal argument must be NativeUiSignal` — a different error, and
# broadening does not move it either.
#
# `real-tls-case-unchanged` is the row that matters most: the TLS sentence is CORRECT for the call it
# was written for, and a fix that made every arity complaint say "std/ui" would be a regression that
# reads like an improvement.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/nativeui-annot.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

echo "── serve is a name two plugins answer to; the diagnosis must say which one was reached"
ssc_usable_or_skip nativeui-annotation-gate "$ssc"

check() {
  local name=$1 want=$2 src=$3 out
  printf '%s\n' "$src" > "$sandbox/$name.ssc"
  out=$(SSC_NO_BUILD_CHECK=1 timeout 200 "$ssc" run "$sandbox/$name.ssc" < /dev/null 2>&1 | head -1)
  if [[ "$out" == *"$want"* ]]; then
    echo "  ✓ $name"
  else
    echo "  ✗ $name: wanted a message containing '$want'"
    echo "    got: $out"
    fails=$((fails + 1))
  fi
}

# The ui primitive, reaching the http plugin by name collision. The message must name std/ui, say
# which primitive actually answered, and give the way out.
check ui-serve-names-std-ui 'std/ui `serve(view, port)` is not available on the native lane' \
'[signal, serve](std/ui/primitives.ssc)

def main(): Unit =
  val s = signal("s", 1)
  serve(s, 8080)'

check ui-serve-names-the-workaround 'Use `emit` to render' \
'[signal, serve](std/ui/primitives.ssc)

def main(): Unit =
  val s = signal("s", 1)
  serve(s, 8080)'

# THE ROW THAT MUST NOT MOVE. A second argument that IS a port-shaped number is the TLS case the
# original sentence was written for, and it stays.
check real-tls-case-unchanged 'native TLS server requires a future server-host extension' \
'[serve](std/http.ssc)

def main(): Unit = serve(8080, 8443)'

if [[ $fails -eq 0 ]]; then echo "✓ nativeui-annotation-gate PASSED"; exit 0; fi
echo "✗ nativeui-annotation-gate: $fails failure(s)"
exit 1
