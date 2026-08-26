#!/usr/bin/env bash
#
# docs-links-resolve — a relative link in `docs/` names a file that is there.
#
# `docs/README.md` is the index for the whole documentation set, and 108 of its 143 links resolved to
# NOTHING: every one of them named `X.md` where `../specs/X.md` was meant. One cause, 108 instances,
# and no gate — which is the part worth fixing, because the entry that reported it says so in as many
# words: "a dozen lines of awk plus test -f would have caught all 107 the day the first one broke".
# (BUGS.md docs-readme-links-107-of-143-point-at-files-that-are-not-there.)
#
# EVERY `.md` UNDER `docs/`, not just the index. The index is where it was found; nothing made it
# special, and the next one will be somewhere else.
#
# Anchors are stripped and not checked — a `#section` needs a heading parser, and the failure this
# guards is a missing FILE. External links are not fetched: a gate that dials the network is red when
# somebody else's host is down, and this repo has already paid a day to that shape.
#
# ONLY LINKS THAT LOOK LIKE FILES, and the rule is the extension. `[List](std/collections)` and
# `[a form](toolkit:textField?signal=teamName)` are markdown link syntax used for something that is
# not a path — a module name, a toolkit URI — and demanding they resolve would be demanding that
# prose be rewritten to satisfy a gate. If you wrote a FILENAME, it has to exist.
#
# THE FROZEN LIST IS THE SAME RATCHET `no-orphan-gates` USES: a known-broken link may stay, a NEW one
# may not, and an entry that starts resolving must be deleted so the list cannot outlive its reason.
# Two kinds are in it and each has one:
#   * six `.ssc` targets in the user guide's IMPORT section. `[names](./geometry.ssc)` is
#     ScalaScript's own import syntax being demonstrated — the markdown link IS the example. Making
#     those files exist would be inventing sources to satisfy a scanner.
#   * three files the tutorial names that were DELETED (`ToolkitDemo.scala` and its cross-backend
#     test). Repointing them would be a guess about which of CounterDemo/ShowHideDemo/TodoListDemo
#     the section now means; the section needs rewriting by someone who knows.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
fails=0
checked=0

# path<TAB>reason. Kept sorted, and compared exactly — a typo here is a hole in the gate.
read -r -d '' KNOWN_BROKEN <<'EOF' || true
docs/tutorial.md	../frontend-examples/src/main/scala/scalascript/frontend/examples/ToolkitDemo.scala	deleted; the tutorial section needs rewriting against the demos that exist
docs/tutorial.md	../frontend-examples/src/test/scala/scalascript/frontend/examples/ToolkitCrossBackendTest.scala	deleted; replaced by ReferenceAppsTest.scala, which tests different apps
docs/user-guide.md	../frontend-examples/src/main/scala/scalascript/frontend/examples/ToolkitDemo.scala	deleted; same section as the tutorial's
EOF
known="$(mktemp)"; printf '%s\n' "$KNOWN_BROKEN" | grep -v '^$' | cut -f1,2 | LC_ALL=C sort > "$known"
seen="$(mktemp)"; : > "$seen"
trap 'rm -f "$known" "$seen"' EXIT HUP INT TERM

check_file() {
  local f="$1" dir
  dir=$(dirname "$f")
  # One link per line, target only. `grep -o` on the markdown link form; the target is what follows
  # the `](` up to the first `)`.
  while IFS= read -r target; do
    case "$target" in
      http://*|https://*|mailto:*|"#"*|"") continue ;;
    esac
    local path="${target%%#*}"
    [[ -n "$path" ]] || continue
    case "${path,,}" in
      *.md|*.ssc|*.ssc0|*.scala|*.sh|*.json|*.yml|*.yaml|*.rs|*.ts|*.js|*.py|*.toml|*.sbt|*.txt|*.html|*.css) ;;
      *) continue ;;
    esac
    checked=$((checked + 1))
    local rel="${f#"$ROOT"/}"
    if [[ ! -e "$dir/$path" ]]; then
      if grep -qxF "$(printf '%s\t%s' "$rel" "$target")" "$known"; then
        printf '%s\t%s\n' "$rel" "$target" >> "$seen"
      else
        echo "  ✗ $rel -> $target"
        fails=$((fails + 1))
      fi
    else
      # An entry that started resolving must go, or the list outlives its reason.
      if grep -qxF "$(printf '%s\t%s' "$rel" "$target")" "$known"; then
        echo "  ✗ KNOWN_BROKEN entry now RESOLVES — delete it: $rel -> $target"
        fails=$((fails + 1))
      fi
    fi
  done < <(outside_fences "$f" | grep -oE '\]\([^)]+\)' | sed -E 's/^\]\(//; s/\)$//')
}

