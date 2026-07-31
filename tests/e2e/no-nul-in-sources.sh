#!/usr/bin/env bash
#
# no-nul-in-sources.sh — no tracked text source may contain a NUL byte.
#
#   ./tests/e2e/no-nul-in-sources.sh              # check the tree
#   ./tests/e2e/no-nul-in-sources.sh --self-test  # prove the check can fail, then check
#
# WHAT THIS GUARDS, and it is not about file hygiene.
#
# One NUL byte makes `grep` treat the WHOLE file as binary, and `grep` then prints nothing — the
# same output it prints for "no matches". Two different states, one answer. Measured 2026-07-31:
# `v2/src/Runtime.scala` (227 KB, the largest runtime source) carried exactly one NUL at byte
# 109523 — a Char literal written as a raw byte instead of an escape — and every `grep` over that
# file had been silently returning "nothing" for anyone who did not know to pass `-a`. Conclusions
# were nearly drawn from that silence.
#
# So this is the same rule the gates already follow, applied to the sources the tools read:
# NOT BEING ABLE TO ANSWER MUST NOT LOOK LIKE ANSWERING "NO".
#
# Binary files that are legitimately binary are excluded by asking git: only paths git considers
# text (`-I` in `git grep`) are candidates, so images, jars and fixtures are not flagged.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

scan() {
  # `git grep -I` skips paths git treats as binary. A file that is text EXCEPT for a stray NUL is
  # exactly the case that is dangerous, so probe every tracked file directly instead of trusting
  # git's own text/binary verdict, which the NUL is what corrupts.
  local target="${1:-}"
  local -a files
  if [[ -n "$target" ]]; then
    files=("$target")
  else
    mapfile -t files < <(git ls-files -- '*.scala' '*.ssc' '*.ssc0' '*.sh' '*.sc' '*.md' '*.mjs' '*.js' '*.ts' '*.yml' '*.yaml' '*.tsv' '*.json')
    # AN EMPTY FILE LIST IS NOT A PASS. Caught on this gate's own first run: invoked from outside a
    # repository, `git ls-files` failed, the list came back empty, and the gate printed "OK" —
    # the exact failure it exists to prevent, in itself. Scanning nothing must be loud.
    if [[ ${#files[@]} -eq 0 ]]; then
      echo "no-nul-in-sources: REFUSING — git listed 0 tracked source files." >&2
      echo "  Not a pass: scanning nothing cannot distinguish a clean tree from an unreadable one." >&2
      echo "  (run from inside the repository; \`git ls-files\` must work here)" >&2
      return 2
    fi
  fi
  python3 - "${files[@]}" <<'PY'
import sys, pathlib
bad = []
for p in sys.argv[1:]:
    f = pathlib.Path(p)
    if not f.is_file():
        continue
    try:
        d = f.read_bytes()
    except OSError:
        continue
    i = d.find(b"\x00")
    if i >= 0:
        line = d[:i].count(b"\n") + 1
        bad.append((p, i, line))
print(f"scanned: {len(sys.argv) - 1} tracked text file(s)   with NUL: {len(bad)}")
for p, i, line in bad[:20]:
    print(f"  FAIL {p}:{line} (byte {i}) — one NUL makes grep treat the WHOLE file as binary, so it")
    print( "       prints nothing for a match AND nothing for no-match. Write the escape instead.")
sys.exit(1 if bad else 0)
PY
}

if [[ "${1:-}" == "--self-test" ]]; then
  echo "--- self-test: a file with a NUL must be rejected ---"
  TMP="$(mktemp -t nul-selftest-XXXXXX).scala"
  printf 'object A { val c = %b }\n' '\0' > "$TMP"
  out="$(scan "$TMP")"; rc=$?
  echo "$out"
  rm -f "$TMP"
  if [[ $rc -eq 0 ]]; then
    echo "SELF-TEST FAILED: the planted NUL passed — this gate proves nothing"; exit 1
  fi
  echo "--- self-test ok (planted NUL caught); checking the tree ---"
fi

scan
rc=$?
if [[ $rc -eq 0 ]]; then echo "no-nul-in-sources: OK"; else echo "no-nul-in-sources: FAIL"; fi
exit $rc
