#!/usr/bin/env bash
# new-self-hosting-front — Phase 0 corpus byte-identity harness.
#
# For every corpus .ssc (examples/ + tests/conformance/), compare the Core IR of the NEW front (the spike)
# against the OLD front (ssc1-front), byte-for-byte, to measure how far the new front already covers the
# real corpus and where it diverges. Both sides use the SAME lowerProg on the SAME extracted code, so the
# comparison isolates the PARSER: ref = lowerProg(parse(code)); new = lowerProg(spikeProject(spikeParse(code))).
#
# Pipeline:
#   1. extract  each .ssc → pure ScalaScript code (reuse ssc1-run's sscProgramSource) — one JVM.
#   2. spike    (sbt) project every <name>.code → <name>.proj via ScalaSpikeSpec's "newfront corpus batch".
#   3. ref      lowerProg(parse(code)) for every code file — one JVM.
#   4. compare  per-file (parallel): lowerProg(proj) vs ref, byte-cmp; categorize divergences.
#
# Prereqs: SSC_JAR = a run-ir-capable v2 kernel jar (build it: `scala-cli --power package v2/src --assembly
# -o /tmp/ssc.jar` — the thin bin/lib/ssc.jar does NOT have run-ir). V2_DIR = <repo>/v2. Run from repo root.
set -u
JAR=${SSC_JAR:?set SSC_JAR to a run-ir-capable v2 kernel jar}; V2=${V2_DIR:?set V2_DIR to <repo>/v2}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=${NEWFRONT_WORK:-$(mktemp -d)}
mkdir -p "$WORK/code" "$WORK/proj" "$WORK/ref" "$WORK/spike"
run() { java -Xss512m -jar "$JAR" run "$@" 2>/dev/null; }

