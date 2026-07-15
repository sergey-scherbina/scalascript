package scalascript.interop.plugin

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private[plugin] object CanonicalProfile:
  val MaxContainerItems: Int = 100000
  val MaxProfileBytes: Int = 4 * 1024 * 1024

  private val SemanticDomain = "ssc-plugin-abi-v1\u0000".getBytes(StandardCharsets.UTF_8)
  private val SchemaDomain = "ssc-plugin-schema-v1\u0000".getBytes(StandardCharsets.UTF_8)

  final case class NormalizedDeclaration(
      value: PluginCapabilityDeclaration,
      semanticPreimage: Array[Byte],
      schemaPreimage: Option[Array[Byte]])

  def declaration(
      raw: PluginCapabilityDeclaration
  ): Either[PluginProfileError, NormalizedDeclaration] =
    for
      _ <- version(raw.schemaVersion)
      _ <- nonEmpty("$.declaration.pluginId", raw.pluginId)
      _ <- container("$.declaration.provisions", raw.provisions.size)
      _ <- container("$.declaration.dependencies", raw.dependencies.size)
      _ <- unique(
        "$.declaration.provisions",
        raw.provisions.map(_.logicalId),
        "DUPLICATE_PROVISION")
      _ <- unique(
        "$.declaration.dependencies",
        raw.dependencies.map(_.pluginId),
        "DUPLICATE_DEPENDENCY")
      provided <- capabilities(
        raw.providedCapabilities,
        "$.declaration.providedCapabilities")
      provisions <- normalizeProvisions(raw.provisions, provided.toSet)
      dependencies <- normalizeRequirements(raw.dependencies, raw.pluginId)
      normalized = raw.copy(
        provisions = provisions,
        providedCapabilities = provided,
        dependencies = dependencies)
      semantic <- semanticDeclaration(normalized)
      schema <- schemaDeclaration(normalized)
    yield NormalizedDeclaration(normalized, semantic, schema)

  def capabilities(
      raw: Vector[String],
      path: String
  ): Either[PluginProfileError, Vector[String]] =
    for
      _ <- container(path, raw.size)
      _ <- unique(path, raw, "DUPLICATE_CAPABILITY")
      encoded <- traverse(raw.zipWithIndex) { case (value, index) =>
        nonEmpty(s"$path[$index]", value).flatMap(_ => string(value, s"$path[$index]"))
          .map(bytes => value -> bytes)
      }
    yield sortEncoded(encoded)

  def nonEmpty(path: String, value: String): Either[PluginProfileError, Unit] =
    if value.isEmpty then error("INVALID_ID", path, "identifier must not be empty")
    else scalarText(path, value).flatMap(_ => stringSize(path, value))

  def digest(path: String, value: String): Either[PluginProfileError, Unit] =
    if value.length == 64 && value.forall(ch => ch >= '0' && ch <= '9' || ch >= 'a' && ch <= 'f')
    then Right(())
    else error("INVALID_SHA256", path, "digest must be lowercase 64-hex SHA-256")

  def aggregateId(
      path: String,
      value: String,
      prefix: String
  ): Either[PluginProfileError, Unit] =
    if !value.startsWith(prefix) then
      error("INVALID_ID", path, s"aggregate id must start with '$prefix'")
    else
      digest(path, value.substring(prefix.length)).left.map { failure =>
        failure.copy(code = "INVALID_ID", message = s"aggregate id after '$prefix' must be lowercase SHA-256")
      }

  def semanticId(bytes: Array[Byte]): String =
    "ssc:plugin-abi:v1:" + domainHash(SemanticDomain, bytes)

  def schemaId(bytes: Array[Byte]): String =
    "ssc:plugin-schema:v1:" + domainHash(SchemaDomain, bytes)

  val framedStringOrdering: Ordering[String] = new Ordering[String]:
    def compare(left: String, right: String): Int =
      val leftBytes = left.getBytes(StandardCharsets.UTF_8)
      val rightBytes = right.getBytes(StandardCharsets.UTF_8)
      val lengthComparison = Integer.compare(leftBytes.length, rightBytes.length)
      if lengthComparison != 0 then lengthComparison
      else unsignedCompare(leftBytes, rightBytes)

  private def version(value: String): Either[PluginProfileError, Unit] =
    if value == PluginProfileVersions.Schema then Right(())
    else error(
      "UNSUPPORTED_PROFILE_VERSION",
      "$.declaration.schemaVersion",
      s"expected ${PluginProfileVersions.Schema}")

  private def normalizeProvisions(
      raw: Vector[SemanticProvision],
      provided: Set[String]
  ): Either[PluginProfileError, Vector[SemanticProvision]] =
    traverse(raw.zipWithIndex) { case (value, index) =>
      val path = s"$$.declaration.provisions[$index]"
      for
        _ <- nonEmpty(s"$path.logicalId", value.logicalId)
        _ <- nonEmpty(s"$path.semanticAbiId", value.semanticAbiId)
        _ <- optionalId(s"$path.schemaId", value.schemaId)
        normalizedCapabilities <- capabilities(value.capabilities, s"$path.capabilities")
        _ <- normalizedCapabilities.find(capability => !provided.contains(capability)) match
          case Some(capability) => error(
            "UNDECLARED_PROVISION_CAPABILITY",
            s"$path.capabilities",
            s"capability '$capability' is absent from providedCapabilities")
          case None => Right(())
        normalized = value.copy(capabilities = normalizedCapabilities)
        encoded <- provision(normalized, path)
      yield normalized -> encoded
    }.map(sortEncoded)

  private def normalizeRequirements(
      raw: Vector[PluginRequirement],
      ownerPluginId: String
  ): Either[PluginProfileError, Vector[PluginRequirement]] =
    traverse(raw.zipWithIndex) { case (value, index) =>
      val path = s"$$.declaration.dependencies[$index]"
      for
        _ <- nonEmpty(s"$path.pluginId", value.pluginId)
        _ <-
          if value.pluginId == ownerPluginId then
            error("SELF_DEPENDENCY", s"$path.pluginId", "a plugin cannot depend on itself")
          else Right(())
        _ <- aggregateId(
          s"$path.semanticAbiId",
          value.semanticAbiId,
          "ssc:plugin-abi:v1:")
        _ <- value.schemaId
          .map(aggregateId(s"$path.schemaId", _, "ssc:plugin-schema:v1:"))
          .getOrElse(Right(()))
        required <- capabilities(value.requiredCapabilities, s"$path.requiredCapabilities")
        normalized = value.copy(requiredCapabilities = required)
        encoded <- requirement(normalized, path)
      yield normalized -> encoded
    }.map(sortEncoded)

  private def semanticDeclaration(
      value: PluginCapabilityDeclaration
  ): Either[PluginProfileError, Array[Byte]] =
    for
      plugin <- string(value.pluginId, "$.declaration.pluginId")
      provisions <- traverse(value.provisions.zipWithIndex) { case (item, index) =>
        provision(item, s"$$.declaration.provisions[$index]")
      }.flatMap(vector(_, "$.semantic.provisions"))
      provided <- traverse(value.providedCapabilities.zipWithIndex) { case (item, index) =>
        string(item, s"$$.declaration.providedCapabilities[$index]")
      }.flatMap(vector(_, "$.semantic.providedCapabilities"))
      dependencies <- traverse(value.dependencies.zipWithIndex) { case (item, index) =>
        requirement(item, s"$$.declaration.dependencies[$index]")
      }.flatMap(vector(_, "$.semantic.dependencies"))
      bytes <- concat(
        Vector(plugin, provisions, provided, dependencies),
        "$.aggregateSemanticAbiId",
        MaxProfileBytes - SemanticDomain.length)
    yield bytes

  private def schemaDeclaration(
      value: PluginCapabilityDeclaration
  ): Either[PluginProfileError, Option[Array[Byte]]] =
    val provisionValues = value.provisions.flatMap(item => item.schemaId.map(item.logicalId -> _))
    val requirementValues = value.dependencies.flatMap(item => item.schemaId.map(item.pluginId -> _))
    if provisionValues.isEmpty && requirementValues.isEmpty then Right(None)
    else
      for
        plugin <- string(value.pluginId, "$.declaration.pluginId")
        provisions <- schemaEntries(provisionValues, "$.schema.provisions")
        requirements <- schemaEntries(requirementValues, "$.schema.dependencies")
        bytes <- concat(
          Vector(plugin, provisions, requirements),
          "$.aggregateSchemaId",
          MaxProfileBytes - SchemaDomain.length)
      yield Some(bytes)

  private def schemaEntries(
      values: Vector[(String, String)],
      path: String
  ): Either[PluginProfileError, Array[Byte]] =
    traverse(values.zipWithIndex) { case ((logicalId, schemaId), index) =>
      for
        logical <- string(logicalId, s"$path[$index].logicalId")
        schema <- string(schemaId, s"$path[$index].schemaId")
        encoded <- concat(Vector(logical, schema), s"$path[$index]")
      yield encoded
    }.map(_.sortWith(unsignedLess)).flatMap(vector(_, path))

  private def provision(
      value: SemanticProvision,
      path: String
  ): Either[PluginProfileError, Array[Byte]] =
    for
      logical <- string(value.logicalId, s"$path.logicalId")
      semantic <- string(value.semanticAbiId, s"$path.semanticAbiId")
      schema <- optionalString(value.schemaId, s"$path.schemaId")
      capabilityValues <- traverse(value.capabilities.zipWithIndex) { case (item, index) =>
        string(item, s"$path.capabilities[$index]")
      }.flatMap(vector(_, s"$path.capabilities"))
      encoded <- concat(Vector(logical, semantic, schema, capabilityValues), path)
    yield encoded

  private def requirement(
      value: PluginRequirement,
      path: String
  ): Either[PluginProfileError, Array[Byte]] =
    for
      plugin <- string(value.pluginId, s"$path.pluginId")
      semantic <- string(value.semanticAbiId, s"$path.semanticAbiId")
      schema <- optionalString(value.schemaId, s"$path.schemaId")
      capabilityValues <- traverse(value.requiredCapabilities.zipWithIndex) { case (item, index) =>
        string(item, s"$path.requiredCapabilities[$index]")
      }.flatMap(vector(_, s"$path.requiredCapabilities"))
      encoded <- concat(Vector(plugin, semantic, schema, capabilityValues), path)
    yield encoded

  private def optionalId(
      path: String,
      value: Option[String]
  ): Either[PluginProfileError, Unit] =
    value.map(nonEmpty(path, _)).getOrElse(Right(()))

  private def string(value: String, path: String): Either[PluginProfileError, Array[Byte]] =
    for
      _ <- scalarText(path, value)
      _ <- stringSize(path, value)
      bytes = value.getBytes(StandardCharsets.UTF_8)
      framed <- prefix(bytes.length, bytes, path)
    yield framed

  private def optionalString(
      value: Option[String],
      path: String
  ): Either[PluginProfileError, Array[Byte]] = value match
    case None => Right(Array(0.toByte))
    case Some(item) =>
      string(item, path).flatMap(bytes => concat(Vector(Array(1.toByte), bytes), path))

  private def vector(
      elements: Vector[Array[Byte]],
      path: String
  ): Either[PluginProfileError, Array[Byte]] =
    container(path, elements.size).flatMap { _ =>
      concat(elements, path).flatMap(payload => prefix(elements.size, payload, path))
    }

  private def prefix(
      value: Int,
      payload: Array[Byte],
      path: String
  ): Either[PluginProfileError, Array[Byte]] =
    val size = 4L + payload.length.toLong
    if size > MaxProfileBytes then resource(path, s"framed value exceeds $MaxProfileBytes bytes")
    else
      val result = new Array[Byte](size.toInt)
      result(0) = (value >>> 24).toByte
      result(1) = (value >>> 16).toByte
      result(2) = (value >>> 8).toByte
      result(3) = value.toByte
      System.arraycopy(payload, 0, result, 4, payload.length)
      Right(result)

  private def concat(
      parts: Vector[Array[Byte]],
      path: String,
      limit: Int = MaxProfileBytes
  ): Either[PluginProfileError, Array[Byte]] =
    val size = parts.foldLeft(0L)(_ + _.length.toLong)
    if size > limit then resource(path, s"canonical preimage exceeds $MaxProfileBytes bytes")
    else
      val result = new Array[Byte](size.toInt)
      var offset = 0
      parts.foreach { part =>
        System.arraycopy(part, 0, result, offset, part.length)
        offset += part.length
      }
      Right(result)

  private def stringSize(path: String, value: String): Either[PluginProfileError, Unit] =
    if value.getBytes(StandardCharsets.UTF_8).length > MaxProfileBytes then
      resource(path, s"UTF-8 string exceeds $MaxProfileBytes bytes")
    else Right(())

  private def scalarText(path: String, value: String): Either[PluginProfileError, Unit] =
    var index = 0
    var valid = true
    while index < value.length && valid do
      val current = value.charAt(index)
      if Character.isHighSurrogate(current) then
        if index + 1 >= value.length || !Character.isLowSurrogate(value.charAt(index + 1)) then
          valid = false
        else index += 2
      else if Character.isLowSurrogate(current) then valid = false
      else index += 1
    if valid then Right(())
    else error("INVALID_ID", path, "text must contain only Unicode scalar values")

  def container(path: String, size: Int): Either[PluginProfileError, Unit] =
    if size > MaxContainerItems then
      resource(path, s"container exceeds $MaxContainerItems items")
    else Right(())

  private def unique(
      path: String,
      values: Vector[String],
      code: String
  ): Either[PluginProfileError, Unit] =
    values.groupBy(identity).collectFirst { case (value, occurrences) if occurrences.size > 1 => value } match
      case Some(value) => error(code, path, s"duplicate value '$value'")
      case None        => Right(())

  private def sortEncoded[A](values: Vector[(A, Array[Byte])]): Vector[A] =
    values.sortWith { case ((_, left), (_, right)) => unsignedLess(left, right) }.map(_._1)

  private def unsignedLess(left: Array[Byte], right: Array[Byte]): Boolean =
    unsignedCompare(left, right) < 0

  private def unsignedCompare(left: Array[Byte], right: Array[Byte]): Int =
    var index = 0
    val limit = math.min(left.length, right.length)
    while index < limit && left(index) == right(index) do index += 1
    if index == limit then Integer.compare(left.length, right.length)
    else Integer.compare(left(index) & 0xff, right(index) & 0xff)

  private def domainHash(domain: Array[Byte], bytes: Array[Byte]): String =
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(domain)
    digest.update(bytes)
    hex(digest.digest())

  private def hex(bytes: Array[Byte]): String =
    val alphabet = "0123456789abcdef"
    val chars = new Array[Char](bytes.length * 2)
    var index = 0
    while index < bytes.length do
      val value = bytes(index) & 0xff
      chars(index * 2) = alphabet.charAt(value >>> 4)
      chars(index * 2 + 1) = alphabet.charAt(value & 0x0f)
      index += 1
    new String(chars)

  private def resource[A](path: String, message: String): Either[PluginProfileError, A] =
    error("RESOURCE_LIMIT", path, message)

  private def error[A](
      code: String,
      path: String,
      message: String
  ): Either[PluginProfileError, A] =
    Left(PluginProfileError(code, path, message))

  private def traverse[A, B](
      values: Vector[A]
  )(f: A => Either[PluginProfileError, B]): Either[PluginProfileError, Vector[B]] =
    values.foldLeft[Either[PluginProfileError, Vector[B]]](Right(Vector.empty)) { (done, value) =>
      for
        accumulated <- done
        next <- f(value)
      yield accumulated :+ next
    }
