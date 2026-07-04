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
  final class IntV(val n: Long) extends Value:
    override def equals(o: Any): Boolean = o match { case iv: IntV => iv.n == n; case _ => false }
    override def hashCode: Int = java.lang.Long.hashCode(n)
    override def toString: String = s"IntV($n)"
  object IntV:
    private val CacheMin  = -128L
    private val CacheMax  = 4096L
    private val cache = Array.tabulate((CacheMax - CacheMin + 1).toInt)(i => new IntV(i + CacheMin))
    def apply(n: Long): IntV =
      if n >= CacheMin && n <= CacheMax then cache((n - CacheMin).toInt) else new IntV(n)
    def unapply(v: IntV): Some[Long] = Some(v.n)
  final case class BigV(n: BigInt)                    extends Value
  final case class FloatV(d: Double)                  extends Value
  final case class StrV(s: String)                    extends Value
  final case class BytesV(b: Vector[Byte])            extends Value
  final case class DataV(tag: String, fields: Vector[Value]) extends Value
  final class ClosV(var env: Env, val arity: Int, val code: Code) extends Value:  // var env: cyclic letrec frames
    // Direct-call fast entry: set by Compiler for simple defs whose body is tryFC-able.
    // Callers can invoke this instead of trampoline to eliminate Done allocs.
    var fcEntry: Option[FastCode.FC] = None
  final case class ForeignV(h: AnyRef)                extends Value
  // Mutable long cell: avoids IntV boxing on every cell.set in tight arithmetic loops.
  // Lowered from `var x: Long = 0` via `lcell.new`.
  final class LongCellV(var v: Long)                  extends Value

object Runtime:
  import Value.*

  // Process argv — the trailing CLI args of `ssc run <file> ARGS...`, exposed to
  // the program through the `io.args` primitive. Set by Main before running.
  var argv: List[String] = Nil

  // Singleton empty env; reused for arity-0 closures to avoid allocation.
  val emptyEnv: Env = Array.empty[Value]

  // Trampoline: run a compiled Code to a final Value, bouncing tail Calls in
  // CONSTANT STACK (specs/10-core-ir.md invariant 7).
  def run(code0: Code, env0: Env): Value =
    var code = code0; var env = env0
    while true do
      code(env) match
        case Done(v) => return v
        case Call(c, args) =>
          if c.arity != args.length then sys.error(s"arity: ${c.arity} expected, ${args.length} given")
          // Fast paths: empty args reuse closure env; empty closure env reuses args (no copy)
          env = if args.isEmpty then c.env else if c.env.isEmpty then args else Runtime.extend(c.env, args)
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
      case Lam(ar, b) =>
        val closV = ClosV(Array.empty[Value], ar, c.compile(b))
        globals(d.name) = closV
        closV.fcEntry = FastCode.tryFC(b, globals)  // set after globals(name) so self-recursive tryFC can resolve the global
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
        val tc = compile(th); val ec = compile(el)
        FastCode.tryFBc(c, globals) match
          // Fast path: condition is pure boolean — no Done/BoolV alloc per call
          case Some(fbc) =>
            (env: Env) => if fbc(env) then tc(env) else ec(env)
          case None =>
            val cc = compile(c)
            (env: Env) => Runtime.value(cc, env) match              // condition: non-tail
              case BoolV(true)  => tc(env)                          // branch: tail (returns Step)
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
        fcs.length match
          case 0 => val v = DataV(tag, Vector.empty); (_: Env) => Done(v)
          case 1 => val fc0 = fcs(0); (env: Env) => Done(DataV(tag, Vector(Runtime.value(fc0, env))))
          case 2 => val fc0 = fcs(0); val fc1 = fcs(1); (env: Env) => Done(DataV(tag, Vector(Runtime.value(fc0, env), Runtime.value(fc1, env))))
          case 3 => val fc0 = fcs(0); val fc1 = fcs(1); val fc2 = fcs(2); (env: Env) => Done(DataV(tag, Vector(Runtime.value(fc0, env), Runtime.value(fc1, env), Runtime.value(fc2, env))))
          case _ => (env: Env) => Done(DataV(tag, fcs.map(fc => Runtime.value(fc, env)).toVector))
      case Match(scrut, arms, default) =>
        val sc = compile(scrut)
        val acs = arms.map(a => (a.tag, a.arity, compile(a.body)))
        val armMap = acs.map { case (t, ar, b) => (t, ar) -> b }.toMap
        val dc = default.map(compile)
        (env: Env) => Runtime.value(sc, env) match                   // scrutinee: non-tail
          case DataV(tag, fs) =>
            armMap.get((tag, fs.length)) match
              case Some(body) =>
                // Avoid fs.toArray (Vector→Array alloc) for the common 0/1/2-field cases
                val extEnv = fs.length match
                  case 0 => env
                  case 1 => Runtime.appendOne(env, fs(0))
                  case 2 => Runtime.extend(env, Array(fs(0), fs(1)))
                  case _ => Runtime.extend(env, fs.toArray)
                body(extEnv)
              case None => dc match
                case Some(d) => d(env)
                case None => sys.error(s"match: no arm for $tag/${fs.length}")
          case v => sys.error(s"match: scrutinee not Data: ${Show.show(v)}")
      case App(fn, args) =>
        // Global-call FC fast path: skip Done/run for the function lookup.
        // Uses tryFC for args (not tryFLC) so FloatV/StrV args pass through unchanged.
        // Uses lazy globals lookup to handle self-recursion (global not set during body compilation).
        val globalFastPath: Option[Code] = fn match
          case Global(g) if args.nonEmpty =>
            args match
              case List(a0) =>
                FastCode.tryFC(a0, globals).map { f0 =>
                  (env: Env) =>
                    val avs = new Array[Value](1); avs(0) = f0(env)
                    globals.getOrElse(g, sys.error(s"unbound global: $g")) match
                      case c: ClosV => Call(c, avs)
                      case v => sys.error(s"app: not a function: ${Show.show(v)}")
                }
              case List(a0, a1) =>
                FastCode.tryFC(a0, globals).flatMap { f0 =>
                  FastCode.tryFC(a1, globals).map { f1 =>
                    (env: Env) =>
                      val avs = new Array[Value](2); avs(0) = f0(env); avs(1) = f1(env)
                      globals.getOrElse(g, sys.error(s"unbound global: $g")) match
                        case c: ClosV => Call(c, avs)
                        case v => sys.error(s"app: not a function: ${Show.show(v)}")
                  }
                }
              case _ => None
          case _ => None
        globalFastPath.getOrElse {
        if args.isEmpty then
          val fc = compile(fn)
          (env: Env) =>
            Runtime.value(fc, env) match
              case c: ClosV => Call(c, Runtime.emptyEnv)             // avoid toArray on empty list
              case v => sys.error(s"app: not a function: ${Show.show(v)}")
        else args match
          // 1-arg fast path: avoid List alloc from acs.map(...).toArray
          case List(a0) =>
            val fc = compile(fn); val ac0 = compile(a0)
            (env: Env) =>
              val fv = Runtime.value(fc, env); val v0 = Runtime.value(ac0, env)
              fv match
                case c: ClosV =>
                  val avs = new Array[Value](1); avs(0) = v0; Call(c, avs)
                case lv @ (DataV("Cons", _) | DataV("Nil", _)) =>
                  v0 match { case IntV(i) => Done(Prims.unlistPub(lv)(i.toInt)); case _ => sys.error("app: list index must be Int") }
                case ForeignV(ab: collection.mutable.ArrayBuffer[?]) =>
                  v0 match { case IntV(i) => Done(ab.asInstanceOf[collection.mutable.ArrayBuffer[Value]](i.toInt)); case _ => sys.error("app: array index must be Int") }
                case DataV(_, fields) =>
                  v0 match { case IntV(i) => Done(fields(i.toInt)); case _ => sys.error("app: DataV index must be Int") }
                case v => sys.error(s"app: not a function: ${Show.show(v)}")
          // 2-arg fast path: avoid List alloc from acs.map(...).toArray
          case List(a0, a1) =>
            val fc = compile(fn); val ac0 = compile(a0); val ac1 = compile(a1)
            (env: Env) =>
              val fv = Runtime.value(fc, env)
              val avs = new Array[Value](2); avs(0) = Runtime.value(ac0, env); avs(1) = Runtime.value(ac1, env)
              fv match
                case c: ClosV => Call(c, avs)
                case v => sys.error(s"app: not a function: ${Show.show(v)}")
          // generic path for 3+ args
          case _ =>
            val fc = compile(fn); val acs = args.map(compile)
            (env: Env) =>
              val fv  = Runtime.value(fc, env)
              val avs = acs.map(ac => Runtime.value(ac, env)).toArray
              fv match
                case c: ClosV => Call(c, avs)
                case v => sys.error(s"app: not a function: ${Show.show(v)}")
        }   // end globalFastPath.getOrElse
      case While(cond, body) =>
        // Try FastCode path: avoids Done boxing and trampoline per iteration
        (FastCode.tryFBc(cond, globals), FastCode.tryFC(body, globals)) match
          case (Some(fbc), Some(fb)) =>
            (env: Env) => { while fbc(env) do fb(env); Done(Value.UnitV) }
          case _ =>
            // Mixed: use tryFBc for condition even if body is slow (saves BoolV alloc per check)
            val fbcOpt = FastCode.tryFBc(cond, globals)
            val bc = compile(body)
            fbcOpt match
              case Some(fbc) =>
                (env: Env) =>
                  while fbc(env) do Runtime.value(bc, env)
                  Done(Value.UnitV)
              case None =>
                val cc = compile(cond)
                (env: Env) =>
                  while (Runtime.value(cc, env) match { case Value.BoolV(b) => b; case _ => false }) do
                    Runtime.value(bc, env)
                  Done(Value.UnitV)                                   // no trampoline bounce per iteration
      case Seq(terms) =>
        // Try FastCode path: prefer FLC-based long-set for arithmetic, then general FC
        val fastOpts = terms.map(t => FastCode.tryFCLongSet(t, globals).orElse(FastCode.tryFC(t, globals)))
        if fastOpts.forall(_.isDefined) then
          val fcs = fastOpts.map(_.get)
          (env: Env) =>
            var last: Value = Value.UnitV
            for fc <- fcs do last = fc(env)
            Done(last)
        else
          val tcs = terms.map(compile)
          (env: Env) =>
            var last: Value = Value.UnitV
            for tc <- tcs do last = Runtime.value(tc, env)           // same env for all, no appendOne
            Done(last)
      case Prim(op, args) =>
        // Fast paths for 1/2/3-arg primitives: avoid List[Value] allocation for args
        args match
          case List(a0) =>
            Prims.resolve1(op) match
              case Some(fn1) =>
                val ac0 = compile(a0)
                (env: Env) => Done(fn1(Runtime.value(ac0, env)))
              case None =>
                val fn = Prims.resolve(op); val ac0 = compile(a0)
                (env: Env) => Done(fn(List(Runtime.value(ac0, env))))
          case List(a0, a1) =>
            Prims.resolve2(op) match
              case Some(fn2) =>
                val ac0 = compile(a0); val ac1 = compile(a1)
                (env: Env) => Done(fn2(Runtime.value(ac0, env), Runtime.value(ac1, env)))
              case None =>
                val fn = Prims.resolve(op); val ac0 = compile(a0); val ac1 = compile(a1)
                (env: Env) => Done(fn(List(Runtime.value(ac0, env), Runtime.value(ac1, env))))
          case List(a0, a1, a2) =>
            Prims.resolve3(op) match
              case Some(fn3) =>
                val ac0 = compile(a0); val ac1 = compile(a1); val ac2 = compile(a2)
                (env: Env) => Done(fn3(Runtime.value(ac0, env), Runtime.value(ac1, env), Runtime.value(ac2, env)))
              case None =>
                // Special fast path: __arith__ with literal op — avoid List alloc on every call
                if op == "__arith__" then a0 match
                  case Lit(Const.CStr(fixedOp)) =>
                    val ac1 = compile(a1); val ac2 = compile(a2)
                    (env: Env) => Done(Prims.arithOp(fixedOp, Runtime.value(ac1, env), Runtime.value(ac2, env)))
                  case _ =>
                    val fn = Prims.resolve(op); val acs = args.map(compile)
                    (env: Env) => Done(fn(acs.map(ac => Runtime.value(ac, env))))
                else
                  val fn = Prims.resolve(op); val acs = args.map(compile)
                  (env: Env) => Done(fn(acs.map(ac => Runtime.value(ac, env))))
          case _ =>                                                    // 0 or 4+ args: generic path
            val fn = Prims.resolve(op); val acs = args.map(compile)
            (env: Env) => Done(fn(acs.map(ac => Runtime.value(ac, env))))

  def constV(c: Const): Value = c match
    case Const.CUnit     => Value.UnitV
    case Const.CBool(b)  => Value.BoolV(b)
    case Const.CInt(n)   => Value.IntV(n)
    case Const.CBig(n)   => Value.BigV(n)
    case Const.CFloat(d) => Value.FloatV(d)
    case Const.CStr(s)   => Value.StrV(s)
    case Const.CBytes(b) => Value.BytesV(b)

