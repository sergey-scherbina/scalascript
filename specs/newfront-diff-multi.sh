#!/usr/bin/env bash
# new-self-hosting-front — Phase 2.0: MULTI-FILE corpus byte-identity harness.
#
# `specs/newfront-diff.sh` extracts and lowers ONE file per program, so a module-loading gap is INVISIBLE
# to it — its 485/499 says nothing about imports. This is the gate that makes Phase 2 measurable. It drives
# ssc1-run's REAL module loader on BOTH sides, so the only difference is the PARSER:
#
#   ref = lowerProg(sscLoadRoots([root]))                         -- loader + ssc1-front's parse
#   new = lowerProg(sscApp(sscDefsOnly(spike(m1)), … spike(root))) -- loader ORDER + the spike's projection
#
# The loader's contract, mirrored here exactly (read off v2/bin/ssc1-run.ssc0:443-479):
#   * sscLoadMod  = sscApp(impDefs, defs)  — a module's transitive imports' defs come BEFORE its own.
#   * sscLoadRoot = sscApp(impDefs, stmts) — the ROOT keeps its entry expressions; MODULES are defs-only
#     (sscDefsOnly: def/extension_start/extension_end/val/var/casecls/caseobj/enum/given/given_obj/object/
#     effect_decl — a module's top-level entry EXPRESSIONS are dropped).
#   * dedup is at first VISIT (`Cons(path, seen)` before descending), so a diamond loads ONCE, in its
#     FIRST slot. ORDER is observable in the Core IR (cf. the caseMethods reverse-accumulate bug).
#   * `[n](./dir)` (no `.ssc`) loads `dir/index.ssc`; `std/…` resolves via SSC_STD (default `v1/runtime/`);
#     a bare `a/b.ssc` tries `<dir>/a/b.ssc` then falls back to sscLibRoot()+rel (CWD-relative) — which is
#     why this script runs from the REPO ROOT.
#   * imports are scanned from the RAW file (sscImports), NOT from the extracted code: in a FENCED .ssc the
#     link line lives in the prose. That is why grepping `.code` for imports undercounts badly (34 vs 216).
#
# Classification follows the arc's rule — COMPARE FIRST, CLASSIFY AFTER (see AGENTS.md "measurement
# apparatus must COMPARE, never PRE-JUDGE"). Nothing is pre-judged from a marker; only cmp decides MATCH.
# SKIP is only for cases where the ORACLE ITSELF cannot load (5 corpus roots have unresolvable imports) —
# those are a property of the corpus, not of the new front.
#
# Prereqs: SSC_JAR = a run-ir-capable v2 kernel jar (`scala-cli --power package v2/src --assembly`).
# Run from the repo root. NEWFRONT_MWORK to keep the work dir.
set -u
JAR=${SSC_JAR:?set SSC_JAR to a run-ir-capable v2 kernel jar}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=${NEWFRONT_MWORK:-$(mktemp -d)}
mkdir -p "$WORK/code" "$WORK/proj" "$WORK/ref" "$WORK/new"
export SSC_STD="${SSC_STD:-$ROOT/v1/runtime/}"
cd "$ROOT"

# ── 1. module-order probe: ssc1-run's loader, collecting PATHS in the order their defs are spliced ──
awk '/^def main = \(\) =>/{exit} {print}' v2/bin/ssc1-run.ssc0 > v2/bin/_nfm_order.ssc0
cat >> v2/bin/_nfm_order.ssc0 <<'DRV'
def mlMod = (rawPath, seen) =>
  let path0 = sscNormalize(rawPath) in
  let path = if sscEndsSsc(path0) then path0 else #sconcat(path0, "/index.ssc") in
  if sscMem(seen, path) then Pair(Nil, seen)
  else
    let fileStr = #utf8->str(#io.readFile(path)) in
    match mlImps(sscImports(fileStr), sscDir(path), Cons(path, seen)) {
      case Pair(impPaths, seen2) => Pair(sscApp(impPaths, Cons(path, Nil)), seen2)
    }
def mlImps = (imps, dir, seen) =>
  match imps {
    case Nil => Pair(Nil, seen)
    case Cons(rel, rest) =>
      match mlMod(sscResolve(dir, rel), seen) {
        case Pair(p1, seen2) => match mlImps(rest, dir, seen2) { case Pair(p2, seen3) => Pair(sscApp(p1, p2), seen3) }
      }
  }
