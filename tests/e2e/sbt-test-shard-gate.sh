#!/usr/bin/env bash
#
# sbt-test-shard-gate — the N slices must partition the suite list EXACTLY.
#
# A shard scheme that drops a suite FAILS GREEN: every shard passes, the union covers less than the
# whole, and the job set reports success over a hole. That is the same failure the conformance
# shards have a gate for (`tests/e2e/build-conformance-shard-gate.sh`), and it is why this exists
# before the matrix does.
#
# IT RUNS ON A SYNTHETIC ENUMERATION, not on sbt. `scripts/sbt-test-shard --from FILE` reads a
# pre-computed listing, so the partition — the part that can silently lose a suite — is exercised in
# milliseconds instead of behind a five-minute test compile. What that does NOT cover is the
# enumeration itself; that is asserted at runtime instead, by the script refusing an enumeration
# under ten suites and refusing a slice of zero.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SHARD="$ROOT/scripts/sbt-test-shard"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
fail=0
ok()  { printf '✓ %s\n' "$*"; }
bad() { printf '✗ %s\n' "$*"; fail=1; }

echo "── sbt test shard partition gate"
[ -x "$SHARD" ] || { bad "not executable: $SHARD"; exit 1; }

# A listing in the shape `show test:definedTestNames` prints. Deliberately uneven — projects with
# one suite and projects with many — because an even list hides an off-by-one in the round-robin.
FIX="$TMP/enum.txt"
{
  printf '[info] alpha / Test / definedTestNames\n[info] \tVector(a.OneTest, a.TwoTest, a.ThreeTest)\n'
  printf '[info] beta / Test / definedTestNames\n[info] \tVector(b.FourTest)\n'
  printf '[info] gamma / Test / definedTestNames\n[info] \tVector(c.FiveTest, c.SixTest)\n'
  printf '[info] delta / Test / definedTestNames\n[info] \tVector(d.SevenTest, d.EightTest, d.NineTest, d.TenTest)\n'
  printf '[info] epsilon / Test / definedTestNames\n[info] \tVector(e.ElevenTest, e.TwelveTest)\n'
} > "$FIX"

whole="$("$SHARD" --list --from "$FIX")"
n_whole="$(printf '%s\n' "$whole" | grep -c . || true)"
if [ "$n_whole" -eq 12 ]; then ok "unsharded listing sees all 12 suites"; else
  bad "unsharded listing found $n_whole, expected 12"; printf '%s\n' "$whole" | sed 's/^/      /'; fi

for N in 2 3 4 5 7; do
  union="$TMP/union.$N"; : > "$union"
  empty=0
  for ((i = 0; i < N; i++)); do
    slice="$("$SHARD" --list --shard "$i/$N" --from "$FIX")"
    [ -n "$slice" ] || empty=$((empty + 1))
    printf '%s\n' "$slice" >> "$union"
  done
  sort -u "$union" | grep -c . > /dev/null
  u="$(grep -c . "$union" || true)"                    # with duplicates
  d="$(sort "$union" | grep . | uniq -d | grep -c . || true)"
  m="$(comm -23 <(printf '%s\n' "$whole" | sort) <(sort "$union" | grep .) | grep -c . || true)"
  x="$(comm -13 <(printf '%s\n' "$whole" | sort) <(sort "$union" | grep .) | grep -c . || true)"
  if [ "$u" -eq "$n_whole" ] && [ "$d" -eq 0 ] && [ "$m" -eq 0 ] && [ "$x" -eq 0 ] && [ "$empty" -eq 0 ]; then
    ok "N=$N: $N slices, no overlap, nothing missing, nothing invented, none empty"
  else
    bad "N=$N: union=$u (want $n_whole) duplicated=$d missing=$m extra=$x empty-slices=$empty"
  fi
done

# ── the refusals, which are what stop a silent green ─────────────────────────
short="$TMP/short.txt"
printf '[info] alpha / Test / definedTestNames\n[info] \tVector(a.OneTest, a.TwoTest)\n' > "$short"
if "$SHARD" --list --from "$short" >/dev/null 2>&1; then
  bad "accepted a 2-suite enumeration — a truncated listing must be refused, not sliced"
else ok "refuses an enumeration too small to be real"; fi

if "$SHARD" --list --shard 9/4 --from "$FIX" >/dev/null 2>&1; then
  bad "accepted --shard 9/4"; else ok "refuses i >= N"; fi
if "$SHARD" --list --shard 1/0 --from "$FIX" >/dev/null 2>&1; then
  bad "accepted --shard 1/0"; else ok "refuses N < 1"; fi

# N greater than the suite count: some slice IS legitimately empty, and the runner must refuse to
# run it rather than report a fast green over nothing. `--list` stays silent; the run path exits 1.
if "$SHARD" --shard 13/14 --from "$FIX" >/dev/null 2>&1; then
  bad "ran a slice that selected 0 suites"; else ok "refuses to RUN an empty slice"; fi

# THE QUERY ITSELF, frozen — because everything above runs on a synthetic listing through `--from`
# and therefore cannot see what the script actually asks sbt. On 2026-08-15 all four dispatch shards
# refused with an empty enumeration while this gate was green, and the cause was the QUERY: the
# sbt 0.13 colon form `show test:definedTestNames` is accepted by sbt 1.10.7, warned about,
# COMPILED for, reported `[success]` on — and prints nothing. Measured both ways on the same warm
# server: colon 0 headers in 259 s, slash 273 headers in 109 s.
#
# This check cannot ask sbt (that is four minutes and a build), so it freezes the spelling instead.
# A gate that can only test the parser should at least say which question the parser is fed.
# COMMENTS ARE STRIPPED FIRST, and the first version of this check did not: the script's own
# comment quotes the dead colon form to explain why it is dead, and the check read that as the
# defect. A freeze that cannot tell code from prose fires on the explanation of the thing it
# guards.
if grep -vE '^[[:space:]]*#' "$SHARD" | grep -q 'show test:definedTestNames'; then
  bad "the enumeration uses the sbt 0.13 colon form, which prints NOTHING on sbt 1.10.7 — use \`show Test/definedTestNames\`"
else ok "the enumeration asks in slash syntax, which is the form that prints a value"; fi

echo
[ "$fail" -eq 0 ] && { echo "✓ sbt test shard partition gate PASSED"; exit 0; }
echo "✗ sbt test shard partition gate FAILED"; exit 1
