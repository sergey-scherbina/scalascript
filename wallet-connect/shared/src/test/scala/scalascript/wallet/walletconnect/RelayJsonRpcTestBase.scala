package scalascript.wallet.walletconnect

import org.scalatest.funsuite.AnyFunSuite

/** Cross-platform spec for [[RelayJsonRpc]] — pure ujson, no crypto. */
abstract class RelayJsonRpcTestBase extends AnyFunSuite:

  test("IdAllocator returns monotonically increasing values") {
    val ids = new RelayJsonRpc.IdAllocator(start = 100L)
    assert(ids.next() == 100L)
    assert(ids.next() == 101L)
    assert(ids.next() == 102L)
  }

  test("buildPublish produces a well-formed irn_publish frame") {
    val frame = RelayJsonRpc.buildPublish(
      id      = 5L,
      topic   = "topic-x",
      message = "Aa+Base64=",
      ttl     = 300L,
      tag     = 1102,
      prompt  = true,
    )
    assert(frame("id").num.toLong == 5L)
    assert(frame("jsonrpc").str == "2.0")
    assert(frame("method").str == "irn_publish")
    val params = frame("params").obj
    assert(params("topic").str   == "topic-x")
    assert(params("message").str == "Aa+Base64=")
    assert(params("ttl").num.toLong == 300L)
    assert(params("tag").num.toInt == 1102)
    assert(params("prompt").bool == true)
  }

  test("buildSubscribe / buildUnsubscribe carry the topic in params") {
    val sub   = RelayJsonRpc.buildSubscribe(1L, "topic-q")
    val unsub = RelayJsonRpc.buildUnsubscribe(2L, "topic-q", Some("sub-id-99"))
    assert(sub("method").str == "irn_subscribe")
    assert(sub("params").obj("topic").str == "topic-q")
    assert(unsub("method").str == "irn_unsubscribe")
    assert(unsub("params").obj("topic").str == "topic-q")
    assert(unsub("params").obj("id").str    == "sub-id-99")
  }

  test("render -> parse round-trips an irn_publish request as Other") {
    val frame = RelayJsonRpc.buildPublish(7L, "t", "MSG", 60L, 1100)
    val text  = RelayJsonRpc.render(frame)
    val back  = RelayJsonRpc.parse(text)
    back match
      case Some(RelayJsonRpc.Inbound.Other(Some("irn_publish"), _)) => succeed
      case other => fail(s"expected Other(irn_publish), got $other")
  }

  test("parse identifies an irn_subscription notification") {
    val raw = ujson.Obj(
      "id"      -> ujson.Num(42),
      "jsonrpc" -> ujson.Str("2.0"),
      "method"  -> ujson.Str("irn_subscription"),
      "params"  -> ujson.Obj(
        "id"   -> ujson.Str("sub-abc"),
        "data" -> ujson.Obj(
          "topic"       -> ujson.Str("topic-7"),
          "message"     -> ujson.Str("eW8="),
          "tag"         -> ujson.Num(1108),
          "publishedAt" -> ujson.Num(1700000000),
        ),
      ),
    ).render()
    val parsed = RelayJsonRpc.parse(raw).get
    parsed match
      case RelayJsonRpc.Inbound.Subscription(id, subId, topic, msg, tag, pubAt) =>
        assert(id == 42L)
        assert(subId == "sub-abc")
        assert(topic == "topic-7")
        assert(msg == "eW8=")
        assert(tag.contains(1108))
        assert(pubAt.contains(1700000000L))
      case other => fail(s"expected Subscription, got $other")
  }

  test("parse identifies a relay ack response") {
    val raw = """{"id":3,"jsonrpc":"2.0","result":true}"""
    RelayJsonRpc.parse(raw).get match
      case RelayJsonRpc.Inbound.Response(3, Some(_), None) => succeed
      case other => fail(s"expected Response, got $other")
  }

  test("parse returns None on malformed JSON") {
    assert(RelayJsonRpc.parse("not-json").isEmpty)
    assert(RelayJsonRpc.parse("{").isEmpty)
  }
