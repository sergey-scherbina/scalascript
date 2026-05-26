package scalascript.typeddata

import org.scalatest.funsuite.AnyFunSuite

class ObjectCodecTest extends AnyFunSuite:

  final case class Draft(id: String, title: String, body: String, dirty: Boolean)

  object Draft:
    private val idField = ObjectFieldSpec.key[String]("id")
    private val titleField = ObjectFieldSpec.required[String]("title", "name")
    private val bodyField = ObjectFieldSpec.withDefault[String]("body", "")
    private val dirtyField = ObjectFieldSpec.withDefault[Boolean]("dirty", false)

    given ObjectCodec[Draft] = ObjectCodec.objectCodec(
      draft => Map(
        idField.name -> JsonCodec[String].encode(draft.id),
        titleField.name -> JsonCodec[String].encode(draft.title),
        bodyField.name -> JsonCodec[String].encode(draft.body),
        dirtyField.name -> JsonCodec[Boolean].encode(draft.dirty)
      ),
      fields =>
        for
          id <- ObjectCodec.field(fields, idField)
          title <- ObjectCodec.field(fields, titleField)
          body <- ObjectCodec.field(fields, bodyField)
          dirty <- ObjectCodec.field(fields, dirtyField)
        yield Draft(id, title, body, dirty),
      fields = List(idField, titleField, bodyField, dirtyField),
      rejectUnknown = true
    )

  test("explicit ObjectCodec maps portable object fields and key metadata"):
    val draft = Draft("d1", "Plan", "ship it", dirty = true)
    val encoded = ObjectCodec[Draft].encode(draft)

    assert(encoded == ObjectValue.obj(
      "id" -> JsonValue.Str("d1"),
      "title" -> JsonValue.Str("Plan"),
      "body" -> JsonValue.Str("ship it"),
      "dirty" -> JsonValue.Bool(true)
    ))
    assert(ObjectCodec[Draft].keyField == Some("id"))
    assert(ObjectCodec[Draft].key(draft) == Some("d1"))
    assert(ObjectCodec[Draft].decode(encoded) == Right(draft))

  test("explicit ObjectCodec supports aliases, defaults, and unknown rejection"):
    val old = ObjectValue.obj(
      "id" -> JsonValue.Str("d1"),
      "name" -> JsonValue.Str("Old title")
    )
    assert(ObjectCodec[Draft].decode(old) == Right(Draft("d1", "Old title", "", dirty = false)))

    val extra = ObjectValue.obj(
      "id" -> JsonValue.Str("d1"),
      "title" -> JsonValue.Str("Plan"),
      "extra" -> JsonValue.Str("nope")
    )
    assert(ObjectCodec[Draft].decode(extra).left.toOption.exists(_.render == "$.extra: unknown field 'extra'"))

  @rejectUnknown
  final case class CachedTodo(
      @key id: String,
      @fieldName("text") @aliases("title") label: String,
      done: Boolean = false
  ) derives ObjectCodec

  test("derived ObjectCodec uses schema annotations and case-class defaults"):
    val stored = ObjectValue.obj(
      "id" -> JsonValue.Str("t1"),
      "title" -> JsonValue.Str("Cached")
    )

    assert(ObjectCodec[CachedTodo].decode(stored) == Right(CachedTodo("t1", "Cached")))
    assert(ObjectCodec[CachedTodo].encode(CachedTodo("t1", "Canonical", done = true)) == ObjectValue.obj(
      "id" -> JsonValue.Str("t1"),
      "text" -> JsonValue.Str("Canonical"),
      "done" -> JsonValue.Bool(true)
    ))
    assert(ObjectCodec[CachedTodo].keyField == Some("id"))
    assert(ObjectCodec[CachedTodo].key(CachedTodo("t1", "Cached")) == Some("t1"))

  test("derived ObjectCodec rejects unknown fields when annotated"):
    val stored = ObjectValue.obj(
      "id" -> JsonValue.Str("t1"),
      "text" -> JsonValue.Str("Cached"),
      "extra" -> JsonValue.Str("nope")
    )

    assert(ObjectCodec[CachedTodo].decode(stored).left.toOption.exists(_.render == "$.extra: unknown field 'extra'"))

  final case class JsonBacked(id: String, value: Int) derives JsonCodec

  test("ObjectCodec can wrap an existing object-shaped JsonCodec"):
    val codec = ObjectCodec.fromJsonCodec[JsonBacked](keyFieldName = Some("id"))
    val value = JsonBacked("j1", 7)

    assert(codec.encode(value) == ObjectValue.obj(
      "id" -> JsonValue.Str("j1"),
      "value" -> JsonValue.Num(7)
    ))
    assert(codec.key(value) == Some("j1"))
    assert(codec.decode(codec.encode(value)) == Right(value))

  test("ObjectCodec wraps non-object JsonCodec values under value field"):
    val codec = ObjectCodec.fromJsonCodec[Int]()

    assert(codec.encode(42) == ObjectValue.obj("value" -> JsonValue.Num(42)))
    assert(codec.decode(codec.encode(42)) == Right(42))
