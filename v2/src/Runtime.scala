package ssc

// The ssc VM: IR -> ssc -> cpu.
// JIT philosophy (per design): once we know what a node is, we compile it ONCE
// into a closure that does exactly that — no re-dispatch on Term at run time.
// compile(Term): Code  turns the IR into a tree of closures (the "generated
// program"); the trampoline driver runs it with proper tail calls (TCO).

type Env  = Array[Value]
type Code  = Env => Step   // a compiled term: given an env, yield the next Step

sealed trait Step
final case class Done(v: Value)                       extends Step  // a finished value
final case class Call(clos: Value.ClosV, args: Array[Value]) extends Step  // a tail call to bounce

sealed trait Value
object Value:
  case object UnitV                                    extends Value
  final case class BoolV(b: Boolean)                  extends Value
  final case class IntV(n: Long)                      extends Value
  final case class BigV(n: BigInt)                    extends Value
  final case class FloatV(d: Double)                  extends Value
  final case class StrV(s: String)                    extends Value
  final case class BytesV(b: Vector[Byte])            extends Value
  final case class DataV(tag: String, fields: Vector[Value]) extends Value
  final class ClosV(var env: Env, val arity: Int, val code: Code) extends Value  // var env: cyclic letrec frames
  final case class ForeignV(h: AnyRef)                extends Value

object Runtime:
  import Value.*

  // Process argv — the trailing CLI args of `ssc run <file> ARGS...`, exposed to
  // the program through the `io.args` primitive. Set by Main before running.
  var argv: List[String] = Nil

  // Trampoline: run a compiled Code to a final Value, bouncing tail Calls in
  // CONSTANT STACK (specs/10-core-ir.md invariant 7).
  def run(code0: Code, env0: Env): Value =
    var code = code0; var env = env0
    while true do
      code(env) match
        case Done(v) => return v
        case Call(c, args) =>
          if c.arity != args.length then sys.error(s"arity: ${c.arity} expected, ${args.length} given")
          env = Runtime.extend(c.env, args)
          code = c.code
    sys.error("unreachable")

  // Non-tail evaluation of a sub-term = run it to a value.
  inline def value(code: Code, env: Env): Value = run(code, env)

  // Extend env with new bindings appended in order; Local(i) = arr[length-1-i] (O(1)).
  def extend(base: Env, vals: Array[Value]): Env =
    val r = new Array[Value](base.length + vals.length)
    System.arraycopy(base, 0, r, 0, base.length)
    System.arraycopy(vals, 0, r, base.length, vals.length)
    r

  def appendOne(base: Env, v: Value): Env =
    val r = new Array[Value](base.length + 1)
    System.arraycopy(base, 0, r, 0, base.length)
    r(base.length) = v
    r

