#!/usr/bin/env bash
#
# `scripts/coord-status` must distinguish the claim mutex's required LEDGER.tsv from genuinely
# invalid active markers. Run the real status script in a throwaway repository so the classification
# is tested end to end without depending on the live repository's claims or GitHub state.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LAB="$(mktemp -d "${TMPDIR:-/tmp}/coord-status-ledger-marker.XXXXXX")"
trap 'rm -rf "$LAB"' EXIT HUP INT TERM
fail=0

check() { # check <label> <expected> <actual>
  local label="$1" expected="$2" actual="$3"
  if [[ "$actual" == "$expected" ]]; then
    printf 'PASS  %s\n' "$label"
  else
    printf 'FAIL  %s\n        expected=%q\n        got=%q\n' \
      "$label" "$expected" "$actual"
    fail=1
  fi
}

mkdir -p "$LAB/repo/scripts" "$LAB/repo/.work/active"
cp "$ROOT/scripts/coord-status" "$LAB/repo/scripts/coord-status"
printf '#!/usr/bin/env bash\nexit 0\n' > "$LAB/repo/scripts/ci-status"
chmod +x "$LAB/repo/scripts/coord-status" "$LAB/repo/scripts/ci-status"

printf 'SPRINT: SPRINT.md\n' > "$LAB/repo/AGENTS.md"
printf '# Sprint\n' > "$LAB/repo/SPRINT.md"
printf '# generation: 1\n#slug\tagent\tstarted\titems\tpaths\n' \
  > "$LAB/repo/.work/active/LEDGER.tsv"
# The regression must exempt the exact ledger name, not all TSV files.
printf 'this is not a claim\n' > "$LAB/repo/.work/active/rogue.tsv"

git -C "$LAB/repo" init -q
git -C "$LAB/repo" symbolic-ref HEAD refs/heads/main
git -C "$LAB/repo" add .
git -C "$LAB/repo" \
  -c user.name=test -c user.email=test@example.com -c commit.gpgsign=false \
  commit -qm "fixture"

SSC_COORD_REF=HEAD SSC_COORD_NOW_EPOCH=0 \
  "$LAB/repo/scripts/coord-status" --no-fetch > "$LAB/output"

invalid_section="$(
  awk '
    $0 == "== invalid active markers ==" { inside = 1; next }
    inside && $0 == "" { exit }
    inside { print }
  ' "$LAB/output"
)"
actual_markers="$(
  printf '%s\n' "$invalid_section" |
    awk '$0 != "none" && $0 !~ /^  repair:/ { print $1 }' |
    LC_ALL=C sort
)"
actual_repairs="$(
  printf '%s\n' "$invalid_section" |
    grep '^  repair:' || true
)"

check "only the genuinely invalid marker is classified" \
  "rogue.tsv" \
  "$actual_markers"
check "repair advice targets only the genuinely invalid marker" \
  "  repair: git mv .work/active/rogue.tsv .work/active/rogue.tsv.claim" \
  "$actual_repairs"

exit "$fail"
