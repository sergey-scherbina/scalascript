#!/usr/bin/env bash
# A dependency coordinate we EMIT into a user's project must name a published version.
#
# `ssc` generates `//> using dep io.scalascript::scalascript-runtime-server-jvm-<backend>:<version>`
# and the sbt-plugin templates carry `addSbtPlugin(... % "<version>")`. Those strings end up in
# somebody else's build, so they have to resolve. A `-SNAPSHOT` there resolves nowhere and the
# failure lands on the user, at their first compile, in a file we wrote for them.
#
# This existed only as a comment reading "must track ThisBuild / version", which was true while the
# two were equal and became wrong the moment main moved to 0.2.0-SNAPSHOT after the v0.1.0 release —
# following it would have emitted exactly the unresolvable coordinate this gate now refuses. The
# rule is the opposite of what the comment said: track the last PUBLISHED release, not the build.
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
failed=0

emit_sites=(
  "$ROOT/v1/tools/cli/src/main/scala/scalascript/cli/Main.scala"
  "$ROOT/v1/tools/cli/src/main/resources/templates/app/project/plugins.sbt"
  "$ROOT/v1/tools/cli/src/main/resources/templates/web-app/project/plugins.sbt"
  "$ROOT/v1/tools/cli/src/main/resources/templates/wasm-app/project/plugins.sbt"
)

# A first draft grepped for lines containing `using dep …` and checked those for -SNAPSHOT. It
# passed against a deliberately broken tree, because in Main.scala the version is bound on its own
# line (`val version = "0.1.0"`) and interpolated into the directive elsewhere — the literal and the
# directive are never on the same line. Match the BINDING, not the sentence it ends up in.
for f in "${emit_sites[@]}"; do
  [[ -f $f ]] || { echo "emitted-coordinate: missing file $f" >&2; failed=1; continue; }
  while IFS= read -r line; do
    case "$line" in
      *-SNAPSHOT*)
        echo "emitted-coordinate: FAILED — ${f#"$ROOT"/} emits a SNAPSHOT coordinate" >&2
        echo "    $line" >&2
        echo "    A user cannot resolve that. Name the last PUBLISHED release instead." >&2
        failed=1
        ;;
    esac
  done < <(grep -nE 'val +version +=  *"[^"]+"|using dep io\.scalascript' "$f" 2>/dev/null || true)
done

# The sbt plugin is EXEMPT from the no-SNAPSHOT rule, and replaced by a stricter one.
#
# That rule says an emitted coordinate must name a PUBLISHED release. For sbt-scalascript-interop
# there is no published release at all -- nothing publishes it anywhere -- so the rule is not merely
# unmet, it is unsatisfiable, and a gate that cannot be satisfied stops being a gate. install.sh
# publishLocals it so `ssc new` produces a project that loads (tests/BUGS.md
# scaffolded-projects-cannot-load-their-build); whether it should be published for real is open.
#
# What IS checkable, and is the defect that actually shipped: the templates named "0.1.0" while the
# plugin build produced "0.1.0-SNAPSHOT", so even a local publish did not match. So: the templates
# must name exactly what that build produces.
plugin_version=$(grep -m1 '^ThisBuild / version' "$ROOT/v1/tools/sbt-plugin/build.sbt" | sed 's/.*:= *"\(.*\)".*/\1/')
while IFS= read -r line; do
  f=${line%%:*}
  asked=$(printf '%s' "$line" | sed -n 's/.*sbt-scalascript-interop" *% *"\([^"]*\)".*/\1/p')
  [[ -n $asked ]] || continue
  if [[ $asked != "$plugin_version" ]]; then
    echo "emitted-coordinate: FAILED — ${f#"$ROOT"/} asks for sbt-scalascript-interop '$asked'," >&2
    echo "    but v1/tools/sbt-plugin builds '$plugin_version'. A scaffolded project cannot resolve" >&2
    echo "    a version that build never produces, local publish or not." >&2
    failed=1
  fi
done < <(grep -rn 'addSbtPlugin.*sbt-scalascript-interop' "$ROOT/v1/tools/cli/src/main/resources/templates" 2>/dev/null || true)

