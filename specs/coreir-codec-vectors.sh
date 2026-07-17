#!/usr/bin/env bash
# coreir-codec-vectors — encode / decode / canonicalization vectors for the canonical Core IR
# codec (specs 10-core-ir.md §3.2 + 12-ir-format.md).
#
# WHY: this codec is the reader for **untrusted persisted capsules**. A codec that fails OPEN
# there is a security property, not a nicety — so every vector below is written so that a
# WRONG ANSWER IS LOUD:
#   - every check prints name / want / got on mismatch (never a bare `[[ $(…) == "$want" ]]`
#     under `set -e`, which exits 1 printing NOTHING — that is how the v21 gates stayed red
#     for days);
#   - negative vectors assert the decoder REJECTS, and a silent accept is a FAIL. An
#     "it didn't crash" check would pass on a fail-open decoder, which is the bug.
#
# Coverage: all 13 nodes and all 7 constants (the pinned inventory in 10-core-ir.md §3.2),
# each through encode, decode, and canonicalization (`encode ∘ decode == canonicalize`).
#
# Usage:  SSC_JAR=/tmp/ssc.jar ./specs/coreir-codec-vectors.sh
#   build: scala-cli --power package v2/src --assembly -o /tmp/ssc.jar
set -u
JAR=${SSC_JAR:?set SSC_JAR to a run-ir-capable ssc kernel jar}
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/.." && pwd)
V2="$REPO/v2"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
pass=0; fail=0

# ── the loud comparator ───────────────────────────────────────────────────────────────────
# COMPARE first, classify after; print want/got on every mismatch.
expect_out() { # name want got
  local name="$1" want="$2" got="$3"
  if [ "$want" = "$got" ]; then
    pass=$((pass + 1)); printf 'ok   %s\n' "$name"
  else
    fail=$((fail + 1))
    printf 'FAIL %s\n' "$name"
    printf '       want: %s\n' "$want"
    printf '       got : %s\n' "$got"
    printf '       diff: '
    diff <(printf '%s\n' "$want") <(printf '%s\n' "$got") | sed -n '1,6p' | tr '\n' '|'
    printf '\n'
  fi
}

