#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
FIXTURES="$ROOT/tests/fixtures/v21-native"
report="$ROOT/target/v21-negative-toolchain-release.tsv"
if [[ ${1:-} == --report && -n ${2:-} ]]; then report=$2; shift 2; fi

# ── MAP/REDUCE, which is how this gate gets sharded (BUGS `negtc-gate-shard-reduce`) ────────────
#
# The two sweeps cost 58.1 min of a 75.6-min CI job — 77 % — and both support `--shard i/N`. The gate
# still cannot simply BE sharded: after the sweeps it runs `v21-sentinel-taxonomy` and
# `v21-runtime-taxonomy-freeze`, which compare against FROZEN WHOLE-CORPUS counts by exact equality
# ("freeze drift: <metric>=N expected M"). A partial report drifts every metric, so a naively sharded
# run is red no matter how healthy the tree is. That is why `--shard` alone was refused here.
#
# So the work splits instead:
#
#   MAP     --sweeps-only --shard i/N --native-out A --parity-out B
#           runs ONLY the two sweeps over slice i and writes its partial TSVs. No taxonomy, no
#           freeze, no assertions — nothing that needs the whole corpus.
#
#   REDUCE  --reduce --native-in A --parity-in B
#           skips the sweeps entirely and runs everything downstream ONCE on already-merged reports:
#           taxonomy, freeze, the negative-toolchain assertions, and the metric report.
#
# Merge the shards' TSVs with `scripts/negtc-merge-reports` (one header, rows sorted for
# determinism). `tests/e2e/negtc-mapreduce-gate.sh` proves the property that makes this legitimate:
# map+merge+reduce over N shards produces byte-identical reports to one unsharded run.
mode=full
shard=""
native_out=""; parity_out=""; native_in=""; parity_in=""
while [[ $# -gt 0 ]]; do
  case ${1:-} in
    --sweeps-only) mode=sweeps; shift ;;
    --reduce)      mode=reduce; shift ;;
    --shard)       shard=${2:?--shard needs i/N}; shift 2 ;;
    --native-out)  native_out=${2:?}; shift 2 ;;
    --parity-out)  parity_out=${2:?}; shift 2 ;;
    --native-in)   native_in=${2:?}; shift 2 ;;
    --parity-in)   parity_in=${2:?}; shift 2 ;;
    *) break ;;
  esac
done

if [[ $mode == sweeps ]]; then
  [[ -n $shard ]] || { echo 'v21-negative-toolchain-release-gate: --sweeps-only needs --shard i/N' >&2; exit 2; }
  [[ -n $native_out && -n $parity_out ]] || {
    echo 'v21-negative-toolchain-release-gate: --sweeps-only needs --native-out and --parity-out' >&2; exit 2; }
fi
if [[ $mode == reduce ]]; then
  [[ -n $native_in && -n $parity_in ]] || {
    echo 'v21-negative-toolchain-release-gate: --reduce needs --native-in and --parity-in' >&2; exit 2; }
  for f in "$native_in" "$parity_in"; do
    [[ -s $f ]] || { echo "v21-negative-toolchain-release-gate: --reduce input missing or empty: $f" >&2; exit 2; }
  done
fi

