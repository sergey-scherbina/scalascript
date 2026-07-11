package ssc.plugin.httpfast

import org.scalatest.funsuite.AnyFunSuite
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}

class HttpProtocolTest extends AnyFunSuite:

  private def parse(raw: String): RawRequest =
    val in  = new ByteArrayInputStream(raw.getBytes(ISO_8859_1))
    val out = new ByteArrayOutputStream()
    val r   = HttpProtocol.parse(new HttpReader(in), out, HttpProtocol.Limits())
    assert(r != null, "expected a parsed request")
    r.nn

  test("parses request line, headers, and a Content-Length body") {
    val req = parse(
      "POST /submit?x=1 HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n\r\nhello")
    assert(req.method == "POST")
    assert(req.path == "/submit")
    assert(req.headers("host") == "localhost")
    assert(new String(req.body, UTF_8) == "hello")
    assert(req.query == Map("x" -> "1"))
    assert(req.keepAlive) // HTTP/1.1 default
  }

  test("GET with no body, query parsing + percent/plus decode") {
    val req = parse("GET /search?q=hello+world&n=%41 HTTP/1.1\r\nHost: x\r\n\r\n")
    assert(req.method == "GET")
    assert(req.path == "/search")
    assert(req.query == Map("q" -> "hello world", "n" -> "A"))
    assert(req.body.isEmpty)
  }

  test("percent-decodes the path but keeps + literal in the path") {
    val req = parse("GET /a%2Fb+c HTTP/1.1\r\nHost: x\r\n\r\n")
    assert(req.path == "/a/b+c")
  }

  test("Connection: close disables keep-alive; HTTP/1.0 defaults to close") {
    assert(!parse("GET / HTTP/1.1\r\nConnection: close\r\n\r\n").keepAlive)
    assert(!parse("GET / HTTP/1.0\r\nHost: x\r\n\r\n").keepAlive)
    assert(parse("GET / HTTP/1.0\r\nConnection: keep-alive\r\n\r\n").keepAlive)
  }

  test("decodes a chunked body") {
    val req = parse(
      "POST /u HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n")
    assert(new String(req.body, UTF_8) == "hello world")
  }

  test("duplicate header values are joined with a comma") {
    val req = parse("GET / HTTP/1.1\r\nX-Tag: a\r\nX-Tag: b\r\n\r\n")
    assert(req.headers("x-tag") == "a, b")
  }

  test("Expect: 100-continue elicits an interim response before the body") {
    val in  = new ByteArrayInputStream(
      "POST / HTTP/1.1\r\nExpect: 100-continue\r\nContent-Length: 2\r\n\r\nhi".getBytes(ISO_8859_1))
    val out = new ByteArrayOutputStream()
    val req = HttpProtocol.parse(new HttpReader(in), out, HttpProtocol.Limits()).nn
    assert(out.toString(ISO_8859_1).startsWith("HTTP/1.1 100 Continue"))
    assert(new String(req.body, UTF_8) == "hi")
  }

  test("a clean EOF at the request start returns null (connection closed)") {
    val in  = new ByteArrayInputStream(Array.emptyByteArray)
    val out = new ByteArrayOutputStream()
    assert(HttpProtocol.parse(new HttpReader(in), out, HttpProtocol.Limits()) == null)
  }

  test("a body over the limit is rejected") {
    val in  = new ByteArrayInputStream(
      "POST / HTTP/1.1\r\nContent-Length: 100\r\n\r\n".getBytes(ISO_8859_1))
    val out = new ByteArrayOutputStream()
    assertThrows[BadRequest](
      HttpProtocol.parse(new HttpReader(in), out, HttpProtocol.Limits(maxBodyBytes = 10)))
  }

  test("a header line longer than the read buffer is parsed (buffer grows)") {
    val big = "x" * 40000
    val req = parse(s"GET / HTTP/1.1\r\nX-Big: $big\r\n\r\n")
    assert(req.headers("x-big") == big)
  }

  test("writeResponse emits status line, Content-Length, Date, and Connection") {
    val out = new ByteArrayOutputStream()
    HttpProtocol.writeResponse(out,
      RawResponse(200, Map("Content-Type" -> "text/plain"), "hi".getBytes(UTF_8)), keepAlive = true)
    val s = out.toString(ISO_8859_1)
    assert(s.startsWith("HTTP/1.1 200 OK\r\n"))
    assert(s.contains("Content-Type: text/plain\r\n"))
    assert(s.contains("Content-Length: 2\r\n"))
    assert(s.contains("Connection: keep-alive\r\n"))
    assert(s.contains("Date: "))
    assert(s.endsWith("\r\n\r\nhi"))
  }
