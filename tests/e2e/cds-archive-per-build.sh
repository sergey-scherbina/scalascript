#!/usr/bin/env bash
# The CDS archive must be keyed on the BUILD, not shared at one path for the whole machine.
#
# It used to be `$XDG_CACHE_HOME/scalascript/ssc.jsa` — one file, every checkout and every worktree.
# `AutoCreateSharedArchive` did not regenerate it when the jars changed underneath, so the JVM
# loaded STALE CLASS DEFINITIONS from the archive in preference to the jars on the classpath.
#
# What that looks like from outside is a cast error with no position and no stack, in code nobody
# touched — and every ordinary hypothesis is wrong. Cleaning `bin/`, `target/` and a full rebuild
# have no effect, because the archive is outside the repository; the same commit in another copy of
# the repo WORKS, if its jars happen to match the archive. It cost about two hours.
#
# This gate asserts the SHAPE that makes the collision impossible: the archive path carries the
# build digest. It checks the generated launchers rather than build.sbt, because the launcher is
# what runs — a template that is right and a launcher that is stale is exactly the gap this class of
# bug lives in.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT" || exit 2

fail=0
checked=0
for l in bin/ssc bin/ssc-standard; do
  [ -r "$l" ] || continue
  grep -q 'SharedArchiveFile' "$l" || continue
  checked=$((checked + 1))
  line="$(grep 'SharedArchiveFile' "$l" | head -1)"
  if printf '%s' "$line" | grep -q 'ssc\.jsa'; then
    echo "  FAIL $l shares ONE archive for the machine: $(printf '%s' "$line" | sed 's/^ *//')"
    fail=1
  elif ! printf '%s' "$line" | grep -q '_SSC_CDS_DG'; then
    echo "  FAIL $l has an archive path that does not carry the build digest:"
    echo "       $(printf '%s' "$line" | sed 's/^ *//')"
    fail=1
  else
    echo "  ok   $l keys its archive on the build digest"
  fi
  grep -q '\.build-digest' "$l" || {
    echo "  FAIL $l never reads bin/lib/.build-digest, so its digest cannot be the build's"
    fail=1
  }
done

# No launcher with CDS at all is not a pass — it is a gate measuring nothing, and this repository
# has shipped three of those.
if [ "$checked" -eq 0 ]; then
  echo "cds-archive-per-build: NO LAUNCHER WITH CDS FOUND — build one first (./install.sh --dev)"
  exit 2
fi

[ "$fail" = 0 ] && echo "cds-archive-per-build: OK ($checked launcher(s))" \
                || echo "cds-archive-per-build: FAIL"
[ "$fail" = 0 ]