[[ $# -eq 0 ]] || { echo 'usage: v21-negative-toolchain-release-gate.sh [--report FILE]' >&2; exit 2; }
[[ -x $ROOT/bin/ssc && -d $ROOT/bin/lib/standard ]] || {
  echo 'v21-negative-toolchain-release-gate: run scripts/sbtc "installBin" first' >&2
  exit 2
}

sandbox=$(mktemp -d "${TMPDIR:-/tmp}/v21-negative-toolchain.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
slim="$sandbox/slim"
standard="$slim/bin/lib/standard"
toolbin="$sandbox/toolbin"
mkdir -p "$slim/bin/lib" "$toolbin" "$sandbox/scan-classes"
cp -R "$ROOT/bin/lib/standard" "$standard"
cp "$ROOT/bin/ssc" "$slim/bin/ssc"
cp "$ROOT/bin/ssc-standard" "$slim/bin/ssc-standard"
chmod +x "$slim/bin/ssc" "$slim/bin/ssc-standard"

[[ ! -e $slim/bin/ssc-tools && ! -e $slim/bin/lib/ssc.jar && \
   ! -e $slim/bin/lib/jars && ! -e $slim/bin/lib/compiler ]]
for launcher in "$slim/bin/ssc" "$slim/bin/ssc-standard"; do
  grep -F 'lib/standard/jars/*' "$launcher" >/dev/null
  grep -F 'scalascript.cli.StandardMain' "$launcher" >/dev/null
  ! grep -F 'scalascript.cli.ssc' "$launcher" >/dev/null
done

compiler_jars=$(find "$slim" -type f -name '*.jar' | grep -Eic 'scala3-compiler|compiler-driver|scala-asm|tasty-core' || true)
scalameta_jars=$(find "$slim" -type f -name '*.jar' | grep -Eic 'scalameta|trees_' || true)
[[ $compiler_jars -eq 0 && $scalameta_jars -eq 0 ]]

jar_cmd=$(command -v jar)
jdeps_cmd=$(command -v jdeps)
while IFS= read -r jar_file; do
  (cd "$sandbox/scan-classes" && "$jar_cmd" xf "$jar_file")
done < <(find "$standard" -type f -name '*.jar' | LC_ALL=C sort)
rm -f "$sandbox/scan-classes/module-info.class" "$sandbox/scan-classes/META-INF/MANIFEST.MF"
find "$sandbox/scan-classes/META-INF/versions" -name module-info.class -delete 2>/dev/null || true
find "$sandbox/scan-classes/META-INF" -type f \
  \( -name '*.SF' -o -name '*.RSA' -o -name '*.DSA' -o -name '*.EC' \) -delete 2>/dev/null || true
"$jar_cmd" --create --file "$sandbox/standard-scan.jar" --no-manifest -C "$sandbox/scan-classes" .
"$jdeps_cmd" --multi-release base --ignore-missing-deps --recursive -verbose:class \
  "$sandbox/standard-scan.jar" >"$sandbox/standard.jdeps"
runtime_modules=$("$jdeps_cmd" --multi-release base --ignore-missing-deps \
  --print-module-deps "$sandbox/standard-scan.jar" | tr -d '\r\n')
# JCA provider discovery is reflective: jdeps cannot see Ed25519's
# jdk.crypto.ec edge, but it is part of the standard crypto runtime contract.
case ",$runtime_modules," in
  *,jdk.crypto.ec,*) ;;
  *) runtime_modules="$runtime_modules,jdk.crypto.ec" ;;
esac
forbidden_refs='scala[.]meta|scala[.]tools|dotty[.]tools|javax[.]tools|java[.]compiler|jdk[.]compiler|ssc[.]bridge|scalascript[.](ast|frontend|interpreter)'
if [[ -z $runtime_modules ]] || grep -Ei "$forbidden_refs" "$sandbox/standard.jdeps" >/dev/null; then
  echo 'v21-negative-toolchain-release-gate: forbidden standard reference/module' >&2
  exit 1
fi
case ",$runtime_modules," in *,java.compiler,*|*,jdk.compiler,*) exit 1 ;; esac

real_java=$(command -v java)
for module in java.compiler jdk.compiler; do
  if "$real_java" --limit-modules "$runtime_modules" --describe-module "$module" \
      >"$sandbox/$module.out" 2>"$sandbox/$module.err"; then
    echo "v21-negative-toolchain-release-gate: compiler module visible: $module" >&2
    exit 1
  fi
done

for tool in awk bash dirname git find mkdir mktemp rm head tr basename grep cat cut python3 cmp; do
  target=$(command -v "$tool")
  [[ -n $target ]] || { echo "missing host utility: $tool" >&2; exit 2; }
  ln -s "$target" "$toolbin/$tool"
done
printf '#!/bin/sh\nexec "%s" --limit-modules "%s" "$@"\n' \
  "$real_java" "$runtime_modules" >"$toolbin/java"
chmod +x "$toolbin/java"
for compiler in scala-cli scalac javac; do
  ! PATH="$toolbin" command -v "$compiler" >/dev/null 2>&1
done

native_report="$sandbox/native-front.tsv"
parity_report="$sandbox/parity.tsv"
sentinel_report="$sandbox/sentinel.tsv"
runtime_report="$sandbox/runtime.tsv"
runtime_freeze_report="$sandbox/runtime-freeze.tsv"
# Generous per-case timeouts: the sanitized env has no warm compiler server and
# CI hosts are contended, so tight defaults (12s/45s) turn slow-under-load runs
# into spurious timeouts. Front/check feed the frozen frontend.ok/checker.ok, and
# bc-parity classifies a residual timeout as a non-fatal skip — but a generous
# limit keeps real parity coverage high. Override, do not rely on script defaults.
# In `--reduce` the sweeps are skipped and the already-merged reports stand in for them.
if [[ $mode == reduce ]]; then
  cp "$native_in" "$native_report"
  cp "$parity_in"  "$parity_report"
