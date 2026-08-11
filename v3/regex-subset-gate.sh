#!/usr/bin/env bash
# regex-subset-gate — every pattern that reaches Parser.regex must be one std/parsing/regex.ssc
# can actually parse.
#
# WHY THIS EXISTS. `std/parsing/regex.ssc` replaced a host regex (`java.util.regex`, reached
# through a native string method) with a matcher written in ScalaScript, so that every lane runs
# the same code instead of each host's own dialect. It implements a SUBSET: literals, character
# classes, the shorthand classes, `* + ?`, groups and `$`. It refuses everything else — loudly, at
# run time, with the pattern in the message.
#
# A loud run-time refusal is the right behaviour and the wrong SCHEDULE: it fires when a user runs
# a parser, not when somebody adds the eighth pattern. This gate moves it to commit time. The
# subset is then a checked contract rather than a claim in a comment.
#
# THE AUTHORITY IS THE MODULE, NOT THIS SCRIPT. The check does not re-implement the grammar in
# shell — a second copy of a grammar goes stale in the direction nobody notices. It extracts the
# patterns, hands each to `rxParse` from the module itself, and reports what the module rejects.
# So the gate cannot disagree with the implementation: it IS the implementation, asked a question.
#
#   v3/regex-subset-gate.sh              # check the whole tree
#   v3/regex-subset-gate.sh --root DIR   # check one directory (the self-test uses this)
#   v3/regex-subset-gate.sh --self-test  # prove the gate can tell the two states apart
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
SCAN="$ROOT"
SELFTEST=0

while [ $# -gt 0 ]; do
  case "$1" in
    --root) SCAN="$2"; shift 2 ;;
    --self-test) SELFTEST=1; shift ;;
    *) echo "usage: $0 [--root DIR] [--self-test]" >&2; exit 2 ;;
  esac
done

# `v3/ssc3` FIRST, and this is not a preference. No job in `.github/workflows/v3.yml` builds
# `bin/ssc` — registering a gate that needs it would paint the workflow red on every run for a
# missing launcher, which is the shape that kept `v3.yml` from ever going green across 48 runs.
# `ssc3` is what this workflow already provisions, and it is also the lane the module exists for.
# `bin/ssc` stays as the local fallback so the gate is runnable in a fresh tree either way.
SSC="$ROOT/v3/ssc3"
[ -x "$SSC" ] || SSC="$ROOT/bin/ssc"
MODULE="$ROOT/std/parsing/regex.ssc"
# The probe lives in `v3/` so its import can be a plain relative path to the module. Removed on any
# exit, not only the happy one: a killed run would otherwise leave a stray `.ssc` in a directory
# other gates walk.
PROBE="$ROOT/v3/.regex-subset-probe.$$.ssc"
trap 'rm -f "$PROBE"' EXIT

# The literal shapes a pattern arrives in. The inner alternation keeps an ESCAPED QUOTE from
# ending the match early: `[^\"]*` is one of the real patterns and a naive `"[^"]*"` truncates it
# to `[^\` , which then fails to parse and would make this gate red for its own bad extraction.
EXTRACT='(Parser\.regex|PRegex|\.regex)\("([^"\\]|\\.)*"\)'

extract_patterns() {
  grep -rhoE "$EXTRACT" --include='*.ssc' "$1" 2>/dev/null |
    sed -E 's/^[^(]*\("//; s/"\)$//' |
    sort -u
}

