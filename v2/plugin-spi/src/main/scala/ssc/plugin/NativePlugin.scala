package ssc.plugin

import scalascript.interop.plugin.PluginCapabilityDeclaration
import ssc.Value

/** Core-free JDBC configuration passed from native root front-matter. */
final case class NativeDatabaseConfig(
    url: String,
    user: Option[String] = None,
    password: Option[String] = None,
    driver: Option[String] = None)

/** One immutable self-hosted content product from the linked module closure.
 *  `document` is a validated public `DocumentContent/6` value; providers must
 *  not reopen or reparse `source`. */
final case class NativeContentModule(
    source: String,
    explicitRoot: Boolean,
    directImports: List[String],
    namespace: String,
    document: Value)

/** Immutable configuration visible to one native provider installation. */
final case class NativeRuntimeConfig(
    databases: Map[String, NativeDatabaseConfig] = Map.empty,
    contentModules: List[NativeContentModule] = Nil)

/** Scalameta-free native intrinsic provider for the ScalaScript 2.1 runtime. */
trait NativePlugin:
  def id: String
  def capabilityDeclaration: Option[PluginCapabilityDeclaration] = None
  def install(context: NativePluginContext): Unit

/** The only standard-tier mutation surface exposed to native providers. */
trait NativePluginContext:
  def argv: List[String]
  def databases: Map[String, NativeDatabaseConfig]
  def contentModules: List[NativeContentModule]
  def invoke(fn: Value, args: List[Value]): Value
  /** Resolve a registered global (e.g. another plugin's native) by name, for cross-plugin
   *  construction — e.g. the content plugin building a real `signal(name, default)` via the
   *  ui plugin. Defaults to None so existing/mock contexts stay source-compatible. */
  def resolveGlobal(name: String): Option[Value] = None
  def withEffect(effectTag: String)(handler: (String, List[Value]) => Value)(body: => Value): Value
  def register(name: String)(fn: List[Value] => Value): Unit
  def registerGlobal(name: String, arity: Int)(fn: List[Value] => Value): Unit
  def registerValue(name: String, value: Value): Unit
  def registerTaggedApply(tag: String)(fn: List[Value] => Value): Unit
  def registerTaggedMethod(tag: String, name: String)(fn: List[Value] => Value): Unit
  def registerFields(tag: String, fields: Vector[String]): Unit
