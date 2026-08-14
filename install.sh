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

# ── content-addressed toolchain cache, shared across worktrees ────────────────────────────────────
#
# MEASURED 2026-08-09: 82 worktrees on this host, each with its own 176 MB `bin/`, each built
# separately from the SAME sources. That is where the contention comes from — test EXECUTION is
# already bounded by `scripts/conformance`'s host-wide slot, builds were bounded by nothing, and 16
# concurrent build JVMs put the machine at load 110, where a conformance shard that fits in 580 s at
# load 42 does not fit at all.
#
# CI has solved this for a year: `.github/workflows/smoke.yml` caches `bin` keyed on
# `scripts/launcher-input-digest`, whose whole purpose is "a content digest of everything that can
# affect the staged toolchain". Locally nothing did. This is that same key, one directory up.
#
# COPIED, NEVER HARD-LINKED, and that is deliberate: the tower `.ssc0` files under
# `bin/lib/*/native-front/tower/` are READ AT RUNTIME, and editing a staged copy is the standard
# way to iterate on the front without a rebuild — I did it twice today. Under hard links that would
# silently rewrite the shared cache for every other worktree. A 176 MB copy costs seconds against a
# build that costs minutes.
#
# Restore is atomic by rename, so a reader never sees a half-populated entry, and the entry is only
# published AFTER the build's own witness check has passed — a cache of a failed build is worse than
# no cache.
CACHE_ROOT="${SSC_TOOLCHAIN_CACHE:-$HOME/.cache/ssc-toolchain}"
_digest=""
if [ "${SSC_TOOLCHAIN_CACHE_OFF:-0}" != 1 ] && [ -x "$ROOT/scripts/launcher-input-digest" ]; then
  _digest="$($ROOT/scripts/launcher-input-digest 2>/dev/null || true)"
fi
_cache_entry=""
[ -n "$_digest" ] && _cache_entry="$CACHE_ROOT/$_digest"

