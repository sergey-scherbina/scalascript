#!/usr/bin/env bash
# A module's broken code block must fail the IMPORT, on every lane.
#
# Until 2026-08-06 it failed on v2 and native — "native frontend rejected incomplete parse …
# parser sentinel _err" — and ran silently on the interpreter, so the same program did different
# things depending on the lane. That is the divergence this gate exists to keep closed, and the
# reason it asserts on ALL THREE rather than on the one that was broken.
#
# The second case is the one that makes the first safe: a `@doc` block is documentation and MUST
# still import. Without it, "reject a module whose block does not parse" would reject every module
# carrying an example that deliberately elides — which is most of the standard library's docs.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BIN="$ROOT/bin"
for launcher in ssc ssc-tools; do
  if [ ! -x "$BIN/$launcher" ]; then
    echo "FAIL: $BIN/$launcher is missing — run ./install.sh --dev first"
    exit 1
  fi
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fails=0

mkdir -p "$WORK/mod"

# ── 1. a module with a broken PROGRAM block ────────────────────────────────────────────────────
cat > "$WORK/mod/broken.ssc" <<'EOF'
---
package: gate.broken
exports:
  - good
---

# broken

```scalascript
val oops = (((
```

```scalascript
def good(): Int = 42
```
EOF

# ── 2. the same module with the bad block marked @doc — documentation, must still import ───────
cat > "$WORK/mod/documented.ssc" <<'EOF'
---
package: gate.documented
exports:
  - good
---

# documented

```scalascript @doc
val oops = (((
```

```scalascript
def good(): Int = 42
```
EOF

consumer() {
  cat > "$WORK/c.ssc" <<EOF
# consumer

[good]($1)

\`\`\`scalascript
def main(): Unit = println(good())
\`\`\`
EOF
}

check() { # name  expect-reject(yes/no)  lane-cmd...
  local name="$1" expect="$2"; shift 2
  local out rc
  out="$("$@" "$WORK/c.ssc" 2>&1)"; rc=$?
  local rejected=no
  if [ $rc -ne 0 ] || printf '%s' "$out" | grep -qiE "failed to parse|incomplete parse|_err"; then rejected=yes; fi
  if [ "$rejected" = "$expect" ]; then
    echo "  ok   $name"
  else
    echo "  FAIL $name — expected rejected=$expect, got $rejected"
    printf '%s\n' "$out" | head -3 | sed 's/^/       /'
    fails=$((fails + 1))
  fi
}

echo "a broken program block in an imported module must be REJECTED:"
consumer "./mod/broken.ssc"
check "interpreter" yes "$BIN/ssc-tools" run --v1
check "v2"          yes "$BIN/ssc-tools" run --v2
check "native"      yes "$BIN/ssc" run

echo "the same block marked @doc is documentation and must still IMPORT:"
consumer "./mod/documented.ssc"
check "interpreter" no "$BIN/ssc-tools" run --v1
check "v2"          no "$BIN/ssc-tools" run --v2
check "native"      no "$BIN/ssc" run

echo
if [ $fails -eq 0 ]; then echo "import-parse-error-gate: OK"; exit 0; fi
echo "import-parse-error-gate: $fails FAILED"; exit 1
