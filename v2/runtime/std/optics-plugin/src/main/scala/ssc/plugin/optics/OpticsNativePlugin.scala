package ssc.plugin.optics

import ssc.{Done, Prims, Runtime, V2PluginRegistry, Value}
import ssc.plugin.{NativePlugin, NativePluginContext}

/** Core-free immutable structural optics over portable v2 values. */
final class OpticsNativePlugin extends NativePlugin:
  def id: String = "63-optics"

  private final class OpticSteps(val steps: List[Value])

  private def list(values: IterableOnce[Value]): Value =
    Vector.from(values).reverseIterator.foldLeft[Value](Value.DataV("Nil", Vector.empty)) {
      (tail, head) => Value.DataV("Cons", Vector(head, tail))
    }

  private def unlist(value: Value): List[Value] = Prims.unlistPub(value)

  private def field(value: Value, name: String): Option[Value] = value match
    case Value.DataV(tag, fields) =>
      V2PluginRegistry.lookupFieldNames(tag, fields.length).flatMap { names =>
        val index = names.indexOf(name)
        if index >= 0 && index < fields.length then Some(fields(index)) else None
      }
    case _ => None

  private def step(value: Value, pathStep: Value): Option[Value] = pathStep match
    case Value.DataV("OField", IndexedSeq(Value.StrV(name))) => field(value, name)
    case Value.DataV("OSome", IndexedSeq()) => value match
      case Value.DataV("Some", IndexedSeq(inner)) => Some(inner)
      case _ => None
    case _ => None

  private def getOption(target: Value, steps: List[Value]): Option[Value] = steps match
    case Nil => Some(target)
    case head :: tail => step(target, head).flatMap(getOption(_, tail))

  private def rebuildField(target: Value, name: String, replacement: Value): Value = target match
    case Value.DataV(tag, fields) =>
      V2PluginRegistry.lookupFieldNames(tag, fields.length) match
        case Some(names) =>
          val index = names.indexOf(name)
          if index >= 0 && index < fields.length then
            Value.DataV(tag, fields.updated(index, replacement))
          else target
        case None => target
    case _ => target

  private def setPath(target: Value, steps: List[Value], replacement: Value): Value = steps match
    case Nil => replacement
    case Value.DataV("OField", IndexedSeq(Value.StrV(name))) :: tail =>
      field(target, name)
        .map(current => rebuildField(target, name, setPath(current, tail, replacement)))
        .getOrElse(target)
    case Value.DataV("OSome", IndexedSeq()) :: tail => target match
      case Value.DataV("Some", IndexedSeq(inner)) =>
        Value.DataV("Some", Vector(setPath(inner, tail, replacement)))
      case _ => target
    case _ => target

  private def getAll(target: Value, steps: List[Value]): List[Value] = steps match
    case Nil => List(target)
    case Value.DataV("OEach", IndexedSeq()) :: tail =>
      unlist(target).flatMap(getAll(_, tail))
    case head :: tail => step(target, head).toList.flatMap(getAll(_, tail))

  private def modifyAll(
      target: Value,
      steps: List[Value],
      update: Value => Value): Value = steps match
    case Nil => update(target)
    case Value.DataV("OEach", IndexedSeq()) :: tail =>
      list(unlist(target).map(modifyAll(_, tail, update)))
    case Value.DataV("OField", IndexedSeq(Value.StrV(name))) :: tail =>
      field(target, name)
        .map(current => rebuildField(target, name, modifyAll(current, tail, update)))
        .getOrElse(target)
    case Value.DataV("OSome", IndexedSeq()) :: tail => target match
      case Value.DataV("Some", IndexedSeq(inner)) =>
        Value.DataV("Some", Vector(modifyAll(inner, tail, update)))
      case _ => target
    case _ => target

  private def isTraversal(steps: List[Value]): Boolean = steps.exists {
    case Value.DataV("OEach", _) => true
    case _ => false
  }

  private def isPartial(steps: List[Value]): Boolean = steps.exists {
    case Value.DataV("OSome", _) | Value.DataV("OEach", _) => true
    case _ => false
  }

  private def kind(steps: List[Value]): String =
    if isTraversal(steps) then "Traversal"
    else if isPartial(steps) then "Optional"
    else "Lens"

  private def closure(body: Array[Value] => Value): Value.ClosV =
    Value.ClosV(Runtime.emptyEnv, -1, env => Done(body(env)))

  private def optic(context: NativePluginContext, steps: List[Value]): Value =
    Value.ForeignV(new Value.NamedMethodObj:
      def underlying: AnyRef = new OpticSteps(steps)
      override def toString: String = kind(steps)

      def getField(name: String): Option[Value] = name match
        case "_show" => Some(Value.StrV(kind(steps)))
        case "get" => Some(closure { env =>
          getOption(env(0), steps).getOrElse(
            throw new IllegalArgumentException("optic.get: path missing"))
        })
        case "getOption" => Some(closure { env =>
          getOption(env(0), steps)
            .map(value => Value.DataV("Some", Vector(value)))
            .getOrElse(Value.DataV("None", Vector.empty))
        })
        case "set" => Some(closure { env =>
          if isTraversal(steps) then modifyAll(env(0), steps, _ => env(1))
          else setPath(env(0), steps, env(1))
        })
        case "modify" | "modifyAll" => Some(closure { env =>
          val update = (value: Value) => context.invoke(env(1), List(value))
          if isTraversal(steps) then modifyAll(env(0), steps, update)
          else getOption(env(0), steps)
            .map(current => setPath(env(0), steps, update(current)))
            .getOrElse(env(0))
        })
        case "getAll" => Some(closure(env => list(getAll(env(0), steps))))
        case "andThen" => Some(closure { env => env(0) match
          case Value.ForeignV(other: Value.NamedMethodObj) => other.underlying match
            case next: OpticSteps => optic(context, steps ++ next.steps)
            case _ => throw new IllegalArgumentException("optic.andThen: not an optic")
          case _ => throw new IllegalArgumentException("optic.andThen: not an optic")
        })
        case "isPartial" => Some(Value.BoolV(isPartial(steps)))
        case _ => None
    )

  private def prism(context: NativePluginContext, variant: String): Value =
    def matches(value: Value): Boolean = value match
      case Value.DataV(tag, _) => tag == variant
      case _ => false

    Value.ForeignV(new Value.NamedMethodObj:
      def underlying: AnyRef = ("Prism", variant)
      override def toString: String = s"Prism[?, $variant]"

      def getField(name: String): Option[Value] = name match
        case "getOption" => Some(closure { env =>
          if matches(env(0)) then Value.DataV("Some", Vector(env(0)))
          else Value.DataV("None", Vector.empty)
        })
        case "reverseGet" => Some(closure(env => env(0)))
        case "set" => Some(closure(env => if matches(env(0)) then env(1) else env(0)))
        case "modify" => Some(closure { env =>
          if matches(env(0)) then context.invoke(env(1), List(env(0))) else env(0)
        })
        case "_variant" => Some(Value.StrV(variant))
        case "_show" => Some(Value.StrV(s"Prism[?, $variant]"))
        case _ => None
    )

  def install(context: NativePluginContext): Unit =
    context.register("optics.focus") {
      case steps :: Nil => optic(context, unlist(steps))
      case _ => throw new IllegalArgumentException("optics.focus(steps)")
    }
    context.register("optics.prism") {
      case Value.StrV(variant) :: Nil => prism(context, variant)
      case _ => throw new IllegalArgumentException("optics.prism(variant)")
    }
