#!/usr/bin/env bash
#
# no-live-backticks-in-heredocs — a backtick inside an UNQUOTED heredoc is a command the shell runs.
#
# THE DEFECT THIS SHIPPED FOR, met as a reader on 2026-08-14 rather than found by reading code.
# `.githooks/pre-push` wrote its claim-overlap refusal into `<<EOF` — unquoted — and the six
# backticked identifiers in that prose were command substitutions:
#
#   `items:`               → command not found: items:
#   `--items`              → command not found: --items
#   `scripts/coord-claim`  → EXECUTED; twenty lines of its usage printed before the message
#   `git show …<slug>.…`   → slug: No such file or directory   (`<slug>` became a redirection)
#
# The five sentences that explain WHY a claim was refused lost every identifier they named, and the
# reader — already asking "did I get the command wrong?" — was shown a command usage block instead.
# That is the worst moment in the system for a message to be corrupt.
#
# WHY A MECHANICAL CHECK FOR WHAT LOOKS LIKE A TYPO. This is the eighth occurrence of the class in
# this project. Seven were an agent's own typing (`git commit -m`, `coord-release --note`), answered
# by `--note-file` in `7bcfab999` and by a written rule; each of those was a slip inside prose that
# was otherwise clean, which is what a rule applied by hand at 95% looks like. This one is
# CHECKED-IN CODE, where no habit reaches it and every agent meets it at the same bad moment.
#
# THE RULE IS NOT "no backticks". It is: if you want a literal backtick in an unquoted heredoc,
# escape it — `\``. Fifteen of the nineteen backticked heredoc lines in this repo already do, in six
# files, which is why this check is green on arrival everywhere except the defect above. If you want
# substitution, you are writing a heredoc that must stay unquoted, and the fix is to move the
# dynamic part OUT into a `printf` and quote the delimiter — which is what `pre-push` now does.
#
# SCOPE, and it is deliberately narrow: an unquoted heredoc body, unescaped backtick, tracked shell
# files only. `<<'EOF'`, `<<"EOF"` and `<<\EOF` are all quoted and are not read.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
echo "── no live backticks inside unquoted heredocs"

