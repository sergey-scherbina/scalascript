#!/usr/bin/env bash
#
# inbox-gate — INBOX.md is a QUEUE, and a queue with no invariants is a graveyard (POLICY.md P-3.10).
#
# What it checks, and why each one is here rather than left to care:
#
#   1. every entry has the fields the queue exists FOR. `reported-by` is what makes `confirmed: no`
#      ("fixed, but the reporter has not confirmed") answerable; `ssc-version` is what makes a report
#      reproducible — and `unknown` is a legitimate value there, since a version the reporter could
#      not obtain is a fact about the report rather than a hole in it. Only ABSENCE is refused: an
#      entry where nobody recorded either way reads later as "nobody asked".
#   2. `triage` is `new` or `needs-info` and nothing else. `routed` is NOT a value: a routed entry has
#      MOVED to a module board, so a routed entry still sitting here is two copies of one record —
#      the failure the whole policy exists to prevent.
#   3. `lane:` / `area:` are ABSENT — and this is about AUTHORITY, not about what a reporter is
#      allowed to say. Those two carry the routing DECISION, whose authority order P-3.3 fixes. The
#      reporter's own diagnosis is welcome and goes in `reporter-suspects:`, which this gate accepts
#      and never treats as routing. Users told us on 2026-07-31 that the old wording read as "do not
#      diagnose"; it was never meant to, and the mechanical split is what makes the difference real
#      rather than a matter of etiquette.
#   4. slugs are unique across INBOX.md and every board — the invariant `bugs-index` already keeps,
#      extended to the one file that can feed into all of them.
#   5. `needs-info` carries `waiting-on`. Otherwise it is indistinguishable from "forgotten".
#   6. AGE IS REPORTED, and past a threshold it FAILS. This is the one check that is about the queue
#      rather than the entries: a report nobody rejects and nobody routes is a report that was lost
#      politely. Default 14 days, `SSC_INBOX_MAX_AGE_DAYS` to override.
#
#   7. NOTHING IS STRANDED OUTSIDE THE REPO. Open `user-report` issues whose URL appears nowhere —
#      not in INBOX.md, not as a `reported-by:` on any board — are reports the age bound cannot see.
#      Without this the queue's own time limit only governs what already got imported, so "lost
#      politely" simply relocates to GitHub, which is the failure the queue exists to remove rather
#      than move. Needs `gh`; without it the check SKIPS LOUDLY (see below) instead of passing.
#
# WHAT IT DOES NOT READ, stated because a filter that is silent about its scope looks complete and is
# not (P-6): it reads INBOX.md and the `## <slug>` headings of tracked board files. It does NOT
# verify that a routed report actually reached a board — nothing here can, because a routed entry
# leaves no trace in this file by design. That property is checked from the other end: every entry
# anywhere carrying `reported-by` came from a user, so
# `git grep -l 'reported-by:' -- '*BUGS.md' '*BACKLOG.md'` is the routed set. BOTH GLOBS: a report
# whose kind is `feature` routes to a BACKLOG, which `specs/work-tracking-layout.md` explicitly
# permits, and this line used to name only `*BUGS.md`. It therefore could not see any of them —
# measured 2026-08-07, when BACKLOG.md already carried one such entry and rozum's three tui-fetch
# reports were about to become the next. Three copies of this derivation exist (here, the note
# printed below, and `specs/bugs-index.md`) and two of the three were narrow.
#
# And when `gh` is missing or unauthenticated, check 7 prints what it could not do and the run says
# so in its final line. A network check that silently becomes a no-op is worse than one that is
# absent: absent, somebody notices.
#
# Usage: tests/e2e/inbox-gate.sh [--self-test]
# Exit:  0 ok · 1 an invariant is broken · 2 usage/environment.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MAX_AGE_DAYS="${SSC_INBOX_MAX_AGE_DAYS:-14}"
# Overridable so the self-test can exercise BOTH branches without surgery on PATH. Emptying PATH
# would also hide `git`, `awk` and `date` from this script, which kills it long before the branch
# under test — the first version of that case failed for exactly that reason. Same idiom as
# `SSC_CI_GH` in tests/e2e/ci-status-guard.sh.
GH="${SSC_INBOX_GH:-gh}"
self_test=0
case "${1:-}" in
  --self-test) self_test=1 ;;
  "") : ;;
  *) printf 'usage: %s [--self-test]\n' "${BASH_SOURCE[0]}" >&2; exit 2 ;;
