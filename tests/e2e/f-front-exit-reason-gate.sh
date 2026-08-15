#!/usr/bin/env bash
#
# f-front-exit-reason-gate — a documentation module (markdown with no `scalascript` fence) is an
# EMPTY PROGRAM on F, exactly as it already is on the reference front.
#
# TWO HALVES, and the first is why the second went unseen for a week.
#
#   1. NOT FIXED HERE, deliberately. The CLI throws `native frontend exited with N` and discards the
#      reason, so `ssc info --front-report` records an EXIT CODE where a diagnostic exists: the
#      console showed `ssc1-run: no scalascript blocks found: …` while the structured report said
#      only `exited with 1`. Every other census bucket names a mechanism; this one named a number,
#      and a bucket that names no mechanism cannot be ranked — which is how five files sat unread at
#      the bottom of the list. I wrote that fix and reverted it: `TowerResult.output` is the tower's
#      captured STDOUT, and `#io.eprint` writes to `Console.err`, which `runTower` does not capture —
#      so appending `output` would have attached unrelated IR text and called it the reason. Doing it
#      properly means TEEING stderr (capture AND pass through), or every normal run loses its front
#      diagnostics. That is a larger change, and once half 2 lands there is no reachable subject left
#      to gate it with: of F's three `#io.exit` sites the other two are usage errors front-report
#      cannot reach. Filed rather than bodged.
#
#   2. Behind it, ONE mechanism for all five files. F's runner has an ordering pass the reference
#      front does not have at all — `sscOrderRoot`, ssc1-run-fsub.ssc0 — because F assembles its
#      closure from SOURCE and therefore has to order the module graph first. That pass called
#      `#io.exit(1)` when a root had no `scalascript` fences. `sscLoadMod`, a few lines up and
#      IDENTICAL in both runners, keeps the stderr note and continues with an empty statement list,
#      and its comment says why: "Short-circuiting to an empty program instead only trades `exit 1`
#      for an ABI error."
#
# THE SUBJECTS ARE REAL FILES, not fixtures: `std/index.ssc` and `std/graphql.ssc` are documentation
# indexes, `examples/deploy.ssc` is a deployment guide, and the two frontend dashboards are prose
# with ```sh and ```text blocks. The reference front has always run them as empty programs.
#
# THE STDERR NOTE IS KEPT ON PURPOSE and is asserted here. "This file has no code" is worth saying;
# it is exiting on it that diverged. A fix that silenced the note as well would pass a gate that only
# checked the exit code, so `note-still-printed` checks the text is still there.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/exit-reason.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

echo "── a documentation module is an empty program on both fronts"
ssc_usable_or_skip f-front-exit-reason-gate "$ssc"

verdict() { # $1 file → the front-report VERDICT field, from the TSV line only
  SSC_NO_BUILD_CHECK=1 timeout 250 "$ssc" info --front-report "$1" 2>/dev/null | tail -1 | cut -f2
}

# $1 label, $2 path — F must LOWER it (the reference always did).
lowers() {
  local name=$1 f=$2 v
  v=$(verdict "$f")
  if [[ "$v" == "F" ]]; then
    echo "  ✓ $name: F"
  else
    echo "  ✗ $name: front-report says '$v', wanted F   ($f)"
    fails=$((fails + 1))
  fi
}

# $1 label, $2 path — F need not LOWER it, but it must no longer fail for THIS reason. Used where a
# second, unrelated cause is queued behind the one being fixed: `std/index.ssc` stopped exiting on
# the missing fence and now reports `(global summon)`, the context-bound gap. Asserting `F` there
# would be asserting somebody else's fix, and asserting the old failure is gone is what this change
# is actually responsible for. It stays true when `summon` is fixed later.
advanced() {
  local name=$1 f=$2 why
  why=$(SSC_NO_BUILD_CHECK=1 timeout 250 "$ssc" info --front-report "$f" 2>/dev/null | tail -1 | cut -f3)
  if [[ "$why" == *"exited with"* || "$why" == *"no scalascript blocks"* ]]; then
    echo "  ✗ $name: still the no-blocks failure — '$why'"
    fails=$((fails + 1))
  else
    echo "  ✓ $name: past it (now: ${why:-lowered by F})"
  fi
}

# The five corpus files the census found in the reasonless bucket.
lowers deploy-guide       "$ROOT/examples/deploy.ssc"
advanced std-index        "$ROOT/std/index.ssc"
lowers std-graphql        "$ROOT/std/graphql.ssc"
lowers dashboard          "$ROOT/examples/frontend/dashboard/dashboard.ssc"
lowers busi-dashboard     "$ROOT/examples/frontend/busi-dashboard/busi-dashboard.ssc"

# A synthetic one, so the gate keeps a subject if those files ever gain a code fence.
cat > "$sandbox/docs-only.ssc" <<'EOF'
---
name: docs-only
---

# Documentation only

This module carries no `scalascript` fence at all — only shell and text.

```sh
echo hello
```

```text
plain
```
EOF
lowers synthetic-docs-only "$sandbox/docs-only.ssc"

echo "── the note is kept; it was the EXIT that diverged, not the message"

note=$(SSC_NO_BUILD_CHECK=1 SSC_FRONT_STRICT=1 timeout 250 "$ssc" run "$sandbox/docs-only.ssc" 2>&1 | grep -c "no scalascript blocks found" || true)
if [[ "${note:-0}" -ge 1 ]]; then
  echo "  ✓ note-still-printed: stderr still says 'no scalascript blocks found'"
else
  echo "  ✗ note-still-printed: the fix silenced the note as well as the exit"
  fails=$((fails + 1))
fi

# And it runs to completion rather than failing, on BOTH fronts.
for front in F legacy; do
  if [[ "$front" == legacy ]]; then
    out=$(SSC_NO_BUILD_CHECK=1 SSC_FRONT=legacy timeout 250 "$ssc" run "$sandbox/docs-only.ssc" 2>/dev/null; echo "rc=$?")
  else
    out=$(SSC_NO_BUILD_CHECK=1 SSC_FRONT_STRICT=1 timeout 250 "$ssc" run "$sandbox/docs-only.ssc" 2>/dev/null; echo "rc=$?")
  fi
  if [[ "$out" == "rc=0" ]]; then
    echo "  ✓ runs-clean-$front: exit 0, no output"
  else
    echo "  ✗ runs-clean-$front: got '$out', wanted 'rc=0'"
    fails=$((fails + 1))
  fi
done

echo "── a module WITH code is unaffected"

cat > "$sandbox/has-code.ssc" <<'EOF'
---
name: has-code
---

# Has code

```scalascript
def main(): Unit = println(41 + 1)
```
EOF
for front in F legacy; do
  if [[ "$front" == legacy ]]; then
    got=$(SSC_NO_BUILD_CHECK=1 SSC_FRONT=legacy timeout 250 "$ssc" run "$sandbox/has-code.ssc" 2>&1 | head -1)
  else
    got=$(SSC_NO_BUILD_CHECK=1 SSC_FRONT_STRICT=1 timeout 250 "$ssc" run "$sandbox/has-code.ssc" 2>&1 | head -1)
  fi
  if [[ "$got" == "42" ]]; then
    echo "  ✓ code-module-$front: 42"
  else
    echo "  ✗ code-module-$front: '$got', wanted 42"
    fails=$((fails + 1))
  fi
done

if [[ $fails -eq 0 ]]; then echo "✓ f-front-exit-reason-gate PASSED"; exit 0; fi
echo "✗ f-front-exit-reason-gate: $fails failure(s)"
exit 1
