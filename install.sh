#!/usr/bin/env bash
# Build ssc and stage all launchers into the repo-local bin/.
# Requires sbt and a JDK (21+).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_PATH="$ROOT/cli/target/scala-3.8.3/ssc.jar"
BIN="$ROOT/bin"

echo "Building ssc fat jar via sbt-assembly..."
(cd "$ROOT" && sbt -no-colors cli/assembly)
[ -f "$JAR_PATH" ] || { echo "Assembly did not produce $JAR_PATH" >&2; exit 1; }

mkdir -p "$BIN"

cat > "$BIN/ssc" <<'LAUNCHER'
#!/usr/bin/env bash
# Detect install root (parent of this bin/ directory) so that bare imports
# like `[actors](std/actors.ssc)` resolve without a relative path prefix.
_SSC_BIN="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_SSC_ROOT="$(dirname "$_SSC_BIN")"
LAUNCHER
# Embed the absolute jar path at install time (stays stable after install).
cat >> "$BIN/ssc" <<EOF
exec java -Dssc.lib.path="\$_SSC_ROOT" -jar "$JAR_PATH" "\$@"
EOF
chmod +x "$BIN/ssc"

for launcher in "$ROOT"/scripts/launchers/*; do
    name="$(basename "$launcher")"
    ln -sf "../scripts/launchers/$name" "$BIN/$name"
done

echo "Staged bin/ launchers:"
for f in "$BIN"/*; do
    echo "  bin/$(basename "$f")"
done
echo ""
echo "Add to PATH for this session:"
echo "  export PATH=\"\$PATH:$BIN\""
