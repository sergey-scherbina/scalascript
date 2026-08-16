#!/usr/bin/env bash
#
# process-stdin-gate — a secret reaches a child through stdin and NOT through its command line.
#
# THE REQUEST (rozum, `process-needs-a-stdin-pipe`, `impact: blocks`). One route of their control
# console installs a Telegram bot, which means handing a token to a child process. `ProcessOptions`
# had `cwd`, `env`, `timeout`, `inheritEnv` and no stdin, so a ScalaScript port of that route could
# only pass the token in argv:
#
#     $ ps -axo command | grep bot-add
#     rozum-gateway messenger bot-add mybot --token 7712345678:AA…
#
# It was the only route of their whole port blocked for a SECURITY reason rather than a capability
# one. The workaround they rejected — write it to a temp file, pass the path — puts the secret on
# disk, which is what the stdin design was avoiding.
#
# ROW 2 IS THE POINT, AND ROW 3 IS WHAT MAKES ROW 2 MEAN ANYTHING. A gate asserting only "the child
# received the value on stdin" passes just as happily on an implementation that ALSO leaves it in
# argv. So the child prints its own command line (`ps -o command= -p $$`) and row 2 asserts the
# token is absent from it — and row 3 passes the same token as an ARGUMENT and asserts the same
# probe DOES see it. Without row 3, row 2 could be green because `ps` was broken, or because the
# token never existed.
#
# THREE LANES, because a field honoured on one and dropped on another is the silent divergence this
# repository keeps paying for: `bin/ssc run`, `ssc-tools run --v1`, and `build-rust`. The jvm and js
# lanes implement it too, and are not exercised here — a jvm/js runner is a different harness, and
# claiming coverage this script does not have would be worse than naming the gap.
#
# NOT COVERED, deliberately: the js lane still ignores `cwd`/`env`/`timeout`/`inheritEnv` entirely
# (`js-exec-ignores-every-processoptions-field-but-stdin`), so its `stdin` works while its siblings
# do not. That is filed, not fixed here.
#
# COST: one cargo build plus two interpreter runs, ~45 s.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
ssc="${SSC_BIN:-$ROOT/bin/ssc}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" && -x "$ssc" ]] || { echo "process-stdin-gate: no launcher — run ./install.sh --dev" >&2; exit 2; }

sandbox=$(mktemp -d "$ROOT/examples/_stdin.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

# Positional, not named: `ProcessOptions(stdin = Some(…))` does not build on the rust lane
# (`rust-named-ctor-args-drop-the-defaulted-fields`), and using it here would measure that instead.
# The token is returned by a def rather than bound to a val because `Some(v)` MOVES a String on the
# rust lane and a later read of the same val is E0382 — a probe detail, not a defect of this feature.
cat > "$sandbox/w.ssc" <<'SSC'
[exec, ProcessOptions](../../std/process.ssc)

def token(): String = "TOKENZZ7712345678AAsecret"

def main(): Unit =
  val viaStdin = exec("sh", List("-c", "read tok; echo \"got:$tok\"; ps -o command= -p $$"),
                      ProcessOptions(None, Map(), None, true, Some(token() + "\n")))
  println("received on stdin  : " + viaStdin.stdout.contains("got:" + token()))
  println("absent from argv   : " + !viaStdin.stdout.split("\n").drop(1).mkString(" ").contains(token()))
  val viaArgv = exec("sh", List("-c", "echo ignored; ps -o command= -p $$", "x", token()), ProcessOptions())
  println("control, argv leaks: " + viaArgv.stdout.contains(token()))
  val none = exec("cat", List(), ProcessOptions())
  println("no stdin, no hang  : " + (none.stdout == ""))

main()
SSC

want=$'received on stdin  : true\nabsent from argv   : true\ncontrol, argv leaks: true\nno stdin, no hang  : true'

check_lane() { # $1 label, $2 output
  local label=$1 out=$2 row got
  while IFS= read -r row; do
    got=$(printf '%s\n' "$out" | grep -F "${row%%:*}:" || true)
    if [[ "$got" == "$row" ]]; then
      echo "  ✓ $label  ${row}"
    else
      echo "  ✗ $label  ${row%%:*}: got '${got#*: }', wanted '${row#*: }'"
      fails=$((fails + 1))
    fi
  done <<< "$want"
}

echo "── a secret travels on stdin, not on the command line"

# ROW 4 IS ALSO A HANG CHECK, so every lane runs under a timeout: `exec("cat", …)` with no stdin
# blocked forever on this repository's v1 lane until the pipe was closed unconditionally. A gate
# without a timeout would not fail there — it would never finish.
v2_out=$(timeout 300 "$ssc" run "$sandbox/w.ssc" 2>/dev/null)
[[ -z "$v2_out" ]] && { echo "  ✗ bin/ssc run produced nothing (hang or crash)"; fails=$((fails + 1)); }
check_lane "run    " "$v2_out"

v1_out=$(timeout 300 "$tools" run --v1 "$sandbox/w.ssc" 2>/dev/null)
[[ -z "$v1_out" ]] && { echo "  ✗ ssc-tools run --v1 produced nothing (hang or crash)"; fails=$((fails + 1)); }
check_lane "--v1   " "$v1_out"

if command -v cargo >/dev/null 2>&1; then
  if (cd "$sandbox" && timeout 900 "$tools" build-rust "$sandbox/w.ssc" >"$sandbox/build.log" 2>&1); then
    rust_out=$(timeout 300 "$sandbox/w" 2>/dev/null)
    [[ -z "$rust_out" ]] && { echo "  ✗ the rust binary produced nothing (hang or crash)"; fails=$((fails + 1)); }
    check_lane "rust   " "$rust_out"
  else
    echo "  ✗ build-rust failed:"
    grep -m3 -E 'Generic\(|error\[E[0-9]+\]' "$sandbox/build.log" | cut -c1-120 | sed 's/^/      /'
    fails=$((fails + 1))
  fi
else
  echo "  [skip] cargo is not on PATH — the rust lane is a SKIP, not a pass." >&2
fi

echo
if [[ "$fails" -ne 0 ]]; then
  echo "process-stdin-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "process-stdin-gate: PASS"
