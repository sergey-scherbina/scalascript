#!/usr/bin/env bash
# The FIRST N for v3's own JVM backend — v3/specs/70-jvm-backend.md §8 step 2.
#
#   ./v3/jvm-backend-census.sh            the whole conformance corpus
#   ./v3/jvm-backend-census.sh --limit 40 the first 40 files, for a quick read
#
# WHAT IT ANSWERS: of the corpus, how many modules does the backend EMIT a class file for, and —
# for every one it refuses — WHICH CONSTRUCT it named. The histogram is the point. N alone says the
# backend is early, which everyone knows; the histogram says which instruction to implement next,
# and that is what orders §8 steps 3-6.
#
# IT IS NOT A GATE AND IS NOT WIRED INTO CI, deliberately. Every file is one JVM start (the cost
# `feedback_f_corpus_gate_cost_is_process_startup` measured), so a full census is minutes; it is run
# when a stage lands and its number is written into SPRINT.md, where a later reader can compare.
#
# THERE IS NO SECOND NUMBER YET, and pretending otherwise would be the dishonest part. The obvious
# companion measurement — "of those that emit, how many RUN to the same answer as `ssc3 run`" — is
# not available before `Prim` (§8 step 6): `run` prints through `io.println`, this backend's harness
# prints the entry function's return value, and a module that calls `io.println` is refused here for
# exactly that reason. The two lanes have no shared observable at stage 2. When `Prim` lands, this
# script grows that column and the fixture gate becomes a differential.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 2

limit=0
[ "${1:-}" = "--limit" ] && limit="${2:-0}"

sandbox="$(mktemp -d "${TMPDIR:-/tmp}/jvmcensus.XXXXXX")"
# THE EMIT RUNS FROM THE REPO ROOT, not from the sandbox, and that is not laziness. `emit-jvm`
# resolves a program's imports relative to the CWD, so running it elsewhere turns every file with an
# `import std/…` into "cannot find the import" — a FRONT refusal that says nothing about the
# backend. Measured on the first attempt: 10 of 25 files were miscounted that way. `<Name>.class`
# therefore lands in the root and is deleted immediately; `*.class` is git-ignored (.gitignore:48)
# and the trap sweeps the rest on any exit.
trap 'rm -rf "$sandbox"; rm -f "$ROOT"/SscJvmCensus*.class' EXIT HUP INT TERM

total=0; emitted=0
reasons="$sandbox/reasons.txt"; : > "$reasons"
emits="$sandbox/emitted.txt";   : > "$emits"

for f in "$ROOT"/tests/conformance/*.ssc; do
  [ -e "$f" ] || continue
  [ "$limit" -gt 0 ] && [ "$total" -ge "$limit" ] && break
  total=$((total + 1))
  name="SscJvmCensus$total"
  if out="$(timeout 300 "$ROOT/v3/ssc3" emit-jvm "$f" "$name" 2>&1)"; then
    emitted=$((emitted + 1))
    basename "$f" .ssc >> "$emits"
  else
    # The refusal's own words are the datum. `Unsupported` says "does not translate <construct>";
    # anything else is a front or verifier refusal and is counted under its own heading, because a
    # file the FRONT cannot read says nothing about the backend.
    reason="$(printf '%s\n' "$out" | grep -o 'does not translate .*' | head -1)"
    if [ -z "$reason" ]; then
      # A front/loader/verifier refusal, folded to its KIND — the message carries a path and a
      # line number, so counting the raw text would give one bucket per file and no histogram.
      reason="(not a backend refusal) $(printf '%s\n' "$out" | grep -m1 -o 'ssc3: .*' \
        | sed -e 's/[^ ]*\.ssc[^ ]*//g' -e 's/[0-9]\+/N/g' | cut -c1-70)"
      [ -z "$reason" ] && reason="(not a backend refusal) unclassified"
    fi
    printf '%s\n' "$reason" >> "$reasons"
  fi
  rm -f "$ROOT/$name.class"
done

if [ "$total" -eq 0 ]; then
  echo "jvm-backend-census: FAIL — no corpus files found. An empty population reporting a number" >&2
  echo "  is the failure this repository keeps hitting; see v3/specs/00-charter.md." >&2
  exit 1
fi

# WHICH FRONT PRODUCED THESE MODULES IS PART OF THE NUMBER. `.ssc` is parsed by the uniml dialect
# front when `v3/.jars/uniml.cp` exists in this checkout and by v3's own parser when it does not,
# and the two refuse different files — so an N reported without naming the front is not comparable
# with the next one. Registering the classpath MID-RUN silently changes the instrument; measured
# 2026-09-02, a census was discarded for exactly that.
if [ -f "$ROOT/v3/.jars/uniml.cp" ]; then front="uniml dialect front (v3/.jars/uniml.cp present)"
else front="v3's own parser (no v3/.jars/uniml.cp)"; fi

echo "── v3 JVM backend, corpus census ─────────────────────────────────────────"
echo "  front:              $front"
echo "  EMITS a class file: $emitted / $total"
echo
echo "  why the rest were refused, most common first:"
sort "$reasons" | uniq -c | sort -rn | sed 's/^/   /'
if [ "$emitted" -gt 0 ]; then
  echo
  echo "  the files that emit:"
  sed 's/^/    /' "$emits"
fi
