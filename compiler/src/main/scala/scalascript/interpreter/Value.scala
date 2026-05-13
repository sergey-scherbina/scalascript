package scalascript.interpreter

import scala.meta.Term

type Env = Map[String, Value]

enum Value:
  case IntV(v: Long)
  case DoubleV(v: Double)
  case StringV(v: String)
  case BoolV(v: Boolean)
  case CharV(v: Char)
  case UnitV
  case NullV
  /** Scala closure: parameter names, body tree, captured environment. */
  case FunV(params: List[String], body: Term, closure: Env)
  /** Built-in function. */
  case NativeFnV(name: String, f: List[Value] => Value)
  /** Case class / object instance. */
  case InstanceV(typeName: String, fields: Map[String, Value])
  case ListV(items: List[Value])
  case OptionV(inner: Option[Value])
  case TupleV(elems: List[Value])

object Value:
  def show(v: Value): String = v match
    case IntV(n)              => n.toString
    case DoubleV(d)           => if d == d.toLong.toDouble then d.toLong.toString else d.toString
    case StringV(s)           => s
    case BoolV(b)             => b.toString
    case CharV(c)             => c.toString
    case UnitV                => "()"
    case NullV                => "null"
    case ListV(items)         => items.map(show).mkString("List(", ", ", ")")
    case OptionV(None)        => "None"
    case OptionV(Some(v))     => s"Some(${show(v)})"
    case TupleV(elems)        => elems.map(show).mkString("(", ", ", ")")
    case InstanceV(t, fields) =>
      if fields.isEmpty then t
      else fields.values.map(show).mkString(s"$t(", ", ", ")")
    case FunV(ps, _, _)       => s"<function(${ps.length})>"
    case NativeFnV(name, _)   => s"<native:$name>"

class InterpretError(msg: String) extends RuntimeException(msg)
private[interpreter] class ReturnSignal(val value: Value) extends Exception
