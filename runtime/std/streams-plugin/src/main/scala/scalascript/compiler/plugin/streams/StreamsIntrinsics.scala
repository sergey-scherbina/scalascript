package scalascript.compiler.plugin.streams

import scalascript.backend.spi.*
import scalascript.frontend.ReactiveSignal
import scalascript.ir.QualifiedName
import scala.collection.mutable.{ListBuffer, LinkedHashMap}
import scalascript.plugin.api.{PluginContext, PluginError, PluginNative, PluginValue}
import scalascript.plugin.api.PluginValue.{Str, Num, Dbl, Bool, Lst, Inst, InstAny, Opt, Foreign, Fn}

/** Backpressured stream intrinsics for the tree-walking interpreter.
 *
 *  `stream { emit(x) }` uses an `ArrayBlockingQueue(16)` (not the rendezvous
 *  `SynchronousQueue` that generators use), giving the producer a 16-element
 *  head-start before it parks. `emit` is routed via a ThreadLocal so nested
 *  stream bodies each target their own queue. */
object StreamsIntrinsics:

  private type Queue = java.util.concurrent.ArrayBlockingQueue[Option[PluginValue]]
  private val _emitQueueTL = new ThreadLocal[Queue]()

  // NativeImpl receives unwrapped primitives (Long, Double, String, Boolean, Unit);
  // wrap them back to Value so they can be stored in the stream queue.
  private def toValue(a: Any): PluginValue = a match
    case n: Long    => PluginValue.int(n)
    case i: Int     => PluginValue.int(i.toLong)
    case d: Double  => PluginValue.double(d)
    case s: String  => PluginValue.string(s)
    case b: Boolean => PluginValue.bool(b)
    case ()         => PluginValue.unit
    case other if PluginValue.isRuntimeValue(other) => PluginValue.wrap(other)
    case _          => PluginValue.string(a.toString)

  private def toHostAny(v: PluginValue): Any = v match
    case Num(n)    => n.toInt
    case Dbl(d) => d
    case Str(s) => s
    case Bool(b)   => b
    case PluginValue.unit      => ()
    case other            => other

  private def newQ(): Queue = new Queue(16)

  private def sourceFromValues(values: List[PluginValue], ctx: PluginContext): PluginValue =
    val queue = newQ()
    Thread.ofVirtual().start { () =>
      try for v <- values do queue.put(Some(v))
      catch case _: Throwable => ()
      finally try queue.put(None) catch case _ => ()
    }
    makeSourceV(queue, ctx)

  private def sourceFromReactiveSignal(signal: ReactiveSignal[?], ctx: PluginContext): PluginValue =
    val queue = newQ()
    queue.put(Some(toValue(signal.apply())))
    signal.asInstanceOf[ReactiveSignal[Any]].subscribe { value =>
      queue.put(Some(toValue(value)))
    }
    makeSourceV(queue, ctx)

  private def drain(queue: Queue): List[PluginValue] =
    val buf = ListBuffer[PluginValue]()
    var item = queue.take()
    while item.isDefined do
      buf += item.get
      item = queue.take()
    buf.toList

  private def strategyName(v: PluginValue): String = v match
    case Str(s) => s
    case Inst(name, _) => name.split('.').lastOption.getOrElse(name)
    case other => PluginValue.showAny(other)

  private def bufferedValues(values: List[PluginValue], capacity: Int, strategy: PluginValue): List[PluginValue] =
    if capacity < 0 then PluginError.raise("Source.buffer: capacity must be >= 0")
    strategyName(strategy) match
      case "Backpressure" | "Block" =>
        values
      case "Drop" | "DropNew" =>
        values.take(capacity)
      case "DropHead" | "DropOldest" =>
        values.takeRight(capacity)
      case "Fail" =>
        if values.length > capacity then PluginError.raise("Source.buffer: buffer overflow")
        values
      case other =>
        PluginError.raise(s"Source.buffer: unknown overflow strategy $other")

  private def sourceValue(v: PluginValue): PluginValue = v match
    case Inst(_, fields) =>
      fields.get("value")
        .orElse(fields.get("current"))
        .orElse(fields.get("initial"))
        .orElse(fields.get("default"))
        .getOrElse(v)
    case other => other

  private def intArg(v: Any): Option[Int] = v match
    case Num(n) => Some(n.toInt)
    case n: Long      => Some(n.toInt)
    case n: Int       => Some(n)
    case _            => None

  private def positiveMillis(raw: PluginValue, op: String): Long = raw match
    case Num(n) if n >= 0 => n
    case Num(_)           => PluginError.raise(s"Source.$op: duration must be >= 0")
    case other                   => intArg(other).map(_.toLong).filter(_ >= 0).getOrElse {
      PluginError.raise(s"Source.$op(durationMillis)")
    }

  private def rateArgs(rate: PluginValue): (Int, Long) = rate match
    case Inst("Rate", fields) =>
      val elements = fields.get("elements").collect { case Num(n) => n.toInt }.getOrElse(0)
      val perMillis = fields.get("perMillis").collect { case Num(n) => n }.getOrElse(1000L)
      if elements <= 0 then PluginError.raise("Source.throttle: rate elements must be > 0")
      if perMillis < 0 then PluginError.raise("Source.throttle: rate perMillis must be >= 0")
      (elements, perMillis)
    case raw if intArg(raw).isDefined =>
      val elements = intArg(raw).get
      if elements <= 0 then PluginError.raise("Source.throttle: rate elements must be > 0")
      (elements, 1000L)
    case _ => PluginError.raise("Source.throttle(rate)")

  private def sleepMillis(ms: Long): Unit =
    if ms > 0 then Thread.sleep(ms)

  private def bindSourceToSignal(source: PluginValue, signal: ReactiveSignal[Any], ctx: PluginContext): PluginValue =
    val runForeach = source.field("runForeach").getOrElse( PluginError.raise("signal.bind(source): not a Source"))
    val setter = PluginValue.nativeFn("ReactiveSignal.bind.set", {
      case List(v) => signal.set(toHostAny(v)); PluginValue.unit
      case _       => PluginValue.unit
    })
    Thread.ofVirtual().start { () =>
      try ctx.synchronized { ctx.invokeCallback(runForeach, List(setter)) }
      catch case _: Throwable => ()
    }
    PluginValue.unit

  private def makeSourceV(queue: Queue, ctx: PluginContext): PluginValue =

    def call(f: PluginValue, args: List[PluginValue]): PluginValue =
      ctx.synchronized { ctx.invokeCallback(f, args).asInstanceOf[PluginValue] }

    def startChained(bodyFn: Queue => Unit): PluginValue =
      val q2 = newQ()
      Thread.ofVirtual().start { () =>
        try bodyFn(q2)
        catch case _: Throwable => ()
        finally try q2.put(None) catch case _ => ()
      }
      makeSourceV(q2, ctx)

    PluginValue.instance("Source", Map(

      "map" -> PluginValue.nativeFn("Source.map", {
        case List(f) => startChained { ownQ =>
          var item = queue.take()
          while item.isDefined do
            ownQ.put(Some(call(f, List(item.get))))
            item = queue.take()
        }
        case _ => PluginError.raise("Source.map(f)")
      }),

      "filter" -> PluginValue.nativeFn("Source.filter", {
        case List(pred) => startChained { ownQ =>
          var item = queue.take()
          while item.isDefined do
            if call(pred, List(item.get)) == PluginValue.bool(true) then ownQ.put(Some(item.get))
            item = queue.take()
        }
        case _ => PluginError.raise("Source.filter(pred)")
      }),

      "take" -> PluginValue.nativeFn("Source.take", {
        case List(Num(n)) => startChained { ownQ =>
          var remaining = n
          var item = queue.take()
          while item.isDefined && remaining > 0 do
            ownQ.put(Some(item.get))
            remaining -= 1
            item = if remaining > 0 then queue.take() else None
        }
        case _ => PluginError.raise("Source.take(n: Int)")
      }),

      "drop" -> PluginValue.nativeFn("Source.drop", {
        case List(Num(n)) => startChained { ownQ =>
          var toDrop = n.toInt
          var item = queue.take()
          while item.isDefined && toDrop > 0 do
            toDrop -= 1
            item = queue.take()
          while item.isDefined do
            ownQ.put(Some(item.get))
            item = queue.take()
        }
        case _ => PluginError.raise("Source.drop(n: Int)")
      }),

      "flatMap" -> PluginValue.nativeFn("Source.flatMap", {
        case List(f) => startChained { ownQ =>
          val forward = PluginValue.nativeFn("_fwdEmit", {
            case List(x) => ownQ.put(Some(x)); PluginValue.unit
            case _       => PluginValue.unit
          })
          var item = queue.take()
          while item.isDefined do
            call(f, List(item.get)) match
              case InstAny(inner) =>
                inner.field("runForeach") match
                  case Some(rf) => call(rf, List(forward))
                  case None     => PluginError.raise("Source.flatMap: body must return a Source")
              case _ => PluginError.raise("Source.flatMap: body must return a Source")
            item = queue.take()
        }
        case _ => PluginError.raise("Source.flatMap(f)")
      }),

      "concat" -> PluginValue.nativeFn("Source.concat", {
        case List(other) => startChained { ownQ =>
          var item = queue.take()
          while item.isDefined do
            ownQ.put(Some(item.get))
            item = queue.take()
          val forward = PluginValue.nativeFn("_fwdEmit", {
            case List(x) => ownQ.put(Some(x)); PluginValue.unit
            case _       => PluginValue.unit
          })
          other match
            case InstAny(inst) =>
              inst.field("runForeach") match
                case Some(rf) => call(rf, List(forward))
                case None     => PluginError.raise("Source.concat(other: Source)")
            case _ => PluginError.raise("Source.concat(other: Source)")
        }
        case _ => PluginError.raise("Source.concat(other: Source)")
      }),

      "zip" -> PluginValue.nativeFn("Source.zip", {
        case List(other) => startChained { ownQ =>
          val otherList = other match
            case InstAny(inst) =>
              inst.field("runToList") match
                case Some(rtl) =>
                  call(rtl, Nil) match
                    case Lst(xs) => xs
                    case _ => Nil
                case None => Nil
            case _ => Nil
          val it = otherList.iterator
          var item = queue.take()
          while item.isDefined && it.hasNext do
            ownQ.put(Some(PluginValue.tuple(List(item.get, it.next()))))
            item = queue.take()
        }
        case _ => PluginError.raise("Source.zip(other: Source)")
      }),

      // ── v1.51.3 combining operators ──────────────────────────────────────

      "merge" -> PluginValue.nativeFn("Source.merge", {
        case List(other) => startChained { ownQ =>
          var item = queue.take()
          while item.isDefined do
            ownQ.put(Some(item.get))
            item = queue.take()
          val forward = PluginValue.nativeFn("_fwdEmit", {
            case List(x) => ownQ.put(Some(x)); PluginValue.unit
            case _       => PluginValue.unit
          })
          other match
            case InstAny(inst) =>
              inst.field("runForeach") match
                case Some(rf) => call(rf, List(forward))
                case None     => PluginError.raise("Source.merge: not a Source")
            case _ => PluginError.raise("Source.merge: not a Source")
        }
        case _ => PluginError.raise("Source.merge(other: Source)")
      }),

      "zipWith" -> PluginValue.nativeFn("Source.zipWith", {
        case List(other) => PluginValue.nativeFn("Source.zipWith$1", {
          case List(f) => startChained { ownQ =>
            val otherList = other match
              case InstAny(inst) =>
                inst.field("runToList") match
                  case Some(rtl) =>
                    call(rtl, Nil) match
                      case Lst(xs) => xs
                      case _ => Nil
                  case None => Nil
              case _ => Nil
            val it = otherList.iterator
            var item = queue.take()
            while item.isDefined && it.hasNext do
              ownQ.put(Some(call(f, List(item.get, it.next()))))
              item = queue.take()
          }
          case _ => PluginError.raise("Source.zipWith(other)(f) — inner")
        })
        case _ => PluginError.raise("Source.zipWith(other)(f) — outer")
      }),

      "broadcast" -> PluginValue.nativeFn("Source.broadcast", {
        case List(Num(n)) =>
          val ni = n.toInt
          val buf = ListBuffer[PluginValue]()
          var item = queue.take()
          while item.isDefined do
            buf += item.get
            item = queue.take()
          val all = buf.toList
          PluginValue.list(
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
        case _ => PluginError.raise("Source.broadcast(n: Int)")
      }),

      "balance" -> PluginValue.nativeFn("Source.balance", {
        case List(Num(n)) =>
          val ni = n.toInt
          val buf = ListBuffer[PluginValue]()
          var item = queue.take()
          while item.isDefined do
            buf += item.get
            item = queue.take()
          val all = buf.toList
          PluginValue.list(
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
        case _ => PluginError.raise("Source.balance(n: Int)")
      }),

      "groupBy" -> PluginValue.nativeFn("Source.groupBy", {
        case List(keyFn) => startChained { ownQ =>
          val groups = LinkedHashMap[PluginValue, ListBuffer[PluginValue]]()
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
            ownQ.put(Some(PluginValue.tuple(List(k, makeSourceV(q, ctx)))))
        }
        case _ => PluginError.raise("Source.groupBy(keyFn)")
      }),

      "mergeSubstreams" -> PluginValue.nativeFn("Source.mergeSubstreams", { _ =>
        startChained { ownQ =>
          val forward = PluginValue.nativeFn("_fwdEmit", {
            case List(x) => ownQ.put(Some(x)); PluginValue.unit
            case _       => PluginValue.unit
          })
          var item = queue.take()
          while item.isDefined do
            item.get match
              case InstAny(inner) =>
                inner.field("runForeach") match
                  case Some(rf) => call(rf, List(forward))
                  case None     => ()
              case _ => ()
            item = queue.take()
        }
      }),

      // ── v1.51.5 buffer/time/signal operators ────────────────────────────

      "buffer" -> PluginValue.nativeFn("Source.buffer", {
        case List(n, strategy) if intArg(n).isDefined =>
          sourceFromValues(bufferedValues(drain(queue), intArg(n).get, strategy), ctx)
        case List(n) if intArg(n).isDefined =>
          val capacity = intArg(n).get
          PluginValue.nativeFn("Source.buffer$1", {
            case List(strategy) => sourceFromValues(bufferedValues(drain(queue), capacity, strategy), ctx)
            case _              => PluginError.raise("Source.buffer(n)(strategy)")
          })
        case _ => PluginError.raise("Source.buffer(n, strategy)")
      }),

      "throttle" -> PluginValue.nativeFn("Source.throttle", {
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
        case _ => PluginError.raise("Source.throttle(rate)")
      }),

      "debounce" -> PluginValue.nativeFn("Source.debounce", {
        case List(duration) =>
          val durationMillis = positiveMillis(duration, "debounce")
          val values = drain(queue)
          if values.nonEmpty then sleepMillis(durationMillis)
          sourceFromValues(values.lastOption.toList, ctx)
        case _ => PluginError.raise("Source.debounce(duration)")
      }),

      // ── v1.51.3 Sink + Flow routing ──────────────────────────────────────

      "to" -> PluginValue.nativeFn("Source.to", {
        case List(InstAny(sink)) =>
          sink.field("run") match
            case Some(runFn) =>
              val selfSrc = makeSourceV(queue, ctx)
              call(runFn, List(selfSrc))
            case None => PluginError.raise("Source.to: not a Sink")
        case _ => PluginError.raise("Source.to(sink)")
      }),

      "via" -> PluginValue.nativeFn("Source.via", {
        case List(InstAny(flow)) =>
          flow.field("apply") match
            case Some(applyFn) =>
              val selfSrc = makeSourceV(queue, ctx)
              call(applyFn, List(selfSrc))
            case None => PluginError.raise("Source.via: not a Flow")
        case _ => PluginError.raise("Source.via(flow)")
      }),

      "runForeach" -> PluginValue.nativeFn("Source.runForeach", {
        case List(f) =>
          var item = queue.take()
          while item.isDefined do
            call(f, List(item.get))
            item = queue.take()
          PluginValue.unit
        case _ => PluginError.raise("Source.runForeach(f)")
      }),

      "runFold" -> PluginValue.nativeFn("Source.runFold", {
        case List(z) => PluginValue.nativeFn("Source.runFold$1", {
          case List(f) =>
            var acc = z
            var item = queue.take()
            while item.isDefined do
              acc = call(f, List(acc, item.get))
              item = queue.take()
            acc
          case _ => PluginError.raise("Source.runFold(z)(f) — inner")
        })
        case _ => PluginError.raise("Source.runFold(z)(f) — outer")
      }),

      "runToList" -> PluginValue.nativeFn("Source.runToList", { _ =>
        val buf = ListBuffer[PluginValue]()
        var item = queue.take()
        while item.isDefined do
          buf += item.get
          item = queue.take()
        PluginValue.list(buf.toList)
      }),

      "runDrain" -> PluginValue.nativeFn("Source.runDrain", { _ =>
        var item = queue.take()
        while item.isDefined do item = queue.take()
        PluginValue.unit
      }),

      // ── v1.51.4 async + error operators ─────────────────────────────────

      "mapAsync" -> PluginValue.nativeFn("Source.mapAsync", {
        case List(Num(n)) => PluginValue.nativeFn("Source.mapAsync$1", {
          case List(f) => startChained { ownQ =>
            val sem     = new java.util.concurrent.Semaphore((n.toInt) max 1)
            val all     = drain(queue)
            val results = new java.util.concurrent.ConcurrentHashMap[Int, PluginValue]()
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
          case _ => PluginError.raise("Source.mapAsync(n)(f) — inner")
        })
        case _ => PluginError.raise("Source.mapAsync(n)(f) — outer")
      }),

      "recover" -> PluginValue.nativeFn("Source.recover", {
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
                q2.put(Some(call(handler, List(PluginValue.string(msg)))))
              catch case _ => ()
            finally try q2.put(None) catch case _ => ()
          }
          makeSourceV(q2, ctx)
        case _ => PluginError.raise("Source.recover(handler)")
      }),

      "mapError" -> PluginValue.nativeFn("Source.mapError", {
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
                call(f, List(PluginValue.string(msg)))
              catch case _ => ()
            finally try q2.put(None) catch case _ => ()
          }
          makeSourceV(q2, ctx)
        case _ => PluginError.raise("Source.mapError(f)")
      }),

      // ── v1.51.1 scan / onError / cancellable ─────────────────────────────

      "scan" -> PluginValue.nativeFn("Source.scan", {
        case List(z) => PluginValue.nativeFn("Source.scan$1", {
          case List(f) => startChained { ownQ =>
            var acc: PluginValue = toValue(z)
            var item = queue.take()
            while item.isDefined do
              acc = call(f, List(acc, item.get))
              ownQ.put(Some(acc))
              item = queue.take()
          }
          case _ => PluginError.raise("Source.scan(z)(f) — inner")
        })
        case _ => PluginError.raise("Source.scan(z)(f) — outer")
      }),

      "onError" -> PluginValue.nativeFn("Source.onError", {
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
                call(handler, List(PluginValue.string(msg)))
              catch case _ => ()
            finally try q2.put(None) catch case _ => ()
          }
          makeSourceV(q2, ctx)
        case _ => PluginError.raise("Source.onError(handler)")
      }),

      "cancellable" -> PluginValue.nativeFn("Source.cancellable", { _ =>
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
        val cancelFn = PluginValue.nativeFn("Source.cancel", { _ =>
          cancelled.set(true)
          PluginValue.unit
        })
        PluginValue.tuple(List(makeSourceV(q2, ctx), cancelFn))
      }),
    ))

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    // stream { body } — body runs on a VT; emit(x) blocks when the 16-element buffer is full.
    QualifiedName("stream") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(body) =>
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
        case _ => PluginError.raise("stream(body: () => Unit)")
    },

    // emit(x) — puts one element into the current stream's queue.
    QualifiedName("emit") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(v) =>
          val q = _emitQueueTL.get()
          if q == null then PluginError.raise("emit called outside a stream body")
          q.put(Some(toValue(v)))
          PluginValue.unit
        case _ => PluginError.raise("emit(value)")
    },

    // Source.from(iterable) — wraps a List/range as a Source.
    QualifiedName("Source.from") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(Lst(items)) =>
          val queue = newQ()
          Thread.ofVirtual().start { () =>
            try for item <- items do queue.put(Some(item))
            catch case _: Throwable => ()
            finally try queue.put(None) catch case _ => ()
          }
          makeSourceV(queue, ctx)
        case _ => PluginError.raise("Source.from(iterable)")
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
        case _ => PluginError.raise("Source.single(x)")
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
        case List(InstAny(gen)) =>
          val queue = newQ()
          val nextFn = gen.field("next").getOrElse(
            PluginError.raise("Source.fromGenerator: not a Generator"))
          Thread.ofVirtual().start { () =>
            try
              var step = ctx.invokeCallback(nextFn, Nil).asInstanceOf[PluginValue]
              while step != PluginValue.none do
                step match
                  case Opt(Some(inner)) => queue.put(Some(inner))
                  case _ =>
                step = ctx.invokeCallback(nextFn, Nil).asInstanceOf[PluginValue]
            catch case _: Throwable => ()
            finally try queue.put(None) catch case _ => ()
          }
          makeSourceV(queue, ctx)
        case _ => PluginError.raise("Source.fromGenerator(gen: Generator)")
    },

    QualifiedName("Source.signal") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(Foreign("ReactiveSignal", signal: ReactiveSignal[?])) =>
          sourceFromReactiveSignal(signal, ctx)
        case List(sig) if PluginValue.isRuntimeValue(sig) => sourceFromValues(List(sourceValue(PluginValue.wrap(sig))), ctx)
        case List(sig)        => sourceFromValues(List(toValue(sig)), ctx)
        case _ => PluginError.raise("Source.signal(sig)")
    },

    QualifiedName("ReactiveSignal.bind") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(Foreign("ReactiveSignal", signal: ReactiveSignal[?]), InstAny(source)) =>
          bindSourceToSignal(source, signal.asInstanceOf[ReactiveSignal[Any]], ctx)
        case _ => PluginError.raise("ReactiveSignal.bind(signal, source)")
    },

    QualifiedName("Rate") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(elements, perMillis) if intArg(elements).isDefined && intArg(perMillis).isDefined =>
          PluginValue.instance("Rate", Map(
            "elements"  -> PluginValue.int(intArg(elements).get.toLong),
            "perMillis" -> PluginValue.int(intArg(perMillis).get.toLong),
          ))
        case List(elements) if intArg(elements).isDefined =>
          PluginValue.instance("Rate", Map("elements" -> PluginValue.int(intArg(elements).get.toLong), "perMillis" -> PluginValue.int(1000L)))
        case _ => PluginError.raise("Rate(elements, perMillis)")
    },

    QualifiedName("OverflowStrategy.Backpressure") -> PluginNative.evalLegacy { (_, _) =>
      PluginValue.instance("OverflowStrategy.Backpressure", Map.empty)
    },
    QualifiedName("OverflowStrategy.Block") -> PluginNative.evalLegacy { (_, _) =>
      PluginValue.instance("OverflowStrategy.Block", Map.empty)
    },
    QualifiedName("OverflowStrategy.Drop") -> PluginNative.evalLegacy { (_, _) =>
      PluginValue.instance("OverflowStrategy.Drop", Map.empty)
    },
    QualifiedName("OverflowStrategy.DropHead") -> PluginNative.evalLegacy { (_, _) =>
      PluginValue.instance("OverflowStrategy.DropHead", Map.empty)
    },
    QualifiedName("OverflowStrategy.DropOldest") -> PluginNative.evalLegacy { (_, _) =>
      PluginValue.instance("OverflowStrategy.DropOldest", Map.empty)
    },
    QualifiedName("OverflowStrategy.Fail") -> PluginNative.evalLegacy { (_, _) =>
      PluginValue.instance("OverflowStrategy.Fail", Map.empty)
    },

    // ── v1.51.4 SSE / WebSocket / bracket source-sink intrinsics ───────────

    QualifiedName("Source.bracket") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(acquire) =>
          PluginValue.nativeFn("Source.bracket$1", {
            case List(release) => PluginValue.nativeFn("Source.bracket$2", {
              case List(use) =>
                val resource = ctx.synchronized { ctx.invokeCallback(acquire, Nil).asInstanceOf[PluginValue] }
                val q = newQ()
                Thread.ofVirtual().start { () =>
                  try
                    val src = ctx.synchronized { ctx.invokeCallback(use, List(resource)).asInstanceOf[PluginValue] }
                    src match
                      case InstAny(inst) =>
                        val forward = PluginValue.nativeFn("_fwdEmit", {
                          case List(x) => q.put(Some(x)); PluginValue.unit
                          case _       => PluginValue.unit
                        })
                        inst.field("runForeach") match
                          case Some(rf) => ctx.synchronized { ctx.invokeCallback(rf, List(forward)) }
                          case None     => ()
                      case _ => ()
                  catch case _: Throwable => ()
                  finally
                    try ctx.synchronized { ctx.invokeCallback(release, List(resource)) } catch case _ => ()
                    try q.put(None) catch case _ => ()
                }
                makeSourceV(q, ctx)
              case _ => PluginError.raise("Source.bracket(acquire)(release)(use) — use")
            })
            case _ => PluginError.raise("Source.bracket(acquire)(release)(use) — release")
          })
        case _ => PluginError.raise("Source.bracket(acquire)(release)(use) — acquire")
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
                if line.startsWith("data:") then q.put(Some(PluginValue.string(line.drop(5).trim)))
            catch case _: Throwable => ()
            finally try q.put(None) catch case _ => ()
          }
          makeSourceV(q, ctx)
        case List(Str(url)) =>
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
                if line.startsWith("data:") then q.put(Some(PluginValue.string(line.drop(5).trim)))
            catch case _: Throwable => ()
            finally try q.put(None) catch case _ => ()
          }
          makeSourceV(q, ctx)
        case _ => PluginError.raise("Source.fromSse(url)")
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
                  q.put(Some(PluginValue.string(data.toString)))
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
        case List(Str(url)) =>
          val q     = newQ()
          val latch = new java.util.concurrent.CountDownLatch(1)
          Thread.ofVirtual().start { () =>
            try
              val listener = new java.net.http.WebSocket.Listener:
                override def onText(ws: java.net.http.WebSocket, data: CharSequence, last: Boolean) =
                  q.put(Some(PluginValue.string(data.toString)))
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
        case _ => PluginError.raise("Source.fromWebSocket(url)")
    },

    QualifiedName("Sink.toSseStream") -> PluginNative.evalLegacy { (ctx, _) =>
      val runFn = PluginValue.nativeFn("Sink.toSseStream.run", {
        case List(InstAny(src)) =>
          src.field("runToList") match
            case Some(rtl) =>
              ctx.synchronized { ctx.invokeCallback(rtl, Nil) } match
                case Lst(items) =>
                  val sb = new StringBuilder
                  for item <- items do
                    val s = item match
                      case Str(v) => v
                      case other            => PluginValue.showAny(other)
                    sb.append(s"data: $s\n\n")
                  PluginValue.string(sb.toString)
                case _ => PluginValue.string("")
            case None => PluginError.raise("Sink.toSseStream: not a Source")
        case _ => PluginValue.unit
      })
      PluginValue.instance("Sink", Map("run" -> runFn))
    },

    QualifiedName("Sink.toWsRoom") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(InstAny(room)) =>
          val broadcastFn = room.field("broadcast").getOrElse(
            PluginError.raise("Sink.toWsRoom: not a WsRoom (no broadcast field)"))
          val runFn = PluginValue.nativeFn("Sink.toWsRoom.run", {
            case List(InstAny(src)) =>
              src.field("runForeach") match
                case Some(rf) =>
                  val emit = PluginValue.nativeFn("_wsEmit", {
                    case List(v) =>
                      val msg = v match
                        case Str(s) => PluginValue.string(s)
                        case other            => PluginValue.string(PluginValue.showAny(other))
                      ctx.synchronized { ctx.invokeCallback(broadcastFn, List(msg)) }
                      PluginValue.unit
                    case _ => PluginValue.unit
                  })
                  ctx.synchronized { ctx.invokeCallback(rf, List(emit)).asInstanceOf[PluginValue] }
                case None => PluginError.raise("Sink.toWsRoom: not a Source")
            case _ => PluginValue.unit
          })
          PluginValue.instance("Sink", Map("run" -> runFn))
        case _ => PluginError.raise("Sink.toWsRoom(room)")
    },

    // ── v1.51.3 Sink intrinsics ───────────────────────────────────────────

    QualifiedName("Sink.foreach") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(f) =>
          val fv = toValue(f)
          val runFn = PluginValue.nativeFn("Sink.foreach.run", {
            case List(InstAny(src)) =>
              src.field("runForeach") match
                case Some(rf) => ctx.synchronized { ctx.invokeCallback(rf, List(fv)).asInstanceOf[PluginValue] }
                case None     => PluginError.raise("Sink.foreach: not a Source")
            case _ => PluginValue.unit
          })
          PluginValue.instance("Sink", Map("run" -> runFn))
        case _ => PluginError.raise("Sink.foreach(f)")
    },

    QualifiedName("Sink.fold") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(z) =>
          val zv = toValue(z)
          PluginValue.nativeFn("Sink.fold$1", {
            case List(f) =>
              val runFn = PluginValue.nativeFn("Sink.fold.run", {
                case List(InstAny(src)) =>
                  src.field("runFold") match
                    case Some(rf) =>
                      val curried = ctx.synchronized { ctx.invokeCallback(rf, List(zv)).asInstanceOf[PluginValue] }
                      curried match
                        case Fn(fn) =>
                          ctx.synchronized { ctx.invokeCallback(fn, List(f)).asInstanceOf[PluginValue] }
                        case _ => PluginError.raise("Sink.fold: runFold not curried")
                    case None => PluginError.raise("Sink.fold: not a Source")
                case _ => PluginValue.unit
              })
              PluginValue.instance("Sink", Map("run" -> runFn))
            case _ => PluginError.raise("Sink.fold(z)(f) — inner")
          })
        case _ => PluginError.raise("Sink.fold(z)(f) — outer")
    },

    QualifiedName("Sink.ignore") -> PluginNative.evalLegacy { (ctx, _) =>
      val runFn = PluginValue.nativeFn("Sink.ignore.run", {
        case List(InstAny(src)) =>
          src.field("runDrain") match
            case Some(rd) => ctx.synchronized { ctx.invokeCallback(rd, Nil).asInstanceOf[PluginValue] }
            case None     => PluginError.raise("Sink.ignore: not a Source")
        case _ => PluginValue.unit
      })
      PluginValue.instance("Sink", Map("run" -> runFn))
    },

    QualifiedName("Sink.toList") -> PluginNative.evalLegacy { (ctx, _) =>
      val runFn = PluginValue.nativeFn("Sink.toList.run", {
        case List(InstAny(src)) =>
          src.field("runToList") match
            case Some(rtl) => ctx.synchronized { ctx.invokeCallback(rtl, Nil).asInstanceOf[PluginValue] }
            case None      => PluginError.raise("Sink.toList: not a Source")
        case _ => PluginValue.unit
      })
      PluginValue.instance("Sink", Map("run" -> runFn))
    },

    // ── v1.51.3 Flow intrinsics ───────────────────────────────────────────

    QualifiedName("Flow.map") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(f) =>
          val fv = toValue(f)
          val applyFn = PluginValue.nativeFn("Flow.map.apply", {
            case List(InstAny(src)) =>
              src.field("map") match
                case Some(mapFn) => ctx.synchronized { ctx.invokeCallback(mapFn, List(fv)).asInstanceOf[PluginValue] }
                case None        => PluginError.raise("Flow.map: not a Source")
            case _ => PluginValue.unit
          })
          PluginValue.instance("Flow", Map("apply" -> applyFn))
        case _ => PluginError.raise("Flow.map(f)")
    },

    QualifiedName("Flow.filter") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(pred) =>
          val pv = toValue(pred)
          val applyFn = PluginValue.nativeFn("Flow.filter.apply", {
            case List(InstAny(src)) =>
              src.field("filter") match
                case Some(filterFn) => ctx.synchronized { ctx.invokeCallback(filterFn, List(pv)).asInstanceOf[PluginValue] }
                case None           => PluginError.raise("Flow.filter: not a Source")
            case _ => PluginValue.unit
          })
          PluginValue.instance("Flow", Map("apply" -> applyFn))
        case _ => PluginError.raise("Flow.filter(pred)")
    },

    QualifiedName("Flow.fromFunction") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(f) =>
          val fv = toValue(f)
          val applyFn = PluginValue.nativeFn("Flow.fromFunction.apply", {
            case List(InstAny(src)) =>
              src.field("map") match
                case Some(mapFn) => ctx.synchronized { ctx.invokeCallback(mapFn, List(fv)).asInstanceOf[PluginValue] }
                case None        => PluginError.raise("Flow.fromFunction: not a Source")
            case _ => PluginValue.unit
          })
          PluginValue.instance("Flow", Map("apply" -> applyFn))
        case _ => PluginError.raise("Flow.fromFunction(f)")
    },

    QualifiedName("Flow.take") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(n) =>
          val nv = toValue(n)
          val applyFn = PluginValue.nativeFn("Flow.take.apply", {
            case List(InstAny(src)) =>
              src.field("take") match
                case Some(takeFn) => ctx.synchronized { ctx.invokeCallback(takeFn, List(nv)).asInstanceOf[PluginValue] }
                case None         => PluginError.raise("Flow.take: not a Source")
            case _ => PluginValue.unit
          })
          PluginValue.instance("Flow", Map("apply" -> applyFn))
        case _ => PluginError.raise("Flow.take(n)")
    },

    QualifiedName("Flow.drop") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(n) =>
          val nv = toValue(n)
          val applyFn = PluginValue.nativeFn("Flow.drop.apply", {
            case List(InstAny(src)) =>
              src.field("drop") match
                case Some(dropFn) => ctx.synchronized { ctx.invokeCallback(dropFn, List(nv)).asInstanceOf[PluginValue] }
                case None         => PluginError.raise("Flow.drop: not a Source")
            case _ => PluginValue.unit
          })
          PluginValue.instance("Flow", Map("apply" -> applyFn))
        case _ => PluginError.raise("Flow.drop(n)")
    },

    QualifiedName("Flow.flatMap") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(f) =>
          val fv = toValue(f)
          val applyFn = PluginValue.nativeFn("Flow.flatMap.apply", {
            case List(InstAny(src)) =>
              src.field("flatMap") match
                case Some(flatMapFn) => ctx.synchronized { ctx.invokeCallback(flatMapFn, List(fv)).asInstanceOf[PluginValue] }
                case None            => PluginError.raise("Flow.flatMap: not a Source")
            case _ => PluginValue.unit
          })
          PluginValue.instance("Flow", Map("apply" -> applyFn))
        case _ => PluginError.raise("Flow.flatMap(f)")
    },

    QualifiedName("Flow.scan") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(z) =>
          val zv = toValue(z)
          PluginValue.nativeFn("Flow.scan$1", {
            case List(f) =>
              val fv = toValue(f)
              val applyFn = PluginValue.nativeFn("Flow.scan.apply", {
                case List(InstAny(src)) =>
                  src.field("scan") match
                    case Some(scanFn) =>
                      val inner = ctx.synchronized { ctx.invokeCallback(scanFn, List(zv)).asInstanceOf[PluginValue] }
                      inner match
                        case Fn(fnv) =>
                          ctx.synchronized { ctx.invokeCallback(fnv, List(fv)).asInstanceOf[PluginValue] }
                        case _ => PluginError.raise("Flow.scan: scan not curried")
                    case None => PluginError.raise("Flow.scan: not a Source")
                case _ => PluginValue.unit
              })
              PluginValue.instance("Flow", Map("apply" -> applyFn))
            case _ => PluginError.raise("Flow.scan(z)(f) — inner")
          })
        case _ => PluginError.raise("Flow.scan(z)(f) — outer")
    },

    QualifiedName("Flow.mapAsync") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(n) =>
          val nv = toValue(n)
          PluginValue.nativeFn("Flow.mapAsync$1", {
            case List(f) =>
              val fv = toValue(f)
              val applyFn = PluginValue.nativeFn("Flow.mapAsync.apply", {
                case List(InstAny(src)) =>
                  src.field("mapAsync") match
                    case Some(mapAsyncFn) =>
                      val inner = ctx.synchronized { ctx.invokeCallback(mapAsyncFn, List(nv)).asInstanceOf[PluginValue] }
                      inner match
                        case Fn(fnv) =>
                          ctx.synchronized { ctx.invokeCallback(fnv, List(fv)).asInstanceOf[PluginValue] }
                        case _ => PluginError.raise("Flow.mapAsync: mapAsync not curried")
                    case None => PluginError.raise("Flow.mapAsync: not a Source")
                case _ => PluginValue.unit
              })
              PluginValue.instance("Flow", Map("apply" -> applyFn))
            case _ => PluginError.raise("Flow.mapAsync(n)(f) — inner")
          })
        case _ => PluginError.raise("Flow.mapAsync(n)(f) — outer")
    },

    QualifiedName("Flow.recover") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(h) =>
          val hv = toValue(h)
          val applyFn = PluginValue.nativeFn("Flow.recover.apply", {
            case List(InstAny(src)) =>
              src.field("recover") match
                case Some(recoverFn) => ctx.synchronized { ctx.invokeCallback(recoverFn, List(hv)).asInstanceOf[PluginValue] }
                case None            => PluginError.raise("Flow.recover: not a Source")
            case _ => PluginValue.unit
          })
          PluginValue.instance("Flow", Map("apply" -> applyFn))
        case _ => PluginError.raise("Flow.recover(h)")
    },

    QualifiedName("Flow.throttle") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(rate) =>
          val rv = toValue(rate)
          val applyFn = PluginValue.nativeFn("Flow.throttle.apply", {
            case List(InstAny(src)) =>
              src.field("throttle") match
                case Some(throttleFn) => ctx.synchronized { ctx.invokeCallback(throttleFn, List(rv)).asInstanceOf[PluginValue] }
                case None             => PluginError.raise("Flow.throttle: not a Source")
            case _ => PluginValue.unit
          })
          PluginValue.instance("Flow", Map("apply" -> applyFn))
        case _ => PluginError.raise("Flow.throttle(rate)")
    },

    QualifiedName("Flow.debounce") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(ms) =>
          val msv = toValue(ms)
          val applyFn = PluginValue.nativeFn("Flow.debounce.apply", {
            case List(InstAny(src)) =>
              src.field("debounce") match
                case Some(debounceFn) => ctx.synchronized { ctx.invokeCallback(debounceFn, List(msv)).asInstanceOf[PluginValue] }
                case None             => PluginError.raise("Flow.debounce: not a Source")
            case _ => PluginValue.unit
          })
          PluginValue.instance("Flow", Map("apply" -> applyFn))
        case _ => PluginError.raise("Flow.debounce(ms)")
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
                q.put(Some(PluginValue.unit))
            catch case _: Throwable => ()
            finally try q.put(None) catch case _ => ()
          }
          makeSourceV(q, ctx)
        case _ => PluginError.raise("Source.tick(durationMillis)")
    },

    QualifiedName("Source.unfold") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(seed) =>
          PluginValue.nativeFn("Source.unfold$1", {
            case List(f) =>
              val q = newQ()
              Thread.ofVirtual().start { () =>
                try
                  var state: PluginValue = toValue(seed)
                  var running = true
                  while running do
                    val result = ctx.synchronized { ctx.invokeCallback(f, List(state)).asInstanceOf[PluginValue] }
                    result match
                      case Opt(Some(t)) if t.asTuple.exists(_.length == 2) =>
                        val xs = t.asTuple.get
                        q.put(Some(xs(1)))
                        state = xs(0)
                      case _ => running = false
                catch case _: Throwable => ()
                finally try q.put(None) catch case _ => ()
              }
              makeSourceV(q, ctx)
            case _ => PluginError.raise("Source.unfold(seed)(f) — inner")
          })
        case _ => PluginError.raise("Source.unfold(seed)(f) — outer")
    },

    QualifiedName("Source.fromCallback") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(register) =>
          val q = newQ()
          val cb = PluginValue.nativeFn("Source.fromCallback.cb", {
            case List(v) => q.put(Some(toValue(v))); PluginValue.unit
            case _       => PluginValue.unit
          })
          Thread.ofVirtual().start { () =>
            try ctx.synchronized { ctx.invokeCallback(register, List(cb)) }
            catch case _: Throwable => ()
            finally try q.put(None) catch case _ => ()
          }
          makeSourceV(q, ctx)
        case _ => PluginError.raise("Source.fromCallback(register)")
    },

    // ── v1.63.6 RemoteSource adapters ────────────────────────────────────────

    // Source[A].remote(name, policy) — register a named remote stream.
    // Stores the source factory in nativeFeatureState under "remoteSource.<name>"
    // and registers a GET /streams/<name> SSE endpoint via ctx.registerRoute.
    QualifiedName("Source.remote") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(src, name: String, policy) =>
          remoteSourceRegister(ctx, src, name, policy)
        case List(src, Str(name), policy) =>
          remoteSourceRegister(ctx, src, name, policy)
        case _ => PluginError.raise("Source.remote(name, policy)")
    },

    // remoteSourceLocal(rs, buffer) — subscribe to a RemoteSource locally.
    // In-process: looks up the source from nativeFeatureState.
    // HTTP: connects via SSE using Source.fromSse pattern.
    QualifiedName("remoteSourceLocal") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(Inst("RemoteSource", fields), bufferV) =>
          val name = fields.get("name").collect { case Str(s) => s }.getOrElse("")
          val buffer = bufferV match
            case Num(n) => n.toInt
            case _             => 1024
          ctx.featureGet(s"remoteSource.$name") match
            case Some(src) => src.asInstanceOf[PluginValue]
            case None =>
              // HTTP SSE fallback — requires a url field set by the connecting party
              fields.get("url").collect { case Str(u) => u } match
                case Some(url) => remoteSourceFromSse(url, buffer, ctx)
                case None      => PluginError.raise(s"remoteSourceLocal: no source registered for '$name' and no url")
        case _ => PluginError.raise("remoteSourceLocal(rs, buffer)")
    },
  )

  private def remoteSourceRegister(ctx: PluginContext, src: Any, name: String, policy: Any): PluginValue =
    ctx.featureSet(s"remoteSource.$name", src)
    // Register SSE route GET /streams/<name>
    val sseHandler = PluginValue.nativeFn(s"stream.$name.sse", { _ =>
      ctx.featureGet(s"remoteSource.$name") match
        case Some(InstAny(srcV)) if srcV.typeNameOf.contains("Source") =>
          val sb = new StringBuilder
          srcV.field("runForeach") match
            case Some(rf) =>
              val emitFn = PluginValue.nativeFn("_sseEmit", {
                case List(v) =>
                  val s = v match
                    case Str(x) => x
                    case other            => PluginValue.showAny(other)
                  sb.append(s"data: $s\n\n")
                  PluginValue.unit
                case _ => PluginValue.unit
              })
              ctx.synchronized { ctx.invokeCallback(rf, List(emitFn)) }
            case _ => ()
          PluginValue.instance("Response", Map(
            "status"  -> PluginValue.int(200),
            "headers" -> PluginValue.mapOf(Map(
              PluginValue.string("Content-Type") -> PluginValue.string("text/event-stream"),
              PluginValue.string("Cache-Control") -> PluginValue.string("no-cache"),
            )),
            "body"    -> PluginValue.string(sb.toString)
          ))
        case _ =>
          PluginValue.instance("Response", Map(
            "status"  -> PluginValue.int(404),
            "body"    -> PluginValue.string(s"stream '$name' not found")
          ))
    })
    ctx.registerRoute("GET", s"/streams/$name", sseHandler)
    PluginValue.instance("RemoteSource", Map(
      "name"   -> PluginValue.string(name),
      "policy" -> toValue(policy)
    ))

  private def remoteSourceFromSse(url: String, buffer: Int, ctx: PluginContext): PluginValue =
    val q = new java.util.concurrent.ArrayBlockingQueue[Option[PluginValue]](buffer.max(1))
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
            val item = PluginValue.string(line.drop(5).trim)
            if q.remainingCapacity() > 0 then q.put(Some(item))
      catch case _: Throwable => ()
      finally try q.put(None) catch case _ => ()
    }
    makeSourceV(q, ctx)
