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
# Say WHY, not where. Pointing at a temp file is useless on CI, which throws the runner away —
# on 2026-08-08 this printed "sbt failed; see /tmp/tmp.5QpHWR382O" 0.02 s after the step began,
# which is `sbt: command not found` wearing the costume of a build failure, and the log naming it
# was gone before anyone could read it. Print the tail; a missing tool then reads as a missing tool.
if ! command -v sbt >/dev/null 2>&1; then
  echo "uniml-classpath: sbt is not on PATH — this script builds an sbt project." >&2
  echo "  CI: add sbt/setup-sbt@v1 to the job, as .github/workflows/ci.yml does." >&2
  echo "  Locally: see setup.sh." >&2
  exit 1
fi
if ! (cd "$ROOT/uniml" && sbt -batch "export unimlScala/Compile/fullClasspath") > "$log" 2>&1; then
  echo "uniml-classpath: sbt failed. Last 20 lines:" >&2
  tail -20 "$log" >&2
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
