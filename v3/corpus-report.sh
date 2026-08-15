#!/usr/bin/env bash
# v3 SSC3-5 — the honest compatibility number. Invariant I-5 of v3/specs/00-charter.md.
#
# Every `tests/conformance/*.ssc` with a checked-in expectation is compiled by v3 and run, and the
# result lands in exactly one of the four buckets of v3/specs/20-core-language.md §4:
#
#   PASS          output matches the expectation the OTHER lanes are held to
#   DIFF          v3 ran it and produced different output          — a DEFECT
#   UNSUPPORTED   v3 refused it, naming a construct and a position — honest, not a defect
#   CRASH         v3 neither ran it nor refused it cleanly         — a DEFECT, worse than DIFF
#   LANE-EXCLUDED the case does not hold the v2 lane to this expectation — NOT attributable to v3
#
# THE LAST BUCKET IS A CORRECTION, added 2026-08-04. `ssc3 run` executes through the v2 bridge, and
# 244 of 355 cases either omit `v2` from `backends:` or declare it `known-red`. Comparing v3's
# output against an expectation the v2 lane is deliberately not held to counts v2's divergence as a
# v3 defect. `js-int-division-by-zero` is the case that exposed it: it expects `Infinity`, v3 prints
# `inf` — and so does v2 itself on the same program, which is why the case excludes v2.
#
# So the number this report produced before today was misattributing. It was not flattering v3 —
# it was blaming v3 for the lane it borrows.
#
# The oracle is the SHARED expectation, not a v3-specific one. A lane that grades its own homework
# measures nothing.
#
# Compare OUTPUT, never the exit code: v2 fails by printing a sentinel at exit 0, and the array
# defect this module's SSC3-1 fixed exited 0 on a wrong answer.
#
# `UNSUPPORTED` must NAME what it could not take. A refusal that says only "cannot compile" is
# counted as a CRASH here, deliberately — a bucket nobody can act on is a bucket that hides work.
#
# Usage:  v3/corpus-report.sh [--limit N] [--list-diff] [--list-crash]
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2

limit=0; show_diff=0; show_crash=0; show_names=0; lane=bridge
while [ $# -gt 0 ]; do
  case "$1" in
    # The EXECUTOR lane — v3's own runtime instead of the v2 VM. The two must give the same number,
    # and until 2026-08-07 they did not: the bridge read 48 and the executor 34, which no probe set
    # had shown because a probe set answers only its own question. Measured here so the difference
    # is a number in the same report rather than a separate errand.
    --exec) lane=exec; shift ;;
    --limit) limit="$2"; shift 2 ;;
    --list-diff) show_diff=1; shift ;;
    --list-crash) show_crash=1; shift ;;
    # THE QUESTION THE HISTOGRAM BELOW CANNOT ANSWER, and it is always the next one asked.
    --names) show_names=1; shift ;;
    *) echo "unknown flag: $1" >&2; exit 2 ;;
  esac
done

# Package once. 383 cases against `scala-cli run` would be a JVM start per case per side; the jars
# turn a ~50-minute report into a few minutes, which is the difference between a number that gets
# looked at and one that does not.
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
# Built through the DRIVER, which compiles each tree once per source digest into a
# content-addressed directory and shares it with every gate. This script used to package its own
# assemblies with `scala-cli`, which built the same sources a second time and measured artifacts
# nothing else had run — and `scala-cli` is gone from v3 entirely now (invariant I-1 means the
# kernel has no dependencies to resolve, so a pinned compiler is all it ever needed).
echo "building v3 and v2 …" >&2
KERNEL_CP="$("$ROOT/v3/ssc3" __kernel-cp)" \
  || { echo "corpus-report: v3 failed to build" >&2; exit 2; }
