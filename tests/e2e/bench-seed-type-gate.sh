#!/usr/bin/env bash
#
# The bench wrapper must pass a seed of the type the workload DECLARES.
#
# WHY. `ssc bench` defeats constant-folding by feeding the workload an opaque `seed`. It detected
# the seed by name (`def\s+workload\s*\(\s*seed\b`) and never read its TYPE, then hardcoded a Long:
# `workload(_ssc_sink.get())` on the JVM lane and `var _ssc_seed: Long = 1L` on interp/js. For the
# one corpus row that declares `def workload(seed: Int)` — `bench/corpus/var-expr-init-int.ssc` —
# that is a type error, and BOTH lanes reported `n/a`:
#
#   jvm  -- [E007] Type Mismatch Error: ... _ssc_sink.getAndAdd(workload(_ssc_sink.get()))
#   js   TypeError: Cannot mix BigInt and other types    (let s = (_imod(seed, 46341) + 1))
#
# Read from the table, that is indistinguishable from "these backends cannot run this workload".
# It is the second time this harness has blamed a backend for a defect in its own wrapper — see the
# `0d`-literal comment in `Main.scala`'s Double branch, which reported three float workloads as
# backend failures. Hence a gate rather than a fix in passing. (bench-wrapper-hardcodes-a-long-seed)
#
# WHAT IT CHECKS. One fixture per seed type, run through the REAL `ssc bench --machine` on every
# lane the corpus is measured on, asserting a number comes out. Not the generated source: a wrapper
# that compiles is not the claim — a wrapper that MEASURES is.
#
# Usage: tests/e2e/bench-seed-type-gate.sh [--lanes ssc,js,jvm]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SSC="${SSC_BIN:-$ROOT/bin/ssc-tools}"
LANES="${1:-}"
[[ "$LANES" == --lanes ]] && { LANES="${2:-}"; }
LANES="${LANES#--lanes=}"
[[ -z "$LANES" || "$LANES" == --* ]] && LANES="ssc,js,jvm"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/ssc-bench-seed.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

# Two workloads, identical arithmetic, differing ONLY in the seed's declared type. The Long one is
# the shape the rest of the corpus uses and is the control: if it fails too, the gate is broken
# rather than the wrapper.
cat > "$TMP/seed-int.ssc" <<'EOF'
# seed-int

```scalascript
def workload(seed: Int): Long =
  var s = (seed % 46341) + 1
  var sum = 0L
  var i = 0
  while i < 1000 do
    s = (s * 48271) % 2147483
    sum = sum + (s % 7).toLong
    i = i + 1
  sum
```
EOF

cat > "$TMP/seed-long.ssc" <<'EOF'
# seed-long

```scalascript
def workload(seed: Long): Long =
  var s = (seed % 46341L) + 1L
  var sum = 0L
  var i = 0
  while i < 1000 do
    s = (s * 48271L) % 2147483L
    sum = sum + (s % 7L)
    i = i + 1
  sum
```
EOF

fails=0
for lane in ${LANES//,/ }; do
  for fixture in seed-int seed-long; do
    out="$(SSC_NO_BUILD_CHECK=1 "$SSC" bench --machine --backend "$lane" \
             --warmup 0 --reps 1 "$TMP/$fixture.ssc" 2>&1 || true)"
    # `bench --machine` prints `BENCH <backend> <ms>`; anything else (including the harness's own
    # "produced no measurement" line) is a failure, and the whole output is printed so the reason
    # is in the log rather than one line of it.
    if printf '%s' "$out" | grep -qE "^BENCH $lane [0-9]"; then
      printf '  ok   %-9s %-10s %s\n' "$lane" "$fixture" "$(printf '%s' "$out" | grep -E "^BENCH $lane" | head -1)"
    else
      printf '  FAIL %-9s %-10s no measurement\n' "$lane" "$fixture"
      printf '%s\n' "$out" | sed 's/^/         | /' | head -12
      fails=$((fails + 1))
    fi
  done
done

if [[ $fails -ne 0 ]]; then
  printf 'bench-seed-type-gate: FAIL (%d cell(s) produced no measurement)\n' "$fails" >&2
  exit 1
fi
printf 'bench-seed-type-gate: OK (every lane measured both seed types)\n'