# encode <ir-data-expr> -> canonical text of `(program (defs) (entry <term>))`
encode() { # ssc0-expression building an Ir* Data tree for the ENTRY term
  cat > "$WORK/e.ssc0" <<EOF
-- \`str->big\` returns \`Data Option\`, so it must be unwrapped before it can be an IrBig
-- payload. (Passing the raw \`Some(...)\` made an early version of these vectors fail --
-- and the codec correctly rejected it with "bad const", i.e. it failed CLOSED. Good.)
def bigOf = (s) => match #str->big(s) { case Some(b) => b case None => #i->big(0) }
def main = () => #io.print(#coreir.encode(IrProg(Nil, $1)))
EOF
  java -Xss512m -jar "$JAR" run "$WORK/e.ssc0" 2>"$WORK/e.err" || {
    printf '<<encode-error: %s>>' "$(head -1 "$WORK/e.err")"; return 0; }
}

enc_entry() { # name want-entry-text ir-data-expr   (asserts only the (entry …) payload)
  local got; got=$(encode "$3")
  expect_out "$1" "(program (defs) (entry $2))" "$got"
}

# canon <text> -> run the text through the kernel loader and re-encode it.
# This is `encode ∘ decode == canonicalize` (12-ir-format.md): a lenient, pretty-printed,
# commented fixture must re-emit as the exact canonical single-line bytes.
canon() { printf '%s' "$1" > "$WORK/c.ir"; java -Xss512m -jar "$JAR" run-ir --canon "$WORK/c.ir" 2>&1; }

# runir <text> -> the program's observable result (proves the decoded term still EXECUTES)
runir() { printf '%s' "$1" > "$WORK/r.ir"; java -Xss512m -jar "$JAR" run-ir "$WORK/r.ir" 2>&1 | tail -1; }

# ── H4: the registered `coreir.decode` prim, exercised FROM .ssc ──────────────────────────
# decode_roundtrip <ir-data-expr> -> #coreir.encode(#coreir.decode(#coreir.encode(x))).
# This proves DECODE RECONSTRUCTS x: the canonical text of decode(encode(x)) must be byte-
# identical to encode(x). It is the strong `decode ∘ encode = id` (at the canonical-text level)
# expressed entirely in the language, closing coreir-canonical-codec-hardening H4.
decode_roundtrip() {
  cat > "$WORK/rt.ssc0" <<EOF
def bigOf = (s) => match #str->big(s) { case Some(b) => b case None => #i->big(0) }
def main = () => #io.print(#coreir.encode(#coreir.decode(#coreir.encode(IrProg(Nil, $1)))))
EOF
  java -Xss512m -jar "$JAR" run "$WORK/rt.ssc0" 2>"$WORK/rt.err" || {
    printf '<<decode-error: %s>>' "$(head -1 "$WORK/rt.err")"; return 0; }
}
rt_entry() { # name want-entry-text ir-data-expr   (round-trips the ENTRY payload through decode)
  local got; got=$(decode_roundtrip "$3")
  expect_out "$1" "(program (defs) (entry $2))" "$got"
}
# rt_prog — round-trip a FULL IrProg (defs + entry). Needed for closed programs: decode VALIDATES
# (H5), so a `(global f)` or a top-level `(local 0)` must actually be bound. Same property, whole
# program: decode(encode(x)) must re-encode to encode(x) byte-for-byte.
decode_roundtrip_prog() {
  cat > "$WORK/rt.ssc0" <<EOF
def main = () => #io.print(#coreir.encode(#coreir.decode(#coreir.encode($1))))
EOF
  java -Xss512m -jar "$JAR" run "$WORK/rt.ssc0" 2>"$WORK/rt.err" || {
    printf '<<decode-error: %s>>' "$(head -1 "$WORK/rt.err")"; return 0; }
}
rt_prog() { local got; got=$(decode_roundtrip_prog "$3"); expect_out "$1" "$2" "$got"; }

# canon_file <text> -> #coreir.encode(#coreir.decode(readFile(text))) : the real
# `encode ∘ decode = canonicalize` (12-ir-format.md), from .ssc, reading the (possibly lenient)
# text from a FILE so embedded `"`/newlines/comments need no shell escaping.
canon_file() {
  printf '%s' "$1" > "$WORK/cf.ir"
  cat > "$WORK/cf.ssc0" <<EOF
def main = () => #io.print(#coreir.encode(#coreir.decode(#utf8->str(#io.readFile("$WORK/cf.ir")))))
EOF
  java -Xss512m -jar "$JAR" run "$WORK/cf.ssc0" 2>"$WORK/cf.err" || {
    printf '<<canon-error: %s>>' "$(head -1 "$WORK/cf.err")"; return 0; }
}

# ── H5: the reader must FAIL CLOSED on malformed IR ───────────────────────────────────────
# reject <name> <expect-substring> <malformed-ir-text>: the untrusted-capsule reader (run-ir)
# MUST reject with a diagnostic CONTAINING <expect-substring>. A silent accept (the program
# runs, or any output that lacks the diagnostic) is a FAIL — fail-OPEN is exactly the bug.
reject() {
  printf '%s' "$3" > "$WORK/bad.ir"
  local got; got=$(java -Xss512m -jar "$JAR" run-ir "$WORK/bad.ir" 2>&1)
  case "$got" in
    *"$2"*) pass=$((pass + 1)); printf 'ok   reject %s\n' "$1" ;;
    *)      fail=$((fail + 1)); printf 'FAIL reject %s (fail-OPEN or wrong diagnostic)\n' "$1"
            printf '       want a diagnostic containing: %s\n' "$2"
            printf '       got : %s\n' "$(printf '%s' "$got" | head -2 | tr '\n' '|')" ;;
  esac
}

echo "== canonical Core IR codec vectors =="
echo "-- jar: $JAR"
echo

