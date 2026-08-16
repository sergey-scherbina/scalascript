#!/usr/bin/env bash
#
# rust-matches-regex-gate — `String.matches(p)` is a FULL-match regex test on this lane too, and the
# `regex` dependency it needs is carried only by programs that use it.
#
# THE DEFECT. Scala's `String.matches(regex)` is a full-match regex test returning Boolean; Rust's
# `str::matches(pattern)` returns an ITERATOR over occurrences of a LITERAL pattern. With no arm,
# the call fell through the generic member path and emitted the Rust member of the same name — the
# same call, a different language, a different answer. It was caught only by luck (the one site in
# std negates the result, so rustc complained about `!Matches<'_, String>`); in any context that
# consumes an iterator or discards it, the emission COMPILES and is silently wrong. The lane refused
# it for that reason until the project chose to depend on `regex`.
#
# THE ROWS ARE ANSWERS, NOT COMPILATION, and each is a case where a literal-substring reading would
# differ from a regex reading:
#
#   "abc123" matches "[a-z]+\d+"     true    — a real pattern, not a literal
#   "abc"    matches "[a-z]+\d+"     false   — and it must say NO
#   "abcd"   matches "abc"           false   — FULL match: Rust's `is_match` would say yes
#   "2026-08-16" matches "\d{4}-…"   true    — quantifiers survive the anchoring
#
# THE THIRD ROW IS THE ONE THAT CATCHES A WRONG ANCHORING, which is the likeliest way to get this
# subtly wrong: `Regex::is_match` SEARCHES, so the lowering wraps the pattern as `^(?:…)$`.
#
# THE LAST ROW IS ABOUT THE DEPENDENCY, and it exists because getting it wrong broke twenty-odd
# modules: the runtime helper references the `regex` crate, so it must be emitted only where the
# dependency is added. A program with no `matches` must contain neither.
#
# COST: two cargo builds, ~40 s. Lives in ci.yml with the other cargo gates.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" ]] || { echo "rust-matches-regex-gate: no launcher at $tools — run ./install.sh --dev" >&2; exit 2; }

sandbox=$(mktemp -d "$ROOT/examples/_matchesrx.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

cat > "$sandbox/m.ssc" <<'SSC'
def main(): Unit =
  println("abc123".matches("[a-z]+\\d+"))
  println("abc".matches("[a-z]+\\d+"))
  println("abc".matches("abc"))
  println("abcd".matches("abc"))
  println("2026-08-16".matches("\\d{4}-\\d{2}-\\d{2}"))
main()
SSC

cat > "$sandbox/nomatches.ssc" <<'SSC'
def main(): Unit =
  println("plain".length)
main()
SSC

WANT='true|false|true|false|true|'

echo "── matches is a full-match regex test on every lane"
for lane in run v1; do
  if [[ "$lane" == run ]]; then out=$(timeout 200 "$ssc" run "$sandbox/m.ssc" 2>&1 | head -6 | tr '\n' '|')
  else out=$(timeout 200 "$tools" run --v1 "$sandbox/m.ssc" 2>&1 | head -6 | tr '\n' '|'); fi
  if [[ "$out" == "$WANT" ]]; then echo "  ✓ $lane: $out"
  else echo "  ✗ $lane: got '$out', wanted '$WANT'"; fails=$((fails + 1)); fi
done

if ! command -v cargo >/dev/null 2>&1; then
  echo "  [skip] cargo is not on PATH — the Rust rows cannot run. That is a SKIP, not a pass."
  echo "rust-matches-regex-gate: PASS (rust rows skipped)"; exit 0
fi

if ! (cd "$sandbox" && timeout 900 "$tools" build-rust "$sandbox/m.ssc" >"$sandbox/m.log" 2>&1); then
  echo "  ✗ rust: build failed: $(grep -m1 -E 'error\[E[0-9]+\]|^error|Generic\(' "$sandbox/m.log" | cut -c1-90)"
  fails=$((fails + 1))
else
  out=$("$sandbox/m" 2>&1 | head -6 | tr '\n' '|')
  if [[ "$out" == "$WANT" ]]; then echo "  ✓ rust: $out"
  else echo "  ✗ rust: got '$out', wanted '$WANT'"; fails=$((fails + 1)); fi
fi

echo "── the dependency is carried only by a program that uses it"
# EMIT rather than build: `build-rust` compiles in a temp directory and leaves no crate here, so a
# check against `$sandbox/m-rust` would be reading a path that does not exist — and "absent" would
# read as "clean" for the non-user row. `emit-rust` writes the crate where it is asked to, and the
# rows below FAIL when the file is missing instead of passing by absence.
(cd "$sandbox" && timeout 300 "$tools" emit-rust "$sandbox/m.ssc" >/dev/null 2>&1)
(cd "$sandbox" && timeout 300 "$tools" emit-rust "$sandbox/nomatches.ssc" >/dev/null 2>&1)
for pair in "m:user of matches" "nomatches:non-user"; do
  name=${pair%%:*}; label=${pair#*:}
  toml="$sandbox/$name-rust/Cargo.toml"
  rt="$sandbox/$name-rust/src/runtime/mod.rs"
  if [[ ! -f "$toml" || ! -f "$rt" ]]; then
    echo "  ✗ $label: no emitted crate at $sandbox/$name-rust — the check would have passed by absence"
    fails=$((fails + 1)); continue
  fi
  hasDep=no; grep -q '^regex' "$toml" && hasDep=yes
  hasFn=no;  grep -q '_str_matches' "$rt" && hasFn=yes
  case "$name:$hasDep:$hasFn" in
    m:yes:yes)          echo "  ✓ $label: dependency and helper both present" ;;
    nomatches:no:no)    echo "  ✓ $label: neither the dependency nor the helper" ;;
    *) echo "  ✗ $label: dependency=$hasDep helper=$hasFn — the two must agree, or the crate is E0433"
       fails=$((fails + 1)) ;;
  esac
done

echo
if [[ "$fails" -ne 0 ]]; then
  echo "rust-matches-regex-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "rust-matches-regex-gate: PASS"
