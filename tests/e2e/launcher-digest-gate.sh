#!/usr/bin/env bash
#
# launcher-digest-gate — proves `scripts/launcher-input-digest` reacts to the things that change the
# staged toolchain and ignores the things that cannot, and that `scripts/smoke-ci` actually consumes
# it.
#
# WHY THIS GATE IS NOT OPTIONAL. The digest exists so CI can SKIP a ~3.5 min launcher build on a
# cache hit. If it fails to notice a change to a compiler source, CI restores the previous commit's
# toolchain, runs the whole suite with it, and reports GREEN about code it never executed. There is
# no symptom: every check passes, the timings look better, and the verdict is about the wrong bytes.
# That is the most expensive failure shape in this repository, so the property gets a gate rather
# than an argument.
#
# The two directions are NOT equally important and the gate says so:
#   * a change under an INCLUDED path MUST change the digest        — correctness
#   * a change under an EXCLUDED path MUST NOT change the digest    — the whole benefit
# Only the first can cause a wrong verdict. If you are ever tempted to weaken one, weaken the second.
#
# Every mutation happens in a THROWAWAY WORKTREE at HEAD, never in the checkout you are sitting in:
# a gate that edits tracked files in place and then restores them is one failed assertion away from
# leaving someone else's work modified.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/ssc-launcher-digest.XXXXXX")"
WT="$TMP/wt"

