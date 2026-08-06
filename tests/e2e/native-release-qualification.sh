#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
QUALIFIER="$ROOT/scripts/native-release-qualify"
PYTHON=${SSC_RELEASE_PYTHON:-$(command -v python3)}
TMP=$(mktemp -d "${TMPDIR:-/tmp}/ssc-native-release-test.XXXXXX")
ARTIFACT_ID=ssc-test-x86_64
PASSED=0
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

[[ -x $QUALIFIER ]] || {
  printf 'native-release-qualification: qualifier is not executable: %s\n' \
    "$QUALIFIER" >&2
  exit 2
}

FAKE_JAVA="$TMP/fake-java"
cat >"$FAKE_JAVA" <<'SH'
#!/bin/sh
mode=${FAKE_JAVA_MODE:-good}
if [ "$#" -ne 2 ] || [ "$1" != "-jar" ]; then
  printf 'fake-java: expected -jar <plugin-host>, got:' >&2
  printf ' %s' "$@" >&2
  printf '\n' >&2
  exit 65
fi
case "$2" in
  */extracted/lib/ssc-plugin-host.jar) ;;
  *)
    printf 'fake-java: plugin host is not the extracted archive file: %s\n' "$2" >&2
    exit 65
    ;;
esac
if [ ! -s "$2" ]; then
  printf 'fake-java: extracted plugin host is missing or empty: %s\n' "$2" >&2
  exit 65
fi
case "$(pwd -P)" in
  */runtime) ;;
  *)
    printf 'fake-java: expected isolated runtime cwd, got=%s\n' "$(pwd -P)" >&2
    exit 65
    ;;
esac
case "$mode" in
  good)
    printf '%s\n' '[ssc-plugin-host] Usage: SubprocessHost <plugin.jar>' >&2
    exit 1
    ;;
  wrong-exit)
    printf '%s\n' '[ssc-plugin-host] Usage: SubprocessHost <plugin.jar>' >&2
    exit 9
    ;;
  wrong-stdout)
    printf '%s\n' 'plugin host wrote stdout'
    printf '%s\n' '[ssc-plugin-host] Usage: SubprocessHost <plugin.jar>' >&2
    exit 1
    ;;
  wrong-stderr)
    printf '%s\n' 'wrong plugin usage' >&2
    exit 1
    ;;
  timeout)
    /bin/sleep 5
    exit 1
    ;;
  *)
    printf 'fake-java: unknown mode=%s\n' "$mode" >&2
    exit 64
    ;;
esac
SH
chmod +x "$FAKE_JAVA"

FAKE_OLD_PYTHON="$TMP/fake-python-3.8"
cat >"$FAKE_OLD_PYTHON" <<SH
#!/bin/sh
if [ "\$1" = "-c" ]; then
  case "\$2" in
    *platform.python_version*)
      printf '%s\n' '3.8.18'
      exit 0
      ;;
    *sys.version_info*)
      exit 1
      ;;
  esac
fi
exec "$PYTHON" "\$@"
SH
chmod +x "$FAKE_OLD_PYTHON"

