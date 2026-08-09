# ssc-usable.sh — is the toolchain in THIS checkout actually runnable?
#
# WHY. `[[ -x "$ROOT/bin/ssc" ]]` is not that question, and four gates in this directory were asking
# it. `scripts/new-worktree` hands you a `bin/` holding the launcher and nothing else: the file is
# executable, and every run dies with
#
#     Error: Could not find or load main class scalascript.cli.StandardMain
#
# Measured 2026-08-09 in a fresh worktree: f-bare-member-call-gate, f-global-v-gate,
# f-curried-def-gate and f-trailing-block-gate all reported FAIL — not SKIP — in one to three
# seconds, every assertion "failing" on the same class-not-found string. Wiring any of them into the
# smoke registry as they stood would have made smoke red in every fresh checkout, which is exactly
# the failure `launchers-are-not-dead-on-arrival` was rewritten to stop producing (its own note
# points at these gates as the shape to copy — the shape was right, the test underneath it was not).
#
# THE PROBE IS FUNCTIONAL, not a path check. Asking whether some jar exists is a proxy for "the
# toolchain runs", and a proxy drifts the first time the layout moves; compiling and running two
# lines does not. It costs one `ssc run` (~2-3 s on a warm host) once per gate.
#
# A SKIP IS NOT FREE, so it is loud: it names the gate, prints what the probe actually got, and says
# how to fix the checkout. A silent skip is how a gate stops testing anything without anybody
# noticing — and note that if this probe were WRONG the gates would skip everywhere and go quiet, so
# every gate that sources this must be observed RUNNING in a built checkout, not merely passing.
#
# Usage:
#   . "$(dirname "${BASH_SOURCE[0]}")/lib/ssc-usable.sh"
#   ssc_usable_or_skip "my-gate-name" "$ssc"

ssc_usable_or_skip() {
  local gate=$1 ssc=$2
  if [ ! -x "$ssc" ]; then
    echo "SKIP $gate: $ssc is not present or not executable"
    echo "    (run ./install.sh --dev to populate bin/)"
    exit 0
  fi
  local dir out
  dir=$(mktemp -d "${TMPDIR:-/tmp}/ssc-usable.XXXXXX") || return 0
  printf 'def main(): Unit = println("ssc-usable-probe")\n' > "$dir/probe.ssc"
  out=$(SSC_NO_BUILD_CHECK=1 timeout 180 "$ssc" run "$dir/probe.ssc" 2>&1 | head -3)
  rm -rf "$dir"
  case "$out" in
    *ssc-usable-probe*) return 0 ;;
  esac
  echo "SKIP $gate: the toolchain in this checkout does not run a two-line program."
  echo "    probe said: $(printf '%s' "$out" | head -1 | cut -c1-90)"
  echo "    (a partially built checkout — e.g. a fresh worktree. Run ./install.sh --dev.)"
  exit 0
}
