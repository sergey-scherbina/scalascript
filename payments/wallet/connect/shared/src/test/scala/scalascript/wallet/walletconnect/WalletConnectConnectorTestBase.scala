package scalascript.wallet.walletconnect

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{ExecutionContext, Future}
import scalascript.blockchain.spi.*
import scalascript.wallet.spi.*

/** Cross-platform spec for [[WalletConnectConnector]] against a mock
 *  relay transport.  Uses `ExecutionContext.parasitic` so the
 *  request-handler's `Future` continuation runs inline — keeps the
 *  test sync-friendly on Scala.js (no `Await.result` available there).
 *
 *  The wire crypto / actual relay ws are out of scope here — the
 *  session-lifecycle + request-routing logic is what's exercised. */
abstract class WalletConnectConnectorTestBase extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.parasitic

  private val baseId    = ChainId.Base
  private val sepoliaId = ChainId.BaseSepolia

  /** Minimal AccountManager — claims to support the two Base-family
   *  chains. The strategies / adapters are intentionally stubs since
   *  we don't exercise signing in this test. */
  private val mgr: AccountManager = new AccountManager:
    def chains: Set[ChainId] = Set(baseId, sepoliaId)
    def strategyFor(c: ChainId): Option[AccountStrategy] =
      if chains.contains(c) then Some(new StubStrategy) else None
    def adapterFor(c: ChainId): Option[ChainAdapter] = None
    def request(req: DappRequest): Future[DappResponse] =
      Future.successful(DappResponse.Ok(ujson.Null))

  private class StubStrategy extends AccountStrategy:
    def kind = "stub"
    def getAddress(c: ChainAdapter): Future[String] = Future.successful("0xstubaddress")
    def signTransaction(c: ChainAdapter)(tx: c.Tx): Future[c.SignedTx] = ???
    def signMessage(c: ChainAdapter, m: Array[Byte]): Future[Array[Byte]] = ???
    def signTypedData(c: ChainAdapter, td: TypedData): Future[Array[Byte]] = ???

  /** Mock transport: records outbound messages, exposes a
   *  `simulateInbound` to drive the connector. */
  private class MockTransport extends WcRelayTransport:
    val sent       = scala.collection.mutable.ArrayBuffer.empty[WcOutbound]
    val subscribed = scala.collection.mutable.Set.empty[String]
    @volatile var handler: WcInbound => Unit = _ => ()
    def connect(projectId: String): Future[Unit] = Future.unit
    def subscribe(topic: String): Future[Unit]   =
      subscribed += topic
      Future.unit
    def unsubscribe(topic: String): Future[Unit] =
      subscribed -= topic
      Future.unit
    def send(message: WcOutbound): Future[Unit]  =
      sent += message
      Future.unit
    def onInbound(h: WcInbound => Unit): Unit    = handler = h
    def close(): Future[Unit]                    = Future.unit
    def simulateInbound(msg: WcInbound): Unit    = handler(msg)

  private val walletMeta = WcAppMetadata(
    name = "ScalaScript Wallet", description = "test", url = "https://example.com",
    icons = Seq.empty,
  )

  private def newConnector(handler: (ChainId, String, ujson.Value) => Future[ujson.Value] = defaultHandler)
      : (WalletConnectConnector, MockTransport) =
    val tx = new MockTransport
    val c  = new WalletConnectConnector(mgr, tx, handler, "test-project-id", walletMeta)
    c.attach(mgr)
    (c, tx)

  private val defaultHandler: (ChainId, String, ujson.Value) => Future[ujson.Value] =
    (_, _, _) => Future.successful(ujson.Str("0xdeadbeef"))

  private val proposer = WcParticipant(
    publicKey = "0xpeerpub",
    metadata  = WcAppMetadata("DApp", "desc", "https://dapp.example", Seq.empty),
  )

  // ── lifecycle ─────────────────────────────────────────────────────────

  test("attach + detach flip isAttached") {
    val (c, _) = newConnector()
    assert(c.isAttached)
    c.detach()
    assert(!c.isAttached)
  }

  // ── session proposals ─────────────────────────────────────────────────

  test("proposal with all-supported chains is approved and subscribed") {
    val (_, tx) = newConnector()
    val proposal = WcSessionProposal(
      id           = 1L,
      pairingTopic = "pairing-abcd1234",
      proposer     = proposer,
      requiredNamespaces = Map(
        "eip155" -> WcNamespace(
          chains  = Seq(baseId),
          methods = Seq("personal_sign", "eth_sendTransaction"),
          events  = Seq("accountsChanged", "chainChanged"),
        ),
      ),
    )
    tx.simulateInbound(WcInbound.Proposal(proposal))
    assert(tx.sent.size == 1)
    tx.sent.head match
      case WcOutbound.ApproveSession(topic, resp) =>
        assert(topic == "pairing-abcd1234")
        assert(resp.proposalId == 1L)
        assert(resp.approved)
        assert(resp.namespaces.contains("eip155"))
        assert(resp.sessionTopic.isDefined)
        assert(tx.subscribed.contains(resp.sessionTopic.get))
      case other => fail(s"expected ApproveSession, got $other")
  }

  test("proposal with unsupported chain is rejected") {
    val (_, tx) = newConnector()
    val proposal = WcSessionProposal(
      id           = 2L,
      pairingTopic = "pairing-x",
      proposer     = proposer,
      requiredNamespaces = Map(
        "eip155" -> WcNamespace(
          chains  = Seq(ChainId.EthereumMainnet),     // not in our supported set
          methods = Seq("personal_sign"),
          events  = Seq.empty,
        ),
      ),
    )
    tx.simulateInbound(WcInbound.Proposal(proposal))
    assert(tx.sent.size == 1)
    tx.sent.head match
      case WcOutbound.RejectSession(topic, resp) =>
        assert(topic == "pairing-x")
        assert(!resp.approved)
        assert(resp.reason.contains("unsupported chains"))
        assert(resp.reason.contains("eip155:1"))
      case other => fail(s"expected RejectSession, got $other")
  }

  // ── request routing ───────────────────────────────────────────────────

  test("session request is dispatched to the handler and result echoed back") {
    var capturedMethod: Option[String]  = None
    var capturedChain:  Option[ChainId] = None
    val (_, tx) = newConnector((chain, method, _) =>
      capturedMethod = Some(method)
      capturedChain  = Some(chain)
      Future.successful(ujson.Str("0xok"))
    )
    // approve a session first
    tx.simulateInbound(WcInbound.Proposal(WcSessionProposal(
      id = 1L, pairingTopic = "p", proposer = proposer,
      requiredNamespaces = Map("eip155" ->
        WcNamespace(Seq(baseId), Seq("eth_sendTransaction"), Seq.empty)),
    )))
    val sessionTopic = tx.sent.head.asInstanceOf[WcOutbound.ApproveSession].response.sessionTopic.get
    tx.sent.clear()

    tx.simulateInbound(WcInbound.Request(WcSessionRequest(
      id = 99L, topic = sessionTopic, chainId = baseId,
      method = "personal_sign", params = ujson.Arr(),
    )))
    // With `ExecutionContext.parasitic` the request-handler's
    // `Future.successful(...).onComplete` continuation fires inline,
    // so `tx.sent` is populated by the time we read it.
    assert(tx.sent.nonEmpty)
    tx.sent.last match
      case WcOutbound.RequestResult(topic, WcSessionResult.Ok(99, value)) =>
        assert(topic == sessionTopic)
        assert(value.str == "0xok")
        assert(capturedMethod.contains("personal_sign"))
        assert(capturedChain.contains(baseId))
      case other => fail(s"expected Ok result, got $other")
  }

  test("request on unknown topic returns -32601 error") {
    val (_, tx) = newConnector()
    tx.simulateInbound(WcInbound.Request(WcSessionRequest(
      id = 42L, topic = "no-such-topic", chainId = baseId,
      method = "personal_sign", params = ujson.Arr(),
    )))
    assert(tx.sent.size == 1)
    tx.sent.head match
      case WcOutbound.RequestResult(topic, WcSessionResult.Error(42, -32601, msg)) =>
        assert(topic == "no-such-topic")
        assert(msg.contains("Unknown session topic"))
      case other => fail(s"expected -32601 error, got $other")
  }

  // ── session delete ────────────────────────────────────────────────────

  test("SessionDelete removes session + unsubscribes the topic") {
    val (c, tx) = newConnector()
    tx.simulateInbound(WcInbound.Proposal(WcSessionProposal(
      id = 1L, pairingTopic = "p", proposer = proposer,
      requiredNamespaces = Map("eip155" -> WcNamespace(Seq(baseId), Seq("personal_sign"), Seq.empty)),
    )))
    val topic = c.activeSessions.head.topic
    tx.simulateInbound(WcInbound.SessionDelete(topic, "user disconnect"))
    assert(c.activeSessions.isEmpty)
    assert(!tx.subscribed.contains(topic))
  }