# ── 1. CONSTANT ENCODE VECTORS — all 7 pinned constants ──────────────────────────────────
echo "-- constants: encode (7/7 of the pinned inventory) --"
enc_entry "const CUnit  -> unit"          "(lit unit)"          'IrLit(IrUnit)'
enc_entry "const CBool  -> true"          "(lit true)"          'IrLit(IrBool(true))'
enc_entry "const CBool  -> false"         "(lit false)"         'IrLit(IrBool(false))'
enc_entry "const CInt   -> (int 42)"      "(lit (int 42))"      'IrLit(IrInt(42))'
enc_entry "const CInt   -> negative"      "(lit (int -7))"      'IrLit(IrInt(#i.neg(7)))'
enc_entry "const CInt   -> Long.MIN"      "(lit (int -9223372036854775808))" 'IrLit(IrInt(#i.add(9223372036854775807, 1)))'
# A value far beyond 64 bits: pins that CBig is arbitrary-precision end-to-end and is NOT
# narrowed on the way out. (`lib/irbin.ssc0`, the deferred v2-bin codec, encodes IrBig via
# `#big->i` and DOES silently narrow -- tracked separately in BUGS.md.)
enc_entry "const CBig   -> (big 123…) arbitrary precision" "(lit (big 123456789012345678901234567890))" \
  'IrLit(IrBig(bigOf("123456789012345678901234567890")))'
enc_entry "const CFloat -> 7.5"           "(lit (float 7.5))"   'IrLit(IrFloat(7.5))'
enc_entry "const CStr   -> \"hi\""        '(lit (str "hi"))'    'IrLit(IrStr("hi"))'
enc_entry "const CBytes -> (bytes 4869)"  "(lit (bytes 4869))"  'IrLit(IrBytes(#str->utf8("Hi")))'
enc_entry "const CBytes -> empty (bytes)" "(lit (bytes))"       'IrLit(IrBytes(#str->utf8("")))'
echo

# ── 2. FLOAT BIT IDENTITY — the -0.0 regression ──────────────────────────────────────────
# 12-ir-format.md §Tokens: FLOAT is the shortest round-tripping decimal, ALWAYS containing a
# `.` or an exponent; specials are exactly nan/inf/-inf; "Negative zero is `-0.0`".
# Before the floatLit split, encode went through the user-visible `floatStr`, which collapses
# whole doubles (2.0 -> "2") for ssc 1.0 output parity -- so -0.0 encoded as "0" and decoded
# back as +0.0. Bit identity silently lost. These vectors pin all of that.
echo "-- floats: canonical bit identity (12-ir-format.md §Tokens) --"
enc_entry "float -0.0 keeps its SIGN BIT"      "(lit (float -0.0))"  'IrLit(IrFloat(#f.neg(0.0)))'
enc_entry "float  0.0 stays positive zero"     "(lit (float 0.0))"   'IrLit(IrFloat(0.0))'
enc_entry "float  2.0 has a '.' (not '2')"     "(lit (float 2.0))"   'IrLit(IrFloat(2.0))'
enc_entry "float  nan  -> nan"                 "(lit (float nan))"   'IrLit(IrFloat(#f.div(0.0, 0.0)))'
enc_entry "float  inf  -> inf"                 "(lit (float inf))"   'IrLit(IrFloat(#f.div(1.0, 0.0)))'
enc_entry "float -inf  -> -inf"                "(lit (float -inf))"  'IrLit(IrFloat(#f.div(#f.neg(1.0), 0.0)))'
enc_entry "float 1e300 keeps its exponent"     "(lit (float 1.0E300))" 'IrLit(IrFloat(1.0E300))'
# -0.0 must stay DISTINGUISHABLE from 0.0 through the canonical form. If the two encode to
# the same bytes, the codec has silently merged two different IEEE-754 values.
z_neg=$(encode 'IrLit(IrFloat(#f.neg(0.0)))'); z_pos=$(encode 'IrLit(IrFloat(0.0))')
if [ "$z_neg" != "$z_pos" ]; then
  pass=$((pass + 1)); echo "ok   float -0.0 and 0.0 encode DIFFERENTLY (bit identity preserved)"
else
  fail=$((fail + 1))
  echo "FAIL float -0.0 and 0.0 encode IDENTICALLY -- IEEE-754 bit identity LOST"
  echo "       -0.0 -> $z_neg"
  echo "        0.0 -> $z_pos"
fi
echo