make_case() {
  local name=$1
  local directory="$TMP/$name"
  mkdir -p "$directory"
  "$PYTHON" - "$directory" "$name" "$ARTIFACT_ID" <<'PY'
import gzip
import hashlib
import io
import os
import sys
import tarfile
import zipfile

directory, case, artifact_id = sys.argv[1:]
archive = os.path.join(directory, f"{artifact_id}.tar.gz")
direct = os.path.join(directory, artifact_id)


def plugin_jar() -> bytes:
    if case == "empty-plugin":
        return b""
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
        main_class = (
            "wrong.Main" if case == "wrong-plugin-main"
            else "scalascript.plugin.SubprocessHost"
        )
        bundle.writestr(
            "META-INF/MANIFEST.MF",
            f"Manifest-Version: 1.0\r\nMain-Class: {main_class}\r\n\r\n",
        )
        if case != "missing-plugin-class":
            class_bytes = b"" if case == "empty-plugin-class" else b"class-bytes"
            bundle.writestr("scalascript/plugin/SubprocessHost.class", class_bytes)
    return output.getvalue()


stub = f"""#!/bin/sh
fixture_mode={case!r}

fail_fixture() {{
  printf 'fake-ssc: %s\\n' "$1" >&2
  exit 65
}}

case "$0" in
  */extracted/ssc) ;;
  *) fail_fixture "not executing extracted ssc: $0" ;;
esac
distribution=${{0%/extracted/ssc}}
distribution_real=$(CDPATH= cd -P "$distribution" && pwd -P) ||
  fail_fixture "cannot resolve distribution root: $distribution"
actual_cwd=$(pwd -P)
[ "$actual_cwd" = "$distribution_real/runtime" ] ||
  fail_fixture "cwd expected=$distribution_real/runtime actual=$actual_cwd"
case "$PWD" in
  "$distribution/runtime"|"$distribution_real/runtime") ;;
  *) fail_fixture "PWD expected=$distribution/runtime actual=$PWD" ;;
esac
[ "$HOME" = "$distribution/home" ] ||
  fail_fixture "HOME expected=$distribution/home actual=$HOME"
[ "$XDG_CACHE_HOME" = "$distribution/home/cache" ] ||
  fail_fixture "XDG_CACHE_HOME is not isolated"
[ "$XDG_CONFIG_HOME" = "$distribution/home/config" ] ||
  fail_fixture "XDG_CONFIG_HOME is not isolated"
[ "$TMPDIR" = "$distribution/home/tmp" ] ||
  fail_fixture "TMPDIR is not isolated"
[ "$PATH" = "$distribution/empty-path" ] ||
  fail_fixture "PATH expected=$distribution/empty-path actual=$PATH"
if [ -n "${{SSC_LIB_PATH+x}}${{SSC_STD_PATH+x}}${{SSC_FRONT+x}}${{SSC_EXEC+x}}${{SSC_HOME+x}}${{JAVA_HOME+x}}${{GRAALVM_HOME+x}}${{GITHUB_WORKSPACE+x}}${{CLASSPATH+x}}${{JAVA_TOOL_OPTIONS+x}}${{JDK_JAVA_OPTIONS+x}}${{_JAVA_OPTIONS+x}}" ]; then
  fail_fixture "poisoned ScalaScript/Java environment reached runtime"
fi

if [ "$#" -eq 1 ] && [ "$1" = "--version" ]; then
  case "$fixture_mode" in
    version-exit) exit 7 ;;
    version-output)
      printf 'ssc test\\nruntime: v1 (wrong)\\n'
      exit 0
      ;;
    version-v20)
      printf 'ssc test\\nruntime: v20 (default; --v1 opts back)  ·  jvm 21-test\\n'
      exit 0
      ;;
    version-stderr)
      printf 'version warning\\n' >&2
      ;;
    version-timeout) /bin/sleep 5 ;;
  esac
  printf 'ssc test\\nruntime: v2 (default; --v1 opts back)  ·  jvm 21-test\\n'
  exit 0
fi

# `run --v1 <probe>` — 3 args, no lane flag. Added when the qualifier started certifying the v1
# front: a candidate binary that had dropped scalameta passed every other check while --v1 was dead.
if [ "$#" -eq 3 ] && [ "$1" = "run" ] && [ "$2" = "--v1" ]; then
  case "$3" in
    /*) fail_fixture "probe path is not relative: $3" ;;
  esac
  [ -f "$actual_cwd/$3" ] || fail_fixture "probe does not resolve below isolated cwd: $3"
  case "$fixture_mode" in
    v1-exit)   exit 11 ;;
    v1-output) printf '86\\n'; exit 0 ;;
    *)         printf '84\\n'; exit 0 ;;
  esac
fi

if [ "$#" -eq 4 ] && [ "$1" = "run" ] && [ "$2" = "--v2" ]; then
  [ "$4" = "release-probe.ssc" ] ||
    fail_fixture "probe path is not relative: $4"
  probe="$actual_cwd/$4"
  [ -f "$probe" ] ||
    fail_fixture "probe does not resolve below isolated cwd: $probe"
  expected_probe='val answer = (40 + 2) * 2
println(answer)'
  actual_probe=$(/bin/cat "$probe") ||
    fail_fixture "cannot read generated probe: $probe"
  [ "$actual_probe" = "$expected_probe" ] ||
    fail_fixture "generated probe content changed"

  case "$3:$fixture_mode" in
    --interpret:vm-exit) exit 8 ;;
    --interpret:vm-output) printf '83\\n'; exit 0 ;;
    --interpret:vm-extra-newline) printf '84\\n\\n'; exit 0 ;;
    --interpret:vm-stderr)
      printf 'runtime warning\\n' >&2
      printf '84\\n'
      exit 0
      ;;
    --interpret:vm-timeout) /bin/sleep 5; exit 0 ;;
    # --bytecode on a native binary is REFUSED, so the stub models a refusal for the GOOD case and
    # the mutations are shaped against that contract, not against the old "it really ran" one.
    --bytecode:asm-succeeds)
      # The failure that matters most: the binary quietly ran the program on the VM while being
      # told --bytecode. Indistinguishable from success unless the qualifier demands a refusal.
      printf '84\\n'
      exit 0
      ;;
    --bytecode:asm-refuses-but-prints)
      printf 'ssc: --bytecode is not available in the native binary\\n' >&2
      printf '84\\n'
      exit 2
      ;;
    --bytecode:asm-refusal-silent)
      # Non-zero, but says nothing about which flag or why.
      exit 2
      ;;
    --bytecode:asm-timeout) /bin/sleep 5; exit 0 ;;
    --bytecode:*)
      printf 'ssc: --bytecode is not available in the native binary: the lane loads generated classes at run time\\n' >&2
      exit 2
      ;;
    --interpret:*) printf '84\\n'; exit 0 ;;
  esac
fi

printf 'unexpected fake ssc args:' >&2
printf ' %s' "$@" >&2
printf '\\n' >&2
exit 64
""".encode()

front = {
    "tower/bin/fsub.ssc": b"fsub\n",
    "tower/bin/ssc1-run-fsub.ssc0": b"run-fsub\n",
    "tower/bin/ssc1-run.ssc0": b"run\n",
    "tower/bin/ssc1-check-run.ssc0": b"check\n",
    "runtime/std/index.ssc": b"index\n",
    "runtime/std/unused-by-probe.ssc": b"manifest-must-still-cover-me\n",
}
manifest_source = dict(front)

if case == "missing-anchor":
    front.pop("tower/bin/fsub.ssc")
    manifest_source.pop("tower/bin/fsub.ssc")
if case == "missing-manifest-file":
    front.pop("runtime/std/unused-by-probe.ssc")
if case == "manifest-omits-file":
    manifest_source.pop("runtime/std/unused-by-probe.ssc")

manifest_lines = []
for relative in sorted(manifest_source):
    digest = hashlib.sha256(manifest_source[relative]).hexdigest()
    if case == "wrong-manifest-digest" and relative == "runtime/std/index.ssc":
        digest = "0" * 64
    manifest_lines.append(f"{digest}  {relative}\n")
if case == "manifest-duplicate":
    manifest_lines.append(manifest_lines[0])
elif case == "manifest-unsorted":
    manifest_lines.reverse()
elif case == "manifest-unsafe-path":
    digest = manifest_lines[0].split("  ", 1)[0]
    manifest_lines[0] = f"{digest}  ../escape\n"
manifest = "".join(manifest_lines).encode()
if case == "bad-manifest-format":
    manifest = b"not a sha manifest\n"

files = {
    "ssc": stub,
    "README.md": b"ScalaScript release fixture\n",
    "lib/ssc-plugin-host.jar": plugin_jar(),
}
for relative, content in front.items():
    files[f"bin/lib/standard/native-front/{relative}"] = content
files["bin/lib/standard/native-front/MANIFEST.sha256"] = manifest

if case == "missing-readme":
    files.pop("README.md")
if case == "missing-plugin":
    files.pop("lib/ssc-plugin-host.jar")
if case == "missing-manifest":
    files.pop("bin/lib/standard/native-front/MANIFEST.sha256")

if case != "missing-direct":
    direct_bytes = stub + (b"# direct mismatch\n" if case == "direct-mismatch" else b"")
    with open(direct, "wb") as stream:
        stream.write(direct_bytes)
    direct_mode = (
        0o644 if case == "direct-nonexec"
        else 0o111 if case == "direct-unreadable"
        else 0o755
    )
    os.chmod(direct, direct_mode)

if case == "bad-archive":
    with open(archive, "wb") as stream:
        stream.write(b"not a gzip tar")
else:
    with tarfile.open(archive, "w:gz", format=tarfile.PAX_FORMAT) as bundle:
        def add_regular(name: str, content: bytes, mode: int = 0o644) -> None:
            entry = tarfile.TarInfo(name)
            entry.size = len(content)
            entry.mode = mode
            entry.mtime = 0
            bundle.addfile(entry, io.BytesIO(content))

        for name in sorted(files):
            if case == "nonexec-ssc" and name == "ssc":
                mode = 0o644
            elif case == "manifest-unreadable" and name.endswith("/MANIFEST.sha256"):
                mode = 0o000
            elif case == "frontend-unreadable" and name.endswith("/runtime/std/index.ssc"):
                mode = 0o000
            else:
                mode = 0o755 if name == "ssc" else 0o644
            add_regular(name, files[name], mode)

        if case == "duplicate":
            add_regular("README.md", files["README.md"])
        elif case == "canonical-duplicate":
            add_regular("./ssc", files["ssc"], 0o755)
        elif case == "unexpected-file":
            add_regular("unexpected.txt", b"unexpected\n")
        elif case == "traversal":
            add_regular("../escape", b"escape\n")
        elif case == "absolute":
            add_regular("/absolute", b"absolute\n")
        elif case in ("symlink", "hardlink", "device"):
            entry = tarfile.TarInfo(f"bin/lib/standard/native-front/{case}")
            entry.mtime = 0
            if case == "symlink":
                entry.type = tarfile.SYMTYPE
                entry.linkname = "../../../../ssc"
            elif case == "hardlink":
                entry.type = tarfile.LNKTYPE
                entry.linkname = "ssc"
            else:
                entry.type = tarfile.CHRTYPE
                entry.devmajor = 1
                entry.devminor = 3
            bundle.addfile(entry)

with open(archive, "rb") as stream:
    digest = hashlib.sha256(stream.read()).hexdigest()
sidecar = f"{digest}  {os.path.basename(archive)}\n"
if case == "checksum-mismatch":
    sidecar = f"{'0' * 64}  {os.path.basename(archive)}\n"
elif case == "checksum-uppercase":
    sidecar = f"{digest.upper()}  {os.path.basename(archive)}\n"
elif case == "checksum-name":
    sidecar = f"{digest}  wrong-name.tar.gz\n"
elif case == "checksum-extra-line":
    sidecar += "extra\n"
if case != "missing-sidecar":
    with open(f"{archive}.sha256", "wb") as stream:
        stream.write(sidecar.encode("ascii"))
if case == "unreadable-sidecar":
    os.chmod(f"{archive}.sha256", 0o000)
if case == "unreadable-archive":
    os.chmod(archive, 0o000)
PY
}

