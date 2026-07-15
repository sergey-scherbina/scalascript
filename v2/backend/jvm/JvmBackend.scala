package ssc.backend.jvm

// v2 JVM backend: Core IR (text S-expr) → self-contained Scala 3 source file.
// The generated file, when compiled with `scalac` and run with `scala`, produces
// the same output as `ssc run-ir` on the same Core IR program.
//
// Usage: java -jar <generator.jar> [<file.coreir>]
//   If no file given, reads Core IR from stdin.
//   Output: Scala 3 source to stdout.
//
// The generated file is self-contained: it embeds the runtime preamble and the
// compiled user code in a single @main function.

// ── Core IR AST (copied from v2/src/CoreIR.scala) ────────────────────────────

enum Const:
  case CUnit
  case CBool(b: Boolean)
  case CInt(n: Long)
  case CBig(n: BigInt)
  case CFloat(d: Double)
  case CStr(s: String)
  case CBytes(b: Vector[Byte])

enum Term:
  case Lit(c: Const)
  case Local(i: Int)
  case Global(name: String)
  case Lam(arity: Int, body: Term)
  case App(fn: Term, args: List[Term])
  case Let(rhs: List[Term], body: Term)
  case LetRec(lams: List[Term], body: Term)
  case If(c: Term, t: Term, e: Term)
  case Ctor(tag: String, fields: List[Term])
  case Match(scrut: Term, arms: List[Arm], default: Option[Term])
  case Prim(op: String, args: List[Term])
  case While(cond: Term, body: Term)
  case Seq(terms: List[Term])

case class Arm(tag: String, arity: Int, body: Term)
case class Def(name: String, body: Term)
case class Program(defs: List[Def], entry: Term)

// ── S-expression reader (same as CoreIR.scala) ───────────────────────────────

enum Sx:
  case Atom(s: String)
  case Str(s: String)
  case Lst(xs: List[Sx])

object Reader:
  def parseProgram(src: String): Program = toProgram(parseOne(src))

  def parseOne(src: String): Sx =
    val p = new P(src)
    p.skipWs(); val sx = p.sexpr(); p.skipWs()
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
            case '"'  => sb += '"'
            case '\\' => sb += '\\'
            case 'n'  => sb += '\n'
            case 'r'  => sb += '\r'
            case 't'  => sb += '\t'
            case 'u'  =>
              sb += Integer.parseInt(s.substring(pos, pos + 4), 16).toChar; pos += 4
            case o => sys.error(s"bad escape \\$o")
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
    case Sx.Lst(Sx.Atom("arm") :: Sx.Atom(t) :: Sx.Atom(a) :: b :: Nil) =>
      Arm(t, a.toInt, toTerm(b))
    case _ => sys.error(s"bad arm: $sx")

  def toTerm(sx: Sx): Term = sx match
    case Sx.Lst(Sx.Atom(h) :: rest) => h match
      case "lit"    => Term.Lit(toConst(rest))
      case "local"  => rest match
        case Sx.Atom(i) :: Nil => Term.Local(i.toInt)
        case _ => sys.error("bad local")
      case "global" => rest match
        case Sx.Atom(n) :: Nil => Term.Global(n)
        case _ => sys.error("bad global")
      case "lam"    => rest match
        case Sx.Atom(a) :: b :: Nil => Term.Lam(a.toInt, toTerm(b))
        case _ => sys.error("bad lam")
      case "app"    => rest match
        case fn :: as => Term.App(toTerm(fn), as.map(toTerm))
        case _ => sys.error("bad app")
      case "let"    => rest match
        case Sx.Lst(r) :: b :: Nil => Term.Let(r.map(toTerm), toTerm(b))
        case _ => sys.error("bad let")
      case "letrec" => rest match
        case Sx.Lst(l) :: b :: Nil => Term.LetRec(l.map(toTerm), toTerm(b))
        case _ => sys.error("bad letrec")
      case "if"     => rest match
        case c :: t :: e :: Nil => Term.If(toTerm(c), toTerm(t), toTerm(e))
        case _ => sys.error("bad if")
      case "ctor"   => rest match
        case Sx.Atom(t) :: fs => Term.Ctor(t, fs.map(toTerm))
        case _ => sys.error("bad ctor")
      case "prim"   => rest match
        case Sx.Atom(o) :: as => Term.Prim(o, as.map(toTerm))
        case _ => sys.error("bad prim")
      case "while"  => rest match
        case c :: b :: Nil => Term.While(toTerm(c), toTerm(b))
        case _ => sys.error("bad while")
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
    case Sx.Lst(Sx.Atom("bytes") :: Sx.Atom(h) :: Nil) :: Nil =>
      Const.CBytes(h.grouped(2).map(p => Integer.parseInt(p, 16).toByte).toVector)
    case Sx.Lst(Sx.Atom("bytes") :: Nil) :: Nil => Const.CBytes(Vector.empty)
    case _ => sys.error(s"bad const: $rest")

  def parseFloat(x: String): Double = x match
    case "nan"  => Double.NaN
    case "inf"  => Double.PositiveInfinity
    case "-inf" => Double.NegativeInfinity
    case _      => x.toDouble

// ── Code generator ────────────────────────────────────────────────────────────

