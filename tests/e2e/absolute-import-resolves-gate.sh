#!/usr/bin/env bash
#
# absolute-import-resolves-gate — an import this lane cannot resolve is REFUSED, not silently
# skipped; and the forms that do resolve keep working.
#
# THE DEFECT. `[Response](/abs/path/std/http.ssc)` contributed NOTHING to a `build-rust` crate — no
# case classes, no `extern class`, nothing — and the build still exited 0. The resolver throws on an
# absolute path (os-lib refuses one as a RelPath), the throw was caught, and the import was dropped.
# What the user then saw was a rustc error inside generated code they never wrote:
#
#     error[E0412]: cannot find type `Response` in this scope
#
# THE OTHER LANES ALREADY REFUSED IT, which is what settles the direction of the fix. Measured on
# the same program before anything changed:
#
#     run       ssc: native frontend import not found: /…/std/http.ssc from abs.ssc
#     --v1      Import /…/std/http.ssc: requirement failed: … is not a relative path
#     build-rust  Cargo crate written …                                    exit 0
#
# So an absolute import path is not supported by the language as implemented, and this lane was the
# only one that did not say so. The fix is a refusal, NOT making absolute paths resolve — that would
# be a language change on three lanes and is not what the entry asked for.
#
# THE SECOND ROW IS THE ONE THAT GENERALISES. A plainly MISSING relative import
# (`../../std/nosuchfile.ssc`) was dropped by the same code path, for the same reason, and is now
# refused too. The defect was never about absolute paths; they were how it was found.
#
# THE LAST TWO ROWS ARE THE ANTI-ROWS, and they carry the real risk of this change. The same code
# path deliberately SKIPS a directory, a compiled `.sscc` and a file already inlined elsewhere in the
# graph — those are not failures and must stay silent. A resolvable relative import and a program
# with no imports at all must still emit, or the refusal has been widened into working programs.
#
# COST: four emit-rust runs, no cargo. ~20 s. Runs in smoke.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" ]] || { echo "absolute-import-resolves-gate: no launcher at $tools — run ./install.sh --dev" >&2; exit 2; }

sandbox=$(mktemp -d "$ROOT/examples/_absimp.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

printf '[Response](%s/std/http.ssc)\ndef main(): Unit =\n  println("x")\nmain()\n' "$ROOT" > "$sandbox/abs.ssc"
printf '[Response](../../std/nosuchfile.ssc)\ndef main(): Unit =\n  println("x")\nmain()\n'          > "$sandbox/missing.ssc"
printf '[Response](../../std/http.ssc)\ndef main(): Unit =\n  val r: Response = Response(201, Map(), "y")\n  println(r.status)\nmain()\n' > "$sandbox/rel.ssc"
printf 'def main(): Unit =\n  println("plain")\nmain()\n'                                            > "$sandbox/noimport.ssc"

row() { # $1 name, $2 want-rc, $3 want-substring-in-output ('' = none)
  local name=$1 wantrc=$2 wantmsg=${3:-} out rc
  out=$( (cd "$sandbox" && timeout 300 "$tools" emit-rust "$name.ssc" 2>&1) ); rc=$?
  if [[ "$rc" != "$wantrc" ]]; then
    echo "  ✗ $name: exit $rc, wanted $wantrc — $(printf '%s' "$out" | tail -1 | cut -c1-70)"
    fails=$((fails + 1)); return
  fi
  if [[ -n "$wantmsg" ]] && ! printf '%s' "$out" | grep -qF "$wantmsg"; then
    echo "  ✗ $name: exit $rc as wanted, but the message does not say '$wantmsg': $(printf '%s' "$out" | tail -1 | cut -c1-70)"
    fails=$((fails + 1)); return
  fi
  echo "  ✓ $name: exit $rc${wantmsg:+, says \"$wantmsg\"}"
}

echo "── an import that resolves to nothing is refused, and says which one"
row abs      1 "import not found"
row missing  1 "../../std/nosuchfile.ssc"

echo "── the anti-rows: what resolves still emits"
row rel      0
row noimport 0

echo
if [[ "$fails" -ne 0 ]]; then
  echo "absolute-import-resolves-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "absolute-import-resolves-gate: PASS"