cleanup() {
  git -C "$ROOT" worktree remove --force "$WT" >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT

git -C "$ROOT" worktree add --detach -q "$WT" HEAD

# The worktree is at HEAD; the SCRIPTS under test come from the checkout this gate was invoked from.
# Otherwise a gate run before committing would silently test the previous version of the very files
# it exists to check — and would fail outright the first time, when they are not in HEAD at all.
# In CI the checkout is clean, so the two are the same bytes.
cp "$ROOT/scripts/launcher-input-digest" "$WT/scripts/launcher-input-digest"
cp "$ROOT/scripts/smoke-ci"              "$WT/scripts/smoke-ci"
chmod +x "$WT/scripts/launcher-input-digest" "$WT/scripts/smoke-ci"

DIGEST="$WT/scripts/launcher-input-digest"
digest() { "$DIGEST"; }

fail() { printf 'launcher-digest-gate[%s]: %s\n' "$1" "$2" >&2; exit 1; }

# ── the digest must depend on CONTENT, not on git state ─────────────────────
#
# It used to write a different LINE for the same file depending on whether git called it untracked,
# dirty or committed, so `git add` and `git commit` each shifted it while the bytes were untouched.
# That is the failure this tool was created to remove — `.build-stamp` forced "a ~3.5 min rebuild
# for nothing" on a docs-only commit, and the replacement forced one after every commit instead.
#
# Asserted STRUCTURALLY, on the shape of the inputs, rather than by staging a probe file. The first
# version of this check did `git add` and `git rm --cached`, passed on its own, and FAILED inside
# smoke-ci: it mutated the git index, which the suite shares. A check with a side effect is a check
# that reports on whatever else was running.
#
# Every line must be `<content-sha>\t<path>` — one canonical spelling. A `dirty ` or `untracked `
# prefix is exactly the defect, and the shape says so without touching anything.
# A PROBE FILE, so the untracked branch actually runs. Without one, a clean tree produces no
# untracked lines at all and the assertion below is vacuous — it PASSED with the defect reinstated,
# which is how this was caught. The probe is created inside the TEMP worktree and only as a file:
# the first version of this check ran `git add`, which mutates an index the suite shares, and it
# passed alone while failing inside smoke-ci.
mkdir -p "$WT/v3"
printf '// digest probe\n' > "$WT/v3/__digest_probe.scala"
explain_out="$(cd "$WT" && ./scripts/launcher-input-digest --explain 2>/dev/null)"
rm -f "$WT/v3/__digest_probe.scala"
# A BASH PATTERN MATCH, not `printf | grep -q`. With `set -o pipefail`, `grep -q` closes the pipe on
# its FIRST match, `printf` dies of SIGPIPE, and the pipeline reports failure — so the test fired
# exactly when the thing it looked for was present. That inversion bit three separate checks today;
# the cure is not to build a pipeline when a string test will do.
if [[ "$explain_out" != *"__digest_probe.scala"* ]]; then
  fail digest-misses-untracked "the probe file is absent from the inputs — a new source would be invisible to the build"
fi
# `|| true` because `grep -v` EXITS 1 WHEN IT FINDS NOTHING — which is the success case here, and
# under `set -e` it killed this script silently. Second time today: the exec gate had the same
# inversion with `grep -q` taking a pipeline's exit status from the process that legitimately failed.
# The tab is written as a REAL tab (bash `$'...'`), not as `\t` inside the pattern. `\t` is not a
# tab in POSIX ERE: GNU grep reads it as the letter `t`, so the pattern became
# `^  ([0-9a-f]{40}|deleted)t`, NO line matched, and `grep -v` returned EVERY line as malformed.
# It passed on this machine because the local `grep` is ugrep, which does accept `\t` — so the gate
# was green locally and red on CI, for two hours, on a repo where a red main blocks every agent's
# evidence. BUGS `launcher-digest-gate-backslash-t-is-not-a-tab-in-ere`.
bad_lines="$(printf '%s\n' "$explain_out" | sed -n '/^digest inputs:/,$p' | tail -n +2 \
             | grep -vE $'^  ([0-9a-f]{40}|deleted)\t' | head -3 || true)"
if [[ -n "$bad_lines" ]]; then
  fail digest-follows-git-state "an input line is not <sha>TAB<path> — the digest still depends on git state:
$bad_lines"
fi

dup="$(printf '%s\n' "$explain_out" | sed -n '/^digest inputs:/,$p' | tail -n +2 \
       | awk -F'\t' '{print $2}' | LC_ALL=C sort | uniq -d | head -3 || true)"
if [[ -n "$dup" ]]; then
  fail digest-duplicate-path "a path appears twice, so its two spellings both feed the digest:
$dup"
fi

# ── portability guard for the pattern above ────────────────────────────────────────────────────
# This gate cannot detect the `\t` bug by RUNNING it here: on ugrep the broken pattern works. So
# assert the property that holds on every host — no `grep -E` pattern in this file may contain a
# backslash-t. That is the "emulate the other host" shape: check the thing the weaker host would
# choke on, rather than the behaviour this host happens to give.
# The discriminator is the QUOTE: `grep -E $'...\t'` is bash expanding a real tab (correct), while
# `grep -E '...\t'` hands the two characters to grep (broken on GNU). So flag only a single quote
# that is NOT preceded by `$`.
# Comments are skipped: the paragraph above deliberately SPELLS the broken form, and a guard that
# cannot describe what it forbids is a guard nobody can read.
if grep -nE "grep -[a-zA-Z]*E +'" "${BASH_SOURCE[0]}" | grep -vE '^[0-9]+: *#' | grep -v 'BASH_SOURCE' | grep -q '\\t'; then
  fail ere-backslash-t "a grep -E pattern in this gate contains \\t, which is the letter t on GNU grep — use a real tab via \$'...'"
fi

base="$(digest)"
[[ -n "$base" ]] || fail bootstrap "the digest is empty"

# ── determinism ───────────────────────────────────────────────────────────────
# A digest that varies between two identical runs would look like "the sources changed" on every
# build and quietly disable the cache — the benign direction, but it would also make every other
# assertion here meaningless.
again="$(digest)"
[[ "$base" == "$again" ]] || fail determinism "two runs over an unchanged tree disagree: $base vs $again"

# ── CORRECTNESS: an included path must move the digest ────────────────────────
# One representative per kind of input, because they reach the digest by different code paths:
# a tracked edit via `git diff`, a new file via `git ls-files --others`, and the build definition
# itself, which is neither a compiler source nor a script.
included_case() { # included_case <label> <relative-path> <mutation-command…>
  local label="$1" path="$2"; shift 2
  ( cd "$WT" && "$@" )
  local after; after="$(digest)"
  [[ "$after" != "$base" ]] || fail "$label" \
    "changing $path did NOT change the digest — a build would be SKIPPED for a changed toolchain, and the suite would report a verdict about the previous commit's compiler"
  ( cd "$WT" && git checkout -q -- "$path" 2>/dev/null || rm -f "$WT/$path" )
  local restored; restored="$(digest)"
  [[ "$restored" == "$base" ]] || fail "$label" "restoring $path did not restore the digest ($restored vs $base)"
}

included_case tracked-compiler-source v2/src/Runtime.scala \
  bash -c 'printf "\n// launcher-digest-gate probe\n" >> v2/src/Runtime.scala'
included_case tracked-v1-source v1/lang/core/src/main/scala/scalascript/parser/PreprocessorRegistry.scala \
  bash -c 'printf "\n// launcher-digest-gate probe\n" >> v1/lang/core/src/main/scala/scalascript/parser/PreprocessorRegistry.scala'
included_case build-definition build.sbt \
  bash -c 'printf "\n// launcher-digest-gate probe\n" >> build.sbt'

# THE ONE SCRIPT THAT DOES REACH THE LAUNCHER, and the reason `scripts/` is not excluded wholesale.
# `build.sbt:2039` writes `$_SSC_ROOT/scripts/launcher-input-digest` into the generated launcher,
# which runs it at STARTUP for the staleness check, and the build runs it again to stamp the digest.
# So a change to it changes what every launcher does. Of everything under `scripts/`, the build
# definition and the launcher templates name exactly this file — measured, then paired with the
# excluded row above so neither can be widened without the other going red.
included_case launcher-digest-tool scripts/launcher-input-digest \
  bash -c 'printf "\n# launcher-digest-gate probe\n" >> scripts/launcher-input-digest'
included_case untracked-new-source v1/lang/core/src/main/scala/__digest_probe.scala \
  bash -c 'printf "class DigestProbe\n" > v1/lang/core/src/main/scala/__digest_probe.scala'

# A MARKDOWN file that is a real build input. sbt packages `src/main/resources` into the jar, and
# `v1/tools/cli/src/main/resources/templates/*/README.md` is the scaffolding `ssc new` writes — so
# excluding markdown by EXTENSION, which is the obvious way to skip the boards, would have silently
# dropped a compiled-in resource. This case is why the board exclusion is by NAME instead.
included_case packaged-markdown-resource v1/tools/cli/src/main/resources/templates/app/README.md \
  bash -c 'printf "\nlauncher-digest-gate probe\n" >> v1/tools/cli/src/main/resources/templates/app/README.md'

# THE DEFAULT FRONT. `specs/` is on the exclusion list because it holds ~420 `.md` documents, and
# for eight days that swallowed `specs/v2.2-p6.5-fsub.ssc` with them -- the F front, which
# `install.sh` stages verbatim into `bin/lib/*/native-front/tower/bin/fsub.ssc`. Measured: appending
# a line to the front left the digest unchanged, so smoke-ci's staleness refusal could not see a
# change to the compiler EVERY default-lane check runs, and an edited F reported green as the old F.
# This case is here rather than in the entry because the exclusion is by DIRECTORY and the file is a
# single exception inside it -- exactly the shape that gets re-broken by someone tidying the list.
front_f=specs/v2.2-p6.5-fsub.ssc
( cd "$WT" && git ls-files --error-unmatch "$front_f" ) >/dev/null 2>&1 \
  || fail front-fixture-missing "$front_f is not tracked -- the default front moved, and the case below would test nothing"
included_case default-front "$front_f" \
  bash -c 'printf "\n// launcher-digest-gate probe\n" >> specs/v2.2-p6.5-fsub.ssc'

# ── BENEFIT: an excluded path must not move the digest ────────────────────────
# This is what buys the cache hit. A docs or board commit is the majority of this repository's
# traffic (measured 2026-07-28: 43 of 58 run-creating commits in one hour touched only `.md` or
# `.work/`), and rebuilding a byte-identical toolchain for each of them is the cost being removed.
excluded_case() { # excluded_case <label> <relative-path> <mutation-command…>
  local label="$1" path="$2"; shift 2
  ( cd "$WT" && "$@" )
  local after; after="$(digest)"
  [[ "$after" == "$base" ]] || fail "$label" \
    "changing $path changed the digest, so a commit that cannot affect the toolchain still forces a rebuild"
  ( cd "$WT" && git checkout -q -- "$path" 2>/dev/null || rm -f "$WT/$path" )
}

excluded_case root-markdown CHANGELOG.md \
  bash -c 'printf "\nlauncher-digest-gate probe\n" >> CHANGELOG.md'

# COORDINATION SCRIPTS. `scripts/` is INCLUDED as a tree and stays so, because `build.sbt` bakes
# `scripts/launcher-input-digest` into the generated launcher and runs it again to stamp the digest —
# so the exclusion is per FILE, and these two rows are the pair that keeps it honest.
#
# Measured in `editing-a-coordination-script-forces-a-compiler-rebuild`: editing `coord-release`, a
# bash script that talks to `git` and is compiled into nothing, made `smoke-ci` refuse to run and
# demanded a full `./install.sh --dev` — ~10 minutes, twice in one session, and it busts the
# content-addressed toolchain cache for everyone who pulls.
excluded_case coordination-script scripts/coord-release \
  bash -c 'printf "\n# launcher-digest-gate probe\n" >> scripts/coord-release'

# THE MEASURED ONE. Of the last 60 commits on main, exactly one touched `scripts/` and nothing else,
# and its file was `scripts/smoke-ci.ssc` — the suite declaration, which the launcher RUNS and does
# not contain. Adding a `Check(...)` row cost a full sbt build before this.
excluded_case suite-declaration scripts/smoke-ci.ssc \
  bash -c 'printf "\n// launcher-digest-gate probe\n" >> scripts/smoke-ci.ssc'
excluded_case conformance-corpus tests/conformance/arithmetic.ssc \
  bash -c 'printf "\nlauncher-digest-gate probe\n" >> tests/conformance/arithmetic.ssc'
excluded_case workflow .github/workflows/smoke.yml \
  bash -c 'printf "\n# launcher-digest-gate probe\n" >> .github/workflows/smoke.yml'
excluded_case claim-churn .work/digest-probe.txt \
  bash -c 'printf "probe\n" > .work/digest-probe.txt'
# The per-module BOARDS, which live INSIDE included trees and are the most-edited files here: 37 of
# the last 200 commits touched nothing but boards and `.work/`. Without these two the cache would
# miss on most of the traffic it exists to serve.
# Both fixtures must be TRACKED boards. `>>` to a missing path CREATES it, so a typo'd or moved
# board would silently downgrade these cases into "an untracked file with an excluded name does not
# move the digest" — true, weaker, and indistinguishable from the real assertion. Cost me one stray
# `v1/BUGS.md` in a worktree while writing this.
for board in scripts/SPRINT.md v2/BUGS.md; do
  ( cd "$WT" && git ls-files --error-unmatch "$board" ) >/dev/null 2>&1 \
    || fail board-fixture-missing "$board is not a tracked file, so the exclusion case below would test nothing"
done
excluded_case module-board-sprint scripts/SPRINT.md \
  bash -c 'printf "\n- [ ] launcher-digest-gate probe\n" >> scripts/SPRINT.md'
excluded_case module-board-bugs v2/BUGS.md \
  bash -c 'printf "\nlauncher-digest-gate probe\n" >> v2/BUGS.md'

# A doc tree NESTED inside an included one. The exclusions used to match the FIRST path component
# only, so `specs/` was skipped and `v3/specs/` was not, and a docs-only commit there paid a full
# launcher build. Same defect as the front case above, pointing the other way: one list, two
# directions, so the gate keeps one case for each.
excluded_case nested-doc-tree v3/specs/00-charter.md \
  bash -c 'printf "\nlauncher-digest-gate probe\n" >> v3/specs/00-charter.md'

# The PREMISE of excluding those names: none of them is ever a packaged resource. Checked against the
# tree rather than asserted in a comment, so the day someone adds `templates/foo/BUGS.md` to the cli
# resources this fails instead of silently dropping it from the digest.
boards_in_resources="$(cd "$WT" && git ls-files | grep -E '/(SPRINT|BACKLOG|BUGS|MILESTONES|SPRINT-ARCHIVE)\.md$' | grep -E '/resources/' || true)"
if [[ -n "$boards_in_resources" ]]; then
  fail board-name-is-a-resource "these files carry a board NAME but sit under a packaged resources/ path,
so excluding that name from the digest drops a real build input:
$boards_in_resources"
fi

# ── the guard must actually CONSUME the digest ────────────────────────────────
# The digest being correct is worth nothing if `scripts/smoke-ci` does not read it. These two run the
# wrapper against a staged `bin/lib` that the test constructs, so the assertion is about the guard's
# behaviour rather than about whatever launcher happens to be installed.
mkdir -p "$WT/bin/lib"
printf '#!/bin/sh\nexit 0\n' > "$WT/bin/ssc"
chmod +x "$WT/bin/ssc"

printf '%s\n' "$base" > "$WT/bin/lib/.build-digest"
set +e
agree_out="$(cd "$WT" && SSC_SMOKE_BUDGET=1 ./scripts/smoke-ci --list 2>&1)"
agree_code=$?
set -e
if [[ "$agree_code" -ne 0 ]]; then
  fail guard-accepts "a MATCHING digest was rejected (exit $agree_code):
$agree_out"
fi

printf '%s\n' "0000000000000000000000000000000000000000000000000000000000000000" \
  > "$WT/bin/lib/.build-digest"
set +e
reject_out="$(cd "$WT" && ./scripts/smoke-ci --list 2>&1)"
reject_code=$?
set -e
if [[ "$reject_code" -eq 0 || "$reject_out" != *"different sources than this tree"* ]]; then
  fail guard-rejects "a MISMATCHED digest was accepted (exit $reject_code):
$reject_out"
fi

# And the override still works, or a deliberate A/B becomes impossible.
set +e
override_out="$(cd "$WT" && SSC_SMOKE_ALLOW_STALE=1 ./scripts/smoke-ci --list 2>&1)"
override_code=$?
set -e
[[ "$override_code" -eq 0 ]] || fail guard-override "SSC_SMOKE_ALLOW_STALE=1 did not bypass the check (exit $override_code):
$override_out"

# ── the fallback: a launcher staged before the digest existed ─────────────────
# Deleting `.build-digest` must fall back to the SHA stamp rather than silently accepting anything.
rm -f "$WT/bin/lib/.build-digest"
printf '%s\n' "0000000000000000000000000000000000000000" > "$WT/bin/lib/.build-stamp"
set +e
fallback_out="$(cd "$WT" && ./scripts/smoke-ci --list 2>&1)"
fallback_code=$?
set -e
if [[ "$fallback_code" -eq 0 || "$fallback_out" != *"no bin/lib/.build-digest"* ]]; then
  fail guard-fallback "with no digest and a mismatched stamp the guard did not fall back (exit $fallback_code):
$fallback_out"
fi

printf 'launcher-digest-gate: PASS\n' 
