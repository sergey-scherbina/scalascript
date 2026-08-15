#!/usr/bin/env bash
set -Eeuo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SSC="$ROOT/bin/ssc-tools"
FIXTURE="$ROOT/tests/conformance/money-portable-v2.ssc"
EXPECTED="$ROOT/tests/conformance/expected/money-portable-v2.txt"
TMP=$(mktemp -d "${TMPDIR:-/tmp}/ssc-v2-swift-cli.XXXXXX")
trap 'rm -rf "$TMP"' EXIT

# ANY unexpected failure NAMES ITSELF. Sixteen commands here redirect their output, and under
# `set -e` each died without a word: the CI step printed "KNOWN GAP ..." as its last line and exited
# 1 with nothing attributable, twice, on two different days.
#
# `-E` propagates the trap into functions and subshells; without it the trap is skipped exactly
# where a helper fails.
#
# THE `$-` TEST IS LOAD-BEARING, and leaving it out broke this gate once already. Eight blocks below
# wrap a command that is SUPPOSED to fail in `set +e` … `set -e`. `set +e` turns off errexit but NOT
# the ERR trap, so a trap without this guard fires on every deliberate failure and aborts a passing
# gate — observed: `FAILED at line 102` on a host where the gate had just run to PASS. When errexit
# is off, the failure is intentional and this says nothing.
trap 'ec=$?; case $- in *e*) echo "v2-swift-cli: FAILED (exit $ec) at line $LINENO: $BASH_COMMAND" >&2; exit $ec ;; esac' ERR

if [[ ! -x "$SSC" ]]; then
  echo "v2-swift-cli: bin/ssc-tools is missing; run scripts/sbtc installBin" >&2
  exit 1
fi

# A SWIFT TOOLCHAIN IS REQUIRED BELOW, and without it this gate failed SILENTLY.
#
# `run-swift` and `run --target macos` compile and execute Swift. GitHub's ubuntu images ship no
# Swift, so on CI those lines died under `set -e` with their output redirected to a temp file —
# the step exited 1 having printed `KNOWN GAP …` as its last word and no reason at all. Measured on
# the 2026-08-15 dispatch: the two gates sharing that step reported `15 ok, 0 FAIL` and
# `all checks passed`, then the step failed with nothing attributable to it.
#
# SKIP LOUDLY rather than fail opaquely, the same shape the rust gates use for `cargo`. This is not
# a pass: the emit half above is pure codegen and runs anywhere, but everything past this point
# needs a compiler this host does not have, and saying so is the only honest verdict available.
# Named rather than hardcoded so the skip branch can be OBSERVED (SSC_SWIFT=/nonexistent) and so a
# host with a side-installed toolchain can point at it. A branch that cannot be exercised is
# indistinguishable from one that does not work.
SWIFT_BIN="${SSC_SWIFT:-swift}"
if ! command -v "$SWIFT_BIN" >/dev/null 2>&1; then
  echo "  [skip] v2-swift-cli: no swift on PATH — the emit checks above ran, the build/run checks"
  echo "         below need a Swift toolchain. NOT a pass: this host cannot test them."
  exit 0
fi

"$SSC" emit-swift --target ios -o "$TMP/ios" "$FIXTURE" >/dev/null
grep -Fq 'platforms: [.iOS(.v16)]' "$TMP/ios/Package.swift"
test -f "$TMP/ios/Sources/AppCore/GeneratedProgram.swift"
if find "$TMP/ios" -name ContentView.swift -print -quit | grep -q .; then
  echo "v2-swift-cli: v1 SwiftUI source leaked into emit-swift" >&2
  exit 1
fi

"$SSC" build --v2 --target macos --out "$TMP/build-a" "$FIXTURE" >/dev/null
"$SSC" build "$FIXTURE" --out "$TMP/build-b" --target macos --v2 >/dev/null
"$SSC" build --target ios --out "$TMP/build-ios" "$FIXTURE" >/dev/null
test -f "$TMP/build-a/macos/Sources/AppCore/SscRuntime.swift"
test -f "$TMP/build-b/macos/Sources/AppCore/SscRuntime.swift"
grep -Fq 'platforms: [.iOS(.v16)]' "$TMP/build-ios/ios/Package.swift"

