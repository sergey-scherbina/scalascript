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
# BOTH launchers, not one. The first draft ran only `bin/ssc-tools`, and the prefix assertion below
# was worthless there: `ssc-tools` prints the raw exception while `bin/ssc` wraps it with `ssc: `, so
# the defect the assertion targets is only VISIBLE on the launcher the gate did not run. A gate that
# cannot reach the state it tests measures nothing.
LAUNCHERS=("$ROOT/bin/ssc-tools" "$ROOT/bin/ssc")
for l in "${LAUNCHERS[@]}"; do
  [[ -x $l ]] || { echo "stub-does-not-serialise: no launcher at $l — run ./install.sh --dev" >&2; exit 2; }
done

tmp=$(mktemp -d "${TMPDIR:-/tmp}/stub-gate.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

cat > "$tmp/missing.ssc" <<'SSC'
def main(): Unit =
  val xs = List("a", "b", "c")
  println("map = " + xs.map(s => s + "!").join(", "))
SSC

failed=0
for SSC in "${LAUNCHERS[@]}"; do
  who=${SSC#"$ROOT"/}
  set +e
  out=$("$SSC" run "$tmp/missing.ssc" 2>&1); rc=$?
  set -e

  if [[ $rc -eq 0 ]]; then
    echo "stub-does-not-serialise: FAILED [$who] — a missing method exited 0" >&2
    echo "--- output: $out" >&2
    failed=1
  fi
  if [[ $out == *"map = Stub"* ]]; then
    echo "stub-does-not-serialise: FAILED [$who] — the sentinel was rendered as data" >&2
    echo "--- output: $out" >&2
    failed=1
  fi
  if [[ $out != *"join"* ]]; then
    echo "stub-does-not-serialise: FAILED [$who] — the error does not name the missing method" >&2
    echo "--- output: $out" >&2
    failed=1
  fi
  # The message is user-facing text and `bin/ssc` already prefixes `ssc: `. Both arms shipped with a
  # second one baked in, so what a user read began `ssc: ssc:`. Of the 148 sys.error calls in
  # Runtime.scala exactly those two carried a prefix — the convention is that they do not.
  if [[ $out == *"ssc: ssc:"* ]]; then
    echo "stub-does-not-serialise: FAILED [$who] — the message prefixes itself; the launcher adds 'ssc: '" >&2
    echo "--- output: $out" >&2
    failed=1
  fi
done
# `join` DOES exist now — as an extension in `std/collection-extras.ssc`, which the repro above does
# not import, on purpose. Extensions are opt-in, so an unimported one is still a missing method and
# the repro keeps its tie to the original report. If anyone ever moves `join` onto the collection
# itself this gate goes RED rather than quietly ceasing to test anything, which is the point.

# The other half: ordinary values must still render. A fix that made every DataV fatal would pass
# the assertions above and break the language.
cat > "$tmp/ok.ssc" <<'SSC'
case class P(a: Int, b: String)
def main(): Unit =
  println(P(1, "x"))
  println("xs = " + List(1, 2, 3).toString())
  println(Map("k" -> 1))
SSC
for SSC in "${LAUNCHERS[@]}"; do
  who=${SSC#"$ROOT"/}
  set +e
  ok=$("$SSC" run "$tmp/ok.ssc" 2>&1); okrc=$?
  set -e
  if [[ $okrc -ne 0 || $ok != *"P(1, x)"* ]]; then
    echo "stub-does-not-serialise: FAILED [$who] — ordinary rendering broke" >&2
    echo "--- output: $ok" >&2
    failed=1
  fi
done

[[ $failed -eq 0 ]] || { echo "stub-does-not-serialise: FAILED" >&2; exit 1; }
echo "stub-does-not-serialise: OK (missing method is fatal and named; ordinary values render)"
