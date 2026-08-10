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
#
# ── THE STAMP, and why this file caches the wrong thing without it ───────────────────────────────
#
# `uniml.cp` holds PATHS to sbt's output directories, and their CONTENTS change without the file
# changing. So a checkout whose classpath predates a change to UniML — a rebase, a sibling's commit,
# a fresh worktree — keeps a `uniml.cp` that looks perfectly valid and points at stale classes.
# `v3/uniml/UniFront.scala` then fails to compile against them, and the driver's fallback prints the
# SCALA COMPILER'S ERROR above its own one-line diagnostic. Read top-down, that says "the kernel does
# not compile" when the truth is "your classpath is old".
#
# It is the same shape as the defect `v3/ssc3`'s `uniml_classpath` comment already describes for
# scala-cli's output directory, one layer up and still unfixed: **a cache of a directory PATH cannot
# see the directory's contents move.** Measured cost, on 2026-08-09 alone: a discarded measurement
# round, and TWICE a red `front-gate` blamed on the commit being tested rather than on the checkout.
#
# So this writes a digest of UniML's own sources beside the classpath. The driver compares it ONLY
# when the compile has already failed — the check costs 0.09 s over 152 files and the front-diff gate
# runs the driver once per fixture, so paying it on every invocation would be a real cost for a rare
# condition.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
out="$ROOT/v3/.jars/uniml.cp"
stamp="$ROOT/v3/.jars/uniml.cp.src"
mkdir -p "$ROOT/v3/.jars"

# The sources sbt compiles into the directories `uniml.cp` names. Sorted, so the digest depends on
# content and not on the order `find` happened to walk.
uniml_src_digest() {
  find "$ROOT/uniml" -path '*/src/*' -name '*.scala' -print0 | LC_ALL=C sort -z | xargs -0 cat | shasum | cut -c1-16
}

# `--check` — is the cached classpath current? Exit 0 yes, 1 no, 2 nothing cached. Gates and the
# driver ask this; it builds nothing.
if [ "${1:-}" = "--check" ]; then
  if [ ! -s "$out" ]; then echo "uniml-classpath: nothing cached at $out"; exit 2; fi
  if [ ! -f "$stamp" ]; then
    echo "uniml-classpath: $out has no source stamp — it predates this check; re-run to fix" >&2
    exit 1
  fi
  if [ "$(uniml_src_digest)" = "$(cat "$stamp")" ]; then
    echo "uniml-classpath: current"
    exit 0
  fi
  echo "uniml-classpath: STALE — $out was built from different uniml/ sources" >&2
  exit 1
fi
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
# Stamped AFTER the classpath, and from the sources as they are now: sbt has just compiled them, so
# this digest is the one those output directories correspond to.
uniml_src_digest > "$stamp"
rm -f "$log"
echo "uniml-classpath: $(printf '%s' "$cp" | tr ':' '\n' | grep -c .) entries -> $out (stamped $(cat "$stamp"))"
