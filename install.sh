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
# BSD `stat -f %m` and GNU `stat -c %Y` spell this differently, and CI is Linux while this host is
# macOS — the shape that has made a gate green here and red there before.
_stat_mtime() { stat -f %m "$1" 2>/dev/null || stat -c %Y "$1" 2>/dev/null; }

if ! command -v sbt &>/dev/null; then
    echo "Error: sbt not found. Run ./setup.sh first to install required tools." >&2
    exit 1
fi

# ── Git submodules ─────────────────────────────────────────────────────────────
# The skills submodule is initialized only in the shared main checkout. A feature
# worktree intentionally has an uninitialized gitlink and reads skills from main;
# initializing it here violates the parallel-agent contract and creates an
# independent mutable checkout in every worktree.
INSTALL_PREFLIGHT_ONLY="${SSC_INSTALL_PREFLIGHT_ONLY:-0}"
if [ -f "$ROOT/.git" ]; then
  GIT_COMMON_DIR="$(git -C "$ROOT" rev-parse --path-format=absolute --git-common-dir)"
  MAIN_ROOT="$(dirname "$GIT_COMMON_DIR")"
  SKILLS_SRC="$MAIN_ROOT/.agents/plugins"
  echo "Worktree detected; skipping submodule update."
  echo "✓ agent skills source: $SKILLS_SRC"
else
  SKILLS_SRC="$ROOT/.agents/plugins"
  if [ "$INSTALL_PREFLIGHT_ONLY" = "1" ]; then
    echo "Main checkout detected; submodules would be updated."
  else
    echo "Updating git submodules..."
    git -C "$ROOT" submodule update --init --remote --recursive
    echo "✓ submodules up to date"
  fi
fi

# Cheap executable classification seam used by the worktree regression. It exits
# before copying skills or starting the expensive build; normal installs never set it.
if [ "$INSTALL_PREFLIGHT_ONLY" = "1" ]; then
  echo "✓ install preflight complete"
  exit 0
fi

# ── Agent skills ──────────────────────────────────────────────────────────────
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
# WITNESS THAT THE BUILD RAN, not that its output exists. The checks below assert the staged files
# are PRESENT, and they are present after any previous successful build — `bin/ssc` is tracked and
# `bin/lib` survives — so a build that produced nothing passed them and install.sh said success.
# The next command then ran the OLD toolchain with a zero exit code, which is the failure this whole
# staleness apparatus exists to prevent (scripts/BUGS.md
# install-sh-exits-0-when-sbt-project-load-fails).
#
# `cli/installBin` rewrites bin/lib/.build-stamp (build.sbt), so its mtime changing is evidence the
# task actually ran. Only the mtime is used, never the CONTENT: the stamp's HEAD sha was superseded
# by scripts/launcher-input-digest for the "is this stale" question, and that decision is not
# reopened here.
_stamp="$LIB/.build-stamp"
_stamp_before=""
[ -f "$_stamp" ] && _stamp_before="$(_stat_mtime "$_stamp")"
(cd "$ROOT" && sbt -no-colors cli/installBin)
_stamp_after=""
[ -f "$_stamp" ] && _stamp_after="$(_stat_mtime "$_stamp")"
if [ -z "$_stamp_after" ] || [ "$_stamp_after" = "$_stamp_before" ]; then
    echo "install.sh: sbt reported success but cli/installBin did not run —" >&2
    echo "  $_stamp was not rewritten, so nothing was staged and bin/lib is whatever it was before." >&2
    echo "  Anything measured with this tree would be the OLD toolchain." >&2
    exit 1
fi
[ -f "$LIB/standard/ssc.jar" ]  || { echo "Stage did not produce $LIB/standard/ssc.jar" >&2; exit 1; }
[ -d "$LIB/standard/jars" ]     || { echo "Stage did not produce $LIB/standard/jars/" >&2; exit 1; }
[ -f "$LIB/ssc.jar" ]           || { echo "Stage did not produce $LIB/ssc.jar" >&2; exit 1; }
[ -d "$LIB/jars" ]              || { echo "Stage did not produce $LIB/jars/" >&2; exit 1; }

mkdir -p "$BIN"

# `cli/installBin` is the single launcher generator. Duplicating its templates
# here caused the full installer to overwrite fresh output with stale bytes and
# once silently defeated a stack-size fix. Keep one authority and fail loudly if
# it did not produce every public launcher.
for launcher in "$BIN/ssc" "$BIN/ssc-standard" "$BIN/ssc-tools"; do
    if [ ! -x "$launcher" ]; then
        echo "Stage did not produce executable launcher $launcher" >&2
        exit 1
    fi
done

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
