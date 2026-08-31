#!/usr/bin/env bash
#
# import-spelling-double-load-gate — `std/x.ssc` and `../std/x.ssc` reach the same file by two
# roots, and the module is spliced TWICE.
#
#   ./tests/e2e/import-spelling-double-load-gate.sh
#
# THIS GATE PINS A DEFECT. `NativeSourceClosure.resolveImport` sends the two spellings to different
# roots — `std/…` to the STAGED tree, `../std/…` to the importer's parent — and `seen` dedups on
# `canonical.getPath`, which cannot notice, because the two paths canonicalise to two different
# files. Every declaration in the module is spliced twice and the duplicate wins.
# v2/BUGS.md `a-module-reached-by-both-spellings-of-std-is-loaded-twice`.
#
# WHEN IT IS FIXED THIS GATE GOES RED, ON PURPOSE, and its failure message says to close the entry
# rather than to relax the check. Do not repair a red here by deleting the assertion.
#
# THE TWO HALVES ARE ONE ARGUMENT, and this is the whole design:
#
#   * with `../std/…` the program FAILS;
#   * changing that ONE import to `std/…` — the same file, one spelling apart — makes it print 1.
#
# The second half is what turns the first from "something is broken" into "the SPELLING is what
# breaks it". Without it the gate would pass on a sandbox that was simply malformed — and it nearly
# was: the first attempt copied only `std/scljet/index.ssc`, whose own transitive imports then
# failed with an unrelated message. Copying the whole tree is not thoroughness, it is what makes the
# control mean anything.
#
# THE POPULATION IS A CHECKOUT, not user projects: outside the repo there is no sibling `std/`, so
# `../std/…` fails to resolve and nobody is surprised. That is why this is a gate rather than a
# release blocker.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
ssc="${SSC:-$ROOT/bin/ssc}"
sandbox="$(mktemp -d "${TMPDIR:-/tmp}/dblload.XXXXXX")"
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

if [ ! -d "$ROOT/std/scljet" ]; then
  echo "import-spelling-double-load: SKIP — no std/scljet in this checkout, nothing to duplicate."
  echo "  A SKIP, not a pass: the subject is absent, so the check cannot answer."
  exit 0
fi

cp -R "$ROOT/std" "$sandbox/std"
mkdir -p "$sandbox/probe"
cat > "$sandbox/pm.ssc" <<'EOF'
```scalascript
[ByteSlice, byteSliceToList](std/scljet/index.ssc)
[mNoop](probe/m.ssc)

println(1)
```
EOF

write_m() {  # $1 = the spelling used inside probe/m.ssc
  cat > "$sandbox/probe/m.ssc" <<EOF
\`\`\`scalascript
[ByteSlice]($1/scljet/index.ssc)

def mNoop(): Int = 0
\`\`\`
EOF
}

fails=0

# HALF 1 — the defect. `../std` reaches the working tree while `std` reaches the staged one.
write_m "../std"
out_rel="$(timeout 300 "$ssc" run "$sandbox/pm.ssc" 2>&1)"; rc_rel=$?

# HALF 2 — the control. THE SAME FILE, one spelling apart, must work.
write_m "std"
out_abs="$(timeout 300 "$ssc" run "$sandbox/pm.ssc" 2>&1)"; rc_abs=$?

if [ "$rc_abs" -ne 0 ] || [ "$(printf '%s' "$out_abs" | tail -1)" != "1" ]; then
  echo "import-spelling-double-load: FAIL — the CONTROL does not work." >&2
  echo "  With the single-root spelling this program must print 1. It did not, so the sandbox is" >&2
  echo "  malformed and half 1 below would prove nothing about spellings." >&2
  echo "  got: $out_abs" >&2
  fails=$((fails + 1))
else
  echo "  ok   control: the single-root spelling prints 1"
fi

if [ "$rc_rel" -eq 0 ]; then
  echo "  ✓ the two spellings now agree — THE DEFECT IS FIXED." >&2
  echo "    That is what this gate exists to notice. Close v2/BUGS.md" >&2
  echo "    a-module-reached-by-both-spellings-of-std-is-loaded-twice and replace this gate with" >&2
  echo "    one that asserts the agreement; do not delete the assertion to get green." >&2
  fails=$((fails + 1))
elif ! grep -q "unbound global" <<<"$out_rel"; then
  echo "import-spelling-double-load: FAIL — it fails, but not with the double-splice shape." >&2
  echo "  want a message containing 'unbound global' (a declaration spliced twice, the duplicate" >&2
  echo "  winning and shadowing a name). A different failure means the subject drifted." >&2
  echo "  got: $out_rel" >&2
  fails=$((fails + 1))
else
  echo "  ok   defect present: the ../ spelling gives '$(grep -oE 'unbound global: [A-Za-z_][A-Za-z0-9_]*' <<<"$out_rel" | head -1)'"
fi

if [ "$fails" -ne 0 ]; then
  echo "import-spelling-double-load-gate: FAIL ($fails)" >&2
  exit 1
fi
echo "import-spelling-double-load-gate: OK (defect still present; control sound)"
