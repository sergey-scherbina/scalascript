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
LANE   = {"native","int","js","jvm","v2-jvm","v2-rust","v3","apparatus","multi","n/a"}
AREA   = {"front","runtime","codegen","cli","conformance","build","docs","plugin","other"}

# `kind` is OPTIONAL (it defaults to `bug` when ranking) but it is NOT free text. Sixteen entries had
# accumulated invented values — `divergence` x8, `gap` x5, `wrong-output` x2, `wrong-answer`,
# `defect` — none of which `--kind` can match, so a query for every wrong answer silently missed
# them. The enum was checked for status, lane and area and not for this one, which is the whole
# reason it drifted. `specs/bugs-index.md` line 84 is the source.
KIND = {"bug", "perf", "feature", "regression", "apparatus", "programme"}
entries, cur, buf = [], None, []
for ln in lines:
    if ln.startswith("## "):
        if cur is not None: entries.append((cur, "\n".join(buf)))
        cur, buf = ln[3:].strip(), []
    elif cur is not None:
        buf.append(ln)
if cur is not None: entries.append((cur, "\n".join(buf)))

problems, slugs, stale = [], {}, []
# The sentence that means "the code is done, the paperwork is not". Kept narrow on purpose: a
# broad "pending" would match half the board, and a check that cries wolf is not read.
STALE_HINT = re.compile(r"awaits? (?:fresh )?(?:independent )?(?:re)?review|landing SHA|SHA pending|remediation .{0,40}green|fix SHA pending", re.I)
def slug_of(h): return h.split("—")[0].split("-—")[0].strip().split()[0] if h.split() else h

for head, body in entries:
    slug = slug_of(head)
    slugs.setdefault(slug, 0)
    slugs[slug] += 1
    m = re.match(r"\s*<!--(.*?)-->", body, re.S)
    if not m:
        problems.append((slug, "no header comment after the heading")); continue
    # TERMINATED, not merely opened. `(.*?)` is unbounded here, so an entry whose `-->` is missing
    # swallows its own prose and matches the NEXT entry's terminator — the fields parse, the gate
    # passes, and the entry is silently invisible to `bugs-report`, whose own parser stops after 13
    # lines and reports it as MISSING-HEADER. Two entries were in exactly that state on 2026-08-04
    # (both mine, from a sha-rewriting script that dropped the `-->`), passing this gate while no
    # `--status` query could see them. A header is a compact block: a blank line inside the match
    # means the terminator is somewhere it does not belong.
    if "\n\n" in m.group(1):
        problems.append((slug, "header comment is not terminated — no `-->` before the entry body, "
                               "so it ran on into the prose (invisible to bugs-report)")); continue
    fields = {}
    for fm in re.finditer(r"([a-z-]+)\s*:\s*([^\n]+)", m.group(1)):
        # A trailing ` · a | b | c` is a TEMPLATE COMMENT, not part of the value. specs/bugs-index.md
        # spells the enums inside its example header so that copying the example — which is how
        # headers actually get written — carries the allowed values with it. Without this strip that
        # template could not be copied: the parser took the whole rest of the line, and `status:
        # open        · open | fixed | …` failed the enum check. Twice on 2026-08-05 an out-of-enum
        # `area` turned this gate red on clean main for everyone, which is what the template is for.
        fields[fm.group(1)] = fm.group(2).split("·")[0].strip()
    for req in ("status", "lane", "area"):
        if req not in fields: problems.append((slug, f"missing required field `{req}`"))
    st = fields.get("status")
    if st is not None and st not in STATUS:
        problems.append((slug, f"status `{st}` not in {sorted(STATUS)}"))
    if fields.get("lane") not in (None,) and fields["lane"] not in LANE:
        problems.append((slug, f"lane `{fields['lane']}` not in the enum"))
    if fields.get("area") not in (None,) and fields["area"] not in AREA:
        problems.append((slug, f"area `{fields['area']}` not in the enum"))
    if fields.get("kind") is not None and fields["kind"] not in KIND:
        problems.append((slug, f"kind `{fields['kind']}` not in {sorted(KIND)} — see specs/bugs-index.md"))
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
            # A LENGTH-BOUNDED shape rule, and it is not the guard that was deleted above.
            #
            # "The reachability check subsumes the guard completely" is true in a full clone and
            # FALSE in CI, which checks out at `fetch-depth: 1` — there the whole `elif` below is
            # skipped, a pasted run id passes on shape alone, and the gate's own SELF-TEST then
            # fails looking for a message the gate can no longer produce. That is what turned `main`
            # red on every push on 2026-08-02, and it is the same shape as the incident in
            # `project_validate_job_red_on_own_selftests_0728`: a job red on its OWN self-test.
            #
            # The deleted guard was `sha.isdigit()` with no length bound, which rejected the real
            # 9-digit abbreviations `611795277` and `261607982`. A GitHub run id is ELEVEN digits,
            # and git abbreviates to 7-10 in this repo, so the bound separates them. Checked
            # unconditionally, because the environment where it matters most is the one that cannot
            # run the reachability check.
            elif sha.isdigit() and len(sha) >= 11:
                problems.append((slug, f"fixed-in `{sha}` looks like a CI run id, not a commit sha"))
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

    # ── THE INVERSE OF THE `fixed-in` CHECK, and the one that was missing ──────────────────────────
    #
    # Above: a `fixed` entry must cite a sha that is REACHABLE. Here: an OPEN entry that says its fix
    # already exists and is waiting to land, while a commit it cites is ALREADY AN ANCESTOR — i.e.
    # the wait is over and nobody re-read it.
    #
    # Measured 2026-08-09, which is why this exists: twenty-two entries across four boards carried
    # the sentence "remediation is green on the feature branch, but fresh independent rereview and
    # the landing SHA are pending" — written 2026-07-15, still open three weeks later, and every one
    # of their fixes had landed. The shas that DANGLE in those entries are frozen review checkpoints,
    # so a reader who checked the obvious thing saw "nothing landed" and moved on.
    #
    # A REPORT, NOT A FAILURE, deliberately. A cited landed commit is at least as often the one that
    # REPORTED the defect, so this cannot decide staleness — only a human or an agent running the
    # entry's own `gate:` can. Failing the build on a heuristic would train people to ignore it,
    # which is the same reflex a false claim-overlap refusal teaches. It is PRINTED unconditionally
    # and COUNTED, because the failure mode it is about is nobody looking.
    if st == "open" and not SHALLOW and STALE_HINT.search(body):
        for sha in sorted(set(re.findall(r"\b[0-9a-f]{9,40}\b", body)))[:8]:
            if subprocess.run(["git", "merge-base", "--is-ancestor", sha, "HEAD"],
                              capture_output=True).returncode == 0:
                stale.append((slug, sha)); break