def mlJoin = (ps) => match ps { case Nil => "" case Cons(p, r) => #sconcat("@@M@@", #sconcat(p, mlJoin(r))) }
def main = () => match #io.args() { case Cons(p, r) =>
  let path = sscNormalize(p) in
  let fileStr = #utf8->str(#io.readFile(path)) in
  match mlImps(sscImports(fileStr), sscDir(path), Cons(path, Nil)) {
    case Pair(impPaths, s2) => #io.print(#sconcat(path, #sconcat(mlJoin(impPaths), "")))
  }
  case Nil => #io.eprint("no-arg") }
DRV

# ── 2. ref driver: the REAL loader + ssc1-front's parse, lowered whole (fresh JVM per root) ──
awk '/^def main = \(\) =>/{exit} {print}' v2/bin/ssc1-run.ssc0 > v2/bin/_nfm_ref.ssc0
cat >> v2/bin/_nfm_ref.ssc0 <<'DRV'
def main = () => match #io.args() { case Cons(p, r) =>
  match sscLoadRoots(Cons(p, Nil), Nil) { case Pair(stmts, seen) => #io.print(#coreir.encode(lowerProg(stmts))) }
  case Nil => #io.eprint("no-arg") }
DRV

# ── 3. extractor: sscProgramSource for any .ssc (root or module) ──
awk '/^def main = \(\) =>/{exit} {print}' v2/bin/ssc1-run.ssc0 > v2/bin/_nfm_extract.ssc0
cat >> v2/bin/_nfm_extract.ssc0 <<'DRV'
def main = () => match #io.args() { case Cons(p, r) =>
  #io.print(sscProgramSource(#utf8->str(#io.readFile(p))))
  case Nil => #io.eprint("no-arg") }
DRV

CANDS="$WORK/cands.txt"
grep -lanE '^\[[^]]+\]\([^)]+\)[[:space:]]*$' examples/*.ssc tests/conformance/*.ssc 2>/dev/null > "$CANDS"
echo "import candidates: $(wc -l < "$CANDS" | tr -d ' ')"

# ── 4. per-root: order → extract → (spike proj deferred) → ref ──
: > "$WORK/scope.txt"
while read -r f; do
  order=$(java -Xss512m -jar "$JAR" run v2/bin/_nfm_order.ssc0 "$f" 2>/dev/null)
  [ -z "$order" ] && { echo "SKIP $(basename "$f" .ssc) |oracle loader cannot resolve an import"; continue; } >> "$WORK/scope.txt"
  case "$order" in *"@@M@@"*) echo "$f|$order" >> "$WORK/scope.txt" ;; esac
done < "$CANDS"
echo "roots with >=1 module: $(grep -c '|' "$WORK/scope.txt" | tr -d ' ')"

# every distinct .ssc we must extract+project (roots + modules), keyed by a sanitized path
python3 - "$WORK/scope.txt" "$WORK/files.txt" <<'PY'
import sys
need=set()
for ln in open(sys.argv[1]):
    if '|' not in ln: continue
    root, order = ln.rstrip('\n').split('|', 1)
    parts = order.split('@@M@@')
    need.add(parts[0])
    for m in parts[1:]: need.add(m)
open(sys.argv[2], 'w').write('\n'.join(sorted(need)) + '\n')
PY
echo "distinct .ssc to project: $(wc -l < "$WORK/files.txt" | tr -d ' ')"

cat > "$WORK/exw.sh" <<EXW
#!/usr/bin/env bash
p="\$1"; [ -z "\$p" ] && exit 0
k=\$(echo "\$p" | sed 's#[^A-Za-z0-9]#_#g')
cd "$ROOT" && java -Xss512m -jar "$JAR" run v2/bin/_nfm_extract.ssc0 "\$p" 2>/dev/null > "$WORK/code/\$k.code"
EXW
xargs -P 6 -I{} bash "$WORK/exw.sh" {} < "$WORK/files.txt"
echo "extracted: $(ls "$WORK/code" | wc -l | tr -d ' ')"

# ── 5. spike batch over every extracted file ──
echo "spike batch (sbt)..."
( cd "$ROOT/uniml" && NEWFRONT_CODE="$WORK/code" NEWFRONT_PROJ="$WORK/proj" \
    sbt -batch "uniml/testOnly scalascript.uniml.spike.ScalaSpikeSpec -- -z \"newfront corpus batch\"" \
    > "$WORK/sbt.log" 2>&1 )
grep -o 'newfront batch:.*' "$WORK/sbt.log" | tail -1

# ── 6. compose + compare, per root (parallel) ──
cat > "$WORK/worker.sh" <<WORKER
#!/usr/bin/env bash
# arg = a LINE NUMBER into scope.txt, not the line itself: a root with 20 modules makes an argv entry
# too long for xargs ("command line cannot be assembled, too long"), which silently shrinks the run.
line=\$(sed -n "\$1p" "$WORK/scope-run.txt"); root="\${line%%|*}"; order="\${line#*|}"
n=\$(basename "\$root" .ssc)
key() { echo "\$1" | sed 's#[^A-Za-z0-9]#_#g'; }
cd "$ROOT"
ref="$WORK/ref/\$n.ir"
java -Xss512m -jar "$JAR" run v2/bin/_nfm_ref.ssc0 "\$root" 2>/dev/null > "\$ref"
[ -s "\$ref" ] || { echo "SKIP \$n |oracle loader/lower produced nothing"; exit 0; }
# compose: sscApp(sscDefsOnly(P_m1), sscApp(sscDefsOnly(P_m2), … P_root))  — the loader's exact order
expr_file="$WORK/new/\$n.expr"
python3 - "\$order" "$WORK/proj" "\$expr_file" <<'PY'
import sys, os, re
order, projdir, out = sys.argv[1], sys.argv[2], sys.argv[3]
parts = order.split('@@M@@')
rootp, mods = parts[0], parts[1:]
def key(p): return re.sub(r'[^A-Za-z0-9]', '_', p)
def proj(p):
    fn = os.path.join(projdir, key(p) + '.proj')
    if not os.path.exists(fn): return None
    return open(fn).read().strip()
rp = proj(rootp)
if rp is None: open(out, 'w').write(''); sys.exit(0)
e = rp
for m in reversed(mods):
    mp = proj(m)
    if mp is None: open(out, 'w').write(''); sys.exit(0)
    e = f'sscApp(sscDefsOnly({mp}), {e})'
open(out, 'w').write(e)
PY
[ -s "\$expr_file" ] || { echo "SKIP \$n |missing a module projection"; exit 0; }
grep -q 'SPIKE_CRASH' "\$expr_file" && { echo "DROP \$n |spike parse crash in a module"; exit 0; }
# an EMPTY sentinel is a real spike failure on some MODULE (it projected no roots); it cannot be lowered,
# so report it as a DROP rather than letting the driver die with an opaque "no arm for EMPTY/0".
# (NB: no backticks in this heredoc — it is unquoted, so they would command-substitute at write time.)
grep -q 'EMPTY' "\$expr_file" && { echo "DROP \$n |a module projected no roots (EMPTY)"; exit 0; }
drv="v2/bin/_nfm_\${n//[^A-Za-z0-9_]/_}.ssc0"
awk '/^def main = \(\) =>/{exit} {print}' v2/bin/ssc1-run.ssc0 > "\$drv"
printf 'def main = () => #io.print(#coreir.encode(lowerProg(%s)))\n' "\$(cat "\$expr_file")" >> "\$drv"
new="$WORK/new/\$n.ir"
java -Xss512m -jar "$JAR" run "\$drv" 2>/dev/null > "\$new"; rm -f "\$drv"
# COMPARE FIRST, classify after — a marker never short-circuits the cmp.
if cmp -s "\$ref" "\$new"; then echo "MATCH \$n"
elif [ ! -s "\$new" ]; then echo "DROP \$n |new front produced nothing"
elif grep -q '__notImplemented__' "\$expr_file"; then echo "HOLE \$n |__notImplemented__ (and diverges)"
else ctx=\$(python3 -c "r=open('\$ref').read();s=open('\$new').read();b=next((i for i in range(min(len(r),len(s))) if r[i]!=s[i]),min(len(r),len(s)));print(r[b:b+40].replace(chr(10),' '))" 2>/dev/null); echo "DIFF \$n |\$ctx"; fi
WORKER

grep '|' "$WORK/scope.txt" > "$WORK/scope-run.txt"
seq 1 "$(wc -l < "$WORK/scope-run.txt")" | xargs -P 6 -I{} bash "$WORK/worker.sh" {} >> "$WORK/results.txt"
rm -f v2/bin/_nfm_*.ssc0

# ── report ──
m=$(grep -c '^MATCH ' "$WORK/results.txt"); dr=$(grep -c '^DROP ' "$WORK/results.txt")
ho=$(grep -c '^HOLE ' "$WORK/results.txt"); di=$(grep -c '^DIFF ' "$WORK/results.txt")
sk=$(grep -c '^SKIP ' "$WORK/results.txt"); t=$((m+dr+ho+di))
echo "════ MULTI-FILE BASELINE: new front vs ssc1-front + the REAL loader, $t comparable roots ════"
printf "  MATCH byte-identical : %d (%d%%)\n" "$m" "$((t>0?m*100/t:0))"
printf "  DROP  program lost   : %d\n" "$dr"
printf "  HOLE  parse hole     : %d\n" "$ho"
printf "  DIFF  content differs: %d\n" "$di"
printf "  SKIP  oracle can't load: %d  (corpus property — the OLD front fails too)\n" "$sk"
echo "─── top DIFF causes (first divergence) ───"
grep '^DIFF ' "$WORK/results.txt" | sed 's/^DIFF [^ ]* |//' | sort | uniq -c | sort -rn | head -10
echo "(full results: $WORK/results.txt)"
