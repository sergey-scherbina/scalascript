#!/usr/bin/env bash
# The sbt plugin's `scripted` scenarios, run by something.
#
# Why this gate exists, in one sentence: nothing ran them, so a JS commit
# (1fea89a79, "record leaderHistory on every accepted claim") could delete all four of their
# `.ssc-artifacts/*.scim` fixtures and go unnoticed for three weeks — after which two scenarios
# failed and a third, `identity`, PASSED while observing that generation had been skipped entirely.
# A test suite nothing invokes is not coverage, it is a folder of intentions.
#
# The plugin is a SEPARATE sbt build (v1/tools/sbt-plugin/build.sbt): not in the root aggregate, not
# in ci.yml. That is why it needs its own gate rather than a line in an existing one.
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
PLUGIN="$ROOT/v1/tools/sbt-plugin"

[[ -d $PLUGIN ]] || { echo "sbt-plugin-scripted: no plugin at $PLUGIN" >&2; exit 2; }

# The `real-ssc` scenario runs against the STAGED launcher rather than a mock, so it needs one. Said
# here, once, rather than letting that scenario fail with "Cannot run program .../bin/ssc-tools" --
# a message that reads like a broken scenario instead of an unbuilt tree.
[[ -x "$ROOT/bin/ssc-tools" ]] || {
  echo "sbt-plugin-scripted: no staged launcher at $ROOT/bin/ssc-tools — run ./install.sh --dev" >&2
  exit 2
}

# The fixtures are the thing that rotted, so their absence is reported as itself rather than as ten
# confusing scenario failures. `.ssc-artifacts/` is gitignored with an exception for these paths; if
# that exception ever stops matching again, this is the line that says so.
missing=0
for f in \
  basic/.ssc-artifacts/math.scim \
  identity/.ssc-artifacts/math.scim \
  multi-module/.ssc-artifacts/math-core.scim \
  multi-module/.ssc-artifacts/math-trig.scim
do
  p="$PLUGIN/src/sbt-test/sbt-scalascript-interop/$f"
  [[ -f $p ]] || { echo "sbt-plugin-scripted: MISSING fixture $f" >&2; missing=1; }
done
if [[ $missing -ne 0 ]]; then
  echo "sbt-plugin-scripted: fixtures are absent — check .gitignore's !**/src/sbt-test/**/.ssc-artifacts/ exception" >&2
  exit 1
fi

cd "$PLUGIN"
if ! sbt -batch scripted > "${TMPDIR:-/tmp}/sbt-plugin-scripted.log" 2>&1; then
  echo "sbt-plugin-scripted: FAILED" >&2
  grep -E '^\[error\] +x |Failed tests:' -A6 "${TMPDIR:-/tmp}/sbt-plugin-scripted.log" \
    | grep -viE 'at (sbt|java|scala|xsbt)\.' | head -20 >&2
  exit 1
fi
# Counted, not written down. The literal said "10 scenarios" and was wrong four scenarios later --
# a number in a message is a claim, and one nothing checks goes stale the first time the thing it
# describes changes.
count=$(find "$PLUGIN/src/sbt-test/sbt-scalascript-interop" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')
echo "sbt-plugin-scripted: OK ($count scenarios, fixtures present)"
