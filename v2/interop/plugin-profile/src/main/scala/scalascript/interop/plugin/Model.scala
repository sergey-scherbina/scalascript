package scalascript.interop.plugin

import scalascript.interop.descriptor.ImplementationDigest

object PluginProfileVersions:
  val Schema: String = "1.0"
  val CanonicalFraming: String = "ssc-plugin-profile-framing/1"

final case class SemanticProvision(
    logicalId: String,
    semanticAbiId: String,
    schemaId: Option[String] = None,
    capabilities: Vector[String] = Vector.empty)

final case class PluginRequirement(
    pluginId: String,
    semanticAbiId: String,
    schemaId: Option[String] = None,
    requiredCapabilities: Vector[String] = Vector.empty)

final case class PluginCapabilityDeclaration(
    schemaVersion: String,
    pluginId: String,
    provisions: Vector[SemanticProvision],
    providedCapabilities: Vector[String] = Vector.empty,
    dependencies: Vector[PluginRequirement] = Vector.empty)

final case class PluginTargetImplementation(
    target: String,
    targetAbi: String,
    implementationDigest: ImplementationDigest,
    requiredCapabilities: Vector[String] = Vector.empty)

final case class PluginCapabilityProfile(
    declaration: PluginCapabilityDeclaration,
    aggregateSemanticAbiId: String,
    aggregateSchemaId: Option[String],
    implementation: PluginTargetImplementation)

final case class PluginProfileError(
    code: String,
    path: String,
    message: String)
