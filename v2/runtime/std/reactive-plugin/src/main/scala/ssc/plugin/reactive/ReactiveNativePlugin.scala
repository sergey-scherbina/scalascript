package ssc.plugin.reactive

import scala.collection.mutable
import ssc.Value
import ssc.plugin.{NativePlugin, NativePluginContext}

/** Core-free synchronous Signal/computed/effect runtime. */
final class ReactiveNativePlugin extends NativePlugin:
  def id: String = "59-reactive"

  private final case class SignalState(
      var value: Value,
      subscribers: mutable.LinkedHashSet[Long],
      writable: Boolean)

  private final case class EffectState(
      run: () => Value,
      dependencies: mutable.LinkedHashSet[Long])

  def install(context: NativePluginContext): Unit =
    val signals = mutable.LinkedHashMap.empty[Long, SignalState]
    val effects = mutable.LinkedHashMap.empty[Long, EffectState]
    val effectStack = mutable.ArrayBuffer.empty[Long]
    val pending = mutable.LinkedHashSet.empty[Long]
    var nextId = 0L
    var flushing = false

    def freshId(): Long =
      nextId += 1
      nextId

    def signalHandle(id: Long): Value =
      Value.DataV("ReactiveSignal", Vector(Value.IntV(id)))

    def signalId(value: Value): Long = value match
      case Value.DataV("ReactiveSignal", Vector(Value.IntV(id))) => id
      case _ => throw new IllegalArgumentException("ReactiveSignal handle expected")

    def signalState(value: Value): SignalState =
      signals.getOrElse(signalId(value),
        throw new IllegalArgumentException("Signal disposed or unknown id"))

    def clearDependencies(effectId: Long): Unit =
      effects.get(effectId).foreach { effect =>
        effect.dependencies.foreach(id => signals.get(id).foreach(_.subscribers.remove(effectId)))
        effect.dependencies.clear()
      }

    def runEffect(effectId: Long): Unit =
      effects.get(effectId).foreach { effect =>
        clearDependencies(effectId)
        effectStack += effectId
        try effect.run()
        finally effectStack.remove(effectStack.length - 1)
      }

    def flush(): Unit =
      if !flushing then
        flushing = true
        try
          while pending.nonEmpty do
            val effectId = pending.head
            pending.remove(effectId)
            runEffect(effectId)
        finally flushing = false

    def read(value: Value): Value =
      val id = signalId(value)
      val state = signalState(value)
      effectStack.lastOption.foreach { effectId =>
        state.subscribers.add(effectId)
        effects.get(effectId).foreach(_.dependencies.add(id))
      }
      state.value

    def write(value: Value, next: Value, allowReadOnly: Boolean = false): Value =
      val state = signalState(value)
      if !state.writable && !allowReadOnly then
        throw new IllegalArgumentException("computed Signal is read-only")
      state.value = next
      state.subscribers.foreach { effectId =>
        if !effectStack.contains(effectId) then pending.add(effectId)
      }
      flush()
      Value.UnitV

    def makeSignal(initial: Value, writable: Boolean): Value =
      val id = freshId()
      signals(id) = SignalState(initial, mutable.LinkedHashSet.empty, writable)
      signalHandle(id)

    def makeEffect(run: () => Value): Long =
      val id = freshId()
      effects(id) = EffectState(run, mutable.LinkedHashSet.empty)
      runEffect(id)
      id

    context.registerGlobal("Signal", 1) {
      case initial :: Nil => makeSignal(initial, writable = true)
      case _ => throw new IllegalArgumentException("Signal(initial)")
    }
    context.registerGlobal("computed", 1) {
      case thunk :: Nil =>
        val result = makeSignal(Value.UnitV, writable = false)
        makeEffect(() => write(result, context.invoke(thunk, Nil), allowReadOnly = true))
        result
      case _ => throw new IllegalArgumentException("computed { ... }")
    }
    context.registerGlobal("effect", 1) {
      case thunk :: Nil =>
        makeEffect(() => context.invoke(thunk, Nil))
        Value.UnitV
      case _ => throw new IllegalArgumentException("effect { ... }")
    }

    context.registerFields("ReactiveSignal", Vector("id"))
    context.registerTaggedApply("ReactiveSignal") {
      case signal :: Nil => read(signal)
      case _ => throw new IllegalArgumentException("signal()")
    }
    context.registerTaggedMethod("ReactiveSignal", "get") {
      case signal :: Nil => read(signal)
      case _ => throw new IllegalArgumentException("signal.get")
    }
    context.registerTaggedMethod("ReactiveSignal", "apply") {
      case signal :: Nil => read(signal)
      case _ => throw new IllegalArgumentException("signal.apply")
    }
    context.registerTaggedMethod("ReactiveSignal", "set") {
      case signal :: next :: Nil => write(signal, next)
      case _ => throw new IllegalArgumentException("signal.set(value)")
    }
