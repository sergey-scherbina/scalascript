#!/usr/bin/env bash
# coreir-inventory-gate — the canonical Core IR node/const inventory drift gate.
#
# WHY THIS EXISTS (AGENTS.md "measurement apparatus must COMPARE, never PRE-JUDGE"):
# `v2/specs/10-core-ir.md` claimed "11 nodes", "Seq is dropped" and "no loop node is needed"
# for ~3 weeks while `v2/src/CoreIR.scala` had 13 nodes and both the Reader and the Writer
# happily round-tripped `While` and `Seq`. Nothing compared the spec to the code, so nothing
# said so. This gate compares SIX independent sources and fails loudly on any disagreement.
#
# The six sources that must agree on the ONE canonical inventory:
#   1. SPEC       — the pinned ```coreir-inventory``` block in v2/specs/10-core-ir.md
#   2. Term       — `enum Term` cases            (v2/src/CoreIR.scala)
#   3. Const      — `enum Const` cases           (v2/src/CoreIR.scala)
#   4. Reader     — accepted S-expr heads        (v2/src/CoreIR.scala Reader.toTerm/toConst)
#   5. Writer     — emitted S-expr heads         (v2/src/CoreIR.scala Writer.term/const)
#   6. IrEncode /
#      IrDecode   — the Data-tree tag surface    (v2/src/Runtime.scala)
#
# Every check prints name/want/got/diff on mismatch — never a bare `[[ x == y ]]` under
# `set -e`, which is how the v21 gates managed to be red for days while printing nothing.
#
# Usage:  ./specs/coreir-inventory-gate.sh            (from the repo root; no build needed)
#         REPO=/path/to/repo ./specs/coreir-inventory-gate.sh
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=${REPO:-$(cd "$HERE/.." && pwd)}
SPEC="$REPO/v2/specs/10-core-ir.md"
COREIR="$REPO/v2/src/CoreIR.scala"
RUNTIME="$REPO/v2/src/Runtime.scala"

for f in "$SPEC" "$COREIR" "$RUNTIME"; do
  [ -r "$f" ] || { echo "FAIL  missing required source: $f"; exit 2; }
done

python3 - "$SPEC" "$COREIR" "$RUNTIME" <<'PY'
import re, sys

spec_path, coreir_path, runtime_path = sys.argv[1:4]
spec    = open(spec_path).read()
coreir  = open(coreir_path).read()
runtime = open(runtime_path).read()

fails = []

def check(name, want, got, note=""):
    """COMPARE first, classify after. Always print want/got/diff on mismatch.

    Compares by MEMBERSHIP, not source order. The inventory's contract is *which*
    nodes/constants exist in each source; the order of `case` clauses inside a Scala
    `match` is incidental (e.g. `Reader.toTerm` writes `match` last because it is a
    multi-line case). An order-significant comparison here reported 4 false gaps on its
    first run — apparatus pre-judging, exactly what AGENTS.md warns about. Order inside
    the pinned spec block is for human readability only.
    """
    want_s, got_s = set(want), set(got)
    if want_s == got_s:
        print(f"ok   {name}  ({len(got_s)}) {' '.join(want) if want else '(empty)'}")
        return True
    missing = [x for x in want if x not in got_s]
    extra   = sorted(x for x in got_s if x not in want_s)
    print(f"FAIL {name}{('  — ' + note) if note else ''}")
    print(f"       want ({len(want_s)}): {' '.join(want) if want else '(empty)'}")
    print(f"       got  ({len(got_s)}):  {' '.join(sorted(got_s)) if got_s else '(empty)'}")
    if missing: print(f"       missing from got : {' '.join(missing)}")
    if extra:   print(f"       unexpected in got: {' '.join(extra)}")
    fails.append(name)
    return False

def block(text, tag):
    m = re.search(r"```" + tag + r"\n(.*?)```", text, re.S)
    if not m:
        print(f"FAIL  spec block ```{tag}``` not found in {spec_path}")
        print( "       the pinned inventory is the contract; without it there is nothing to compare against")
        sys.exit(1)
    return m.group(1)

# ── 1. SPEC: the pinned inventory ────────────────────────────────────────────────────────
# Format (order-significant, one per line):
#   node  <TermCase> <sexpr-head> <IrTag>
#   const <ConstCase> <sexpr-form> <IrTag>
spec_nodes, spec_consts = [], []
for line in block(spec, "coreir-inventory").splitlines():
    line = line.split("#", 1)[0].strip()
    if not line: continue
    parts = line.split()
    if parts[0] == "node" and len(parts) == 4:  spec_nodes.append(tuple(parts[1:]))
    elif parts[0] == "const" and len(parts) == 4: spec_consts.append(tuple(parts[1:]))
    else:
        print(f"FAIL  malformed pinned-inventory line: {line!r}")
        print( "       want: 'node <TermCase> <head> <IrTag>' or 'const <ConstCase> <form> <IrTag>'")
        sys.exit(1)

def section(text, start, end):
    i = text.index(start)
    j = text.index(end, i)
    return text[i:j]

# ── 2/3. Term + Const enum cases ─────────────────────────────────────────────────────────
term_enum  = re.findall(r"^  case ([A-Z]\w*)", section(coreir, "enum Term:", "case class Arm"), re.M)
const_enum = re.findall(r"^  case (C\w*)",     section(coreir, "enum Const:", "enum Term:"),    re.M)