object CodeGen:
  import Term.*

  // Mutable counter for fresh variable names (thread-local to the generator invocation).
  private var counter = 0
  private var longGlobalDefs: Map[String, Int] = Map.empty
  private def fresh(): Int = { val n = counter; counter += 1; n }

  // Sanitize a Def name to a valid Scala identifier.
  private def safeName(s: String): String =
    val base = s.replace(".", "_").replace("-", "_").replace("@", "_at_").replace("?", "_q_")
                .replace("!", "_bang_").replace("'", "_p_")
    // In Scala 3, `name_:` is parsed as if `:` is an operator — add a trailing digit to avoid.
    if base.endsWith("_") then base + "x" else base

  // ── Runtime preamble embedded in every generated file ────────────────────────
  private val preamble: String = """
import scala.collection.mutable

type V = Any
type Fn = Array[V] => V
private final case class _TcoJump(fid: Int, args: Array[V])

// ADT helper: (tag, fields) tuple
private def _mkAdt(tag: String, fields: Array[V]): V = (tag, fields)
private def _adtTag(v: V): String   = v.asInstanceOf[(String, Array[V])]._1
private def _adtFields(v: V): Array[V] = v.asInstanceOf[(String, Array[V])]._2
private def _adtField(v: V, i: Int): V = v.asInstanceOf[(String, Array[V])]._2(i)

// Long coercion for Long-cell specialization sites (genTerm emits bare _asLong
// at top level; R._asLong is private to R)
private def _asLong(v: V): Long = v match
  case n: Long   => n
  case b: Byte   => b.toLong
  case x => throw new RuntimeException(s"expected Long, got $x")

// Closure call helpers
private def _call(fn: V, args: Array[V]): V = fn.asInstanceOf[Fn](args)
private def _call0(fn: V): V = fn.asInstanceOf[Fn](Array.empty)
private def _call1(fn: V, a: V): V = fn.asInstanceOf[Fn](Array(a))
private def _call2(fn: V, a: V, b: V): V = fn.asInstanceOf[Fn](Array(a, b))
private def _call3(fn: V, a: V, b: V, c: V): V = fn.asInstanceOf[Fn](Array(a, b, c))

// Show: convert a Value to a string (for display, not serialization)
private def _show(v: V): String = v match
  case ()         => "()"
  case b: Boolean => b.toString
  case n: Long    => n.toString
  case d: Double  => _showDouble(d)
  case s: String  => s
  case bi: BigInt => bi.toString
  case (tag: String, fields: Array[V]) =>
    // VM anyStr semantics: Cons/Nil chains render as List(…)
    if tag == "Cons" || tag == "Nil" then
      val items = scala.collection.mutable.ListBuffer.empty[String]
      var cur: V = (tag, fields)
      var go = true
      while go do cur match
        case ("Cons", fs: Array[V]) => items += _show(fs(0)); cur = fs(1)
        case _ => go = false
      s"List(${items.mkString(", ")})"
    else if fields.isEmpty then tag else s"$tag(${fields.map(_show).mkString(", ")})"
  case v: Vector[?] => v.asInstanceOf[Vector[Byte]].map(b => f"${b & 0xff}%02x").mkString("#", "", "")
  case _: Function1[?, ?] => "<closure>"
  case _ => v.toString

private def _showDouble(d: Double): String =
  // VM floatStr semantics (CoreIR.Writer): whole doubles collapse to the
  // integer form ("3", not "3.0"); nan/inf are lowercase.
  if d.isNaN then "nan"
  else if d == Double.PositiveInfinity then "inf"
  else if d == Double.NegativeInfinity then "-inf"
  else if d == d.toLong.toDouble then d.toLong.toString
  else d.toString

// ADT list helpers (Cons/Nil encoding used by many primitives)
private val _None: V = ("None", Array.empty[V])
private def _Some(v: V): V = ("Some", Array[V](v))
private def _Nil: V = ("Nil", Array.empty[V])
private def _Cons(h: V, t: V): V = ("Cons", Array[V](h, t))
private def _strList(xs: Seq[String]): V = xs.foldRight(_Nil)((s, acc) => _Cons(s, acc))
private def _valList(vs: Seq[V]): V = vs.foldRight(_Nil)((v, acc) => _Cons(v, acc))
private def _unlist(v: V): List[V] =
  var cur = v; val buf = scala.collection.mutable.ListBuffer[V]()
  while cur.asInstanceOf[(String, Array[V])]._1 == "Cons" do
    val f = cur.asInstanceOf[(String, Array[V])]._2
    buf += f(0); cur = f(1)
  buf.toList

// Runtime object: all primitives
object R:
  private def _asLong(v: V): Long = v match
    case n: Long   => n
    case b: Byte   => b.toLong
    case x => throw new RuntimeException(s"expected Long, got $x")
  private def _asDouble(v: V): Double = v match
    case d: Double => d
    case n: Long   => n.toDouble
    case x => throw new RuntimeException(s"expected Double, got $x")
  private def _asStr(v: V): String = v match
    case s: String => s
    case x => throw new RuntimeException(s"expected String, got $x")
  private def _asBool(v: V): Boolean = v match
    case b: Boolean => b
    case x => throw new RuntimeException(s"expected Boolean, got $x")
  private def _asBig(v: V): BigInt = v match
    case n: BigInt => n
    case n: Long   => BigInt(n)
    case x => throw new RuntimeException(s"expected BigInt, got $x")
  private def _asBytes(v: V): Vector[Byte] = v.asInstanceOf[Vector[Byte]]
  private def _asMap(v: V) = v.asInstanceOf[mutable.HashMap[V, V]]
  private def _asArr(v: V) = v.asInstanceOf[mutable.ArrayBuffer[V]]
  private def _asCell(v: V) = v.asInstanceOf[Array[V]]
  private def _asLCell(v: V) = v.asInstanceOf[Array[Long]]

  private def _numBinI(a: V, b: V, fi: (Long,Long) => Long, ff: (Double,Double) => Double): V =
    (a, b) match
      case (x: Long, y: Long)     => fi(x, y)
      case (x: Double, y: Double) => ff(x, y)
      case (x: Double, y: Long)   => ff(x, y.toDouble)
      case (x: Long, y: Double)   => ff(x.toDouble, y)
      case _ => fi(_asLong(a), _asLong(b))

  private def _numCmpI(a: V, b: V, fi: (Long,Long) => Boolean, ff: (Double,Double) => Boolean): V =
    (a, b) match
      case (x: Long, y: Long)     => fi(x, y)
      case (x: Double, y: Double) => ff(x, y)
      case (x: Double, y: Long)   => ff(x, y.toDouble)
      case (x: Long, y: Double)   => ff(x.toDouble, y)
      case _ => fi(_asLong(a), _asLong(b))

  private def _out(v: V, ps: java.io.PrintStream): Unit = v match
    case s: String => ps.print(s)
    case _ => ps.print(_show(v))

  private def _isSystem(v: V): Boolean = v match
    case ("System", _: Array[V]) => true
    case _                       => false

  private def _foreach(v: V, fn: V): Unit =
    v match
      case ("Nil", _: Array[V]) => ()
      case ("Cons", _: Array[V]) =>
        var cur = v
        var go = true
        while go do cur match
          case ("Cons", fs: Array[V]) =>
            _call1(fn, fs(0))
            cur = fs(1)
          case ("Nil", _: Array[V]) => go = false
          case other => throw new RuntimeException(s"foreach: expected list, got ${_show(other)}")
      case buf: mutable.ArrayBuffer[?] =>
        buf.asInstanceOf[mutable.ArrayBuffer[V]].foreach(a => _call1(fn, a))
      case other => throw new RuntimeException(s"foreach: expected list/array, got ${_show(other)}")

  private def _method(name: String, recv: V, args: Array[V]): V = name match
    case "nanoTime" if args.isEmpty && _isSystem(recv) => System.nanoTime()
    case "toDouble" if args.isEmpty => recv match
      case d: Double => d
      case n: Long   => n.toDouble
      case s: String => s.toDoubleOption.getOrElse(0.0d)
      case _         => 0.0d
    case "toLong" | "toInt" if args.isEmpty => recv match
      case n: Long   => n
      case d: Double => d.toLong
      case s: String => s.toLongOption.getOrElse(0L)
      case _         => 0L
    case "toString" if args.isEmpty => _show(recv)
    case "foreach" if args.length == 1 => _foreach(recv, args(0)); ()
    case _ => throw new RuntimeException(s"__method__: no dispatch for .$name on ${_show(recv)}")

  // argv set before v2main is called (set via R.argv = args)
  var argv: List[String] = Nil

  def prim1(op: String, a0: V): V = op match
    case "__autoPrint__" => ()
    case "i.neg"    => a0 match { case d: Double => -d; case v => -_asLong(v) }
    case "i.not"    => ~_asLong(a0)
    case "not"      => !_asBool(a0)
    case "slen"     => _asStr(a0).length.toLong
    case "str.trim" => _asStr(a0).trim
    case "str.lines"=> val s = _asStr(a0); val parts = s.split("\n", -1)
                       parts.foldRight(_Nil)((x, acc) => _Cons(x, acc))
    case "str->utf8"=> _asStr(a0).getBytes("UTF-8").toVector
    case "utf8->str"=> new String(_asBytes(a0).toArray, "UTF-8")
    case "i->f"     => _asLong(a0).toDouble
    case "f->i"     => _asDouble(a0).toLong
    case "i->big"   => BigInt(_asLong(a0))
    case "big->i"   => _asBig(a0).toLong
    case "i->str"   => _asLong(a0).toString
    case "f->str"   => _showDouble(_asDouble(a0))
    case "big->str" => _asBig(a0).toString
    case "f.sqrt"   => math.sqrt(_asDouble(a0))
    case "f.floor"  => math.floor(_asDouble(a0))
    case "f.ceil"   => math.ceil(_asDouble(a0))
    case "f.round"  => math.rint(_asDouble(a0))
    case "f.trunc"  => _asDouble(a0).toLong.toDouble
    case "f.neg"    => -_asDouble(a0)
    case "f.isNaN"  => _asDouble(a0).isNaN
    case "f.isInf"  => _asDouble(a0).isInfinite
    case "tagOf"    => _adtTag(a0)
    case "arity"    => _adtFields(a0).length.toLong
    case "blen"     => _asBytes(a0).length.toLong
    case "cell.new" => Array[V](a0)
    case "cell.get" => _asCell(a0)(0)
    case "lcell.new"=> Array[Long](_asLong(a0))
    case "lcell.get"=> _asLCell(a0)(0)
    case "arr.new"  => mutable.ArrayBuffer[V]()
    case "arr.len"  => _asArr(a0).length.toLong
    case "arr.pop"  => val buf = _asArr(a0); buf.remove(buf.length - 1)
    case "map.new"  => mutable.HashMap[V, V]()
    case "map.size" => _asMap(a0).size.toLong
    case "map.keys" => _valList(_asMap(a0).keys.toSeq)
    case "__handler_dispatch_selected__" => ()
    case "__handler_dispatch_miss__" => throw new RuntimeException("match: no matching case")
    case "io.args"  => _strList(argv)
    case "io.print" => _out(a0, Console.out); ()
    case "io.println"=> _out(a0, Console.out); Console.out.println(); ()
    case "io.eprint"=> _out(a0, Console.err); ()
    case "big->f"   => _asBig(a0).toDouble
    case "str->i"   => _asStr(a0).toLongOption.map(_Some(_)).getOrElse(_None)
    case "str->big" => scala.util.Try(BigInt(_asStr(a0))).toOption.map(_Some(_)).getOrElse(_None)
    case "str->f"   => _asStr(a0).toDoubleOption.map(_Some(_)).getOrElse(_None)
    case "big.neg"  => -_asBig(a0)
    case _ => throw new RuntimeException(s"unknown prim1: $op")

  def prim2(op: String, a0: V, a1: V): V = op match
    case "i.add"    => _numBinI(a0, a1, _ + _, _ + _)
    case "i.sub"    => _numBinI(a0, a1, _ - _, _ - _)
    case "i.mul"    => _numBinI(a0, a1, _ * _, _ * _)
    case "i.div"    => _numBinI(a0, a1, _ / _, _ / _)
    case "i.mod"    => _numBinI(a0, a1, _ % _, _ % _)
    case "i.and"    => _asLong(a0) & _asLong(a1)
    case "i.or"     => _asLong(a0) | _asLong(a1)
    case "i.xor"    => _asLong(a0) ^ _asLong(a1)
    case "i.shl"    => _asLong(a0) << _asLong(a1)
    case "i.shr"    => _asLong(a0) >> _asLong(a1)
    case "i.ushr"   => _asLong(a0) >>> _asLong(a1)
    case "i.eq"     => _numCmpI(a0, a1, _ == _, _ == _)
    case "i.lt"     => _numCmpI(a0, a1, _ < _,  _ < _)
    case "i.le"     => _numCmpI(a0, a1, _ <= _, _ <= _)
    case "i.gt"     => _numCmpI(a0, a1, _ > _,  _ > _)
    case "i.ge"     => _numCmpI(a0, a1, _ >= _, _ >= _)
    case "f.add"    => _asDouble(a0) + _asDouble(a1)
    case "f.sub"    => _asDouble(a0) - _asDouble(a1)
    case "f.mul"    => _asDouble(a0) * _asDouble(a1)
    case "f.div"    => _asDouble(a0) / _asDouble(a1)
    case "f.eq"     => _asDouble(a0) == _asDouble(a1)
    case "f.lt"     => _asDouble(a0) <  _asDouble(a1)
    case "f.le"     => _asDouble(a0) <= _asDouble(a1)
    case "f.gt"     => _asDouble(a0) >  _asDouble(a1)
    case "f.ge"     => _asDouble(a0) >= _asDouble(a1)
    case "sconcat"  => (a0, a1) match
                         case (s1: String, s2: String) => s1 + s2
                         case ((t1: String, f1: Array[V]), (t2: String, f2: Array[V])) =>
                           val n = f1.length + f2.length; (s"Tuple$n", (f1 ++ f2).asInstanceOf[Array[V]])
                         case _ => _asStr(a0) + _asStr(a1)
    case "seq"      => _asStr(a0) == _asStr(a1)
    case "scmp"     => _asStr(a0).compareTo(_asStr(a1)).toLong
    case "sindexOf" => _asStr(a0).indexOf(_asStr(a1)).toLong
    case "sslice"   => _asStr(a0).substring(_asLong(a1).toInt)  // fallback: no end
    case "scodeAt"  => _asStr(a0).charAt(_asLong(a1).toInt).toLong
    case "str.split"=> val parts = _asStr(a0).split(java.util.regex.Pattern.quote(_asStr(a1)), -1)
                       parts.foldRight(_Nil)((s, acc) => _Cons(s, acc))
    case "bconcat"  => _asBytes(a0) ++ _asBytes(a1)
    case "bget"     => (_asBytes(a0)(_asLong(a1).toInt) & 0xff).toLong
    case "fieldAt"  => _adtField(a0, _asLong(a1).toInt)
    case "cell.set" => _asCell(a0)(0) = a1; ()
    case "lcell.set"=> _asLCell(a0)(0) = _asLong(a1); ()
    case "arr.get"  => _asArr(a0)(_asLong(a1).toInt)
    case "arr.push" => _asArr(a0) += a1; ()
    case "map.get"  => _asMap(a0).get(a1).map(_Some(_)).getOrElse(_None)
    case "map.has"  => _asMap(a0).contains(a1)
    case "map.del"  => _asMap(a0).remove(a1); ()
    case "big.add"  => _asBig(a0) + _asBig(a1)
    case "big.sub"  => _asBig(a0) - _asBig(a1)
    case "big.mul"  => _asBig(a0) * _asBig(a1)
    case "big.div"  => _asBig(a0) / _asBig(a1)
    case "big.mod"  => _asBig(a0) % _asBig(a1)
    case "big.eq"   => _asBig(a0) == _asBig(a1)
    case "big.lt"   => _asBig(a0) <  _asBig(a1)
    case "big.le"   => _asBig(a0) <= _asBig(a1)
    case "big.gt"   => _asBig(a0) >  _asBig(a1)
    case "big.ge"   => _asBig(a0) >= _asBig(a1)
    case "io.writeFile" => java.nio.file.Files.write(java.nio.file.Path.of(_asStr(a0)), _asBytes(a1).toArray); ()
    case "io.env"   => sys.env.get(_asStr(a0)).map(_Some(_)).getOrElse(_None)
    case "io.readFile" => java.nio.file.Files.readAllBytes(java.nio.file.Path.of(_asStr(a0))).toVector
    case "global.reg" => ()
    case "__method__" => _method(_asStr(a0), a1, Array.empty[V])
    case _ => throw new RuntimeException(s"unknown prim2: $op")

  // Bridge-generated arithmetic: prim3("__arith__", op, left, right)
  private def _arith(op: V, l: V, r: V): V = (l, r) match
    case (x: Long, y: Long) => (op: @unchecked) match
      case "+"   => x + y;   case "-"   => x - y;   case "*"   => x * y
      case "/"   => x / y;   case "%"   => x % y
      case "=="  => x == y;  case "!="  => x != y
      case "<"   => x < y;   case "<="  => x <= y;  case ">"   => x > y;  case ">="  => x >= y
      case "&"   => x & y;   case "|"   => x | y;   case "^"   => x ^ y
      case "<<"  => x << y.toInt; case ">>" => x >> y.toInt; case ">>>" => x >>> y.toInt
      case "++"  => x.toString + y.toString
      case s => throw new RuntimeException(s"__arith__ Int×Int unknown op: $s")
    case (x: Double, y: Double) => (op: @unchecked) match
      case "+"  => x + y;  case "-" => x - y;  case "*" => x * y;  case "/" => x / y; case "%" => x % y
      case "==" => x == y; case "!=" => x != y; case "<" => x < y; case "<=" => x <= y; case ">" => x > y; case ">=" => x >= y
      case "++" => x.toString + y.toString
      case s => throw new RuntimeException(s"__arith__ Double×Double unknown op: $s")
    case (x: Long, y: Double) => (op: @unchecked) match
      case "+"  => x + y;  case "-" => x - y;  case "*" => x * y;  case "/" => x / y; case "%" => x % y
      case "==" => x == y; case "!=" => x != y; case "<" => x < y; case "<=" => x <= y; case ">" => x > y; case ">=" => x >= y
      case s => throw new RuntimeException(s"__arith__ Long×Double unknown op: $s")
    case (x: Double, y: Long) => (op: @unchecked) match
      case "+"  => x + y;  case "-" => x - y;  case "*" => x * y;  case "/" => x / y; case "%" => x % y
      case "==" => x == y; case "!=" => x != y; case "<" => x < y; case "<=" => x <= y; case ">" => x > y; case ">=" => x >= y
      case s => throw new RuntimeException(s"__arith__ Double×Long unknown op: $s")
    case (x: String, y: String) => (op: @unchecked) match
      case "++" | "+" => x + y
      case "==" => x == y; case "!=" => x != y; case "<" => x < y; case "<=" => x <= y; case ">" => x > y; case ">=" => x >= y
      case s => throw new RuntimeException(s"__arith__ Str×Str unknown op: $s")
    case _ =>
      val opS = op.asInstanceOf[String]
      if opS == "++" || opS == "+" then _show(l) + _show(r)
      else throw new RuntimeException(s"__arith__ $opS: unsupported types ${l.getClass.getSimpleName}×${r.getClass.getSimpleName}")

  def prim3(op: String, a0: V, a1: V, a2: V): V = op match
    case "__arith__" => _arith(a0, a1, a2)
    case "__method__" => _method(_asStr(a0), a1, Array[V](a2))
    case "sslice"   => _asStr(a0).substring(_asLong(a1).toInt, _asLong(a2).toInt)
    case "map.put"  => _asMap(a0).update(a1, a2); ()
    case "arr.set"  => _asArr(a0)(_asLong(a1).toInt) = a2; ()
    case "bslice"   => _asBytes(a0).slice(_asLong(a1).toInt, _asLong(a2).toInt)
    case _ => throw new RuntimeException(s"unknown prim3: $op")

  def primN(op: String, args: Array[V]): V = op match
    case "io.args"  => _strList(argv)
    case "io.nanoTime" => System.nanoTime()
    case "__method__" if args.length >= 2 => _method(_asStr(args(0)), args(1), args.drop(2))
    case "map.new"  => mutable.HashMap[V, V]()
    case "arr.new"  => mutable.ArrayBuffer[V]()
    case "sfromCodes" => String(_unlist(args(0)).map(v => _asLong(v).toChar).toArray)
    case "io.exit"  => sys.exit(args(0).asInstanceOf[Long].toInt); ()
    case _ => if args.length == 1 then prim1(op, args(0))
              else if args.length == 2 then prim2(op, args(0), args(1))
              else if args.length == 3 then prim3(op, args(0), args(1), args(2))
              else throw new RuntimeException(s"unknown primN[$${args.length}]: $op")
"""

  // ── Tail-call detection ───────────────────────────────────────────────────────

  // Returns the list of "is-in-tail-position" flags for every App(Global(name), ...)
  // occurrence in `t`, given that `t` itself is in tail position iff `isTail`.
  // Used to decide whether @tailrec is safe for a global def named `name`.
  private def globalTailPositions(name: String, t: Term, isTail: Boolean): List[Boolean] =
    import Term.*
    t match
      case App(Global(n), args) if n == name =>
        List(isTail) ++ args.flatMap(a => globalTailPositions(name, a, false))
      case App(fn, args) =>
        globalTailPositions(name, fn, false) ++ args.flatMap(a => globalTailPositions(name, a, false))
      case Global(n) if n == name => List(isTail)
      case Global(_) | Lit(_) | Local(_) => Nil
      case Lam(_, body) => globalTailPositions(name, body, false) // new scope
      case If(c, th, el) =>
        globalTailPositions(name, c, false) ++
        globalTailPositions(name, th, isTail) ++
        globalTailPositions(name, el, isTail)
      case Let(rhs, body) =>
        rhs.flatMap(r => globalTailPositions(name, r, false)) ++
        globalTailPositions(name, body, isTail)
      case LetRec(lams, body) =>
        lams.flatMap(l => globalTailPositions(name, l, false)) ++
        globalTailPositions(name, body, isTail)
      case Seq(ts) if ts.nonEmpty =>
        ts.init.flatMap(t2 => globalTailPositions(name, t2, false)) ++
        globalTailPositions(name, ts.last, isTail)
      case Seq(_) => Nil
      case Match(scrut, arms, default) =>
        globalTailPositions(name, scrut, false) ++
        arms.flatMap(a => globalTailPositions(name, a.body, isTail)) ++
        default.toList.flatMap(d => globalTailPositions(name, d, isTail))
      case Prim(_, args) => args.flatMap(a => globalTailPositions(name, a, false))
      case Ctor(_, fields) => fields.flatMap(f => globalTailPositions(name, f, false))
      case While(cond, body) =>
        globalTailPositions(name, cond, false) ++ globalTailPositions(name, body, false)

  // True iff the global def `name` is safely @tailrec:
  // it has at least one self-call and ALL self-calls are in tail position.
  private def isSafeTailRecGlobal(name: String, lamBody: Term): Boolean =
    val positions = globalTailPositions(name, lamBody, true)
    positions.nonEmpty && positions.forall(identity)

  // Same for LetRec single-lam: self-call is App(Local(selfIdx), ...).
  // selfIdx = arity of the lam (params are 0..arity-1, self-ref is at arity).
  private def localTailPositions(selfIdx: Int, t: Term, isTail: Boolean): List[Boolean] =
    import Term.*
    t match
      case App(Local(i), args) if i == selfIdx =>
        List(isTail) ++ args.flatMap(a => localTailPositions(selfIdx, a, false))
      case App(fn, args) =>
        localTailPositions(selfIdx, fn, false) ++ args.flatMap(a => localTailPositions(selfIdx, a, false))
      case Local(_) | Global(_) | Lit(_) => Nil
      case Lam(_, body) => localTailPositions(selfIdx + 1, body, false) // params shift selfIdx
      case If(c, th, el) =>
        localTailPositions(selfIdx, c, false) ++
        localTailPositions(selfIdx, th, isTail) ++
        localTailPositions(selfIdx, el, isTail)
      case Let(rhs, body) =>
        rhs.flatMap(r => localTailPositions(selfIdx, r, false)) ++
        localTailPositions(selfIdx + rhs.length, body, isTail)
      case LetRec(lams, body) =>
        lams.flatMap(l => localTailPositions(selfIdx, l, false)) ++
        localTailPositions(selfIdx + lams.length, body, isTail)
      case Seq(ts) if ts.nonEmpty =>
        ts.init.flatMap(t2 => localTailPositions(selfIdx, t2, false)) ++
        localTailPositions(selfIdx, ts.last, isTail)
      case Seq(_) => Nil
      case Match(scrut, arms, default) =>
        localTailPositions(selfIdx, scrut, false) ++
        arms.flatMap(a => localTailPositions(selfIdx + a.arity, a.body, isTail)) ++
        default.toList.flatMap(d => localTailPositions(selfIdx, d, isTail))
      case Prim(_, args) => args.flatMap(a => localTailPositions(selfIdx, a, false))
      case Ctor(_, fields) => fields.flatMap(f => localTailPositions(selfIdx, f, false))
      case While(cond, body) =>
        localTailPositions(selfIdx, cond, false) ++ localTailPositions(selfIdx, body, false)

  private def isSafeTailRecLocal(selfIdx: Int, lamBody: Term): Boolean =
    val positions = localTailPositions(selfIdx, lamBody, true)
    positions.nonEmpty && positions.forall(identity)

  private final case class GroupCall(offset: Int, argsLen: Int, isTail: Boolean)

  private def localGroupCalls(startIdx: Int, size: Int, t: Term, isTail: Boolean): List[GroupCall] =
    import Term.*
    def inGroup(i: Int): Boolean = i >= startIdx && i < startIdx + size
    t match
      case App(Local(i), args) if inGroup(i) =>
        GroupCall(i - startIdx, args.length, isTail) ::
          args.flatMap(a => localGroupCalls(startIdx, size, a, false))
      case App(fn, args) =>
        localGroupCalls(startIdx, size, fn, false) ++ args.flatMap(a => localGroupCalls(startIdx, size, a, false))
      case Local(_) | Global(_) | Lit(_) => Nil
      case Lam(arity, body) => localGroupCalls(startIdx + arity, size, body, false)
      case If(c, th, el) =>
        localGroupCalls(startIdx, size, c, false) ++
        localGroupCalls(startIdx, size, th, isTail) ++
        localGroupCalls(startIdx, size, el, isTail)
      case Let(rhs, body) =>
        rhs.flatMap(r => localGroupCalls(startIdx, size, r, false)) ++
        localGroupCalls(startIdx + rhs.length, size, body, isTail)
      case LetRec(lams, body) =>
        lams.flatMap {
          case Lam(arity, lamBody) => localGroupCalls(startIdx + arity + lams.length, size, lamBody, false)
          case other              => localGroupCalls(startIdx + lams.length, size, other, false)
        } ++ localGroupCalls(startIdx + lams.length, size, body, isTail)
      case Seq(ts) if ts.nonEmpty =>
        ts.init.flatMap(t2 => localGroupCalls(startIdx, size, t2, false)) ++
        localGroupCalls(startIdx, size, ts.last, isTail)
      case Seq(_) => Nil
      case Match(scrut, arms, default) =>
        localGroupCalls(startIdx, size, scrut, false) ++
        arms.flatMap(a => localGroupCalls(startIdx + a.arity, size, a.body, isTail)) ++
        default.toList.flatMap(d => localGroupCalls(startIdx, size, d, isTail))
      case Prim(_, args) => args.flatMap(a => localGroupCalls(startIdx, size, a, false))
      case Ctor(_, fields) => fields.flatMap(f => localGroupCalls(startIdx, size, f, false))
      case While(cond, body) =>
        localGroupCalls(startIdx, size, cond, false) ++ localGroupCalls(startIdx, size, body, false)

  private def isSafeMutualTailRecGroup(lams: List[Term]): Option[List[(Int, Term)]] =
    if lams.length <= 1 then None
    else
      val infos = lams.map {
        case Lam(arity, body) if arity > 0 => Some((arity, body))
        case _ => None
      }
      if infos.exists(_.isEmpty) then None
      else
        val lamInfos = infos.flatten
        val arities = lamInfos.map(_._1)
        val calls = lamInfos.flatMap { case (arity, body) =>
          localGroupCalls(arity, lams.length, body, true)
        }
        val ok = calls.nonEmpty && calls.forall { call =>
          val targetFid = lams.length - 1 - call.offset
          call.isTail && targetFid >= 0 && targetFid < arities.length && call.argsLen == arities(targetFid)
        }
        if ok then Some(lamInfos) else None

  private def shiftLocalMap[A](m: Map[Int, A], by: Int): Map[Int, A] =
    if by == 0 || m.isEmpty then m else m.map { case (k, v) => (k + by, v) }

  // ── Code generator: Term → Scala 3 expression string ─────────────────────────

  // Returns true if term is statically known to produce a Long (unboxed).
  // Used to decide whether to generate direct Long arithmetic or boxed prim dispatch.
  private def isLongTyped(t: Term, scope: List[String], longVars: Set[String]): Boolean =
    import Term.*
    t match
      case Lit(Const.CInt(_))  => true
      case Local(i) if i < scope.length && longVars.contains(scope(i)) => true
      case Prim("lcell.get", List(Local(i))) if i < scope.length && longVars.contains(scope(i)) => true
      case App(Global(name), args) if longGlobalDefs.get(name).contains(args.length) =>
        args.forall(a => isLongTyped(a, scope, longVars))
      case If(c, th, el) =>
        isBoolTyped(c, scope, longVars) &&
        isLongTyped(th, scope, longVars) &&
        isLongTyped(el, scope, longVars)
      case Prim("__arith__", List(Lit(Const.CStr(op)), l, r)) =>
        val isArith = Set("+", "-", "*", "/", "%", "&", "|", "^", "<<", ">>", ">>>").contains(op)
        isArith && isLongTyped(l, scope, longVars) && isLongTyped(r, scope, longVars)
      case _ => false

  private def isBoolTyped(t: Term, scope: List[String], longVars: Set[String]): Boolean =
    import Term.*
    t match
      case Lit(Const.CBool(_)) => true
      case Prim("__arith__", List(Lit(Const.CStr(op)), l, r)) =>
        val isCmp = Set("==", "!=", "<", "<=", ">", ">=").contains(op)
        isCmp && isLongTyped(l, scope, longVars) && isLongTyped(r, scope, longVars)
      case _ => false

  // Generate a Long-typed expression (assumes isLongTyped returned true, or falls back to _asLong).
  private def genTermAsLong(t: Term, scope: List[String],
                             directDefs: Map[String, Int], directLocals: Map[Int, (String, Int)],
                             longVars: Set[String]): String =
    import Term.*
    t match
      case Lit(Const.CInt(n))  => s"${n}L"
      case Local(i) if i < scope.length && longVars.contains(scope(i)) => scope(i)
      case Prim("lcell.get", List(Local(i))) if i < scope.length && longVars.contains(scope(i)) => scope(i)
      case App(Global(name), args)
           if longGlobalDefs.get(name).contains(args.length) &&
              args.forall(a => isLongTyped(a, scope, longVars)) =>
        val sn = safeName(name)
        val args_s = args.map(a => genTermAsLong(a, scope, directDefs, directLocals, longVars)).mkString(", ")
        s"${sn}_long($args_s)"
      case If(c, th, el)
           if isBoolTyped(c, scope, longVars) &&
              isLongTyped(th, scope, longVars) &&
              isLongTyped(el, scope, longVars) =>
        val c_s = genTermAsBool(c, scope, directDefs, directLocals, longVars)
        val th_s = genTermAsLong(th, scope, directDefs, directLocals, longVars)
        val el_s = genTermAsLong(el, scope, directDefs, directLocals, longVars)
        s"(if $c_s then $th_s else $el_s)"
      case Prim("__arith__", List(Lit(Const.CStr(op)), l, r))
           if isLongTyped(l, scope, longVars) && isLongTyped(r, scope, longVars) =>
        val l_s = genTermAsLong(l, scope, directDefs, directLocals, longVars)
        val r_s = genTermAsLong(r, scope, directDefs, directLocals, longVars)
        s"($l_s $op $r_s)"
      case _ =>
        s"_asLong(${genTerm(t, scope, directDefs, directLocals, longVars)})"

  private def genTermAsBool(t: Term, scope: List[String],
                             directDefs: Map[String, Int], directLocals: Map[Int, (String, Int)],
                             longVars: Set[String]): String =
    import Term.*
    t match
      case Lit(Const.CBool(b)) => b.toString
      case Prim("__arith__", List(Lit(Const.CStr(op)), l, r))
           if Set("==", "!=", "<", "<=", ">", ">=").contains(op) &&
              isLongTyped(l, scope, longVars) && isLongTyped(r, scope, longVars) =>
        val l_s = genTermAsLong(l, scope, directDefs, directLocals, longVars)
        val r_s = genTermAsLong(r, scope, directDefs, directLocals, longVars)
        s"($l_s $op $r_s)"
      case _ =>
        s"(${genTerm(t, scope, directDefs, directLocals, longVars)}).asInstanceOf[Boolean]"

  // scope(i) = name for Local(i); scope.head = Local(0) = innermost binder
  // directDefs: global names that have a `<name>_direct(...)` def — call them directly
  //             (instead of through the closure lazy val) to enable @tailrec.
  // directLocals: local indices that have a `<varname>_direct(...)` def (for single LetRec @tailrec).
  //               Maps local index (in current scope) → (directDefName, arity).
  // longVars: names of let-bindings that were emitted as `var name: Long` (Long-cell optimization).
  def genTerm(t: Term, scope: List[String],
              directDefs: Map[String, Int] = Map.empty,
              directLocals: Map[Int, (String, Int)] = Map.empty,
              longVars: Set[String] = Set.empty,
              tailLocalJumps: Map[Int, (Int, Int)] = Map.empty,
              isTailPosition: Boolean = false): String =
    import Term.*
    t match
      case Lit(c)       => genConst(c)
      case Local(i)     =>
        if i < scope.length then scope(i)
        else sys.error(s"local($i) out of scope (depth=${scope.length})")
      case Global(name) => safeName(name)
      case Lam(n, body) =>
        val d = fresh()
        // params: p0_d = first arg, p{n-1}_d = last arg = Local(0)
        val params = (0 until n).map(k => s"p${k}_$d")
        // scope for body: [p{n-1}_d, ..., p0_d] ++ outer scope
        val bodyScope = params.reverse.toList ++ scope
        // Shift directLocals indices by n (params consume n spots)
        val shiftedLocals = directLocals.map { case (k, v) => (k + n, v) }
        if n == 0 then
          // 0-arity: thunk — longVars not passed into lambda body (params reset scope)
          val body_s = genTerm(body, scope, directDefs, shiftedLocals)
          s"((_a$d: Array[V]) => { val _unused$d = _a$d; $body_s }): V"
        else
          val paramBinds = params.zipWithIndex.map { case (p, k) => s"val $p: V = _a$d($k)" }.mkString("; ")
          val body_s = genTerm(body, bodyScope, directDefs, shiftedLocals)
          s"((_a$d: Array[V]) => { $paramBinds; $body_s }): V"

      case App(fn, args) =>
        // Check for direct-callable global
        fn match
          case Local(i) if isTailPosition && tailLocalJumps.contains(i) =>
            val (fid, arity) = tailLocalJumps(i)
            if args.length == arity then
              val args_s = args.map(a => genTerm(a, scope, directDefs, directLocals, longVars, tailLocalJumps, false)).mkString(", ")
              s"_TcoJump($fid, Array[V]($args_s))"
            else
              val fn_s = genTerm(fn, scope, directDefs, directLocals, longVars, tailLocalJumps, false)
              val args_s = args.map(a => genTerm(a, scope, directDefs, directLocals, longVars, tailLocalJumps, false)).mkString(", ")
              val n = args.length
              if n == 0 then s"_call0($fn_s)"
              else if n == 1 then s"_call1($fn_s, $args_s)"
              else if n == 2 then s"_call2($fn_s, $args_s)"
              else if n == 3 then s"_call3($fn_s, $args_s)"
              else s"_call($fn_s, Array($args_s))"
          case Global(name)
               if longGlobalDefs.get(name).contains(args.length) &&
                  args.forall(a => isLongTyped(a, scope, longVars)) =>
            val sn = safeName(name)
            val args_s = args.map(a => genTermAsLong(a, scope, directDefs, directLocals, longVars)).mkString(", ")
            s"${sn}_long($args_s): V"
          case Global(name) if directDefs.contains(name) =>
            val sn = safeName(name)
            val args_s = args.map(a => genTerm(a, scope, directDefs, directLocals, longVars, tailLocalJumps, false)).mkString(", ")
            s"${sn}_direct($args_s)"
          case Local(i) if directLocals.contains(i) =>
            val (dname, _) = directLocals(i)
            val args_s = args.map(a => genTerm(a, scope, directDefs, directLocals, longVars, tailLocalJumps, false)).mkString(", ")
            s"${dname}_direct($args_s)"
          case _ =>
            val fn_s = genTerm(fn, scope, directDefs, directLocals, longVars, tailLocalJumps, false)
            val args_s = args.map(a => genTerm(a, scope, directDefs, directLocals, longVars, tailLocalJumps, false)).mkString(", ")
            val n = args.length
            if n == 0 then s"_call0($fn_s)"
            else if n == 1 then s"_call1($fn_s, $args_s)"
            else if n == 2 then s"_call2($fn_s, $args_s)"
            else if n == 3 then s"_call3($fn_s, $args_s)"
            else s"_call($fn_s, Array($args_s))"

      case Let(rhs, body) =>
        val d = fresh()
        val n = rhs.length
        // Bind each rhs sequentially; each can see previous bindings
        // l0_d = first binding = local(n-1) in body; l{n-1}_d = last = local(0)
        val parts = new StringBuilder
        var curScope = scope
        var curLocals = directLocals
        var curLongs = longVars  // track long-cell var names
        val lnames = (0 until n).map { k =>
          val name = s"l${k}_$d"
          rhs(k) match
            case Prim("lcell.new", List(Lit(Const.CInt(v)))) =>
              // Long-cell optimization: emit `var name: Long = v` instead of boxed V
              parts.append(s"var $name: Long = ${v}L; ")
              curLongs = curLongs + name
            case r =>
              val rhs_s = genTerm(r, curScope, directDefs, curLocals, curLongs, tailLocalJumps, false)
              parts.append(s"val $name: V = $rhs_s; ")
          curScope = name :: curScope
          curLocals = curLocals.map { case (idx, v) => (idx + 1, v) }
          name
        }
        // body scope: [l{n-1}_d, ..., l0_d] ++ outer (lnames.reverse = [l{n-1}, ..., l0])
        val bodyScope = lnames.reverse.toList ++ scope
        val bodyLocals = shiftLocalMap(directLocals, n)
        val bodyJumps = shiftLocalMap(tailLocalJumps, n)
        val body_s = genTerm(body, bodyScope, directDefs, bodyLocals, curLongs, bodyJumps, isTailPosition)
        s"{ ${parts.toString}$body_s }"

      case LetRec(lams, body) =>
        val d = fresh()
        val n = lams.length
        val rnames = (0 until n).map(k => s"r${k}_$d").toList
        // letrec scope for body: [r{n-1}_d, ..., r0_d] ++ outer
        val letrecScope = rnames.reverse ++ scope
        // For each lam body: params (innermost) ++ letrec vars ++ outer
        val sb = new StringBuilder

        // Single-lam @tailrec optimization:
        // If there's exactly one lam and its body has only tail self-calls
        // (via Local(lamArity) after params are pushed), emit @tailrec def.
        val singleTailRec: Option[(String, Int, List[String], Term)] =
          if n == 1 then
            lams(0) match
              case Lam(lamArity, lamBody) if lamArity > 0 =>
                // self-call index in lam body scope = lamArity (after params shifted by lamArity)
                if isSafeTailRecLocal(lamArity, lamBody) then
                  Some((rnames(0), lamArity, rnames, lamBody))
                else None
              case _ => None
          else None

        singleTailRec match
          case Some((rn, lamArity, _, lamBody)) =>
            val ld = fresh()
            val params = (0 until lamArity).map(k => s"p${k}_$ld")
            val lamBodyScope = params.reverse.toList ++ letrecScope
            // Self-call in lam body: Local(lamArity) → rn_direct
            val innerLocals = directLocals.map { case (k, v) => (k + lamArity + 1, v) } +
              (lamArity -> (rn, lamArity))
            val body_s = genTerm(lamBody, lamBodyScope, directDefs, innerLocals, longVars)
            val paramDecls = params.map(p => s"$p: V").mkString(", ")
            sb.append(s"import scala.annotation.tailrec; ")
            sb.append(s"@tailrec def ${rn}_direct($paramDecls): V = $body_s; ")
            // Wrapper closure
            val wrapArgs = params.zipWithIndex.map { case (_, k) => s"_aw$ld($k)" }.mkString(", ")
            sb.append(s"var $rn: V = ((_aw$ld: Array[V]) => ${rn}_direct($wrapArgs)): V; ")
            val bodyLocals = directLocals.map { case (k, v) => (k + 1, v) }
            val bodyJumps = shiftLocalMap(tailLocalJumps, 1)
            val body2_s = genTerm(body, letrecScope, directDefs, bodyLocals, longVars, bodyJumps, isTailPosition)
            s"{ ${sb.toString}$body2_s }"

          case None =>
            isSafeMutualTailRecGroup(lams) match
              case Some(lamInfos) =>
                rnames.foreach { rn => sb.append(s"var $rn: V = null.asInstanceOf[V]; ") }
                val dispatcher = s"_mutual_${d}"
                sb.append(s"def $dispatcher(_fid0: Int, _args0: Array[V]): V = { ")
                sb.append("var _fid = _fid0; var _args = _args0; var _res: V = null; var _done = false; ")
                sb.append("while !_done do { val _out: V = _fid match { ")
                lamInfos.zipWithIndex.foreach { case ((lamArity, lamBody), fid) =>
                  val ld = fresh()
                  val params = (0 until lamArity).map(k => s"p${k}_$ld")
                  val lamBodyScope = params.reverse.toList ++ letrecScope
                  val innerLocals = shiftLocalMap(directLocals, lamArity + n)
                  val jumpLocals = (0 until n).map { targetFid =>
                    val localIdx = lamArity + (n - 1 - targetFid)
                    localIdx -> (targetFid, lamInfos(targetFid)._1)
                  }.toMap
                  val paramBinds = params.zipWithIndex.map { case (p, k) => s"val $p: V = _args($k)" }.mkString("; ")
                  val body_s = genTerm(lamBody, lamBodyScope, directDefs, innerLocals, longVars, jumpLocals, true)
                  sb.append(s"case $fid => { $paramBinds; $body_s }; ")
                }
                sb.append("""case _ => throw new RuntimeException("bad mutual TCO function id") }; """)
                sb.append("_out match { case _j: _TcoJump => _fid = _j.fid; _args = _j.args; case _v => _res = _v; _done = true } }; _res }; ")
                rnames.zipWithIndex.foreach { case (rn, fid) =>
                  sb.append(s"$rn = ((_a${d}_$fid: Array[V]) => $dispatcher($fid, _a${d}_$fid)): V; ")
                }
                val bodyLocals = shiftLocalMap(directLocals, n)
                val bodyJumps = shiftLocalMap(tailLocalJumps, n)
                val body_s = genTerm(body, letrecScope, directDefs, bodyLocals, longVars, bodyJumps, isTailPosition)
                s"{ ${sb.toString}$body_s }"

              case None =>
                // Standard LetRec: var bindings for mutual recursion
                rnames.foreach { rn => sb.append(s"var $rn: V = null.asInstanceOf[V]; ") }
                lams.zip(rnames).foreach { case (lam, rn) =>
                  lam match
                    case Lam(lamArity, lamBody) =>
                      val ld = fresh()
                      val params = (0 until lamArity).map(k => s"p${k}_$ld")
                      val lamBodyScope = params.reverse.toList ++ letrecScope
                      val innerLocals = shiftLocalMap(directLocals, lamArity + n)
                      if lamArity == 0 then
                        val body_s = genTerm(lamBody, letrecScope, directDefs, innerLocals, longVars)
                        sb.append(s"$rn = ((_a$ld: Array[V]) => { val _u$ld = _a$ld; $body_s }): V; ")
                      else
                        val paramBinds = params.zipWithIndex.map { case (p, k) => s"val $p: V = _a$ld($k)" }.mkString("; ")
                        val body_s = genTerm(lamBody, lamBodyScope, directDefs, innerLocals, longVars)
                        sb.append(s"$rn = ((_a$ld: Array[V]) => { $paramBinds; $body_s }): V; ")
                    case _ => sys.error(s"letrec binding must be a Lam, got: $lam")
                }
                val bodyLocals = shiftLocalMap(directLocals, n)
                val bodyJumps = shiftLocalMap(tailLocalJumps, n)
                val body_s = genTerm(body, letrecScope, directDefs, bodyLocals, longVars, bodyJumps, isTailPosition)
                s"{ ${sb.toString}$body_s }"

      case If(c, th, el) =>
        val c_s  = genTerm(c,  scope, directDefs, directLocals, longVars, tailLocalJumps, false)
        val th_s = genTerm(th, scope, directDefs, directLocals, longVars, tailLocalJumps, isTailPosition)
        val el_s = genTerm(el, scope, directDefs, directLocals, longVars, tailLocalJumps, isTailPosition)
        s"(if ($c_s).asInstanceOf[Boolean] then $th_s else $el_s)"

      case Ctor(tag, fields) =>
        if fields.isEmpty then
          s"""("$tag", Array.empty[V])"""
        else
          val fields_s = fields.map(f => genTerm(f, scope, directDefs, directLocals, longVars, tailLocalJumps, false)).mkString(", ")
          s"""("$tag", Array[V]($fields_s))"""

      case Match(scrut, arms, default) =>
        val d = fresh()
        val scrut_s = genTerm(scrut, scope, directDefs, directLocals, longVars, tailLocalJumps, false)
        val sb = new StringBuilder
        sb.append(s"{ val _sv$d: V = $scrut_s; ")
        sb.append(s"val _st$d: String = _adtTag(_sv$d); val _sf$d: Array[V] = _adtFields(_sv$d); ")
        sb.append("(")
        val conditions = arms.zipWithIndex.map { case (arm, _) =>
          val ld = fresh()
          val armN = arm.arity
          // field vars: f0_ld = fields(0), f1_ld = fields(1), ...
          // arm body scope: [f{n-1}_ld, ..., f0_ld] ++ outer
          val fnames = (0 until armN).map(k => s"f${k}_$ld")
          val armBodyScope = fnames.reverse.toList ++ scope
          val armLocals = directLocals.map { case (k, v) => (k + armN, v) }
          val armJumps = shiftLocalMap(tailLocalJumps, armN)
          val fieldBinds = fnames.zipWithIndex.map { case (fn, k) =>
            s"val $fn: V = _sf$d($k)"
          }.mkString("; ")
          val body_s = genTerm(arm.body, armBodyScope, directDefs, armLocals, longVars, armJumps, isTailPosition)
          val guard = if armN > 0 then s""" && _sf$d.length == $armN""" else s""" && _sf$d.length == 0"""
          s"""if (_st$d == "${arm.tag}"$guard) { $fieldBinds; $body_s }"""
        }
        sb.append(conditions.mkString("\nelse "))
        default match
          case Some(d_term) =>
            val def_s = genTerm(d_term, scope, directDefs, directLocals, longVars, tailLocalJumps, isTailPosition)
            sb.append(s"\nelse { $def_s }")
          case None =>
            sb.append(s"""\nelse throw new RuntimeException("match: no arm for " + _st$d)""")
        sb.append(") }")
        sb.toString

      case Prim(op, args) =>
        import Term.*
        // ── Long-cell fast paths (avoid V=Any boxing in tight loops) ────────────
        (op, args) match
          case ("lcell.get", List(Local(i))) if i < scope.length && longVars.contains(scope(i)) =>
            scope(i)  // direct Long var read — no boxing or prim dispatch
          case ("lcell.set", List(Local(i), expr)) if i < scope.length && longVars.contains(scope(i)) =>
            val varName = scope(i)
            val exprStr = if isLongTyped(expr, scope, longVars)
              then genTermAsLong(expr, scope, directDefs, directLocals, longVars)
              else s"_asLong(${genTerm(expr, scope, directDefs, directLocals, longVars, tailLocalJumps, false)})"
            s"$varName = $exprStr"
          case ("__arith__", List(Lit(Const.CStr(aop)), l, r))
               if isLongTyped(l, scope, longVars) && isLongTyped(r, scope, longVars) =>
            val l_s = genTermAsLong(l, scope, directDefs, directLocals, longVars)
            val r_s = genTermAsLong(r, scope, directDefs, directLocals, longVars)
            aop match
              case "==" | "!=" | "<" | "<=" | ">" | ">=" => s"($l_s $aop $r_s)"  // returns Boolean
              case "++"                                   => s"($l_s.toString + $r_s.toString)"
              case _                                      => s"($l_s $aop $r_s): V"  // arithmetic, returns Long
          case _ =>
            // Generic fallback
            val n = args.length
            val args_s = args.map(a => genTerm(a, scope, directDefs, directLocals, longVars, tailLocalJumps, false)).mkString(", ")
            if n == 0 then s"""R.primN("$op", Array.empty[V])"""
            else if n == 1 then s"""R.prim1("$op", $args_s)"""
            else if n == 2 then s"""R.prim2("$op", $args_s)"""
            else if n == 3 then s"""R.prim3("$op", $args_s)"""
            else s"""R.primN("$op", Array[V]($args_s))"""

      case While(cond, body) =>
        // If condition is a Long comparison, generate it directly (no .asInstanceOf[Boolean])
        val isBoolExpr = cond match
          case Prim("__arith__", List(Lit(Const.CStr(op)), l, r)) =>
            Set("==", "!=", "<", "<=", ">", ">=").contains(op) &&
            isLongTyped(l, scope, longVars) && isLongTyped(r, scope, longVars)
          case _ => false
        val c_s = if isBoolExpr then genTerm(cond, scope, directDefs, directLocals, longVars, tailLocalJumps, false)
                  else s"(${genTerm(cond, scope, directDefs, directLocals, longVars, tailLocalJumps, false)}).asInstanceOf[Boolean]"
        val b_s = genTerm(body, scope, directDefs, directLocals, longVars, tailLocalJumps, false)
        s"{ while ($c_s) do { $b_s; () }; () }"

      case Seq(terms) =>
        if terms.isEmpty then "()"
        else
          val parts = terms.zipWithIndex.map { case (t2, idx) =>
            genTerm(t2, scope, directDefs, directLocals, longVars, tailLocalJumps, isTailPosition && idx == terms.length - 1)
          }
          s"{ ${parts.init.map(s => s"$s; ()").mkString("; ")}; ${parts.last} }"

  def genConst(c: Const): String = c match
    case Const.CUnit      => "()"
    case Const.CBool(b)   => b.toString
    case Const.CInt(n)    => s"(${n}L: V)"
    case Const.CBig(n)    => s"""BigInt("$n"): V"""
    case Const.CFloat(d)  =>
      if d.isNaN then "(Double.NaN: V)"
      else if d == Double.PositiveInfinity then "(Double.PositiveInfinity: V)"
      else if d == Double.NegativeInfinity then "(Double.NegativeInfinity: V)"
      else s"(${d}d: V)"
    case Const.CStr(s)    => s""""${escapeStr(s)}": V"""
    case Const.CBytes(bs) =>
      if bs.isEmpty then "(Vector.empty[Byte]: V)"
      else s"Vector[Byte](${bs.map(b => s"${b & 0xff}.toByte").mkString(", ")}): V"

  def escapeStr(s: String): String =
    val sb = new StringBuilder
    s.foreach {
      case '"'  => sb ++= "\\\""
      case '\\' => sb ++= "\\\\"
      case '\n' => sb ++= "\\n"
      case '\r' => sb ++= "\\r"
      case '\t' => sb ++= "\\t"
      case c if c.isControl => sb ++= f"\\u${c.toInt}%04x"
      case c    => sb += c
    }
    sb.toString

  // Collect all Global names referenced in a term (for stub generation).
  private def collectGlobals(t: Term): Set[String] =
    import Term.*
    t match
      case Global(n)        => Set(n)
      case Lit(_) | Local(_) => Set.empty
      case Lam(_, b)        => collectGlobals(b)
      case App(fn, args)    => args.foldLeft(collectGlobals(fn))(_ | collectGlobals(_))
      case Let(rhs, b)      => rhs.foldLeft(collectGlobals(b))(_ | collectGlobals(_))
      case LetRec(ls, b)    => ls.foldLeft(collectGlobals(b))(_ | collectGlobals(_))
      case If(c, th, el)    => collectGlobals(c) | collectGlobals(th) | collectGlobals(el)
      case Ctor(_, fs)      => fs.foldLeft(Set.empty[String])(_ | collectGlobals(_))
      case Match(s, arms, d)=>
        val base = collectGlobals(s) | d.fold(Set.empty[String])(collectGlobals)
        arms.foldLeft(base)((acc, arm) => acc | collectGlobals(arm.body))
      case Prim(_, args)    => args.foldLeft(Set.empty[String])(_ | collectGlobals(_))
      case While(c, b)      => collectGlobals(c) | collectGlobals(b)
      case Seq(ts)          => ts.foldLeft(Set.empty[String])(_ | collectGlobals(_))

  // Generate the full Scala 3 source file for a Program.
  def generate(p: Program): String =
    counter = 0  // reset per invocation
    longGlobalDefs = Map.empty
    val sb = new StringBuilder
    sb.append("// v2 JVM backend — generated Scala 3 source\n")
    sb.append(preamble)
    sb.append("\n@main def v2main(): Unit =\n")

    // Pass 1: lambda defs (generate before value defs, same as VM two-pass)
    val lamDefs = p.defs.filter { d => d.body match { case Term.Lam(_, _) => true; case _ => false } }
    val valDefs = p.defs.filter { d => d.body match { case Term.Lam(_, _) => false; case _ => true } }

    // All lambda defs stay available as lazy-val closures. Some lambdas also get
    // direct methods: @tailrec methods for safe tail recursion and Long-specialized
    // methods for proven-Long call chains such as recursive fib.
    // Bodies reference globals by name, which will be defined in this same scope.
    // Scala closures capture by reference (ObjectRef), so forward refs are OK as long as
    // all vals are set before any closure is called.
    val globalLambdaArities: Map[String, Int] = lamDefs.flatMap { d =>
      d.body match
        case Term.Lam(n, _) => Some(d.name -> n)
        case _              => None
    }.toMap
    val tailRecGlobalDefs: Set[String] = lamDefs.flatMap { d =>
      d.body match
        case Term.Lam(n, body) if n > 0 && isSafeTailRecGlobal(d.name, body) =>
          Some(d.name)
        case _ => None
    }.toSet
    val directGlobalDefs: Map[String, Int] =
      tailRecGlobalDefs.map(name => name -> globalLambdaArities(name)).toMap

    def inferLongGlobalDefs(): Map[String, Int] =
      var current = globalLambdaArities.filter(_._2 > 0)
      var changed = true
      while changed do
        longGlobalDefs = current
        val next = lamDefs.flatMap { d =>
          d.body match
            case Term.Lam(n, body) if n > 0 =>
              val scope = (0 until n).map(k => s"p${k}_long").reverse.toList
              if isLongTyped(body, scope, scope.toSet) then Some(d.name -> n) else None
            case _ => None
        }.toMap
        changed = next != current
        current = next
      current

    longGlobalDefs = inferLongGlobalDefs()

    if lamDefs.nonEmpty || valDefs.nonEmpty then
      // Emit all global lam defs
      for d <- lamDefs do
        val sname = safeName(d.name)
        d.body match
          case Term.Lam(n, body) =>
            val ld = fresh()
            val params = (0 until n).map(k => s"p${k}_$ld")
            // Top-level lam bodies have empty outer scope (globals accessed by name)
            val bodyScope = params.reverse.toList
            if longGlobalDefs.contains(d.name) && n > 0 then
              val paramDecls = params.map(p => s"$p: Long").mkString(", ")
              val body_s = genTermAsLong(body, bodyScope, directGlobalDefs, Map.empty, params.toSet)
              if tailRecGlobalDefs.contains(d.name) then
                sb.append(s"  import scala.annotation.tailrec\n")
                sb.append(s"  @tailrec\n")
              sb.append(s"  def ${sname}_long($paramDecls): Long = $body_s\n")
            if tailRecGlobalDefs.contains(d.name) && n > 0 then
              // @tailrec def + lazy val wrapper
              val paramDecls = params.map(p => s"$p: V").mkString(", ")
              val body_s = genTerm(body, bodyScope, directGlobalDefs, Map.empty)
              sb.append(s"  import scala.annotation.tailrec\n")
              sb.append(s"  @tailrec def ${sname}_direct($paramDecls): V = $body_s\n")
              val wrap =
                if longGlobalDefs.contains(d.name) then
                  val wrapArgs = params.zipWithIndex.map { case (_, k) => s"_asLong(_aw$ld($k))" }.mkString(", ")
                  s"${sname}_long($wrapArgs)"
                else
                  val wrapArgs = params.zipWithIndex.map { case (_, k) => s"_aw$ld($k)" }.mkString(", ")
                  s"${sname}_direct($wrapArgs)"
              sb.append(s"  lazy val $sname: V = ((_aw$ld: Array[V]) => $wrap): V\n")
            else if n == 0 then
              val body_s = genTerm(body, List.empty, directGlobalDefs, Map.empty)
              sb.append(s"  lazy val $sname: V = ((_a$ld: Array[V]) => { val _u$ld = _a$ld; $body_s }): V\n")
            else
              if longGlobalDefs.contains(d.name) then
                val wrapArgs = params.zipWithIndex.map { case (_, k) => s"_asLong(_a$ld($k))" }.mkString(", ")
                sb.append(s"  lazy val $sname: V = ((_a$ld: Array[V]) => ${sname}_long($wrapArgs)): V\n")
              else
                val paramBinds = params.zipWithIndex.map { case (p, k) => s"val $p: V = _a$ld($k)" }.mkString("; ")
                val body_s = genTerm(body, bodyScope, directGlobalDefs, Map.empty)
                sb.append(s"  lazy val $sname: V = ((_a$ld: Array[V]) => { $paramBinds; $body_s }): V\n")
          case _ => () // shouldn't happen

      for d <- valDefs do
        val sname = safeName(d.name)
        val body_s = genTerm(d.body, List.empty, directGlobalDefs, Map.empty)
        sb.append(s"  lazy val $sname: V = $body_s\n")

    // Generate stubs for Global names referenced but not defined (e.g. _err from type errors)
    val definedRawNames = p.defs.map(_.name).toSet
    val definedNames = definedRawNames.map(safeName)
    val allRefs = p.defs.foldLeft(collectGlobals(p.entry)) { (acc, d) => acc | collectGlobals(d.body) }
    val bridgeGlobals = Map[String, String](
      "println" -> s"""  lazy val ${safeName("println")}: V = ((args: Array[V]) => { args.foreach(a => R.prim1("io.print", a)); Console.out.println(); () }): V\n""",
      "print"   -> s"""  lazy val ${safeName("print")}: V = ((args: Array[V]) => { args.foreach(a => R.prim1("io.print", a)); () }): V\n"""
    )
    for (raw, code) <- bridgeGlobals.toSeq.sortBy(_._1)
        if allRefs(raw) && !definedRawNames(raw) do
      sb.append(code)
    val undefinedRefs = allRefs.diff(bridgeGlobals.keySet).map(safeName).diff(definedNames)
    for name <- undefinedRefs.toSeq.sorted do
      sb.append(s"""  lazy val $name: V = throw new RuntimeException("unbound global: $name")\n""")

    // Entry: compute and print result if non-Unit (same as VM's `out` function).
    // Use Console.out.println directly to avoid shadowing by user-defined `println` global.
    val entry_s = genTerm(p.entry, List.empty, directGlobalDefs, Map.empty)
    sb.append(s"  val _entry: V = $entry_s\n")
    sb.append("  if !_entry.isInstanceOf[Unit] then Console.out.println(_show(_entry))\n")
    sb.toString

// ── Main entry point ──────────────────────────────────────────────────────────

@main def JvmBackend(args: String*): Unit =
  val src = args.toList match
    case Nil | List("-") =>
      io.Source.fromInputStream(System.in)(using io.Codec.UTF8).mkString
    case List(file) =>
      io.Source.fromFile(file)(using io.Codec.UTF8).mkString
    case _ =>
      Console.err.println("usage: JvmBackend [<file.coreir>]  (reads stdin if no file)")
      sys.exit(1)
  val prog = Reader.parseProgram(src)
  val code = CodeGen.generate(prog)
  println(code)