object Compiler:
  import Value.*, Term.*

  /** Compile a whole program; returns the entry Code (globals captured inside). */
  def compile(p: Program): Code = compileWithGlobals(p)._1

  /** Compile a whole program; returns (entry Code, live globals map) for bench use. */
  def compileWithGlobals(p: Program): (Code, collection.mutable.Map[String, Value]) =
    val globals = collection.mutable.HashMap[String, Value]()
    val c = new C(globals)
    // pass 1: lambda defs -> closures (recursion resolves via Global at call time)
    for d <- p.defs do d.body match
      case Lam(ar, b) => globals(d.name) = ClosV(Array.empty[Value], ar, c.compile(b))
      case _ => ()
    // pass 2: value defs (may reference the lambda globals)
    for d <- p.defs do d.body match
      case Lam(_, _) => ()
      case other => globals(d.name) = Runtime.run(c.compile(other), Array.empty[Value])
    (c.compile(p.entry), globals)

  final class C(globals: collection.mutable.Map[String, Value]):
    def compile(t: Term): Code = t match
      case Lit(k) =>
        val v = constV(k); (_: Env) => Done(v)                       // const folded once
      case Local(i) =>
        (env: Env) => Done(env(env.length - 1 - i))
      case Global(g) =>
        (_: Env) => Done(globals.getOrElse(g, sys.error(s"unbound global: $g")))
      case Lam(ar, b) =>
        val bc = compile(b); (env: Env) => Done(ClosV(env, ar, bc))
      case If(c, th, el) =>
        val cc = compile(c); val tc = compile(th); val ec = compile(el)
        (env: Env) => Runtime.value(cc, env) match                   // condition: non-tail
          case BoolV(true)  => tc(env)                               // branch: tail (returns its Step)
          case BoolV(false) => ec(env)
          case v => sys.error(s"if: condition not Bool: ${Show.show(v)}")
      case Let(rhs, body) =>
        val rcs = rhs.map(compile); val bc = compile(body)
        (env: Env) =>
          var e = env
          rcs.foreach(rc => e = Runtime.appendOne(e, Runtime.value(rc, e)))  // sequential, non-tail rhs
          bc(e)                                                      // body: tail
      case LetRec(lams, body) =>
        val acs = lams.map {
          case Lam(ar, b) => (ar, compile(b))
          case _ => sys.error("letrec binding must be a lam")
        }
        val bc = compile(body)
        (env: Env) =>
          val cs = acs.map { case (ar, code) => ClosV(Array.empty[Value], ar, code) }
          val envP = Runtime.extend(env, cs.toArray)                 // last binding = Local(0)
          cs.foreach(_.env = envP)                                   // tie the cyclic frame
          bc(envP)                                                   // body: tail
      case Ctor(tag, fields) =>
        val fcs = fields.map(compile)
        (env: Env) => Done(DataV(tag, fcs.map(fc => Runtime.value(fc, env)).toVector))
      case Match(scrut, arms, default) =>
        val sc = compile(scrut)
        val acs = arms.map(a => (a.tag, a.arity, compile(a.body)))
        val dc = default.map(compile)
        (env: Env) => Runtime.value(sc, env) match                   // scrutinee: non-tail
          case DataV(tag, fs) =>
            acs.find { case (t, ar, _) => t == tag && ar == fs.length } match
              case Some((_, _, body)) => body(Runtime.extend(env, fs.toArray))    // arm: tail
              case None => dc match
                case Some(d) => d(env)
                case None => sys.error(s"match: no arm for $tag/${fs.length}")
          case v => sys.error(s"match: scrutinee not Data: ${Show.show(v)}")
      case Prim(op, args) =>
        val fn = Prims.resolve(op)                                   // resolved once, at compile
        val acs = args.map(compile)
        (env: Env) => Done(fn(acs.map(ac => Runtime.value(ac, env))))
      case App(fn, args) =>
        val fc = compile(fn); val acs = args.map(compile)
        (env: Env) =>
          val fv  = Runtime.value(fc, env)                           // fn + args: non-tail
          val avs = acs.map(ac => Runtime.value(ac, env)).toArray
          fv match
            case c: ClosV => Call(c, avs)                            // THE tail call: hand to driver
            case v => sys.error(s"app: not a function: ${Show.show(v)}")

  def constV(c: Const): Value = c match
    case Const.CUnit     => Value.UnitV
    case Const.CBool(b)  => Value.BoolV(b)
    case Const.CInt(n)   => Value.IntV(n)
    case Const.CBig(n)   => Value.BigV(n)
    case Const.CFloat(d) => Value.FloatV(d)
    case Const.CStr(s)   => Value.StrV(s)
    case Const.CBytes(b) => Value.BytesV(b)

// ── Primitives δ — resolved once at compile time (specs/10-core-ir.md §5) ─────

