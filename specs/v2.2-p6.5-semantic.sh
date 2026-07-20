#!/usr/bin/env bash
# P6.5 F — SEMANTIC-EQUIVALENCE gate (F5b leg (a): the golden-OUTPUT reference; design
# specs/v2-f5b-typed-ir-design.md §3a). This is the IMMOVABLE truth for the whole typed-IR arc.
#
# specs/v2.2-p6.5-corpus.sh compares F's IR to the oracle's IR BYTE for byte. That check dies the
# moment F's IR diverges from the untyped oracle by design (Stage 1+). THIS gate instead compares the
# OBSERVABLE OUTPUT of running the IR — stdout bytes + exit status — which does NOT move when the IR
# changes. So it is frozen NOW, while F still emits untyped IR (= the current-correct behaviour), and
# every later typed stage must keep it green.
#
#   golden(P) = run-ir( oracle(P) )   -- oracle = ssc1-front + ssc1-lower; captured from the ORACLE, so
#                                        the reference is independent of any F refactor bug. FROZEN into
#                                        specs/v2.2-p6.5-golden/ and committed.
#   check(P)  = run-ir( F(P) )         -- F = driver(ssc1-front(F)); its OUTPUT must equal golden(P).
#
# ── AGENTS.md "measurement apparatus must COMPARE, never PRE-JUDGE" — the guards, spelled out ──
#   * COMPARE FIRST, CLASSIFY AFTER. At freeze we RUN both the oracle AND F and compare their outputs;
#     a program enters the golden set only when they AGREE today (so the gate is honestly GREEN now and
#     the frozen value is the oracle's). Membership is decided by a real comparison, never a heuristic.
#   * NO "equal empties" pass. The observable is the TUPLE (exit-status, stdout-bytes). A no-`main`
#     program legitimately yields rc=0 + empty stdout; a program that ERRORED also yields empty stdout
#     but rc!=0. Comparing the tuple (never stdout alone) keeps those distinct. Freeze REQUIRES rc==0,
#     so an "empty" golden always means "ran cleanly, produced nothing" — never "failed".
#   * NORMALIZE NOTHING. Outputs are compared as raw bytes via cmp(1); no trailing-newline / whitespace
#     / line-ending massaging that could make a real difference vanish.
#   * DETERMINISM FILTER. The oracle output is captured TWICE; a program whose two runs differ (clocks,
#     RNG, hash-order, addresses) is EXCLUDED from the golden set — a non-reproducible observable is not
#     a golden. Same for programs that error (rc!=0) or exceed the run budget.
#   * EVERY MISMATCH PRINTS ITS DIFF (expected=… got=…). A check that can fail silently will.
#   * FAIL LOUD (exit 2) if the apparatus itself is broken: nothing extracted / F0 not bootstrapped /
#     (check mode) no goldens present / 0 programs compared. A green that never ran the comparison is a lie.
#
# Modes:
#   freeze   — regenerate specs/v2.2-p6.5-golden/ from the oracle (run once now; re-run only to
#              intentionally extend/re-baseline the golden set, e.g. after a stage adds coverage).
#   check    — (default) assert run-ir(F(P)) == frozen golden for every golden P. Exit 1 on any mismatch.
#   classify — F4 CUTOVER RATCHET (self-maintaining). Bucket EVERY corpus+tower program: MATCH /
#              oracle-excluded (auto) / GAP·OUT·DEFERRED (committed manifest specs/v2.2-p6.5-classify.expected)
#              / genuine-FAIL. Exit 1 iff an F disagreement is UNEXPECTED (not in the manifest). Green means
#              "no unexpected diff"; GAP>0 is reported as the pre-flip precondition. Committed goldens untouched.
#
# Corpus = examples/*.ssc + tests/conformance/*.ssc (extracted exactly as specs/v2.2-p6.5-corpus.sh)
#          PLUS the "tower examples" — the curated differential cases inlined in specs/v2.2-p6.5-fsub.sh
#          (parsed out at runtime, prefixed `tower__`, so there is a single source of truth, no drift).
#
# Prereqs: SSC_JAR = a run-ir-capable v2 kernel jar
#          (build: `scala-cli --power package v2/src --assembly -o /tmp/ssc.jar`)
#          V2_DIR = <repo>/v2
set -u
JAR=${SSC_JAR:?set SSC_JAR to a run-ir-capable v2 kernel jar}; V2=${V2_DIR:?set V2_DIR to <repo>/v2}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
FSUB="$ROOT/specs/v2.2-p6.5-fsub.ssc"
FSUB_SH="$ROOT/specs/v2.2-p6.5-fsub.sh"
GOLD="$ROOT/specs/v2.2-p6.5-golden"
WORK=${NEWFRONT_WORK:-$(mktemp -d)}
mkdir -p "$WORK/code" "$WORK/ref" "$WORK/sem" "$WORK/sandbox"
# K62.3: size the onSizedStack worker (Main.scala:26); -Xss would only size the main thread. See
# specs/v2.2-p6.5-fsub.sh for the full note.
SSC_STACK=${SSC_STACK:-1073741824}
JVM="-Dssc.stackSize=$SSC_STACK"
MODE=${1:-check}
TIMEOUT_BIN=$(command -v timeout || command -v gtimeout || true)
SEM_COMPILE_TIMEOUT=${SEM_COMPILE_TIMEOUT:-25}   # F can loop on out-of-subset input → bound the compile
SEM_RUN_TIMEOUT=${SEM_RUN_TIMEOUT:-8}            # a well-behaved program finishes in <1s; cut servers/actors
# A golden far larger than any real program output is a degenerate artifact (e.g. an unhandled effect
# printing a 100k-element list as an Op envelope). It adds no semantic protection a small golden lacks
# and would bloat the committed set — excluded with a visible count, not silently.
SEM_MAX_GOLDEN=${SEM_MAX_GOLDEN:-65536}
NPROC=${SEM_PAR:-6}
run()  { java $JVM -jar "$JAR" "$@" 2>/dev/null; }
die()  { echo "FATAL(p65-semantic): $1" >&2; shift; for l in "$@"; do echo "       $l" >&2; done; exit 2; }
[ -f "$FSUB" ] || die "F source not found: $FSUB"
[ -n "$TIMEOUT_BIN" ] || echo "WARN: no timeout(1)/gtimeout(1) — a pathological program can hang this gate. brew install coreutils." >&2
cd "$V2" || die "cannot cd to V2_DIR=$V2"

