#!/usr/bin/env bash
# The two measurements `v3/specs/70-jvm-backend.md` rests on, re-runnable.
#
# THEY ARE HERE BECAUSE A NUMBER WHOSE APPARATUS IS GONE CANNOT BE RE-READ. The document says a
# JDK-only bytecode backend can skip `StackMapTable` and that the JDK's own compiler is reachable
# in-process; both are load-bearing for the recommendation, and both are one command away from
# being checked against whatever JDK the reader has.
#
#   1. CLASS-FILE VERSION BOUNDARY. A hand-built class with ONE branch and no StackMapTable,
#      emitted at 49/50/51/52. Expected on a modern JVM: 49 and 50 run, 51 and 52 raise
#      `VerifyError: Expecting a stackmap frame`. 50 is the last version whose verifier falls back
#      to type inference, and that is what makes the hard part of a hand-written writer optional.
#
#      The BRANCH is the whole point. The first version of this probe used a straight-line method
#      and every version ran it, including 52 — a method with no jump targets needs no frames, so
#      the probe answered the same for both hypotheses and discriminated nothing.
#
#   2. IN-PROCESS javac. `javax.tools.JavaCompiler` on a full JDK, then the same program under
#      `--limit-modules java.base`. Expected: works, then `NoClassDefFoundError` — the Java-source
#      arm is not merely degraded without `jdk.compiler`, it does not link.
#
# This is a PROBE, not a gate: it prints what it found and exits 0. Its answers are facts about the
# host's JVM, and a different JVM giving different ones is information rather than a failure.
set -uo pipefail
cd "$(dirname "$0")" || exit 2
work="$(mktemp -d "${TMPDIR:-/tmp}/jvmprobe.XXXXXX")"
trap 'rm -rf "$work"' EXIT
cp classfile-probe.py JcProbe.java "$work/" && cd "$work" || exit 2

echo "── 1 · a branch with no StackMapTable, by class-file version ──────────────"
for v in 49 50 51 52; do
  python3 classfile-probe.py "$v" "Br$v" >/dev/null || { echo "  probe generator failed"; exit 0; }
  printf '  major %-3s ' "$v"
  java -cp . "Br$v" 2>&1 | head -2 | tr '\n' ' '; echo
done

echo
echo "── 2 · the JDK's own compiler, in-process and then without jdk.compiler ───"
if ! javac JcProbe.java 2>/dev/null; then echo "  no javac on PATH — cannot ask"; exit 0; fi
printf '  full JDK          '; java -cp . JcProbe 2>&1 | tr '\n' ' '; echo
printf '  --limit-modules   '; java --limit-modules java.base -cp . JcProbe 2>&1 | head -2 | tr '\n' ' '; echo
