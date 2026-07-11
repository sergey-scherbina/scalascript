#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SSC="$ROOT/bin/ssc"
FIXTURE="$ROOT/tests/conformance/money-portable-v2.ssc"
EXPECTED="$ROOT/tests/conformance/expected/money-portable-v2.txt"
TMP=$(mktemp -d "${TMPDIR:-/tmp}/ssc-v2-swift-cli.XXXXXX")
trap 'rm -rf "$TMP"' EXIT

if [[ ! -x "$SSC" ]]; then
  echo "v2-swift-cli: bin/ssc is missing; run scripts/sbtc installBin" >&2
  exit 1
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

"$SSC" run-swift "$FIXTURE" >"$TMP/run-swift.out"
diff -u "$EXPECTED" "$TMP/run-swift.out"
"$SSC" run --target macos "$FIXTURE" --v2 >"$TMP/run-target.out"
diff -u "$EXPECTED" "$TMP/run-target.out"

set +e
"$SSC" run --v2 --target ios "$FIXTURE" >"$TMP/ios-run.out" 2>"$TMP/ios-run.err"
IOS_EXIT=$?
set -e
test "$IOS_EXIT" -eq 1
grep -Fqx \
  'run --target ios: checked program does not define a NativeUi application' \
  "$TMP/ios-run.err"
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

echo "v2-swift-cli: PASS"
