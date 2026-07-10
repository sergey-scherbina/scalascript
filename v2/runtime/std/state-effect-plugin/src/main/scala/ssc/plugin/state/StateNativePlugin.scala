package ssc.plugin.state

import ssc.{Done, Runtime, Value}
import ssc.plugin.{NativePlugin, NativePluginContext}

/** Core-free dynamically scoped State effect for the standard native runtime. */
final class StateNativePlugin extends NativePlugin:
  def id: String = "57-state-effect"

  private def closure(arity: Int)(fn: List[Value] => Value): Value.ClosV =
    Value.ClosV(Runtime.emptyEnv, arity, env => Done(fn(env.toList)))

  def install(context: NativePluginContext): Unit =
    context.registerValue("State", Value.DataV("State", Vector.empty))
    context.registerFields("Pair", Vector("_1", "_2"))

    val runState = closure(1) {
      case List(initial) => closure(1) {
        case List(thunk) =>
          var current = initial
          val handler: (String, List[Value]) => Value =
            case ("get", Nil) => current
            case ("set", List(next)) => current = next; Value.UnitV
            case ("modify", List(fn)) => current = context.invoke(fn, List(current)); Value.UnitV
            case (operation, _) =>
              throw new IllegalArgumentException(s"unknown native State operation: $operation")
          val bodyResult = context.withEffect("State")(handler) {
            context.invoke(thunk, Nil)
          }
          Value.DataV("Pair", Vector(current, bodyResult))
        case _ => throw new IllegalArgumentException("runState(initial)(body)")
      }
      case _ => throw new IllegalArgumentException("runState(initial)(body)")
    }
    context.registerValue("runState", runState)
