#!/usr/bin/env bash
# Every public launcher `cli/installBin` writes must be present and executable.
#
# WHY THIS EXISTS, and why CI structurally cannot replace it. On 2026-08-10 a fresh worktree of
# `main` could not build at all: the toolchain cache restores `bin/lib` and skips
# `sbt cli/installBin` — but `installBin` is what WRITES the launchers, and only `bin/ssc` is
# tracked in git. So a cache HIT in a worktree that had never built left no `bin/ssc-tools`.
#
# The cache lives in `$HOME/.cache/ssc-toolchain`. A GitHub runner's is always empty, so CI always
# MISSES and always builds — it cannot see this class at all. It bites exactly where the cache is
# warm and the worktree is fresh: every local agent, at once, and silently, because the next command
# fails for a reason that looks like the change under test.
#
# It cost three "0 rustc errors" measurements against a user's repros before anyone noticed the
# launcher was missing — `grep -c '^error'` counts nothing when the command never ran.
#
# NOT THE SAME AS `launchers-are-not-dead-on-arrival`, and the difference is the whole point. That
# gate discovers DELEGATING launchers (the ones containing `SSC="$SCRIPT_DIR/`) and asks whether they
# still work; it deliberately SKIPS a `bin/` with none of them, because a fresh worktree is a
# partially built checkout and a red there is one people learn to skip. Neither half sees today's
# failure: `ssc-tools` and `ssc-standard` do not delegate, so they are not in its subject list at
# all, and a launcher that is ABSENT cannot be discovered by walking `bin/`.
#
# This gate asks the other question — is every launcher the BUILD declares actually staged — which
# is answerable from a list that does not depend on what happens to be on disk.
#
# NOT A BUILD. This reads what is staged; `install.sh` produces it. Sub-second by construction.
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
BIN="$ROOT/bin"

# The list is READ from build.sbt, not restated here. A second copy of "which launchers exist"
# drifts the day someone adds one, and a gate that checks a stale list is how the next launcher goes
# missing with this check green — the same shape as the bug it is about.
# `lib` is the staging DIRECTORY (`val libDir = root / "bin" / "lib"`), not a launcher, and it is
# excluded by name rather than by testing the filesystem — a filesystem test would call a MISSING
# launcher a directory-that-is-absent and pass, which is the exact state this gate exists to catch.
mapfile -t declared < <(
  grep -oE '"?\$?\{?root\}?"? */ *"bin" */ *"[a-z0-9-]+"' "$ROOT/build.sbt" 2>/dev/null |
    grep -oE '"[a-z0-9-]+"$' | tr -d '"' | grep -vx 'lib' | sort -u
)

if [[ ${#declared[@]} -eq 0 ]]; then
  echo "launcher-set-complete: FAILED — could not read any launcher name out of build.sbt" >&2
  echo "    The list is derived on purpose; if the staging code changed shape, teach this gate the" >&2
  echo "    new shape rather than hardcoding names here." >&2
  exit 1
fi

failed=0
for name in "${declared[@]}"; do
  l="$BIN/$name"
  if [[ ! -f $l ]]; then
    echo "launcher-set-complete: FAILED — build.sbt writes bin/$name and it is not there" >&2
    echo "    A toolchain restored from cache without it fails on the NEXT command, whose error" >&2
    echo "    will look like it is about your change." >&2
    failed=1
  elif [[ ! -x $l ]]; then
    echo "launcher-set-complete: FAILED — bin/$name exists but is not executable" >&2
    failed=1
  fi
done

[[ $failed -eq 0 ]] || { echo "launcher-set-complete: FAILED" >&2; exit 1; }
echo "launcher-set-complete: OK (${#declared[@]} launchers declared in build.sbt, all present and executable)"
