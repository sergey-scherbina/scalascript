package scalascript.x402.server

import scalascript.x402.*
import scalascript.server.{Request, Response}
import scala.concurrent.{ExecutionContext, Future}
import java.util.Base64
import ujson.*

// ── PaymentConfig ─────────────────────────────────────────────────────────────

case class PaymentConfig(
  requirements:   PaymentRequirements,
  facilitator:    Facilitator,
  nonceStore:     NonceStore     = NonceStore.inMemory(),
  settlementMode: SettlementMode = SettlementMode.Synchronous,
  onSettled:      (PaymentPayload, Request) => Future[Unit] = (_, _) => Future.unit,
)

// ── JSON serialization helpers ────────────────────────────────────────────────

private object Json:
  def requirementsBody(req: PaymentRequirements): String =
    val scheme = req.scheme match
      case PaymentScheme.Exact(amount) =>
        ujson.Obj("type" -> "exact", "amount" -> amount.toString)
      case PaymentScheme.Stream(rate, unit, maxU, maxA) =>
        ujson.Obj("type" -> "stream", "ratePerUnit" -> rate.toString,
          "unitName" -> unit, "maxUnits" -> maxU, "maxAmount" -> maxA.toString)
      case PaymentScheme.CardanoExact(lovelace, asset) =>
        val base = ujson.Obj("type" -> "cardanoExact", "lovelace" -> lovelace.toString)
        asset.foreach(a => base("asset") = ujson.Obj(
          "policyId" -> a.policyId, "assetName" -> a.assetName, "symbol" -> a.symbol))
        base
    ujson.Obj(
      "x402Version"       -> 1,
      "scheme"            -> scheme,
      "network"           -> req.network.toString,
      "chainId"           -> req.network.chainId,
      "asset"             -> ujson.Obj(
        "address"  -> req.asset.address,
        "symbol"   -> req.asset.symbol,
        "decimals" -> req.asset.decimals,
      ),
      "payTo"             -> req.payTo,
      "resource"          -> req.resource,
      "description"       -> req.description,
      "maxTimeoutSeconds" -> req.maxTimeoutSeconds,
    ).toString

  def parse(raw: String): PaymentPayload =
    val j      = ujson.read(raw)
    val auth   = j("authorization")
    val scheme = j("scheme")("type").str match
      case "exact"        => PaymentScheme.Exact(BigInt(j("scheme")("amount").str))
      case "stream"       => PaymentScheme.Stream(
        BigInt(j("scheme")("ratePerUnit").str),
        j("scheme")("unitName").str,
        j("scheme")("maxUnits").num.toInt,
        BigInt(j("scheme")("maxAmount").str),
      )
      case "cardanoExact" => PaymentScheme.CardanoExact(
        BigInt(j("scheme")("lovelace").str),
        if j("scheme").obj.contains("asset") then
          val a = j("scheme")("asset")
          Some(CardanoAsset(a("policyId").str, a("assetName").str, a("symbol").str))
        else None,
      )
      case other => throw RuntimeException(s"Unknown scheme type: $other")
    val network = j("network").str match
      case "BaseSepolia"     => Network.BaseSepolia
      case "Base"            => Network.Base
      case "EthereumMainnet" => Network.EthereumMainnet
      case "Polygon"         => Network.Polygon
      case "Arbitrum"        => Network.Arbitrum
      case "Optimism"        => Network.Optimism
      case other             => throw RuntimeException(s"Unknown network: $other")
    PaymentPayload(
      x402Version   = j.obj.get("x402Version").map(_.num.toInt).getOrElse(1),
      scheme        = scheme,
      network       = network,
      authorization = TransferAuthorization(
        from        = auth("from").str,
        to          = auth("to").str,
        value       = BigInt(auth("value").str),
        validAfter  = BigInt(auth("validAfter").str),
        validBefore = BigInt(auth("validBefore").str),
        nonce       = auth("nonce").str,
      ),
      signature    = j.obj.get("signature").map(_.str).getOrElse(""),
      cardanoProof = j.obj.get("cardanoProof").map { p =>
        CardanoPaymentProof(
          address   = p("address").str,
          signature = p("signature").str,
          key       = p("key").str,
        )
      },
    )

