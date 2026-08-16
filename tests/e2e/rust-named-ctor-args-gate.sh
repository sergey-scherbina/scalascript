#!/usr/bin/env bash
#
# rust-named-ctor-args-gate — `Ctor(field = v)` builds, with every other field from its default.
#
# WHERE THIS CAME FROM. Two rozum requests landed fields on `ProcessOptions` this week — `stdin`
# (`process-needs-a-stdin-pipe`) and the options `exec` had been dropping — and then the natural
# spelling of both, the one `std/process.ssc` itself documents, did not build on the lane they are
# porting to:
#
#     exec("pwd", List(), ProcessOptions(cwd = Some("/tmp")))
#     error[E0063]: missing fields `env`, `inheritEnv`, `stdin` and 1 other field
#
# The named branch built the struct literal from the NAMED fields alone. A probe on a LATE field
# settled which half was broken: it emitted `inheritEnv: false`, not `cwd: false`, so the names were
# already right and the defaults were the missing part.
#
# ROW 4 IS THE ONE THAT CANNOT PASS BY ACCIDENT. `Point(y = 1, x = 2)` names two fields OUT OF
# DECLARATION ORDER and omits a third. If the fix reordered wrongly it prints `1,2,…`; if it dropped
# the default it does not compile; if it filled the wrong slot the third field is not `d`. One row,
# three distinguishable failures — which is why it is worth more than the three single-field rows
# above it, and why those are still here: they are the shapes users actually write.
#
# COMPARED AGAINST `run` ROW BY ROW, not against literals: a change that moved both lanes together
# is still a defect.
#
# COST: one cargo build plus one interpreter run, ~45 s.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
ssc="${SSC_BIN:-$ROOT/bin/ssc}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" && -x "$ssc" ]] || { echo "rust-named-ctor-args-gate: no launcher — run ./install.sh --dev" >&2; exit 2; }

if ! command -v cargo >/dev/null 2>&1; then
  echo "rust-named-ctor-args-gate: [skip] cargo is not on PATH. That is a SKIP, not a pass." >&2
  exit 0
fi

sandbox=$(mktemp -d "$ROOT/examples/_namedctor.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

cat > "$sandbox/n.ssc" <<'SSC'
[exec, ProcessOptions](../../std/process.ssc)

case class Point(x: Int, y: Int = 7, tag: String = "d")

def main(): Unit =
  val a = exec("pwd", List(), ProcessOptions(cwd = Some("/tmp")))
  println("named first  : " + a.stdout.trim.endsWith("tmp"))
  val b = exec("sh", List("-c", "echo [$HOME]"), ProcessOptions(inheritEnv = false))
  println("named late   : " + b.stdout.trim)
  val c = exec("cat", List(), ProcessOptions(stdin = Some("piped\n")))
  println("named newest : " + c.stdout.trim)
  val p = Point(y = 1, x = 2)
  println("out of order : " + p.x + "," + p.y + "," + p.tag)

main()
SSC

int_out=$(timeout 600 "$ssc" run "$sandbox/n.ssc" 2>/dev/null)
if [[ -z "$int_out" ]]; then
  echo "  ✗ the interpreter produced nothing — the oracle is unusable, so this gate cannot decide" >&2
  exit 1
fi

if ! (cd "$sandbox" && timeout 900 "$tools" build-rust "$sandbox/n.ssc" >"$sandbox/build.log" 2>&1); then
  echo "  ✗ build-rust failed — a named constructor argument does not lower:"
  grep -m3 -E 'Generic\(|error\[E[0-9]+\]' "$sandbox/build.log" | cut -c1-120 | sed 's/^/      /'
  exit 1
fi
rust_out=$("$sandbox/n" 2>/dev/null)

echo "── a named constructor argument fills the rest from the declared defaults"
while IFS= read -r want; do
  label=${want%%:*}
  got=$(printf '%s\n' "$rust_out" | grep -F "$label:" || true)
  if [[ "$got" == "$want" ]]; then
    echo "  ✓ ${want}"
  else
    echo "  ✗ ${label}: rust '${got#*: }', interpreter '${want#*: }'"
    fails=$((fails + 1))
  fi
done <<< "$int_out"

# The oracle must still be right: two lanes that had both regressed would agree on every row above.
# `2,1,d` pins all three of the things row 4 can get wrong at once — order, the omitted field's
# default, and which slot each name landed in.
if printf '%s\n' "$int_out" | grep -q 'out of order : 2,1,d'; then
  echo "  ✓ the oracle itself is right: out-of-order names keep their fields and the default holds"
else
  echo "  ✗ the interpreter no longer answers '2,1,d' — the oracle regressed, so agreement between"
  echo "    the lanes proves nothing here"
  fails=$((fails + 1))
fi

echo
if [[ "$fails" -ne 0 ]]; then
  echo "rust-named-ctor-args-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "rust-named-ctor-args-gate: PASS"
