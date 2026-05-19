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

case class BlockfrostUtxo(
  txHash:   String,
  index:    Int,
  lovelace: BigInt,
  assets:   Map[String, BigInt],          // unit → quantity
)

// ── Trait ─────────────────────────────────────────────────────────────────────

trait BlockfrostClient:
  def getAddressInfo(address: String): Future[AddressInfo]
  def isTxConfirmed(txHash: String): Future[Boolean]
  def getUtxos(address: String): Future[Seq[BlockfrostUtxo]]
  def submitTx(cbor: Array[Byte]): Future[String]

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

  def getUtxos(address: String): Future[Seq[BlockfrostUtxo]] =
    Future {
      val url  = s"${config.baseUrl}/addresses/$address/utxos"
      val resp = basicRequest.headers(authHeaders).get(uri"$url")
        .response(asStringAlways).send(backend)
      if resp.code.code != 200 then
        throw RuntimeException(s"Blockfrost UTxO ${resp.code}: ${resp.body}")
      ujson.read(resp.body).arr.toSeq.map { u =>
        val amounts  = u("amount").arr
        val lovelace = amounts.find(_("unit").str == "lovelace")
          .map(a => BigInt(a("quantity").str)).getOrElse(BigInt(0))
        val assets   = amounts.filterNot(_("unit").str == "lovelace")
          .map(a => a("unit").str -> BigInt(a("quantity").str)).toMap
        BlockfrostUtxo(u("tx_hash").str, u("tx_index").num.toInt, lovelace, assets)
      }
    }

  def submitTx(cbor: Array[Byte]): Future[String] =
    Future {
      val url  = s"${config.baseUrl}/tx/submit"
      val resp = basicRequest.headers(authHeaders ++ Map("Content-Type" -> "application/cbor"))
        .body(cbor).post(uri"$url")
        .response(asStringAlways).send(backend)
      if resp.code.code != 200 then
        throw RuntimeException(s"Blockfrost submit ${resp.code}: ${resp.body}")
      ujson.read(resp.body).str
    }

// ── Factory ───────────────────────────────────────────────────────────────────

object Blockfrost:
  def connect(config: BlockfrostConfig)(using ExecutionContext): BlockfrostClient =
    new BlockfrostClientImpl(config)