# ── 3. NODE ENCODE VECTORS — all 13 pinned nodes ─────────────────────────────────────────
echo "-- nodes: encode (13/13 of the pinned inventory) --"
enc_entry "node Lit"     "(lit (int 1))"                      'IrLit(IrInt(1))'
enc_entry "node Local"   "(lam 1 (local 0))"                  'IrLam(1, IrLocal(0))'
enc_entry "node Global"  "(global f)"                         'IrGlobal("f")'
enc_entry "node Lam"     "(lam 2 (lit unit))"                 'IrLam(2, IrLit(IrUnit))'
enc_entry "node Lam 0 (thunk)" "(lam 0 (lit unit))"           'IrLam(0, IrLit(IrUnit))'
enc_entry "node App"     "(app (global f) (lit (int 1)))"     'IrApp(IrGlobal("f"), Cons(IrLit(IrInt(1)), Nil))'
enc_entry "node App 0 (force)" "(app (global f))"             'IrApp(IrGlobal("f"), Nil)'
enc_entry "node Let"     "(let ((lit (int 1))) (local 0))"    'IrLet(Cons(IrLit(IrInt(1)), Nil), IrLocal(0))'
enc_entry "node LetRec"  "(letrec ((lam 1 (local 0))) (local 0))" 'IrLetRec(Cons(IrLam(1, IrLocal(0)), Nil), IrLocal(0))'
enc_entry "node If"      "(if (lit true) (lit (int 1)) (lit (int 2)))" 'IrIf(IrLit(IrBool(true)), IrLit(IrInt(1)), IrLit(IrInt(2)))'
enc_entry "node Ctor"    "(ctor Pair (lit (int 1)) (lit (int 2)))" 'IrCtor("Pair", Cons(IrLit(IrInt(1)), Cons(IrLit(IrInt(2)), Nil)))'
enc_entry "node Ctor 0 (nullary)" "(ctor Nil)"                'IrCtor("Nil", Nil)'
enc_entry "node Prim"    "(prim i.add (lit (int 1)) (lit (int 2)))" 'IrPrim("i.add", Cons(IrLit(IrInt(1)), Cons(IrLit(IrInt(2)), Nil)))'
enc_entry "node While  (optimization node, §3.1)" "(while (lit false) (lit unit))" 'IrWhile(IrLit(IrBool(false)), IrLit(IrUnit))'
enc_entry "node Seq    (optimization node, §3.1)" "(seq (lit (int 1)) (lit (int 2)))" 'IrSeq(Cons(IrLit(IrInt(1)), Cons(IrLit(IrInt(2)), Nil)))'
enc_entry "node Match  (no default)" "(match (local 0) ((arm Nil 0 (lit (int 0)))))" \
  'IrMatch(IrLocal(0), Cons(IrArm("Nil", 0, IrLit(IrInt(0))), Nil), None)'
enc_entry "node Match  (with default)" "(match (local 0) ((arm Nil 0 (lit (int 0)))) (default (lit (int 9))))" \
  'IrMatch(IrLocal(0), Cons(IrArm("Nil", 0, IrLit(IrInt(0))), Nil), Some(IrLit(IrInt(9))))'
echo

# ── 4. DECODE + CANONICALIZATION ROUND-TRIP ──────────────────────────────────────────────
# `12-ir-format.md`: the reader is deliberately lenient (arbitrary whitespace, `;` comments)
# and "reading then re-encoding any accepted input yields the canonical bytes". We cannot yet
# assert `encode ∘ decode == canonicalize` from .ssc because `coreir.decode` is not registered
# as a primitive (tracked: coreir-canonical-codec-hardening H4). What we CAN pin today is that
# the canonical bytes we emit actually DECODE AND EXECUTE — i.e. the encoder's output is real
# input to the kernel loader, not merely a string that looks right.
echo "-- decode: canonical bytes must load and run (Reader path) --"
expect_out "decode+run: fact 5 (canonical single line from 12-ir-format.md §Example)" "120" \
  "$(runir '(program (defs (def fact (lam 1 (if (prim i.le (local 0) (lit (int 1))) (lit (int 1)) (prim i.mul (local 0) (app (global fact) (prim i.sub (local 0) (lit (int 1))))))))) (entry (app (global fact) (lit (int 5)))))')"
