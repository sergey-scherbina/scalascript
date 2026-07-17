package ssc

// Core IR — the untyped bytecode of the ssc VM (specs/10-core-ir.md).
// Pipeline: ssc0 -> IR -> ssc(VM) -> cpu. This file is the IR itself plus its
// canonical S-expr text form (specs/12-ir-format.md): Reader (IR in) + Writer
// (IR out = coreir.encode).

enum Const:
  case CUnit
  case CBool(b: Boolean)
  case CInt(n: Long)        // 64-bit wrapping
  case CBig(n: BigInt)      // arbitrary precision
  case CFloat(d: Double)    // IEEE-754 double
  case CStr(s: String)
  case CBytes(b: Vector[Byte])

enum Term:
  case Lit(c: Const)
  case Local(i: Int)                 // de Bruijn; 0 = innermost (last binder of a group)
  case Global(name: String)
  case Lam(arity: Int, body: Term)   // arity 0 = thunk
  case App(fn: Term, args: List[Term])
  case Let(rhs: List[Term], body: Term)
  case LetRec(lams: List[Term], body: Term)
  case If(c: Term, t: Term, e: Term)
  case Ctor(tag: String, fields: List[Term])
  case Match(scrut: Term, arms: List[Arm], default: Option[Term])
  case Prim(op: String, args: List[Term])
  // Optimization: Java while-loop (no trampoline bounce per iteration)
  case While(cond: Term, body: Term)
  // Optimization: evaluate terms in sequence with same env (no Let-binding overhead)
  case Seq(terms: List[Term])

case class Arm(tag: String, arity: Int, body: Term)
case class Def(name: String, body: Term)
case class Program(defs: List[Def], entry: Term)

/** Private lowering vocabulary shared by every CoreIR consumer. General
  * frontend patterns carry selected and/or terminal-miss decision markers
  * outside nested lambdas; canonical frontends keep the direct
  * Match(Local(0), ...) shape. A total ordered chain may eliminate its
  * synthetic miss after a final catch-all, so either marker qualifies the
  * root. These names are deliberately absent from the public primitive
  * manifest. */
private[ssc] object HandlerDispatchShape:
  val SelectedPrimitive = "__handler_dispatch_selected__"
  val MissPrimitive = "__handler_dispatch_miss__"

  def isRoot(arity: Int, body: Term): Boolean =
    arity == 1 && (body match
      case Term.Match(Term.Local(0), _, _) => true
      case other                           => containsDecisionMarker(other))

  private def containsDecisionMarker(term: Term): Boolean = term match
    case Term.Prim(SelectedPrimitive, _) => true
    case Term.Prim(MissPrimitive, _)     => true
    case Term.Lam(_, _)                  => false
    case Term.App(fn, args)              =>
      containsDecisionMarker(fn) || args.exists(containsDecisionMarker)
    case Term.Let(rhs, body)             =>
      rhs.exists(containsDecisionMarker) || containsDecisionMarker(body)
    case Term.LetRec(lams, body)      => lams.exists {
      case Term.Lam(_, _) => false
      case other          => containsDecisionMarker(other)
    } || containsDecisionMarker(body)
    case Term.If(cond, yes, no)       =>
      containsDecisionMarker(cond) || containsDecisionMarker(yes) || containsDecisionMarker(no)
    case Term.Ctor(_, fields)         => fields.exists(containsDecisionMarker)
    case Term.Match(scrutinee, arms, default) =>
      containsDecisionMarker(scrutinee) ||
        arms.exists(arm => containsDecisionMarker(arm.body)) ||
        default.exists(containsDecisionMarker)
    case Term.Prim(_, args)           => args.exists(containsDecisionMarker)
    case Term.While(cond, body)       =>
      containsDecisionMarker(cond) || containsDecisionMarker(body)
    case Term.Seq(terms)              => terms.exists(containsDecisionMarker)
    case _                            => false

// ── S-expr reader (lenient: whitespace + `;` comments) ───────────────────────

enum Sx:
  case Atom(s: String)
  case Str(s: String)
  case Lst(xs: List[Sx])

