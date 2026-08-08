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
case "$build_version" in
  *-SNAPSHOT) ;;
  *)
    if git -C "$ROOT" rev-parse -q --verify "refs/tags/v$build_version^{commit}" >/dev/null 2>&1 &&
       [ "$(git -C "$ROOT" rev-parse -q "refs/tags/v$build_version^{commit}")" = "$(git -C "$ROOT" rev-parse -q HEAD)" ]; then
      : # this IS the release commit for v$build_version
    else
      echo "emitted-coordinate: FAILED — ThisBuild / version is '$build_version', not a SNAPSHOT," >&2
      echo "    and HEAD is not the v$build_version tag." >&2
      echo "    Between releases the build version must be a SNAPSHOT, or every intermediate build" >&2
      echo "    claims to be the release. On the release commit itself, tag it and this passes." >&2
      failed=1
    fi
    ;;
esac

if [[ $failed -ne 0 ]]; then
  echo "emitted-coordinate-is-published: FAILED" >&2
  exit 1
fi
echo "emitted-coordinate-is-published: OK (build $build_version, emitted coordinates are releases)"
