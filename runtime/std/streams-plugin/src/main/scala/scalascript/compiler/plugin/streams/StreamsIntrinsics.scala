package scalascript.compiler.plugin.streams

import scalascript.backend.spi.*
import scalascript.frontend.ReactiveSignal
import scalascript.ir.QualifiedName
import scalascript.interpreter.{Value, InterpretError, Computation}

import scala.collection.mutable.{ListBuffer, LinkedHashMap}
import scalascript.plugin.api.PluginNative
import scalascript.plugin.api.PluginContext

/** Backpressured stream intrinsics for the tree-walking interpreter.
 *
 *  `stream { emit(x) }` uses an `ArrayBlockingQueue(16)` (not the rendezvous
 *  `SynchronousQueue` that generators use), giving the producer a 16-element
 *  head-start before it parks. `emit` is routed via a ThreadLocal so nested
 *  stream bodies each target their own queue. */
object StreamsIntrinsics:

  private type Queue = java.util.concurrent.ArrayBlockingQueue[Option[Value]]
  private val _emitQueueTL = new ThreadLocal[Queue]()

  // NativeImpl receives unwrapped primitives (Long, Double, String, Boolean, Unit);
  // wrap them back to Value so they can be stored in the stream queue.
  private def toValue(a: Any): Value = a match
    case n: Long    => Value.intV(n)
    case i: Int     => Value.intV(i.toLong)
    case d: Double  => Value.doubleV(d)
    case s: String  => Value.StringV(s)
    case b: Boolean => Value.boolV(b)
    case ()         => Value.UnitV
    case v: Value   => v
    case _          => Value.StringV(a.toString)

  private def toHostAny(v: Value): Any = v match
    case Value.IntV(n)    => n.toInt
    case Value.DoubleV(d) => d
    case Value.StringV(s) => s
    case Value.BoolV(b)   => b
    case Value.UnitV      => ()
    case other            => other

  private def newQ(): Queue = new Queue(16)

  private def sourceFromValues(values: List[Value], ctx: PluginContext): Value =
    val queue = newQ()
    Thread.ofVirtual().start { () =>
      try for v <- values do queue.put(Some(v))
      catch case _: Throwable => ()
      finally try queue.put(None) catch case _ => ()
    }
    makeSourceV(queue, ctx)

  private def sourceFromReactiveSignal(signal: ReactiveSignal[?], ctx: PluginContext): Value =
    val queue = newQ()
    queue.put(Some(toValue(signal.apply())))
    signal.asInstanceOf[ReactiveSignal[Any]].subscribe { value =>
      queue.put(Some(toValue(value)))
    }
    makeSourceV(queue, ctx)

  private def drain(queue: Queue): List[Value] =
    val buf = ListBuffer[Value]()
    var item = queue.take()
    while item.isDefined do
      buf += item.get
      item = queue.take()
    buf.toList

  private def strategyName(v: Value): String = v match
    case Value.StringV(s) => s
    case Value.InstanceV(name, _) => name.split('.').lastOption.getOrElse(name)
    case other => Value.show(other)

  private def bufferedValues(values: List[Value], capacity: Int, strategy: Value): List[Value] =
    if capacity < 0 then throw InterpretError("Source.buffer: capacity must be >= 0")
    strategyName(strategy) match
      case "Backpressure" | "Block" =>
        values
      case "Drop" | "DropNew" =>
        values.take(capacity)
      case "DropHead" | "DropOldest" =>
        values.takeRight(capacity)
      case "Fail" =>
        if values.length > capacity then throw InterpretError("Source.buffer: buffer overflow")
        values
      case other =>
        throw InterpretError(s"Source.buffer: unknown overflow strategy $other")

  private def sourceValue(v: Value): Value = v match
    case Value.InstanceV(_, fields) =>
      fields.get("value")
        .orElse(fields.get("current"))
        .orElse(fields.get("initial"))
        .orElse(fields.get("default"))
        .getOrElse(v)
    case other => other

  private def intArg(v: Any): Option[Int] = v match
    case Value.IntV(n) => Some(n.toInt)
    case n: Long      => Some(n.toInt)
    case n: Int       => Some(n)
    case _            => None

  private def positiveMillis(raw: Value, op: String): Long = raw match
    case Value.IntV(n) if n >= 0 => n
    case Value.IntV(_)           => throw InterpretError(s"Source.$op: duration must be >= 0")
    case other                   => intArg(other).map(_.toLong).filter(_ >= 0).getOrElse {
      throw InterpretError(s"Source.$op(durationMillis)")
    }

  private def rateArgs(rate: Value): (Int, Long) = rate match
    case Value.InstanceV("Rate", fields) =>
      val elements = fields.get("elements").collect { case Value.IntV(n) => n.toInt }.getOrElse(0)
      val perMillis = fields.get("perMillis").collect { case Value.IntV(n) => n }.getOrElse(1000L)
      if elements <= 0 then throw InterpretError("Source.throttle: rate elements must be > 0")
      if perMillis < 0 then throw InterpretError("Source.throttle: rate perMillis must be >= 0")
      (elements, perMillis)
    case raw if intArg(raw).isDefined =>
      val elements = intArg(raw).get
      if elements <= 0 then throw InterpretError("Source.throttle: rate elements must be > 0")
      (elements, 1000L)
    case _ => throw InterpretError("Source.throttle(rate)")

  private def sleepMillis(ms: Long): Unit =
    if ms > 0 then Thread.sleep(ms)

  private def bindSourceToSignal(source: Value.InstanceV, signal: ReactiveSignal[Any], ctx: PluginContext): Value =
    val runForeach = source.fields.getOrElse("runForeach", throw InterpretError("signal.bind(source): not a Source"))
    val setter = Value.NativeFnV("ReactiveSignal.bind.set", Computation.pureFn {
      case List(v) => signal.set(toHostAny(v)); Value.UnitV
      case _       => Value.UnitV
    })
    Thread.ofVirtual().start { () =>
      try ctx.synchronized { ctx.invokeCallback(runForeach, List(setter)) }
      catch case _: Throwable => ()
    }
    Value.UnitV

  private def makeSourceV(queue: Queue, ctx: PluginContext): Value =

    def call(f: Value, args: List[Value]): Value =
      ctx.synchronized { ctx.invokeCallback(f, args).asInstanceOf[Value] }

    def startChained(bodyFn: Queue => Unit): Value =
      val q2 = newQ()
      Thread.ofVirtual().start { () =>
        try bodyFn(q2)
        catch case _: Throwable => ()
        finally try q2.put(None) catch case _ => ()
      }
      makeSourceV(q2, ctx)

    Value.InstanceV("Source", Map(

      "map" -> Value.NativeFnV("Source.map", Computation.pureFn {
        case List(f) => startChained { ownQ =>
          var item = queue.take()
          while item.isDefined do
            ownQ.put(Some(call(f, List(item.get))))
            item = queue.take()
        }
        case _ => throw InterpretError("Source.map(f)")
      }),

      "filter" -> Value.NativeFnV("Source.filter", Computation.pureFn {
        case List(pred) => startChained { ownQ =>
          var item = queue.take()
          while item.isDefined do
            if call(pred, List(item.get)) == Value.True then ownQ.put(Some(item.get))
            item = queue.take()
        }
        case _ => throw InterpretError("Source.filter(pred)")
      }),

      "take" -> Value.NativeFnV("Source.take", Computation.pureFn {
        case List(Value.IntV(n)) => startChained { ownQ =>
          var remaining = n
          var item = queue.take()
          while item.isDefined && remaining > 0 do
            ownQ.put(Some(item.get))
            remaining -= 1
            item = if remaining > 0 then queue.take() else None
        }
        case _ => throw InterpretError("Source.take(n: Int)")
      }),

      "drop" -> Value.NativeFnV("Source.drop", Computation.pureFn {
        case List(Value.IntV(n)) => startChained { ownQ =>
          var toDrop = n.toInt
          var item = queue.take()
          while item.isDefined && toDrop > 0 do
            toDrop -= 1
            item = queue.take()
          while item.isDefined do
            ownQ.put(Some(item.get))
            item = queue.take()
        }
        case _ => throw InterpretError("Source.drop(n: Int)")
      }),

      "flatMap" -> Value.NativeFnV("Source.flatMap", Computation.pureFn {
        case List(f) => startChained { ownQ =>
          val forward = Value.NativeFnV("_fwdEmit", Computation.pureFn {
            case List(x) => ownQ.put(Some(x)); Value.UnitV
            case _       => Value.UnitV
          })
          var item = queue.take()
          while item.isDefined do
            call(f, List(item.get)) match
              case inner: Value.InstanceV =>
                inner.fields.get("runForeach") match
                  case Some(rf) => call(rf, List(forward))
                  case None     => throw InterpretError("Source.flatMap: body must return a Source")
              case _ => throw InterpretError("Source.flatMap: body must return a Source")
            item = queue.take()
        }
        case _ => throw InterpretError("Source.flatMap(f)")
      }),

      "concat" -> Value.NativeFnV("Source.concat", Computation.pureFn {
        case List(other) => startChained { ownQ =>
          var item = queue.take()
          while item.isDefined do
            ownQ.put(Some(item.get))
            item = queue.take()
          val forward = Value.NativeFnV("_fwdEmit", Computation.pureFn {
            case List(x) => ownQ.put(Some(x)); Value.UnitV
            case _       => Value.UnitV
          })
          other match
            case inst: Value.InstanceV =>
              inst.fields.get("runForeach") match
                case Some(rf) => call(rf, List(forward))
                case None     => throw InterpretError("Source.concat(other: Source)")
            case _ => throw InterpretError("Source.concat(other: Source)")
        }
        case _ => throw InterpretError("Source.concat(other: Source)")
      }),

      "zip" -> Value.NativeFnV("Source.zip", Computation.pureFn {
        case List(other) => startChained { ownQ =>
          val otherList = other match
            case inst: Value.InstanceV =>
              inst.fields.get("runToList") match
                case Some(rtl) =>
                  call(rtl, Nil) match
                    case Value.ListV(xs) => xs
                    case _ => Nil
                case None => Nil
            case _ => Nil
          val it = otherList.iterator
          var item = queue.take()
          while item.isDefined && it.hasNext do
            ownQ.put(Some(Value.TupleV(List(item.get, it.next()))))
            item = queue.take()
        }
        case _ => throw InterpretError("Source.zip(other: Source)")
      }),

      // ── v1.51.3 combining operators ──────────────────────────────────────

      "merge" -> Value.NativeFnV("Source.merge", Computation.pureFn {
        case List(other) => startChained { ownQ =>
          var item = queue.take()
          while item.isDefined do
            ownQ.put(Some(item.get))
            item = queue.take()
          val forward = Value.NativeFnV("_fwdEmit", Computation.pureFn {
            case List(x) => ownQ.put(Some(x)); Value.UnitV
            case _       => Value.UnitV
          })
          other match
            case inst: Value.InstanceV =>
              inst.fields.get("runForeach") match
                case Some(rf) => call(rf, List(forward))
                case None     => throw InterpretError("Source.merge: not a Source")
            case _ => throw InterpretError("Source.merge: not a Source")
        }
        case _ => throw InterpretError("Source.merge(other: Source)")
      }),

      "zipWith" -> Value.NativeFnV("Source.zipWith", Computation.pureFn {
        case List(other) => Value.NativeFnV("Source.zipWith$1", Computation.pureFn {
          case List(f) => startChained { ownQ =>
            val otherList = other match
              case inst: Value.InstanceV =>
                inst.fields.get("runToList") match
                  case Some(rtl) =>
                    call(rtl, Nil) match
                      case Value.ListV(xs) => xs
                      case _ => Nil
                  case None => Nil
              case _ => Nil
            val it = otherList.iterator
            var item = queue.take()
            while item.isDefined && it.hasNext do
              ownQ.put(Some(call(f, List(item.get, it.next()))))
              item = queue.take()
          }
          case _ => throw InterpretError("Source.zipWith(other)(f) — inner")
        })
        case _ => throw InterpretError("Source.zipWith(other)(f) — outer")
      }),

      "broadcast" -> Value.NativeFnV("Source.broadcast", Computation.pureFn {
        case List(Value.IntV(n)) =>
          val ni = n.toInt
          val buf = ListBuffer[Value]()
          var item = queue.take()
          while item.isDefined do
            buf += item.get
            item = queue.take()
          val all = buf.toList
          Value.ListV(
            (0 until ni).toList.map { _ =>
              val q = newQ()
              Thread.ofVirtual().start { () =>
                try for v <- all do q.put(Some(v))
                catch case _: Throwable => ()
                finally try q.put(None) catch case _ => ()
              }
              makeSourceV(q, ctx)
            }
          )
        case _ => throw InterpretError("Source.broadcast(n: Int)")
      }),

      "balance" -> Value.NativeFnV("Source.balance", Computation.pureFn {
        case List(Value.IntV(n)) =>
          val ni = n.toInt
          val buf = ListBuffer[Value]()
          var item = queue.take()
          while item.isDefined do
            buf += item.get
            item = queue.take()
          val all = buf.toList
          Value.ListV(
            (0 until ni).toList.map { i =>
              val slice = (i until all.length by ni).map(all(_)).toList
              val q = newQ()
              Thread.ofVirtual().start { () =>
                try for v <- slice do q.put(Some(v))
                catch case _: Throwable => ()
                finally try q.put(None) catch case _ => ()
              }
              makeSourceV(q, ctx)
            }
          )
        case _ => throw InterpretError("Source.balance(n: Int)")
      }),

      "groupBy" -> Value.NativeFnV("Source.groupBy", Computation.pureFn {
        case List(keyFn) => startChained { ownQ =>
          val groups = LinkedHashMap[Value, ListBuffer[Value]]()
          var item = queue.take()
          while item.isDefined do
            val key = call(keyFn, List(item.get))
            groups.getOrElseUpdate(key, ListBuffer()) += item.get
            item = queue.take()
          for (k, vs) <- groups do
            val elems = vs.toList
            val q = newQ()
            Thread.ofVirtual().start { () =>
              try for v <- elems do q.put(Some(v))
              catch case _: Throwable => ()
              finally try q.put(None) catch case _ => ()
            }
            ownQ.put(Some(Value.TupleV(List(k, makeSourceV(q, ctx)))))
        }
        case _ => throw InterpretError("Source.groupBy(keyFn)")
      }),

      "mergeSubstreams" -> Value.NativeFnV("Source.mergeSubstreams", Computation.pureFn { _ =>
        startChained { ownQ =>
          val forward = Value.NativeFnV("_fwdEmit", Computation.pureFn {
            case List(x) => ownQ.put(Some(x)); Value.UnitV
            case _       => Value.UnitV
          })
          var item = queue.take()
          while item.isDefined do
            item.get match
              case inner: Value.InstanceV =>
                inner.fields.get("runForeach") match
                  case Some(rf) => call(rf, List(forward))
                  case None     => ()
              case _ => ()
            item = queue.take()
        }
      }),

      // ── v1.51.5 buffer/time/signal operators ────────────────────────────

      "buffer" -> Value.NativeFnV("Source.buffer", Computation.pureFn {
        case List(n, strategy: Value) if intArg(n).isDefined =>
          sourceFromValues(bufferedValues(drain(queue), intArg(n).get, strategy), ctx)
        case List(n) if intArg(n).isDefined =>
          val capacity = intArg(n).get
          Value.NativeFnV("Source.buffer$1", Computation.pureFn {
            case List(strategy) => sourceFromValues(bufferedValues(drain(queue), capacity, strategy), ctx)
            case _              => throw InterpretError("Source.buffer(n)(strategy)")
          })
        case _ => throw InterpretError("Source.buffer(n, strategy)")
      }),

      "throttle" -> Value.NativeFnV("Source.throttle", Computation.pureFn {
        case List(rate) =>
          val (elements, perMillis) = rateArgs(rate)
          startChained { ownQ =>
            var emittedInWindow = 0
            var windowStartNs = System.nanoTime()
            var item = queue.take()
            while item.isDefined do
              if emittedInWindow >= elements then
                val elapsedMs = (System.nanoTime() - windowStartNs) / 1000000L
                sleepMillis(math.max(0L, perMillis - elapsedMs))
                windowStartNs = System.nanoTime()
                emittedInWindow = 0
              ownQ.put(Some(item.get))
              emittedInWindow += 1
              item = queue.take()
          }
        case _ => throw InterpretError("Source.throttle(rate)")
      }),

      "debounce" -> Value.NativeFnV("Source.debounce", Computation.pureFn {
        case List(duration) =>
          val durationMillis = positiveMillis(duration, "debounce")
          val values = drain(queue)
          if values.nonEmpty then sleepMillis(durationMillis)
          sourceFromValues(values.lastOption.toList, ctx)
        case _ => throw InterpretError("Source.debounce(duration)")
      }),

      // ── v1.51.3 Sink + Flow routing ──────────────────────────────────────

      "to" -> Value.NativeFnV("Source.to", Computation.pureFn {
        case List(sink: Value.InstanceV) =>
          sink.fields.get("run") match
            case Some(runFn) =>
              val selfSrc = makeSourceV(queue, ctx)
              call(runFn, List(selfSrc))
            case None => throw InterpretError("Source.to: not a Sink")
        case _ => throw InterpretError("Source.to(sink)")
      }),

      "via" -> Value.NativeFnV("Source.via", Computation.pureFn {
        case List(flow: Value.InstanceV) =>
          flow.fields.get("apply") match
            case Some(applyFn) =>
              val selfSrc = makeSourceV(queue, ctx)
              call(applyFn, List(selfSrc))
            case None => throw InterpretError("Source.via: not a Flow")
        case _ => throw InterpretError("Source.via(flow)")
      }),

      "runForeach" -> Value.NativeFnV("Source.runForeach", Computation.pureFn {
        case List(f) =>
          var item = queue.take()
          while item.isDefined do
            call(f, List(item.get))
            item = queue.take()
          Value.UnitV
        case _ => throw InterpretError("Source.runForeach(f)")
      }),

      "runFold" -> Value.NativeFnV("Source.runFold", Computation.pureFn {
        case List(z) => Value.NativeFnV("Source.runFold$1", Computation.pureFn {
          case List(f) =>
            var acc = z
            var item = queue.take()
            while item.isDefined do
              acc = call(f, List(acc, item.get))
              item = queue.take()
            acc
          case _ => throw InterpretError("Source.runFold(z)(f) — inner")
        })
        case _ => throw InterpretError("Source.runFold(z)(f) — outer")
      }),

      "runToList" -> Value.NativeFnV("Source.runToList", Computation.pureFn { _ =>
        val buf = ListBuffer[Value]()
        var item = queue.take()
        while item.isDefined do
          buf += item.get
          item = queue.take()
        Value.ListV(buf.toList)
      }),

      "runDrain" -> Value.NativeFnV("Source.runDrain", Computation.pureFn { _ =>
        var item = queue.take()
        while item.isDefined do item = queue.take()
        Value.UnitV
      }),

      // ── v1.51.4 async + error operators ─────────────────────────────────

      "mapAsync" -> Value.NativeFnV("Source.mapAsync", Computation.pureFn {
        case List(Value.IntV(n)) => Value.NativeFnV("Source.mapAsync$1", Computation.pureFn {
          case List(f) => startChained { ownQ =>
            val sem     = new java.util.concurrent.Semaphore((n.toInt) max 1)
            val all     = drain(queue)
            val results = new java.util.concurrent.ConcurrentHashMap[Int, Value]()
            val latch   = new java.util.concurrent.CountDownLatch(all.size)
            all.zipWithIndex.foreach { (v, i) =>
              Thread.ofVirtual().start { () =>
                sem.acquire()
                try results.put(i, call(f, List(v)))
                catch case _: Throwable => ()
                finally
                  sem.release()
                  latch.countDown()
              }
            }
            latch.await()
            for i <- all.indices do
              Option(results.get(i)).foreach(r => ownQ.put(Some(r)))
          }
          case _ => throw InterpretError("Source.mapAsync(n)(f) — inner")
        })
        case _ => throw InterpretError("Source.mapAsync(n)(f) — outer")
      }),

      "recover" -> Value.NativeFnV("Source.recover", Computation.pureFn {
        case List(handler) =>
          val q2 = newQ()
          Thread.ofVirtual().start { () =>
            try
              var item = queue.take()
              while item.isDefined do
                q2.put(Some(item.get))
                item = queue.take()
            catch case e: Throwable =>
              try
                val msg = Option(e.getMessage).getOrElse(e.getClass.getName)
                q2.put(Some(call(handler, List(Value.StringV(msg)))))
              catch case _ => ()
            finally try q2.put(None) catch case _ => ()
          }
          makeSourceV(q2, ctx)
        case _ => throw InterpretError("Source.recover(handler)")
      }),

      "mapError" -> Value.NativeFnV("Source.mapError", Computation.pureFn {
        case List(f) =>
          val q2 = newQ()
          Thread.ofVirtual().start { () =>
            try
              var item = queue.take()
              while item.isDefined do
                q2.put(Some(item.get))
                item = queue.take()
            catch case e: Throwable =>
              try
                val msg = Option(e.getMessage).getOrElse(e.getClass.getName)
                call(f, List(Value.StringV(msg)))
              catch case _ => ()
            finally try q2.put(None) catch case _ => ()
          }
          makeSourceV(q2, ctx)
        case _ => throw InterpretError("Source.mapError(f)")
      }),

      // ── v1.51.1 scan / onError / cancellable ─────────────────────────────

      "scan" -> Value.NativeFnV("Source.scan", Computation.pureFn {
        case List(z) => Value.NativeFnV("Source.scan$1", Computation.pureFn {
          case List(f) => startChained { ownQ =>
            var acc: Value = toValue(z)
            var item = queue.take()
            while item.isDefined do
              acc = call(f, List(acc, item.get))
              ownQ.put(Some(acc))
              item = queue.take()
          }
          case _ => throw InterpretError("Source.scan(z)(f) — inner")
        })
        case _ => throw InterpretError("Source.scan(z)(f) — outer")
      }),

      "onError" -> Value.NativeFnV("Source.onError", Computation.pureFn {
        case List(handler) =>
          val q2 = newQ()
          Thread.ofVirtual().start { () =>
            try
              var item = queue.take()
              while item.isDefined do
                q2.put(Some(item.get))
                item = queue.take()
            catch case e: Throwable =>
              try
                val msg = Option(e.getMessage).getOrElse(e.getClass.getName)
                call(handler, List(Value.StringV(msg)))
              catch case _ => ()
            finally try q2.put(None) catch case _ => ()
          }
          makeSourceV(q2, ctx)
        case _ => throw InterpretError("Source.onError(handler)")
      }),

      "cancellable" -> Value.NativeFnV("Source.cancellable", Computation.pureFn { _ =>
        val cancelled = new java.util.concurrent.atomic.AtomicBoolean(false)
        val q2 = newQ()
        Thread.ofVirtual().start { () =>
          try
            var item = queue.take()
            while item.isDefined && !cancelled.get() do
              q2.put(Some(item.get))
              item = if !cancelled.get() then queue.take() else None
          catch case _: Throwable => ()
          finally try q2.put(None) catch case _ => ()
        }
        val cancelFn = Value.NativeFnV("Source.cancel", Computation.pureFn { _ =>
          cancelled.set(true)
          Value.UnitV
        })
        Value.TupleV(List(makeSourceV(q2, ctx), cancelFn))
      }),
    ))

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    // stream { body } — body runs on a VT; emit(x) blocks when the 16-element buffer is full.
    QualifiedName("stream") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(body: Value) =>
          val queue = newQ()
          Thread.ofVirtual().start { () =>
            val prev = _emitQueueTL.get()
            _emitQueueTL.set(queue)
            try ctx.invokeCallback(body, Nil)
            catch case _: Throwable => ()
            finally
              try queue.put(None) catch case _ => ()
              if prev == null then _emitQueueTL.remove() else _emitQueueTL.set(prev)
          }
          makeSourceV(queue, ctx)
        case _ => throw InterpretError("stream(body: () => Unit)")
    },

    // emit(x) — puts one element into the current stream's queue.
    QualifiedName("emit") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(v) =>
          val q = _emitQueueTL.get()
          if q == null then throw InterpretError("emit called outside a stream body")
          q.put(Some(toValue(v)))
          Value.UnitV
        case _ => throw InterpretError("emit(value)")
    },

    // Source.from(iterable) — wraps a List/range as a Source.
    QualifiedName("Source.from") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(Value.ListV(items)) =>
          val queue = newQ()
          Thread.ofVirtual().start { () =>
            try for item <- items do queue.put(Some(item))
            catch case _: Throwable => ()
            finally try queue.put(None) catch case _ => ()
          }
          makeSourceV(queue, ctx)
        case _ => throw InterpretError("Source.from(iterable)")
    },

    // Source.single(x) — one-element source.
    QualifiedName("Source.single") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(v) =>
          val queue = newQ()
          val vv = toValue(v)
          Thread.ofVirtual().start { () =>
            try queue.put(Some(vv))
            catch case _: Throwable => ()
            finally try queue.put(None) catch case _ => ()
          }
          makeSourceV(queue, ctx)
        case _ => throw InterpretError("Source.single(x)")
    },

    // Source.empty — completes immediately.
    QualifiedName("Source.empty") -> PluginNative.evalLegacy { (ctx, _) =>
      val queue = newQ()
      queue.put(None)
      makeSourceV(queue, ctx)
    },

    // Source.fromGenerator(gen) — wraps a Generator[T] as a Source.
    QualifiedName("Source.fromGenerator") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(gen: Value.InstanceV) =>
          val queue = newQ()
          val nextFn = gen.fields.getOrElse("next",
            throw InterpretError("Source.fromGenerator: not a Generator"))
          Thread.ofVirtual().start { () =>
            try
              var step = ctx.invokeCallback(nextFn, Nil).asInstanceOf[Value]
              while step != Value.NoneV do
                step match
                  case Value.OptionV(Some(v)) => queue.put(Some(v))
                  case _ =>
                step = ctx.invokeCallback(nextFn, Nil).asInstanceOf[Value]
            catch case _: Throwable => ()
            finally try queue.put(None) catch case _ => ()
          }
          makeSourceV(queue, ctx)
        case _ => throw InterpretError("Source.fromGenerator(gen: Generator)")
    },

    QualifiedName("Source.signal") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(Value.Foreign("ReactiveSignal", signal: ReactiveSignal[?])) =>
          sourceFromReactiveSignal(signal, ctx)
        case List(sig: Value) => sourceFromValues(List(sourceValue(sig)), ctx)
        case List(sig)        => sourceFromValues(List(toValue(sig)), ctx)
        case _ => throw InterpretError("Source.signal(sig)")
    },

    QualifiedName("ReactiveSignal.bind") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(Value.Foreign("ReactiveSignal", signal: ReactiveSignal[?]), source: Value.InstanceV) =>
          bindSourceToSignal(source, signal.asInstanceOf[ReactiveSignal[Any]], ctx)
        case _ => throw InterpretError("ReactiveSignal.bind(signal, source)")
    },

    QualifiedName("Rate") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(elements, perMillis) if intArg(elements).isDefined && intArg(perMillis).isDefined =>
          Value.InstanceV("Rate", Map(
            "elements"  -> Value.intV(intArg(elements).get.toLong),
            "perMillis" -> Value.intV(intArg(perMillis).get.toLong),
          ))
        case List(elements) if intArg(elements).isDefined =>
          Value.InstanceV("Rate", Map("elements" -> Value.intV(intArg(elements).get.toLong), "perMillis" -> Value.intV(1000L)))
        case _ => throw InterpretError("Rate(elements, perMillis)")
    },

    QualifiedName("OverflowStrategy.Backpressure") -> PluginNative.evalLegacy { (_, _) =>
      Value.InstanceV("OverflowStrategy.Backpressure", Map.empty)
    },
    QualifiedName("OverflowStrategy.Block") -> PluginNative.evalLegacy { (_, _) =>
      Value.InstanceV("OverflowStrategy.Block", Map.empty)
    },
    QualifiedName("OverflowStrategy.Drop") -> PluginNative.evalLegacy { (_, _) =>
      Value.InstanceV("OverflowStrategy.Drop", Map.empty)
    },
    QualifiedName("OverflowStrategy.DropHead") -> PluginNative.evalLegacy { (_, _) =>
      Value.InstanceV("OverflowStrategy.DropHead", Map.empty)
    },
    QualifiedName("OverflowStrategy.DropOldest") -> PluginNative.evalLegacy { (_, _) =>
      Value.InstanceV("OverflowStrategy.DropOldest", Map.empty)
    },
    QualifiedName("OverflowStrategy.Fail") -> PluginNative.evalLegacy { (_, _) =>
      Value.InstanceV("OverflowStrategy.Fail", Map.empty)
    },

    // ── v1.51.4 SSE / WebSocket / bracket source-sink intrinsics ───────────

    QualifiedName("Source.bracket") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(acquire) =>
          Value.NativeFnV("Source.bracket$1", Computation.pureFn {
            case List(release) => Value.NativeFnV("Source.bracket$2", Computation.pureFn {
              case List(use) =>
                val resource = ctx.synchronized { ctx.invokeCallback(acquire, Nil).asInstanceOf[Value] }
                val q = newQ()
                Thread.ofVirtual().start { () =>
                  try
                    val src = ctx.synchronized { ctx.invokeCallback(use, List(resource)).asInstanceOf[Value] }
                    src match
                      case inst: Value.InstanceV =>
                        val forward = Value.NativeFnV("_fwdEmit", Computation.pureFn {
                          case List(x) => q.put(Some(x)); Value.UnitV
                          case _       => Value.UnitV
                        })
                        inst.fields.get("runForeach") match
                          case Some(rf) => ctx.synchronized { ctx.invokeCallback(rf, List(forward)) }
                          case None     => ()
                      case _ => ()
                  catch case _: Throwable => ()
                  finally
                    try ctx.synchronized { ctx.invokeCallback(release, List(resource)) } catch case _ => ()
                    try q.put(None) catch case _ => ()
                }
                makeSourceV(q, ctx)
              case _ => throw InterpretError("Source.bracket(acquire)(release)(use) — use")
            })
            case _ => throw InterpretError("Source.bracket(acquire)(release)(use) — release")
          })
        case _ => throw InterpretError("Source.bracket(acquire)(release)(use) — acquire")
    },

    QualifiedName("Source.fromSse") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(url: String) =>
          val q = newQ()
          Thread.ofVirtual().start { () =>
            try
              val client = java.net.http.HttpClient.newHttpClient()
              val req    = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("Accept", "text/event-stream")
                .build()
              val resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofLines())
              val it   = resp.body().iterator()
              while it.hasNext do
                val line = it.next()
                if line.startsWith("data:") then q.put(Some(Value.StringV(line.drop(5).trim)))
            catch case _: Throwable => ()
            finally try q.put(None) catch case _ => ()
          }
          makeSourceV(q, ctx)
        case List(url: Value.StringV) =>
          val q = newQ()
          Thread.ofVirtual().start { () =>
            try
              val client = java.net.http.HttpClient.newHttpClient()
              val req    = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url.v))
                .header("Accept", "text/event-stream")
                .build()
              val resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofLines())
              val it   = resp.body().iterator()
              while it.hasNext do
                val line = it.next()
                if line.startsWith("data:") then q.put(Some(Value.StringV(line.drop(5).trim)))
            catch case _: Throwable => ()
            finally try q.put(None) catch case _ => ()
          }
          makeSourceV(q, ctx)
        case _ => throw InterpretError("Source.fromSse(url)")
    },

    QualifiedName("Source.fromWebSocket") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(url: String) =>
          val q     = newQ()
          val latch = new java.util.concurrent.CountDownLatch(1)
          Thread.ofVirtual().start { () =>
            try
              val listener = new java.net.http.WebSocket.Listener:
                override def onText(ws: java.net.http.WebSocket, data: CharSequence, last: Boolean) =
                  q.put(Some(Value.StringV(data.toString)))
                  ws.request(1)
                  null
                override def onClose(ws: java.net.http.WebSocket, code: Int, reason: String) =
                  latch.countDown()
                  null
                override def onError(ws: java.net.http.WebSocket, error: Throwable) =
                  latch.countDown()
              java.net.http.HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(java.net.URI.create(url), listener)
                .get(5, java.util.concurrent.TimeUnit.SECONDS)
                .request(1)
              latch.await()
            catch case _: Throwable => ()
            finally try q.put(None) catch case _ => ()
          }
          makeSourceV(q, ctx)
        case List(url: Value.StringV) =>
          val q     = newQ()
          val latch = new java.util.concurrent.CountDownLatch(1)
          Thread.ofVirtual().start { () =>
            try
              val listener = new java.net.http.WebSocket.Listener:
                override def onText(ws: java.net.http.WebSocket, data: CharSequence, last: Boolean) =
                  q.put(Some(Value.StringV(data.toString)))
                  ws.request(1)
                  null
                override def onClose(ws: java.net.http.WebSocket, code: Int, reason: String) =
                  latch.countDown()
                  null
                override def onError(ws: java.net.http.WebSocket, error: Throwable) =
                  latch.countDown()
              java.net.http.HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(java.net.URI.create(url.v), listener)
                .get(5, java.util.concurrent.TimeUnit.SECONDS)
                .request(1)
              latch.await()
            catch case _: Throwable => ()
            finally try q.put(None) catch case _ => ()
          }
          makeSourceV(q, ctx)
        case _ => throw InterpretError("Source.fromWebSocket(url)")
    },

    QualifiedName("Sink.toSseStream") -> PluginNative.evalLegacy { (ctx, _) =>
      val runFn = Value.NativeFnV("Sink.toSseStream.run", Computation.pureFn {
        case List(src: Value.InstanceV) =>
          src.fields.get("runToList") match
            case Some(rtl) =>
              ctx.synchronized { ctx.invokeCallback(rtl, Nil) } match
                case Value.ListV(items) =>
                  val sb = new StringBuilder
                  for item <- items do
                    val s = item match
                      case Value.StringV(v) => v
                      case other            => Value.show(other)
                    sb.append(s"data: $s\n\n")
                  Value.StringV(sb.toString)
                case _ => Value.EmptyStr
            case None => throw InterpretError("Sink.toSseStream: not a Source")
        case _ => Value.UnitV
      })
      Value.InstanceV("Sink", Map("run" -> runFn))
    },

    QualifiedName("Sink.toWsRoom") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(room: Value.InstanceV) =>
          val broadcastFn = room.fields.getOrElse("broadcast",
            throw InterpretError("Sink.toWsRoom: not a WsRoom (no broadcast field)"))
          val runFn = Value.NativeFnV("Sink.toWsRoom.run", Computation.pureFn {
            case List(src: Value.InstanceV) =>
              src.fields.get("runForeach") match
                case Some(rf) =>
                  val emit = Value.NativeFnV("_wsEmit", Computation.pureFn {
                    case List(v) =>
                      val msg = v match
                        case Value.StringV(s) => Value.StringV(s)
                        case other            => Value.StringV(Value.show(other))
                      ctx.synchronized { ctx.invokeCallback(broadcastFn, List(msg)) }
                      Value.UnitV
                    case _ => Value.UnitV
                  })
                  ctx.synchronized { ctx.invokeCallback(rf, List(emit)).asInstanceOf[Value] }
                case None => throw InterpretError("Sink.toWsRoom: not a Source")
            case _ => Value.UnitV
          })
          Value.InstanceV("Sink", Map("run" -> runFn))
        case _ => throw InterpretError("Sink.toWsRoom(room)")
    },

    // ── v1.51.3 Sink intrinsics ───────────────────────────────────────────

    QualifiedName("Sink.foreach") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(f) =>
          val fv = toValue(f)
          val runFn = Value.NativeFnV("Sink.foreach.run", Computation.pureFn {
            case List(src: Value.InstanceV) =>
              src.fields.get("runForeach") match
                case Some(rf) => ctx.synchronized { ctx.invokeCallback(rf, List(fv)).asInstanceOf[Value] }
                case None     => throw InterpretError("Sink.foreach: not a Source")
            case _ => Value.UnitV
          })
          Value.InstanceV("Sink", Map("run" -> runFn))
        case _ => throw InterpretError("Sink.foreach(f)")
    },

    QualifiedName("Sink.fold") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(z) =>
          val zv = toValue(z)
          Value.NativeFnV("Sink.fold$1", Computation.pureFn {
            case List(f) =>
              val runFn = Value.NativeFnV("Sink.fold.run", Computation.pureFn {
                case List(src: Value.InstanceV) =>
                  src.fields.get("runFold") match
                    case Some(rf) =>
                      val curried = ctx.synchronized { ctx.invokeCallback(rf, List(zv)).asInstanceOf[Value] }
                      curried match
                        case fn: Value.NativeFnV =>
                          ctx.synchronized { ctx.invokeCallback(fn, List(f)).asInstanceOf[Value] }
                        case _ => throw InterpretError("Sink.fold: runFold not curried")
                    case None => throw InterpretError("Sink.fold: not a Source")
                case _ => Value.UnitV
              })
              Value.InstanceV("Sink", Map("run" -> runFn))
            case _ => throw InterpretError("Sink.fold(z)(f) — inner")
          })
        case _ => throw InterpretError("Sink.fold(z)(f) — outer")
    },

    QualifiedName("Sink.ignore") -> PluginNative.evalLegacy { (ctx, _) =>
      val runFn = Value.NativeFnV("Sink.ignore.run", Computation.pureFn {
        case List(src: Value.InstanceV) =>
          src.fields.get("runDrain") match
            case Some(rd) => ctx.synchronized { ctx.invokeCallback(rd, Nil).asInstanceOf[Value] }
            case None     => throw InterpretError("Sink.ignore: not a Source")
        case _ => Value.UnitV
      })
      Value.InstanceV("Sink", Map("run" -> runFn))
    },

    QualifiedName("Sink.toList") -> PluginNative.evalLegacy { (ctx, _) =>
      val runFn = Value.NativeFnV("Sink.toList.run", Computation.pureFn {
        case List(src: Value.InstanceV) =>
          src.fields.get("runToList") match
            case Some(rtl) => ctx.synchronized { ctx.invokeCallback(rtl, Nil).asInstanceOf[Value] }
            case None      => throw InterpretError("Sink.toList: not a Source")
        case _ => Value.UnitV
      })
      Value.InstanceV("Sink", Map("run" -> runFn))
    },

    // ── v1.51.3 Flow intrinsics ───────────────────────────────────────────

    QualifiedName("Flow.map") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(f) =>
          val fv = toValue(f)
          val applyFn = Value.NativeFnV("Flow.map.apply", Computation.pureFn {
            case List(src: Value.InstanceV) =>
              src.fields.get("map") match
                case Some(mapFn) => ctx.synchronized { ctx.invokeCallback(mapFn, List(fv)).asInstanceOf[Value] }
                case None        => throw InterpretError("Flow.map: not a Source")
            case _ => Value.UnitV
          })
          Value.InstanceV("Flow", Map("apply" -> applyFn))
        case _ => throw InterpretError("Flow.map(f)")
    },

    QualifiedName("Flow.filter") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(pred) =>
          val pv = toValue(pred)
          val applyFn = Value.NativeFnV("Flow.filter.apply", Computation.pureFn {
            case List(src: Value.InstanceV) =>
              src.fields.get("filter") match
                case Some(filterFn) => ctx.synchronized { ctx.invokeCallback(filterFn, List(pv)).asInstanceOf[Value] }
                case None           => throw InterpretError("Flow.filter: not a Source")
            case _ => Value.UnitV
          })
          Value.InstanceV("Flow", Map("apply" -> applyFn))
        case _ => throw InterpretError("Flow.filter(pred)")
    },

    QualifiedName("Flow.fromFunction") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(f) =>
          val fv = toValue(f)
          val applyFn = Value.NativeFnV("Flow.fromFunction.apply", Computation.pureFn {
            case List(src: Value.InstanceV) =>
              src.fields.get("map") match
                case Some(mapFn) => ctx.synchronized { ctx.invokeCallback(mapFn, List(fv)).asInstanceOf[Value] }
                case None        => throw InterpretError("Flow.fromFunction: not a Source")
            case _ => Value.UnitV
          })
          Value.InstanceV("Flow", Map("apply" -> applyFn))
        case _ => throw InterpretError("Flow.fromFunction(f)")
    },

    QualifiedName("Flow.take") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(n) =>
          val nv = toValue(n)
          val applyFn = Value.NativeFnV("Flow.take.apply", Computation.pureFn {
            case List(src: Value.InstanceV) =>
              src.fields.get("take") match
                case Some(takeFn) => ctx.synchronized { ctx.invokeCallback(takeFn, List(nv)).asInstanceOf[Value] }
                case None         => throw InterpretError("Flow.take: not a Source")
            case _ => Value.UnitV
          })
          Value.InstanceV("Flow", Map("apply" -> applyFn))
        case _ => throw InterpretError("Flow.take(n)")
    },

    QualifiedName("Flow.drop") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(n) =>
          val nv = toValue(n)
          val applyFn = Value.NativeFnV("Flow.drop.apply", Computation.pureFn {
            case List(src: Value.InstanceV) =>
              src.fields.get("drop") match
                case Some(dropFn) => ctx.synchronized { ctx.invokeCallback(dropFn, List(nv)).asInstanceOf[Value] }
                case None         => throw InterpretError("Flow.drop: not a Source")
            case _ => Value.UnitV
          })
          Value.InstanceV("Flow", Map("apply" -> applyFn))
        case _ => throw InterpretError("Flow.drop(n)")
    },

    QualifiedName("Flow.flatMap") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(f) =>
          val fv = toValue(f)
          val applyFn = Value.NativeFnV("Flow.flatMap.apply", Computation.pureFn {
            case List(src: Value.InstanceV) =>
              src.fields.get("flatMap") match
                case Some(flatMapFn) => ctx.synchronized { ctx.invokeCallback(flatMapFn, List(fv)).asInstanceOf[Value] }
                case None            => throw InterpretError("Flow.flatMap: not a Source")
            case _ => Value.UnitV
          })
          Value.InstanceV("Flow", Map("apply" -> applyFn))
        case _ => throw InterpretError("Flow.flatMap(f)")
    },

    QualifiedName("Flow.scan") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(z) =>
          val zv = toValue(z)
          Value.NativeFnV("Flow.scan$1", Computation.pureFn {
            case List(f) =>
              val fv = toValue(f)
              val applyFn = Value.NativeFnV("Flow.scan.apply", Computation.pureFn {
                case List(src: Value.InstanceV) =>
                  src.fields.get("scan") match
                    case Some(scanFn) =>
                      val inner = ctx.synchronized { ctx.invokeCallback(scanFn, List(zv)).asInstanceOf[Value] }
                      inner match
                        case fnv: Value.NativeFnV =>
                          ctx.synchronized { ctx.invokeCallback(fnv, List(fv)).asInstanceOf[Value] }
                        case _ => throw InterpretError("Flow.scan: scan not curried")
                    case None => throw InterpretError("Flow.scan: not a Source")
                case _ => Value.UnitV
              })
              Value.InstanceV("Flow", Map("apply" -> applyFn))
            case _ => throw InterpretError("Flow.scan(z)(f) — inner")
          })
        case _ => throw InterpretError("Flow.scan(z)(f) — outer")
    },

    QualifiedName("Flow.mapAsync") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(n) =>
          val nv = toValue(n)
          Value.NativeFnV("Flow.mapAsync$1", Computation.pureFn {
            case List(f) =>
              val fv = toValue(f)
              val applyFn = Value.NativeFnV("Flow.mapAsync.apply", Computation.pureFn {
                case List(src: Value.InstanceV) =>
                  src.fields.get("mapAsync") match
                    case Some(mapAsyncFn) =>
                      val inner = ctx.synchronized { ctx.invokeCallback(mapAsyncFn, List(nv)).asInstanceOf[Value] }
                      inner match
                        case fnv: Value.NativeFnV =>
                          ctx.synchronized { ctx.invokeCallback(fnv, List(fv)).asInstanceOf[Value] }
                        case _ => throw InterpretError("Flow.mapAsync: mapAsync not curried")
                    case None => throw InterpretError("Flow.mapAsync: not a Source")
                case _ => Value.UnitV
              })
              Value.InstanceV("Flow", Map("apply" -> applyFn))
            case _ => throw InterpretError("Flow.mapAsync(n)(f) — inner")
          })
        case _ => throw InterpretError("Flow.mapAsync(n)(f) — outer")
    },

    QualifiedName("Flow.recover") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(h) =>
          val hv = toValue(h)
          val applyFn = Value.NativeFnV("Flow.recover.apply", Computation.pureFn {
            case List(src: Value.InstanceV) =>
              src.fields.get("recover") match
                case Some(recoverFn) => ctx.synchronized { ctx.invokeCallback(recoverFn, List(hv)).asInstanceOf[Value] }
                case None            => throw InterpretError("Flow.recover: not a Source")
            case _ => Value.UnitV
          })
          Value.InstanceV("Flow", Map("apply" -> applyFn))
        case _ => throw InterpretError("Flow.recover(h)")
    },

    QualifiedName("Flow.throttle") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(rate) =>
          val rv = toValue(rate)
          val applyFn = Value.NativeFnV("Flow.throttle.apply", Computation.pureFn {
            case List(src: Value.InstanceV) =>
              src.fields.get("throttle") match
                case Some(throttleFn) => ctx.synchronized { ctx.invokeCallback(throttleFn, List(rv)).asInstanceOf[Value] }
                case None             => throw InterpretError("Flow.throttle: not a Source")
            case _ => Value.UnitV
          })
          Value.InstanceV("Flow", Map("apply" -> applyFn))
        case _ => throw InterpretError("Flow.throttle(rate)")
    },

    QualifiedName("Flow.debounce") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(ms) =>
          val msv = toValue(ms)
          val applyFn = Value.NativeFnV("Flow.debounce.apply", Computation.pureFn {
            case List(src: Value.InstanceV) =>
              src.fields.get("debounce") match
                case Some(debounceFn) => ctx.synchronized { ctx.invokeCallback(debounceFn, List(msv)).asInstanceOf[Value] }
                case None             => throw InterpretError("Flow.debounce: not a Source")
            case _ => Value.UnitV
          })
          Value.InstanceV("Flow", Map("apply" -> applyFn))
        case _ => throw InterpretError("Flow.debounce(ms)")
    },

    // ── v1.51.1 factory intrinsics ────────────────────────────────────────

    QualifiedName("Source.tick") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(duration) =>
          val ms = positiveMillis(toValue(duration), "tick")
          val q = newQ()
          Thread.ofVirtual().start { () =>
            try
              while true do
                if ms > 0 then Thread.sleep(ms)
                q.put(Some(Value.UnitV))
            catch case _: Throwable => ()
            finally try q.put(None) catch case _ => ()
          }
          makeSourceV(q, ctx)
        case _ => throw InterpretError("Source.tick(durationMillis)")
    },

    QualifiedName("Source.unfold") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(seed) =>
          Value.NativeFnV("Source.unfold$1", Computation.pureFn {
            case List(f) =>
              val q = newQ()
              Thread.ofVirtual().start { () =>
                try
                  var state: Value = toValue(seed)
                  var running = true
                  while running do
                    val result = ctx.synchronized { ctx.invokeCallback(f, List(state)).asInstanceOf[Value] }
                    result match
                      case Value.NoneV => running = false
                      case Value.OptionV(Some(Value.TupleV(xs))) if xs.length == 2 =>
                        q.put(Some(xs(1)))
                        state = xs(0)
                      case _ => running = false
                catch case _: Throwable => ()
                finally try q.put(None) catch case _ => ()
              }
              makeSourceV(q, ctx)
            case _ => throw InterpretError("Source.unfold(seed)(f) — inner")
          })
        case _ => throw InterpretError("Source.unfold(seed)(f) — outer")
    },

    QualifiedName("Source.fromCallback") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(register) =>
          val q = newQ()
          val cb = Value.NativeFnV("Source.fromCallback.cb", Computation.pureFn {
            case List(v) => q.put(Some(toValue(v))); Value.UnitV
            case _       => Value.UnitV
          })
          Thread.ofVirtual().start { () =>
            try ctx.synchronized { ctx.invokeCallback(register, List(cb)) }
            catch case _: Throwable => ()
            finally try q.put(None) catch case _ => ()
          }
          makeSourceV(q, ctx)
        case _ => throw InterpretError("Source.fromCallback(register)")
    },

    // ── v1.63.6 RemoteSource adapters ────────────────────────────────────────

    // Source[A].remote(name, policy) — register a named remote stream.
    // Stores the source factory in nativeFeatureState under "remoteSource.<name>"
    // and registers a GET /streams/<name> SSE endpoint via ctx.registerRoute.
    QualifiedName("Source.remote") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(src, name: String, policy) =>
          remoteSourceRegister(ctx, src, name, policy)
        case List(src, name: Value.StringV, policy) =>
          remoteSourceRegister(ctx, src, name.v, policy)
        case _ => throw InterpretError("Source.remote(name, policy)")
    },

    // remoteSourceLocal(rs, buffer) — subscribe to a RemoteSource locally.
    // In-process: looks up the source from nativeFeatureState.
    // HTTP: connects via SSE using Source.fromSse pattern.
    QualifiedName("remoteSourceLocal") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(Value.InstanceV("RemoteSource", fields), bufferV) =>
          val name = fields.get("name").collect { case Value.StringV(s) => s }.getOrElse("")
          val buffer = bufferV match
            case Value.IntV(n) => n.toInt
            case _             => 1024
          ctx.featureGet(s"remoteSource.$name") match
            case Some(src) => src.asInstanceOf[Value]
            case None =>
              // HTTP SSE fallback — requires a url field set by the connecting party
              fields.get("url").collect { case Value.StringV(u) => u } match
                case Some(url) => remoteSourceFromSse(url, buffer, ctx)
                case None      => throw InterpretError(s"remoteSourceLocal: no source registered for '$name' and no url")
        case _ => throw InterpretError("remoteSourceLocal(rs, buffer)")
    },
  )

  private def remoteSourceRegister(ctx: PluginContext, src: Any, name: String, policy: Any): Value =
    ctx.featureSet(s"remoteSource.$name", src)
    // Register SSE route GET /streams/<name>
    val sseHandler = Value.NativeFnV(s"stream.$name.sse", Computation.pureFn { _ =>
      ctx.featureGet(s"remoteSource.$name") match
        case Some(srcV: Value.InstanceV) if srcV.typeName == "Source" =>
          val sb = new StringBuilder
          srcV.fields.get("runForeach") match
            case Some(rf) =>
              val emitFn = Value.NativeFnV("_sseEmit", Computation.pureFn {
                case List(v) =>
                  val s = v match
                    case Value.StringV(x) => x
                    case other            => Value.show(other)
                  sb.append(s"data: $s\n\n")
                  Value.UnitV
                case _ => Value.UnitV
              })
              ctx.synchronized { ctx.invokeCallback(rf, List(emitFn)) }
            case _ => ()
          Value.InstanceV("Response", Map(
            "status"  -> Value.intV(200),
            "headers" -> Value.MapV(Map(
              Value.StringV("Content-Type") -> Value.StringV("text/event-stream"),
              Value.StringV("Cache-Control") -> Value.StringV("no-cache"),
            )),
            "body"    -> Value.StringV(sb.toString)
          ))
        case _ =>
          Value.InstanceV("Response", Map(
            "status"  -> Value.intV(404),
            "body"    -> Value.StringV(s"stream '$name' not found")
          ))
    })
    ctx.registerRoute("GET", s"/streams/$name", sseHandler)
    Value.InstanceV("RemoteSource", Map(
      "name"   -> Value.StringV(name),
      "policy" -> toValue(policy)
    ))

  private def remoteSourceFromSse(url: String, buffer: Int, ctx: PluginContext): Value =
    val q = new java.util.concurrent.ArrayBlockingQueue[Option[Value]](buffer.max(1))
    Thread.ofVirtual().start { () =>
      try
        val client = java.net.http.HttpClient.newHttpClient()
        val req    = java.net.http.HttpRequest.newBuilder()
          .uri(java.net.URI.create(url))
          .header("Accept", "text/event-stream")
          .build()
        val resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofLines())
        val it   = resp.body().iterator()
        while it.hasNext do
          val line = it.next()
          if line.startsWith("data:") then
            val item = Value.StringV(line.drop(5).trim)
            if q.remainingCapacity() > 0 then q.put(Some(item))
      catch case _: Throwable => ()
      finally try q.put(None) catch case _ => ()
    }
    makeSourceV(q, ctx)