esac

fail=0
bad()  { printf 'FAIL  %s\n' "$*" >&2; fail=1; }
note() { printf '  %s\n' "$*"; }

# Extract the queue region of a given INBOX file, so the self-test can run the SAME parser over a
# fabricated one. A gate whose self-test exercises a copy of the logic proves nothing about the
# logic that runs.
queue_region() {
  awk '
    /<!-- inbox-entries:start/ { inq = 1; next }
    /<!-- inbox-entries:end/   { inq = 0 }
    inq { print }
  ' "$1"
}

# One record per entry, fields separated by ASCII US (\x1f) — NOT tab.
#
# Tab is an IFS *whitespace* character, so `read` collapses a run of them into one delimiter and an
# EMPTY field vanishes, shifting every later field left. Found by this file's own --self-test: the
# `lane:` case did not trip because `banned` had shifted into `waiting-on`, and — worse — the
# `needs-info without waiting-on` case was tripping off a value that had shifted INTO `waiting-on`,
# i.e. passing for the wrong reason. A non-whitespace separator keeps empty fields.
parse_entries() {
  queue_region "$1" | awk '
    function flush(   ) {
      if (slug != "") {
        printf "%s\037%s\037%s\037%s\037%s\037%s\037%s\037%s\n",
               slug, t, rb, ra, sv, rp, wo, banned
      }
      slug = ""; t = ""; rb = ""; ra = ""; sv = ""; rp = ""; wo = ""; banned = ""
    }
    /^## / { flush(); slug = $2; next }
    slug != "" {
      for (i = 1; i <= NF; i++) {
        if ($i == "triage:")      t  = $(i+1)
        if ($i == "reported-at:") ra = $(i+1)
        if ($i == "ssc-version:") sv = $(i+1)
        if ($i == "repro:")       rp = $(i+1)
        if ($i == "reported-by:") { rb = $(i+1); for (j = i+2; j <= NF; j++) { if ($j == "-->") break; rb = rb " " $j } }
        if ($i == "waiting-on:")  wo = "yes"
        if ($i == "lane:" || $i == "area:") banned = banned (banned ? "," : "") substr($i, 1, length($i)-1)
      }
    }
    END { flush() }
  '
}