# ── 1. extract corpus (reuse $WORK/code if already populated, e.g. by specs/newfront-diff.sh) ──
ncode=$(ls "$WORK/code"/*.code 2>/dev/null | wc -l | tr -d ' ')
if [ "$ncode" -eq 0 ]; then
  echo "extracting corpus (ssc1-run's sscProgramSource)..."
  awk '/^def main = \(\) =>/{exit} {print}' "$V2/bin/ssc1-run.ssc0" > "$V2/bin/_sem_extract.ssc0"
  cat >> "$V2/bin/_sem_extract.ssc0" <<'DRV'
def exOne = (p) => #sconcat("@@F@@", #sconcat(p, #sconcat("@@C@@", #sconcat(sscProgramSource(#utf8->str(#io.readFile(p))), "@@E@@"))))
def exAll = (ps) => match ps { case Nil => "" case Cons(p, r) => #sconcat(exOne(p), exAll(r)) }
def main = () => #io.print(exAll(#io.args()))
DRV
  FILES=$(ls "$ROOT"/examples/*.ssc "$ROOT"/tests/conformance/*.ssc 2>/dev/null)
  run run bin/_sem_extract.ssc0 $FILES > "$WORK/all.extracted"
  rm -f "$V2/bin/_sem_extract.ssc0"
  python3 - "$WORK/all.extracted" "$WORK/code" <<'PY'
import sys, os, re
blob=open(sys.argv[1],encoding='utf-8',errors='replace').read(); outd=sys.argv[2]
for m in re.finditer(r'@@F@@(.*?)@@C@@(.*?)@@E@@', blob, re.DOTALL):
    name=os.path.splitext(os.path.basename(m.group(1)))[0]
    open(os.path.join(outd,name+'.code'),'w',encoding='utf-8').write(m.group(2))
PY
  ncode=$(ls "$WORK/code"/*.code 2>/dev/null | wc -l | tr -d ' ')
fi

# ── 1b. extract the "tower examples" — the `d NAME 'SRC'` differential cases from fsub.sh ──
# Parsed at runtime so there is ONE source of truth (no committed snapshot to drift). The sources are
# single-quoted and contain no embedded single-quote, so first-quote-closes parsing is exact.
if ! ls "$WORK/code"/tower__*.code >/dev/null 2>&1; then
  python3 - "$FSUB_SH" "$WORK/code" <<'PY'
import sys, os, re
txt=open(sys.argv[1],encoding='utf-8').read(); outd=sys.argv[2]
# only the differential-corpus region (between the header echo and the --self block)
lo=txt.find('--- differential corpus'); hi=txt.find('if [ "${1:-}" = "--self" ]')
region=txt[lo:hi] if lo>=0 and hi>lo else txt
n=0
for m in re.finditer(r"^d +(\S+) +'((?:[^']|\n)*?)'\s*$", region, re.MULTILINE):
    name, src = m.group(1), m.group(2)
    open(os.path.join(outd,'tower__'+name+'.code'),'w',encoding='utf-8').write(src+'\n')
    n+=1
print("tower cases extracted:", n)
PY
fi
ncode=$(ls "$WORK/code"/*.code 2>/dev/null | wc -l | tr -d ' ')
echo "corpus: $ncode .code files ($(ls "$WORK/code"/tower__*.code 2>/dev/null | wc -l | tr -d ' ') tower)"
[ "$ncode" -gt 0 ] || die "extracted 0 .code files — the corpus/tower extractor produced nothing." \
  "checked: $ROOT/examples/*.ssc, $ROOT/tests/conformance/*.ssc, and $FSUB_SH"

# ── 2. bootstrap F0 = driver(ssc1-front(F)) : F made runnable on the VM (same driver as corpus.sh) ──
cat > "$V2/bin/_sem_drv.ssc0" <<'DRV'
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
FSUB_SRC="$FSUB" run run bin/_sem_drv.ssc0 > "$WORK/F0.ir"
rm -f "$V2/bin/_sem_drv.ssc0"
[ -s "$WORK/F0.ir" ] || die "could not bootstrap F0 from $FSUB" \
  "(re-run specs/v2.2-p6.5-fsub.sh to see the bootstrap error)"
echo "F0 bootstrapped ($(wc -c < "$WORK/F0.ir") bytes)"

# ── 3. oracle IR driver (fresh JVM per file, no parser-owned-cell leak) ──
cat > "$V2/bin/_sem_refone.ssc0" <<'DRV'
import "../lib/ssc1-lower.ssc0"
def main = () => match #io.args() { case Cons(p, r) => #io.print(#coreir.encode(lowerProg(parse(#utf8->str(#io.readFile(p)))))) case Nil => #io.eprint("no-arg") }
DRV

# ── shared worker helpers passed to xargs via a written script (quoted heredoc; env exported) ──
export WORK V2 JAR JVM GOLD TIMEOUT_BIN SEM_COMPILE_TIMEOUT SEM_RUN_TIMEOUT SEM_MAX_GOLDEN

# run-ir <irfile> with the run budget, from an isolated sandbox CWD (so a program's file writes never
# pollute $V2 and never leak between programs). Prints stdout; returns the program's exit code (124=timeout).
cat > "$WORK/runir.sh" <<'RUNIR'
#!/usr/bin/env bash
ir="$1"; sb="$2"; rm -rf "$sb"; mkdir -p "$sb"
if [ -n "$TIMEOUT_BIN" ]; then ( cd "$sb" && "$TIMEOUT_BIN" "$SEM_RUN_TIMEOUT" java $JVM -jar "$JAR" run-ir "$ir" 2>/dev/null ); else ( cd "$sb" && java $JVM -jar "$JAR" run-ir "$ir" 2>/dev/null ); fi
RUNIR

# ── FREEZE worker: capture the oracle golden where it is deterministic+rc0 AND F agrees today ──
cat > "$WORK/freeze_worker.sh" <<'WORKER'
#!/usr/bin/env bash
code="$1"; n=$(basename "$code" .code)
ref="$WORK/ref/$n.ir"
# oracle IR (fresh JVM). Empty ⇒ oracle itself produced nothing to run.
( cd "$V2" && java $JVM -jar "$JAR" run bin/_sem_refone.ssc0 "$code" 2>/dev/null ) > "$ref"
[ -s "$ref" ] || { echo "EXCL_ORACLE_NOIR $n"; exit 0; }
# run the oracle IR twice → (rc, out) determinism check
o1="$WORK/sem/$n.o1"; o2="$WORK/sem/$n.o2"
bash "$WORK/runir.sh" "$ref" "$WORK/sandbox/$n.o1" > "$o1"; rc1=$?
bash "$WORK/runir.sh" "$ref" "$WORK/sandbox/$n.o2" > "$o2"; rc2=$?
[ "$rc1" = 124 ] && { echo "EXCL_ORACLE_TIMEOUT $n"; exit 0; }
[ "$rc1" = 0 ] || { echo "EXCL_ORACLE_ERR $n |rc=$rc1"; exit 0; }
if [ "$rc1" != "$rc2" ] || ! cmp -s "$o1" "$o2"; then echo "EXCL_NONDET $n"; exit 0; fi
# oracle is a clean, reproducible golden candidate. Now: does F agree TODAY?
fir="$WORK/sem/$n.fir"
if [ -n "$TIMEOUT_BIN" ]; then ( cd "$V2" && "$TIMEOUT_BIN" "$SEM_COMPILE_TIMEOUT" java $JVM -jar "$JAR" run-ir "$WORK/F0.ir" "$code" 2>/dev/null ) > "$fir"; else ( cd "$V2" && java $JVM -jar "$JAR" run-ir "$WORK/F0.ir" "$code" 2>/dev/null ) > "$fir"; fi
[ -s "$fir" ] || { echo "EXCL_F_NOCOMPILE $n"; exit 0; }
fo="$WORK/sem/$n.fo"
bash "$WORK/runir.sh" "$fir" "$WORK/sandbox/$n.fo" > "$fo"; rcf=$?
if [ "$rcf" != "$rc1" ] || ! cmp -s "$fo" "$o1"; then echo "EXCL_F_DISAGREE $n |orc=$rc1 frc=$rcf"; exit 0; fi
gsz=$(wc -c < "$o1" | tr -d ' ')
if [ "$gsz" -gt "$SEM_MAX_GOLDEN" ]; then echo "EXCL_TOO_LARGE $n |${gsz}B > ${SEM_MAX_GOLDEN}B"; exit 0; fi
# FREEZE: store the ORACLE's output (canonical) as the golden.
cp "$o1" "$GOLD/$n.out"
sha=$( ( shasum -a 256 "$GOLD/$n.out" 2>/dev/null || sha256sum "$GOLD/$n.out" ) | awk '{print $1}')
printf 'FROZEN %s rc=%s bytes=%s sha=%s\n' "$n" "$rc1" "$(wc -c < "$GOLD/$n.out" | tr -d ' ')" "$sha"
WORKER

# ── CHECK worker: assert run-ir(F(P)) output == frozen golden ──
# Emits EXACTLY ONE line to stdout (interleave-safe under xargs -P, same discipline as corpus.sh);
# the expected/got detail for a mismatch is written to a per-program side file $WORK/diffs/$n.txt so
# it never interleaves with another worker's output and is never dropped by a `^MISMATCH` grep.
cat > "$WORK/check_worker.sh" <<'WORKER'
#!/usr/bin/env bash
n="$1"; code="$WORK/code/$n.code"; golden="$GOLD/$n.out"; diff="$WORK/diffs/$n.txt"
[ -f "$golden" ] || { echo "MISSING_GOLDEN $n"; exit 0; }
[ -f "$code" ]   || { echo "MISSING_CODE $n"; exit 0; }
grc=$(awk -v k="$n" '$1=="golden" && $2==k {print $3}' "$WORK/index.rc"); grc=${grc:-0}
fir="$WORK/sem/$n.fir"
if [ -n "$TIMEOUT_BIN" ]; then ( cd "$V2" && "$TIMEOUT_BIN" "$SEM_COMPILE_TIMEOUT" java $JVM -jar "$JAR" run-ir "$WORK/F0.ir" "$code" 2>/dev/null ) > "$fir"; else ( cd "$V2" && java $JVM -jar "$JAR" run-ir "$WORK/F0.ir" "$code" 2>/dev/null ) > "$fir"; fi
if [ ! -s "$fir" ]; then
  { echo "MISMATCH $n: F emitted NO IR (regression) — golden expects rc=$grc, $(wc -c < "$golden" | tr -d ' ')B"; } > "$diff"
  echo "MISMATCH $n |F emitted no IR (regression)"; exit 0
fi
fo="$WORK/sem/$n.co"
bash "$WORK/runir.sh" "$fir" "$WORK/sandbox/$n.co" > "$fo"; rcf=$?
if [ "$rcf" != "$grc" ]; then
  { echo "MISMATCH $n: exit expected=$grc got=$rcf"; } > "$diff"
  echo "MISMATCH $n |exit expected=$grc got=$rcf"; exit 0
fi
if cmp -s "$fo" "$golden"; then echo "MATCH $n"; else
  { echo "MISMATCH $n (output differs, exit=$rcf):"
    echo "  expected($(wc -c < "$golden" | tr -d ' ')B): $(head -c 300 "$golden" | tr '\n' '~')"
    echo "  got     ($(wc -c < "$fo"     | tr -d ' ')B): $(head -c 300 "$fo"     | tr '\n' '~')"
  } > "$diff"
  echo "MISMATCH $n |output differs (exit=$rcf)"
fi
WORKER

case "$MODE" in
  freeze)
    echo "═══════ FREEZE — capturing golden OUTPUT from the oracle ═══════"
    rm -rf "$GOLD"; mkdir -p "$GOLD"
    ls "$WORK/code"/*.code | xargs -P "$NPROC" -I{} bash "$WORK/freeze_worker.sh" {} > "$WORK/freeze.txt"
    rm -f "$V2/bin/_sem_refone.ssc0"
    # build the committed index (rc per golden), sorted for a stable diff
    : > "$WORK/index.rc"
    grep '^FROZEN ' "$WORK/freeze.txt" | while read -r _ nm rc by sh; do
      printf 'golden %s %s %s %s\n' "$nm" "${rc#rc=}" "${by#bytes=}" "${sh#sha=}"
    done | sort > "$GOLD/index.tsv"
    fz=$(grep -c '^FROZEN '            "$WORK/freeze.txt")
    oerr=$(grep -c '^EXCL_ORACLE_ERR '     "$WORK/freeze.txt")
    oto=$(grep -c '^EXCL_ORACLE_TIMEOUT '  "$WORK/freeze.txt")
    onoir=$(grep -c '^EXCL_ORACLE_NOIR '   "$WORK/freeze.txt")
    ond=$(grep -c '^EXCL_NONDET '          "$WORK/freeze.txt")
    fnc=$(grep -c '^EXCL_F_NOCOMPILE '     "$WORK/freeze.txt")
    fdis=$(grep -c '^EXCL_F_DISAGREE '     "$WORK/freeze.txt")
    olrg=$(grep -c '^EXCL_TOO_LARGE '      "$WORK/freeze.txt")
    tot=$(ls "$WORK/code"/*.code | wc -l | tr -d ' ')
    nonempty=$(for f in "$GOLD"/*.out; do [ -s "$f" ] && echo x; done | wc -l | tr -d ' ')
    echo "─────────────────────────────────────────────────────────────────"
    printf "  FROZEN goldens              : %d  (%d with non-empty stdout, %d ran-clean-but-empty)\n" "$fz" "$nonempty" "$((fz-nonempty))"
    echo   "  ── excluded (NOT wrong — just not eligible as a stable golden) ──"
    printf "  oracle errored (rc!=0)      : %d\n" "$oerr"
    printf "  oracle timed out (>%ss)      : %d\n" "$SEM_RUN_TIMEOUT" "$oto"
    printf "  oracle emitted no IR        : %d\n" "$onoir"
    printf "  non-deterministic output    : %d\n" "$ond"
    printf "  output too large (>%sB)   : %d\n" "$SEM_MAX_GOLDEN" "$olrg"
    printf "  F emits no IR (coverage gap): %d\n" "$fnc"
    printf "  F DISAGREES today           : %d\n" "$fdis"
    printf "  total corpus+tower          : %d\n" "$tot"
    # F-DISAGREE is surfaced in full (not hidden): it is exactly F's current semantic gaps.
    if [ "$fdis" -gt 0 ]; then
      echo "  ── F-DISAGREE list (F runs to a DIFFERENT output than the oracle today; tracked by corpus.sh) ──"
      grep '^EXCL_F_DISAGREE ' "$WORK/freeze.txt" | awk '{print $2}' | sort | tr '\n' ' '; echo
    fi
    echo "  goldens written to: $GOLD  (commit this directory)"
    ;;
  check)
    rm -f "$V2/bin/_sem_refone.ssc0"
    [ -d "$GOLD" ] && ls "$GOLD"/*.out >/dev/null 2>&1 || die "no frozen goldens in $GOLD — run 'freeze' first." \
      "(the golden set is committed; a clean checkout must contain it.)"
    # rc index for the check workers
    if [ -f "$GOLD/index.tsv" ]; then cp "$GOLD/index.tsv" "$WORK/index.rc"; else : > "$WORK/index.rc"; fi
    rm -rf "$WORK/diffs"; mkdir -p "$WORK/diffs"
    NAMES=$(ls "$GOLD"/*.out | sed 's:.*/::;s:\.out$::' | sort)
    ng=$(echo "$NAMES" | grep -c .)
    echo "═══════ CHECK — run-ir(F(P)) output vs $ng frozen goldens ═══════"
    echo "$NAMES" | xargs -P "$NPROC" -I{} bash "$WORK/check_worker.sh" {} > "$WORK/check.txt"
    m=$(grep -c '^MATCH '           "$WORK/check.txt")
    mm=$(grep -c '^MISMATCH '       "$WORK/check.txt")
    mg=$(grep -c '^MISSING_GOLDEN ' "$WORK/check.txt")
    mc=$(grep -c '^MISSING_CODE '   "$WORK/check.txt")
    t=$((m+mm+mg+mc))
    [ "$t" -gt 0 ] || die "0 programs compared — apparatus broken (no goldens/code reached the comparator)." \
      "check file: $WORK/check.txt"
    echo "─────────────────────────────────────────────────────────────────"
    printf "  MATCH (F output == golden)  : %d / %d\n" "$m" "$ng"
    printf "  MISMATCH (F output != golden): %d\n" "$mm"
    [ "$mc" -gt 0 ] && printf "  MISSING_CODE (corpus drift)  : %d\n" "$mc"
    if [ "$mm" -gt 0 ] || [ "$mc" -gt 0 ]; then
      echo "─── failures (expected=… got=… per program) ───"
      grep '^MISSING_CODE ' "$WORK/check.txt"
      for f in "$WORK"/diffs/*.txt; do [ -f "$f" ] && cat "$f"; done
      exit 1
    fi
    echo "  *** SEMANTIC GATE GREEN: F is output-equivalent to the untyped oracle on all $ng goldens ***"
    ;;
  classify)
    # F4 CUTOVER CLASSIFICATION GATE (self-maintaining, output-equivalence lens). Buckets EVERY corpus+
    # tower program, then requires every non-MATCH to be EXPECTED (listed in the committed manifest).
    # An UNEXPECTED disagreement (F runs to a different output than the oracle, or emits no IR, and is NOT
    # in the manifest) is a genuine-FAIL → exit 1. Mirrors the negtc release gate: the HARD invariant
    # (no unexpected disagree) is exact and never softened; the manifest buckets are DERIVED documentation.
    #
    # Reuses the FREEZE compute (oracle×2 + F, per program) but writes throwaway goldens to $WORK so the
    # committed specs/v2.2-p6.5-golden/ is untouched — this mode is a read-only classifier, never a
    # re-baseline. Runs at freeze cost (~90s parallel); it is a pre-flip ratchet, not an every-commit gate.
    MANIFEST="$ROOT/specs/v2.2-p6.5-classify.expected"
    [ -f "$MANIFEST" ] || die "classification manifest not found: $MANIFEST" \
      "(commit specs/v2.2-p6.5-classify.expected — the F4 cutover expected-non-match list.)"
    GOLD="$WORK/classify-gold"; export GOLD; rm -rf "$GOLD"; mkdir -p "$GOLD"
    echo "═══════ CLASSIFY — bucket every program (output-equivalence); require no UNEXPECTED disagree ═══════"
    ls "$WORK/code"/*.code | xargs -P "$NPROC" -I{} bash "$WORK/freeze_worker.sh" {} > "$WORK/classify.txt"
    rm -f "$V2/bin/_sem_refone.ssc0"
    total=$(ls "$WORK/code"/*.code | wc -l | tr -d ' ')
    matchN=$(grep -c '^FROZEN ' "$WORK/classify.txt")
    # oracle-side exclusions (NOT F's concern — auto-classified at runtime, zero manifest churn)
    oerr=$(grep -c '^EXCL_ORACLE_ERR '     "$WORK/classify.txt")
    oto=$(grep -c  '^EXCL_ORACLE_TIMEOUT ' "$WORK/classify.txt")
    onoir=$(grep -c '^EXCL_ORACLE_NOIR '   "$WORK/classify.txt")
    ond=$(grep -c   '^EXCL_NONDET '        "$WORK/classify.txt")
    olrg=$(grep -c  '^EXCL_TOO_LARGE '     "$WORK/classify.txt")
    oracleExcl=$((oerr+oto+onoir+ond+olrg))
    # the F-side non-matches that MUST be classified by the manifest
    disagree=$(grep '^EXCL_F_DISAGREE ' "$WORK/classify.txt" | awk '{print $2}' | sort)
    nocompile=$(grep '^EXCL_F_NOCOMPILE ' "$WORK/classify.txt" | awk '{print $2}' | sort)
    nonmatch=$(printf '%s\n%s\n' "$disagree" "$nocompile" | grep -c .)
    # manifest lookup: bucket for a name (GAP/OUT/DEFERRED), or empty if unlisted
    bucketOf() { awk -F'\t' -v k="$1" '!/^#/ && NF>=2 && $2==k {print $1; exit}' "$MANIFEST"; }
    gap=0; out=0; deferred=0; badbucket=0
    unexpected=""
    for n in $disagree $nocompile; do
      b=$(bucketOf "$n")
      case "$b" in
        GAP) gap=$((gap+1));;
        OUT) out=$((out+1));;
        DEFERRED) deferred=$((deferred+1));;
        "") unexpected="$unexpected $n";;
        *) badbucket=$((badbucket+1)); unexpected="$unexpected $n(bad-bucket:$b)";;
      esac
    done
    # self-maintaining reverse check: a manifest entry that now MATCHES (F improved) is STALE — warn, no fail
    stale=""
    while IFS=$'\t' read -r bkt nm _rest; do
      case "$bkt" in \#*|"") continue;; esac
      [ -n "$nm" ] || continue
      if grep -q "^FROZEN $nm " "$WORK/classify.txt"; then stale="$stale $nm"; fi
    done < "$MANIFEST"
    echo "─────────────────────────────────────────────────────────────────"
    printf "  total corpus+tower           : %d\n" "$total"
    printf "  MATCH (F output == oracle)   : %d\n" "$matchN"
    printf "  oracle-excluded (not F)      : %d  (err %d, timeout %d, no-IR %d, nondet %d, too-large %d)\n" \
      "$oracleExcl" "$oerr" "$oto" "$onoir" "$ond" "$olrg"
    printf "  F non-match (needs a bucket) : %d\n" "$nonmatch"
    printf "    ├─ GAP (F incomplete; handle before flip) : %d\n" "$gap"
    printf "    ├─ OUT (v2 correct / oracle bug; forever) : %d\n" "$out"
    printf "    └─ DEFERRED (needs kernel δ)              : %d\n" "$deferred"
    [ "$gap" -gt 0 ] && { echo "  ── GAP programs (must be delegated/documented before the flip) ──";
      for n in $disagree $nocompile; do [ "$(bucketOf "$n")" = GAP ] && printf '     %s\n' "$n"; done; }
    if [ -n "$stale" ]; then
      echo "  ── ⓘ RECLASSIFY (F now MATCHes these — remove from the manifest) ──"
      for n in $stale; do printf '     %s\n' "$n"; done
    fi
    if [ -n "$unexpected" ] || [ "$badbucket" -gt 0 ]; then
      echo "─────────────────────────────────────────────────────────────────"
      echo "  *** genuine-FAIL: UNEXPECTED output disagreement(s) not in $MANIFEST ***"
      for n in $unexpected; do
        echo "     $n"
        d=$(grep -E "^EXCL_F_(DISAGREE|NOCOMPILE) ${n%%(*} " "$WORK/classify.txt" | head -1)
        [ -n "$d" ] && echo "       $d"
      done
      echo "  → add each to the manifest with a bucket (GAP/OUT/DEFERRED) after confirming which it is,"
      echo "    or FIX F if it is a real regression. green = 0 genuine-FAIL."
      exit 1
    fi
    echo "─────────────────────────────────────────────────────────────────"
    echo "  *** CLASSIFY GREEN: 0 unexpected disagreements — every non-match is expected."
    echo "      Cutover note: $gap GAP program(s) still break under F and MUST be handled before step 4 (flip)."
    ;;
  *) die "unknown mode '$MODE' (use: freeze | check | classify)";;
esac
