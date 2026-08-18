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
  # CONTENT IS NOT WIRED, and the reason is measured rather than assumed. Its provider needs a ROOT
  # DOCUMENT — v2's compiler sets one from the `.ssc` it is compiling, and neither of v3's lanes
  # does — so every entry point answers
  # `contentDocument() is unavailable: native compilation has no explicit root content`. While the
  # `unknown name` refusal upstream kept those programs from running, the module was harmless; the
  # moment a top-level `def` could be passed as a value they reached it, and five cases went from an
  # honest refusal to a wrong answer. Filed as v3-the-content-provider-has-no-root-document.
  # v2NativeContentPlugin
  # JSON IS WIRED AGAIN SINCE v3 GAINED AN EXACT DECIMAL. It was out because its core answers
  # `DecimalV` and v3 had no counterpart, so `json-self-hosted-import` — which exists to pin
  # `jsonParse("0.0")` printing `0.0` rather than a float — would have got a plausible wrong answer.
  # `Value.VDec` carries the canonical text now, both directions.
  v2NativeJsonPlugin
  v2NativeCryptoPlugin
  v2NativeActorsPlugin
  # COROUTINES AND GENERATORS, which reach v3 through the registry's `globalValues` table rather
  # than through Prim handlers: `suspend`, `coroutineCreate`, `coroutineResume` are all
  # `registerGlobal`, and six corpus cases wait on the first of them.
  # HOST supplies `cwd` and the rest of the ambient environment, as VALUES rather than functions —
  # see the nullary-call arm in V2Fleet.
  v2NativeHostPlugin
  v2NativeGeneratorPlugin
  v2NativeDatasetPlugin
)

cd "$ROOT" || exit 2
mkdir -p "$ROOT/v3/.jars"

# `export <module>/Runtime/fullClasspath` per module, concatenated. `--error` keeps sbt's own
# chatter out of the value; the LAST line is the classpath, because sbt prints it alone.
cp_all=""
for m in "${MODULES[@]}"; do
  # `-Dsbt.supershell=false` AND an escape-strip, because the validation's FIRST CI firing named
  # the culprit: on a cold runner sbt's supershell emits the terminal-control sequence `ESC[0J`
  # (erase-display) as its last stdout line, so `tail -1` caught THAT instead of the classpath —
  # a non-empty file whose one entry "does not exist" because it is not a path at all. A warm
  # local sbt never prints it, which is why every local run was green while CI unregistered the
  # uniml front. Supershell off removes the source; the strip-and-drop-blank guards the next
  # decoration sbt invents; the validation below stays, because it is what turned this from an
  # archaeology session into a one-line answer.
  line="$(sbt -batch --error -Dsbt.supershell=false "export $m/Runtime/fullClasspath" 2>/dev/null             | sed $'s/\x1b\[[0-9;]*[A-Za-z]//g' | grep -v '^[[:space:]]*$' | tail -1)"
  if [ -z "$line" ]; then
    echo "plugin-classpath: $m produced no classpath — is it a project in build.sbt?" >&2
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
    echo "plugin-classpath: $m exported a line that is not a classpath — entry does not exist:" >&2
    echo "    $bad" >&2
    echo "  full line: $(printf '%s' "$line" | cut -c1-200)" >&2
    exit 1
  fi
  cp_all="${cp_all:+$cp_all:}$line"
done

printf '%s' "$cp_all" > "$OUT"
echo "plugin-classpath: ${#MODULES[@]} module(s) -> $OUT" >&2
