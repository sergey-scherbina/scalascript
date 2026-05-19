package scalascript.blockfrost

import scala.concurrent.{ExecutionContext, Future}

// ── Config ────────────────────────────────────────────────────────────────────

case class BlockfrostConfig(
  projectId: String,
  baseUrl:   String = "https://cardano-mainnet.blockfrost.io/api/v0",
)

// ── Domain types ──────────────────────────────────────────────────────────────

case class AddressInfo(
  address:         String,
  lovelaceBalance: BigInt,
  assets:          Map[String, BigInt],   // unit → quantity
)

// ── Trait ─────────────────────────────────────────────────────────────────────

trait BlockfrostClient:
  def getAddressInfo(address: String): Future[AddressInfo]
  def isTxConfirmed(txHash: String): Future[Boolean]

// ── HTTP implementation ───────────────────────────────────────────────────────

private class BlockfrostClientImpl(config: BlockfrostConfig)(using ec: ExecutionContext)
    extends BlockfrostClient:

  import sttp.client4.*

  private val backend = DefaultSyncBackend()
  private def authHeaders = Map("project_id" -> config.projectId)

  def getAddressInfo(address: String): Future[AddressInfo] =
    Future {
      val url  = s"${config.baseUrl}/addresses/$address"
      val resp = basicRequest.headers(authHeaders).get(uri"$url")
        .response(asStringAlways).send(backend)
      if resp.code.code != 200 then
        throw RuntimeException(s"Blockfrost ${resp.code}: ${resp.body}")
      val j        = ujson.read(resp.body)
      val amounts  = j("amount").arr
      val lovelace = amounts.find(_("unit").str == "lovelace")
        .map(a => BigInt(a("quantity").str)).getOrElse(BigInt(0))
      val assets   = amounts.filterNot(_("unit").str == "lovelace")
        .map(a => a("unit").str -> BigInt(a("quantity").str)).toMap
      AddressInfo(address, lovelace, assets)
    }

  def isTxConfirmed(txHash: String): Future[Boolean] =
    Future {
      val url  = s"${config.baseUrl}/txs/$txHash"
      val resp = basicRequest.headers(authHeaders).get(uri"$url")
        .response(asStringAlways).send(backend)
      resp.code.code == 200
    }

// ── Factory ───────────────────────────────────────────────────────────────────

object Blockfrost:
  def connect(config: BlockfrostConfig)(using ExecutionContext): BlockfrostClient =
    new BlockfrostClientImpl(config)
