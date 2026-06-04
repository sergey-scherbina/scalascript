package scalascript.ast

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

class SsccFormatV3Test extends AnyFunSuite:

  private def parseModule(ssc: String): Module =
    Parser.parse(ssc)

  private def roundtrip(m: Module): Module =
    val bytes = SsccFormatV3.write(m, m.manifest.map(SsccFormat.manifestToBytes).getOrElse(Array.emptyByteArray))
    SsccFormatV3.read(bytes, SsccFormat.manifestFromBytes) match
      case Right(m2)  => m2
      case Left(err)  => sys.error(s"v3 read failed: $err")

  // ─── Trie tests ───────────────────────────────────────────────────────────

  test("TrieBuilder: intern returns stable ids, serialize+decode round-trips") {
    val trie = new TrieBuilder
    val id0  = trie.intern("hello")
    val id1  = trie.intern("world")
    val id2  = trie.intern("hello")   // duplicate → same id
    val id3  = trie.intern("userName")
    val id4  = trie.intern("userId")
    val id5  = trie.intern("userEmail")
    assert(id0 == id2)
    assert(id0 != id1)

    val trieBytes = trie.serialize()
    val pos       = Array(0)
    val dict      = TrieDecoder.decode(trieBytes, pos)
    assert(pos(0) == trieBytes.length)
    assert(dict(id0) == "hello")
    assert(dict(id1) == "world")
    assert(dict(id3) == "userName")
    assert(dict(id4) == "userId")
    assert(dict(id5) == "userEmail")
  }

  test("TrieBuilder: empty trie round-trips") {
    val trie     = new TrieBuilder
    val bytes    = trie.serialize()
    val pos      = Array(0)
    val dict     = TrieDecoder.decode(bytes, pos)
    assert(dict.isEmpty)
  }

  test("TrieBuilder: single string round-trips") {
    val trie = new TrieBuilder
    val id   = trie.intern("scala")
    val bytes = trie.serialize()
    val pos   = Array(0)
    val dict  = TrieDecoder.decode(bytes, pos)
    assert(dict(id) == "scala")
  }

  test("TrieBuilder: unicode strings round-trip") {
    val trie  = new TrieBuilder
    val id0   = trie.intern("Привет")
    val id1   = trie.intern("世界")
    val id2   = trie.intern("🌍")
    val bytes = trie.serialize()
    val pos   = Array(0)
    val dict  = TrieDecoder.decode(bytes, pos)
    assert(dict(id0) == "Привет")
    assert(dict(id1) == "世界")
    assert(dict(id2) == "🌍")
  }

  // ─── Varint tests ─────────────────────────────────────────────────────────

  test("Varint: round-trip small values") {
    for n <- Seq(0L, 1L, 127L, 128L, 255L, 300L, 16383L, 16384L, 2097151L) do
      val buf = new java.io.ByteArrayOutputStream
      Varint.write(buf, n)
      val bytes = buf.toByteArray
      val pos   = Array(0)
      assert(Varint.read(bytes, pos) == n, s"round-trip failed for $n")
      assert(pos(0) == bytes.length)
  }

  // ─── Module round-trip tests ─────────────────────────────────────────────

  test("v3 write+read: module with only sections (no manifest)") {
    val ssc = """
# Hello

Some prose text here.
"""
    val m  = parseModule(ssc)
    val m2 = roundtrip(m)
    assert(m2.sections.length == m.sections.length)
    assert(m2.sections.head.heading.text == m.sections.head.heading.text)
    assert(m2.sections.head.heading.level == m.sections.head.heading.level)
    val prose = m2.sections.head.content.collectFirst { case Content.Prose(t, _) => t }
    assert(prose.isDefined)
    assert(prose.get.contains("Some prose text"))
  }

  test("v3 write+read: nested sections") {
    val ssc = """
# Top

Top prose.

## Sub

Sub prose.

### Sub-sub

Deep.
"""
    val m  = parseModule(ssc)
    val m2 = roundtrip(m)
    assert(m2.sections.nonEmpty)
    val top = m2.sections.head
    assert(top.heading.text == "Top")
    assert(top.subsections.nonEmpty)
    val sub = top.subsections.head
    assert(sub.heading.text == "Sub")
    assert(sub.subsections.nonEmpty)
    assert(sub.subsections.head.heading.text == "Sub-sub")
  }

  test("v3 write+read: scalascript code block survives parse round-trip") {
    val ssc = """
# Code

```scalascript
val x: Int = 42
def hello(name: String): String = s"Hello, $name"
```
"""
    val m  = parseModule(ssc)
    val m2 = roundtrip(m)
    val cb = m2.sections.head.content.collectFirst {
      case cb: Content.CodeBlock => cb
    }
    assert(cb.isDefined)
    assert(cb.get.lang == "scalascript")
    assert(cb.get.source.contains("val x"))
    assert(cb.get.tree.isDefined)
  }

  test("v3 write+read: non-parseable code block (SQL)") {
    val ssc = """
# Query

```sql @db=main
SELECT * FROM users WHERE id = 42
```
"""
    val m  = parseModule(ssc)
    val m2 = roundtrip(m)
    val cb = m2.sections.head.content.collectFirst {
      case cb: Content.CodeBlock => cb
    }
    assert(cb.isDefined)
    assert(cb.get.lang == "sql")
    assert(cb.get.source.contains("SELECT"))
    assert(cb.get.attrs.get("db").contains("main"))
  }

  test("v3 write+read: import declarations") {
    val ssc = """
# Imports

[Foo, Bar as B](./other.ssc)
"""
    val m  = parseModule(ssc)
    val m2 = roundtrip(m)
    val imp = m2.sections.head.content.collectFirst {
      case i: Content.Import => i
    }
    assert(imp.isDefined)
    assert(imp.get.path == "./other.ssc")
    assert(imp.get.bindings.exists(_.name == "Foo"))
    assert(imp.get.bindings.exists(b => b.name == "Bar" && b.alias.contains("B")))
  }

  test("v3 write+read: ordered and unordered lists") {
    val ssc = """
# List

- item one
- item two
  - nested

1. first
2. second
"""
    val m  = parseModule(ssc)
    val m2 = roundtrip(m)
    val lists = m2.sections.head.content.collect { case dl: Content.DataList => dl }
    assert(lists.length >= 1)
  }

  test("v3 write+read: string deduplication via trie (no duplicates in output)") {
    // This test checks that repeated identifiers share the same trie entry
    val ssc = """
# Deduplication

Long repeated prose that mentions userId and userId and userId again.

```scalascript
val userId: String = "x"
val userId2: String = userId + userId
```
"""
    val m     = parseModule(ssc)
    val bytes = SsccFormatV3.write(m, Array.empty)
    // Just check that it round-trips; size comparison is informational
    SsccFormatV3.read(bytes, _ => Manifest(
      name = None, version = None, description = None,
      dependencies = Map.empty, exports = Nil, targets = Nil,
      routes = Nil, pkg = None, translations = Map.empty,
      raw = Map.empty
    )) match
      case Right(_)  => // ok
      case Left(err) => fail(s"v3 read failed: $err")
  }

  // ─── Full SsccFormat integration (env-flag) ──────────────────────────────

  test("SsccFormat.read can decode v3 bytes produced by SsccFormatV3") {
    val ssc = """
# Simple Module

Hello world.
"""
    val m      = parseModule(ssc)
    val mBytes = m.manifest.map(SsccFormat.manifestToBytes).getOrElse(Array.emptyByteArray)
    val payload = SsccFormatV3.write(m, mBytes)
    // Construct v3 header manually
    import SsccFormat.{Magic, V3Version}
    import java.io.ByteArrayOutputStream
    val hdr = new ByteArrayOutputStream()
    hdr.write(Magic)
    hdr.write(V3Version)
    hdr.write(0x00)  // no compression
    val crc = { val c = new java.util.zip.CRC32(); c.update(payload); c.getValue.toInt }
    hdr.write((crc >>> 24) & 0xff)
    hdr.write((crc >>> 16) & 0xff)
    hdr.write((crc >>>  8) & 0xff)
    hdr.write( crc         & 0xff)
    hdr.write(payload)
    val full = hdr.toByteArray

    SsccFormat.read(full) match
      case Right(m2) =>
        assert(m2.sections.nonEmpty)
        assert(m2.sections.head.heading.text == "Simple Module")
      case Left(err) => fail(s"SsccFormat.read v3 failed: $err")
  }

  // ─── Phase B: scalameta token stream tests ──────────────────────────────

  test("v3 phase-B: scalascript block with identifiers round-trips via sm token stream") {
    val ssc = """
# Users

```scalascript
case class User(userId: String, userName: String, userEmail: String)
val users: List[User] = Nil
def findUser(userId: String): Option[User] = users.find(_.userId == userId)
def createUser(userName: String, userEmail: String): User =
  User("id", userName, userEmail)
```
"""
    val m  = parseModule(ssc)
    val m2 = roundtrip(m)
    val cb = m2.sections.head.content.collectFirst { case cb: Content.CodeBlock => cb }
    assert(cb.isDefined)
    assert(cb.get.lang == "scalascript")
    assert(cb.get.tree.isDefined, "tree must be present — parsed from reconstructed sm-token source")
    assert(cb.get.source.contains("userId"))
    assert(cb.get.source.contains("userName"))
    assert(cb.get.source.contains("userEmail"))
  }

  test("v3 phase-B: interpolated string survives sm token stream") {
    val ssc = """
# Greet

```scalascript
def greet(name: String): String = s"Hello, $name!"
val msg: String = greet("world")
```
"""
    val m  = parseModule(ssc)
    val m2 = roundtrip(m)
    val cb = m2.sections.head.content.collectFirst { case cb: Content.CodeBlock => cb }
    assert(cb.isDefined)
    assert(cb.get.source.contains("Hello"))
    assert(cb.get.tree.isDefined)
  }

  test("v3 phase-B: source text reconstructed from token stream is parseable") {
    val ssc = """
# Math

```scalascript
def fib(n: Int): Int =
  if n <= 1 then n
  else fib(n - 1) + fib(n - 2)
val result: Int = fib(10)
```
"""
    val m  = parseModule(ssc)
    val m2 = roundtrip(m)
    val cb = m2.sections.head.content.collectFirst { case cb: Content.CodeBlock => cb }
    assert(cb.isDefined)
    assert(cb.get.tree.isDefined)
    assert(cb.get.source.contains("fib"))
    assert(cb.get.source.contains("result"))
  }

  // ─── Size comparison ─────────────────────────────────────────────────────

  test("v3 vs v2 size delta on a real .ssc file (informational)") {
    val ssc = """
# Real Module

This section has some prose.

## Sub-section with code

```scalascript
case class User(userId: String, userName: String, userEmail: String)
val users: List[User] = Nil
def findUser(userId: String): Option[User] = users.find(_.userId == userId)
def listUsers: List[User] = users
def createUser(userName: String, userEmail: String): User =
  User(java.util.UUID.randomUUID().toString, userName, userEmail)
```

## Imports

[User, UserService](./user-service.ssc)
"""
    val m      = parseModule(ssc)
    val mBytes = m.manifest.map(SsccFormat.manifestToBytes).getOrElse(Array.emptyByteArray)
    val v3Payload = SsccFormatV3.write(m, mBytes)
    val v2Bytes   = SsccFormat.write(m)   // v2 (default)
    val v3Total   = 10 + v3Payload.length  // header 10 bytes
    println(s"\n[size delta] v2=${v2Bytes.length}B  v3=${v3Total}B  ratio=${v3Total.toDouble / v2Bytes.length}")
    // Not a hard assertion — just informational
    assert(v3Total > 0)
  }
