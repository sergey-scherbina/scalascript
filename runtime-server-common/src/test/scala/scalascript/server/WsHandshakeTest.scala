package scalascript.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class WsHandshakeTest extends AnyFunSuite with Matchers:

  // ── negotiateSubprotocol ───────────────────────────────────────

  test("negotiateSubprotocol — empty server list returns Some(\"\")") {
    WsHandshake.negotiateSubprotocol("chat, superchat", Nil) shouldBe Some("")
    WsHandshake.negotiateSubprotocol("", Nil) shouldBe Some("")
  }

  test("negotiateSubprotocol — server preference order wins on overlap") {
    WsHandshake.negotiateSubprotocol("v1, v2, v3", List("v2", "v1")) shouldBe Some("v2")
    WsHandshake.negotiateSubprotocol("v3, v1, v2", List("v2", "v1")) shouldBe Some("v2")
  }

  test("negotiateSubprotocol — no overlap returns None (caller refuses with 400)") {
    WsHandshake.negotiateSubprotocol("v3, v4", List("v1", "v2")) shouldBe None
  }

  test("negotiateSubprotocol — empty client offer with non-empty server list → None") {
    WsHandshake.negotiateSubprotocol("", List("v1")) shouldBe None
  }

  test("negotiateSubprotocol — tolerates whitespace and empty entries in client offer") {
    WsHandshake.negotiateSubprotocol("  v1 ,  , v2 ", List("v2")) shouldBe Some("v2")
  }

  // ── upgradeResponse ─────────────────────────────────────────────

  test("upgradeResponse — RFC 6455 §1.3 worked example") {
    // Same example WsFramingTest uses; the upgrade response embeds
    // `Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=`.
    val bytes = WsHandshake.upgradeResponse("dGhlIHNhbXBsZSBub25jZQ==", "")
    val text  = new String(bytes, "US-ASCII")
    text should startWith ("HTTP/1.1 101 Switching Protocols\r\n")
    text should include    ("Upgrade: websocket\r\n")
    text should include    ("Connection: Upgrade\r\n")
    text should include    ("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n")
    text should endWith    ("\r\n\r\n")
  }

  test("upgradeResponse — chosen subprotocol is emitted") {
    val bytes = WsHandshake.upgradeResponse("dGhlIHNhbXBsZSBub25jZQ==", "chat")
    val text  = new String(bytes, "US-ASCII")
    text should include ("Sec-WebSocket-Protocol: chat\r\n")
  }

  test("upgradeResponse — empty subprotocol skips the header") {
    val bytes = WsHandshake.upgradeResponse("dGhlIHNhbXBsZSBub25jZQ==", "")
    val text  = new String(bytes, "US-ASCII")
    text should not include ("Sec-WebSocket-Protocol:")
  }

  // ── rejectResponse ──────────────────────────────────────────────

  test("rejectResponse — fixed shape with Content-Length 0 and Connection: close") {
    val bytes = WsHandshake.rejectResponse(404, "Not Found")
    val text  = new String(bytes, "US-ASCII")
    text shouldBe "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
  }

  test("rejectResponse — preserves the supplied status code and reason verbatim") {
    new String(WsHandshake.rejectResponse(401, "Unauthorized"),         "US-ASCII") should include ("401 Unauthorized\r\n")
    new String(WsHandshake.rejectResponse(503, "Service Unavailable"),  "US-ASCII") should include ("503 Service Unavailable\r\n")
    new String(WsHandshake.rejectResponse(400, "Bad Request"),          "US-ASCII") should include ("400 Bad Request\r\n")
    new String(WsHandshake.rejectResponse(403, "Forbidden"),            "US-ASCII") should include ("403 Forbidden\r\n")
  }