capture_qualifier() {
  local java_mode=$1
  local python_runtime=$2
  shift 2
  CASE_OUTPUT=
  CASE_RC=
  set +e
  CASE_OUTPUT=$(
    FAKE_JAVA_MODE="$java_mode" \
    SSC_LIB_PATH=/poison/ssc-lib \
    SSC_STD_PATH=/poison/ssc-std \
    SSC_FRONT=/poison/ssc-front \
    SSC_EXEC=/poison/ssc-exec \
    SSC_HOME=/poison/ssc-home \
    JAVA_HOME=/poison/java-home \
    GRAALVM_HOME=/poison/graalvm-home \
    GITHUB_WORKSPACE=/poison/workspace \
    CLASSPATH=/poison/classpath \
    JAVA_TOOL_OPTIONS=-Dpoison=java-tool-options \
    JDK_JAVA_OPTIONS=-Dpoison=jdk-java-options \
    _JAVA_OPTIONS=-Dpoison=java-options \
    SSC_RELEASE_JAVA="$FAKE_JAVA" \
    SSC_RELEASE_PYTHON="$python_runtime" \
    SSC_RELEASE_TIMEOUT_SECONDS=1 \
      "$QUALIFIER" "$@" 2>&1
  )
  CASE_RC=$?
  set -e
}

