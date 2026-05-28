package scalascript.interpreter

import scala.collection.mutable
import Computation.Pure

private[interpreter] object SignalRuntime:

  def install(interp: Interpreter): Unit =
    interp.globals("Signal") = Value.NativeFnV("Signal", {
      case List(init) => Pure(makeSignal(interp, init))
      case _          => throw InterpretError("Signal(initial)")
    })
    interp.globals("computed") = Value.NativeFnV("computed", {
      case List(thunk) => Pure(makeComputed(interp, thunk))
      case _           => throw InterpretError("computed { ... }")
    })
    interp.globals("effect") = Value.NativeFnV("effect", {
      case List(thunk) => makeEffect(interp, thunk); Pure(Value.UnitV)
      case _           => throw InterpretError("effect { ... }")
    })

  private def freshReactiveId(interp: Interpreter): Long =
    interp.reactiveCounter += 1; interp.reactiveCounter

  private def signalInstance(id: Long): Value.InstanceV =
    Value.InstanceV("Signal", Map("id" -> Value.intV(id)))

  private def makeSignal(interp: Interpreter, init: Value): Value.InstanceV =
    val id = freshReactiveId(interp)
    interp.signals(id) = interp.SignalState(init, mutable.HashSet.empty)
    signalInstance(id)

  def signalGet(interp: Interpreter, id: Long): Value =
    val s = interp.signals.getOrElse(id, throw InterpretError("Signal disposed or unknown id"))
    if interp.effectStack.nonEmpty then
      val eid = interp.effectStack.top
      s.subs += eid
      interp.effects.get(eid).foreach(_.deps += id)
    s.value

  def signalSet(interp: Interpreter, id: Long, v: Value): Unit =
    val s = interp.signals.getOrElse(id, throw InterpretError("Signal disposed or unknown id"))
    s.value = v
    s.subs.foreach { eid =>
      if !interp.effectStack.contains(eid) then interp.pendingEffects += eid
    }
    if !interp.reactiveFlushing then reactiveFlush(interp)

  private def reactiveFlush(interp: Interpreter): Unit =
    interp.reactiveFlushing = true
    try
      while interp.pendingEffects.nonEmpty do
        val eid = interp.pendingEffects.head
        interp.pendingEffects -= eid
        runEffect(interp, eid)
    finally interp.reactiveFlushing = false

  private def clearEffectDeps(interp: Interpreter, eid: Long): Unit =
    interp.effects.get(eid).foreach { e =>
      e.deps.foreach { sid => interp.signals.get(sid).foreach(_.subs -= eid) }
      e.deps.clear()
    }

  private def runEffect(interp: Interpreter, eid: Long): Unit =
    interp.effects.get(eid).foreach { e =>
      clearEffectDeps(interp, eid)
      interp.effectStack.push(eid)
      try Computation.run(interp.callValue(e.thunk, Nil, Map.empty))
      finally interp.effectStack.pop()
    }

  private[interpreter] def makeEffect(interp: Interpreter, thunk: Value): Value =
    val id = freshReactiveId(interp)
    interp.effects(id) = interp.EffectState(thunk, mutable.HashSet.empty)
    runEffect(interp, id)
    Value.UnitV

  private[interpreter] def makeComputed(interp: Interpreter, thunk: Value): Value.InstanceV =
    val sid = freshReactiveId(interp)
    val eid = freshReactiveId(interp)
    interp.signals(sid) = interp.SignalState(Value.UnitV, mutable.HashSet.empty)
    val updater = Value.NativeFnV("computed.update", Computation.pureFn { _ =>
      val v = Computation.run(interp.callValue(thunk, Nil, Map.empty))
      signalSet(interp, sid, v)
      Value.UnitV
    })
    interp.effects(eid) = interp.EffectState(updater, mutable.HashSet.empty)
    runEffect(interp, eid)
    signalInstance(sid)
