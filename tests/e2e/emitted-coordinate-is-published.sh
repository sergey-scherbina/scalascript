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
  done < <(grep -nE 'val +version +=  *"[^"]+"|addSbtPlugin\(.*scalascript|using dep io\.scalascript' "$f" 2>/dev/null || true)
done

# The build itself must NOT be at a plain release version between releases: that is how an
# intermediate build calls itself the release. Its whole point is to differ from the emitted one.
build_version=$(grep -m1 '^ThisBuild / version' "$ROOT/build.sbt" | sed 's/.*:= *"\(.*\)".*/\1/')
case "$build_version" in
  *-SNAPSHOT) ;;
  *)
    echo "emitted-coordinate: FAILED — ThisBuild / version is '$build_version', not a SNAPSHOT." >&2
    echo "    Between releases the build version must be a SNAPSHOT, or every intermediate" >&2
    echo "    build claims to be the release. Set it at release time, revert right after." >&2
    failed=1
    ;;
esac

if [[ $failed -ne 0 ]]; then
  echo "emitted-coordinate-is-published: FAILED" >&2
  exit 1
fi
echo "emitted-coordinate-is-published: OK (build $build_version, emitted coordinates are releases)"
