#!/usr/bin/env bash
# Produce (or refresh) the UniML classpath the `uniml` front needs, at v3/.jars/uniml.cp.
#
# SEPARATE from the driver on purpose. UniML is an sbt project and v3's kernel is not; making the
# driver run sbt would mean every `ssc3 run` could block on a build of something it does not use.
# So availability of the second front is an explicit, cached fact: the file exists or it does not.
#
# `40-front-on-uniml.md` §4 has the measured reason this is a classpath and not merged sources —
# UniML has a package `scalascript.uniml.dialect.scalascript`, and inside it `import
# scalascript.uniml.*` resolves to the INNERMOST one. sbt never sees it because each subproject is
# its own compilation unit.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
out="$ROOT/v3/.jars/uniml.cp"
mkdir -p "$ROOT/v3/.jars"
log="$(mktemp)"
if ! (cd "$ROOT/uniml" && sbt -batch "export unimlScala/Compile/fullClasspath") > "$log" 2>&1; then
  echo "uniml-classpath: sbt failed; see $log" >&2
  exit 1
fi
cp="$(grep -oE '^/[^ ]*classes[^ ]*' "$log" | tail -1)"
if [ -z "$cp" ]; then
  echo "uniml-classpath: sbt produced no classpath line; see $log" >&2
  exit 1
fi
printf '%s' "$cp" > "$out"
rm -f "$log"
echo "uniml-classpath: $(printf '%s' "$cp" | tr ':' '\n' | grep -c .) entries -> $out"
