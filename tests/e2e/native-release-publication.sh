#!/usr/bin/env bash

set -euo pipefail
export LC_ALL=C

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
PUBLISHER="$ROOT/scripts/native-release-publish"
TMP_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/ssc-native-release-publish-test.XXXXXX")
trap 'rm -rf "$TMP_ROOT"' EXIT

artifact_ids=(
  ssc-linux-x86_64
  ssc-macos-arm64
  ssc-macos-x86_64
)
asset_names=()
for artifact_id in "${artifact_ids[@]}"; do
  asset_names+=(
    "$artifact_id"
    "$artifact_id.tar.gz"
    "$artifact_id.tar.gz.sha256"
  )
done

expected_asset_csv=$(
  printf '%s\n' "${asset_names[@]}" | sort | paste -sd, -
)

FAKE_BIN="$TMP_ROOT/fake-bin"
mkdir -p "$FAKE_BIN"
cat >"$FAKE_BIN/gh" <<'FAKE_GH'
#!/usr/bin/env bash
set -euo pipefail

: "${FAKE_GH_LOG:?}"
: "${FAKE_GH_COUNTER:?}"

call_count=0
if [[ -f "$FAKE_GH_COUNTER" ]]; then
  IFS= read -r call_count <"$FAKE_GH_COUNTER"
fi
call_count=$((call_count + 1))
printf '%s\n' "$call_count" >"$FAKE_GH_COUNTER"

{
  printf 'CALL\0'
  for argument in "$@"; do
    printf '%s\0' "$argument"
  done
  printf 'END\0'
} >>"$FAKE_GH_LOG"

if [[ $call_count -gt 2 ]]; then
  printf 'fake gh: unexpected third call\n' >&2
  exit 98
fi

if [[ ${1:-} == "api" ]]; then
  case "${FAKE_GH_LOOKUP:-404}" in
    200)
      printf 'HTTP/2.0 200 OK\n'
      exit 0
      ;;
    401)
      printf 'HTTP/2.0 401 Unauthorized\n'
      exit 1
      ;;
    403)
      printf 'HTTP/2.0 403 Forbidden\n'
      exit 1
      ;;
    404)
      printf 'HTTP/2.0 404 Not Found\n'
      exit 1
      ;;
    404-zero)
      printf 'HTTP/2.0 404 Not Found\n'
      exit 0
      ;;
    429)
      printf 'HTTP/2.0 429 Too Many Requests\n'
      exit 1
      ;;
    500)
      printf 'HTTP/2.0 500 Internal Server Error\n'
      exit 1
      ;;
    malformed)
      printf 'status: 404\n'
      exit 1
      ;;
    network)
      printf 'dial tcp: network unavailable\n' >&2
      exit 1
      ;;
    *)
      printf 'fake gh: unknown lookup mode: %s\n' "$FAKE_GH_LOOKUP" >&2
      exit 97
      ;;
  esac
fi

if [[ ${1:-} == "release" && ${2:-} == "create" ]]; then
  exit "${FAKE_GH_CREATE_EXIT:-0}"
fi

printf 'fake gh: refused command: %s\n' "$*" >&2
exit 96
FAKE_GH
chmod +x "$FAKE_BIN/gh"

case_index=0
passed=0
case_token=test-token
case_repository=owner/project
case_lookup=404
case_create_exit=0

new_fixture() {
  local name=$1
  fixture_directory="$TMP_ROOT/fixture-$case_index-$name"
  mkdir -p "$fixture_directory"
  local artifact_id
  for artifact_id in "${artifact_ids[@]}"; do
    printf 'direct bytes for %s\n' "$artifact_id" \
      >"$fixture_directory/$artifact_id"
    printf 'archive bytes for %s\n' "$artifact_id" \
      >"$fixture_directory/$artifact_id.tar.gz"
    python3 - \
      "$fixture_directory/$artifact_id.tar.gz" \
      "$fixture_directory/$artifact_id.tar.gz.sha256" <<'PY'
import hashlib
import os
import sys

archive, sidecar = sys.argv[1:]
with open(archive, "rb") as stream:
    digest = hashlib.sha256(stream.read()).hexdigest()
with open(sidecar, "wb") as stream:
    stream.write(f"{digest}  {os.path.basename(archive)}\n".encode("ascii"))
PY
  done
}