# A `[names](path)` INSIDE A FENCE IS NOT A LINK — it is a ScalaScript import, and the two spellings
# resolve against different roots. `docs/mcp.md`'s example imports `std/mcp/server.ssc`, which the
# runtime resolves against the STD ROOT and this scan would resolve against `docs/`. It read as a
# link for as long as it was written `../std/mcp/server.ssc`, which resolves as BOTH — an accident
# that held until the example had to be made runnable from any directory
# (BUGS.md mcp-v2-a-curried-plugin-native-yields-a-closure-instead-of-registering: the doc row of
# v21-standard-mcp-smoke extracts that program to a temp dir and runs it).
#
# Only ``` fences: an indented code block cannot be told from a quoted paragraph here, and no doc
# under docs/ uses one for an import.
# Inline `code` goes with them, and for the same reason: docs/mcp.md's prose says
# "the import is a bracketed list, `[names](std/mcp/server.ssc)`" — a QUOTATION of the syntax, which
# is no more a link than the fenced program it describes.
outside_fences() {
  awk '/^[[:space:]]*```/ { inblk = !inblk; next } !inblk' "$1" | sed 's/`[^`]*`//g'
}

echo "── every relative link under docs/ resolves"
while IFS= read -r f; do check_file "$f"; done < <(find "$ROOT/docs" -name '*.md' | sort)

# A SELF-TEST, because "no broken links" and "the scan found no links" print the same line. The plant
# is the exact shape that broke: a bare `X.md` in a file whose sibling directory holds the target.
probe=$(mktemp -d "${TMPDIR:-/tmp}/docs-links.XXXXXX")
trap 'rm -rf "$probe"' EXIT HUP INT TERM
mkdir -p "$probe/docs"
printf '[gone](nosuchfile.md) and [ok](README.md) and [ext](https://example.invalid/x.md)\n' > "$probe/docs/README.md"
planted=0
while IFS= read -r target; do
  case "$target" in http://*|https://*|mailto:*|"#"*|"") continue ;; esac
  [[ -e "$probe/docs/${target%%#*}" ]] || planted=$((planted + 1))
done < <(grep -oE '\]\([^)]+\)' "$probe/docs/README.md" | sed -E 's/^\]\(//; s/\)$//')

if [[ "$planted" -eq 1 ]]; then
  echo "  ✓ self-test: the planted broken link is seen, the resolvable one and the external one are not"
else
  echo "  ✗ self-test: saw $planted broken links in the plant, expected exactly 1" >&2
  fails=$((fails + 1))
fi

# A frozen entry that no longer appears at all — the link was deleted or rewritten — is stale in the
# other direction, and the ratchet only holds if it drains.
missing=$(LC_ALL=C sort -u "$seen" | comm -23 "$known" -)
if [[ -n "$missing" ]]; then
  echo "  ✗ KNOWN_BROKEN names a link that is no longer in the docs — delete it:"
  printf '%s\n' "$missing" | sed 's/^/      /'
  fails=$((fails + 1))
fi

echo
if [[ "$fails" -ne 0 ]]; then
  echo "docs-links-resolve: FAIL ($fails)" >&2
  exit 1
fi
echo "docs-links-resolve: PASS ($checked file-looking links, $(wc -l < "$known" | tr -d ' ') known-broken, none new)"