run_case() {
  local name=$1
  local java_mode=${2:-good}
  local python_runtime=${3:-$PYTHON}
  local directory="$TMP/$name"
  make_case "$name"
  capture_qualifier \
    "$java_mode" \
    "$python_runtime" \
    "$ARTIFACT_ID" \
    "$directory/$ARTIFACT_ID.tar.gz"
}

expect_pass() {
  local name=$1
  local expected_sha
  local expected_output
  run_case "$name"
  if [[ $CASE_RC -ne 0 ]]; then
    printf 'native-release-qualification[%s]: expected exit=0, got=%s\n%s\n' \
      "$name" "$CASE_RC" "$CASE_OUTPUT" >&2
    exit 1
  fi
  expected_sha=$("$PYTHON" - "$TMP/$name/$ARTIFACT_ID.tar.gz" <<'PY'
import hashlib
import sys

with open(sys.argv[1], "rb") as stream:
    print(hashlib.sha256(stream.read()).hexdigest())
PY
)
  expected_output="QUALIFIED artifact=$ARTIFACT_ID sha256=$expected_sha vm=84 asm=84 plugin-host=ready"
  if [[ $CASE_OUTPUT != "$expected_output" ]]; then
    printf 'native-release-qualification[%s]: summary expected=%q actual=%q\n' \
      "$name" "$expected_output" "$CASE_OUTPUT" >&2
    exit 1
  fi
  PASSED=$((PASSED + 1))
}

