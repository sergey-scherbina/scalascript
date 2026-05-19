package scalascript.x402.queue

import scalascript.x402.*
import scalascript.db.{PgClient, RowDecoder}
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import ExecutionContext.Implicits.global

class PgSettlementQueueTest extends AnyFunSuite:

  private val testReq = PaymentRequirements(
    scheme      = PaymentScheme.Exact(1_000_000L),
    network     = Network.Base,
    asset       = Assets.USDC_BASE,
    payTo       = "0xpayTo",
    resource    = "/api/premium",
    description = "Test",
  )

  private val testPayload = PaymentPayload(
    scheme        = PaymentScheme.Exact(1_000_000L),
    network       = Network.Base,
    authorization = TransferAuthorization(
      from        = "0xfrom",
      to          = "0xpayTo",
      value       = BigInt(1_000_000),
      validAfter  = BigInt(0),
      validBefore = BigInt(9_999_999_999L),
      nonce       = "0x" + "ab" * 32,
    ),
    signature     = "0x" + "cc" * 65,
  )

  // ── Minimal mock PgClient ────────────────────────────────────────────────────

  private class MockPg(queryRows: List[(Long, String, String)] = Nil) extends PgClient:
    val sqls   = scala.collection.mutable.Buffer.empty[String]
    val params = scala.collection.mutable.Buffer.empty[Seq[Any]]
    var updatedIds: List[Long] = Nil

    def query[A](sql: String, ps: Any*)(using d: RowDecoder[A]): Future[List[A]] =
      sqls += sql
      // Return pre-built tuples cast directly — avoids ResultSet entirely
      Future.successful(queryRows.asInstanceOf[List[A]])

    def queryOne[A](sql: String, ps: Any*)(using d: RowDecoder[A]): Future[Option[A]] =
      Future.successful(None)

    def execute(sql: String, ps: Any*): Future[Int] =
      sqls += sql
      params += ps.toSeq
      if sql.contains("UPDATE") then
        updatedIds = updatedIds :+ ps.head.asInstanceOf[Long]
      Future.successful(1)

    def transaction[A](f: PgClient => Future[A]): Future[A] = f(this)
    def close(): Unit = ()

  // ── enqueue tests ─────────────────────────────────────────────────────────────

  test("enqueue executes INSERT SQL") {
    val pg    = MockPg()
    val queue = PgSettlementQueue(pg)
    Await.result(queue.enqueue(testPayload, testReq), 5.seconds)
    assert(pg.sqls.exists(_.contains("INSERT")))
    assert(pg.sqls.exists(_.contains("x402_settlement_queue")))
  }

  test("enqueue stores valid JSON for payload") {
    val pg    = MockPg()
    val queue = PgSettlementQueue(pg)
    Await.result(queue.enqueue(testPayload, testReq), 5.seconds)
    val insertParams = pg.params.find(_ => true).get
    val payloadJson  = insertParams(0).toString
    val j = ujson.read(payloadJson)
    assert(j("network").str  == "Base")
    assert(j("signature").str == testPayload.signature)
    assert(j("scheme")("type").str == "exact")
    assert(j("authorization")("nonce").str == testPayload.authorization.nonce)
  }

  test("enqueue stores valid JSON for requirements") {
    val pg    = MockPg()
    val queue = PgSettlementQueue(pg)
    Await.result(queue.enqueue(testPayload, testReq), 5.seconds)
    val insertParams = pg.params.find(_ => true).get
    val reqJson = insertParams(1).toString
    val j = ujson.read(reqJson)
    assert(j("payTo").str == "0xpayTo")
    assert(j("asset")("symbol").str == "USDC")
  }

  // ── process tests ─────────────────────────────────────────────────────────────

  test("process calls settle for each queued row") {
    val payloadJson = ujson.Obj(
      "x402Version"   -> 1,
      "scheme"        -> ujson.Obj("type" -> "exact", "amount" -> "1000000"),
      "network"       -> "Base",
      "authorization" -> ujson.Obj(
        "from" -> "0xf", "to" -> "0xt",
        "value" -> "1", "validAfter" -> "0", "validBefore" -> "9999999999",
        "nonce" -> "0x00",
      ),
      "signature" -> "0xsig",
    ).toString
    val reqJson = ujson.Obj(
      "scheme"            -> ujson.Obj("type" -> "exact", "amount" -> "1000000"),
      "network"           -> "Base",
      "asset"             -> ujson.Obj("address" -> Assets.USDC_BASE.address, "symbol" -> "USDC", "decimals" -> 6),
      "payTo"             -> "0xpayTo",
      "resource"          -> "/api",
      "description"       -> "test",
      "maxTimeoutSeconds" -> 300,
    ).toString
    val pg    = MockPg(List((42L, payloadJson, reqJson)))
    var settled = 0
    val fac = new Facilitator:
      def verify(p: PaymentPayload, r: PaymentRequirements) = Future.successful(VerifyResult.Ok)
      def settle(p: PaymentPayload, r: PaymentRequirements) =
        settled += 1
        Future.successful(SettleResult.Ok("0xtx"))
    val queue = PgSettlementQueue(pg)
    Await.result(queue.process(fac), 5.seconds)
    assert(settled == 1)
    assert(pg.updatedIds.contains(42L))
  }

  test("process with empty queue is a no-op") {
    val pg    = MockPg(Nil)
    var called = false
    val fac = new Facilitator:
      def verify(p: PaymentPayload, r: PaymentRequirements) = ???
      def settle(p: PaymentPayload, r: PaymentRequirements) = { called = true; ??? }
    val queue = PgSettlementQueue(pg)
    Await.result(queue.process(fac), 5.seconds)
    assert(!called)
  }

  // ── createTable ───────────────────────────────────────────────────────────────

  test("createTable SQL contains expected columns") {
    assert(PgSettlementQueue.createTable.contains("x402_settlement_queue"))
    assert(PgSettlementQueue.createTable.contains("payload_json"))
    assert(PgSettlementQueue.createTable.contains("requirements_json"))
    assert(PgSettlementQueue.createTable.contains("processed"))
  }
