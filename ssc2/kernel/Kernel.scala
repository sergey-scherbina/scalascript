package ssc2

// ssc 2.0 — the K1 kernel: untyped Core IR evaluator.
// The only long-lived inner Scala (specs/00-overview.md). Reference semantics,
// correctness over speed. Reads canonical S-expr Core IR (specs/12-ir-format.md),
// evaluates per the big-step rules with a trampoline for the TCO guarantee
// (specs/10-core-ir.md invariant 7).

// ── Core IR (specs/10-core-ir.md §3) ────────────────────────────────────────

enum Const:
  case CUnit
  case CBool(b: Boolean)
  case CInt(n: Long)         // 64-bit wrapping
  case CBig(n: BigInt)       // arbitrary precision
  case CFloat(d: Double)     // IEEE-754 double
  case CStr(s: String)
  case CBytes(b: Vector[Byte])

enum Term:
  case Lit(c: Const)
  case Local(i: Int)                                  // de Bruijn; 0 = innermost (last binder)
  case Global(name: String)
  case Lam(arity: Int, body: Term)                    // arity 0 = thunk
  case App(fn: Term, args: List[Term])               // exact-arity; no args = thunk force
  case Let(rhs: List[Term], body: Term)              // sequential (let*)
  case LetRec(lams: List[Term], body: Term)          // each lam mutually recursive
  case If(c: Term, t: Term, e: Term)
  case Ctor(tag: String, fields: List[Term])
  case Match(scrut: Term, arms: List[Arm], default: Option[Term])
  case Prim(op: String, args: List[Term])

case class Arm(tag: String, arity: Int, body: Term)
case class Def(name: String, body: Term)
case class Program(defs: List[Def], entry: Term)

// ── Values (specs/10-core-ir.md §2) ─────────────────────────────────────────
// Sealed trait (not enum) so ClosV can hold a mutable env for cyclic LetRec frames.

type Env = List[Value]

sealed trait Value
object Value:
  case object UnitV                                   extends Value
  final case class  BoolV(b: Boolean)                extends Value
  final case class  IntV(n: Long)                    extends Value
  final case class  BigV(n: BigInt)                  extends Value
  final case class  FloatV(d: Double)                extends Value
  final case class  StrV(s: String)                  extends Value
  final case class  BytesV(b: Vector[Byte])          extends Value
  final case class  DataV(tag: String, fields: Vector[Value]) extends Value
  final class       ClosV(var env: Env, val arity: Int, val body: Term) extends Value
  final case class  ForeignV(h: AnyRef)              extends Value

