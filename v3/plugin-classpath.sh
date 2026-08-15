#!/usr/bin/env bash
# The v2 PLUGIN FLEET's classpath, built once and cached — `v3/.jars/plugins.cp`.
#
# WHY A CACHED FILE AND NOT A BUILD ON EVERY RUN. Exactly the reason `v3/uniml-classpath.sh` gives
# for the second front: a caller who is not using the fleet has no business waiting for sbt, and a
# build failure has no business making `ssc3 run` fail. Availability is an explicit fact — the file
# exists or it does not — and the driver reads it rather than discovering it.
#
# WHAT THE FLEET IS. `v2/runtime/std/*-plugin/` — twenty-one providers that between them already
# implement seventeen of the nineteen host names the corpus refuses. They register themselves
# through `java.util.ServiceLoader`, so being on the classpath is the whole of "installed".
#
# ONE MODULE PER LINE, so adding a plugin is a one-line edit and the list is readable as the answer
# to "which providers does v3 offer". Start with the ones whose names the corpus actually reaches;
# the rest are a one-line addition each.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/v3/.jars/plugins.cp"

MODULES=(
  v2NativeFsPlugin
  v2NativeOsPlugin
  # ADDED ONE MODULE PER REFUSED NAME, and the names came from the compiler's own message rather
  # than from a scanner: a sweep of every corpus case counting only `the host function '…' is not
  # implemented on this lane`. `element`, `localStorageGet` and the signal family are ui; the
  # `content*` family and `signal` are content; `__jsonCoreWrap*` is json; `sha256` is crypto and
  # actors; `add` is http-fast. Two of the refused names — `actorGroupTell` and `webauthnChallenge`
  # — match no plugin source by name at all, so this list is not expected to answer them.
  v2NativeUiPlugin
  v2NativeContentPlugin
  v2NativeJsonPlugin
  v2NativeCryptoPlugin
  v2NativeActorsPlugin
  # COROUTINES AND GENERATORS, which reach v3 through the registry's `globalValues` table rather
  # than through Prim handlers: `suspend`, `coroutineCreate`, `coroutineResume` are all
  # `registerGlobal`, and six corpus cases wait on the first of them.
  v2NativeGeneratorPlugin
  v2NativeDatasetPlugin
)

cd "$ROOT" || exit 2
mkdir -p "$ROOT/v3/.jars"

# `export <module>/Runtime/fullClasspath` per module, concatenated. `--error` keeps sbt's own
# chatter out of the value; the LAST line is the classpath, because sbt prints it alone.
cp_all=""
for m in "${MODULES[@]}"; do
  line="$(sbt -batch --error "export $m/Runtime/fullClasspath" 2>/dev/null | tail -1)"
  if [ -z "$line" ]; then
    echo "plugin-classpath: $m produced no classpath — is it a project in build.sbt?" >&2
    exit 1
  fi
  cp_all="${cp_all:+$cp_all:}$line"
done

printf '%s' "$cp_all" > "$OUT"
echo "plugin-classpath: ${#MODULES[@]} module(s) -> $OUT" >&2
