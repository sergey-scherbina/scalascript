#!/usr/bin/env bash
#
# rust-toint-parity-gate — a numeric conversion answers the same on `run` and on `build-rust`, and
# when it cannot, both lanes STOP.
#
# TWO USER REPORTS FROM ROZUM, one line apart in the runtime template:
#
#   toint-on-a-non-integer-diverges          `"abc".toInt` was 0 on build-rust and fatal on run
#   toint-on-a-char-and-tolist-on-a-string   `"ab".toList` was a closure on run and a list on build-rust
#
# Both are the same shape — the same source, the same input, two answers, no diagnostic — and both
# were found by a user whose program had to move lanes. The Rust arms were
# `parse::<i64>().unwrap_or(0)` and `parse::<f64>().unwrap_or(0.0)`, so a non-numeric string became
# a 0 nobody asked for; `run` throws, matching Scala, so `run` is the oracle and the quiet lane is
# the one that moved. `String.toList` had no arm at all in the v2 dispatcher — it had one for a
# list, an Option, a Set, a Map, a LazyList and an ArrayBuffer — so the selection eta-expanded into
# a function value.
#
# THE toDouble ROWS ARE NOT IN EITHER REPORT. The twin sat one line below the reported arm with the
# identical `unwrap_or(0.0)`, and fixing the reported one alone would have left it. It is asserted
# here so the pair stays fixed together.
#
# THE SILENT ZERO HAD TWO EMISSION PATHS, and this gate is what established that — it went red on a
# fix I had already called done:
#
#   receiver's type not statically known   ->  `_to_int(x)`, the runtime template
#   walker knows it is a String            ->  `("abc".to_string().parse::<i64>().unwrap_or(0))`
#                                              emitted INLINE, never reaching the helper
#
# Both now route through `_to_int` / `_to_double`, which is why there are rows for BOTH spellings:
# a fix to either one alone passes half of them.
#
# TWO MORE ROZUM REPORTS ARE ASSERTED HERE, because they are the same lane disagreement in a
# different method and they were fixed in the same pass:
#
#   `s.indexOf("</head>")`   took the LIST lowering (`s.iter().position(…)`) and did not compile
#   `parts(0) + SEP`         emitted `String + String`, an impl Rust does not have
#
# The `indexOf` rows include a NON-ASCII haystack on purpose: `str::find` answers a byte offset and
# Scala answers a UTF-16 index, so a fix that returns `find`'s number is right for ASCII and quietly
# wrong for anything else. `"héllo</head>x"` is 5 in Scala and 6 in bytes, and the row pins 5.
#
# COST: three cargo builds, measured 9 s standalone on a WARM cargo cache — the crates carry no
# external dependencies, which is why it is nothing like `build-rust-refuses-loudly` (74.8 s, one of
# its crates pulls serde_json). It still runs in `ci.yml` beside that gate rather than on the push
# path, because 9 s is the warm number and a CI runner compiles the crate cold; smoke was hitting
# its own job timeout when cargo gates lived there, and that is not a thing to re-learn.
# Without cargo it SKIPS loudly rather than passing quietly: a rust gate that silently becomes a
# no-op is worse than one that is missing.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" ]] || { echo "rust-toint-parity-gate: no launcher at $tools — run ./install.sh --dev" >&2; exit 2; }

