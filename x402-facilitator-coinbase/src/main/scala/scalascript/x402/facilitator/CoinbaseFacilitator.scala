package scalascript.x402.facilitator

import scalascript.x402.*
import scalascript.coinbase.CoinbaseClient
import scala.concurrent.{ExecutionContext, Future}
import ujson.*

// ── Coinbase facilitator ───────────────────────────────────────────────────────
// Delegates verify/settle to the Coinbase x402 REST API via CoinbaseClient.

class CoinbaseFacilitatorImpl(client: CoinbaseClient)(using ec: ExecutionContext)
    extends Facilitator:

  def verify(payload: PaymentPayload, req: PaymentRequirements): Future[VerifyResult] =
    client.x402.verify(encodePayload(payload, req)).map { j =>
      if j.obj.get("isValid").exists(_.bool) then VerifyResult.Ok
      else VerifyResult.Fail(j.obj.get("error").map(_.str).getOrElse("Verification failed"))
    }.recover { case ex =>
      VerifyResult.Fail(s"Coinbase verify error: ${ex.getMessage}")
    }

  def settle(payload: PaymentPayload, req: PaymentRequirements): Future[SettleResult] =
    client.x402.settle(encodePayload(payload, req)).map { j =>
      val hash = j.obj.get("txHash").orElse(j.obj.get("transaction_hash")).map(_.str)
      hash match
        case Some(h) => SettleResult.Ok(h)
        case None    =>
          if j.obj.get("success").exists(_.bool) then SettleResult.Ok("0x" + "0" * 64)
          else SettleResult.Fail(j.obj.get("error").map(_.str).getOrElse("Settlement failed"))
    }.recover { case ex =>
      SettleResult.Fail(s"Coinbase settle error: ${ex.getMessage}")
    }

  private def encodePayload(payload: PaymentPayload, req: PaymentRequirements): ujson.Value =
    val auth = payload.authorization
    ujson.Obj(
      "x402Version"   -> payload.x402Version,
      "scheme"        -> schemeJson(payload.scheme),
      "network"       -> payload.network.toString,
      "payTo"         -> req.payTo,
      "asset"         -> ujson.Obj(
        "address"  -> req.asset.address,
        "symbol"   -> req.asset.symbol,
        "decimals" -> req.asset.decimals,
      ),
      "authorization" -> ujson.Obj(
        "from"        -> auth.from,
        "to"          -> auth.to,
        "value"       -> auth.value.toString,
        "validAfter"  -> auth.validAfter.toString,
        "validBefore" -> auth.validBefore.toString,
        "nonce"       -> auth.nonce,
      ),
      "signature"     -> payload.signature,
    )

  private def schemeJson(scheme: PaymentScheme): ujson.Value = scheme match
    case PaymentScheme.Exact(amount) =>
      ujson.Obj("type" -> "exact", "amount" -> amount.toString)
    case PaymentScheme.Stream(rate, unit, maxU, maxA) =>
      ujson.Obj("type" -> "stream", "ratePerUnit" -> rate.toString,
        "unitName" -> unit, "maxUnits" -> maxU, "maxAmount" -> maxA.toString)
    case PaymentScheme.CardanoExact(lovelace, asset) =>
      val o = ujson.Obj("type" -> "cardanoExact", "lovelace" -> lovelace.toString)
      asset.foreach(a => o("asset") = ujson.Obj(
        "policyId" -> a.policyId, "assetName" -> a.assetName, "symbol" -> a.symbol))
      o

object CoinbaseFacilitator:
  def apply(client: CoinbaseClient)(using ExecutionContext): Facilitator =
    new CoinbaseFacilitatorImpl(client)
