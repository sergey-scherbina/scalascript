#!/usr/bin/env bash
#
# launchers-are-not-dead-on-arrival — every shipped launcher in bin/ must get a two-line
# hello-world PAST the tier wall.
#
# MEASURED 2026-08-02: four of the five delegating launchers were dead. `sscc`, `jssc`, `ssc-js`
# and `ssc-wasm` each resolved `SSC="$SCRIPT_DIR/ssc"` and exec'd a subcommand that the STANDARD
# ssc refuses outright:
#
#     $ bin/sscc hello.ssc
#     ssc: 'compile-jvm' requires the optional ScalaScript tools/compatibility tier
#
# `ssc-spark` failed a step later, on `unknown standard run option: --backend`. So every JVM lane
# and every JS lane in tests/e2e failed at its FIRST call, and the failure looked like a product
# regression ("server did not start within 90s") rather than a launcher that never ran.
#
# ── WHY NOTHING CAUGHT IT ────────────────────────────────────────────────────────────────────────
#
# The gates that drive those launchers were all unwired (BUGS.md orphaned-e2e-gates-52) — they were
# REPORTING this the whole time and nothing was listening. The wired gates go through `bin/ssc`
# directly, which is the one launcher that was fine. A check that only exercises the healthy path
# cannot see a sibling die, which is why this one walks bin/ instead of naming launchers.
#
# ── WHAT IT ASSERTS ──────────────────────────────────────────────────────────────────────────────
#
# Not "the launcher succeeds" — `emit-wasm` needs a Scala.js toolchain that a plain checkout may
# not have, and demanding success would make this gate a flake. The assertion is narrower and is
# exactly the failure that happened: the launcher must not die by DECLINING ITS OWN SUBCOMMAND.
# Anything past the tier wall is somebody else's gate.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BIN="$ROOT/bin"
echo "── launchers are not dead on arrival"

[ -x "$BIN/ssc" ] || { echo "✗ no built launcher at $BIN/ssc — build first"; exit 1; }

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
printf 'def main() =\n  println("ok")\n' > "$WORK/hello.ssc"

# The launchers that delegate to another launcher — the class that broke. Discovered, not listed:
# a hand-written list is the third copy of a fact and would miss the next launcher added.
mapfile -t DELEGATING < <(
  for f in "$BIN"/*; do
    [ -f "$f" ] && [ -x "$f" ] || continue
    grep -qE 'SSC="\$SCRIPT_DIR/' "$f" 2>/dev/null && basename "$f"
  done
)

if [ "${#DELEGATING[@]}" -lt 4 ]; then
  echo "✗ found only ${#DELEGATING[@]} delegating launcher(s) in $BIN — discovery broke."
  echo "    An almost-empty subject list makes every assertion below vacuous, so this fails."
  exit 1
fi
echo "✓ discovered ${#DELEGATING[@]} delegating launchers: ${DELEGATING[*]}"

fail=0
for name in "${DELEGATING[@]}"; do
  out="$(SSC_NO_BUILD_CHECK=1 timeout 240 "$BIN/$name" "$WORK/hello.ssc" 2>&1)"
  if printf '%s' "$out" | grep -q "requires the optional ScalaScript tools"; then
    echo "  ✗ $name: declines its own subcommand — it delegates to the STANDARD ssc"
    printf '%s' "$out" | grep -m1 "requires the optional" | sed 's/^/      /'
    fail=1
  elif printf '%s' "$out" | grep -qE "unknown standard run option"; then
    echo "  ✗ $name: passes a flag the standard ssc does not know — same cause, later step"
    printf '%s' "$out" | grep -m1 "unknown standard" | sed 's/^/      /'
    fail=1
  else
    echo "  ✓ $name: reaches its backend"
  fi
done

echo
if [ "$fail" -ne 0 ]; then
  echo "    Point the launcher at bin/ssc-tools: those subcommands live in the optional tier and"
  echo "    the standard ssc refuses them. Source of truth is v1/tools/scripts/launchers/ —"
  echo "    bin/ holds SYMLINKS to it, so editing bin/ edits the source, but a fresh install.sh"
  echo "    re-links from there and an edit made anywhere else is lost."
  echo "✗ launchers-are-not-dead-on-arrival FAILED"
  exit 1
fi
echo "✓ launchers-are-not-dead-on-arrival PASSED"
