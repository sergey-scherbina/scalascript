#!/usr/bin/env bash
# Target-neutral control-interoperability catalog validator and lane runner.
set -uo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$DIR/../.." && pwd)"
VECTORS="$DIR/vectors.tsv"
LANES="$DIR/lanes.tsv"
PROBES="$DIR/probes"
EXPECTED="$DIR/expected"
PENDING="$DIR/pending"
SSC="${SSC:-$ROOT/bin/ssc}"
tmp="$(mktemp -d "${TMPDIR:-/tmp}/interop-conformance.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

validation_errors=0

usage() {
  cat <<'EOF'
Usage:
  tests/interop-conformance/run.sh
  tests/interop-conformance/run.sh --validate
  tests/interop-conformance/run.sh --list
  tests/interop-conformance/run.sh --lane <lane>
  tests/interop-conformance/run.sh --all-installed

The default gate validates both catalogs and runs every ready process lane.
Set SSC=/path/to/ssc to select the standard launcher used by portable lanes.
EOF
}

validation_error() {
  echo "catalog error: $*" >&2
  validation_errors=$((validation_errors + 1))
}

require_file() {
  [ -f "$1" ] || validation_error "missing required file: $1"
}

capabilities_are_subset() {
  have="$1"
  need="$2"
  old_ifs="$IFS"
  IFS=,
  for capability in $need; do
    case ",$have," in
      *",$capability,"*) ;;
      *) IFS="$old_ifs"; return 1 ;;
    esac
  done
  IFS="$old_ifs"
  return 0
}

missing_capabilities() {
  have="$1"
  need="$2"
  missing=""
  old_ifs="$IFS"
  IFS=,
  for capability in $need; do
    case ",$have," in
      *",$capability,"*) ;;
      *) missing="${missing}${missing:+,}${capability}" ;;
    esac
  done
  IFS="$old_ifs"
  printf '%s' "$missing"
}

vector_field() {
  key="$1"
  column="$2"
  awk -F '\t' -v key="$key" -v column="$column" \
    'NR > 1 && ($1 "-" $2) == key { print $(column); exit }' "$VECTORS"
}

lane_field() {
  lane="$1"
  column="$2"
  awk -F '\t' -v lane="$lane" -v column="$column" \
    'NR > 1 && $1 == lane { print $(column); exit }' "$LANES"
}

metadata_value() {
  file="$1"
  field="$2"
  sed -n "s/^${field}:[[:space:]]*//p" "$file" | head -1
}

validate_vector_catalog() {
  expected_header="$(printf 'id\tslug\tlaw\tcapabilities\tphase\texpectedExit\texpectedStream\toracle')"
  actual_header="$(head -1 "$VECTORS" 2>/dev/null)"
  [ "$actual_header" = "$expected_header" ] || \
    validation_error "vectors.tsv header must be exactly: $expected_header"

  if ! awk -F '\t' '
    function bad(message) {
      printf "vectors.tsv:%d: %s\n", NR, message > "/dev/stderr"
      errors++
    }
    function check_caps(value,    n, parts, i, previous) {
      if (value == "") { bad("capabilities must not be empty"); return }
      n = split(value, parts, ",")
      previous = ""
      for (i = 1; i <= n; i++) {
        if (parts[i] !~ /^[a-z][a-z0-9-]*$/)
          bad("invalid capability: " parts[i])
        if (previous != "" && parts[i] <= previous)
          bad("capabilities must be sorted and unique: " value)
        previous = parts[i]
      }
    }
    NR == 1 { next }
    {
      rows++
      if (NF != 8) { bad("expected 8 tab-separated fields, found " NF); next }
      if (index($0, "\r") != 0) bad("carriage returns are forbidden")
      if ($1 !~ /^[0-9][0-9]$/) bad("id must be a two-digit decimal")
      if (($1 + 0) <= previousId) bad("ids must be strictly increasing")
      previousId = $1 + 0
      if (seenId[$1]++) bad("duplicate id: " $1)
      if ($2 !~ /^[a-z0-9]+(-[a-z0-9]+)*$/) bad("invalid slug: " $2)
      if (seenSlug[$2]++) bad("duplicate slug: " $2)
      if ($3 == "") bad("law must not be empty")
      check_caps($4)
      if ($5 != "specified" && $5 != "pending-codec" && $5 != "pending-spec")
        bad("invalid phase: " $5)
      if ($6 != "zero" && $6 != "nonzero" && $6 != "pending")
        bad("invalid expectedExit: " $6)
      if ($7 != "stdout" && $7 != "stderr" && $7 != "structured" && $7 != "pending")
        bad("invalid expectedStream: " $7)
      if ($8 == "") bad("oracle must not be empty")
      if ($5 == "specified" && $6 == "pending") bad("specified vector needs a concrete exit contract")
      if ($5 == "specified" && $7 == "pending") bad("specified vector needs a concrete stream contract")
      if ($5 != "specified" && ($6 != "pending" || $7 != "pending"))
        bad("pending vector must use pending exit and stream")
    }
    END {
      if (rows == 0) { print "vectors.tsv: no vector records" > "/dev/stderr"; errors++ }
      exit(errors == 0 ? 0 : 1)
    }
  ' "$VECTORS"; then
    validation_errors=$((validation_errors + 1))
  fi
}

