#!/usr/bin/env bash
set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-sentinel-taxonomy-smoke.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

cat >"$tmp/native.tsv" <<'EOF'
file	front	sentinel	check	runtime	front_rc	check_rc	runtime_rc	detail
backend.ssc	OK	PRESENT	OK	NOT_RUN	0	0	-	CoreIR contains _err
server.ssc	OK	PRESENT	OK	NOT_RUN	0	0	-	CoreIR contains _err
tool.ssc	OK	PRESENT	OK	NOT_RUN	0	0	-	CoreIR contains _err
standard.ssc	OK	PRESENT	OK	NOT_RUN	0	0	-	CoreIR contains _err
clear.ssc	OK	clear	OK	OK	0	0	0	
EOF
cat >"$tmp/parity.tsv" <<'EOF'
file	category	vm_rc	bytecode_rc	detail
backend.ssc	skipped-backend	-	-	target
server.ssc	skipped-server	-	-	server
tool.ssc	both-fail	1	1	sentinel
standard.ssc	both-fail	1	1	sentinel
clear.ssc	identical	0	0	
EOF
cat >"$tmp/overrides.tsv" <<'EOF'
file	category	reason
tool.ssc	tools-backend	explicit compiler surface
EOF
cat >"$tmp/limits.tsv" <<'EOF'
category	max_count
backend	1
nondeterministic	0
server	1
tools-backend	1
standard-gap	1
total	4
EOF

"$ROOT/scripts/v21-sentinel-taxonomy" \
  --native-report "$tmp/native.tsv" --parity-report "$tmp/parity.tsv" \
  --manifest "$tmp/overrides.tsv" --limits "$tmp/limits.tsv" \
  --report "$tmp/report.tsv" >"$tmp/summary"
grep -F $'backend\t1' "$tmp/summary" >/dev/null
grep -F $'tools-backend\t1' "$tmp/summary" >/dev/null
grep -F $'standard.ssc\tstandard-gap\tboth-fail' "$tmp/report.tsv" >/dev/null

# The parity classifier must recognize non-parenthesized and named server
# entrypoints before trying either backend, plus explicit external-I/O rows.
true_bin=/usr/bin/true
[[ -x "$true_bin" ]] || true_bin=/bin/true
"$ROOT/scripts/bc-parity-sweep" --ssc "$true_bin" --only x402-server.ssc \
  --report "$tmp/server-parity.tsv" >/dev/null
grep -F $'x402-server.ssc\tskipped-server' "$tmp/server-parity.tsv" >/dev/null
"$ROOT/scripts/bc-parity-sweep" --ssc "$true_bin" --only graphql-client.ssc \
  --report "$tmp/nondet-parity.tsv" >/dev/null
grep -F $'graphql-client.ssc\tskipped-nondeterministic' "$tmp/nondet-parity.tsv" >/dev/null

# A tools exception may not silently disappear or move to another category.
grep -v '^tool.ssc' "$tmp/native.tsv" >"$tmp/native-stale.tsv"
if "$ROOT/scripts/v21-sentinel-taxonomy" \
    --native-report "$tmp/native-stale.tsv" --parity-report "$tmp/parity.tsv" \
    --manifest "$tmp/overrides.tsv" --limits "$tmp/limits.tsv" \
    --report "$tmp/stale.tsv" >/dev/null 2>&1; then
  echo 'stale tools manifest row was accepted' >&2
  exit 1
fi

# Existing categories may shrink, but growth requires an explicit baseline edit.
sed 's/standard-gap\t1/standard-gap\t0/' "$tmp/limits.tsv" >"$tmp/tight.tsv"
if "$ROOT/scripts/v21-sentinel-taxonomy" \
    --native-report "$tmp/native.tsv" --parity-report "$tmp/parity.tsv" \
    --manifest "$tmp/overrides.tsv" --limits "$tmp/tight.tsv" \
    --report "$tmp/growth.tsv" >/dev/null 2>&1; then
  echo 'category growth was accepted' >&2
  exit 1
fi

echo 'v2.1 sentinel taxonomy smoke: PASS'
