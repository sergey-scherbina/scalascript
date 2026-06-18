package scalascript.codegen

/** Minimal **Scala.js-linkable** algebraic-effects runtime for the WASM backend.
 *
 *  It is the pure-Scala subset of `JvmGenRuntimeSources`' effect runtime
 *  (`_Computation` .. `_handleWithReturn`, plus `_binOp`/`_bigIntOp`/`_bigDecOp`
 *  for arithmetic, `_dispatch`/`_seqX`/`_seq`/`_isFree` for CPS-aware
 *  collection HOFs, and `_anyFlatMap` for multi-shot resume — all over
 *  `Any`-typed effect-op results; `_dispatch`'s JVM reflection fallback is
 *  replaced by an error since it can't link) — the JVM preamble's *other* parts
 *  (generators / coroutines / `std.fs`) use `Thread` + `java.nio` and crash the
 *  Scala.js linker, which is why we can't reuse the full `JvmGen.generate`
 *  output. This subset uses only pure Scala (+ `RuntimeException`, which Scala.js
 *  supports), so it links and runs on the Scala.js → wasm path.
 *
 *  Emitted in `package _ssc_runtime` alongside `JvmGen.generateUserOnly`'s
 *  CPS-lowered code (which does `import _ssc_runtime.{given, *}`).
 *
 *  KEEP IN SYNC with `JvmGenRuntimeSources` `_Computation` .. `_handleWithReturn`. */
