package scalascript.cli.lsp

import org.scalatest.funsuite.AnyFunSuite
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets

class LspProtocolTest extends AnyFunSuite:

  // ─── encode / decode ────────────────────────────────────────────────

  test("encode + decode initialize request") {
    val req = LspProtocol.Request(
      id     = ujson.Num(1),
      method = "initialize",
      params = ujson.Obj("processId" -> ujson.Null)
    )
    val s   = LspProtocol.encode(req)
    LspProtocol.decode(s) match
      case Right(LspProtocol.Request(id, m, p)) =>
        assert(id == ujson.Num(1))
        assert(m == "initialize")
        assert(p("processId") == ujson.Null)
      case other => fail(s"unexpected: $other")
  }

  test("encode + decode notification") {
    val n = LspProtocol.Notification("initialized", ujson.Obj())
    val s = LspProtocol.encode(n)
    LspProtocol.decode(s) match
      case Right(LspProtocol.Notification(m, _)) => assert(m == "initialized")
      case other => fail(s"unexpected: $other")
  }

  test("encode + decode success response") {
    val r = LspProtocol.success(ujson.Num(42), ujson.Obj("ok" -> true))
    val s = LspProtocol.encode(r)
    LspProtocol.decode(s) match
      case Right(LspProtocol.Response(id, Some(res), None)) =>
        assert(id == ujson.Num(42))
        assert(res("ok").bool)
      case other => fail(s"unexpected: $other")
  }

  test("encode + decode error response") {
    val r = LspProtocol.failure(
      ujson.Num(2),
      LspProtocol.ErrorCodes.MethodNotFound,
      "method not found: foo"
    )
    val s = LspProtocol.encode(r)
    LspProtocol.decode(s) match
      case Right(LspProtocol.Response(id, None, Some(err))) =>
        assert(id == ujson.Num(2))
        assert(err.code == LspProtocol.ErrorCodes.MethodNotFound)
        assert(err.message.contains("method not found"))
      case other => fail(s"unexpected: $other")
  }

  test("decode rejects malformed JSON") {
    LspProtocol.decode("not json") match
      case Left(msg) => assert(msg.contains("malformed JSON"))
      case other     => fail(s"unexpected: $other")
  }

  test("decode rejects non-object payloads") {
    LspProtocol.decode("[1,2,3]") match
      case Left(msg) => assert(msg.contains("not a JSON object"))
      case other     => fail(s"unexpected: $other")
  }

  test("decode rejects payloads missing 'method'") {
    LspProtocol.decode("""{"id": 1, "jsonrpc": "2.0"}""") match
      case Left(msg) => assert(msg.contains("missing 'method'"))
      case other     => fail(s"unexpected: $other")
  }

  // ─── framing ──────────────────────────────────────────────────────

  test("readFrame round-trips a simple frame") {
    val payload = """{"jsonrpc":"2.0","id":1,"method":"ping","params":{}}"""
    val bytes   = payload.getBytes(StandardCharsets.UTF_8)
    val frame   = s"Content-Length: ${bytes.length}\r\n\r\n$payload"
    val in      = new ByteArrayInputStream(frame.getBytes(StandardCharsets.UTF_8))
    LspProtocol.readFrame(in) match
      case Right(Some(s)) => assert(s == payload)
      case other          => fail(s"unexpected: $other")
  }

  test("readFrame handles multiple sequential frames") {
    val p1 = """{"jsonrpc":"2.0","method":"a","params":{}}"""
    val p2 = """{"jsonrpc":"2.0","method":"b","params":{}}"""
    val sb = new StringBuilder()
    sb.append(s"Content-Length: ${p1.getBytes(StandardCharsets.UTF_8).length}\r\n\r\n")
    sb.append(p1)
    sb.append(s"Content-Length: ${p2.getBytes(StandardCharsets.UTF_8).length}\r\n\r\n")
    sb.append(p2)
    val in = new ByteArrayInputStream(sb.toString.getBytes(StandardCharsets.UTF_8))
    val a  = LspProtocol.readFrame(in)
    val b  = LspProtocol.readFrame(in)
    val c  = LspProtocol.readFrame(in)
    assert(a == Right(Some(p1)))
    assert(b == Right(Some(p2)))
    assert(c == Right(None))
  }

  test("readFrame returns None on clean EOF") {
    val in = new ByteArrayInputStream(Array.empty[Byte])
    assert(LspProtocol.readFrame(in) == Right(None))
  }

  test("readFrame rejects missing Content-Length") {
    val frame = "Content-Type: text/plain\r\n\r\n{}"
    val in    = new ByteArrayInputStream(frame.getBytes(StandardCharsets.UTF_8))
    LspProtocol.readFrame(in) match
      case Left(msg) => assert(msg.contains("missing Content-Length"))
      case other     => fail(s"unexpected: $other")
  }

  test("readFrame rejects malformed header") {
    val frame = "this is not a header\r\n\r\n{}"
    val in    = new ByteArrayInputStream(frame.getBytes(StandardCharsets.UTF_8))
    LspProtocol.readFrame(in) match
      case Left(msg) => assert(msg.contains("malformed header"))
      case other     => fail(s"unexpected: $other")
  }

  test("readFrame rejects negative Content-Length") {
    val frame = "Content-Length: not-a-number\r\n\r\n{}"
    val in    = new ByteArrayInputStream(frame.getBytes(StandardCharsets.UTF_8))
    LspProtocol.readFrame(in) match
      case Left(msg) => assert(msg.contains("invalid Content-Length"))
      case other     => fail(s"unexpected: $other")
  }

  test("readFrame handles UTF-8 multi-byte payload") {
    val payload = """{"jsonrpc":"2.0","method":"a","params":{"greeting":"Здравствуйте 你好"}}"""
    val bytes   = payload.getBytes(StandardCharsets.UTF_8)
    val frame   = new java.io.ByteArrayOutputStream()
    frame.write(s"Content-Length: ${bytes.length}\r\n\r\n".getBytes(StandardCharsets.US_ASCII))
    frame.write(bytes)
    val in = new ByteArrayInputStream(frame.toByteArray)
    LspProtocol.readFrame(in) match
      case Right(Some(s)) => assert(s == payload)
      case other          => fail(s"unexpected: $other")
  }

  test("writeFrame produces a well-formed frame") {
    val out = new ByteArrayOutputStream()
    LspProtocol.writeFrame(out, """{"a":1}""")
    val s = new String(out.toByteArray, StandardCharsets.UTF_8)
    assert(s.startsWith("Content-Length: 7\r\n\r\n"))
    assert(s.endsWith("""{"a":1}"""))
  }

  test("writeFrame + readFrame round-trip") {
    val out = new ByteArrayOutputStream()
    val payload = """{"jsonrpc":"2.0","id":99,"method":"ping","params":[]}"""
    LspProtocol.writeFrame(out, payload)
    val in = new ByteArrayInputStream(out.toByteArray)
    LspProtocol.readFrame(in) match
      case Right(Some(s)) => assert(s == payload)
      case other          => fail(s"unexpected: $other")
  }