# A UI PROGRAM, not just the money fixture. Everything above builds
# tests/conformance/money-portable-v2.ssc, which imports no UI primitives — so this gate ran the
# right command on a subject that could not exhibit the failure it exists to catch. A primitive that
# lands on the DOM lane only breaks every UI program on the Swift target and nothing here noticed:
# `forJsonView` did exactly that on 2026-07-20 and went unseen for two and a half weeks
# (v2/BUGS.md swift-macos-build-broken-by-forJsonView).
#
# No Xcode and no macOS runner needed: the failure is `unsupported global` raised by SwiftBackend's
# validation, on the JVM, before any swiftc runs. That is why this belongs here rather than in a
# mac-only job.
UI_FIXTURE="$ROOT/examples/frontend/ios-hello/ios-hello.ssc"
# KNOWN RED against the entry above, declared rather than left failing: the build IS broken today.
# It flips this gate red the moment it starts working, which is the signal to delete this block.
if "$SSC" build --v2 --target macos --out "$TMP/build-ui" "$UI_FIXTURE" >/dev/null 2>&1; then
  echo "v2-swift-cli: the UI target now BUILDS — delete this known-red block, let the check count," >&2
  echo "  and close v2/BUGS.md swift-macos-build-broken-by-forJsonView" >&2
  exit 1
else
  echo "  KNOWN GAP  UI target — swift-macos-build-broken-by-forJsonView (declared, not counted)"
fi

# stderr kept: a failure here used to leave nothing but `set -e` and an exit code.
if ! "$SSC" run-swift "$FIXTURE" >"$TMP/run-swift.out" 2>"$TMP/run-swift.err"; then
  echo "v2-swift-cli: run-swift FAILED — last 15 lines of its stderr:" >&2
  tail -15 "$TMP/run-swift.err" | sed 's/^/  | /' >&2
  exit 1
fi
diff -u "$EXPECTED" "$TMP/run-swift.out"
"$SSC" run --target macos "$FIXTURE" --v2 >"$TMP/run-target.out"
diff -u "$EXPECTED" "$TMP/run-target.out"

"$SSC" package --target macos --out "$TMP/plain-package" "$FIXTURE" \
  >"$TMP/plain-package.out" 2>"$TMP/plain-package.err"
test -f "$TMP/plain-package/macos/Sources/AppCore/GeneratedProgram.swift"
test -f "$TMP/plain-package/macos/Package.swift"
! grep -Fq 'Exception in thread' "$TMP/plain-package.err"
! grep -Fq 'Parser' "$TMP/plain-package.err"

# ── an exact-match assertion that SHOWS THE MISMATCH ────────────────────────────────────────────
#
# `grep -Fqx PATTERN FILE` is silent on failure: with the ERR trap it now names the line, but the
# reader still cannot see WHAT the file said, and `$TMP` is deleted on exit so there is nothing left
# to inspect. Measured 2026-08-15 on the runner: this gate failed at exactly such a line and the log
# carried the expected string and not one character of the actual one.
#
# WHY THE FILE'S CONTENT AND NOT JUST A REFUSAL: what is ESTABLISHED is only that this assertion
# passes here and fails on the runner. Whether the message differs, the file is empty, or the command
# failed some other way is NOT known — all three produce this identical failure, and an earlier
# version of this comment picked one ("differs between hosts") and stated it as fact. Printing the
# content is what will decide it, which is the whole reason for the helper.
expect_line() { # expect_line <file> <exact line>
  if ! grep -Fqx "$2" "$1"; then
    echo "v2-swift-cli: expected this exact line in $(basename "$1"):" >&2
    echo "  want | $2" >&2
    echo "  got  | (file below, first 10 lines)" >&2
    if [ -s "$1" ]; then head -10 "$1" | sed 's/^/       | /' >&2; else echo "       | <empty>" >&2; fi
    return 1
  fi
}

set +e
"$SSC" run --v2 --target ios "$FIXTURE" >"$TMP/ios-run.out" 2>"$TMP/ios-run.err"
IOS_EXIT=$?
set -e
test "$IOS_EXIT" -eq 1
expect_line "$TMP/ios-run.err" \
  'run --target ios: checked program does not define a NativeUi application'
