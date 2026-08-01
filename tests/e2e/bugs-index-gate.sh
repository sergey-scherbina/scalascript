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

# Every BUGS.md, not just the root one — bugs live in the module that owns the fix
# (specs/work-tracking-layout.md). `runtime` is a SYMLINK to `v1/runtime`, so the find below prunes
# symlinked directories: without that, three files are visited twice and the entry count comes out
# 885 instead of 630. BUGS_FILE=<path> narrows to one file.
if [[ -n "${BUGS_FILE:-}" ]]; then
  FILES=("$BUGS_FILE")
else
  FILES=()
  while IFS= read -r f; do FILES+=("$f"); done < <(
    find "$ROOT" -name BUGS.md -not -path '*/target/*' -not -path '*/.git/*' \
         -not -path '*/node_modules/*' -not -path '*/.scala-build/*' -type f -print 2>/dev/null | sort
  )
fi

run_check() {
  python3 - "$@" <<'PY'
import re, subprocess, sys, pathlib

paths = [pathlib.Path(p) for p in sys.argv[1:]]
text = "\n".join(p.read_text() for p in paths)
lines = text.split("\n")

# RESOLUTION requires history this checkout may not have. CI clones with fetch-depth: 1, where
# `git cat-file` cannot see any commit but the tip — measured on run 30484689408, that reported
# 319 of 320 valid shas as "does not resolve" and turned main red. A gate whose verdict depends on
# clone depth is not a gate, so resolution is checked only where it CAN be, and said out loud.
SHALLOW = subprocess.run(["git", "rev-parse", "--is-shallow-repository"],
                         capture_output=True, text=True).stdout.strip() == "true"

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
            # SHAPE first, everywhere.
            #
            # The `isdigit` guard that used to live here is GONE, and its removal is the point: an
            # 11-digit CI run id matches [0-9a-f]{7,40}, which is why it was added — but so does a
            # perfectly real abbreviated sha that happens to be all digits. `611795277` is one, and
            # this gate rejected it as "not a commit sha" on 2026-08-02. The reachability check
            # below subsumes the guard completely: a run id is not an ancestor of HEAD either, and
            # it now says so by name instead of by a heuristic that has a false positive.
            if not re.fullmatch(r"[0-9a-f]{7,40}", sha):
                problems.append((slug, f"fixed-in `{sha}` is not a commit sha"))
            elif not SHALLOW:
                # REACHABILITY, not existence. `git cat-file -e` is satisfied by any object lying
                # around in the object database — including a PRE-REBASE ORPHAN, which is what a
                # `fixed-in` written before the push becomes the moment `git rebase` rewrites it.
                # The old commit survives locally and unreferenced, so the record looks fine on the
                # machine that wrote it and points at nothing in a fresh clone. Measured 2026-08-02:
                # 17 entries across five BUGS.md files were in exactly that state, and one of them
                # was written by me the previous day.
                #
                # Anchored at HEAD, not origin/main, on purpose: the normal flow is commit the fix,
                # then mark it `fixed-in` in a follow-up commit, and at that moment the fix is on
                # your feature branch and not yet on main. HEAD accepts that and still refuses an
                # orphan, because a rebase leaves the old sha unreachable from the new HEAD too.
                r = subprocess.run(["git", "merge-base", "--is-ancestor", sha, "HEAD"],
                                   capture_output=True)
                if r.returncode != 0:
                    exists = subprocess.run(["git", "cat-file", "-e", sha + "^{commit}"],
                                            capture_output=True).returncode == 0
                    if exists:
                        why = ("exists locally but is NOT an ancestor of HEAD — a pre-rebase "
                               "orphan, invisible in a fresh clone")
                    elif sha.isdigit() and len(sha) >= 9:
                        why = "looks like a CI run id, not a commit sha"
                    else:
                        why = "does not resolve to a commit"
                    problems.append((slug, f"fixed-in `{sha}` {why}"))
    if st == "duplicate" and not fields.get("duplicate-of"):
        problems.append((slug, "status: duplicate requires `duplicate-of: <slug>`"))

# Uniqueness is checked across the CONCATENATION of every file, not per file: after the split a
# slug could otherwise exist twice in two modules and each file would look fine on its own.
for s, n in slugs.items():
    if n > 1: problems.append((s, f"slug appears {n} times — slugs must be unique across all BUGS.md"))

if SHALLOW:
    print("note: shallow clone — `fixed-in` checked for SHAPE only; run in a full clone to verify "
          "each sha resolves.")
print(f"files: {len(paths)}   entries: {len(entries)}   problems: {len(problems)}")
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

## bad-fixed-not-a-sha — fixed-in is a CI run id, not a sha
<!-- status: fixed
     lane: int
     area: runtime
     fixed-in: 30484689408 -->
BAD
  out="$(run_check "$TMP")"; rc=$?
  echo "$out"
  rm -f "$TMP"
  if [[ $rc -eq 0 ]]; then
    echo "SELF-TEST FAILED: the malformed fixture passed — this gate proves nothing"; exit 1
  fi
  # "not a commit sha" without the leading "is": the run-id fixture now reports "looks like a CI
  # run id, not a commit sha" — a MORE specific message than before, and the substring covers both
  # it and the plain shape failure. This assertion caught the message change the moment it landed,
  # which is what a self-test is for; loosening it to match is correct here, deleting it would not
  # be.
  for want in "no header comment" "not in" "requires" "not a commit sha"; do
    if ! printf '%s' "$out" | grep -q "$want"; then
      echo "SELF-TEST FAILED: expected a problem mentioning '$want'"; exit 1
    fi
  done
  echo "--- self-test ok (4 planted defects all caught); checking ${#FILES[@]} file(s) ---"
fi

run_check "${FILES[@]}"
rc=$?
if [[ $rc -eq 0 ]]; then echo "bugs-index-gate: OK"; else echo "bugs-index-gate: FAIL"; fi
exit $rc