# Returns non-zero when it finds a problem. It must NOT rely on the global `fail`: the self-test
# calls it inside a condition, and a call in a subshell (or one whose result is only observable
# through a global) cannot report anything back. The first version did exactly that and its
# self-test caught it — six refusals reported as "did NOT trip".
check_file() {  # check_file <inbox path> <label> -> 0 ok, 1 problem
  local file="$1" label="$2" entries n=0
  local fail=0
  entries="$(parse_entries "$file")"
  if [ -z "$entries" ]; then
    printf '%s: queue is EMPTY — nothing waiting\n' "$label"
    return 0
  fi
  local today_epoch; today_epoch="$(date -u +%s)"
  while IFS=$'\037' read -r slug triage rb ra sv rp wo banned; do
    [ -n "$slug" ] || continue
    n=$((n + 1))
    case "$triage" in
      new|needs-info) : ;;
      "")   bad "$label: $slug has no triage: field" ;;
      # `routed` is the interesting refusal: it means someone recorded the routing here instead of
      # moving the entry, which leaves the record in two places.
      routed) bad "$label: $slug is triage: routed — a routed entry MOVES to the module board and is deleted from here (two copies is the defect this queue avoids)" ;;
      *)    bad "$label: $slug has triage: $triage (expected new or needs-info)" ;;
    esac
    [ -n "$rb" ] || bad "$label: $slug has no reported-by — nothing can tell them when it is fixed"
    # The FIELD must be present; `unknown` is a perfectly good value and `inbox-add` writes it when
    # the reporter could not supply one. What is refused is silence — an entry where nobody recorded
    # whether the version is known, which reads later as "nobody asked".
    [ -n "$sv" ] || bad "$label: $slug has no ssc-version field (write \`unknown\` if it is not known — absence is the only thing refused)"
    [ -n "$rp" ] || bad "$label: $slug has no repro: field (use 'none' if there is no case)"
    [ -z "$banned" ] || bad "$label: $slug carries $banned — those two fields carry the ROUTING DECISION (P-3.3), which an inbox entry has not reached. This is NOT a limit on what the reporter may say: their diagnosis is welcome and belongs in reporter-suspects: and the body."
    if [ "$triage" = "needs-info" ] && [ -z "$wo" ]; then
      bad "$label: $slug is needs-info with no waiting-on: — indistinguishable from forgotten"
    fi
    if [ -z "$ra" ]; then
      bad "$label: $slug has no reported-at"
    else
      local e age
      e="$(date -u -j -f %Y-%m-%d "$ra" +%s 2>/dev/null || date -u -d "$ra" +%s 2>/dev/null || echo "")"
      if [ -z "$e" ]; then
        bad "$label: $slug has an unparseable reported-at: $ra (want YYYY-MM-DD)"
      else
        age=$(( (today_epoch - e) / 86400 ))
        if [ "$age" -gt "$MAX_AGE_DAYS" ]; then
          bad "$label: $slug has been waiting ${age}d (limit ${MAX_AGE_DAYS}d) — route it, ask the reporter, or close it"
        else
          printf '  ok   %-34s %s  waiting %sd\n' "$slug" "$triage" "$age"
        fi
      fi
    fi
  done <<< "$entries"
  printf '%s: %d entr%s\n' "$label" "$n" "$([ "$n" -eq 1 ] && echo y || echo ies)"
  return "$fail"
}

if [ "$self_test" -eq 1 ]; then
  # Prove each refusal FIRES before trusting the green. Built by feeding the real parser a fabricated
  # INBOX, one defect at a time — a gate nobody has seen fail is a hypothesis (P-6.1).
  lab="$(mktemp -d)"; trap 'rm -rf "$lab"' EXIT
  st_fail=0
  expect_red() {  # expect_red <name> <entry text>
    local name="$1" entry="$2"
    { echo '<!-- inbox-entries:start -->'; printf '%s\n' "$entry"; echo '<!-- inbox-entries:end -->'; } > "$lab/INBOX.md"
    if check_file "$lab/INBOX.md" selftest >/dev/null 2>&1; then
      printf 'FAIL  --self-test: %s did NOT trip the gate\n' "$name" >&2; st_fail=1
    else
      printf '  ok   --self-test trips on: %s\n' "$name"
    fi
  }
  old="$(date -u -j -v-400d +%Y-%m-%d 2>/dev/null || date -u -d '400 days ago' +%Y-%m-%d)"
  hdr='<!-- triage: new
     reported-by: someone
     reported-at: 2026-07-31
     ssc-version: 1.0.0
     repro: none -->'
  expect_red "triage: routed left in the queue"  "## a-slug — s
<!-- triage: routed
     reported-by: x
     reported-at: 2026-07-31
     ssc-version: 1.0.0
     repro: none -->"
  expect_red "missing reported-by"               "## b-slug — s
<!-- triage: new
     reported-at: 2026-07-31
     ssc-version: 1.0.0
     repro: none -->"
  expect_red "missing ssc-version"               "## c-slug — s
<!-- triage: new
     reported-by: x
     reported-at: 2026-07-31
     repro: none -->"
  expect_red "needs-info without waiting-on"     "## d-slug — s
<!-- triage: needs-info
     reported-by: x
     reported-at: 2026-07-31
     ssc-version: 1.0.0
     repro: none -->"
  expect_red "a triaged conclusion (lane:) left here" "## e-slug — s
