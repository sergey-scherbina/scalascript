#!/usr/bin/env bash
# Developer-mode installer for contributors working from the monorepo.
# User-facing standalone installers live in releases/.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN="$ROOT/bin"
LIB="$BIN/lib"

usage() {
    cat <<'USAGE'
Usage:
  ./install.sh --dev

For normal user installation without cloning/building the monorepo, use one of:
  cs install ssc --channel https://releases.scalascript.io/coursier.json
  brew install scalascript/tap/ssc
  curl -fsSL https://get.scalascript.io | sh

This script is intentionally developer-only because it requires sbt and a JDK
and builds/stages the local checkout into ./bin.
USAGE
}

case "${1:-}" in
    --dev)
        shift
        ;;
    -h|--help)
        usage
        exit 0
        ;;
    *)
        usage
        exit 1
        ;;
esac

if [ "$#" -ne 0 ]; then
    usage
    exit 1
fi

echo "Staging ssc (thin jar + deps) via sbt cli/installBin..."
(cd "$ROOT" && sbt -no-colors cli/installBin)
[ -f "$LIB/ssc.jar" ]  || { echo "Stage did not produce $LIB/ssc.jar" >&2; exit 1; }
[ -d "$LIB/jars" ]     || { echo "Stage did not produce $LIB/jars/" >&2; exit 1; }

mkdir -p "$BIN"

# Launcher: classpath-based, no fat jar needed.
# bin/lib/jars/* holds runtime JARs; bin/lib/ssc.jar is the thin entry-point.
cat > "$BIN/ssc" <<'LAUNCHER'
#!/usr/bin/env bash
_SSC_BIN="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_SSC_ROOT="$(dirname "$_SSC_BIN")"
exec java -Dssc.lib.path="$_SSC_ROOT" -cp "$_SSC_BIN/lib/jars/*:$_SSC_BIN/lib/ssc.jar" scalascript.cli.ssc "$@"
LAUNCHER
chmod +x "$BIN/ssc"

for launcher in "$ROOT"/tools/scripts/launchers/*; do
    name="$(basename "$launcher")"
    ln -sf "../tools/scripts/launchers/$name" "$BIN/$name"
done

echo "Staged bin/ launchers:"
for f in "$BIN"/*; do
    echo "  bin/$(basename "$f")"
done
echo ""
echo "Layout:"
echo "  bin/lib/ssc.jar           — thin entry-point JAR"
echo "  bin/lib/jars/             — $(ls "$LIB/jars" | wc -l | tr -d ' ') runtime JARs"
echo "  bin/lib/compiler/jars/    — $(ls "$LIB/compiler/jars" | wc -l | tr -d ' ') compile-only JARs (lazy-loaded)"
echo "  bin/lib/compiler/plugins/ — auto-loaded .sscpkg plugins:"
for f in "$LIB/compiler/plugins"/*.sscpkg; do
    echo "    $(basename "$f")"
done
echo ""
echo "Add to PATH for this session:"
echo "  export PATH=\"\$PATH:$BIN\""
