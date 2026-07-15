package scalascript.interop.plugin

import java.nio.charset.StandardCharsets
import org.scalatest.funsuite.AnyFunSuite
import scalascript.interop.descriptor.*

class PluginProfileInventorySuite extends AnyFunSuite:
  private val target = TargetProfile("jvm", "jvm-21", Vector("host.threads"))

  test("inventory validates exact bindings and returns deterministic dependency-first order") {
    val dependency = profile(
      "plugin.database",
      provided = Vector("cap.database"),
      implementationRequired = Vector("host.socket"))
    val consumer = profile(
      "plugin.app",
      dependencies = Vector(requirement(dependency, Vector("cap.database"))),
      implementationRequired = Vector("host.threads"))

    val result = validate(
      Vector(binding(consumer), binding(dependency)),
      Vector(consumer, dependency),
      target,
      Set("host.socket"))

    assert(result.map(_.declaration.pluginId) == Vector("plugin.database", "plugin.app"))
  }

  test("independent plugins use plugin id as deterministic topological tie-break") {
    val values = Vector("aa", "z", "\uD800\uDC00", "\uE000").map(profile(_))

    val result = validate(values.reverse.map(binding), values, target, Set.empty)

    assert(result.map(_.declaration.pluginId) == Vector("z", "aa", "\uE000", "\uD800\uDC00"))
  }

  test("non-plugin bindings and profiles for other targets stay outside plugin admission") {
    val value = profile("plugin.one")
    val otherTarget = profile("plugin.one", targetAbi = "node-22", targetId = "js")
    val codec = DependencyBinding(
      DependencyKind.Codec,
      "codec.json",
      "codec.json@1",
      None,
      ImplementationDigest("0" * 64),
      None)

    assert(validate(
      Vector(codec, binding(value)),
      Vector(otherTarget, value),
      target,
      Set.empty) == Vector(value))
  }

  test("inventory rejects missing and ambiguous plugin implementations") {
    val value = profile("plugin.one")
    assert(errorCode(PluginProfileInventory.validate(
      Vector(binding(value)), Vector.empty, target, Set.empty)) == "MISSING_PLUGIN_PROFILE")
    assert(errorCode(PluginProfileInventory.validate(
      Vector(binding(value)), Vector(value, value), target, Set.empty)) ==
      "AMBIGUOUS_PLUGIN_IMPLEMENTATION")
    assert(errorCode(PluginProfileInventory.validate(
      Vector(binding(value), binding(value)), Vector(value), target, Set.empty)) ==
      "AMBIGUOUS_PLUGIN_IMPLEMENTATION")
  }

  test("inventory rejects target and target ABI mismatch") {
    val value = profile("plugin.one")
    val wrongTarget = binding(value).copy(target = Some("js"))
    val wrongAbi = profile("plugin.one", targetAbi = "jvm-17")

    assert(errorCode(PluginProfileInventory.validate(
      Vector(wrongTarget), Vector(value), target, Set.empty)) == "TARGET_MISMATCH")
    assert(errorCode(PluginProfileInventory.validate(
      Vector(binding(wrongAbi)), Vector(wrongAbi), target, Set.empty)) ==
      "TARGET_ABI_MISMATCH")
  }

  test("inventory rejects semantic schema digest and binding capability mismatch") {
    val value = profile("plugin.one", implementationRequired = Vector("host.threads"))
    val exact = binding(value)
    val cases = Vector(
      exact.copy(semanticAbiId = aggregateAbi('a')) -> "SEMANTIC_ABI_MISMATCH",
      exact.copy(schemaId = None) -> "SCHEMA_MISMATCH",
      exact.copy(implementationDigest = ImplementationDigest("f" * 64)) ->
        "IMPLEMENTATION_DIGEST_MISMATCH",
      exact.copy(requiredCapabilities = Vector.empty) -> "CAPABILITY_DENIED")

    cases.foreach { case (invalid, expected) =>
      assert(errorCode(PluginProfileInventory.validate(
        Vector(invalid), Vector(value), target, Set.empty)) == expected)
    }
  }

  test("manifest aggregate id shape is checked before profile selection") {
    val value = profile("plugin.one")
    val exact = binding(value)
    val malformedSemantic = exact.copy(semanticAbiId = "not-an-aggregate")
    val malformedSchema = exact.copy(schemaId = Some("not-an-aggregate"))

    assert(errorCode(PluginProfileInventory.validate(
      Vector(malformedSemantic), Vector.empty, target, Set.empty)) == "INVALID_ID")
    assert(errorCode(PluginProfileInventory.validate(
      Vector(malformedSchema), Vector.empty, target, Set.empty)) == "INVALID_ID")
  }

  test("manifest diagnostics preserve original indices across non-plugin bindings") {
    val value = profile("plugin.one")
    val codec = DependencyBinding(
      DependencyKind.Codec,
      "codec.json",
      "codec.json@1",
      None,
      ImplementationDigest("0" * 64),
      None)
    val invalidPlugin = binding(value).copy(target = Some("js"))

    assert(error(PluginProfileInventory.validate(
      Vector(codec, invalidPlugin), Vector(value), target, Set.empty)).path ==
      "$.dependencyManifest[1].target")
  }

  test("target implementation capabilities must be target features or explicitly admitted") {
    val value = profile("plugin.one", implementationRequired = Vector("host.socket"))

    assert(errorCode(PluginProfileInventory.validate(
      Vector(binding(value)), Vector(value), target, Set.empty)) == "CAPABILITY_DENIED")
    assert(validate(
      Vector(binding(value)), Vector(value), target, Set("host.socket")) == Vector(value))
  }

  test("transitive dependencies require an explicit exact manifest binding") {
    val dependency = profile("plugin.dep")
    val consumer = profile(
      "plugin.consumer",
      dependencies = Vector(requirement(dependency)))

    assert(errorCode(PluginProfileInventory.validate(
      Vector(binding(consumer)),
      Vector(consumer, dependency),
      target,
      Set.empty)) == "HIDDEN_PLUGIN_DEPENDENCY")
  }

  test("dependency aggregate ABI schema and provided capabilities are checked") {
    val dependency = profile("plugin.dep", provided = Vector("cap.read"))
    val wrongAbi = profile(
      "plugin.wrong-abi",
      dependencies = Vector(PluginRequirement(
        dependency.declaration.pluginId,
        aggregateAbi('c'),
        dependency.aggregateSchemaId)))
    val wrongSchema = profile(
      "plugin.wrong-schema",
      dependencies = Vector(PluginRequirement(
        dependency.declaration.pluginId,
        dependency.aggregateSemanticAbiId,
        Some(aggregateSchema('d')))))
    val missingCapability = profile(
      "plugin.missing-capability",
      dependencies = Vector(requirement(dependency, Vector("cap.write"))))

    def manifest(consumer: PluginCapabilityProfile) =
      Vector(binding(consumer), binding(dependency))
    def profiles(consumer: PluginCapabilityProfile) = Vector(consumer, dependency)

    assert(errorCode(PluginProfileInventory.validate(
      manifest(wrongAbi), profiles(wrongAbi), target, Set.empty)) == "SEMANTIC_ABI_MISMATCH")
    assert(errorCode(PluginProfileInventory.validate(
      manifest(wrongSchema), profiles(wrongSchema), target, Set.empty)) == "SCHEMA_MISMATCH")
    assert(errorCode(PluginProfileInventory.validate(
      manifest(missingCapability), profiles(missingCapability), target, Set.empty)) ==
      "CAPABILITY_DENIED")
  }

  test("dependency cycles reject before recursive aggregate edge comparison") {
    val a = profile(
      "plugin.a",
      dependencies = Vector(PluginRequirement("plugin.b", aggregateAbi('b'))),
      schema = None)
    val b = profile(
      "plugin.b",
      dependencies = Vector(PluginRequirement("plugin.a", a.aggregateSemanticAbiId)),
      schema = None)

    assert(errorCode(PluginProfileInventory.validate(
      Vector(binding(a), binding(b)),
      Vector(a, b),
      target,
      Set.empty)) == "PLUGIN_DEPENDENCY_CYCLE")
  }

  test("forged available profiles reject before admission") {
    val value = profile("plugin.one")
    val forged = value.copy(aggregateSemanticAbiId = aggregateAbi('e'))

    assert(errorCode(PluginProfileInventory.validate(
      Vector(binding(value)), Vector(forged), target, Set.empty)) == "SEMANTIC_ABI_MISMATCH")
  }

  private def profile(
      id: String,
      provided: Vector[String] = Vector.empty,
      dependencies: Vector[PluginRequirement] = Vector.empty,
      implementationRequired: Vector[String] = Vector.empty,
      targetAbi: String = "jvm-21",
      targetId: String = "jvm",
      schema: Option[String] = Some("service.schema@1")
  ): PluginCapabilityProfile =
    val declaration = PluginCapabilityDeclaration(
      PluginProfileVersions.Schema,
      id,
      Vector(SemanticProvision(s"$id.service", s"$id.service@1", schema, provided)),
      provided,
      dependencies)
    val implementation = PluginCapabilityProfiles.implementationFromBytes(
      targetId,
      targetAbi,
      s"artifact:$id:$targetId:$targetAbi".getBytes(StandardCharsets.UTF_8),
      implementationRequired) match
        case Right(value) => value
        case Left(error)  => fail(error.toString)
    PluginCapabilityProfiles.bind(declaration, implementation) match
      case Right(value) => value
      case Left(error)  => fail(error.toString)

  private def requirement(
      dependency: PluginCapabilityProfile,
      capabilities: Vector[String] = Vector.empty
  ): PluginRequirement =
    PluginRequirement(
      dependency.declaration.pluginId,
      dependency.aggregateSemanticAbiId,
      dependency.aggregateSchemaId,
      capabilities)

  private def binding(profile: PluginCapabilityProfile): DependencyBinding =
    PluginCapabilityProfiles.dependencyBinding(profile) match
      case Right(value) => value
      case Left(error)  => fail(error.toString)

  private def validate(
      bindings: Vector[DependencyBinding],
      profiles: Vector[PluginCapabilityProfile],
      target: TargetProfile,
      admitted: Set[String]
  ): Vector[PluginCapabilityProfile] =
    PluginProfileInventory.validate(bindings, profiles, target, admitted) match
      case Right(value) => value
      case Left(error)  => fail(error.toString)

  private def aggregateAbi(char: Char): String =
    "ssc:plugin-abi:v1:" + char.toString * 64

  private def aggregateSchema(char: Char): String =
    "ssc:plugin-schema:v1:" + char.toString * 64

  private def errorCode[A](value: Either[PluginProfileError, A]): String = value match
    case Left(error) => error.code
    case Right(_)    => fail("expected a structured plugin profile error")

  private def error[A](value: Either[PluginProfileError, A]): PluginProfileError = value match
    case Left(value) => value
    case Right(_)    => fail("expected a structured plugin profile error")