validate_lane_catalog() {
  expected_header="$(printf 'lane\tadapter\tstatus\tcapabilities\treason')"
  actual_header="$(head -1 "$LANES" 2>/dev/null)"
  [ "$actual_header" = "$expected_header" ] || \
    validation_error "lanes.tsv header must be exactly: $expected_header"

  if ! awk -F '\t' '
    function bad(message) {
      printf "lanes.tsv:%d: %s\n", NR, message > "/dev/stderr"
      errors++
    }
    function check_caps(value, allowEmpty,    n, parts, i, previous) {
      if (value == "") {
        if (!allowEmpty) bad("ready/optional lane capabilities must not be empty")
        return
      }
      n = split(value, parts, ",")
      previous = ""
      for (i = 1; i <= n; i++) {
        if (parts[i] !~ /^[a-z][a-z0-9-]*$/)
          bad("invalid capability: " parts[i])
        if (previous != "" && parts[i] <= previous)
          bad("capabilities must be sorted and unique: " value)
        previous = parts[i]
      }
    }
    BEGIN {
      required = "portable-vm portable-asm scala-explicit scala-direct jvm-generated js-generated rust-generated wasm-generated swift-generated"
      split(required, requiredLane, " ")
    }
    NR == 1 { next }
    {
      rows++
      if (NF != 5) { bad("expected 5 tab-separated fields, found " NF); next }
      if (index($0, "\r") != 0) bad("carriage returns are forbidden")
      if ($1 !~ /^[a-z0-9]+(-[a-z0-9]+)*$/) bad("invalid lane id: " $1)
      if (seenLane[$1]++) bad("duplicate lane: " $1)
      if ($2 != "ssc-vm" && $2 != "ssc-asm" && $2 != "scala3-control-test" && $2 != "none")
        bad("unknown adapter: " $2)
      if ($3 != "ready" && $3 != "optional" && $3 != "pending")
        bad("invalid status: " $3)
      check_caps($4, $3 == "pending")
      if ($3 == "pending" && $2 != "none") bad("pending lane adapter must be none")
      if ($3 != "pending" && $2 == "none") bad("ready/optional lane needs an adapter")
      if ($5 == "") bad("reason must not be empty")
    }
    END {
      for (i in requiredLane)
        if (!seenLane[requiredLane[i]]) {
          print "lanes.tsv: missing mandatory lane: " requiredLane[i] > "/dev/stderr"
          errors++
        }
      if (rows == 0) { print "lanes.tsv: no lane records" > "/dev/stderr"; errors++ }
      exit(errors == 0 ? 0 : 1)
    }
  ' "$LANES"; then
    validation_errors=$((validation_errors + 1))
  fi
}

process_lane_accepts_vector() {
  required_caps="$1"
  while IFS= read -r lane; do
    adapter="$(lane_field "$lane" 2)"
    status="$(lane_field "$lane" 3)"
    lane_caps="$(lane_field "$lane" 4)"
    case "$adapter:$status" in
      ssc-vm:ready|ssc-vm:optional|ssc-asm:ready|ssc-asm:optional)
        capabilities_are_subset "$lane_caps" "$required_caps" && return 0
        ;;
    esac
  done < "$tmp/lane-keys"
  return 1
}

