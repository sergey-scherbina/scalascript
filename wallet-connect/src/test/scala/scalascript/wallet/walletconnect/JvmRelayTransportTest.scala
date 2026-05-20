package scalascript.wallet.walletconnect

import java.nio.charset.StandardCharsets.UTF_8
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.blockchain.spi.ChainId

class JvmRelayTransportTest extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.global

  /** Synchronous in-memory `WsChannel` — records every outbound text
   *  frame and lets tests inject inbound ones via `simulateText`. */
  private final class MockWsChannel extends WsChannel:
    val sent       = scala.collection.mutable.ArrayBuffer.empty[String]
    val urls       = scala.collection.mutable.ArrayBuffer.empty[String]
    @volatile var handler: String => Unit = _ => ()
    def connect(url: String, headers: Map[String, String] = Map.empty): Future[Unit] =
      urls += url
      Future.successful(())
    def send(text: String): Future[Unit] =
      sent += text
      Future.successful(())
    def onText(h: String => Unit): Unit = handler = h
    def close(): Future[Unit] = Future.successful(())
    def simulateText(text: String): Unit = handler(text)

  /** A fresh wallet ed25519 seed (32 B). */
  private def newJwtSeed(): Array[Byte] =
    val rng = new java.security.SecureRandom()
    val b   = new Array[Byte](32)
    rng.nextBytes(b)
    b

  /** Extract the params.message field from the rendered irn_publish
   *  JSON the channel observed. */
  private def parseIrnPublish(text: String): (String /*topic*/, String /*message-b64*/, Int /*tag*/) =
    val frame = ujson.read(text)
    assert(frame("method").str == "irn_publish")
    val params = frame("params").obj
    (params("topic").str, params("message").str, params("tag").num.toInt)

  // ── outbound: ApproveSession on a pairing topic ───────────────────────

  test("ApproveSession on a pairing topic produces a Type-1 envelope decrypting to wc_sessionSettle") {
    val ws    = new MockWsChannel
    val store = new WcSessionStore
    val tx    = new JvmRelayTransport(ws, store, newJwtSeed())

    // dApp's X25519 keypair — the wallet learns the peerPub from the proposal.
    val peerKp = WcKeyAgreement.generateKeypair()

    // Pairing symKey from the wc: URI — registered before send.
    val pairingSymKey = Array.fill[Byte](32)(0x55.toByte)
    val pairingTopic  = WcKeyAgreement.hexEncode(WcKeyAgreement.topicFromSymKey(pairingSymKey))
    store.register(pairingTopic, pairingSymKey, Some(peerKp.publicKey))

    val response = WcSessionResponse(
      proposalId   = 7L,
      approved     = true,
      namespaces   = Map("eip155" -> WcNamespace(
        chains  = Seq(ChainId.Base),
        methods = Seq("personal_sign"),
        events  = Seq("accountsChanged"),
      )),
      sessionTopic = None,
    )

    tx.send(WcOutbound.ApproveSession(pairingTopic, response))

    assert(ws.sent.size == 1, "exactly one ws frame should have been emitted")
    val (topic, msgB64, _) = parseIrnPublish(ws.sent.head)
    assert(topic == pairingTopic)

    // Decrypt the envelope with the pairing symKey; expect Type-1 with the wallet's pubkey.
    val opened = WcEnvelope.open(pairingSymKey, WcEnvelope.decodeBase64(msgB64))
    assert(opened.senderPublicKey.isDefined, "wallet must ship its X25519 pubkey in Type-1 envelope")

    // The session topic should now be in the store under sha256(symKey).
    val sessionTopics = store.topics() - pairingTopic
    assert(sessionTopics.nonEmpty, "the transport must have registered the new session topic")

    // Verify the dApp could derive the same symKey via ECDH.
    val walletPub    = opened.senderPublicKey.get
    val sharedAtDapp = WcKeyAgreement.deriveSharedSecret(peerKp.privateKey, walletPub)
    val symKeyAtDapp = WcKeyAgreement.deriveSymKey(sharedAtDapp)
    val sessionTopicAtDapp = WcKeyAgreement.hexEncode(WcKeyAgreement.topicFromSymKey(symKeyAtDapp))
    assert(sessionTopics.contains(sessionTopicAtDapp),
      "wallet's stored session topic must match what the dApp would derive")

    // The plaintext is a wc_sessionSettle JSON-RPC method call.
    val payload = ujson.read(new String(opened.plaintext, UTF_8))
    assert(payload("method").str == "wc_sessionSettle")
    assert(payload("id").num.toLong == 7L)
    val nsObj = payload("params").obj("namespaces").obj
    assert(nsObj.contains("eip155"))
  }

  // ── outbound: EmitEvent on a session topic ────────────────────────────

  test("EmitEvent on a session topic publishes a Type-0 envelope that round-trips") {
    val ws    = new MockWsChannel
    val store = new WcSessionStore
    val tx    = new JvmRelayTransport(ws, store, newJwtSeed())

    val symKey       = Array.fill[Byte](32)(0x77.toByte)
    val sessionTopic = WcKeyAgreement.hexEncode(WcKeyAgreement.topicFromSymKey(symKey))
    store.register(sessionTopic, symKey, None)

    tx.send(WcOutbound.EmitEvent(sessionTopic, "accountsChanged",
      ujson.Arr(ujson.Str("0xabc")), ChainId.Base))

    assert(ws.sent.size == 1)
    val (topic, msgB64, tag) = parseIrnPublish(ws.sent.head)
    assert(topic == sessionTopic)
    assert(tag == RelayJsonRpc.Tag.SessionEvent)
    val opened = WcEnvelope.open(symKey, WcEnvelope.decodeBase64(msgB64))
    assert(opened.senderPublicKey.isEmpty, "EmitEvent must use Type-0 envelope")
    val payload = ujson.read(new String(opened.plaintext, UTF_8))
    assert(payload("method").str == "wc_sessionEvent")
    val params = payload("params").obj
    assert(params("chainId").str == ChainId.Base.toString)
    val event = params("event").obj
    assert(event("name").str == "accountsChanged")
    assert(event("data").arr.head.str == "0xabc")
  }

  // ── inbound: Type-0 wc_sessionRequest ─────────────────────────────────

  test("inbound irn_subscription with a Type-0 wc_sessionRequest dispatches WcInbound.Request") {
    val ws    = new MockWsChannel
    val store = new WcSessionStore
    val tx    = new JvmRelayTransport(ws, store, newJwtSeed())

    val symKey       = Array.fill[Byte](32)(0x88.toByte)
    val sessionTopic = WcKeyAgreement.hexEncode(WcKeyAgreement.topicFromSymKey(symKey))
    store.register(sessionTopic, symKey, None)

    @volatile var received: Option[WcInbound] = None
    tx.onInbound(msg => received = Some(msg))
    tx.connect("project-x")     // installs onText handler

    val innerJson = ujson.Obj(
      "id"      -> ujson.Num(99),
      "jsonrpc" -> ujson.Str("2.0"),
      "method"  -> ujson.Str("wc_sessionRequest"),
      "params"  -> ujson.Obj(
        "chainId" -> ujson.Str(ChainId.Base.toString),
        "request" -> ujson.Obj(
          "method" -> ujson.Str("personal_sign"),
          "params" -> ujson.Arr(ujson.Str("0xdata"), ujson.Str("0xaddr")),
        ),
      ),
    ).render()

    val sealedB64 = WcEnvelope.encodeBase64(
      WcEnvelope.sealType0(symKey, innerJson.getBytes(UTF_8))
    )

    val frame = ujson.Obj(
      "id"      -> ujson.Num(1),
      "jsonrpc" -> ujson.Str("2.0"),
      "method"  -> ujson.Str("irn_subscription"),
      "params"  -> ujson.Obj(
        "id"   -> ujson.Str("sub-1"),
        "data" -> ujson.Obj("topic" -> ujson.Str(sessionTopic), "message" -> ujson.Str(sealedB64)),
      ),
    ).render()

    ws.simulateText(frame)
    received match
      case Some(WcInbound.Request(req)) =>
        assert(req.id == 99L)
        assert(req.topic == sessionTopic)
        assert(req.chainId == ChainId.Base)
        assert(req.method == "personal_sign")
        assert(req.params.arr.size == 2)
        assert(req.params.arr.head.str == "0xdata")
      case other => fail(s"expected Request, got $other")
  }

  // ── inbound: unknown topic is silently dropped ────────────────────────

  test("inbound on an unknown topic — handler not called, no exception") {
    val ws    = new MockWsChannel
    val store = new WcSessionStore
    val tx    = new JvmRelayTransport(ws, store, newJwtSeed())

    @volatile var callCount = 0
    tx.onInbound(_ => callCount += 1)
    tx.connect("project-x")

    val sealedB64 = WcEnvelope.encodeBase64(
      WcEnvelope.sealType0(Array.fill[Byte](32)(0x55.toByte), "{}".getBytes(UTF_8))
    )
    val frame = ujson.Obj(
      "method" -> ujson.Str("irn_subscription"),
      "params" -> ujson.Obj(
        "id"   -> ujson.Str("sub-1"),
        "data" -> ujson.Obj("topic" -> ujson.Str("unknown-topic"), "message" -> ujson.Str(sealedB64)),
      ),
    ).render()
    ws.simulateText(frame)
    assert(callCount == 0)
  }

  // ── connect: ws URL carries the verifiable JWT ────────────────────────

  test("connect builds a ws URL with projectId + auth=<jwt>, and the JWT verifies") {
    val seed   = newJwtSeed()
    val pub    = RelayJwt.publicKeyFromPrivate(seed)
    val ws     = new MockWsChannel
    val tx     = new JvmRelayTransport(ws, new WcSessionStore, seed,
                                       relayUrl = "wss://relay.example.com")
    tx.connect("proj-123")

    assert(ws.urls.size == 1)
    val url = ws.urls.head
    assert(url.startsWith("wss://relay.example.com?projectId=proj-123&auth="))
    val jwt = url.split("auth=", 2)(1)
    val parts = jwt.split('.')
    assert(parts.length == 3, s"jwt must have 3 segments, got ${parts.length}")
    val unsigned = parts(0) + "." + parts(1)
    val sig      = java.util.Base64.getUrlDecoder.decode(parts(2))
    assert(RelayJwt.verify(unsigned, sig, pub),
      "JWT signature must verify under the wallet's public key")
  }

  // ── outbound: RequestResult round-trips a Type-0 envelope ─────────────

  test("RequestResult publishes a Type-0 envelope decoding to the JSON-RPC response") {
    val ws    = new MockWsChannel
    val store = new WcSessionStore
    val tx    = new JvmRelayTransport(ws, store, newJwtSeed())

    val symKey       = Array.fill[Byte](32)(0xcc.toByte)
    val sessionTopic = WcKeyAgreement.hexEncode(WcKeyAgreement.topicFromSymKey(symKey))
    store.register(sessionTopic, symKey, None)

    tx.send(WcOutbound.RequestResult(sessionTopic,
      WcSessionResult.Ok(123L, ujson.Str("0xsigned"))))

    assert(ws.sent.size == 1)
    val (topic, msgB64, tag) = parseIrnPublish(ws.sent.head)
    assert(topic == sessionTopic)
    assert(tag == RelayJsonRpc.Tag.SessionRequestResponse)
    val opened = WcEnvelope.open(symKey, WcEnvelope.decodeBase64(msgB64))
    val payload = ujson.read(new String(opened.plaintext, UTF_8))
    assert(payload("id").num.toLong == 123L)
    assert(payload("jsonrpc").str == "2.0")
    assert(payload("result").str == "0xsigned")
  }

  // ── outbound: spinning until a future-based handler completes ─────────

  test("subscribe / unsubscribe emit irn_subscribe / irn_unsubscribe JSON-RPC frames") {
    val ws = new MockWsChannel
    val tx = new JvmRelayTransport(ws, new WcSessionStore, newJwtSeed())
    tx.subscribe("topic-A")
    tx.unsubscribe("topic-A")
    assert(ws.sent.size == 2)
    val sub = ujson.read(ws.sent(0))
    val uns = ujson.read(ws.sent(1))
    assert(sub("method").str == "irn_subscribe")
    assert(sub("params").obj("topic").str == "topic-A")
    assert(uns("method").str == "irn_unsubscribe")
    assert(uns("params").obj("topic").str == "topic-A")
  }

  /** Spin-wait helper kept around for asynchronous extensions of the
   *  test suite — currently every assertion is synchronous because
   *  `MockWsChannel`'s Futures resolve eagerly. */
  @annotation.unused
  private def awaitTrue(cond: => Boolean, timeout: FiniteDuration): Unit =
    val deadline = System.currentTimeMillis() + timeout.toMillis
    while !cond && System.currentTimeMillis() < deadline do Thread.sleep(10)
    require(cond, s"condition not met within $timeout")