else
  shard_args=()
  [[ -n $shard ]] && shard_args=(--shard "$shard")
  PATH="$toolbin" JAVA="$toolbin/java" SSC_NO_CDS=1 \
    NATIVE_FRONT_STANDARD_DIR="$standard" NATIVE_FRONT_TIMEOUT="${NATIVE_FRONT_TIMEOUT:-20}" \
    "$ROOT/scripts/native-front-corpus" --standard --ssc "$slim/bin/ssc" \
    "${shard_args[@]}" --report "$native_report"
  # `--strict` is deliberately NOT passed in map mode: a shard sees a fraction of the corpus, so its
  # own strict verdict would be about a slice. The reduce step applies strictness to the merged whole,
  # which is the only place it means anything.
  strict_args=(--strict)
  [[ $mode == sweeps ]] && strict_args=()
  PATH="$toolbin" SSC_NO_CDS=1 SSC="$slim/bin/ssc" BC_PARITY_TIMEOUT="${BC_PARITY_TIMEOUT:-90}" \
    "$ROOT/scripts/bc-parity-sweep" "${strict_args[@]}" "${shard_args[@]}" --report "$parity_report"
fi

# MAP mode stops here: everything below needs the whole corpus.
if [[ $mode == sweeps ]]; then
  cp "$native_report" "$native_out"
  cp "$parity_report" "$parity_out"
  echo "v21-negative-toolchain-release-gate: shard $shard sweeps done -> $native_out, $parity_out"
  exit 0
fi
"$ROOT/scripts/v21-sentinel-taxonomy" --native-report "$native_report" \
  --parity-report "$parity_report" --report "$sentinel_report"
"$ROOT/scripts/v21-runtime-taxonomy" --parity-report "$parity_report" \
  --sentinel-report "$sentinel_report" --report "$runtime_report"
"$ROOT/scripts/v21-runtime-taxonomy-freeze" --taxonomy-report "$runtime_report" \
  --report "$runtime_freeze_report" >/dev/null

run_negative() { PATH="$toolbin" SSC_NO_CDS=1 "$slim/bin/ssc" "$@"; }

# Every check prints its diff (AGENTS.md, "measurement apparatus must COMPARE, never PRE-JUDGE").
#
# These assertions used to be bare `[[ $(…) == … ]]` under `set -e`, which aborts the script with NO
# OUTPUT AT ALL — the gate simply exited 1 after a silent gap. That is a documented, repeat offender
# in this repo (BUGS `ci-testtimeout`; AGENTS.md lists it among the apparatus failures that cost
# days), and it is worse here than anywhere: the whole section is unreachable while an earlier step
# fails, so it can sit broken and unnoticed, then surface as a wordless red the day the earlier step
# is fixed. That is exactly what happened on 2026-07-28.
expect_run() { # expect_run <label> <expected> <cmd…>
  local label=$1 want=$2 got rc errfile; shift 2
  errfile=$(mktemp)
  # STDOUT ONLY for the comparison — stderr is captured separately, for the message.
  #
  # The first version of this helper compared `$("$@" 2>&1)`, which QUIETLY CHANGED WHAT THESE
  # ASSERTIONS ASSERT: the bare `[[ $(cmd) == … ]]` it replaced captured stdout alone. The job was to
  # make a silent check loud, not to make it stricter. It went red on the very next run —
  #   http-server-provider (vm): expected $'203\npong:/ping'
  #                              got      $'ScalaScript web · http://…\nCtrl+C to stop.\n203\npong:/ping'
  # — the program had exited 0 with exactly the right stdout, and the server's startup banner on
  # stderr was the whole difference. Diagnostics must observe more than the assertion does, never
  # less and never differently.
  got=$("$@" 2>"$errfile") && rc=0 || rc=$?
  if [[ $got != "$want" ]]; then
    printf 'v21-negative-toolchain-release-gate: %s MISMATCH (exit %s)\n  expected=%q\n  got=%q\n  stderr=%q\n' \
      "$label" "$rc" "$want" "$got" "$(head -c 2000 "$errfile")" >&2
    rm -f "$errfile"
    exit 1
  fi
  rm -f "$errfile"
}
json_expected=$'Ada\n2\ntrue\n1000.01\ntrue\n{"payload":[1,2]}\n[1,2,3]\n{"name":"A\\"B","on":true}'
expect_run "json-provider (vm)" "$json_expected" run_negative run "$FIXTURES/json-provider.ssc"
expect_run "json-provider (bytecode)" "$json_expected" run_negative run --bytecode "$FIXTURES/json-provider.ssc"
expect_run "sql-provider (vm)" $'1\n7\nAda\ntrue' run_negative run "$FIXTURES/sql-provider.ssc"
expect_run "state-effect-provider (bytecode)" $'17\n20\n2\n101\n101\n2' run_negative run --bytecode "$FIXTURES/state-effect-provider.ssc"
crypto_expected=$'signature valid: true\ntampered valid: false\nmalformed valid: false\nsignature matches vector: true\nround-trip valid: true'
expect_run "crypto-verify-demo (vm)" "$crypto_expected" run_negative run "$ROOT/examples/crypto-verify-demo.ssc"
expect_run "crypto-verify-demo (bytecode)" "$crypto_expected" run_negative run --bytecode "$ROOT/examples/crypto-verify-demo.ssc"
provider_smoke=pass
port=$((32000 + ($$ % 10000)))
expect_run "http-server-provider (vm)" $'203\npong:/ping' run_negative run "$FIXTURES/http-server-provider.ssc" -- "$port"
expect_run "http-server-provider (bytecode)" $'203\npong:/ping' run_negative run --bytecode "$FIXTURES/http-server-provider.ssc" -- "$((port + 1))"
server_smoke=pass

