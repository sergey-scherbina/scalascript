package ssc

// The ssc VM: IR -> ssc -> cpu.
// JIT philosophy (per design): once we know what a node is, we compile it ONCE
// into a closure that does exactly that — no re-dispatch on Term at run time.
// compile(Term): Code  turns the IR into a tree of closures (the "generated
// program"); the trampoline driver runs it with proper tail calls (TCO).

type Env  = List[Value]
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

  // Trampoline: run a compiled Code to a final Value, bouncing tail Calls in
  // CONSTANT STACK (specs/10-core-ir.md invariant 7).
  def run(code0: Code, env0: Env): Value =
    var code = code0; var env = env0
    while true do
      code(env) match
        case Done(v) => return v
        case Call(c, args) =>
          if c.arity != args.length then sys.error(s"arity: ${c.arity} expected, ${args.length} given")
          env = prepend(args, c.env)
          code = c.code
    sys.error("unreachable")

  // Non-tail evaluation of a sub-term = run it to a value.
  inline def value(code: Code, env: Env): Value = run(code, env)

  // Push a frame so the LAST element is index 0 (specs/10-core-ir.md §1.3).
  def prepend(xs: Array[Value], base: Env): Env =
    var e = base; var i = 0
    while i < xs.length do { e = xs(i) :: e; i += 1 }
    e

object Compiler:
  import Value.*, Term.*

  /** Compile a whole program; returns the entry Code (globals captured inside). */
  def compile(p: Program): Code =
    val globals = collection.mutable.HashMap[String, Value]()
    val c = new C(globals)
    // pass 1: lambda defs -> closures (recursion resolves via Global at call time)
    for d <- p.defs do d.body match
      case Lam(ar, b) => globals(d.name) = ClosV(Nil, ar, c.compile(b))
      case _ => ()
    // pass 2: value defs (may reference the lambda globals)
    for d <- p.defs do d.body match
      case Lam(_, _) => ()
      case other => globals(d.name) = Runtime.run(c.compile(other), Nil)
    c.compile(p.entry)

  final class C(globals: collection.mutable.Map[String, Value]):
    def compile(t: Term): Code = t match
      case Lit(k) =>
        val v = constV(k); (_: Env) => Done(v)                       // const folded once
      case Local(i) =>
        (env: Env) => Done(env(i))
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
          rcs.foreach(rc => e = Runtime.value(rc, e) :: e)           // sequential, non-tail rhs
          bc(e)                                                      // body: tail
      case LetRec(lams, body) =>
        val acs = lams.map {
          case Lam(ar, b) => (ar, compile(b))
          case _ => sys.error("letrec binding must be a lam")
        }
        val bc = compile(body)
        (env: Env) =>
          val cs = acs.map { case (ar, code) => ClosV(Nil, ar, code) }
          val envP = Runtime.prepend(cs.toArray, env)                // last binding = index 0
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
              case Some((_, _, body)) => body(Runtime.prepend(fs.toArray, env))  // arm: tail
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
    case "i.add" => a => IntV(int(a, 0) + int(a, 1))
    case "i.sub" => a => IntV(int(a, 0) - int(a, 1))
    case "i.mul" => a => IntV(int(a, 0) * int(a, 1))
    case "i.div" => a => IntV(int(a, 0) / int(a, 1))
    case "i.mod" => a => IntV(int(a, 0) % int(a, 1))
    case "i.neg" => a => IntV(-int(a, 0))
    case "i.and" => a => IntV(int(a, 0) & int(a, 1))
    case "i.or"  => a => IntV(int(a, 0) | int(a, 1))
    case "i.xor" => a => IntV(int(a, 0) ^ int(a, 1))
    case "i.not" => a => IntV(~int(a, 0))
    case "i.shl" => a => IntV(int(a, 0) << int(a, 1))
    case "i.shr" => a => IntV(int(a, 0) >> int(a, 1))
    case "i.ushr"=> a => IntV(int(a, 0) >>> int(a, 1))
    case "i.eq"  => a => BoolV(int(a, 0) == int(a, 1))
    case "i.lt"  => a => BoolV(int(a, 0) <  int(a, 1))
    case "i.le"  => a => BoolV(int(a, 0) <= int(a, 1))
    case "i.gt"  => a => BoolV(int(a, 0) >  int(a, 1))
    case "i.ge"  => a => BoolV(int(a, 0) >= int(a, 1))
    case "not"   => a => BoolV(!bool(a, 0))
    case "io.print"  => a => out(a(0), Console.out); UnitV
    case "io.eprint" => a => out(a(0), Console.err); UnitV
    case _ => sys.error(s"unimplemented primitive: $op")

  private def int(a: List[Value], k: Int): Long = a(k) match
    case IntV(n) => n
    case v => sys.error(s"expected Int, got ${Show.show(v)}")
  private def bool(a: List[Value], k: Int): Boolean = a(k) match
    case BoolV(b) => b
    case v => sys.error(s"expected Bool, got ${Show.show(v)}")
  private def out(v: Value, ps: java.io.PrintStream): Unit = v match
    case StrV(s) => ps.print(s)
    case _ => ps.print(Show.show(v))

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
