#!/usr/bin/env bash
#
# Every gitlink recorded in the tree must name a commit its submodule's remote actually has.
#
# WHY. On 2026-07-30 `main` recorded `.agents/plugins` at `fe840592b21ca4e2f9d0aa8d69b5a3a9a2ff5ba0`
# while the real commit was `fe84059ec273af52bef87dcbf5409f69262c5d80`. Both start `fe84059`: the
# 40-char SHA had been extended by hand from the 7-char abbreviation and diverged after it. The
# consequence was repo-wide and lasted hours — `git submodule update` failed for everyone, every
# working tree stayed permanently dirty, and `scripts/coord-release` (which refuses on a dirty tree)
# could not release ANY claim, submodule-related or not.
#
# The mechanism that let a fabricated SHA reach `main`: `git update-index --cacheinfo 160000,<sha>,<p>`
# accepts ANY 40-character hex string and never checks that the commit exists — in the submodule or
# anywhere. It is the normal way to bump a pointer without a submodule checkout, and it is safe only
# when the SHA comes from `git -C <submodule> rev-parse HEAD`.
#
# HOW. `git fetch --depth 1 <url> <sha>` into a scratch repo. MEASURED against the incident's own two
# SHAs before this gate was written:
#
#   fe840592b…  ->  fatal: remote error: upload-pack: not our ref     (the fabricated one)
#   fe84059ec…  ->  fetched                                            (the real one)
#
# Chosen over the alternatives for a specific reason each: `git ls-remote <url> <sha>` only matches
# REF TIPS, so a legitimate pointer to a non-tip commit would fail it; and `git -C <sub> cat-file -e`
# needs the submodule checked out, which CI does not do — no workflow passes `submodules:` to
# `actions/checkout`, so an object-store check would silently verify nothing there. The fetch probe
# needs neither a checkout nor a ref, and it is the same request `git submodule update` makes, so it
# fails exactly when the real operation would.
#
# Usage:
#   tests/e2e/submodule-gitlink-resolves.sh              # check every gitlink in the index
#   tests/e2e/submodule-gitlink-resolves.sh --self-test  # prove the probe can FAIL, then check
#
# Exit: 0 ok · 1 a gitlink names a commit its remote does not have · 2 usage/environment.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

self_test=0
case "${1:-}" in
  --self-test) self_test=1 ;;
  "") : ;;
  *) printf 'usage: %s [--self-test]\n' "${BASH_SOURCE[0]}" >&2; exit 2 ;;
esac

SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT
git -C "$SCRATCH" init -q

# One probe, used by both the real check and the self-test — so the self-test proves THE code path
# that runs in anger, not a lookalike.
probe() {  # probe <url> <sha> -> 0 if the remote has it
  git -C "$SCRATCH" fetch -q --depth 1 "$1" "$2" >/dev/null 2>&1
}

fail=0
note() { printf '  %s\n' "$*"; }
bad()  { printf 'FAIL  %s\n' "$*" >&2; fail=1; }

# Gitlinks are mode 160000 entries in the index. Reading the INDEX rather than `.gitmodules` is
# deliberate: the recorded pointer is what breaks, and a gitlink can exist with no `.gitmodules`
# entry (then it has no URL and nothing can resolve it — which is itself worth failing on).
gitlinks="$(git ls-files -s | awk '$1 == "160000" { print $2 "\t" $4 }')"

if [ -z "$gitlinks" ]; then
  echo "submodule-gitlink-resolves: no gitlinks in the index — nothing to check"
  exit 0
fi

if [ "$self_test" -eq 1 ]; then
  # Prove the probe can FAIL, using the same function and a real remote. The bad SHA is derived from
  # a real one by flipping one hex digit, so it is well-formed and only its EXISTENCE differs — which
  # is exactly the incident's shape. A gate whose failure path is never exercised is the thing this
  # repo keeps getting bitten by.
  first_sha="$(printf '%s\n' "$gitlinks" | head -1 | cut -f1)"
  first_path="$(printf '%s\n' "$gitlinks" | head -1 | cut -f2)"
  first_url="$(git config -f .gitmodules --get "submodule.$first_path.url" || true)"
  if [ -z "$first_url" ]; then
    printf 'submodule-gitlink-resolves --self-test: no URL for %s; cannot self-test\n' "$first_path" >&2
    exit 2
  fi
  flipped="$(printf '%s' "$first_sha" | sed 's/./0/40; s/^\(.\{39\}\)0$/\1f/')"
  [ "$flipped" = "$first_sha" ] && flipped="$(printf '%s' "$first_sha" | sed 's/.$/0/')"
  if probe "$first_url" "$flipped"; then
    bad "--self-test: the probe ACCEPTED a fabricated SHA ($flipped) — it cannot detect the defect it exists for"
    exit 1
  fi
  note "--self-test: probe correctly refused a fabricated SHA ($flipped)"
  if ! probe "$first_url" "$first_sha"; then
    bad "--self-test: the probe REFUSED the recorded SHA ($first_sha) — it would fail on a healthy tree"
    exit 1
  fi
  note "--self-test: probe accepted the recorded SHA — both directions verified"
fi

echo "== gitlinks recorded in the index =="
while IFS=$'\t' read -r sha path; do
  [ -n "$sha" ] || continue
  url="$(git config -f .gitmodules --get "submodule.$path.url" || true)"
  if [ -z "$url" ]; then
    bad "$path records $sha but .gitmodules declares no URL for it — nothing can resolve this pointer"
    continue
  fi
  if probe "$url" "$sha"; then
    printf '  ok   %-24s %s\n' "$path" "$sha"
  else
    bad "$path records $sha, which $url does NOT have."
    note "This is how the 2026-07-30 outage started: a 40-char SHA extended by hand from a 7-char"
    note "abbreviation. Repair with the SHA the submodule itself reports, never a typed one:"
    note "  git -C $path fetch origin && git -C $path rev-parse origin/HEAD"
    note "  git update-index --cacheinfo 160000,\$(git -C $path rev-parse origin/HEAD),$path"
  fi
done <<< "$gitlinks"

if [ "$fail" -eq 0 ]; then
  echo "submodule-gitlink-resolves: OK"
else
  echo "submodule-gitlink-resolves: FAILED" >&2
fi
exit "$fail"
