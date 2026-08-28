#!/usr/bin/env bash
#
# release-installer-launcher — `releases/install.sh` installs a RUNNABLE toolchain, offline.
#
# THIS GATE USED TO PIN THE DEFECT. It asserted that the installer writes
# `exec java -Xss"${SSC_XSS:-64m}" -jar .../ssc.jar "$@"` — a wrapper around a jar that NO release
# has ever published (v0.1.0 and v0.1.1 ship three native binaries and their tarballs, nothing else).
# So it was green on an installer that could only ever download a 404, which is what an old contract
# does when the thing it describes is replaced and only one direction of the pair gets rewritten.
# (BUGS.md install-channels-are-fiction.)
#
# WHAT IT PINS NOW is the contract the shipped artifact actually has:
#   * the archive is unpacked WHOLE, because the binary finds its staged front by walking up from
#     its own real path (NativeImageInstallRoot) — copying just `ssc` out of it is the tempting
#     shortcut that produces a toolchain which cannot find its own front;
#   * the launcher is a SYMLINK into that tree, which works only because that resolution calls
#     `toRealPath()`, and passes argv through untouched;
#   * a TAMPERED archive is REFUSED. The old gate could not have had this row: there was nothing to
#     verify, and a partial download over a flaky link is the ordinary case, not the exotic one.
#
# OFFLINE. `curl` is faked, as before — that technique is the half of the old gate worth keeping.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/ssc-release-installer.XXXXXX")"
FAKE_BIN="$TMP/bin"
PREFIX="$TMP/prefix"
SERVE="$TMP/serve"
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT

mkdir -p "$FAKE_BIN" "$SERVE"

