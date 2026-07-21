package ssc.plugin.generator

import java.util.concurrent.{CancellationException, SynchronousQueue}
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable
import ssc.{Done, Prims, Runtime, Value}
import ssc.plugin.{NativePlugin, NativePluginContext}

/** Core-free pull Generator runtime shared by the native VM and direct ASM. */
final class GeneratorNativePlugin extends NativePlugin:
  def id: String = "59-generator"

  private sealed trait Event
  private final case class Item(value: Value) extends Event
  private case object End extends Event
  private final case class Failed(error: Throwable) extends Event

  private trait SuspendTarget:
    def suspend(value: Value): Value

  private final class Emitter(state: GeneratorState) extends SuspendTarget:
    def emit(value: Value): Unit = state.emit(value)
    def suspend(value: Value): Value =
      emit(value)
      Value.UnitV

  private val currentSuspendTarget = new ThreadLocal[SuspendTarget]()

  private def withSuspendTarget[A](target: SuspendTarget)(body: => A): A =
    val previous = currentSuspendTarget.get()
    currentSuspendTarget.set(target)
    try body
    finally
      if previous == null then currentSuspendTarget.remove()
      else currentSuspendTarget.set(previous)

  private def failureDetail(error: Throwable): String =
    var root = error
    while root.getCause != null && root.getCause != root do root = root.getCause
    root match
      case thrown: ssc.SscThrow => thrown.value match
        case Value.DataV(tag, fields)
            if fields.size == 1 && (tag.endsWith("Exception") || tag.endsWith("Error")) =>
          Prims.display(fields.head)
        case value => Prims.display(value)
      case _ =>
        val rootMessage = Option(root.getMessage).filter(_.nonEmpty)
          .getOrElse(root.getClass.getSimpleName)
        val ownerMessage = Option(error.getMessage).filter(_.nonEmpty)
          .getOrElse(error.getClass.getSimpleName)
        if ownerMessage == rootMessage then rootMessage else s"$ownerMessage: $rootMessage"

  private final class GeneratorState(
      produce: Emitter => Unit,
      cleanup: () => Unit):
    private val events = new SynchronousQueue[Event]()
    private val cancelled = new AtomicBoolean(false)
    private val cleaned = new AtomicBoolean(false)
    @volatile private var producer: Thread = null
    private var terminal: Event = null

    private def cleanupOnce(): Unit =
      if cleaned.compareAndSet(false, true) then cleanup()

    private def deliver(event: Event): Unit =
      if !cancelled.get() then events.put(event)

    private def runProducer(): Unit =
      val emitter = Emitter(this)
      try
        withSuspendTarget(emitter)(produce(emitter))
        deliver(End)
      catch
        case _: CancellationException if cancelled.get() => ()
        case _: InterruptedException if cancelled.get()  => ()
        case error: Throwable =>
          cleanupOnce()
          if !cancelled.get() then
            try deliver(Failed(error))
            catch case _: InterruptedException => Thread.currentThread().interrupt()

    def start(): Unit =
      val thread = Thread.ofVirtual().name("ssc-generator").unstarted(() => runProducer())
      producer = thread
      thread.start()

    def emit(value: Value): Unit =
      if cancelled.get() then throw new CancellationException("Generator cancelled")
      events.put(Item(value))

    def pull(): Option[Value] = synchronized {
      terminal match
        case End                  => None
        case Failed(error)        => throw error
        case _ if cancelled.get() => None
        case _ =>
          events.take() match
            case Item(value) => Some(value)
            case End =>
              terminal = End
              None
            case Failed(error) =>
              val bounded = new IllegalStateException(
                s"Generator producer failed: ${failureDetail(error)}", error)
              terminal = Failed(bounded)
              throw bounded
    }

    def cancel(): Unit =
      if cancelled.compareAndSet(false, true) then
        cleanupOnce()
        val thread = producer
        if thread != null && thread != Thread.currentThread() then thread.interrupt()

  private final class CoroutineState(body: Value, context: NativePluginContext)
      extends SuspendTarget:
    private val fromBody = new SynchronousQueue[Value]()
    private val toBody = new SynchronousQueue[Value]()
    private val cancelled = new AtomicBoolean(false)
    @volatile private var bodyThread: Thread = null
    private var started = false
    private var terminal = false

    private def step(tag: String, value: Value): Value =
      Value.DataV(tag, Vector(value))

    private def runBody(): Unit =
      try
        toBody.take()
        val result = withSuspendTarget(this)(context.invoke(body, Nil))
        fromBody.put(step("Returned", result))
      catch
        case _: CancellationException if cancelled.get() => ()
        case _: InterruptedException if cancelled.get()  => ()
        case error: Throwable =>
          if !cancelled.get() then
            try fromBody.put(step("Errored", Value.StrV(failureDetail(error))))
            catch case _: InterruptedException => Thread.currentThread().interrupt()

    private def start(): Unit =
      val thread = Thread.ofVirtual().name("ssc-coroutine").unstarted(() => runBody())
      bodyThread = thread
      started = true
      thread.start()

    def suspend(value: Value): Value =
      if cancelled.get() then throw new CancellationException("Coroutine cancelled")
      fromBody.put(step("Yielded", value))
      toBody.take()

    def resume(input: Value): Value = synchronized {
      if terminal || cancelled.get() then
        throw new IllegalStateException(
          "coroutineResume: coroutine already completed or cancelled")
      if !started then start()
      try
        toBody.put(input)
        val result = fromBody.take()
        result match
          case Value.DataV("Returned" | "Errored" | "Cancelled", _) => terminal = true
          case _ => ()
        result
      catch
        case error: InterruptedException =>
          Thread.currentThread().interrupt()
          throw new IllegalStateException("coroutineResume: interrupted", error)
    }

    def cancel(): Unit = synchronized {
      if !terminal && cancelled.compareAndSet(false, true) then
        terminal = true
        val thread = bodyThread
        if thread != null && thread != Thread.currentThread() then
          thread.interrupt()
          thread.join(500L)
    }

  private def closure(arity: Int)(fn: List[Value] => Value): Value.ClosV =
    Value.ClosV(Runtime.emptyEnv, arity, env => Done(fn(env.toList)))

  private def list(values: IterableOnce[Value]): Value =
    val vector = Vector.from(values)
    var result: Value = Value.DataV("Nil", Vector.empty)
    val iterator = vector.reverseIterator
    while iterator.hasNext do
      result = Value.DataV("Cons", Vector(iterator.next(), result))
    result

  private def tuple(left: Value, right: Value): Value =
    Value.DataV("Tuple2", Vector(left, right))

  private def invoke(
      context: NativePluginContext,
      operation: String,
      fn: Value,
      args: Value*): Value =
    try context.invoke(fn, args.toList)
    catch case error: Throwable =>
      val rendered = args.map(Prims.display).mkString(", ")
      throw new IllegalArgumentException(
        s"Generator.$operation callback failed for [$rendered]", error)

  private def make(
      context: NativePluginContext,
      cleanup: () => Unit = () => ())(
      produce: Emitter => Unit): Value =
    val state = GeneratorState(produce, cleanup)
    val value = Value.ForeignV(GeneratorValue(state, context))
    state.start()
    value

  private def generator(value: Value, operation: String): GeneratorValue = value match
    case Value.ForeignV(result: GeneratorValue) => result
    case _ => throw new IllegalArgumentException(s"Generator.$operation expects another Generator")

  private final class GeneratorValue(
      val state: GeneratorState,
      context: NativePluginContext) extends Value.NamedMethodObj:
    def underlying: AnyRef = this

    private def derived(cleanup: () => Unit = () => state.cancel())(
        produce: Emitter => Unit): Value =
      make(context, cleanup)(produce)

    private def intArg(args: List[Value], operation: String): Int = args match
      case Value.IntV(value) :: Nil => Math.toIntExact(value)
      case _ => throw new IllegalArgumentException(s"Generator.$operation(n: Int)")

    private def drain(consume: Value => Unit): Unit =
      var next = state.pull()
      while next.nonEmpty do
        consume(next.get)
        next = state.pull()

    def getField(name: String): Option[Value] = name match
      case "next" => Some(closure(0) { _ =>
        state.pull().map(value => Value.DataV("Some", Vector(value)))
          .getOrElse(Value.DataV("None", Vector.empty))
      })
      case "toList" => Some(closure(0) { _ =>
        val values = mutable.ArrayBuffer.empty[Value]
        drain(values += _)
        list(values)
      })
      case "foreach" => Some(closure(1) {
        case fn :: Nil =>
          drain(value => invoke(context, "foreach", fn, value))
          Value.UnitV
        case _ => throw new IllegalArgumentException("Generator.foreach(callback)")
      })
      case "map" => Some(closure(1) {
        case fn :: Nil => derived() { emitter =>
          var next = state.pull()
          while next.nonEmpty do
            emitter.emit(invoke(context, "map", fn, next.get))
            next = state.pull()
        }
        case _ => throw new IllegalArgumentException("Generator.map(callback)")
      })
      case "filter" => Some(closure(1) {
        case fn :: Nil => derived() { emitter =>
          var next = state.pull()
          while next.nonEmpty do
            val value = next.get
            invoke(context, "filter", fn, value) match
              case Value.BoolV(true)  => emitter.emit(value)
              case Value.BoolV(false) => ()
              case _ => throw new IllegalArgumentException(
                "Generator.filter callback must return Boolean")
            next = state.pull()
        }
        case _ => throw new IllegalArgumentException("Generator.filter(callback)")
      })
      case "take" => Some(closure(1) { args =>
        val count = intArg(args, "take")
        derived() { emitter =>
          if count <= 0 then state.cancel()
          else
            var remaining = count
            var next = state.pull()
            while remaining > 0 && next.nonEmpty do
              emitter.emit(next.get)
              remaining -= 1
              if remaining > 0 then next = state.pull()
            if remaining == 0 then state.cancel()
        }
      })
      case "drop" => Some(closure(1) { args =>
        val count = math.max(0, intArg(args, "drop"))
        derived() { emitter =>
          var skipped = 0
          var next = state.pull()
          while skipped < count && next.nonEmpty do
            skipped += 1
            next = state.pull()
          while next.nonEmpty do
            emitter.emit(next.get)
            next = state.pull()
        }
      })
      case "flatMap" => Some(closure(1) {
        case fn :: Nil =>
          val inner = new java.util.concurrent.atomic.AtomicReference[GeneratorState](null)
          derived(() => {
            state.cancel()
            val active = inner.get()
            if active != null then active.cancel()
          }) { emitter =>
            var outer = state.pull()
            while outer.nonEmpty do
              val nested = generator(invoke(context, "flatMap", fn, outer.get), "flatMap")
              inner.set(nested.state)
              var next = nested.state.pull()
              while next.nonEmpty do
                emitter.emit(next.get)
                next = nested.state.pull()
              inner.set(null)
              outer = state.pull()
          }
        case _ => throw new IllegalArgumentException("Generator.flatMap(callback)")
      })
      case "zip" => Some(closure(1) {
        case other :: Nil =>
          val right = generator(other, "zip")
          derived(() => { state.cancel(); right.state.cancel() }) { emitter =>
            var running = true
            while running do
              state.pull() match
                case None => running = false
                case Some(left) =>
                  right.state.pull() match
                    case Some(value) => emitter.emit(tuple(left, value))
                    case None        => running = false
            state.cancel()
            right.state.cancel()
          }
        case _ => throw new IllegalArgumentException("Generator.zip(other)")
      })
      case "zipWithIndex" => Some(closure(0) { _ => derived() { emitter =>
        var index = 0L
        var next = state.pull()
        while next.nonEmpty do
          emitter.emit(tuple(next.get, Value.IntV(index)))
          index += 1L
          next = state.pull()
      }})
      case _ => None

  def install(context: NativePluginContext): Unit =
    context.registerGlobal("generator", 1) {
      case body :: Nil => make(context) { _ => invoke(context, "body", body) }
      case _ => throw new IllegalArgumentException("generator(body)")
    }
    context.registerGlobal("coroutineCreate", 1) {
      case body :: Nil => Value.ForeignV(CoroutineState(body, context))
      case _ => throw new IllegalArgumentException("coroutineCreate(body)")
    }
    context.registerGlobal("coroutineResume", 2) {
      case Value.ForeignV(state: CoroutineState) :: input :: Nil => state.resume(input)
      case _ => throw new IllegalArgumentException(
        "coroutineResume: invalid coroutine handle")
    }
    context.registerGlobal("coroutineCancel", 1) {
      case Value.ForeignV(state: CoroutineState) :: Nil =>
        state.cancel()
        Value.UnitV
      case _ => throw new IllegalArgumentException(
        "coroutineCancel: invalid coroutine handle")
    }
    context.registerGlobal("suspend", 1) {
      case value :: Nil =>
        val target = currentSuspendTarget.get()
        if target == null then
          throw new IllegalStateException(
            "suspend called outside a coroutine or generator body")
        target.suspend(value)
      case _ => throw new IllegalArgumentException("suspend(value)")
    }