<!-- triage: new
     lane: native
     reported-by: x
     reported-at: 2026-07-31
     ssc-version: 1.0.0
     repro: none -->"
  expect_red "an entry older than the limit"     "## f-slug — s
<!-- triage: new
     reported-by: x
     reported-at: $old
     ssc-version: 1.0.0
     repro: none -->"
  # The reporter's diagnosis must be ACCEPTED. This case is the mechanical half of "say anything you
  # like": if `reporter-suspects:` ever started tripping the same check as `lane:`, the policy would
  # have quietly reverted to the one users complained about, and nothing else would notice.
  { echo '<!-- inbox-entries:start -->'; printf '## h-slug — s
<!-- triage: new
     reported-by: x
     reported-at: 2026-07-31
     ssc-version: unknown
     repro: none
     reporter-suspects: the Map desugaring, not the List one
     impact: blocks -->
'; echo '<!-- inbox-entries:end -->'; } > "$lab/INBOX.md"
  if check_file "$lab/INBOX.md" selftest >/dev/null 2>&1; then
    printf '  ok   --self-test ACCEPTS a reporter diagnosis (reporter-suspects + impact)\n'
  else
    printf 'FAIL  --self-test: a reporter diagnosis was REJECTED — the queue is refusing information again\n' >&2; st_fail=1
  fi

  # …and the green direction, so the gate is not merely a machine that always says red.
  { echo '<!-- inbox-entries:start -->'; printf '## g-slug — s\n%s\n' "$hdr"; echo '<!-- inbox-entries:end -->'; } > "$lab/INBOX.md"
  if check_file "$lab/INBOX.md" selftest >/dev/null 2>&1; then
    printf '  ok   --self-test accepts a well-formed entry\n'
  else
    printf 'FAIL  --self-test: a WELL-FORMED entry was rejected\n' >&2; st_fail=1
  fi
  # Check 7 gets a STUBBED `gh` rather than a live one: a self-test that depends on what happens to
  # be open on GitHub today asserts nothing reproducible, and a network round-trip in a self-test is
  # a flake waiting to be blamed on the code. The stub emits one issue that is old and referenced
  # nowhere — the exact shape the check exists to catch.
  stub="$lab/bin"; mkdir -p "$stub"
  old_iso="$(date -u -j -v-400d +%Y-%m-%d 2>/dev/null || date -u -d '400 days ago' +%Y-%m-%d)"
  cat > "$stub/gh" <<STUB
#!/bin/sh
case "\$*" in
  *"auth status"*) exit 0 ;;
  *"issue list"*)  printf '99999\t%s\t%s\n' "https://example.invalid/issues/99999" "$old_iso" ;;
  *) exit 0 ;;
esac
STUB
  chmod +x "$stub/gh"
  st_out="$(SSC_INBOX_GH="$stub/gh" bash "$0" 2>&1 || true)"
  case "$st_out" in
    *"is in NEITHER the queue nor a board"*)
      printf '  ok   --self-test trips on: an open issue that reached neither the queue nor a board\n' ;;
    *)
      printf 'FAIL  --self-test: a stranded user-report issue did NOT trip the gate\n' >&2; st_fail=1 ;;
  esac

  # And the missing-`gh` path must SKIP LOUDLY. A network check that quietly becomes a no-op is
  # worse than an absent one, because absence gets noticed.
  sk_out="$(SSC_INBOX_GH="$lab/no-such-gh" bash "$0" 2>&1 || true)"
  case "$sk_out" in
    *"SKIPPED — no"*) printf '  ok   --self-test says so out loud when `gh` is unavailable\n' ;;
    *) printf 'FAIL  --self-test: a missing `gh` did not announce itself\n' >&2; st_fail=1 ;;
  esac

  [ "$st_fail" -eq 0 ] || { echo 'inbox-gate --self-test: FAILED' >&2; exit 1; }
  echo '--self-test: every refusal fires, and a valid entry passes'
fi

cd "$ROOT"
[ -f INBOX.md ] || { echo 'inbox-gate: INBOX.md not found' >&2; exit 2; }

