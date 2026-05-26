package scalascript.typeddata

import scala.deriving.Mirror

private[typeddata] final case class JsonProductField(
    name:    String,
    aliases: List[String],
    key:     Boolean,
    encode:  Any => JsonValue,
    decode:  Map[String, JsonValue] => Either[DecodeError, Any],
    graphRole: String = "",
    rdfId: Boolean = false,
    rdfPredicate: Option[String] = None
):
  def names: List[String] = name :: aliases

private[typeddata] final case class RowProductField(
    name:    String,
    aliases: List[String],
    key:     Boolean,
    encode:  Any => RowValue,
    decode:  Map[String, RowValue] => Either[DecodeError, Any]
):
  def names: List[String] = name :: aliases

private[typeddata] object DerivedProductCodecs:
  def jsonProductCodec[A](
      mirror: Mirror.ProductOf[A],
      fields: Vector[JsonProductField],
      rejectUnknown: Boolean
  ): JsonCodec[A] =
    JsonCodec.instance(
      value =>
        val values = value.asInstanceOf[Product].productIterator
        JsonValue.Obj(fields.iterator.zip(values).map { (field, raw) =>
          field.name -> field.encode(raw)
        }.toMap)
      ,
      {
        case JsonValue.Obj(rawFields) =>
          rejectUnknownJson(rawFields, fields, rejectUnknown).flatMap { _ =>
            decodeProduct(rawFields, fields.map(_.decode), mirror)
          }
        case other => Left(DecodeError(s"expected object, got ${JsonValue.kind(other)}"))
      }
    )

  def rowProductCodec[A](
      mirror: Mirror.ProductOf[A],
      fields: Vector[RowProductField],
      rejectUnknown: Boolean
  ): RowCodec[A] =
    RowCodec.instance(
      value =>
        val values = value.asInstanceOf[Product].productIterator
        fields.iterator.zip(values).map { (field, raw) =>
          field.name -> field.encode(raw)
        }.toMap
      ,
      row =>
        rejectUnknownRow(row, fields, rejectUnknown).flatMap { _ =>
          decodeProduct(row, fields.map(_.decode), mirror)
        }
    )

  def objectProductCodec[A](
      mirror: Mirror.ProductOf[A],
      fields: Vector[JsonProductField],
      rejectUnknown: Boolean
  ): ObjectCodec[A] =
    ObjectCodec.instance(
      value =>
        val values = value.asInstanceOf[Product].productIterator
        ObjectValue(fields.iterator.zip(values).map { (field, raw) =>
          field.name -> field.encode(raw)
        }.toMap)
      ,
      objectValue =>
        rejectUnknownJson(objectValue.fields, fields, rejectUnknown).flatMap { _ =>
          decodeProduct(objectValue.fields, fields.map(_.decode), mirror)
        },
      fields.find(_.key).map(_.name)
    )

  def vertexProductCodec[A](
      mirror: Mirror.ProductOf[A],
      label: String,
      fields: Vector[JsonProductField]
  ): VertexCodec[A] =
    val idField = fields.find(_.key).orElse(fields.find(_.name == "id")).getOrElse {
      throw IllegalArgumentException(s"VertexCodec[$label] requires an @key field or a field named id")
    }
    VertexCodec.instance[A](
      label,
      value =>
        val values = value.asInstanceOf[Product].productIterator.toVector
        val encoded = fields.iterator.zip(values.iterator).map { (field, raw) => field -> field.encode(raw) }.toVector
        val id = scalarString(idField.name, encoded.find(_._1 == idField).map(_._2))
        VertexValue(
          id,
          Set(label),
          encoded.iterator.collect { case (field, json) if field != idField => field.name -> json }.toMap
        )
      ,
      vertex =>
        val rawFields = vertex.properties + (idField.name -> JsonValue.Str(vertex.id))
        decodeProduct(rawFields, fields.map(_.decode), mirror)
      ,
      value =>
        val values = value.asInstanceOf[Product].productIterator.toVector
        scalarString(idField.name, fields.iterator.zip(values.iterator).find(_._1 == idField).map((field, raw) => field.encode(raw)))
    )

  def edgeProductCodec[A](
      mirror: Mirror.ProductOf[A],
      label: String,
      fields: Vector[JsonProductField]
  ): EdgeCodec[A] =
    val fromField = fields.find(_.graphRole == "from").orElse(fields.find(_.name == "from")).getOrElse {
      throw IllegalArgumentException(s"EdgeCodec[$label] requires an @graphFrom field or a field named from")
    }
    val toField = fields.find(_.graphRole == "to").orElse(fields.find(_.name == "to")).getOrElse {
      throw IllegalArgumentException(s"EdgeCodec[$label] requires an @graphTo field or a field named to")
    }
    val idField = fields.find(_.key)
    EdgeCodec.instance[A](
      label,
      value =>
        val values = value.asInstanceOf[Product].productIterator.toVector
        val encoded = fields.iterator.zip(values.iterator).map { (field, raw) => field -> field.encode(raw) }.toVector
        val from = scalarString(fromField.name, encoded.find(_._1 == fromField).map(_._2))
        val to = scalarString(toField.name, encoded.find(_._1 == toField).map(_._2))
        val id = idField.map(field => scalarString(field.name, encoded.find(_._1 == field).map(_._2)))
        val reserved = Set(fromField, toField) ++ idField.toSet
        EdgeValue(
          id,
          from,
          to,
          label,
          encoded.iterator.collect { case (field, json) if !reserved.contains(field) => field.name -> json }.toMap
        )
      ,
      edge =>
        val base = edge.properties ++ Map(fromField.name -> JsonValue.Str(edge.from), toField.name -> JsonValue.Str(edge.to))
        val rawFields = idField.flatMap(field => edge.id.map(id => field.name -> JsonValue.Str(id))).fold(base)(base + _)
        decodeProduct(rawFields, fields.map(_.decode), mirror)
      ,
      value =>
        val values = value.asInstanceOf[Product].productIterator.toVector
        scalarString(fromField.name, fields.iterator.zip(values.iterator).find(_._1 == fromField).map((field, raw) => field.encode(raw)))
      ,
      value =>
        val values = value.asInstanceOf[Product].productIterator.toVector
        scalarString(toField.name, fields.iterator.zip(values.iterator).find(_._1 == toField).map((field, raw) => field.encode(raw)))
    )

  def rdfProductCodec[A](
      mirror: Mirror.ProductOf[A],
      classIri: Option[String],
      fields: Vector[JsonProductField]
  ): RdfCodec[A] =
    val idField = fields.find(_.rdfId).orElse(fields.find(_.key)).orElse(fields.find(_.name == "id")).getOrElse {
      throw IllegalArgumentException("RdfCodec requires an @rdfId/@key field or a field named id")
    }
    RdfCodec.instance[A](
      classIri,
      value =>
        val values = value.asInstanceOf[Product].productIterator.toVector
        val encoded = fields.iterator.zip(values.iterator).map { (field, raw) => field -> field.encode(raw) }.toVector
        val subject = RdfNode.Iri(scalarString(idField.name, encoded.find(_._1 == idField).map(_._2)))
        val typeTriples = classIri.toVector.map(iri => RdfTriple(subject, RdfCodec.RdfType, RdfNode.Iri(iri)))
        val propertyTriples = encoded.iterator.collect {
          case (field, json) if field != idField =>
            RdfTriple(subject, field.rdfPredicate.getOrElse(field.name), RdfNode.Literal(json))
        }.toVector
        RdfValue(subject, typeTriples ++ propertyTriples)
      ,
      rdf =>
        val subjectId = rdf.subject match
          case RdfNode.Iri(value) => value
          case RdfNode.Blank(id) => id
          case RdfNode.Literal(value) => scalarString(idField.name, Some(value))
        val byPredicate = rdf.triples.collect {
          case RdfTriple(_, predicate, RdfNode.Literal(value)) => predicate -> value
        }.toMap
        val rawFields =
          fields.foldLeft(Map(idField.name -> JsonValue.Str(subjectId))) { (acc, field) =>
            if field == idField then acc
            else
              byPredicate.get(field.rdfPredicate.getOrElse(field.name)) match
                case Some(value) => acc + (field.name -> value)
                case None => acc
          }
        decodeProduct(rawFields, fields.map(_.decode), mirror)
      ,
      value =>
        val values = value.asInstanceOf[Product].productIterator.toVector
        RdfNode.Iri(scalarString(idField.name, fields.iterator.zip(values.iterator).find(_._1 == idField).map((field, raw) => field.encode(raw))))
    )

  private def decodeProduct[A, Repr](
      repr: Repr,
      decoders: Vector[Repr => Either[DecodeError, Any]],
      mirror: Mirror.ProductOf[A]
  ): Either[DecodeError, A] =
    val values = Array.ofDim[Any](decoders.size)
    var index = 0
    while index < decoders.size do
      decoders(index)(repr) match
        case Right(value) => values(index) = value
        case Left(error) => return Left(error)
      index += 1
    Right(mirror.fromProduct(Tuple.fromArray(values)))

  private def rejectUnknownJson(
      rawFields: Map[String, JsonValue],
      fields: Vector[JsonProductField],
      rejectUnknown: Boolean
  ): Either[DecodeError, Unit] =
    if !rejectUnknown then Right(())
    else
      val known = fields.iterator.flatMap(_.names).toSet
      rawFields.keys.find(!known.contains(_)) match
        case Some(name) => Left(DecodeError(s"unknown field '$name'").at(name))
        case None => Right(())

  private def rejectUnknownRow(
      row: Map[String, RowValue],
      fields: Vector[RowProductField],
      rejectUnknown: Boolean
  ): Either[DecodeError, Unit] =
    if !rejectUnknown then Right(())
    else
      val known = fields.iterator.flatMap(_.names).map(_.toLowerCase(java.util.Locale.ROOT)).toSet
      row.keys.find(name => !known.contains(name.toLowerCase(java.util.Locale.ROOT))) match
        case Some(name) => Left(DecodeError(s"unknown column '$name'").at(name))
        case None => Right(())

  private def scalarString(name: String, value: Option[JsonValue]): String =
    ObjectCodec.scalarKey(value).getOrElse {
      throw IllegalArgumentException(s"graph/RDF id field '$name' must encode to a string, number, or boolean")
    }