object Reader:
  /** Bounded decoding (`specs/12-ir-format.md` §"Bounded decoding").
    *
    * The canonical reader is the entry point for **untrusted persisted capsules**, so it has
    * to fail with a *diagnostic*, never with a JVM `StackOverflowError` — an `Error` is not a
    * catchable failure, it is a crash, and on a hostile input that is a denial of service.
    *
    * Measured on the toolchain JDK (21) before this bound existed: a 300 KB, perfectly
    * well-formed capsule (`(seq (seq … (lit unit) …))`, nothing malformed — merely deep)
    * killed the reader with `StackOverflowError` in `Reader$P.readAtom` at `-Xss1m`, the
    * **Linux/CI default** main-thread stack. macOS defaults to 2m, which is exactly the
    * asymmetry that kept CI red for 192 consecutive runs while every developer saw green.
    *
    * Why 1000 is safe for real programs: measured max paren nesting of every real Core IR we
    * have — the 79,667 B self-hosted compiler's OWN IR (the X1 fixpoint) is depth **25**, and
    * the `.coreir` fixtures under `v2/conformance` are 6-12. So this bound is ~40x headroom over the
    * deepest program the toolchain has ever produced, while rejecting the bomb outright.
    *
    * Override with `-Dssc.coreir.maxDepth=N` for a deliberately deep experiment.
    *
    * NOTE (recorded, not silently fixed): bounding the *reader* does not by itself make the
    * whole capsule path DoS-safe. `Compiler.valuePositionsNeedEffectThreading` / `FastCode.tryFC`
    * independently overflow at ~depth 500 on `-Xss1m` — a separate unbounded recursion, tracked
    * in `BUGS.md` as `coreir-compiler-unbounded-depth`. This bound is the codec's half. */
  val MaxDepth: Int = Option(System.getProperty("ssc.coreir.maxDepth")).flatMap(_.toIntOption).getOrElse(1000)

  def parseProgram(src: String): Program =
    val p = toProgram(parseOne(src))
    validate(p)  // fail CLOSED on malformed IR — this is the untrusted-capsule entry point
    p

  def parseOne(src: String): Sx =
    val p = new P(src)
    p.skipWs(); val sx = p.sexpr(); p.skipWs()
    if !p.atEnd then sys.error(s"trailing input at offset ${p.pos}")
    sx

  final class P(val s: String):
    var pos = 0
    /** Current `(` nesting depth — see [[Reader.MaxDepth]]. */
    private var depth = 0
    def atEnd = pos >= s.length
    def peek = s.charAt(pos)
    def isDelim(c: Char) = c.isWhitespace || c == '(' || c == ')' || c == ';' || c == '"'
    def skipWs(): Unit =
      var go = true
      while go do
        if atEnd then go = false
        else if peek == ';' then while !atEnd && peek != '\n' do pos += 1
        else if peek.isWhitespace then pos += 1
        else go = false
    def sexpr(): Sx =
      skipWs()
      if atEnd then sys.error("unexpected EOF")
      peek match
        case '(' =>
          pos += 1
          depth += 1
          // Bounded decoding: reject BEFORE recursing, so a hostile capsule gets a diagnostic
          // instead of a StackOverflowError. See Reader.MaxDepth.
          if depth > MaxDepth then
            sys.error(
              s"nesting depth exceeds the bound of $MaxDepth at offset $pos " +
              s"(bounded decoding, specs/12-ir-format.md; real Core IR is depth <= 25; " +
              s"override with -Dssc.coreir.maxDepth=N)")
          val buf = collection.mutable.ListBuffer[Sx]()
          var go = true
          while go do
            skipWs()
            if atEnd then sys.error("unterminated list")
            else if peek == ')' then { pos += 1; go = false }
            else buf += sexpr()
          depth -= 1
          Sx.Lst(buf.toList)
        case ')' => sys.error(s"unexpected ')' at offset $pos")
        case '"' => Sx.Str(readString())
        case _   => Sx.Atom(readAtom())
    def readAtom(): String =
      val start = pos
      while !atEnd && !isDelim(peek) do pos += 1
      s.substring(start, pos)
    def readString(): String =
      pos += 1
      val sb = new StringBuilder
      var go = true
      while go do
        if atEnd then sys.error("unterminated string")
        val c = peek; pos += 1
        if c == '"' then go = false
        else if c == '\\' then
          val e = peek; pos += 1
          e match
            case '"' => sb += '"'; case '\\' => sb += '\\'
            case 'n' => sb += '\n'; case 'r' => sb += '\r'; case 't' => sb += '\t'
            case 'u' => sb += Integer.parseInt(s.substring(pos, pos + 4), 16).toChar; pos += 4
            case o   => sys.error(s"bad escape \\$o")
        else sb += c
      sb.toString

  def toProgram(sx: Sx): Program = sx match
    case Sx.Lst(Sx.Atom("program") :: d :: e :: Nil) =>
      val defs = d match
        case Sx.Lst(Sx.Atom("defs") :: ds) => ds.map(toDef)
        case _ => sys.error("expected (defs ...)")
      val entry = e match
        case Sx.Lst(Sx.Atom("entry") :: t :: Nil) => toTerm(t)
        case _ => sys.error("expected (entry <term>)")
      Program(defs, entry)
    case _ => sys.error("expected (program <defs> <entry>)")

  def toDef(sx: Sx): Def = sx match
    case Sx.Lst(Sx.Atom("def") :: Sx.Atom(n) :: b :: Nil) => Def(n, toTerm(b))
    case _ => sys.error(s"bad def: $sx")

  def toArm(sx: Sx): Arm = sx match
    case Sx.Lst(Sx.Atom("arm") :: Sx.Atom(t) :: Sx.Atom(a) :: b :: Nil) => Arm(t, natOf(a, "arm arity"), toTerm(b))
    case _ => sys.error(s"bad arm: $sx")

  def toTerm(sx: Sx): Term = sx match
    case Sx.Lst(Sx.Atom(h) :: rest) => h match
      case "lit"    => Term.Lit(toConst(rest))
      case "local"  => rest match { case Sx.Atom(i) :: Nil => Term.Local(natOf(i, "local index")); case _ => sys.error("bad local") }
      case "global" => rest match { case Sx.Atom(n) :: Nil => Term.Global(n);      case _ => sys.error("bad global") }
      case "lam"    => rest match { case Sx.Atom(a) :: b :: Nil => Term.Lam(natOf(a, "lam arity"), toTerm(b)); case _ => sys.error("bad lam") }
      case "app"    => rest match { case fn :: as => Term.App(toTerm(fn), as.map(toTerm)); case _ => sys.error("bad app") }
      case "let"    => rest match { case Sx.Lst(r) :: b :: Nil => Term.Let(r.map(toTerm), toTerm(b)); case _ => sys.error("bad let") }
      case "letrec" => rest match { case Sx.Lst(l) :: b :: Nil => Term.LetRec(l.map(toTerm), toTerm(b)); case _ => sys.error("bad letrec") }
      case "if"     => rest match { case c :: t :: e :: Nil => Term.If(toTerm(c), toTerm(t), toTerm(e)); case _ => sys.error("bad if") }
      case "ctor"   => rest match { case Sx.Atom(t) :: fs => Term.Ctor(t, fs.map(toTerm)); case _ => sys.error("bad ctor") }
      case "prim"   => rest match { case Sx.Atom(o) :: as => Term.Prim(o, as.map(toTerm)); case _ => sys.error("bad prim") }
      case "while"  => rest match { case c :: b :: Nil => Term.While(toTerm(c), toTerm(b)); case _ => sys.error("bad while") }
      case "seq"    => Term.Seq(rest.map(toTerm))
      case "match"  => rest match
        case s :: Sx.Lst(arms) :: tail =>
          val default = tail match
            case Nil => None
            case Sx.Lst(Sx.Atom("default") :: d :: Nil) :: Nil => Some(toTerm(d))
            case _ => sys.error("bad match default")
          Term.Match(toTerm(s), arms.map(toArm), default)
        case _ => sys.error("bad match")
      case other => sys.error(s"unknown term head: ($other ...)")
    case other => sys.error(s"not a term: $other")

  def toConst(rest: List[Sx]): Const = rest match
    case Sx.Atom("unit")  :: Nil => Const.CUnit
    case Sx.Atom("true")  :: Nil => Const.CBool(true)
    case Sx.Atom("false") :: Nil => Const.CBool(false)
    case Sx.Lst(Sx.Atom("int")   :: Sx.Atom(n) :: Nil) :: Nil => Const.CInt(intOf(n, "int literal"))
    case Sx.Lst(Sx.Atom("big")   :: Sx.Atom(n) :: Nil) :: Nil => Const.CBig(bigOf(n, "big literal"))
    case Sx.Lst(Sx.Atom("float") :: Sx.Atom(x) :: Nil) :: Nil => Const.CFloat(parseFloat(x))
    case Sx.Lst(Sx.Atom("str")   :: Sx.Str(s)  :: Nil) :: Nil => Const.CStr(s)
    case Sx.Lst(Sx.Atom("bytes") :: Sx.Atom(h) :: Nil) :: Nil => Const.CBytes(parseHex(h))
    case Sx.Lst(Sx.Atom("bytes") :: Nil)               :: Nil => Const.CBytes(Vector.empty)
    case _ => sys.error(s"bad const: $rest")

  def parseFloat(x: String): Double = x match
    case "nan" => Double.NaN; case "inf" => Double.PositiveInfinity; case "-inf" => Double.NegativeInfinity
    case _ => x.toDouble

  // ── strict token validation — fail CLOSED (specs/12-ir-format.md §Tokens) ────────────────
  // The reader decodes UNTRUSTED persisted capsules, so every numeric/hex token is *validated*,
  // not merely `.toInt`-ed: an accepted-but-malformed token is a fail-OPEN security defect
  // ("Leniency is about layout, never about validity"). Leniency covers whitespace + comments
  // only. Before this, `(local -1)` decoded to a negative de Bruijn index (an OOB env read),
  // `(int +1)`/`(int 01)` were silently accepted, and `(bytes abc)`/`(bytes +1)` were taken as
  // bytes — each a term the canonical Writer could never have produced.

  /** NAT := `0` | `[1-9][0-9]*` — no sign, no leading zeros. */
  private def isNat(s: String): Boolean =
    s == "0" || (s.nonEmpty && s.charAt(0) != '0' && s.forall(c => c >= '0' && c <= '9'))

  /** de Bruijn index / arity token. Rejects `-1`, `+1`, `01`, empty, non-digits, Int overflow. */
  def natOf(s: String, what: String): Int =
    if !isNat(s) then sys.error(s"$what: not a canonical NAT (0|[1-9][0-9]*): '$s'")
    s.toIntOption.getOrElse(sys.error(s"$what: NAT out of Int range: '$s'"))

  /** INT := `-?` NAT, canonical (no `+`, no leading zeros, `-0` is not canonical). 64-bit. */
  def intOf(s: String, what: String): Long =
    if !isCanonicalInt(s) then sys.error(s"$what: not a canonical INT (0|-?[1-9][0-9]*): '$s'")
    s.toLongOption.getOrElse(sys.error(s"$what: INT out of 64-bit range: '$s'"))

  /** big INT := `-?` NAT, arbitrary precision — same canonical shape, no width bound. */
  def bigOf(s: String, what: String): BigInt =
    if !isCanonicalInt(s) then sys.error(s"$what: not a canonical INT (0|-?[1-9][0-9]*): '$s'")
    BigInt(s)

  private def isCanonicalInt(s: String): Boolean =
    if s.startsWith("-") then s != "-0" && isNat(s.substring(1)) else isNat(s)

  private def isHexDigit(c: Char): Boolean =
    (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')

  /** HEX := an even-length run of hex digits (canonical form is lowercase, §Tokens; the reader
    * tolerates upper case as layout). Rejects odd length, `+`/`-` signs, and non-hex chars. */
  def parseHex(h: String): Vector[Byte] =
    if h.length % 2 != 0 then sys.error(s"bytes: hex must be even length, got ${h.length} digit(s): '$h'")
    if !h.forall(isHexDigit) then sys.error(s"bytes: non-hex digit in '$h' (expected [0-9a-fA-F])")
    h.grouped(2).map(p => Integer.parseInt(p, 16).toByte).toVector

  // ── structural validation — fail CLOSED on malformed IR (specs/12-ir-format.md §Bounded) ─
  /** Reject a decoded program that is structurally invalid — a term the encoder could never
    * have produced. Runs on EVERY `parseProgram` (the untrusted-capsule entry point). Checks,
    * each naming the offending node:
    *   - every `Local i` resolves to an enclosing binder (`0 <= i < depth`) — a FREE local is
    *     unforgeable by the encoder and reads a wrong / out-of-bounds `env` slot at run time;
    *   - `letrec` bindings are all `Lam` (10-core-ir.md §4 "bindings must be Lam");
    *   - every `Global g` is closed: a top-level `def`, or an `@`-named-arg cell (the runtime's
    *     own resolve fallback, `Runtime.scala` — the kernel reader cannot see the plugin registry).
    * Arities / indices are already NAT-validated at parse; this adds the scope-level checks. The
    * de Bruijn scope model matches the evaluator exactly (10-core-ir.md §4): entry & each def body
    * start at depth 0; `Lam ar` adds `ar`; `Let` is let* (rhs i sees i earlier binders, body sees
    * all); `LetRec` binds all lambdas simultaneously; a `Match` arm of arity k adds k. */
  def validate(p: Program): Unit =
    val defNames = p.defs.iterator.map(_.name).toSet
    def globalOk(g: String): Boolean = defNames.contains(g) || g.startsWith("@")
    def go(t: Term, depth: Int): Unit = t match
      case Term.Lit(_)    => ()
      case Term.Local(i)  =>
        if i < 0 || i >= depth then
          sys.error(s"local index out of range: (local $i) with $depth binder(s) in scope")
      case Term.Global(g) =>
        if !globalOk(g) then
          sys.error(s"unbound global: (global $g) is neither a top-level def nor an @-cell")
      case Term.Lam(ar, b)     => go(b, depth + ar)
      case Term.App(fn, as)    => go(fn, depth); as.foreach(go(_, depth))
      case Term.Let(rhs, body) =>
        rhs.iterator.zipWithIndex.foreach { case (r, i) => go(r, depth + i) }
        go(body, depth + rhs.length)
      case Term.LetRec(lams, body) =>
        val d2 = depth + lams.length
        lams.foreach {
          case l: Term.Lam => go(l, d2)
          case other       => sys.error(s"letrec binding must be a lam, got: ${nodeName(other)}")
        }
        go(body, d2)
      case Term.If(c, th, el)  => go(c, depth); go(th, depth); go(el, depth)
      case Term.Ctor(_, fs)    => fs.foreach(go(_, depth))
      case Term.Prim(_, as)    => as.foreach(go(_, depth))
      case Term.While(c, b)    => go(c, depth); go(b, depth)
      case Term.Seq(ts)        => ts.foreach(go(_, depth))
      case Term.Match(scrut, arms, default) =>
        go(scrut, depth)
        arms.foreach(a => go(a.body, depth + a.arity))
        default.foreach(go(_, depth))
    p.defs.foreach(d => go(d.body, 0))
    go(p.entry, 0)

  private def nodeName(t: Term): String = t match
    case _: Term.Lit => "lit"; case _: Term.Local => "local"; case _: Term.Global => "global"
    case _: Term.App => "app"; case _: Term.Let => "let"; case _: Term.LetRec => "letrec"
    case _: Term.If => "if"; case _: Term.Ctor => "ctor"; case _: Term.Match => "match"
    case _: Term.Prim => "prim"; case _: Term.While => "while"; case _: Term.Seq => "seq"
    case _: Term.Lam => "lam"

// ── Writer: Term -> canonical S-expr (= coreir.encode, specs/12-ir-format.md) ─

object Writer:
  import Term.*
  def program(p: Program): String =
    val defs = if p.defs.isEmpty then "(defs)" else s"(defs ${p.defs.map(d => s"(def ${d.name} ${term(d.body)})").mkString(" ")})"
    s"(program $defs (entry ${term(p.entry)}))"

  def term(t: Term): String = t match
    case Lit(c)          => s"(lit ${const(c)})"
    case Local(i)        => s"(local $i)"
    case Global(n)       => s"(global $n)"
    case Lam(a, b)       => s"(lam $a ${term(b)})"
    case App(fn, as)     => s"(app ${term(fn)}${as.map(a => " " + term(a)).mkString})"
    case Let(r, b)       => s"(let (${r.map(term).mkString(" ")}) ${term(b)})"
    case LetRec(l, b)    => s"(letrec (${l.map(term).mkString(" ")}) ${term(b)})"
    case If(c, t, e)     => s"(if ${term(c)} ${term(t)} ${term(e)})"
    case Ctor(tag, fs)   => s"(ctor $tag${fs.map(f => " " + term(f)).mkString})"
    case Prim(op, as)    => s"(prim $op${as.map(a => " " + term(a)).mkString})"
    case While(c, b)     => s"(while ${term(c)} ${term(b)})"
    case Seq(ts)         => s"(seq${ts.map(t => " " + term(t)).mkString})"
    case Match(s, arms, d) =>
      val a = arms.map(m => s"(arm ${m.tag} ${m.arity} ${term(m.body)})").mkString(" ")
      val dd = d.map(x => s" (default ${term(x)})").getOrElse("")
      s"(match ${term(s)} ($a)$dd)"

  def const(c: Const): String = c match
    case Const.CUnit     => "unit"
    case Const.CBool(b)  => b.toString
    case Const.CInt(n)   => s"(int $n)"
    case Const.CBig(n)   => s"(big $n)"
    case Const.CFloat(d) => s"(float ${floatLit(d)})"
    case Const.CStr(s)   => s"(str ${strLit(s)})"
    case Const.CBytes(b) => if b.isEmpty then "(bytes)" else s"(bytes ${b.map(x => f"${x & 0xff}%02x").mkString})"

  /** Canonical IR **float literal** — `specs/12-ir-format.md` §Tokens: "canonical shortest
    * round-tripping decimal of the IEEE-754 value ..., always containing a `.` or an
    * exponent; specials are exactly `nan`, `inf`, `-inf`. Negative zero is `-0.0`."
    *
    * DELIBERATELY NOT `floatStr`. `floatStr` is the **user-visible** renderer shared by
    * `f->str`, `FloatV.toString`, Float↔String concat and `Show`, where whole doubles
    * collapse (`2.0` -> `"2"`) on purpose, for ssc 1.0 output parity. Encoding IR through
    * it cost the canonical form its **negative-zero bit identity**, because
    * `floatStr(-0.0) == "0" == floatStr(0.0)` — `-0.0` silently decoded back as `+0.0`.
    * The two contracts genuinely differ, so they are two functions. Do not re-merge them:
    * "fixing" `floatStr` instead would change program OUTPUT across the corpus, and the
    * run-ir-only fixpoint gate would not notice (it has no float constants at all).
    *
    * `Double.toString` is exactly the spec's FLOAT on JDK 19+ (JDK-4511638 made it the
    * shortest round-tripping decimal); it always emits a `.` or an `E`, and `-0.0` prints
    * as `-0.0`. Verified on the toolchain JDK (21). On a pre-19 JDK it still round-trips
    * but is not always shortest, so the canonical bytes would become host-dependent — the
    * kernel requires JDK 19+ for this reason. */
  def floatLit(d: Double): String =
    if d.isNaN then "nan"
    else if d == Double.PositiveInfinity then "inf"
    else if d == Double.NegativeInfinity then "-inf"
    else d.toString

  /** USER-VISIBLE float rendering (`f->str`, `.toString`, concat, `Show`) — ssc 1.0 output
    * parity, which collapses whole doubles: `2.0` renders as `"2"`. This is NOT the
    * canonical IR literal form; see [[floatLit]] before changing anything here. */
  def floatStr(d: Double): String =
    if d.isNaN then "nan" else if d == Double.PositiveInfinity then "inf"
    else if d == Double.NegativeInfinity then "-inf"
    else if d == d.toLong.toDouble && !d.isInfinite then d.toLong.toString
    else d.toString
  def strLit(s: String): String =
    val sb = new StringBuilder("\"")
    s.foreach {
      case '"' => sb ++= "\\\""; case '\\' => sb ++= "\\\\"
      case '\n' => sb ++= "\\n"; case '\r' => sb ++= "\\r"; case '\t' => sb ++= "\\t"
      case c if c.isControl => sb ++= f"\\u${c.toInt}%04x"
      case c => sb += c
    }
    sb += '"'; sb.toString
