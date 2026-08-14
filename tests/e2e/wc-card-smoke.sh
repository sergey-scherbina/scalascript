#!/usr/bin/env bash
# v0.8 emit-wc smoke — emits a Custom Element bundle for `wc-card.ssc` and asserts the expected JS
# shape: the runtime preamble, the component object in scope, `customElements.define` for the
# kebab-cased tag, shadow DOM, and the attribute-change re-render.
#
# ── 2026-08-14: THE GATE OUTLIVED ITS RUNNER, so all nine needles were checked against "" ─────────
#
# It drove `scala-cli --power run "$ROOT/compiler" --main-class scalascript.cli.ssc -- emit-wc` — an
# sbt project that no longer exists anywhere in the repository — with stderr sent to /dev/null, and
# `ROOT` was one `..` short as well (`tests/`, from `d0665660a`). `bundle` was therefore the EMPTY
# STRING and every `grep` missed, which reads as nine product defects and was one dead command.
#
# THERE WAS NO PRODUCT DEFECT UNDERNEATH. Pointed at `bin/ssc-tools emit-wc`, the same nine needles
# are all present in a 149 KB bundle, first run, no other change. Recorded because the earlier
# census of this gate reported "three missing needles", which is not what an empty bundle produces —
# a census of failure MESSAGES has a shelf life, and the one thing that survives is the command.
# (tests/BUGS.md `orphaned-e2e-gates-52`, batch 4, the "outlived its runner" group.)
#
# THE EMPTINESS IS NOW ASSERTED BEFORE THE NEEDLES. A gate that greps an empty string reports its
# subject as broken in as many ways as it has patterns, and every one of them is the same fact.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SSC_TOOLS="$ROOT/bin/ssc-tools"
SRC="$ROOT/examples/wc-card.ssc"

[[ -x "$SSC_TOOLS" && -f "$SRC" ]] || {
    echo "wc-card-smoke: need $SSC_TOOLS and $SRC — run ./install.sh --dev first" >&2
    exit 2
}

echo "============================================================"
echo "  v0.8 — ssc emit-wc smoke"
echo "============================================================"
echo

work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
bundle="$work/bundle.js"
"$SSC_TOOLS" emit-wc "$SRC" >"$bundle" 2>"$work/err"

# One fact, reported once. Without this, an empty bundle fails all nine checks below and the reader
# has to work out that they are not nine problems.
if [ ! -s "$bundle" ]; then
    echo "  [FAIL] emit-wc produced NOTHING — the needles below would all miss for this one reason"
    sed 's/^/         | /' "$work/err" >&2
    exit 1
fi

# GREP THE FILE, NEVER `printf "$bundle" | grep -q`. Under `pipefail` that pipeline is a LIAR whose
# answer depends on WHERE the match is: `grep -q` exits at the first hit and closes the pipe, printf
# takes SIGPIPE with 147 KB still to write, and pipefail reports 141 — which the `if` reads as NOT
# FOUND. Measured here: the very first needle, `function _show(`, sits near the top of the bundle and
# reported MISSING while `grep -qF` on the same bytes in a file matched. The eight needles further
# down all passed, because printf had finished by the time grep exited. An early match is the one
# most likely to be reported as a miss, which is the worst possible failure schedule.
fail=0
check() {
    local name="$1" needle="$2"
    if grep -qF -- "$needle" "$bundle"; then
        echo "  [PASS] $name"
    else
        echo "  [FAIL] $name  (needle: $needle)"
        fail=1
    fi
}

check "JsRuntime preamble"       "function _show("
check "Component object emitted" "const Card = "
check "observedAttributes set"   "observedAttributes"
check "Both attrs listed"        "'title', 'body'"
check "Tag registered"           "customElements.define"
check "Shadow DOM mounted"       "attachShadow"
check "CSS injected"             "shadow.innerHTML"
check "render() called"          "Card.render(this.getAttribute('title')"
check "attr change re-renders"   "attributeChangedCallback()"

echo
if [ $fail -eq 0 ]; then
    echo "Custom Element bundle has the expected shape ($(wc -c < "$bundle" | tr -d ' ') bytes)."
    exit 0
fi
exit 1