// ── FastCode: Value-returning closures (no Done boxing) ──────────────────────
// Used in While/If fast-paths to avoid 20+ Done allocations per iteration.
// Only covers expressions that are provably non-tail (primitives, locals, lits, seq).
object FastCode:
  import Value.*, Term.*

  type FC  = Env => Value         // fast: returns Value directly (no Done wrapping)
  type FLC = Env => Long          // fast long: returns unboxed Long (avoids IntV boxing)
  type FBc = Env => Boolean       // fast bool: avoids BoolV boxing for conditions

  // Explicit Seq FC class — all Seq FCs share ONE class, so the `fb(env)` call site
  // in the fast While loop stays monomorphic regardless of Seq length.
  // `var last` in apply() is a plain JVM local — no ObjectRef boxing.
  final class SeqFastCode(private val fcs: Array[FC], private val n: Int) extends (Env => Value):
    def apply(env: Env): Value =
      var i = 0; var last: Value = Value.UnitV
      while i < n do { last = fcs(i)(env); i += 1 }
      last

  /** Try to compile a term to a FastLongCode (Env => Long), eliminating IntV boxing.
   *  Covers Local lookups from LongCellV/IntV, arithmetic ops, and integer literals. */
  def tryFLC(t: Term, globals: collection.mutable.Map[String, Value]): Option[FLC] = t match
    case Lit(Const.CInt(n)) => Some(_ => n)
    case Local(i) =>
      // Optimistic: assume Local holds an IntV or LongCellV (function params, let-bindings)
      val n = i; Some((env: Env) => env(env.length - 1 - n) match
        case IntV(x) => x
        case lc: LongCellV => lc.v
        case _ => 0L)
    case Prim("lcell.get", List(Local(i))) =>
      val n = i; Some(env => env(env.length - 1 - n).asInstanceOf[LongCellV].v)
    case Prim("cell.get", List(Local(i))) =>
      // Optimistic: read IntV directly from foreign cell (works when cell holds IntV)
      val n = i; Some((env: Env) =>
        val cell = env(env.length - 1 - n).asInstanceOf[ForeignV].h.asInstanceOf[Array[Value]]
        cell(0) match { case IntV(x) => x; case _ => 0L })
    // arr.get — optimistic: array element is an Int; used in tight loops (e.g. array-update)
    case Prim("arr.get", List(a0, a1)) =>
      tryFC(a0, globals).flatMap { fca => tryFLC(a1, globals).map { fci =>
        (env: Env) => fca(env) match
          case ForeignV(ab: collection.mutable.ArrayBuffer[?]) =>
            ab.asInstanceOf[collection.mutable.ArrayBuffer[Value]](fci(env).toInt) match
              case IntV(x) => x; case _ => 0L
          case _ => 0L
      } }
    // fieldAt with literal index — optimistic: field is an Int (DataV with Int fields)
    case Prim("fieldAt", List(recv, Lit(Const.CInt(k)))) =>
      tryFC(recv, globals).map { fcr => (env: Env) =>
        fcr(env) match
          case DataV(_, fields) => fields(k.toInt) match { case IntV(x) => x; case _ => 0L }
          case _ => 0L
      }
    // App(Global) — optimistic: returns 0L for non-Int-returning functions (Float→0L).
    // Safe for lcell.set callers (normSq, Int-valued globals). NOT used for cell.set bodies.
    // Uses fcEntry if available to skip the trampoline (one fewer Done alloc per call).
    // The per-call fca.map alloc is stack-allocated by JVM escape analysis (fresh, short-lived).
    case App(Global(name), args) =>
      val argOpts = args.map(tryFC(_, globals))
      if argOpts.forall(_.isDefined) then
        val fca = argOpts.map(_.get).toArray
        Some((env: Env) =>
          val fn = globals.getOrElse(name, sys.error(s"tryFLC App: unbound global: $name"))
          fn match
            case c: ClosV =>
              val argEnv = if c.env.isEmpty then fca.map(f => f(env): Value) else c.env ++ fca.map(f => f(env): Value)
              c.fcEntry match
                case Some(bodyFC) => bodyFC(argEnv) match { case IntV(x) => x; case _ => 0L }
                case None => Runtime.run(c.code, argEnv) match { case IntV(x) => x; case _ => 0L }
            case _ => 0L
        )
      else None
    case Prim("i.add", List(a0, a1)) =>
      tryFLC(a0, globals).flatMap(f0 => tryFLC(a1, globals).map(f1 => env => f0(env) + f1(env)))
    case Prim("i.sub", List(a0, a1)) =>
      tryFLC(a0, globals).flatMap(f0 => tryFLC(a1, globals).map(f1 => env => f0(env) - f1(env)))
    case Prim("i.mul", List(a0, a1)) =>
      tryFLC(a0, globals).flatMap(f0 => tryFLC(a1, globals).map(f1 => env => f0(env) * f1(env)))
    // __arith__ with literal op string — bridge-generated arithmetic (same as i.xxx but dynamic op)
    case Prim("__arith__", List(Lit(Const.CStr(op)), a0, a1))
        if op == "+" || op == "-" || op == "*" || op == "%" || op == "/" =>
      tryFLC(a0, globals).flatMap { f0 => tryFLC(a1, globals).map { f1 =>
        val fn: (Long, Long) => Long = op match
          case "+" => _ + _; case "-" => _ - _; case "*" => _ * _
          case "%" => _ % _; case "/" => _ / _; case _   => (_, _) => 0L
        (env: Env) => fn(f0(env), f1(env))
      } }
    // __method__("toInt"/"toLong", recv) — common Int conversions, fast-path via FLC (returns Long)
    case Prim("__method__", List(Lit(Const.CStr("toInt")), recv)) =>
      tryFLC(recv, globals).map { fr => env => fr(env).toInt.toLong }
    case Prim("__method__", List(Lit(Const.CStr("toLong")), recv)) =>
      tryFLC(recv, globals).map { fr => env => fr(env) }
    // App(Global).length/size — resolves a global function call and measures the returned
    // string/collection length in a single FLC step.  Specific pattern avoids adding a
    // general App(Global) case to tryFC (which causes JVM JIT interference on other benches).
    case Prim("__method__", List(Lit(Const.CStr(n)), App(Global(fname), fargs)))
        if n == "length" || n == "size" =>
      val argOpts = fargs.map(tryFC(_, globals))
      if argOpts.forall(_.isDefined) then
        val fca = argOpts.map(_.get).toArray
        Some((env: Env) =>
          globals.getOrElse(fname, sys.error(s"FLC AppLen: unbound: $fname")) match
            case c: ClosV =>
              val argEnv = c.env ++ fca.map(f => f(env): Value)
              Runtime.run(c.code, argEnv) match
                case StrV(s)        => s.length.toLong
                case DataV(_, fs)   => fs.length.toLong
                case _              => 0L
            case _ => 0L
        )
      else None
    // __method__("length"/"size", recv) — string/collection length for local/lit receivers.
    case Prim("__method__", List(Lit(Const.CStr(n)), recv)) if n == "length" || n == "size" =>
      tryFC(recv, globals).map { fcr => (env: Env) =>
        fcr(env) match
          case StrV(s)        => s.length.toLong
          case DataV(_, fs)   => fs.length.toLong
          case _              => 0L
      }
    // __method__("_N", recv) — tuple field accessor; returns Long only if the field is Int/Long
    case Prim("__method__", List(Lit(Const.CStr(n)), recv)) if n.matches("_\\d+") =>
      val idx = n.drop(1).toInt - 1
      tryFC(recv, globals).map { fcr => (env: Env) =>
        fcr(env) match
          case DataV(_, fields) => fields(idx) match { case IntV(x) => x; case _ => 0L }
          case _ => 0L
      }
    case _ => None

  /** Try to compile a cell.set to a FastCode that stores a raw Long (no IntV alloc). */
  def tryFCLongSet(t: Term, globals: collection.mutable.Map[String, Value]): Option[FC] = t match
    case Prim("lcell.set", List(Local(c), body)) =>
      tryFLC(body, globals).map { flc =>
        val cn = c
        (env: Env) => { env(env.length - 1 - cn).asInstanceOf[LongCellV].v = flc(env); UnitV }
      }
    case Prim("cell.set", List(Local(c), body)) =>
      // Optimistic: if the cell holds IntV, store new IntV (still 1 alloc but skips boxing chain)
      tryFLC(body, globals).map { flc =>
        val cn = c
        (env: Env) => {
          val cell = env(env.length - 1 - cn).asInstanceOf[ForeignV].h.asInstanceOf[Array[Value]]
          cell(0) = IntV(flc(env))   // still 1 IntV alloc (but avoids nested IntV chain)
          UnitV
        }
      }
    case _ => None

  /** Float-safe FC for arm bodies and cell.set values: for __arith__, uses arithOp directly
   *  (correct for Float operands) instead of the FLC-first shortcut (which coerces Float→0L). */
  def tryFCValue(t: Term, globals: collection.mutable.Map[String, Value]): Option[FC] = t match
    case Prim("__arith__", List(Lit(Const.CStr(op)), a0, a1)) =>
      tryFCValue(a0, globals).flatMap { fc0 => tryFCValue(a1, globals).map { fc1 =>
        (env: Env) => Prims.arithOp(op, fc0(env), fc1(env)): Value
      } }
    case _ => tryFC(t, globals)

  /** Try to compile a term to a FastCode.  Returns None if the term
   *  requires a tail call (Lam, App, LetRec, Match with complex arms). */
  def tryFC(t: Term, globals: collection.mutable.Map[String, Value]): Option[FC] = t match
    case Lit(k) =>
      val v = Compiler.constV(k); Some(_ => v)
    case Local(i) =>
      val n = i; Some(env => env(env.length - 1 - n))
    case Global(g) =>
      Some(_ => globals.getOrElse(g, sys.error(s"unbound global: $g")))
    // lcell.get: return IntV(c.v) but for FC callers who need a Value
    case Prim("lcell.get", List(Local(i))) =>
      val n = i; Some((env: Env) => IntV(env(env.length - 1 - n).asInstanceOf[LongCellV].v))
    // cell.get: return IntV from ForeignV cell
    case Prim("cell.get", List(Local(i))) =>
      val n = i; Some((env: Env) =>
        env(env.length - 1 - n).asInstanceOf[ForeignV].h.asInstanceOf[Array[Value]](0) match
          case v: IntV => v; case v => v)
    // lcell.set: delegate to tryFCLongSet (lcell always holds Long — safe)
    case Prim("lcell.set", _) =>
      tryFCLongSet(t, globals)
    // cell.set: Float-safe body evaluation.
    // NOT using tryFCLongSet: FLC coerces Float→0L (wrong for var x: Double cells).
    // For __arith__ bodies, go straight to arithOp (handles FloatV+FloatV → FloatV).
    // App args handled via a LOCAL resolveArg — NOT exposed to global tryFC — so the
    // resulting App-FC class never appears in SeqFastCode and causes no JIT call-site
    // pollution for unrelated benchmarks (instance-field, mutual-recursion).
    case Prim("cell.set", List(Local(c), body)) =>
      // resolveArg: Float-safe argument compilation for cell.set.
      // Handles App inline (not in global tryFC) to avoid JIT call-site pollution in SeqFastCode.
      // Uses fcEntry for App targets: skips trampoline, direct FC call.
      def resolveArg(t: Term): Option[FC] = t match
        case App(Global(g), appArgs) =>
          val appArgFCs = appArgs.map(tryFC(_, globals))
          if appArgFCs.forall(_.isDefined) then
            val fca = appArgFCs.map(_.get).toArray; val gn = g
            // Compile-time fast path: if the callee's fcEntry is already set (defs compiled in
            // pass 1 before any call-site), capture it and use a pre-allocated argEnv.
            // Safe: bodyFC is pure (no trampoline, no mutation of argEnv), runs synchronously.
            globals.get(g).collect { case closV: ClosV if closV.fcEntry.isDefined && closV.env.isEmpty =>
              val bodyFC = closV.fcEntry.get
              val sharedArgEnv = new Array[Value](fca.length)
              (env: Env) =>
                var i = 0; while i < fca.length do { sharedArgEnv(i) = fca(i)(env); i += 1 }
                bodyFC(sharedArgEnv)
            }.orElse(Some((env: Env) =>
              globals.getOrElse(gn, sys.error(s"cell.set: unbound: $gn")) match
                case closV: ClosV =>
                  val argEnv = if closV.env.isEmpty then fca.map(f => f(env): Value)
                               else closV.env ++ fca.map(f => f(env): Value)
                  closV.fcEntry match
                    case Some(bodyFC) => bodyFC(argEnv)
                    case None         => Runtime.run(closV.code, argEnv)
                case _ => sys.error("cell.set: not a function")
            ))
          else None
        case _ => tryFCValue(t, globals)
      val fcBodyOpt: Option[FC] = body match
        case Prim("__arith__", List(Lit(Const.CStr(op)), a0, a1)) =>
          resolveArg(a0).flatMap { fc0 =>
            resolveArg(a1).map { fc1 =>
              (env: Env) => Prims.arithOp(op, fc0(env), fc1(env)): Value
            }
          }
        case _ => tryFCValue(body, globals)
      fcBodyOpt.map { fcBody =>
        val cn = c
        (env: Env) =>
          val cell = env(env.length - 1 - cn).asInstanceOf[ForeignV].h.asInstanceOf[Array[Value]]
          cell(0) = fcBody(env)
          UnitV
      }
    // __arith__ with literal op — try FLC (unboxed Long) first, wrap result in IntV
    case Prim("__arith__", List(Lit(Const.CStr(op)), a0, a1)) =>
      val flcOpt: Option[FC] = tryFLC(t, globals).map { flc => (env: Env) => IntV(flc(env)): Value }
      flcOpt orElse
        // Non-numeric ops (++, string concat, etc.) — fall through to general dispatch
        tryFC(a0, globals).flatMap { fc0 => tryFC(a1, globals).map { fc1 =>
          (env: Env) => Prims.arithOp(op, fc0(env), fc1(env)): Value
        } }
    // __method__("foreach", list, lambda) fast path: traverse Cons/Nil list without
    // materialising a Vector (avoids unlist() O(n) alloc per call).
    case Prim("__method__", Lit(Const.CStr("foreach")) :: recv :: lambdaArg :: Nil) =>
      tryFC(recv, globals).flatMap { fcr =>
        // Inline-body path for Lam(1, body): skip closure creation + trampoline per element.
        // fcb receives extended env with the list element appended.
        val inlinePath: Option[FC] = lambdaArg match
          case Lam(1, lambdaBody) =>
            tryFC(lambdaBody, globals).map { fcb =>
              (env: Env) =>
                var cur = fcr(env)
                while cur.isInstanceOf[DataV] && cur.asInstanceOf[DataV].tag == "Cons" do
                  val cons = cur.asInstanceOf[DataV]
                  fcb(Runtime.appendOne(env, cons.fields(0)))
                  cur = cons.fields(1)
                UnitV
            }
          case _ => None
        inlinePath orElse
          // ClosV path for Global-referenced lambdas
          tryFC(lambdaArg, globals).map { fcl =>
            (env: Env) =>
              val lam = fcl(env).asInstanceOf[ClosV]
              var cur = fcr(env)
              while cur.isInstanceOf[DataV] && cur.asInstanceOf[DataV].tag == "Cons" do
                val cons = cur.asInstanceOf[DataV]
                Runtime.run(lam.code, Runtime.appendOne(lam.env, cons.fields(0)))
                cur = cons.fields(1)
              UnitV
          }
      }
    // __method__ with literal method name — try FLC for Int conversions, else general dispatch
    case Prim("__method__", Lit(Const.CStr(m)) :: recv :: args) =>
      val flcOpt: Option[FC] = tryFLC(t, globals).map { flc => (env: Env) => IntV(flc(env)): Value }
      flcOpt orElse
        tryFC(recv, globals).flatMap { fcr =>
          val argOpts = args.map(tryFC(_, globals))
          if argOpts.forall(_.isDefined) then
            val fca = argOpts.map(_.get)
            Some((env: Env) => Prims.methodOp(m, fcr(env), fca.map(f => f(env))): Value)
          else None
        }
    case Prim(op, List(a0)) =>
      Prims.resolve1(op).flatMap { fn1 =>
        tryFC(a0, globals).map { fc0 => env => fn1(fc0(env)) }
      }
    case Prim(op, List(a0, a1)) =>
      Prims.resolve2(op).flatMap { fn2 =>
        tryFC(a0, globals).flatMap { fc0 =>
          tryFC(a1, globals).map { fc1 => env => fn2(fc0(env), fc1(env)) }
        }
      }
    case Prim(op, List(a0, a1, a2)) =>
      Prims.resolve3(op).flatMap { fn3 =>
        tryFC(a0, globals).flatMap { fc0 =>
          tryFC(a1, globals).flatMap { fc1 =>
            tryFC(a2, globals).map { fc2 => env => fn3(fc0(env), fc1(env), fc2(env)) }
          }
        }
      }
    case Seq(terms) =>
      val opts = terms.map(tryFC(_, globals))
      if opts.forall(_.isDefined) then
        // Direct captures for 1/2/3 terms: JIT inlines f0/f1/f2 as known types.
        // SeqFastCode fallback for n≥4 keeps class diversity bounded.
        val fcsArr = opts.map(_.get).toArray
        fcsArr.length match
          case 1 => val f0 = fcsArr(0); Some(env => f0(env))
          case 2 => val f0 = fcsArr(0); val f1 = fcsArr(1); Some(env => { f0(env); f1(env) })
          case 3 => val f0 = fcsArr(0); val f1 = fcsArr(1); val f2 = fcsArr(2); Some(env => { f0(env); f1(env); f2(env) })
          case n => Some(new SeqFastCode(fcsArr, n))
      else None
    case If(c, th, el) =>
      tryFC(c, globals).flatMap { fcc =>
        tryFC(th, globals).flatMap { fct =>
          tryFC(el, globals).map { fce =>
            env => (fcc(env) match { case BoolV(b) => b; case _ => false }) match
              case true  => fct(env)
              case false => fce(env)
          }
        }
      }
    case Let(rhs, body) =>
      val rOpts = rhs.map(tryFC(_, globals))
      if rOpts.forall(_.isDefined) then
        val rFcs = rOpts.map(_.get)
        tryFC(body, globals).map { fbody =>
          env =>
            var e = env
            for rfc <- rFcs do e = Runtime.appendOne(e, rfc(e))
            fbody(e)
        }
      else None
    case Ctor(tag, fields) =>
      val opts = fields.map(tryFC(_, globals))
      if opts.forall(_.isDefined) then
        val fcs = opts.map(_.get)
        // Constant Ctor: all fields are Lit → precompute once, return cached
        if fields.forall(_.isInstanceOf[Lit]) then
          val precomputed = DataV(tag, fcs.map(fc => fc(Array.empty)).toVector)
          Some(_ => precomputed)
        else fcs.length match
          // Inline Vector(...) construction to avoid intermediate Range/Seq allocs
          case 1 => val fc0 = fcs(0); Some(env => DataV(tag, Vector(fc0(env))))
          case 2 => val fc0 = fcs(0); val fc1 = fcs(1); Some(env => DataV(tag, Vector(fc0(env), fc1(env))))
          case 3 => val fc0 = fcs(0); val fc1 = fcs(1); val fc2 = fcs(2); Some(env => DataV(tag, Vector(fc0(env), fc1(env), fc2(env))))
          case n => val fcsArr = fcs.toArray; Some(env => DataV(tag, (0 until n).map(i => fcsArr(i)(env)).toVector))
      else None
    // __arith__("++") — tuple/string concat fast-path (avoids full trampoline for DataV++)
    case Prim("__arith__", List(Lit(Const.CStr("++")), a0, a1)) =>
      // Ctor ++ Ctor fast path: build result DataV directly from combined field FCs (no intermediate DataV).
      // Avoids: (1) creating an intermediate LHS DataV, (2) a Vector.++ call per iteration.
      val ctorFused: Option[FC] = (a0, a1) match
        case (Ctor(_, lf), Ctor(_, rf)) =>
          val allOpts = (lf ++ rf).map(tryFC(_, globals))
          if allOpts.forall(_.isDefined) then
            val fcs = allOpts.map(_.get).toArray
            val n   = fcs.length
            val tag = s"Tuple$n"
            Some(n match
              case 4 =>
                val f0 = fcs(0); val f1 = fcs(1); val f2 = fcs(2); val f3 = fcs(3)
                (env: Env) => DataV(tag, Vector(f0(env), f1(env), f2(env), f3(env)))
              case 2 =>
                val f0 = fcs(0); val f1 = fcs(1)
                (env: Env) => DataV(tag, Vector(f0(env), f1(env)))
              case _ =>
                (env: Env) => DataV(tag, fcs.map(f => f(env): Value).toVector)
            )
          else None
        case _ => None
      ctorFused.orElse {
        tryFC(a0, globals).flatMap { f0 => tryFC(a1, globals).map { f1 =>
          // If RHS is constant (all-Lit Ctor), precompute once and capture in closure
          val isConstRHS = a1.isInstanceOf[Ctor] && a1.asInstanceOf[Ctor].fields.forall(_.isInstanceOf[Lit])
          if isConstRHS then
            val constR = f1(Array.empty)
            (env: Env) =>
              (f0(env), constR) match
                case (DataV(lt, lf), DataV(rt, rf)) =>
                  val combined = lf ++ rf; DataV(s"Tuple${combined.length}", combined)
                case (StrV(l), StrV(r)) => StrV(l + r)
                case (l, r) => Prims.arithOp("++", l, r)
          else
            (env: Env) =>
              (f0(env), f1(env)) match
                case (DataV(lt, lf), DataV(rt, rf)) if lt.startsWith("Tuple") && rt.startsWith("Tuple") =>
                  val combined = lf ++ rf; DataV(s"Tuple${combined.length}", combined)
                case (StrV(l), StrV(r))  => StrV(l + r)
                case (StrV(l), IntV(r))  => StrV(l + r.toString)
                case (StrV(l), v)        => StrV(l + Show.show(v))
                case (l, r)              => Prims.arithOp("++", l, r)
        } }
      }
    case Match(scrut, arms, default) =>
      // Fast match: compile scrutinee and ALL arm bodies (using float-safe tryFCValue).
      // Returns None if any arm body isn't FC-able (e.g. recursive App, LetRec).
      tryFC(scrut, globals).flatMap { fcScrut =>
        val armFCOpts = arms.map(arm => tryFCValue(arm.body, globals).map(fcBody => (arm.tag, arm.arity, fcBody)))
        val defFCOpt  = default.map(d => tryFCValue(d, globals))
        if armFCOpts.forall(_.isDefined) && (default.isEmpty || defFCOpt.exists(_.isDefined)) then
          val armFCs = armFCOpts.map(_.get)
          val armMap = armFCs.map { case (tag, ar, fcBody) => tag -> (ar, fcBody) }.toMap
          val defFC  = defFCOpt.flatten
          Some((env: Env) =>
            fcScrut(env) match
              case DataV(tag, fs) =>
                armMap.get(tag) match
                  case Some((ar, fcBody)) =>
                    val extEnv = ar match
                      case 0 => env
                      case 1 => Runtime.appendOne(env, fs(0))
                      case 2 => Runtime.extend(env, Array(fs(0), fs(1)))
                      case _ => Runtime.extend(env, fs.toArray)
                    fcBody(extEnv)
                  case None => defFC.map(_(env)).getOrElse(Value.UnitV)
              case _ => defFC.map(_(env)).getOrElse(Value.UnitV)
          )
        else None
      }
    // Lam in while body: compile body once; create ClosV capturing current env per call.
    // This allows `shapes.foreach(s => ...)` in a while loop to fast-compile the outer loop.
    case Lam(arity, body) =>
      val bodyC = Compiler.C(globals).compile(body)
      val ar = arity
      Some((env: Env) => ClosV(env, ar, bodyC))
    case _ => None

  /** tryFC with App(Global) support, for computing fcEntry of mutually-recursive functions.
   *  selfName: blocks App(Global(selfName)) to prevent direct self-recursion
   *  (self-recursive defs use the trampoline for TCO safety).
   *  App(Global) FCs capture the target ClosV directly — so fcEntry updates in the
   *  sibling def are visible at runtime without re-compilation. */
  def tryFCMutual(t: Term, globals: collection.mutable.Map[String, Value], selfName: String): Option[FC] = t match
    case App(Global(name), args) if name != selfName =>
      globals.get(name).collect { case closV: ClosV if closV.env.isEmpty =>
        val argOpts = args.map(a => tryFCMutual(a, globals, selfName))
        if argOpts.forall(_.isDefined) then
          val fca = argOpts.map(_.get).toArray
          Some((env: Env) =>
            val argEnv = fca.map(f => f(env): Value)
            closV.fcEntry match
              case Some(bodyFC) => bodyFC(argEnv)
              case None => Runtime.run(closV.code, argEnv)
          )
        else None
      }.flatten
    case If(c, th, el) =>
      tryFBc(c, globals).flatMap { fcc =>
        tryFCMutual(th, globals, selfName).flatMap { fct =>
          tryFCMutual(el, globals, selfName).map { fce =>
            (env: Env) => (fcc(env)) match
              case true  => fct(env)
              case false => fce(env)
          }
        }
      }
    case _ => tryFC(t, globals)

  /** Try to compile a condition term to a FastBoolCode (avoids BoolV allocation). */
  def tryFBc(t: Term, globals: collection.mutable.Map[String, Value]): Option[FBc] = t match
    // __arith__ comparisons — bridge generates these; fast path via unboxed Long comparison
    case Prim("__arith__", List(Lit(Const.CStr(op)), a0, a1))
        if op == "<" || op == "<=" || op == ">" || op == ">=" || op == "==" || op == "!=" =>
      tryFLC(a0, globals).flatMap { f0 => tryFLC(a1, globals).map { f1 =>
        val fn: (Long, Long) => Boolean = op match
          case "<"  => _ < _; case "<=" => _ <= _; case ">"  => _ > _
          case ">=" => _ >= _; case "==" => _ == _; case "!=" => _ != _; case _ => (_, _) => false
        (env: Env) => fn(f0(env), f1(env))
      } }
    case Prim(op, List(a0, a1)) if op.startsWith("i.l") || op.startsWith("i.g") || op == "i.eq" =>
      // Integer comparisons: try FLC first to avoid IntV boxing of operands
      tryFLC(a0, globals).flatMap { flc0 =>
        tryFLC(a1, globals).map { flc1 =>
          val cmpFn: (Long, Long) => Boolean = op match
            case "i.lt" => _ < _
            case "i.le" => _ <= _
            case "i.gt" => _ > _
            case "i.ge" => _ >= _
            case "i.eq" => _ == _
            case _      => (_, _) => false
          (env: Env) => cmpFn(flc0(env), flc1(env))
        }
      } orElse {
        // Fallback to Value-based comparison
        val fn2bOpt: Option[(Value, Value) => Boolean] = op match
          case "i.lt"  => Some { case (IntV(x), IntV(y)) => x < y;  case _ => false }
          case "i.le"  => Some { case (IntV(x), IntV(y)) => x <= y; case _ => false }
          case "i.gt"  => Some { case (IntV(x), IntV(y)) => x > y;  case _ => false }
          case "i.ge"  => Some { case (IntV(x), IntV(y)) => x >= y; case _ => false }
          case "i.eq"  => Some { case (IntV(x), IntV(y)) => x == y; case (a,b) => a==b }
          case _       => None
        fn2bOpt.flatMap { fn2b =>
          tryFC(a0, globals).flatMap { fc0 =>
            tryFC(a1, globals).map { fc1 => (env: Env) => fn2b(fc0(env), fc1(env)) }
          }
        }
      }
    case Prim(op, List(a0, a1)) =>
      val fn2bOpt: Option[(Value, Value) => Boolean] = op match
        case "f.lt"  => Some { case (FloatV(x), FloatV(y)) => x < y; case _ => false }
        case "f.le"  => Some { case (FloatV(x), FloatV(y)) => x <= y; case _ => false }
        case "f.gt"  => Some { case (FloatV(x), FloatV(y)) => x > y; case _ => false }
        case "f.ge"  => Some { case (FloatV(x), FloatV(y)) => x >= y; case _ => false }
        case "f.eq"  => Some { case (FloatV(x), FloatV(y)) => x == y; case _ => false }
        case "seq"   => Some { case (StrV(a), StrV(b)) => a == b; case _ => false }
        case _       => None
      fn2bOpt.flatMap { fn2b =>
        tryFC(a0, globals).flatMap { fc0 =>
          tryFC(a1, globals).map { fc1 => (env: Env) => fn2b(fc0(env), fc1(env)) }
        }
      }
    case Prim("not", List(a0)) =>
      tryFBc(a0, globals).map { fbc => (env: Env) => !fbc(env) }
    case _ =>
      // Fallback: try FC and unbox BoolV
      tryFC(t, globals).map { fc => (env: Env) => fc(env) match { case BoolV(b) => b; case _ => false } }

