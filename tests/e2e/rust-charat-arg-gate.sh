#!/usr/bin/env bash
#
# rust-charat-arg-gate — `f(s.charAt(i))` at an `Int` parameter builds and answers the same.
#
# `charAt` returns `SscChar` on this lane, a newtype over i64 that exists so `Display` prints a
# CHARACTER rather than a number. It compares and does arithmetic against `i64` through its own
# impls, so `c == 44` and `c - 48` already worked — but Rust has no implicit conversion, so handing
# one to a def declared `(c: Int)` was `error[E0308]: expected i64, found SscChar`. Eight of the
# thirty-two errors `std/json-core.ssc` emits are exactly this shape.
#
# `.0`, NOT `.ssc_to_int()`. The first version emitted the trait method and produced a crate that
# said `no method named ssc_to_int found for struct SscChar` — the trait is only emitted when
# something else pulls it in. That was invisible in the emitted TEXT, which read exactly as intended,
# and visible the moment the probe was RUN. Hence this gate runs the binary rather than grepping the
# crate.
#
# COMPARED AGAINST `run`: `hex` returns a code-unit arithmetic result, so a lane that silently passed
# the wrong integer would still compile and print a plausible number.
#
# COST: one cargo build plus one interpreter run, ~40 s.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
ssc="${SSC_BIN:-$ROOT/bin/ssc}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" && -x "$ssc" ]] || { echo "rust-charat-arg-gate: no launcher — run ./install.sh --dev" >&2; exit 2; }
if ! command -v cargo >/dev/null 2>&1; then
  echo "rust-charat-arg-gate: [skip] cargo is not on PATH. That is a SKIP, not a pass." >&2
  exit 0
fi

sandbox=$(mktemp -d "$ROOT/examples/_charat.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

cat > "$sandbox/c.ssc" <<'SSC'
def hex(c: Int): Int =
  if c >= 48 && c <= 57 then c - 48
  else if c >= 97 && c <= 102 then c - 87
  else -1

def main(): Unit =
  val s = "7f"
  println("digit0: " + hex(s.charAt(0)))
  println("digit1: " + hex(s.charAt(1)))

main()
SSC

int_out=$(timeout 600 "$ssc" run "$sandbox/c.ssc" 2>/dev/null)
[[ -z "$int_out" ]] && { echo "  ✗ the interpreter produced nothing — the oracle is unusable" >&2; exit 1; }

if ! (cd "$sandbox" && timeout 900 "$tools" build-rust "$sandbox/c.ssc" >"$sandbox/build.log" 2>&1); then
  echo "  ✗ build-rust failed — a charAt result does not reach an Int parameter:"
  grep -m3 -E 'error\[E[0-9]+\]|Generic\(' "$sandbox/build.log" | cut -c1-110 | sed 's/^/      /'
  exit 1
fi
rust_out=$(timeout 300 "$sandbox/c" 2>/dev/null)

echo "── a charAt result reaches an Int parameter"
while IFS= read -r row; do
  got=$(printf '%s\n' "$rust_out" | grep -F "${row%%:*}:" || true)
  if [[ "$got" == "$row" ]]; then echo "  ✓ $row"
  else echo "  ✗ ${row%%:*}: rust '${got#*: }', interpreter '${row#*: }'"; fails=$((fails + 1)); fi
done <<< "$int_out"

# The oracle must still be right: '7' is 55, so hex gives 7; 'f' is 102, so hex gives 15. Two lanes
# that had both regressed to, say, returning the code unit would agree on every row above.
if printf '%s\n' "$int_out" | grep -q 'digit1: 15'; then
  echo "  ✓ the oracle itself is right: 'f' is 102 and hex(102) is 15, not 102"
else
  echo "  ✗ the interpreter no longer answers 15 — the oracle regressed"; fails=$((fails + 1))
fi

echo
if [[ "$fails" -ne 0 ]]; then echo "rust-charat-arg-gate: FAIL ($fails)" >&2; exit 1; fi
echo "rust-charat-arg-gate: PASS"