expect_fail() {
  local name=$1
  local check=$2
  local java_mode=${3:-good}
  local python_runtime=${4:-$PYTHON}
  run_case "$name" "$java_mode" "$python_runtime"
  if [[ $CASE_RC -eq 0 ]]; then
    printf 'native-release-qualification[%s]: expected failure, got success:\n%s\n' \
      "$name" "$CASE_OUTPUT" >&2
    exit 1
  fi
  local needle="FAILED check '$check'"
  if [[ $CASE_OUTPUT != *"$needle"* ||
        $CASE_OUTPUT != *'--- expected'* ||
        $CASE_OUTPUT != *'--- actual'* ]]; then
    printf 'native-release-qualification[%s]: expected diagnostic=%s, got:\n%s\n' \
      "$name" "$needle" "$CASE_OUTPUT" >&2
    exit 1
  fi
  PASSED=$((PASSED + 1))
}

expect_invocation_fail() {
  local name=$1
  local check=$2
  shift 2
  capture_qualifier good "$PYTHON" "$@"
  if [[ $CASE_RC -eq 0 ]]; then
    printf 'native-release-qualification[%s]: expected failure, got success:\n%s\n' \
      "$name" "$CASE_OUTPUT" >&2
    exit 1
  fi
  local needle="FAILED check '$check'"
  if [[ $CASE_OUTPUT != *"$needle"* ||
        $CASE_OUTPUT != *'--- expected'* ||
        $CASE_OUTPUT != *'--- actual'* ]]; then
    printf 'native-release-qualification[%s]: expected diagnostic=%s, got:\n%s\n' \
      "$name" "$needle" "$CASE_OUTPUT" >&2
    exit 1
  fi
  PASSED=$((PASSED + 1))
}

