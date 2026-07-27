#!/usr/bin/env bash
# F5b — census of the UNTYPED `__method__` sites F still emits, by method name.
#
# Why this exists: F5b slices past Stage 1 are about replacing `__method__` tag-dispatch with typed
# prims, and the kernel-line payoff is proportional to which arms actually carry traffic. Both earlier
# slices picked their target from the design document rather than from the emitted IR, and both shipped
# a first probe that could not fail (one compared OUTPUT, which agrees whether or not the lowering is
# typed; the other grepped LINES on a single-line IR). This measures the IR itself, and prints the
# NEGATIVE cases beside the positive ones, because the negatives are what protect the runtime.
#
#   ./specs/v2-f5b-method-census.sh [program.ssc ...]
#
# With no argument it censuses F's OWN source — the largest real program F compiles, and the one whose
# fixpoint is the self-hosting claim. Extra arguments are censused individually.
#
# Prereqs (same as v2.2-p6.5-fsub.sh): SSC_JAR = run-ir-capable kernel jar, V2_DIR = <repo>/v2
set -u
JAR=${SSC_JAR:?set SSC_JAR to the ssc kernel jar}; V2=${V2_DIR:?set V2_DIR to <repo>/v2}
HERE=$(cd "$(dirname "$0")" && pwd)
FSUB="$HERE/v2.2-p6.5-fsub.ssc"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"; rm -f "$V2/bin/_p65_census_drv.ssc0"' EXIT
cd "$V2" || exit 2
SSC_STACK=${SSC_STACK:-1073741824}
JVM="-Dssc.stackSize=$SSC_STACK"
run()   { java $JVM -jar "$JAR" run "$@" 2>/dev/null; }
runir() { java $JVM -jar "$JAR" run-ir "$@" 2>/dev/null; }

# Same driver as the X1 gate: `#`-prims are ssc0, so F's pure `compile` needs a file-reading wrapper.
cat > bin/_p65_census_drv.ssc0 <<'DRV'
import "../lib/ssc1-lower.ssc0"
def app2 = (xs, ys) => match xs { case Nil => ys case Cons(h, t) => Cons(h, app2(t, ys)) }
def prim0 = (nm) => Pair("prim", Pair(nm, Nil))
def prim1 = (nm, a) => Pair("prim", Pair(nm, Cons(a, Nil)))
def consPat = Pair("cpat", Pair("Cons", Cons(Pair("vpat", "path"), Cons(Pair("vpat", "rest"), Nil))))
def readCompile = (dqArg, bsArg) => prim1("io.print", mkApp(mkVar("compile"), Cons(prim1("utf8->str", prim1("io.readFile", mkVar("path"))), Cons(dqArg, Cons(bsArg, Nil)))))
def fileMain = (dqArg, bsArg) => mkDef("main", Nil, Pair("match", Pair(prim0("io.args"), Cons(Pair(consPat, readCompile(dqArg, bsArg)), Nil))))
def main = () =>
  let srcPath = match #io.env("FSUB_SRC") { case Some(p) => p case None => "" } in
  let fsubSrc = #utf8->str(#io.readFile(srcPath)) in
  let dqArg = mkStr(#sfromCodes(Cons(34, Nil))) in
  let bsArg = mkStr(#sfromCodes(Cons(92, Nil))) in
  let prog = app2(parse(fsubSrc), Cons(fileMain(dqArg, bsArg), Nil)) in
  #io.print(#coreir.encode(lowerProg(prog)))
DRV

FSUB_SRC="$FSUB" run bin/_p65_census_drv.ssc0 > "$WORK/F0.ir"
[ -s "$WORK/F0.ir" ] || { echo "FAIL: could not bootstrap F0 from $FSUB"; exit 1; }
echo "F0 bootstrapped ($(wc -c < "$WORK/F0.ir" | tr -d ' ') bytes)"

# Count `(prim __method__ ... (lit (str "NAME"))` occurrences by NAME. The IR is ONE line, so this
# counts OCCURRENCES via grep -o, never lines — the exact mistake the slice-1b-3 probe made first.
census() { # census <label> <ir-file>
  local label="$1" ir="$2"
  local total
  total=$(grep -o '__method__' "$ir" 2>/dev/null | wc -l | tr -d ' ')
  echo
  echo "=== $label — $(wc -c < "$ir" | tr -d ' ') B IR, $total untyped __method__ site(s) ==="
  if [ "$total" = "0" ]; then echo "  (none)"; return; fi
  # The method name is the first string literal following the __method__ tag. Parsed in python, not
  # with a grep/sed pipeline: the IR is one line and method names can contain `)` and escaped quotes,
  # and a regex that stops at the first `)` silently mis-buckets them (the first version of this
  # script reported 14 sites as a bare `"` for exactly that reason). Anything that does NOT match the
  # literal-name shape is reported as `<dynamic/other>` rather than dropped — an unexplained residue
  # is a finding, not noise.
  python3 - "$ir" <<'PYCENSUS'
import sys, collections
ir = open(sys.argv[1], encoding="utf-8", errors="replace").read()
LIT = '(lit (str "'
counts, other = collections.Counter(), 0
i = 0
while True:
    i = ir.find("__method__", i)
    if i < 0:
        break
    i += len("__method__")
    j = i
    while j < len(ir) and ir[j].isspace():
        j += 1
    # A named call site is exactly `__method__ (lit (str "NAME"))`. Read NAME with a real string
    # scanner (escape-aware) instead of a regex: the IR is ONE line, so a regex that stops at the
    # first `)` mis-buckets names, and one that allows any body silently swallows hundreds of
    # characters of unrelated IR. Both mistakes were made here before this scanner existed.
    if not ir.startswith(LIT, j):
        other += 1
        continue
    k = j + len(LIT)
    buf = []
    while k < len(ir) and ir[k] != '"':
        if ir[k] == "\\" and k + 1 < len(ir):
            buf.append(ir[k + 1]); k += 2; continue
        buf.append(ir[k]); k += 1
    name = "".join(buf)
    # A method name is a plain identifier/operator. Anything else means the scan desynchronised —
    # report it rather than inventing a bucket for it.
    if k >= len(ir) or not name or any(c in name for c in "()\n "):
        other += 1
        continue
    counts[name] += 1
    i = k
total = sum(counts.values()) + other
for name, n in counts.most_common(25):
    print(f"  {n:5d} .{name}")
if other:
    print(f"  {other:5d} <dynamic/other — no literal name at the call site>")
print(f"  ----- {total} total (named {sum(counts.values())} + other {other})")
PYCENSUS
}

if [ "$#" -eq 0 ]; then
  # F's own source: the biggest real program in the loop, and the fixpoint subject.
  FSUB_SRC="$FSUB" run bin/_p65_census_drv.ssc0 > "$WORK/self.ir"
  census "F(F_src) — F's own source" "$WORK/self.ir"
else
  for p in "$@"; do
    case "$p" in /*) src="$p" ;; *) src="$OLDPWD/$p" ;; esac
    [ -f "$src" ] || { echo "skip (not a file): $p"; continue; }
    runir "$WORK/F0.ir" "$src" > "$WORK/p.ir"
    census "$p" "$WORK/p.ir"
  done
fi
