package scalascript.x402.client

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

case class Eip712Domain(
  name:              String,
  version:           String,
  chainId:           Int,
  verifyingContract: String,
)

enum Network:
  case BaseSepolia, Base, EthereumMainnet, Polygon, Arbitrum, Optimism

  def chainId: Int = this match
    case BaseSepolia     => 84532
    case Base            => 8453
    case EthereumMainnet => 1
    case Polygon         => 137
    case Arbitrum        => 42161
    case Optimism        => 10

enum CardanoSigningMode:
  case Description
  case ScalusClaim

case class CardanoPaymentProof(
  coseSign1: String,
  publicKey: String,
  address:   String,
)

trait Wallet:
  def address: String
  def network: Network
  def cardanoSigningMode: CardanoSigningMode = CardanoSigningMode.Description

  def signEip712(
    domain: Eip712Domain,
    types:  Map[String, Seq[(String, String)]],
    value:  Map[String, Any],
  ): Future[String]

  def signCip8(message: Array[Byte]): Future[CardanoPaymentProof]

object Wallets:
  def metaMask()(using ExecutionContext): Future[Wallet] =
    metaMask(Network.Base)

  def metaMask(network: Network)(using ExecutionContext): Future[Wallet] =
    BrowserEip1193Wallet.connect(network)

  def metaMask(address: String, network: Network)(using ExecutionContext): Wallet =
    BrowserEip1193Wallet.connected(address, network)

private final class BrowserEip1193Wallet private (
  provider: BrowserEip1193Wallet.EthereumProvider,
  val address: String,
  val network: Network,
)(using ec: ExecutionContext) extends Wallet:

  def signEip712(
    domain: Eip712Domain,
    types:  Map[String, Seq[(String, String)]],
    value:  Map[String, Any],
  ): Future[String] =
    BrowserEip1193Wallet.ensureChain(provider, network).flatMap { _ =>
      val typedData = BrowserEip1193Wallet.eip712Json(domain, types, value)
      BrowserEip1193Wallet
        .request(provider, "eth_signTypedData_v4", js.Array(address, ujson.write(typedData)))
        .map(_.asInstanceOf[String])
    }

  def signCip8(message: Array[Byte]): Future[CardanoPaymentProof] =
    Future.failed(UnsupportedOperationException(
      s"MetaMask wallet ($network) does not support Cardano CIP-8 signing (message length=${message.length})"
    ))

private object BrowserEip1193Wallet:
  @js.native @JSGlobal("globalThis")
  private object GlobalThis extends js.Object:
    var window: js.Any = js.native

  trait EthereumProvider extends js.Object:
    def request(args: js.Object): js.Promise[js.Any]

  private val EthDomainFields: Seq[(String, String)] = Seq(
    "string"  -> "name",
    "string"  -> "version",
    "uint256" -> "chainId",
    "address" -> "verifyingContract",
  )

  def connect(network: Network)(using ec: ExecutionContext): Future[Wallet] =
    try
      val provider = currentProvider()
      for
        accounts <- request(provider, "eth_requestAccounts", js.Array()).map(asStringArray)
        address  <- accounts.headOption match
          case Some(a) if a.nonEmpty => Future.successful(a)
          case _ => Future.failed(IllegalStateException("MetaMask returned no accounts"))
        _ <- ensureChain(provider, network)
      yield BrowserEip1193Wallet(provider, address, network)
    catch
      case e: Throwable => Future.failed(e)

  def connected(address: String, network: Network)(using ec: ExecutionContext): Wallet =
    require(address.nonEmpty, "MetaMask wallet address must be non-empty")
    BrowserEip1193Wallet(currentProvider(), address, network)

  def ensureChain(provider: EthereumProvider, network: Network)(using ec: ExecutionContext): Future[Unit] =
    request(provider, "eth_chainId", js.Array()).map(_.asInstanceOf[String]).flatMap { actual =>
      val expected = chainIdHex(network)
      if actual.equalsIgnoreCase(expected) then Future.successful(())
      else Future.failed(IllegalStateException(
        s"MetaMask is connected to chain $actual, expected $expected for $network"
      ))
    }

  def request(provider: EthereumProvider, method: String, params: js.Array[js.Any]): Future[js.Any] =
    provider
      .request(js.Dynamic.literal(method = method, params = params).asInstanceOf[js.Object])
      .toFuture

  def eip712Json(
    domain: Eip712Domain,
    userTypes: Map[String, Seq[(String, String)]],
    userValue: Map[String, Any],
  ): ujson.Obj =
    val primaryType = (userTypes.keys.toSet - "EIP712Domain").headOption.getOrElse(
      throw IllegalArgumentException("signEip712: types map must include at least one non-EIP712Domain entry")
    )
    val allTypes = userTypes + ("EIP712Domain" -> EthDomainFields)
    val typesJson = ujson.Obj()
    allTypes.foreach { case (name, fields) =>
      typesJson(name) = ujson.Arr(fields.map { case (tpe, fieldName) =>
        ujson.Obj("name" -> fieldName, "type" -> tpe)
      }*)
    }
    ujson.Obj(
      "types" -> typesJson,
      "domain" -> ujson.Obj(
        "name"              -> domain.name,
        "version"           -> domain.version,
        "chainId"           -> domain.chainId,
        "verifyingContract" -> domain.verifyingContract,
      ),
      "primaryType" -> primaryType,
      "message" -> ujson.Obj.from(userValue.map { case (k, v) => k -> jsonValue(v) }),
    )

  private def currentProvider(): EthereumProvider =
    if js.isUndefined(GlobalThis.window) || GlobalThis.window == null then
      throw UnsupportedOperationException("window.ethereum is not available")
    val eth = GlobalThis.window.asInstanceOf[js.Dynamic].ethereum
    if js.isUndefined(eth) || eth == null then
      throw UnsupportedOperationException("window.ethereum is not available")
    eth.asInstanceOf[EthereumProvider]

  private def asStringArray(value: js.Any): Seq[String] =
    value.asInstanceOf[js.Array[String]].toSeq

  private def chainIdHex(network: Network): String =
    "0x" + network.chainId.toHexString

  private def jsonValue(v: Any): ujson.Value = v match
    case s: String     => ujson.Str(s)
    case bi: BigInt    => ujson.Str(bi.toString)
    case i: Int        => ujson.Num(i.toDouble)
    case l: Long       => ujson.Num(l.toDouble)
    case d: Double     => ujson.Num(d)
    case b: Boolean    => ujson.Bool(b)
    case value: ujson.Value => value
    case other         => ujson.Str(other.toString)
