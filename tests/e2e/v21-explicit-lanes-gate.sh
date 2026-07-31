#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
manifest="$ROOT/tests/fixtures/v21-explicit-lanes/manifest.tsv"
report="$ROOT/target/v21-explicit-lanes.tsv"
if [[ ${1:-} == --report && -n ${2:-} ]]; then report=$2; shift 2; fi
[[ $# -eq 0 ]] || { echo 'usage: v21-explicit-lanes-gate.sh [--report FILE]' >&2; exit 2; }
[[ -f $manifest ]] || { echo "v21-explicit-lanes-gate: missing manifest: $manifest" >&2; exit 2; }

tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-explicit-lanes.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

# A bare `[[ $(…) == "$want" ]]` under `set -e` exits 1 printing NOTHING — no check
# name, no diff. That silence hid a real regression in this gate for days and forced
# a manual bisect. Every assertion goes through this helper instead.
expect_out() {
  local name=$1 want=$2 got=$3
  if [[ $got != "$want" ]]; then
    echo "v21-explicit-lanes-gate: FAILED check '$name'" >&2
    echo "--- want" >&2; printf '%s\n' "$want" >&2
    echo "--- got" >&2;  printf '%s\n' "$got"  >&2
    echo "--- diff (want vs got)" >&2
    diff <(printf '%s\n' "$want") <(printf '%s\n' "$got") >&2 || true
    exit 1
  fi
}

awk -F '\t' -v root="$ROOT" '
  BEGIN { OFS="\t" }
  NR == 1 {
    if ($0 != "file\tfamily\tlane\tregression\tdependency_tier") {
      print "v21-explicit-lanes-gate: invalid manifest header" > "/dev/stderr"; bad=1
    }
    next
  }
  {
    if (NF != 5 || $1 == "" || $2 == "" || $4 == "" || $5 == "") {
      print "v21-explicit-lanes-gate: malformed row: " $0 > "/dev/stderr"; bad=1; next
    }
    if (seen[$1]++) { print "v21-explicit-lanes-gate: duplicate member: " $1 > "/dev/stderr"; bad=1 }
    if ($3 != "provider-lane" && $3 != "target-lane") {
      print "v21-explicit-lanes-gate: invalid lane: " $1 "=" $3 > "/dev/stderr"; bad=1
    }
    if (system("test -f \"" root "/examples/" $1 "\"") != 0) {
      print "v21-explicit-lanes-gate: stale source: " $1 > "/dev/stderr"; bad=1
    }
    if ($4 !~ /^tests\/e2e\/v21-explicit-.*-smoke[.]sh$/ ||
        system("test -x \"" root "/" $4 "\"") != 0) {
      print "v21-explicit-lanes-gate: invalid regression: " $1 "=" $4 > "/dev/stderr"; bad=1
    }
    count[$3]++; family[$2]++; total++
  }
  END {
    # 15 -> 13 and provider 8 -> 6 on 2026-07-31: MCP moved into the standard graph, and this
    # manifest is about what is NOT in the default graph, so its two rows left. Its coverage did
    # not: tests/e2e/v21-standard-mcp-smoke.sh runs the same two examples through `bin/ssc`.
    if (total != 13) { print "v21-explicit-lanes-gate: exact member count " total " != 13" > "/dev/stderr"; bad=1 }
    if (count["provider-lane"] != 6 || count["target-lane"] != 7) {
      print "v21-explicit-lanes-gate: lane counts must be provider=6 target=7" > "/dev/stderr"; bad=1
    }
    split("pdf:3 graph:1 swift:1 nfc:1 quoted:2 scljet-vfs:2 wasm:2 x402:1", expected, " ")
    for (i in expected) { split(expected[i], pair, ":"); if (family[pair[1]] != pair[2]) { print "v21-explicit-lanes-gate: family drift: " pair[1] > "/dev/stderr"; bad=1 } }
    if (bad) exit 1
  }
' "$manifest"

tail -n +2 "$manifest" | cut -f4 | LC_ALL=C sort -u >"$tmp/regressions"
while IFS= read -r regression; do
  "$ROOT/$regression" </dev/null | tee "$tmp/$(basename "$regression").out"
done <"$tmp/regressions"

mkdir -p "$(dirname -- "$report")"
awk -F '\t' -v OFS='\t' '
  NR == 1 { print "file", "category", "result", "family", "regression", "dependency_tier"; next }
  { print $1, $3, "pass", $2, $4, $5 }
' "$manifest" >"$report"

provider=$(awk -F '\t' 'NR > 1 && $2 == "provider-lane" {n++} END {print n+0}' "$report")
target=$(awk -F '\t' 'NR > 1 && $2 == "target-lane" {n++} END {print n+0}' "$report")
# The SECOND freeze of the same numbers — the awk END block above is the first. Both must move
# together, and only this one is checked against the generated report rather than the manifest.
expect_out lane-counts $'provider=6\ntarget=7' "provider=$provider"$'\n'"target=$target"
echo "PASS v21-explicit-lanes-gate (13 exact rows: provider=$provider target=$target)"
echo "REPORT: $report"
