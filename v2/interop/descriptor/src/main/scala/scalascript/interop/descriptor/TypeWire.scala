package scalascript.interop.descriptor

private[descriptor] object TypeWire:
  import WireSupport.*

  private val primitives = Map(
    "Unit" -> AbiPrimitive.Unit,
    "Boolean" -> AbiPrimitive.Boolean,
    "I32" -> AbiPrimitive.I32,
    "I64" -> AbiPrimitive.I64,
    "BigInt" -> AbiPrimitive.BigInt,
    "F64" -> AbiPrimitive.F64,
    "String" -> AbiPrimitive.String,
    "Bytes" -> AbiPrimitive.Bytes,
    "Char" -> AbiPrimitive.Char
  )

  private val variances = Map(
    "Invariant" -> Variance.Invariant,
    "Covariant" -> Variance.Covariant,
    "Contravariant" -> Variance.Contravariant
  )

  def writePrimitive(value: AbiPrimitive): ujson.Value = tagged(value.toString)
  def readPrimitive(value: ujson.Value, path: String): Result[AbiPrimitive] =
    enumValue(value, path, primitives)

  def writeVariance(value: Variance): ujson.Value = tagged(value.toString)
  def readVariance(value: ujson.Value, path: String): Result[Variance] =
    enumValue(value, path, variances)

  def writeTypeParameterRef(value: TypeParameterRef): ujson.Value = obj(
    "depth" -> index(value.depth),
    "index" -> index(value.index),
    "kindArity" -> index(value.kindArity)
  )

  def readTypeParameterRef(value: ujson.Value, path: String): Result[TypeParameterRef] =
    for
      objectValue <- exactObject(value, path, "depth", "index", "kindArity")
      depth <- int(field(objectValue, "depth"), s"$path.depth")
      parameterIndex <- int(field(objectValue, "index"), s"$path.index")
      kindArity <- int(field(objectValue, "kindArity"), s"$path.kindArity")
    yield TypeParameterRef(depth, parameterIndex, kindArity)

  def writeTypeParameter(value: AbiTypeParameter): ujson.Value = obj(
    "index" -> index(value.index),
    "name" -> str(value.name),
    "variance" -> writeVariance(value.variance),
    "kindArity" -> index(value.kindArity),
    "lowerBound" -> optional(value.lowerBound)(writeType),
    "upperBound" -> optional(value.upperBound)(writeType)
  )

  def readTypeParameter(value: ujson.Value, path: String): Result[AbiTypeParameter] =
    for
      objectValue <- exactObject(
        value,
        path,
        "index",
        "name",
        "variance",
        "kindArity",
        "lowerBound",
        "upperBound"
      )
      parameterIndex <- int(field(objectValue, "index"), s"$path.index")
      name <- string(field(objectValue, "name"), s"$path.name")
      variance <- readVariance(field(objectValue, "variance"), s"$path.variance")
      kindArity <- int(field(objectValue, "kindArity"), s"$path.kindArity")
      lower <- option(field(objectValue, "lowerBound"), s"$path.lowerBound")(readType)
      upper <- option(field(objectValue, "upperBound"), s"$path.upperBound")(readType)
    yield AbiTypeParameter(parameterIndex, name, variance, kindArity, lower, upper)

  def writeEffectRef(value: EffectRef): ujson.Value = obj(
    "stableEffectId" -> str(value.stableEffectId),
    "typeArguments" -> arr(value.typeArguments.map(writeType))
  )

  def readEffectRef(value: ujson.Value, path: String): Result[EffectRef] =
    for
      objectValue <- exactObject(value, path, "stableEffectId", "typeArguments")
      stableEffectId <- string(field(objectValue, "stableEffectId"), s"$path.stableEffectId")
      arguments <- vector(field(objectValue, "typeArguments"), s"$path.typeArguments")(readType)
    yield EffectRef(stableEffectId, arguments)

  def writeEffectRow(value: EffectRow): ujson.Value = obj(
    "members" -> arr(value.members.map(writeEffectRef)),
    "openTail" -> optional(value.openTail)(writeTypeParameterRef)
  )

  def readEffectRow(value: ujson.Value, path: String): Result[EffectRow] =
    for
      objectValue <- exactObject(value, path, "members", "openTail")
      members <- vector(field(objectValue, "members"), s"$path.members")(readEffectRef)
      openTail <- option(field(objectValue, "openTail"), s"$path.openTail")(readTypeParameterRef)
    yield EffectRow(members, openTail)

  def writeType(value: AbiType): ujson.Value = value match
    case AbiType.Primitive(primitive) =>
      tagged("Primitive", "value" -> writePrimitive(primitive))
    case AbiType.Named(stableTypeId, arguments) =>
      tagged(
        "Named",
        "stableTypeId" -> str(stableTypeId),
        "arguments" -> arr(arguments.map(writeType))
      )
    case AbiType.TypeParameter(reference) =>
      tagged("TypeParameter", "reference" -> writeTypeParameterRef(reference))
    case AbiType.Tuple(elements) =>
      tagged("Tuple", "elements" -> arr(elements.map(writeType)))
    case AbiType.Function(parameterLists, result, effects) =>
      tagged(
        "Function",
        "parameterLists" -> arr(parameterLists.map(list => arr(list.map(writeType)))),
        "result" -> writeType(result),
        "effects" -> writeEffectRow(effects)
      )
    case AbiType.Union(alternatives) =>
      tagged("Union", "alternatives" -> arr(alternatives.map(writeType)))
    case AbiType.Intersection(parts) =>
      tagged("Intersection", "parts" -> arr(parts.map(writeType)))
    case AbiType.TypeLambda(parameters, body) =>
      tagged(
        "TypeLambda",
        "parameters" -> arr(parameters.map(writeTypeParameter)),
        "body" -> writeType(body)
      )

  def writeIdentityType(value: AbiType): ujson.Value = writeType(identityType(value))

  def writeIdentityEffectRow(value: EffectRow): ujson.Value =
    writeEffectRow(value.copy(members = value.members.map { member =>
      member.copy(typeArguments = member.typeArguments.map(identityType))
    }))

  def identityType(value: AbiType): AbiType = value match
    case AbiType.Primitive(_) | AbiType.TypeParameter(_) => value
    case AbiType.Named(id, arguments) =>
      AbiType.Named(id, arguments.map(identityType))
    case AbiType.Tuple(elements) =>
      AbiType.Tuple(elements.map(identityType))
    case AbiType.Function(parameterLists, result, effects) =>
      AbiType.Function(
        parameterLists.map(_.map(identityType)),
        identityType(result),
        effects.copy(members = effects.members.map { member =>
          member.copy(typeArguments = member.typeArguments.map(identityType))
        })
      )
    case AbiType.Union(alternatives) =>
      AbiType.Union(alternatives.map(identityType))
    case AbiType.Intersection(parts) =>
      AbiType.Intersection(parts.map(identityType))
    case AbiType.TypeLambda(parameters, body) =>
      AbiType.TypeLambda(
        parameters.map { parameter =>
          parameter.copy(
            name = "",
            lowerBound = parameter.lowerBound.map(identityType),
            upperBound = parameter.upperBound.map(identityType)
          )
        },
        identityType(body)
      )

  def readType(value: ujson.Value, path: String): Result[AbiType] = value match
    case objectValue: ujson.Obj =>
      objectValue.value.get("tag") match
        case Some(ujson.Str("Primitive")) =>
          tag(value, path, "value").flatMap { case (fields, _) =>
            readPrimitive(field(fields, "value"), s"$path.value").map(AbiType.Primitive.apply)
          }
        case Some(ujson.Str("Named")) =>
          for
            taggedValue <- tag(value, path, "stableTypeId", "arguments")
            (fields, _) = taggedValue
            stableTypeId <- string(field(fields, "stableTypeId"), s"$path.stableTypeId")
            arguments <- vector(field(fields, "arguments"), s"$path.arguments")(readType)
          yield AbiType.Named(stableTypeId, arguments)
        case Some(ujson.Str("TypeParameter")) =>
          tag(value, path, "reference").flatMap { case (fields, _) =>
            readTypeParameterRef(field(fields, "reference"), s"$path.reference")
              .map(AbiType.TypeParameter.apply)
          }
        case Some(ujson.Str("Tuple")) =>
          tag(value, path, "elements").flatMap { case (fields, _) =>
            vector(field(fields, "elements"), s"$path.elements")(readType)
              .map(AbiType.Tuple.apply)
          }
        case Some(ujson.Str("Function")) =>
          for
            taggedValue <- tag(value, path, "parameterLists", "result", "effects")
            (fields, _) = taggedValue
            lists <- vector(field(fields, "parameterLists"), s"$path.parameterLists") {
              (listValue, listPath) => vector(listValue, listPath)(readType)
            }
            result <- readType(field(fields, "result"), s"$path.result")
            effects <- readEffectRow(field(fields, "effects"), s"$path.effects")
          yield AbiType.Function(lists, result, effects)
        case Some(ujson.Str("Union")) =>
          tag(value, path, "alternatives").flatMap { case (fields, _) =>
            vector(field(fields, "alternatives"), s"$path.alternatives")(readType)
              .map(AbiType.Union.apply)
          }
        case Some(ujson.Str("Intersection")) =>
          tag(value, path, "parts").flatMap { case (fields, _) =>
            vector(field(fields, "parts"), s"$path.parts")(readType)
              .map(AbiType.Intersection.apply)
          }
        case Some(ujson.Str("TypeLambda")) =>
          for
            taggedValue <- tag(value, path, "parameters", "body")
            (fields, _) = taggedValue
            parameters <- vector(field(fields, "parameters"), s"$path.parameters")(readTypeParameter)
            body <- readType(field(fields, "body"), s"$path.body")
          yield AbiType.TypeLambda(parameters, body)
        case Some(ujson.Str(other)) => schema(s"$path.tag", s"unsupported tag $other")
        case Some(_)                => schema(s"$path.tag", "expected JSON string")
        case None                   => schema(path, "missing tag field")
    case _ => schema(path, "expected tagged ABI type object")