invoke() {
  local name=$1
  shift
  case_index=$((case_index + 1))
  current_case="$TMP_ROOT/case-$case_index-$name"
  mkdir -p "$current_case"
  current_stdout="$current_case/stdout"
  current_stderr="$current_case/stderr"
  current_log="$current_case/gh.log"
  current_counter="$current_case/gh.counter"

  set +e
  env \
    PATH="$FAKE_BIN:$PATH" \
    GH_TOKEN="$case_token" \
    GH_REPO="$case_repository" \
    FAKE_GH_LOG="$current_log" \
    FAKE_GH_COUNTER="$current_counter" \
    FAKE_GH_LOOKUP="$case_lookup" \
    FAKE_GH_CREATE_EXIT="$case_create_exit" \
    "$PUBLISHER" "$@" >"$current_stdout" 2>"$current_stderr"
  current_exit=$?
  set -e
}

print_file_diff() {
  local label=$1
  local expected=$2
  local actual=$3
  python3 - "$label" "$expected" "$actual" <<'PY'
import pathlib
import sys

label, expected_path, actual_path = sys.argv[1:]
expected = pathlib.Path(expected_path).read_bytes()
actual = pathlib.Path(actual_path).read_bytes()
print(f"{label}: expected={expected!r} actual={actual!r}", file=sys.stderr)
PY
}

assert_exit() {
  local expected=$1
  if [[ $current_exit -ne $expected ]]; then
    printf '%s exit: expected=%s actual=%s\n' \
      "$current_case" "$expected" "$current_exit" >&2
    printf 'stdout=%s\n' "$(python3 -c 'import pathlib,sys; print(repr(pathlib.Path(sys.argv[1]).read_bytes()))' "$current_stdout")" >&2
    printf 'stderr=%s\n' "$(python3 -c 'import pathlib,sys; print(repr(pathlib.Path(sys.argv[1]).read_bytes()))' "$current_stderr")" >&2
    exit 1
  fi
}

assert_empty() {
  local label=$1
  local actual=$2
  if [[ -s "$actual" ]]; then
    local expected="$current_case/expected-empty"
    : >"$expected"
    print_file_diff "$label" "$expected" "$actual"
    exit 1
  fi
}

assert_line() {
  local label=$1
  local actual=$2
  local expected_line=$3
  local expected="$current_case/expected-$label"
  printf '%s\n' "$expected_line" >"$expected"
  if ! cmp -s "$expected" "$actual"; then
    print_file_diff "$label" "$expected" "$actual"
    exit 1
  fi
}

assert_no_gh() {
  if [[ -e "$current_log" && -s "$current_log" ]]; then
    printf '%s gh transcript: expected=<empty> actual=' "$current_case" >&2
    python3 -c 'import pathlib,sys; print(repr(pathlib.Path(sys.argv[1]).read_bytes()))' \
      "$current_log" >&2
    exit 1
  fi
}

start_expected_log() {
  expected_log="$current_case/expected-gh.log"
  : >"$expected_log"
}

append_expected_call() {
  {
    printf 'CALL\0'
    local argument
    for argument in "$@"; do
      printf '%s\0' "$argument"
    done
    printf 'END\0'
  } >>"$expected_log"
}

append_expected_lookup() {
  local tag=$1
  append_expected_call \
    api \
    --method \
    GET \
    --include \
    --silent \
    "repos/$case_repository/releases/tags/$tag"
}

