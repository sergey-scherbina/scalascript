#!/usr/bin/env bash
# P6.5 F — REAL-CORPUS acceptance gate (the F3 progress metric).
#
# specs/newfront-diff.sh measures the Scala SPIKE vs ssc1-front. THIS measures P6.5's F -- the
# subset-written, self-hosting compiler (specs/v2.2-p6.5-fsub.ssc) -- vs the SAME oracle
# (ssc1-front + ssc1-lower), over the SAME real corpus, byte for byte. It is the honest "how much of
# the real language does F cover today" number for F3, and it reproduces + generalizes the audit's
# hand-measured 0/5 (specs/v2-front-convergence-2026-07-18.md §5) to the full corpus.
#
# For every extracted corpus program P (pure ScalaScript source; markdown/fences already stripped by
# ssc1-run's sscProgramSource, exactly as newfront-diff.sh extracts it):
#   ref  = #coreir.encode(lowerProg(parse(code)))   -- FRESH JVM per file (ssc1-front + ssc1-lower);
#                                                       fresh JVM avoids the parser-owned-cell leak
#   mine = run-ir F0.ir code                         -- F0 = driver(ssc1-front(F)): F made runnable
#   MATCH iff cmp -s mine ref
#
# Reuses $NEWFRONT_WORK/{code,ref} when present, so a newfront-diff.sh run and this share the
# (identical) extraction + reference IRs and this only has to compute F's side.
#
# AGENTS.md §"measurement apparatus must COMPARE, never PRE-JUDGE": FAILS LOUDLY (exit 2) if nothing is
# extracted / bootstrapped / compared. A gate that reports 0==green is worse than no gate.
#
# Prereqs: SSC_JAR = run-ir-capable v2 kernel jar
#          (build: `scala-cli --power package v2/src --assembly -o /tmp/ssc.jar`)
#          V2_DIR = <repo>/v2
set -u
JAR=${SSC_JAR:?set SSC_JAR to a run-ir-capable v2 kernel jar}; V2=${V2_DIR:?set V2_DIR to <repo>/v2}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
FSUB="$ROOT/specs/v2.2-p6.5-fsub.ssc"
WORK=${NEWFRONT_WORK:-$(mktemp -d)}
mkdir -p "$WORK/code" "$WORK/ref" "$WORK/p65"
# K62.3 (fixed 2026-07-20): size the onSizedStack WORKER via -Dssc.stackSize (bytes); the old
# -Xss512m sized only the MAIN thread (dead flag) and F0 overflowed the 64 MB worker default. See
# specs/v2.2-p6.5-fsub.sh for the full note. Exported so the parallel worker heredoc inherits it.
SSC_STACK=${SSC_STACK:-1073741824}
JVM="-Dssc.stackSize=$SSC_STACK"
export JVM
run()   { java $JVM -jar "$JAR" run "$@" 2>/dev/null; }
die() { echo "FATAL(p65-corpus): $1" >&2; shift; for l in "$@"; do echo "       $l" >&2; done; exit 2; }
[ -f "$FSUB" ] || die "F source not found: $FSUB"
cd "$V2" || die "cannot cd to V2_DIR=$V2"
# F0 is F interpreted on the VM; on corpus inputs OUTSIDE F's subset its recursive-descent parser can
# loop or be pathologically slow. WITHOUT a per-file timeout ONE such program hangs the whole gate
# (measured: the run stalled at 13/504 forever) — a gate that hangs is as untrustworthy as one that
# lies green. So each file is bounded; a file that exceeds the budget is a distinct TIMEOUT bucket,
# never a silent MATCH/drop.
TIMEOUT_BIN=$(command -v timeout || command -v gtimeout || true)
P65_TIMEOUT=${P65_TIMEOUT:-20}
[ -n "$TIMEOUT_BIN" ] || echo "WARN: no timeout(1)/gtimeout(1) found — a pathological program can hang this gate. brew install coreutils." >&2