echo "== INBOX.md =="
check_file "$ROOT/INBOX.md" "inbox" || fail=1

# Slug uniqueness across the queue AND every board — a report that routes later must not collide.
# An ENTRY is a `## ` heading whose next non-blank line opens an HTML comment — the same signal
# `tests/e2e/bugs-index-gate.sh` uses when it reports "no header comment after the heading". Using
# the heading alone counted prose sections as entries and produced six false duplicates on the first
# run (`## Wallet …`, `## 2026-07-27 …`). Duplicated logic needs ONE vocabulary on both sides (P-6),
# so this recognises an entry the way the gate that owns entries recognises one.
board_slugs() {
  local f
  for f in $(git ls-files '*BUGS.md' '*BACKLOG.md' 2>/dev/null); do
    awk '
      /^## / { pending = $2; next }
      pending != "" && $0 ~ /^[[:space:]]*$/ { next }
      pending != "" { if ($0 ~ /^[[:space:]]*<!--/) print pending; pending = "" }
    ' "$f"
  done
}
dupes="$(
  { parse_entries "$ROOT/INBOX.md" | cut -d$'\037' -f1
    board_slugs
  } | grep -v '^$' | sort | uniq -d
)"
if [ -n "$dupes" ]; then
  while read -r d; do
    [ -n "$d" ] && bad "slug $d exists in more than one of INBOX.md / the boards"
  done <<< "$dupes"
fi

# ── 7. open user-report issues that reached neither the queue nor a board ─────
issue_note="not checked"
if command -v "$GH" >/dev/null 2>&1; then
  issues="$("$GH" issue list --label user-report --state open --json number,url,createdAt \
              --jq '.[] | "\(.number)\t\(.url)\t\(.createdAt[0:10])"' 2>/dev/null || true)"
  if [ -z "$issues" ] && ! "$GH" auth status >/dev/null 2>&1; then
    issue_note="SKIPPED — \`gh\` is present but not authenticated"
    printf '  ??   open user-report issues: %s\n' "$issue_note"
  else
    stranded=0 seen=0
    # A report is "landed" if its URL appears anywhere tracked: in the queue, or as the
    # `reported-by:` of an entry that has already been routed to a board.
    while IFS=$'\t' read -r num url created; do
      [ -n "$num" ] || continue
      seen=$((seen + 1))
      if git grep -q -F -- "$url" -- 'INBOX.md' '*BUGS.md' '*BACKLOG.md' 2>/dev/null; then
        printf '  ok   issue #%-6s landed\n' "$num"
      else
        age_e="$(date -u -j -f %Y-%m-%d "$created" +%s 2>/dev/null || date -u -d "$created" +%s 2>/dev/null || echo "")"
        age="?"
        [ -n "$age_e" ] && age=$(( ( $(date -u +%s) - age_e ) / 86400 ))
        if [ "$age" != "?" ] && [ "$age" -gt "$MAX_AGE_DAYS" ]; then
          bad "issue #$num has been open ${age}d and is in NEITHER the queue nor a board: $url"
          note "import it: scripts/inbox-add --from-issue $num"
          stranded=$((stranded + 1))
        else
          printf '  ??   issue #%-6s not imported yet (%sd) — %s\n' "$num" "$age" "$url"
        fi
      fi
    done <<< "$issues"
    issue_note="$seen open, $stranded past the ${MAX_AGE_DAYS}d limit"
  fi
else
  issue_note="SKIPPED — no \`gh\` on PATH"
  printf '  ??   open user-report issues: %s\n' "$issue_note"
fi

echo
if [ "$fail" -eq 0 ]; then
  echo "inbox-gate: OK  (user-report issues: $issue_note)"
  note "not checked here: that a routed report reached a board. Routed entries leave no trace in"
  note "INBOX.md by design; the routed set is"
  note "\`git grep -l 'reported-by:' -- '*BUGS.md' '*BACKLOG.md'\` — a \`feature\` report routes to a BACKLOG."
else
  echo "inbox-gate: FAILED" >&2
fi
exit "$fail"
