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
#
# ── 2026-08-14: THE SUBJECT MOVED BEHIND A CACHE, AND BOTH ROWS STOPPED REACHING IT ───────────────
#
# The toolchain cache landed in install.sh on 2026-08-09, three days after this gate was written:
# on a `scripts/launcher-input-digest` HIT it restores `bin/lib` and SKIPS `sbt cli/installBin`
# entirely. The witness this gate exists to guard lives on the build path, so from that day both
# rows were verdicts about a path they could no longer enter. Measured here, in a throwaway clone:
#
#   cache ON,  stub exits 0  ->  HIT, sbt never called, install.sh exit 0   <- read as "the defect is
#                                                                              back". It is not.
#   cache ON,  stub exits 1  ->  HIT, sbt never called, install.sh exit 1   <- read as "row 1 holds".
#                                It exits 1 at the LATER `sbt-plugin publishLocal`, which the stub
#                                also intercepts — nothing to do with what row 1 claims to test.
#   cache OFF, stub exits 0  ->  builds, witness fires: "cli/installBin did not run"  <- the subject,
#                                intact. The fix never regressed.
#
# So one row was a false RED and the other a false GREEN, from one cause, and neither had anything
# to say about the witness. Nobody noticed for five days because this gate is invoked by nothing
# (tests/BUGS.md `orphaned-e2e-gates-52`).
#
# Three consequences, all of them in the code below:
#   * `SSC_TOOLCHAIN_CACHE_OFF=1` on both rows — the build path IS the subject.
#   * REACHABILITY IS ASSERTED, NOT ASSUMED. Each row first requires the run to have printed
#     `Staging ssc …` and not `cache HIT`. A gate that cannot reach its subject must say so, not
#     hand back the exit code it happened to get. This is the general lesson: the exit code alone
#     could not tell the two situations apart, and the mechanism string can.
#   * EACH ROW ASSERTS THE MECHANISM, not just the sign of the exit code — row 2 requires the
#     witness's own words, so "exited 1 for some other reason" is a failure and not a pass.
#
# And the cache path was destructive here in a way nothing declared: `rm -rf bin/lib` followed by a
# 176 MB restore, in the SHARED main checkout, twice per run. With the cache off install.sh touches
# neither, and the final check below asserts exactly that — the toolchain this gate ran against is
# the toolchain it left behind.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

STAMP="$ROOT/bin/lib/.build-stamp"
stamp_mtime() { stat -f %m "$1" 2>/dev/null || stat -c %Y "$1" 2>/dev/null; }

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

# THE STATE THE SECOND ROW DEPENDS ON. The defect is "a previous build's artefacts satisfy the
# existence checks", so a tree that has never been built cannot exercise it: the witness would fire
# because the stamp is ABSENT, not because it failed to move, and the two are indistinguishable in
# the output. Refusing loudly beats passing on the wrong evidence — the entry this gate guards
# records a first reproduction that passed for exactly that reason and was withdrawn.
if [ ! -f "$STAMP" ]; then
    echo "  [FAIL] cannot judge: $STAMP is absent, so there is no previous build to go stale."
    echo "         Run ./install.sh --dev first; row 2 would otherwise pass without the witness."
    exit 1
fi
stamp_before="$(stamp_mtime "$STAMP")"

fail=0
row() {  # row <label> <stub-exit> <mechanism-the-output-must-contain>
    local label="$1" code="$2" must="$3" out rc
    mkstub "$code"
    out=$(cd "$ROOT" && SSC_TOOLCHAIN_CACHE_OFF=1 PATH="$WORK/bin:$PATH" timeout 300 ./install.sh --dev 2>&1)
    rc=$?

    # Reachability first: a verdict from a run that never entered the build path is not a verdict.
    if printf '%s' "$out" | grep -q 'cache HIT' || ! printf '%s' "$out" | grep -q 'Staging ssc'; then
        printf '  [FAIL] %-28s never reached the build path — this gate measured nothing\n' "$label"
        printf '%s' "$out" | grep -E 'cache (HIT|MISS)|Staging ssc' | sed 's/^/         /'
        fail=1
        return
    fi

    if [ $rc -eq 0 ]; then
        printf '  [FAIL] %-28s install.sh exit=0 — it reported SUCCESS\n' "$label"
        printf '%s' "$out" | tail -3 | sed 's/^/         /'
        fail=1
    elif ! printf '%s' "$out" | grep -qF "$must"; then
        printf '  [FAIL] %-28s install.sh exit=%s, but not through the guard\n' "$label" "$rc"
        printf '         expected the output to contain: %s\n' "$must"
        printf '%s' "$out" | tail -3 | sed 's/^/         /'
        fail=1
    else
        printf '  [PASS] %-28s install.sh exit=%s via %s\n' "$label" "$rc" "$must"
    fi
}

row "sbt fails loudly  (exit 1)" 1 "Project loading failed"
row "sbt fails QUIETLY (exit 0)" 0 "cli/installBin did not run"

# The gate must leave the toolchain it borrowed exactly as it found it. Under the cache this was
# false — `rm -rf bin/lib` and a restore, in the shared checkout — and nothing said so.
stamp_after="$(stamp_mtime "$STAMP")"
if [ "$stamp_after" != "$stamp_before" ]; then
    echo "  [FAIL] this gate MODIFIED the toolchain it was measuring against"
    echo "         $STAMP mtime $stamp_before -> $stamp_after"
    fail=1
fi

echo
if [ $fail -eq 0 ]; then
    echo "install.sh refuses to report success when the build produced nothing."
    exit 0
fi
exit 1