append_expected_create() {
  local tag=$1
  local directory=$2
  local arguments=(
    release
    create
    "$tag"
    --repo
    "$case_repository"
    --verify-tag
    --title
    "$tag"
    --generate-notes
  )
  local artifact_id
  for artifact_id in "${artifact_ids[@]}"; do
    arguments+=(
      "$directory/$artifact_id"
      "$directory/$artifact_id.tar.gz"
      "$directory/$artifact_id.tar.gz.sha256"
    )
  done
  append_expected_call "${arguments[@]}"
}

assert_gh_log() {
  if [[ ! -f "$current_log" ]]; then
    : >"$current_log"
  fi
  if ! cmp -s "$expected_log" "$current_log"; then
    print_file_diff "gh transcript" "$expected_log" "$current_log"
    exit 1
  fi
}

pass_case() {
  local name=$1
  passed=$((passed + 1))
  printf 'ok %02d - %s\n' "$passed" "$name"
}

checksum_error_line() {
  local artifact_id=$1
  local directory=$2
  python3 - "$artifact_id" "$directory" <<'PY'
import hashlib
import os
import sys

artifact_id, root = sys.argv[1:]
archive_name = f"{artifact_id}.tar.gz"
with open(os.path.join(root, archive_name), "rb") as stream:
    digest = hashlib.sha256(stream.read()).hexdigest()
expected = f"{digest}  {archive_name}\n".encode("ascii")
with open(os.path.join(root, f"{archive_name}.sha256"), "rb") as stream:
    actual = stream.read()
print(
    "native release publication: "
    f"checksum bytes {artifact_id}: "
    f"expected={expected!r} actual={actual!r}"
)
PY
}

new_fixture good
invoke good v2.3.4 "$fixture_directory"
assert_exit 0
assert_line \
  "stdout" \
  "$current_stdout" \
  "native release publication: tag=v2.3.4 assets=9 status=created"
assert_empty "stderr" "$current_stderr"
asset_root=$(cd "$fixture_directory" && pwd -P)
start_expected_log
append_expected_lookup v2.3.4
append_expected_create v2.3.4 "$asset_root"
assert_gh_log
pass_case "confirmed 404 creates one exact nine-asset release"

invoke wrong-arity v2.3.4
assert_exit 1
assert_empty "stdout" "$current_stdout"
assert_line \
  "stderr" \
  "$current_stderr" \
  "native release publication: arguments: expected=2:<stable-tag>,<artifact-directory> actual=1"
assert_no_gh
pass_case "wrong arity fails before GitHub"

invalid_tags=(
  v2.3.4-rc1
  v02.3.4
  v2.03.4
  v2.3.04
  2.3.4
  vfoo.bar.baz
  v2.3
  v2.3.4.5
)
for invalid_tag in "${invalid_tags[@]}"; do
  invoke "tag-$invalid_tag" "$invalid_tag" "$fixture_directory"
  assert_exit 1
  assert_empty "stdout" "$current_stdout"
  assert_line \
    "stderr" \
    "$current_stderr" \
    "native release publication: release tag: expected=stable-vMAJOR.MINOR.PATCH actual=$invalid_tag"
  assert_no_gh
  pass_case "malformed tag $invalid_tag fails before GitHub"
done

case_token=
invoke missing-token v2.3.4 "$fixture_directory"
assert_exit 1
assert_line \
  "stderr" \
  "$current_stderr" \
  "native release publication: GH_TOKEN: expected=non-empty actual=missing"
assert_no_gh
pass_case "missing token fails before GitHub"
case_token=test-token

case_repository=not-a-repository
invoke malformed-repository v2.3.4 "$fixture_directory"
assert_exit 1
assert_line \
  "stderr" \
  "$current_stderr" \
  "native release publication: GH_REPO: expected=owner/repository actual=not-a-repository"
assert_no_gh
pass_case "malformed repository fails before GitHub"
case_repository=owner/project

missing_directory="$TMP_ROOT/does-not-exist"
invoke missing-directory v2.3.4 "$missing_directory"
assert_exit 1
assert_line \
  "stderr" \
  "$current_stderr" \
  "native release publication: artifact directory type: expected=directory actual=missing-or-nondirectory"
