#!/usr/bin/env bash
#
# ui-select-from-gate — `selectFromView` renders a real <select> on the native lane, and renders it
# IDENTICALLY to the static `select()` it is the reactive sibling of.
#
# This primitive was a positioned refusal until its three parts landed together (constructor,
# renderer arm, field-name registration). The ledger entry it closes argues at length that a
# descriptor ALONE is worse than a refusal — it lets a program build a node nothing can draw — so
# the rows below demand rendered MARKUP, never merely a call that does not throw.
#
# WHY `matches-static` IS THE LOAD-BEARING ROW. The renderer does not assemble HTML; it rebuilds the
# same NativeUiElement tree `std/ui/lower.ssc` builds for a static SelectNode and hands it back to
# the element renderer. That is what keeps attribute escaping, sorting and void-tag handling in ONE
# place — and it is only true while this row holds. If someone later hand-rolls the markup here, the
# two drift and this row is what says so.
#
# The native lane renders a SNAPSHOT: no data-ssc-key, no reconcile container. That is not a
# shortcut, it is what this lane does for `forKeyedView` too (renderKeyed emits no markers) and what
# the JVM lane's Picker fallback does. The browser owns keyed reconciliation — specs/std-ui-select.md
# § "Reactive options (selectFrom)".
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/ui-select-from.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

echo "── a reactive <select> renders its options, and renders them like the static one"
ssc_usable_or_skip ui-select-from-gate "$ssc"

# Emit a program's UI to $sandbox/<name> and echo the <select> markup it produced.
emit_select() {
  local name=$1 body=$2 out
  cat > "$sandbox/$name.ssc" <<EOF
[signal, emit](std/ui/primitives.ssc)
[lower](std/ui/lower.ssc)
[defaultTheme](std/ui/theme.ssc)
[vstack](std/ui/layout.ssc)
[select, selectFrom](std/ui/input.ssc)

def main(): Unit =
$body
  emit(lower(tree, defaultTheme), "$sandbox/$name")
  println("emitted")
EOF
  out=$(SSC_NO_BUILD_CHECK=1 SSC_FRONT_STRICT=1 timeout 300 "$ssc" run "$sandbox/$name.ssc" < /dev/null 2>&1 | head -1)
  if [[ "$out" != "emitted" ]]; then
    printf 'RUNFAIL %s' "$out"
    return
  fi
  python3 - "$sandbox/$name/index.html" <<'PY'
import io, re, sys
html = io.open(sys.argv[1], encoding="utf-8").read()
print("".join(re.findall(r"<select.*?</select>", html)))
PY
}

row() {
  local name=$1 got=$2 want=$3
  if [[ "$got" == *"$want"* ]]; then
    echo "  ✓ $name"
  else
    echo "  ✗ $name"
    echo "      want substring: $want"
    echo "      got:            $got"
    fails=$((fails + 1))
  fi
}

# ── 1. it renders at all, with its options in list order ─────────────────────────────────────────
# RED before the fix: `ssc: std/ui \`selectFromView\` … is declared … but not implemented`.
basic=$(emit_select basic '  val choice = signal("choice", "b")
  val opts = signal("opts", [("a", "Apple"), ("b", "Banana")])
  val tree = vstack(gap = 8)(
    selectFrom(opts, { (v, l) => v }, (p) => p, choice, label = "Fruit")
  )')
row renders-options "$basic" '<option value="a">Apple</option><option selected="selected" value="b">Banana</option>'

# ── 2. THE LOAD-BEARING ROW: identical to the static select given the same options ────────────────
static=$(emit_select static '  val choice = signal("choice", "b")
  val tree = vstack(gap = 8)(
    select([("a", "Apple"), ("b", "Banana")], choice, label = "Fruit")
  )')
if [[ "$basic" == "$static" && "$basic" == *"<select"* ]]; then
  echo "  ✓ matches-static"
else
  echo "  ✗ matches-static: the reactive select drifted from the static one"
  echo "      static:   $static"
  echo "      reactive: $basic"
  fails=$((fails + 1))
fi

# ── 3. the selection is READ, not assumed — the marker moves with the signal ──────────────────────
# A front that ignored `selected` would still pass row 1, whose selection happens to be the last
# option. This one puts it in the middle of three.
tracks=$(emit_select tracks '  val choice = signal("choice", "b")
  val opts = signal("opts", [("a", "A"), ("b", "B"), ("c", "C")])
  val tree = vstack(gap = 8)(
    selectFrom(opts, { (v, l) => v }, (p) => p, choice)
  )')
row tracks-selection "$tracks" '<option value="a">A</option><option selected="selected" value="b">B</option><option value="c">C</option>'

# ── 4. placeholder, and it is selected only when nothing is ──────────────────────────────────────
ph=$(emit_select placeholder '  val tree = vstack(gap = 8)(
    selectFrom(signal("o", [("a", "A")]), { (v, l) => v }, (p) => p, signal("s", ""), placeholder = "Pick")
  )')
row placeholder-selected-when-empty "$ph" '<option disabled="disabled" hidden="hidden" selected="selected" value="">Pick</option>'

phNot=$(emit_select placeholder-not '  val tree = vstack(gap = 8)(
    selectFrom(signal("o", [("a", "A")]), { (v, l) => v }, (p) => p, signal("s", "a"), placeholder = "Pick")
  )')
row placeholder-unselected-when-chosen "$phNot" '<option disabled="disabled" hidden="hidden" value="">Pick</option>'

# ── 5. disabled reaches the tag ──────────────────────────────────────────────────────────────────
dis=$(emit_select disabled '  val tree = vstack(gap = 8)(
    selectFrom(signal("o", [("a", "A")]), { (v, l) => v }, (p) => p, signal("s", "a"), disabled = true)
  )')
row disabled-attribute "$dis" '<select disabled="disabled"'

# ── 6. an empty list is an empty <select>, not a crash ───────────────────────────────────────────
empty=$(emit_select empty '  val tree = vstack(gap = 8)(
    selectFrom(signal("o", []), { (v, l) => v }, (p) => p, signal("s", ""))
  )')
row empty-list-renders "$empty" '</select>'
if [[ "$empty" == *"<option"* ]]; then
  echo "  ✗ empty-list-has-no-options: an empty items signal produced an <option>"
  fails=$((fails + 1))
else
  echo "  ✓ empty-list-has-no-options"
fi

# ── 7. the corpus file this unblocked, end to end ────────────────────────────────────────────────
subject="$ROOT/examples/frontend/std-ui/smoke-test.ssc"
if [[ ! -f "$subject" ]]; then
  echo "  ⊘ smoke-test-runs: subject absent"
else
  out=$(SSC_NO_BUILD_CHECK=1 SSC_FRONT_STRICT=1 timeout 300 "$ssc" run "$subject" < /dev/null 2>&1 | head -1)
  if [[ "$out" == "smoke:ok" ]]; then
    echo "  ✓ smoke-test-runs"
  else
    echo "  ✗ smoke-test-runs: $out"
    fails=$((fails + 1))
  fi
fi

if [[ $fails -eq 0 ]]; then
  echo "✓ ui-select-from-gate PASSED"
  exit 0
fi
echo "✗ ui-select-from-gate: $fails failure(s)"
exit 1
