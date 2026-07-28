#!/usr/bin/env bash
#
# build-ram-budget-gate — prove the host RAM guards actually guard.
#
# The thing being defended against here is not a crash, it is a LIE: a semaphore that admits
# everyone, a heap cap that loses to an inherited -Xmx, a slot that leaks on failure, an overcommit
# check that never trips. Every one of those fails green and looks exactly like working protection —
# which is how `jvm-mem-guard` ran for a week with a 0-byte log across an OOM event.
#
# So each assertion below observes the real thing (a real JVM's MaxHeapSize, real wall-clock overlap
# between real processes) and prints expected/got on mismatch. AGENTS.md: a check that can fail
# silently will.
#
# Usage: tests/e2e/build-ram-budget-gate.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GUARD="$ROOT/scripts/build-guard"
REPORT="$ROOT/scripts/build-ram-report"
REAPER="$ROOT/scripts/kill-stale-builders"
TMP="$(mktemp -d)"
# Isolated semaphore dir: this gate must never contend with, or corrupt, the real host-wide one.
export SSC_BUILD_SEMDIR="$TMP/sem"
trap 'rm -rf "$TMP"' EXIT

fail=0
ok()  { printf '✓ %s\n' "$*"; }
bad() { printf '✗ %s\n' "$*"; fail=1; }
eq()  { # eq <what> <expected> <got>
  if [ "$2" = "$3" ]; then ok "$1 ($3)"; else bad "$1: expected=$2 got=$3"; fi
}

echo "── build RAM budget gate"

for f in "$GUARD" "$REPORT" "$REAPER"; do
  [ -x "$f" ] || { bad "not executable: $f"; }
done

# ── 1. slot count is DERIVED from host RAM, not hardcoded ────────────────────
# A constant is wrong on the next machine, and this is the number that decides how much build load
# the host will admit at once. Recompute it independently and compare.
line=$("$GUARD" --print 2>/dev/null)
host_mb=$(printf '%s' "$line" | tr ' ' '\n' | sed -n 's/^host_mb=//p')
slots=$(printf '%s'   "$line" | tr ' ' '\n' | sed -n 's/^slots=//p')
reserve=$(printf '%s' "$line" | tr ' ' '\n' | sed -n 's/^reserve_mb=//p')
slot_mb=$(printf '%s' "$line" | tr ' ' '\n' | sed -n 's/^slot_mb=//p')
if [ -n "$host_mb" ] && [ -n "$slots" ]; then
  want=$(( (host_mb - reserve) / slot_mb )); [ "$want" -lt 1 ] && want=1
  eq "slots derived from host RAM ((${host_mb}-${reserve})/${slot_mb})" "$want" "$slots"
else
  bad "build-guard --print did not report host_mb/slots; got: $line"
fi

# ── 2. the semaphore actually SERIALIZES ────────────────────────────────────
# Three guarded commands, one slot. Each records the wall-clock second it starts and ends. If the
# semaphore admits everyone, the intervals overlap; that is the whole failure mode, so measure
# overlap rather than trusting that a slot was taken.
export SSC_BUILD_MIN_FREE_MB=0        # isolate the semaphore property from host memory state
i=0
while [ "$i" -lt 3 ]; do
  ( "$GUARD" --slots 1 --wait 60 -- bash -c \
      'printf "%s " "$(date +%s)" >> '"$TMP"'/run.'"$i"'; sleep 2; date +%s >> '"$TMP"'/run.'"$i" ) &
  i=$(( i + 1 ))
done
wait

overlaps=0
started=0
for a in 0 1 2; do
  [ -f "$TMP/run.$a" ] || continue
  started=$(( started + 1 ))
  read -r as ae < "$TMP/run.$a"
  for b in 0 1 2; do
    [ "$a" = "$b" ] && continue
    [ -f "$TMP/run.$b" ] || continue
    read -r bs be < "$TMP/run.$b"
    # strict overlap: b starts before a ends AND ends after a starts
    if [ "$bs" -lt "$ae" ] && [ "$be" -gt "$as" ]; then overlaps=$(( overlaps + 1 )); fi
  done