# The scanner is a separate file rather than an inline heredoc so that this gate can be run against
# ITSELF without the fixture problem the sibling gate hit: `no-gnu-only-shell-constructs` passed on
# its first run because `git ls-files` could not yet see it. Here the scanner is written to a temp
# path and this file stays in the scan set below, so a regression in this file's own quoting fails
# this gate.
tmp=$(mktemp -d "${TMPDIR:-/tmp}/heredoc-bt.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

cat > "$tmp/scan.py" <<'PY'
import re, sys

# A heredoc opener: `<<WORD` or `<<-WORD`, delimiter unquoted. `<<<` is a herestring, not a heredoc.
# A quoted delimiter (`<<'W'`, `<<"W"`, `<<\W`) disables every expansion, so its body is inert.
OPEN = re.compile(r"<<(-?)(?!<)\s*(\\?)(['\"]?)([A-Za-z_][A-Za-z0-9_]*)\3")


def masked(line):
    """Positions that are inside a quoted string or a comment, and so are not shell syntax.

    THIS EXISTS BECAUSE THE FIRST VERSION WITHOUT IT WAS WRONG, in both directions of the same
    mistake — it read a STRING as SYNTAX:

        printf '<<encode-error: %s>>'                specs/coreir-codec-vectors.sh:55
        echo "the pre-fix spelling (\\`<<EOF\\`) …"    this gate's own control message

    Neither opens anything. Each was taken for an opener with no matching delimiter line, so the
    scanner ran to end-of-file and reported every backticked COMMENT below it — 16 findings in one
    file and 2 in this one, none of them real. Same shape as the sibling gate's comment-stripping
    rule, arrived at the same way: by the check failing on arrival and the output making no sense.
    """
    out = [False] * len(line)
    i, q = 0, None
    while i < len(line):
        c = line[i]
        if q is None:
            if c == "\\":
                out[i] = True
                if i + 1 < len(line):
                    out[i + 1] = True
                i += 2
                continue
            if c in "'\"":
                q = c
                out[i] = True
            elif c == "#" and (i == 0 or line[i - 1].isspace()):
                for k in range(i, len(line)):
                    out[k] = True
                break
        else:
            out[i] = True
            if q == '"' and c == "\\":
                if i + 1 < len(line):
                    out[i + 1] = True
                i += 2
                continue
            if c == q:
                q = None
        i += 1
    return out


def findings(path, lines):
    out, i = [], 0
    while i < len(lines):
        line = lines[i]
        if line.lstrip().startswith("#"):
            i += 1
            continue
        mask = masked(line)
        m = None
        for cand in OPEN.finditer(line):
            if not mask[cand.start()]:
                m = cand           # the LAST real opener on the line owns the body that follows
        if m:
            dash, esc, quote, delim = m.groups()
            quoted = bool(esc) or bool(quote)
            j = i + 1
            while j < len(lines):
                if (lines[j].strip() if dash else lines[j]) == delim:
                    break
                if not quoted:
                    stripped = re.sub(r"\\.", "", lines[j])   # `\`` is a literal, and so is `\\`
                    if "`" in stripped:
                        out.append((j + 1, delim, lines[j].strip()))
                j += 1
            i = j + 1
            continue
        i += 1
    return out

bad = 0
for path in sys.argv[1:]:
    try:
        lines = open(path, encoding="utf-8", errors="replace").read().split("\n")
    except OSError:
        continue
    for ln, delim, text in findings(path, lines):
        bad += 1
        print(f"{path}:{ln}: live backtick inside <<{delim} (unquoted) — {text[:90]}")
sys.exit(1 if bad else 0)
PY

# ── the negative control, first: a green from a check that cannot see is worth nothing (P-6.1b) ──
#
# Both fixtures are written through QUOTED heredocs, so what lands on disk is exactly the spelling
# being tested and this gate's own source stays inert.
mkdir -p "$tmp/fixtures"
cat > "$tmp/fixtures/broken.sh" <<'FIXTURE'
#!/usr/bin/env bash
cat >&2 <<EOF
one of its words until it is released. `scripts/coord-claim` refuses prose since e341d8402, but
EOF
FIXTURE
cat > "$tmp/fixtures/escaped.sh" <<'FIXTURE'
#!/usr/bin/env bash
cat >&2 <<EOF
Build first (\`sbt v2Core/compile\`). Refusing to report green on a tree that was never built.
EOF
FIXTURE
cat > "$tmp/fixtures/quoted.sh" <<'FIXTURE'
#!/usr/bin/env bash
cat >&2 <<'INNER'
one of its words until it is released. `scripts/coord-claim` refuses prose since e341d8402, but
INNER
FIXTURE
# A `<<WORD` that is INSIDE a string opens nothing. Both spellings below are real lines from this
# repo, and both were misread as openers with no closing delimiter by the first version of the
# scanner — which then reported every backticked comment to end-of-file. Kept as a control because a
# rule that fires on a mention teaches people to route around it.
cat > "$tmp/fixtures/mention.sh" <<'FIXTURE'
#!/usr/bin/env bash
printf '<<encode-error: %s>>' "$(head -1 "$WORK/e.err")"
echo "the pre-fix spelling (\`<<EOF\` with a live backtick) is detected"
# a trailing comment mentioning `scripts/coord-claim`, below both, with no heredoc anywhere
FIXTURE

fails=0
if python3 "$tmp/scan.py" "$tmp/fixtures/broken.sh" >/dev/null 2>&1; then
  echo "  ✗ control: the PRE-FIX spelling was not detected — this check cannot fail, so its green means nothing"
  fails=$((fails + 1))
else
  echo "  ✓ control: the pre-fix spelling (\`<<EOF\` with a live backtick) is detected"
fi
for ok in escaped quoted mention; do
  if python3 "$tmp/scan.py" "$tmp/fixtures/$ok.sh" >/dev/null 2>&1; then
    echo "  ✓ control: the $ok form is not a finding"
  else
    echo "  ✗ control: the $ok form was flagged — this rule would teach people to route around it"
    fails=$((fails + 1))
  fi
done

# ── the repository ────────────────────────────────────────────────────────────────────────────────
#
# THE POPULATION IS THE PART THAT GOES WRONG, not the rule. Three times in two days on this project a
# check was correct and its population was not — a mention counted as a caller, a mention counted as
# an execution, ports read out of source instead of processes read out of the machine. This gate's
# first population was a path allow-list (`*.sh` plus `scripts/` plus `tests/` plus `.githooks/`) and
# it scanned 314 of the repository's 348 tracked shell files. The 34 it could not see were the
# extensionless tools OUTSIDE those directories — `bin/ssc`, `v2/ssc`, `v2/ssc1`, `v3/ssc3`, the
# `v1/tools/scripts/launchers/*`. The launcher every agent runs was outside the check.
#
# So the population is now a PROPERTY, not a list of places: tracked, and shell by extension or by
# shebang. `.githooks/*` is named because its files are extensionless AND have no shebang guarantee.
mapfile -t candidates < <(
  git ls-files -z \
    | while IFS= read -r -d '' f; do
        [[ -f "$f" ]] || continue
        case "$f" in *.sh|.githooks/*) printf '%s\n' "$f"; continue ;; esac
        # An extension is a property of the BASENAME. Testing the whole path would skip
        # `some.dir/tool`, which is how a population quietly loses a file.
        case "${f##*/}" in *.*) continue ;; esac
        IFS= read -r first < "$f" 2>/dev/null || continue
        case "$first" in '#!'*sh|'#!'*bash|'#!'*"env sh"|'#!'*"env bash") printf '%s\n' "$f" ;; esac
      done
)
echo "  · scanning ${#candidates[@]} tracked shell files"
case " ${candidates[*]} " in
  *" tests/e2e/no-live-backticks-in-heredocs.sh "*) echo "  ✓ this file is in its own scan set" ;;
  *) echo "  ✗ this file is NOT in its own scan set — the rule cannot police its own quoting"
     fails=$((fails + 1)) ;;
esac

if out=$(python3 "$tmp/scan.py" "${candidates[@]}" 2>&1); then
  echo "  ✓ no live backticks in any unquoted heredoc"
else
  printf '%s\n' "$out" | sed 's/^/  ✗ /'
  echo "    Fix: move the dynamic part out into a printf and quote the delimiter (<<'EOF'), or"
  echo "         escape the backtick (\\\`) if it is meant to be literal."
  fails=$((fails + 1))
fi

if [[ $fails -eq 0 ]]; then echo "✓ no-live-backticks-in-heredocs PASSED"; exit 0; fi
echo "✗ no-live-backticks-in-heredocs: $fails failure(s)"
exit 1
