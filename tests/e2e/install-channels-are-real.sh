#!/usr/bin/env bash
#
# install-channels-are-real — we may only tell a user to run an install command that can work.
#
# THREE THINGS WERE WRONG AT ONCE and each is a row here.
#
#   1. `releases/install.sh` downloaded `ssc.jar` at a hardcoded `0.1.0`. No release has EVER
#      published an `ssc.jar` — v0.1.0 and v0.1.1 both ship three native binaries plus their
#      tarballs — so the installer fetched a 404, and the version constant was stale on top of it.
#   2. `install.sh`, `docs/user-guide.md` and `docs/getting-started-standalone.md` offered a coursier
#      channel at `releases.scalascript.io`, a `scalascript/tap` Homebrew tap, and
#      `get.scalascript.io`. Measured 2026-08-18: neither domain resolves, the tap is a 404, and
#      `io/scalascript/` is a 404 on Maven Central.
#   3. `specs/arch-ssc-new.md` recorded the coursier channel as "✓ Landed".
#
# WHAT THIS CHECKS IS INTERNAL CONSISTENCY, ON PURPOSE — no network. A gate that curls a domain is
# red when GitHub has a bad afternoon, and this repo has already paid a day to that shape. The two
# facts it compares are both in the tree: what `native-release.yml` PUBLISHES, and what the installer
# and the docs ASK A USER TO RUN.
#
# The forbidden hosts are banned only in COMMAND position — a line starting with `cs`, `brew` or
# `curl`. Prose that explains why a channel does not exist has to stay legal, or the correction
# itself would trip the gate that the correction exists to protect.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
fails=0
workflow="$ROOT/.github/workflows/native-release.yml"
installer="$ROOT/releases/install.sh"

# The publishing side: every `artifact_id:` the release matrix builds.
published=$(grep -oE '^ *artifact_id: *[A-Za-z0-9_.-]+' "$workflow" | awk '{print $NF}' | sort -u)
# The consuming side: every artifact id the installer can select.
selected=$(grep -oE 'ARTIFACT=[A-Za-z0-9_.-]+' "$installer" | cut -d= -f2 | sort -u)

echo "── the installer offers exactly the artifacts the release publishes"
if [[ -z "$published" ]]; then
  echo "  ✗ no artifact_id found in native-release.yml — this gate stopped being able to look" >&2
  fails=$((fails + 1))
elif [[ "$published" == "$selected" ]]; then
  echo "  ✓ $(printf '%s' "$published" | tr '\n' ' ')"
else
  echo "  ✗ published and selected disagree:"
  diff <(printf '%s\n' "$published") <(printf '%s\n' "$selected") | sed 's/^/      /'
  fails=$((fails + 1))
fi

# `ssc.jar` is the exact asset the old installer fetched and no release has ever produced one.
echo "── the installer fetches only assets a release actually publishes"
# COMMENTS ARE NOT INSTRUCTIONS. The installer's own header explains that it used to fetch
# `ssc.jar`, and a blunt match on the file made that explanation trip the check that the explanation
# exists to protect — the same trap as the prose/command split below.
if grep -vE '^[[:space:]]*#' "$installer" | grep -q 'ssc\.jar'; then
  echo "  ✗ releases/install.sh mentions ssc.jar — the release publishes <id>, <id>.tar.gz and .sha256"
  fails=$((fails + 1))
else
  echo "  ✓ no ssc.jar"
fi

# A hardcoded release version is what went stale. `/releases/latest/download/` needs none.
echo "── the installer carries no release version to go stale"
if grep -qE '^[^#]*(SSC_VERSION|VERSION)="?\$?\{?[A-Za-z_]*:-[0-9]+\.[0-9]+\.[0-9]+' "$installer"; then
  echo "  ✗ releases/install.sh defaults to a hardcoded version:"
  grep -nE '^[^#]*(SSC_VERSION|VERSION)="?\$?\{?[A-Za-z_]*:-[0-9]+\.[0-9]+\.[0-9]+' "$installer" |
    sed 's/^/      /'
  fails=$((fails + 1))
else
  echo "  ✓ the default follows /releases/latest/download"
fi

# ── no command anywhere tells a user to use a channel we do not publish ──────────────────────────
#
# Scoped to the files a user is actually pointed at. `specs/` is design history and says explicitly
# that these are unbuilt phases, so a command inside a phase description there is a plan, not an
# instruction.
dead_hosts='releases\.scalascript\.io|get\.scalascript\.io|scalascript/tap'
targets=(
  "$ROOT/install.sh"
  "$ROOT/README.md"
  "$ROOT/releases/install.sh"
)
while IFS= read -r f; do targets+=("$f"); done < <(find "$ROOT/docs" -name '*.md' 2>/dev/null)

echo "── no live install command points at a channel that does not exist"
# ONE grep PASS, not a bash loop over every line of every doc. The loop cost 43 s of the smoke
# budget for a check that reads a few hundred lines; `grep` does the same work in milliseconds and
# the pattern says the rule outright: optional indent, an optional shell prompt, then the tool.
cmd_re='^[[:space:]]*[$#]?[[:space:]]*(cs|brew|curl)[[:space:]]'
offenders=$(grep -hnE "$cmd_re" "${targets[@]}" 2>/dev/null | grep -E "$dead_hosts" || true)
if [[ -n "$offenders" ]]; then
  echo "  ✗ install commands naming a channel that does not exist:"
  printf '%s\n' "$offenders" | sed 's/^/      /'
  fails=$((fails + 1))
else
  echo "  ✓ none in install.sh, README.md, releases/ or docs/"
fi

# ── self-test: the row above must be able to SEE an offender ─────────────────────────────────────
#
# Without this, "no offenders" and "the scan is broken" print the same line. The plant is the exact
# command that was in `install.sh` until today.
probe=$(mktemp -d "${TMPDIR:-/tmp}/install-channels.XXXXXX")
trap 'rm -rf "$probe"' EXIT HUP INT TERM
printf 'A doc that explains why releases.scalascript.io does not exist is fine.\n\n  brew install scalascript/tap/ssc\n' > "$probe/planted.md"
planted=$(grep -hnE "$cmd_re" "$probe/planted.md" 2>/dev/null | grep -cE "$dead_hosts" || true)
if [[ "$planted" == "1" ]]; then
  echo "  ✓ self-test: exactly one line of the plant is caught — the command, not the prose beside it"
else
  echo "  ✗ self-test: the scan caught $planted of the plant, expected exactly 1 — every green above is meaningless" >&2
  fails=$((fails + 1))
fi

echo
if [[ "$fails" -ne 0 ]]; then echo "install-channels-are-real: FAIL ($fails)" >&2; exit 1; fi
echo "install-channels-are-real: PASS"
