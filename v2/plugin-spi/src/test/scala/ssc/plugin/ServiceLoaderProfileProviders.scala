package ssc.plugin

import scalascript.interop.plugin.*
import ssc.Value

final class ProfiledServicePlugin extends NativePlugin:
  def id: String = "service-profiled"
  override def capabilityDeclaration: Option[PluginCapabilityDeclaration] = Some(
    PluginCapabilityDeclaration(
      PluginProfileVersions.Schema,
      id,
      Vector(SemanticProvision("service", "test.service@1"))))
  def install(context: NativePluginContext): Unit =
    context.registerValue("serviceProfiled", Value.IntV(2))
