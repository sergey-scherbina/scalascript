#!/usr/bin/env bash
# project-partition-gate — the three-part partition of specs/project-partitioning.md, checked.
#
# The document states which modules are the LANGUAGE, which are its STANDARD LIBRARY, and which are
# ADDITIONAL libraries. Prose decays; this checks the facts that make the split mean anything, all
# read live from build.sbt rather than restated here:
#
#   1. no ADDITIONAL library is in the standard-tier allowlist — that is what "additional" MEANS,
#      and nothing else defends it. Adding one domain library to `standardJarPrefixes` would put a
#      payments processor in every user's default distribution, silently.
#   2. `v2/runtime/std/*` is exactly the shipped set and `v2/runtime/providers/*` is exactly the
#      not-shipped set. That directory pair is the only place the boundary is encoded in the tree,
#      and it is worth keeping exact so the rest of the repo can be moved TOWARD it.
#   3. UniML reaches into `v1/` only through `uniml/markdown/bridge`. §8.3 asserted this once and was
#      WRONG — the prose came from an extractor that read the next project's `.dependsOn(…)`. It is
#      computed here so the claim cannot drift from the build again.
#   4. no fossil directories in the main checkout (§6).
#
#   tests/e2e/project-partition-gate.sh              # check
#   tests/e2e/project-partition-gate.sh --self-test  # plant each defect, prove each is caught
#
# SCOPE NOTE, so nobody reads more into a green than is there: checks 1-3 read build.sbt and are the
# same everywhere. Check 4 (fossils) is LOCAL HYGIENE — CI clones fresh and never has them, so a
# green in CI says nothing about the developer's checkout. It fires where it can act.
set -uo pipefail

# ── self-test ──────────────────────────────────────────────────────────────────────────────────
# One planted defect per check, each into a COPY of build.sbt, each of which must turn this gate
# red. A gate nobody has seen fail is a gate nobody knows works.
if [ "${1:-}" = "--self-test" ]; then
  self_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
  tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
  bad=0
  plant() {
    local name="$1" sed_expr="$2"
    sed "$sed_expr" "$self_root/build.sbt" > "$tmp/build.sbt"
    if cmp -s "$tmp/build.sbt" "$self_root/build.sbt"; then
      echo "  self-test BROKEN: '$name' changed nothing — the anchor moved"; bad=1; return
    fi
    if SSC_PARTITION_BUILD_SBT="$tmp/build.sbt" bash "${BASH_SOURCE[0]}" >/dev/null 2>&1; then
      echo "  NOT CAUGHT: $name"; bad=1
    else
      echo "  caught: $name"
    fi
  }
  # 1. a payments library sneaks into the default distribution
  plant "additional library added to standardJarPrefixes" \
        's|"scala-library-", "scala3-library_3-", "asm-",|"scala-library-", "scala3-library_3-", "asm-", "scalascript-payments-stripe_",|'
  # 2. a v2 std module drops out of the shipped set
  plant "v2/runtime/std module removed from standardJarPrefixes" \
        's|"scalascript-v2-native-optics-plugin_",||'
  # 3. a provider is shipped
  plant "v2/runtime/providers module added to standardJarPrefixes" \
        's|"scalascript-v2-native-json-plugin_",|"scalascript-v2-native-json-plugin_", "scalascript-v2-native-pdf-plugin_",|'
  # 4. UniML reaches v1 outside the bridge — `uniml/core` made to depend on the v1 interpreter
  plant "UniML module depending on v1 outside the bridge" \
        's|\.in(file("uniml/yaml"))|.in(file("uniml/yaml")).dependsOn(backendInterpreter)|'
  # 5. a fossil directory in the main checkout. Planted in a throwaway root, because the real one
  #    may legitimately have them right now — the gate must catch it either way.
  fossil_root="$tmp/root"; mkdir -p "$fossil_root/lang/core/target"
  if SSC_PARTITION_ROOT="$fossil_root" bash "${BASH_SOURCE[0]}" >/dev/null 2>&1; then
    echo "  NOT CAUGHT: fossil lang/ in the main checkout"; bad=1
  else
    echo "  caught: fossil lang/ in the main checkout"
  fi
  # and the unmodified tree must still pass, or the gate is simply always red
  if SSC_PARTITION_ROOT="$tmp/empty" bash "${BASH_SOURCE[0]}" >/dev/null 2>&1; then echo "  clean tree passes"; else
    echo "  BROKEN: the clean tree does NOT pass — every 'caught' above is meaningless"; bad=1; fi
  [ "$bad" -eq 0 ] && { echo "project-partition-gate --self-test: OK"; exit 0; }
  echo "project-partition-gate --self-test: FAIL"; exit 1
fi

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
SBT="${SSC_PARTITION_BUILD_SBT:-$ROOT/build.sbt}"
DOC="$ROOT/specs/project-partitioning.md"

[ -f "$SBT" ] || { echo "project-partition-gate: no build.sbt at $SBT" >&2; exit 2; }