# Reader leniency: the SAME term, pretty-printed across lines with a `;` comment, must decode
# to the same program and produce the same result.
expect_out "decode+run: lenient pretty/commented form == canonical form" "120" \
  "$(runir '(program
  ; a comment — the reader is lenient about layout, per 12-ir-format.md
  (defs (def fact (lam 1
    (if (prim i.le (local 0) (lit (int 1)))
        (lit (int 1))
        (prim i.mul (local 0) (app (global fact) (prim i.sub (local 0) (lit (int 1)))))))))
  (entry (app (global fact) (lit (int 5)))))')"
# Float bit identity has to survive the FULL round-trip, not just the encoder: -0.0 is the
# only double for which `f.eq` cannot witness the difference (-0.0 == 0.0 is true in IEEE-754),
# so divide by it — 1/-0.0 = -inf while 1/+0.0 = +inf. If the codec dropped the sign bit, this
# prints inf and the vector fails loudly.
expect_out "decode+run: -0.0 keeps its sign THROUGH the reader (1/-0.0 = -inf)" '"-inf"' \
  "$(runir '(program (defs) (entry (prim f->str (prim f.div (lit (float 1.0)) (lit (float -0.0))))))')"
expect_out "decode+run: +0.0 stays positive (1/0.0 = inf)" '"inf"' \
  "$(runir '(program (defs) (entry (prim f->str (prim f.div (lit (float 1.0)) (lit (float 0.0))))))')"
# Backward compatibility: the OLD non-canonical integral-float spelling `(float 2)` (what the
# encoder emitted before the floatLit split) must still READ. Reader leniency is what makes the
# encoder's canonicalization a safe change rather than a format break.
expect_out "decode: legacy '(float 2)' spelling still reads (reader leniency)" '"2"' \
  "$(runir '(program (defs) (entry (prim f->str (lit (float 2)))))')"
echo

# ── 5. BOUNDED DECODING — hostile input must be a DIAGNOSTIC, never a crash ──────────────
# This codec reads UNTRUSTED capsules. A StackOverflowError is an Error, not a catchable
# failure: it is a crash, and on hostile input that is a denial of service. Measured before
# the bound existed: a 300 KB well-formed-but-deep capsule killed the reader at -Xss1m.
#
# These run at -Xss1m ON PURPOSE. That is the Linux/CI default main-thread stack; macOS
# defaults to 2m. Testing only at the developer default is exactly how a whole test family
# passed on macs and StackOverflowError'd in CI for 192 consecutive runs.
echo "-- bounded decoding: hostile input at -Xss1m (the CI/Linux default stack) --"
bomb() { python3 -c "
n=$1
print('(program (defs) (entry ' + '(seq ' * n + '(lit unit)' + ')' * n + '))', end='')" > "$WORK/bomb.ir"
  java -Xss1m -jar "$JAR" run-ir "$WORK/bomb.ir" 2>&1 | head -1; }

deep=$(bomb 50000)
case "$deep" in
  *StackOverflowError*)
    fail=$((fail + 1))
    echo "FAIL deep capsule (50000) CRASHES the reader at -Xss1m"
    echo "       want: a diagnostic mentioning the depth bound"
    echo "       got : $deep"
    echo "       StackOverflowError is an Error, not a failure -- the reader fails OPEN on hostile input" ;;
  *"nesting depth exceeds"*)
    pass=$((pass + 1)); echo "ok   deep capsule (50000) REJECTED with a depth diagnostic (not StackOverflowError)" ;;
  *)
    fail=$((fail + 1))
    echo "FAIL deep capsule (50000) was neither rejected nor diagnosed"
    echo "       want: a diagnostic mentioning 'nesting depth exceeds'"
    echo "       got : $deep" ;;
esac

# The bound must not be so tight that real programs break. Real Core IR is SHALLOW: measured,
# the 79,667 B self-hosted compiler's own IR (the X1 fixpoint) is depth 25 and the .coreir
# fixtures are 6-12. A depth-30 program is deeper than anything the toolchain has ever emitted
# and must still decode and run.
ok30=$(bomb 30)
case "$ok30" in
  *"nesting depth exceeds"*|*StackOverflowError*)
    fail=$((fail + 1))
    echo "FAIL depth-30 capsule rejected -- the bound is too TIGHT for real IR (fixpoint IR is depth 25)"
    echo "       got : $ok30" ;;
  *) pass=$((pass + 1)); echo "ok   depth-30 capsule still decodes (bound leaves real IR, depth<=25, working)" ;;
