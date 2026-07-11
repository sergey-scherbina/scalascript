#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SSC="$ROOT/bin/ssc"
FIXTURE="$ROOT/examples/swift/appcore-nativeui.ssc"
TMP=$(mktemp -d "${TMPDIR:-/tmp}/ssc-v2-swiftui-apple.XXXXXX")
MAC_PID=""

cleanup() {
  if [[ -n "$MAC_PID" ]] && kill -0 "$MAC_PID" 2>/dev/null; then
    kill "$MAC_PID" 2>/dev/null || true
    wait "$MAC_PID" 2>/dev/null || true
  fi
  rm -rf "$TMP"
}
trap cleanup EXIT

fail() {
  echo "v2-swiftui-apple: $*" >&2
  exit 1
}

if [[ ! -x "$SSC" ]]; then
  fail "bin/ssc is missing; run scripts/sbtc installBin"
fi
for tool in xcodebuild xcrun plutil; do
  command -v "$tool" >/dev/null || fail "$tool is required"
done

"$SSC" build --v2 --target macos --out "$TMP/mac-a" "$FIXTURE" >/dev/null
"$SSC" build --v2 --target macos --out "$TMP/mac-b" "$FIXTURE" >/dev/null
"$SSC" build --v2 --target ios --out "$TMP/ios" "$FIXTURE" >/dev/null