# The build itself must NOT be at a plain release version between releases: that is how an
# intermediate build calls itself the release. Its whole point is to differ from the emitted one.
build_version=$(grep -m1 '^ThisBuild / version' "$ROOT/build.sbt" | sed 's/.*:= *"\(.*\)".*/\1/')
# A plain version is allowed ONLY on the commit that is that release. The first draft demanded a
# SNAPSHOT unconditionally, which is right about the steady state and wrong about the one legitimate
# transition: a release commit sets `0.1.1` by definition, so this gate would have failed every
# release — including the one it was written to protect. The rule that actually holds is "you may
# call yourself 0.1.1 only if you ARE the v0.1.1 tag".
# THE TAG MAY NOT BE IN THIS CHECKOUT, and that is not the same as "not tagged". Measured on the
# v0.1.1 release: `5dfad1c58` was cut on a `release/0.1.1` branch, this gate ran before the tag
# existed and turned smoke RED on the release commit itself — the one commit whose colour anybody
# looks at. CI's `actions/checkout@v4` is shallow and fetches no tags either, so even tagging first
# does not guarantee a local ref.
#
# So the local ref is the fast path and the REMOTE is the fallback, consulted only here: an ordinary
# commit carries a SNAPSHOT and returns above without a single network call. The failure message
# names both reasons, because "not tagged yet" and "tagged, but this checkout cannot see it" need
# different actions from whoever reads it.
head_sha=$(git -C "$ROOT" rev-parse -q HEAD)
tag_points_at_head() {
  local v=$1 local_sha remote_sha
  local_sha=$(git -C "$ROOT" rev-parse -q --verify "refs/tags/v$v^{commit}" 2>/dev/null || true)
  if [ -n "$local_sha" ]; then
    [ "$local_sha" = "$head_sha" ] && return 0 || return 1
  fi
  # AN ANNOTATED TAG ANSWERS ITS OWN OBJECT, NOT THE COMMIT, and this repository's tags are
  # annotated: `refs/tags/v0.1.1` is `cdb84377…` while the commit is `5dfad1c5…`. The commit is on
  # the DEREFERENCED `^{}` line, which only appears when the pattern can match it — an exact
  # `refs/tags/v0.1.1` returns just the tag object and the comparison then fails on a tag that is
  # perfectly correct. Measured while writing this; the trailing `*` is the whole fix.
  # A timeout keeps the gate from hanging on a dead network.
  remote_sha=$(timeout 30 git -C "$ROOT" ls-remote --tags origin "refs/tags/v$v*" 2>/dev/null |
               awk -v want="refs/tags/v$v^{}" '$2 == want {print $1; found=1; exit}
                    END { if (!found) exit 1 }')
  if [ -z "$remote_sha" ]; then
    # Lightweight tag: no `^{}` line, the plain entry IS the commit.
    remote_sha=$(timeout 30 git -C "$ROOT" ls-remote --tags origin "refs/tags/v$v" 2>/dev/null |
                 awk '{print $1; exit}')
  fi
  [ -n "$remote_sha" ] && [ "$remote_sha" = "$head_sha" ]
}
case "$build_version" in
  *-SNAPSHOT) ;;
  *)
    if tag_points_at_head "$build_version"; then
      : # this IS the release commit for v$build_version
    else
      echo "emitted-coordinate: FAILED — ThisBuild / version is '$build_version', not a SNAPSHOT," >&2
      echo "    and no v$build_version tag points at HEAD ($head_sha) — locally or on origin." >&2
      echo "    Between releases the build version must be a SNAPSHOT, or every intermediate build" >&2
      echo "    claims to be the release." >&2
      echo "    On a release: push the commit and its tag TOGETHER —" >&2
      echo "      git tag v$build_version && git push --atomic origin main v$build_version" >&2
      echo "    Pushing the commit first is what turned smoke red on the v0.1.1 release commit." >&2
      failed=1
    fi
    ;;
esac

if [[ $failed -ne 0 ]]; then
  echo "emitted-coordinate-is-published: FAILED" >&2
  exit 1
fi
echo "emitted-coordinate-is-published: OK (build $build_version, emitted coordinates are releases)"
