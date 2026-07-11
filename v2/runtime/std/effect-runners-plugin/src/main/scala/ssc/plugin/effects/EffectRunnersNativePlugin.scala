package ssc.plugin.effects

import scala.collection.mutable.ListBuffer
import ssc.{Done, PortableEffects, Runtime, Value}
import ssc.plugin.{NativePlugin, NativePluginContext}

/** Core-free standard Logger and Stream runners over the portable Op protocol. */
final class EffectRunnersNativePlugin extends NativePlugin:
  def id: String = "56-effect-runners"

  private final class CollectedSource(items: Value) extends Value.NamedMethodObj:
    def getField(name: String): Option[Value] = name match
      case "runToList" => Some(closure(0)(_ => items))
      case _ => None
    def underlying: AnyRef = this

  private def closure(arity: Int)(fn: List[Value] => Value): Value.ClosV =
    Value.ClosV(Runtime.emptyEnv, arity, env => Done(fn(env.toList)))

  private def invoke(context: NativePluginContext, fn: Value, args: Value*): Value =
    context.invoke(fn, args.toList)

  private def handle(computation: Value)(handler: Value => Value): Value =
    PortableEffects.handle(computation, closure(1) {
      case List(event) => handler(event)
      case args => throw new IllegalArgumentException(s"effect handler event: $args")
    })

  private def list(values: List[Value]): Value =
    values.foldRight[Value](Value.DataV("Nil", Vector.empty)) { (value, tail) =>
      Value.DataV("Cons", Vector(value, tail))
    }

  def install(context: NativePluginContext): Unit =
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
          case other => throw new IllegalArgumentException(s"runLogger: unsupported event $other")
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
          case other => throw new IllegalArgumentException(s"runLoggerToList: unsupported event $other")
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
          case other => throw new IllegalArgumentException(s"runStream: unsupported event $other")
        }
      case _ => throw new IllegalArgumentException("runStream(body)")
    })
