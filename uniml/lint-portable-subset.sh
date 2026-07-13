#!/usr/bin/env bash
# uniml-portable-2-subset: guards the Scala 3 ∩ ScalaScript-v2 RUNTIME subset.
# Scans the dual-compilable UniML sources (core + json/yaml/markdown dialects) for
# constructs that don't run on the self-hosted v2 compiler and fails if any appear.
# NOTE: this checks RUNTIME-portability constructs (the ones UniML was cleaned of).
# It deliberately does NOT flag idiomatic-Scala usages that are blocked only by the
# v2 *.ssc FRONTEND* today (companion `val` members, first-class object values, nested
# types in objects, `final`/`sealed` modifiers, `Set.empty`) — those are standard
# Scala 3 and are the v2.2 self-hosted-dialect track's to support; see
# specs/uniml-portable-gapmap.md "Gold-standard finding".
set -uo pipefail
cd "$(dirname "$0")/.." || exit 2   # repo root
DIRS="uniml/core/src/main uniml/json/src/main uniml/yaml/src/main uniml/markdown/src/main"
# banned RUNTIME-portability patterns (must be absent from code — comments are stripped first)
PATTERN='scala\.collection\.mutable|[^a-zA-Z]ArrayBuffer|[^a-zA-Z]StringBuilder|\.newBuilder|LinkedHashMap|HashSet|\.matches\(|"[^"]*"\.r[^a-zA-Z]|java\.lang\.Character|[^a-zA-Z]Character\.|\.isWhitespace|\.isSpaceChar|\.isLetter|\.isDigit|\.isLetterOrDigit|new Array|[^a-zA-Z]Array\['
fail=0
files=$(find $DIRS -name '*.scala' 2>/dev/null)
for f in $files; do
  # strip // line comments and /* */ and scaladoc * lines, then match
  stripped=$(sed -E 's://.*$::; s:^[[:space:]]*\*.*$::' "$f")
  hits=$(printf '%s\n' "$stripped" | grep -nE "$PATTERN")
  if [ -n "$hits" ]; then
    echo "VIOLATION in $f:"
    printf '%s\n' "$hits" | sed 's/^/    /'
    fail=1
  fi
done
if [ "$fail" = 0 ]; then
  echo "OK — UniML core+dialects are within the portable runtime subset (no mutable/regex/Character/etc.)."
else
  echo "== portable-subset lint FAILED: the constructs above do not run on v2 =="
fi
exit "$fail"