validate_files_and_adapters() {
  awk -F '\t' 'NR > 1 && NF == 8 { print $1 "-" $2 }' "$VECTORS" > "$tmp/vector-keys"
  awk -F '\t' 'NR > 1 && NF == 5 { print $1 }' "$LANES" > "$tmp/lane-keys"

  while IFS= read -r key; do
    [ -n "$key" ] || continue
    phase="$(vector_field "$key" 5)"
    stream="$(vector_field "$key" 7)"
    caps="$(vector_field "$key" 4)"
    probe="$PROBES/$key.ssc"
    expected="$EXPECTED/$key.txt"
    pending="$PENDING/$key.pending"

    case "$phase" in
      specified)
        [ ! -e "$pending" ] || validation_error "$key is specified but still has a pending record"
        if [ "$stream" = "stdout" ] || [ "$stream" = "stderr" ]; then
          if process_lane_accepts_vector "$caps"; then
            [ -f "$probe" ] || validation_error "$key is eligible on a process lane but has no probe"
            [ -f "$expected" ] || validation_error "$key is eligible on a process lane but has no expected bytes"
          fi
        fi
        ;;
      pending-codec|pending-spec)
        [ -f "$pending" ] || validation_error "$key has phase $phase but no pending record"
        [ ! -e "$probe" ] || validation_error "$key is pending but has a runnable probe"
        [ ! -e "$expected" ] || validation_error "$key is pending but has expected process bytes"
        ;;
    esac
  done < "$tmp/vector-keys"

  for probe in "$PROBES"/*.ssc; do
    [ -e "$probe" ] || continue
    key="$(basename "$probe" .ssc)"
    if ! grep -Fxq "$key" "$tmp/vector-keys"; then
      validation_error "orphan probe: $probe"
      continue
    fi
    id="${key%%-*}"
    slug="${key#*-}"
    phase="$(vector_field "$key" 5)"
    expected_exit="$(vector_field "$key" 6)"
    expected_stream="$(vector_field "$key" 7)"
    caps="$(vector_field "$key" 4)"
    [ "$phase" = "specified" ] || validation_error "$probe belongs to non-specified vector $key"
    process_lane_accepts_vector "$caps" || validation_error "$probe is orphaned: no process lane declares its capabilities"
    [ "$(metadata_value "$probe" name)" = "interop-$id-$slug" ] || validation_error "$probe name metadata disagrees with $key"
    [ "$(metadata_value "$probe" axis)" = "$slug" ] || validation_error "$probe axis metadata disagrees with $key"
    probe_status="$(metadata_value "$probe" status)"
    case "$probe_status" in
      measurable-now|measurable-now-intra-lane) ;;
      *) validation_error "$probe status must identify a measurable-now adapter" ;;
    esac
    probe_exit="$(metadata_value "$probe" expected-exit)"
    probe_stream="$(metadata_value "$probe" expected-stream)"
    probe_exit="${probe_exit:-zero}"
    probe_stream="${probe_stream:-stdout}"
    [ "$probe_exit" = "$expected_exit" ] || validation_error "$probe expected-exit disagrees with vectors.tsv for $key"
    [ "$probe_stream" = "$expected_stream" ] || validation_error "$probe expected-stream disagrees with vectors.tsv for $key"
  done

  for expected in "$EXPECTED"/*.txt; do
    [ -e "$expected" ] || continue
    key="$(basename "$expected" .txt)"
    if ! grep -Fxq "$key" "$tmp/vector-keys"; then
      validation_error "orphan expected output: $expected"
      continue
    fi
    [ -f "$PROBES/$key.ssc" ] || validation_error "$expected has no matching probe: $expected"
    stream="$(vector_field "$key" 7)"
    [ "$stream" = "stdout" ] || [ "$stream" = "stderr" ] || \
      validation_error "$expected belongs to non-process stream $stream: $expected"
  done

  for pending in "$PENDING"/*.pending; do
    [ -e "$pending" ] || continue
    key="$(basename "$pending" .pending)"
    if ! grep -Fxq "$key" "$tmp/vector-keys"; then
      validation_error "orphan pending record: $pending"
      continue
    fi
    slug="${key#*-}"
    phase="$(vector_field "$key" 5)"
    [ "$phase" != "specified" ] || validation_error "$pending belongs to specified vector $key"
    [ "$(metadata_value "$pending" axis)" = "$slug" ] || validation_error "$pending axis metadata disagrees with $key"
    [ "$(metadata_value "$pending" status)" = "$phase" ] || validation_error "$pending status metadata disagrees with $key"
    [ -n "$(metadata_value "$pending" needs)" ] || validation_error "$pending needs metadata is empty for $key"
    [ -n "$(metadata_value "$pending" expected)" ] || validation_error "$pending expected metadata is empty for $key"
  done
}

validate_catalogs() {
  require_file "$VECTORS"
  require_file "$LANES"
  [ "$validation_errors" -eq 0 ] || return 1
  validate_vector_catalog
  validate_lane_catalog
  [ "$validation_errors" -eq 0 ] || return 1
  validate_files_and_adapters
  [ "$validation_errors" -eq 0 ]
}

print_matrix() {
  printf '%-18s %-34s %-12s %s\n' "LANE" "VECTOR" "STATUS" "DETAIL"
  printf '%-18s %-34s %-12s %s\n' "----" "------" "------" "------"
  while IFS= read -r lane; do
    lane_status="$(lane_field "$lane" 3)"
    lane_caps="$(lane_field "$lane" 4)"
    lane_reason="$(lane_field "$lane" 5)"
    adapter="$(lane_field "$lane" 2)"
    while IFS= read -r key; do
      phase="$(vector_field "$key" 5)"
      required_caps="$(vector_field "$key" 4)"
      stream="$(vector_field "$key" 7)"
      if [ "$phase" != "specified" ]; then
        status="PENDING"; detail="$phase"
      elif [ "$lane_status" = "pending" ]; then
        status="PENDING"; detail="$lane_reason"
      elif ! capabilities_are_subset "$lane_caps" "$required_caps"; then
        status="UNSUPPORTED"; detail="missing: $(missing_capabilities "$lane_caps" "$required_caps")"
      elif { [ "$adapter" = "ssc-vm" ] || [ "$adapter" = "ssc-asm" ]; } && [ "$stream" = "structured" ]; then
        status="UNSUPPORTED"; detail="structured host result"
      else
        status="READY"; detail="$adapter"
      fi
      printf '%-18s %-34s %-12s %s\n' "$lane" "$key" "$status" "$detail"
    done < "$tmp/vector-keys"
  done < "$tmp/lane-keys"
}

summarize_file() {
  LC_ALL=C tr '\n' ' ' < "$1" | head -c 80
}

run_process_lane() {
  lane="$1"
  adapter="$2"
  lane_caps="$3"
  if [ ! -x "$SSC" ] && ! command -v "$SSC" >/dev/null 2>&1; then
    echo "LANE $lane UNAVAILABLE: ssc binary not found at '$SSC'" >&2
    return 3
  fi

  timeout_bin=""
  if command -v timeout >/dev/null 2>&1; then timeout_bin="$(command -v timeout)"
  elif command -v gtimeout >/dev/null 2>&1; then timeout_bin="$(command -v gtimeout)"
  fi

  pass=0
  fail=0
  printf '\n%-18s %-34s %-10s %s\n' "LANE" "VECTOR" "STATUS" "RESULT"
  printf '%-18s %-34s %-10s %s\n' "----" "------" "------" "------"
  while IFS= read -r key; do
    phase="$(vector_field "$key" 5)"
    required_caps="$(vector_field "$key" 4)"
    stream="$(vector_field "$key" 7)"
    [ "$phase" = "specified" ] || continue
    capabilities_are_subset "$lane_caps" "$required_caps" || continue
    { [ "$stream" = "stdout" ] || [ "$stream" = "stderr" ]; } || continue

    probe="$PROBES/$key.ssc"
    expected="$EXPECTED/$key.txt"
    expected_exit="$(vector_field "$key" 6)"
    out="$tmp/$lane-$key.out"
    err="$tmp/$lane-$key.err"
    command=("$SSC" run)
    [ "$adapter" = "ssc-asm" ] && command+=(--bytecode)
    command+=("$probe")
    if [ -n "$timeout_bin" ]; then
      "$timeout_bin" 60 "${command[@]}" >"$out" 2>"$err"
    else
      "${command[@]}" >"$out" 2>"$err"
    fi
    rc=$?

    exit_ok=false
    case "$expected_exit" in
      zero) [ "$rc" -eq 0 ] && exit_ok=true ;;
      nonzero) [ "$rc" -ne 0 ] && exit_ok=true ;;
    esac
    if [ "$stream" = "stdout" ]; then got="$out"; unexpected="$err"
    else got="$err"; unexpected="$out"
    fi

    if $exit_ok && cmp -s "$got" "$expected" && [ ! -s "$unexpected" ]; then
      printf '%-18s %-34s %-10s %s\n' "$lane" "$key" "PASS" "$(summarize_file "$got")"
      pass=$((pass + 1))
    else
      printf '%-18s %-34s %-10s rc=%s want-exit=%s stdout=[%s] stderr=[%s]\n' \
        "$lane" "$key" "FAIL" "$rc" "$expected_exit" \
        "$(summarize_file "$out")" "$(summarize_file "$err")"
      fail=$((fail + 1))
    fi
  done < "$tmp/vector-keys"

  echo "$lane: $pass PASS, $fail FAIL"
  [ "$fail" -eq 0 ]
}

run_scala_lane() {
  lane="$1"
  if [ ! -x "$ROOT/scripts/sbtc" ]; then
    echo "LANE $lane UNAVAILABLE: scripts/sbtc is missing" >&2
    return 3
  fi
  echo "LANE $lane: scala3ControlApi semantic-vector suite"
  (cd "$ROOT" && scripts/sbtc \
    "scala3ControlApi/testOnly scalascript.controlapi.SemanticVectorConformanceTest")
}

run_lane() {
  lane="$1"
  grep -Fxq "$lane" "$tmp/lane-keys" || { echo "error: unknown lane '$lane'" >&2; return 2; }
  adapter="$(lane_field "$lane" 2)"
  status="$(lane_field "$lane" 3)"
  lane_caps="$(lane_field "$lane" 4)"
  reason="$(lane_field "$lane" 5)"
  if [ "$status" = "pending" ]; then
    echo "LANE $lane PENDING: $reason"
    return 3
  fi
  case "$adapter" in
    ssc-vm|ssc-asm) run_process_lane "$lane" "$adapter" "$lane_caps" ;;
    scala3-control-test) run_scala_lane "$lane" ;;
    *) echo "LANE $lane UNAVAILABLE: adapter '$adapter' is not installed" >&2; return 3 ;;
  esac
}

print_non_process_lanes() {
  echo
  while IFS= read -r lane; do
    adapter="$(lane_field "$lane" 2)"
    status="$(lane_field "$lane" 3)"
    reason="$(lane_field "$lane" 5)"
    case "$adapter" in
      ssc-vm|ssc-asm) ;;
      *) printf 'LANE %-18s %-10s %s\n' "$lane" "$(echo "$status" | tr '[:lower:]' '[:upper:]')" "$reason" ;;
    esac
  done < "$tmp/lane-keys"
}

mode="default"
selected_lane=""
case "$#" in
  0) ;;
  1)
    case "$1" in
      --validate) mode="validate" ;;
      --list) mode="list" ;;
      --all-installed) mode="all-installed" ;;
      -h|--help) usage; exit 0 ;;
      *) usage >&2; exit 2 ;;
    esac
    ;;
  2)
    [ "$1" = "--lane" ] || { usage >&2; exit 2; }
    mode="lane"
    selected_lane="$2"
    ;;
  *) usage >&2; exit 2 ;;
esac

if ! validate_catalogs; then
  echo "catalog validation: FAIL ($validation_errors error group(s))" >&2
  exit 2
fi

vector_count="$(awk 'END { print NR - 1 }' "$VECTORS")"
lane_count="$(awk 'END { print NR - 1 }' "$LANES")"
echo "catalog validation: PASS ($vector_count vectors, $lane_count lanes)"

case "$mode" in
  validate) exit 0 ;;
  list) print_matrix; exit 0 ;;
  lane) run_lane "$selected_lane"; exit $? ;;
esac

overall=0
while IFS= read -r lane; do
  adapter="$(lane_field "$lane" 2)"
  status="$(lane_field "$lane" 3)"
  case "$mode:$status:$adapter" in
    default:ready:ssc-vm|default:ready:ssc-asm)
      run_lane "$lane" || overall=1
      ;;
    all-installed:ready:*|all-installed:optional:*)
      run_lane "$lane" || rc=$?
      if [ "${rc:-0}" -ne 0 ] && [ "${rc:-0}" -ne 3 ]; then overall=1; fi
      rc=0
      ;;
  esac
done < "$tmp/lane-keys"

print_non_process_lanes
exit "$overall"
