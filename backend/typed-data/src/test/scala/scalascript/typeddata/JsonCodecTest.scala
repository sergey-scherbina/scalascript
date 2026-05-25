package scalascript.typeddata

import org.scalatest.funsuite.AnyFunSuite

class JsonCodecTest extends AnyFunSuite:

  final case class Todo(id: String, text: String, done: Boolean, tags: List[String], priority: Option[Int])

  given JsonCodec[Todo] = JsonCodec.objectCodec(
    todo => Map(
      "id" -> JsonCodec[String].encode(todo.id),
      "text" -> JsonCodec[String].encode(todo.text),
      "done" -> JsonCodec[Boolean].encode(todo.done),
      "tags" -> JsonCodec[List[String]].encode(todo.tags),
      "priority" -> JsonCodec[Option[Int]].encode(todo.priority)
    ),
    fields =>
      for
        id <- JsonCodec.field[String](fields, "id")
        text <- JsonCodec.field[String](fields, "text")
        done <- JsonCodec.field[Boolean](fields, "done")
        tags <- JsonCodec.field[List[String]](fields, "tags")
        priority <- JsonCodec.field[Option[Int]](fields, "priority")
      yield Todo(id, text, done, tags, priority)
  )

  test("explicit JsonCodec encodes and decodes product-shaped values"):
    val todo = Todo("1", "ship", done = false, tags = List("typed", "json"), priority = Some(2))
    val encoded = JsonCodec[Todo].encode(todo)

    assert(encoded == JsonValue.obj(
      "id" -> JsonValue.Str("1"),
      "text" -> JsonValue.Str("ship"),
      "done" -> JsonValue.Bool(false),
      "tags" -> JsonValue.arr(JsonValue.Str("typed"), JsonValue.Str("json")),
      "priority" -> JsonValue.Num(2)
    ))
    assert(JsonCodec[Todo].decode(encoded) == Right(todo))

  test("JsonCodec reports field paths for nested decode failures"):
    val bad = JsonValue.obj(
      "id" -> JsonValue.Str("1"),
      "text" -> JsonValue.Str("ship"),
      "done" -> JsonValue.Bool(false),
      "tags" -> JsonValue.arr(JsonValue.Str("ok"), JsonValue.Num(1)),
      "priority" -> JsonValue.Null
    )

    val error = JsonCodec[Todo].decode(bad).left.toOption.get
    assert(error.render == "$.tags.1: expected string, got number")

  test("primitive JsonCodec rejects lossy integer decoding"):
    assert(JsonCodec[Int].decode(JsonValue.Num(BigDecimal("1.5"))).left.toOption.exists(_.render == "$: expected int, got 1.5"))

  final case class DerivedTodo(id: String, done: Boolean, priority: Option[Int]) derives JsonCodec
  final case class DerivedBoard(name: String, todos: List[DerivedTodo]) derives JsonCodec

  test("derives JsonCodec maps case classes by field name"):
    val board = DerivedBoard("main", List(DerivedTodo("1", done = true, priority = Some(3))))
    val encoded = JsonCodec[DerivedBoard].encode(board)

    assert(encoded == JsonValue.obj(
      "name" -> JsonValue.Str("main"),
      "todos" -> JsonValue.arr(JsonValue.obj(
        "id" -> JsonValue.Str("1"),
        "done" -> JsonValue.Bool(true),
        "priority" -> JsonValue.Num(3)
      ))
    ))
    assert(JsonCodec[DerivedBoard].decode(encoded) == Right(board))

  test("derived JsonCodec reports nested field paths"):
    val bad = JsonValue.obj(
      "name" -> JsonValue.Str("main"),
      "todos" -> JsonValue.arr(JsonValue.obj(
        "id" -> JsonValue.Str("1"),
        "done" -> JsonValue.Str("yes"),
        "priority" -> JsonValue.Null
      ))
    )

    val error = JsonCodec[DerivedBoard].decode(bad).left.toOption.get
    assert(error.render == "$.todos.0.done: expected boolean, got string")

  sealed trait Event derives JsonCodec
  final case class UserJoined(id: String, name: String) extends Event derives JsonCodec
  final case class Purchase(userId: String, amount: Double) extends Event derives JsonCodec
  case object Heartbeat extends Event derives JsonCodec

  test("derives JsonCodec maps ADTs with explicit discriminator and value payload"):
    val event: Event = Purchase("u1", 12.5)
    val encoded = JsonCodec[Event].encode(event)

    assert(encoded == JsonValue.obj(
      "$type" -> JsonValue.Str("Purchase"),
      "value" -> JsonValue.obj(
        "userId" -> JsonValue.Str("u1"),
        "amount" -> JsonValue.Num(BigDecimal(12.5))
      )
    ))
    assert(JsonCodec[Event].decode(encoded) == Right(event))

  test("derives JsonCodec handles case-object ADT variants"):
    val event: Event = Heartbeat
    val encoded = JsonCodec[Event].encode(event)

    assert(encoded == JsonValue.obj(
      "$type" -> JsonValue.Str("Heartbeat"),
      "value" -> JsonValue.obj()
    ))
    assert(JsonCodec[Event].decode(encoded) == Right(Heartbeat))

  test("derived ADT JsonCodec reports discriminator and payload errors"):
    val unknown = JsonValue.obj(
      "$type" -> JsonValue.Str("Missing"),
      "value" -> JsonValue.obj()
    )
    assert(JsonCodec[Event].decode(unknown).left.toOption.exists(_.render == "$.$type: unknown type 'Missing'"))

    val badPayload = JsonValue.obj(
      "$type" -> JsonValue.Str("UserJoined"),
      "value" -> JsonValue.obj(
        "id" -> JsonValue.Str("u1"),
        "name" -> JsonValue.Num(1)
      )
    )
    assert(JsonCodec[Event].decode(badPayload).left.toOption.exists(_.render == "$.value.name: expected string, got number"))

  final case class SchemaTodo(id: String, text: String, done: Boolean)

  object SchemaTodo:
    private val idField = JsonFieldSpec.required[String]("id")
    private val textField = JsonFieldSpec.required[String]("text", "title")
    private val doneField = JsonFieldSpec.withDefault[Boolean]("done", false)

    given JsonCodec[SchemaTodo] = JsonCodec.objectCodec(
      todo => Map(
        idField.name -> JsonCodec[String].encode(todo.id),
        textField.name -> JsonCodec[String].encode(todo.text),
        doneField.name -> JsonCodec[Boolean].encode(todo.done)
      ),
      fields =>
        for
          id <- JsonCodec.field(fields, idField)
          text <- JsonCodec.field(fields, textField)
          done <- JsonCodec.field(fields, doneField)
        yield SchemaTodo(id, text, done),
      fields = List(idField, textField, doneField),
      rejectUnknown = true
    )

  test("explicit JsonCodec field specs support aliases and defaults"):
    val json = JsonValue.obj(
      "id" -> JsonValue.Str("1"),
      "title" -> JsonValue.Str("from old schema")
    )

    assert(JsonCodec[SchemaTodo].decode(json) == Right(SchemaTodo("1", "from old schema", done = false)))

  test("explicit JsonCodec field specs reject unknown fields when requested"):
    val json = JsonValue.obj(
      "id" -> JsonValue.Str("1"),
      "text" -> JsonValue.Str("ship"),
      "extra" -> JsonValue.Str("nope")
    )

    assert(JsonCodec[SchemaTodo].decode(json).left.toOption.exists(_.render == "$.extra: unknown field 'extra'"))
