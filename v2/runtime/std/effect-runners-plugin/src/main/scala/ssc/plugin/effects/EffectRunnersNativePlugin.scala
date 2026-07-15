package ssc.plugin.effects

import java.util.concurrent.{CompletableFuture, CompletionException}
import scala.collection.mutable.ListBuffer
import ssc.{Done, PortableEffects, Prims, Runtime, Value}
import ssc.plugin.{NativePlugin, NativePluginContext}

/** Core-free standard Logger, Stream, and Async runners. */
final class EffectRunnersNativePlugin extends NativePlugin:
  def id: String = "56-effect-runners"

  private final class CollectedSource(items: Value) extends Value.NamedMethodObj:
    def getField(name: String): Option[Value] = name match
      case "runToList" => Some(closure(0)(_ => items))
      case _ => None
    def underlying: AnyRef = this

  private final class AsyncFuture(val completion: CompletableFuture[Value])

  private def closure(arity: Int)(fn: List[Value] => Value): Value.ClosV =
    Value.ClosV(Runtime.emptyEnv, arity, env => Done(fn(env.toList)))

  private def invoke(context: NativePluginContext, fn: Value, args: Value*): Value =
    context.invoke(fn, args.toList)

  private def handle(computation: Value)(
      handler: PartialFunction[Value, Value]
  ): Value =
    PortableEffects.handle(computation, Runtime.handlerPartialFunction(handler))

  private def list(values: List[Value]): Value =
    values.foldRight[Value](Value.DataV("Nil", Vector.empty)) { (value, tail) =>
      Value.DataV("Cons", Vector(value, tail))
    }

  private def rootMessage(error: Throwable): String =
    var root = error
    while root.getCause != null && root.getCause != root do root = root.getCause
    Option(root.getMessage).filter(_.nonEmpty).getOrElse(root.getClass.getSimpleName)

  private def asyncFailure(operation: String, error: Throwable): IllegalStateException =
    new IllegalStateException(s"Async.$operation failed: ${rootMessage(error)}", error)

  private def awaitFuture(value: Value, operation: String): Value = value match
    case Value.ForeignV(future: AsyncFuture) =>
      try future.completion.join()
      catch
        case error: CompletionException =>
          throw asyncFailure(operation, Option(error.getCause).getOrElse(error))
    case _ => throw new IllegalArgumentException(s"Async.$operation expects an Async future")

  private def asyncRunner(context: NativePluginContext, parallel: Boolean): Value =
    lazy val handler: (String, List[Value]) => Value =
      case ("delay", Value.IntV(milliseconds) :: Nil) =>
        if milliseconds > 0 then
          try Thread.sleep(milliseconds)
          catch case error: InterruptedException =>
            Thread.currentThread().interrupt()
            throw asyncFailure("delay", error)
        Value.UnitV
      case ("delay", _) => throw new IllegalArgumentException("Async.delay(ms: Int)")
      case ("async", thunk :: Nil) =>
        val completion = new CompletableFuture[Value]()
        def execute(): Unit =
          try completion.complete(context.withEffect("Async")(handler) {
            invoke(context, thunk)
          })
          catch case error: Throwable => completion.completeExceptionally(error)
        if parallel then Thread.ofVirtual().name("ssc-async").start(() => execute())
        else execute()
        Value.ForeignV(AsyncFuture(completion))
      case ("async", _) => throw new IllegalArgumentException("Async.async(thunk)")
      case ("await", future :: Nil) => awaitFuture(future, "await")
      case ("await", _) => throw new IllegalArgumentException("Async.await(future)")
      case ("parallel", thunks :: Nil) =>
        val callbacks =
          try Prims.unlistPub(thunks)
          catch case error: Throwable =>
            throw new IllegalArgumentException("Async.parallel(thunks: List[() => A])", error)
        if parallel then
          val futures = callbacks.map { thunk =>
            handler("async", List(thunk))
          }
          list(futures.map(future => awaitFuture(future, "parallel")))
        else
          list(callbacks.map { thunk =>
            try context.withEffect("Async")(handler) { invoke(context, thunk) }
            catch case error: Throwable => throw asyncFailure("parallel", error)
          })
      case ("parallel", _) =>
        throw new IllegalArgumentException("Async.parallel(thunks: List[() => A])")
      case ("recvFrom", Value.ForeignV(obj: Value.NamedMethodObj) :: Nil) =>
        obj.getField("recv") match
          case Some(recv) =>
            try invoke(context, recv)
            catch case error: Throwable => throw asyncFailure("recvFrom", error)
          case None => throw new IllegalArgumentException("Async.recvFrom: receiver has no recv method")
      case ("recvFrom", _) =>
        throw new IllegalArgumentException("Async.recvFrom(ws: named method object)")
      case (operation, _) => throw new IllegalArgumentException(s"unknown native Async operation: $operation")

    closure(1) {
      case thunk :: Nil => context.withEffect("Async")(handler) {
        invoke(context, thunk)
      }
      case _ => throw new IllegalArgumentException(
        if parallel then "runAsyncParallel(body)" else "runAsync(body)")
    }

  /** RFC-4122 v4 UUID from `rng` (parity with the v1 RandomHandler). */
  private def uuidV4(rng: java.util.Random): String =
    val bytes = new Array[Byte](16)
    rng.nextBytes(bytes)
    bytes(6) = ((bytes(6) & 0x0f) | 0x40).toByte
    bytes(8) = ((bytes(8) & 0x3f) | 0x80).toByte
    def hex(b: Byte) = f"${b & 0xff}%02x"
    val u = bytes.map(hex).mkString
    s"${u.take(8)}-${u.slice(8, 12)}-${u.slice(12, 16)}-${u.slice(16, 20)}-${u.drop(20)}"

  def install(context: NativePluginContext): Unit =
    // `Random` capability — ambient (root default) handler. On the v1
    // interpreter/JS/JVM backends `Random` is a root-installed default handler,
    // so a bare `Random.uuid()` with no explicit `runRandom { … }` resolves; the
    // native lane otherwise leaks `unhandled runtime effect: Random.uuid`. These
    // register as tag-qualified globals (`Random.uuid`), which the method-dispatch
    // path (Runtime effectTag.name lookup) resolves IN PLACE — after any ACTIVE
    // handler (`V2EffectContext.peek`) — so the value is produced at the call site
    // and the surrounding dynamic scope (e.g. `runActors`) is preserved.
    val rng = new java.util.Random()
    context.register("Random.uuid") { _ => Value.StrV(uuidV4(rng)) }
    context.register("Random.nextInt") {
      case List(Value.IntV(n)) if n > 0 => Value.IntV(rng.nextInt(n.toInt).toLong)
      case _ => throw new IllegalArgumentException("Random.nextInt(n: Int) — n must be > 0")
    }
    context.register("Random.nextDouble") { _ => Value.FloatV(rng.nextDouble()) }
    context.register("Random.pick") {
      case List(xs) =>
        val items = Prims.unlistPub(xs)
        if items.isEmpty then
          throw new IllegalArgumentException("Random.pick(xs: List[A]) — list must be non-empty")
        items(rng.nextInt(items.length))
      case _ => throw new IllegalArgumentException("Random.pick(xs: List[A])")
    }

    val loggerLevels = List("trace", "debug", "info", "warn", "error")
    loggerLevels.foreach { level =>
      context.registerGlobal(s"Logger_$level", 1) {
        case List(message) => PortableEffects.perform(s"Logger.$level", List(message))
        case _ => throw new IllegalArgumentException(s"Logger.$level(message)")
      }
    }

    context.registerValue("runLogger", closure(1) {
      case List(thunk) =>
        handle(invoke(context, thunk)) {
          case Value.DataV("log", IndexedSeq(Value.StrV(message), resume)) =>
            println(s"[LOG] $message")
            invoke(context, resume, Value.UnitV)
          case Value.DataV(level, IndexedSeq(Value.StrV(message), resume))
              if loggerLevels.contains(level) =>
            println(s"[${level.toUpperCase}] $message")
            invoke(context, resume, Value.UnitV)
          case Value.DataV("Return", IndexedSeq(value)) => value
        }
      case _ => throw new IllegalArgumentException("runLogger(body)")
    })

    context.registerValue("runLoggerToList", closure(1) {
      case List(thunk) =>
        val messages = ListBuffer.empty[Value]
        handle(invoke(context, thunk)) {
          case Value.DataV("log", IndexedSeq(Value.StrV(message), resume)) =>
            messages += Value.DataV("Tuple2", Vector(Value.StrV("log"), Value.StrV(message)))
            invoke(context, resume, Value.UnitV)
          case Value.DataV(level, IndexedSeq(Value.StrV(message), resume))
              if loggerLevels.contains(level) =>
            messages += Value.DataV("Tuple2", Vector(Value.StrV(level), Value.StrV(message)))
            invoke(context, resume, Value.UnitV)
          case Value.DataV("Return", IndexedSeq(value)) =>
            Value.DataV("Tuple2", Vector(value, list(messages.toList)))
        }
      case _ => throw new IllegalArgumentException("runLoggerToList(body)")
    })

    context.registerValue("runStream", closure(1) {
      case List(thunk) =>
        val emitted = ListBuffer.empty[Value]
        def result(value: Value): Value =
          val items = list(emitted.toList)
          Value.DataV("Tuple2", Vector(Value.ForeignV(CollectedSource(items)), value))
        handle(invoke(context, thunk)) {
          case Value.DataV("emit", IndexedSeq(value, resume)) =>
            emitted += value
            invoke(context, resume, Value.UnitV)
          case Value.DataV("complete", IndexedSeq(resume)) =>
            result(Value.UnitV)
          case Value.DataV("Return", IndexedSeq(value)) =>
            result(value)
        }
      case _ => throw new IllegalArgumentException("runStream(body)")
    })

    context.registerValue("runAsync", asyncRunner(context, parallel = false))
    context.registerValue("runAsyncParallel", asyncRunner(context, parallel = true))
