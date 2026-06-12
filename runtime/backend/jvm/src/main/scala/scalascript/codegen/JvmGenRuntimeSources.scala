package scalascript.codegen

/** Large embedded runtime-source string constants emitted into the generated
 *  Scala preamble (stub-serve, filesystem, generator, effects free-monad, and
 *  reactive-signal runtimes). Pure `String` data — no generator state — lifted
 *  out of JvmGen to keep the generator navigable. Self-typed only for
 *  package-consistency with the other JvmGen mixins; reads nothing from `self`.
 *  (`logger`/`common`/`serveRuntime` stay in JvmGen — they call loader methods.) */
private[codegen] trait JvmGenRuntimeSources:
  self: JvmGen =>

  private[codegen] val stubServeRuntime: String =
    """|
       |// ── stub serve runtime (no HTTP server) ──────────────────────────────────────
       |// No-op stubs for route/onWebSocket/_routes/_httpDoRequest so the actor and
       |// Http-effect runtimes compile in scripts that don't use serve()/WebSockets.
       |private case class _SscRouteEntry(method: String, path: String)
       |private val _routes = scala.collection.mutable.ArrayBuffer.empty[_SscRouteEntry]
       |private def route(method: String, path: String)(handler: Request => Any): Unit =
       |  _routes.append(_SscRouteEntry(method, path))
       |private trait _SscWs { def send(t: String): Unit; def recv(): Option[String] }
       |// Signature mirrors the real `onWebSocket` in WebSocketRuntime (incl. the
       |// `protocols` list the cluster actor route passes) so the actor runtime
       |// compiles identically with or without a real serve runtime.
       |private def onWebSocket(path: String, origins: List[String] = Nil,
       |    protocols: List[String] = Nil, maxConnections: Int = 0,
       |    maxMessagesPerSec: Int = 0)(handler: _SscWs => Unit): Unit = ()
       |private def _httpDoRequest(method: String, url: String, body: String,
       |    headers: Map[String, String]): Any =
       |  sys.error("Http effect requires a serve runtime; call runHttp{} or add serve()")
       |""".stripMargin

  private[codegen] val fsRuntime: String =
    """|
       |// ── std.fs — synchronous file primitives (java.nio.file) ─────────────────
       |// Defined under the user-facing names so nested calls like
       |// `println(readFile(path))` resolve directly without intrinsic
       |// dispatch (dispatch only fires for top-level Apply, not args).
       |def writeFile(path: String, contents: String): Unit =
       |  java.nio.file.Files.write(
       |    java.nio.file.Paths.get(path),
       |    contents.getBytes(java.nio.charset.StandardCharsets.UTF_8))
       |  ()
       |def readFile(path: String): String =
       |  new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),
       |             java.nio.charset.StandardCharsets.UTF_8)
       |def deleteFile(path: String): Unit =
       |  java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(path))
       |  ()
       |def exists(path: String): Boolean =
       |  java.nio.file.Files.exists(java.nio.file.Paths.get(path))
       |
       |""".stripMargin

  private[codegen] val generatorRuntime: String =
    """|
       |// ── v1.10 Generator — pull-based lazy streams via virtual threads ────────
       |// Each Generator[A] runs its body in a virtual thread.
       |// suspend(v) blocks the body thread until the consumer calls .next().
       |private val _genQueueTL = new ThreadLocal[java.util.concurrent.SynchronousQueue[Option[Any]]]()
       |
       |private def _suspend(v: Any): Unit =
       |  val q = _genQueueTL.get()
       |  if q == null then throw new RuntimeException("suspend called outside a coroutine or generator body")
       |  q.put(Some(v))
       |
       |def suspend(v: Any): Any =
       |  val coH = _coHandleTL.get()
       |  if coH != null then
       |    coH.fromBody.put(Yielded(v))
       |    coH.toBody.take()
       |  else
       |    _suspend(v)
       |
       |class _Generator[+A](bodyFn: () => Unit):
       |  private type Q = java.util.concurrent.SynchronousQueue[Option[Any]]
       |  private val queue: Q = new Q()
       |  Thread.ofVirtual().start { () =>
       |    _genQueueTL.set(queue)
       |    try bodyFn()
       |    catch case _: Throwable => ()
       |    finally try queue.put(None) catch case _ => ()
       |  }
       |
       |  def next(): Option[A] = queue.take().asInstanceOf[Option[A]]
       |
       |  def foreach(f: A => Unit): Unit =
       |    var item = queue.take().asInstanceOf[Option[A]]
       |    while item.isDefined do
       |      f(item.get)
       |      item = queue.take().asInstanceOf[Option[A]]
       |
       |  def toList: List[A] =
       |    val buf = scala.collection.mutable.ListBuffer.empty[A]
       |    var item = queue.take().asInstanceOf[Option[A]]
       |    while item.isDefined do
       |      buf += item.get
       |      item = queue.take().asInstanceOf[Option[A]]
       |    buf.toList
       |
       |  def map[B](f: A => B): _Generator[B] = new _Generator[B]({ () =>
       |    var item = queue.take().asInstanceOf[Option[A]]
       |    while item.isDefined do
       |      _suspend(f(item.get))
       |      item = queue.take().asInstanceOf[Option[A]]
       |  })
       |
       |  def filter(pred: A => Boolean): _Generator[A] = new _Generator[A]({ () =>
       |    var item = queue.take().asInstanceOf[Option[A]]
       |    while item.isDefined do
       |      if pred(item.get) then _suspend(item.get)
       |      item = queue.take().asInstanceOf[Option[A]]
       |  })
       |
       |  def take(n: Int): _Generator[A] = new _Generator[A]({ () =>
       |    var remaining = n
       |    var item = queue.take().asInstanceOf[Option[A]]
       |    while item.isDefined && remaining > 0 do
       |      _suspend(item.get)
       |      remaining -= 1
       |      item = if remaining > 0 then queue.take().asInstanceOf[Option[A]] else None
       |  })
       |
       |  def drop(n: Int): _Generator[A] = new _Generator[A]({ () =>
       |    var toDrop = n
       |    var item = queue.take().asInstanceOf[Option[A]]
       |    while item.isDefined && toDrop > 0 do
       |      toDrop -= 1
       |      item = queue.take().asInstanceOf[Option[A]]
       |    while item.isDefined do
       |      _suspend(item.get)
       |      item = queue.take().asInstanceOf[Option[A]]
       |  })
       |
       |  def flatMap[B](f: A => _Generator[B]): _Generator[B] = new _Generator[B]({ () =>
       |    var item = queue.take().asInstanceOf[Option[A]]
       |    while item.isDefined do
       |      val inner = f(item.get)
       |      var sub = inner.next()
       |      while sub.isDefined do
       |        _suspend(sub.get)
       |        sub = inner.next()
       |      item = queue.take().asInstanceOf[Option[A]]
       |  })
       |
       |  def zip[B](other: _Generator[B]): _Generator[(A, B)] = new _Generator[(A, B)]({ () =>
       |    var a = queue.take().asInstanceOf[Option[A]]
       |    var b = other.next()
       |    while a.isDefined && b.isDefined do
       |      _suspend((a.get, b.get))
       |      a = queue.take().asInstanceOf[Option[A]]
       |      if a.isDefined then b = other.next()
       |  })
       |
       |  def zipWithIndex: _Generator[(A, Int)] = new _Generator[(A, Int)]({ () =>
       |    var idx = 0
       |    var item = queue.take().asInstanceOf[Option[A]]
       |    while item.isDefined do
       |      _suspend((item.get, idx))
       |      idx += 1
       |      item = queue.take().asInstanceOf[Option[A]]
       |  })
       |
       |def generator[T](body: () => Unit): _Generator[T] = new _Generator[T](body)
       |
       |// ── v1.9 Coroutine primitive — virtual-thread handshake ──────────────────
       |// Two-way suspend/resume via a pair of SynchronousQueues.
       |// Protocol (lazy start): body waits on toBody.take() for the first resume,
       |// each suspend puts Yielded(out) and takes from toBody for the next input.
       |case class Yielded(value: Any)
       |case class Returned(value: Any)
       |case class Errored(message: String)
       |
       |private case class _CoHandle(
       |  fromBody: java.util.concurrent.SynchronousQueue[Any],
       |  toBody:   java.util.concurrent.SynchronousQueue[Any]
       |)
       |case class _Coroutine(_id: Long)
       |private val _coHandleTL = new ThreadLocal[_CoHandle]()
       |private val _coHandles  = new java.util.concurrent.ConcurrentHashMap[Long, _CoHandle]()
       |private val _nextCoId   = new java.util.concurrent.atomic.AtomicLong(0L)
       |
       |def coroutineCreate(body: () => Any): _Coroutine =
       |  val fromBody = new java.util.concurrent.SynchronousQueue[Any]()
       |  val toBody   = new java.util.concurrent.SynchronousQueue[Any]()
       |  val handle   = _CoHandle(fromBody, toBody)
       |  val id       = _nextCoId.getAndIncrement()
       |  _coHandles.put(id, handle)
       |  Thread.ofVirtual().start { () =>
       |    _coHandleTL.set(handle)
       |    toBody.take()
       |    try
       |      val result = body()
       |      fromBody.put(Returned(result))
       |    catch case t: Throwable =>
       |      val msg = Option(t.getMessage).getOrElse(t.getClass.getSimpleName)
       |      try fromBody.put(Errored(msg)) catch case _: Throwable => ()
       |  }
       |  _Coroutine(id)
       |
       |def coroutineResume(co: Any, in: Any): Any =
       |  co match
       |    case _Coroutine(id) =>
       |      val handle = _coHandles.get(id)
       |      if handle == null then throw new RuntimeException("coroutineResume: coroutine already completed")
       |      handle.toBody.put(in)
       |      val step = handle.fromBody.take()
       |      step match
       |        case _: Returned | _: Errored => _coHandles.remove(id)
       |        case _ => ()
       |      step
       |    case _ => throw new RuntimeException("coroutineResume: not a coroutine")
       |
       |""".stripMargin

  /** Free-Monad runtime for algebraic effects. Mirrors the interpreter and JS
   *  backend: Pure values are plain Scala values, Perform/FlatMap are case
   *  classes, _bind is constant-time, _run / _handle right-associate
   *  FlatMaps in a while-loop (stack-safe in bind-chain depth). */
  private[codegen] val effectsRuntime: String =
    """|
       |// ── Algebraic effects runtime (trampolined Free Monad) ─────────────────
       |sealed trait _Computation
       |case class _Perform(eff: String, op: String, args: List[Any]) extends _Computation
       |case class _FlatMap(sub: Any, k: Any => Any) extends _Computation
       |
       |def _bind(c: Any, f: Any => Any): Any = c match
       |  case _: _Computation => _FlatMap(c, f)
       |  case v               => f(v)
       |
       |def _perform(eff: String, op: String, args: Any*): _Computation =
       |  _Perform(eff, op, args.toList)
       |
       |def _run(c0: Any): Any =
       |  var current: Any = c0
       |  while true do
       |    current match
       |      case _Perform(eff, op, _) =>
       |        throw new RuntimeException(s"Unhandled effect: $eff.$op")
       |      case _FlatMap(sub, f) => sub match
       |        case _Perform(eff, op, _) =>
       |          throw new RuntimeException(s"Unhandled effect: $eff.$op")
       |        case _FlatMap(s2, g) =>
       |          current = _FlatMap(s2, (x: Any) => _FlatMap(g.asInstanceOf[Any => Any](x), f))
       |        case v =>
       |          current = f.asInstanceOf[Any => Any](v)
       |      case v => return v
       |  throw new RuntimeException("unreachable")
       |
       |def _handle(
       |  bodyThunk:  () => Any,
       |  handledOps: Set[String],
       |  handlers:   Map[String, List[Any] => Any]
       |): Any =
       |  def interp(initial: Any): Any =
       |    var current: Any = initial
       |    while true do
       |      current match
       |        case _Perform(eff, op, args) =>
       |          val key = s"$eff.$op"
       |          if handledOps(key) then
       |            val resume: Any => Any = (v: Any) => v
       |            current = handlers(key)(args :+ resume)
       |          else return current
       |        case _FlatMap(sub, f) => sub match
       |          case _Perform(eff, op, args) =>
       |            val key = s"$eff.$op"
       |            val fn = f.asInstanceOf[Any => Any]
       |            if handledOps(key) then
       |              val resume: Any => Any = (v: Any) => interp(fn(v))
       |              current = handlers(key)(args :+ resume)
       |            else
       |              return _FlatMap(_Perform(eff, op, args),
       |                              (v: Any) => interp(fn(v)))
       |          case _FlatMap(s2, g) =>
       |            current = _FlatMap(s2,
       |              (x: Any) => _FlatMap(g.asInstanceOf[Any => Any](x), f))
       |          case v =>
       |            current = f.asInstanceOf[Any => Any](v)
       |        case v => return v
       |    throw new RuntimeException("unreachable")
       |  interp(bodyThunk())
       |
       |/** Loose flatMap used inside handler case bodies — accepts callbacks that
       | *  return either an iterable (multi-shot resume) or a single value
       | *  (one-shot resume), matching the duck-typed JS semantics. */
       |def _anyFlatMap(xs: Any, f: Any => Any): Any = xs match
       |  case ys: scala.collection.Iterable[_] =>
       |    ys.asInstanceOf[Iterable[Any]].toList.flatMap { x =>
       |      f(x) match
       |        case zs: scala.collection.Iterable[_] => zs.asInstanceOf[Iterable[Any]].toList
       |        case v                                => List(v)
       |    }
       |  case _ => xs
       |
       |// ── Exact numerics (v1.64): BigInt / Decimal ───────────────────────────
       |// `Decimal`/`RoundingMode` are ScalaScript names; alias them to the native
       |// Scala types so emitted `Decimal("1.5")`, `Decimal(123, 2)`, `val x: Decimal`,
       |// and `RoundingMode.HALF_UP` all resolve.  `BigInt` is already native.
       |type Decimal = scala.math.BigDecimal
       |val Decimal = scala.math.BigDecimal
       |val RoundingMode = scala.math.BigDecimal.RoundingMode
       |// Conversions + non-native method names, matching the interpreter surface.
       |extension (n: Int)
       |  def toBigInt: BigInt      = BigInt(n)
       |  def toDecimal: BigDecimal = BigDecimal(n)
       |extension (n: BigInt)
       |  def toDecimal: BigDecimal = BigDecimal(n)
       |  def isEven: Boolean       = !n.testBit(0)
       |  def isOdd: Boolean        = n.testBit(0)
       |  def negate: BigInt        = -n
       |extension (d: BigDecimal)
       |  def toDecimal: BigDecimal = d
       |  def negate: BigDecimal    = -d
       |  def isZero: Boolean       = d.signum == 0
       |  def divide(o: BigDecimal, scale: Int, mode: scala.math.BigDecimal.RoundingMode.Value): BigDecimal =
       |    BigDecimal(d.bigDecimal.divide(o.bigDecimal, scale, java.math.RoundingMode.valueOf(mode.toString)))
       |  def roundTo(mode: scala.math.BigDecimal.RoundingMode.Value): BigDecimal = d.setScale(0, mode)
       |
       |def _bigIntOp(op: String, x: BigInt, y: BigInt): Any = op match
       |  case "+" => x + y
       |  case "-" => x - y
       |  case "*" => x * y
       |  case "/" => x / y
       |  case "%" => x % y
       |  case "<" => x < y
       |  case ">" => x > y
       |  case "<=" => x <= y
       |  case ">=" => x >= y
       |  case "==" => x == y
       |  case "!=" => x != y
       |  case _ => sys.error(s"Cannot $op on BigInt")
       |
       |def _bigDecOp(op: String, x: BigDecimal, y: BigDecimal): Any = op match
       |  case "+" => x + y
       |  case "-" => x - y
       |  case "*" => x * y
       |  case "/" => x / y
       |  case "%" => x % y
       |  case "<" => x < y
       |  case ">" => x > y
       |  case "<=" => x <= y
       |  case ">=" => x >= y
       |  case "==" => x == y
       |  case "!=" => x != y
       |  case _ => sys.error(s"Cannot $op on Decimal")
       |
       |/** Dynamic binary operator dispatch for CPS contexts where operands are
       | *  typed as `Any`. Mirrors the interpreter's `infix` table. */
       |def _binOp(op: String, a: Any, b: Any): Any = (op, a, b) match
       |  case ("+",  x: Int,    y: Int)    => x + y
       |  case ("+",  x: Long,   y: Long)   => x + y
       |  case ("+",  x: Long,   y: Int)    => x + y
       |  case ("+",  x: Int,    y: Long)   => x + y
       |  case ("+",  x: Double, y: Double) => x + y
       |  case ("+",  x: Int,    y: Double) => x + y
       |  case ("+",  x: Double, y: Int)    => x + y
       |  case ("+",  x: String, _)         => x + b.toString
       |  case ("+",  _,         y: String) => a.toString + y
       |  case ("-",  x: Int,    y: Int)    => x - y
       |  case ("-",  x: Long,   y: Long)   => x - y
       |  case ("-",  x: Double, y: Double) => x - y
       |  case ("-",  x: Int,    y: Double) => x.toDouble - y
       |  case ("-",  x: Double, y: Int)    => x - y.toDouble
       |  case ("*",  x: Int,    y: Int)    => x * y
       |  case ("*",  x: Long,   y: Long)   => x * y
       |  case ("*",  x: Double, y: Double) => x * y
       |  case ("/",  x: Int,    y: Int)    => x / y
       |  case ("/",  x: Long,   y: Long)   => x / y
       |  case ("/",  x: Double, y: Double) => x / y
       |  case ("%",  x: Int,    y: Int)    => x % y
       |  case ("<",  x: Int,    y: Int)    => x < y
       |  case ("<",  x: Long,   y: Long)   => x < y
       |  case ("<",  x: Double, y: Double) => x < y
       |  case (">",  x: Int,    y: Int)    => x > y
       |  case (">",  x: Long,   y: Long)   => x > y
       |  case (">",  x: Double, y: Double) => x > y
       |  case ("<=", x: Int,    y: Int)    => x <= y
       |  case ("<=", x: Long,   y: Long)   => x <= y
       |  case ("<=", x: Double, y: Double) => x <= y
       |  case (">=", x: Int,    y: Int)    => x >= y
       |  case (">=", x: Long,   y: Long)   => x >= y
       |  case (">=", x: Double, y: Double) => x >= y
       |  case ("%",  x: Long,   y: Long)   => x % y
       |  case ("%",  x: Double, y: Double) => x % y
       |  // Mixed-width numeric ops — widen to the dominant primitive and retry,
       |  // so `_binOp` is total over Int/Long/Double combinations (a `var s: Long`
       |  // doing `s % 7` flows here as (Long, Int)). Same-type and String cases
       |  // above win first; these only catch the remaining mixed pairs.
       |  case (o, x: Long,   y: Int)    => _binOp(o, x, y.toLong)
       |  case (o, x: Int,    y: Long)   => _binOp(o, x.toLong, y)
       |  case (o, x: Double, y: Int)    => _binOp(o, x, y.toDouble)
       |  case (o, x: Int,    y: Double) => _binOp(o, x.toDouble, y)
       |  case (o, x: Double, y: Long)   => _binOp(o, x, y.toDouble)
       |  case (o, x: Long,   y: Double) => _binOp(o, x.toDouble, y)
       |  // Exact numerics (v1.64): BigInt / BigDecimal with Int/Long/BigInt
       |  // widening.  Decimal⊕Double is intentionally absent → falls through to
       |  // the error case (mixing exact and inexact is rejected).
       |  case (o, x: BigInt, y: BigInt)         => _bigIntOp(o, x, y)
       |  case (o, x: BigInt, y: Int)            => _bigIntOp(o, x, BigInt(y))
       |  case (o, x: Int,    y: BigInt)         => _bigIntOp(o, BigInt(x), y)
       |  case (o, x: BigInt, y: Long)           => _bigIntOp(o, x, BigInt(y))
       |  case (o, x: Long,   y: BigInt)         => _bigIntOp(o, BigInt(x), y)
       |  case (o, x: BigDecimal, y: BigDecimal) => _bigDecOp(o, x, y)
       |  case (o, x: BigDecimal, y: Int)        => _bigDecOp(o, x, BigDecimal(y))
       |  case (o, x: Int,    y: BigDecimal)     => _bigDecOp(o, BigDecimal(x), y)
       |  case (o, x: BigDecimal, y: Long)       => _bigDecOp(o, x, BigDecimal(y))
       |  case (o, x: Long,   y: BigDecimal)     => _bigDecOp(o, BigDecimal(x), y)
       |  case (o, x: BigDecimal, y: BigInt)     => _bigDecOp(o, x, BigDecimal(y))
       |  case (o, x: BigInt, y: BigDecimal)     => _bigDecOp(o, BigDecimal(x), y)
       |  // Collection ops — `+`/`-` on Set/Map for membership update,
       |  // `+` on List/Map for cons/insert (CPS dep code uses these
       |  // via _binOp when operands' static types are Any).
       |  case ("+", xs: Set[_], y)         => xs.asInstanceOf[Set[Any]] + y
       |  case ("-", xs: Set[_], y)         => xs.asInstanceOf[Set[Any]] - y
       |  case ("+", xs: Map[_, _], y: (_, _)) =>
       |    xs.asInstanceOf[Map[Any, Any]] + y.asInstanceOf[(Any, Any)]
       |  case _ => sys.error(s"Cannot $op on $a, $b")
       |
       |// ── Built-in `Async` effect + v1.11 coroutine-based `runAsync` ─────────
       |//
       |// v1.11: Async.* ops check _coHandleTL.  When set (inside a runAsync
       |// virtual thread), they suspend with an IORequest case class instead of
       |// returning a _Perform node.  The runAsync scheduler drives the coroutine
       |// and dispatches IORequests.  runAsyncParallel still uses the old Free
       |// monad path (Async.* return _perform nodes when _coHandleTL is null).
       |
       |// IORequest types for the runAsync coroutine scheduler
       |private case class _DelayIO(ms: Long)
       |private case class _AsyncIO(thunk: () => Any)
       |private case class _AwaitIO(fut: Any)
       |private case class _ParallelIO(thunks: List[() => Any])
       |private case class _RecvFromIO(ws: Any)
       |
       |object Async:
       |  def delay(ms: Int): Any =
       |    val coH = _coHandleTL.get()
       |    if coH != null then
       |      coH.fromBody.put(Yielded(_DelayIO(ms.toLong)))
       |      coH.toBody.take()
       |    else _perform("Async", "delay", ms)
       |  def async(thunk: () => Any): Any =
       |    val coH = _coHandleTL.get()
       |    if coH != null then
       |      coH.fromBody.put(Yielded(_AsyncIO(thunk)))
       |      coH.toBody.take()
       |    else _perform("Async", "async", thunk)
       |  def await(fut: Any): Any =
       |    val coH = _coHandleTL.get()
       |    if coH != null then
       |      coH.fromBody.put(Yielded(_AwaitIO(fut)))
       |      coH.toBody.take()
       |    else _perform("Async", "await", fut)
       |  def parallel(thunks: List[() => Any]): Any =
       |    val coH = _coHandleTL.get()
       |    if coH != null then
       |      coH.fromBody.put(Yielded(_ParallelIO(thunks)))
       |      coH.toBody.take()
       |    else _perform("Async", "parallel", thunks)
       |  def recvFrom(ws: Any): Any =
       |    val coH = _coHandleTL.get()
       |    if coH != null then
       |      coH.fromBody.put(Yielded(_RecvFromIO(ws)))
       |      coH.toBody.take()
       |    else
       |      ws.asInstanceOf[Map[String, Any]]("recv").asInstanceOf[() => Any]()
       |
       |case class Future(value: Any)
       |
       |// ── CPS-aware collection helpers (sequence Free callbacks) ──────────
       |//
       |// In CPS-emitted bodies the receiver of `xs.map(fn)` is typed `Any`
       |// (the Free monad's value carrier), so Scala can't resolve `.map`
       |// statically.  `_dispatch` runs the method at runtime — for HOFs
       |// it routes through `_seq*` helpers that thread per-element Free
       |// results into a single sequenced Free, matching the interpreter's
       |// `Computation.sequence` semantics.  Pure callbacks short-circuit
       |// (no Free anywhere → return the plain array).
       |
       |def _isFree(c: Any): Boolean = c.isInstanceOf[_Computation]
       |
       |def _seq(comps: List[Any]): Any =
       |  if !comps.exists(_isFree) then comps
       |  else
       |    def loop(i: Int, acc: List[Any]): Any =
       |      if i == comps.length then acc
       |      else _bind(comps(i), (v: Any) => loop(i + 1, acc :+ v))
       |    loop(0, Nil)
       |
       |def _seqMap(xs: List[Any], fn: Any => Any): Any =
       |  _seq(xs.map(fn))
       |
       |def _seqFlatMap(xs: List[Any], fn: Any => Any): Any =
       |  val s = _seqMap(xs, fn)
       |  // Option-returning fns flatten via .toList (Some(v) → [v];
       |  // None → []) so `xs.flatMap(x => Option[v])` works at
       |  // runtime like the Scala stdlib does.
       |  def flatten(v: Any): List[Any] = v match
       |    case ys: List[_]   => ys.asInstanceOf[List[Any]]
       |    case opt: Option[_] => opt.toList.asInstanceOf[List[Any]]
       |    case other         => List(other)
       |  s match
       |    case c: _Computation =>
       |      _bind(c, (rs: Any) => rs.asInstanceOf[List[Any]].flatMap(flatten))
       |    case rs: List[_] => rs.asInstanceOf[List[Any]].flatMap(flatten)
       |    case _ => s
       |
       |def _seqFilter(xs: List[Any], fn: Any => Any, neg: Boolean): Any =
       |  val flags = xs.map(fn)
       |  val pick = (bs: List[Any]) => xs.zip(bs).collect {
       |    case (x, b: Boolean) if (if neg then !b else b) => x
       |  }
       |  _seq(flags) match
       |    case c: _Computation => _bind(c, (bs: Any) => pick(bs.asInstanceOf[List[Any]]))
       |    case bs: List[_]     => pick(bs.asInstanceOf[List[Any]])
       |    case other           => other
       |
       |def _seqForeach(xs: List[Any], fn: Any => Any): Any =
       |  _seq(xs.map(fn)) match
       |    case c: _Computation => _bind(c, (_: Any) => ())
       |    case _               => ()
       |
       |def _seqExists(xs: List[Any], fn: Any => Any): Any =
       |  _seq(xs.map(fn)) match
       |    case c: _Computation => _bind(c, (bs: Any) =>
       |      bs.asInstanceOf[List[Any]].exists { case b: Boolean => b; case _ => false })
       |    case bs: List[_]     =>
       |      bs.asInstanceOf[List[Any]].exists { case b: Boolean => b; case _ => false }
       |    case _ => false
       |
       |def _seqForall(xs: List[Any], fn: Any => Any): Any =
       |  _seq(xs.map(fn)) match
       |    case c: _Computation => _bind(c, (bs: Any) =>
       |      bs.asInstanceOf[List[Any]].forall { case b: Boolean => b; case _ => false })
       |    case bs: List[_]     =>
       |      bs.asInstanceOf[List[Any]].forall { case b: Boolean => b; case _ => false }
       |    case _ => true
       |
       |def _seqCount(xs: List[Any], fn: Any => Any): Any =
       |  _seq(xs.map(fn)) match
       |    case c: _Computation => _bind(c, (bs: Any) =>
       |      bs.asInstanceOf[List[Any]].count { case b: Boolean => b; case _ => false })
       |    case bs: List[_]     =>
       |      bs.asInstanceOf[List[Any]].count { case b: Boolean => b; case _ => false }
       |    case _ => 0
       |
       |def _seqFind(xs: List[Any], fn: Any => Any): Any =
       |  val flags = xs.map(fn)
       |  val pick  = (bs: List[Any]) =>
       |    val i = bs.indexWhere { case b: Boolean => b; case _ => false }
       |    if i < 0 then None else Some(xs(i))
       |  _seq(flags) match
       |    case c: _Computation => _bind(c, (bs: Any) => pick(bs.asInstanceOf[List[Any]]))
       |    case bs: List[_]     => pick(bs.asInstanceOf[List[Any]])
       |    case _               => None
       |
       |def _seqFoldLeft(xs: List[Any], init: Any, fn: (Any, Any) => Any): Any =
       |  def loop(i: Int, acc: Any): Any =
       |    if i == xs.length then acc
       |    else
       |      val next = fn(acc, xs(i))
       |      next match
       |        case c: _Computation => _bind(c, (v: Any) => loop(i + 1, v))
       |        case v               => loop(i + 1, v)
       |  loop(0, init)
       |
       |/** Runtime method dispatcher used in CPS contexts where the receiver
       | *  is statically `Any`.  Covers the collection HOFs that need
       | *  Free-aware sequencing plus the common direct methods used inside
       | *  `runAsync`/`handle` bodies.  Methods we don't know about fall
       | *  through to Java reflection so a typo at the call site surfaces
       | *  as the same NoSuchMethod we'd get with a direct call. */
       |def _dispatch(obj: Any, method: String, args: List[Any]): Any =
       |  (obj, method, args) match
       |    // List HOFs — CPS-aware
       |    case (xs: List[_], "map",       List(fn))   => _seqMap     (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any])
       |    case (xs: List[_], "flatMap",   List(fn))   => _seqFlatMap (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any])
       |    case (xs: List[_], "filter",    List(fn))   => _seqFilter  (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any], neg = false)
       |    case (xs: List[_], "filterNot", List(fn))   => _seqFilter  (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any], neg = true)
       |    case (xs: List[_], "foreach",   List(fn))   => _seqForeach (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any])
       |    case (xs: List[_], "exists",    List(fn))   => _seqExists  (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any])
       |    case (xs: List[_], "forall",    List(fn))   => _seqForall  (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any])
       |    case (xs: List[_], "find",      List(fn))   => _seqFind    (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any])
       |    case (xs: List[_], "count",     List(fn))   => _seqCount   (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any])
       |    case (xs: List[_], "foldLeft",  List(init)) =>
       |      // Curried in Scala: foldLeft(init)(fn) — return the fn-taker.
       |      (fn: ((Any, Any) => Any)) => _seqFoldLeft(xs.asInstanceOf[List[Any]], init, fn)
       |    // Direct List methods we use commonly inside CPS bodies
       |    case (xs: List[_], "head",     Nil)       => xs.head
       |    case (xs: List[_], "tail",     Nil)       => xs.tail
       |    case (xs: List[_], "size",     Nil)       => xs.size
       |    case (xs: List[_], "length",   Nil)       => xs.length
       |    case (xs: List[_], "isEmpty",  Nil)       => xs.isEmpty
       |    case (xs: List[_], "nonEmpty", Nil)       => xs.nonEmpty
       |    case (xs: List[_], "reverse",  Nil)       => xs.reverse
       |    // `.toMap` / `.toSet` carry implicit evidence — reflection
       |    // sees them as 1-arg methods that don't match a Nil call.
       |    case (xs: List[_], "toMap",    Nil)       =>
       |      xs.asInstanceOf[List[(Any, Any)]].toMap
       |    case (xs: List[_], "toSet",    Nil)       => xs.toSet
       |    case (xs: List[_], "zip",      List(other)) =>
       |      xs.zip(other.asInstanceOf[Iterable[Any]])
       |    case (xs: List[_], "zipWithIndex", Nil)   => xs.zipWithIndex
       |    // `.sortBy(fn)` carries an implicit Ordering — like toMap,
       |    // reflection-arity check rejects the 2-arg signature.
       |    case (xs: List[_], "sortBy",  List(fn))   =>
       |      given _ordAny: Ordering[Any] = new Ordering[Any]:
       |        def compare(a: Any, b: Any): Int = (a, b) match
       |          case (x: Int,    y: Int)    => x.compare(y)
       |          case (x: Long,   y: Long)   => x.compare(y)
       |          case (x: Double, y: Double) => x.compare(y)
       |          case (x: String, y: String) => x.compare(y)
       |          case _ => a.toString.compare(b.toString)
       |      xs.asInstanceOf[List[Any]].sortBy(fn.asInstanceOf[Any => Any])
       |    case (xs: List[_], "sorted", Nil) =>
       |      given _ordAny: Ordering[Any] = new Ordering[Any]:
       |        def compare(a: Any, b: Any): Int = (a, b) match
       |          case (x: Int,    y: Int)    => x.compare(y)
       |          case (x: Long,   y: Long)   => x.compare(y)
       |          case (x: Double, y: Double) => x.compare(y)
       |          case (x: String, y: String) => x.compare(y)
       |          case _ => a.toString.compare(b.toString)
       |      xs.asInstanceOf[List[Any]].sorted
       |    case (xs: List[_], "groupBy", List(fn))   =>
       |      xs.asInstanceOf[List[Any]].groupBy(fn.asInstanceOf[Any => Any])
       |    case (xs: List[_], "headOption", Nil)     => xs.headOption
       |    case (xs: List[_], "lastOption", Nil)     => xs.lastOption
       |    case (xs: List[_], "drop",   List(n: Int))  => xs.drop(n)
       |    case (xs: List[_], "take",   List(n: Int))  => xs.take(n)
       |    case (xs: List[_], "distinct", Nil)       => xs.distinct
       |    case (xs: List[_], "contains", List(x))   =>
       |      xs.asInstanceOf[List[Any]].contains(x)
       |    case (xs: List[_], "mkString", Nil)       => xs.mkString
       |    case (xs: List[_], "mkString", List(s: String)) => xs.mkString(s)
       |    case (xs: List[_], "sum",      Nil)       => xs.asInstanceOf[List[Any]].foldLeft(0: Any)((a, b) => _binOp("+", a, b))
       |    case (s: String,   "length",   Nil)       => s.length
       |    case (s: String,   "size",     Nil)       => s.length
       |    case (s: String,   "toInt",    Nil)       => s.toInt
       |    case (s: String,   "toLong",   Nil)       => s.toLong
       |    case (s: String,   "toDouble", Nil)       => s.toDouble
       |    case (s: String,   "take",     List(n: Int))  => s.take(n)
       |    case (s: String,   "drop",     List(n: Int))  => s.drop(n)
       |    case (s: String,   "head",     Nil)       => s.head
       |    case (s: String,   "tail",     Nil)       => s.tail
       |    case (s: String,   "isEmpty",  Nil)       => s.isEmpty
       |    case (s: String,   "nonEmpty", Nil)       => s.nonEmpty
       |    case (s: String,   "trim",     Nil)       => s.trim
       |    case (s: String,   "toLowerCase", Nil)    => s.toLowerCase
       |    case (s: String,   "toUpperCase", Nil)    => s.toUpperCase
       |    case (s: String,   "split",    List(sep: String)) => s.split(sep).toList
       |    // Option — `getOrElse` takes a by-name param which Java
       |    // reflection can't resolve directly from a String arg.
       |    case (opt: Option[_], "get",        Nil)       => opt.get
       |    case (opt: Option[_], "getOrElse",  List(d))   => opt.getOrElse(d)
       |    case (opt: Option[_], "isDefined",  Nil)       => opt.isDefined
       |    case (opt: Option[_], "isEmpty",    Nil)       => opt.isEmpty
       |    case (opt: Option[_], "nonEmpty",   Nil)       => opt.nonEmpty
       |    case (opt: Option[_], "map",        List(fn))  =>
       |      opt.asInstanceOf[Option[Any]].map(fn.asInstanceOf[Any => Any])
       |    case (opt: Option[_], "flatMap",    List(fn))  =>
       |      opt.asInstanceOf[Option[Any]].flatMap(x => fn.asInstanceOf[Any => Option[Any]](x))
       |    case (opt: Option[_], "foreach",    List(fn))  =>
       |      opt.asInstanceOf[Option[Any]].foreach(fn.asInstanceOf[Any => Any]); ()
       |    // Map ops — by-name default arg in `getOrElse` confuses
       |    // the reflection fallback, so dispatch explicitly.
       |    case (m: Map[_, _], "getOrElse", List(k, d)) =>
       |      m.asInstanceOf[Map[Any, Any]].getOrElse(k, d)
       |    case (m: Map[_, _], "get",       List(k))    =>
       |      m.asInstanceOf[Map[Any, Any]].get(k)
       |    case (m: Map[_, _], "contains",  List(k))    =>
       |      m.asInstanceOf[Map[Any, Any]].contains(k)
       |    case (m: Map[_, _], "size",      Nil)        => m.size
       |    case (m: Map[_, _], "isEmpty",   Nil)        => m.isEmpty
       |    case (m: Map[_, _], "nonEmpty",  Nil)        => m.nonEmpty
       |    case (m: Map[_, _], "keys",      Nil)        =>
       |      m.asInstanceOf[Map[Any, Any]].keys
       |    case (m: Map[_, _], "values",    Nil)        =>
       |      m.asInstanceOf[Map[Any, Any]].values
       |    // Map key access for runtime record types (e.g. `info.mailboxSize` on
       |    // a ProcessInfo map).  Must come after the explicit method cases above.
       |    case (m: Map[_, _], key, Nil)               =>
       |      m.asInstanceOf[Map[Any, Any]].getOrElse(key, null)
       |    // Set ops
       |    case (s: Set[_], "contains",  List(x)) => s.asInstanceOf[Set[Any]].contains(x)
       |    case (s: Set[_], "size",      Nil)     => s.size
       |    case (s: Set[_], "isEmpty",   Nil)     => s.isEmpty
       |    case (s: Set[_], "nonEmpty",  Nil)     => s.nonEmpty
       |    // Numeric widening / narrowing conversions on a boxed primitive value
       |    // (e.g. `Bump.tick().toLong` where the perform result flows through
       |    // `_dispatch` as an Any). Java reflection can't resolve `toLong` on a
       |    // boxed Integer, so dispatch them explicitly.
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
       |    // Fallback: try Java reflection so non-HOF method calls still work
       |    case _ =>
       |      val cls = obj.getClass
       |      val ms  = cls.getMethods.filter(m =>
       |        m.getName == method && m.getParameterCount == args.length)
       |      if ms.isEmpty then
       |        sys.error(s"No method '$method' on ${cls.getName} with ${args.length} arg(s)")
       |      val boxed: Array[Object] = args.map(_.asInstanceOf[AnyRef]).toArray
       |      ms.head.invoke(obj, boxed*)
       |
       |// v1.11 coroutine-based runAsync scheduler
       |def _driveAsyncCo(
       |  fromBody: java.util.concurrent.SynchronousQueue[Any],
       |  toBody:   java.util.concurrent.SynchronousQueue[Any]
       |): Any =
       |  while true do
       |    fromBody.take() match
       |      case Returned(v)           => return v
       |      case Errored(msg)          => throw new RuntimeException(s"Async error: $msg")
       |      case Yielded(_DelayIO(ms)) =>
       |        if ms > 0 then Thread.sleep(ms)
       |        toBody.put(())
       |      case Yielded(_AsyncIO(thunk)) =>
       |        toBody.put(Future(_runAsync(thunk)))
       |      case Yielded(_AwaitIO(fut)) =>
       |        toBody.put(fut match
       |          case Future(v) => v
       |          case other     => sys.error(s"Async.await: expected Future, got $other"))
       |      case Yielded(_ParallelIO(thunks)) =>
       |        toBody.put(thunks.map(_runAsync))
       |      case Yielded(_RecvFromIO(ws)) =>
       |        toBody.put(ws.asInstanceOf[Map[String, Any]]("recv").asInstanceOf[() => Any]())
       |      case other =>
       |        sys.error(s"_driveAsyncCo: unexpected step: $other")
       |  sys.error("unreachable")
       |
       |def _runAsync(bodyThunk: () => Any): Any =
       |  val fromBody = new java.util.concurrent.SynchronousQueue[Any]()
       |  val toBody   = new java.util.concurrent.SynchronousQueue[Any]()
       |  val handle   = _CoHandle(fromBody, toBody)
       |  Thread.ofVirtual().start { () =>
       |    _coHandleTL.set(handle)
       |    toBody.take()
       |    try
       |      val result = bodyThunk()
       |      fromBody.put(Returned(result))
       |    catch case t: Throwable =>
       |      val msg = Option(t.getMessage).getOrElse(t.getClass.getSimpleName)
       |      try fromBody.put(Errored(msg)) catch case _: Throwable => ()
       |  }
       |  toBody.put(())
       |  _driveAsyncCo(fromBody, toBody)
       |
       |// ── runAsyncParallel: real-thread alternate handler ────────────────────
       |//
       |// Same `Async.*` API as `runAsync` but `async` / `parallel` submit
       |// their thunks to an `ExecutorService`.  `await` blocks the calling
       |// thread on the future; `parallel` waits on each future in declared
       |// order so the result list mirrors input order regardless of
       |// completion order — value-deterministic code retains byte-identical
       |// output across the single- and parallel-handler variants.
       |
       |val _parallelFutures =
       |  new java.util.concurrent.ConcurrentHashMap[Long, java.util.concurrent.Future[Any]]()
       |val _parallelFutureSeq = new java.util.concurrent.atomic.AtomicLong(0L)
       |def _freshFutureId(): Long = _parallelFutureSeq.incrementAndGet()
       |
       |def _runAsyncParallel(bodyThunk: () => Any): Any =
       |  // Java 21 requirement: virtual threads (Project Loom) for lightweight parallelism.
       |  val _ex = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
       |  try
       |    def dispatch(op: String, args: List[Any], resume: Any => Any): Any = op match
       |      case "delay" =>
       |        val ms = args(0).asInstanceOf[Int]
       |        if ms > 0 then Thread.sleep(ms.toLong)
       |        resume(())
       |      case "async" =>
       |        val thunk = args(0).asInstanceOf[() => Any]
       |        val fut: java.util.concurrent.Future[Any] = _ex.submit(
       |          new java.util.concurrent.Callable[Any] {
       |            def call(): Any = interp(thunk())
       |          })
       |        val fid = _freshFutureId()
       |        _parallelFutures.put(fid, fut)
       |        resume(Future(("_parId", fid)))
       |      case "await" =>
       |        args(0) match
       |          case Future(("_parId", fid: Long)) =>
       |            val fut = _parallelFutures.remove(fid)
       |            if fut == null then sys.error("Async.await: stale Future")
       |            resume(fut.get())
       |          case Future(v) => resume(v)
       |          case _         => sys.error("Async.await(future)")
       |      case "parallel" =>
       |        val thunks = args(0).asInstanceOf[List[() => Any]]
       |        val futs = thunks.map { t =>
       |          _ex.submit(new java.util.concurrent.Callable[Any] {
       |            def call(): Any = interp(t())
       |          })
       |        }
       |        resume(futs.map(_.get()))
       |      case _ => sys.error("Unknown Async operation: " + op)
       |    def interp(initial: Any): Any =
       |      var current: Any = initial
       |      while true do
       |        current match
       |          case _Perform("Async", op, args) =>
       |            current = dispatch(op, args, (v: Any) => v)
       |          case _Perform(_, _, _) => return current
       |          case _FlatMap(sub, f) => sub match
       |            case _Perform("Async", op, args) =>
       |              val fn = f.asInstanceOf[Any => Any]
       |              current = dispatch(op, args, (v: Any) => interp(fn(v)))
       |            case _Perform(_, _, _) =>
       |              val fn = f.asInstanceOf[Any => Any]
       |              return _FlatMap(sub, (v: Any) => interp(fn(v)))
       |            case _FlatMap(s2, g) =>
       |              current = _FlatMap(s2,
       |                (x: Any) => _FlatMap(g.asInstanceOf[Any => Any](x), f))
       |            case v =>
       |              current = f.asInstanceOf[Any => Any](v)
       |          case v => return v
       |      throw new RuntimeException("unreachable")
       |    interp(bodyThunk())
       |  finally _ex.shutdown()
       |
       |// ── Storage: built-in key-value effect ─────────────────────────────────
       |//
       |// `Storage.{get,put,remove,has,keys}` produce `_Perform("Storage",
       |// op, args)` nodes; `_runStorage(bodyThunk, path)` is the handler.
       |// When `path` is non-null it hydrates from / flushes to that JSON
       |// file on every mutation (file-backed); otherwise the map stays
       |// in-process and is discarded at scope exit (ephemeral mode).
       |
       |def _storageLoad(path: String, state: scala.collection.mutable.LinkedHashMap[String, String]): Unit =
       |  val p = java.nio.file.Paths.get(path)
       |  if java.nio.file.Files.exists(p) then
       |    val src = java.nio.file.Files.readString(p).trim
       |    if src.startsWith("{") && src.endsWith("}") then
       |      var i = 1
       |      val end = src.length - 1
       |      def skipWs(): Unit = while i < end && src.charAt(i).isWhitespace do i += 1
       |      def readStr(): String =
       |        if i >= end || src.charAt(i) != '"' then sys.error(s"Storage JSON: expected string at $i")
       |        i += 1
       |        val sb = new StringBuilder
       |        while i < end && src.charAt(i) != '"' do
       |          if src.charAt(i) == '\\' && i + 1 < end then
       |            src.charAt(i + 1) match
       |              case '"'  => sb.append('"');  i += 2
       |              case '\\' => sb.append('\\'); i += 2
       |              case 'n'  => sb.append('\n'); i += 2
       |              case 't'  => sb.append('\t'); i += 2
       |              case 'r'  => sb.append('\r'); i += 2
       |              case c    => sb.append(c);    i += 2
       |          else { sb.append(src.charAt(i)); i += 1 }
       |        i += 1
       |        sb.toString
       |      skipWs()
       |      while i < end do
       |        val k = readStr(); skipWs()
       |        if i >= end || src.charAt(i) != ':' then sys.error("Storage JSON: expected ':'")
       |        i += 1; skipWs()
       |        val v = readStr(); skipWs()
       |        state(k) = v
       |        if i < end && src.charAt(i) == ',' then i += 1
       |        skipWs()
       |
       |def _storageSave(path: String, state: scala.collection.mutable.LinkedHashMap[String, String]): Unit =
       |  def esc(s: String): String =
       |    val sb = new StringBuilder
       |    sb.append('"')
       |    s.foreach {
       |      case '"'  => sb.append("\\\"")
       |      case '\\' => sb.append("\\\\")
       |      case '\n' => sb.append("\\n")
       |      case '\r' => sb.append("\\r")
       |      case '\t' => sb.append("\\t")
       |      case c    => sb.append(c)
       |    }
       |    sb.append('"').toString
       |  val body = state.iterator.map { case (k, v) => esc(k) + ":" + esc(v) }.mkString(",")
       |  java.nio.file.Files.writeString(java.nio.file.Paths.get(path), "{" + body + "}")
       |
       |def _runStorage(bodyThunk: () => Any, path: String): Any =
       |  val state = scala.collection.mutable.LinkedHashMap.empty[String, String]
       |  if path != null then _storageLoad(path, state)
       |  def flush(): Unit = if path != null then _storageSave(path, state)
       |  def dispatch(op: String, args: List[Any], resume: Any => Any): Any = op match
       |    case "get" =>
       |      val k = args(0).asInstanceOf[String]
       |      resume(if state.contains(k) then Some(state(k)) else None)
       |    case "put" =>
       |      val k = args(0).asInstanceOf[String]
       |      state(k) = _show(args(1))
       |      flush()
       |      resume(())
       |    case "remove" =>
       |      state.remove(args(0).asInstanceOf[String])
       |      flush()
       |      resume(())
       |    case "has" => resume(state.contains(args(0).asInstanceOf[String]))
       |    case "keys" => resume(state.keys.toList)
       |    case _ => sys.error("Unknown Storage operation: " + op)
       |  def interp(initial: Any): Any =
       |    var current: Any = initial
       |    while true do
       |      current match
       |        case _Perform("Storage", op, args) =>
       |          current = dispatch(op, args, (v: Any) => v)
       |        case _Perform(_, _, _) => return current
       |        case _FlatMap(sub, f) => sub match
       |          case _Perform("Storage", op, args) =>
       |            val fn = f.asInstanceOf[Any => Any]
       |            current = dispatch(op, args, (v: Any) => interp(fn(v)))
       |          case _Perform(_, _, _) =>
       |            val fn = f.asInstanceOf[Any => Any]
       |            return _FlatMap(sub, (v: Any) => interp(fn(v)))
       |          case _FlatMap(s2, g) =>
       |            current = _FlatMap(s2,
       |              (x: Any) => _FlatMap(g.asInstanceOf[Any => Any](x), f))
       |          case v =>
       |            current = f.asInstanceOf[Any => Any](v)
       |        case v => return v
       |    throw new RuntimeException("unreachable")
       |  interp(bodyThunk())
       |""".stripMargin +
    """|
       |// ── v1.6 Actors — Phase 1 cooperative scheduler ────────────────────────
       |//
       |// Same Computation / Free-Monad walk as `_runAsync` but the outer
       |// loop interleaves multiple actors.  Mailboxes are `LinkedBlockingQueue`s
       |// (v1.9.x: same infrastructure as coroutines, thread-safe);
       |// blocked-on-receive state lives on each actor along with the
       |// captured continuation.  Quiescence with timeout-armed receives
       |// sleeps until the earliest deadline and resumes that actor with
       |// `None`.  Single-threaded for parity with the interpreter and
       |// JsGen — a Loom variant can swap the scheduler later without
       |// changing the API surface.
       |
       |// Phase 3: nodeId="" means local (backward-compatible default)
       |case class _Pid(nodeId: String, localId: Long)
       |// v1.6 Phase 2 — supervision message types visible to ScalaScript code
       |case class Exit(from: Any, reason: Any)
       |case class Down(ref: Any, from: Any, reason: Any)
       |case object noproc
       |// v1.23 — cluster visibility events
       |case class NodeJoined(nodeId: String)
       |case class NodeLeft(nodeId: String, reason: String)
       |// v1.23 — leader election (Bully) events
       |case class LeaderElected(nodeId: String)
       |case class LeaderLost(nodeId: String)
       |// v1.23 — config-distribution events
       |case class ConfigChanged(key: String, value: String)
       |// v1.23 — drain / rolling-restart events
       |case class DrainStateChanged(nodeId: String, draining: Boolean)
       |// v1.23 — cluster metrics aggregation events
       |case class MetricChanged(name: String, nodeId: String, value: Double)
       |
       |/** Adapter: a partial-function literal becomes a total
       | *  `Any => Option[Any]`.  Used by emitReceiveMatcher so the
       | *  generated source doesn't fight Scala 3's `(x) => x match`
       | *  postfix-match precedence trap. */
       |def _pfToFun(pf: PartialFunction[Any, Option[Any]]): Any => Option[Any] =
       |  (msg: Any) => pf.applyOrElse(msg, (_: Any) => None)
       |
       |val _receiveSpecs =
       |  new java.util.concurrent.ConcurrentHashMap[Long, Any => Option[Any]]()
       |val _receiveSpecSeq = new java.util.concurrent.atomic.AtomicLong(0L)
       |def _registerReceive(matcher: Any => Option[Any]): Long =
       |  val id = _receiveSpecSeq.incrementAndGet()
       |  _receiveSpecs.put(id, matcher)
       |  id
       |
       |object Actor:
       |  def spawn(thunk: () => Any): Any              = _perform("Actor", "spawn",       thunk)
       |  def spawn_link(thunk: () => Any): Any         = _perform("Actor", "spawnLink",   thunk)
       |  def self(): Any                               = _perform("Actor", "self")
       |  def send(pid: Any, msg: Any): Any             = _perform("Actor", "send",        pid, msg)
       |  def exit(pid: Any, reason: Any): Any          = _perform("Actor", "exit",        pid, reason)
       |  def receive_(specId: Long): Any               = _perform("Actor", "receive",     specId)
       |  def receive_t(specId: Long, ms: Any): Any     = _perform("Actor", "receive_t",   specId, ms)
       |  // v1.6 Phase 2 — supervision
       |  def link(pid: Any): Any                       = _perform("Actor", "link",        pid)
       |  def monitor(pid: Any): Any                    = _perform("Actor", "monitor",     pid)
       |  def demonitor(ref: Any): Any                  = _perform("Actor", "demonitor",   ref)
       |  def trapExit(b: Any): Any                     = _perform("Actor", "trapExit",    b)
       |  // v1.6 Phase 3 — distributed
       |  def startNode(nodeId: Any, url: Any = ""): Any = _perform("Actor", "startNode",   nodeId, url)
       |  def connectNode(url: Any, tok: Any = ""): Any  = _perform("Actor", "connectNode", url, tok)
       |  def joinCluster(seeds: Any, tok: Any = ""): Any = _perform("Actor", "joinCluster", seeds, tok)
       |  def register(name: Any, pid: Any): Any             = _perform("Actor", "register",       name, pid)
       |  def whereis(name: Any): Any                        = _perform("Actor", "whereis",        name)
       |  // v1.6.x — cluster-wide registry
       |  def globalRegister(name: Any, pid: Any): Any       = _perform("Actor", "globalRegister", name, pid)
       |  def globalWhereis(name: Any): Any                  = _perform("Actor", "globalWhereis",  name)
       |  // v1.6.x — scheduled sends
       |  def sendAfter(delayMs: Any, pid: Any, msg: Any): Any   = _perform("Actor", "sendAfter",   delayMs, pid, msg)
       |  def sendInterval(periodMs: Any, pid: Any, msg: Any): Any = _perform("Actor", "sendInterval", periodMs, pid, msg)
       |  def cancelTimer(ref: Any): Any                          = _perform("Actor", "cancelTimer", ref)
       |  // v1.6.x — bounded mailbox spawn
       |  def spawnBounded(cap: Any, overflow: Any, thunk: () => Any): Any = _perform("Actor", "spawnBounded", cap, overflow, thunk)
       |  // v1.6.x — process introspection
       |  def processInfo(pid: Any): Any = _perform("Actor", "processInfo", pid)
       |  // v1.23 — cluster visibility
       |  def clusterMembers(): Any         = _perform("Actor", "clusterMembers")
       |  def subscribeClusterEvents(): Any = _perform("Actor", "subscribeClusterEvents")
       |  // v1.23 — phi-accrual failure detector
       |  def phiOf(nid: Any): Any           = _perform("Actor", "phiOf", nid)
       |  def isSuspect(nid: Any, thr: Any = 8.0): Any = _perform("Actor", "isSuspect", nid, thr)
       |  // v1.23 — local node identity + phi vector
       |  def selfNode(): Any      = _perform("Actor", "selfNode")
       |  def clusterHealth(): Any = _perform("Actor", "clusterHealth")
       |  // v1.23 — cluster-wide failure detector
       |  def broadcastHealth(): Any                            = _perform("Actor", "broadcastHealth")
       |  def clusterIsDown(nid: Any, thr: Any = 8.0): Any      = _perform("Actor", "clusterIsDown", nid, thr)
       |  // v1.23 — leader election (Bully)
       |  def electLeader(): Any                                = _perform("Actor", "electLeader")
       |  def currentLeader(): Any                              = _perform("Actor", "currentLeader")
       |  def subscribeLeaderEvents(): Any                      = _perform("Actor", "subscribeLeaderEvents")
       |  def setAutoReelect(enabled: Any): Any                 = _perform("Actor", "setAutoReelect", enabled)
       |  // v1.23 — leader-protocol switch (Raft / external coordinator stubs).
       |  // See specs/cluster-raft.md for the spec.  Calling these promotes the
       |  // node off Bully but the alternative protocols' actual algorithms
       |  // land in subsequent phases — for now these mark intent and let
       |  // `leaderProtocol()` observe it.
       |  def useRaftLeaderElection(): Any                      = _perform("Actor", "useRaftLeaderElection")
       |  def useExternalCoordinator(acquireLease: Any, renewLease: Any,
       |                              releaseLease: Any, currentHolder: Any): Any =
       |    _perform("Actor", "useExternalCoordinator", acquireLease, renewLease, releaseLease, currentHolder)
       |  def leaderProtocol(): Any                             = _perform("Actor", "leaderProtocol")
       |  // v1.23 — bounded ring buffer of accepted leader claims this node has
       |  // observed.  Each entry is (term, leaderId, wallClockMs).
       |  def leaderHistory(): Any                              = _perform("Actor", "leaderHistory")
       |  // v1.23 — auto-reconnect policy (exponential backoff per peer)
       |  def setReconnectPolicy(initialMs: Any, maxMs: Any): Any = _perform("Actor", "setReconnectPolicy", initialMs, maxMs)
       |  def setReconnectPolicy(initialMs: Any, maxMs: Any, giveUpAfterMs: Any): Any =
       |    _perform("Actor", "setReconnectPolicy", initialMs, maxMs, giveUpAfterMs)
       |  // v1.23 — per-link heartbeat cadence + dead-after threshold
       |  def setHeartbeatTimeout(intervalMs: Any, deadAfterMs: Any): Any =
       |    _perform("Actor", "setHeartbeatTimeout", intervalMs, deadAfterMs)
       |  // v1.23 — quorum-aware Bully threshold (split-brain guard)
       |  def setQuorumSize(n: Any): Any = _perform("Actor", "setQuorumSize", n)
       |  // v1.23 — cluster endpoint shared-secret
       |  def setClusterAuthToken(token: Any): Any = _perform("Actor", "setClusterAuthToken", token)
       |  // v1.23 — periodic gossip re-discovery (ask peers for their peer list)
       |  def requestGossip(): Any = _perform("Actor", "requestGossip")
       |  // v1.23 — cluster configuration distribution
       |  def clusterConfigSet(key: Any, value: Any): Any  = _perform("Actor", "clusterConfigSet", key, value)
       |  def clusterConfigGet(key: Any): Any              = _perform("Actor", "clusterConfigGet", key)
       |  def clusterConfigKeys(): Any                     = _perform("Actor", "clusterConfigKeys")
       |  def subscribeConfigEvents(): Any                 = _perform("Actor", "subscribeConfigEvents")
       |  // v1.23 — drain / rolling-restart
       |  def setDraining(b: Any): Any                     = _perform("Actor", "setDraining", b)
       |  def isDraining(): Any                            = _perform("Actor", "isDraining")
       |  def drainingPeers(): Any                         = _perform("Actor", "drainingPeers")
       |  def subscribeDrainEvents(): Any                  = _perform("Actor", "subscribeDrainEvents")
       |  // v1.23 — cluster metrics aggregation
       |  def clusterMetricSet(name: Any, value: Any): Any = _perform("Actor", "clusterMetricSet", name, value)
       |  def clusterMetricGet(name: Any): Any             = _perform("Actor", "clusterMetricGet", name)
       |  def clusterMetricSum(name: Any): Any             = _perform("Actor", "clusterMetricSum", name)
       |  def clusterMetricNames(): Any                    = _perform("Actor", "clusterMetricNames")
       |  def subscribeMetricEvents(): Any                 = _perform("Actor", "subscribeMetricEvents")
       |
       |// v1.6.x — bounded mailbox overflow strategies.  Plain string values so
       |// `spawnBounded(cap, Overflow.DropOldest, thunk)` compiles and passes the
       |// right string to the actor scheduler.
       |object Overflow:
       |  val DropOldest: Any = "DropOldest"
       |  val DropNewest: Any = "DropNewest"
       |  val Block: Any = "Block"
       |  val Fail: Any = "Fail"
       |
       |class _ActorState:
       |  val mailbox = new java.util.concurrent.LinkedBlockingQueue[Any]()
       |  var pending: Any = null
       |  // (matcher, k, deadline?, wrapSome)
       |  var blocked: (Any => Option[Any], Any => Any, Option[Long], Boolean) = null
       |  // v1.6.x bounded mailbox
       |  var cap:      Int    = 0   // 0 = unbounded
       |  var overflow: String = ""
       |  val blockedSends = scala.collection.mutable.ArrayDeque.empty[(Long, Any, Any => Any)]
       |
       |def _runActors(bodyThunk: () => Any): Any =
       |  val actors    = scala.collection.mutable.LongMap.empty[_ActorState]
       |  // Phase 2 supervision state
       |  val links     = scala.collection.mutable.LongMap.empty[scala.collection.mutable.Set[Long]]
       |  val monitors  = scala.collection.mutable.LongMap.empty[scala.collection.mutable.Map[Long, Long]]
       |  val trapExitM = scala.collection.mutable.LongMap.empty[Boolean]
       |  var nextMonRef: Long = 0L
       |  // v1.6.x scheduled sends — timerId → (fireAt, periodMs, targetId, msg)
       |  val _timers      = scala.collection.mutable.LongMap.empty[(Long, Option[Long], Long, Any)]
       |  var _nextTimerId: Long = 0L
       |  // Phase 3 distributed state
       |  var _localNodeId:  String  = ""
       |  var _localNodeUrl: String  = ""
       |  @volatile var _joinMode:  Boolean = false
       |  @volatile var _joinToken: String  = ""
       |  val _peerUrls       = new java.util.concurrent.ConcurrentHashMap[String, String]()
       |  val _nodeRegistry    = new java.util.concurrent.ConcurrentHashMap[String, Long]()
       |  val _globalRegistry  = new java.util.concurrent.ConcurrentHashMap[String, _Pid]()
       |  val _peerChannels    = new java.util.concurrent.ConcurrentHashMap[String, String => Unit]()
       |  val _remoteInbox    = new java.util.concurrent.ConcurrentLinkedQueue[(Long, Any)]()
       |  case class _RemoteHandlerInfo(function: String, path: Option[String], requestType: Option[String], responseType: Option[String], transports: Set[String])
       |  val _remoteHandlers = new java.util.concurrent.ConcurrentHashMap[String, _RemoteHandlerInfo]()
       |  val _peerLastPong   = new java.util.concurrent.ConcurrentHashMap[String, Long]()
       |  val _nodeDownQueue  = new java.util.concurrent.ConcurrentLinkedQueue[String]()
       |  // cross-node monitors: nodeId → [(localActorId, monRef, remotePid.localId)]
       |  val _remoteMonitors = new java.util.concurrent.ConcurrentHashMap[String,
       |    java.util.concurrent.CopyOnWriteArrayList[(Long, Long, Long)]]()
       |  // cross-node links:   nodeId → [(localActorId, remotePid.localId)]
       |  val _remoteLinks    = new java.util.concurrent.ConcurrentHashMap[String,
       |    java.util.concurrent.CopyOnWriteArrayList[(Long, Long)]]()
       |  // v1.23 — cluster visibility
       |  val _clusterEventSubs  = new java.util.concurrent.CopyOnWriteArrayList[java.lang.Long]()
       |  val _clusterEventQueue = new java.util.concurrent.ConcurrentLinkedQueue[(String, String, String)]()
       |  // JSON string-escape helper — hoisted above all subsequent vals so
       |  // nested defs can forward-reference it without Scala 3 flagging the
       |  // ref as "extending over" a val initialiser.
       |  def _jstr(s: String): String =
       |    val sb = new StringBuilder(s.length + 2).append('"')
       |    s.foreach { case '"' => sb.append("\\\""); case '\\' => sb.append("\\\\")
       |                case '\n' => sb.append("\\n"); case c => sb.append(c) }
       |    sb.append('"').toString
       |  // v1.23 — bounded ring buffer of cluster events as JSON lines.  Feeds
       |  // `GET /_ssc-cluster/events`.  Independent of in-process subscribers
       |  // — events land here whether or not any actor has called
       |  // `subscribe*Events`, so ops tooling always has data.  Cap 200.
       |  val _CLUSTER_EVENT_LOG_MAX = 200
       |  val _clusterEventLog       = new java.util.concurrent.ConcurrentLinkedDeque[String]()
       |  def _recordEventLog(json: String): Unit =
       |    _clusterEventLog.offer(json)
       |    while _clusterEventLog.size() > _CLUSTER_EVENT_LOG_MAX do _clusterEventLog.pollFirst()
       |  // v1.23 — shared-secret Bearer token for /_ssc-cluster/* endpoints.
       |  // Reads `SSC_CLUSTER_TOKEN` env at startup.  Empty ⇒ endpoints open.
       |  @volatile var _clusterAuthToken: String =
       |    Option(System.getenv("SSC_CLUSTER_TOKEN")).getOrElse("")
       |  def _fireClusterEvent(tag: String, nodeId: String, reason: String = ""): Unit =
       |    val ts = System.currentTimeMillis()
       |    val logEntry =
       |      if tag == "NodeJoined" then
       |        "{\"ts\":" + ts.toString + ",\"type\":\"NodeJoined\",\"nodeId\":" + _jstr(nodeId) + "}"
       |      else
       |        "{\"ts\":" + ts.toString + ",\"type\":\"NodeLeft\",\"nodeId\":" + _jstr(nodeId) +
       |        ",\"reason\":" + _jstr(reason) + "}"
       |    _recordEventLog(logEntry)
       |    if !_clusterEventSubs.isEmpty then _clusterEventQueue.offer((tag, nodeId, reason))
       |  // v1.23 — phi-accrual failure detector: sliding window of inter-pong intervals.
       |  val _PHI_HIST_MAX  = 100
       |  val _peerPongHist  = new java.util.concurrent.ConcurrentHashMap[String,
       |    java.util.concurrent.ConcurrentLinkedDeque[java.lang.Long]]()
       |  // v1.23 — cluster-wide FD: peerNodeId -> view of (targetNodeId -> phi).
       |  val _peerPhiViews  = new java.util.concurrent.ConcurrentHashMap[String,
       |    java.util.concurrent.ConcurrentHashMap[String, java.lang.Double]]()
       |  // v1.23 — leader election (Bully) state.  Single node-wide view.
       |  val _currentLeader        = new java.util.concurrent.atomic.AtomicReference[String]("")
       |  @volatile var _electionInProgress: Boolean = false
       |  @volatile var _electionStartedAt:  Long    = 0L
       |  @volatile var _gotAliveResponse:   Boolean = false
       |  val _ELECTION_TIMEOUT_MS  = 2000L
       |  val _leaderEventSubs      = new java.util.concurrent.CopyOnWriteArrayList[java.lang.Long]()
       |  val _leaderEventQueue     = new java.util.concurrent.ConcurrentLinkedQueue[(String, String)]()
       |  def _fireLeaderEvent(tag: String, leaderId: String): Unit =
       |    val ts = System.currentTimeMillis()
       |    _recordEventLog("{\"ts\":" + ts.toString + ",\"type\":" + _jstr(tag) +
       |                    ",\"nodeId\":" + _jstr(leaderId) + "}")
       |    if !_leaderEventSubs.isEmpty then _leaderEventQueue.offer((tag, leaderId))
       |  @volatile var _autoReelect: Boolean = false
       |  // v1.23 — protocol dispatch (cluster-raft.md §6).  "bully" today;
       |  //   Phase 3a flips to "raft", Phase 3b flips to "coord".
       |  val _leaderProtocol      = new java.util.concurrent.atomic.AtomicReference[String]("bully")
       |  @volatile var _leaderCoordinator: Any = null
       |  // v1.23 — bounded leader-claim history (cluster-raft.md §6).
       |  val _LEADER_HIST_MAX     = 100
       |  val _leaderHistTermSeq   = new java.util.concurrent.atomic.AtomicLong(0L)
       |  val _leaderHist          = new java.util.concurrent.ConcurrentLinkedDeque[(Long, String, Long)]()
       |  def _recordLeaderHist(leaderId: String): Unit =
       |    // Caller still gates on prev != new, so every call is a real change.
       |    val term = _leaderHistTermSeq.incrementAndGet()
       |    _leaderHist.offer((term, leaderId, System.currentTimeMillis()))
       |    while _leaderHist.size() > _LEADER_HIST_MAX do _leaderHist.pollFirst()
       |  // v1.23 — external-coordinator lease state (cluster-raft.md §5).
       |  // Pulled out via `productElement` so the runtime can call them
       |  // without structural types or reflection.
       |  @volatile var _coordAcquireFn: AnyRef = null  // (String, Long) => Boolean
       |  @volatile var _coordRenewFn:   AnyRef = null  // String => Boolean
       |  @volatile var _coordReleaseFn: AnyRef = null  // String => Unit
       |  @volatile var _coordHolderFn:  AnyRef = null  // () => Option[String]
       |  @volatile var _coordIsLeader:  Boolean = false
       |  val _coordTickThread = new java.util.concurrent.atomic.AtomicReference[Thread](null)
       |  val _COORD_LEASE_TIMEOUT_MS  = 5000L
       |  val _COORD_RENEW_INTERVAL_MS = 1000L
       |  def _ensureCoordTickThread(): Unit =
       |    if _coordTickThread.get() != null then return
       |    val t = Thread.ofVirtual().start { () =>
       |      try
       |        var done = false
       |        while !done && _leaderProtocol.get() == "coord" do
       |          try
       |            if !_coordIsLeader then
       |              val acq = _coordAcquireFn
       |              if acq != null then
       |                val got = try acq.asInstanceOf[(String, Long) => Boolean](_localNodeId, _COORD_LEASE_TIMEOUT_MS)
       |                          catch case _: Throwable => false
       |                if got then
       |                  _coordIsLeader = true
       |                  val prev = _currentLeader.getAndSet(_localNodeId)
       |                  if prev != _localNodeId then
       |                    _fireLeaderEvent("LeaderElected", _localNodeId)
       |                    _recordLeaderHist(_localNodeId)
       |            else
       |              val ren = _coordRenewFn
       |              if ren != null then
       |                val ok = try ren.asInstanceOf[String => Boolean](_localNodeId)
       |                         catch case _: Throwable => false
       |                if !ok then
       |                  _coordIsLeader = false
       |                  val prev = _currentLeader.getAndSet("")
       |                  if prev.nonEmpty then _fireLeaderEvent("LeaderLost", prev)
       |          catch case _: Throwable => ()
       |          try Thread.sleep(_COORD_RENEW_INTERVAL_MS)
       |          catch case _: InterruptedException => done = true
       |      catch case _: Throwable => ()
       |    }
       |    if !_coordTickThread.compareAndSet(null, t) then t.interrupt()
       |  // v1.23 — Raft state (cluster-raft.md §4.1).
       |  @volatile var _raftCurrentTerm: Long   = 0L
       |  @volatile var _raftVotedFor:    String = ""        // "" = None
       |  @volatile var _raftState:       String = "follower" // follower | candidate | leader
       |  @volatile var _raftLeaderId:    String = ""
       |  @volatile var _raftElectionDue: Long   = 0L
       |  @volatile var _raftVotes:       Int    = 0
       |  val _RAFT_ELECTION_LO  = 150L
       |  val _RAFT_ELECTION_HI  = 300L
       |  val _RAFT_HEARTBEAT_MS = 50L
       |  val _raftTickThread = new java.util.concurrent.atomic.AtomicReference[Thread](null)
       |  val _raftRand       = new scala.util.Random()
       |  def _raftRandTimeout: Long =
       |    _RAFT_ELECTION_LO + _raftRand.nextInt((_RAFT_ELECTION_HI - _RAFT_ELECTION_LO).toInt + 1)
       |  def _raftBroadcastHeartbeat(): Unit =
       |    val payload = "{\"t\":\"raft_append\",\"from\":" + _jstr(_localNodeId) +
       |                  ",\"term\":" + _raftCurrentTerm.toString + "}"
       |    _peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
       |  def _raftAdoptLeader(newLeader: String): Unit =
       |    val prev = _currentLeader.getAndSet(newLeader)
       |    if prev != newLeader then
       |      _fireLeaderEvent("LeaderElected", newLeader)
       |      _recordLeaderHist(newLeader)
       |  def _startRaftElection(): Unit =
       |    _raftState       = "candidate"
       |    _raftCurrentTerm = _raftCurrentTerm + 1
       |    _raftVotedFor    = _localNodeId
       |    _raftVotes       = 1
       |    _raftElectionDue = System.currentTimeMillis() + _raftRandTimeout
       |    _raftPersist()
       |    val peerIds = scala.collection.mutable.ListBuffer.empty[String]
       |    _peerChannels.keySet().forEach(p => peerIds += p)
       |    val total = peerIds.size + 1
       |    // Single-node majority is trivially us — claim immediately.
       |    if _raftVotes > total / 2 then
       |      _raftState    = "leader"
       |      _raftLeaderId = _localNodeId
       |      _raftAdoptLeader(_localNodeId)
       |      _raftBroadcastHeartbeat()
       |    else
       |      val payload = "{\"t\":\"raft_vote_req\",\"from\":" + _jstr(_localNodeId) +
       |                    ",\"term\":" + _raftCurrentTerm.toString + ",\"lastLogTerm\":0}"
       |      peerIds.foreach { nid =>
       |        try Option(_peerChannels.get(nid)).foreach(_.apply(payload))
       |        catch case _: Throwable => ()
       |      }
       |  def _ensureRaftTickThread(): Unit =
       |    if _raftTickThread.get() != null then return
       |    val t = Thread.ofVirtual().start { () =>
       |      try
       |        while _leaderProtocol.get() == "raft" do
       |          Thread.sleep(_RAFT_HEARTBEAT_MS)
       |          val now = System.currentTimeMillis()
       |          _raftState match
       |            case "leader" =>
       |              _raftBroadcastHeartbeat()
       |            case "follower" | "candidate" =>
       |              if now >= _raftElectionDue then _startRaftElection()
       |            case _ => ()
       |      catch case _: InterruptedException => ()
       |    }
       |    if !_raftTickThread.compareAndSet(null, t) then t.interrupt()
       |  // v1.23 — Raft persistence (cluster-raft.md §4.1).  One JSON file per
       |  // node, written on every (term, votedFor) mutation so a crashed-and-
       |  // restarted node doesn't double-vote in the same term.  Best-effort:
       |  // IO errors are swallowed (the alternative is to refuse to start,
       |  // which is worse for trusted-deployment use).
       |  def _raftStatePath: java.nio.file.Path =
       |    val key = if _localNodeId.isEmpty then "default" else _localNodeId.replaceAll("[^A-Za-z0-9._-]", "_")
       |    java.nio.file.Paths.get(s".ssc-raft-state-$key.json")
       |  def _raftPersist(): Unit =
       |    try
       |      val voted = _raftVotedFor.replace("\\", "\\\\").replace("\"", "\\\"")
       |      val json  = "{\"currentTerm\":" + _raftCurrentTerm.toString + ",\"votedFor\":\"" + voted + "\"}"
       |      java.nio.file.Files.writeString(_raftStatePath, json,
       |        java.nio.charset.StandardCharsets.UTF_8,
       |        java.nio.file.StandardOpenOption.CREATE,
       |        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)
       |    catch case _: Throwable => ()
       |  def _raftLoad(): Unit =
       |    try
       |      val p = _raftStatePath
       |      if java.nio.file.Files.exists(p) then
       |        val s = java.nio.file.Files.readString(p)
       |        val termIdx = s.indexOf("\"currentTerm\"")
       |        if termIdx >= 0 then
       |          val ci = s.indexOf(':', termIdx); var i = ci + 1
       |          while i < s.length && s(i) == ' ' do i += 1
       |          var j = i; while j < s.length && (s(j).isDigit || s(j) == '-') do j += 1
       |          if j > i then s.substring(i, j).toLongOption.foreach(t => _raftCurrentTerm = t)
       |        val vk = "\"votedFor\""
       |        val ki = s.indexOf(vk)
       |        if ki >= 0 then
       |          val qi = s.indexOf('"', ki + vk.length + 1)
       |          val qe = if qi > 0 then s.indexOf('"', qi + 1) else -1
       |          if qe > qi then _raftVotedFor = s.substring(qi + 1, qe)
       |    catch case _: Throwable => ()
       |  // v1.23 — drain-aware step-down (cluster-raft.md §7).  Called when
       |  // `setDraining(true)` flips while this node holds leadership.
       |  // Releases the lease (coord), reverts to follower (Raft), or just
       |  // clears the cached leader (Bully); always fires LeaderLost(self).
       |  def _stepDownIfLeader(): Unit =
       |    _leaderProtocol.get() match
       |      case "raft" =>
       |        if _raftState == "leader" then
       |          _raftState    = "follower"
       |          _raftLeaderId = ""
       |          val prev = _currentLeader.getAndSet("")
       |          if prev.nonEmpty then _fireLeaderEvent("LeaderLost", prev)
       |      case "coord" =>
       |        if _coordIsLeader then
       |          _coordIsLeader = false
       |          val rel = _coordReleaseFn
       |          if rel != null then
       |            try rel.asInstanceOf[String => Unit](_localNodeId)
       |            catch case _: Throwable => ()
       |          val prev = _currentLeader.getAndSet("")
       |          if prev.nonEmpty then _fireLeaderEvent("LeaderLost", prev)
       |      case _ =>
       |        if _currentLeader.compareAndSet(_localNodeId, "") then
       |          _fireLeaderEvent("LeaderLost", _localNodeId)
       |  // v1.23 — auto-reconnect: exponential-backoff retry per peer URL after a
       |  // disconnect.  Both fields 0 ⇒ disabled (default).  `setReconnectPolicy`
       |  // sets them at runtime.  `_reconnectGiveUpMs` caps the total
       |  // wall-clock retry budget per URL (0 = retry forever).
       |  @volatile var _reconnectInitialMs: Long = 0L
       |  @volatile var _reconnectMaxMs:     Long = 0L
       |  @volatile var _reconnectGiveUpMs:  Long = 0L
       |  // v1.23 — per-link heartbeat tuning.  Defaults 30s ping / 40s dead
       |  // match the pre-v1.23 hardcoded values; `setHeartbeatTimeout` tunes
       |  // them for low-latency / test clusters.
       |  @volatile var _peerHeartbeatIntervalMs:  Long = 30000L
       |  @volatile var _peerHeartbeatDeadAfterMs: Long = 40000L
       |  // v1.23 — quorum-aware Bully threshold.  0 = no quorum check;
       |  // set to N/2+1 of expected cluster size for split-brain guard.
       |  @volatile var _quorumSize: Long = 0L
       |  def _hasQuorum: Boolean = _quorumSize <= 0L || (_peerChannels.size + 1L) >= _quorumSize
       |  // v1.23 — cluster configuration distribution.  LWW per key by timestamp;
       |  // ties broken by lex-greatest nodeId so all nodes converge.
       |  val _clusterConfig    = new java.util.concurrent.ConcurrentHashMap[String, (String, Long, String)]()
       |  val _configEventSubs  = new java.util.concurrent.CopyOnWriteArrayList[java.lang.Long]()
       |  val _configEventQueue = new java.util.concurrent.ConcurrentLinkedQueue[(String, String)]()
       |  def _fireConfigEvent(key: String, value: String): Unit =
       |    val ts = System.currentTimeMillis()
       |    _recordEventLog("{\"ts\":" + ts.toString + ",\"type\":\"ConfigChanged\",\"key\":" +
       |                    _jstr(key) + ",\"value\":" + _jstr(value) + "}")
       |    if !_configEventSubs.isEmpty then _configEventQueue.offer((key, value))
       |  // Returns true if (ts, origin) wins over the stored (ts, origin) for key.
       |  def _applyConfigUpdate(key: String, value: String, ts: Long, origin: String): Boolean =
       |    val prev = _clusterConfig.get(key)
       |    val accept =
       |      prev == null || ts > prev._2 ||
       |      (ts == prev._2 && origin > prev._3)
       |    if accept then
       |      _clusterConfig.put(key, (value, ts, origin))
       |      _fireConfigEvent(key, value)
       |    accept
       |  // Snapshot every locally-known config entry to a single peer.  Called
       |  // on every successful handshake so late-joining nodes pick up entries
       |  // set before they joined.  LWW on the receiver protects us from
       |  // downgrading any value the new peer might already have.
       |  def _sendConfigSnapshot(targetSend: String => Unit): Unit =
       |    _clusterConfig.forEach { (key, tuple) =>
       |      val payload = "{\"t\":\"config_set\",\"key\":" + _jstr(key) +
       |                    ",\"value\":" + _jstr(tuple._1) +
       |                    ",\"ts\":" + tuple._2.toString +
       |                    ",\"origin\":" + _jstr(tuple._3) + "}"
       |      try targetSend(payload) catch case _: Throwable => ()
       |    }
       |  // v1.23 — drain / rolling-restart state
       |  val _isDrainingSelf  = new java.util.concurrent.atomic.AtomicBoolean(false)
       |  val _drainingPeers   = new java.util.concurrent.ConcurrentHashMap[String, java.lang.Boolean]()
       |  val _drainEventSubs  = new java.util.concurrent.CopyOnWriteArrayList[java.lang.Long]()
       |  val _drainEventQueue = new java.util.concurrent.ConcurrentLinkedQueue[(String, Boolean)]()
       |  def _fireDrainEvent(nodeId: String, draining: Boolean): Unit =
       |    val ts = System.currentTimeMillis()
       |    _recordEventLog("{\"ts\":" + ts.toString + ",\"type\":\"DrainStateChanged\",\"nodeId\":" +
       |                    _jstr(nodeId) + ",\"draining\":" + draining.toString + "}")
       |    if !_drainEventSubs.isEmpty then _drainEventQueue.offer((nodeId, draining))
       |  // Tell a freshly-handshaken peer our current drain state.  No-op when we
       |  // are not draining (peers default-assume `false`).
       |  def _sendDrainState(targetSend: String => Unit): Unit =
       |    if _isDrainingSelf.get() then
       |      val payload = "{\"t\":\"drain\",\"from\":" + _jstr(_localNodeId) + ",\"draining\":true}"
       |      try targetSend(payload) catch case _: Throwable => ()
       |  // v1.23 — cluster metrics: per-node gauges.
       |  //   _clusterMetrics(name)(nodeId) = latest value
       |  val _clusterMetrics    = new java.util.concurrent.ConcurrentHashMap[String,
       |    java.util.concurrent.ConcurrentHashMap[String, java.lang.Double]]()
       |  val _metricEventSubs   = new java.util.concurrent.CopyOnWriteArrayList[java.lang.Long]()
       |  val _metricEventQueue  = new java.util.concurrent.ConcurrentLinkedQueue[(String, String, Double)]()
       |  def _fireMetricEvent(name: String, nodeId: String, value: Double): Unit =
       |    val ts = System.currentTimeMillis()
       |    _recordEventLog("{\"ts\":" + ts.toString + ",\"type\":\"MetricChanged\",\"name\":" +
       |                    _jstr(name) + ",\"nodeId\":" + _jstr(nodeId) +
       |                    ",\"value\":" + value.toString + "}")
       |    if !_metricEventSubs.isEmpty then _metricEventQueue.offer((name, nodeId, value))
       |  def _applyMetricUpdate(name: String, nodeId: String, value: Double): Unit =
       |    val inner = _clusterMetrics.computeIfAbsent(name, _ =>
       |      new java.util.concurrent.ConcurrentHashMap[String, java.lang.Double]())
       |    val boxed = java.lang.Double.valueOf(value)
       |    val prev  = inner.put(nodeId, boxed)
       |    if prev == null || prev.doubleValue() != value then
       |      _fireMetricEvent(name, nodeId, value)
       |  // Snapshot every local metric to a single peer on handshake so late
       |  // joiners catch up without waiting for the next set.
       |  def _sendMetricSnapshot(targetSend: String => Unit): Unit =
       |    _clusterMetrics.forEach { (name, inner) =>
       |      val localVal = inner.get(_localNodeId)
       |      if localVal != null then
       |        val payload = "{\"t\":\"metric\",\"from\":" + _jstr(_localNodeId) +
       |                      ",\"name\":" + _jstr(name) +
       |                      ",\"value\":" + localVal.doubleValue().toString + "}"
       |        try targetSend(payload) catch case _: Throwable => ()
       |    }
       |""".stripMargin +
    """|  // v1.23 — Bearer-token gate shared by every /_ssc-cluster/* HTTP
       |  // route.  Returns Some(401-response) when the token is set and the
       |  // request's Authorization header doesn't carry `Bearer <token>`;
       |  // None when the token is empty (endpoints open) or matches.  Mirrors
       |  // `Interpreter.clusterAuthReject`.
       |  def _clusterAuthReject(req: Request): Option[Response] =
       |    val tok = _clusterAuthToken
       |    if tok.isEmpty then None
       |    else
       |      val hdr = req.headers.getOrElse("authorization", "")
       |      if hdr == ("Bearer " + tok) then None
       |      else Some(Response(
       |        401,
       |        Map("Content-Type" -> "application/json"),
       |        "{\"error\":\"unauthorized\",\"hint\":\"set Authorization: Bearer <token>\"}"))
       |  // v1.23 — `GET /_ssc-cluster/status` JSON snapshot of cluster state.
       |  // Idempotent: subsequent `startNode` calls are no-ops for the route
       |  // table.  Mirrors `Interpreter.registerClusterStatusRoute`.
       |  def _registerClusterStatusRoute(): Unit =
       |    val path = "/_ssc-cluster/status"
       |    if _routes.exists(r => r.method == "GET" && r.path == path) then return
       |    route("GET", path) { req =>
       |      _clusterAuthReject(req) match
       |        case Some(r) => r
       |        case None =>
       |          val sb = new StringBuilder("{")
       |          def kv(k: String, jsonVal: String, first: Boolean = false): Unit =
       |            if !first then sb.append(',')
       |            sb.append('"').append(k).append("\":").append(jsonVal)
       |          def jsonStrArr(xs: Iterable[String]): String =
       |            xs.map(_jstr).mkString("[", ",", "]")
       |          val members = scala.collection.mutable.ListBuffer.empty[String]
       |          _peerChannels.keySet().forEach(p => members += p)
       |          val drainPeers = scala.collection.mutable.ListBuffer.empty[String]
       |          _drainingPeers.forEach { (nid, dr) =>
       |            if dr != null && dr.booleanValue() then drainPeers += nid
       |          }
       |          val leaderNow =
       |            _leaderProtocol.get() match
       |              case "raft" => _raftLeaderId
       |              case _      => _currentLeader.get()
       |          kv("nodeId",        _jstr(_localNodeId), first = true)
       |          kv("leader",        _jstr(leaderNow))
       |          kv("protocol",      _jstr(_leaderProtocol.get()))
       |          kv("members",       jsonStrArr(members.toList))
       |          kv("drainingSelf",  if _isDrainingSelf.get() then "true" else "false")
       |          kv("drainingPeers", jsonStrArr(drainPeers.toList))
       |          kv("raftTerm",      _raftCurrentTerm.toString)
       |          kv("raftState",     _jstr(_raftState))
       |          sb.append('}')
       |          Response(200, Map("Content-Type" -> "application/json"), sb.toString)
       |    }
       |  // v1.23 — `POST /_ssc-cluster/drain` toggles local drain state.
       |  // Body is JSON `{"enabled":true|false}` (empty body = enable).
       |  // Mirrors the in-process `setDraining` effect: flips
       |  // `_isDrainingSelf`, broadcasts DrainStateChanged to peers, steps
       |  // down if we were leader.  Used by `ssc cluster drain <url> [--off]`.
       |  def _registerClusterDrainRoute(): Unit =
       |    val path = "/_ssc-cluster/drain"
       |    if _routes.exists(r => r.method == "POST" && r.path == path) then return
       |    route("POST", path) { req =>
       |      _clusterAuthReject(req) match
       |        case Some(r) => r
       |        case None =>
       |          val body = req.body
       |          val enabled: Boolean =
       |            if body.trim.isEmpty then true
       |            else
       |              val needle = "\"enabled\":"
       |              val i = body.indexOf(needle)
       |              if i < 0 then true
       |              else
       |                val rest = body.substring(i + needle.length).trim
       |                !rest.startsWith("false")
       |          val prev = _isDrainingSelf.getAndSet(enabled)
       |          if prev != enabled then
       |            val payload = "{\"t\":\"drain\",\"from\":" + _jstr(_localNodeId) +
       |                          ",\"draining\":" + enabled.toString + "}"
       |            _peerChannels.forEach { (_, send) =>
       |              try send(payload) catch case _: Throwable => ()
       |            }
       |            _fireDrainEvent(_localNodeId, enabled)
       |            if enabled then _stepDownIfLeader()
       |          Response(
       |            200,
       |            Map("Content-Type" -> "application/json"),
       |            "{\"drainingSelf\":" + (if enabled then "true" else "false") + "}")
       |    }
       |  // v1.23 — `GET /_ssc-cluster/events[?since=<ts>]` returns the bounded
       |  // ring buffer of recent cluster events as a JSON array.  Optional
       |  // `since` query filters to entries strictly newer than the given
       |  // epoch-ms.  Idempotent registration.
       |  def _registerClusterEventsRoute(): Unit =
       |    val path = "/_ssc-cluster/events"
       |    if _routes.exists(r => r.method == "GET" && r.path == path) then return
       |    route("GET", path) { req =>
       |      _clusterAuthReject(req) match
       |        case Some(r) => r
       |        case None =>
       |          val sinceMs: Long =
       |            req.query.get("since").flatMap(_.toLongOption).getOrElse(0L)
       |          val sb = new StringBuilder("[")
       |          var first = true
       |          val it = _clusterEventLog.iterator()
       |          while it.hasNext do
       |            val line = it.next()
       |            val tsMatch =
       |              if sinceMs <= 0L then true
       |              else
       |                val tsPrefix = "{\"ts\":"
       |                if line.startsWith(tsPrefix) then
       |                  val end = line.indexOf(',', tsPrefix.length)
       |                  if end > 0 then
       |                    line.substring(tsPrefix.length, end).toLongOption
       |                      .exists(_ > sinceMs)
       |                  else false
       |                else false
       |            if tsMatch then
       |              if !first then sb.append(',')
       |              sb.append(line)
       |              first = false
       |          sb.append(']')
       |          Response(200, Map("Content-Type" -> "application/json"), sb.toString)
       |    }
       |  // v1.23 — `POST /_ssc-cluster/step-down`.  If this node is the current
       |  // leader, step down (clear `_currentLeader`, broadcast `LeaderLost`,
       |  // surrender any external coordinator lease).  If it's not the leader,
       |  // returns 409 Conflict so the operator notices.  Apps with
       |  // `setAutoReelect(true)` re-elect automatically — that's the rolling-
       |  // restart pattern.
       |  def _registerClusterStepDownRoute(): Unit =
       |    val path = "/_ssc-cluster/step-down"
       |    if _routes.exists(r => r.method == "POST" && r.path == path) then return
       |    route("POST", path) { req =>
       |      _clusterAuthReject(req) match
       |        case Some(r) => r
       |        case None =>
       |          val wasLeader =
       |            _leaderProtocol.get() match
       |              case "raft"  => _raftState == "leader"
       |              case "coord" => _coordIsLeader
       |              case _       => _currentLeader.get() == _localNodeId
       |          if !wasLeader then
       |            Response(
       |              409,
       |              Map("Content-Type" -> "application/json"),
       |              "{\"error\":\"not_leader\",\"leader\":" + _jstr(_currentLeader.get()) + "}")
       |          else
       |            _stepDownIfLeader()
       |            Response(
       |              200,
       |              Map("Content-Type" -> "application/json"),
       |              "{\"steppedDown\":true,\"nodeId\":" + _jstr(_localNodeId) + "}")
       |    }
       |  // v1.23 — `GET /_ssc-cluster/metrics-prom` returns `_clusterMetrics`
       |  // gauges in Prometheus text exposition format.  One
       |  // `<sanitized-name>{nodeId="<id>"} <value>` line per (metric, peer)
       |  // pair, plus `# TYPE … gauge` declarations.  Same Bearer-token gate.
       |  def _registerClusterMetricsPromRoute(): Unit =
       |    val path = "/_ssc-cluster/metrics-prom"
       |    if _routes.exists(r => r.method == "GET" && r.path == path) then return
       |    route("GET", path) { req =>
       |      _clusterAuthReject(req) match
       |        case Some(r) => r
       |        case None =>
       |          // Prometheus metric names must match `[a-zA-Z_:][a-zA-Z0-9_:]*`.
       |          def sanitize(s: String): String =
       |            val sb = new StringBuilder(s.length)
       |            var i = 0
       |            while i < s.length do
       |              val c = s.charAt(i)
       |              val ok =
       |                (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
       |                (c >= '0' && c <= '9') || c == '_' || c == ':'
       |              sb.append(if ok then c else '_')
       |              i += 1
       |            val out = sb.toString
       |            if out.nonEmpty && out.charAt(0) >= '0' && out.charAt(0) <= '9'
       |            then "_" + out else out
       |          def escLabel(s: String): String =
       |            s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
       |          val sb = new StringBuilder()
       |          _clusterMetrics.forEach { (name, inner) =>
       |            val pName = sanitize(name)
       |            sb.append("# TYPE ").append(pName).append(" gauge\n")
       |            inner.forEach { (nodeId, value) =>
       |              sb.append(pName)
       |                .append("{nodeId=\"").append(escLabel(nodeId)).append("\"} ")
       |                .append(value.doubleValue())
       |                .append('\n')
       |            }
       |          }
       |          Response(
       |            200,
       |            Map("Content-Type" -> "text/plain; version=0.0.4; charset=utf-8"),
       |            sb.toString)
       |    }
       |  // v1.63.5 — `GET /_ssc-cluster/handlers` returns the remote handler
       |  // registry as a JSON array so `ssc cluster handlers` can list
       |  // exported operations from a running node.
       |  def _registerClusterHandlersRoute(): Unit =
       |    val path = "/_ssc-cluster/handlers"
       |    if _routes.exists(r => r.method == "GET" && r.path == path) then return
       |    route("GET", path) { req =>
       |      _clusterAuthReject(req) match
       |        case Some(r) => r
       |        case None =>
       |          val sb = new StringBuilder("[")
       |          var first = true
       |          _remoteHandlers.forEach { (name, info) =>
       |            if !first then sb.append(',')
       |            first = false
       |            sb.append('{')
       |            sb.append("\"name\":").append(_jstr(name))
       |            sb.append(",\"function\":").append(_jstr(info.function))
       |            info.path.foreach { p => sb.append(",\"path\":").append(_jstr(p)) }
       |            info.requestType.foreach { t => sb.append(",\"requestType\":").append(_jstr(t)) }
       |            info.responseType.foreach { t => sb.append(",\"responseType\":").append(_jstr(t)) }
       |            val transports = info.transports.map(t => "\"" + t + "\"").mkString(",")
       |            sb.append(s",\"transports\":[$transports]")
       |            sb.append('}')
       |          }
       |          sb.append(']')
       |          Response(200, Map("Content-Type" -> "application/json"), sb.toString)
       |    }
       |""".stripMargin +
    """|  def _broadcastCoordinator(): Unit =
       |    val payload = "{\"t\":\"coordinator\",\"from\":" + _jstr(_localNodeId) + "}"
       |    _peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
       |  def _startElection(): Unit =
       |    if _localNodeId.isEmpty then
       |      val prev = _currentLeader.getAndSet(_localNodeId)
       |      if prev != _localNodeId then { _fireLeaderEvent("LeaderElected", _localNodeId); _recordLeaderHist(_localNodeId) }
       |    else
       |      val higher = scala.collection.mutable.ListBuffer.empty[String]
       |      _peerChannels.keySet().forEach(nid => if nid > _localNodeId then higher += nid)
       |      if higher.isEmpty then
       |        // v1.23 — quorum gate: refuse self-claim when below quorum
       |        // (split-brain guard).  No-op when `_quorumSize = 0`.
       |        if !_hasQuorum then ()
       |        else
       |          val prev = _currentLeader.getAndSet(_localNodeId)
       |          _broadcastCoordinator()
       |          if prev != _localNodeId then
       |            _fireLeaderEvent("LeaderElected", _localNodeId)
       |            _recordLeaderHist(_localNodeId)
       |      else
       |        _electionInProgress = true
       |        _electionStartedAt  = System.currentTimeMillis()
       |        _gotAliveResponse   = false
       |        val payload = "{\"t\":\"election\",\"from\":" + _jstr(_localNodeId) + "}"
       |        higher.foreach { nid =>
       |          try Option(_peerChannels.get(nid)).foreach(_.apply(payload))
       |          catch case _: Throwable => ()
       |        }
       |  def _recordPongInterval(nid: String): Unit =
       |    val now  = System.currentTimeMillis()
       |    val last = _peerLastPong.getOrDefault(nid, 0L)
       |    if last > 0L then
       |      val delta = java.lang.Long.valueOf(now - last)
       |      val dq    = _peerPongHist.computeIfAbsent(nid, _ =>
       |        new java.util.concurrent.ConcurrentLinkedDeque[java.lang.Long]())
       |      dq.offer(delta)
       |      while dq.size() > _PHI_HIST_MAX do dq.pollFirst()
       |  def _computePhi(nid: String): Double =
       |    val hist = _peerPongHist.get(nid)
       |    if hist == null || hist.isEmpty then return Double.PositiveInfinity
       |    val n = hist.size
       |    var s = 0.0
       |    val it = hist.iterator
       |    while it.hasNext do s += it.next().longValue().toDouble
       |    val mean = s / n
       |    var sq = 0.0
       |    val it2 = hist.iterator
       |    while it2.hasNext do
       |      val d = it2.next().longValue().toDouble - mean
       |      sq += d * d
       |    val variance = if n > 1 then sq / (n - 1) else 1.0
       |    val stddev   = math.sqrt(variance).max(50.0)
       |    val now      = System.currentTimeMillis()
       |    val last     = _peerLastPong.getOrDefault(nid, now)
       |    val elapsed  = (now - last).toDouble
       |    if elapsed <= mean then 0.0
       |    else
       |      val z    = (elapsed - mean) / stddev
       |      val tail = math.exp(-z * z / 2.0) / (z * math.sqrt(2.0 * math.Pi))
       |      if tail <= 0.0 then Double.PositiveInfinity
       |      else -math.log10(tail.min(1.0))
       |
       |  val ready  = scala.collection.mutable.ArrayDeque.empty[Long]
       |  var nextId: Long = 0L
       |  var rootResult: Any = ()
       |
       |  def spawnActor(thunk: () => Any, cap: Int = 0, overflow: String = ""): Long =
       |    val id = nextId
       |    nextId += 1
       |    val st = new _ActorState
       |    st.pending = thunk()
       |    st.cap = cap
       |    st.overflow = overflow
       |    actors.put(id, st)
       |    ready.append(id)
       |    id
       |
       |  val rootId = spawnActor(bodyThunk)
       |
       |  def _fireNodeDown(nodeId: String): Unit =
       |    val noconn = "noconnection"
       |    val deadBase = Map("nodeId" -> nodeId)
       |    Option(_remoteMonitors.remove(nodeId)).foreach { list =>
       |      list.forEach { (actorId, monRef, rPidLocalId) =>
       |        actors.get(actorId).foreach { st =>
       |          st.mailbox.offer(Down(monRef, _Pid(nodeId, rPidLocalId), noconn))
       |          tryWakeBlocked(actorId)
       |        }
       |      }
       |    }
       |    Option(_remoteLinks.remove(nodeId)).foreach { list =>
       |      list.forEach { (actorId, rPidLocalId) =>
       |        if trapExitM.getOrElse(actorId, false) then
       |          actors.get(actorId).foreach { st =>
       |            st.mailbox.offer(Exit(_Pid(nodeId, rPidLocalId), noconn))
       |            tryWakeBlocked(actorId)
       |          }
       |        else killActor(actorId, noconn)
       |      }
       |    }
       |
       |  def _connectPeer(url: String, token: String): Unit =
       |    Thread.ofVirtual().start { () =>
       |      try
       |        import java.net.URI
       |        import java.net.http.{HttpClient => _JHC2, WebSocket => _JWs2}
       |        import java.util.concurrent.{LinkedBlockingQueue => _LBQ, CompletableFuture => _CF}
       |        val recvQ  = new _LBQ[String | Null]()
       |        val textB  = new StringBuilder()
       |        @volatile var _ws2: _JWs2 | Null = null
       |        val listener = new _JWs2.Listener:
       |          override def onText(ws: _JWs2, data: CharSequence, last: Boolean): _CF[?] =
       |            textB.append(data)
       |            if last then { val m = textB.toString(); textB.setLength(0); recvQ.offer(m) }
       |            ws.request(1); _CF.completedFuture(null)
       |          override def onClose(ws: _JWs2, c: Int, r: String): _CF[?] =
       |            recvQ.offer(null); _CF.completedFuture(null)
       |          override def onError(ws: _JWs2 | Null, e: Throwable): Unit =
       |            System.err.println("ssc-peer error [" + url + "]: " + e.getMessage); recvQ.offer(null)
       |        val hdrs = if token.nonEmpty then Map("Authorization" -> ("Bearer " + token)) else Map.empty[String,String]
       |        val builder = _JHC2.newHttpClient().newWebSocketBuilder()
       |        hdrs.foreach { case (k, v) => builder.header(k, v) }
       |        builder.subprotocols("ssc-actors-v1")
       |        val ws = builder.buildAsync(URI.create(url), listener).join()
       |        _ws2 = ws
       |        def sendFn(t: String): Unit = if _ws2 != null then _ws2.sendText(t, true)
       |        def recvFn(): String | Null  = recvQ.take()
       |        sendFn("{\"nodeId\":" + _jstr(_localNodeId) + "}")
       |        val first = recvFn()
       |        if first != null then
       |          val pnId = _parseNodeId(first)
       |          if pnId.nonEmpty then
       |            _peerUrls.put(pnId, url)
       |            _peerChannels.put(pnId, sendFn)
       |            _peerLastPong.put(pnId, System.currentTimeMillis())
       |            _fireClusterEvent("NodeJoined", pnId)
       |            if _joinMode then try sendFn("{\"t\":\"peers_req\",\"from\":" + _jstr(_localNodeId) + "}") catch case _: Throwable => ()
       |            // v1.23 — snapshot the cluster config to the new peer so it
       |            // sees entries set before it joined (LWW protects existing values).
       |            _sendConfigSnapshot(sendFn)
       |            _sendDrainState(sendFn)
       |            _sendMetricSnapshot(sendFn)
       |            val hbThread = Thread.ofVirtual().start { () =>
       |              try
       |                while _peerChannels.containsKey(pnId) do
       |                  Thread.sleep(_peerHeartbeatIntervalMs)
       |                  if _peerChannels.containsKey(pnId) then
       |                    val age = System.currentTimeMillis() - _peerLastPong.getOrDefault(pnId, 0L)
       |                    if age > _peerHeartbeatDeadAfterMs then
       |                      _peerChannels.remove(pnId)
       |                      try if _ws2 != null then _ws2.abort() catch case _: Throwable => ()
       |                    else try _peerChannels.get(pnId)("{\"t\":\"ping\"}") catch case _: Throwable => ()
       |              catch case _: InterruptedException => ()
       |            }
       |            var running = true
       |            while running do
       |              val msg = recvFn()
       |              if msg == null then running = false
       |              else _dispatchPeerEnv(pnId, msg)
       |            hbThread.interrupt()
       |            _peerChannels.remove(pnId)
       |            _peerLastPong.remove(pnId)
       |            _peerUrls.remove(pnId)
       |            _peerPongHist.remove(pnId)
       |            _peerPhiViews.remove(pnId)
       |            _drainingPeers.remove(pnId)
       |            _clusterMetrics.forEach { (_, inner) => inner.remove(pnId) }
       |            _nodeDownQueue.offer(pnId)
       |            _fireClusterEvent("NodeLeft", pnId, "disconnect")
       |            if _currentLeader.compareAndSet(pnId, "") then
       |              _fireLeaderEvent("LeaderLost", pnId)
       |              if _autoReelect then _startElection()
       |            // v1.23 — auto-reconnect: schedule exponential-backoff retries
       |            // for this URL until the peer reappears.
       |            if _reconnectInitialMs > 0L then _scheduleReconnect(url, token)
       |      catch case e: Throwable =>
       |        System.err.println("connectNode error [" + url + "]: " + e.getMessage)
       |        // v1.23 — schedule reconnect from the dial-failure path too.
       |        // Without this, non-seed nodes racing each other only ever
       |        // reach the seed and the cluster stays fragmented.
       |        if _reconnectInitialMs > 0L && !_peerUrls.containsValue(url) then
       |          _scheduleReconnect(url, token)
       |    }
       |
       |  // v1.23 — URL-keyed dedupe so concurrent peer-loss + dial-failure
       |  // events for the same URL don't each spin up an independent
       |  // exponential-backoff loop (FD exhaustion under sustained churn).
       |  // `lazy` is required: `killActor` is referenced earlier in the
       |  // emitted preamble (`_connectPeer`'s catch) than where it's
       |  // defined, and a regular val here would block the forward
       |  // reference per Scala's init-order rule.
       |  lazy val _reconnectActive =
       |    java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
       |
       |  def _scheduleReconnect(rurl: String, rtok: String): Unit =
       |    if !_reconnectActive.add(rurl) then return
       |    Thread.ofVirtual().start { () =>
       |      val startedAt = System.currentTimeMillis()
       |      var delay = _reconnectInitialMs.max(1L)
       |      var done  = false
       |      try
       |        while !done && !_peerUrls.containsValue(rurl) do
       |          try Thread.sleep(delay) catch case _: InterruptedException => done = true
       |          if !done && _reconnectInitialMs <= 0L then done = true
       |          if !done && _peerUrls.containsValue(rurl) then done = true
       |          // v1.23 — give-up budget: stop retrying after the
       |          // configured wall-clock elapsed.  0 ⇒ retry forever.
       |          if !done && _reconnectGiveUpMs > 0L &&
       |             (System.currentTimeMillis() - startedAt) >= _reconnectGiveUpMs then
       |            done = true
       |          if !done then
       |            try _connectPeer(rurl, rtok) catch case _: Throwable => ()
       |            if _peerUrls.containsValue(rurl) then done = true
       |            else
       |              val cap = if _reconnectMaxMs > 0L then _reconnectMaxMs else delay
       |              delay = math.min(delay * 2L, cap.max(delay))
       |      catch case _: Throwable => ()
       |      finally _reconnectActive.remove(rurl)
       |    }
       |
       |  def _parseNodeId(json: String): String =
       |    val key = "\"nodeId\""
       |    val ki = json.indexOf(key); if ki < 0 then return ""
       |    val vi = json.indexOf('"', ki + key.length + 1); if vi < 0 then return ""
       |    val ve = json.indexOf('"', vi + 1); if ve < 0 then return ""
       |    json.substring(vi + 1, ve)
       |
       |  def _dispatchPeerEnv(pnId: String, json: String): Unit =
       |    val ti = json.indexOf("\"t\""); if ti < 0 then return
       |    val vi = json.indexOf('"', ti + 4); if vi < 0 then return
       |    val ve = json.indexOf('"', vi + 1); if ve < 0 then return
       |    val t  = json.substring(vi + 1, ve)
       |    t match
       |      case "msg" =>
       |        val toId = _extractToLocalId(json)
       |        if toId >= 0 then
       |          val body = _extractBody(json)
       |          if body != null then
       |            val msg = _deserializeValue(body)
       |            _remoteInbox.offer((toId, msg))
       |      case "ping" => try Option(_peerChannels.get(pnId)).foreach(_.apply("{\"t\":\"pong\"}")) catch case _: Throwable => ()
       |      case "pong" =>
       |        _recordPongInterval(pnId)
       |        _peerLastPong.put(pnId, System.currentTimeMillis())
       |      case "peers_req" =>
       |        val sb = new StringBuilder("{\"t\":\"peers_resp\",\"peers\":[")
       |        var first = true
       |        if _localNodeUrl.nonEmpty then
       |          sb.append("{\"nodeId\":" + _jstr(_localNodeId) + ",\"url\":" + _jstr(_localNodeUrl) + "}")
       |          first = false
       |        _peerUrls.forEach { (nid, u) =>
       |          if u.nonEmpty then
       |            if !first then sb.append(',')
       |            sb.append("{\"nodeId\":" + _jstr(nid) + ",\"url\":" + _jstr(u) + "}")
       |            first = false
       |        }
       |        sb.append("]}")
       |        try Option(_peerChannels.get(pnId)).foreach(_.apply(sb.toString)) catch case _: Throwable => ()
       |      case "peers_resp" =>
       |        _extractPeersList(json).foreach { case (pnid, purl) =>
       |          if pnid.nonEmpty && purl.nonEmpty && pnid != _localNodeId && !_peerChannels.containsKey(pnid) then
       |            _connectPeer(purl, _joinToken)
       |        }
       |      case "global_reg" =>
       |        val grName    = _extractJsonStr(json, "\"name\"")
       |        val grNodeId  = _extractJsonStr(json, "\"nodeId\"")
       |        val grLocalId = _extractJsonStr(json, "\"localId\"").toLongOption.getOrElse(0L)
       |        if grName.nonEmpty && grNodeId.nonEmpty then
       |          _globalRegistry.put(grName, _Pid(grNodeId, grLocalId))
       |      case "phi_vector" =>
       |        // v1.23 — peer's phi vector.  Parse out `from` and the `view`
       |        // pair list, replace our recorded view of that peer.
       |        val from = _extractJsonStr(json, "\"from\"")
       |        if from.nonEmpty then
       |          val pairs = _extractPhiView(json)
       |          val m = new java.util.concurrent.ConcurrentHashMap[String, java.lang.Double]()
       |          pairs.foreach { case (nid, p) => m.put(nid, java.lang.Double.valueOf(p)) }
       |          _peerPhiViews.put(from, m)
       |      case "election" =>
       |        // v1.23 — Bully: lower-id peer is calling an election.  Respond
       |        // with `alive` (we're bigger) and start our own election.
       |        val from = _extractJsonStr(json, "\"from\"")
       |        if from.nonEmpty && from < _localNodeId then
       |          val reply = "{\"t\":\"alive\",\"from\":" + _jstr(_localNodeId) + "}"
       |          try Option(_peerChannels.get(from)).foreach(_.apply(reply))
       |          catch case _: Throwable => ()
       |          if !_electionInProgress then _startElection()
       |      case "alive" =>
       |        _gotAliveResponse = true
       |      case "coordinator" =>
       |        val from = _extractJsonStr(json, "\"from\"")
       |        if from.nonEmpty then
       |          val prev = _currentLeader.getAndSet(from)
       |          _electionInProgress = false
       |          if prev != from then
       |            _fireLeaderEvent("LeaderElected", from)
       |            _recordLeaderHist(from)
       |      case "config_set" =>
       |        // v1.23 — cluster config distribution.  LWW by (ts, originNodeId).
       |        val key   = _extractJsonStr(json, "\"key\"")
       |        val value = _extractJsonStr(json, "\"value\"")
       |        val orig  = _extractJsonStr(json, "\"origin\"")
       |        val ts    = _extractJsonLong(json, "\"ts\"")
       |        if key.nonEmpty then _applyConfigUpdate(key, value, ts, orig)
       |      case "drain" =>
       |        // v1.23 — peer announced its drain state.
       |        val from = _extractJsonStr(json, "\"from\"")
       |        if from.nonEmpty then
       |          val isDraining = json.contains("\"draining\":true")
       |          val prev = _drainingPeers.put(from, java.lang.Boolean.valueOf(isDraining))
       |          if prev == null || prev.booleanValue() != isDraining then
       |            _fireDrainEvent(from, isDraining)
       |      case "metric" =>
       |        val from  = _extractJsonStr(json, "\"from\"")
       |        val name  = _extractJsonStr(json, "\"name\"")
       |        val value = _extractJsonDouble(json, "\"value\"")
       |        if from.nonEmpty && name.nonEmpty then _applyMetricUpdate(name, from, value)
       |      // v1.23 — Raft RPCs (cluster-raft.md §4.2).
       |      case "raft_vote_req" =>
       |        val from = _extractJsonStr(json, "\"from\"")
       |        val term = _extractJsonLong(json, "\"term\"")
       |        if from.nonEmpty then
       |          var mutated = false
       |          val granted =
       |            if term < _raftCurrentTerm then false
       |            else
       |              if term > _raftCurrentTerm then
       |                _raftCurrentTerm = term
       |                _raftVotedFor    = ""
       |                _raftState       = "follower"
       |                mutated = true
       |              if _raftVotedFor.isEmpty || _raftVotedFor == from then
       |                _raftVotedFor    = from
       |                _raftElectionDue = System.currentTimeMillis() + _raftRandTimeout
       |                mutated = true
       |                true
       |              else false
       |          if mutated then _raftPersist()
       |          val reply = "{\"t\":\"raft_vote_resp\",\"from\":" + _jstr(_localNodeId) +
       |                      ",\"term\":" + _raftCurrentTerm.toString +
       |                      ",\"granted\":" + granted.toString + "}"
       |          try Option(_peerChannels.get(from)).foreach(_.apply(reply))
       |          catch case _: Throwable => ()
       |      case "raft_vote_resp" =>
       |        val term = _extractJsonLong(json, "\"term\"")
       |        val granted = json.contains("\"granted\":true")
       |        if term == _raftCurrentTerm && _raftState == "candidate" && granted then
       |          _raftVotes = _raftVotes + 1
       |          val total = _peerChannels.size() + 1
       |          if _raftVotes > total / 2 then
       |            _raftState    = "leader"
       |            _raftLeaderId = _localNodeId
       |            _raftAdoptLeader(_localNodeId)
       |            _raftBroadcastHeartbeat()
       |      case "raft_append" =>
       |        val from = _extractJsonStr(json, "\"from\"")
       |        val term = _extractJsonLong(json, "\"term\"")
       |        if from.nonEmpty && term >= _raftCurrentTerm then
       |          val termChanged = term > _raftCurrentTerm
       |          _raftCurrentTerm = term
       |          _raftState       = "follower"
       |          val prevLeader   = _raftLeaderId
       |          _raftLeaderId    = from
       |          _raftElectionDue = System.currentTimeMillis() + _raftRandTimeout
       |          if termChanged then _raftPersist()
       |          if prevLeader != from then _raftAdoptLeader(from)
       |      case _      => ()
       |
       |  def _extractJsonStr(json: String, key: String, fromIdx: Int = 0): String =
       |    val ki = json.indexOf(key, fromIdx); if ki < 0 then return ""
       |    val vi = json.indexOf('"', ki + key.length + 1); if vi < 0 then return ""
       |    val ve = json.indexOf('"', vi + 1); if ve < 0 then return ""
       |    json.substring(vi + 1, ve)
       |
       |  def _extractJsonLong(json: String, key: String): Long =
       |    val ki = json.indexOf(key); if ki < 0 then return 0L
       |    val ci = json.indexOf(':', ki + key.length); if ci < 0 then return 0L
       |    var i = ci + 1; while i < json.length && json(i) == ' ' do i += 1
       |    var j = i; while j < json.length && (json(j).isDigit || json(j) == '-') do j += 1
       |    if j > i then json.substring(i, j).toLongOption.getOrElse(0L) else 0L
       |
       |  def _extractJsonDouble(json: String, key: String): Double =
       |    val ki = json.indexOf(key); if ki < 0 then return 0.0
       |    val ci = json.indexOf(':', ki + key.length); if ci < 0 then return 0.0
       |    var i = ci + 1; while i < json.length && json(i) == ' ' do i += 1
       |    var j = i
       |    while j < json.length && (json(j).isDigit || json(j) == '-' ||
       |                              json(j) == '.' || json(j) == 'e' || json(j) == 'E' ||
       |                              json(j) == '+') do j += 1
       |    if j > i then json.substring(i, j).toDoubleOption.getOrElse(0.0) else 0.0
       |
       |  def _extractPeersList(json: String): List[(String, String)] =
       |    val ak = "\"peers\""; val ai = json.indexOf(ak); if ai < 0 then return Nil
       |    val ab = json.indexOf('[', ai + ak.length); if ab < 0 then return Nil
       |    var ae = ab + 1; var depth = 1
       |    while ae < json.length && depth > 0 do
       |      if json(ae) == '[' then depth += 1
       |      else if json(ae) == ']' then depth -= 1
       |      ae += 1
       |    val arr = json.substring(ab + 1, ae - 1)
       |    val buf = scala.collection.mutable.ListBuffer.empty[(String, String)]
       |    var pos = 0
       |    while pos < arr.length do
       |      val ob = arr.indexOf('{', pos); if ob < 0 then pos = arr.length
       |      else
       |        var oe = ob + 1; var d2 = 1
       |        while oe < arr.length && d2 > 0 do
       |          if arr(oe) == '{' then d2 += 1
       |          else if arr(oe) == '}' then d2 -= 1
       |          oe += 1
       |        val obj = arr.substring(ob, oe)
       |        val nid = _extractJsonStr(obj, "\"nodeId\"")
       |        val url = _extractJsonStr(obj, "\"url\"")
       |        if nid.nonEmpty && url.nonEmpty then buf += ((nid, url))
       |        pos = oe
       |    buf.toList
       |
       |  // v1.23 — parse a `view` field of shape [["nodeA",0.5],["nodeB",2.3], ...]
       |  // from a phi_vector envelope.  Returns the inner pairs.
       |  def _extractPhiView(json: String): List[(String, Double)] =
       |    val key = "\"view\""; val ki = json.indexOf(key); if ki < 0 then return Nil
       |    val outer = json.indexOf('[', ki + key.length); if outer < 0 then return Nil
       |    var oe = outer + 1; var od = 1
       |    while oe < json.length && od > 0 do
       |      if json(oe) == '[' then od += 1
       |      else if json(oe) == ']' then od -= 1
       |      oe += 1
       |    val arr = json.substring(outer + 1, oe - 1)
       |    val buf = scala.collection.mutable.ListBuffer.empty[(String, Double)]
       |    var pos = 0
       |    while pos < arr.length do
       |      val ib = arr.indexOf('[', pos); if ib < 0 then pos = arr.length
       |      else
       |        var ie = ib + 1; var d2 = 1
       |        while ie < arr.length && d2 > 0 do
       |          if arr(ie) == '[' then d2 += 1
       |          else if arr(ie) == ']' then d2 -= 1
       |          ie += 1
       |        val inner = arr.substring(ib + 1, ie - 1).trim
       |        val nameEnd = inner.indexOf('"', 1)
       |        if inner.startsWith("\"") && nameEnd > 0 then
       |          val nm = inner.substring(1, nameEnd)
       |          val tail = inner.substring(nameEnd + 1).dropWhile(c => c == ',' || c == ' ').trim
       |          tail.toDoubleOption match
       |            case Some(d) => buf += ((nm, d))
       |            case None    => ()
       |        pos = ie
       |    buf.toList
       |
       |  def _extractToLocalId(json: String): Long =
       |    val toKey = "\"to\""; val ti = json.indexOf(toKey); if ti < 0 then return -1L
       |    val lk = "\"localId\""; val li = json.indexOf(lk, ti); if li < 0 then return -1L
       |    val ci = json.indexOf(':', li + lk.length); if ci < 0 then return -1L
       |    var i = ci + 1; while i < json.length && json(i) == ' ' do i += 1
       |    var j = i; while j < json.length && (json(j).isDigit || json(j) == '-') do j += 1
       |    if j > i then json.substring(i, j).toLongOption.getOrElse(-1L) else -1L
       |
       |  def _extractBody(json: String): String | Null =
       |    val bk = "\"body\""; val bi = json.indexOf(bk); if bi < 0 then return null
       |    val ci = json.indexOf(':', bi + bk.length); if ci < 0 then return null
       |    var i = ci + 1; while i < json.length && json(i) == ' ' do i += 1
       |    if i >= json.length then return null
       |    // Body is a nested JSON object — find balanced {}
       |    if json(i) != '{' then return null
       |    var depth = 0; var j = i
       |    while j < json.length do
       |      if json(j) == '{' then depth += 1
       |      else if json(j) == '}' then { depth -= 1; if depth == 0 then return json.substring(i, j + 1) }
       |      j += 1
       |    null
       |
       |  def _deserializeValue(json: String): Any =
       |    val ti = json.indexOf("\"$t\""); if ti < 0 then return json
       |    val vi = json.indexOf('"', ti + 5); if vi < 0 then return json
       |    val ve = json.indexOf('"', vi + 1); if ve < 0 then return json
       |    val tag = json.substring(vi + 1, ve)
       |    tag match
       |      case "i" =>
       |        val nv = json.indexOf("\"v\""); val ci = json.indexOf(':', nv + 3)
       |        var i = ci + 1; while i < json.length && json(i) == ' ' do i += 1
       |        var j = i; while j < json.length && (json(j).isDigit || json(j) == '-') do j += 1
       |        json.substring(i, j).toLongOption.getOrElse(0L)
       |      case "d" =>
       |        val nv = json.indexOf("\"v\""); val ci = json.indexOf(':', nv + 3)
       |        var i = ci + 1; while i < json.length && json(i) == ' ' do i += 1
       |        var j = i; while j < json.length && (json(j).isDigit || json(j) == '-' || json(j) == '.' || json(j) == 'e') do j += 1
       |        json.substring(i, j).toDoubleOption.getOrElse(0.0)
       |      case "s" =>
       |        val nv = json.indexOf("\"v\""); val qi = json.indexOf('"', json.indexOf(':', nv + 3) + 1)
       |        val qe = json.indexOf('"', qi + 1)
       |        if qi >= 0 && qe > qi then json.substring(qi + 1, qe) else ""
       |      case "b" =>
       |        json.contains("true")
       |      case "u" => ()
       |      case "pid" =>
       |        val ni = json.indexOf("\"n\""); val qi = json.indexOf('"', json.indexOf(':', ni + 3) + 1)
       |        val qe = json.indexOf('"', qi + 1); val nid = if qi >= 0 && qe > qi then json.substring(qi + 1, qe) else ""
       |        val li = json.indexOf("\"id\""); val ci = json.indexOf(':', li + 4)
       |        var i = ci + 1; while i < json.length && json(i) == ' ' do i += 1
       |        var j = i; while j < json.length && (json(j).isDigit || json(j) == '-') do j += 1
       |        val lid = json.substring(i, j).toLongOption.getOrElse(0L)
       |        _Pid(nid, lid)
       |      case _ => json
       |
       |""".stripMargin +
    """|
       |  def _resumeBlockedSender(state: _ActorState): Unit =
       |    if state.cap <= 0 || state.blockedSends.isEmpty then return
       |    if state.mailbox.size >= state.cap then return
       |    while state.blockedSends.nonEmpty do
       |      val (senderId, msg, senderK) = state.blockedSends.removeHead()
       |      actors.get(senderId) match
       |        case Some(ss) if ss != null =>
       |          state.mailbox.offer(msg)
       |          // Defer senderK(()) via _FlatMap so the continuation's side
       |          // effects run in the sender's own stepActor turn, not in the
       |          // current actor's turn (ordering fix: Block overflow).
       |          ss.pending = _FlatMap((), senderK)
       |          ready.append(senderId)
       |          return
       |        case _ => ()  // dead sender — skip
       |
       |  def tryDeliver(state: _ActorState, matcher: Any => Option[Any], wrapSome: Boolean): Option[Any] =
       |    while !state.mailbox.isEmpty do
       |      val msg = state.mailbox.peek()
       |      matcher(msg) match
       |        case Some(bodyC) =>
       |          state.mailbox.poll()
       |          _resumeBlockedSender(state)
       |          if wrapSome then
       |            return Some(_FlatMap(bodyC, (v: Any) => Some(v)))
       |          else
       |            return Some(bodyC)
       |        case None =>
       |          state.mailbox.poll()
       |          _resumeBlockedSender(state)
       |    None
       |
       |  def tryWakeBlocked(id: Long): Unit =
       |    actors.get(id).foreach { st =>
       |      if st.blocked != null then
       |        val b = st.blocked
       |        tryDeliver(st, b._1, b._4) match
       |          case Some(c) =>
       |            st.pending = _FlatMap(c, b._2)
       |            st.blocked = null
       |            ready.append(id)
       |          case None => ()
       |    }
       |
       |  def killActor(targetId: Long, reason: Any): Unit =
       |    if !actors.contains(targetId) then return
       |    val _dyingSt = actors(targetId)
       |    actors.remove(targetId)
       |    trapExitM.remove(targetId)
       |    // Resume blocked senders: target died → send becomes silent no-op.
       |    if _dyingSt.blockedSends.nonEmpty then
       |      _dyingSt.blockedSends.foreach { (senderId, _, senderK) =>
       |        actors.get(senderId).foreach { ss =>
       |          ss.pending = _FlatMap((), senderK)
       |          ready.append(senderId)
       |        }
       |      }
       |      _dyingSt.blockedSends.clear()
       |    val deadPid = _Pid("", targetId)
       |    links.remove(targetId).foreach { linkedSet =>
       |      linkedSet.foreach { linkedId =>
       |        links.get(linkedId).foreach(_.remove(targetId))
       |        if trapExitM.getOrElse(linkedId, false) then
       |          actors.get(linkedId).foreach { st =>
       |            st.mailbox.offer(Exit(deadPid, reason))
       |            tryWakeBlocked(linkedId)
       |          }
       |        else
       |          killActor(linkedId, reason)
       |      }
       |    }
       |    monitors.remove(targetId).foreach { monMap =>
       |      monMap.foreach { (monRef, observerId) =>
       |        actors.get(observerId).foreach { st =>
       |          st.mailbox.offer(Down(monRef, deadPid, reason))
       |          tryWakeBlocked(observerId)
       |        }
       |      }
       |    }
       |
       |  def handleActorOp(id: Long, state: _ActorState, op: String, args: List[Any], k: Any => Any): Either[Unit, Any] = op match
       |    case "spawn" =>
       |      val thunk = args(0).asInstanceOf[() => Any]
       |      val childId = spawnActor(thunk)
       |      Right(k(_Pid(_localNodeId, childId)))
       |    case "spawnLink" =>
       |      val thunk = args(0).asInstanceOf[() => Any]
       |      val childId = spawnActor(thunk)
       |      // Atomic bidirectional link
       |      links.getOrElseUpdate(id,      scala.collection.mutable.Set.empty) += childId
       |      links.getOrElseUpdate(childId, scala.collection.mutable.Set.empty) += id
       |      Right(k(_Pid(_localNodeId, childId)))
       |    case "spawnBounded" =>
       |      val cap = args(0) match
       |        case n: Int  => n
       |        case n: Long => n.toInt
       |        case _       => 0
       |      val ov = args(1) match
       |        case s: String => s
       |        case m: scala.collection.immutable.Map[?, ?] =>
       |          m.asInstanceOf[Map[String, Any]].getOrElse("_type", "DropNewest").toString
       |        case _ => "DropNewest"
       |      val thunk = args(2).asInstanceOf[() => Any]
       |      val childId = spawnActor(thunk, cap, ov)
       |      Right(k(_Pid(_localNodeId, childId)))
       |    case "self" => Right(k(_Pid(_localNodeId, id)))
       |    case "processInfo" =>
       |      args(0) match
       |        case _Pid(_, targetId) =>
       |          actors.get(targetId) match
       |            case None => Right(k(None))
       |            case Some(ts) =>
       |              val lnks = links.get(targetId).map(_.toList.map(lid => _Pid("", lid))).getOrElse(List.empty)
       |              val status = if ts.blocked != null then "blocked" else "running"
       |              val info = Map("_type" -> "ProcessInfo", "mailboxSize" -> ts.mailbox.size,
       |                             "links" -> lnks, "status" -> status)
       |              Right(k(Some(info)))
       |        case _ => Right(k(None))
       |    case "send" =>
       |      args(0) match
       |        case _Pid(pidNode, targetId) =>
       |          if pidNode.nonEmpty && pidNode != _localNodeId then
       |            // Remote send — serialize and enqueue to peer channel
       |            Option(_peerChannels.get(pidNode)).foreach { sendFn =>
       |              val body = _serializeValue(args(1))
       |              sendFn(_mkMsgEnv(_localNodeId, id, pidNode, targetId, body))
       |            }
       |          else
       |            actors.get(targetId) match
       |              case Some(ts) =>
       |                val _delivered =
       |                  if ts.cap > 0 && ts.mailbox.size >= ts.cap then
       |                    ts.overflow match
       |                      case "DropOldest" =>
       |                        ts.mailbox.poll(); ts.mailbox.offer(args(1)); true
       |                      case "DropNewest" => false
       |                      case "Fail" =>
       |                        killActor(id, "mailbox_overflow")
       |                        return if actors.contains(id) then Right(k(())) else Left(())
       |                      case "Block" =>
       |                        ts.blockedSends.append((id, args(1), k))
       |                        return Left(())
       |                      case _ => ts.mailbox.offer(args(1)); true
       |                  else { ts.mailbox.offer(args(1)); true }
       |                if _delivered && ts.blocked != null then
       |                  val b = ts.blocked
       |                  tryDeliver(ts, b._1, b._4) match
       |                    case Some(c) =>
       |                      ts.pending = _FlatMap(c, b._2)
       |                      ts.blocked = null
       |                      ready.append(targetId)
       |                    case None => ()
       |              case None => ()
       |        case _ => ()
       |      Right(k(()))
       |    case "exit" =>
       |      args(0) match
       |        case _Pid(_, targetId) => killActor(targetId, args(1))
       |        case _                 => ()
       |      if actors.contains(id) then Right(k(())) else Left(())
       |    case "receive" =>
       |      val matcher = _receiveSpecs.get(args(0).asInstanceOf[Long])
       |      tryDeliver(state, matcher, false) match
       |        case Some(c) => Right(_FlatMap(c, k))
       |        case None =>
       |          state.blocked = (matcher, k, None, false)
       |          Left(())
       |    case "receive_t" =>
       |      val matcher = _receiveSpecs.get(args(0).asInstanceOf[Long])
       |      val ms = args(1) match
       |        case n: Int  => n.toLong
       |        case n: Long => n
       |        case _       => 0L
       |      tryDeliver(state, matcher, true) match
       |        case Some(c) => Right(_FlatMap(c, k))
       |        case None =>
       |          state.blocked = (matcher, k, Some(System.currentTimeMillis() + ms), true)
       |          Left(())
       |    // ── v1.6 Phase 2 — supervision ─────────────────────────────────────
       |    case "link" =>
       |      args(0) match
       |        case _Pid(nid, targetId) =>
       |          if nid.nonEmpty && nid != _localNodeId then
       |            if _peerChannels.containsKey(nid) then
       |              _remoteLinks.computeIfAbsent(nid, _ => new java.util.concurrent.CopyOnWriteArrayList()).add((id, targetId))
       |            else
       |              if trapExitM.getOrElse(id, false) then
       |                actors.get(id).foreach(_.mailbox.offer(Exit(_Pid(nid, targetId), noproc)))
       |              else killActor(id, noproc)
       |          else if actors.contains(targetId) then
       |            links.getOrElseUpdate(id,       scala.collection.mutable.Set.empty) += targetId
       |            links.getOrElseUpdate(targetId, scala.collection.mutable.Set.empty) += id
       |          else
       |            if trapExitM.getOrElse(id, false) then
       |              actors.get(id).foreach(_.mailbox.offer(Exit(_Pid("", targetId), noproc)))
       |            else
       |              killActor(id, noproc)
       |        case _ => ()
       |      if actors.contains(id) then Right(k(())) else Left(())
       |    case "monitor" =>
       |      args(0) match
       |        case _Pid(nid, targetId) =>
       |          val monRef = nextMonRef; nextMonRef += 1
       |          if nid.nonEmpty && nid != _localNodeId then
       |            if _peerChannels.containsKey(nid) then
       |              _remoteMonitors.computeIfAbsent(nid, _ => new java.util.concurrent.CopyOnWriteArrayList()).add((id, monRef, targetId))
       |            else
       |              actors.get(id).foreach { st =>
       |                st.mailbox.offer(Down(monRef, _Pid(nid, targetId), "noconnection"))
       |                tryWakeBlocked(id)
       |              }
       |            Right(k(monRef))
       |          else if actors.contains(targetId) then
       |            monitors.getOrElseUpdate(targetId, scala.collection.mutable.Map.empty)(monRef) = id
       |            Right(k(monRef))
       |          else
       |            actors.get(id).foreach { st =>
       |              st.mailbox.offer(Down(monRef, _Pid("", targetId), noproc))
       |              tryWakeBlocked(id)
       |            }
       |            Right(k(monRef))
       |        case _ => Right(k(-1L))
       |    case "demonitor" =>
       |      val monRef = args(0).asInstanceOf[Long]
       |      monitors.foreachEntry((_, m) => m.remove(monRef))
       |      Right(k(()))
       |    case "trapExit" =>
       |      trapExitM(id) = args(0) match
       |        case b: Boolean => b
       |        case _          => args(0) == true
       |      Right(k(()))
       |    // ── Phase 3 — distributed ────────────────────────────────────────────
       |    case "startNode" =>
       |      _localNodeId  = args(0).toString
       |      _localNodeUrl = if args.length > 1 then args(1).toString else ""
       |      // Register /_ssc-actors WS route for inbound peer connections.
       |      // v1.23 — the blocking handshake + recv loop is wrapped in a
       |      // virtual thread so the WS server's single-thread dispatch
       |      // executor returns immediately and stays free to process the
       |      // next peer.  Without this the FIRST inbound peer's recv loop
       |      // monopolises the executor and subsequent handshakes stall
       |      // in the queue — fragmented clusters where every node only
       |      // sees the seed.
       |      // protocols: echo the `ssc-actors-v1` subprotocol the peer clients
       |      // dial with (both JS-codegen `connectNode` and JVM-codegen offer only
       |      // v1). Without it the WS upgrade emits no `Sec-WebSocket-Protocol`
       |      // header and a spec-compliant `ws` client (JS peer) rejects the
       |      // connection ("Server sent no subprotocol") → no Bully convergence.
       |      onWebSocket("/_ssc-actors", protocols = List("ssc-actors-v1")) { ws =>
       |        Thread.ofVirtual().start { () =>
       |          def wsSend(t: String): Unit = ws.send(t)
       |          def wsRecv(): String | Null  = ws.recv() match
       |            case Some(s) => s
       |            case None    => null
       |          val first = wsRecv()
       |          if first != null then
       |            val pnId = _parseNodeId(first)
       |            if pnId.nonEmpty then
       |              wsSend("{\"nodeId\":" + _jstr(_localNodeId) + "}")
       |              _peerChannels.put(pnId, wsSend)
       |              _peerLastPong.put(pnId, System.currentTimeMillis())
       |              _fireClusterEvent("NodeJoined", pnId)
       |              _sendConfigSnapshot(wsSend)
       |              _sendDrainState(wsSend)
       |              _sendMetricSnapshot(wsSend)
       |              val hbThread = Thread.ofVirtual().start { () =>
       |                try
       |                  while _peerChannels.containsKey(pnId) do
       |                    Thread.sleep(_peerHeartbeatIntervalMs)
       |                    if _peerChannels.containsKey(pnId) then
       |                      val age = System.currentTimeMillis() - _peerLastPong.getOrDefault(pnId, 0L)
       |                      if age > _peerHeartbeatDeadAfterMs then _peerChannels.remove(pnId)
       |                      else try _peerChannels.get(pnId)("{\"t\":\"ping\"}") catch case _: Throwable => ()
       |                catch case _: InterruptedException => ()
       |              }
       |              var running = true
       |              while running do
       |                val msg = wsRecv()
       |                if msg == null then running = false else _dispatchPeerEnv(pnId, msg)
       |              hbThread.interrupt()
       |              _peerChannels.remove(pnId)
       |              _peerLastPong.remove(pnId)
       |              _peerUrls.remove(pnId)
       |              _peerPongHist.remove(pnId)
       |              _peerPhiViews.remove(pnId)
       |              _drainingPeers.remove(pnId)
       |              _clusterMetrics.forEach { (_, inner) => inner.remove(pnId) }
       |              _nodeDownQueue.offer(pnId)
       |              _fireClusterEvent("NodeLeft", pnId, "disconnect")
       |              if _currentLeader.compareAndSet(pnId, "") then
       |                _fireLeaderEvent("LeaderLost", pnId)
       |                if _autoReelect then _startElection()
       |        }
       |      }
       |      // v1.23 — operational HTTP endpoints under /_ssc-cluster/* so
       |      // ops tooling (`ssc cluster status / drain / events / step-down
       |      // / metrics-prom`) can talk to codegen-built nodes just like
       |      // interpreter nodes.  Idempotent — repeated startNode calls
       |      // are no-ops for the route table.
       |      _registerClusterStatusRoute()
       |      _registerClusterDrainRoute()
       |      _registerClusterEventsRoute()
       |      _registerClusterStepDownRoute()
       |      _registerClusterMetricsPromRoute()
       |      _registerClusterHandlersRoute()
       |      Right(k(()))
       |    case "connectNode" =>
       |      val url = args(0).toString
       |      val tok = if args.length > 1 then args(1).toString else ""
       |      _connectPeer(url, tok)
       |      Right(k(()))
       |    case "joinCluster" =>
       |      _joinMode  = true
       |      _joinToken = if args.length > 1 then args(1).toString else ""
       |      val seeds = args(0) match
       |        case lst: List[?] => lst.collect { case s: String => s }
       |        case _            => Nil
       |      seeds.foreach(u => _connectPeer(u, _joinToken))
       |      Right(k(()))
       |    case "register" =>
       |      val name = args(0).toString
       |      val localId = args(1) match { case _Pid(_, lid) => lid; case _ => id }
       |      _nodeRegistry.put(name, localId)
       |      Right(k(()))
       |    case "whereis" =>
       |      val name = args(0).toString
       |      val result =
       |        if _nodeRegistry.containsKey(name) && actors.contains(_nodeRegistry.get(name)) then
       |          Some(_Pid(_localNodeId, _nodeRegistry.get(name)))
       |        else
       |          None
       |      Right(k(result))
       |    // v1.6.x — cluster-wide registry
       |    case "globalRegister" =>
       |      val grName    = args(0).toString
       |      val grPidRaw  = args(1).asInstanceOf[_Pid]
       |      // v1.23 — local-spawn Pids carry an empty nodeId.  Stamp the
       |      // local node identity onto the registered Pid so cross-node
       |      // lookups can route back here; without this the broadcast
       |      // payload's `nodeId` is "" and remote nodes silently drop
       |      // every cross-node send to this name.
       |      val grNid     = if grPidRaw.nodeId.nonEmpty then grPidRaw.nodeId else _localNodeId
       |      val grPid     = _Pid(grNid, grPidRaw.localId)
       |      _globalRegistry.put(grName, grPid)
       |      val payload = "{\"t\":\"global_reg\",\"name\":" + _jstr(grName) + ",\"nodeId\":" + _jstr(grNid) + ",\"localId\":" + _jstr(grPid.localId.toString) + "}"
       |      _peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
       |      Right(k(()))
       |    case "globalWhereis" =>
       |      val gwName = args(0).toString
       |      val result = Option(_globalRegistry.get(gwName))
       |      Right(k(result))
       |    // v1.23 — cluster visibility
       |    case "clusterMembers" =>
       |      val buf = scala.collection.mutable.ListBuffer.empty[String]
       |      _peerChannels.keySet().forEach(k0 => buf += k0)
       |      Right(k(buf.toList))
       |    case "subscribeClusterEvents" =>
       |      val boxed = java.lang.Long.valueOf(id)
       |      if !_clusterEventSubs.contains(boxed) then _clusterEventSubs.add(boxed)
       |      Right(k(()))
       |    // v1.23 — phi-accrual failure detector
       |    case "phiOf" =>
       |      Right(k(_computePhi(args(0).toString)))
       |    case "isSuspect" =>
       |      val thr = args(1) match
       |        case d: Double => d
       |        case l: Long   => l.toDouble
       |        case i: Int    => i.toDouble
       |        case _         => 8.0
       |      Right(k(_computePhi(args(0).toString) >= thr))
       |    // v1.23 — local node identity
       |    case "selfNode" =>
       |      Right(k(_localNodeId))
       |    // v1.23 — cluster health (phi vector for connected peers)
       |    case "clusterHealth" =>
       |      val m = scala.collection.mutable.Map.empty[String, Double]
       |      _peerChannels.keySet().forEach(k0 => m(k0) = _computePhi(k0))
       |      Right(k(m.toMap))
       |    // v1.23 — cluster-wide FD: broadcast phi vector to peers.
       |    case "broadcastHealth" =>
       |      val sb = new StringBuilder("{\"t\":\"phi_vector\",\"from\":")
       |      sb.append(_jstr(_localNodeId)).append(",\"view\":[")
       |      var first = true
       |      _peerChannels.keySet().forEach { nid =>
       |        val phi = _computePhi(nid)
       |        if !phi.isInfinite && !phi.isNaN then
       |          if !first then sb.append(',')
       |          sb.append("[").append(_jstr(nid)).append(',').append(phi).append(']')
       |          first = false
       |      }
       |      sb.append("]}")
       |      val payload = sb.toString
       |      _peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
       |      Right(k(()))
       |    // v1.23 — cluster-wide FD: majority vote across peer views.
       |    case "clusterIsDown" =>
       |      val target = args(0).toString
       |      val thr    = args(1) match
       |        case d: Double => d
       |        case l: Long   => l.toDouble
       |        case i: Int    => i.toDouble
       |        case _         => 8.0
       |      var votes = 0
       |      var total = 0
       |      if _peerChannels.containsKey(target) then
       |        total += 1
       |        if _computePhi(target) >= thr then votes += 1
       |      _peerPhiViews.forEach { (peerNid, peerView) =>
       |        if peerNid != target then
       |          val p = peerView.get(target)
       |          if p != null then
       |            total += 1
       |            if p.doubleValue() >= thr then votes += 1
       |      }
       |      val majority = (total + 1) / 2
       |      Right(k(total > 0 && votes >= majority))
       |    // v1.23 — leader election (Bully or Raft, picked by _leaderProtocol)
       |    case "electLeader" =>
       |      _leaderProtocol.get() match
       |        case "raft" => _startRaftElection()
       |        case _      => _startElection()
       |      Right(k(()))
       |    case "currentLeader" =>
       |      _leaderProtocol.get() match
       |        case "raft"  => Right(k(_raftLeaderId))
       |        case "coord" =>
       |          val holderFn = _coordHolderFn
       |          val held: Option[String] =
       |            if holderFn != null then
       |              try holderFn.asInstanceOf[() => Option[String]]()
       |              catch case _: Throwable => None
       |            else None
       |          Right(k(held.getOrElse("")))
       |        case _       => Right(k(_currentLeader.get()))
       |    case "subscribeLeaderEvents" =>
       |      val boxed = java.lang.Long.valueOf(id)
       |      if !_leaderEventSubs.contains(boxed) then _leaderEventSubs.add(boxed)
       |      Right(k(()))
       |    case "setAutoReelect" =>
       |      _autoReelect = args(0) match
       |        case b: Boolean => b
       |        case _          => false
       |      Right(k(()))
       |    // v1.23 — protocol switch + history (cluster-raft.md §6).
       |    case "useRaftLeaderElection" =>
       |      _leaderProtocol.set("raft")
       |      _raftLoad()
       |      _raftState       = "follower"
       |      _raftElectionDue = System.currentTimeMillis() + _raftRandTimeout
       |      _ensureRaftTickThread()
       |      Right(k(()))
       |    case "useExternalCoordinator" =>
       |      _leaderProtocol.set("coord")
       |      if args.length >= 4 then
       |        _coordAcquireFn = args(0).asInstanceOf[AnyRef]
       |        _coordRenewFn   = args(1).asInstanceOf[AnyRef]
       |        _coordReleaseFn = args(2).asInstanceOf[AnyRef]
       |        _coordHolderFn  = args(3).asInstanceOf[AnyRef]
       |        // Try once synchronously so callers don't wait a tick.
       |        val got = try _coordAcquireFn.asInstanceOf[(String, Long) => Boolean](_localNodeId, _COORD_LEASE_TIMEOUT_MS)
       |                  catch case _: Throwable => false
       |        if got then
       |          _coordIsLeader = true
       |          val prev = _currentLeader.getAndSet(_localNodeId)
       |          if prev != _localNodeId then
       |            _fireLeaderEvent("LeaderElected", _localNodeId)
       |            _recordLeaderHist(_localNodeId)
       |        _ensureCoordTickThread()
       |      Right(k(()))
       |    case "leaderProtocol" =>
       |      Right(k(_leaderProtocol.get()))
       |    case "leaderHistory" =>
       |      val buf = scala.collection.mutable.ListBuffer.empty[(Long, String, Long)]
       |      _leaderHist.iterator().forEachRemaining(e => buf += e)
       |      Right(k(buf.toList))
       |    // v1.23 — auto-reconnect policy
       |    case "setReconnectPolicy" =>
       |      def _argL(i: Int): Long = if args.length > i then args(i) match
       |        case l: Long   => l
       |        case i2: Int   => i2.toLong
       |        case d: Double => d.toLong
       |        case _         => 0L
       |      else 0L
       |      _reconnectInitialMs = _argL(0).max(0L)
       |      _reconnectMaxMs     = _argL(1).max(_reconnectInitialMs)
       |      // v1.23 — optional 3rd arg: total wall-clock retry budget (ms)
       |      // per URL; 0 = no cap (retry forever).
       |      _reconnectGiveUpMs  = _argL(2).max(0L)
       |      Right(k(()))
       |    // v1.23 — heartbeat cadence + dead-after threshold
       |    case "setHeartbeatTimeout" =>
       |      val iv = args(0) match
       |        case l: Long   => l
       |        case i: Int    => i.toLong
       |        case d: Double => d.toLong
       |        case _         => 30000L
       |      val dead = args(1) match
       |        case l: Long   => l
       |        case i: Int    => i.toLong
       |        case d: Double => d.toLong
       |        case _         => 40000L
       |      _peerHeartbeatIntervalMs  = iv.max(1L)
       |      _peerHeartbeatDeadAfterMs = dead.max(_peerHeartbeatIntervalMs)
       |      Right(k(()))
       |    // v1.23 — cluster endpoint shared-secret
       |    case "setClusterAuthToken" =>
       |      _clusterAuthToken = args.headOption.map(_.toString).getOrElse("")
       |      Right(k(()))
       |    // v1.23 — quorum-aware Bully threshold
       |    case "setQuorumSize" =>
       |      val n = args(0) match
       |        case l: Long   => l
       |        case i: Int    => i.toLong
       |        case d: Double => d.toLong
       |        case _         => 0L
       |      _quorumSize = n.max(0L)
       |      Right(k(()))
       |    // v1.23 — periodic gossip re-discovery: ask every connected peer
       |    // for their peer-URL list.  Replies come back via the existing
       |    // `peers_resp` handler and feed `_connectPeer` for unknown URLs.
       |    case "requestGossip" =>
       |      val payload = "{\"t\":\"peers_req\",\"from\":" + _jstr(_localNodeId) + "}"
       |      _peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
       |      Right(k(()))
       |    // v1.23 — cluster configuration distribution.
       |    case "clusterConfigSet" =>
       |      val key   = args(0).toString
       |      val value = args(1).toString
       |      val ts    = System.currentTimeMillis()
       |      val orig  = _localNodeId
       |      _applyConfigUpdate(key, value, ts, orig)
       |      val payload = "{\"t\":\"config_set\",\"key\":" + _jstr(key) +
       |                    ",\"value\":" + _jstr(value) +
       |                    ",\"ts\":" + ts.toString +
       |                    ",\"origin\":" + _jstr(orig) + "}"
       |      _peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
       |      Right(k(()))
       |    case "clusterConfigGet" =>
       |      val key = args(0).toString
       |      val entry = _clusterConfig.get(key)
       |      val result: Any = if entry == null then None else Some(entry._1)
       |      Right(k(result))
       |    case "clusterConfigKeys" =>
       |      val buf = scala.collection.mutable.ListBuffer.empty[String]
       |      _clusterConfig.keySet().forEach(k0 => buf += k0)
       |      Right(k(buf.toList))
       |    case "subscribeConfigEvents" =>
       |      val boxed = java.lang.Long.valueOf(id)
       |      if !_configEventSubs.contains(boxed) then _configEventSubs.add(boxed)
       |      Right(k(()))
       |    // v1.23 — drain / rolling-restart
       |    case "setDraining" =>
       |      val b = args(0) match
       |        case bb: Boolean => bb
       |        case _           => false
       |      val prev = _isDrainingSelf.getAndSet(b)
       |      if prev != b then
       |        val payload = "{\"t\":\"drain\",\"from\":" + _jstr(_localNodeId) +
       |                      ",\"draining\":" + b.toString + "}"
       |        _peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
       |        _fireDrainEvent(_localNodeId, b)
       |        // v1.23 — drain-aware step-down: if we just flipped to
       |        // draining and we're the leader, release leadership.
       |        if b then _stepDownIfLeader()
       |      Right(k(()))
       |    case "isDraining" =>
       |      Right(k(_isDrainingSelf.get()))
       |    case "drainingPeers" =>
       |      val buf = scala.collection.mutable.ListBuffer.empty[String]
       |      _drainingPeers.forEach { (nid, v) => if v != null && v.booleanValue() then buf += nid }
       |      Right(k(buf.toList))
       |    case "subscribeDrainEvents" =>
       |      val boxed = java.lang.Long.valueOf(id)
       |      if !_drainEventSubs.contains(boxed) then _drainEventSubs.add(boxed)
       |      Right(k(()))
       |    // v1.23 — cluster metrics aggregation
       |    case "clusterMetricSet" =>
       |      val name = args(0).toString
       |      val value = args(1) match
       |        case d: Double => d
       |        case l: Long   => l.toDouble
       |        case i: Int    => i.toDouble
       |        case _         => 0.0
       |      _applyMetricUpdate(name, _localNodeId, value)
       |      val payload = "{\"t\":\"metric\",\"from\":" + _jstr(_localNodeId) +
       |                    ",\"name\":" + _jstr(name) +
       |                    ",\"value\":" + value.toString + "}"
       |      _peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
       |      Right(k(()))
       |    case "clusterMetricGet" =>
       |      val name = args(0).toString
       |      val inner = _clusterMetrics.get(name)
       |      val m = scala.collection.mutable.Map.empty[String, Double]
       |      if inner != null then
       |        inner.forEach { (nid, v) => m(nid) = v.doubleValue() }
       |      Right(k(m.toMap))
       |    case "clusterMetricSum" =>
       |      val name = args(0).toString
       |      val inner = _clusterMetrics.get(name)
       |      var sum = 0.0
       |      if inner != null then
       |        inner.forEach { (_, v) => sum += v.doubleValue() }
       |      Right(k(sum))
       |    case "clusterMetricNames" =>
       |      val buf = scala.collection.mutable.ListBuffer.empty[String]
       |      _clusterMetrics.keySet().forEach(s => buf += s)
       |      Right(k(buf.toList))
       |    case "subscribeMetricEvents" =>
       |      val boxed = java.lang.Long.valueOf(id)
       |      if !_metricEventSubs.contains(boxed) then _metricEventSubs.add(boxed)
       |      Right(k(()))
       |    // v1.6.x — scheduled sends
       |    case "sendAfter" =>
       |      val delayMs  = args(0).asInstanceOf[Long]
       |      val targetId = args(1).asInstanceOf[_Pid].localId
       |      val msg      = args(2)
       |      val fireAt   = System.currentTimeMillis() + delayMs
       |      val ref      = _nextTimerId; _nextTimerId += 1
       |      _timers(ref) = (fireAt, None, targetId, msg)
       |      Right(k(ref))
       |    case "sendInterval" =>
       |      val periodMs = args(0).asInstanceOf[Long]
       |      val targetId = args(1).asInstanceOf[_Pid].localId
       |      val msg      = args(2)
       |      val fireAt   = System.currentTimeMillis() + periodMs
       |      val ref      = _nextTimerId; _nextTimerId += 1
       |      _timers(ref) = (fireAt, Some(periodMs), targetId, msg)
       |      Right(k(ref))
       |    case "cancelTimer" =>
       |      _timers.remove(args(0).asInstanceOf[Long])
       |      Right(k(()))
       |    case other => sys.error("Unknown Actor op: " + other)
       |
       |  // Synchronous fallback handler for non-Actor effects performed
       |  // inside an actor body — dep code like std/mapreduce/distributed
       |  // calls `Random.uuid()` while running under `runActors`, and the
       |  // value-producing primitives (Random.*, Clock.now/nowIso) can be
       |  // evaluated in-place without a continuation.  Unsupported effects
       |  // still throw with a clear message.  Blocking ops (Clock.sleep)
       |  // intentionally don't appear here — they'd freeze the single
       |  // actor scheduler thread.
       |  lazy val _actorRng = new java.util.Random()
       |  def _actorFallback(eff: String, op: String, args: List[Any]): Any =
       |    (eff, op) match
       |      case ("Random", "uuid") =>
       |        val bytes = new Array[Byte](16)
       |        _actorRng.nextBytes(bytes)
       |        bytes(6) = ((bytes(6) & 0x0f) | 0x40).toByte
       |        bytes(8) = ((bytes(8) & 0x3f) | 0x80).toByte
       |        def hex(b: Byte) = f"${b & 0xff}%02x"
       |        val u = bytes.map(hex).mkString
       |        s"${u.take(8)}-${u.slice(8,12)}-${u.slice(12,16)}-${u.slice(16,20)}-${u.drop(20)}"
       |      case ("Random", "nextInt") =>
       |        val n = args(0) match { case x: Int => x; case x: Long => x.toInt; case _ => 1 }
       |        _actorRng.nextInt(if n > 0 then n else 1)
       |      case ("Random", "nextDouble") => _actorRng.nextDouble()
       |      case ("Random", "pick") =>
       |        val xs = args(0).asInstanceOf[List[Any]]
       |        xs(_actorRng.nextInt(xs.size))
       |      case ("Clock", "now")    => java.lang.System.currentTimeMillis()
       |      case ("Clock", "nowIso") =>
       |        java.time.format.DateTimeFormatter.ISO_INSTANT
       |          .format(java.time.Instant.ofEpochMilli(java.lang.System.currentTimeMillis()))
       |      case _ =>
       |        throw new RuntimeException("Unhandled effect inside actor: " + eff + "." + op)
       |
       |  def stepActor(id: Long, initial: Any): Unit =
       |    var current: Any = initial
       |    while true do
       |      current match
       |        case _Perform("Actor", op, args) =>
       |          handleActorOp(id, actors(id), op, args, (v: Any) => v) match
       |            case Right(next) => current = next
       |            case Left(_)     => return
       |        case _Perform(eff, op, args) =>
       |          // Plain perform with no continuation: compute via
       |          // fallback and use the value as the final result of
       |          // this actor step.
       |          current = _actorFallback(eff, op, args)
       |        case _FlatMap(sub, f) => sub match
       |          case _Perform("Actor", op, args) =>
       |            handleActorOp(id, actors(id), op, args, f.asInstanceOf[Any => Any]) match
       |              case Right(next) => current = next
       |              case Left(_)     => return
       |          case _Perform(eff, op, args) =>
       |            val v = _actorFallback(eff, op, args)
       |            current = f.asInstanceOf[Any => Any](v)
       |          case _FlatMap(s2, g) =>
       |            current = _FlatMap(s2,
       |              (x: Any) => _FlatMap(g.asInstanceOf[Any => Any](x), f))
       |          case v =>
       |            current = f.asInstanceOf[Any => Any](v)
       |        case v =>
       |          if id == rootId then rootResult = v
       |          // Fire monitors with reason "normal" on natural completion.
       |          val myPid = _Pid(_localNodeId, id)
       |          monitors.remove(id).foreach { monMap =>
       |            monMap.foreach { (monRef, observerId) =>
       |              actors.get(observerId).foreach { st =>
       |                st.mailbox.offer(Down(monRef, myPid, "normal"))
       |                tryWakeBlocked(observerId)
       |              }
       |            }
       |          }
       |          links.remove(id).foreach { linkedSet =>
       |            linkedSet.foreach { linkedId =>
       |              links.get(linkedId).foreach(_.remove(id))
       |            }
       |          }
       |          actors.remove(id)
       |          return
       |
       |  def _mkMsgEnv(fromNode: String, fromId: Long, toNode: String, toId: Long, body: String): String =
       |    "{\"t\":\"msg\",\"to\":{\"nodeId\":" + _jstr(toNode) + ",\"localId\":" + toId +
       |    "},\"from\":{\"nodeId\":" + _jstr(fromNode) + ",\"localId\":" + fromId +
       |    "},\"body\":" + body + "}"
       |
       |  def _serializeValue(v: Any): String = v match
       |    case n: Long    => "{\"$t\":\"i\",\"v\":" + n + "}"
       |    case n: Int     => "{\"$t\":\"i\",\"v\":" + n + "}"
       |    case d: Double  => "{\"$t\":\"d\",\"v\":" + d + "}"
       |    case s: String  => "{\"$t\":\"s\",\"v\":" + _jstr(s) + "}"
       |    case b: Boolean => "{\"$t\":\"b\",\"v\":" + b + "}"
       |    case ()         => "{\"$t\":\"u\"}"
       |    case _Pid(nId, lId) => "{\"$t\":\"pid\",\"n\":" + _jstr(nId) + ",\"id\":" + lId + "}"
       |    case xs: List[?] => "{\"$t\":\"l\",\"v\":[" + xs.map(_serializeValue).mkString(",") + "]}"
       |    case _          => "{\"$t\":\"s\",\"v\":" + _jstr(v.toString) + "}"
       |
       |  val _isDistributed = _localNodeId.nonEmpty || !_peerChannels.isEmpty
       |
       |  while ready.nonEmpty ||
       |        !_nodeDownQueue.isEmpty ||
       |        !_clusterEventQueue.isEmpty ||
       |        !_leaderEventQueue.isEmpty ||
       |        !_configEventQueue.isEmpty ||
       |        !_drainEventQueue.isEmpty ||
       |        !_metricEventQueue.isEmpty ||
       |        _electionInProgress ||
       |        _timers.nonEmpty ||
       |        actors.exists { (_, st) => st != null && st.blocked != null && st.blocked._3.isDefined } ||
       |        (_isDistributed && actors.nonEmpty && actors.exists { (_, st) => st != null && st.blocked != null })
       |  do
       |    // Drain remote inbox
       |    while !_remoteInbox.isEmpty do
       |      val (targetId, msg) = _remoteInbox.poll()
       |      actors.get(targetId).foreach { ts =>
       |        ts.mailbox.offer(msg)
       |        tryWakeBlocked(targetId)
       |      }
       |    // Drain node-down notifications
       |    while !_nodeDownQueue.isEmpty do
       |      _fireNodeDown(_nodeDownQueue.poll())
       |    // v1.23 — deliver cluster events to subscribers.
       |    while !_clusterEventQueue.isEmpty do
       |      val (_tag, _nid, _reason) = _clusterEventQueue.poll()
       |      val _msg: Any =
       |        if _tag == "NodeJoined" then NodeJoined(_nid)
       |        else NodeLeft(_nid, _reason)
       |      val _it = _clusterEventSubs.iterator
       |      while _it.hasNext do
       |        val _aid = _it.next().longValue()
       |        actors.get(_aid).foreach { ts =>
       |          ts.mailbox.offer(_msg)
       |          tryWakeBlocked(_aid)
       |        }
       |    // v1.23 — Bully election timeout: claim self if no higher-id peer
       |    // responded with `alive` within the window.
       |    if _electionInProgress && System.currentTimeMillis() - _electionStartedAt >= _ELECTION_TIMEOUT_MS then
       |      _electionInProgress = false
       |      // v1.23 — quorum gate: same as `_startElection.higher.isEmpty`
       |      // branch — even though no higher peer responded, decline to
       |      // self-claim when below quorum.
       |      if !_gotAliveResponse && _hasQuorum then
       |        val _prev = _currentLeader.getAndSet(_localNodeId)
       |        _broadcastCoordinator()
       |        if _prev != _localNodeId then
       |          _fireLeaderEvent("LeaderElected", _localNodeId)
       |          _recordLeaderHist(_localNodeId)
       |    // v1.23 — deliver leader events to subscribers.
       |    while !_leaderEventQueue.isEmpty do
       |      val (_tag, _lid) = _leaderEventQueue.poll()
       |      val _msg: Any =
       |        if _tag == "LeaderElected" then LeaderElected(_lid)
       |        else LeaderLost(_lid)
       |      val _it = _leaderEventSubs.iterator
       |      while _it.hasNext do
       |        val _aid = _it.next().longValue()
       |        actors.get(_aid).foreach { ts =>
       |          ts.mailbox.offer(_msg)
       |          tryWakeBlocked(_aid)
       |        }
       |    // v1.23 — deliver config-change events to subscribers.
       |    while !_configEventQueue.isEmpty do
       |      val (_key, _val) = _configEventQueue.poll()
       |      val _msg: Any = ConfigChanged(_key, _val)
       |      val _it = _configEventSubs.iterator
       |      while _it.hasNext do
       |        val _aid = _it.next().longValue()
       |        actors.get(_aid).foreach { ts =>
       |          ts.mailbox.offer(_msg)
       |          tryWakeBlocked(_aid)
       |        }
       |    // v1.23 — deliver drain-state events to subscribers.
       |    while !_drainEventQueue.isEmpty do
       |      val (_nid, _drn) = _drainEventQueue.poll()
       |      val _msg: Any = DrainStateChanged(_nid, _drn)
       |      val _it = _drainEventSubs.iterator
       |      while _it.hasNext do
       |        val _aid = _it.next().longValue()
       |        actors.get(_aid).foreach { ts =>
       |          ts.mailbox.offer(_msg)
       |          tryWakeBlocked(_aid)
       |        }
       |    // v1.23 — deliver metric events to subscribers.
       |    while !_metricEventQueue.isEmpty do
       |      val (_nm, _nid, _val) = _metricEventQueue.poll()
       |      val _msg: Any = MetricChanged(_nm, _nid, _val)
       |      val _it = _metricEventSubs.iterator
       |      while _it.hasNext do
       |        val _aid = _it.next().longValue()
       |        actors.get(_aid).foreach { ts =>
       |          ts.mailbox.offer(_msg)
       |          tryWakeBlocked(_aid)
       |        }
       |    // Fire scheduled sends whose deadline has passed.
       |    if _timers.nonEmpty then
       |      val _nowMs = System.currentTimeMillis()
       |      val _firedRefs = _timers.collect { case (r, (fa, _, _, _)) if _nowMs >= fa => r }.toList
       |      for _ref <- _firedRefs; _entry <- _timers.get(_ref) do
       |        val (fireAt, period, targetId, msg) = _entry
       |        actors.get(targetId).foreach { ts =>
       |          ts.mailbox.offer(msg)
       |          tryWakeBlocked(targetId)
       |        }
       |        period match
       |          case Some(p) => _timers(_ref) = (fireAt + p, period, targetId, msg)
       |          case None    => _timers.remove(_ref)
       |    if ready.isEmpty then
       |      val _blockDeadline = actors.iterator.collect {
       |        case (aid, st) if st != null && st.blocked != null && st.blocked._3.isDefined =>
       |          (aid, st.blocked._3.get)
       |      }.toList.minByOption(_._2)
       |      val _timerDeadline = if _timers.isEmpty then None else Some(_timers.values.map(_._1).min)
       |      val _sleepUntil = List(_blockDeadline.map(_._2), _timerDeadline).flatten.minOption
       |      val _sleepFor   = _sleepUntil.map(_ - System.currentTimeMillis()).getOrElse(if _isDistributed then 30L else Long.MaxValue)
       |      if _sleepFor > 0 then
       |        try Thread.sleep(_sleepFor)
       |        catch case _: InterruptedException => ()
       |      _blockDeadline match
       |        case Some((aid, deadline)) if System.currentTimeMillis() >= deadline =>
       |          val st = actors(aid)
       |          val (_, k, _, _) = st.blocked
       |          st.pending = k(None)
       |          st.blocked = null
       |          ready.append(aid)
       |        case _ => ()
       |    else
       |      val id = ready.removeHead()
       |      actors.get(id).foreach { st =>
       |        if st.pending != null then
       |          val initial = st.pending
       |          st.pending = null
       |          stepActor(id, initial)
       |      }
       |
       |  // v1.23 — graceful cluster shutdown: release the coord lease if we
       |  // hold it, so the next leader can claim immediately instead of
       |  // waiting for the TTL.  Raft's final (term, votedFor) is already
       |  // on disk via _raftPersist().
       |  if _leaderProtocol.get() == "coord" && _coordIsLeader then
       |    val rel = _coordReleaseFn
       |    if rel != null then
       |      try rel.asInstanceOf[String => Unit](_localNodeId)
       |      catch case _: Throwable => ()
       |    _coordIsLeader = false
       |  // Interrupt tick threads so they don't leak across reused JVMs
       |  // (each `_runActors` call has its own closure-captured state, so
       |  // an orphan thread would otherwise loop forever on stale refs).
       |  val rtt = _raftTickThread.getAndSet(null);   if rtt != null then rtt.interrupt()
       |  val ctt = _coordTickThread.getAndSet(null);  if ctt != null then ctt.interrupt()
       |  rootResult
       |""".stripMargin +
    """|
       |// ── v1.4 Logger effect ─────────────────────────────────────────────────────
       |//
       |// Logger.{info,warn,error,debug}  → _perform("Logger", op, msg)
       |// runLogger { body }              — "[LEVEL] msg" to stdout
       |// runLoggerJson { body }          — {"level":"…","msg":"…"} newline-JSON
       |// runLoggerToList { body }        — (result, List[(level, msg)])
       |
       |private def _loggerJsonStr(s: String): String =
       |  val sb = new StringBuilder("\"")
       |  s.foreach {
       |    case '"'  => sb.append("\\\"")
       |    case '\\' => sb.append("\\\\")
       |    case '\n' => sb.append("\\n")
       |    case '\r' => sb.append("\\r")
       |    case '\t' => sb.append("\\t")
       |    case c    => sb.append(c)
       |  }
       |  sb.append('"').toString
       |
       |def runLogger(bodyThunk: () => Any): Any =
       |  val ops = Set("Logger.info", "Logger.warn", "Logger.error", "Logger.debug")
       |  _handle(bodyThunk, ops, Map(
       |    "Logger.info"  -> { (args: List[Any]) => println(s"[INFO] ${args(0)}");  args.last.asInstanceOf[Any => Any](()) },
       |    "Logger.warn"  -> { (args: List[Any]) => println(s"[WARN] ${args(0)}");  args.last.asInstanceOf[Any => Any](()) },
       |    "Logger.error" -> { (args: List[Any]) => println(s"[ERROR] ${args(0)}"); args.last.asInstanceOf[Any => Any](()) },
       |    "Logger.debug" -> { (args: List[Any]) => println(s"[DEBUG] ${args(0)}"); args.last.asInstanceOf[Any => Any](()) },
       |  ))
       |
       |def runLoggerJson(bodyThunk: () => Any): Any =
       |  val ops = Set("Logger.info", "Logger.warn", "Logger.error", "Logger.debug")
       |  def fmt(level: String)(args: List[Any]): Any =
       |    println(s"{\"level\":\"$level\",\"msg\":${_loggerJsonStr(args(0).toString)}}")
       |    args.last.asInstanceOf[Any => Any](())
       |  _handle(bodyThunk, ops, Map(
       |    "Logger.info"  -> fmt("info"),
       |    "Logger.warn"  -> fmt("warn"),
       |    "Logger.error" -> fmt("error"),
       |    "Logger.debug" -> fmt("debug"),
       |  ))
       |
       |def runLoggerToList(bodyThunk: () => Any): Any =
       |  val log = scala.collection.mutable.ArrayBuffer.empty[(String, String)]
       |  def writeLog(level: String)(args: List[Any]): Any =
       |    log += (level -> args(0).toString)
       |    args.last.asInstanceOf[Any => Any](())
       |  val ops = Set("Logger.info", "Logger.warn", "Logger.error", "Logger.debug")
       |  val result = _handle(bodyThunk, ops, Map(
       |    "Logger.info"  -> writeLog("info"),
       |    "Logger.warn"  -> writeLog("warn"),
       |    "Logger.error" -> writeLog("error"),
       |    "Logger.debug" -> writeLog("debug"),
       |  ))
       |  (result, log.toList)
       |
       |// ── v1.4 Random effect ─────────────────────────────────────────────────────
       |//
       |// Random.{nextInt,nextDouble,uuid,pick}  → _perform("Random", op, args*)
       |// runRandom { body }          — java.util.Random (non-deterministic)
       |// runRandomSeeded(seed)(body) — deterministic seeded java.util.Random
       |
       |object Random:
       |  def nextInt(n: Any): Any  = _perform("Random", "nextInt",    n)
       |  def nextDouble(): Any     = _perform("Random", "nextDouble")
       |  def uuid(): Any           = _perform("Random", "uuid")
       |  def pick(xs: Any): Any    = _perform("Random", "pick",       xs)
       |
       |private def _randomHandlers(rng: java.util.Random): Map[String, List[Any] => Any] = Map(
       |  "Random.nextInt" -> { (args: List[Any]) =>
       |    val n = args(0).asInstanceOf[Int]
       |    args.last.asInstanceOf[Any => Any](rng.nextInt(if n > 0 then n else 1))
       |  },
       |  "Random.nextDouble" -> { (args: List[Any]) =>
       |    args.last.asInstanceOf[Any => Any](rng.nextDouble())
       |  },
       |  "Random.uuid" -> { (args: List[Any]) =>
       |    val bytes = new Array[Byte](16)
       |    rng.nextBytes(bytes)
       |    bytes(6) = ((bytes(6) & 0x0f) | 0x40).toByte
       |    bytes(8) = ((bytes(8) & 0x3f) | 0x80).toByte
       |    def hex(b: Byte) = f"${b & 0xff}%02x"
       |    val u = bytes.map(hex).mkString
       |    args.last.asInstanceOf[Any => Any](s"${u.take(8)}-${u.slice(8,12)}-${u.slice(12,16)}-${u.slice(16,20)}-${u.drop(20)}")
       |  },
       |  "Random.pick" -> { (args: List[Any]) =>
       |    val xs = args(0).asInstanceOf[List[Any]]
       |    args.last.asInstanceOf[Any => Any](xs(rng.nextInt(xs.size)))
       |  },
       |)
       |
       |def runRandom(bodyThunk: () => Any): Any =
       |  val ops = Set("Random.nextInt", "Random.nextDouble", "Random.uuid", "Random.pick")
       |  _handle(bodyThunk, ops, _randomHandlers(new java.util.Random()))
       |
       |def runRandomSeeded(seed: Any)(bodyThunk: () => Any): Any =
       |  val ops = Set("Random.nextInt", "Random.nextDouble", "Random.uuid", "Random.pick")
       |  val s = seed match
       |    case n: Long => n
       |    case n: Int  => n.toLong
       |    case _       => sys.error("runRandomSeeded(seed: Long)")
       |  _handle(bodyThunk, ops, _randomHandlers(new java.util.Random(s)))
       |
       |// ── v1.4 Clock effect ──────────────────────────────────────────────────────
       |//
       |// Clock.{now,nowIso,sleep}  → _perform("Clock", op, args*)
       |// runClock { body }         — real wall clock; sleep → Thread.sleep(ms)
       |// runClockAt(t0) { body }   — frozen at t0 ms since epoch; sleep is no-op
       |
       |object Clock:
       |  def now(): Any          = _perform("Clock", "now")
       |  def nowIso(): Any       = _perform("Clock", "nowIso")
       |  def sleep(ms: Any): Any = _perform("Clock", "sleep", ms)
       |
       |private def _clockHandlers(frozen: Option[Long]): Map[String, List[Any] => Any] =
       |  def nowMs()  = frozen.getOrElse(java.lang.System.currentTimeMillis())
       |  def nowIso() =
       |    java.time.format.DateTimeFormatter.ISO_INSTANT
       |      .format(java.time.Instant.ofEpochMilli(nowMs()))
       |  Map(
       |    "Clock.now"    -> { (args: List[Any]) => args.last.asInstanceOf[Any => Any](nowMs()) },
       |    "Clock.nowIso" -> { (args: List[Any]) => args.last.asInstanceOf[Any => Any](nowIso()) },
       |    "Clock.sleep"  -> { (args: List[Any]) =>
       |      val ms = args(0) match { case n: Long => n; case n: Int => n.toLong; case _ => 0L }
       |      if frozen.isEmpty && ms > 0 then Thread.sleep(ms)
       |      args.last.asInstanceOf[Any => Any](())
       |    },
       |  )
       |
       |def runClock(bodyThunk: () => Any): Any =
       |  val ops = Set("Clock.now", "Clock.nowIso", "Clock.sleep")
       |  _handle(bodyThunk, ops, _clockHandlers(None))
       |
       |def runClockAt(t0: Any)(bodyThunk: () => Any): Any =
       |  val ops = Set("Clock.now", "Clock.nowIso", "Clock.sleep")
       |  val frozen = t0 match
       |    case n: Long => n
       |    case n: Int  => n.toLong
       |    case _       => sys.error("runClockAt(t0: Long)")
       |  _handle(bodyThunk, ops, _clockHandlers(Some(frozen)))
       |
       |// ── v1.4 Env effect ────────────────────────────────────────────────────────
       |//
       |// Env.{get,set,required}  → _perform("Env", op, args*)
       |// runEnv { body }          — real process env; Env.set mutates local overlay
       |// runEnvWith(map) { body } — fixture map; Env.set mutates overlay
       |
       |object Env:
       |  def get(key: Any): Any             = _perform("Env", "get",      key)
       |  def set(key: Any, value: Any): Any = _perform("Env", "set",      key, value)
       |  def required(key: Any): Any        = _perform("Env", "required", key)
       |
       |private def _envHandlers(
       |  overlay: scala.collection.mutable.Map[String, String],
       |  useReal: Boolean
       |): Map[String, List[Any] => Any] =
       |  def lookup(k: String): Option[String] =
       |    overlay.get(k)
       |      .orElse(if useReal then Option(java.lang.System.getenv(k)).filter(_.nonEmpty) else None)
       |  Map(
       |    "Env.get" -> { (args: List[Any]) =>
       |      args.last.asInstanceOf[Any => Any](lookup(args(0).toString))
       |    },
       |    "Env.set" -> { (args: List[Any]) =>
       |      overlay(args(0).toString) = args(1).toString
       |      args.last.asInstanceOf[Any => Any](())
       |    },
       |    "Env.required" -> { (args: List[Any]) =>
       |      val k = args(0).toString
       |      lookup(k) match
       |        case Some(v) => args.last.asInstanceOf[Any => Any](v)
       |        case None    => sys.error(s"Env.required: key '$k' not found in environment")
       |    },
       |  )
       |
       |def runEnv(bodyThunk: () => Any): Any =
       |  val ops = Set("Env.get", "Env.set", "Env.required")
       |  _handle(bodyThunk, ops, _envHandlers(scala.collection.mutable.Map.empty, useReal = true))
       |
       |def runEnvWith(initMap: Any)(bodyThunk: () => Any): Any =
       |  val ops = Set("Env.get", "Env.set", "Env.required")
       |  val overlay = initMap match
       |    case m: Map[_, _] =>
       |      scala.collection.mutable.Map.from(m.asInstanceOf[Map[String, String]])
       |    case _ => sys.error("runEnvWith(map: Map[String, String])")
       |  _handle(bodyThunk, ops, _envHandlers(overlay, useReal = false))
       |
       |// ── v1.4 Http effect ──────────────────────────────────────────────────────
       |//
       |// Http.{get,post,request}  → _perform("Http", op, args*)
       |// runHttp { body }              — delegates to real _httpDoRequest
       |// runHttpStub(routes) { body }  — test stub: url→body Map
       |
       |object Http:
       |  def get(url: Any): Any                                   = _perform("Http", "get",     url)
       |  def post(url: Any, body: Any): Any                       = _perform("Http", "post",    url, body)
       |  def request(method: Any, url: Any, headers: Any, body: Any): Any =
       |    _perform("Http", "request", method, url, headers, body)
       |
       |private def _httpEffectHandlers(
       |  routes: Option[Map[String, String]]
       |): Map[String, List[Any] => Any] =
       |  def stubResponse(url: String): Any =
       |    routes match
       |      case Some(m) if m.contains(url) =>
       |        Map("status" -> 200, "headers" -> Map.empty, "body" -> m(url))
       |      case _ =>
       |        Map("status" -> 404, "headers" -> Map.empty, "body" -> "")
       |  def mkResponse(url: String, method: String, body: String,
       |                 headers: Map[String, String]): Any =
       |    routes.fold(_httpDoRequest(method, url, body, headers))(_ => stubResponse(url))
       |  Map(
       |    "Http.get" -> { (args: List[Any]) =>
       |      val url = args(0).toString
       |      args.last.asInstanceOf[Any => Any](mkResponse(url, "GET", "", Map.empty))
       |    },
       |    "Http.post" -> { (args: List[Any]) =>
       |      val url = args(0).toString; val body = args(1).toString
       |      args.last.asInstanceOf[Any => Any](mkResponse(url, "POST", body, Map.empty))
       |    },
       |    "Http.request" -> { (args: List[Any]) =>
       |      val method = args(0).toString; val url = args(1).toString
       |      val headers = args(2) match
       |        case m: Map[_, _] => m.asInstanceOf[Map[String, String]]
       |        case _            => Map.empty[String, String]
       |      val body = if args.size > 3 then args(3).toString else ""
       |      args.last.asInstanceOf[Any => Any](mkResponse(url, method, body, headers))
       |    },
       |  )
       |
       |def runHttp(bodyThunk: () => Any): Any =
       |  val ops = Set("Http.get", "Http.post", "Http.request")
       |  _handle(bodyThunk, ops, _httpEffectHandlers(None))
       |
       |def runHttpStub(routes: Any)(bodyThunk: () => Any): Any =
       |  val ops = Set("Http.get", "Http.post", "Http.request")
       |  val m = routes match
       |    case r: Map[_, _] => r.asInstanceOf[Map[String, String]]
       |    case _            => sys.error("runHttpStub(routes: Map[String, String])")
       |  _handle(bodyThunk, ops, _httpEffectHandlers(Some(m)))
       |
       |// ── v1.4 Retry effect ─────────────────────────────────────────────────────
       |//
       |// Retry.attempt(n, delayMs)(thunk)  — retries thunk up to n times on exception
       |// runRetry { body }        — real Thread.sleep between attempts
       |// runRetryNoSleep { body } — test handler: no sleep
       |
       |object Retry:
       |  def attempt(n: Any, delayMs: Any): Any => Any =
       |    (thunk: Any) => _perform("Retry", "attempt", n, delayMs, thunk)
       |
       |private def _retryHandlers(doSleep: Boolean): Map[String, List[Any] => Any] = Map(
       |  "Retry.attempt" -> { (args: List[Any]) =>
       |    val n = args(0) match { case i: Int => i.toLong; case l: Long => l; case _ => 0L }
       |    val delayMs = args(1) match { case i: Int => i.toLong; case l: Long => l; case _ => 0L }
       |    val thunk = args(2).asInstanceOf[() => Any]
       |    val resume = args.last.asInstanceOf[Any => Any]
       |    var lastErr: Throwable = null
       |    var result: Any = ()
       |    var attempt = 0
       |    var succeeded = false
       |    while attempt <= n && !succeeded do
       |      try { result = thunk(); succeeded = true }
       |      catch case e: Throwable =>
       |        lastErr = e; attempt += 1
       |        if attempt <= n && doSleep && delayMs > 0 then Thread.sleep(delayMs)
       |    if succeeded then resume(result) else throw lastErr
       |  },
       |)
       |
       |def runRetry(bodyThunk: () => Any): Any =
       |  val ops = Set("Retry.attempt")
       |  _handle(bodyThunk, ops, _retryHandlers(doSleep = true))
       |
       |def runRetryNoSleep(bodyThunk: () => Any): Any =
       |  val ops = Set("Retry.attempt")
       |  _handle(bodyThunk, ops, _retryHandlers(doSleep = false))
       |""".stripMargin +
    """|
       |// ── v1.4 Cache effect ─────────────────────────────────────────────────────
       |//
       |// Cache.memoize(key, ttlSeconds)(thunk)  — process-local TTL memoization
       |// runCache { body }        — uses module-level _cacheStore
       |// runCacheBypass { body }  — always recomputes; skips cache
       |
       |private val _cacheStore = new java.util.concurrent.ConcurrentHashMap[String, (Long, Any)]()
       |private val _cacheBypass = ThreadLocal.withInitial[Boolean](() => false)
       |
       |object Cache:
       |  def memoize(key: Any, ttlSeconds: Any): Any => Any =
       |    (thunk: Any) => _perform("Cache", "memoize", key, ttlSeconds, thunk)
       |
       |private def _cacheHandlers(bypass: Boolean): Map[String, List[Any] => Any] = Map(
       |  "Cache.memoize" -> { (args: List[Any]) =>
       |    val key = args(0).toString
       |    val ttlMs = (args(1) match
       |      case i: Int  => i.toLong
       |      case l: Long => l
       |      case _       => 0L
       |    ) * 1000L
       |    val thunk = args(2).asInstanceOf[() => Any]
       |    val resume = args.last.asInstanceOf[Any => Any]
       |    if bypass || _cacheBypass.get() then resume(thunk())
       |    else
       |      val nowMs = java.lang.System.currentTimeMillis()
       |      val cached = Option(_cacheStore.get(key))
       |      cached match
       |        case Some((expiry, v)) if nowMs < expiry => resume(v)
       |        case _ =>
       |          val v = thunk()
       |          _cacheStore.put(key, (nowMs + ttlMs, v))
       |          resume(v)
       |  },
       |)
       |
       |def runCache(bodyThunk: () => Any): Any =
       |  val prior = _cacheBypass.get()
       |  _cacheBypass.set(false)
       |  try _handle(bodyThunk, Set("Cache.memoize"), _cacheHandlers(false))
       |  finally _cacheBypass.set(prior)
       |
       |def runCacheBypass(bodyThunk: () => Any): Any =
       |  val prior = _cacheBypass.get()
       |  _cacheBypass.set(true)
       |  try _handle(bodyThunk, Set("Cache.memoize"), _cacheHandlers(true))
       |  finally _cacheBypass.set(prior)
       |
       |// ── v1.4 State effect ─────────────────────────────────────────────────────
       |//
       |// State.get              → _perform("State", "get")
       |// State.set(s)           → _perform("State", "set", s)
       |// State.modify(f)        → _perform("State", "modify", f)
       |// runState(s0) { body }  — returns (finalState, result)
       |
       |object State:
       |  def get(): Any          = _perform("State", "get")
       |  def set(s: Any): Any    = _perform("State", "set", s)
       |  def modify(f: Any): Any = _perform("State", "modify", f)
       |
       |def runState(s0: Any)(bodyThunk: () => Any): Any =
       |  var state: Any = s0
       |  val handlers: Map[String, List[Any] => Any] = Map(
       |    "State.get" -> { (args: List[Any]) =>
       |      args.last.asInstanceOf[Any => Any](state)
       |    },
       |    "State.set" -> { (args: List[Any]) =>
       |      state = args(0)
       |      args.last.asInstanceOf[Any => Any](())
       |    },
       |    "State.modify" -> { (args: List[Any]) =>
       |      state = args(0).asInstanceOf[Any => Any](state)
       |      args.last.asInstanceOf[Any => Any](())
       |    },
       |  )
       |  val ops = Set("State.get", "State.set", "State.modify")
       |  val result = _handle(bodyThunk, ops, handlers)
       |  (state, result)
       |
       |// ── v1.4 Tx effect ────────────────────────────────────────────────────────
       |//
       |// Tx.atomic { body }  — signals transactional scope; default is no-op
       |// runTx { body }      — default no-op handler (just runs body directly)
       |
       |object Tx:
       |  def atomic(thunk: () => Any): Any = thunk()
       |
       |def runTx(bodyThunk: () => Any): Any = bodyThunk()
       |
       |// ── v1.4 Auth effect ──────────────────────────────────────────────────────
       |//
       |// Auth.currentUser  — Option[Any] from thread-local
       |// Auth.require      — current user or throw RuntimeException
       |// runAuthWith(user) { body }  — injects a fixed user
       |
       |private val _authUser = ThreadLocal.withInitial[Option[Any]](() => None)
       |
       |object Auth:
       |  def currentUser: Any = _authUser.get()
       |  def require: Any = _authUser.get() match
       |    case Some(u) => u
       |    case None    => throw new RuntimeException("Auth.require: no authenticated user in context")
       |
       |def runAuthWith(user: Any)(bodyThunk: () => Any): Any =
       |  val prior = _authUser.get()
       |  _authUser.set(Some(user))
       |  try bodyThunk() finally _authUser.set(prior)
       |
       |// ── v1.51.6 Stream algebraic effect ────────────────────────────────────────
       |//
       |// Stream.emit(x)       — push to the active stream buffer
       |// Stream.complete()    — no-op (body ends naturally)
       |// Stream.error(msg)    — throw a RuntimeException
       |// Stream.request(n)    — advisory demand hint (no-op)
       |// runStream(bodyThunk) — discharges Stream effect; returns (_Source, Any)
       |//   where _Source.runToList() returns the emitted values.
       |//   Uses a module-level var buffer so Stream.emit is a direct side effect;
       |//   no CPS trampoline is needed, so while/var loops work inside the body.
       |//   ArrayBuffer repr: O(1) length, no bulk-copy after loop.
       |
       |class _Source(val _data: scala.collection.mutable.ArrayBuffer[Any]):
       |  def runToList(): scala.collection.mutable.ArrayBuffer[Any] = _data
       |  def toList: List[Any] = _data.toList
       |
       |private var _streamBuf: scala.collection.mutable.ArrayBuffer[Any] = null
       |
       |object Stream:
       |  def emit(x: Any): Any   = { if _streamBuf != null then _streamBuf += x; () }
       |  def complete(): Any      = ()
       |  def error(msg: Any): Any = throw new RuntimeException(String.valueOf(msg))
       |  def request(n: Any): Any = ()
       |
       |def runStream(bodyThunk: () => Any): Any =
       |  val buf = scala.collection.mutable.ArrayBuffer.empty[Any]
       |  _streamBuf = buf
       |  try
       |    val result = bodyThunk()
       |    (new _Source(buf), result)
       |  finally
       |    _streamBuf = null
       |
       |""".stripMargin

  /** Reactive runtime — same push-model as the interpreter and JsGen.
   *  Signals are mutable cells with a subscriber set; reads inside an
   *  active effect / computed register a mutual subscription; writes
   *  queue subscribers into a LinkedHashSet and a scheduled flush
   *  drains it so each effect runs at most once per synchronous
   *  transaction (dedupes the diamond). */
  private[codegen] val reactiveRuntime: String =
    """|
       |// ── Reactive signals (fine-grained reactivity) ─────────────────────
       |class _Signal(var value: Any, val subs: scala.collection.mutable.HashSet[Long])
       |class _Effect(val thunk: () => Any, val deps: scala.collection.mutable.HashSet[Long])
       |
       |val _signals = scala.collection.mutable.HashMap.empty[Long, _Signal]
       |val _effects = scala.collection.mutable.HashMap.empty[Long, _Effect]
       |var _reactiveSeq: Long = 0L
       |val _effectStack = scala.collection.mutable.Stack.empty[Long]
       |val _pendingEffects = scala.collection.mutable.LinkedHashSet.empty[Long]
       |var _reactiveFlushing = false
       |
       |def _freshReactiveId(): Long = { _reactiveSeq += 1; _reactiveSeq }
       |
       |def _signalGet(id: Long): Any =
       |  val s = _signals.getOrElse(id, sys.error("Signal disposed or unknown id"))
       |  if _effectStack.nonEmpty then
       |    val eid = _effectStack.top
       |    s.subs += eid
       |    _effects.get(eid).foreach(_.deps += id)
       |  s.value
       |
       |def _signalSet(id: Long, v: Any): Unit =
       |  val s = _signals.getOrElse(id, sys.error("Signal disposed or unknown id"))
       |  s.value = v
       |  // Skip subscribers currently running — otherwise an effect
       |  // that writes a signal it also reads infinite-loops itself.
       |  s.subs.foreach { eid =>
       |    if !_effectStack.contains(eid) then _pendingEffects += eid
       |  }
       |  if !_reactiveFlushing then _reactiveFlush()
       |
       |def _reactiveFlush(): Unit =
       |  _reactiveFlushing = true
       |  try
       |    while _pendingEffects.nonEmpty do
       |      val eid = _pendingEffects.head
       |      _pendingEffects -= eid
       |      _runEffect(eid)
       |  finally _reactiveFlushing = false
       |
       |def _clearEffectDeps(eid: Long): Unit =
       |  _effects.get(eid).foreach { e =>
       |    e.deps.foreach { sid => _signals.get(sid).foreach(_.subs -= eid) }
       |    e.deps.clear()
       |  }
       |
       |def _runEffect(eid: Long): Unit =
       |  _effects.get(eid).foreach { e =>
       |    _clearEffectDeps(eid)
       |    _effectStack.push(eid)
       |    try e.thunk()
       |    finally _effectStack.pop()
       |  }
       |
       |/** User-visible Signal handle — parameterised on the value type
       | *  so callers get back `count.get: Int` instead of `Any` and the
       | *  Scala typer resolves arithmetic (`count.get * 2`) cleanly. */
       |class Signal[A](val id: Long):
       |  def get: A           = _signalGet(id).asInstanceOf[A]
       |  def set(v: A): Unit  = _signalSet(id, v)
       |  def apply(): A       = get
       |  override def toString: String = s"Signal(${get})"
       |object Signal:
       |  def apply[A](initial: A): Signal[A] =
       |    val id = _freshReactiveId()
       |    _signals(id) = _Signal(initial, scala.collection.mutable.HashSet.empty)
       |    new Signal[A](id)
       |
       |def effect(thunk: => Any): Unit =
       |  val eid = _freshReactiveId()
       |  _effects(eid) = _Effect(() => thunk, scala.collection.mutable.HashSet.empty)
       |  _runEffect(eid)
       |
       |def computed[A](thunk: => A): Signal[A] =
       |  val sid = _freshReactiveId()
       |  val eid = _freshReactiveId()
       |  _signals(sid) = _Signal(null, scala.collection.mutable.HashSet.empty)
       |  val updater: () => Any = () => _signalSet(sid, thunk)
       |  _effects(eid) = _Effect(updater, scala.collection.mutable.HashSet.empty)
       |  _runEffect(eid)
       |  new Signal[A](sid)
       |
       |""".stripMargin