# ── the payload a release publishes, in miniature ────────────────────────────────────────────────
#
# Same shape as the real archive: `ssc` at the root, `lib/`, and the staged front under
# `bin/lib/native-front`. The fake `ssc` prints its own resolved location and its argv, so
# the assertions below can tell "the launcher ran the unpacked binary" from "the launcher ran
# something".
stage="$TMP/stage"
mkdir -p "$stage/lib" "$stage/bin/lib/native-front/tower/bin"
cat > "$stage/ssc" <<'FAKE_SSC'
#!/usr/bin/env sh
# RESOLVE LIKE THE REAL BINARY DOES, and that means FOLLOWING THE SYMLINK. `NativeImageInstallRoot`
# calls `toRealPath()` on the running executable before looking for the staged front; a fake that
# used `dirname "$0"` would report the launcher's directory and fail here for a reason the real
# binary does not have — a gate telling the truth about the wrong program.
real="$0"
while [ -L "$real" ]; do
  link=$(readlink "$real")
  case "$link" in
    /*) real="$link" ;;
    *)  real="$(dirname "$real")/$link" ;;
  esac
done
self=$(cd "$(dirname "$real")" && pwd)
if [ ! -d "$self/bin/lib/native-front" ]; then
  echo "fake ssc: staged front not found beside me at $self" >&2
  exit 3
fi
if [ "${1:-}" = "--version" ]; then echo "ssc 9.9.9-fake"; exit 0; fi
echo "root=$self argv=$*"
FAKE_SSC
chmod +x "$stage/ssc"
: > "$stage/lib/ssc-plugin-host.jar"
: > "$stage/bin/lib/native-front/tower/bin/fsub.ssc"
printf 'README\n' > "$stage/README.md"

archive="$SERVE/ssc-fake.tar.gz"
tar -czf "$archive" -C "$stage" ssc lib bin README.md
if command -v sha256sum >/dev/null 2>&1; then
  ( cd "$SERVE" && sha256sum "ssc-fake.tar.gz" > "ssc-fake.tar.gz.sha256" )
else
  ( cd "$SERVE" && shasum -a 256 "ssc-fake.tar.gz" > "ssc-fake.tar.gz.sha256" )
fi

# ── a curl that serves those two files, whatever platform the installer asks for ─────────────────
cat > "$FAKE_BIN/curl" <<FAKE_CURL
#!/usr/bin/env sh
set -eu
url=""; out=""
while [ "\$#" -gt 0 ]; do
  case "\$1" in
    -o) shift; out="\$1" ;;
    -*) ;;
    *)  url="\$1" ;;
  esac
  shift
done
[ -n "\$out" ] && [ -n "\$url" ]
case "\$url" in
  *.tar.gz.sha256) cp "$SERVE/ssc-fake.tar.gz.sha256" "\$out" ;;
  *.tar.gz)        cp "$SERVE/ssc-fake.tar.gz" "\$out" ;;
  *) echo "fake curl: unexpected url \$url" >&2; exit 22 ;;
esac
FAKE_CURL
chmod +x "$FAKE_BIN/curl"

# The `.sha256` names the REAL artifact id, and the installer verifies in its own temp directory, so
# the recorded name has to be the one it downloaded to.
rewrite_sha_for() {
  local artifact="$1"
  local digest
  digest=$(awk '{print $1}' "$SERVE/ssc-fake.tar.gz.sha256")
  printf '%s  %s.tar.gz\n' "$digest" "$artifact" > "$SERVE/ssc-fake.tar.gz.sha256"
}
case "$(uname -s)/$(uname -m)" in
  Linux/x86_64|Linux/amd64)    ARTIFACT=ssc-linux-x86_64 ;;
  Darwin/arm64|Darwin/aarch64) ARTIFACT=ssc-macos-arm64 ;;
  Darwin/x86_64)               ARTIFACT=ssc-macos-x86_64 ;;
  *) echo "release-installer-launcher: [skip] no published artifact for this host" >&2; exit 0 ;;
esac
rewrite_sha_for "$ARTIFACT"

fails=0
echo "── the installer produces a runnable toolchain"
if ! PATH="$FAKE_BIN:/usr/bin:/bin" PREFIX="$PREFIX" sh "$ROOT/releases/install.sh" >"$TMP/install.log" 2>&1; then
  echo "  ✗ install.sh failed:"; sed 's/^/      /' "$TMP/install.log"; exit 1
fi

launcher="$PREFIX/bin/ssc"
if [[ ! -L "$launcher" ]]; then
  echo "  ✗ $launcher is not a symlink — a copied binary cannot find its staged front"; fails=$((fails + 1))
else
  echo "  ✓ the launcher is a symlink into the unpacked tree"
fi
if [[ ! -d "$PREFIX/lib/scalascript/bin/lib/native-front" ]]; then
  echo "  ✗ the archive was not unpacked whole — the staged front is missing"; fails=$((fails + 1))
else
  echo "  ✓ the archive is unpacked whole"
fi

out=$(PATH="$FAKE_BIN:/usr/bin:/bin" "$launcher" one two)
# NORMALISED on both sides. `mktemp` hands back a path with `TMPDIR`'s trailing slash still in it
# (`/var/folders/.../T//ssc-…`) and macOS resolves `/var` to `/private/var`, so the literal string
# and what a process reports for the same directory differ without either being wrong.
expected_root=$(cd "$PREFIX/lib/scalascript" && pwd)
if [[ "$out" == "root=$expected_root argv=one two" ]]; then
  echo "  ✓ the launcher runs the unpacked binary and passes argv through"
else
  echo "  ✗ launcher output was '$out'"; fails=$((fails + 1))
fi

# ── a tampered archive must be refused ───────────────────────────────────────────────────────────
echo "── a digest mismatch stops the install"
printf 'corrupted\n' >> "$SERVE/ssc-fake.tar.gz"
PREFIX2="$TMP/prefix2"
if PATH="$FAKE_BIN:/usr/bin:/bin" PREFIX="$PREFIX2" sh "$ROOT/releases/install.sh" >"$TMP/tamper.log" 2>&1; then
  echo "  ✗ a tampered archive installed anyway"; fails=$((fails + 1))
else
  if [[ -e "$PREFIX2/bin/ssc" ]]; then
    echo "  ✗ refused, but a launcher was left behind at $PREFIX2/bin/ssc"; fails=$((fails + 1))
  else
    echo "  ✓ refused, and nothing was installed"
  fi
fi

echo
if [[ "$fails" -ne 0 ]]; then echo "release-installer-launcher: FAIL ($fails)" >&2; exit 1; fi
printf 'release-installer-launcher: PASS\n'
