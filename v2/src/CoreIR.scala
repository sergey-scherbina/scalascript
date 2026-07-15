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
  def parseProgram(src: String): Program = toProgram(parseOne(src))

  def parseOne(src: String): Sx =
    val p = new P(src)
    p.skipWs(); val sx = p.sexpr(); p.skipWs()
    if !p.atEnd then sys.error(s"trailing input at offset ${p.pos}")
    sx

  final class P(val s: String):
    var pos = 0
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
          val buf = collection.mutable.ListBuffer[Sx]()
          var go = true
          while go do
            skipWs()
            if atEnd then sys.error("unterminated list")
            else if peek == ')' then { pos += 1; go = false }
            else buf += sexpr()
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
    case Sx.Lst(Sx.Atom("arm") :: Sx.Atom(t) :: Sx.Atom(a) :: b :: Nil) => Arm(t, a.toInt, toTerm(b))
    case _ => sys.error(s"bad arm: $sx")

  def toTerm(sx: Sx): Term = sx match
    case Sx.Lst(Sx.Atom(h) :: rest) => h match
      case "lit"    => Term.Lit(toConst(rest))
      case "local"  => rest match { case Sx.Atom(i) :: Nil => Term.Local(i.toInt); case _ => sys.error("bad local") }
      case "global" => rest match { case Sx.Atom(n) :: Nil => Term.Global(n);      case _ => sys.error("bad global") }
      case "lam"    => rest match { case Sx.Atom(a) :: b :: Nil => Term.Lam(a.toInt, toTerm(b)); case _ => sys.error("bad lam") }
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
    case Sx.Lst(Sx.Atom("int")   :: Sx.Atom(n) :: Nil) :: Nil => Const.CInt(n.toLong)
    case Sx.Lst(Sx.Atom("big")   :: Sx.Atom(n) :: Nil) :: Nil => Const.CBig(BigInt(n))
    case Sx.Lst(Sx.Atom("float") :: Sx.Atom(x) :: Nil) :: Nil => Const.CFloat(parseFloat(x))
    case Sx.Lst(Sx.Atom("str")   :: Sx.Str(s)  :: Nil) :: Nil => Const.CStr(s)
    case Sx.Lst(Sx.Atom("bytes") :: Sx.Atom(h) :: Nil) :: Nil => Const.CBytes(parseHex(h))
    case Sx.Lst(Sx.Atom("bytes") :: Nil)               :: Nil => Const.CBytes(Vector.empty)
    case _ => sys.error(s"bad const: $rest")

  def parseFloat(x: String): Double = x match
    case "nan" => Double.NaN; case "inf" => Double.PositiveInfinity; case "-inf" => Double.NegativeInfinity
    case _ => x.toDouble
  def parseHex(h: String): Vector[Byte] = h.grouped(2).map(p => Integer.parseInt(p, 16).toByte).toVector

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
    case Const.CFloat(d) => s"(float ${floatStr(d)})"
    case Const.CStr(s)   => s"(str ${strLit(s)})"
    case Const.CBytes(b) => if b.isEmpty then "(bytes)" else s"(bytes ${b.map(x => f"${x & 0xff}%02x").mkString})"

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
