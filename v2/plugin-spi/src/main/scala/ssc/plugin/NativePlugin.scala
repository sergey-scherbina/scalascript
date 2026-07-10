package ssc.plugin

import ssc.Value

/** Scalameta-free native intrinsic provider for the ScalaScript 2.1 runtime. */
trait NativePlugin:
  def id: String
  def install(context: NativePluginContext): Unit

/** The only standard-tier mutation surface exposed to native providers. */
trait NativePluginContext:
  def argv: List[String]
  def invoke(fn: Value, args: List[Value]): Value
  def register(name: String)(fn: List[Value] => Value): Unit
  def registerGlobal(name: String, arity: Int)(fn: List[Value] => Value): Unit
  def registerValue(name: String, value: Value): Unit
  def registerFields(tag: String, fields: Vector[String]): Unit
