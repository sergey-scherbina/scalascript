package scalascript.uniml.address

import org.scalatest.funsuite.AnyFunSuite

/** The addressing model on its SECOND format — same triple, different resolver.
 *
 *  What is worth asserting here is not "we can walk JSON" but the two things the model claims:
 *  that both halves of an address are real (the path AND the bytes), and that stability is
 *  reported from the format rather than assumed.
 */
class JsonAddressTest extends AnyFunSuite:

  private val doc =
    """{"users":[{"name":"ann","age":34},{"name":"bob","age":41}],"active":true,"note":null}"""

  private def at(path: String): DocAddressedValue =
    JsonAddress.read(doc, path) match
      case Right(v) => v
      case Left(m)  => fail(s"$path → $m")

  test("an address carries BOTH halves: the format's own type/value AND where the bytes are") {
    val name = at("users/0/name")
    assert(name.typeName == "string")
    assert(name.value == "\"ann\"", "the value is the lexeme as written, never reinterpreted")
    // The physical half must actually point at those bytes in the source.
    assert(doc.substring(name.offset, name.offset + name.length) == "\"ann\"",
      s"offset ${name.offset} length ${name.length} must select the value in the source")
    assert(name.line == 1 && name.column > 1)
  }

  test("the format's own types, not a universal lattice") {
    assert(at("users/1/age").typeName == "number")
    assert(at("users/1/age").value == "41")
    assert(at("active").typeName == "boolean")
    assert(at("note").typeName == "null")
    assert(at("users").typeName == "array")
    assert(at("users/0").typeName == "object")
    // The document root addresses as itself.
    assert(at("").typeName == "object")
  }

  test("a composite's physical half spans exactly the composite") {
    val users = at("users")
    assert(doc.substring(users.offset, users.offset + users.length).startsWith("[{\"name\":\"ann\""))
    assert(doc.substring(users.offset, users.offset + users.length).endsWith("}]"))
  }

  test("STABILITY: an object key names by identity, an array index names by position") {
    // A key is a NAME: it survives its neighbours changing.
    assert(at("active").stable, "an object key addresses by name → stable")
    assert(at("users").stable)

    // An index is a POSITION: insert a sibling before it and this same address silently means a
    // different value. That is exactly the non-IPK rowid situation, and it must be said out loud.
    assert(!at("users/0").stable, "an array index addresses by position → NOT stable")

    // ...and everything beneath it inherits that. A stable key under a positional index is still
    // reachable only through a position, so the whole address is positional.
    assert(!at("users/0/name").stable,
      "a key under an array index is still positional — stability cannot be regained by descending")
  }

  test("STABILITY is not theory: inserting a sibling silently moves a positional address") {
    val before = JsonAddress.read(doc, "users/0/name").toOption.get.value
    assert(before == "\"ann\"")
    val grown = """{"users":[{"name":"zoe"},{"name":"ann","age":34},{"name":"bob","age":41}],"active":true,"note":null}"""
    val after = JsonAddress.read(grown, "users/0/name").toOption.get.value
    assert(after == "\"zoe\"", "the SAME address now names a different value — which is why it reports stable=false")
    // The stable one still means what it meant.
    assert(JsonAddress.read(grown, "active").toOption.get.value ==
           JsonAddress.read(doc, "active").toOption.get.value)
  }

  test("an address that does not resolve is an error, never a guess") {
    assert(JsonAddress.read(doc, "nope").left.exists(_.contains("no such key")))
    assert(JsonAddress.read(doc, "users/9/name").left.exists(_.contains("out of range")))
    assert(JsonAddress.read(doc, "users/x").left.exists(_.contains("not an array index")))
    assert(JsonAddress.read(doc, "active/deeper").left.exists(_.contains("cannot descend")))
    assert(JsonAddress.read("{", "a").isLeft, "a broken document is a failed read, not an empty one")
  }

  test("duplicate keys: the first wins, and it is a real answer rather than a refusal") {
    // JSON permits duplicates; UniML keeps both losslessly. Addressing has to pick one and say so
    // by behaviour — first-wins matches document order, which is what a path implies.
    val dup = """{"a":1,"a":2}"""
    assert(JsonAddress.read(dup, "a").toOption.get.value == "1")
  }

  test("whitespace and multi-line documents keep the physical half exact") {
    val pretty = "{\n  \"a\": {\n    \"b\": 7\n  }\n}"
    val b = JsonAddress.read(pretty, "a/b") match
      case Right(v) => v
      case Left(m)  => fail(m)
    assert(b.typeName == "number" && b.value == "7")
    assert(pretty.substring(b.offset, b.offset + b.length) == "7")
    assert(b.line == 3, s"line must be the real source line, got ${b.line}")
  }
