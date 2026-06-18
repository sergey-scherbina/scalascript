package scalascript.codegen

/** Minimal **Scala.js-linkable** algebraic-effects runtime for the WASM backend.
 *
 *  It is the pure-Scala subset of `JvmGenRuntimeSources`' effect runtime
 *  (`_Computation` .. `_handleWithReturn`) — the JVM preamble's *other* parts
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
      |}
      |""".stripMargin
