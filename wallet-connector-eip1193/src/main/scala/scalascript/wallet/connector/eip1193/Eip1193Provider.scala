package scalascript.wallet.connector.eip1193

import scala.concurrent.{ExecutionContext, Future}
import scalascript.blockchain.spi.{ChainId, ChainContext, TxIntent, TypedData}
import scalascript.wallet.spi.*

/** EIP-1193 / EIP-6963 protocol translator.
 *
 *  Translates the JSON-RPC method surface dApps expect on
 *  `window.ethereum.request({method, params})` into
 *  `AccountManager.request(DappRequest)` + chain RPC passthrough.
 *  Pure handler — no DOM coupling — so the same impl powers both a
 *  Scala.js browser injection (Phase 3.5, deferred until the JS
 *  toolchain wiring lands) and a test-time direct invocation.
 *
 *  Method coverage prioritised for the x402 / wallet-spi use case:
 *
 *    eth_requestAccounts / eth_accounts   — discovery
 *    eth_chainId / wallet_switchEthereumChain — active chain control
 *    personal_sign                        — ECDSA over a prefixed msg
 *    eth_signTypedData_v4                 — EIP-712 typed data
 *    eth_sendTransaction                  — full tx flow
 *    eth_call / eth_getBalance / eth_blockNumber / eth_getTransaction*
 *                                         — RPC passthrough via the
 *                                           active ChainContext
 *
 *  Anything else surfaces as `4200 unsupported method`.
 *
 *  See docs/wallet-spi.md §7.1 for the connector design. */