# A hit needs the LAUNCHERS as well as lib/. `cli/installBin` is what writes `bin/ssc`,
# `bin/ssc-standard` and `bin/ssc-tools`, and a hit skips it — but only `bin/ssc` is tracked in git,
# so in a FRESH worktree the other two simply never existed. Every fresh checkout of main failed at
# the launcher guard below with "Stage did not produce executable launcher …/bin/ssc-standard",
# while the checkout that published the entry kept working because its launchers were already
# there. An entry published before this fix has no `bin/`, so it is treated as a MISS and rebuilt —
# that is what heals the ones already on disk, rather than requiring anyone to clear a cache.
# One restore, two callers. The second is the re-check inside the build slot below, and it must be
# BYTE-FOR-BYTE the same operation as the hit path — a second, hand-written copy of "restore" is how
# the two drift until one of them stages something the other does not.
#
# `cp -Rc` asks APFS to CLONE (copy-on-write) instead of duplicating 176 MB. Not for speed — plain
# `cp -R` of `bin/lib` measures 0.37 s here and the clone 0.06 s, both irrelevant — but for DISK:
# there are 92 worktrees on this host and every one of them holds a private copy of the same bytes.
# It must stay a COPY and never a symlink: `bin/lib/*/native-front/tower/` is read at runtime and
# editing a staged copy is a normal thing to do here (see the note further up), so a shared symlink
# would let one agent's experiment change what every other agent runs. Falls back to `cp -R` when
# the filesystem cannot clone, so this is a saving where available and a no-op where not.
_restore_from_cache() { # $1 = cache entry dir
  rm -rf "$LIB"
  mkdir -p "$BIN"
  cp -Rc "$1/lib" "$LIB" 2>/dev/null || cp -R "$1/lib" "$LIB"
  for _l in "$1"/bin/*; do
    [ -f "$_l" ] || continue
    cp "$_l" "$BIN/$(basename "$_l")"
    chmod +x "$BIN/$(basename "$_l")"
  done
}

if [ -n "$_cache_entry" ] && [ -d "$_cache_entry/lib" ] && [ -d "$_cache_entry/bin" ]; then
  echo "Toolchain cache HIT ($_digest) — restoring bin/lib instead of building."
  echo "  from: $_cache_entry"
  _restore_from_cache "$_cache_entry"
  # The witness below asserts a build RAN; a restore is not a build, so it is skipped rather than
  # faked. What replaces it is the same assertion the build path makes afterwards: the four staged
  # artefacts must be present, and they are checked for both paths further down.
  echo "  restored $(du -sh "$LIB" 2>/dev/null | cut -f1) — skipping sbt cli/installBin"
else
  [ -n "$_digest" ] && echo "Toolchain cache MISS ($_digest) — building."
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
# Through the host-wide build slot when it is available: a cache MISS is the expensive path and the
# only one worth queueing. A cache HIT never reaches here, so restoring costs no wait — which is the
# composition that matters, since most agents on a shared base will hit.
#
# AND THE CACHE IS CONSULTED AGAIN ONCE THE SLOT IS OURS, which is the whole point of this shape.
# The miss was decided BEFORE queueing, and the wait is minutes: agents rebase onto the same new
# `main`, compute the same new digest and miss together, so the second one used to wait ~8 minutes
# for a slot and then rebuild what the first had already published to the shared cache while it
# waited. Two slots bounded the CONCURRENCY without stopping them from building the SAME SOURCES —
# which is the thing `scripts/build-slot`'s own header names as the original problem. A herd now
# costs one build instead of one per pair.
#
# The step runs as an exported function rather than a second copy of these lines, so the restore is
# the same code as the hit path, and it leaves a marker file when it restored: a restore is not a
# build, so the stamp witness below must be skipped exactly as the hit path skips it. Without the
# marker this would fail with "sbt reported success but cli/installBin did not run" — correctly, and
# unhelpfully.
_slot_restored="$(mktemp -t ssc-install-restored.XXXXXX)"; rm -f "$_slot_restored"
_slot_step() {
  if [ -n "$_cache_entry" ] && [ -d "$_cache_entry/lib" ] && [ -d "$_cache_entry/bin" ]; then
    echo "  another agent published this digest while we waited — restoring, not rebuilding."
    _restore_from_cache "$_cache_entry"
    : > "$_slot_restored"
    return 0
  fi
  sbt -no-colors cli/installBin
}
export -f _slot_step _restore_from_cache
export _cache_entry _slot_restored LIB BIN
if [ -x "$ROOT/scripts/build-slot" ]; then
  (cd "$ROOT" && "$ROOT/scripts/build-slot" bash -c '_slot_step')
else
  (cd "$ROOT" && bash -c '_slot_step')
fi
if [ -f "$_slot_restored" ]; then
  rm -f "$_slot_restored"
  echo "  restored $(du -sh "$LIB" 2>/dev/null | cut -f1) from the cache — no build was run."
else
  _stamp_after=""
  [ -f "$_stamp" ] && _stamp_after="$(_stat_mtime "$_stamp")"
  if [ -z "$_stamp_after" ] || [ "$_stamp_after" = "$_stamp_before" ]; then
      echo "install.sh: sbt reported success but cli/installBin did not run —" >&2
      echo "  $_stamp was not rewritten, so nothing was staged and bin/lib is whatever it was before." >&2
      echo "  Anything measured with this tree would be the OLD toolchain." >&2
      exit 1
  fi
fi

# Publish AFTER the witness: a cached failed build would be served to every other worktree.
if [ -n "$_cache_entry" ] && [ ! -d "$_cache_entry/lib" ]; then
  _tmp="$CACHE_ROOT/.tmp.$$"
  rm -rf "$_tmp"; mkdir -p "$_tmp"
  if cp -R "$LIB" "$_tmp/lib" 2>/dev/null; then
    # The ENTRY DIRECTORY MAY ALREADY EXIST WITHOUT `lib`: the conformance memo lives at
    # `<entry>/conformance-memo.txt`, so a test run creates the entry before any build fills it.
    # `mv "$_tmp" "$_cache_entry"` then moves the temp dir INSIDE it instead of becoming it, the
    # cache is never populated, and every later install misses forever — measured, after which the
    # log showed "MISS" for a digest whose directory plainly existed.
    # Renaming `lib` itself is the atomic step that actually matters: a reader tests `-d entry/lib`,
    # so it sees no lib or a complete one, never a partial copy.
    mkdir -p "$_cache_entry"
    # Launchers FIRST, lib second. A reader tests `-d entry/lib` and now also `-d entry/bin`, and
    # `lib` is the last thing published, so an entry is never visible as a hit while incomplete.
    if [ -d "$BIN" ]; then
      rm -rf "$_tmp/bin"; mkdir -p "$_tmp/bin"
      # EVERY file `bin/` holds, not a named three. The guard below only checks ssc, ssc-standard
      # and ssc-tools, but `installBin` also writes ssc-js, ssc-wasm, ssc-spark, ssc-provider and
      # sscc — and a restored toolchain missing those is the same defect one command further on,
      # discovered later and by someone else. `lib` is a directory and is copied separately.
      for _l in "$BIN"/*; do
        [ -f "$_l" ] && cp "$_l" "$_tmp/bin/"
      done
      rm -rf "$_cache_entry/bin"
      mv "$_tmp/bin" "$_cache_entry/bin" 2>/dev/null || true
    fi
    if mv "$_tmp/lib" "$_cache_entry/lib" 2>/dev/null; then
      echo "Toolchain cached as $_digest — other worktrees on this base will not rebuild."
    fi
    # Losing the race is fine: the other builder published for the SAME digest, i.e. the same bytes
    # by construction.
    rm -rf "$_tmp"
  else
    rm -rf "$_tmp"
  fi
fi
fi
# The sbt interop plugin, published to the local ivy repo.
#
# `ssc new` scaffolds five of six templates with `addSbtPlugin(... sbt-scalascript-interop ...)` and
# `enablePlugins(ScalascriptInteropPlugin)`, so a generated project cannot even LOAD its build
# unless that artifact resolves. Nothing publishes it anywhere, so before this every `ssc new`
# produced a project that failed on its first `sbt compile` with "Not found" -- reproduced in
# tests/BUGS.md scaffolded-projects-cannot-load-their-build.
#
# It is a SEPARATE sbt build (not in the root aggregate), hence its own invocation. Local publish
# only: whether this plugin should be published for real is an open product question, and until it
# is, this makes the scaffolds work for anyone who builds ssc from source.
#
# IT IS ALSO THE ONLY STEP THAT USED TO RUN UNCONDITIONALLY, and it sits outside the cache branch
# above — so an install that hit the toolchain cache, and therefore did no building at all, still
# started a whole second sbt JVM for a separate build. It was additionally the only build step NOT
# holding a `scripts/build-slot` slot, i.e. the one thing guaranteed to run was the one thing not
# queued. Measured while asking why a worktree install is expensive.
#
# What it produces goes to `~/.ivy2/local`, which is HOST-WIDE: 92 worktrees on this machine were
# publishing identical bytes to one shared path. So the artefact is skippable exactly when it is
# already there for the version this build would produce AND nothing under the plugin has changed
# since — the same "is my output already on disk" question the toolchain cache above asks, answered
# with mtimes because the plugin is one small project rather than a digest-worth of inputs.
#
# NOT simply deleted, and the distinction matters: `ssc new` scaffolds five of six templates with
# `addSbtPlugin(... sbt-scalascript-interop ...)`, so without the artefact a generated project
# cannot load its build at all (tests/BUGS.md scaffolded-projects-cannot-load-their-build). The
# guarantee is preserved; only the repetition is removed. `SSC_SKIP_SBT_PLUGIN=1` skips it outright
# for someone who knows they will not scaffold.
_plugin_dir="$ROOT/v1/tools/sbt-plugin"
_plugin_version="$(sed -n 's/^ThisBuild \/ version *:= *"\(.*\)".*/\1/p' "$_plugin_dir/build.sbt" | head -1)"
_plugin_ivy="$HOME/.ivy2/local/org.scalascript/sbt-scalascript-interop/scala_2.12/sbt_1.0/$_plugin_version/jars/sbt-scalascript-interop.jar"
_publish_needed=1
if [ "${SSC_SKIP_SBT_PLUGIN:-0}" = 1 ]; then
    _publish_needed=0
    echo "Skipping the sbt interop plugin publish (SSC_SKIP_SBT_PLUGIN=1) — 'ssc new' scaffolds will"
    echo "  not resolve it unless it was published earlier."
elif [ -n "$_plugin_version" ] && [ -f "$_plugin_ivy" ]; then
    # `find -newer` over the plugin's own sources: any change to them, or to its build definition,
    # makes the published jar stale and the publish necessary again. Cheap — this is one small
    # project, and the alternative (start sbt to ask) is the cost being avoided.
    if [ -z "$(find "$_plugin_dir" -type f \( -name '*.scala' -o -name '*.sbt' -o -name '*.properties' \) -newer "$_plugin_ivy" -print -quit 2>/dev/null)" ]; then
        _publish_needed=0
        echo "sbt interop plugin $_plugin_version already published locally and unchanged — skipping."
    fi
fi
if [ "$_publish_needed" = 1 ]; then
    echo "Publishing the sbt interop plugin locally (ssc new needs it to resolve)..."
    if [ -x "$ROOT/scripts/build-slot" ]; then
        (cd "$_plugin_dir" && "$ROOT/scripts/build-slot" sbt -no-colors -batch publishLocal)
    else
        (cd "$_plugin_dir" && sbt -no-colors -batch publishLocal)
    fi || {
        echo "install.sh: sbt-plugin publishLocal failed — 'ssc new' will produce projects that cannot" >&2
        echo "  load their build. See tests/BUGS.md scaffolded-projects-cannot-load-their-build." >&2
        exit 1
    }
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
