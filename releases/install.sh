#!/usr/bin/env sh
#
# ScalaScript standalone installer — downloads the native binary from the GitHub release.
#
# WHAT THIS USED TO DO AND WHY IT COULD NOT WORK. It fetched `ssc.jar` at a hardcoded `0.1.0` and
# wrote a `java -jar` wrapper. No release has ever published an `ssc.jar`: v0.1.0 and v0.1.1 both
# ship three NATIVE binaries and their tarballs, nothing else. So this script downloaded a 404 and
# the version constant was stale on top of it.
#
# NO VERSION CONSTANT NOW, deliberately. GitHub serves `/releases/latest/download/<asset>`, so the
# default follows the newest release without anyone remembering to bump a file — the failure mode
# this script was in. `SSC_VERSION=0.1.1` still pins an exact tag.
#
# The archive is verified against the `.sha256` the release publishes beside it. A partial download
# over a flaky link is the ordinary case, not the adversarial one, and it is the one that would
# otherwise leave a half-extracted toolchain behind.
set -eu

REPO="${SSC_REPO:-sergey-scherbina/scalascript}"
PREFIX="${PREFIX:-$HOME/.local}"
BIN_DIR="$PREFIX/bin"
LIB_DIR="$PREFIX/lib/scalascript"

if [ -n "${SSC_VERSION:-}" ]; then
  BASE_URL="${SSC_BASE_URL:-https://github.com/$REPO/releases/download/v$SSC_VERSION}"
  WANTED="v$SSC_VERSION"
else
  BASE_URL="${SSC_BASE_URL:-https://github.com/$REPO/releases/latest/download}"
  WANTED="the latest release"
fi

# The three artifact ids are the ones `.github/workflows/native-release.yml` builds. They are checked
# against that file by tests/e2e/install-channels-are-real.sh, so this list cannot drift from what is
# actually published without a gate going red.
os=$(uname -s)
arch=$(uname -m)
case "$os/$arch" in
  Linux/x86_64|Linux/amd64)    ARTIFACT=ssc-linux-x86_64 ;;
  Darwin/arm64|Darwin/aarch64) ARTIFACT=ssc-macos-arm64 ;;
  Darwin/x86_64)               ARTIFACT=ssc-macos-x86_64 ;;
  *)
    echo "install.sh: no published binary for $os/$arch." >&2
    echo "            Published: linux x86_64, macOS arm64, macOS x86_64." >&2
    echo "            Build from a checkout instead: ./install.sh --dev" >&2
    exit 1
    ;;
esac

fetch() {
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$1" -o "$2"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$1" -O "$2"
  else
    echo "install.sh: curl or wget is required" >&2
    exit 1
  fi
}

tmp=$(mktemp -d "${TMPDIR:-/tmp}/ssc-install.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

# `${WANTED}` in braces: a bare `$WANTED` glued to a multi-byte character was read as
# part of the NAME and `set -u` killed the script on its own progress message.
echo "Downloading $ARTIFACT from ${WANTED}..."
fetch "$BASE_URL/$ARTIFACT.tar.gz"        "$tmp/$ARTIFACT.tar.gz"
fetch "$BASE_URL/$ARTIFACT.tar.gz.sha256" "$tmp/$ARTIFACT.tar.gz.sha256"

# The published `.sha256` names the archive, so verification runs in the directory holding both.
( cd "$tmp" && if command -v sha256sum >/dev/null 2>&1; then
    sha256sum -c "$ARTIFACT.tar.gz.sha256" >/dev/null
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 -c "$ARTIFACT.tar.gz.sha256" >/dev/null
  else
    echo "install.sh: neither sha256sum nor shasum found — refusing to install unverified bytes" >&2
    exit 1
  fi )

# THE LAYOUT IS NOT DECORATION. The native binary finds its staged lib/ by walking up from its OWN
# real path looking for `lib/native-front` (NativeImageInstallRoot), so the archive has to be
# unpacked whole and the launcher has to point INTO it. `toRealPath()` resolves the symlink first,
# which is why a symlink works here and a copy of the binary alone would not.
rm -rf "$LIB_DIR"
mkdir -p "$LIB_DIR" "$BIN_DIR"
tar -xzf "$tmp/$ARTIFACT.tar.gz" -C "$LIB_DIR"
chmod +x "$LIB_DIR/ssc"
ln -sf "$LIB_DIR/ssc" "$BIN_DIR/ssc"

# Prove the installed thing runs before claiming success — the failure this replaces printed
# "Installed ssc 0.1.0" over a wrapper around a jar that was never downloaded.
if ! "$BIN_DIR/ssc" --version >/dev/null 2>&1; then
  echo "install.sh: installed to $LIB_DIR but 'ssc --version' failed — not usable" >&2
  exit 1
fi

echo "Installed $("$BIN_DIR/ssc" --version | head -1) to $BIN_DIR/ssc"
case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *) echo "Add it to PATH:  export PATH=\"\$PATH:$BIN_DIR\"" ;;
esac