expect_pass good

expect_invocation_fail malformed-artifact-id artifact-id \
  '../bad' "$TMP/good/$ARTIFACT_ID.tar.gz"
expect_invocation_fail wrong-archive-name archive-name \
  'ssc-other' "$TMP/good/$ARTIFACT_ID.tar.gz"

expect_fail missing-readme archive-required-file
expect_fail missing-anchor archive-required-file
expect_fail missing-manifest archive-required-file
expect_fail manifest-unreadable frontend-manifest-read
expect_fail frontend-unreadable frontend-file-read
expect_fail missing-manifest-file frontend-manifest-files
expect_fail manifest-omits-file frontend-manifest-files
expect_fail wrong-manifest-digest frontend-manifest-digest
expect_fail bad-manifest-format frontend-manifest-format
expect_fail manifest-duplicate frontend-manifest-duplicate
expect_fail manifest-unsorted frontend-manifest-order
expect_fail manifest-unsafe-path frontend-manifest-path
expect_fail duplicate archive-duplicate
expect_fail canonical-duplicate archive-duplicate
expect_fail unexpected-file archive-layout
expect_fail traversal archive-path
expect_fail absolute archive-path
expect_fail symlink archive-entry-type
expect_fail hardlink archive-entry-type
expect_fail device archive-entry-type
expect_fail nonexec-ssc archive-ssc-executable
expect_fail missing-direct direct-binary-file
expect_fail direct-nonexec direct-binary-executable
expect_fail direct-unreadable direct-binary-read
expect_fail direct-mismatch direct-binary-identity
expect_fail missing-plugin archive-required-file
expect_fail empty-plugin archive-plugin-host
expect_fail wrong-plugin-main plugin-main-entry
expect_fail missing-plugin-class plugin-main-class
expect_fail empty-plugin-class plugin-main-class
expect_fail bad-archive archive-read
expect_fail missing-sidecar checksum-sidecar
expect_fail unreadable-sidecar checksum-read
expect_fail unreadable-archive checksum-read
expect_fail checksum-mismatch checksum-sidecar
expect_fail checksum-uppercase checksum-sidecar
expect_fail checksum-name checksum-sidecar
expect_fail checksum-extra-line checksum-sidecar
expect_fail version-exit version-exit
expect_fail version-output version-stdout
expect_fail version-v20 version-stdout
expect_fail version-stderr version-stderr
expect_fail vm-exit vm-exit
expect_fail vm-output vm-stdout
expect_fail vm-extra-newline vm-stdout
expect_fail vm-stderr vm-stderr
expect_fail v1-exit             v1-exit
expect_fail v1-output           v1-stdout
expect_fail asm-succeeds        asm-bytecode-refused
expect_fail asm-refuses-but-prints asm-stdout
expect_fail asm-refusal-silent  asm-refusal-names-flag
expect_fail version-timeout version-timeout
expect_fail vm-timeout vm-timeout
expect_fail asm-timeout asm-timeout
expect_fail plugin-java-exit plugin-host-exit wrong-exit
expect_fail plugin-java-stdout plugin-host-stdout wrong-stdout
expect_fail plugin-java-stderr plugin-host-stderr wrong-stderr
expect_fail plugin-java-timeout plugin-timeout timeout
expect_fail python-too-old python-runtime good "$FAKE_OLD_PYTHON"

printf 'PASS native-release-qualification: %s compare-first cases\n' "$PASSED"