# Uniqueness is checked across the CONCATENATION of every file, not per file: after the split a
# slug could otherwise exist twice in two modules and each file would look fine on its own.
for s, n in slugs.items():
    if n > 1: problems.append((s, f"slug appears {n} times — slugs must be unique across all BUGS.md"))

if SHALLOW:
    print("note: shallow clone — `fixed-in` checked for SHAPE only; run in a full clone to verify "
          "each sha resolves.")
print(f"files: {len(paths)}   entries: {len(entries)}   problems: {len(problems)}   stale-looking open entries: {len(stale)}")
for sl, sha in stale[:20]:
    print(f"  STALE? [{sl[:52]}] says the fix awaits landing, but {sha} is already an ancestor")
if len(stale) > 20:
    print(f"  … and {len(stale) - 20} more")
if stale:
    print("  ^ NOT failures. Run each entry's own `gate:` before closing it — a cited landed commit\n"
          "    is often the one that REPORTED the defect.")
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

## bad-unterminated-header — the `-->` is missing, so the header runs into the prose
<!-- status: open
     lane: int
     area: front

Prose starts here with no terminator above it. Before 2026-08-04 this PASSED: the unbounded
`(.*?)` reached the next entry's `-->`. bugs-report saw MISSING-HEADER for the same entry.

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

## bad-kind — an invented kind that no `--kind` query can match
<!-- status: open
     kind: divergence
     lane: int
     area: runtime -->
BAD
  # The STALE-OPEN case, appended rather than inlined because it needs a sha that really IS an
  # ancestor of HEAD — a literal in the heredoc would either dangle or, worse, stop being an ancestor
  # the day someone rewrites history and quietly turn the check off.
  cat >> "$TMP" <<STALE
## stale-open-entry — its fix landed and it still says otherwise
<!-- status: open
     lane: int
     area: runtime -->

Remediation is green on the feature branch, but fresh independent rereview and the landing SHA are
pending. Reported against $(git rev-parse --short=9 HEAD).
STALE
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
  for want in "no header comment" "not in" "requires" "not a commit sha" "not terminated"; do
    if ! printf '%s' "$out" | grep -q "$want"; then
      echo "SELF-TEST FAILED: expected a problem mentioning '$want'"; exit 1
    fi
  done
  # The stale-open report does NOT set the exit code — it is a heuristic and says so — so it needs
  # its own assertion. Without this the check could silently stop reporting and every other line of
  # this self-test would still pass. PROVED: switching the report off fails exactly here.
  #
  # ONLY IN A FULL CLONE, and this is not a loosening. The report itself is guarded by `not SHALLOW`
  # because it asks `git merge-base --is-ancestor`, which a depth-1 clone cannot answer. CI checks
  # out with `actions/checkout@v4` and no `fetch-depth`, so it is always shallow — and the
  # unconditional assertion turned every push RED for an hour while the check it guards was
  # deliberately not running. A self-test must assert what the check DOES in the environment it is
  # in; asserting more makes the gate a liar about itself. Reproduced with `git clone --depth 1`
  # before and after, which is the only control that distinguishes these two states.
  # ONE DECISION, READ BACK — not a second `git rev-parse`. The check already decides shallowness
  # and PRINTS it ("note: shallow clone — …"), so the assertion consumes that instead of recomputing
  # the same fact. Two computations of one fact can disagree, and when they do this assertion demands
  # a report the check deliberately skipped: run 31329355192 failed exactly there while the run an
  # hour later, on the same code, printed "shallow clone — … not asserted" and passed. The comment
  # above already records this turning every push red once; recomputing was why it could happen twice.
  if printf '%s' "$out" | grep -q "note: shallow clone"; then
    echo "--- self-test: shallow clone — the stale-open report is skipped by the check, so it is not asserted"
  elif ! printf '%s' "$out" | grep -q "STALE? \[stale-open-entry\]"; then
    echo "SELF-TEST FAILED: the stale-open report did not name an entry whose fix has landed"; exit 1
  fi
  echo "--- self-test ok (6 planted defects all caught); checking ${#FILES[@]} file(s) ---"
fi

run_check "${FILES[@]}"
rc=$?
if [[ $rc -eq 0 ]]; then echo "bugs-index-gate: OK"; else echo "bugs-index-gate: FAIL"; fi
exit $rc