class Eip1193Provider(
  val manager: AccountManager,
  ctxFor:      ChainId => ChainContext,
  initialChain: ChainId,
)(using ec: ExecutionContext) extends DappConnector:

  import Eip1193Errors.*

  def protocol: String = "eip-1193"

  @volatile private var attached: Boolean = false
  @volatile private var activeChain: ChainId = initialChain

  def attach(unused: AccountManager): Unit =
    // The connector already has `manager`; the trait method exists for
    // the host to signal "we're live now". Idempotent.
    attached = true

  def detach(): Unit =
    attached = false

  /** Entry point: a dApp calls `request({method, params})`. */
  def request(method: String, params: ujson.Value = ujson.Arr()): Future[ujson.Value] =
    if !attached then return Future.failed(disconnected)
    method match
      case "eth_requestAccounts" | "eth_accounts" => handleAccounts()
      case "eth_chainId"                           => Future.successful(chainIdHex(activeChain))
      case "wallet_switchEthereumChain"            => handleSwitchChain(params)
      case "personal_sign"                         => handlePersonalSign(params)
      case "eth_signTypedData_v4"                  => handleSignTypedData(params)
      case "eth_signTypedData" | "eth_signTypedData_v3" =>
        // v3/v4 share the JSON shape we accept; we don't differentiate.
        handleSignTypedData(params)
      case "eth_sendTransaction"                   => handleSendTransaction(params)
      case "eth_call" | "eth_getBalance" | "eth_blockNumber"
         | "eth_getTransactionByHash" | "eth_getTransactionReceipt"
         | "eth_getCode" | "eth_estimateGas" | "eth_gasPrice"
         | "eth_maxPriorityFeePerGas" | "eth_getTransactionCount"
         | "eth_getBlockByNumber" =>
        passthrough(method, params)
      case other =>
        Future.failed(unsupportedMethod(other))

  // ── method handlers ───────────────────────────────────────────────────

  private def handleAccounts(): Future[ujson.Value] =
    manager.strategyFor(activeChain) match
      case None =>
        Future.failed(chainDisconnected)
      case Some(strategy) =>
        manager.adapterFor(activeChain) match
          case None =>
            Future.failed(chainDisconnected)
          case Some(adapter) =>
            strategy.getAddress(adapter).map(addr => ujson.Arr(ujson.Str(addr)))

  private def handleSwitchChain(params: ujson.Value): Future[ujson.Value] =
    parseSwitchChainParams(params) match
      case None =>
        Future.failed(invalidParams("wallet_switchEthereumChain expects [{chainId: hexString}]"))
      case Some(newId) =>
        if !manager.chains.contains(newId) then
          Future.failed(chainDisconnected)
        else
          activeChain = newId
          Future.successful(ujson.Null)

  private def handlePersonalSign(params: ujson.Value): Future[ujson.Value] =
    // EIP-1193 personal_sign: [data, address] OR [address, data] (MetaMask
    // historically accepted either order — we accept both).
    val arr = paramsArray(params)
    val (dataHex, address) = orderPersonalSignArgs(arr) match
      case Some(t) => t
      case None    => return Future.failed(invalidParams("personal_sign expects [data, address]"))

    activeStrategyAndAdapter match
      case None =>
        Future.failed(unauthorized(s"Account $address not registered"))
      case Some((strategy, adapter)) =>
        val bytes = decodeHex(dataHex)
        strategy.signMessage(adapter, bytes).map { sig =>
          ujson.Str(encodeHex(sig, withPrefix = true))
        }

  private def handleSignTypedData(params: ujson.Value): Future[ujson.Value] =
    // eth_signTypedData_v4 params: [address, typedDataJson | typedDataString]
    val arr = paramsArray(params)
    if arr.size < 2 then
      return Future.failed(invalidParams("eth_signTypedData_v4 expects [address, typedData]"))
    val address = arr(0).str
    val tdJson  = arr(1) match
      case ujson.Str(s) => ujson.read(s)
      case obj          => obj
    val typedData = parseEip712(tdJson) match
      case Right(td)   => td
      case Left(err)   => return Future.failed(invalidParams(err))
    activeStrategyAndAdapter match
      case None =>
        Future.failed(unauthorized(s"Account $address not registered"))
      case Some((strategy, adapter)) =>
        strategy.signTypedData(adapter, typedData).map { sig =>
          ujson.Str(encodeHex(sig, withPrefix = true))
        }

  private def handleSendTransaction(params: ujson.Value): Future[ujson.Value] =
    val arr = paramsArray(params)
    if arr.isEmpty then
      Future.failed(invalidParams("eth_sendTransaction expects [{from, to, value, data, ...}]"))
    else
      val tx = arr(0).obj
      tx.get("from").map(_.str) match
        case None =>
          Future.failed(invalidParams("eth_sendTransaction requires `from`"))
        case Some(from) =>
          val toOpt = tx.get("to").map(_.str)
          val value = tx.get("value").map(v => parseHexInt(v.str)).getOrElse(BigInt(0))
          val data  = tx.get("data").map(v => decodeHex(v.str)).getOrElse(Array.emptyByteArray)
          activeStrategyAndAdapter match
            case None =>
              Future.failed(unauthorized(s"Account $from not registered"))
            case Some((strategy, adapter)) =>
              val intent = toOpt match
                case Some(target) => TxIntent.ContractCall(target, data, value)
                case None         => TxIntent.Deploy(data)
              val ctx = ctxFor(activeChain)
              for
                builtTx <- adapter.buildTransaction(intent, from, ctx)
                signed  <- strategy.signTransaction(adapter)(builtTx.asInstanceOf[adapter.Tx])
                hash    <- adapter.broadcast(signed.asInstanceOf[adapter.SignedTx], ctx)
              yield ujson.Str(hash.value)

  private def passthrough(method: String, params: ujson.Value): Future[ujson.Value] =
    val ctx = ctxFor(activeChain)
    params match
      case ujson.Arr(items) => ctx.rpcCall(method, items.toSeq*)
      case other            => ctx.rpcCall(method, other)

  // ── helpers ────────────────────────────────────────────────────────────

  /** Look up the strategy + adapter for the active chain. Matching
   *  the dApp-supplied address against the strategy's bound address
   *  is the host's responsibility (the policy layer in mcp-wallet
   *  does this properly). The connector accepts any address and the
   *  strategy signs with whichever key it holds. */
  private def activeStrategyAndAdapter: Option[(AccountStrategy, scalascript.blockchain.spi.ChainAdapter)] =
    for
      strategy <- manager.strategyFor(activeChain)
      adapter  <- manager.adapterFor(activeChain)
    yield (strategy, adapter)

  private def parseSwitchChainParams(params: ujson.Value): Option[ChainId] =
    val arr = paramsArray(params)
    if arr.isEmpty then None
    else
      val obj    = arr(0).obj
      val hexId  = obj.get("chainId").map(_.str)
      hexId.map(h => ChainId(s"eip155:${parseHexInt(h)}"))

  private def paramsArray(params: ujson.Value): Seq[ujson.Value] = params match
    case ujson.Arr(items) => items.toSeq
    case ujson.Null       => Seq.empty
    case other            => Seq(other)

  private def orderPersonalSignArgs(arr: Seq[ujson.Value]): Option[(String, String)] =
    arr.toList match
      case (data: ujson.Str) :: (addr: ujson.Str) :: _ if addr.value.startsWith("0x") && isLikelyAddress(addr.value) =>
        Some((data.value, addr.value))
      case (addr: ujson.Str) :: (data: ujson.Str) :: _ if isLikelyAddress(addr.value) =>
        Some((data.value, addr.value))
      case _ => None

  private def isLikelyAddress(s: String): Boolean =
    s.stripPrefix("0x").length == 40

  private def chainIdHex(c: ChainId): ujson.Str =
    ujson.Str(s"0x${c.reference.toLong.toHexString}")

  private def parseHexInt(s: String): BigInt =
    BigInt(s.stripPrefix("0x").stripPrefix("0X"), 16)

  private def parseEip712(json: ujson.Value): Either[String, TypedData.Eip712] =
    try
      val obj    = json.obj
      val domain = obj("domain").obj.toMap
      val typesJson = obj("types").obj
      val types  = typesJson.toMap.map { case (k, v) =>
        val fields = v.arr.toSeq.map { field =>
          val f = field.obj
          // EIP-712 JSON: {name: "...", type: "..."}.
          // Our TypedData.Eip712 stores (type, name).
          (f("type").str, f("name").str)
        }
        k -> fields
      }
      val primaryType = obj("primaryType").str
      val value       = obj("message").obj.toMap   // EIP-712 uses "message" for the value
      Right(TypedData.Eip712(domain = domain, types = types, value = value, primaryType = primaryType))
    catch
      case ex: Throwable => Left(s"malformed EIP-712 JSON: ${ex.getMessage}")

  private def decodeHex(s: String): Array[Byte] =
    val clean = s.stripPrefix("0x").stripPrefix("0X")
    require(clean.length % 2 == 0, s"hex string of odd length: $clean")
    val out = new Array[Byte](clean.length / 2)
    var i = 0
    while i < out.length do
      out(i) = Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16).toByte
      i += 1
    out

  private def encodeHex(bytes: Array[Byte], withPrefix: Boolean): String =
    val sb = new java.lang.StringBuilder
    if withPrefix then sb.append("0x")
    var i = 0
    while i < bytes.length do
      sb.append(f"${bytes(i) & 0xff}%02x")
      i += 1
    sb.toString