run_check() {
  local scan="$1" pats n
  pats="$(extract_patterns "$scan" || true)"

  if [ -z "$pats" ]; then
    # An empty extraction is not a pass. `pick-gates-by-what-ci-runs` and the extension gate both
    # learned this the same way: a check that silently measures nothing reports green forever.
    echo "  ✋ extracted NO patterns from $scan — the shapes in EXTRACT no longer match, so this"
    echo "     run proves nothing. Fix the extraction rather than trusting the green."
    return 2
  fi

  n="$(printf '%s\n' "$pats" | wc -l | tr -d ' ')"

  {
    echo "[rxParse](../std/parsing/regex.ssc)"
    echo
    echo '```scalascript'
    echo 'def chk(pat: String): Int ='
    echo '  try'
    echo '    rxParse(pat)'
    echo '    0'
    echo '  catch'
    # A BARE binder. `case e: RuntimeException =>` runs on the v2 lane and is refused by v3's front
    # with "a `catch` arm binds one name at Tier 0", so the typed spelling would make this gate a
    # gate that only works where it is not registered. `case e =>` is accepted by both, and is
    # valid Scala besides.
    echo '    case e =>'
    echo '      println("REFUSED " + pat)'
    echo '      1'
    echo
    echo 'var bad = 0'
    # Read line by line: the extracted text is already SOURCE, escapes and all, so it goes back
    # between quotes verbatim — re-escaping would be a second encoding to keep in step with the
    # first. A `printf` over an unquoted expansion would also split `[ \t]*` at its space.
    while IFS= read -r p; do
      [ -n "$p" ] && printf 'bad = bad + chk("%s")\n' "$p"
    done <<< "$pats"
    echo 'println("checked, refused " + bad)'
    echo '```'
  } > "$PROBE"

  local out rc=0
  out="$("$SSC" run "$PROBE" 2>&1)" || rc=$?
  rm -f "$PROBE"

  if [ $rc -ne 0 ]; then
    echo "  ✋ the probe did not run (exit $rc). This is a gate fault, not a verdict:"
    printf '%s\n' "$out" | sed 's/^/     /' | head -20
    return 2
  fi

  local refused
  refused="$(printf '%s\n' "$out" | grep '^REFUSED ' | sed 's/^REFUSED //' || true)"

  if [ -n "$refused" ]; then
    echo "  ✗ $(printf '%s\n' "$refused" | wc -l | tr -d ' ') of $n pattern(s) are outside the subset"
    echo "    std/parsing/regex.ssc can parse. Each one fails LOUDLY at run time today; this gate"
    echo "    is only telling you earlier. Either narrow the pattern or widen the module."
    printf '%s\n' "$refused" | sed 's|^|      /|; s|$|/|'
    return 1
  fi

  echo "  ✓ all $n distinct pattern(s) parse under the supported subset"
  return 0
}

self_test() {
  local tmp fails=0
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN

  # 1 — a tree whose patterns are all inside the subset must PASS.
  mkdir -p "$tmp/ok"
  cat > "$tmp/ok/a.ssc" <<'EOF'
val x = Parser.regex("[a-z]+")
val y = Parser.regex("[ \t]*\n")
EOF
  if run_check "$tmp/ok" > /dev/null 2>&1; then
    echo "  ✓ 1/3 a supported tree passes"
  else
    echo "  ✗ 1/3 a supported tree was REJECTED — the gate refuses what it is supposed to allow"
    fails=$((fails + 1))
  fi

  # 2 — THE NEGATIVE CONTROL. Alternation is outside the subset, so this tree must FAIL. Without
  # this case the gate could be `exit 0` and nobody would know.
  mkdir -p "$tmp/bad"
  cat > "$tmp/bad/a.ssc" <<'EOF'
val x = Parser.regex("[a-z]+")
val y = Parser.regex("cat|dog")
EOF
  if run_check "$tmp/bad" > /dev/null 2>&1; then
    echo "  ✗ 2/3 an ALTERNATION pattern passed — the gate cannot tell the two states apart"
    fails=$((fails + 1))
  else
    echo "  ✓ 2/3 an alternation pattern is refused"
  fi

  # 3 — a tree with no patterns at all must not report success. This is the failure mode where the
  # extraction rots and every later run is a meaningless green.
  mkdir -p "$tmp/empty"
  echo 'val x = 1' > "$tmp/empty/a.ssc"
  local rc=0
  run_check "$tmp/empty" > /dev/null 2>&1 || rc=$?
  if [ $rc -eq 2 ]; then
    echo "  ✓ 3/3 an empty extraction refuses instead of passing"
  else
    echo "  ✗ 3/3 an empty extraction returned $rc — a rotted extraction would read as green"
    fails=$((fails + 1))
  fi

  if [ $fails -eq 0 ]; then
    echo "  regex-subset-gate --self-test: 3/3"
    return 0
  fi
  echo "  regex-subset-gate --self-test: $((3 - fails))/3"
  return 1
}

echo "regex-subset-gate"

if [ ! -x "$SSC" ]; then
  echo "  ✋ no launcher at $SSC — run ./install.sh --dev first"
  exit 2
fi
if [ ! -f "$MODULE" ]; then
  echo "  ✋ $MODULE is missing; there is nothing to check patterns against"
  exit 2
fi

if [ $SELFTEST -eq 1 ]; then
  self_test
  exit $?
fi

run_check "$SCAN"
exit $?