sandbox=$(mktemp -d "${TMPDIR:-/tmp}/rust-toint.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

echo "── a numeric conversion answers the same on both lanes"

# ── the run lane ─────────────────────────────────────────────────────────────────────────────────
run_says() { # $1 name, $2 expected output (newlines as |), $3 source
  local name=$1 want=$2 src=$3 out
  printf '%s\n' "$src" > "$sandbox/$name.ssc"
  out=$(timeout 200 "$ssc" run "$sandbox/$name.ssc" 2>&1 | head -12 | tr '\n' '|')
  if [[ "$out" == "$want" ]]; then
    echo "  ✓ run  $name: $out"
  else
    echo "  ✗ run  $name: got '$out', wanted '$want'"
    fails=$((fails + 1))
  fi
}

# THE GOOD PATH, and it is one program on purpose: every row that does NOT stop the process shares a
# binary, because each cargo build is ~30 s and only a row that ABORTS needs one of its own.
GOOD='val SEP: String = "-"
def main(): Unit =
  println("8".toInt)
  println("1.5".toDouble)
  println("abc".charAt(0).toInt)
  println("ab".toList.length)
  println("ab".toList.map(c => c.toInt).sum)
  val h: String = "abc</head>def"
  println(h.indexOf("</head>"))
  val u: String = "héllo</head>x"
  println(u.indexOf("</head>"))
  println(u.indexOf("zzz"))
  val parts: List[String] = List("a", "b")
  println(parts(0) + SEP)
main()'
GOOD_WANT='8|1.5|97|2|195|3|5|-1|a-|'

run_says good "$GOOD_WANT" "$GOOD"

# The two that must STOP. `run` names the operation; the exact wording differs between lanes and is
# not asserted — what is asserted is that the program does not continue with a fabricated number.
BAD_INT='def f(s: String): Int = s.toInt
def main(): Unit = println(f("abc"))
main()'
BAD_DBL='def f(s: String): Double = s.toDouble
def main(): Unit = println(f("abc"))
main()'

run_says bad-toint 'ssc: String.toInt: invalid integer|' "$BAD_INT"
run_says bad-todouble 'ssc: For input string: "abc"|' "$BAD_DBL"

echo "── and the same source on the Rust lane"

if ! command -v cargo >/dev/null 2>&1; then
  echo "  [skip] cargo is not on PATH — the Rust half of this gate cannot run."
  echo "         That is a SKIP, not a pass: install a Rust toolchain to get the parity check."
else
  rust_says() { # $1 name, $2 expected stdout (newlines as |), $3 expected exit, $4 source
    local name=$1 want=$2 wantrc=$3 src=$4 out rc
    printf '%s\n' "$src" > "$sandbox/$name.ssc"
    if ! (cd "$sandbox" && timeout 600 "$tools" build-rust "$sandbox/$name.ssc" >"$sandbox/$name.build" 2>&1); then
      echo "  ✗ rust $name: build-rust failed"
      tail -3 "$sandbox/$name.build" | sed 's/^/        /'
      fails=$((fails + 1)); return
    fi
    out=$(cd "$sandbox" && timeout 200 "./$name" 2>/dev/null | head -12 | tr '\n' '|'); rc=$?
    # The binary's own exit code, not the pipeline's — a panic is the POINT of two of these rows.
    (cd "$sandbox" && timeout 200 "./$name" >/dev/null 2>&1); rc=$?
    if [[ "$out" == "$want" && "$rc" -eq "$wantrc" ]]; then
      echo "  ✓ rust $name: '$out' exit=$rc"
    else
      echo "  ✗ rust $name: got '$out' exit=$rc, wanted '$want' exit=$wantrc"
      fails=$((fails + 1))
    fi
  }

  rust_says good "$GOOD_WANT" 0 "$GOOD"

  # THE REPORTED DIVERGENCE, through the path this fix owns. It printed `false` before — the
  # round-trip check `s.toInt.toString == s` answered on this lane and killed the program on the
  # other. A PARAMETER receiver is not incidental: it is what routes through `_to_int`, and the
  # literal spelling still takes the inline path (see the header).
  rust_says bad-toint '' 101 "$BAD_INT"

  # THE OTHER EMISSION PATH, and the one a user actually writes. A literal receiver is typed for the
  # walker, so it used to take an INLINE `parse::<i64>().unwrap_or(0)` and never reached the runtime
  # helper at all — the first version of this gate went red here, on a fix I had already called
  # done. Both spellings now route through `_to_int`.
  rust_says bad-toint-literal '' 101 'def main(): Unit = println("abc".toInt)
main()'

  # THE TWIN NOBODY REPORTED.
  rust_says bad-todouble '' 101 "$BAD_DBL"
fi

echo "── and the SECOND Rust generator, which carried the same silent zero"
#
# `v2/backend/rust/` is the CoreIR → Rust generator, a different file from the `build-rust` walker
# above, and it had the identical `parse::<i64>().unwrap_or(0)` arm
# (BUGS `v2-rust-backend-carries-the-same-silent-zero-and-nothing-runs-it`).
#
# THE ENTRY SAID "NOTHING RUNS THIS BACKEND". That is half wrong and the half matters:
# `v2/backend/check.sh` drives it on every fixture with the VM as oracle — it is `check.sh` ITSELF
# that is invoked by nothing. But that harness compares STDOUT and treats a VM abort as "run-ir
# failed", so it structurally cannot express "must abort" and would never have seen this row. This
# gate can, because it reads the binary's exit code, and it is wired (ci.yml).
#
# ONE program for every row, not one per row: each row costs a scala-cli start plus a rustc, and the
# aborting call goes LAST so the rows before it are still observable in stdout.
#
# The wanted values are `run-ir`'s, re-derivable with:
#     java -jar <v2 jar> run-ir <the .coreir below>
if ! command -v rustc >/dev/null 2>&1; then
  echo "  [skip] rustc is not on PATH — the v2 generator half cannot run."
elif ! command -v scala-cli >/dev/null 2>&1; then
  echo "  [skip] scala-cli is not on PATH — the v2 generator half cannot run."
else
  cat > "$sandbox/v2conv.coreir" <<'IR'
(program
 (defs
  (def main
   (lam 0
    (seq (prim __autoOutput__ (prim __method__ (lit (str "toInt")) (lit (str "8"))))
     (seq (prim __autoOutput__ (prim __method__ (lit (str "toInt")) (lit (str " 8 "))))
     (seq (prim __autoOutput__ (prim __method__ (lit (str "toDouble")) (lit (str "8"))))
     (seq (prim __autoOutput__ (prim __method__ (lit (str "toFloat")) (lit (str "8"))))
     (seq (prim __autoOutput__ (prim __method__ (lit (str "toInt")) (lit (int 7))))
      (prim __autoOutput__ (prim __method__ (lit (str "toInt")) (lit (str "abc")))))))))))) 
 (entry (app (global main))))
IR
  if ! scala-cli run "$ROOT/v2/backend/rust" -q --server=false \
        < "$sandbox/v2conv.coreir" > "$sandbox/v2conv.rs" 2>"$sandbox/v2conv.genlog"; then
    echo "  ✗ v2 generator: could not emit Rust"
    tail -3 "$sandbox/v2conv.genlog" | sed 's/^/        /'
    fails=$((fails + 1))
  elif ! rustc -O "$sandbox/v2conv.rs" -o "$sandbox/v2conv-bin" 2>"$sandbox/v2conv.rustc"; then
    echo "  ✗ v2 generator: rustc refused the emitted source"
    tail -3 "$sandbox/v2conv.rustc" | sed 's/^/        /'
    fails=$((fails + 1))
  else
    v2out=$("$sandbox/v2conv-bin" 2>/dev/null | head -12 | tr '\n' '|')
    "$sandbox/v2conv-bin" >/dev/null 2>&1; v2rc=$?
    # `8|8|8|8|7|` then a panic. THREE of these six rows were wrong before, and only one of them was
    # the reported defect — the control run against the old generator reads
    #
    #     got '8|0|0|8|7|0|' exit=0
    #
    # i.e. `" 8 ".toInt` was 0 (no `.trim()`, while the VM parses `s.trim`), `"8".toDouble` was 0
    # (no String arm at all), and `"abc".toInt` was 0 instead of aborting. Two silent wrong answers
    # on VALID input, found only because the row list asked the siblings the same question.
    if [[ "$v2out" == "8|8|8|8|7|" && "$v2rc" -ne 0 ]]; then
      echo "  ✓ v2 generator: '$v2out' then exit=$v2rc (aborts on junk, like run)"
    else
      echo "  ✗ v2 generator: got '$v2out' exit=$v2rc, wanted '8|8|8|8|7|' and a non-zero exit"
      fails=$((fails + 1))
    fi
  fi
fi

echo
if [[ "$fails" -ne 0 ]]; then
  echo "rust-toint-parity-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "rust-toint-parity-gate: PASS"
