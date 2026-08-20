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
  # THE SAME GUARDS `plugin-classpath.sh` CARRIES, LIFTED VERBATIM, because this file is its sibling
  # and had none of them. One rule written at one site and missing at the other is a shape this
  # repository has paid for more than once; the comments below are the sibling's own, with its CI
  # history attached, and they are kept rather than paraphrased so the two cannot drift.
  line="$(sbt -batch --error -Dsbt.supershell=false "export $m/Runtime/fullClasspath" 2>/dev/null             | sed $'s/\x1b\[[0-9;]*[A-Za-z]//g' | grep -v '^[[:space:]]*$' | tail -1)"
  if [ -z "$line" ]; then
    echo "jvm-classpath: $m produced no classpath — is it a project in build.sbt?" >&2
    exit 1
  fi
  # VALIDATED, NOT TRUSTED. `tail -1` of sbt's stdout is the classpath only when sbt printed it
  # alone; on a cold runner the last line can be anything, and a plausible-looking non-classpath
  # written to $OUT is worse than none — v3.yml went red 2026-08-18 with `Not found: ssc`,
  # 75 unresolved-symbol errors in V2Fleet.scala, because plugins.cp existed and did not contain
  # the jars, and the driver's silent fallback then unregistered the UNIML FRONT two layers away
  # (`front-capability-gate: CANNOT RUN`). Diagnosing that took a CI-log archaeology session;
  # this check makes the same failure a one-line answer naming the module and the entry.
  #
  # Every ':'-separated entry must EXIST — an sbt warning, a partial line or a path from another
  # machine all fail this, and nothing that fails it can be a fullClasspath sbt just computed.
  bad=""
  IFS=':' read -ra _entries <<< "$line"
  for _e in "${_entries[@]}"; do
    [ -e "$_e" ] || { bad="$_e"; break; }
  done
  if [ -n "$bad" ]; then
    echo "jvm-classpath: $m exported a line that is not a classpath — entry does not exist:" >&2
    echo "    $bad" >&2
    echo "  full line: $(printf '%s' "$line" | cut -c1-200)" >&2
    exit 1
  fi
  cp_all="${cp_all:+$cp_all:}$line"
done

printf '%s' "$cp_all" > "$OUT"
echo "jvm-classpath: ${#MODULES[@]} module(s) -> $OUT" >&2
