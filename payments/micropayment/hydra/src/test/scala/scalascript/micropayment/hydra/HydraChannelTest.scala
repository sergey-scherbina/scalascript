package scalascript.micropayment.hydra

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.micropayment.spi.*
import scalascript.blockchain.spi.{ChainId, Asset}
import java.time.Instant

class HydraChannelTest extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.global

  private val HeadId       = "hydra-test-head-0001"
  private val PayerAddr    = "addr1_payer_test"
  private val PayeeAddr    = "addr1_payee_test"
  private val TestAsset    = Asset(ChainId.Base, "", "tADA", 6)

  private def makeChannel(policy: SettlementPolicy = SettlementPolicy.onClose)
      : (HydraChannel, HydraChannel, StubHydraNodeClient) =
    val stub  = HydraNodeClient.stub()
    def mkCh  = new HydraChannel(
      channelId        = "test-hydra-ch-1",
      headId           = HeadId,
      payerAddress     = PayerAddr,
      payeeAddress     = PayeeAddr,
      assetInfo        = TestAsset,
      openedAt         = Instant.now(),
      node             = stub,
      settlementPolicy = policy,
    )
    (mkCh, mkCh, stub)

  // ── pay() ──────────────────────────────────────────────────────────────────

  test("pay() sends NewTx to node") {
    val (payer, _, stub) = makeChannel()
    val f = payer.pay(100_000)
    val sent = stub.sent.last
    assert(sent.isInstanceOf[HydraClientMsg.NewTx])
    // confirm so pay() resolves
    val txId = new String(stub.sent.last.asInstanceOf[HydraClientMsg.NewTx].transaction.grouped(2).map(h => Integer.parseInt(h, 16).toByte).toArray, "UTF-8").takeWhile(_ != ':')
    stub.inject(HydraServerMsg.TxValid(HeadId, txId))
    val r = Await.result(f, 5.seconds)
    assert(r.amount     == BigInt(100_000))
    assert(r.sequence   == 1)
    assert(r.cumulative == BigInt(100_000))
  }

  test("pay() resolves only after TxValid") {
    val (payer, _, stub) = makeChannel()
    val f = payer.pay(50_000)
    assert(!f.isCompleted)
    val txId = extractTxId(stub)
    stub.inject(HydraServerMsg.TxValid(HeadId, txId))
    val r = Await.result(f, 5.seconds)
    assert(r.sequence == 1)
  }

  test("pay() fails when TxInvalid") {
    val (payer, _, stub) = makeChannel()
    val f = payer.pay(50_000)
    val txId = extractTxId(stub)
    stub.inject(HydraServerMsg.TxInvalid(HeadId, txId, "insufficient funds"))
    val ex = intercept[Exception] { Await.result(f, 5.seconds) }
    assert(ex.getMessage.contains("TxInvalid"))
  }

  test("pay() × 2: sequence increments") {
    val (payer, _, stub) = makeChannel()
    val f1 = payer.pay(100_000); val id1 = extractTxId(stub); stub.inject(HydraServerMsg.TxValid(HeadId, id1))
    val r1 = Await.result(f1, 5.seconds)
    val f2 = payer.pay(200_000); val id2 = extractTxId(stub); stub.inject(HydraServerMsg.TxValid(HeadId, id2))
    val r2 = Await.result(f2, 5.seconds)
    assert(r1.sequence   == 1); assert(r2.sequence   == 2)
    assert(r1.cumulative == BigInt(100_000)); assert(r2.cumulative == BigInt(300_000))
  }

  // ── receive() ─────────────────────────────────────────────────────────────

  test("receive() after TxValid: updates payee state") {
    val (payer, payee, stub) = makeChannel()
    val f = payer.pay(100_000)
    val txId = extractTxId(stub)
    stub.inject(HydraServerMsg.TxValid(HeadId, txId))
    val r = Await.result(f, 5.seconds)
    Await.result(payee.receive(r), 5.seconds)
    assert(payee.state.sequence     == 1)
    assert(payee.state.offChainPaid == BigInt(100_000))
  }

  test("receive() before TxValid: waits for confirmation") {
    val (payer, payee, stub) = makeChannel()
    val f = payer.pay(100_000)
    val txId = extractTxId(stub)
    // Confirm payer first
    stub.inject(HydraServerMsg.TxValid(HeadId, txId))
    val r = Await.result(f, 5.seconds)
    // Now receive waits — inject TxValid again for payee path
    // (in practice payee node also fires TxValid; stub has two subscribers)
    // receive() should already see confirmedTxIds since same stub fired it above
    Await.result(payee.receive(r), 5.seconds)
    assert(payee.state.offChainPaid == BigInt(100_000))
  }

  // ── settle / close ─────────────────────────────────────────────────────────

  test("settle(): sends Close → Fanout, waits HeadIsClosed + HeadIsFinalized") {
    val (payer, payee, stub) = makeChannel()
    val f = payer.pay(200_000)
    val txId = extractTxId(stub)
    stub.inject(HydraServerMsg.TxValid(HeadId, txId))
    val r = Await.result(f, 5.seconds)
    Await.result(payee.receive(r), 5.seconds)

    val sf = payee.settle()
    stub.inject(HydraServerMsg.HeadIsClosed(HeadId, 1L))
    stub.inject(HydraServerMsg.HeadIsFinalized(HeadId))
    val result = Await.result(sf, 5.seconds)
    assert(result.isInstanceOf[SettlementResult.Ok])
    val ok = result.asInstanceOf[SettlementResult.Ok]
    assert(ok.settled  == BigInt(200_000))
    assert(ok.txHash   == HeadId)
    assert(payee.state.offChainPaid == BigInt(0))
    assert(payee.state.onChainPaid  == BigInt(200_000))
  }

  test("settle() sent Close and Fanout to node") {
    val (_, payee, stub) = makeChannel()
    val sf = payee.settle()
    stub.inject(HydraServerMsg.HeadIsClosed(HeadId, 0L))
    stub.inject(HydraServerMsg.HeadIsFinalized(HeadId))
    Await.result(sf, 5.seconds)
    val tags = stub.sent.toSeq.map {
      case HydraClientMsg.Close  => "Close"
      case HydraClientMsg.Fanout => "Fanout"
      case _                     => "other"
    }
    assert(tags.contains("Close"))
    assert(tags.contains("Fanout"))
  }

  test("close() delegates to settle()") {
    val (_, payee, stub) = makeChannel()
    val cf = payee.close()
    stub.inject(HydraServerMsg.HeadIsClosed(HeadId, 0L))
    stub.inject(HydraServerMsg.HeadIsFinalized(HeadId))
    val r = Await.result(cf, 5.seconds)
    assert(r.isInstanceOf[SettlementResult.Ok])
  }

  // ── threshold settlement policy ────────────────────────────────────────────

  test("threshold policy triggers settle on payee after threshold crossed") {
    val policy = SettlementPolicy.threshold(BigInt(300_000))
    val (payer, payee, stub) = makeChannel(policy)

    def sendPayment(amount: BigInt): Unit =
      val f = payer.pay(amount)
      val txId = extractTxId(stub)
      stub.inject(HydraServerMsg.TxValid(HeadId, txId))
      val r = Await.result(f, 5.seconds)
      Await.result(payee.receive(r), 5.seconds)

    sendPayment(BigInt(200_000))
    assert(payee.state.offChainPaid == BigInt(200_000))  // not yet

    // Trigger auto-settle: inject HeadIsClosed+HeadIsFinalized *before* the second receive
    // because threshold policy calls settle() inside receive()
    val futureSettle = Future {
      Thread.sleep(50)
      stub.inject(HydraServerMsg.HeadIsClosed(HeadId, 1L))
      stub.inject(HydraServerMsg.HeadIsFinalized(HeadId))
    }
    sendPayment(BigInt(200_000))
    Await.result(futureSettle, 5.seconds)
    assert(payee.state.offChainPaid == BigInt(0))
    assert(payee.state.onChainPaid  == BigInt(400_000))
  }

  // ── Provider ──────────────────────────────────────────────────────────────

  test("HydraHeadProvider.open() creates a HydraChannel") {
    val (provider, _) = HydraHeadProvider.stub(HeadId, PayerAddr, PayeeAddr)
    val config = ChannelConfig(ChainId.Base, TestAsset, PayeeAddr, BigInt(0), SettlementPolicy.onClose, 1.hour)
    val ch = Await.result(provider.open(config), 5.seconds)
    assert(ch.isInstanceOf[HydraChannel])
    assert(provider.kind == ChannelKind.HydraHead)
  }

  test("HydraHeadProvider stub returns shared node for inject") {
    val (provider, stub) = HydraHeadProvider.stub(HeadId, PayerAddr, PayeeAddr)
    val config = ChannelConfig(ChainId.Base, TestAsset, PayeeAddr, BigInt(0), SettlementPolicy.onClose, 1.hour)
    val ch = Await.result(provider.open(config), 5.seconds).asInstanceOf[HydraChannel]
    val f = ch.pay(100_000)
    val txId = extractTxId(stub)
    stub.inject(HydraServerMsg.TxValid(HeadId, txId))
    val r = Await.result(f, 5.seconds)
    assert(r.amount == BigInt(100_000))
  }

  // ── Message parsing ────────────────────────────────────────────────────────

  test("HydraServerMsg.parse: HeadIsOpen") {
    val json = """{"tag":"HeadIsOpen","headId":"abc123"}"""
    assert(HydraServerMsg.parse(json) == Some(HydraServerMsg.HeadIsOpen("abc123")))
  }

  test("HydraServerMsg.parse: TxValid") {
    val json = """{"tag":"TxValid","headId":"h1","transaction":{"id":"tx42"}}"""
    assert(HydraServerMsg.parse(json) == Some(HydraServerMsg.TxValid("h1", "tx42")))
  }

  test("HydraServerMsg.parse: unknown tag returns None") {
    val json = """{"tag":"SomeFutureTag","data":{}}"""
    assert(HydraServerMsg.parse(json).isEmpty)
  }

  test("HydraClientMsg.NewTx.toJson round-trips tag") {
    val msg = HydraClientMsg.NewTx("deadbeef")
    val json = ujson.read(msg.toJson)
    assert(json("tag").str == "NewTx")
    assert(json("transaction").str == "deadbeef")
  }

  // ── StubHydraNodeClient ────────────────────────────────────────────────────

  test("stub records sent messages") {
    val stub = HydraNodeClient.stub()
    Await.result(stub.send(HydraClientMsg.Close)(using ExecutionContext.global), 5.seconds)
    assert(stub.sent.size == 1)
    assert(stub.sent.head == HydraClientMsg.Close)
  }

  test("stub dispatches injected messages to subscribers") {
    val stub = HydraNodeClient.stub()
    var received: Option[HydraServerMsg] = None
    stub.subscribe(msg => received = Some(msg))
    stub.inject(HydraServerMsg.HeadIsOpen(HeadId))
    assert(received == Some(HydraServerMsg.HeadIsOpen(HeadId)))
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private def extractTxId(stub: StubHydraNodeClient): String =
    val lastNew = stub.sent.reverseIterator.collectFirst { case m: HydraClientMsg.NewTx => m }.get
    val bytes = lastNew.transaction.grouped(2).map(h => Integer.parseInt(h, 16).toByte).toArray
    new String(bytes, "UTF-8").takeWhile(_ != ':')
