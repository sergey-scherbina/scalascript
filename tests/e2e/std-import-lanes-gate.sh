#!/usr/bin/env bash
#
# One file, one `std/` import, three lanes — all three must resolve it.
#
# WHY. `AutoResolve` (the JVM lane's dependency walker) looked for a bare `std/foo.ssc` under
# `ImportResolver.libPath` only. `libPath` is whatever the launcher passed as `-Dssc.lib.path`,
# which in a dev tree is the REPO ROOT — and `std/` is not there, it is at `v1/runtime/std`. The
# interpreter and the js lane use `ImportResolver.stdPath`, the six-rule `discoverStdRoot` search
# (`specs/std-root-resolution.md §3`) that knows the dev-tree layout. So one lane out of three had
# its own notion of where the standard library is, and the SAME file compiled on two of them:
#
#   run --v1     imported ok
#   run-js       imported ok
#   compile-jvm  auto-resolve: cannot resolve import 'std/http.ssc' (looked at examples/std/http.ssc)
#
# It cost the JVM lane of `components-smoke`, `middleware-smoke` and `upload-smoke`, and produced
# `bundle-smoke`'s "not found, skipped" warning — four orphaned gates, one cause, and every one of
# them reported it as "the server process EXITED before it listened", which reads like a serving
# bug. (tests/BUGS.md orphaned-e2e-gates-52.)
#
# WHY THE FIXTURE LIVES UNDER examples/. Resolution is relative to the IMPORTING FILE, so a fixture
# in $TMPDIR is a different question from one inside the tree — and the tree is where every gate
# that hit this actually lives. Running it from both places is the point: `/tmp` has no `std/`
# ancestor at all, so it can only pass via the discovered std root.
#
# Usage: tests/e2e/std-import-lanes-gate.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SSC="${SSC_BIN:-$ROOT/bin/ssc-tools}"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/ssc-stdimport.XXXXXX")"
IN_TREE="$ROOT/examples/.std-import-lanes-fixture.ssc"
trap 'rm -rf "$TMP"; rm -f "$IN_TREE"' EXIT

fixture() {
  cat > "$1" <<'EOF'
# std-import-lanes fixture

[httpGet](std/http.ssc)

```scalascript
println("imported ok")
```
EOF
}
fixture "$IN_TREE"
fixture "$TMP/out-of-tree.ssc"

fails=0
check() { # $1 label  $2 command...  — must succeed AND say so
  local label="$1"; shift
  local out
  out="$(SSC_NO_BUILD_CHECK=1 timeout 120 "$@" 2>&1 || true)"
  if printf '%s' "$out" | grep -qE 'imported ok|JVM artifact written'; then
    printf '  ok   %s\n' "$label"
  else
    printf '  FAIL %s\n' "$label"
    printf '%s\n' "$out" | sed 's/^/         | /' | head -4
    fails=$((fails + 1))
  fi
}

for where in in-tree out-of-tree; do
  case "$where" in
    in-tree)     f="$IN_TREE" ;;
    out-of-tree) f="$TMP/out-of-tree.ssc" ;;
  esac
  check "$where int" "$SSC" run --v1 "$f"
  check "$where js"  "$SSC" run-js      "$f"
  check "$where jvm" "$SSC" compile-jvm "$f" -o "$TMP/$where.scjvm"
done

if [[ $fails -ne 0 ]]; then
  printf 'std-import-lanes-gate: FAIL (%d lane/location pair(s) could not resolve std/http.ssc)\n' "$fails" >&2
  exit 1
fi
printf 'std-import-lanes-gate: OK (int, js and jvm all resolve a std import, in-tree and out)\n'