# THE v2 SIDE IS THE DRIVER'S COMMAND, NOT A CLASSPATH THIS SCRIPT ASSEMBLES.
#
# It used to be `V2_CP="$(ssc3 __v2-cp)"` here and `java -cp "$V2_CP" ssc.cli run-ir` below — a
# second copy of a decision `v3/ssc3` already makes. The two copies diverged the moment the driver
# learned to load the v2 PLUGIN FLEET: this script builds the IR with the FRONT, which has the
# fleet installed, so lowering emits `(prim "mkdirs" …)` where it used to emit a refusal — and then
# it ran that IR on a plain `ssc.cli` with no plugins. Build side and run side disagreed about which
# prims exist, and four cases that MATCH through `v3/ssc3 run --bridge`, individually and
# concurrently, were counted DIFF here. The comment above says a census answers only its own
# question and that the question is set by the command actually invoked; that is as true of the v2
# half of the bridge as of the front.
V2RUN_LINES="$("$ROOT/v3/ssc3" __v2-run)" \
  || { echo "corpus-report: v2 failed to build" >&2; exit 2; }
IFS=$'\n' read -r -d '' -a V2RUN <<< "$V2RUN_LINES" || true

# WHICH FRONT THIS REPORT MEASURES.
#
# It packaged `v3/src` alone and nothing else, so `SSC3_FRONT` was a NO-OP here: with the uniml
# front made the default in `v3/ssc3`, two runs of this script — one of them with `SSC3_FRONT=v3` —
# gave the same N and the same buckets, and I reported that as "both fronts agree". They did not
# agree; only one of them ran, twice. A census answers only its own question, and the question this
# script asks is set by the command it actually invokes.
#
# The uniml front cannot be an assembly — its classpath is DIRECTORIES, which is measured and
# documented in `v3/ssc3`. So it runs from the classpath the driver caches.
SSC3RUN=(java -Xss512m -cp "$KERNEL_CP" ssc3.ssc3)
SSC3BUILD=(java -cp "$KERNEL_CP" ssc3.ssc3)
front_used="v3"
if [ "${SSC3_FRONT:-auto}" != "v3" ] && [ -s "$ROOT/v3/.jars/uniml.cp" ]; then
  ucp="$("$ROOT/v3/ssc3" __classpath 2>/dev/null)" || ucp=""
  if [ -n "$ucp" ]; then
    SSC3RUN=(java -Xss512m -cp "$ucp" ssc3.ssc3uniml)
    SSC3BUILD=(java -cp "$ucp" ssc3.ssc3uniml)
    front_used="uniml"
  else
    echo "corpus-report: the uniml front is present but its classpath did not build" >&2
  fi
fi

# Does this case hold the v2 lane to its expectation? `backends:` lists the lanes it applies to;
# a `known-red: … v2 …` declares the lane a declared, expiring failure. Either way, a difference on
# the bridge is not v3's to answer for.
holds_v2() {
  local f="$1"
  grep -qE '^known-red:.*\bv2\b' "$f" && return 1
  if grep -qE '^backends:' "$f"; then
    grep -qE '^backends:.*\bv2\b' "$f" || return 1
  fi
  return 0
}

pass=0; diff=0; unsup=0; crash=0; excl=0; total=0
: > "$WORK/diff.txt"; : > "$WORK/crash.txt"; : > "$WORK/unsup.txt"; : > "$WORK/excl.txt"