# ── 1. extract — ssc1-run's sscProgramSource, with its `main` stripped + a batch main appended ──
awk '/^def main = \(\) =>/{exit} {print}' "$V2/bin/ssc1-run.ssc0" > "$V2/bin/_nf_extract.ssc0"
cat >> "$V2/bin/_nf_extract.ssc0" <<'DRV'
def exOne = (p) => #sconcat("@@F@@", #sconcat(p, #sconcat("@@C@@", #sconcat(sscProgramSource(#utf8->str(#io.readFile(p))), "@@E@@"))))
def exAll = (ps) => match ps { case Nil => "" case Cons(p, r) => #sconcat(exOne(p), exAll(r)) }
def main = () => #io.print(exAll(#io.args()))
DRV
FILES=$(ls "$ROOT"/examples/*.ssc "$ROOT"/tests/conformance/*.ssc 2>/dev/null)
echo "corpus: $(echo "$FILES" | wc -l | tr -d ' ') programs"
( cd "$V2" && run bin/_nf_extract.ssc0 $FILES ) > "$WORK/all.extracted"
python3 - "$WORK/all.extracted" "$WORK/code" <<'PY'
import sys, os, re
blob=open(sys.argv[1],encoding='utf-8',errors='replace').read(); outd=sys.argv[2]
for m in re.finditer(r'@@F@@(.*?)@@C@@(.*?)@@E@@', blob, re.DOTALL):
    name=os.path.splitext(os.path.basename(m.group(1)))[0]
    open(os.path.join(outd,name+'.code'),'w',encoding='utf-8').write(m.group(2))
PY
echo "extracted: $(ls "$WORK/code"/*.code | wc -l | tr -d ' ') .code files"

# ── 2. spike batch (sbt) → .proj ──
echo "spike batch (sbt)..."
( cd "$ROOT/uniml" && NEWFRONT_CODE="$WORK/code" NEWFRONT_PROJ="$WORK/proj" \
    sbt -batch "uniml/testOnly scalascript.uniml.spike.ScalaSpikeSpec -- -z \"newfront corpus batch\"" \
    > "$WORK/sbt.log" 2>&1 )
grep -o 'newfront batch:.*' "$WORK/sbt.log" | tail -1

# ── 3. ref driver — per-file, FRESH JVM (NOT a batch: ssc1-front's parse populates
#      parser-owned accumulator cells — caseMethodsCell, classBodyFieldsCell, … — that
#      are NEVER reset between parses, so a one-JVM batch leaks earlier programs' case-
#      class methods into later programs. A fresh JVM per file gives each ref an empty
#      cell state, matching the fresh JVM the spike side already uses. Apples-to-apples.)
cat > "$V2/bin/_nf_refone.ssc0" <<'DRV'
import "../lib/ssc1-lower.ssc0"
def main = () => match #io.args() { case Cons(p, r) => #io.print(#coreir.encode(lowerProg(parse(#utf8->str(#io.readFile(p)))))) case Nil => #io.eprint("no-arg") }
DRV

# ── 4. compare — per-file (parallel): BOTH ref and spike lowered in fresh JVMs ──
cat > "$WORK/worker.sh" <<WORKER
#!/usr/bin/env bash
pf="\$1"; n=\$(basename "\$pf" .proj); code="$WORK/code/\$n.code"; ref="$WORK/ref/\$n.ir"
[ -f "\$code" ] || { echo "SKIP \$n"; exit 0; }
# ref: fresh JVM per file (no cross-program cell leak)
( cd "$V2" && java -Xss512m -jar "$JAR" run bin/_nf_refone.ssc0 "\$code" 2>/dev/null ) > "\$ref"
[ -s "\$ref" ] || { echo "SKIP \$n"; exit 0; }
grep -q 'SPIKE_CRASH' "\$pf" && { echo "DROP \$n |spike parse crash"; exit 0; }
grep -q '^EMPTY\$' "\$pf" && { echo "DROP \$n |spike projected no roots"; exit 0; }
# NOTE: a `Nil` projection is NOT pre-judged a DROP. For a doc-only .ssc (fences are optional) the extracted
# program is legitimately EMPTY, and `Nil` lowers to the bare prelude — byte-identical to ssc1-front's
# parse(""). Lower it and let the cmp decide; a NON-empty program that collapses to Nil still lands in the
# `< 7400` prelude-only DROP bucket below. Same rule as the hole check: compare first, classify after.
drv="$V2/bin/_nf_\${n//[^A-Za-z0-9_]/_}.ssc0"
printf 'import "../lib/ssc1-lower.ssc0"\ndef main = () => #io.print(#coreir.encode(lowerProg(%s)))\n' "\$(cat "\$pf")" > "\$drv"
sp="$WORK/spike/\$n.ir"; ( cd "$V2" && java -Xss512m -jar "$JAR" run "\$drv" 2>/dev/null ) > "\$sp"; rm -f "\$drv"
# BYTE-COMPARE FIRST, classify after. A \`__notImplemented__\` in the projection is NOT proof of a parse
# hole: \`???\` (Predef.???) is a legitimate expression that LOWERS to that prim, so a program using it can
# be byte-identical (predef-notimplemented was reported HOLE for exactly this reason while it actually
# MATCHES). A projection carrying the marker still lowers fine — the prim is valid — so only a hole that
# also DIVERGES is a real gap. Byte-equality is the ground truth; the marker is just a hint for triage.
if cmp -s "\$ref" "\$sp"; then echo "MATCH \$n"
elif grep -q '__notImplemented__' "\$pf"; then echo "HOLE \$n |__notImplemented__"
elif [ \$(wc -c < "\$sp") -lt 7400 ]; then echo "DROP \$n |prelude-only (program lost)"
else ctx=\$(python3 -c "r=open('\$ref').read();s=open('\$sp').read();b=next((i for i in range(min(len(r),len(s))) if r[i]!=s[i]),0);print(r[b:b+40].replace(chr(10),' '))" 2>/dev/null); echo "DIFF \$n |\$ctx"; fi
WORKER
ls "$WORK/proj"/*.proj | xargs -P 6 -I{} bash "$WORK/worker.sh" {} > "$WORK/results.txt"
rm -f "$V2"/bin/_nf_*.ssc0

# ── report ──
m=$(grep -c '^MATCH ' "$WORK/results.txt"); dr=$(grep -c '^DROP ' "$WORK/results.txt")
ho=$(grep -c '^HOLE ' "$WORK/results.txt"); di=$(grep -c '^DIFF ' "$WORK/results.txt")
t=$((m+dr+ho+di))
echo "═══════════ BASELINE: new front (spike) vs ssc1-front, $t programs ═══════════"
printf "  MATCH byte-identical : %d (%d%%)\n" "$m" "$((t>0?m*100/t:0))"
printf "  DROP  program lost   : %d   (top-level statements / empty projection — the #1 gap)\n" "$dr"
printf "  HOLE  parse hole     : %d   (spike can't parse a construct)\n" "$ho"
printf "  DIFF  content differs: %d\n" "$di"
echo "─── top DIFF causes (first divergence) ───"
grep '^DIFF ' "$WORK/results.txt" | sed 's/^DIFF [^ ]* |//' | sort | uniq -c | sort -rn | head -10
echo "(full results: $WORK/results.txt)"
