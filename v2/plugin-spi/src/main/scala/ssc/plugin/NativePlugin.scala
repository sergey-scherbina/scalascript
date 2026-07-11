package ssc.plugin

import ssc.Value

/** Core-free JDBC configuration passed from native root front-matter. */
final case class NativeDatabaseConfig(
    url: String,
    user: Option[String] = None,
    password: Option[String] = None,
    driver: Option[String] = None)

/** Immutable configuration visible to one native provider installation. */
final case class NativeRuntimeConfig(
    databases: Map[String, NativeDatabaseConfig] = Map.empty)

/** Scalameta-free native intrinsic provider for the ScalaScript 2.1 runtime. */
trait NativePlugin:
  def id: String
  def install(context: NativePluginContext): Unit

/** The only standard-tier mutation surface exposed to native providers. */
trait NativePluginContext:
  def argv: List[String]
  def databases: Map[String, NativeDatabaseConfig]
  def invoke(fn: Value, args: List[Value]): Value
  def withEffect(effectTag: String)(handler: (String, List[Value]) => Value)(body: => Value): Value
  def register(name: String)(fn: List[Value] => Value): Unit
  def registerGlobal(name: String, arity: Int)(fn: List[Value] => Value): Unit
  def registerValue(name: String, value: Value): Unit
  def registerTaggedApply(tag: String)(fn: List[Value] => Value): Unit
  def registerTaggedMethod(tag: String, name: String)(fn: List[Value] => Value): Unit
  def registerFields(tag: String, fields: Vector[String]): Unit