object WasmEffectRuntime:

  val source: String =
    """package _ssc_runtime {
      |  sealed trait _Computation
      |  case class _Perform(eff: String, op: String, args: List[Any]) extends _Computation
      |  case class _FlatMap(sub: Any, k: Any => Any) extends _Computation
      |  def _bind(c: Any, f: Any => Any): Any = c match {
      |    case _: _Computation => _FlatMap(c, f)
      |    case v               => f(v)
      |  }
      |  def _perform(eff: String, op: String, args: Any*): _Computation = _Perform(eff, op, args.toList)
      |  def _run(c0: Any): Any = {
      |    var current: Any = c0
      |    while (true) {
      |      current match {
      |        case _Perform(eff, op, _) => throw new RuntimeException("Unhandled effect: " + eff + "." + op)
      |        case _FlatMap(sub, f) => sub match {
      |          case _Perform(eff, op, _) => throw new RuntimeException("Unhandled effect: " + eff + "." + op)
      |          case _FlatMap(s2, g) => current = _FlatMap(s2, (x: Any) => _FlatMap(g.asInstanceOf[Any => Any](x), f))
      |          case v => current = f.asInstanceOf[Any => Any](v)
      |        }
      |        case v => return v
      |      }
      |    }
      |    throw new RuntimeException("unreachable")
      |  }
      |  def _handle(bodyThunk: () => Any, handledOps: Set[String], handlers: Map[String, List[Any] => Any]): Any = {
      |    def interp(initial: Any): Any = {
      |      var current: Any = initial
      |      while (true) {
      |        current match {
      |          case _Perform(eff, op, args) =>
      |            val key = eff + "." + op
      |            if (handledOps(key)) { val resume: Any => Any = (v: Any) => v; current = handlers(key)(args :+ resume) }
      |            else return current
      |          case _FlatMap(sub, f) => sub match {
      |            case _Perform(eff, op, args) =>
      |              val key = eff + "." + op
      |              val fn = f.asInstanceOf[Any => Any]
      |              if (handledOps(key)) { val resume: Any => Any = (v: Any) => interp(fn(v)); current = handlers(key)(args :+ resume) }
      |              else return _FlatMap(_Perform(eff, op, args), (v: Any) => interp(fn(v)))
      |            case _FlatMap(s2, g) => current = _FlatMap(s2, (x: Any) => _FlatMap(g.asInstanceOf[Any => Any](x), f))
      |            case v => current = f.asInstanceOf[Any => Any](v)
      |          }
      |          case v => return v
      |        }
      |      }
      |      throw new RuntimeException("unreachable")
      |    }
      |    interp(bodyThunk())
      |  }
      |  def _handleWithReturn(bodyThunk: () => Any, handledOps: Set[String], handlers: Map[String, List[Any] => Any], retMap: Any => Any): Any = {
      |    def hwr(comp: Any): Any = {
      |      var current: Any = comp
      |      while (true) {
      |        current match {
      |          case _Perform(eff, op, args) =>
      |            val key = eff + "." + op
      |            if (handledOps(key)) { val resume: Any => Any = (v: Any) => hwr(v); return handlers(key)(args :+ resume) }
      |            else return current
      |          case _FlatMap(sub, f) => sub match {
      |            case _Perform(eff, op, args) =>
      |              val key = eff + "." + op
      |              val fn = f.asInstanceOf[Any => Any]
      |              if (handledOps(key)) { val resume: Any => Any = (v: Any) => hwr(fn(v)); return handlers(key)(args :+ resume) }
      |              else return _FlatMap(_Perform(eff, op, args), (v: Any) => hwr(fn(v)))
      |            case _FlatMap(s2, g) => current = _FlatMap(s2, (x: Any) => _FlatMap(g.asInstanceOf[Any => Any](x), f))
      |            case v => current = f.asInstanceOf[Any => Any](v)
      |          }
      |          case v => return retMap(v)
      |        }
      |      }
      |      throw new RuntimeException("unreachable")
      |    }
      |    hwr(bodyThunk())
      |  }
      |  // Dynamic binary operator dispatch for CPS contexts where operands are
      |  // typed as `Any` (e.g. `a + b` over effect-op results threaded through
      |  // `_bind`). Pure-Scala subset of JvmGenRuntimeSources `_binOp`.
      |  def _binOp(op: String, a: Any, b: Any): Any = (op, a, b) match {
      |    case ("+",  x: Int,    y: Int)    => x + y
      |    case ("+",  x: Long,   y: Long)   => x + y
      |    case ("+",  x: Long,   y: Int)    => x + y
      |    case ("+",  x: Int,    y: Long)   => x + y
      |    case ("+",  x: Double, y: Double) => x + y
      |    case ("+",  x: Int,    y: Double) => x + y
      |    case ("+",  x: Double, y: Int)    => x + y
      |    case ("+",  x: String, _)         => x + b.toString
      |    case ("+",  _,         y: String) => a.toString + y
      |    case ("-",  x: Int,    y: Int)    => x - y
      |    case ("-",  x: Long,   y: Long)   => x - y
      |    case ("-",  x: Double, y: Double) => x - y
      |    case ("-",  x: Int,    y: Double) => x.toDouble - y
      |    case ("-",  x: Double, y: Int)    => x - y.toDouble
      |    case ("*",  x: Int,    y: Int)    => x * y
      |    case ("*",  x: Long,   y: Long)   => x * y
      |    case ("*",  x: Double, y: Double) => x * y
      |    case ("/",  x: Int,    y: Int)    => x / y
      |    case ("/",  x: Long,   y: Long)   => x / y
      |    case ("/",  x: Double, y: Double) => x / y
      |    case ("%",  x: Int,    y: Int)    => x % y
      |    case ("<",  x: Int,    y: Int)    => x < y
      |    case ("<",  x: Long,   y: Long)   => x < y
      |    case ("<",  x: Double, y: Double) => x < y
      |    case (">",  x: Int,    y: Int)    => x > y
      |    case (">",  x: Long,   y: Long)   => x > y
      |    case (">",  x: Double, y: Double) => x > y
      |    case ("<=", x: Int,    y: Int)    => x <= y
      |    case ("<=", x: Long,   y: Long)   => x <= y
      |    case ("<=", x: Double, y: Double) => x <= y
      |    case (">=", x: Int,    y: Int)    => x >= y
      |    case (">=", x: Long,   y: Long)   => x >= y
      |    case (">=", x: Double, y: Double) => x >= y
      |    case ("%",  x: Long,   y: Long)   => x % y
      |    case ("%",  x: Double, y: Double) => x % y
      |    case (o, x: Long,   y: Int)    => _binOp(o, x, y.toLong)
      |    case (o, x: Int,    y: Long)   => _binOp(o, x.toLong, y)
      |    case (o, x: Double, y: Int)    => _binOp(o, x, y.toDouble)
      |    case (o, x: Int,    y: Double) => _binOp(o, x.toDouble, y)
      |    case (o, x: Double, y: Long)   => _binOp(o, x, y.toDouble)
      |    case (o, x: Long,   y: Double) => _binOp(o, x.toDouble, y)
      |    case (o, x: BigInt, y: BigInt)         => _bigIntOp(o, x, y)
      |    case (o, x: BigInt, y: Int)            => _bigIntOp(o, x, BigInt(y))
      |    case (o, x: Int,    y: BigInt)         => _bigIntOp(o, BigInt(x), y)
      |    case (o, x: BigInt, y: Long)           => _bigIntOp(o, x, BigInt(y))
      |    case (o, x: Long,   y: BigInt)         => _bigIntOp(o, BigInt(x), y)
      |    case (o, x: BigDecimal, y: BigDecimal) => _bigDecOp(o, x, y)
      |    case (o, x: BigDecimal, y: Int)        => _bigDecOp(o, x, BigDecimal(y))
      |    case (o, x: Int,    y: BigDecimal)     => _bigDecOp(o, BigDecimal(x), y)
      |    case (o, x: BigDecimal, y: Long)       => _bigDecOp(o, x, BigDecimal(y))
      |    case (o, x: Long,   y: BigDecimal)     => _bigDecOp(o, BigDecimal(x), y)
      |    case (o, x: BigDecimal, y: BigInt)     => _bigDecOp(o, x, BigDecimal(y))
      |    case (o, x: BigInt, y: BigDecimal)     => _bigDecOp(o, BigDecimal(x), y)
      |    case ("+", xs: Set[_], y)            => xs.asInstanceOf[Set[Any]] + y
      |    case ("-", xs: Set[_], y)            => xs.asInstanceOf[Set[Any]] - y
      |    case ("+", xs: Map[_, _], y: (_, _)) => xs.asInstanceOf[Map[Any, Any]] + y.asInstanceOf[(Any, Any)]
      |    case _ => sys.error(s"Cannot $op on $a, $b")
      |  }
      |  def _bigIntOp(op: String, x: BigInt, y: BigInt): Any = op match {
      |    case "+" => x + y
      |    case "-" => x - y
      |    case "*" => x * y
      |    case "/" => x / y
      |    case "%" => x % y
      |    case "<" => x < y
      |    case ">" => x > y
      |    case "<=" => x <= y
      |    case ">=" => x >= y
      |    case "==" => x == y
      |    case "!=" => x != y
      |    case _ => sys.error(s"Cannot $op on BigInt")
      |  }
      |  def _bigDecOp(op: String, x: BigDecimal, y: BigDecimal): Any = op match {
      |    case "+" => x + y
      |    case "-" => x - y
      |    case "*" => x * y
      |    case "/" => x / y
      |    case "%" => x % y
      |    case "<" => x < y
      |    case ">" => x > y
      |    case "<=" => x <= y
      |    case ">=" => x >= y
      |    case "==" => x == y
      |    case "!=" => x != y
      |    case _ => sys.error(s"Cannot $op on Decimal")
      |  }
      |  // ── CPS-aware collection dispatch ────────────────────────────────────
      |  // Runtime method dispatch for CPS contexts where the receiver is `Any`
      |  // (e.g. `xs.map(..)` on an effect-op result). Pure-Scala subset of
      |  // JvmGenRuntimeSources `_dispatch` + its `_seqX`/`_seq`/`_isFree` helpers —
      |  // the JVM version's Java-reflection fallback can't link under Scala.js, so
      |  // here unknown methods raise a clear error instead.
      |  def _isFree(c: Any): Boolean = c.isInstanceOf[_Computation]
      |  def _seq(comps: List[Any]): Any = {
      |    if (!comps.exists(_isFree)) comps
      |    else {
      |      def loop(i: Int, acc: List[Any]): Any =
      |        if (i == comps.length) acc
      |        else _bind(comps(i), (v: Any) => loop(i + 1, acc :+ v))
      |      loop(0, Nil)
      |    }
      |  }
      |  def _seqMap(xs: List[Any], fn: Any => Any): Any = _seq(xs.map(fn))
      |  def _seqFlatMap(xs: List[Any], fn: Any => Any): Any = {
      |    val s = _seqMap(xs, fn)
      |    def flatten(v: Any): List[Any] = v match {
      |      case ys: List[_]    => ys.asInstanceOf[List[Any]]
      |      case opt: Option[_] => opt.toList.asInstanceOf[List[Any]]
      |      case other          => List(other)
      |    }
      |    s match {
      |      case c: _Computation => _bind(c, (rs: Any) => rs.asInstanceOf[List[Any]].flatMap(flatten))
      |      case rs: List[_]     => rs.asInstanceOf[List[Any]].flatMap(flatten)
      |      case _               => s
      |    }
      |  }
      |  def _seqFilter(xs: List[Any], fn: Any => Any, neg: Boolean): Any = {
      |    val flags = xs.map(fn)
      |    val pick = (bs: List[Any]) => xs.zip(bs).collect {
      |      case (x, b: Boolean) if (if (neg) !b else b) => x
      |    }
      |    _seq(flags) match {
      |      case c: _Computation => _bind(c, (bs: Any) => pick(bs.asInstanceOf[List[Any]]))
      |      case bs: List[_]     => pick(bs.asInstanceOf[List[Any]])
      |      case other           => other
      |    }
      |  }
      |  def _seqForeach(xs: List[Any], fn: Any => Any): Any = _seq(xs.map(fn)) match {
      |    case c: _Computation => _bind(c, (_: Any) => ())
      |    case _               => ()
      |  }
      |  def _seqExists(xs: List[Any], fn: Any => Any): Any = _seq(xs.map(fn)) match {
      |    case c: _Computation => _bind(c, (bs: Any) => bs.asInstanceOf[List[Any]].exists { case b: Boolean => b; case _ => false })
      |    case bs: List[_]     => bs.asInstanceOf[List[Any]].exists { case b: Boolean => b; case _ => false }
      |    case _               => false
      |  }
      |  def _seqForall(xs: List[Any], fn: Any => Any): Any = _seq(xs.map(fn)) match {
      |    case c: _Computation => _bind(c, (bs: Any) => bs.asInstanceOf[List[Any]].forall { case b: Boolean => b; case _ => false })
      |    case bs: List[_]     => bs.asInstanceOf[List[Any]].forall { case b: Boolean => b; case _ => false }
      |    case _               => true
      |  }
      |  def _seqCount(xs: List[Any], fn: Any => Any): Any = _seq(xs.map(fn)) match {
      |    case c: _Computation => _bind(c, (bs: Any) => bs.asInstanceOf[List[Any]].count { case b: Boolean => b; case _ => false })
      |    case bs: List[_]     => bs.asInstanceOf[List[Any]].count { case b: Boolean => b; case _ => false }
      |    case _               => 0
      |  }
      |  def _seqFind(xs: List[Any], fn: Any => Any): Any = {
      |    val flags = xs.map(fn)
      |    val pick = (bs: List[Any]) => {
      |      val i = bs.indexWhere { case b: Boolean => b; case _ => false }
      |      if (i < 0) None else Some(xs(i))
      |    }
      |    _seq(flags) match {
      |      case c: _Computation => _bind(c, (bs: Any) => pick(bs.asInstanceOf[List[Any]]))
      |      case bs: List[_]     => pick(bs.asInstanceOf[List[Any]])
      |      case _               => None
      |    }
      |  }
      |  def _seqFoldLeft(xs: List[Any], init: Any, fn: (Any, Any) => Any): Any = {
      |    def loop(i: Int, acc: Any): Any =
      |      if (i == xs.length) acc
      |      else {
      |        val next = fn(acc, xs(i))
      |        next match {
      |          case c: _Computation => _bind(c, (v: Any) => loop(i + 1, v))
      |          case v               => loop(i + 1, v)
      |        }
      |      }
      |    loop(0, init)
      |  }
      |  private def _ordAny: Ordering[Any] = new Ordering[Any] {
      |    def compare(a: Any, b: Any): Int = (a, b) match {
      |      case (x: Int,    y: Int)    => x.compare(y)
      |      case (x: Long,   y: Long)   => x.compare(y)
      |      case (x: Double, y: Double) => x.compare(y)
      |      case (x: String, y: String) => x.compare(y)
      |      case _ => a.toString.compare(b.toString)
      |    }
      |  }
      |  def _dispatch(obj: Any, method: String, args: List[Any]): Any = (obj, method, args) match {
      |    case (xs: List[_], "map",       List(fn)) => _seqMap     (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any])
      |    case (xs: List[_], "flatMap",   List(fn)) => _seqFlatMap (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any])
      |    case (xs: List[_], "filter",    List(fn)) => _seqFilter  (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any], neg = false)
      |    case (xs: List[_], "filterNot", List(fn)) => _seqFilter  (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any], neg = true)
      |    case (xs: List[_], "foreach",   List(fn)) => _seqForeach (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any])
      |    case (xs: List[_], "exists",    List(fn)) => _seqExists  (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any])
      |    case (xs: List[_], "forall",    List(fn)) => _seqForall  (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any])
      |    case (xs: List[_], "find",      List(fn)) => _seqFind    (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any])
      |    case (xs: List[_], "count",     List(fn)) => _seqCount   (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any])
      |    case (xs: List[_], "foldLeft",  List(init)) =>
      |      (fn: ((Any, Any) => Any)) => _seqFoldLeft(xs.asInstanceOf[List[Any]], init, fn)
      |    case (xs: List[_], "head",      Nil) => xs.head
      |    case (xs: List[_], "tail",      Nil) => xs.tail
      |    case (xs: List[_], "size",      Nil) => xs.size
      |    case (xs: List[_], "length",    Nil) => xs.length
      |    case (xs: List[_], "isEmpty",   Nil) => xs.isEmpty
      |    case (xs: List[_], "nonEmpty",  Nil) => xs.nonEmpty
      |    case (xs: List[_], "reverse",   Nil) => xs.reverse
      |    case (xs: List[_], "toMap",     Nil) => xs.asInstanceOf[List[(Any, Any)]].toMap
      |    case (xs: List[_], "toSet",     Nil) => xs.toSet
      |    case (xs: List[_], "zip",       List(other)) => xs.zip(other.asInstanceOf[Iterable[Any]])
      |    case (xs: List[_], "zipWithIndex", Nil) => xs.zipWithIndex
      |    case (xs: List[_], "sortBy",  List(fn)) =>
      |      given Ordering[Any] = _ordAny
      |      xs.asInstanceOf[List[Any]].sortBy(fn.asInstanceOf[Any => Any])
      |    case (xs: List[_], "sorted", Nil) =>
      |      given Ordering[Any] = _ordAny
      |      xs.asInstanceOf[List[Any]].sorted
      |    case (xs: List[_], "groupBy", List(fn)) => xs.asInstanceOf[List[Any]].groupBy(fn.asInstanceOf[Any => Any])
      |    case (xs: List[_], "headOption", Nil) => xs.headOption
      |    case (xs: List[_], "lastOption", Nil) => xs.lastOption
      |    case (xs: List[_], "drop",   List(n: Int)) => xs.drop(n)
      |    case (xs: List[_], "take",   List(n: Int)) => xs.take(n)
      |    case (xs: List[_], "distinct", Nil) => xs.distinct
      |    case (xs: List[_], "contains", List(x)) => xs.asInstanceOf[List[Any]].contains(x)
      |    case (xs: List[_], "mkString", Nil) => xs.mkString
      |    case (xs: List[_], "mkString", List(s: String)) => xs.mkString(s)
      |    case (xs: List[_], "sum",      Nil) => xs.asInstanceOf[List[Any]].foldLeft(0: Any)((a, b) => _binOp("+", a, b))
      |    case (s: String,   "length",   Nil) => s.length
      |    case (s: String,   "size",     Nil) => s.length
      |    case (s: String,   "toInt",    Nil) => s.toInt
      |    case (s: String,   "toLong",   Nil) => s.toLong
      |    case (s: String,   "toDouble", Nil) => s.toDouble
      |    case (s: String,   "take",     List(n: Int)) => s.take(n)
      |    case (s: String,   "drop",     List(n: Int)) => s.drop(n)
      |    case (s: String,   "head",     Nil) => s.head
      |    case (s: String,   "tail",     Nil) => s.tail
      |    case (s: String,   "isEmpty",  Nil) => s.isEmpty
      |    case (s: String,   "nonEmpty", Nil) => s.nonEmpty
      |    case (s: String,   "trim",     Nil) => s.trim
      |    case (s: String,   "toLowerCase", Nil) => s.toLowerCase
      |    case (s: String,   "toUpperCase", Nil) => s.toUpperCase
      |    case (s: String,   "split",    List(sep: String)) => s.split(sep).toList
      |    case (opt: Option[_], "get",        Nil)     => opt.get
      |    case (opt: Option[_], "getOrElse",  List(d)) => opt.getOrElse(d)
      |    case (opt: Option[_], "isDefined",  Nil)     => opt.isDefined
      |    case (opt: Option[_], "isEmpty",    Nil)     => opt.isEmpty
      |    case (opt: Option[_], "nonEmpty",   Nil)     => opt.nonEmpty
      |    case (opt: Option[_], "map",        List(fn)) => opt.asInstanceOf[Option[Any]].map(fn.asInstanceOf[Any => Any])
      |    case (opt: Option[_], "flatMap",    List(fn)) => opt.asInstanceOf[Option[Any]].flatMap(x => fn.asInstanceOf[Any => Option[Any]](x))
      |    case (opt: Option[_], "foreach",    List(fn)) => opt.asInstanceOf[Option[Any]].foreach(fn.asInstanceOf[Any => Any]); ()
      |    case (m: Map[_, _], "getOrElse", List(k, d)) => m.asInstanceOf[Map[Any, Any]].getOrElse(k, d)
      |    case (m: Map[_, _], "get",       List(k))    => m.asInstanceOf[Map[Any, Any]].get(k)
      |    case (m: Map[_, _], "contains",  List(k))    => m.asInstanceOf[Map[Any, Any]].contains(k)
      |    case (m: Map[_, _], "size",      Nil)        => m.size
      |    case (m: Map[_, _], "isEmpty",   Nil)        => m.isEmpty
      |    case (m: Map[_, _], "nonEmpty",  Nil)        => m.nonEmpty
      |    case (m: Map[_, _], "keys",      Nil)        => m.asInstanceOf[Map[Any, Any]].keys
      |    case (m: Map[_, _], "values",    Nil)        => m.asInstanceOf[Map[Any, Any]].values
      |    case (m: Map[_, _], key, Nil)                => m.asInstanceOf[Map[Any, Any]].getOrElse(key, null)
      |    case (s: Set[_], "contains",  List(x)) => s.asInstanceOf[Set[Any]].contains(x)
      |    case (s: Set[_], "size",      Nil)     => s.size
      |    case (s: Set[_], "isEmpty",   Nil)     => s.isEmpty
      |    case (s: Set[_], "nonEmpty",  Nil)     => s.nonEmpty
      |    case (n: Int,    "toLong",   Nil) => n.toLong
      |    case (n: Int,    "toDouble", Nil) => n.toDouble
      |    case (n: Int,    "toFloat",  Nil) => n.toFloat
      |    case (n: Int,    "toInt",    Nil) => n
      |    case (n: Long,   "toInt",    Nil) => n.toInt
      |    case (n: Long,   "toDouble", Nil) => n.toDouble
      |    case (n: Long,   "toFloat",  Nil) => n.toFloat
      |    case (n: Long,   "toLong",   Nil) => n
      |    case (n: Double, "toInt",    Nil) => n.toInt
      |    case (n: Double, "toLong",   Nil) => n.toLong
      |    case (n: Double, "toFloat",  Nil) => n.toFloat
      |    case (n: Double, "toDouble", Nil) => n
      |    case (n: Float,  "toInt",    Nil) => n.toInt
      |    case (n: Float,  "toLong",   Nil) => n.toLong
      |    case (n: Float,  "toDouble", Nil) => n.toDouble
      |    case _ => sys.error("wasm effect runtime: cannot dispatch '" + method + "' on " + obj +
      |      " (dynamic method outside the linkable subset; the JVM reflection fallback can't link under Scala.js)")
      |  }
      |  // Multi-shot resume: `opts.flatMap(o => resume(o))` lowers to this. A
      |  // resume that returns an iterable (the collected branch results) is
      |  // flattened; a scalar branch result is wrapped. Pure-Scala copy of
      |  // JvmGenRuntimeSources `_anyFlatMap`.
      |  def _anyFlatMap(xs: Any, f: Any => Any): Any = xs match {
      |    case ys: scala.collection.Iterable[_] =>
      |      ys.asInstanceOf[Iterable[Any]].toList.flatMap { x =>
      |        f(x) match {
      |          case zs: scala.collection.Iterable[_] => zs.asInstanceOf[Iterable[Any]].toList
      |          case v                                => List(v)
      |        }
      |      }
      |    case _ => xs
      |  }
      |}
      |""".stripMargin