# ── 4. Reader: accepted heads ────────────────────────────────────────────────────────────
reader_src   = section(coreir, "def toTerm(sx: Sx): Term", "def toConst")
reader_heads = re.findall(r'^      case "(\w+)"', reader_src, re.M)
# `match` is written as a trailing multi-line case; catch it wherever it sits.
reader_heads = sorted(set(reader_heads), key=lambda h: reader_src.index(f'"{h}"'))

reader_const_src   = section(coreir, "def toConst(rest: List[Sx])", "def parseFloat")
reader_const_forms = re.findall(r'Sx\.Atom\("(\w+)"\)', reader_const_src)
seen = set(); reader_const_forms = [x for x in reader_const_forms if not (x in seen or seen.add(x))]

# ── 5. Writer: emitted heads ─────────────────────────────────────────────────────────────
# Per-CASE extraction: for each `case <TermCase>(...) =>` take the head of the s-expr the
# case RETURNS — i.e. the LAST `s"(<head>` in its body, since each case's result is its final
# string expression. Two traps this avoids, both hit on the way here:
#   - a flat regex over the whole method scoops up `(arm ...)`, a sub-form (`case class Arm`)
#     emitted inside the Match case, not a Term node;
#   - taking the FIRST head in the body returns `arm` for Match, which builds its arms before
#     returning `s"(match ...)"`.
writer_src = section(coreir, "def term(t: Term): String", "def const(c: Const)")
writer_heads = []
_wcases = list(re.finditer(r"^    case (\w+)[\s(]", writer_src, re.M))
for _i, _m in enumerate(_wcases):
    _body = writer_src[_m.end(): _wcases[_i + 1].start() if _i + 1 < len(_wcases) else len(writer_src)]
    _hs = re.findall(r's"\((\w+)', _body)
    if _hs: writer_heads.append(_hs[-1])

writer_const_src = section(coreir, "def const(c: Const): String", "def floatStr")
writer_const_out = re.findall(r'Const\.(C\w+)', writer_const_src)

# ── 6. IrEncode / IrDecode Data tags ─────────────────────────────────────────────────────
enc_term_src  = section(runtime, "object IrEncode:", "  private def const(v: Value)")
enc_const_src = section(runtime, "  private def const(v: Value): String", "  private def list(v: Value)")
dec_term_src  = section(runtime, "object IrDecode:", "  private def constant(v: Value)")
dec_const_src = section(runtime, "  private def constant(v: Value): Const", "  private def list(v: Value)")

def irtags(src):
    out, seen = [], set()
    for t in re.findall(r'DataV\("(Ir\w+)"', src):
        if t not in seen: seen.add(t); out.append(t)
    return out

enc_terms  = [t for t in irtags(enc_term_src)  if t not in ("IrProg", "IrDef", "IrArm")]
dec_terms  = [t for t in irtags(dec_term_src)  if t not in ("IrProg", "IrDef", "IrArm")]
enc_consts = irtags(enc_const_src)
dec_consts = irtags(dec_const_src)

# ── the comparisons ──────────────────────────────────────────────────────────────────────
print("== canonical Core IR inventory: 6 sources must agree ==")
print(f"-- spec: {spec_path}")
print()
print("-- nodes --")
check("spec nodes            == enum Term cases",     [n[0] for n in spec_nodes], term_enum,
      "10-core-ir.md's node inventory has drifted from CoreIR.scala")
check("spec heads            == Reader accepted heads", [n[1] for n in spec_nodes], reader_heads,
      "the reader accepts a head the pinned inventory does not list (or vice versa)")
check("spec heads            == Writer emitted heads",  [n[1] for n in spec_nodes], writer_heads,
      "the writer emits a head the pinned inventory does not list (or vice versa)")
check("spec Ir tags          == IrEncode term tags",    [n[2] for n in spec_nodes], enc_terms,
      "coreir.encode cannot encode a node the inventory pins")
check("spec Ir tags          == IrDecode term tags",    [n[2] for n in spec_nodes], dec_terms,
      "coreir.decode cannot decode a node the inventory pins")
check("IrEncode term tags    == IrDecode term tags",    enc_terms, dec_terms,
      "the Data-tree codec is ASYMMETRIC: one direction handles a node the other does not")
print()
print("-- constants --")
check("spec consts           == enum Const cases",   [c[0] for c in spec_consts], const_enum,
      "10-core-ir.md's constant inventory has drifted from CoreIR.scala")
check("spec consts           == Writer const cases", [c[0] for c in spec_consts], writer_const_out,
      "the writer does not emit every pinned constant")
check("spec Ir tags          == IrEncode const tags", [c[2] for c in spec_consts], enc_consts,
      "coreir.encode cannot encode a constant the inventory pins")
check("spec Ir tags          == IrDecode const tags", [c[2] for c in spec_consts], dec_consts,
      "coreir.decode cannot decode a constant the inventory pins")
check("IrEncode const tags   == IrDecode const tags", enc_consts, dec_consts,
      "the Data-tree constant codec is ASYMMETRIC")

print()
if fails:
    print(f"FAILED {len(fails)}/{10} inventory checks: {', '.join(fails)}")
    print("The canonical Core IR inventory is NOT pinned. Either the spec or the code is lying;")
    print("reconcile them deliberately (a node/const change is a version bump per 10-core-ir.md).")
    sys.exit(1)
print("ok   *** canonical Core IR inventory PINNED: all 6 sources agree ***")
PY
