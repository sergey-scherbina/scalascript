#!/usr/bin/env sh
set -eu

VERSION="${SSC_VERSION:-0.1.0}"
PREFIX="${PREFIX:-$HOME/.local}"
BIN_DIR="$PREFIX/bin"
LIB_DIR="$PREFIX/lib/scalascript"
BASE_URL="${SSC_BASE_URL:-https://github.com/sergey-scherbina/scalascript/releases/download/v$VERSION}"

mkdir -p "$BIN_DIR" "$LIB_DIR"

if command -v curl >/dev/null 2>&1; then
  curl -fsSL "$BASE_URL/ssc.jar" -o "$LIB_DIR/ssc.jar"
elif command -v wget >/dev/null 2>&1; then
  wget -q "$BASE_URL/ssc.jar" -O "$LIB_DIR/ssc.jar"
else
  echo "install.sh: curl or wget is required" >&2
  exit 1
fi

cat > "$BIN_DIR/ssc" <<EOF
#!/usr/bin/env sh
exec java -Xss64m -jar "$LIB_DIR/ssc.jar" "\$@"
EOF
chmod +x "$BIN_DIR/ssc"

echo "Installed ssc $VERSION to $BIN_DIR/ssc"
