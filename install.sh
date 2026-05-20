#!/usr/bin/env bash
# Build ssc and stage all launchers into the repo-local bin/.
# Requires sbt and a JDK (21+).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB="$ROOT/lib"
BIN="$ROOT/bin"

echo "Staging ssc (thin jar + deps) via sbt cli/stage..."
(cd "$ROOT" && sbt -no-colors cli/stage)
[ -f "$LIB/ssc.jar" ]  || { echo "Stage did not produce $LIB/ssc.jar" >&2; exit 1; }
[ -d "$LIB/jars" ]     || { echo "Stage did not produce $LIB/jars/" >&2; exit 1; }

mkdir -p "$BIN"

# Launcher: classpath-based, no fat jar needed.
# lib/jars/* holds all transitive JARs; lib/ssc.jar is the thin entry-point.
cat > "$BIN/ssc" <<'LAUNCHER'
#!/usr/bin/env bash
# Detect install root (parent of this bin/ directory) so that bare imports
# like `[actors](std/actors.ssc)` resolve without a relative path prefix.
_SSC_BIN="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_SSC_ROOT="$(dirname "$_SSC_BIN")"
exec java -Dssc.lib.path="$_SSC_ROOT" -cp "$_SSC_ROOT/lib/jars/*:$_SSC_ROOT/lib/ssc.jar" scalascript.cli.ssc "$@"
LAUNCHER
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
echo "Layout:"
echo "  lib/ssc.jar        — thin entry-point JAR"
echo "  lib/jars/          — $(ls "$LIB/jars" | wc -l | tr -d ' ') JARs"
echo "  lib/plugins/       — drop .sscpkg files here for auto-loading"
echo ""
echo "Add to PATH for this session:"
echo "  export PATH=\"\$PATH:$BIN\""
