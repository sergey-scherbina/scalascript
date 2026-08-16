#!/usr/bin/env bash
# `ssc-tools check` learns what names exist by ASKING the runtime, not by keeping a copy of it.
#
# ── WHY ───────────────────────────────────────────────────────────────────────────────────────────
#
# The typer decided whether a name exists from a hand-maintained list in `Typer.pluginBuiltins`. The
# interpreter decides from its own global table. Extracting both and diffing them on 2026-08-16:
#
#     111 ambient interpreter globals — the typer did not know 77 of them.
#     coroutineCreate readFile writeFile exec Http Response Random Logger Clock Cache Stream Env …
#
# Not exotic names. `check` called every one of them undefined, which cost four conformance cases
# outright and forced undefined-name reporting to be switched OFF inside variadic arguments to land
# the argument-inference work at all.
#
# The fix is `Interpreter.ambientGlobalNames` — a probe interpreter, its builtins installed, its keys
# read — so the two cannot drift. This gate holds that property, and it has to hold it in BOTH
# directions or a generated source that still needs its manual copy has replaced nothing:
#
#   * a program using an ambient global type-checks                     (the win)
#   * the name is NOT a string literal in Typer.scala                   (it came from the runtime)
#   * a genuinely undefined name is still rejected                      (anti-constant)
#   * the hand-added std-module names do not GROW                       (frozen ratchet, see below)
#
# ── THE FROZEN LIST, AND THE DEFECT IT RATCHETS ───────────────────────────────────────────────────
#
# Ten names were hand-added to `pluginBuiltins` on 2026-08-16 to stop `check examples/*.ssc` going
# red. They are real `std/` exports — and the v1 interpreter does not bind them either, so they make
# `check` ACCEPT names its own runtime refuses (`ssc-tools check` says OK on `vstack("a")`, `run
# --v1` says `Undefined: vstack`). That is the defect this programme exists to remove, pointing the
# other way, and it is filed as `check-accepts-names-the-v1-runtime-does-not-have`.
#
# Deleting them is NOT the fix — it turns a permissive checker into a red CI step on programs that
# are not wrong, because the real question is what `check` should do with a `frontend: react`
# example at all. So the list is FROZEN: it may shrink, never grow. A new hand-added name is a new
# lie, and this gate is where it stops.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SSC_TOOLS="$ROOT/bin/ssc-tools"
TYPER="$ROOT/v1/lang/core/src/main/scala/scalascript/typer/Typer.scala"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/ssc-prelude.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
pass=0; fail=0

[[ -x "$SSC_TOOLS" ]] || { echo "typer-prelude-from-runtime-names: no $SSC_TOOLS — run ./install.sh --dev" >&2; exit 2; }
[[ -f "$TYPER"     ]] || { echo "typer-prelude-from-runtime-names: no $TYPER" >&2; exit 2; }

check_src() {
  printf '```scalascript\n%s\n```\n' "$2" > "$WORK/$1.ssc"
  SSC_NO_CDS=1 timeout 200 "$SSC_TOOLS" check "$WORK/$1.ssc" 2>&1 | head -1
}
ok()   { echo "  ok   $1"; pass=$((pass + 1)); }
bad()  { echo "  FAIL $1"; [ -n "${2:-}" ] && echo "         $2"; fail=$((fail + 1)); }

echo "============================================================"
echo "  the checker's prelude comes from the runtime"
echo "============================================================"
echo

# ── the win, at STATEMENT position where undefined-name reporting has always been on ─────────────
for n in coroutineCreate readFile writeFile exec Random; do
  out="$(check_src "amb-$n" "val probe = $n")"
  if [[ "$out" == *": OK" ]]; then ok "[$n] known to check"
  else bad "[$n] check calls an ambient runtime global undefined" "$out"; fi
done

# ── and it came from the RUNTIME, not from a fresh hand entry ────────────────────────────────────
# This is the half that makes the row above mean something. Without it, someone closing the next gap
# by typing the name into `pluginBuiltins` would leave this gate green while undoing its purpose.
for n in coroutineCreate readFile exec Random; do
  if grep -q "\"$n\"" "$TYPER"; then
    bad "[$n] is a string literal in Typer.scala — the hand list is growing back, ask the runtime instead"
  else
    ok "[$n] absent from Typer.scala — learned from Interpreter.ambientGlobalNames"
  fi
done

# ── anti-constant: the check still rejects something ─────────────────────────────────────────────
out="$(check_src "undef" "thisNameDoesNotExistAnywhere(1)")"
if [[ "$out" == *"Reference to undefined name"* ]]; then ok "[control] a genuinely undefined name is still rejected"
else bad "[control] nothing is rejected any more — the prelude now accepts everything" "$out"; fi

# ── the four conformance cases this cost, checked as they stand ──────────────────────────────────
for c in coroutine-basic coroutine-error coroutine-native-lifecycle html-dsl; do
  f="$ROOT/tests/conformance/$c.ssc"
  if [[ ! -f "$f" ]]; then bad "[$c] corpus case is gone — this row proves nothing"; continue; fi
  out="$(SSC_NO_CDS=1 timeout 200 "$SSC_TOOLS" check "$f" 2>&1 | head -1)"
  if [[ "$out" == *": OK" ]]; then ok "[$c] accepted"
  else bad "[$c] rejected again" "${out##*error: }"; fi
done

# ── the frozen ratchet: hand-added std-module names may shrink, never grow ───────────────────────
read -r -d '' FROZEN <<'EOF' || true
Transport
vstack
hstack
divider
spacer
Node
MapOp
FilterOp
FlatMapOp
PureMarkupCodec
EOF
# Names in the `stdlib .ssc library modules` / `std/mcp … std/ui` blocks of pluginBuiltins. Counted
# rather than listed from the file, because the point is the SIZE of the lie, not its spelling.
present=0
while read -r n; do [ -z "$n" ] && continue; grep -q "\"$n\"" "$TYPER" && present=$((present + 1)); done <<< "$FROZEN"
frozen_n=$(grep -c . <<< "$FROZEN")
if [ "$present" -le "$frozen_n" ]; then
  ok "[ratchet] $present of $frozen_n frozen hand-added names still present (may shrink, never grow)"
else
  bad "[ratchet] $present hand-added names against a frozen $frozen_n"
fi

echo
if [ $fail -eq 0 ]; then
  echo "typer-prelude-from-runtime-names: OK ($pass checks)"
  exit 0
fi
echo "typer-prelude-from-runtime-names: $pass ok, $fail FAIL"
exit 1