object Prims:
  import Value.*
  type Fn = List[Value] => Value

  def resolve(op: String): Fn = op match
    case "i.add" => a => numBin(a, _ + _, _ + _)
    case "i.sub" => a => numBin(a, _ - _, _ - _)
    case "i.mul" => a => numBin(a, _ * _, _ * _)
    case "i.div" => a => numBin(a, _ / _, _ / _)
    case "i.mod" => a => numBin(a, _ % _, _ % _)
    case "i.neg" => a => numUn(a, -_, -_)
    case "i.and" => a => IntV(int(a, 0) & int(a, 1))
    case "i.or"  => a => IntV(int(a, 0) | int(a, 1))
    case "i.xor" => a => IntV(int(a, 0) ^ int(a, 1))
    case "i.not" => a => IntV(~int(a, 0))
    case "i.shl" => a => IntV(int(a, 0) << int(a, 1))
    case "i.shr" => a => IntV(int(a, 0) >> int(a, 1))
    case "i.ushr"=> a => IntV(int(a, 0) >>> int(a, 1))
    case "i.eq"  => a => numCmp(a, _ == _, _ == _)
    case "i.lt"  => a => numCmp(a, _ <  _, _ <  _)
    case "i.le"  => a => numCmp(a, _ <= _, _ <= _)
    case "i.gt"  => a => numCmp(a, _ >  _, _ >  _)
    case "i.ge"  => a => numCmp(a, _ >= _, _ >= _)
    case "not"   => a => BoolV(!bool(a, 0))
    // BigInt
    case "big.add" => a => BigV(big(a, 0) + big(a, 1))
    case "big.sub" => a => BigV(big(a, 0) - big(a, 1))
    case "big.mul" => a => BigV(big(a, 0) * big(a, 1))
    case "big.div" => a => BigV(big(a, 0) / big(a, 1))
    case "big.mod" => a => BigV(big(a, 0) % big(a, 1))
    case "big.neg" => a => BigV(-big(a, 0))
    case "big.eq"  => a => BoolV(big(a, 0) == big(a, 1))
    case "big.lt"  => a => BoolV(big(a, 0) <  big(a, 1))
    case "big.le"  => a => BoolV(big(a, 0) <= big(a, 1))
    case "big.gt"  => a => BoolV(big(a, 0) >  big(a, 1))
    case "big.ge"  => a => BoolV(big(a, 0) >= big(a, 1))
    // Float (IEEE-754)
    case "f.add" => a => FloatV(flt(a, 0) + flt(a, 1))
    case "f.sub" => a => FloatV(flt(a, 0) - flt(a, 1))
    case "f.mul" => a => FloatV(flt(a, 0) * flt(a, 1))
    case "f.div" => a => FloatV(flt(a, 0) / flt(a, 1))
    case "f.neg" => a => FloatV(-flt(a, 0))
    case "f.sqrt"  => a => FloatV(math.sqrt(flt(a, 0)))
    case "f.floor" => a => FloatV(math.floor(flt(a, 0)))
    case "f.ceil"  => a => FloatV(math.ceil(flt(a, 0)))
    case "f.round" => a => FloatV(math.rint(flt(a, 0)))
    case "f.trunc" => a => FloatV(flt(a, 0).toLong.toDouble)
    case "f.eq" => a => BoolV(flt(a, 0) == flt(a, 1))
    case "f.lt" => a => BoolV(flt(a, 0) <  flt(a, 1))
    case "f.le" => a => BoolV(flt(a, 0) <= flt(a, 1))
    case "f.gt" => a => BoolV(flt(a, 0) >  flt(a, 1))
    case "f.ge" => a => BoolV(flt(a, 0) >= flt(a, 1))
    case "f.isNaN" => a => BoolV(flt(a, 0).isNaN)
    case "f.isInf" => a => BoolV(flt(a, 0).isInfinite)
    // numeric conversions (explicit)
    case "i->big"  => a => BigV(BigInt(int(a, 0)))
    case "big->i"  => a => IntV(big(a, 0).toLong)
    case "i->f"    => a => FloatV(int(a, 0).toDouble)
    case "f->i"    => a => IntV(flt(a, 0).toLong)
    case "big->f"  => a => FloatV(big(a, 0).toDouble)
    case "f->big"  => a => BigV(BigDecimal(flt(a, 0)).toBigInt)
    case "i->str"  => a => StrV(int(a, 0).toString)
    case "big->str"=> a => StrV(big(a, 0).toString)
    case "f->str"  => a => StrV(Writer.floatStr(flt(a, 0)))
    case "str->i"  => a => str(a, 0).toLongOption.fold(none)(n => some(IntV(n)))
    case "str->big"=> a => scala.util.Try(BigInt(str(a, 0))).toOption.fold(none)(b => some(BigV(b)))
    case "str->f"  => a => str(a, 0).toDoubleOption.fold(none)(d => some(FloatV(d)))
    // String (UTF-16 code units; O(1) indexing)
    case "slen"      => a => IntV(str(a, 0).length.toLong)
    case "sconcat"   => a => (a(0), a(1)) match {
      case (DataV(_, f1), DataV(_, f2)) =>
        val n = f1.length + f2.length; DataV(s"Tuple$n", f1 ++ f2)
      case _ => StrV(anyStr(a(0)) + anyStr(a(1)))
    }
    case "sslice"    => a => StrV(str(a, 0).substring(int(a, 1).toInt, int(a, 2).toInt))
    case "scodeAt"   => a => IntV(str(a, 0).charAt(int(a, 1).toInt).toLong)
    case "sfromCodes"=> a => StrV(unlist(a(0)).map(v => asInt(v).toChar).mkString)
    case "seq"       => a => BoolV(str(a, 0) == str(a, 1))
    case "scmp"      => a => IntV(str(a, 0).compareTo(str(a, 1)).toLong)
    case "sindexOf"  => a => IntV(str(a, 0).indexOf(str(a, 1)).toLong)
    case "str.split" => a => { val parts = str(a, 0).split(java.util.regex.Pattern.quote(str(a, 1)), -1); val nilV: Value = DataV("Nil", Vector.empty); parts.foldRight(nilV)((s, acc) => DataV("Cons", Vector(StrV(s), acc))) }
    case "str.trim"  => a => StrV(str(a, 0).trim)
    case "str.lines" => a => { val parts = str(a, 0).split("\n", -1); val nilV: Value = DataV("Nil", Vector.empty); parts.foldRight(nilV)((s, acc) => DataV("Cons", Vector(StrV(s), acc))) }
    // Bytes
    case "blen"      => a => IntV(bytes(a, 0).length.toLong)
    case "bget"      => a => IntV((bytes(a, 0)(int(a, 1).toInt) & 0xff).toLong)
    case "bslice"    => a => BytesV(bytes(a, 0).slice(int(a, 1).toInt, int(a, 2).toInt))
    case "bconcat"   => a => BytesV(bytes(a, 0) ++ bytes(a, 1))
    case "str->utf8" => a => BytesV(str(a, 0).getBytes("UTF-8").toVector)
    case "utf8->str" => a => StrV(new String(bytes(a, 0).toArray, "UTF-8"))
    // Data (generic reflection)
    case "tagOf"   => a => StrV(asData(a(0))._1)
    case "arity"   => a => IntV(asData(a(0))._2.length.toLong)
    case "fieldAt" => a => asData(a(0))._2(int(a, 1).toInt)
    // Map (Foreign, mutable; keys/values are Values)
    case "map.new"  => _ => ForeignV(collection.mutable.HashMap[Value, Value]())
    case "map.get"  => a => asMap(a(0)).get(a(1)).fold(none)(some)
    case "map.put"  => a => asMap(a(0)).update(a(1), a(2)); UnitV
    case "map.has"  => a => BoolV(asMap(a(0)).contains(a(1)))
    case "map.del"  => a => asMap(a(0)).remove(a(1)); UnitV
    case "map.keys" => a => listOf(asMap(a(0)).keys.toSeq)
    case "map.size" => a => IntV(asMap(a(0)).size.toLong)
    // Array (Foreign, growable)
    case "arr.new"   => _ => ForeignV(collection.mutable.ArrayBuffer[Value]())
    case "arr.len"   => a => IntV(asArr(a(0)).length.toLong)
    case "arr.get"   => a => asArr(a(0))(int(a, 1).toInt)
    case "arr.set"   => a => asArr(a(0))(int(a, 1).toInt) = a(2); UnitV
    case "arr.push"  => a => asArr(a(0)) += a(1); UnitV
    case "arr.pop"   => a => asArr(a(0)).remove(asArr(a(0)).length - 1)
    case "arr.slice" => a => ForeignV(collection.mutable.ArrayBuffer.from(asArr(a(0)).slice(int(a, 1).toInt, int(a, 2).toInt)))
    // Cell (Foreign, single mutable ref)
    case "cell.new" => a => ForeignV(scala.Array[Value](a(0)))
    case "cell.get" => a => asCell(a(0))(0)
    case "cell.set" => a => asCell(a(0))(0) = a(1); UnitV
    // I/O [eff]
    case "io.print"   => a => out(a(0), Console.out); UnitV
    case "io.println" => a => out(a(0), Console.out); Console.out.println(); UnitV
    case "io.eprint"  => a => out(a(0), Console.err); UnitV
    case "io.args"   => _ => strList(Runtime.argv)
    case "io.readFile"  => a => BytesV(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(str(a, 0))).toVector)
    case "io.writeFile" => a => java.nio.file.Files.write(java.nio.file.Path.of(str(a, 0)), bytes(a, 1).toArray); UnitV
    case "io.env"  => a => sys.env.get(str(a, 0)).fold(none)(s => some(StrV(s)))
    case "io.exit" => a => sys.exit(int(a, 0).toInt); UnitV
    // Core IR serialization: a Data-tree (IrProg/IrLam/… built in ssc0) -> canonical bytecode
    case "coreir.encode" => a => StrV(IrEncode.program(a(0)))
    case _ => sys.error(s"unimplemented primitive: $op")

  // numeric dispatch helpers: promote to Float when either operand is FloatV
  private def numBin(a: List[Value], fi: (Long, Long) => Long, ff: (Double, Double) => Double): Value =
    (a(0), a(1)) match {
      case (FloatV(x), FloatV(y)) => FloatV(ff(x, y))
      case (FloatV(x), IntV(y))   => FloatV(ff(x, y.toDouble))
      case (IntV(x), FloatV(y))   => FloatV(ff(x.toDouble, y))
      case _                      => IntV(fi(int(a, 0), int(a, 1)))
    }
  private def numUn(a: List[Value], fi: Long => Long, ff: Double => Double): Value =
    a(0) match { case FloatV(x) => FloatV(ff(x)); case _ => IntV(fi(int(a, 0))) }
  private def numCmp(a: List[Value], fi: (Long, Long) => Boolean, ff: (Double, Double) => Boolean): Value =
    (a(0), a(1)) match {
      case (FloatV(x), FloatV(y)) => BoolV(ff(x, y))
      case (FloatV(x), IntV(y))   => BoolV(ff(x, y.toDouble))
      case (IntV(x), FloatV(y))   => BoolV(ff(x.toDouble, y))
      case _                      => BoolV(fi(int(a, 0), int(a, 1)))
    }

  // typed argument accessors
  private def int(a: List[Value], k: Int): Long = asInt(a(k))
  private def asInt(v: Value): Long = v match { case IntV(n) => n; case x => sys.error(s"expected Int, got ${Show.show(x)}") }
  private def big(a: List[Value], k: Int): BigInt = a(k) match { case BigV(n) => n; case v => sys.error(s"expected BigInt, got ${Show.show(v)}") }
  private def flt(a: List[Value], k: Int): Double = a(k) match { case FloatV(d) => d; case v => sys.error(s"expected Float, got ${Show.show(v)}") }
  private def str(a: List[Value], k: Int): String = a(k) match { case StrV(s) => s; case v => sys.error(s"expected Str, got ${Show.show(v)}") }
  private def anyStr(v: Value): String = v match { case StrV(s) => s; case IntV(n) => n.toString; case BoolV(b) => b.toString; case FloatV(d) => d.toString; case _ => Show.show(v) }
  private def bytes(a: List[Value], k: Int): Vector[Byte] = a(k) match { case BytesV(b) => b; case v => sys.error(s"expected Bytes, got ${Show.show(v)}") }
  private def bool(a: List[Value], k: Int): Boolean = a(k) match { case BoolV(b) => b; case v => sys.error(s"expected Bool, got ${Show.show(v)}") }
  private def asData(v: Value): (String, Vector[Value]) = v match { case DataV(t, fs) => (t, fs); case x => sys.error(s"expected Data, got ${Show.show(x)}") }
  private def asMap(v: Value) = v match { case ForeignV(m: collection.mutable.HashMap[Value, Value] @unchecked) => m; case x => sys.error(s"expected Map, got ${Show.show(x)}") }
  private def asArr(v: Value) = v match { case ForeignV(a: collection.mutable.ArrayBuffer[Value] @unchecked) => a; case x => sys.error(s"expected Array, got ${Show.show(x)}") }
  private def asCell(v: Value) = v match { case ForeignV(c: Array[Value] @unchecked) => c; case x => sys.error(s"expected Cell, got ${Show.show(x)}") }

  // Option / list helpers (the ssc0 ADT encoding the kernel speaks)
  private val none: Value = DataV("None", Vector.empty)
  private def some(v: Value): Value = DataV("Some", Vector(v))
  private def listOf(vs: Seq[Value]): Value = vs.foldRight[Value](DataV("Nil", Vector.empty))((x, acc) => DataV("Cons", Vector(x, acc)))
  private def strList(xs: Seq[String]): Value = listOf(xs.map(StrV(_)))
  private def unlist(v: Value): List[Value] = v match
    case DataV("Cons", Vector(h, t)) => h :: unlist(t)
    case DataV("Nil", _) => Nil
    case x => sys.error(s"expected a list, got ${Show.show(x)}")

  private def out(v: Value, ps: java.io.PrintStream): Unit = v match
    case StrV(s) => ps.print(s)
    case _ => ps.print(Show.show(v))

