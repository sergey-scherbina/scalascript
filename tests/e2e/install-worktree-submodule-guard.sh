#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SHARED_MAIN="$(dirname "$(git -C "$ROOT" rev-parse --path-format=absolute --git-common-dir)")"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/ssc-install-worktree.XXXXXX")"
WT="$TMP/worktree"
FAKE_MAIN="$TMP/main-checkout"

cleanup() {
  git -C "$ROOT" worktree remove --force "$WT" >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT

# The implementation under test may be uncommitted while this regression is
# developed. Create the real git worktree from HEAD, then overlay only the
# current installer; otherwise the apparatus silently tests yesterday's file.
git -c submodule.recurse=false -C "$ROOT" worktree add --detach "$WT" HEAD >/dev/null
cp "$ROOT/install.sh" "$WT/install.sh"
mkdir -p "$FAKE_MAIN/.git"
cp "$ROOT/install.sh" "$FAKE_MAIN/install.sh"

worktree_out="$(SSC_INSTALL_PREFLIGHT_ONLY=1 bash "$WT/install.sh" --dev)"
main_out="$(SSC_INSTALL_PREFLIGHT_ONLY=1 bash "$FAKE_MAIN/install.sh" --dev)"

if ! grep -Fq 'Worktree detected; skipping submodule update.' <<<"$worktree_out"; then
  printf 'install-worktree-submodule-guard: expected worktree skip, got:\n%s\n' "$worktree_out" >&2
  exit 1
fi
if ! grep -Fq "agent skills source: $SHARED_MAIN/.agents/plugins" <<<"$worktree_out"; then
  printf 'install-worktree-submodule-guard: wrong shared-main skills source:\n%s\n' "$worktree_out" >&2
  exit 1
fi
if ! git -C "$WT" submodule status .agents/plugins | grep -q '^-'; then
  printf 'install-worktree-submodule-guard: worktree submodule was initialized unexpectedly\n' >&2
  git -C "$WT" submodule status .agents/plugins >&2 || true
  exit 1
fi
if ! grep -Fq 'Main checkout detected; submodules would be updated.' <<<"$main_out"; then
  printf 'install-worktree-submodule-guard: expected main update classification, got:\n%s\n' "$main_out" >&2
  exit 1
fi

printf 'install-worktree-submodule-guard: PASS\n'
