package scalascript.interpreter.vm.jit

import scalascript.interpreter.Value

/** Narrow helpers for JIT-emitted method calls on ref-valued expressions.
 *
 *  The helpers deliberately cover only pure numeric reads. Unsupported receiver
 *  or result shapes throw, and JitRuntime falls back to the interpreter. */
object JitRefDispatch:

  private def longValue(v: Value): Long = v match
    case Value.IntV(n) => n
    case other         => throw new ClassCastException(s"expected IntV, got ${other.getClass.getName}")

  private def instanceValue(inst: Value.InstanceV): Value =
    val arr = inst.fieldsArr
    if arr != null && arr.nonEmpty then arr(0)
    else inst.fields.getOrElse("value", Value.UnitV)

  def getOrElseLong(recv: AnyRef, defaultValue: Long): Long = recv match
    case opt: Value.OptionV =>
      val inner = opt.inner
      if inner == null then defaultValue else longValue(inner)
    case inst: Value.InstanceV if inst.typeName == "Right" =>
      longValue(instanceValue(inst))
    case inst: Value.InstanceV if inst.typeName == "Left" =>
      defaultValue
    case other =>
      throw new ClassCastException(s"getOrElseLong unsupported receiver: ${other.getClass.getName}")

  def sizeLong(recv: AnyRef): Long = recv match
    case Value.ListV(items)    => items.length.toLong
    case Value.MapV(entries)   => entries.size.toLong
    case Value.SetV(items)     => items.size.toLong
    case Value.TupleV(elems)   => elems.length.toLong
    case Value.StringV(value)  => value.length.toLong
    case other =>
      throw new ClassCastException(s"sizeLong unsupported receiver: ${other.getClass.getName}")

  def headLong(recv: AnyRef): Long = recv match
    case Value.ListV(head :: _)  => longValue(head)
    case Value.TupleV(head :: _) => longValue(head)
    case other =>
      throw new ClassCastException(s"headLong unsupported receiver: ${other.getClass.getName}")

  def getOrElseRef(recv: AnyRef, defaultValue: Value): Object = recv match
    case opt: Value.OptionV =>
      val inner = opt.inner
      if inner == null then defaultValue.asInstanceOf[Object] else inner.asInstanceOf[Object]
    case inst: Value.InstanceV if inst.typeName == "Right" =>
      instanceValue(inst).asInstanceOf[Object]
    case inst: Value.InstanceV if inst.typeName == "Left" =>
      defaultValue.asInstanceOf[Object]
    case other =>
      throw new ClassCastException(s"getOrElseRef unsupported receiver: ${other.getClass.getName}")

  def mapGetOrElseRef(recv: AnyRef, key: Value, defaultValue: Value): Object = recv match
    case Value.MapV(entries) =>
      entries.getOrElse(key, defaultValue).asInstanceOf[Object]
    case other =>
      throw new ClassCastException(s"mapGetOrElseRef unsupported receiver: ${other.getClass.getName}")

  def mkStringRef(recv: AnyRef): Object = recv match
    case Value.ListV(items) =>
      Value.StringV(items.iterator.map(Value.show).mkString).asInstanceOf[Object]
    case other =>
      throw new ClassCastException(s"mkStringRef unsupported receiver: ${other.getClass.getName}")

  def mkStringRef(recv: AnyRef, sep: String): Object = recv match
    case Value.ListV(items) =>
      Value.StringV(items.iterator.map(Value.show).mkString(sep)).asInstanceOf[Object]
    case other =>
      throw new ClassCastException(s"mkStringRef unsupported receiver: ${other.getClass.getName}")

  def mkStringRef(recv: AnyRef, start: String, sep: String, end: String): Object = recv match
    case Value.ListV(items) =>
      Value.StringV(items.iterator.map(Value.show).mkString(start, sep, end)).asInstanceOf[Object]
    case other =>
      throw new ClassCastException(s"mkStringRef unsupported receiver: ${other.getClass.getName}")
