#!/usr/bin/env bash
# `./install.sh --dev` must not report success for a build that produced nothing.
#
# scripts/BUGS.md `install-sh-exits-0-when-sbt-project-load-fails`: sbt refused to load the project,
# printed its interactive `Project loading failed: (r)etry, (q)uit, (l)ast, or (i)gnore?` prompt,
# took the default on EOF — and exited 0. install.sh passed that through, wrote no launchers, and
# said success. Worse than a failed build: `bin/ssc` is TRACKED, so the next command runs whatever
# was in `bin/lib` before — a silent fall back to an older toolchain.
#
# THE FIX CAME FROM OUTSIDE THIS REPO, which is why this gate exists. Measured 2026-08-06 with sbt
# runner 2.0.1 / project sbt 1.10.7: sbt now exits 1 on a project that will not load (the prompt is
# still printed), and `set -euo pipefail` in install.sh does the rest. Nothing in install.sh changed
# to earn that. A runner downgrade brings the defect straight back, and nothing else would notice.
#
# Two rows, and the SECOND is the one that matters, because it is the state the fix depends on:
#
#   sbt fails loudly (exit 1)   install.sh must fail   — true today, via set -e
#   sbt fails QUIETLY (exit 0)  install.sh must fail   — the original defect. This must hold even
#                               when sbt lies, because that is exactly what it used to do.
#
# Both rows run install.sh with a STUB `sbt` first on PATH, so the gate costs seconds rather than a
# real build. The stub is faithful to the shape being tested: it prints sbt's own failure text and
# stages nothing.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

mkstub() {  # mkstub <exit-code>
    mkdir -p "$WORK/bin"
    cat > "$WORK/bin/sbt" <<EOF
#!/usr/bin/env bash
echo "[error] [\$PWD/build.sbt]:1: illegal start of simple expression"
echo "[warn] Project loading failed: (r)etry, (q)uit, (l)ast, or (i)gnore? (default: r)"
exit $1
EOF
    chmod +x "$WORK/bin/sbt"
}

echo "============================================================"
echo "  install.sh must not report success for a build that ran"
echo "============================================================"
echo

fail=0
row() {  # row <label> <stub-exit>
    local label="$1" code="$2" out rc
    mkstub "$code"
    out=$(cd "$ROOT" && PATH="$WORK/bin:$PATH" timeout 300 ./install.sh --dev 2>&1)
    rc=$?
    if [ $rc -ne 0 ]; then
        printf '  [PASS] %-28s install.sh exit=%s\n' "$label" "$rc"
    else
        printf '  [FAIL] %-28s install.sh exit=0 — it reported SUCCESS\n' "$label"
        echo "$out" | tail -3 | sed 's/^/         /'
        fail=1
    fi
}

row "sbt fails loudly  (exit 1)" 1
row "sbt fails QUIETLY (exit 0)" 0

echo
if [ $fail -eq 0 ]; then
    echo "install.sh refuses to report success when the build produced nothing."
    exit 0
fi
exit 1
