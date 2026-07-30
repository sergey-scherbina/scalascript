#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# The launchers warn when their build is older than HEAD; this suite spawns a JVM per case per lane,
# and each check is one `git rev-parse` (~11 ms). Inherited by every child, so run.sc's seven spawn
# sites are covered here rather than one at a time. Only the subprocess is saved: a tree at HEAD
# never prints the warning anyway.
export SSC_NO_BUILD_CHECK=1

# Route the BARE invocation through the RAM-bounded entrypoint, so the guarded path is the only path.
#
# WHY, measured 2026-07-28: this script caps nothing. run.sc spawns a subprocess per case x 3 lanes,
# each fork defaults to ~1/4 of host RAM, and several worktrees run at once — the aggregate saturates
# the machine. `scripts/conformance` exists for exactly this (semaphore + `-Xmx`), and AGENTS.md
# names it the RAM-bounded entrypoint, but nothing stopped a caller from running THIS file directly.
# It happened: three full-corpus runs in parallel with sibling builds, 139,831 pageouts, and Sergiy
# had to kill the processes by hand. The host memory guard did not help — it sheds BUILD JVMs, not
# conformance forks — and jetsam never got the chance. A rule that depends on every caller
# remembering it is a wish, not a rule.
#
# The wrapper calls back into this script with SSC_CONFORMANCE_GUARDED=1, which is what stops the
# recursion — and that direction matters: the `--server=false` below must still apply, because the
# wrapper's own default invocation lacked it and therefore leaked the multi-GB bloop daemon this
# file's next comment warns about. Guarded now means BOTH protections, not either one.
#
# Escape hatch: SSC_CONFORMANCE_GUARDED=1 runs this unbounded, for a caller who genuinely wants that.
if [ -z "${SSC_CONFORMANCE_GUARDED:-}" ] && [ -x "$DIR/../../scripts/conformance" ]; then
  exec "$DIR/../../scripts/conformance" "$@"
fi
# `--server=false`: compile run.sc in-process. Without it `scala-cli run.sc` starts a bloop
# daemon (PPID 1, adopted by launchd) that lingers at multi-GB and never exits — the run.sc
# shebang's `--server=false` applies only to a DIRECT `./run.sc`, not to `scala-cli run.sc`,
# and the `~/.zshenv` wrapper only covers interactive zsh, not non-interactive shells / other
# agents. Always invoke conformance through this wrapper. (bloop-serverless-scripts)
exec scala-cli --server=false "$DIR/run.sc" -- "$@"