assert_no_gh
pass_case "missing artifact directory fails before GitHub"

new_fixture symlink-directory-target
symlink_directory="$TMP_ROOT/symlink-directory"
ln -s "$fixture_directory" "$symlink_directory"
invoke symlink-directory v2.3.4 "$symlink_directory"
assert_exit 1
assert_line \
  "stderr" \
  "$current_stderr" \
  "native release publication: artifact directory type: expected=directory actual=symlink"
assert_no_gh
pass_case "symlink artifact directory fails before GitHub"

invoke symlink-directory-trailing-slash v2.3.4 "$symlink_directory/"
assert_exit 1
assert_line \
  "stderr" \
  "$current_stderr" \
  "native release publication: artifact directory type: expected=directory actual=symlink"
assert_no_gh
pass_case "symlink artifact directory with trailing slash fails before GitHub"

for missing_asset in "${asset_names[@]}"; do
  new_fixture "missing-${missing_asset//[^A-Za-z0-9]/-}"
  rm "$fixture_directory/$missing_asset"
  actual_asset_csv=$(
    printf '%s\n' "${asset_names[@]}" |
      awk -v missing="$missing_asset" '$0 != missing' |
      sort |
      paste -sd, -
  )
  invoke "missing-${missing_asset//[^A-Za-z0-9]/-}" \
    v2.3.4 "$fixture_directory"
  assert_exit 1
  assert_line \
    "stderr" \
    "$current_stderr" \
    "native release publication: asset set: expected=$expected_asset_csv actual=$actual_asset_csv"
  assert_no_gh
  pass_case "missing $missing_asset fails before GitHub"
done

new_fixture unexpected
printf 'unexpected\n' >"$fixture_directory/unexpected.bin"
invoke unexpected v2.3.4 "$fixture_directory"
assert_exit 1
assert_line \
  "stderr" \
  "$current_stderr" \
  "native release publication: asset set: expected=$expected_asset_csv actual=$expected_asset_csv,unexpected.bin"
assert_no_gh
pass_case "unexpected tenth asset fails before GitHub"

new_fixture symlink-binary
rm "$fixture_directory/ssc-linux-x86_64"
ln -s ssc-linux-x86_64.tar.gz "$fixture_directory/ssc-linux-x86_64"
invoke symlink-binary v2.3.4 "$fixture_directory"
assert_exit 1
assert_line \
  "stderr" \
  "$current_stderr" \
  "native release publication: asset type ssc-linux-x86_64: expected=regular-file actual=symlink"
assert_no_gh
pass_case "symlink direct binary fails before GitHub"

new_fixture directory-archive
rm "$fixture_directory/ssc-macos-arm64.tar.gz"
mkdir "$fixture_directory/ssc-macos-arm64.tar.gz"
invoke directory-archive v2.3.4 "$fixture_directory"
assert_exit 1
assert_line \
  "stderr" \
  "$current_stderr" \
  "native release publication: asset type ssc-macos-arm64.tar.gz: expected=regular-file actual=directory"
assert_no_gh
pass_case "directory archive fails before GitHub"

new_fixture symlink-sidecar
rm "$fixture_directory/ssc-macos-x86_64.tar.gz.sha256"
ln -s ssc-linux-x86_64.tar.gz.sha256 \
  "$fixture_directory/ssc-macos-x86_64.tar.gz.sha256"
invoke symlink-sidecar v2.3.4 "$fixture_directory"
assert_exit 1
assert_line \
  "stderr" \
  "$current_stderr" \
  "native release publication: asset type ssc-macos-x86_64.tar.gz.sha256: expected=regular-file actual=symlink"
assert_no_gh
pass_case "symlink checksum fails before GitHub"

new_fixture empty-binary
: >"$fixture_directory/ssc-linux-x86_64"
invoke empty-binary v2.3.4 "$fixture_directory"
assert_exit 1
assert_line \
  "stderr" \
  "$current_stderr" \
  "native release publication: asset size ssc-linux-x86_64: expected=>0 actual=0"
