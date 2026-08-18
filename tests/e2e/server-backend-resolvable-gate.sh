#!/usr/bin/env bash
#
# server-backend-resolvable-gate — the coordinate `--server-backend` writes into a user's script can
# actually be fetched.
#
# WHAT SHIPPED FOR TWO RELEASES. `ssc run --target jvm --server-backend jetty` compiles the program to
# a Scala script and runs it under scala-cli in another process, and prepended
# `//> using dep io.scalascript::scalascript-runtime-server-jvm-jetty:0.1.1` so that process could
# find the backend. Nothing from this project was ever published anywhere — measured 2026-08-18,
# `repo1.maven.org/maven2/io/scalascript/` is a 404 — so every such run died in scala-cli's resolver.
# The gate that owned that line checked it was not a `-SNAPSHOT`, which was true, while its own
# comment stated the rule it could not enforce: "must name the last PUBLISHED release".
# (BUGS.md emitted-server-backend-coordinate-resolves-nowhere.)
#
# SO THIS ASKS THE QUESTION THAT ONE COULD NOT: does the coordinate RESOLVE. Two rows, and they fail
# for different reasons on purpose —
#
#   * the version the CLI emits must be present in `releases/maven`, the tree Pages serves. Offline,
#     and it is the row that catches the ordinary mistake: bumping the constant after a release
#     without publishing the artifacts for it.
#   * the emitted script must actually RUN. Nothing short of running it proves the directive is
#     well-formed, ordered correctly and pointed at a tree with a usable pom — the first version of
#     this work emitted a repository line the resolver silently ignored until it was run.
#
# The second row needs `scala-cli` and the network (Jetty itself comes from Central, as it should),
# and says [skip] when they are missing rather than passing quietly. It runs with its OWN resolver
# cache — see the comment at the call: with the shared one it stayed green after the fix under test
# was removed.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
main_scala="$ROOT/v1/tools/cli/src/main/scala/scalascript/cli/Main.scala"
maven="$ROOT/releases/maven"
fails=0
export SSC_NO_BUILD_CHECK=1

echo "── the coordinate the CLI emits is in the tree Pages serves"

# The constant is read from the source rather than restated here: a gate that carries its own copy of
# the value it checks is comparing a file with itself.
version=$(grep -m1 -E '^ *val version *= *"[^"]+"' "$main_scala" | sed -E 's/.*"([^"]+)".*/\1/')
if [[ -z "$version" ]]; then
  echo "  ✗ could not read the emitted version out of Main.scala — this gate stopped being able to look" >&2
  exit 1
fi

for backend in jetty netty; do
  pom="$maven/io/scalascript/scalascript-runtime-server-jvm-${backend}_3/$version/scalascript-runtime-server-jvm-${backend}_3-$version.pom"
  jar="${pom%.pom}.jar"
  if [[ -f "$pom" && -s "$jar" ]]; then
    echo "  ✓ $backend $version: pom and jar present"
  else
    # Backticks would be a COMMAND SUBSTITUTION inside double quotes, and this line ran sbt for
    # real the first time it printed. Single quotes around the command name instead.
    echo "  ✗ $backend $version: no pom/jar under releases/maven — 'sbt publishServerBackends' was not run for this version"
    fails=$((fails + 1))
  fi
done

# The URL in the source must be the Pages URL of THIS repository. A typo here is a 404 at somebody
# else's first compile, and nothing else in the tree would notice.
origin=$(git -C "$ROOT" remote get-url origin 2>/dev/null || true)
slug=$(printf '%s' "$origin" | sed -E 's#^git@github.com:##; s#^https://github.com/##; s#\.git$##')
owner=${slug%%/*}; repo=${slug##*/}
want="https://$owner.github.io/$repo/maven"
have=$(grep -m1 -A2 'DefaultServerBackendRepo' "$main_scala" | grep -oE 'https://[^"]+')
if [[ "$have" == "$want" ]]; then
  echo "  ✓ the default repository is this repo's Pages tree: $have"
else
  echo "  ✗ default repository is '$have', but this repo publishes to '$want'"
  fails=$((fails + 1))
fi

# ── the emitted script runs ──────────────────────────────────────────────────────────────────────
echo "── a generated --server-backend script resolves and runs"
if ! command -v scala-cli >/dev/null 2>&1; then
  echo "  [skip] scala-cli is not on PATH. That is a SKIP, not a pass." >&2
elif [[ ! -x "$tools" ]]; then
  echo "  [skip] no launcher — run ./install.sh --dev. That is a SKIP, not a pass." >&2
