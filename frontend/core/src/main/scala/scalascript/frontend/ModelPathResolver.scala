package scalascript.frontend

import scalascript.ast.{ModelDef, ModelFieldType}

/** Resolves dot-separated field paths against a list of model descriptors.
 *
 *  Used by backend emitters to:
 *  - Statically verify that `ForModel`/`ModelText` field paths are valid
 *  - Determine the element type for iteration (to derive the `idKey`)
 *  - Select the right id key for `ForEach`-style renders */
object ModelPathResolver:

  /** Resolve `path` starting from `rootModelName` using `allModels` as the registry.
   *
   *  Returns:
   *  - `Right(tpe)` — the `ModelFieldType` at the end of the path
   *  - `Left(msg)` — human-readable error (unknown model, unknown field, etc.)
   *
   *  Example: `resolve("assets.lines", "BalanceSheet", models)` might return
   *  `Right(ListOf(Nested("AssetLine")))`. */
  def resolve(path: String, rootModelName: String, allModels: List[ModelDef]): Either[String, ModelFieldType] =
    val index = allModels.map(m => m.name -> m).toMap
    index.get(rootModelName) match
      case None => Left(s"Unknown model '$rootModelName'")
      case Some(_) =>
        val segments = path.split('.').toList.filter(_.nonEmpty)
        walkPath(segments, ModelFieldType.Nested(rootModelName), index)

  /** Resolve `path` starting from a known `rootType`. */
  def resolveFromType(path: String, rootType: ModelFieldType, allModels: List[ModelDef]): Either[String, ModelFieldType] =
    val index = allModels.map(m => m.name -> m).toMap
    val segments = path.split('.').toList.filter(_.nonEmpty)
    walkPath(segments, rootType, index)

  /** The element type for a `ListOf` — unwrapping one level of `ListOf` or `Optional`. */
  def elementType(tpe: ModelFieldType): Either[String, ModelFieldType] = tpe match
    case ModelFieldType.ListOf(inner)    => Right(inner)
    case ModelFieldType.Optional(inner)  => elementType(inner)
    case other => Left(s"Cannot iterate non-list type: $other")

  /** The id-key field name for a `Nested` element type, or `None` if none found. */
  def idKey(elementType: ModelFieldType, allModels: List[ModelDef]): Option[String] =
    val index = allModels.map(m => m.name -> m).toMap
    elementType match
      case ModelFieldType.Nested(name) => index.get(name).flatMap(_.identifyingField)
      case _                           => None

  private def walkPath(
      segments: List[String],
      current: ModelFieldType,
      index: Map[String, ModelDef]
  ): Either[String, ModelFieldType] =
    segments match
      case Nil => Right(current)
      case head :: tail =>
        resolveField(head, current, index) match
          case Left(err)   => Left(err)
          case Right(next) => walkPath(tail, next, index)

  private def resolveField(
      field: String,
      inType: ModelFieldType,
      index: Map[String, ModelDef]
  ): Either[String, ModelFieldType] =
    inType match
      case ModelFieldType.Nested(name) =>
        index.get(name) match
          case None    => Left(s"Unknown model '$name'")
          case Some(m) =>
            m.fields.find(_.name == field) match
              case None    => Left(s"Field '$field' not found in model '$name'")
              case Some(f) => Right(f.tpe)
      case ModelFieldType.ListOf(inner)   => resolveField(field, inner, index)
      case ModelFieldType.Optional(inner) => resolveField(field, inner, index)
      case leaf => Left(s"Cannot access field '$field' on scalar type $leaf")