// ── Reader: canonical S-expr → Core IR (specs/12-ir-format.md) ───────────────
// Lenient on whitespace and `;` comments; the canonical writer (later) is strict.

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
    def atEnd: Boolean = pos >= s.length
    def peek: Char = s.charAt(pos)
    def isDelim(c: Char): Boolean = c.isWhitespace || c == '(' || c == ')' || c == ';' || c == '"'
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
      pos += 1 // opening quote
      val sb = new StringBuilder
      var go = true
      while go do
        if atEnd then sys.error("unterminated string")
        val c = peek; pos += 1
        if c == '"' then go = false
        else if c == '\\' then
          if atEnd then sys.error("dangling escape")
          val e = peek; pos += 1
          e match
            case '"'  => sb += '"'
            case '\\' => sb += '\\'
            case 'n'  => sb += '\n'
            case 'r'  => sb += '\r'
            case 't'  => sb += '\t'
            case 'u'  => sb += Integer.parseInt(s.substring(pos, pos + 4), 16).toChar; pos += 4
            case o    => sys.error(s"bad escape \\$o")
        else sb += c
      sb.toString

  // Sx → AST
  def toProgram(sx: Sx): Program = sx match
    case Sx.Lst(Sx.Atom("program") :: defsSx :: entrySx :: Nil) =>
      val defs = defsSx match
        case Sx.Lst(Sx.Atom("defs") :: ds) => ds.map(toDef)
        case _ => sys.error("expected (defs ...)")
      val entry = entrySx match
        case Sx.Lst(Sx.Atom("entry") :: t :: Nil) => toTerm(t)
        case _ => sys.error("expected (entry <term>)")
      Program(defs, entry)
    case _ => sys.error("expected (program <defs> <entry>)")

  def toDef(sx: Sx): Def = sx match
    case Sx.Lst(Sx.Atom("def") :: Sx.Atom(name) :: body :: Nil) => Def(name, toTerm(body))
    case _ => sys.error(s"bad def: $sx")

  def toArm(sx: Sx): Arm = sx match
    case Sx.Lst(Sx.Atom("arm") :: Sx.Atom(tag) :: Sx.Atom(ar) :: body :: Nil) =>
      Arm(tag, ar.toInt, toTerm(body))
    case _ => sys.error(s"bad arm: $sx")

  def toTerm(sx: Sx): Term = sx match
    case Sx.Lst(Sx.Atom(head) :: rest) => head match
      case "lit"    => Term.Lit(toConst(rest))
      case "local"  => rest match { case Sx.Atom(i) :: Nil => Term.Local(i.toInt); case _ => sys.error("bad local") }
      case "global" => rest match { case Sx.Atom(n) :: Nil => Term.Global(n);      case _ => sys.error("bad global") }
      case "lam"    => rest match { case Sx.Atom(a) :: b :: Nil => Term.Lam(a.toInt, toTerm(b)); case _ => sys.error("bad lam") }
      case "app"    => rest match { case fn :: args => Term.App(toTerm(fn), args.map(toTerm)); case _ => sys.error("bad app") }
      case "let"    => rest match { case Sx.Lst(r) :: b :: Nil => Term.Let(r.map(toTerm), toTerm(b)); case _ => sys.error("bad let") }
      case "letrec" => rest match { case Sx.Lst(l) :: b :: Nil => Term.LetRec(l.map(toTerm), toTerm(b)); case _ => sys.error("bad letrec") }
      case "if"     => rest match { case c :: t :: e :: Nil => Term.If(toTerm(c), toTerm(t), toTerm(e)); case _ => sys.error("bad if") }
      case "ctor"   => rest match { case Sx.Atom(tag) :: fs => Term.Ctor(tag, fs.map(toTerm)); case _ => sys.error("bad ctor") }
      case "prim"   => rest match { case Sx.Atom(op) :: as => Term.Prim(op, as.map(toTerm)); case _ => sys.error("bad prim") }
      case "match"  => rest match
        case scrut :: Sx.Lst(arms) :: tail =>
          val default = tail match
            case Nil => None
            case Sx.Lst(Sx.Atom("default") :: d :: Nil) :: Nil => Some(toTerm(d))
            case _ => sys.error("bad match default")
          Term.Match(toTerm(scrut), arms.map(toArm), default)
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
    case "nan" => Double.NaN
    case "inf" => Double.PositiveInfinity
    case "-inf" => Double.NegativeInfinity
    case _ => x.toDouble

  def parseHex(h: String): Vector[Byte] =
    h.grouped(2).map(p => Integer.parseInt(p, 16).toByte).toVector

// ── Evaluator (specs/10-core-ir.md §4) with TCO trampoline (invariant 7) ─────

object Interp:
  import Value.*, Term.*

  def run(p: Program): Value =
    val globals = collection.mutable.HashMap[String, Value]()
    val it = new It(globals)
    // pass 1: lambda defs become closures (env = Nil; recursion goes via Global)
    for d <- p.defs do d.body match
      case Lam(ar, b) => globals(d.name) = ClosV(Nil, ar, b)
      case _ => ()
    // pass 2: value defs (may reference the lambda globals above)
    for d <- p.defs do d.body match
      case Lam(_, _) => ()
      case other => globals(d.name) = it.eval(other, Nil)
    it.eval(p.entry, Nil)

  final class It(globals: collection.mutable.Map[String, Value]):
    def eval(t0: Term, env0: Env): Value =
      var term = t0
      var env  = env0
      while true do
        term match
          case Lit(c)      => return constV(c)
          case Local(i)    => return env(i)
          case Global(g)   => return globals.getOrElse(g, sys.error(s"unbound global: $g"))
          case Lam(ar, b)  => return ClosV(env, ar, b)
          case Ctor(t, fs) => return DataV(t, fs.map(f => eval(f, env)).toVector)
          case Prim(op, a) => return Prims(op, a.map(x => eval(x, env)))

          case If(c, th, el) =>                               // tail: branch loops
            eval(c, env) match
              case BoolV(true)  => term = th
              case BoolV(false) => term = el
              case v => sys.error(s"if: condition not a Bool: ${Show.show(v)}")

          case Let(rhs, body) =>                              // sequential; body is tail
            var e = env
            for r <- rhs do e = eval(r, e) :: e
            env = e; term = body

          case LetRec(lams, body) =>                          // cyclic frame; body is tail
            val cs = lams.map {
              case Lam(ar, b) => ClosV(Nil, ar, b)
              case _ => sys.error("letrec binding must be a lam")
            }
            val envP = cs.reverse ::: env                     // last binding = index 0
            cs.foreach(_.env = envP)
            env = envP; term = body

          case Match(scrut, arms, default) =>                 // arm body is tail
            eval(scrut, env) match
              case DataV(tag, fs) =>
                arms.find(a => a.tag == tag && a.arity == fs.length) match
                  case Some(a) => env = fs.reverse.toList ::: env; term = a.body
                  case None => default match
                    case Some(d) => term = d
                    case None => sys.error(s"match: no arm for $tag/${fs.length}")
              case v => sys.error(s"match: scrutinee not Data: ${Show.show(v)}")

          case App(fn, args) =>                               // THE tail call
            val fv  = eval(fn, env)
            val avs = args.map(a => eval(a, env))
            fv match
              case c: ClosV =>
                if c.arity != avs.length then sys.error(s"arity: ${c.arity} expected, ${avs.length} given")
                env = avs.reverse ::: c.env                   // last arg = index 0; loop, no stack growth
                term = c.body
              case v => sys.error(s"app: not a function: ${Show.show(v)}")
      end while
      sys.error("unreachable")

    def constV(c: Const): Value = c match
      case Const.CUnit     => UnitV
      case Const.CBool(b)  => BoolV(b)
      case Const.CInt(n)   => IntV(n)
      case Const.CBig(n)   => BigV(n)
      case Const.CFloat(d) => FloatV(d)
      case Const.CStr(s)   => StrV(s)
      case Const.CBytes(b) => BytesV(b)