assert_no_gh
pass_case "empty binary fails before GitHub"

new_fixture stale-digest
printf '%064d  ssc-linux-x86_64.tar.gz\n' 0 \
  >"$fixture_directory/ssc-linux-x86_64.tar.gz.sha256"
expected_checksum_error=$(checksum_error_line ssc-linux-x86_64 "$fixture_directory")
invoke stale-digest v2.3.4 "$fixture_directory"
assert_exit 1
assert_line "stderr" "$current_stderr" "$expected_checksum_error"
assert_no_gh
pass_case "stale checksum digest fails before GitHub"

new_fixture wrong-sidecar-basename
python3 - "$fixture_directory/ssc-macos-arm64.tar.gz.sha256" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
digest = path.read_text(encoding="ascii").split()[0]
path.write_text(f"{digest}  bad-macos-arm64.tar.gz\n", encoding="ascii")
PY
expected_checksum_error=$(checksum_error_line ssc-macos-arm64 "$fixture_directory")
invoke wrong-sidecar-basename v2.3.4 "$fixture_directory"
assert_exit 1
assert_line "stderr" "$current_stderr" "$expected_checksum_error"
assert_no_gh
pass_case "wrong checksum basename fails before GitHub"

new_fixture trailing-sidecar-bytes
printf 'trailing\n' >>"$fixture_directory/ssc-macos-x86_64.tar.gz.sha256"
expected_sidecar_size=$(
  python3 -c \
    'print(len(("0" * 64 + "  ssc-macos-x86_64.tar.gz\n").encode("ascii")))'
)
actual_sidecar_size=$(
  python3 -c \
    'import os,sys; print(os.path.getsize(sys.argv[1]))' \
    "$fixture_directory/ssc-macos-x86_64.tar.gz.sha256"
)
invoke trailing-sidecar-bytes v2.3.4 "$fixture_directory"
assert_exit 1
assert_line \
  "stderr" \
  "$current_stderr" \
  "native release publication: checksum size ssc-macos-x86_64: expected=$expected_sidecar_size actual=$actual_sidecar_size"
assert_no_gh
pass_case "trailing checksum bytes fail before GitHub"

for lookup_mode in 200 401 403 429 500 malformed network 404-zero; do
  new_fixture "lookup-$lookup_mode"
  case_lookup=$lookup_mode
  invoke "lookup-$lookup_mode" v2.3.4 "$fixture_directory"
  assert_exit 1
  assert_empty "stdout" "$current_stdout"
  case "$lookup_mode" in
    200)
      lookup_actual=exit-0+HTTP-200
      ;;
    401|403|429|500)
      lookup_actual=exit-1+HTTP-$lookup_mode
      ;;
    404-zero)
      lookup_actual=exit-0+HTTP-404
      ;;
    malformed|network)
      lookup_actual=exit-1+HTTP-unknown
      ;;
  esac
  assert_line \
    "stderr" \
    "$current_stderr" \
    "native release publication: release lookup: expected=nonzero-exit+HTTP-404 actual=$lookup_actual"
  start_expected_log
  append_expected_lookup v2.3.4
  assert_gh_log
  pass_case "lookup mode $lookup_mode cannot create"
done
case_lookup=404

new_fixture create-failure
case_create_exit=42
invoke create-failure v2.3.4 "$fixture_directory"
assert_exit 1
assert_empty "stdout" "$current_stdout"
assert_line \
  "stderr" \
  "$current_stderr" \
  "native release publication: release create: expected=exit-0 actual=nonzero"
asset_root=$(cd "$fixture_directory" && pwd -P)
start_expected_log
append_expected_lookup v2.3.4
append_expected_create v2.3.4 "$asset_root"
assert_gh_log
pass_case "create failure is red with no retry"
case_create_exit=0

printf 'native release publication e2e: %s compare-first cases passed\n' \
  "$passed"
