package scalascript.codegen

/** Minimal **Scala.js-linkable** algebraic-effects runtime for the WASM backend.
 *
 *  It is the pure-Scala subset of `JvmGenRuntimeSources`' effect runtime
 *  (`_Computation` .. `_handleWithReturn`, plus `_binOp`/`_bigIntOp`/`_bigDecOp`
 *  for arithmetic over `Any`-typed effect-op results) — the JVM preamble's *other* parts
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
      |}
      |""".stripMargin
