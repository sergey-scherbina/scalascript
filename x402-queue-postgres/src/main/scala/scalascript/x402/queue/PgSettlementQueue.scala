package scalascript.x402.queue

import scalascript.x402.*
import scalascript.db.PgClient
import scala.concurrent.{ExecutionContext, Future}
import ujson.*

// ── Postgres-backed SettlementQueue ──────────────────────────────────────────
// Table: x402_settlement_queue (id, payload_json, requirements_json, created_at, processed)
// enqueue: INSERT with processed = false
// process: SELECT WHERE NOT processed, settle each, UPDATE processed = true

private class PgSettlementQueueImpl(db: PgClient)(using ec: ExecutionContext) extends SettlementQueue:

  def enqueue(payload: PaymentPayload, req: PaymentRequirements): Future[Unit] =
    val payloadJson = encodePayload(payload)
    val reqJson     = encodeReq(req)
    db.execute(
      "INSERT INTO x402_settlement_queue (payload_json, requirements_json, processed) VALUES (?, ?, false)",
      payloadJson, reqJson,
    ).map(_ => ())

  def process(facilitator: Facilitator): Future[Unit] =
    import scalascript.db.RowDecoder
    given RowDecoder[(Long, String, String)] =
      rs => (rs.getLong(1), rs.getString(2), rs.getString(3))
    db.query[(Long, String, String)](
      "SELECT id, payload_json, requirements_json FROM x402_settlement_queue WHERE processed = false ORDER BY id"
    ).flatMap { rows =>
      Future.traverse(rows) { (id, payloadJson, reqJson) =>
        val payload = decodePayload(payloadJson)
        val req     = decodeReq(reqJson)
        facilitator.settle(payload, req).flatMap { _ =>
          db.execute("UPDATE x402_settlement_queue SET processed = true WHERE id = ?", id)
        }
      }.map(_ => ())
    }

  // ── JSON helpers ─────────────────────────────────────────────────────────────

  private def encodePayload(p: PaymentPayload): String =
    val auth = p.authorization
    ujson.Obj(
      "x402Version"   -> p.x402Version,
      "scheme"        -> schemeJson(p.scheme),
      "network"       -> p.network.toString,
      "authorization" -> ujson.Obj(
        "from"        -> auth.from,
        "to"          -> auth.to,
        "value"       -> auth.value.toString,
        "validAfter"  -> auth.validAfter.toString,
        "validBefore" -> auth.validBefore.toString,
        "nonce"       -> auth.nonce,
      ),
      "signature"     -> p.signature,
    ).toString

  private def decodePayload(json: String): PaymentPayload =
    val j    = ujson.read(json)
    val auth = j("authorization")
    PaymentPayload(
      x402Version   = j("x402Version").num.toInt,
      scheme        = decodeScheme(j("scheme")),
      network       = decodeNetwork(j("network").str),
      authorization = TransferAuthorization(
        from        = auth("from").str,
        to          = auth("to").str,
        value       = BigInt(auth("value").str),
        validAfter  = BigInt(auth("validAfter").str),
        validBefore = BigInt(auth("validBefore").str),
        nonce       = auth("nonce").str,
      ),
      signature     = j("signature").str,
    )

  private def encodeReq(req: PaymentRequirements): String =
    ujson.Obj(
      "scheme"            -> schemeJson(req.scheme),
      "network"           -> req.network.toString,
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

  private def decodeReq(json: String): PaymentRequirements =
    val j       = ujson.read(json)
    val network = decodeNetwork(j("network").str)
    PaymentRequirements(
      scheme      = decodeScheme(j("scheme")),
      network     = network,
      asset       = Asset(
        address  = j("asset")("address").str,
        symbol   = j("asset")("symbol").str,
        decimals = j("asset")("decimals").num.toInt,
        network  = network,
      ),
      payTo       = j("payTo").str,
      resource    = j("resource").str,
      description = j("description").str,
      maxTimeoutSeconds = j("maxTimeoutSeconds").num.toInt,
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

  private def decodeScheme(j: ujson.Value): PaymentScheme = j("type").str match
    case "exact"        => PaymentScheme.Exact(BigInt(j("amount").str))
    case "stream"       => PaymentScheme.Stream(
      BigInt(j("ratePerUnit").str), j("unitName").str,
      j("maxUnits").num.toInt, BigInt(j("maxAmount").str))
    case "cardanoExact" => PaymentScheme.CardanoExact(BigInt(j("lovelace").str), None)
    case other          => throw RuntimeException(s"Unknown scheme: $other")

  private def decodeNetwork(s: String): Network = s match
    case "BaseSepolia"     => Network.BaseSepolia
    case "Base"            => Network.Base
    case "EthereumMainnet" => Network.EthereumMainnet
    case "Polygon"         => Network.Polygon
    case "Arbitrum"        => Network.Arbitrum
    case "Optimism"        => Network.Optimism
    case other             => throw RuntimeException(s"Unknown network: $other")

object PgSettlementQueue:
  def apply(db: PgClient)(using ExecutionContext): SettlementQueue =
    new PgSettlementQueueImpl(db)

  val createTable: String =
    """CREATE TABLE IF NOT EXISTS x402_settlement_queue (
      |  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
      |  payload_json    TEXT    NOT NULL,
      |  requirements_json TEXT  NOT NULL,
      |  processed       BOOLEAN NOT NULL DEFAULT false,
      |  created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
      |)""".stripMargin