// ── 402 response ──────────────────────────────────────────────────────────────

private def paymentRequired(req: PaymentRequirements, reason: String = ""): Response =
  val body = ujson.Obj(
    "error"        -> "Payment Required",
    "requirements" -> ujson.read(Json.requirementsBody(req)),
  )
  if reason.nonEmpty then body("reason") = reason
  Response(
    status  = 402,
    headers = Map("Content-Type" -> "application/json; charset=utf-8"),
    body    = body.toString,
  )

// ── Stream scheme validation ──────────────────────────────────────────────────

private def validateStream(
  req:     PaymentRequirements,
  httpReq: Request,
  payload: PaymentPayload,
): Option[Response] =
  req.scheme match
    case PaymentScheme.Stream(ratePerUnit, _, _, maxAmount) =>
      val units    = httpReq.headers.get("x-units").flatMap(_.toIntOption).getOrElse(1)
      val expected = ratePerUnit * units
      if payload.authorization.value != expected then
        Some(paymentRequired(req,
          s"Stream: authorization ${payload.authorization.value} != expected $expected for $units unit(s)"))
      else if expected > maxAmount then
        Some(paymentRequired(req,
          s"Stream: charge $expected exceeds maxAmount $maxAmount"))
      else None
    case _ => None

// ── withPayment middleware ────────────────────────────────────────────────────

def withPayment(config: PaymentConfig)(
  handler: Request => Future[Response]
)(using ec: ExecutionContext): Request => Future[Response] =

  req =>
    val headerOpt = req.headers.get("x-payment")
      .orElse(req.headers.get("X-Payment"))

    headerOpt match
      case None =>
        Future.successful(paymentRequired(config.requirements))

      case Some(encoded) =>
        val result: Future[Response] =
          Future(Json.parse(String(Base64.getDecoder.decode(encoded), "UTF-8")))
            .flatMap { payload =>
              validateStream(config.requirements, req, payload) match
                case Some(errResp) => Future.successful(errResp)
                case None =>
                  config.nonceStore.claim(payload.authorization.nonce, payload.authorization.validBefore)
                    .flatMap {
                      case false =>
                        Future.successful(paymentRequired(config.requirements, "Nonce already used"))
                      case true =>
                        config.facilitator.verify(payload, config.requirements).flatMap {
                          case VerifyResult.Fail(reason) =>
                            Future.successful(paymentRequired(config.requirements, reason))
                          case VerifyResult.Ok =>
                            config.settlementMode match
                              case SettlementMode.Synchronous =>
                                config.facilitator.settle(payload, config.requirements).flatMap {
                                  case SettleResult.Fail(reason) =>
                                    Future.successful(paymentRequired(config.requirements, reason))
                                  case SettleResult.Ok(_) =>
                                    config.onSettled(payload, req).flatMap(_ => handler(req))
                                }
                              case SettlementMode.Async(queue) =>
                                queue.enqueue(payload, config.requirements)
                                  .flatMap(_ => config.onSettled(payload, req))
                                  .flatMap(_ => handler(req))
                        }
                    }
            }
        result.recover { case ex =>
          paymentRequired(config.requirements, s"Payment parse error: ${ex.getMessage}")
        }

// ── withStreamPayment convenience wrapper ─────────────────────────────────────

def withStreamPayment(config: PaymentConfig, defaultUnits: Int = 1)(
  handler: (Request, Int) => Future[Response]
)(using ExecutionContext): Request => Future[Response] =
  req =>
    val units = req.headers.get("x-units").flatMap(_.toIntOption).getOrElse(defaultUnits)
    // Inject x-units so validateStream sees the resolved count
    val reqWithUnits = req.copy(headers = req.headers + ("x-units" -> units.toString))
    withPayment(config)(r => handler(r, units))(reqWithUnits)
