package scalascript.typeddata

import org.scalatest.funsuite.AnyFunSuite

class RowCodecTest extends AnyFunSuite:

  final case class UserRow(id: String, age: Int, active: Boolean, score: Option[Double]) derives RowCodec

  test("derives RowCodec maps case classes to column maps"):
    val row = UserRow("u1", 42, active = true, score = Some(9.5))

    assert(RowCodec[UserRow].encode(row) == Map(
      "id" -> RowValue.Str("u1"),
      "age" -> RowValue.Num(42),
      "active" -> RowValue.Bool(true),
      "score" -> RowValue.Num(BigDecimal(9.5))
    ))

  test("derived RowCodec decodes rows and nullable option columns"):
    val decoded = RowCodec[UserRow].decode(Map(
      "id" -> RowValue.Str("u1"),
      "age" -> RowValue.Num(42),
      "active" -> RowValue.Bool(true),
      "score" -> RowValue.Null
    ))

    assert(decoded == Right(UserRow("u1", 42, active = true, score = None)))

  test("derived RowCodec reports column paths"):
    val error = RowCodec[UserRow].decode(Map(
      "id" -> RowValue.Str("u1"),
      "age" -> RowValue.Str("old"),
      "active" -> RowValue.Bool(true),
      "score" -> RowValue.Null
    )).left.toOption.get

    assert(error.render == "$.age: expected number column, got string")

  final case class SchemaUser(id: String, displayName: String, active: Boolean)

  object SchemaUser:
    private val idColumn = RowFieldSpec.key[String]("id")
    private val displayNameColumn = RowFieldSpec.required[String]("display_name", "name")
    private val activeColumn = RowFieldSpec.withDefault[Boolean]("active", true)

    given RowCodec[SchemaUser] = RowCodec.objectCodec(
      user => Map(
        idColumn.name -> RowValueCodec[String].encode(user.id),
        displayNameColumn.name -> RowValueCodec[String].encode(user.displayName),
        activeColumn.name -> RowValueCodec[Boolean].encode(user.active)
      ),
      row =>
        for
          id <- RowCodec.field(row, idColumn)
          displayName <- RowCodec.field(row, displayNameColumn)
          active <- RowCodec.field(row, activeColumn)
        yield SchemaUser(id, displayName, active),
      fields = List(idColumn, displayNameColumn, activeColumn),
      rejectUnknown = true
    )

  test("explicit RowCodec field specs support aliases, defaults, and key metadata"):
    val row = Map(
      "id" -> RowValue.Str("u1"),
      "name" -> RowValue.Str("Ada")
    )

    assert(RowCodec[SchemaUser].decode(row) == Right(SchemaUser("u1", "Ada", active = true)))
    assert(RowCodec[SchemaUser].encode(SchemaUser("u1", "Ada", active = false)) == Map(
      "id" -> RowValue.Str("u1"),
      "display_name" -> RowValue.Str("Ada"),
      "active" -> RowValue.Bool(false)
    ))

  test("explicit RowCodec field specs reject unknown columns when requested"):
    val row = Map(
      "id" -> RowValue.Str("u1"),
      "display_name" -> RowValue.Str("Ada"),
      "active" -> RowValue.Bool(true),
      "extra" -> RowValue.Str("nope")
    )

    assert(RowCodec[SchemaUser].decode(row).left.toOption.exists(_.render == "$.extra: unknown column 'extra'"))

  @rejectUnknown
  final case class AnnotatedUser(
      @key id: String,
      @fieldName("display_name") @aliases("name") displayName: String,
      active: Boolean = true
  ) derives RowCodec

  test("derived RowCodec uses schema annotations and case-class defaults"):
    val row = Map(
      "ID" -> RowValue.Str("u1"),
      "NAME" -> RowValue.Str("Ada")
    )

    assert(RowCodec[AnnotatedUser].decode(row) == Right(AnnotatedUser("u1", "Ada")))
    assert(RowCodec[AnnotatedUser].encode(AnnotatedUser("u1", "Ada", active = false)) == Map(
      "id" -> RowValue.Str("u1"),
      "display_name" -> RowValue.Str("Ada"),
      "active" -> RowValue.Bool(false)
    ))

  test("derived RowCodec rejects unknown columns when annotated"):
    val row = Map(
      "id" -> RowValue.Str("u1"),
      "display_name" -> RowValue.Str("Ada"),
      "extra" -> RowValue.Str("nope")
    )

    assert(RowCodec[AnnotatedUser].decode(row).left.toOption.exists(_.render == "$.extra: unknown column 'extra'"))