frontend_total=$(awk -F '\t' 'NR > 1 {n++} END {print n+0}' "$native_report")
frontend_ok=$(awk -F '\t' 'NR > 1 && $2 == "OK" {n++} END {print n+0}' "$native_report")
frontend_noncode=$(awk -F '\t' 'NR > 1 && $2 == "NON_CODE" {n++} END {print n+0}' "$native_report")
checker_ok=$(awk -F '\t' 'NR > 1 && $4 == "OK" {n++} END {print n+0}' "$native_report")
parity_identical=$(awk -F '\t' 'NR > 1 && $2 == "identical" {n++} END {print n+0}' "$parity_report")
parity_both=$(awk -F '\t' 'NR > 1 && $2 == "both-fail" {n++} END {print n+0}' "$parity_report")
parity_skipped=$(awk -F '\t' 'NR > 1 && $2 ~ /^skipped-/ {n++} END {print n+0}' "$parity_report")
parity_mismatch=$(awk -F '\t' 'NR > 1 && $2 == "mismatch" {n++} END {print n+0}' "$parity_report")
parity_one_sided=$(awk -F '\t' 'NR > 1 && ($2 == "vm-error" || $2 == "bytecode-error") {n++} END {print n+0}' "$parity_report")
parity_provider_lane=$(awk -F '\t' 'NR > 1 && $2 == "provider-lane" {n++} END {print n+0}' "$parity_report")
parity_target_lane=$(awk -F '\t' 'NR > 1 && $2 == "target-lane" {n++} END {print n+0}' "$parity_report")
parity_delegated=$((parity_provider_lane + parity_target_lane))
runtime_blockers=$(awk -F '\t' '$1 == "blocker-total" {print $2}' "$runtime_freeze_report")

mkdir -p "$(dirname "$report")"
{
  printf 'metric\tvalue\n'
  printf 'runtime.modules\t%s\n' "$runtime_modules"
  printf 'default.launcher\tstandard\ntools.present\tfalse\n'
  printf 'compiler.jars\t%s\nscalameta.jars\t%s\n' "$compiler_jars" "$scalameta_jars"
  printf 'scala-cli.available\tfalse\nscalac.available\tfalse\njavac.available\tfalse\n'
  printf 'java.compiler.available\tfalse\njdk.compiler.available\tfalse\nforbidden.references\t0\n'
  printf 'frontend.total\t%s\nfrontend.ok\t%s\nfrontend.non-code\t%s\nchecker.ok\t%s\n' "$frontend_total" "$frontend_ok" "$frontend_noncode" "$checker_ok"
  printf 'parity.identical\t%s\nparity.both-fail\t%s\nparity.skipped\t%s\nparity.delegated\t%s\nparity.provider-lane\t%s\nparity.target-lane\t%s\nparity.mismatch\t%s\nparity.one-sided\t%s\n' "$parity_identical" "$parity_both" "$parity_skipped" "$parity_delegated" "$parity_provider_lane" "$parity_target_lane" "$parity_mismatch" "$parity_one_sided"
  printf 'runtime.blockers\t%s\nprovider.smoke\t%s\nserver.smoke\t%s\nrelease.ready\ttrue\n' "$runtime_blockers" "$provider_smoke" "$server_smoke"
} >"$report"
"$ROOT/scripts/v21-negative-toolchain-freeze" "$report"
cat "$report"
echo 'PASS v21-negative-toolchain-release-gate'