! grep -Fq 'Exception in thread' "$TMP/ios-run.err"

set +e
"$SSC" package --v2 --target ios --out "$TMP/package" "$FIXTURE" \
  >"$TMP/ios-package.out" 2>"$TMP/ios-package.err"
PACKAGE_EXIT=$?
set -e
test "$PACKAGE_EXIT" -eq 1
grep -Fqx \
  'ssc package: --team-id or SSC_TEAM_ID is required' \
  "$TMP/ios-package.err"
! grep -Fq 'Exception in thread' "$TMP/ios-package.err"
! test -e "$TMP/package/ios/Sources/AppCore/GeneratedProgram.swift"

set +e
"$SSC" package --v2 "$FIXTURE" \
  >"$TMP/v2-package-no-target.out" 2>"$TMP/v2-package-no-target.err"
NO_TARGET_EXIT=$?
set -e
test "$NO_TARGET_EXIT" -eq 1
grep -Fqx 'ssc package --v2: --target is required' "$TMP/v2-package-no-target.err"
! grep -Fq 'Exception in thread' "$TMP/v2-package-no-target.err"

set +e
"$SSC" package --v2 --target macos --distribution --team-id TEAM123 \
  --notary-timeout-seconds not-an-integer "$FIXTURE" \
  >"$TMP/macos-timeout.out" 2>"$TMP/macos-timeout.err"
TIMEOUT_EXIT=$?
set -e
test "$TIMEOUT_EXIT" -eq 1
grep -Fqx \
  'ssc package --target macos --distribution: --notary-timeout-seconds must be an integer in 1..3600' \
  "$TMP/macos-timeout.err"
! grep -Fq 'Exception in thread' "$TMP/macos-timeout.err"

set +e
"$SSC" run --v2 --target ios --device "$FIXTURE" \
  >"$TMP/ios-device.out" 2>"$TMP/ios-device.err"
DEVICE_EXIT=$?
set -e
test "$DEVICE_EXIT" -eq 1
grep -Fqx \
  'run --target ios --device: --team-id or SSC_TEAM_ID is required' \
  "$TMP/ios-device.err"
! grep -Fq 'Exception in thread' "$TMP/ios-device.err"

set +e
"$SSC" publish --v2 --target ios --testflight "$FIXTURE" \
  >"$TMP/ios-publish.out" 2>"$TMP/ios-publish.err"
PUBLISH_EXIT=$?
set -e
test "$PUBLISH_EXIT" -eq 1
grep -Fqx \
  'ssc publish --target ios: --team-id or SSC_TEAM_ID is required' \
  "$TMP/ios-publish.err"
! grep -Fq 'Exception in thread' "$TMP/ios-publish.err"

printf '%s\n' '{"key_id":"ONLY"}' >"$TMP/incomplete-api-key.json"
set +e
"$SSC" publish --v2 --target ios --testflight --team-id TEAM123 \
  --api-key-path "$TMP/incomplete-api-key.json" "$FIXTURE" \
  >"$TMP/incomplete-api-key.out" 2>"$TMP/incomplete-api-key.err"
INCOMPLETE_KEY_EXIT=$?
set -e
test "$INCOMPLETE_KEY_EXIT" -eq 1
grep -Fqx \
  'ssc publish --target ios: API key JSON requires non-empty key_id and key' \
  "$TMP/incomplete-api-key.err"
! grep -Fq 'Exception in thread' "$TMP/incomplete-api-key.err"

printf '%s\n' '{"key_id":"K","issuer_id":"I","key":"S"}' >"$TMP/api-key.json"
set +e
PATH=/usr/bin:/bin "$SSC" publish --v2 --target ios --testflight --team-id TEAM123 \
  --api-key-path "$TMP/api-key.json" "$FIXTURE" \
  >"$TMP/missing-fastlane.out" 2>"$TMP/missing-fastlane.err"
MISSING_FASTLANE_EXIT=$?
set -e
test "$MISSING_FASTLANE_EXIT" -eq 1
grep -Fqx \
  'ssc publish --target ios: fastlane is required for fastlane' \
  "$TMP/missing-fastlane.err"
! grep -Fq 'Exception in thread' "$TMP/missing-fastlane.err"

echo "v2-swift-cli: PASS"
