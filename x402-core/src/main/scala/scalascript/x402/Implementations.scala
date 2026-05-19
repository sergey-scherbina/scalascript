package scalascript.x402

import scala.concurrent.{ExecutionContext, Future}
import scala.collection.concurrent.TrieMap
import scala.collection.mutable

// ── In-memory NonceStore ──────────────────────────────────────────────────────

private class InMemoryNonceStore extends NonceStore:
  private val used = TrieMap.empty[Bytes32, BigInt]   // nonce -> validBefore

  def claim(nonce: Bytes32, validBefore: BigInt): Future[Boolean] =
    Future.successful(used.putIfAbsent(nonce, validBefore).isEmpty)

  def cleanup(): Future[Unit] =
    val now = BigInt(System.currentTimeMillis() / 1000)
    used.filterInPlace((_, vb) => vb > now)
    Future.unit

// ── In-memory SettlementQueue ─────────────────────────────────────────────────

private class InMemorySettlementQueue(using ec: ExecutionContext) extends SettlementQueue:
  private val queue = mutable.Queue.empty[(PaymentPayload, PaymentRequirements)]

  def enqueue(payload: PaymentPayload, req: PaymentRequirements): Future[Unit] =
    Future.successful(queue.synchronized(queue.enqueue((payload, req))))

  def process(facilitator: Facilitator): Future[Unit] =
    val items = queue.synchronized {
      val snap = queue.toList
      queue.clear()
      snap
    }
    Future.traverse(items) { (payload, req) => facilitator.settle(payload, req) }.map(_ => ())

// ── Testnet facilitator ───────────────────────────────────────────────────────

private class TestnetFacilitator extends Facilitator:
  def verify(payload: PaymentPayload, req: PaymentRequirements): Future[VerifyResult] =
    Future.successful(VerifyResult.Ok)
  def settle(payload: PaymentPayload, req: PaymentRequirements): Future[SettleResult] =
    Future.successful(SettleResult.Ok("0x" + "0" * 64))

// ── WithFallback facilitator ──────────────────────────────────────────────────

private class WithFallbackFacilitator(primary: Facilitator, fallback: Facilitator)(using ec: ExecutionContext)
    extends Facilitator:

  def verify(payload: PaymentPayload, req: PaymentRequirements): Future[VerifyResult] =
    primary.verify(payload, req).flatMap {
      case _: VerifyResult.Fail => fallback.verify(payload, req)
      case ok                   => Future.successful(ok)
    }

  def settle(payload: PaymentPayload, req: PaymentRequirements): Future[SettleResult] =
    primary.settle(payload, req).flatMap {
      case _: SettleResult.Fail => fallback.settle(payload, req)
      case ok                   => Future.successful(ok)
    }

object Facilitators:
  def testnet(): Facilitator = new TestnetFacilitator
  def withFallback(primary: Facilitator, fallback: Facilitator)(using ExecutionContext): Facilitator =
    new WithFallbackFacilitator(primary, fallback)