done
eq "all 3 guarded commands ran" 3 "$started"
if [ "$overlaps" -eq 0 ]; then
  ok "semaphore serialized them (0 overlapping intervals at --slots 1)"
else
  bad "semaphore did NOT serialize: expected=0 overlapping intervals got=$overlaps"
  for a in 0 1 2; do [ -f "$TMP/run.$a" ] && printf '    run.%s: %s\n' "$a" "$(cat "$TMP/run.$a")"; done
fi

# ── 3. the heap cap BEATS a larger inherited -Xmx ───────────────────────────
# Dev hosts carry -Xmx12g in JDK_JAVA_OPTIONS. The cap is appended so it is the LAST -Xmx and wins by
# last-wins; if that ever regresses, children silently get 12 GB again. Asked of a real JVM, not of
# the env string — the env string is what we set, MaxHeapSize is what the JVM did.
if command -v java >/dev/null 2>&1; then
  got=$(JDK_JAVA_OPTIONS="-Xmx12g" "$GUARD" --slots 4 --xmx 1g -- \
          java -XX:+PrintFlagsFinal -version 2>/dev/null |
        awk '/ MaxHeapSize /{print int($4/1048576)}' | head -1)
  if [ -n "$got" ] && [ "$got" -ge 1000 ] && [ "$got" -le 1100 ]; then
    ok "child heap cap wins over inherited -Xmx12g (MaxHeapSize=${got} MB, wanted ~1024)"
  else
    bad "child heap cap did NOT win: expected=~1024 MB got=${got:-<none>} MB"
  fi
else
  echo "  (skip: no java on PATH)"
fi

# ── 4. a FAILING command must still release its slot ────────────────────────
# `exec` would drop the EXIT trap and leak the slot permanently — the exact trap scripts/conformance
# documents. A leak is invisible until the next build blocks forever, so test it directly.
"$GUARD" --slots 1 --wait 20 -- bash -c 'exit 3' >/dev/null 2>&1
rc1=$?
"$GUARD" --slots 1 --wait 20 -- true >/dev/null 2>&1
rc2=$?
eq "failing command's exit status is passed through" 3 "$rc1"
eq "slot was released after failure (next acquire succeeds)" 0 "$rc2"

# ── 4b. a GNU-shaped `stat` must not kill the guard ─────────────────────────
# REGRESSION TEST for the bug that took this gate red on the first Linux runner it ever saw.
# `stat -f` means "format" on BSD and "--file-system" on GNU, so `stat -f %m` SUCCEEDS on Linux and
# prints text starting with "  File: …". That reached `$(( now - mt ))`, where bash treats a bare
# word as a variable name, and `set -u` aborted the guard with "File: unbound variable".
#
# Every local run passed, because macOS `stat -f %m` really is mtime. So this simulates the other
# platform instead of waiting for it: a fake `stat` first on PATH that behaves like GNU's. The point
# is not the flag order (that is fixed) but that non-numeric output can never reach arithmetic.
fake="$TMP/fakebin"; mkdir -p "$fake"
cat > "$fake/stat" <<'FAKESTAT'
#!/usr/bin/env bash
# GNU-shaped: -c is the format flag; -f is --file-system and prints a "File:" block.
if [ "${1:-}" = "-c" ]; then shift; shift; exit 0; fi   # succeed but print nothing (the nastier case)
printf '  File: "%s"
  ID: 0 Namelen: 255 Type: apfs
