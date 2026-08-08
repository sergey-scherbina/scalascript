#!/usr/bin/env bash
# `ssc new` must produce a project that LOADS.
#
# It did not. Five of six templates scaffold `addSbtPlugin(... sbt-scalascript-interop ...)` plus
# `enablePlugins(ScalascriptInteropPlugin)`, and nothing published that artifact — so every
# generated project died on its first command:
#
#   sbt.librarymanagement.ResolveException: Error downloading
#     org.scalascript:sbt-scalascript-interop;sbtVersion=1.0;scalaVersion=2.12  Not found
#
# Nobody noticed because nothing had ever scaffolded a project and then tried to build it. Checking
# that the template FILES contain the right strings would not have caught it either: the coordinate
# was well-formed, it just named an artifact that did not exist. So this gate resolves for real.
#
# `sbt update` rather than `compile`: resolution is the thing that was broken, and it is far cheaper
# than compiling a scaffold.
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
SSC="$ROOT/bin/ssc-tools"
[[ -x $SSC ]] || { echo "scaffold-loads-its-build: no launcher at $SSC — run ./install.sh --dev" >&2; exit 2; }

tmp=$(mktemp -d "${TMPDIR:-/tmp}/scaffold-gate.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

# One template is enough to catch the class (they share the plugins.sbt), and each `sbt update` is
# expensive. `app` is the one a newcomer reaches for first.
template=app
name=demo

( cd "$tmp" && "$SSC" new "$name" --template "$template" ) > "$tmp/new.log" 2>&1 || {
  echo "scaffold-loads-its-build: FAILED — 'ssc new' itself errored" >&2
  tail -5 "$tmp/new.log" >&2
  exit 1
}
[[ -f "$tmp/$name/build.sbt" ]] || {
  echo "scaffold-loads-its-build: FAILED — no build.sbt in the scaffolded project" >&2; exit 1; }

if ! ( cd "$tmp/$name" && sbt -batch -no-colors update ) > "$tmp/update.log" 2>&1; then
  echo "scaffold-loads-its-build: FAILED — the scaffolded project cannot resolve its build" >&2
  grep -iE 'not found|unresolved|ResolveException' "$tmp/update.log" | head -6 >&2
  echo "    If this names sbt-scalascript-interop, install.sh's publishLocal step did not run," >&2
  echo "    or the templates name a version the plugin build does not produce." >&2
  exit 1
fi
echo "scaffold-loads-its-build: OK ($template scaffolds and resolves)"