problems=0
fail() { printf 'FAIL  %s\n' "$1"; problems=$((problems + 1)); }

# ── the two lists, both read from build.sbt ────────────────────────────────────────────────────
read -r -d '' PY <<'PYEOF'
import re, sys, pathlib, json
sbt = pathlib.Path(sys.argv[1]).read_text()
m = re.search(r'val standardJarPrefixes = Set\((.*?)\n      \)', sbt, re.S)
if not m:
    print("NOALLOWLIST"); raise SystemExit(0)
allow = set(re.findall(r'"([^"]+)"', m.group(1)))
mods = []
for pm in re.finditer(r'lazy val (\w+)\s*=\s*(?:project|crossProject\([^)]*\)[^\n]*)', sbt):
    seg = sbt[pm.end():pm.end() + 1400]
    d = re.search(r'\.in\(file\("([^"]+)"\)\)', seg)
    if not d:
        continue
    a = re.search(r'(?:moduleName|name)\s*:=\s*"([^"]+)"', seg)
    art = a.group(1) if a else ""
    shipped = bool(art) and any(art.startswith(p.rstrip('_-')) or art == p.rstrip('_-') for p in allow)
    mods.append({"dir": d.group(1), "art": art, "std": shipped})
print(json.dumps(mods))
PYEOF

MODS=$(python3 -c "$PY" "$SBT")
[ "$MODS" = "NOALLOWLIST" ] && { echo "FAIL  build.sbt has no standardJarPrefixes allowlist — the gate cannot read the tier"; exit 1; }

# Part III by directory. Kept in the gate rather than parsed out of the document on purpose: a gate
# that reads its expectation from the file it is checking cannot fail.
ADDITIONAL_RE='^(payments/|gov/|mcp/|frontend/|uniml/|v2/runtime/providers/|v1/lang/uniml-)'

# ── 1. no additional library ships in the standard tier ────────────────────────────────────────
while IFS=$'\t' read -r dir art; do
  [ -z "$dir" ] && continue
  fail "additional library in the STANDARD tier: $dir ($art)
        Part III is defined by being outside the default distribution (specs/project-partitioning.md
        §7 invariant 1). Either it is not an additional library — say so in the document and move it
        — or it must not be in standardJarPrefixes."
done < <(printf '%s' "$MODS" | python3 -c '
import json,sys,re
pat=re.compile(sys.argv[1])
for m in json.load(sys.stdin):
    if m["std"] and pat.match(m["dir"]): print(m["dir"]+"\t"+m["art"])
' "$ADDITIONAL_RE")

# ── 2. v2 std ships, v2 providers do not ───────────────────────────────────────────────────────
while IFS=$'\t' read -r kind dir; do
  [ -z "$kind" ] && continue
  case "$kind" in
    std-not-shipped)
      fail "v2/runtime/std module NOT in the standard tier: $dir
        The v2 std/providers pair is the one exact std-vs-additional boundary in the tree. A module
        under std/ that does not ship is either misplaced or missing from standardJarPrefixes." ;;
    provider-shipped)
      fail "v2/runtime/providers module IS in the standard tier: $dir
        A provider that ships is a standard-library module wearing the wrong directory." ;;
  esac
