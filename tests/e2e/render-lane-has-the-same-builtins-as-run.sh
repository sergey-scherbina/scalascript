#!/usr/bin/env bash
#
# render-lane-has-the-same-builtins-as-run — a name that is BOTH a case class and a builtin
# companion must keep both halves on every lane.
#
# `case class Response(...)` in std/http.ssc binds `Response` to its constructor. The builtins bind
# the same name to a companion carrying `html` / `text` / `json` / `redirect`. In Scala those are
# two halves of one name; on the v1 interpreter the import overwrote the companion and half the
# name was gone:
#
#     $ ssc-tools run --v1 repro.ssc
#     No method 'html' on NativeFnV(<native:Response>)     # …while Response(200, Map(), "x") works
#
# ── WHY NOTHING CAUGHT IT, AND WHY THIS GATE IS SHAPED AS A DIFFERENTIAL ─────────────────────────
#
# The default lane resolves it correctly, so every check that runs one lane passed. It took a
# SECOND lane disagreeing to make it visible, and the four gates that would have shown it
# (build/bundle/render/components) were in the unwired pile with two mechanical faults stacked in
# front (tests/BUGS.md orphaned-e2e-gates-52). By the time anyone read the error it looked like a
# render bug; it is not — `ssc-tools run --v1` reproduces it with no route, no handler and no
# render involved. That misreading is why this compares lanes instead of asserting on one.
#
# The constructor row is not decoration. If a future fix makes the companion win outright, ordinary
# construction breaks and only that row would say so.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BIN="$ROOT/bin"
export SSC_NO_BUILD_CHECK=1
echo "── render lane has the same builtins as run"

[ -x "$BIN/ssc-tools" ] || { echo "✗ no ssc-tools launcher at $BIN — build first"; exit 1; }

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
fail=0

# name | expression | expected substring
CASES=(
  "companion-html|Response.html(\"<p>hi</p>\").body|<p>hi</p>"
  "companion-text|Response.text(\"plain\").body|plain"
  "companion-json|Response.json(\"[1]\").status|200"
  "constructor|Response(201, Map(), \"ctor\").body|ctor"
)

for row in "${CASES[@]}"; do
  IFS='|' read -r label expr want <<< "$row"
  cat > "$WORK/$label.ssc" <<EOF
[Response](std/http.ssc)

def main() =
  println($expr)
EOF
  for lane in "run" "run --v1"; do
    # STDOUT ONLY. `2>&1` here was a FALSE PASS, caught 2026-08-02: the interpreter echoes the
    # offending source line in its diagnostic —
    #     4 |   println(Response.html("<p>hi</p>").body)
    # — so grepping the combined stream for `<p>hi</p>` matched the ERROR TEXT and reported a
    # passing cell for a program that had just died. Two of the four cases passed that way, and
    # only `json` (expecting "200", a string absent from the source) failed honestly.
    got="$(timeout 120 "$BIN/ssc-tools" $lane "$WORK/$label.ssc" 2>"$WORK/$label.$$.err")"
    if printf '%s' "$got" | grep -qF "$want"; then
      echo "  ✓ $label  [$lane]"
    else
      echo "  ✗ $label  [$lane] — stdout did not contain '$want'"
      grep -m1 -E "No method|Error|error" "$WORK/$label.$$.err" | sed 's/^/        /'
      fail=1
    fi
  done
done

echo
if [ "$fail" -ne 0 ]; then
  echo "    A name bound to BOTH a case class and a builtin companion must keep both halves."
  echo "    The import path records the displaced side in shadowedAlternatives"
  echo "    (SectionRuntime, rememberShadowedAlternativeForImport); member dispatch has to read it."
  echo "✗ render-lane-has-the-same-builtins-as-run FAILED"
  exit 1
fi
echo "✓ render-lane-has-the-same-builtins-as-run PASSED"
