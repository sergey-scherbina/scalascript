#!/usr/bin/env bash
# v0.8 emit-wc smoke — emits a Custom Element bundle for `wc-card.ssc`
# and asserts the expected JS shape: `customElements.define` for the
# kebab-cased tag, the component object in scope, the runtime preamble.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/examples/wc-card.ssc"

bundle=$(scala-cli --power run "$ROOT/compiler" \
  --main-class scalascript.cli.ssc --server=false \
  -- emit-wc "$SRC" 2>/dev/null)

fail=0
echo "============================================================"
echo "  v0.8 — ssc emit-wc smoke"
echo "============================================================"
echo

check() {
    local name="$1"
    local needle="$2"
    if echo "$bundle" | grep -q -- "$needle"; then
        echo "  [PASS] $name"
    else
        echo "  [FAIL] $name  (needle: $needle)"
        fail=1
    fi
}

check "JsRuntime preamble"      "function _show("
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
    echo "Custom Element bundle has the expected shape."
    exit 0
fi
exit 1