esac
echo

# ── 6. H4 — coreir.decode round-trips EVERY node and constant (decode ∘ encode = id) ─────
# `coreir.decode : Str|Bytes -> IrProg` is now a registered prim (10-core-ir.md §5). For every
# node and constant, decode(encode(x)) must re-encode to the SAME canonical bytes as encode(x).
# Same want-texts as §§1/3 — proving the two directions agree on all 13 nodes + 7 constants.
echo "-- H4 decode: round-trip all 7 constants (decode ∘ encode = id) --"
rt_entry "rt const CUnit"   "(lit unit)"          'IrLit(IrUnit)'
rt_entry "rt const CBool t" "(lit true)"          'IrLit(IrBool(true))'
rt_entry "rt const CBool f" "(lit false)"         'IrLit(IrBool(false))'
rt_entry "rt const CInt"    "(lit (int 42))"      'IrLit(IrInt(42))'
rt_entry "rt const CInt -"  "(lit (int -7))"      'IrLit(IrInt(#i.neg(7)))'
rt_entry "rt const CInt MIN" "(lit (int -9223372036854775808))" 'IrLit(IrInt(#i.add(9223372036854775807, 1)))'
rt_entry "rt const CBig"    "(lit (big 123456789012345678901234567890))" 'IrLit(IrBig(bigOf("123456789012345678901234567890")))'
rt_entry "rt const CFloat"  "(lit (float 7.5))"   'IrLit(IrFloat(7.5))'
rt_entry "rt const CStr"    '(lit (str "hi"))'    'IrLit(IrStr("hi"))'
rt_entry "rt const CBytes"  "(lit (bytes 4869))"  'IrLit(IrBytes(#str->utf8("Hi")))'
rt_entry "rt const CBytes empty" "(lit (bytes))"  'IrLit(IrBytes(#str->utf8("")))'
echo "-- H4 decode: float bit identity survives the FULL .ssc round-trip --"
rt_entry "rt float -0.0"    "(lit (float -0.0))"  'IrLit(IrFloat(#f.neg(0.0)))'
rt_entry "rt float 0.0"     "(lit (float 0.0))"   'IrLit(IrFloat(0.0))'
rt_entry "rt float 2.0"     "(lit (float 2.0))"   'IrLit(IrFloat(2.0))'
rt_entry "rt float nan"     "(lit (float nan))"   'IrLit(IrFloat(#f.div(0.0, 0.0)))'
rt_entry "rt float inf"     "(lit (float inf))"   'IrLit(IrFloat(#f.div(1.0, 0.0)))'
rt_entry "rt float -inf"    "(lit (float -inf))"  'IrLit(IrFloat(#f.div(#f.neg(1.0), 0.0)))'
echo "-- H4 decode: round-trip all 13 nodes (decode ∘ encode = id) --"
rt_entry "rt node Lit"     "(lit (int 1))"                      'IrLit(IrInt(1))'
rt_entry "rt node Local"   "(lam 1 (local 0))"                  'IrLam(1, IrLocal(0))'
# Global / App reference `f`, so the round-trip program DEFINES it (decode validates closedness).
rt_prog  "rt node Global"  "(program (defs (def f (lam 0 (lit unit)))) (entry (global f)))" \
  'IrProg(Cons(IrDef("f", IrLam(0, IrLit(IrUnit))), Nil), IrGlobal("f"))'
rt_entry "rt node Lam"     "(lam 2 (lit unit))"                 'IrLam(2, IrLit(IrUnit))'
rt_entry "rt node Lam 0"   "(lam 0 (lit unit))"                 'IrLam(0, IrLit(IrUnit))'
rt_prog  "rt node App"     "(program (defs (def f (lam 1 (local 0)))) (entry (app (global f) (lit (int 1)))))" \
  'IrProg(Cons(IrDef("f", IrLam(1, IrLocal(0))), Nil), IrApp(IrGlobal("f"), Cons(IrLit(IrInt(1)), Nil)))'
