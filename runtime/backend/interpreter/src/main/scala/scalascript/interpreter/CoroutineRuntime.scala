package scalascript.interpreter

import scala.collection.mutable.ListBuffer
import scala.collection.immutable.{Map => IMap}
import Computation.Pure

private[interpreter] object CoroutineRuntime:

  private type Queue = java.util.concurrent.SynchronousQueue[Option[Value]]

  def makeGeneratorV(queue: Queue, interp: Interpreter): Value =
    def startChained(bodyFn: Queue => Unit): Value =
      val q2 = new Queue()
      Thread.ofVirtual().start { () =>
        interp._genQueueTL.set(q2)
        try bodyFn(q2)
        catch case _: Throwable => ()
        finally try q2.put(None) catch case _ => ()
      }
      makeGeneratorV(q2, interp)

    Value.InstanceV("Generator", Map(
      "next" -> Value.NativeFnV("Generator.next", Computation.pureFn { _ =>
        Value.OptionV(queue.take())
      }),
      "foreach" -> Value.NativeFnV("Generator.foreach", Computation.pureFn {
        case List(f) =>
          var item = queue.take()
          while item.isDefined do
            Computation.run(interp.callValue1(f, item.get, Map.empty))
            item = queue.take()
          Value.UnitV
        case _ => throw InterpretError("Generator.foreach(f)")
      }),
      "toList" -> Value.NativeFnV("Generator.toList", Computation.pureFn { _ =>
        val buf = ListBuffer[Value]()
        var item = queue.take()
        while item.isDefined do
          buf += item.get
          item = queue.take()
        Value.ListV(buf.toList)
      }),
      "map" -> Value.NativeFnV("Generator.map", Computation.pureFn {
        case List(f) => startChained { ownQ =>
          var item = queue.take()
          while item.isDefined do
            val mapped = Computation.run(interp.callValue1(f, item.get, Map.empty))
            ownQ.put(Some(mapped))
            item = queue.take()
        }
        case _ => throw InterpretError("Generator.map(f)")
      }),
      "filter" -> Value.NativeFnV("Generator.filter", Computation.pureFn {
        case List(pred) => startChained { ownQ =>
          var item = queue.take()
          while item.isDefined do
            if Computation.run(interp.callValue1(pred, item.get, Map.empty)) == Value.True then
              ownQ.put(Some(item.get))
            item = queue.take()
        }
        case _ => throw InterpretError("Generator.filter(pred)")
      }),
      "take" -> Value.NativeFnV("Generator.take", Computation.pureFn {
        case List(Value.IntV(n)) => startChained { ownQ =>
          var remaining = n
          var item = queue.take()
          while item.isDefined && remaining > 0 do
            ownQ.put(Some(item.get))
            remaining -= 1
            item = if remaining > 0 then queue.take() else None
        }
        case _ => throw InterpretError("Generator.take(n: Int)")
      }),
      "drop" -> Value.NativeFnV("Generator.drop", Computation.pureFn {
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
        case _ => throw InterpretError("Generator.drop(n: Int)")
      }),
      "flatMap" -> Value.NativeFnV("Generator.flatMap", Computation.pureFn {
        case List(f) => startChained { ownQ =>
          var item = queue.take()
          while item.isDefined do
            val inner = Computation.run(interp.callValue1(f, item.get, Map.empty))
            inner match
              case Value.InstanceV("Generator", fields) =>
                val innerNext = fields("next")
                var sub = Computation.run(interp.callValue(innerNext, Nil, Map.empty))
                while sub != Value.NoneV do
                  sub match
                    case Value.OptionV(Some(v)) => ownQ.put(Some(v))
                    case _ =>
                  sub = Computation.run(interp.callValue(innerNext, Nil, Map.empty))
              case _ => throw InterpretError("Generator.flatMap: body must return a Generator")
            item = queue.take()
        }
        case _ => throw InterpretError("Generator.flatMap(f)")
      }),
      "zip" -> Value.NativeFnV("Generator.zip", Computation.pureFn {
        case List(other: Value.InstanceV) => startChained { ownQ =>
          val otherNext = other.fields("next")
          var a = queue.take()
          var b = Computation.run(interp.callValue(otherNext, Nil, Map.empty))
          while a.isDefined && b != Value.NoneV do
            val bVal = b match { case Value.OptionV(Some(v)) => v; case _ => Value.UnitV }
            ownQ.put(Some(Value.TupleV(a.get :: bVal :: Nil)))
            a = queue.take()
            b = if a.isDefined then Computation.run(interp.callValue(otherNext, Nil, Map.empty)) else Value.NoneV
        }
        case _ => throw InterpretError("Generator.zip(other: Generator)")
      }),
      "zipWithIndex" -> Value.NativeFnV("Generator.zipWithIndex", Computation.pureFn { _ =>
        startChained { ownQ =>
          var idx = 0
          var item = queue.take()
          while item.isDefined do
            ownQ.put(Some(Value.TupleV(item.get :: Value.intV(idx) :: Nil)))
            idx += 1
            item = queue.take()
        }
      }),
    ))

  def install(interp: Interpreter): Unit =
    // ── v1.10 Generator — generator { body } / suspend(v) ──────────────
    interp.globals("generator") = Value.NativeFnV("generator", {
      case List(thunk) =>
        val queue = new interp.GenQueue()
        Thread.ofVirtual().start { () =>
          interp._genQueueTL.set(queue)
          try Computation.run(interp.callValue(thunk, Nil, Map.empty))
          catch case _: Throwable => ()
          finally try queue.put(None) catch case _ => ()
        }
        Pure(makeGeneratorV(queue, interp))
      case _ => throw InterpretError("generator(body: => Unit)")
    })
    // suspend handles both generator (R=Unit) and coroutine contexts.
    interp.globals("suspend") = Value.NativeFnV("suspend", {
      case List(v) =>
        val coH = interp._coHandleTL.get()
        if coH != null then
          coH.fromBody.put(Value.InstanceV("Yielded", new IMap.Map1("value", v)))
          Pure(coH.toBody.take())
        else
          val genQ = interp._genQueueTL.get()
          if genQ == null then
            throw InterpretError("suspend called outside a coroutine or generator body")
          genQ.put(Some(v))
          Computation.PureUnit
      case _ => throw InterpretError("suspend(v)")
    })

    // ── v1.9 Coroutines — coroutineCreate / coroutineResume ──────────────
    interp.globals("coroutineCreate") = Value.NativeFnV("coroutineCreate", {
      case List(thunk) =>
        val fromBody   = new java.util.concurrent.LinkedBlockingQueue[Value](1)
        val toBody     = new java.util.concurrent.SynchronousQueue[Value]()
        val threadRef  = new java.util.concurrent.atomic.AtomicReference[Thread](null)
        val handle     = interp.CoHandle(fromBody, toBody, threadRef)
        val id         = interp.nextCoId.getAndIncrement()
        interp.coHandles.put(id, handle)
        Thread.ofVirtual().start { () =>
          threadRef.set(Thread.currentThread())
          interp._coHandleTL.set(handle)
          try
            toBody.take()  // lazy start: block until first coroutineResume
            val result = Computation.run(interp.callValue(thunk, Nil, Map.empty))
            fromBody.put(Value.InstanceV("Returned", new IMap.Map1("value", result)))
          catch case t: Throwable =>
            val msg = Option(t.getMessage).getOrElse(t.getClass.getSimpleName)
            // offer instead of put: if handle was removed (cancelled) nobody
            // reads fromBody, so we must not block the virtual thread forever.
            fromBody.offer(Value.InstanceV("Errored", new IMap.Map1("message", Value.StringV(msg))))
        }
        Pure(Value.InstanceV("Coroutine", new IMap.Map1("_id", Value.intV(id))))
      case _ => throw InterpretError("coroutineCreate(body: () => T)")
    })

    interp.globals("coroutineResume") = Value.NativeFnV("coroutineResume", {
      case List(Value.InstanceV("Coroutine", fields), in) =>
        val id = fields.getOrElse("_id", null) match
          case Value.IntV(n) => n
          case _ => throw InterpretError("coroutineResume: invalid coroutine handle")
        val handle = interp.coHandles.get(id)
        if handle == null then throw InterpretError("coroutineResume: coroutine already completed or cancelled")
        handle.toBody.put(in)
        val step = handle.fromBody.take()
        step match
          case Value.InstanceV("Returned" | "Errored" | "Cancelled", _) => interp.coHandles.remove(id)
          case _ => ()
        Pure(step)
      case _ => throw InterpretError("coroutineResume(co, in)")
    })

    interp.globals("coroutineCancel") = Value.NativeFnV("coroutineCancel", {
      case List(Value.InstanceV("Coroutine", fields)) =>
        val id = fields.getOrElse("_id", null) match
          case Value.IntV(n) => n
          case _ => throw InterpretError("coroutineCancel: invalid coroutine handle")
        val handle = interp.coHandles.remove(id)
        if handle != null then
          val thread = handle.bodyThread.get()
          if thread != null then
            thread.interrupt()
            thread.join(500)
        Computation.PureUnit
      case _ => throw InterpretError("coroutineCancel(co)")
    })
