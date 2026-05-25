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