MAC_A="$TMP/mac-a/macos"
MAC_B="$TMP/mac-b/macos"
IOS="$TMP/ios/ios"
shopt -s globstar nullglob
for tree in "$MAC_A" "$MAC_B" "$IOS"; do
  test -f "$tree/.ssc-swift-generated.json"
  test -f "$tree/Sources/AppCore/GeneratedProgram.swift"
  test -f "$tree/Sources/AppCore/NativeUiHost.swift"
  test -f "$tree/AppleApp/NativeUiStore.swift"
  test -f "$tree/AppleApp/NativeUiRenderer.swift"
  test -f "$tree/AppleApp/Resources/Assets.xcassets/Contents.json"
  LEGACY_CONTENT_VIEWS=("$tree"/**/ContentView.swift)
  if [[ ${#LEGACY_CONTENT_VIEWS[@]} -ne 0 ]]; then
    fail "legacy ContentView.swift leaked into the v2 Apple tree"
  fi
done

diff -qr "$MAC_A" "$MAC_B" >/dev/null || fail "two macOS trees are not byte-deterministic"

MAC_PROJECTS=("$MAC_A"/*.xcodeproj)
IOS_PROJECTS=("$IOS"/*.xcodeproj)
[[ ${#MAC_PROJECTS[@]} -eq 1 ]] || fail "expected one macOS Xcode project"
[[ ${#IOS_PROJECTS[@]} -eq 1 ]] || fail "expected one iOS Xcode project"
MAC_PROJECT="${MAC_PROJECTS[0]}"
IOS_PROJECT="${IOS_PROJECTS[0]}"
MAC_SCHEME=$(basename "$MAC_PROJECT" .xcodeproj)
IOS_SCHEME=$(basename "$IOS_PROJECT" .xcodeproj)
[[ "$MAC_SCHEME" == "$IOS_SCHEME" ]] || fail "platforms emitted different application schemes"

xcodebuild -list -json -project "$MAC_PROJECT" >"$TMP/mac-list.json"
grep -Fq "\"$MAC_SCHEME\"" "$TMP/mac-list.json"
! grep -Fq "${MAC_SCHEME}Cli" "$TMP/mac-list.json"

setting() {
  local key=$1 file=$2
  awk -F ' = ' -v key="$key" '$1 ~ "^[[:space:]]*" key "$" { print $2; exit }' "$file"
}

MAC_DEST='platform=macOS'
MAC_DERIVED="$TMP/mac-derived"
xcodebuild build \
  -project "$MAC_PROJECT" -scheme "$MAC_SCHEME" -configuration Debug \
  -destination "$MAC_DEST" -derivedDataPath "$MAC_DERIVED" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO \
  >"$TMP/mac-build.log"
xcodebuild -showBuildSettings \
  -project "$MAC_PROJECT" -scheme "$MAC_SCHEME" -configuration Debug \
  -destination "$MAC_DEST" -derivedDataPath "$MAC_DERIVED" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO \
  >"$TMP/mac-settings.txt"

MAC_TARGET_DIR=$(setting TARGET_BUILD_DIR "$TMP/mac-settings.txt")
MAC_PRODUCT=$(setting FULL_PRODUCT_NAME "$TMP/mac-settings.txt")
[[ "$MAC_PRODUCT" == "$MAC_SCHEME.app" ]] || fail "unexpected macOS product: $MAC_PRODUCT"
MAC_APP="$MAC_TARGET_DIR/$MAC_PRODUCT"
MAC_INFO="$MAC_APP/Contents/Info.plist"
test -f "$MAC_INFO"
[[ $(plutil -extract CFBundlePackageType raw -o - "$MAC_INFO") == APPL ]]
[[ $(plutil -extract CFBundleIdentifier raw -o - "$MAC_INFO") == com.scalascript.appcore-nativeui ]]
MAC_EXEC_NAME=$(plutil -extract CFBundleExecutable raw -o - "$MAC_INFO")
[[ "$MAC_EXEC_NAME" != *Cli ]] || fail "macOS application selected the debug CLI"
MAC_EXEC="$MAC_APP/Contents/MacOS/$MAC_EXEC_NAME"
test -x "$MAC_EXEC"
[[ $(setting SWIFT_VERSION "$TMP/mac-settings.txt") == 6.0 ]]
[[ $(setting MACOSX_DEPLOYMENT_TARGET "$TMP/mac-settings.txt") == 13.0 ]]
[[ $(setting SUPPORTS_MACCATALYST "$TMP/mac-settings.txt") == NO ]]

"$MAC_EXEC" >"$TMP/mac-run.out" 2>"$TMP/mac-run.err" &
MAC_PID=$!
MAC_EXITED=0
for _ in {1..30}; do
  if ! kill -0 "$MAC_PID" 2>/dev/null; then
    MAC_EXITED=1
    break
  fi
  sleep 0.1
done
if [[ $MAC_EXITED -eq 1 ]]; then
  set +e
  wait "$MAC_PID"
  MAC_STATUS=$?
  set -e
  MAC_PID=""
  [[ $MAC_STATUS -eq 0 ]] || fail "macOS application exited $MAC_STATUS"
else
  kill "$MAC_PID" 2>/dev/null || true
  wait "$MAC_PID" 2>/dev/null || true
  MAC_PID=""
fi

SIM_LINE=$(xcrun simctl list devices available | sed -nE \
  '/iPhone/ { s/^[[:space:]]*([^()]*) \(([0-9A-F-]+)\).*/\2|\1/p; q; }')
[[ -n "$SIM_LINE" ]] || fail "no available concrete iPhone Simulator"
SIM_ID=${SIM_LINE%%|*}
SIM_NAME=${SIM_LINE#*|}
IOS_DEST="platform=iOS Simulator,id=$SIM_ID"
IOS_DERIVED="$TMP/ios-derived"
xcodebuild build \
  -project "$IOS_PROJECT" -scheme "$IOS_SCHEME" -configuration Debug \
  -destination "$IOS_DEST" -derivedDataPath "$IOS_DERIVED" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO \
  >"$TMP/ios-build.log"
xcodebuild -showBuildSettings \
  -project "$IOS_PROJECT" -scheme "$IOS_SCHEME" -configuration Debug \
  -destination "$IOS_DEST" -derivedDataPath "$IOS_DERIVED" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO \
  >"$TMP/ios-settings.txt"

IOS_TARGET_DIR=$(setting TARGET_BUILD_DIR "$TMP/ios-settings.txt")
IOS_PRODUCT=$(setting FULL_PRODUCT_NAME "$TMP/ios-settings.txt")
[[ "$IOS_PRODUCT" == "$IOS_SCHEME.app" ]] || fail "unexpected iOS product: $IOS_PRODUCT"
IOS_APP="$IOS_TARGET_DIR/$IOS_PRODUCT"
IOS_INFO="$IOS_APP/Info.plist"
test -f "$IOS_INFO"
[[ $(plutil -extract CFBundlePackageType raw -o - "$IOS_INFO") == APPL ]]
[[ $(plutil -extract CFBundleIdentifier raw -o - "$IOS_INFO") == com.scalascript.appcore-nativeui ]]
IOS_EXEC_NAME=$(plutil -extract CFBundleExecutable raw -o - "$IOS_INFO")
[[ "$IOS_EXEC_NAME" != *Cli ]] || fail "iOS application selected the debug CLI"
test -f "$IOS_APP/$IOS_EXEC_NAME"
[[ $(setting SWIFT_VERSION "$TMP/ios-settings.txt") == 6.0 ]]
[[ $(setting IPHONEOS_DEPLOYMENT_TARGET "$TMP/ios-settings.txt") == 16.0 ]]
[[ $(setting SUPPORTS_MACCATALYST "$TMP/ios-settings.txt") == NO ]]
grep -Fq iphonesimulator "$TMP/ios-settings.txt"

echo "v2-swiftui-apple: PASS (macOS $MAC_PRODUCT; iOS $IOS_PRODUCT on $SIM_NAME)"
