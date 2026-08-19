#!/usr/bin/env bash
#
# front-capability-count.sh <path-to-front-capability-gate.sh>   — print how many cases are DECLARED
# front-capability-count.sh --selftest                           — prove this reader reads them
#
# WHY THIS IS A FILE AND NOT FOUR LINES INSIDE `front-diff.sh`. The one-sided CEILING in
# `v3/front-diff.sh` is not a constant: it is this number, so that declaring a case both raises the
# ceiling and names the reason in the same edit. That makes the reader below part of the gate's
# arithmetic — and it was wrong for as long as it was invisible. It used to be
#
#     re.search(r"declare -a " + var + r"=\(([^)]*)\)", s, re.S)   →  len(m.group(1).split())
#
# which ends the list at the FIRST closing bracket and counts the WORDS before it. A comment written
# inside the array therefore MOVED THE CEILING: citing a file position in brackets read 23, the same
# note as plain prose read 83, against a true 76 — measured, both, in
# `v3-front-diff-ceiling-is-derived-by-word-counting-and-a-comment-changes-it`. Neither said a word,
# and the upward move is the dangerous one: it admits regressions nobody declared.
#
# Extracted so `--selftest` can plant those exact two shapes and watch the number NOT move.
set -euo pipefail

read_count() {
  python3 - "$1" <<'PY'
import re, sys
try: s = open(sys.argv[1]).read().splitlines()
except OSError: raise SystemExit(1)
tot = 0
for var in ("KNOWN_CONF_V3_ONLY", "KNOWN_CONF_UNIML_ONLY"):
    start = next((i for i, ln in enumerate(s) if re.match(r"\s*declare -a " + var + r"=\(", ln)), None)
    if start is None: raise SystemExit(1)   # a list that moved is not a list of zero
    body = [re.sub(r"#.*", "", s[start].split("=(", 1)[1])]
    for ln in s[start + 1:]:
        ln = re.sub(r"#.*", "", ln)
        if ")" in ln:
            body.append(ln.split(")", 1)[0]); break
        body.append(ln)
    else: raise SystemExit(1)               # unterminated: refuse rather than count a prefix
    names = " ".join(body).split()
    # A NAME, not prose. Anything else means the list is being read wrong, and reading it wrong
    # silently is the whole failure this file exists to stop.
    if any(not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._/-]*", n) for n in names): raise SystemExit(1)
    tot += len(names)
print(tot)
PY
}

if [ "${1:-}" = "--selftest" ]; then
  root="$(cd "$(dirname "$0")/.." && pwd)"
  src="$root/v3/front-capability-gate.sh"
  [ -f "$src" ] || { echo "  FAIL selftest: $src is missing"; exit 1; }
  # A TRAP, not a tidy-up at the end: a leftover temp copy is nobody's to clean but it is everybody's
  # to trip over, and the assertions below exit non-zero on purpose.
  tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
  truth="$(read_count "$src")" || { echo "  FAIL selftest: the real list is unreadable"; exit 1; }

  # PLANTED AT THE DECLARATION, NOT AT A NAME. The first version of this anchored its `sed` on
  # `distributed-failure-partial`, a name that was in the list that day and was REMOVED the next —
  # at which point every plant silently became a no-op, the "bad" file was byte-identical to the
  # good one, and the selftest failed complaining that a clean list had been answered rather than
  # refused. A plant must be anchored on something the thing under test cannot delete; the
  # `declare -a` line is that, since without it there is no list to count.
  plant() { awk -v ins="$2" '{print} /^declare -a KNOWN_CONF_UNIML_ONLY=\(/{print ins}' "$1"; }

  # THE TWO SHAPES THAT MOVED IT. Both are comments, and a comment must not be able to move a ceiling.
  plant "$src" '  # cited at foo.ssc line 424 (right here)' > "$tmp/bracket.sh"
  plant "$src" '  # cited at that file on line four hundred and twenty four' > "$tmp/prose.sh"
  for shape in bracket prose; do
    got="$(read_count "$tmp/$shape.sh")" || got="unreadable"
    if [ "$got" != "$truth" ]; then
      echo "  FAIL selftest: a $shape comment inside the array moved the count $truth -> $got"
      exit 1
    fi
  done

  # AND THE TWO IT MUST REFUSE rather than answer. A prefix of a list is a number, and a number
  # compares — which is exactly how a truncated list passed for a ceiling.
  plant "$src" '  not-a-name-because-of-the-bang!' > "$tmp/prose-token.sh"
  awk '/^declare -a KNOWN_CONF_UNIML_ONLY=\(/{drop=1} drop && /^\)/{next} {print}' "$src" > "$tmp/unterminated.sh"
  for bad in prose-token unterminated; do
    if got="$(read_count "$tmp/$bad.sh" 2>/dev/null)"; then
      echo "  FAIL selftest: a $bad list was answered with $got instead of refused"
      exit 1
    fi
  done

  echo "  ceiling reader OK — $truth declared; two comment shapes do not move it, two broken lists refuse"
  exit 0
fi

[ $# -eq 1 ] || { echo "usage: $0 <front-capability-gate.sh> | --selftest" >&2; exit 2; }
read_count "$1"