' "${2:-/}"
exit 0
FAKESTAT
chmod +x "$fake/stat"
mkdir -p "$SSC_BUILD_SEMDIR/slot.0" 2>/dev/null
echo 999999 > "$SSC_BUILD_SEMDIR/slot.0/pid"          # a dead owner, so reap_stale must inspect it
out="$(PATH="$fake:$PATH" SSC_BUILD_MIN_FREE_MB=0 "$GUARD" --slots 2 --wait 20 -- true 2>&1)"; rc=$?
rm -rf "$SSC_BUILD_SEMDIR/slot.0" 2>/dev/null
if [ "$rc" -eq 0 ] && ! printf '%s' "$out" | grep -q 'unbound variable'; then
  ok "a GNU-shaped stat does not crash the stale-slot reaper"
else
  bad "GNU-shaped stat broke the guard: expected=exit 0 and no 'unbound variable' got=exit $rc"
  printf '    %s\n' "$(printf '%s' "$out" | head -3)"
fi

# ── 5. the overcommit check trips, and only when it should ──────────────────
# Both directions. A gate that always passes and a gate that always fails are equally useless, and
# only comparing both tells them apart.
SSC_BUILD_RAM_OVERCOMMIT=1000 "$REPORT" --gate --fast --brief >/dev/null 2>&1
eq "--gate passes at a generous overcommit budget" 0 "$?"

# The fail direction needs something to be over-committed BY. On a CI runner mid-`validate` there is
# no build JVM at all, so DECLARED is legitimately 0 and no budget can be exceeded — asserting a
# failure there would be asserting a bug. Spawn a JVM of our own so the check has a subject on every
# host, and say plainly when we cannot.
if command -v java >/dev/null 2>&1; then
  java -Xmx512m -cp . -version >/dev/null 2>&1 &   # short-lived; only needs to exist for the sample
  sleep 0.3
  declared=$("$REPORT" --fast --tsv 2>/dev/null | awk -F'\t' '$1=="TOTAL"{print $4}')
  if [ -n "$declared" ] && [ "$declared" -gt 0 ] 2>/dev/null; then
    SSC_BUILD_RAM_OVERCOMMIT=0.0001 "$REPORT" --gate --fast --brief >/dev/null 2>&1
    rc=$?
    if [ "$rc" -ne 0 ]; then
      ok "--gate fails at an impossible overcommit budget (declared=${declared} MB, rc=$rc)"
    else
      bad "--gate returned 0 at overcommit=0.0001 with declared=${declared} MB — it can never report overcommit"
    fi
  else
    echo "  (skip: no build processes on this host, so DECLARED is 0 and nothing can exceed a budget)"
  fi
  wait 2>/dev/null
else
  echo "  (skip: no java on PATH)"
fi

# ── 6. the report names the numbers it exists to name ───────────────────────
out=$("$REPORT" --fast --brief 2>/dev/null)
for want in RESIDENT DECLARED HOST pressure; do
  case "$out" in
    *"$want"*) ok "report includes $want" ;;
    *) bad "report is missing '$want'; got:"; printf '    %s\n' "$out" ;;
  esac
done

# ── 7. the reaper never kills without --kill ────────────────────────────────
# Its default is a dry run and must stay one: it is wired into launchd, and a default that kills
# would take out live builders on a schedule.
before=$(ps ax -o pid=,command= | grep -cE 'sbt-launch|bloop|sbt/standalone' || true)
SSC_IDLE_SAMPLE_SECS=1 "$REAPER" --idle 1 >/dev/null 2>&1
rc=$?
after=$(ps ax -o pid=,command= | grep -cE 'sbt-launch|bloop|sbt/standalone' || true)
eq "reaper dry-run exits 0" 0 "$rc"
if [ "$after" -ge "$before" ]; then
  ok "reaper dry-run killed nothing (builders before=$before after=$after)"
else
  bad "reaper dry-run KILLED builders: expected>=$before got=$after"
fi
SSC_IDLE_SAMPLE_SECS=1 "$REAPER" --idle 1 --bogus-flag >/dev/null 2>&1
eq "reaper rejects an unknown flag instead of ignoring it" 2 "$?"

echo
[ "$fail" -eq 0 ] && { echo "✓ build RAM budget gate PASSED"; exit 0; }
echo "✗ build RAM budget gate FAILED"
exit 1
