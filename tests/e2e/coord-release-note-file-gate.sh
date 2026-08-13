#!/usr/bin/env bash
# `coord-release --note-file` must deliver the note BYTE FOR BYTE.
#
# WHAT THIS IS GUARDING. A release note is the durable record of a piece of work, it is long, and it
# is full of `identifiers in backticks` because that is how this repo writes about code. Passed
# inline as --note "…`foo`…" the SHELL runs the backticked text as a command and substitutes its
# output, so the note that lands is missing words — five times here, once eating four words out of
# an already-pushed note that then had to be amended and force-pushed.
#
# THE TEST IS THE PAYLOAD, not the plumbing: a note containing backticks, a `$VAR`, a `$(cmd)` and a
# backslash must come back out unchanged. Any implementation that routes the note through another
# round of shell evaluation fails on one of those four, and they fail for DIFFERENT wrong
# implementations — backticks and $(cmd) catch command substitution, $VAR catches parameter
# expansion, and the backslash catches an `echo` that interprets escapes.
set -euo pipefail
ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
REL="$ROOT/scripts/coord-release"
[[ -x $REL ]] || { echo "coord-release-note-file: no $REL" >&2; exit 2; }

tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
fails=0
ok()   { printf '  ok    %s\n' "$1"; }
bad()  { printf '  FAIL  %s\n' "$1" >&2; fails=1; }

# The payload. `date` and `whoami` are chosen because they SUCCEED — a command substitution that
# ran would leave real output here, not an error, which is exactly how the original defect hid.
# The payload deliberately names a SCRIPT IN THIS REPO, because that is what a real release note
# does — and see the control at the bottom for what happened the first time this file evaluated it.
PAYLOAD='level 3: `scripts/smoke-ci` 86/86 and `$HOME` untouched; ran `date` and $(whoami); path a\b'
printf '%s\n' "$PAYLOAD" > "$tmp/note.txt"

# 1. The note survives the flag. Driven through the argument parser only — the parser is the part
#    that can corrupt it, and running a real release would need a repo state this gate must not
#    invent. `--level` is deliberately absent so the script exits before touching any file, which
#    also proves the read happens BEFORE any side effect.
# WHAT THIS CHECK PROVES AND WHAT IT DOES NOT. It proves the flag READS the file and gets past note
# handling — with a readable file and no --level, the script must reach the `--level is required`
# check and complain about THAT, naming no note problem. It does not print the note back, so byte
# fidelity is not observable here; that comes from construction (`extra="$(cat -- "$2")"` performs
# no expansion on what it read) plus the control at the bottom, which shows the inline path IS the
# one that corrupts. Saying so beats a check that looks stronger than it is.
# The FIRST line only: the usage block printed after it necessarily mentions --note-file, so
# scanning the whole output matches the help text and never the complaint. (Caught by this gate
# failing on a correct implementation, which is the useful direction for a gate to fail in.)
out=$(head -1 <<< "$("$REL" some-slug --note-file "$tmp/note.txt" 2>&1 || true)")
if grep -q 'level is required' <<< "$out" && ! grep -qiE 'note file|--note' <<< "$out"; then
  ok "--note-file reads a real file and raises no note complaint"
else
  bad "--note-file did not get past note handling: $(head -1 <<< "$out")"
fi

# CHECKS 2-4 ASSERT THE MESSAGE, NEVER MERELY A NON-ZERO EXIT, and the first draft of this gate got
# that wrong in a way worth recording: `coord-release` refuses to run outside the shared checkout,
# so every invocation from a worktree exits non-zero ANYWAY. Driven against three deliberately
# broken implementations — readability check deleted, empty check deleted, both-flags check deleted
# — the exit-code version passed all three. The subject was reachable without the thing tested.
say() { # say <label> <expected-substring> <args...>
  local label=$1 want=$2; shift 2
  local got; got=$(head -1 <<< "$("$REL" "$@" 2>&1 || true)")
  if grep -qF "$want" <<< "$got"; then ok "$label"; else bad "$label — got: $got"; fi
}

# 2. A missing file is refused, not silently released with an empty note.
say "a missing note file stops the release" \
    "cannot read note file" some-slug --level 3 --note-file "$tmp/nope.txt"

# 3. An EMPTY file is refused too — an empty note is the failure this flag exists to prevent.
: > "$tmp/empty.txt"
say "an empty note file stops the release" \
    "note file is empty" some-slug --level 3 --note-file "$tmp/empty.txt"

# 4. --note and --note-file together are refused rather than one silently winning.
say "--note with --note-file is refused" \
    "use --note OR --note-file, not both" some-slug --level 3 --note "inline" --note-file "$tmp/note.txt"

# 4b. And in the other ORDER, because the two flags guard each other and a guard written on only one
#     side passes this suite while leaving the real hole.
say "--note-file with --note is refused (reverse order)" \
    "use --note OR --note-file, not both" some-slug --level 3 --note-file "$tmp/note.txt" --note "inline"

# 5. THE CONTROL, and the reason this gate is worth having: a note passed INLINE the way a caller
#    naturally would is corrupted by the shell before coord-release ever sees it. If this stops
#    being true the trap is gone and the flag is no longer load-bearing.
#
#    IT USES ITS OWN, HARMLESS PAYLOAD, and the first version did not — it evaluated $PAYLOAD above,
#    which names `scripts/smoke-ci`, so the control RAN THE WHOLE SMOKE SUITE and the gate died on
#    its own 60 s budget. That is the defect demonstrating itself inside the test written to catch
#    it, and it is exactly why a real release note must never reach an evaluating context: the
#    backticks in one are the names of things in this repo, and some of them are runnable.
CONTROL='a note mentioning `echo SUBSTITUTED` and $USER'
inline=$(eval "printf '%s' \"$CONTROL\"" 2>/dev/null || true)
if [[ "$inline" == "$CONTROL" ]]; then
  bad "control: an inline double-quoted note was NOT corrupted — re-check what this gate protects"
else
  ok "control: the same note passed inline IS corrupted by the shell"
fi

[[ $fails -eq 0 ]] && echo "coord-release-note-file: PASS" || { echo "coord-release-note-file: FAIL" >&2; exit 1; }
