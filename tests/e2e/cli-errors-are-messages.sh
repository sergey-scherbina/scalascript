#!/usr/bin/env bash
# An error a USER can cause must read as a sentence, on BOTH launchers.
#
# `bin/ssc` (StandardMain) has printed `ssc: <message>` since it was written. `bin/ssc-tools`
# (`@main def ssc`) had no top-level catch, so the identical failure arrived as
#
#     Exception in thread "main" java.lang.RuntimeException: `Cons.join` was called but …
#             at scala.sys.package$.error(package.scala:28)
#             at …  (25 more lines)
#
# Same language, same mistake, two different ideas of what an error looks like. `v2-swift-cli.sh`
# already asserts `Exception in thread` is absent on nine CLI paths — it was absent there because
# those paths catch their own errors, not because the entry point did.
#
# Both halves are checked, and the second is the one that keeps this honest: the trace must still be
# REACHABLE under SSC_STACKTRACE=1. A fix that merely deleted the trace would pass the first half
# and leave `ssc` undebuggable — the message is for the person running a program, the trace is for
# the person fixing the compiler, and both people exist.
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
LAUNCHERS=("$ROOT/bin/ssc-tools" "$ROOT/bin/ssc")
for l in "${LAUNCHERS[@]}"; do
  [[ -x $l ]] || { echo "cli-errors-are-messages: no launcher at $l — run ./install.sh --dev" >&2; exit 2; }
done

tmp=$(mktemp -d "${TMPDIR:-/tmp}/cli-err.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

# A runtime failure, not a parse error: parse errors already have their own diagnostics, and it was
# the runtime path that escaped uncaught.
#
# Division by zero specifically, and NOT a missing method. The first draft used `.nosuchmethod(…)`,
# which made the control ambiguous: on a build predating the Stub fix that program prints `x = Stub`
# and exits 0, so the gate went red for a reason that had nothing to do with stack traces. `1 / 0`
# throws on every build there has ever been, which is what a control needs.
cat > "$tmp/boom.ssc" <<'SSC'
def main(): Unit =
  val n = 0
  println("x = " + (1 / n))
SSC

failed=0
for SSC in "${LAUNCHERS[@]}"; do
  who=${SSC#"$ROOT"/}

  set +e
  out=$(SSC_STACKTRACE= "$SSC" run "$tmp/boom.ssc" 2>&1); rc=$?
  set -e

  if [[ $rc -eq 0 ]]; then
    echo "cli-errors-are-messages: FAILED [$who] — a failing program exited 0" >&2
    echo "--- output: $out" >&2
    failed=1
  fi
  if [[ $out == *"Exception in thread"* ]]; then
    echo "cli-errors-are-messages: FAILED [$who] — the JVM's default handler reported this, not us" >&2
    echo "--- output: $out" >&2
    failed=1
  fi
  # `\tat scalascript…` — a stack frame. Matched as a tab-prefixed `at `, because the word "at" on
  # its own appears in ordinary prose. NOTE: local grep is ugrep and CI is GNU, which disagree about
  # `\t` in an ERE, so the tab is written with $'…' and left to the shell.
  if printf '%s\n' "$out" | grep -qF "$(printf '\tat ')"; then
    echo "cli-errors-are-messages: FAILED [$who] — a stack frame reached the user by default" >&2
    echo "--- output: $out" >&2
    failed=1
  fi
  if [[ $out != *"ssc: "* ]]; then
    echo "cli-errors-are-messages: FAILED [$who] — the error is not prefixed 'ssc: '" >&2
    echo "--- output: $out" >&2
    failed=1
  fi
  if [[ $out == *"ssc: ssc:"* ]]; then
    echo "cli-errors-are-messages: FAILED [$who] — the message prefixes itself" >&2
    echo "--- output: $out" >&2
    failed=1
  fi

  # The other half: the trace is one env var away.
  set +e
  traced=$(SSC_STACKTRACE=1 "$SSC" run "$tmp/boom.ssc" 2>&1); trc=$?
  set -e
  if [[ $trc -eq 0 ]]; then
    echo "cli-errors-are-messages: FAILED [$who] — SSC_STACKTRACE=1 changed the exit code" >&2
    failed=1
  fi
  if ! printf '%s\n' "$traced" | grep -qF "$(printf '\tat ')"; then
    echo "cli-errors-are-messages: FAILED [$who] — SSC_STACKTRACE=1 printed no stack frames" >&2
    echo "--- output: $traced" >&2
    failed=1
  fi
  if [[ $traced != *"ssc: "* ]]; then
    echo "cli-errors-are-messages: FAILED [$who] — SSC_STACKTRACE=1 dropped the message itself" >&2
    echo "--- output: $traced" >&2
    failed=1
  fi
done

# And a program that WORKS must be untouched by any of this — a guard that swallowed the exit code
# or the output would pass everything above.
cat > "$tmp/ok.ssc" <<'SSC'
def main(): Unit =
  println("fine")
SSC
for SSC in "${LAUNCHERS[@]}"; do
  who=${SSC#"$ROOT"/}
  set +e
  ok=$("$SSC" run "$tmp/ok.ssc" 2>&1); okrc=$?
  set -e
  if [[ $okrc -ne 0 || $ok != *"fine"* ]]; then
    echo "cli-errors-are-messages: FAILED [$who] — a working program broke" >&2
    echo "--- output: $ok" >&2
    failed=1
  fi
done

[[ $failed -eq 0 ]] || { echo "cli-errors-are-messages: FAILED" >&2; exit 1; }
echo "cli-errors-are-messages: OK (both launchers: message by default, trace under SSC_STACKTRACE=1)"
