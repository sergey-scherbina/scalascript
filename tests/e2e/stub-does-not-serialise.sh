#!/usr/bin/env bash
# A missing method must not become output.
#
# `Stub` is the runtime's soft landing for "no such method" — dispatch keeps it, deliberately. What
# it must never do is turn into a STRING, because then a typo becomes data: rozum had
# `{"cell":{Stub}}` reach an HTTP response body with a 200 and not one line in the log, so a missing
# method arrived at an end user as plausible JSON.
#
# The two arms that did it were `anyStr` (string interpolation and `+` — the path rozum's server
# actually took) and `Show.show`. Both are shared by every lane, which is why this gate exists and
# why the full suite is the real check.
#
# It also asserts the message NAMES the method. The sentinel used to arrive with its breadcrumb
# blanked, so the failure said "Stub" and nothing else — the one fact a person debugging needs.
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
SSC="$ROOT/bin/ssc-tools"
[[ -x $SSC ]] || { echo "stub-does-not-serialise: no launcher at $SSC — run ./install.sh --dev" >&2; exit 2; }

tmp=$(mktemp -d "${TMPDIR:-/tmp}/stub-gate.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

cat > "$tmp/missing.ssc" <<'SSC'
def main(): Unit =
  val xs = List("a", "b", "c")
  println("map = " + xs.map(s => s + "!").join(", "))
SSC

failed=0
set +e
out=$("$SSC" run "$tmp/missing.ssc" 2>&1); rc=$?
set -e

if [[ $rc -eq 0 ]]; then
  echo "stub-does-not-serialise: FAILED — a missing method exited 0" >&2
  echo "--- output: $out" >&2
  failed=1
fi
if [[ $out == *"map = Stub"* ]]; then
  echo "stub-does-not-serialise: FAILED — the sentinel was rendered as data" >&2
  echo "--- output: $out" >&2
  failed=1
fi
if [[ $out != *"join"* ]]; then
  echo "stub-does-not-serialise: FAILED — the error does not name the missing method" >&2
  echo "--- output: $out" >&2
  failed=1
fi

# The other half: ordinary values must still render. A fix that made every DataV fatal would pass
# the assertions above and break the language.
cat > "$tmp/ok.ssc" <<'SSC'
case class P(a: Int, b: String)
def main(): Unit =
  println(P(1, "x"))
  println("xs = " + List(1, 2, 3).toString())
  println(Map("k" -> 1))
SSC
set +e
ok=$("$SSC" run "$tmp/ok.ssc" 2>&1); okrc=$?
set -e
if [[ $okrc -ne 0 || $ok != *"P(1, x)"* ]]; then
  echo "stub-does-not-serialise: FAILED — ordinary rendering broke" >&2
  echo "--- output: $ok" >&2
  failed=1
fi

[[ $failed -eq 0 ]] || { echo "stub-does-not-serialise: FAILED" >&2; exit 1; }
echo "stub-does-not-serialise: OK (missing method is fatal and named; ordinary values render)"
