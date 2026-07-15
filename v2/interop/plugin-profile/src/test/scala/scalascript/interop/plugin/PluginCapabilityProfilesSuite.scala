package scalascript.interop.plugin

import java.nio.charset.StandardCharsets
import org.scalatest.funsuite.AnyFunSuite
import scalascript.interop.descriptor.*

class PluginCapabilityProfilesSuite extends AnyFunSuite:
  private val validDependencyAbi = aggregateAbi('a')
  private val validDependencySchema = aggregateSchema('b')

  private val declaration = PluginCapabilityDeclaration(
    schemaVersion = PluginProfileVersions.Schema,
    pluginId = "plugin.echo",
    provisions = Vector(
      SemanticProvision(
        "service.z",
        "echo.z@1",
        Some("echo.z.schema@1"),
        Vector("cap.write")),
      SemanticProvision(
        "service.a",
        "echo.a@1",
        None,
        Vector("cap.read"))),
    providedCapabilities = Vector("cap.write", "cap.read"),
    dependencies = Vector(PluginRequirement(
      "plugin.logging",
      validDependencyAbi,
      Some(validDependencySchema),
      Vector("cap.log"))))

  test("canonical identities and plugin binding are independent of set-like input order") {
    val richDeclaration = declaration.copy(
      provisions = declaration.provisions.updated(
        0,
        declaration.provisions.head.copy(capabilities = Vector("cap.write", "cap.read"))),
      dependencies = declaration.dependencies :+ PluginRequirement(
        "plugin.metrics",
        aggregateAbi('c'),
        None,
        Vector("cap.metrics", "cap.log")))
    val first = bind(richDeclaration, "artifact-v1", Vector("host.net", "host.fs"))
    val reordered = bind(
      richDeclaration.copy(
        provisions = richDeclaration.provisions.reverse.map(provision =>
          provision.copy(capabilities = provision.capabilities.reverse)),
        providedCapabilities = richDeclaration.providedCapabilities.reverse,
        dependencies = richDeclaration.dependencies.reverse.map(requirement =>
          requirement.copy(requiredCapabilities = requirement.requiredCapabilities.reverse))),
      "artifact-v1",
      Vector("host.fs", "host.net"))

    assert(first.aggregateSemanticAbiId == reordered.aggregateSemanticAbiId)
    assert(first.aggregateSchemaId == reordered.aggregateSchemaId)
    assert(binding(first) == binding(reordered))
    assert(first.declaration.provisions.map(_.logicalId) == Vector("service.a", "service.z"))
    assert(first.declaration.providedCapabilities == Vector("cap.read", "cap.write"))
  }

  test("canonical identity vector is frozen") {
    val profile = bind(declaration, "artifact-v1", Vector("host.net", "host.fs"))

    assert(profile.aggregateSemanticAbiId ==
      "ssc:plugin-abi:v1:f1582769b5e96e607486af18fdcf5ef2ec6321d12c05449d854034eb5a895616")
    assert(profile.aggregateSchemaId.contains(
      "ssc:plugin-schema:v1:72feecf4e6cc67902ce2d27017f06a146267d5fc45e9fb155961e7bbe3d8816d"))
  }

  test("semantic schema dependency and capability changes affect aggregate identities") {
    val base = bind(declaration)
    val semantic = bind(declaration.copy(provisions = declaration.provisions.updated(
      0,
      declaration.provisions.head.copy(semanticAbiId = "echo.z@2"))))
    val schema = bind(declaration.copy(provisions = declaration.provisions.updated(
      0,
      declaration.provisions.head.copy(schemaId = Some("echo.z.schema@2")))))
    val dependency = bind(declaration.copy(dependencies = declaration.dependencies.map(
      _.copy(semanticAbiId = aggregateAbi('c')))))
    val capability = bind(declaration.copy(
      providedCapabilities = declaration.providedCapabilities :+ "cap.audit"))

    assert(semantic.aggregateSemanticAbiId != base.aggregateSemanticAbiId)
    assert(schema.aggregateSemanticAbiId != base.aggregateSemanticAbiId)
    assert(schema.aggregateSchemaId != base.aggregateSchemaId)
    assert(dependency.aggregateSemanticAbiId != base.aggregateSemanticAbiId)
    assert(capability.aggregateSemanticAbiId != base.aggregateSemanticAbiId)
  }

  test("implementation bytes affect only implementation identity and exact binding") {
    val first = bind(declaration, "artifact-v1")
    val second = bind(declaration, "artifact-v2")

    assert(first.aggregateSemanticAbiId == second.aggregateSemanticAbiId)
    assert(first.aggregateSchemaId == second.aggregateSchemaId)
    assert(first.implementation.implementationDigest != second.implementation.implementationDigest)
    assert(binding(first).implementationDigest != binding(second).implementationDigest)
  }

  test("artifact bytes use exact SHA-256 and precomputed digests are checked") {
    val implementation = implementationFor("abc")
    assert(implementation.implementationDigest.value ==
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

    val malformed = PluginCapabilityProfiles.implementationFromDigest(
      "jvm",
      "jvm-21",
      ImplementationDigest("ABC"))
    assert(errorCode(malformed) == "INVALID_SHA256")
  }

  test("schema identity is absent only when provisions and dependencies have no schema") {
    val schemaFree = PluginCapabilityDeclaration(
      PluginProfileVersions.Schema,
      "plugin.schema-free",
      Vector(SemanticProvision("service", "service@1")))

    assert(bind(schemaFree).aggregateSchemaId.isEmpty)
  }

  test("raw invalid declarations fail before normalization erases evidence") {
    val validRequirement = PluginRequirement("plugin.dep", aggregateAbi('d'))
    val cases = Vector(
      declaration.copy(schemaVersion = "2.0") -> "UNSUPPORTED_PROFILE_VERSION",
      declaration.copy(pluginId = "") -> "INVALID_ID",
      declaration.copy(provisions = Vector(
        SemanticProvision("same", "a"), SemanticProvision("same", "b"))) ->
        "DUPLICATE_PROVISION",
      declaration.copy(dependencies = Vector(validRequirement, validRequirement)) ->
        "DUPLICATE_DEPENDENCY",
      declaration.copy(providedCapabilities = Vector("same", "same")) ->
        "DUPLICATE_CAPABILITY",
      declaration.copy(dependencies = Vector(validRequirement.copy(pluginId = declaration.pluginId))) ->
        "SELF_DEPENDENCY",
      declaration.copy(
        provisions = Vector(SemanticProvision("service", "service@1", capabilities = Vector("missing"))),
        providedCapabilities = Vector.empty) -> "UNDECLARED_PROVISION_CAPABILITY",
      declaration.copy(dependencies = Vector(validRequirement.copy(semanticAbiId = "not-aggregate"))) ->
        "INVALID_ID",
      declaration.copy(dependencies = Vector(validRequirement.copy(schemaId = Some("not-aggregate")))) ->
        "INVALID_ID",
      declaration.copy(pluginId = "\ud800") -> "INVALID_ID")

    cases.foreach { case (invalid, expected) =>
      assert(errorCode(PluginCapabilityProfiles.bind(invalid, implementationFor())) == expected)
    }
  }

  test("profile containers and canonical preimages are bounded") {
    val tooMany = declaration.copy(
      providedCapabilities = Vector.fill(CanonicalProfile.MaxContainerItems + 1)("same"))
    val tooLarge = declaration.copy(pluginId = "x" * CanonicalProfile.MaxProfileBytes)

    assert(errorCode(PluginCapabilityProfiles.bind(tooMany, implementationFor())) == "RESOURCE_LIMIT")
    assert(errorCode(PluginCapabilityProfiles.bind(tooLarge, implementationFor())) == "RESOURCE_LIMIT")
    assert(errorCode(PluginCapabilityProfiles.implementationFromBytes(
      "jvm",
      "jvm-21",
      Array.emptyByteArray,
      Vector("same", "same"))) == "DUPLICATE_CAPABILITY")

    def emptyDeclaration(pluginId: String) = PluginCapabilityDeclaration(
      PluginProfileVersions.Schema,
      pluginId,
      Vector.empty)
    val exactDomainInclusiveBoundary = "x" * (CanonicalProfile.MaxProfileBytes - 34)
    val oneByteOverBoundary = "x" * (CanonicalProfile.MaxProfileBytes - 33)
    assert(PluginCapabilityProfiles.bind(
      emptyDeclaration(exactDomainInclusiveBoundary),
      implementationFor()).isRight)
    assert(errorCode(PluginCapabilityProfiles.bind(
      emptyDeclaration(oneByteOverBoundary),
      implementationFor())) == "RESOURCE_LIMIT")
  }

  test("checked projection rejects forged or stale aggregate identities") {
    val profile = bind(declaration)
    val forgedAbi = profile.copy(aggregateSemanticAbiId = aggregateAbi('f'))
    val forgedSchema = profile.copy(aggregateSchemaId = Some(aggregateSchema('f')))

    assert(errorCode(PluginCapabilityProfiles.dependencyBinding(forgedAbi)) ==
      "SEMANTIC_ABI_MISMATCH")
    assert(errorCode(PluginCapabilityProfiles.dependencyBinding(forgedSchema)) ==
      "SCHEMA_MISMATCH")
  }

  test("projection emits exactly one aggregate plugin dependency binding") {
    val profile = bind(declaration, "artifact", Vector("host.net"))
    val projected = binding(profile)

    assert(projected.kind == DependencyKind.Plugin)
    assert(projected.logicalId == declaration.pluginId)
    assert(projected.semanticAbiId == profile.aggregateSemanticAbiId)
    assert(projected.schemaId == profile.aggregateSchemaId)
    assert(projected.target.contains("jvm"))
    assert(projected.requiredCapabilities == Vector("host.net"))
  }

  private def bind(
      value: PluginCapabilityDeclaration,
      bytes: String = "artifact",
      required: Vector[String] = Vector.empty
  ): PluginCapabilityProfile =
    PluginCapabilityProfiles.bind(value, implementationFor(bytes, required)) match
      case Right(profile) => profile
      case Left(error)    => fail(error.toString)

  private def implementationFor(
      bytes: String = "artifact",
      required: Vector[String] = Vector.empty
  ): PluginTargetImplementation =
    PluginCapabilityProfiles.implementationFromBytes(
      "jvm",
      "jvm-21",
      bytes.getBytes(StandardCharsets.UTF_8),
      required) match
        case Right(value) => value
        case Left(error)  => fail(error.toString)

  private def binding(profile: PluginCapabilityProfile): DependencyBinding =
    PluginCapabilityProfiles.dependencyBinding(profile) match
      case Right(value) => value
      case Left(error)  => fail(error.toString)

  private def aggregateAbi(char: Char): String =
    "ssc:plugin-abi:v1:" + char.toString * 64

  private def aggregateSchema(char: Char): String =
    "ssc:plugin-schema:v1:" + char.toString * 64

  private def errorCode[A](value: Either[PluginProfileError, A]): String = value match
    case Left(error) => error.code
    case Right(_)    => fail("expected a structured plugin profile error")
