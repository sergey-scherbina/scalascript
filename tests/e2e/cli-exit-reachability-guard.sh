#!/usr/bin/env bash
#
# Can a TEST reach a CLI boundary that ends the process? (BUGS `cli-command-System.exit-kills-the-test-fork`)
#
# The failure this prevents does not look like a failure. A `sys.exit` reached from an in-process test
# kills the forked test JVM mid-flight: the run prints "Tests: succeeded N, failed 0" and "All tests
# passed", then dies with `sbt.ForkMain … failed with exit code`, and the case that was executing never
# appears in the report at all. `scripts/detect-fork-exit` recognises that signature AFTER the fact.
# This guard is the other half — it stops the situation being created.
#
# Why it is not enough to check "does this test currently exit": whether `run` exits depends on the
# ARGUMENTS, at run time. `OAuthCli.run` is `status` plus `if rc != 0 then sys.exit(rc)`, so a suite
# calling it is safe exactly until someone adds a failure-path case. The reachable-boundary question
# is the one a static check can answer, and it is the one that stays answered.
#
#   ./tests/e2e/cli-exit-reachability-guard.sh              # check the tree
#   ./tests/e2e/cli-exit-reachability-guard.sh --self-test  # assert BOTH verdicts, then check the tree
#
# A detector only ever observed staying quiet is not a detector, so --self-test builds a file that
# MUST trip it and a file that must NOT, and runs in CI alongside the real check.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

SELF_TEST=0
[[ "${1:-}" == "--self-test" ]] && SELF_TEST=1

scan() { # scan <extra-test-dir-or-empty> -> prints one "TEST|OBJECT|MEMBER" line per reachable boundary
  EXTRA="${1:-}" python3 - <<'PY'
import os, re, sys

MAIN = "v1/tools/cli/src/main/scala"
EXIT_RE = re.compile(r'\b(?:sys\.exit|System\.exit)\b')

# Members that can end the process: a direct exit call, or (transitively, within the same object) a
# call to one that does. Deliberately intra-object — a cross-object call graph needs a real compiler
# front end, and over-reaching would produce noise that gets the guard switched off. Recorded as a
# known bound rather than left implicit.
exiting = {}
for d, _, fs in os.walk(MAIN):
    for f in fs:
        if not f.endswith(".scala"):
            continue
        src = open(os.path.join(d, f), encoding="utf-8", errors="replace").read()
        if not EXIT_RE.search(src):
            continue
        objs = re.findall(r'^\s*(?:private\s+)?object\s+([A-Za-z_][A-Za-z0-9_]*)', src, re.M)
        if not objs:
            continue
        owner = objs[-1]
        members = exiting.setdefault(owner, set())
        parts = re.split(r'^(?=\s{0,4}(?:private\s+|protected\s+)?(?:final\s+)?def\s)', src, flags=re.M)
        def name_of(part):
            m = re.match(r'\s*(?:private\s+|protected\s+)?(?:final\s+)?def\s+([A-Za-z_][A-Za-z0-9_]*)', part)
            return m.group(1) if m else None
        for part in parts:
            n = name_of(part)
            if n and EXIT_RE.search(part):
                members.add(n)
        for _ in range(3):                      # fixpoint over an object's own call edges
            for part in parts:
                n = name_of(part)
                if not n or n in members:
                    continue
                if any(re.search(r'\b' + re.escape(e) + r'\s*\(', part) for e in members):
                    members.add(n)

roots = ["."]
extra = os.environ.get("EXTRA", "")
if extra:
    roots.append(extra)

hits = []
for r in roots:
    for d, _, fs in os.walk(r):
        if "/target/" in d or "/.git" in d:
            continue
        if "src/test" not in d and not extra:
            continue
        if extra and r == extra:
            pass
        elif "src/test" not in d:
            continue
        for f in fs:
            if not f.endswith(".scala"):
                continue
            p = os.path.join(d, f)
            src = open(p, encoding="utf-8", errors="replace").read()
            # strip comments so a doc-comment MENTION is not a call (this guard's own header would
            # otherwise trip it, which is how the first draft failed)
            src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
            src = re.sub(r'^\s*//.*$', '', src, flags=re.M)
            for owner, members in exiting.items():
                for me in members:
                    if re.search(r'\b' + re.escape(owner) + r'\s*\.\s*' + re.escape(me) + r'\s*\(', src):
                        hits.append(f"{p}|{owner}|{me}")
for h in sorted(set(hits)):
    print(h)
PY
}

report() { # report <hits>
  local hits="$1"
  printf '\n✋ a test can reach a CLI boundary that ends the process:\n\n'
  while IFS='|' read -r t o m; do
    [[ -n "$t" ]] && printf '  %s\n      calls %s.%s, which can sys.exit\n' "$t" "$o" "$m"
  done <<<"$hits"
  cat <<'EOF'

An exit reached from an in-process test kills the forked test JVM: the run reports
"Tests: succeeded N, failed 0", then dies with `ForkMain … failed with exit code`, and the case that
was running never appears at all — a green-looking red (BUGS cli-command-System.exit-kills-the-test-fork).

Fix by calling the exit-FREE equivalent. The established shape is OAuthCli: `status(args): Int` holds
the logic and returns a code, `run` is `status` plus the exit and is the only thing main may call.
Give the boundary you need that split, and have the test assert the code.
EOF
}

if [[ "$SELF_TEST" -eq 1 ]]; then
  TMP="$(mktemp -d "${TMPDIR:-/tmp}/cli-exit-guard.XXXXXX")"
  trap 'rm -rf "$TMP"' EXIT
  mkdir -p "$TMP/src/test/scala"

  # (1) must FIRE: a test calling the known exiting boundary
  cat > "$TMP/src/test/scala/TripTest.scala" <<'SCALA'
class TripTest:
  def go(): Unit = OAuthCli.run(List("mint", "short", "alice"))
SCALA
  if [[ -z "$(scan "$TMP")" ]]; then
    printf 'SELF-TEST FAIL: the guard stayed quiet on a test that calls OAuthCli.run\n' >&2
    exit 1
  fi
  printf 'self-test: fires on a test that reaches an exiting boundary — ok\n'

  # (2) must STAY QUIET: the same call spelled against the exit-free entry point
  cat > "$TMP/src/test/scala/TripTest.scala" <<'SCALA'
class TripTest:
  def go(): Unit = val rc = OAuthCli.status(List("mint", "short", "alice")); assert(rc == 0)
SCALA
  if [[ -n "$(scan "$TMP")" ]]; then
    printf 'SELF-TEST FAIL: the guard fired on OAuthCli.status, which cannot exit\n' >&2
    exit 1
  fi
  printf 'self-test: quiet on the exit-free equivalent — ok\n'
fi

HITS="$(scan "")"
if [[ -n "$HITS" ]]; then
  report "$HITS"
  exit 1
fi
printf 'cli-exit-reachability: no test reaches an exiting CLI boundary\n'
