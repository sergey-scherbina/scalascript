#!/usr/bin/env bash
#
# conformance-lanes-flag — the two run.sc behaviours the smoke suite now depends on:
#
#   1. `--lanes` restricts which backend lanes run, and says so when it skips one.
#   2. `--only` that matches NOTHING is an ERROR, not a green run over zero cases.
#
# WHY THEY NEED A GATE. `scripts/smoke-ci` names 13 conformance cases by hand in an `--only`, and
# splits them across two invocations by `--lanes`. Both flags fail in the same silent direction:
#
#   * `--only` used to exit 0 on zero matches, printing "Results: 0 passed, 0 failed out of 0 tests".
#     One renamed case would have quietly shrunk the push-path corpus check; one mistyped list would
#     have emptied it. Every check green, nothing tested.
#   * `--lanes` value could be swallowed as the POSITIONAL corpus directory — the trap run.sc already
#     documents for `--shard 0/4`, where `0/4` became the corpus dir and the run silently tested
#     nothing. Same shape, same invisibility.
#
# These are cheap flags with an expensive failure mode, which is exactly the combination that earns a
# gate rather than an argument.
#
# The cases below are chosen so that each ASSERTS A COUNT or a specific message. "It exited 0" proves
# nothing here — an empty run exits 0 too, which is the whole problem.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

RUN=(scala-cli tests/conformance/run.sc --)
SAMPLE=arithmetic          # no `backends:` line, so every lane is eligible — the case that can
                           # distinguish "lane filtered out" from "case does not support the lane"

fail() { printf 'conformance-lanes-flag[%s]: %s\n' "$1" "$2" >&2; exit 1; }

capture() { # capture <expected-exit> <label> <args…>
  local want="$1" label="$2"; shift 2
  local out code
  set +e
  out="$("${RUN[@]}" "$@" 2>&1)"
  code=$?
  set -e
  if [[ "$code" -ne "$want" ]]; then
    fail "$label" "expected exit=$want, got=$code:
$out"
  fi
  printf '%s' "$out"
}

# ── 1. --lanes actually filters, and the skip names the RIGHT reason ──────────
# Two assertions, and the second is the one that matters: reporting a caller's `--lanes` choice as
# `backends:` would blame the CASE for a decision it did not make, and send the reader off to edit
# front-matter that is perfectly correct.
out="$(capture 0 lanes-filters --only "$SAMPLE" --lanes int --no-memo)"
grep -q 'PASS \[INT\]'              <<<"$out" || fail lanes-filters "the requested lane did not run:
$out"
grep -q 'SKIP \[JS \] (--lanes: int)' <<<"$out" || fail lanes-reason "an excluded lane did not report \`--lanes\` as the reason:
$out"
grep -q 'SKIP \[JVM\] (--lanes: int)' <<<"$out" || fail lanes-reason "an excluded lane did not report \`--lanes\` as the reason:
$out"
# The excluded lanes must be VISIBLE as skips. A lane that simply vanished from the output would be
# indistinguishable from a lane that passed, in a file whose whole job is per-lane accounting.
[[ "$(grep -c 'SKIP \[' <<<"$out")" -ge 2 ]] || fail lanes-visible "excluded lanes vanished instead of being reported as SKIP:
$out"

# The complement: without the flag, the same case DOES run the other lanes. Without this the case
# above passes for a suite that never ran JS at all.
out="$(capture 0 lanes-default --only "$SAMPLE" --no-memo)"
grep -qE '(PASS|FAIL|KNOWN-RED) \[JS ' <<<"$out" || fail lanes-default "with no --lanes, the JS lane still did not run — the filter case above proves nothing:
$out"

# ── 2. --lanes must not be eaten as the positional corpus directory ───────────
# `--only X --lanes int` selecting its case is the observable: if `int` had been taken as the corpus
# dir, the run would have found no cases there and (before fix 1) exited 0 having tested nothing.
out2="$(capture 0 lanes-not-positional --only "$SAMPLE" --lanes int --no-memo)"
grep -q "1 matching case(s)" <<<"$out2" \
  || fail lanes-not-positional "the case count is not 1, so --lanes may have been read as the corpus dir:
$out2"

# ── 3. an unknown lane is refused, not silently ignored ───────────────────────
out="$(capture 2 lanes-unknown --only "$SAMPLE" --lanes intt)"
grep -q 'unknown lane' <<<"$out" || fail lanes-unknown "an unknown lane did not produce a naming error:
$out"
# Silently ignoring it would run ALL lanes and look like a pass; silently dropping it would run none.
out="$(capture 2 lanes-empty --only "$SAMPLE" --lanes ,,)"
grep -q 'requires at least one lane' <<<"$out" || fail lanes-empty "an empty --lanes was accepted:
$out"

# ── 4. --only matching nothing is an ERROR ────────────────────────────────────
# THE case this gate exists for. Before 2026-07-31 this exited 0.
out="$(capture 2 only-empty --only 'no-such-case-6f1c2d')"
grep -q 'matched no case' <<<"$out" || fail only-empty "a zero-match --only did not say so:
$out"

# And a NON-empty --only still works, or the check above could be satisfied by refusing everything.
out="$(capture 0 only-nonempty --only "$SAMPLE" --lanes int --no-memo)"
grep -q 'PASS \[INT\]' <<<"$out" || fail only-nonempty "a matching --only stopped working:
$out"

printf 'conformance-lanes-flag: PASS\n'