# ── 1. extract (reuse $WORK/code if already populated, e.g. by specs/newfront-diff.sh) ──
ncode=$(ls "$WORK/code"/*.code 2>/dev/null | wc -l | tr -d ' ')
if [ "$ncode" -eq 0 ]; then
  echo "extracting corpus (ssc1-run's sscProgramSource)..."
  awk '/^def main = \(\) =>/{exit} {print}' "$V2/bin/ssc1-run.ssc0" > "$V2/bin/_p65c_extract.ssc0"
  cat >> "$V2/bin/_p65c_extract.ssc0" <<'DRV'
def exOne = (p) => #sconcat("@@F@@", #sconcat(p, #sconcat("@@C@@", #sconcat(sscProgramSource(#utf8->str(#io.readFile(p))), "@@E@@"))))
def exAll = (ps) => match ps { case Nil => "" case Cons(p, r) => #sconcat(exOne(p), exAll(r)) }
def main = () => #io.print(exAll(#io.args()))
DRV
  FILES=$(ls "$ROOT"/examples/*.ssc "$ROOT"/tests/conformance/*.ssc 2>/dev/null)
  run bin/_p65c_extract.ssc0 $FILES > "$WORK/all.extracted"
  rm -f "$V2/bin/_p65c_extract.ssc0"
  python3 - "$WORK/all.extracted" "$WORK/code" <<'PY'
import sys, os, re
blob=open(sys.argv[1],encoding='utf-8',errors='replace').read(); outd=sys.argv[2]
for m in re.finditer(r'@@F@@(.*?)@@C@@(.*?)@@E@@', blob, re.DOTALL):
    name=os.path.splitext(os.path.basename(m.group(1)))[0]
    open(os.path.join(outd,name+'.code'),'w',encoding='utf-8').write(m.group(2))
PY
  ncode=$(ls "$WORK/code"/*.code 2>/dev/null | wc -l | tr -d ' ')
fi
echo "corpus: $ncode extracted .code files"
[ "$ncode" -gt 0 ] || die "extracted 0 .code files — the corpus extractor produced nothing." \
  "checked: $ROOT/examples/*.ssc and $ROOT/tests/conformance/*.ssc"

# ── 2. bootstrap F0 = driver(ssc1-front(F)) : F made runnable on the VM (same driver as fsub.sh) ──
cat > "$V2/bin/_p65c_drv.ssc0" <<'DRV'
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
FSUB_SRC="$FSUB" run bin/_p65c_drv.ssc0 > "$WORK/F0.ir"
rm -f "$V2/bin/_p65c_drv.ssc0"
[ -s "$WORK/F0.ir" ] || die "could not bootstrap F0 from $FSUB" \
  "(re-run specs/v2.2-p6.5-fsub.sh to see the bootstrap error)"
echo "F0 bootstrapped ($(wc -c < "$WORK/F0.ir") bytes)"

# ── 3. ref driver (fresh JVM per file, no parser-cell leak) ──
cat > "$V2/bin/_p65c_refone.ssc0" <<'DRV'
import "../lib/ssc1-lower.ssc0"
def main = () => match #io.args() { case Cons(p, r) => #io.print(#coreir.encode(lowerProg(parse(#utf8->str(#io.readFile(p)))))) case Nil => #io.eprint("no-arg") }
DRV

# ── 4. compare per file (parallel): mine = F0(code), ref = fresh-JVM oracle ──
# QUOTED heredoc: written verbatim; WORK/V2/JAR/TIMEOUT_BIN/P65_TIMEOUT/JVM reach the worker via exported env.
cat > "$WORK/p65worker.sh" <<'WORKER'
#!/usr/bin/env bash
code="$1"; n=$(basename "$code" .code); ref="$WORK/ref/$n.ir"; mine="$WORK/p65/$n.ir"
[ -s "$ref" ] || ( cd "$V2" && java $JVM -jar "$JAR" run bin/_p65c_refone.ssc0 "$code" 2>/dev/null ) > "$ref"
[ -s "$ref" ] || { echo "SKIP $n"; exit 0; }
rc=0
if [ -n "$TIMEOUT_BIN" ]; then
  "$TIMEOUT_BIN" "$P65_TIMEOUT" java $JVM -jar "$JAR" run-ir "$WORK/F0.ir" "$code" 2>/dev/null > "$mine"; rc=$?
else
  java $JVM -jar "$JAR" run-ir "$WORK/F0.ir" "$code" 2>/dev/null > "$mine"; rc=$?
fi
if [ "$rc" -eq 124 ]; then echo "TIMEOUT $n |F0 exceeded ${P65_TIMEOUT}s (ref $(wc -c < "$ref")B)"
elif cmp -s "$mine" "$ref"; then echo "MATCH $n"
elif [ ! -s "$mine" ]; then echo "EMPTY $n |F produced nothing (ref $(wc -c < "$ref")B)"
else echo "DIFF $n |ref $(wc -c < "$ref")B mine $(wc -c < "$mine")B"; fi
WORKER
export WORK V2 JAR TIMEOUT_BIN P65_TIMEOUT JVM
ls "$WORK/code"/*.code | xargs -P 6 -I{} bash "$WORK/p65worker.sh" {} > "$WORK/p65results.txt"
rm -f "$V2/bin/_p65c_refone.ssc0"

# ── report ──
m=$(grep -c '^MATCH ' "$WORK/p65results.txt"); di=$(grep -c '^DIFF ' "$WORK/p65results.txt")
em=$(grep -c '^EMPTY ' "$WORK/p65results.txt"); sk=$(grep -c '^SKIP ' "$WORK/p65results.txt")
to=$(grep -c '^TIMEOUT ' "$WORK/p65results.txt")
t=$((m+di+em+to))
[ "$t" -gt 0 ] || die "0 programs compared — apparatus broken (no ref IRs / no F0 output reached the comparator)." \
  "results file: $WORK/p65results.txt"
echo "═══════ P6.5 F vs ssc1-front+ssc1-lower, $t programs ($sk skipped: ref empty) ═══════"
printf "  MATCH byte-identical : %d (%d%%)\n" "$m" "$((t>0?m*100/t:0))"
printf "  DIFF  content differs: %d\n" "$di"
printf "  EMPTY F produced none: %d\n" "$em"
printf "  TIMEOUT (>%ss)        : %d\n" "$P65_TIMEOUT" "$to"
echo "─── MATCHing programs (F already reproduces these byte-for-byte) ───"
grep '^MATCH ' "$WORK/p65results.txt" | awk '{print $2}' | sort | tr '\n' ' '; echo
echo "(full results: $WORK/p65results.txt)"
