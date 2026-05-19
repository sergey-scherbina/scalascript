#!/usr/bin/env bash
# Build ssc and stage all launchers into the repo-local bin/.
# Requires sbt and a JDK (21+).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_PATH="$ROOT/cli/target/scala-3.8.3/ssc.jar"
BIN="$ROOT/bin"

echo "Building ssc fat jar via sbt-assembly..."
(cd "$ROOT" && sbt -no-colors cli/assembly)
[ -f "$JAR_PATH" ] || { echo "Assembly did not produce $JAR_PATH" >&2; exit 1; }

mkdir -p "$BIN"

cat > "$BIN/ssc" <<EOF
#!/usr/bin/env bash
exec java -jar "$JAR_PATH" "\$@"
EOF
chmod +x "$BIN/ssc"

for launcher in "$ROOT"/scripts/launchers/*; do
    name="$(basename "$launcher")"
    ln -sf "../scripts/launchers/$name" "$BIN/$name"
done

echo "Staged bin/ launchers:"
for f in "$BIN"/*; do
    echo "  bin/$(basename "$f")"
done
echo ""
echo "Add to PATH for this session:"
echo "  export PATH=\"\$PATH:$BIN\""