// coreir.encode — serialize an IR-as-Data tree (built by an ssc0 program) to the
// canonical Core IR text (specs/12-ir-format.md). The format stays owned by the kernel
// in ONE place; ssc0 emits IR by building Data and calling this. Tags: IrProg/IrDef/
// IrLit/IrLocal/IrGlobal/IrLam/IrApp/IrLet/IrLetRec/IrIf/IrCtor/IrMatch/IrArm/IrPrim and
// consts IrUnit/IrBool/IrInt/IrBig/IrFloat/IrStr. Lists = Cons/Nil, Option = Some/None.
object IrEncode:
  import Value.*
  def program(v: Value): String = v match
    case DataV("IrProg", Vector(defs, entry)) =>
      val ds = list(defs).map {
        case DataV("IrDef", Vector(StrV(n), b)) => s"(def $n ${term(b)})"
        case x => sys.error(s"coreir.encode: bad IrDef ${Show.show(x)}")
      }
      val defsStr = if ds.isEmpty then "(defs)" else s"(defs ${ds.mkString(" ")})"
      s"(program $defsStr (entry ${term(entry)}))"
    case x => sys.error(s"coreir.encode: expected IrProg, got ${Show.show(x)}")

  private def term(v: Value): String = v match
    case DataV("IrLit", Vector(c))            => s"(lit ${const(c)})"
    case DataV("IrLocal", Vector(IntV(i)))    => s"(local $i)"
    case DataV("IrGlobal", Vector(StrV(n)))   => s"(global $n)"
    case DataV("IrLam", Vector(IntV(ar), b))  => s"(lam $ar ${term(b)})"
    case DataV("IrApp", Vector(f, args))      => s"(app ${term(f)}${list(args).map(x => " " + term(x)).mkString})"
    case DataV("IrLet", Vector(rhs, b))       => s"(let (${list(rhs).map(term).mkString(" ")}) ${term(b)})"
    case DataV("IrLetRec", Vector(lams, b))   => s"(letrec (${list(lams).map(term).mkString(" ")}) ${term(b)})"
    case DataV("IrIf", Vector(c, t, e))       => s"(if ${term(c)} ${term(t)} ${term(e)})"
    case DataV("IrCtor", Vector(StrV(tg), fs))=> s"(ctor $tg${list(fs).map(x => " " + term(x)).mkString})"
    case DataV("IrPrim", Vector(StrV(op), as))=> s"(prim $op${list(as).map(x => " " + term(x)).mkString})"
    case DataV("IrMatch", Vector(s, arms, d)) =>
      val a = list(arms).map {
        case DataV("IrArm", Vector(StrV(tg), IntV(ar), b)) => s"(arm $tg $ar ${term(b)})"
        case x => sys.error(s"coreir.encode: bad IrArm ${Show.show(x)}")
      }.mkString(" ")
      val dd = d match
        case DataV("Some", Vector(t)) => s" (default ${term(t)})"
        case DataV("None", _) => ""
        case x => sys.error(s"coreir.encode: bad default ${Show.show(x)}")
      s"(match ${term(s)} ($a)$dd)"
    case x => sys.error(s"coreir.encode: bad term ${Show.show(x)}")

  private def const(v: Value): String = v match
    case DataV("IrUnit", _)                 => "unit"
    case DataV("IrBool", Vector(BoolV(b)))  => b.toString
    case DataV("IrInt", Vector(IntV(n)))    => s"(int $n)"
    case DataV("IrBig", Vector(BigV(n)))    => s"(big $n)"
    case DataV("IrFloat", Vector(FloatV(d)))=> s"(float ${Writer.floatStr(d)})"
    case DataV("IrStr", Vector(StrV(s)))    => s"(str ${Writer.strLit(s)})"
    case x => sys.error(s"coreir.encode: bad const ${Show.show(x)}")

  private def list(v: Value): List[Value] = v match
    case DataV("Cons", Vector(h, t)) => h :: list(t)
    case DataV("Nil", _) => Nil
    case x => sys.error(s"coreir.encode: expected list, got ${Show.show(x)}")

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
