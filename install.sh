#!/usr/bin/env bash
# Installer for contributors working from the monorepo.
# Builds and stages ssc into ./bin via sbt.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN="$ROOT/bin"
LIB="$BIN/lib"

usage() {
    cat <<'MSG'
ScalaScript standalone install options:

  cs install ssc --channel https://releases.scalascript.io/coursier.json
  brew install scalascript/tap/ssc
  curl -fsSL https://get.scalascript.io | sh

For a contributor build from this monorepo, run:

  ./install.sh --dev
MSG
}

case "${1:-}" in
    --dev)
        shift
        ;;
    ""|-h|--help)
        usage
        exit 0
        ;;
    *)
        echo "Unknown option: $1" >&2
        usage >&2
        exit 1
        ;;
esac

if [ "$#" -ne 0 ]; then
    echo "Unexpected arguments: $*" >&2
    usage >&2
    exit 1
fi

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
[ -f "$LIB/standard/ssc.jar" ]  || { echo "Stage did not produce $LIB/standard/ssc.jar" >&2; exit 1; }
[ -d "$LIB/standard/jars" ]     || { echo "Stage did not produce $LIB/standard/jars/" >&2; exit 1; }
[ -f "$LIB/ssc.jar" ]           || { echo "Stage did not produce $LIB/ssc.jar" >&2; exit 1; }
[ -d "$LIB/jars" ]              || { echo "Stage did not produce $LIB/jars/" >&2; exit 1; }

mkdir -p "$BIN"

# The ScalaScript 2.1 standard tier is the default launcher. Keep this launcher
# in sync with the checked-in bin/ssc (AppCDS cold-start cut).
#
# NOTE: these heredocs OVERWRITE the launchers `sbt cli/installBin` (above) just
# generated from build.sbt's templates — so there are two generators for the same
# three files and THIS one wins for anything that runs install.sh (including CI's
# conformance job). A fix applied to only one side silently does nothing here:
# that is exactly how -Xss64m was "fixed" in build.sbt yet every scljet case kept
# StackOverflowError-ing in CI. Change both, or neither.
cat > "$BIN/ssc" <<'LAUNCHER'
#!/usr/bin/env bash
_SSC_BIN="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_SSC_ROOT="$(dirname "$_SSC_BIN")"

# AppCDS: mmap a class-data archive instead of parsing classes on every launch —
# cuts `ssc` cold-start ~50%. Auto-created on first run (JDK 19+), auto-recreated
# if the classpath changes; only CDS (NOT -XX:TieredStopAtLevel=1, which would hurt
# long-running `ssc serve`). Old JDKs ignore the flags. Opt out with SSC_NO_CDS=1.
_SSC_CDS_ARGS=()
if [[ "${SSC_NO_CDS:-}" != "1" ]]; then
  _SSC_CACHE="${SSC_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/scalascript}"
  if mkdir -p "$_SSC_CACHE" 2>/dev/null; then
    _SSC_CDS_ARGS=(-XX:+IgnoreUnrecognizedVMOptions \
                   -XX:+AutoCreateSharedArchive \
                   -XX:SharedArchiveFile="$_SSC_CACHE/ssc.jsa" \
                   -Xlog:cds=off -Xlog:cds+dynamic=off)
  fi
fi

exec java "${_SSC_CDS_ARGS[@]}" -Xss64m -Dssc.lib.path="$_SSC_ROOT" \
  -cp "$_SSC_BIN/lib/standard/jars/*:$_SSC_BIN/lib/standard/ssc.jar" \
  scalascript.cli.StandardMain "$@"
LAUNCHER
chmod +x "$BIN/ssc"

cat > "$BIN/ssc-standard" <<'STANDARD_LAUNCHER'
#!/usr/bin/env bash
_SSC_BIN="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_SSC_ROOT="$(dirname "$_SSC_BIN")"
exec java -Xss64m -Dssc.lib.path="$_SSC_ROOT" \
  -cp "$_SSC_BIN/lib/standard/jars/*:$_SSC_BIN/lib/standard/ssc.jar" \
  scalascript.cli.StandardMain "$@"
STANDARD_LAUNCHER
chmod +x "$BIN/ssc-standard"

cat > "$BIN/ssc-tools" <<'TOOLS_LAUNCHER'
#!/usr/bin/env bash
_SSC_BIN="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_SSC_ROOT="$(dirname "$_SSC_BIN")"
exec java -Xss64m -Dssc.lib.path="$_SSC_ROOT" \
  -cp "$_SSC_BIN/lib/jars/*:$_SSC_BIN/lib/ssc.jar" \
  scalascript.cli.ssc "$@"
TOOLS_LAUNCHER
chmod +x "$BIN/ssc-tools"

for launcher in "$ROOT"/v1/tools/scripts/launchers/*; do
    name="$(basename "$launcher")"
    ln -sf "../v1/tools/scripts/launchers/$name" "$BIN/$name"
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
