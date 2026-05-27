package scalascript.compiler.plugin.streams

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.interpreter.{Value, InterpretError, Computation}

import scala.collection.mutable.{ListBuffer, LinkedHashMap}

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
    case n: Long    => Value.IntV(n)
    case i: Int     => Value.IntV(i.toLong)
    case d: Double  => Value.DoubleV(d)
    case s: String  => Value.StringV(s)
    case b: Boolean => Value.BoolV(b)
    case ()         => Value.UnitV
    case v: Value   => v
    case _          => Value.StringV(a.toString)

  private def newQ(): Queue = new Queue(16)

  private def makeSourceV(queue: Queue, ctx: NativeContext): Value =

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
            if call(pred, List(item.get)) == Value.BoolV(true) then ownQ.put(Some(item.get))
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
    ))

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    // stream { body } — body runs on a VT; emit(x) blocks when the 16-element buffer is full.
    QualifiedName("stream") -> NativeImpl((ctx, args) =>
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
    ),

    // emit(x) — puts one element into the current stream's queue.
    QualifiedName("emit") -> NativeImpl((_, args) =>
      args match
        case List(v) =>
          val q = _emitQueueTL.get()
          if q == null then throw InterpretError("emit called outside a stream body")
          q.put(Some(toValue(v)))
          Value.UnitV
        case _ => throw InterpretError("emit(value)")
    ),

    // Source.from(iterable) — wraps a List/range as a Source.
    QualifiedName("Source.from") -> NativeImpl((ctx, args) =>
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
    ),

    // Source.single(x) — one-element source.
    QualifiedName("Source.single") -> NativeImpl((ctx, args) =>
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
    ),

    // Source.empty — completes immediately.
    QualifiedName("Source.empty") -> NativeImpl((ctx, _) =>
      val queue = newQ()
      queue.put(None)
      makeSourceV(queue, ctx)
    ),

    // Source.fromGenerator(gen) — wraps a Generator[T] as a Source.
    QualifiedName("Source.fromGenerator") -> NativeImpl((ctx, args) =>
      args match
        case List(gen: Value.InstanceV) =>
          val queue = newQ()
          val nextFn = gen.fields.getOrElse("next",
            throw InterpretError("Source.fromGenerator: not a Generator"))
          Thread.ofVirtual().start { () =>
            try
              var step = ctx.invokeCallback(nextFn, Nil).asInstanceOf[Value]
              while step != Value.OptionV(None) do
                step match
                  case Value.OptionV(Some(v)) => queue.put(Some(v))
                  case _ =>
                step = ctx.invokeCallback(nextFn, Nil).asInstanceOf[Value]
            catch case _: Throwable => ()
            finally try queue.put(None) catch case _ => ()
          }
          makeSourceV(queue, ctx)
        case _ => throw InterpretError("Source.fromGenerator(gen: Generator)")
    ),

    // ── v1.51.3 Sink intrinsics ───────────────────────────────────────────

    QualifiedName("Sink.foreach") -> NativeImpl((ctx, args) =>
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
    ),

    QualifiedName("Sink.fold") -> NativeImpl((ctx, args) =>
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
    ),

    QualifiedName("Sink.ignore") -> NativeImpl((ctx, _) =>
      val runFn = Value.NativeFnV("Sink.ignore.run", Computation.pureFn {
        case List(src: Value.InstanceV) =>
          src.fields.get("runDrain") match
            case Some(rd) => ctx.synchronized { ctx.invokeCallback(rd, Nil).asInstanceOf[Value] }
            case None     => throw InterpretError("Sink.ignore: not a Source")
        case _ => Value.UnitV
      })
      Value.InstanceV("Sink", Map("run" -> runFn))
    ),

    QualifiedName("Sink.toList") -> NativeImpl((ctx, _) =>
      val runFn = Value.NativeFnV("Sink.toList.run", Computation.pureFn {
        case List(src: Value.InstanceV) =>
          src.fields.get("runToList") match
            case Some(rtl) => ctx.synchronized { ctx.invokeCallback(rtl, Nil).asInstanceOf[Value] }
            case None      => throw InterpretError("Sink.toList: not a Source")
        case _ => Value.UnitV
      })
      Value.InstanceV("Sink", Map("run" -> runFn))
    ),

    // ── v1.51.3 Flow intrinsics ───────────────────────────────────────────

    QualifiedName("Flow.map") -> NativeImpl((ctx, args) =>
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
    ),

    QualifiedName("Flow.filter") -> NativeImpl((ctx, args) =>
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
    ),
  )
