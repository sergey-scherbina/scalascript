#!/usr/bin/env bash
#
# bugs-index-gate.sh — every BUGS.md entry carries a valid machine-readable header.
#
#   ./tests/e2e/bugs-index-gate.sh              # check BUGS.md
#   ./tests/e2e/bugs-index-gate.sh --self-test  # prove the checks can fail, then check
#
# WHAT THIS GUARDS — spec: specs/bugs-index.md
#
# The status used to live in prose. Measured 2026-07-29, that produced: 614 entries of which 108
# had NO status line at all, and three different words for "closed" (FIXED 332 / DONE 67 /
# RESOLVED 3) plus ten one-off freeform ones. So every agent wrote its own `awk` and they gave
# different answers to the same question — on 2026-07-28 a query for "remaining v2 work" silently
# omitted those 108 entries.
#
# Hand-tidying is what produced that state, so the invariant is enforced here instead.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

TARGET="${1:-}"
[[ "$TARGET" == "--self-test" ]] && TARGET=""
FILE="${BUGS_FILE:-$ROOT/BUGS.md}"

run_check() {
  python3 - "$1" <<'PY'
import re, subprocess, sys, pathlib

path = pathlib.Path(sys.argv[1])
text = path.read_text()
lines = text.split("\n")

STATUS = {"open", "fixed", "wontfix", "duplicate", "unknown"}
LANE   = {"native","int","js","jvm","v2-jvm","v2-rust","apparatus","multi","n/a"}
AREA   = {"front","runtime","codegen","cli","conformance","build","docs","plugin","other"}

entries, cur, buf = [], None, []
for ln in lines:
    if ln.startswith("## "):
        if cur is not None: entries.append((cur, "\n".join(buf)))
        cur, buf = ln[3:].strip(), []
    elif cur is not None:
        buf.append(ln)
if cur is not None: entries.append((cur, "\n".join(buf)))

problems, slugs = [], {}
def slug_of(h): return h.split("—")[0].split("-—")[0].strip().split()[0] if h.split() else h

for head, body in entries:
    slug = slug_of(head)
    slugs.setdefault(slug, 0)
    slugs[slug] += 1
    m = re.match(r"\s*<!--(.*?)-->", body, re.S)
    if not m:
        problems.append((slug, "no header comment after the heading")); continue
    fields = {}
    for fm in re.finditer(r"([a-z-]+)\s*:\s*([^\n]+)", m.group(1)):
        fields[fm.group(1)] = fm.group(2).strip()
    for req in ("status", "lane", "area"):
        if req not in fields: problems.append((slug, f"missing required field `{req}`"))
    st = fields.get("status")
    if st is not None and st not in STATUS:
        problems.append((slug, f"status `{st}` not in {sorted(STATUS)}"))
    if fields.get("lane") not in (None,) and fields["lane"] not in LANE:
        problems.append((slug, f"lane `{fields['lane']}` not in the enum"))
    if fields.get("area") not in (None,) and fields["area"] not in AREA:
        problems.append((slug, f"area `{fields['area']}` not in the enum"))
    if st == "fixed":
        sha = fields.get("fixed-in")
        if not sha:
            problems.append((slug, "status: fixed requires `fixed-in: <sha>`"))
        elif sha != "unrecorded":
            r = subprocess.run(["git", "cat-file", "-e", sha + "^{commit}"],
                               capture_output=True)
            if r.returncode != 0:
                problems.append((slug, f"fixed-in `{sha}` does not resolve to a commit"))
    if st == "duplicate" and not fields.get("duplicate-of"):
        problems.append((slug, "status: duplicate requires `duplicate-of: <slug>`"))

for s, n in slugs.items():
    if n > 1: problems.append((s, f"slug appears {n} times — slugs must be unique"))

print(f"entries: {len(entries)}   problems: {len(problems)}")
for s, why in problems[:25]:
    print(f"  FAIL [{s[:56]}] {why}")
if len(problems) > 25:
    print(f"  … and {len(problems) - 25} more")
sys.exit(1 if problems else 0)
PY
}

if [[ "${1:-}" == "--self-test" ]]; then
  echo "--- self-test: a malformed entry must be rejected ---"
  TMP="$(mktemp -t bugs-selftest-XXXXXX).md"
  cat > "$TMP" <<'BAD'
# Bug tracker

## good-entry — fine
<!-- status: open
     lane: native
     area: front -->

## bad-no-header — this one has no header at all

## bad-status — bogus status
<!-- status: banana
     lane: native
     area: front -->

## bad-fixed-no-sha — fixed without fixed-in
<!-- status: fixed
     lane: int
     area: runtime -->
BAD
  out="$(run_check "$TMP")"; rc=$?
  echo "$out"
  rm -f "$TMP"
  if [[ $rc -eq 0 ]]; then
    echo "SELF-TEST FAILED: the malformed fixture passed — this gate proves nothing"; exit 1
  fi
  for want in "no header comment" "not in" "requires"; do
    if ! printf '%s' "$out" | grep -q "$want"; then
      echo "SELF-TEST FAILED: expected a problem mentioning '$want'"; exit 1
    fi
  done
  echo "--- self-test ok (3 planted defects all caught); checking $FILE ---"
fi

run_check "$FILE"
rc=$?
if [[ $rc -eq 0 ]]; then echo "bugs-index-gate: OK"; else echo "bugs-index-gate: FAIL"; fi
exit $rc
