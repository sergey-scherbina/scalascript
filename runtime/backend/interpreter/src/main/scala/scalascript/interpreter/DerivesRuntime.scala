package scalascript.interpreter

import scala.collection.mutable
import Computation.Pure

/** Synthesizes `derives` typeclass instances (Eq, Show, Hash, Order, custom).
 *
 *  Structural helpers live here so they can be reused across all four typeclasses
 *  without touching Interpreter directly.  Each method that needs the field-order
 *  registry or globals receives `interp: Interpreter`.
 */
private[interpreter] object DerivesRuntime:

  def synthesizeDerivedInstance(
    typeName:   String,
    fieldNames: List[String],
    tcName:     String,
    env:        mutable.Map[String, Value],
    interp:     Interpreter
  ): Unit =
    val typeKey = s"$tcName[$typeName]"
    val instance: Value = tcName match

      case "Eq" =>
        Value.InstanceV("Eq", Map(
          "eqv"  -> Value.NativeFnV("Eq.eqv",  {
            case List(a, b) => Pure(Value.boolV(structuralEq(a, b)))
            case _          => Pure(Value.False)
          }),
          "neqv" -> Value.NativeFnV("Eq.neqv", {
            case List(a, b) => Pure(Value.boolV(!structuralEq(a, b)))
            case _          => Pure(Value.True)
          })
        ))

      case "Show" =>
        Value.InstanceV("Show", Map(
          "show" -> Value.NativeFnV("Show.show", {
            case List(v) => Pure(Value.StringV(structuralShow(v, interp)))
            case _       => Pure(Value.StringV(""))
          })
        ))

      case "Hash" =>
        Value.InstanceV("Hash", Map(
          "hash" -> Value.NativeFnV("Hash.hash", {
            case List(v) => Pure(Value.intV(structuralHash(v, interp).toLong))
            case _       => Pure(Value.intV(0))
          })
        ))

      case "Order" =>
        Value.InstanceV("Order", Map(
          "compare" -> Value.NativeFnV("Order.compare", {
            case List(a, b) => Pure(Value.intV(structuralCompare(a, b, interp).toLong))
            case _          => Pure(Value.intV(0))
          }),
          "lt"  -> Value.NativeFnV("Order.lt",  { case List(a, b) => Pure(Value.boolV(structuralCompare(a, b, interp) < 0));  case _ => Pure(Value.False) }),
          "gt"  -> Value.NativeFnV("Order.gt",  { case List(a, b) => Pure(Value.boolV(structuralCompare(a, b, interp) > 0));  case _ => Pure(Value.False) }),
          "lte" -> Value.NativeFnV("Order.lte", { case List(a, b) => Pure(Value.boolV(structuralCompare(a, b, interp) <= 0)); case _ => Pure(Value.False) }),
          "gte" -> Value.NativeFnV("Order.gte", { case List(a, b) => Pure(Value.boolV(structuralCompare(a, b, interp) >= 0)); case _ => Pure(Value.False) }),
          "min" -> Value.NativeFnV("Order.min", { case List(a, b) => Pure(if structuralCompare(a, b, interp) <= 0 then a else b); case _ => Pure(Value.UnitV) }),
          "max" -> Value.NativeFnV("Order.max", { case List(a, b) => Pure(if structuralCompare(a, b, interp) >= 0 then a else b); case _ => Pure(Value.UnitV) })
        ))

      case _ =>
        // Unknown typeclass — try looking up TC.derived in globals
        interp.globals.get(tcName) match
          case Some(tcObj: Value.InstanceV) =>
            tcObj.fields.get("derived") match
              case Some(fn) =>
                val mirror = Value.InstanceV("Mirror", Map(
                  "label"  -> Value.StringV(typeName),
                  "fields" -> Value.ListV(fieldNames.map(Value.StringV.apply))
                ))
                Computation.run(interp.callValue(fn, List(mirror), Map.empty))
              case None => Value.UnitV
          case _ => Value.UnitV

    if instance != Value.UnitV then
      env(typeKey) = instance
      interp.globals(typeKey) = instance

  private def structuralEq(a: Value, b: Value): Boolean = (a, b) match
    case (Value.IntV(x),     Value.IntV(y))     => x == y
    case (Value.DoubleV(x),  Value.DoubleV(y))  => x == y
    case (Value.StringV(x),  Value.StringV(y))  => x == y
    case (Value.BoolV(x),    Value.BoolV(y))    => x == y
    case (Value.UnitV,       Value.UnitV)       => true
    case (Value.ListV(xs),   Value.ListV(ys))   =>
      xs.length == ys.length && xs.zip(ys).forall { case (x, y) => structuralEq(x, y) }
    case (Value.InstanceV(t1, f1), Value.InstanceV(t2, f2)) =>
      t1 == t2 && f1.keySet == f2.keySet && f1.keys.forall(k => structuralEq(f1(k), f2(k)))
    case _ => a == b

  private def structuralShow(v: Value, interp: Interpreter): String = v match
    case Value.InstanceV(typeName, fields) =>
      if fields.isEmpty then typeName
      else
        val fieldStr = interp.typeFieldOrder.get(typeName) match
          case Some(order) => order.map(k => s"$k=${structuralShow(fields.getOrElse(k, Value.UnitV), interp)}").mkString(", ")
          case None        => fields.map { case (k, v) => s"$k=${structuralShow(v, interp)}" }.mkString(", ")
        s"$typeName($fieldStr)"
    case _ => Value.show(v)

  private def structuralHash(v: Value, interp: Interpreter): Int = v match
    case Value.IntV(n)    => n.##
    case Value.DoubleV(d) => d.##
    case Value.StringV(s) => s.##
    case Value.BoolV(b)   => b.##
    case Value.UnitV      => 0
    case Value.ListV(xs)  => xs.foldLeft(1)((acc, x) => acc * 31 + structuralHash(x, interp))
    case Value.InstanceV(typeName, fields) =>
      val fieldHashes = interp.typeFieldOrder.get(typeName) match
        case Some(order) => order.map(k => structuralHash(fields.getOrElse(k, Value.UnitV), interp))
        case None        => fields.values.map(structuralHash(_, interp)).toList
      fieldHashes.foldLeft(typeName.##)((acc, h) => acc * 31 + h)
    case _ => v.##

  private def structuralCompare(a: Value, b: Value, interp: Interpreter): Int = (a, b) match
    case (Value.IntV(x),    Value.IntV(y))    => x.compareTo(y)
    case (Value.DoubleV(x), Value.DoubleV(y)) => x.compareTo(y)
    case (Value.StringV(x), Value.StringV(y)) => x.compareTo(y)
    case (Value.BoolV(x),   Value.BoolV(y))   => x.compareTo(y)
    case (Value.InstanceV(t1, f1), Value.InstanceV(t2, f2)) if t1 == t2 =>
      interp.typeFieldOrder.get(t1) match
        case Some(order) =>
          order.iterator.map { k =>
            structuralCompare(f1.getOrElse(k, Value.UnitV), f2.getOrElse(k, Value.UnitV), interp)
          }.find(_ != 0).getOrElse(0)
        case None => 0
    case _ => 0