// ── Plugin registry — lets external code add Prim handlers (v2-plugin-bridge) ──
// External bridge modules call V2PluginRegistry.register(op, fn) at startup
// to supply handlers for Prim ops unknown to the built-in Prims table.
// Prims.resolve falls back here before throwing "unimplemented primitive".
object V2PluginRegistry:
  type Fn = List[Value] => Value
  private val handlers = collection.mutable.HashMap[String, Fn]()
  def register(op: String, fn: Fn): Unit = handlers(op) = fn
  def lookup(op: String): Option[Fn] = handlers.get(op)

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
    case "__isTag__" => a => a(0) match
      case DataV(t, fs) => BoolV(t == str(a, 1) && fs.length == int(a, 2).toInt)
      case _            => BoolV(false)
    // Map (Foreign, mutable; keys/values are Values)
    case "map.new"  => _ => ForeignV(collection.mutable.HashMap[Value, Value]())
    case "map.get"  => a => asMap(a(0)).get(a(1)).fold(none)(some)
    case "map.put"  => a => asMap(a(0)).update(a(1), a(2)); UnitV
    case "map.has"  => a => BoolV(asMap(a(0)).contains(a(1)))
    case "map.del"  => a => asMap(a(0)).remove(a(1)); UnitV
    case "map.keys" => a => listOf(asMap(a(0)).keys.toSeq)
    case "map.size" => a => IntV(asMap(a(0)).size.toLong)
    // Array (Foreign, growable)
    case "__mk_arr__" => a => ForeignV(collection.mutable.ArrayBuffer.from(a))
    case "arr.new"   => _ => ForeignV(collection.mutable.ArrayBuffer[Value]())
    case "arr.len"   => a => IntV(asArr(a(0)).length.toLong)
    case "arr.get"   => a => asArr(a(0))(int(a, 1).toInt)
    case "arr.set"   => a => asArr(a(0))(int(a, 1).toInt) = a(2); UnitV
    case "arr.push"  => a => asArr(a(0)) += a(1); UnitV
    case "arr.pop"   => a => asArr(a(0)).remove(asArr(a(0)).length - 1)
    case "arr.slice" => a => ForeignV(collection.mutable.ArrayBuffer.from(asArr(a(0)).slice(int(a, 1).toInt, int(a, 2).toInt)))
    // Cell (Foreign, single mutable ref)
    case "__mk_method_obj__" => a =>
      val pairs = a.grouped(2).map { case List(StrV(k), v) => k -> v; case g => g(0).toString -> g(1) }.toList
      ForeignV(collection.immutable.Map.from(pairs))
    case "__math_obj__" => _ => ForeignV("__math__")
    case "cell.new" => a => ForeignV(scala.Array[Value](a(0)))
    case "cell.get" => a => asCell(a(0))(0)
    case "cell.set" => a => asCell(a(0))(0) = a(1); UnitV
    // Long cell: mutable long without Value boxing per store (for tight integer loops)
    case "lcell.new" => a => new LongCellV(asInt1(a(0)))
    case "lcell.get" => a => IntV(a(0).asInstanceOf[LongCellV].v)
    case "lcell.set" => a => a(0).asInstanceOf[LongCellV].v = asInt1(a(1)); UnitV
    // I/O [eff]
    case "io.print"   => a => out(a(0), Console.out); UnitV
    case "io.println" => a => out(a(0), Console.out); Console.out.println(); UnitV
    case "io.eprint"  => a => out(a(0), Console.err); UnitV
    case "io.args"   => _ => strList(Runtime.argv)
    case "io.nanoTime"  => _ => IntV(System.nanoTime())
    case "io.readFile"  => a => BytesV(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(str(a, 0))).toVector)
    case "io.writeFile" => a => java.nio.file.Files.write(java.nio.file.Path.of(str(a, 0)), bytes(a, 1).toArray); UnitV
    case "io.env"  => a => sys.env.get(str(a, 0)).fold(none)(s => some(StrV(s)))
    case "io.exit" => a => sys.exit(int(a, 0).toInt); UnitV
    // Core IR serialization: a Data-tree (IrProg/IrLam/… built in ssc0) -> canonical bytecode
    case "coreir.encode" => a => StrV(IrEncode.program(a(0)))
    // ── FrontendBridge collection factories ────────────────────────────────────────
    // Map(k->v, ...) factory: args are Tuple2 pairs (DataV("Tuple2", [k, v]))
    case "__mk_map__" => a =>
      val m = collection.mutable.HashMap[Value, Value]()
      a.foreach {
        case DataV("Tuple2", Vector(k, v)) => m(k) = v
        case DataV("->", Vector(k, v))     => m(k) = v
        case pair => sys.error(s"Map factory: expected k->v pair, got ${Show.show(pair)}")
      }
      ForeignV(m)
    // ── Dynamic dispatch primitives (for FrontendBridge — no static type info) ────
    // __arith__(op, lhs, rhs): type-dispatched arithmetic/comparison/string concat.
    // Covers the cases that ssc1c maps to typed i.*/f.* ops.
    case "__arith__" => a =>
      val op = str(a, 0)
      if op == "->" then DataV("Tuple2", Vector(a(1), a(2)))
      else (a(1), a(2)) match
        case (IntV(x), IntV(y)) => op match
          case "+"   => IntV(x + y);   case "-"   => IntV(x - y);   case "*"  => IntV(x * y)
          case "/"   => IntV(x / y);   case "%"   => IntV(x % y)
          case "==" => BoolV(x == y); case "!=" => BoolV(x != y)
          case "<"   => BoolV(x < y);  case "<=" => BoolV(x <= y)
          case ">"   => BoolV(x > y);  case ">=" => BoolV(x >= y)
          case "&"   => IntV(x & y);   case "|"   => IntV(x | y);   case "^"  => IntV(x ^ y)
          case "<<"  => IntV(x << y.toInt); case ">>" => IntV(x >> y.toInt); case ">>>" => IntV(x >>> y.toInt)
          case "++"  => StrV(x.toString + y.toString)
          case "to"    => { val nilV: Value = DataV("Nil", Vector.empty); (x to y).foldRight(nilV)((i, acc) => DataV("Cons", Vector(IntV(i), acc))) }
          case "until" => { val nilV: Value = DataV("Nil", Vector.empty); (x until y).foldRight(nilV)((i, acc) => DataV("Cons", Vector(IntV(i), acc))) }
          case _     => sys.error(s"__arith__: unknown op $op for Int")
        case (FloatV(x), FloatV(y)) => op match
          case "+"  => FloatV(x + y); case "-"  => FloatV(x - y); case "*"  => FloatV(x * y)
          case "/"  => FloatV(x / y); case "%"  => FloatV(x % y)
          case "==" => BoolV(x == y); case "!=" => BoolV(x != y)
          case "<"  => BoolV(x < y);  case "<=" => BoolV(x <= y)
          case ">"  => BoolV(x > y);  case ">=" => BoolV(x >= y)
          case "++" => StrV(x.toString + y.toString)
          case _    => sys.error(s"__arith__: unknown op $op for Float")
        case (IntV(x), FloatV(y)) => op match  // widening
          case "+" => FloatV(x + y); case "-" => FloatV(x - y); case "*" => FloatV(x * y)
          case "/" => FloatV(x / y); case "==" => BoolV(x == y); case "!=" => BoolV(x != y)
          case "<" => BoolV(x < y); case "<=" => BoolV(x <= y); case ">" => BoolV(x > y); case ">=" => BoolV(x >= y)
          case _ => sys.error(s"__arith__: unknown op $op for Int+Float")
        case (FloatV(x), IntV(y)) => op match
          case "+" => FloatV(x + y); case "-" => FloatV(x - y); case "*" => FloatV(x * y)
          case "/" => FloatV(x / y); case "==" => BoolV(x == y); case "!=" => BoolV(x != y)
          case "<" => BoolV(x < y); case "<=" => BoolV(x <= y); case ">" => BoolV(x > y); case ">=" => BoolV(x >= y)
          case _ => sys.error(s"__arith__: unknown op $op for Float+Int")
        case (StrV(x), StrV(y)) => op match
          case "++" | "+" => StrV(x + y)
          case "==" => BoolV(x == y); case "!=" => BoolV(x != y)
          case "<" => BoolV(x < y); case "<=" => BoolV(x <= y)
          case ">" => BoolV(x > y); case ">=" => BoolV(x >= y)
          case _    => sys.error(s"__arith__: unknown op $op for String")
        case (StrV(x), IntV(y)) => op match
          case "*"        => StrV(x * y.toInt)
          case "+" | "++" => StrV(x + y.toString)
          case _   => sys.error(s"__arith__: unknown op $op for String+Int")
        case (IntV(x), StrV(y)) => op match
          case "+" | "++" => StrV(x.toString + y)
          case _   => sys.error(s"__arith__: unknown op $op for Int+String")
        case (FloatV(x), StrV(y)) => op match
          case "+" | "++" => StrV(x.toString + y)
          case _   => sys.error(s"__arith__: unknown op $op for Float+String")
        case (StrV(x), FloatV(y)) => op match
          case "+" | "++" => StrV(x + y.toString)
          case _   => sys.error(s"__arith__: unknown op $op for String+Float")
        case (BoolV(x), BoolV(y)) => op match
          case "==" => BoolV(x == y); case "!=" => BoolV(x != y)
          case _    => sys.error(s"__arith__: op $op not valid for Bool")
        case (lv, rv) if isList(lv) && op == "++" => listOf(unlist(lv) ++ unlist(rv))
        // Tuple concatenation: (a,b) ++ (c,d) = (a,b,c,d)
        case (DataV(lt, lf), DataV(rt, rf))
            if op == "++" && lt.startsWith("Tuple") && rt.startsWith("Tuple") =>
          val combined = lf ++ rf
          DataV(s"Tuple${combined.length}", combined)
        case (lv, rv) => op match
          case "==" => BoolV(lv == rv); case "!=" => BoolV(lv != rv)
          case "++" | "+" => StrV(anyStr(lv) + anyStr(rv))
          case "->" => DataV("Tuple2", Vector(lv, rv))  // k -> v pair
          case _    => sys.error(s"__arith__: type mismatch for $op: ${Show.show(lv)}, ${Show.show(rv)}")
    // __unary__(op, val): type-dispatched unary operators
    case "__unary__" => a =>
      val op = str(a, 0)
      a(1) match
        case IntV(n)   => op match { case "-" => IntV(-n); case "~" => IntV(~n); case _ => sys.error(s"__unary__: $op on Int") }
        case FloatV(d) => op match { case "-" => FloatV(-d); case _ => sys.error(s"__unary__: $op on Float") }
        case BoolV(b)  => op match { case "!" => BoolV(!b); case _ => sys.error(s"__unary__: $op on Bool") }
        case v => sys.error(s"__unary__: $op on ${Show.show(v)}")
    // __eq__(a, b): structural equality (works on all Value types including ADTs)
    case "__eq__" => a => BoolV(a(0) == a(1))
    // __method__(name, receiver, args...): method dispatch on receiver type
    case "__method__" => a =>
      val name = str(a, 0)
      val recv = a(1)
      val margs = a.drop(2).toList
      (recv, name, margs) match
        case (IntV(n), "toString", Nil)      => StrV(n.toString)
        case (IntV(n), "toInt", Nil)         => IntV(n.toInt.toLong)    // truncate to 32-bit
        case (IntV(n), "toLong", Nil)        => IntV(n)
        case (IntV(n), "toByte", Nil)        => IntV(n.toByte.toLong)
        case (IntV(n), "toShort", Nil)       => IntV(n.toShort.toLong)
        case (IntV(n), "toChar", Nil)        => IntV(n & 0xffffL)
        case (IntV(n), "toDouble", Nil)      => FloatV(n.toDouble)
        case (IntV(n), "toFloat", Nil)       => FloatV(n.toDouble)
        case (IntV(n), "abs", Nil)           => IntV(math.abs(n))
        case (FloatV(d), "toString", Nil)    => StrV(Writer.floatStr(d))
        case (FloatV(d), "toInt", Nil)       => IntV(d.toLong)
        case (FloatV(d), "toLong", Nil)      => IntV(d.toLong)
        case (StrV(s), "length", Nil)        => IntV(s.length.toLong)
        case (StrV(s), "size", Nil)          => IntV(s.length.toLong)
        case (StrV(s), "isEmpty", Nil)       => BoolV(s.isEmpty)
        case (StrV(s), "nonEmpty", Nil)      => BoolV(s.nonEmpty)
        case (StrV(s), "toInt", Nil)         => s.toLongOption.fold(none)(n => some(IntV(n)))
        case (StrV(s), "toDouble", Nil)      => s.toDoubleOption.fold(none)(d => some(FloatV(d)))
        case (StrV(s), "trim", Nil)          => StrV(s.trim)
        case (StrV(s), "toUpperCase", Nil)   => StrV(s.toUpperCase)
        case (StrV(s), "toLowerCase", Nil)   => StrV(s.toLowerCase)
        case (StrV(s), "reverse", Nil)       => StrV(s.reverse)
        case (StrV(s), "split", List(StrV(d))) => {
          val parts = s.split(java.util.regex.Pattern.quote(d), -1)
          val nilV: Value = DataV("Nil", Vector.empty)
          parts.foldRight(nilV)((x, acc) => DataV("Cons", Vector(StrV(x), acc)))
        }
        case (StrV(s), "contains", List(StrV(sub))) => BoolV(s.contains(sub))
        case (StrV(s), "startsWith", List(StrV(pfx))) => BoolV(s.startsWith(pfx))
        case (StrV(s), "endsWith", List(StrV(sfx)))   => BoolV(s.endsWith(sfx))
        case (StrV(s), "substring", List(IntV(i)))      => StrV(s.substring(i.toInt))
        case (StrV(s), "substring", List(IntV(i), IntV(j))) => StrV(s.substring(i.toInt, j.toInt))
        case (StrV(s), "charAt", List(IntV(i)))         => IntV(s.charAt(i.toInt).toLong)
        case (StrV(s), "indexOf", List(StrV(sub)))      => IntV(s.indexOf(sub).toLong)
        // ── scala.math object ──────────────────────────────────────────────────────
        case (ForeignV("__math__"), "Pi", Nil)         => FloatV(math.Pi)
        case (ForeignV("__math__"), "E", Nil)          => FloatV(math.E)
        case (ForeignV("__math__"), "abs", List(IntV(x)))   => IntV(math.abs(x))
        case (ForeignV("__math__"), "abs", List(FloatV(x))) => FloatV(math.abs(x))
        case (ForeignV("__math__"), "round", List(FloatV(x))) => IntV(math.round(x))
        case (ForeignV("__math__"), "round", List(IntV(x)))   => IntV(x)
        case (ForeignV("__math__"), "floor", List(FloatV(x))) => FloatV(math.floor(x))
        case (ForeignV("__math__"), "ceil", List(FloatV(x)))  => FloatV(math.ceil(x))
        case (ForeignV("__math__"), "sqrt", List(FloatV(x)))  => FloatV(math.sqrt(x))
        case (ForeignV("__math__"), "sqrt", List(IntV(x)))    => FloatV(math.sqrt(x.toDouble))
        case (ForeignV("__math__"), "pow", List(FloatV(b), FloatV(e)))  => FloatV(math.pow(b, e))
        case (ForeignV("__math__"), "pow", List(IntV(b), IntV(e)))      => FloatV(math.pow(b.toDouble, e.toDouble))
        case (ForeignV("__math__"), "sin", List(FloatV(x)))   => FloatV(math.sin(x))
        case (ForeignV("__math__"), "cos", List(FloatV(x)))   => FloatV(math.cos(x))
        case (ForeignV("__math__"), "tan", List(FloatV(x)))   => FloatV(math.tan(x))
        case (ForeignV("__math__"), "log", List(FloatV(x)))   => FloatV(math.log(x))
        case (ForeignV("__math__"), "log10", List(FloatV(x))) => FloatV(math.log10(x))
        case (ForeignV("__math__"), "exp", List(FloatV(x)))   => FloatV(math.exp(x))
        case (ForeignV("__math__"), "min", List(IntV(a), IntV(b)))      => IntV(math.min(a, b))
        case (ForeignV("__math__"), "max", List(IntV(a), IntV(b)))      => IntV(math.max(a, b))
        case (ForeignV("__math__"), "min", List(FloatV(a), FloatV(b)))  => FloatV(math.min(a, b))
        case (ForeignV("__math__"), "max", List(FloatV(a), FloatV(b)))  => FloatV(math.max(a, b))
        case (DataV("Nil", _), "isEmpty", Nil)  => BoolV(true)
        case (DataV("Cons", _), "isEmpty", Nil) => BoolV(false)
        case (DataV("Nil", _), "nonEmpty", Nil)  => BoolV(false)
        case (DataV("Cons", _), "nonEmpty", Nil) => BoolV(true)
        case (DataV("Nil", _), "length", Nil) | (DataV("Nil", _), "size", Nil) =>
          IntV(unlist(recv).length.toLong)
        case (DataV("Cons", _), "length", Nil) | (DataV("Cons", _), "size", Nil) =>
          IntV(unlist(recv).length.toLong)
        case (DataV("Nil", _), "head", Nil)  => sys.error("head on empty list")
        case (DataV("Cons", f), "head", Nil) => f(0)
        case (DataV("Cons", f), "tail", Nil) => f(1)
        case (DataV("Nil", _), "tail", Nil)  => sys.error("tail on empty list")
        // ── List HOFs (DataV Cons/Nil linked list) ──────────────────────────────
        case (ls, "map", List(fn: Value.ClosV)) if isList(ls) =>
          listOf(unlist(ls).map(x => callClos(fn, Array(x))))
        case (ls, "flatMap", List(fn: Value.ClosV)) if isList(ls) =>
          listOf(unlist(ls).flatMap(x => unlist(callClos(fn, Array(x)))))
        case (ls, "filter", List(fn: Value.ClosV)) if isList(ls) =>
          listOf(unlist(ls).filter(x => callClos(fn, Array(x)) == Value.BoolV(true)))
        case (ls, "filterNot", List(fn: Value.ClosV)) if isList(ls) =>
          listOf(unlist(ls).filterNot(x => callClos(fn, Array(x)) == Value.BoolV(true)))
        case (ls, "foldLeft", List(z, fn: Value.ClosV)) if isList(ls) =>
          unlist(ls).foldLeft(z)((acc, x) => callClos(fn, Array(acc, x)))
        case (ls, "foldRight", List(z, fn: Value.ClosV)) if isList(ls) =>
          unlist(ls).foldRight(z)((x, acc) => callClos(fn, Array(x, acc)))
        case (ls, "foreach", List(fn: Value.ClosV)) if isList(ls) =>
          unlist(ls).foreach(x => callClos(fn, Array(x))); UnitV
        case (ls, "find", List(fn: Value.ClosV)) if isList(ls) =>
          unlist(ls).find(x => callClos(fn, Array(x)) == Value.BoolV(true)) match
            case Some(v) => some(v); case None => none
        case (ls, "exists", List(fn: Value.ClosV)) if isList(ls) =>
          BoolV(unlist(ls).exists(x => callClos(fn, Array(x)) == Value.BoolV(true)))
        case (ls, "forall", List(fn: Value.ClosV)) if isList(ls) =>
          BoolV(unlist(ls).forall(x => callClos(fn, Array(x)) == Value.BoolV(true)))
        case (ls, "count", List(fn: Value.ClosV)) if isList(ls) =>
          IntV(unlist(ls).count(x => callClos(fn, Array(x)) == Value.BoolV(true)).toLong)
        case (ls, "sortBy", List(fn: Value.ClosV)) if isList(ls) =>
          listOf(unlist(ls).sortBy(x => callClos(fn, Array(x)))(valueOrdering))
        case (ls, "sortWith", List(fn: Value.ClosV)) if isList(ls) =>
          listOf(unlist(ls).sortWith((a, b) => callClos(fn, Array(a, b)) == Value.BoolV(true)))
        case (ls, "groupBy", List(fn: Value.ClosV)) if isList(ls) =>
          val groups = unlist(ls).groupBy(x => callClos(fn, Array(x)))
          val pairs = groups.map { case (k, vs) => DataV("Tuple2", Vector(k, listOf(vs))) }.toList
          listOf(pairs)
        case (ls, "zip", List(other)) if isList(ls) =>
          listOf(unlist(ls).zip(unlist(other)).map { case (a, b) => DataV("Tuple2", Vector(a, b)) })
        case (ls, "zipWithIndex", Nil) if isList(ls) =>
          listOf(unlist(ls).zipWithIndex.map { case (a, i) => DataV("Tuple2", Vector(a, IntV(i.toLong))) })
        case (ls, "take", List(IntV(n))) if isList(ls) => listOf(unlist(ls).take(n.toInt))
        case (ls, "drop", List(IntV(n))) if isList(ls) => listOf(unlist(ls).drop(n.toInt))
        case (ls, "takeWhile", List(fn: Value.ClosV)) if isList(ls) =>
          listOf(unlist(ls).takeWhile(x => callClos(fn, Array(x)) == Value.BoolV(true)))
        case (ls, "dropWhile", List(fn: Value.ClosV)) if isList(ls) =>
          listOf(unlist(ls).dropWhile(x => callClos(fn, Array(x)) == Value.BoolV(true)))
        case (ls, "flatten", Nil) if isList(ls) => listOf(unlist(ls).flatMap(unlist))
        case (ls, "reverse", Nil) if isList(ls) => listOf(unlist(ls).reverse)
        case (ls, "distinct", Nil) if isList(ls) => listOf(unlist(ls).distinct)
        case (ls, "last", Nil) if isList(ls) => unlist(ls).last
        case (ls, "init", Nil) if isList(ls) => listOf(unlist(ls).init)
        case (ls, "sum", Nil) if isList(ls) =>
          unlist(ls).foldLeft[Value](IntV(0)) {
            case (IntV(a), IntV(b)) => IntV(a + b)
            case (FloatV(a), FloatV(b)) => FloatV(a + b)
            case (IntV(a), FloatV(b)) => FloatV(a + b)
            case (FloatV(a), IntV(b)) => FloatV(a + b)
            case (a, b) => sys.error(s"sum: cannot add ${Show.show(a)} and ${Show.show(b)}")
          }
        case (ls, "max", Nil) if isList(ls) => unlist(ls).max(valueOrdering)
        case (ls, "min", Nil) if isList(ls) => unlist(ls).min(valueOrdering)
        case (ls, "maxBy", List(fn: Value.ClosV)) if isList(ls) =>
          unlist(ls).maxBy(x => callClos(fn, Array(x)))(valueOrdering)
        case (ls, "minBy", List(fn: Value.ClosV)) if isList(ls) =>
          unlist(ls).minBy(x => callClos(fn, Array(x)))(valueOrdering)
        case (ls, "mkString", Nil) if isList(ls) =>
          StrV(unlist(ls).map(anyStr).mkString)
        case (ls, "mkString", List(StrV(sep))) if isList(ls) =>
          StrV(unlist(ls).map(anyStr).mkString(sep))
        case (ls, "mkString", List(StrV(pre), StrV(sep), StrV(post))) if isList(ls) =>
          StrV(unlist(ls).map(anyStr).mkString(pre, sep, post))
        case (ls, "toList", Nil) if isList(ls) => ls
        case (ls, "toSet", Nil) if isList(ls) =>
          listOf(unlist(ls).distinct)  // approximate set as distinct list
        case (ls, "toVector", Nil) if isList(ls) => ls
        case (ls, "contains", List(v)) if isList(ls) => BoolV(unlist(ls).contains(v))
        case (ls, "indexOf", List(v)) if isList(ls) => IntV(unlist(ls).indexOf(v).toLong)
        case (ls, "+:", List(v)) if isList(ls) => DataV("Cons", Vector(v, ls))
        case (ls, ":+", List(v)) if isList(ls) => listOf(unlist(ls) :+ v)
        case (ls, "++", List(other)) if isList(ls) => listOf(unlist(ls) ++ unlist(other))
        case (ls, "splitAt", List(IntV(n))) if isList(ls) =>
          val (a, b) = unlist(ls).splitAt(n.toInt)
          DataV("Tuple2", Vector(listOf(a), listOf(b)))
        case (ls, "partition", List(fn: Value.ClosV)) if isList(ls) =>
          val (yes, no) = unlist(ls).partition(x => callClos(fn, Array(x)) == Value.BoolV(true))
          DataV("Tuple2", Vector(listOf(yes), listOf(no)))
        // ── Tuple accessors ──────────────────────────────────────────────────────
        case (DataV(tag, fields), n, Nil) if tag.startsWith("Tuple") && n.matches("_\\d+") =>
          fields(n.drop(1).toInt - 1)
        case (DataV(_, fields), "fieldAt", List(IntV(i))) => fields(i.toInt)
        // ── Option methods ───────────────────────────────────────────────────────
        case (DataV("Some", Vector(v)), "get", Nil) => v
        case (DataV("None", _), "get", Nil) => sys.error("None.get")
        case (DataV("Some", _), "isEmpty", Nil)  => BoolV(false)
        case (DataV("None", _), "isEmpty", Nil)  => BoolV(true)
        case (DataV("Some", _), "isDefined", Nil) => BoolV(true)
        case (DataV("None", _), "isDefined", Nil) => BoolV(false)
        case (DataV("Some", Vector(v)), "getOrElse", List(_)) => v
        case (DataV("None", _), "getOrElse", List(d)) => d
        case (DataV("Some", Vector(v)), "map", List(fn: Value.ClosV)) => some(callClos(fn, Array(v)))
        case (DataV("None", _), "map", List(_)) => none
        case (DataV("Some", Vector(v)), "flatMap", List(fn: Value.ClosV)) => callClos(fn, Array(v))
        case (DataV("None", _), "flatMap", List(_)) => none
        case (DataV("Some", Vector(v)), "filter", List(fn: Value.ClosV)) =>
          if callClos(fn, Array(v)) == BoolV(true) then some(v) else none
        case (DataV("None", _), "filter", List(_)) => none
        case (DataV("Some", Vector(v)), "foreach", List(fn: Value.ClosV)) =>
          callClos(fn, Array(v)); UnitV
        case (DataV("None", _), "foreach", List(_)) => UnitV
        case (DataV("Some", Vector(v)), "orElse", List(_)) => some(v)
        case (DataV("None", _), "orElse", List(other)) => other
        case (DataV("Some", Vector(v)), "toList", Nil) => listOf(Seq(v))
        case (DataV("None", _), "toList", Nil) => listOf(Seq.empty)
        case (DataV("Some", Vector(v)), "fold", List(_, fn: Value.ClosV)) => callClos(fn, Array(v))
        case (DataV("None", _), "fold", List(default, _)) => default
        // ── Either methods ───────────────────────────────────────────────────────
        case (DataV("Bench", _), "opaque", List(v)) => v
        case (DataV("BenchObj", _), "opaque", List(v)) => v
        // LazyList
        case (DataV("LazyList", _), "from", List(IntV(n))) =>
          ForeignV(LazyList.from(n.toInt).map(i => IntV(i.toLong): Value))
        case (DataV("LazyList", _), "from", List(IntV(n), IntV(step))) =>
          ForeignV(LazyList.iterate(n)(x => x + step).map(i => IntV(i): Value))
        case (ForeignV(ll: LazyList[?]), "map", List(fn: Value.ClosV)) =>
          ForeignV(ll.asInstanceOf[LazyList[Value]].map(v => callClos(fn, Array(v))))
        case (ForeignV(ll: LazyList[?]), "filter", List(fn: Value.ClosV)) =>
          ForeignV(ll.asInstanceOf[LazyList[Value]].filter(v => callClos(fn, Array(v)) match { case BoolV(b) => b; case _ => false }))
        case (ForeignV(ll: LazyList[?]), "take", List(IntV(n))) =>
          listOf(ll.asInstanceOf[LazyList[Value]].take(n.toInt))
        case (ForeignV(ll: LazyList[?]), "sum", Nil) =>
          ll.asInstanceOf[LazyList[Value]].foldLeft(0L) { case (acc, IntV(i)) => acc + i; case (acc, _) => acc } match
            case s => IntV(s)
        case (ForeignV(ll: LazyList[?]), "toList", Nil) =>
          listOf(ll.asInstanceOf[LazyList[Value]].toList)
        case (DataV("Right", Vector(v)), "map", List(fn: Value.ClosV)) => DataV("Right", Vector(callClos(fn, Array(v))))
        case (DataV("Left", _), "map", List(_)) => recv
        case (DataV("Right", Vector(v)), "flatMap", List(fn: Value.ClosV)) => callClos(fn, Array(v))
        case (DataV("Left", _), "flatMap", List(_)) => recv
        case (DataV("Right", Vector(v)), "fold", List(_, fn: Value.ClosV)) => callClos(fn, Array(v))
        case (DataV("Left",  Vector(v)), "fold", List(fn: Value.ClosV, _)) => callClos(fn, Array(v))
        case (DataV("Right", Vector(v)), "getOrElse", List(_)) => v
        case (DataV("Left",  _), "getOrElse", List(d)) => d
        case (DataV("Right", _), "isRight", Nil) => BoolV(true)
        case (DataV("Left",  _), "isRight", Nil) => BoolV(false)
        case (DataV("Right", _), "isLeft",  Nil) => BoolV(false)
        case (DataV("Left",  _), "isLeft",  Nil) => BoolV(true)
        case (DataV("Right", Vector(v)), "toOption", Nil) => some(v)
        case (DataV("Left",  _), "toOption", Nil) => none
        // ── Map/HashMap ──────────────────────────────────────────────────────────
        case (ForeignV(m: collection.mutable.HashMap[?, ?]), "size", Nil) =>
          IntV(m.size.toLong)
        case (ForeignV(m: collection.mutable.HashMap[?, ?]), "get", List(k)) =>
          m.asInstanceOf[collection.mutable.HashMap[Value,Value]].get(k) match
            case Some(v) => some(v); case None => none
        case (ForeignV(m: collection.mutable.HashMap[?, ?]), "getOrElse", List(k, default)) =>
          m.asInstanceOf[collection.mutable.HashMap[Value,Value]].getOrElse(k, default)
        case (ForeignV(m: collection.mutable.HashMap[?, ?]), "apply", List(k)) =>
          m.asInstanceOf[collection.mutable.HashMap[Value,Value]](k)
        case (ForeignV(m: collection.mutable.HashMap[?, ?]), "updated", List(k, v)) =>
          val nm = m.asInstanceOf[collection.mutable.HashMap[Value,Value]].clone()
          nm.update(k, v); ForeignV(nm)
        case (ForeignV(m: collection.mutable.HashMap[?, ?]), "removed", List(k)) =>
          val nm = m.asInstanceOf[collection.mutable.HashMap[Value,Value]].clone()
          nm.remove(k); ForeignV(nm)
        case (ForeignV(m: collection.mutable.HashMap[?, ?]), "+", List(DataV("Tuple2", Vector(k, v)))) =>
          val nm = m.asInstanceOf[collection.mutable.HashMap[Value,Value]].clone()
          nm.update(k, v); ForeignV(nm)
        case (ForeignV(m: collection.mutable.HashMap[?, ?]), "contains", List(k)) =>
          BoolV(m.asInstanceOf[collection.mutable.HashMap[Value,Value]].contains(k))
        case (ForeignV(m: collection.mutable.HashMap[?, ?]), "isEmpty", Nil) =>
          BoolV(m.isEmpty)
        case (ForeignV(m: collection.mutable.HashMap[?, ?]), "nonEmpty", Nil) =>
          BoolV(m.nonEmpty)
        case (ForeignV(m: collection.mutable.HashMap[?, ?]), "keys", Nil) =>
          listOf(m.asInstanceOf[collection.mutable.HashMap[Value,Value]].keys.toSeq)
        case (ForeignV(m: collection.mutable.HashMap[?, ?]), "values", Nil) =>
          listOf(m.asInstanceOf[collection.mutable.HashMap[Value,Value]].values.toSeq)
        case (ForeignV(m: collection.mutable.HashMap[?, ?]), "toList", Nil) =>
          listOf(m.asInstanceOf[collection.mutable.HashMap[Value,Value]].toList.map {
            case (k, v) => DataV("Tuple2", Vector(k, v))
          })
        // ── Method object (given/typeclass instances) ─────────────────────────────
        case (ForeignV(m: collection.immutable.Map[?, ?]), _, _) =>
          val mm = m.asInstanceOf[collection.immutable.Map[String, Value]]
          mm.get(name) match
            case Some(fn: ClosV) if margs.isEmpty && fn.arity > 0 => fn  // eta-expand
            case Some(fn: ClosV) => callClos(fn, margs.toArray)
            case Some(v) if margs.isEmpty => v
            case _ => sys.error(s"__method__: no method '$name' in method-object")
        case (v, "toString", Nil) => StrV(anyStr(v))
        case _ =>
          V2PluginRegistry.lookup(s"__method__.$name") match
            case Some(fn) => fn(a)
            case None => sys.error(s"__method__: no dispatch for .$name on ${Show.show(recv)}")
    case op =>
      V2PluginRegistry.lookup(op) match
        case Some(fn) => fn
        case None => (_: List[Value]) => sys.error(s"unimplemented primitive: $op")

  /** Dispatch `__arith__(op, l, r)` without allocating a List[Value] for the common cases. */
  def arithOp(op: String, l: Value, r: Value): Value = (l, r) match
    case (IntV(x), IntV(y)) => op match
      case "+"  => IntV(x + y);  case "-"  => IntV(x - y);  case "*"  => IntV(x * y)
      case "/"  => IntV(x / y);  case "%"  => IntV(x % y)
      case "==" => BoolV(x == y); case "!=" => BoolV(x != y)
      case "<"  => BoolV(x < y); case "<=" => BoolV(x <= y); case ">"  => BoolV(x > y); case ">=" => BoolV(x >= y)
      case "&"  => IntV(x & y);  case "|"  => IntV(x | y);  case "^"  => IntV(x ^ y)
      case "<<" => IntV(x << y.toInt); case ">>" => IntV(x >> y.toInt); case ">>>" => IntV(x >>> y.toInt)
      case _ => resolve("__arith__")(List(StrV(op), l, r))
    case (FloatV(x), FloatV(y)) => op match
      case "+"  => FloatV(x + y); case "-" => FloatV(x - y); case "*" => FloatV(x * y)
      case "/"  => FloatV(x / y); case "%" => FloatV(x % y)
      case "==" => BoolV(x == y); case "!=" => BoolV(x != y)
      case "<"  => BoolV(x < y); case "<=" => BoolV(x <= y); case ">"  => BoolV(x > y); case ">=" => BoolV(x >= y)
      case _ => resolve("__arith__")(List(StrV(op), l, r))
    case (IntV(x), FloatV(y)) => op match
      case "+"  => FloatV(x + y); case "-" => FloatV(x - y); case "*" => FloatV(x * y)
      case "/"  => FloatV(x / y)
      case "==" => BoolV(x == y); case "!=" => BoolV(x != y)
      case "<"  => BoolV(x < y); case "<=" => BoolV(x <= y); case ">"  => BoolV(x > y); case ">=" => BoolV(x >= y)
      case _ => resolve("__arith__")(List(StrV(op), l, r))
    case (FloatV(x), IntV(y)) => op match
      case "+"  => FloatV(x + y); case "-" => FloatV(x - y); case "*" => FloatV(x * y)
      case "/"  => FloatV(x / y)
      case "==" => BoolV(x == y); case "!=" => BoolV(x != y)
      case "<"  => BoolV(x < y); case "<=" => BoolV(x <= y); case ">"  => BoolV(x > y); case ">=" => BoolV(x >= y)
      case _ => resolve("__arith__")(List(StrV(op), l, r))
    case (StrV(x), StrV(y)) => op match
      case "++" | "+" => StrV(x + y)
      case "==" => BoolV(x == y); case "!=" => BoolV(x != y)
      case "<"  => BoolV(x < y); case "<=" => BoolV(x <= y); case ">"  => BoolV(x > y); case ">=" => BoolV(x >= y)
      case _ => resolve("__arith__")(List(StrV(op), l, r))
    case _ => resolve("__arith__")(List(StrV(op), l, r))

  /** Dispatch `__method__(name, recv, args...)` without trampoline — for FastCode. */
  def methodOp(name: String, recv: Value, args: List[Value]): Value =
    resolve("__method__")(StrV(name) :: recv :: args)

  // ── Allocation-free fast paths for 1/2/3-arg primitives ─────────────────────
  // These avoid creating a List[Value] for arg passing on the hot path.
  type Fn1 = Value => Value
  type Fn2 = (Value, Value) => Value
  type Fn3 = (Value, Value, Value) => Value

  def resolve1(op: String): Option[Fn1] = op match
    case "cell.get" => Some(c  => asCell(c)(0))
    case "cell.new" => Some(v  => ForeignV(scala.Array[Value](v)))
    case "lcell.get"=> Some(c  => IntV(c.asInstanceOf[LongCellV].v))
    case "lcell.new"=> Some(v  => new LongCellV(asInt1(v)))
    case "i.neg"    => Some { case IntV(n) => IntV(-n);  case v => IntV(-asInt1(v)) }
    case "i.not"    => Some { case IntV(n) => IntV(~n);  case v => IntV(~asInt1(v)) }
    case "not"      => Some { case BoolV(b) => BoolV(!b); case v => sys.error(s"not: not Bool: ${Show.show(v)}") }
    case "slen"     => Some { case StrV(s) => IntV(s.length.toLong); case v => sys.error(s"slen: not Str: ${Show.show(v)}") }
    case "arr.len"  => Some(a  => IntV(asArr(a).length.toLong))
    case "arr.new"  => Some(_ => ForeignV(collection.mutable.ArrayBuffer[Value]()))
    case "arr.pop"  => Some(a  => { val buf = asArr(a); buf.remove(buf.length - 1) })
    case "arr.push" => None  // 2-arg
    case "tagOf"    => Some(v  => StrV(asData(v)._1))
    case "arity"    => Some(v  => IntV(asData(v)._2.length.toLong))
    case "map.size" => Some(m  => IntV(asMap(m).size.toLong))
    case "map.new"  => None  // 0-arg
    case "map.keys" => Some(m  => listOf(asMap(m).keys.toSeq))
    case "io.args"  => None  // 0-arg
    case "blen"     => Some { case BytesV(b) => IntV(b.length.toLong); case v => sys.error(s"blen: not Bytes") }
    case "str->utf8"=> Some { case StrV(s) => BytesV(s.getBytes("UTF-8").toVector); case v => sys.error("str->utf8: not Str") }
    case "utf8->str"=> Some { case BytesV(b) => StrV(new String(b.toArray, "UTF-8")); case v => sys.error("utf8->str: not Bytes") }
    case "str.trim" => Some { case StrV(s) => StrV(s.trim); case v => sys.error("str.trim: not Str") }
    case "str.lines"=> Some { case StrV(s) =>
                         val parts = s.split("\n", -1); val nilV: Value = DataV("Nil", Vector.empty)
                         parts.foldRight(nilV)((x, acc) => DataV("Cons", Vector(StrV(x), acc)))
                         case v => sys.error("str.lines: not Str") }
    case "i->str"   => Some { case IntV(n) => StrV(n.toString);   case v => sys.error("i->str: not Int") }
    case "i->big"   => Some { case IntV(n) => BigV(BigInt(n));     case v => sys.error("i->big: not Int") }
    case "big->i"   => Some { case BigV(n) => IntV(n.toLong);      case v => sys.error("big->i: not BigInt") }
    case "i->f"     => Some { case IntV(n) => FloatV(n.toDouble);  case v => sys.error("i->f: not Int") }
    case "f->i"     => Some { case FloatV(d) => IntV(d.toLong);    case v => sys.error("f->i: not Float") }
    case "f->str"   => Some { case FloatV(d) => StrV(Writer.floatStr(d)); case v => sys.error("f->str: not Float") }
    case "big->str" => Some { case BigV(n) => StrV(n.toString);    case v => sys.error("big->str: not BigInt") }
    case "runLogger"=> Some(f  => { Runtime.run(f.asInstanceOf[ClosV].code, f.asInstanceOf[ClosV].env); UnitV })
    case _          => None

  def resolve2(op: String): Option[Fn2] = op match
    case "i.add"    => Some { case (IntV(x),   IntV(y))   => IntV(x + y)
                              case (FloatV(x), FloatV(y)) => FloatV(x + y)
                              case (FloatV(x), IntV(y))   => FloatV(x + y.toDouble)
                              case (IntV(x),  FloatV(y))  => FloatV(x.toDouble + y)
                              case (a, b)                  => sys.error(s"i.add: bad args") }
    case "i.sub"    => Some { case (IntV(x),   IntV(y))   => IntV(x - y)
                              case (FloatV(x), FloatV(y)) => FloatV(x - y)
                              case (a, b)                  => IntV(asInt1(a) - asInt1(b)) }
    case "i.mul"    => Some { case (IntV(x),   IntV(y))   => IntV(x * y)
                              case (FloatV(x), FloatV(y)) => FloatV(x * y)
                              case (a, b)                  => IntV(asInt1(a) * asInt1(b)) }
    case "i.div"    => Some { case (IntV(x),   IntV(y))   => IntV(x / y)
                              case (a, b)                  => IntV(asInt1(a) / asInt1(b)) }
    case "i.mod"    => Some { case (IntV(x),   IntV(y))   => IntV(x % y)
                              case (a, b)                  => IntV(asInt1(a) % asInt1(b)) }
    case "i.eq"     => Some { case (IntV(x),   IntV(y))   => BoolV(x == y)
                              case (a, b)                  => BoolV(asInt1(a) == asInt1(b)) }
    case "i.lt"     => Some { case (IntV(x),   IntV(y))   => BoolV(x < y)
                              case (FloatV(x), FloatV(y)) => BoolV(x < y)
                              case (a, b)                  => BoolV(asInt1(a) < asInt1(b)) }
    case "i.le"     => Some { case (IntV(x),   IntV(y))   => BoolV(x <= y)
                              case (a, b)                  => BoolV(asInt1(a) <= asInt1(b)) }
    case "i.gt"     => Some { case (IntV(x),   IntV(y))   => BoolV(x > y)
                              case (a, b)                  => BoolV(asInt1(a) > asInt1(b)) }
    case "i.ge"     => Some { case (IntV(x),   IntV(y))   => BoolV(x >= y)
                              case (a, b)                  => BoolV(asInt1(a) >= asInt1(b)) }
    case "i.and"    => Some { case (IntV(x),   IntV(y))   => IntV(x & y);  case (a,b) => IntV(asInt1(a) & asInt1(b)) }
    case "i.or"     => Some { case (IntV(x),   IntV(y))   => IntV(x | y);  case (a,b) => IntV(asInt1(a) | asInt1(b)) }
    case "i.xor"    => Some { case (IntV(x),   IntV(y))   => IntV(x ^ y);  case (a,b) => IntV(asInt1(a) ^ asInt1(b)) }
    case "i.shl"    => Some { case (IntV(x),   IntV(y))   => IntV(x << y); case (a,b) => IntV(asInt1(a) << asInt1(b)) }
    case "i.shr"    => Some { case (IntV(x),   IntV(y))   => IntV(x >> y); case (a,b) => IntV(asInt1(a) >> asInt1(b)) }
    case "i.ushr"   => Some { case (IntV(x),   IntV(y))   => IntV(x >>> y);case (a,b) => IntV(asInt1(a) >>> asInt1(b)) }
    case "f.add"    => Some { case (FloatV(x), FloatV(y)) => FloatV(x + y); case (a,b) => sys.error("f.add: not Float") }
    case "f.sub"    => Some { case (FloatV(x), FloatV(y)) => FloatV(x - y); case (a,b) => sys.error("f.sub: not Float") }
    case "f.mul"    => Some { case (FloatV(x), FloatV(y)) => FloatV(x * y); case (a,b) => sys.error("f.mul: not Float") }
    case "f.div"    => Some { case (FloatV(x), FloatV(y)) => FloatV(x / y); case (a,b) => sys.error("f.div: not Float") }
    case "f.eq"     => Some { case (FloatV(x), FloatV(y)) => BoolV(x == y); case (a,b) => sys.error("f.eq: not Float") }
    case "f.lt"     => Some { case (FloatV(x), FloatV(y)) => BoolV(x <  y); case (a,b) => sys.error("f.lt: not Float") }
    case "f.le"     => Some { case (FloatV(x), FloatV(y)) => BoolV(x <= y); case (a,b) => sys.error("f.le: not Float") }
    case "f.gt"     => Some { case (FloatV(x), FloatV(y)) => BoolV(x >  y); case (a,b) => sys.error("f.gt: not Float") }
    case "f.ge"     => Some { case (FloatV(x), FloatV(y)) => BoolV(x >= y); case (a,b) => sys.error("f.ge: not Float") }
    case "sconcat"  => Some { case (StrV(a),  StrV(b))    => StrV(a + b)
                              case (DataV(_, f1), DataV(_, f2)) =>
                                val n = f1.length + f2.length; DataV(s"Tuple$n", f1 ++ f2)
                              case (a, b) => sys.error("sconcat: bad types") }
    case "seq"      => Some { case (StrV(a),  StrV(b))    => BoolV(a == b); case (a,b) => sys.error("seq: not Str") }
    case "scmp"     => Some { case (StrV(a),  StrV(b))    => IntV(a.compareTo(b).toLong); case _ => sys.error("scmp: not Str") }
    case "sindexOf" => Some { case (StrV(a),  StrV(b))    => IntV(a.indexOf(b).toLong); case _ => sys.error("sindexOf: not Str") }
    case "str.split"=> Some { case (StrV(a),  StrV(b))    =>
                                val parts = a.split(java.util.regex.Pattern.quote(b), -1)
                                val nilV: Value = DataV("Nil", Vector.empty)
                                parts.foldRight(nilV)((x, acc) => DataV("Cons", Vector(StrV(x), acc)))
                              case _ => sys.error("str.split: not Str") }
    case "cell.set" => Some { (c, v) => asCell(c)(0) = v; UnitV }
    case "lcell.set"=> Some { (c, v) => c.asInstanceOf[LongCellV].v = asInt1(v); UnitV }
    case "map.get"  => Some { (m, k) => asMap(m).get(k).fold(none)(some) }
    case "map.has"  => Some { (m, k) => BoolV(asMap(m).contains(k)) }
    case "map.del"  => Some { (m, k) => asMap(m).remove(k); UnitV }
    case "arr.get"  => Some { (a, i) => asArr(a)(asInt1(i).toInt) }
    case "arr.push" => Some { (a, v) => asArr(a) += v; UnitV }
    case "bget"     => Some { case (BytesV(b), IntV(i)) => IntV((b(i.toInt) & 0xff).toLong); case _ => sys.error("bget: bad args") }
    case "bconcat"  => Some { case (BytesV(a), BytesV(b)) => BytesV(a ++ b); case _ => sys.error("bconcat: bad args") }
    case "fieldAt"  => Some { (v, i) => asData(v)._2(asInt1(i).toInt) }
    case "big.add"  => Some { case (BigV(x),BigV(y)) => BigV(x+y); case _ => sys.error("big.add") }
    case "big.sub"  => Some { case (BigV(x),BigV(y)) => BigV(x-y); case _ => sys.error("big.sub") }
    case "big.mul"  => Some { case (BigV(x),BigV(y)) => BigV(x*y); case _ => sys.error("big.mul") }
    case "big.div"  => Some { case (BigV(x),BigV(y)) => BigV(x/y); case _ => sys.error("big.div") }
    case "big.mod"  => Some { case (BigV(x),BigV(y)) => BigV(x%y); case _ => sys.error("big.mod") }
    case "big.eq"   => Some { case (BigV(x),BigV(y)) => BoolV(x==y); case _ => sys.error("big.eq") }
    case "big.lt"   => Some { case (BigV(x),BigV(y)) => BoolV(x<y);  case _ => sys.error("big.lt") }
    case "big.le"   => Some { case (BigV(x),BigV(y)) => BoolV(x<=y); case _ => sys.error("big.le") }
    case "big.gt"   => Some { case (BigV(x),BigV(y)) => BoolV(x>y);  case _ => sys.error("big.gt") }
    case "big.ge"   => Some { case (BigV(x),BigV(y)) => BoolV(x>=y); case _ => sys.error("big.ge") }
    case _          => None

  def resolve3(op: String): Option[Fn3] = op match
    case "sslice"   => Some { case (StrV(s), IntV(i), IntV(j)) => StrV(s.substring(i.toInt, j.toInt)); case _ => sys.error("sslice") }
    case "map.put"  => Some { (m, k, v) => asMap(m).update(k, v); UnitV }
    case "arr.set"  => Some { (a, i, v) => asArr(a)(asInt1(i).toInt) = v; UnitV }
    case "bslice"   => Some { case (BytesV(b), IntV(i), IntV(j)) => BytesV(b.slice(i.toInt, j.toInt)); case _ => sys.error("bslice") }
    case _          => None

  private def asInt1(v: Value): Long = v match { case IntV(n) => n; case x => sys.error(s"expected Int, got ${Show.show(x)}") }

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
  private def isList(v: Value): Boolean = v match
    case DataV("Nil", _) | DataV("Cons", _) => true
    case _ => false
  private def callClos(fn: Value.ClosV, args: Array[Value]): Value =
    Runtime.run(fn.code, if args.isEmpty then fn.env else Runtime.extend(fn.env, args))
  private val valueOrdering: Ordering[Value] = Ordering.fromLessThan {
    case (IntV(a), IntV(b))     => a < b
    case (FloatV(a), FloatV(b)) => a < b
    case (StrV(a), StrV(b))     => a < b
    case (IntV(a), FloatV(b))   => a.toDouble < b
    case (FloatV(a), IntV(b))   => a < b.toDouble
    case _                      => false
  }
  private def listOf(vs: Seq[Value]): Value = vs.foldRight[Value](DataV("Nil", Vector.empty))((x, acc) => DataV("Cons", Vector(x, acc)))
  private def strList(xs: Seq[String]): Value = listOf(xs.map(StrV(_)))
  private def unlist(v: Value): List[Value] = unlistPub(v)
  def unlistPub(v: Value): List[Value] = v match
    case DataV("Cons", Vector(h, t)) => h :: unlistPub(t)
    case DataV("Nil", _) => Nil
    case x => sys.error(s"expected a list, got ${Show.show(x)}")

  // O(i) list indexed access without materializing the whole list; used by tryFLC App path.
  def listAt(v: Value, i: Int): Value = v match
    case DataV("Cons", Vector(h, _)) if i == 0 => h
    case DataV("Cons", Vector(_, t))            => listAt(t, i - 1)
    case _ => sys.error(s"list index out of bounds: $i")

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
    case DataV("IrWhile", Vector(c, b))       => s"(while ${term(c)} ${term(b)})"
    case DataV("IrSeq",   Vector(ts))         => s"(seq${list(ts).map(x => " " + term(x)).mkString})"
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
    case c: LongCellV => s"<lcell:${c.v}>"
