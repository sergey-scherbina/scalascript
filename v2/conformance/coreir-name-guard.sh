#!/usr/bin/env bash
# The canonical Core IR Writer must refuse a name that would not survive a decode.
#
# Names, tags and opcodes are emitted VERBATIM — the canonical form has no quoting for them — so a
# value holding a DELIMITER (whitespace, `(`, `)`, `;`, `"` — `Reader.isDelim`) makes `readAtom`
# stop early and the remainder re-parse as structure. That is not a formatting nit: `coreir.encode`
# serialises a `Data` tree its CALLER built, and Core IR is the persisted `SavedContinuation`
# capsule format, so this is where a caller-influenced name arrives
# (`coreir-canonical-codec-contract`).
#
# NOT A `SYMBOL` CHECK, and the difference is the whole reason this gate is narrow. The documented
# grammar used to read `[A-Za-z_][A-Za-z0-9_.]*`, which is NARROWER than what the Writer
# legitimately emits — `(prim str->i …)` is in ordinary generated IR from the ssc1c prelude and the
# Reader reads it back — so enforcing that spelling would have rejected correct output. The grammar
# was widened to match (`coreir-symbol-grammar-drift`); what remains enforceable is the round trip,
# which is exactly the delimiter set. The `str->i` row below is the control that pins that
# distinction: if someone later "tightens" the guard to the old grammar, this gate goes red.
#
# Runs the REAL Writer and the REAL Reader through scala-cli against `v2/src`, rather than asserting
# on the source text — a gate that greps for the guard would pass against a guard that never fires.
set -uo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
V2="$(dirname "$DIR")"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/coreir-name-guard.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
scli() { command scala-cli "$@" --server=false; }

cat > "$TMP/probe.scala" <<'SCALA'
import ssc.*

@main def probe(): Unit =
  // `extra` exists because Reader.parseProgram VALIDATES as well as parses: a program whose entry
  // reaches an undefined global is rejected there, which is correct and has nothing to do with the
  // name guard. The first draft of this probe referenced `_sel_map` without defining it and read
  // the validator's complaint as an encoder failure.
  def encWith(extra: List[Def], t: Term): Either[String, String] =
    try Right(Writer.program(Program(Def("main", Term.Lam(0, t)) :: extra, Term.Global("main"))))
    catch case e: Throwable => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  def enc(t: Term): Either[String, String] = encWith(Nil, t)

  // ── must be ACCEPTED: what the Writer legitimately emits today ──────────────
  // `str->i` is the control for the SYMBOL-vs-delimiter distinction; it is not in the old grammar
  // and IS in real generated IR.
  for (label, t) <- Vector(
        "plain prim"        -> Term.Prim("i.add", List(Term.Lit(Const.CInt(1)), Term.Lit(Const.CInt(2)))),
        "arrow prim str->i" -> Term.Prim("str->i", List(Term.Lit(Const.CStr("7")))),
        "dotted prim"       -> Term.Prim("io.print", List(Term.Lit(Const.CStr("x")))),
        "ctor tag"          -> Term.Ctor("Cons", List(Term.Lit(Const.CInt(1)), Term.Ctor("Nil", Nil))),
        "global"            -> Term.Global("_sel_map"),
      )
  do
    val defined = if label == "global" then List(Def("_sel_map", Term.Lit(Const.CUnit))) else Nil
    encWith(defined, t) match
      case Right(s) =>
        // Accepted is not enough: it must ROUND TRIP, which is what the guard exists to protect.
        try { Reader.parseProgram(s); println(s"ACCEPT-OK   $label") }
        catch case e: Throwable => println(s"ACCEPT-BUT-UNREADABLE $label :: ${e.getMessage}")
      case Left(m) => println(s"WRONGLY-REFUSED $label :: $m")

  // ── must be REFUSED: each delimiter, in each of the emitted positions ───────
  val delims = Vector("space" -> "a b", "open" -> "a(b", "close" -> "a)b", "semi" -> "a;b", "quote" -> "a\"b")
  for (dn, bad) <- delims do
    val cases = Vector(
      "prim op"  -> Term.Prim(bad, Nil),
      "ctor tag" -> Term.Ctor(bad, Nil),
      "global"   -> Term.Global(bad),
      "arm tag"  -> Term.Match(Term.Lit(Const.CInt(0)), List(Arm(bad, 0, Term.Lit(Const.CUnit))), None),
    )
    for (pos, t) <- cases do
      enc(t) match
        case Left(_)  => println(s"REFUSE-OK   $dn in $pos")
        case Right(s) =>
          // Say WHY it matters, not just that it happened: show the round trip breaking.
          val rt = try { Reader.parseProgram(s); "and it round-tripped (so the guard may be unnecessary here)" }
                   catch case e: Throwable => s"and decoding it FAILS: ${Option(e.getMessage).getOrElse("")}"
          println(s"NOT-REFUSED $dn in $pos :: $rt")

  // A def NAME is the fifth site and is reached through Program, not Term.
  try
    Writer.program(Program(List(Def("a b", Term.Lit(Const.CUnit))), Term.Lit(Const.CUnit)))
    println("NOT-REFUSED space in def name")
  catch case _: Throwable => println("REFUSE-OK   space in def name")

  // An empty atom cannot be written at all — there is no such token.
  try { Writer.program(Program(List(Def("", Term.Lit(Const.CUnit))), Term.Lit(Const.CUnit))); println("NOT-REFUSED empty def name") }
  catch case _: Throwable => println("REFUSE-OK   empty def name")
SCALA
# `v2/src` carries its own @main, so the probe's must be named explicitly — otherwise scala-cli
# runs the compiler instead and the gate reads as "no output", which is why it says so below.
out="$(scli run "$V2/src" "$TMP/probe.scala" -q --main-class probe 2>"$TMP/err")"
rc=$?
if [ "$rc" -ne 0 ] && [ -z "$out" ]; then
  echo "FAIL: the probe did not run — a gate that cannot run must not look like one that passed"
  head -20 "$TMP/err"
  exit 1
fi

echo "$out"
bad=$(grep -c -E '^(NOT-REFUSED|WRONGLY-REFUSED|ACCEPT-BUT-UNREADABLE)' <<<"$out")
ok=$(grep -c -E '^(ACCEPT-OK|REFUSE-OK)' <<<"$out")
# Real failures FIRST. The count check below is about the probe drifting, and reporting it first
# made a genuine regression read as "the probe changed shape" — which is what it said when this
# file was run against the pre-guard Writer, where 22 rows were NOT-REFUSED.
if [ "$bad" -ne 0 ]; then
  echo "coreir-name-guard: FAIL ($bad rows) — a name that cannot survive a decode was emitted"
  exit 1
fi
# The floor is not a round number: 5 accept rows + 5 delimiters x 4 positions + 2 def-name rows.
expected=27
if [ "$ok" -ne "$expected" ]; then
  echo "FAIL: expected exactly $expected checks, saw $ok — the probe itself changed shape"
  exit 1
fi
echo "coreir-name-guard: OK — $ok checks, every delimiter refused in every emitted position"