else
  sandbox=$(mktemp -d "${TMPDIR:-/tmp}/server-backend.XXXXXX")
  serve_log="$sandbox/httpd.log"
  # A local static server over the SAME tree Pages will serve, so the row exercises resolution
  # without depending on a deployment having happened.
  port=$(( 8900 + RANDOM % 400 ))
  ( cd "$maven" && exec python3 -m http.server "$port" >"$serve_log" 2>&1 ) &
  httpd=$!
  trap 'kill "$httpd" 2>/dev/null; rm -rf "$sandbox"' EXIT HUP INT TERM
  ready=0
  for _ in $(seq 1 40); do
    if curl -fsS -o /dev/null "http://127.0.0.1:$port/io/scalascript/" 2>/dev/null; then ready=1; break; fi
    sleep 0.25
  done
  if [[ "$ready" -ne 1 ]]; then
    echo "  ✗ the local Maven server never came up on $port"; fails=$((fails + 1))
  else
    cat > "$sandbox/p.ssc" <<'SSC'
def main(): Unit =
  println("server backend reached")

main()
SSC
    # A SANDBOXED RESOLVER CACHE, and this is the difference between a gate and a green light.
    # Measured while writing it: with the shared `~/.cache/coursier`, deleting the repository
    # directive entirely and rebuilding left this row PASSING — the artifact was already cached from
    # an earlier run, so nothing had to resolve. A cold cache costs 19 s and 38 MB and is the only
    # state in which "it resolved" means anything.
    out=$(COURSIER_CACHE="$sandbox/cs" SSC_SERVER_BACKEND_REPO="http://127.0.0.1:$port" \
          timeout 900 "$tools" run --target jvm --server-backend jetty "$sandbox/p.ssc" 2>"$sandbox/err")
    if [[ "$out" == *"server backend reached"* ]]; then
      echo "  ✓ jetty: resolved from the tree and the program ran"
    else
      echo "  ✗ jetty: the generated script did not run. stdout='$out'"
      grep -m4 -E "error|not found|Failed to download .*io/scalascript" "$sandbox/err" | cut -c1-110 | sed 's/^/      /'
      fails=$((fails + 1))
    fi
  fi
fi

# ── the offline path: a self-contained jar, and NOTHING resolved for the backend ─────────────────
#
# `SSC_SERVER_BACKEND_JAR` is the convenience escape hatch — behind a proxy, on a machine with no
# route to Central, or when the exact bytes matter. The row asserts BOTH halves: the program runs,
# and no `io/scalascript` fetch is attempted at all. Without the second half a jar that was silently
# ignored while the coordinate resolved from cache would pass.
echo "── the offline jar path resolves nothing"
jetty_assembly=$(find "$ROOT/v1/runtime/http-server/jvm-jetty/target" -name 'ssc-server-jetty.jar' -print -quit 2>/dev/null || true)
if [[ -z "$jetty_assembly" ]]; then
  echo "  [skip] no assembly — run 'sbt runtimeServerJvmJetty/assembly'. That is a SKIP, not a pass." >&2
elif [[ ! -x "$tools" ]]; then
  echo "  [skip] no launcher — run ./install.sh --dev. That is a SKIP, not a pass." >&2
else
  off=$(mktemp -d "${TMPDIR:-/tmp}/server-backend-jar.XXXXXX")
  cat > "$off/p.ssc" <<'SSC'
def main(): Unit =
  println("offline jar path reached")

main()
SSC
  out=$(COURSIER_CACHE="$off/cs" SSC_SERVER_BACKEND_JAR="$jetty_assembly" \
        timeout 900 "$tools" run --target jvm --server-backend jetty "$off/p.ssc" 2>"$off/err")
  tried=$(grep -c "io/scalascript" "$off/err" || true)
  if [[ "$out" == *"offline jar path reached"* && "$tried" -eq 0 ]]; then
    echo "  ✓ ran from the jar, and no io.scalascript coordinate was fetched"
  else
    echo "  ✗ offline path: stdout='$out', io.scalascript fetch attempts=$tried"
    grep -m3 -E "error|not found" "$off/err" | cut -c1-110 | sed 's/^/      /'
    fails=$((fails + 1))
  fi
  rm -rf "$off"
fi

echo
if [[ "$fails" -ne 0 ]]; then echo "server-backend-resolvable-gate: FAIL ($fails)" >&2; exit 1; fi
echo "server-backend-resolvable-gate: PASS"
