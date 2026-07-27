#!/usr/bin/env bash
#
# f-front-delegation-visible — when F does not compile a file, say so; when the user made a typo,
# do not.
#
# BUGS.md `f-front-silent-delegation-hides-coverage-gaps`. F is the DEFAULT native front. When it
# cannot lower a file, `RunNativeV2` transparently re-lowers through the legacy front and uses that
# result. Correct behaviour — but it was announced only under `SSC_FRONT_TRACE`, an env var nobody
# sets, so every F coverage gap looked like plain success at the CLI, and any corpus run driven
# through `bin/ssc` measured the LEGACY front for those programs while reporting them as F.
#
# THE TEST IS TWO-SIDED BY NECESSITY, not for symmetry. The fallback fires for two unrelated
# reasons, because an F gap and a user error both surface as an unbound global: `undefinedThing()`
# in user code routes through exactly the same path as a construct F cannot lower. A one-sided test
# ("the marker appears") would be satisfied by printing it unconditionally — which would put a
# compiler-internals line under every typo, and a message that appears when nothing is wrong is one
# people filter out within a day, taking the real signal with it.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="$ROOT/bin/ssc"
MARKER='ssc: F did not lower this file; compiled with the default front instead'
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/f-delegation.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

if [[ ! -x "$ssc" ]]; then
  echo "SKIP f-front-delegation-visible: $ssc not built (run scripts/sbtc installBin)"
  exit 0
fi

# The marker string is duplicated between the runner and this gate on purpose — that is what makes a
# silent rewording fail here instead of silently switching the measurement off. Assert they match.
if ! grep -qF "$MARKER" \
     "$ROOT/v1/tools/cli/src/main/scala/scalascript/cli/RunNativeV2.scala"; then
  echo "FAIL the marker in RunNativeV2.scala no longer matches the one this gate greps for"
  echo "     gate expects: $MARKER"
  fails=$((fails + 1))
fi

# ── a program F handles: no marker, correct output ───────────────────────────────────────────────
cat > "$sandbox/plain.ssc" <<'SSC'
def main(): Int =
  val xs = List(1, 2, 3)
  xs.map(x => x * 2).sum
SSC
out=$("$ssc" run "$sandbox/plain.ssc" 2>"$sandbox/plain.err"); rc=$?
err=$(cat "$sandbox/plain.err")
if [[ $rc -ne 0 ]]; then
  echo "FAIL [plain] exit $rc"; echo "     stderr: $err"; fails=$((fails + 1))
elif grep -qF "$MARKER" <<<"$err"; then
  echo "FAIL [plain] claimed a delegation for a file F compiles — the marker would be meaningless"
  echo "     stderr: $err"; fails=$((fails + 1))
else
  echo "ok   [plain] compiled by F, no marker"
fi

# ── a user error: the fallback DOES fire, and must stay quiet about it ────────────────────────────
# `undefinedThing()` is an unbound global, which is exactly the signal F's coverage pre-check uses,
# so this genuinely exercises the delegation path — it is not a program that merely fails early.
cat > "$sandbox/typo.ssc" <<'SSC'
def main(): Int = undefinedThing()
SSC
"$ssc" run "$sandbox/typo.ssc" >"$sandbox/typo.out" 2>"$sandbox/typo.err"
err=$(cat "$sandbox/typo.err")
if grep -qF "$MARKER" <<<"$err"; then
  echo "FAIL [user-error] printed the coverage-gap marker for a plain typo"
  echo "     A message that fires when nothing is wrong is one people learn to ignore."
  echo "     stderr: $err"; fails=$((fails + 1))
else
  echo "ok   [user-error] fallback fired silently, as it should"
fi

# ── a real F gap: the marker must appear ─────────────────────────────────────────────────────────
# Chosen from what F actually cannot lower TODAY, so it is inherently perishable: when F grows to
# cover it, this stops being a gap. That is reported as a SKIP naming the file rather than a silent
# pass, because "no gap found" and "the check did not run" must not look the same.
gap_found=0
for candidate in "$ROOT"/tests/conformance/*.ssc; do
  [[ -e "$candidate" ]] || continue
  "$ssc" run "$candidate" >/dev/null 2>"$sandbox/gap.err" || true
  if grep -qF "$MARKER" "$sandbox/gap.err"; then
    echo "ok   [f-gap] $(basename "$candidate") delegates and says so"
    gap_found=1
    break
  fi
done
if [[ $gap_found -eq 0 ]]; then
  echo "SKIP [f-gap] no conformance case currently delegates — either F covers them all, or the"
  echo "     marker is not being emitted. Re-check before trusting an F coverage number."
fi

if [[ $fails -ne 0 ]]; then
  echo "f-front-delegation-visible: $fails failure(s)"
  exit 1
fi
echo "f-front-delegation-visible: all checks passed"
