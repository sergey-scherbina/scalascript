#!/usr/bin/env bash
# Installer for contributors working from the monorepo.
# Builds and stages ssc into ./bin via sbt.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN="$ROOT/bin"
LIB="$BIN/lib"

if ! command -v java &>/dev/null; then
    echo "Error: JDK not found. Run ./setup.sh first to install required tools." >&2
    exit 1
fi
if ! command -v sbt &>/dev/null; then
    echo "Error: sbt not found. Run ./setup.sh first to install required tools." >&2
    exit 1
fi

# ── Git submodules ─────────────────────────────────────────────────────────────
echo "Updating git submodules..."
git -C "$ROOT" submodule update --init --remote --recursive
echo "✓ submodules up to date"

# ── Agent skills ──────────────────────────────────────────────────────────────
SKILLS_SRC="$ROOT/.agents/plugins"
if [ -d "$SKILLS_SRC" ]; then
  echo ""
  echo "Updating agent skills..."
  DEST="$HOME/.claude/commands"
  mkdir -p "$DEST"
  for skill_dir in "$SKILLS_SRC"/*/; do
    name="$(basename "$skill_dir")"
    src="$skill_dir/commands/$name.md"
    if [ -f "$src" ]; then
      cp "$src" "$DEST/$name.md"
      echo "  ✓ $name → $DEST/$name.md"
    fi
  done
fi

echo ""
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
