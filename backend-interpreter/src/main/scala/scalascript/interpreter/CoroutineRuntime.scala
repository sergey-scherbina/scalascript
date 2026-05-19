package scalascript.interpreter

import Computation.Pure

private[interpreter] object CoroutineRuntime:

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
        Pure(interp.makeGeneratorV(queue))
      case _ => throw InterpretError("generator(body: => Unit)")
    })
    // suspend handles both generator (R=Unit) and coroutine contexts.
    interp.globals("suspend") = Value.NativeFnV("suspend", {
      case List(v) =>
        val coH = interp._coHandleTL.get()
        if coH != null then
          coH.fromBody.put(Value.InstanceV("Yielded", Map("value" -> v)))
          Pure(coH.toBody.take())
        else
          val genQ = interp._genQueueTL.get()
          if genQ == null then
            throw InterpretError("suspend called outside a coroutine or generator body")
          genQ.put(Some(v))
          Pure(Value.UnitV)
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
            fromBody.put(Value.InstanceV("Returned", Map("value" -> result)))
          catch case t: Throwable =>
            val msg = Option(t.getMessage).getOrElse(t.getClass.getSimpleName)
            // offer instead of put: if handle was removed (cancelled) nobody
            // reads fromBody, so we must not block the virtual thread forever.
            fromBody.offer(Value.InstanceV("Errored", Map("message" -> Value.StringV(msg))))
        }
        Pure(Value.InstanceV("Coroutine", Map("_id" -> Value.IntV(id))))
      case _ => throw InterpretError("coroutineCreate(body: () => T)")
    })

    interp.globals("coroutineResume") = Value.NativeFnV("coroutineResume", {
      case List(Value.InstanceV("Coroutine", fields), in) =>
        val id = fields.get("_id") match
          case Some(Value.IntV(n)) => n
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
        val id = fields.get("_id") match
          case Some(Value.IntV(n)) => n
          case _ => throw InterpretError("coroutineCancel: invalid coroutine handle")
        val handle = interp.coHandles.remove(id)
        if handle != null then
          val thread = handle.bodyThread.get()
          if thread != null then
            thread.interrupt()
            thread.join(500)
        Pure(Value.UnitV)
      case _ => throw InterpretError("coroutineCancel(co)")
    })
