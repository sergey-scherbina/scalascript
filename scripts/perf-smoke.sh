#!/usr/bin/env bash
# Lightweight opt-in performance smoke for developer checkouts.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_JMH=false
TARGET_MS="${SSC_BENCH_TARGET_MS:-}"
REQUIRE_TARGET=false

usage() {
  cat <<'EOF'
Usage: scripts/perf-smoke.sh [--jmh] [--target-ms N] [--require-target]

Runs a quick interpreter-only `ssc bench --smoke` over the checked-in corpus.
With --jmh, also runs a single-iteration JMH smoke for one interpreter hot path.

Environment:
  SSC_CMD="/path/to/ssc"       command used for `ssc bench` (default: bin/ssc or ssc)
  SSC_BENCH_TARGET_MS=N        default --target-ms value
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --jmh) RUN_JMH=true; shift ;;
    --target-ms) TARGET_MS="$2"; shift 2 ;;
    --target-ms=*) TARGET_MS="${1#--target-ms=}"; shift ;;
    --require-target) REQUIRE_TARGET=true; shift ;;
    --help|-h) usage; exit 0 ;;
    *) echo "perf-smoke: unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if [[ -n "${SSC_CMD:-}" ]]; then
  read -r -a SSC_ARR <<<"$SSC_CMD"
elif [[ -x "$ROOT/bin/ssc" ]]; then
  SSC_ARR=("$ROOT/bin/ssc")
elif command -v ssc >/dev/null 2>&1; then
  SSC_ARR=(ssc)
else
  echo "perf-smoke: no ssc command found; run ./install.sh --dev or set SSC_CMD" >&2
  exit 1
fi

BENCH_ARGS=(bench --smoke --warmup 0 --reps 1)
if [[ -n "$TARGET_MS" ]]; then
  BENCH_ARGS+=(--target-ms "$TARGET_MS")
  if $REQUIRE_TARGET; then BENCH_ARGS+=(--require-target); fi
elif $REQUIRE_TARGET; then
  echo "perf-smoke: --require-target needs --target-ms N or SSC_BENCH_TARGET_MS" >&2
  exit 2
fi

echo "== ssc bench smoke =="
"${SSC_ARR[@]}" "${BENCH_ARGS[@]}"

if $RUN_JMH; then
  if ! command -v sbt >/dev/null 2>&1; then
    echo "perf-smoke: sbt not found; cannot run JMH smoke" >&2
    exit 1
  fi
  echo "== JMH smoke =="
  (cd "$ROOT" && sbt 'interpreterBench/Jmh/run -wi 1 -i 1 -f 1 -rff bench/jmh-smoke.json -rf json .*InterpreterBench.interp_arithLoop.*')
else
  echo "JMH smoke skipped; pass --jmh to run one short JMH benchmark."
fi
