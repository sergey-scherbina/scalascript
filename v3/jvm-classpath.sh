#!/usr/bin/env bash
# The JVM LIBRARIES a v3 program may import, built once and cached — `v3/.jars/jvm.cp`.
#
# WHY A SEPARATE LIST FROM THE PLUGIN FLEET. They answer different questions.
# `plugin-classpath.sh` answers "which providers does v3 offer", and a provider REGISTERS names.
# This answers "which JVM packages may a program import", and an import NAMES a class the program
# then constructs and calls. Conflating them would make `v3/plugin-classpath.sh` a list of two
# unrelated things, and its own comment is the answer to a question this is not.
#
# WHY A DECLARED LIST AND NOT THE WHOLE CLASSPATH. An import that resolves to whatever happens to be
# built is an import whose meaning depends on the build order. A name in this file is a decision;
# a class that merely exists is an accident.
#
# ONE MODULE PER LINE, exactly as the fleet's list is, and for the same reason: adding one is a
# one-line edit and the list reads as the answer to "what may a program import".
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/v3/.jars/jvm.cp"

MODULES=(
  # `scalascript.typeddata` — what `std/mapreduce/distributed.ssc` imports: DatasetWire,
  # DatasetWirePartition, JsonValue. Five points of surface, read off the program rather than off
  # the package.
  backendTypedDataRuntime
)

cd "$ROOT" || exit 2
mkdir -p "$ROOT/v3/.jars"

cp_all=""
for m in "${MODULES[@]}"; do
  line="$(sbt -batch --error "export $m/Runtime/fullClasspath" 2>/dev/null | tail -1)"
  if [ -z "$line" ]; then
    echo "jvm-classpath: $m produced no classpath — is it a project in build.sbt?" >&2
    exit 1
  fi
  cp_all="${cp_all:+$cp_all:}$line"
done

printf '%s' "$cp_all" > "$OUT"
echo "jvm-classpath: ${#MODULES[@]} module(s) -> $OUT" >&2