rt_prog  "rt node App 0"   "(program (defs (def f (lam 0 (lit unit)))) (entry (app (global f))))" \
  'IrProg(Cons(IrDef("f", IrLam(0, IrLit(IrUnit))), Nil), IrApp(IrGlobal("f"), Nil))'
rt_entry "rt node Let"     "(let ((lit (int 1))) (local 0))"    'IrLet(Cons(IrLit(IrInt(1)), Nil), IrLocal(0))'
rt_entry "rt node LetRec"  "(letrec ((lam 1 (local 0))) (local 0))" 'IrLetRec(Cons(IrLam(1, IrLocal(0)), Nil), IrLocal(0))'
rt_entry "rt node If"      "(if (lit true) (lit (int 1)) (lit (int 2)))" 'IrIf(IrLit(IrBool(true)), IrLit(IrInt(1)), IrLit(IrInt(2)))'
rt_entry "rt node Ctor"    "(ctor Pair (lit (int 1)) (lit (int 2)))" 'IrCtor("Pair", Cons(IrLit(IrInt(1)), Cons(IrLit(IrInt(2)), Nil)))'
rt_entry "rt node Ctor 0"  "(ctor Nil)"                         'IrCtor("Nil", Nil)'
rt_entry "rt node Prim"    "(prim i.add (lit (int 1)) (lit (int 2)))" 'IrPrim("i.add", Cons(IrLit(IrInt(1)), Cons(IrLit(IrInt(2)), Nil)))'
rt_entry "rt node While"   "(while (lit false) (lit unit))"     'IrWhile(IrLit(IrBool(false)), IrLit(IrUnit))'
rt_entry "rt node Seq"     "(seq (lit (int 1)) (lit (int 2)))"  'IrSeq(Cons(IrLit(IrInt(1)), Cons(IrLit(IrInt(2)), Nil)))'
# Match scrutinizes `(local 0)`, so it is wrapped in a 1-arg lam that binds it (decode validates).
rt_entry "rt node Match"   "(lam 1 (match (local 0) ((arm Nil 0 (lit (int 0))))))" \
  'IrLam(1, IrMatch(IrLocal(0), Cons(IrArm("Nil", 0, IrLit(IrInt(0))), Nil), None))'
rt_entry "rt node Match+def" "(lam 1 (match (local 0) ((arm Nil 0 (lit (int 0)))) (default (lit (int 9)))))" \
  'IrLam(1, IrMatch(IrLocal(0), Cons(IrArm("Nil", 0, IrLit(IrInt(0))), Nil), Some(IrLit(IrInt(9)))))'
echo
echo "-- H4 decode: encode ∘ decode = canonicalize (12-ir-format.md), from .ssc --"
# A whole program with defs + a str literal, given in the LENIENT pretty/commented form, must
# re-emit as the exact canonical single-line bytes — the property 12-ir-format.md promises and
# that was previously not expressible from the language (coreir.decode did not exist).
expect_out "canon: lenient fact + str re-emits canonical (encode∘decode=canonicalize)" \
  '(program (defs (def fact (lam 1 (if (prim i.le (local 0) (lit (int 1))) (lit (str "one")) (app (global fact) (prim i.sub (local 0) (lit (int 1)))))))) (entry (app (global fact) (lit (int 5)))))' \
  "$(canon_file '(program
     ; a comment — the reader is lenient about LAYOUT only (12-ir-format.md)
     (defs (def fact (lam 1
       (if (prim i.le (local 0) (lit (int 1)))
           (lit (str "one"))
           (app (global fact) (prim i.sub (local 0) (lit (int 1))))))))
     (entry (app (global fact) (lit (int 5)))))')"
# decode also accepts Bytes (UTF-8 of the canonical text), not just Str.
expect_out "H4 decode: Bytes input (UTF-8) decodes like Str" "(program (defs) (entry (lit (int 7))))" \
  "$(cat > "$WORK/db.ssc0" <<'EOF'
def main = () => #io.print(#coreir.encode(#coreir.decode(#str->utf8("(program (defs) (entry (lit (int 7))))"))))
EOF
  java -Xss512m -jar "$JAR" run "$WORK/db.ssc0" 2>&1)"
