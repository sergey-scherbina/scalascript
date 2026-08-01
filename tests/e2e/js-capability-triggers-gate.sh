#!/usr/bin/env bash
#
# js-capability-triggers-gate — an intrinsic whose implementation lives in a runtime CHUNK must have
# a trigger that pulls that chunk in.
#
# THE FAILURE THIS REFUSES IS SILENT AT COMPILE TIME. JsGen decides which runtime chunks to bundle by
# scanning the program text for names (`hasWsServer` and friends in JsGen.scala). An intrinsic that
# is in the table but not in the scan emits its CALL and none of its DEFINITION:
#
#     const ctx = tls("server.crt", "server.key");     // …and no `function tls` anywhere
#     ReferenceError: tls is not defined
#
# Nothing catches that except running a program that uses the name. MEASURED 2026-08-02: thirteen of
# the twenty-one WsServer intrinsics had no trigger, and four picked at random — tls, wsConnect,
# noCache, useGzip — each reproduced the ReferenceError. Only `tls` had ever surfaced, through one
# corpus case, and only because a TreeShaker fix stopped deleting the call that had been hiding it.
#
# DERIVED, NOT RESTATED. The expected set is computed from the two files that already hold the
# truth — the intrinsic table and the chunk source — so adding an intrinsic to ws-server.mjs and
# forgetting the trigger fails here instead of at a user's runtime. A hand-written list here would
# be a third copy, which is the shape that produced the bug.
#
# SCOPE, stated rather than implied: WsServer only. The same structure applies to the other chunks
# (Jwt, Graphql, HtmlDsl, …) and their trigger sets have NOT been audited — extending this gate is
# worth doing and is not the same as claiming they are fine.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHUNK="$ROOT/v1/runtime/backend/js/src/main/resources/scalascript/js-runtime/ws-server.mjs"
JSGEN="$ROOT/v1/runtime/backend/js/src/main/scala/scalascript/codegen/JsGen.scala"
INTR="$ROOT/v1/runtime/backend/js/src/main/scala/scalascript/codegen/intrinsics"
fail=0
ok()  { printf '✓ %s\n' "$*"; }
bad() { printf '✗ %s\n' "$*"; fail=1; }

echo "── js capability triggers (WsServer)"
for f in "$CHUNK" "$JSGEN"; do
  [ -f "$f" ] || { bad "missing: $f"; exit 1; }
done

# Functions the chunk defines, intersected with the names the intrinsic table routes to it.
grep -oE '^function [a-zA-Z_][a-zA-Z0-9_]*' "$CHUNK" | awk '{print $2}' | sort -u > /tmp/.jscap-chunk.$$
grep -rhoE 'RuntimeCall\("[^"]+"\)' "$INTR"/*.scala | sed 's/RuntimeCall("//;s/")//' | sort -u > /tmp/.jscap-intr.$$
comm -12 /tmp/.jscap-chunk.$$ /tmp/.jscap-intr.$$ > /tmp/.jscap-need.$$
rm -f /tmp/.jscap-chunk.$$ /tmp/.jscap-intr.$$

need=$(grep -c . /tmp/.jscap-need.$$ || true)
# A vacuous pass is the failure mode this file is about, so refuse an empty expected set: if either
# extraction breaks, "0 intrinsics, all fine" would print green forever.
if [ "$need" -lt 5 ]; then
  bad "only $need intrinsic(s) resolved to ws-server.mjs — the extraction broke; a near-empty"
  printf '    expected set would make every assertion below vacuous.\n'
  rm -f /tmp/.jscap-need.$$
  echo; echo "✗ js capability triggers gate FAILED"; exit 1
fi
ok "$need intrinsics implemented in ws-server.mjs"

# The trigger block: `val hasWsServer = …` up to the line that consumes it.
triggers="$(sed -n '/val hasWsServer/,/if hasWsServer/p' "$JSGEN")"
[ -n "$triggers" ] || { bad "could not find the hasWsServer block in JsGen.scala"; exit 1; }

missing=""
while read -r fn; do
  printf '%s' "$triggers" | grep -qF "\"$fn(\"" || missing="$missing $fn"
done < /tmp/.jscap-need.$$
rm -f /tmp/.jscap-need.$$

if [ -n "$missing" ]; then
  bad "these emit a call with no definition — no hasWsServer trigger:"
  for m in $missing; do printf '      %s\n' "$m"; done
  printf '    Add `allText.contains("<name>(")` to the hasWsServer block in JsGen.scala.\n'
  printf '    Over-including the chunk costs bundle bytes; under-including it ships a ReferenceError.\n'
else
  ok "every one of them has a trigger"
fi

echo
[ "$fail" -eq 0 ] && { echo "✓ js capability triggers gate PASSED"; exit 0; }
echo "✗ js capability triggers gate FAILED"; exit 1
