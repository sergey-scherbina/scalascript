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

case class BlockfrostProtocolParams(
  minFeeA:             BigInt,
  minFeeB:             BigInt,
  maxTxSize:           BigInt,
  priceMem:            BigDecimal,
  priceStep:           BigDecimal,
  coinsPerUtxoSize:    BigInt,
  collateralPercent:   Int,
  maxCollateralInputs: Int,
  costModels:          Map[String, Seq[Long]],
)

object BlockfrostProtocolParams:
  def fromJson(json: ujson.Value): BlockfrostProtocolParams =
    def big(name: String): BigInt =
      json.obj.get(name).map {
        case ujson.Num(n) => BigDecimal(n).toBigInt
        case ujson.Str(s) => BigInt(s)
        case other        => throw IllegalArgumentException(s"Blockfrost protocol parameter '$name' is not numeric: $other")
      }.getOrElse(BigInt(0))
    def dec(name: String): BigDecimal =
      json.obj.get(name).map {
        case ujson.Num(n) => BigDecimal.decimal(n)
        case ujson.Str(s) => BigDecimal(s)
        case other        => throw IllegalArgumentException(s"Blockfrost protocol parameter '$name' is not decimal: $other")
      }.getOrElse(BigDecimal(0))
    def int(name: String): Int = big(name).toInt
    val models = json.obj.get("cost_models").map { raw =>
      raw.obj.toMap.view.mapValues {
        case ujson.Arr(values) =>
          values.toSeq.map {
            case ujson.Num(n) => BigDecimal(n).toLong
            case ujson.Str(s) => s.toLong
            case other        => throw IllegalArgumentException(s"Blockfrost cost model value is not integer: $other")
          }
        case other => throw IllegalArgumentException(s"Blockfrost cost model is not an array: $other")
      }.toMap
    }.getOrElse(Map.empty)
    BlockfrostProtocolParams(
      minFeeA             = big("min_fee_a"),
      minFeeB             = big("min_fee_b"),
      maxTxSize           = big("max_tx_size"),
      priceMem            = dec("price_mem"),
      priceStep           = dec("price_step"),
      coinsPerUtxoSize    = big("coins_per_utxo_size"),
      collateralPercent   = int("collateral_percent"),
      maxCollateralInputs = int("max_collateral_inputs"),
      costModels          = models,
    )

// ── Trait ─────────────────────────────────────────────────────────────────────

trait BlockfrostClient:
  def getAddressInfo(address: String): Future[AddressInfo]
  def isTxConfirmed(txHash: String): Future[Boolean]
  def getUtxos(address: String): Future[Seq[BlockfrostUtxo]]
  def submitTx(cbor: Array[Byte]): Future[String]
  def getProtocolParams(): Future[BlockfrostProtocolParams] =
    Future.failed(NotImplementedError("getProtocolParams not implemented"))

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

  override def getProtocolParams(): Future[BlockfrostProtocolParams] =
    Future {
      val url  = s"${config.baseUrl}/epochs/latest/parameters"
      val resp = basicRequest.headers(authHeaders).get(uri"$url")
        .response(asStringAlways).send(backend)
      if resp.code.code != 200 then
        throw RuntimeException(s"Blockfrost protocol params ${resp.code}: ${resp.body}")
      BlockfrostProtocolParams.fromJson(ujson.read(resp.body))
    }

// ── Factory ───────────────────────────────────────────────────────────────────

object Blockfrost:
  def connect(config: BlockfrostConfig)(using ExecutionContext): BlockfrostClient =
    new BlockfrostClientImpl(config)
