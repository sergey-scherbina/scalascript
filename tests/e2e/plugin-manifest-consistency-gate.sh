#!/usr/bin/env bash
#
# plugin-manifest-consistency-gate — every plugin the marketplace lists either has a
# `.claude-plugin/plugin.json` or is on the declared list of ones that do not.
#
#   ./tests/e2e/plugin-manifest-consistency-gate.sh
#   ./tests/e2e/plugin-manifest-consistency-gate.sh --self-test
#
# WHAT THIS PINS, AND WHAT IT DELIBERATELY DOES NOT DECIDE. `.agents/plugins/.claude-plugin/
# marketplace.json` lists ten plugins, each with a `source` pointing at its directory. Six carry a
# per-plugin manifest and four do not. `tests/BUGS.md`
# `four-skills-have-no-plugin-manifest-and-nothing-notices` records the split and says plainly that
# it is an INCONSISTENCY, not a known breakage: either the manifest is optional and six directories
# carry a file nobody reads, or it is required and four marketplace entries resolve to a directory
# without one. Nothing measured says which.
#
# So this gate does not assert "every plugin must have one" — that would be RED today and would be
# ASSERTING THE ANSWER TO A QUESTION NOBODY HAS ANSWERED. It asserts the SET, by name, in both
# directions:
#
#   * a plugin that loses its manifest, or a NEW listed plugin without one, is a regression;
#   * a plugin on the declared list that GAINS one means the split is closing — the row must come
#     out here in the same commit, so the number and the list cannot drift apart in two files.
#
# That is the shape v3's `KNOWN_CONF_DISAGREE` uses for the same reason: a count is blind to a swap.
# One plugin gaining a manifest while another loses it leaves the count at four and says nothing.
#
# THE SUBMODULE MAY NOT BE CHECKED OUT, and that must not read as a pass. `.agents/plugins` is a
# submodule; a checkout without it has no marketplace to compare against. The gate SKIPS loudly in
# that case rather than finding zero problems in zero plugins — a check that cannot see its subject
# reporting OK is the failure mode half this repository's gate work has been about.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

PLUGINS_DIR="${PLUGINS_DIR:-$ROOT/.agents/plugins}"
MARKET="$PLUGINS_DIR/.claude-plugin/marketplace.json"

# The declared list: plugins known to have NO per-plugin manifest, as measured 2026-08-15 and
# re-measured 2026-08-31 (unchanged). Alphabetical, one per line, so a diff is readable.
DECLARED_NO_MANIFEST="isolate
multi-repo
rozum
spec-dev"

if [ "${1:-}" = "--self-test" ]; then
  # The gate must be able to SAY NO. A fabricated tree with one plugin missing from the declared
  # list has to fail — otherwise the comparison below could be satisfied by anything.
  probe="$(mktemp -d "${TMPDIR:-/tmp}/pmc.XXXXXX")"
  mkdir -p "$probe/.claude-plugin" "$probe/alpha/.claude-plugin" "$probe/beta"
  printf '{"plugins":[{"name":"alpha","source":"./alpha"},{"name":"beta","source":"./beta"}]}\n' \
    > "$probe/.claude-plugin/marketplace.json"
  printf '{}\n' > "$probe/alpha/.claude-plugin/plugin.json"
  out="$(PLUGINS_DIR="$probe" "$0" 2>&1)"; rc=$?
  rm -rf "$probe"
  if [ "$rc" -eq 0 ]; then
    echo "plugin-manifest-consistency --self-test: FAIL — a tree whose missing set is {beta}," >&2
    echo "  which is NOT the declared set, was accepted. The comparison does not discriminate." >&2
    exit 1
  fi
  echo "plugin-manifest-consistency --self-test: OK — an undeclared missing manifest is refused"
fi

if [ ! -r "$MARKET" ]; then
  echo "plugin-manifest-consistency: SKIP — no marketplace at $MARKET"
  echo "  The .agents/plugins submodule is not checked out here, so there is nothing to compare."
  echo "  This is a SKIP and not a pass: a check that cannot see its subject must not report OK."
  exit 0
fi

listed="$(python3 - "$MARKET" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
for p in (d.get("plugins") if isinstance(d, dict) else d):
    print(p["name"])
PY
)" || { echo "plugin-manifest-consistency: FAIL — marketplace.json did not parse" >&2; exit 1; }

if [ -z "$listed" ]; then
  echo "plugin-manifest-consistency: FAIL — the marketplace lists NO plugins." >&2
  echo "  Scanning nothing cannot distinguish a consistent tree from an unreadable one." >&2
  exit 1
fi

missing=""
for name in $listed; do
  [ -f "$PLUGINS_DIR/$name/.claude-plugin/plugin.json" ] || missing="$missing$name
"
done
actual="$(printf '%s' "$missing" | sed '/^$/d' | sort)"
want="$(printf '%s' "$DECLARED_NO_MANIFEST" | sed '/^$/d' | sort)"

n_listed="$(printf '%s\n' "$listed" | sed '/^$/d' | wc -l | tr -d ' ')"
if [ "$actual" = "$want" ]; then
  echo "plugin-manifest-consistency: OK — $n_listed listed, the manifest-less set is exactly the declared one"
  exit 0
fi

echo "plugin-manifest-consistency: FAIL — the manifest-less set is not the declared set" >&2
echo "  declared: $(printf '%s' "$want" | tr '\n' ' ')" >&2
echo "  actual:   $(printf '%s' "$actual" | tr '\n' ' ')" >&2
echo "  A NEW name is a regression: a listed plugin lost or never had its manifest." >&2
echo "  A name that DISAPPEARED means the split is closing — drop it from DECLARED_NO_MANIFEST" >&2
echo "  in the same commit, so this gate and tests/BUGS.md cannot drift apart." >&2
exit 1