echo

# ── 7. H5 — the reader FAILS CLOSED on malformed IR (was fail-OPEN) ───────────────────────
# Each vector feeds a term the canonical Writer could NEVER produce and asserts a specific
# rejection. Measured on the pre-H5 kernel (base jar), the ones marked (was fail-OPEN) were
# SILENTLY ACCEPTED — the program ran with wrong/out-of-bounds semantics and NO diagnostic.
echo "-- H5 fail-closed: de Bruijn indices (NAT + in-range) --"
reject "(local -1) negative de Bruijn (was fail-OPEN: OOB env read)" \
  "not a canonical NAT" '(program (defs) (entry (lam 1 (local -1))))'
reject "(local 5) out of range in a 1-binder lam (was fail-OPEN: reads wrong/OOB slot)" \
  "local index out of range" '(program (defs) (entry (lam 1 (local 5))))'
reject "(local 0) free at top level (no binder in scope)" \
  "local index out of range" '(program (defs) (entry (local 0)))'
reject "(lam +1 …) signed arity (was fail-OPEN)" \
  "not a canonical NAT" '(program (defs) (entry (lam +1 (lit unit))))'
reject "(arm T -1 …) negative arm arity (was fail-OPEN)" \
  "not a canonical NAT" '(program (defs) (entry (match (local 0) ((arm Nil -1 (lit unit))))))'
echo "-- H5 fail-closed: integer / hex tokens (canonical form) --"
reject "(int +1) signed integer (was fail-OPEN: read as 1)" \
  "not a canonical INT" '(program (defs) (entry (lit (int +1))))'
reject "(int 01) leading-zero integer (was fail-OPEN: read as 1)" \
  "not a canonical INT" '(program (defs) (entry (lit (int 01))))'
reject "(bytes abc) odd-length hex (was fail-OPEN: took 2 bytes)" \
  "hex must be even length" '(program (defs) (entry (lit (bytes abc))))'
reject "(bytes +1) signed / non-hex digit (was fail-OPEN: Integer.parseInt took +1)" \
  "non-hex digit" '(program (defs) (entry (lit (bytes +1))))'
reject "(bytes zz) non-hex letters" \
  "non-hex digit" '(program (defs) (entry (lit (bytes zz))))'
echo "-- H5 fail-closed: structural (letrec-is-Lam, closed globals) --"
reject "letrec binding is a non-Lam term" \
  "letrec binding must be a lam" '(program (defs) (entry (letrec ((lit (int 1))) (local 0))))'
# The KEYSTONE fail-open: an unbound global in a NEVER-EVALUATED branch. Pre-H5 the reader
# accepted it and the program printed 1 (the runtime never touched `(global nope)`), so the
# invalid capsule ran clean. Now it is rejected at DECODE, before it can execute at all.
reject "unbound global in a dead branch (was fail-OPEN: program ran, printed 1)" \
  "unbound global" '(program (defs) (entry (if (lit true) (lit (int 1)) (global nope))))'
reject "unbound global in the entry" \
  "unbound global" '(program (defs) (entry (global nope)))'
# A defined global, and an @-named-arg cell, must still be ACCEPTED (closedness, not paranoia).
expect_out "H5: a DEFINED global still decodes and runs (not over-eager)" "7" \
  "$(runir '(program (defs (def g (lam 0 (lit (int 7))))) (entry (app (global g))))')"
echo
# coreir.decode inherits the same fail-closed reader: malformed text rejected from .ssc too.
echo "-- H5 fail-closed: coreir.decode inherits the validated reader --"
expect_out "coreir.decode rejects (local -1) from .ssc" "REJECTED" \
  "$(cat > "$WORK/dr.ssc0" <<'EOF'
def main = () => #io.print(#coreir.encode(#coreir.decode("(program (defs) (entry (lam 1 (local -1))))")))
EOF
  java -Xss512m -jar "$JAR" run "$WORK/dr.ssc0" >/dev/null 2>&1 && echo ACCEPTED || echo REJECTED)"
echo

echo "== $pass passed, $fail failed =="
[ "$fail" -eq 0 ] || exit 1
echo "ok   *** all canonical Core IR codec vectors pass ***"