done < <(printf '%s' "$MODS" | python3 -c '
import json,sys
for m in json.load(sys.stdin):
    d=m["dir"]
    if d.startswith("v2/runtime/std/") and not m["std"]:       print("std-not-shipped\t"+d)
    if d.startswith("v2/runtime/providers/") and m["std"]:     print("provider-shipped\t"+d)
')

# ── 3. UniML reaches v1 only through the bridge ────────────────────────────────────────────────
# specs/project-partitioning.md §8.3 claims that all of UniML's v1 knowledge is concentrated in
# `uniml/markdown/bridge`. That claim was WRONG in an earlier revision of the document and the error
# was a measurement bug — a fixed-size window that read the next project's `.dependsOn(…)` — so it
# is computed here rather than restated, and each block is bounded at the next `lazy val`.
#
# `uniml/xml` -> `v1/runtime/std/markup-core` is allowed for now and named explicitly: markup-core
# has no dependencies at all and merely lives in the v1 tree. When the markup cluster moves out,
# delete the exemption and this check becomes "the bridge, and nothing else".
while IFS=$'\t' read -r dir via; do
  [ -z "$dir" ] && continue
  fail "UniML module reaches into v1/ without going through the bridge: $dir
        via: $via
        UniML must not be tied to a language version (specs/project-partitioning.md §8.3). Either
        route it through uniml/markdown/bridge, or — if the dependency is on a module that is itself
        v1-free and merely LIVES in the v1 tree — move that module out and widen the exemption here."
done < <(python3 - "$SBT" <<'PYEOF'
import re, sys, pathlib
sbt = pathlib.Path(sys.argv[1]).read_text()
starts = [(m.start(), m.group(1)) for m in re.finditer(r'^lazy val (\w+)\s*=', sbt, re.M)]
blocks, dirs = {}, {}
for i, (pos, name) in enumerate(starts):
    end = starts[i + 1][0] if i + 1 < len(starts) else len(sbt)
    b = sbt[pos:end]; blocks[name] = b
    d = re.search(r'\.in\(file\("([^"]+)"\)\)', b)
    if d: dirs[name] = d.group(1)
# JOINED WITH A COMMA, not a space: a module may declare SEVERAL `.dependsOn(…)` calls, and
# space-joining them fused two names into one unparseable token that matched no project — so such a
# module was invisible to this check. Found by the self-test's planted defect, which is the only
# reason it is not still here.
deps = {n: [x.strip() for x in ",".join(re.findall(r'\.dependsOn\(([^)]*)\)', b)).split(",") if x.strip()]
        for n, b in blocks.items()}
def norm(x):
    x = re.sub(r'\s*%\s*"?\w+"?$', '', x).strip()
    for c in (x, x + "Cross", re.sub(r'(Jvm|JVM|Js|JS)$', '', x), re.sub(r'(Jvm|JVM|Js|JS)$', '', x) + "Cross"):
        if c in dirs: return c
    return None
def closure(n):
    seen, st = set(), [n]
    while st:
        for r in deps.get(st.pop(), ()):
            k = norm(r)
            if k and k not in seen: seen.add(k); st.append(k)
    return seen
ALLOWED = {"v1/runtime/std/markup-core"}          # v1-free, merely lives there — see §8.3
BRIDGE  = "uniml/markdown/bridge"
for n, d in sorted(dirs.items(), key=lambda kv: kv[1]):
    if not d.startswith("uniml/") or d == BRIDGE:
        continue
    v1 = sorted({dirs[c] for c in closure(n) if dirs[c].startswith("v1/")} - ALLOWED)
    if v1:
        print(d + "\t" + ", ".join(v1))
PYEOF
)

# ── 4. no fossil directories at the root ───────────────────────────────────────────────────────
# Tracked files decide: `lang/` and `tools/` at the root are pre-`v1/` build output with nothing in
# git. If one ever gains a tracked file it has become real and this gate should be revisited, not
# silenced — so the check is "exists AND has no tracked files", which is exactly the fossil shape.
#
# ONLY `lang/` and `tools/` — the genuine pre-`v1/` fossils, which are inert and stay deleted.
# `conformance/` and `scalascript/` look like fossils and are not: they hold `.scala-build/` output
# and a stray `codegen/JsGen.class`, i.e. a tool writing to a RELATIVE path from the wrong working
# directory, and they come back within minutes of being removed. Gating them would flap until that
# tool is fixed, and a flapping gate is one people learn to ignore. Recorded in §8.6 instead.
#
# Checked against the MAIN checkout, not `$ROOT`. A worktree is created fresh from origin/main and
# can never hold a fossil, so a gate that looked at its own tree would have been green in every
# worktree and in CI — green in both states, which is the failure this repository keeps paying for.
# The fossils live in the shared checkout; that is where the check has to look.
FOSSIL_ROOT="${SSC_PARTITION_ROOT:-$(git -C "$ROOT" worktree list 2>/dev/null | head -1 | awk '{print $1}')}"
FOSSIL_ROOT="${FOSSIL_ROOT:-$ROOT}"
for fossil in lang tools; do
  [ -e "$FOSSIL_ROOT/$fossil" ] || continue
  tracked=$(git -C "$FOSSIL_ROOT" ls-files "$fossil" 2>/dev/null | head -1)
  [ -n "$tracked" ] && continue
  case "$fossil" in
    lang|tools) twin=" It shadows the real v1/$fossil for anyone reading the root." ;;
    *)          twin="" ;;
  esac
  fail "fossil directory in the main checkout: $FOSSIL_ROOT/$fossil/
        An untracked leftover of the pre-\`v1/\` layout, with nothing in git.$twin
        See specs/project-partitioning.md §6.
        Remove: rm -rf \"$FOSSIL_ROOT/$fossil\""
done

# ── 5. the document exists and still names all three parts ─────────────────────────────────────
if [ -f "$DOC" ]; then
  for part in "Part I — the language" "Part II — the standard library" "Part III — additional libraries"; do
    grep -qF "$part" "$DOC" || fail "specs/project-partitioning.md no longer names \"$part\""
  done
else
  fail "specs/project-partitioning.md is missing — this gate checks the partition it defines"
fi

total=$(printf '%s' "$MODS" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')
shipped=$(printf '%s' "$MODS" | python3 -c 'import json,sys; print(sum(1 for m in json.load(sys.stdin) if m["std"]))')
echo "modules: $total   standard tier: $shipped   problems: $problems"
[ "$problems" -eq 0 ] && { echo "project-partition-gate: OK"; exit 0; }
echo "project-partition-gate: FAIL"; exit 1