// ── Primitives δ (specs/10-core-ir.md §5) — minimal set; widen as needed ─────

object Prims:
  import Value.*
  def apply(op: String, args: List[Value]): Value = op match
    case "i.add" => IntV(i(args, 0) + i(args, 1))
    case "i.sub" => IntV(i(args, 0) - i(args, 1))
    case "i.mul" => IntV(i(args, 0) * i(args, 1))
    case "i.div" => IntV(i(args, 0) / i(args, 1))
    case "i.mod" => IntV(i(args, 0) % i(args, 1))
    case "i.neg" => IntV(-i(args, 0))
    case "i.and" => IntV(i(args, 0) & i(args, 1))
    case "i.or"  => IntV(i(args, 0) | i(args, 1))
    case "i.xor" => IntV(i(args, 0) ^ i(args, 1))
    case "i.not" => IntV(~i(args, 0))
    case "i.shl" => IntV(i(args, 0) << i(args, 1))
    case "i.shr" => IntV(i(args, 0) >> i(args, 1))
    case "i.ushr"=> IntV(i(args, 0) >>> i(args, 1))
    case "i.eq"  => BoolV(i(args, 0) == i(args, 1))
    case "i.lt"  => BoolV(i(args, 0) <  i(args, 1))
    case "i.le"  => BoolV(i(args, 0) <= i(args, 1))
    case "i.gt"  => BoolV(i(args, 0) >  i(args, 1))
    case "i.ge"  => BoolV(i(args, 0) >= i(args, 1))
    case "not"   => BoolV(!b(args, 0))
    case "io.print"  => out(args(0), Console.out); UnitV
    case "io.eprint" => out(args(0), Console.err); UnitV
    case _ => sys.error(s"unimplemented primitive: $op")

  private def i(a: List[Value], k: Int): Long = a(k) match
    case IntV(n) => n
    case v => sys.error(s"expected Int, got ${Show.show(v)}")
  private def b(a: List[Value], k: Int): Boolean = a(k) match
    case BoolV(x) => x
    case v => sys.error(s"expected Bool, got ${Show.show(v)}")
  private def out(v: Value, ps: java.io.PrintStream): Unit = v match
    case StrV(s) => ps.print(s)
    case _ => ps.print(Show.show(v))

// ── Value rendering (data shape; exact text not part of conformance) ─────────

object Show:
  import Value.*
  def show(v: Value): String = v match
    case UnitV        => "()"
    case BoolV(x)     => x.toString
    case IntV(n)      => n.toString
    case BigV(n)      => n.toString
    case FloatV(d)    => d.toString
    case StrV(s)      => "\"" + s + "\""
    case BytesV(bs)   => bs.map(x => f"${x & 0xff}%02x").mkString("#", "", "")
    case DataV(t, fs) => if fs.isEmpty then t else s"$t(${fs.map(show).mkString(", ")})"
    case _: ClosV     => "<closure>"
    case ForeignV(_)  => "<foreign>"

// ── CLI: ssc2 run <file.coreir> ──────────────────────────────────────────────

@main def ssc2(args: String*): Unit = args.toList match
  case "run" :: file :: Nil =>
    val src = scala.io.Source.fromFile(file)(using scala.io.Codec.UTF8).mkString
    println(Show.show(Interp.run(Reader.parseProgram(src))))
  case _ =>
    Console.err.println("usage: ssc2 run <file.coreir>")
    sys.exit(2)