# ── ONE CASE, one line of verdict ────────────────────────────────────────────────────────────────
#
# Pulled out of the loop so the loop can be PARALLEL. Measured before changing anything: 120 cases
# cost 79s and 240 cost 442s — the per-case cost GROWS, because the corpus is alphabetical and the
# later `scljet-*` family re-parses a large import closure every time. The host has 14 cores and
# the report was using 1.2 of them.
#
# Each case writes ONE line — `verdict<TAB>name<TAB>message` — and nothing else, so the parallel
# runs cannot interleave inside a record. Temporary files are per-case for the same reason: the
# serial version shared one `$WORK/o` and `$WORK/e`, which two concurrent cases would overwrite
# under each other.
run_case() {
  local f="$1"
  local name exp got err msg out ere irf
  name="$(basename "$f" .ssc)"
  exp="tests/conformance/expected/$name.txt"
  out="$WORK/o.$name"; ere="$WORK/e.$name"; irf="$WORK/ir.$name"
  if [ "$lane" = "exec" ]; then
    err="$("${SSC3RUN[@]}" exec "$f" 2>"$ere" >"$out"; echo $?)"
  else
    err="$("${SSC3BUILD[@]}" build "$f" 2>"$ere" >"$irf"; echo $?)"
  fi
  if [ "$err" = "0" ]; then
    if [ "$lane" = "exec" ]; then got="$(cat "$out")"
    else got="$("${V2RUN[@]}" run-ir "$irf" 2>/dev/null)"; fi
    if [ "$got" = "$(cat "$exp")" ]; then
      printf 'PASS\t%s\t\n' "$name"
    elif ! holds_v2 "$f"; then
      # Ran, differed, but this case does not hold the v2 lane to that expectation. Still counted
      # and still listed — a silent skip would hide work — but not as a v3 defect.
      printf 'EXCL\t%s\t\n' "$name"
    else
      printf 'DIFF\t%s\t\n' "$name"
    fi
  else
    msg="$(head -1 "$ere")"
    # A positioned, named refusal is UNSUPPORTED. A stack trace or a bare failure is a CRASH,
    # because it tells the reader nothing they can act on.
    if grep -qE ':[0-9]+:[0-9]+:' "$ere" && ! grep -q '\tat ' "$ere"; then
      printf 'UNSUP\t%s\t%s\n' "$name" "$msg"
    else
      printf 'CRASH\t%s\t%s\n' "$name" "$msg"
    fi
  fi
  rm -f "$out" "$ere" "$irf"
}
# The case list, in corpus order, honouring `--limit`.
: > "$WORK/cases.txt"
for f in tests/conformance/*.ssc; do
  name="$(basename "$f" .ssc)"
  [ -f "tests/conformance/expected/$name.txt" ] || continue
  if [ "$limit" -gt 0 ] && [ "$total" -ge "$limit" ]; then break; fi
  total=$((total + 1))
  printf '%s\n' "$f" >> "$WORK/cases.txt"
done

# HOW MANY AT ONCE, and why not all of them. Each case is a JVM; the deep-recursion cases sit on
# the bridge's stack limit and whether they overflow depends on the memory available at that moment,
# so piling on every core would trade minutes for a number that moves. Half the cores is the
# compromise, and `SSC3_CORPUS_JOBS` overrides it — set it to 1 to reproduce the serial reading.
JOBS="${SSC3_CORPUS_JOBS:-$(( $(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4) / 2 ))}"
[ "$JOBS" -ge 1 ] 2>/dev/null || JOBS=1
echo "running $total case(s), $JOBS at a time …" >&2
# A pool of background jobs IN THIS SHELL, not `xargs bash -c`. The first version used xargs and
# every case "crashed": `export -f` does not carry ARRAYS, so `SSC3RUN` arrived empty in the
# subshell and the report read 240 CRASH — a wrong answer that looked like a catastrophic
# regression. Measured immediately, which is the only reason it cost a minute rather than a
# morning.
#
# Waves of `$JOBS`, each writing to its OWN file so two cases cannot interleave inside a record.
slot=0
while IFS= read -r f; do
  # `< /dev/null` IS LOAD-BEARING: this loop reads the case list on fd 0, and a background job
  # inherits it. With the plugin fleet on, `std-os-readline` really does read stdin — so it ate the
  # REST OF THE CASE LIST and every case after it was never dispatched at all. The report still said
  # "running 369 case(s)" (that count is taken upfront) while its buckets summed to 294: 75 cases
  # silently vanished, and the number of them was the tail of the list behind the reader.
  #
  # It was latent before and the fleet only made it live — with no plugins loaded nothing on either
  # side ever read stdin. A case must not be able to consume its own harness's input under any
  # configuration, so this is closed here rather than in the one case that exposed it.
  run_case "$f" >> "$WORK/v.$slot" < /dev/null &
  slot=$(( (slot + 1) % JOBS ))
  [ "$slot" -eq 0 ] && wait
done < "$WORK/cases.txt"
wait
cat "$WORK"/v.* > "$WORK/verdicts.txt" 2>/dev/null || : > "$WORK/verdicts.txt"

while IFS=$'\t' read -r verdict name msg; do
  case "$verdict" in
    PASS)  pass=$((pass + 1)) ;;
    DIFF)  diff=$((diff + 1)); printf '%s\n' "$name" >> "$WORK/diff.txt" ;;
    EXCL)  excl=$((excl + 1)); printf '%s\n' "$name" >> "$WORK/excl.txt" ;;
    UNSUP) unsup=$((unsup + 1)); printf '%s\t%s\n' "$name" "$msg" >> "$WORK/unsup.txt" ;;
    CRASH) crash=$((crash + 1)); printf '%s\t%s\n' "$name" "$msg" >> "$WORK/crash.txt" ;;
  esac
done < "$WORK/verdicts.txt"

echo
echo "═══ SSC3 vs the conformance corpus — $lane lane, $front_used front ═══"
printf '  PASS         %4d\n' "$pass"
printf '  DIFF         %4d   (defect)\n' "$diff"
printf '  UNSUPPORTED  %4d   (honest)\n' "$unsup"
printf '  CRASH        %4d   (defect, worse than DIFF)\n' "$crash"
printf '  LANE-EXCL    %4d   (the case excludes the v2 lane — not v3'"'"'s to answer for)\n' "$excl"
printf '  ────────────────\n'
printf '  N = %d / %d\n' "$pass" "$total"

if [ -s "$WORK/unsup.txt" ]; then
  echo
  echo "what UNSUPPORTED is actually blocked on, most common first:"
  sed 's/.*: //' "$WORK/unsup.txt" | sed "s/'[^']*'/'…'/g" | sort | uniq -c | sort -rn | head -12 | sed 's/^/  /'
fi

# WHAT THE ELISION THROWS AWAY, on request. Replacing every quoted identifier with a placeholder is
# the right DEFAULT: it groups 49 files blocked on forty different names into one line and ranks the
# SHAPES of the remaining work. But a shape decides nothing on its own — the DISTRIBUTION does, and
# the two are indistinguishable once elided. `Dataset 12, suspend 6, math 4` then a tail of ones
# says "standard-library breadth, no single win"; the same 49 concentrated on ONE name would say
# "one bug, go fix it". Opposite conclusions from an identical line.
#
# It is here because I hand-rolled this exact sweep twice on 2026-08-10, and the DATASET decision in
# `v3/BACKLOG.md` rests on a third hand count. A question asked every time the histogram is read
# belongs in the tool that prints the histogram.
if [ "$show_names" = 1 ] && [ -s "$WORK/unsup.txt" ]; then
  echo
  echo "the identifiers behind those messages, most common first:"
  grep -ohE "(unknown name|unknown function|host function) '[^']*'" "$WORK/unsup.txt" \
    | sed "s/.*'\(.*\)'/\1/" | sort | uniq -c | sort -rn | head -20 | sed 's/^/    /'
fi
[ "$show_diff" = 1 ] && { echo; echo "DIFF:"; sed 's/^/  /' "$WORK/diff.txt"; echo "LANE-EXCLUDED:"; sed 's/^/  /' "$WORK/excl.txt"; }
[ "$show_crash" = 1 ] && { echo; echo "CRASH:"; sed 's/^/  /' "$WORK/crash.txt"; }
echo
echo "N is a MEASUREMENT, not a target. It may rise in any commit and fall in none (I-5)."
# HONEST CAVEAT, measured 2026-08-02: three consecutive runs of this report gave N = 16, 16, 17 and
# DIFF = 5, 3, 4. The variance is entirely in the deep-recursion cases — deep-tail-recursion,
# tail-recursion, mutual-recursion — which recurse ~100 000 deep through the bridge, where v2 has no
# TCO. Whether they overflow depends on the JVM's available stack at that moment, so on a contended
# host they land in DIFF sometimes and PASS others.
#
# Not papered over by pinning a stack size: the flakiness is a real property of running deep
# recursion WITHOUT tail calls, and hiding it would make N look steadier than the thing it measures.
# The executor (`ssc3 exec`) runs the same programs in constant stack; when the front's own lane can
# run the corpus, this variance disappears rather than being suppressed.
echo "  ±1: the deep-recursion cases sit on the bridge's stack limit — see the note in this script."
exit 0
